package com.pebbles_boon.metalrender.compat.iris;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes translated artifacts without ever writing original shader-pack GLSL.
 */
public final class IrisPipelineCache {
  private static final int MANIFEST_SCHEMA = 2;
  private static final long MAX_STAGE_ARTIFACT_BYTES =
      64L * 1024L * 1024L;
  static final long MAX_LIBRARY_VALIDATION_MSL_BYTES =
      16L * 1024L * 1024L;
  private static final long MAX_MANIFEST_BYTES = 64L * 1024L;
  private static final long MAX_COMPLETE_MARKER_BYTES = 4096L;
  public static final int DEFAULT_MAX_ENTRIES = 512;
  public static final long DEFAULT_MAX_BYTES = 1024L * 1024L * 1024L;

  private final IrisPipelineCacheLayout layout;
  private final int maxEntries;
  private final long maxBytes;

  public IrisPipelineCache(IrisPipelineCacheLayout layout) {
    this(layout, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
  }

  public IrisPipelineCache(IrisPipelineCacheLayout layout, int maxEntries,
      long maxBytes) {
    this.layout = Objects.requireNonNull(layout, "layout");
    if (maxEntries <= 0 || maxBytes <= 0) {
      throw new IllegalArgumentException("cache bounds must be positive");
    }
    this.maxEntries = maxEntries;
    this.maxBytes = maxBytes;
  }

  /**
   * Read-only cache hit lookup. It never loads original GLSL because none is
   * persisted.
   */
  public synchronized Optional<IrisPipelineCacheLayout.CachePaths> lookup(
      IrisFinalShaderProgram program, IrisTranslationProfile profile)
      throws IOException {
    IrisShaderCacheKey key = IrisShaderCacheKey.from(program, profile);
    IrisPipelineCacheLayout.CachePaths paths = layout.paths(key);
    return isComplete(program, profile, paths)
        ? Optional.of(paths)
        : Optional.empty();
  }

  /**
   * Reads one generated MSL stage only after revalidating its content-address,
   * completion marker, manifest path, byte count, digest, file type, size
   * bound, and UTF-8 syntax marker.
   *
   * <p>The caller needs only the derived cache key and profile; final GLSL is
   * therefore not retained by the deferred Metal validation queue.</p>
   */
  public synchronized Optional<VerifiedMslStage> readVerifiedMslStage(
      IrisShaderCacheKey key, IrisTranslationProfile profile,
      IrisShaderStage stage) throws IOException {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(stage, "stage");

    IrisPipelineCacheLayout.CachePaths paths = layout.paths(key);
    if (!validCompleteMarker(key, profile, paths)) {
      return Optional.empty();
    }
    Optional<Map<String, String>> manifestResult =
        readStrictManifest(paths.manifest());
    if (manifestResult.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> manifest = manifestResult.orElseThrow();
    if (!"metalrender-iris-pipeline-cache".equals(manifest.get("format"))
        || !Integer.toString(MANIFEST_SCHEMA).equals(manifest.get("schema"))
        || !key.sha256().equals(manifest.get("key.sha256"))
        || !profile.sha256().equals(
            manifest.get("translation.profile.sha256"))
        || !profile.canonicalValue().equals(
            manifest.get("translation.profile"))
        || !"complete".equals(manifest.get("translation.status"))
        || !"pending".equals(manifest.get("pipeline.status"))
        || !"false".equals(
            manifest.get("source.original_glsl_persisted"))) {
      return Optional.empty();
    }

    String prefix = "stage." + stage.cacheName();
    Path mslPath = paths.msl(stage);
    String expectedRelative = slash(paths.directory().relativize(mslPath));
    if (!"true".equals(manifest.get(prefix + ".present"))
        || !expectedRelative.equals(manifest.get(prefix + ".msl"))) {
      return Optional.empty();
    }
    long declaredBytes = parseBoundedSize(
        manifest.get(prefix + ".msl.bytes"));
    String declaredDigest = manifest.get(prefix + ".msl.sha256");
    if (declaredBytes <= 0
        || declaredBytes > MAX_LIBRARY_VALIDATION_MSL_BYTES
        || declaredDigest == null
        || !declaredDigest.matches("[0-9a-f]{64}")) {
      return Optional.empty();
    }

    Optional<byte[]> content = readBoundedRegularFile(
        mslPath, MAX_LIBRARY_VALIDATION_MSL_BYTES);
    if (content.isEmpty()) {
      return Optional.empty();
    }
    byte[] mslUtf8 = content.orElseThrow();
    String actualDigest = sha256(mslUtf8);
    if (mslUtf8.length != declaredBytes
        || !actualDigest.equals(declaredDigest)
        || !validMsl(mslUtf8)) {
      return Optional.empty();
    }
    return Optional.of(new VerifiedMslStage(
        key.sha256(), stage, mslUtf8, actualDigest));
  }

  /**
   * Reads one SPIR-V stage only after the same content-address and manifest
   * verification used by the translation cache. Stage 3/4 reflection never
   * consumes an unchecked cache file.
   */
  public synchronized Optional<VerifiedSpirvStage> readVerifiedSpirvStage(
      IrisShaderCacheKey key, IrisTranslationProfile profile,
      IrisShaderStage stage) throws IOException {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(stage, "stage");
    IrisPipelineCacheLayout.CachePaths paths = layout.paths(key);
    if (!validCompleteMarker(key, profile, paths)) {
      return Optional.empty();
    }
    Optional<Map<String, String>> manifestResult =
        readStrictManifest(paths.manifest());
    if (manifestResult.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> manifest = manifestResult.orElseThrow();
    if (!"metalrender-iris-pipeline-cache".equals(manifest.get("format"))
        || !Integer.toString(MANIFEST_SCHEMA).equals(manifest.get("schema"))
        || !key.sha256().equals(manifest.get("key.sha256"))
        || !profile.sha256().equals(
            manifest.get("translation.profile.sha256"))
        || !profile.canonicalValue().equals(
            manifest.get("translation.profile"))
        || !"complete".equals(manifest.get("translation.status"))
        || !"pending".equals(manifest.get("pipeline.status"))
        || !"false".equals(
            manifest.get("source.original_glsl_persisted"))) {
      return Optional.empty();
    }
    String prefix = "stage." + stage.cacheName();
    Path spirvPath = paths.spirv(stage);
    String expectedRelative = slash(paths.directory().relativize(spirvPath));
    if (!"true".equals(manifest.get(prefix + ".present"))
        || !expectedRelative.equals(manifest.get(prefix + ".spirv"))) {
      return Optional.empty();
    }
    long declaredBytes = parseBoundedSize(
        manifest.get(prefix + ".spirv.bytes"));
    String declaredDigest = manifest.get(prefix + ".spirv.sha256");
    if (declaredBytes < 5L * Integer.BYTES
        || declaredBytes > MAX_STAGE_ARTIFACT_BYTES
        || declaredBytes % Integer.BYTES != 0
        || declaredDigest == null
        || !declaredDigest.matches("[0-9a-f]{64}")) {
      return Optional.empty();
    }
    Optional<byte[]> content = readBoundedRegularFile(
        spirvPath, MAX_STAGE_ARTIFACT_BYTES);
    if (content.isEmpty()) {
      return Optional.empty();
    }
    byte[] spirv = content.orElseThrow();
    String actualDigest = sha256(spirv);
    if (spirv.length != declaredBytes
        || !actualDigest.equals(declaredDigest)
        || !validSpirvBytes(spirv)) {
      return Optional.empty();
    }
    return Optional.of(new VerifiedSpirvStage(
        key.sha256(), stage, spirv, actualDigest));
  }

  /**
   * Immutable content-addressed store. A valid hit is never rewritten.
   * {@code translation.complete} is written last.
   */
  public synchronized StoreResult store(
      IrisFinalShaderProgram program, IrisShaderTranslation translation)
      throws IOException {
    Objects.requireNonNull(program, "program");
    Objects.requireNonNull(translation, "translation");

    IrisShaderCacheKey expected =
        IrisShaderCacheKey.from(program, translation.profile());
    if (!expected.equals(translation.key())) {
      throw new IllegalArgumentException(
          "translation key does not match captured final GLSL");
    }
    for (IrisShaderStage stage : IrisShaderStage.values()) {
      boolean sourcePresent = program.hasStage(stage);
      boolean artifactPresent = translation.stage(stage) != null;
      if (sourcePresent != artifactPresent) {
        throw new IllegalArgumentException(
            "translation stages do not match capture at " + stage);
      }
    }

    IrisPipelineCacheLayout.CachePaths paths = layout.paths(expected);
    if (isComplete(program, translation.profile(), paths)) {
      return new StoreResult(paths, true);
    }
    Files.deleteIfExists(paths.translationComplete());

    long outputBytes = estimatedOutputBytes(translation);
    if (outputBytes > maxBytes) {
      throw new IOException("translated program exceeds cache byte limit");
    }
    Files.createDirectories(paths.spirvDirectory());
    Files.createDirectories(paths.mslDirectory());

    for (IrisShaderStage stage : IrisShaderStage.values()) {
      IrisShaderTranslation.StageArtifacts artifacts =
          translation.stage(stage);
      if (artifacts == null) {
        continue;
      }
      writeAtomically(paths.spirv(stage), artifacts.spirv());
      writeAtomically(paths.msl(stage),
          artifacts.msl().getBytes(StandardCharsets.UTF_8));
    }

    writeAtomically(paths.manifest(),
        renderManifest(program, translation, paths)
            .getBytes(StandardCharsets.UTF_8));
    writeAtomically(paths.translationComplete(),
        renderCompleteMarker(translation)
            .getBytes(StandardCharsets.US_ASCII));
    enforceBounds(paths.directory());
    return new StoreResult(paths, false);
  }

  static String renderManifest(IrisFinalShaderProgram program,
      IrisShaderTranslation translation,
      IrisPipelineCacheLayout.CachePaths paths) {
    StringBuilder manifest = new StringBuilder(2048);
    manifest.append("format=metalrender-iris-pipeline-cache\n");
    manifest.append("schema=").append(MANIFEST_SCHEMA).append('\n');
    manifest.append("key.sha256=").append(translation.key().sha256())
        .append('\n');
    manifest.append("backend=").append(translation.backendId()).append('\n');
    manifest.append("translation.profile.sha256=")
        .append(translation.profile().sha256()).append('\n');
    manifest.append("translation.profile=")
        .append(translation.profile().canonicalValue()).append('\n');
    manifest.append("translation.status=complete\n");
    manifest.append("pipeline.status=pending\n");
    manifest.append("source.original_glsl_persisted=false\n");
    manifest.append("program.name.sha256=")
        .append(IrisShaderCacheKey.sha256(program.programName())).append('\n');
    manifest.append("program.name.utf16_chars=")
        .append(program.programName().length()).append('\n');

    for (IrisShaderStage stage : IrisShaderStage.values()) {
      String prefix = "stage." + stage.cacheName();
      String source = program.source(stage);
      manifest.append(prefix).append(".present=")
          .append(source != null).append('\n');
      if (source == null) {
        continue;
      }
      manifest.append(prefix).append(".source.sha256=")
          .append(IrisShaderCacheKey.sha256(source)).append('\n');
      manifest.append(prefix).append(".source.utf16_chars=")
          .append(source.length()).append('\n');
      IrisShaderTranslation.StageArtifacts artifacts =
          translation.stage(stage);
      byte[] spirv = artifacts.spirv();
      byte[] msl = artifacts.msl().getBytes(StandardCharsets.UTF_8);
      manifest.append(prefix).append(".spirv=")
          .append(paths.directory().relativize(paths.spirv(stage)))
          .append('\n');
      manifest.append(prefix).append(".spirv.bytes=")
          .append(spirv.length).append('\n');
      manifest.append(prefix).append(".spirv.sha256=")
          .append(sha256(spirv)).append('\n');
      manifest.append(prefix).append(".msl=")
          .append(paths.directory().relativize(paths.msl(stage)))
          .append('\n');
      manifest.append(prefix).append(".msl.bytes=")
          .append(msl.length).append('\n');
      manifest.append(prefix).append(".msl.sha256=")
          .append(sha256(msl)).append('\n');
    }

    manifest.append("pipeline.binary_archive.expected=")
        .append(paths.directory().relativize(paths.metalBinaryArchive()))
        .append('\n');
    return manifest.toString().replace('\\', '/');
  }

  private static String renderCompleteMarker(
      IrisShaderTranslation translation) {
    return "translation.status=complete\n"
        + "pipeline.status=pending\n"
        + "key.sha256=" + translation.key().sha256() + "\n"
        + "profile.sha256=" + translation.profile().sha256() + "\n";
  }

  private static long estimatedOutputBytes(
      IrisShaderTranslation translation) {
    long bytes = 16L * 1024L;
    for (IrisShaderTranslation.StageArtifacts stage
        : translation.stages().values()) {
      bytes = Math.addExact(bytes, stage.spirv().length);
      bytes = Math.addExact(bytes,
          stage.msl().getBytes(StandardCharsets.UTF_8).length);
    }
    return bytes;
  }

  private static boolean isComplete(IrisFinalShaderProgram program,
      IrisTranslationProfile profile,
      IrisPipelineCacheLayout.CachePaths paths) throws IOException {
    if (!validCompleteMarker(program, profile, paths)) {
      return false;
    }
    for (IrisShaderStage stage : IrisShaderStage.values()) {
      if (program.hasStage(stage)
          && (!validSpirv(paths.spirv(stage))
          || !validMsl(paths.msl(stage)))) {
        return false;
      }
    }
    return validManifest(program, profile, paths);
  }

  private static boolean validCompleteMarker(IrisFinalShaderProgram program,
      IrisTranslationProfile profile,
      IrisPipelineCacheLayout.CachePaths paths) throws IOException {
    return validCompleteMarker(
        IrisShaderCacheKey.from(program, profile), profile, paths);
  }

  private static boolean validCompleteMarker(IrisShaderCacheKey key,
      IrisTranslationProfile profile,
      IrisPipelineCacheLayout.CachePaths paths) throws IOException {
    Optional<byte[]> marker = readBoundedRegularFile(
        paths.translationComplete(), MAX_COMPLETE_MARKER_BYTES);
    if (marker.isEmpty()) {
      return false;
    }
    String expectedMarker = "translation.status=complete\n"
        + "pipeline.status=pending\n"
        + "key.sha256=" + key.sha256() + "\n"
        + "profile.sha256=" + profile.sha256() + "\n";
    return expectedMarker.equals(new String(
        marker.orElseThrow(), StandardCharsets.US_ASCII));
  }

  private static boolean validManifest(IrisFinalShaderProgram program,
      IrisTranslationProfile profile,
      IrisPipelineCacheLayout.CachePaths paths) throws IOException {
    if (!Files.isRegularFile(paths.manifest(), LinkOption.NOFOLLOW_LINKS)
        || Files.size(paths.manifest()) <= 0
        || Files.size(paths.manifest()) > MAX_MANIFEST_BYTES) {
      return false;
    }
    String manifest = Files.readString(paths.manifest(),
        StandardCharsets.UTF_8);
    IrisShaderCacheKey key = IrisShaderCacheKey.from(program, profile);
    if (!(hasManifestLine(manifest,
        "format=metalrender-iris-pipeline-cache")
        && hasManifestLine(manifest, "schema=" + MANIFEST_SCHEMA)
        && hasManifestLine(manifest, "key.sha256=" + key.sha256())
        && hasManifestLine(manifest,
            "translation.profile.sha256=" + profile.sha256())
        && hasManifestLine(manifest, "translation.status=complete")
        && hasManifestLine(manifest, "pipeline.status=pending")
        && hasManifestLine(manifest,
            "source.original_glsl_persisted=false"))) {
      return false;
    }
    for (IrisShaderStage stage : IrisShaderStage.values()) {
      String prefix = "stage." + stage.cacheName();
      String source = program.source(stage);
      if (!hasManifestLine(manifest,
          prefix + ".present=" + (source != null))) {
        return false;
      }
      if (source == null) {
        continue;
      }
      Path spirv = paths.spirv(stage);
      Path msl = paths.msl(stage);
      if (!hasManifestLine(manifest,
              prefix + ".source.sha256="
                  + IrisShaderCacheKey.sha256(source))
          || !hasManifestLine(manifest,
              prefix + ".source.utf16_chars=" + source.length())
          || !hasManifestLine(manifest,
              prefix + ".spirv="
                  + slash(paths.directory().relativize(spirv)))
          || !hasManifestLine(manifest,
              prefix + ".spirv.bytes=" + Files.size(spirv))
          || !hasManifestLine(manifest,
              prefix + ".spirv.sha256=" + sha256(spirv))
          || !hasManifestLine(manifest,
              prefix + ".msl="
                  + slash(paths.directory().relativize(msl)))
          || !hasManifestLine(manifest,
              prefix + ".msl.bytes=" + Files.size(msl))
          || !hasManifestLine(manifest,
              prefix + ".msl.sha256=" + sha256(msl))) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasManifestLine(String manifest, String line) {
    return ("\n" + manifest + "\n").contains("\n" + line + "\n");
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String sha256(byte[] content) {
    return HexFormat.of().formatHex(newSha256().digest(content));
  }

  private static String sha256(Path path) throws IOException {
    MessageDigest digest = newSha256();
    byte[] buffer = new byte[8192];
    try (InputStream input = Files.newInputStream(path)) {
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read > 0) {
          digest.update(buffer, 0, read);
        }
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "JVM does not provide SHA-256", impossible);
    }
  }

  private static boolean validSpirv(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    long size = Files.size(path);
    if (size < 5L * Integer.BYTES
        || size > MAX_STAGE_ARTIFACT_BYTES
        || size % Integer.BYTES != 0) {
      return false;
    }
    byte[] magic = new byte[Integer.BYTES];
    try (InputStream input = Files.newInputStream(path)) {
      if (input.readNBytes(magic, 0, magic.length) != magic.length) {
        return false;
      }
    }
    return (magic[0] & 0xff) == 0x03
        && (magic[1] & 0xff) == 0x02
        && (magic[2] & 0xff) == 0x23
        && (magic[3] & 0xff) == 0x07;
  }

  private static boolean validSpirvBytes(byte[] content) {
    return content.length >= 5 * Integer.BYTES
        && content.length <= MAX_STAGE_ARTIFACT_BYTES
        && content.length % Integer.BYTES == 0
        && (content[0] & 0xff) == 0x03
        && (content[1] & 0xff) == 0x02
        && (content[2] & 0xff) == 0x23
        && (content[3] & 0xff) == 0x07;
  }

  private static boolean validMsl(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    long size = Files.size(path);
    if (size <= 0 || size > MAX_STAGE_ARTIFACT_BYTES) {
      return false;
    }
    int prefixLength = (int) Math.min(size, 8192L);
    byte[] prefix = new byte[prefixLength];
    try (InputStream input = Files.newInputStream(path)) {
      if (input.readNBytes(prefix, 0, prefix.length) != prefix.length) {
        return false;
      }
    }
    String mslPrefix = new String(prefix, StandardCharsets.UTF_8);
    return !mslPrefix.isBlank()
        && mslPrefix.contains("metal_stdlib")
        && !mslPrefix.contains("#version");
  }

  private static boolean validMsl(byte[] content) {
    String msl;
    try {
      msl = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content)).toString();
    } catch (CharacterCodingException malformed) {
      return false;
    }
    int prefixLength = Math.min(msl.length(), 8192);
    String prefix = msl.substring(0, prefixLength);
    return !prefix.isBlank()
        && prefix.contains("metal_stdlib")
        && !prefix.contains("#version")
        && msl.indexOf('\0') < 0;
  }

  private static Optional<Map<String, String>> readStrictManifest(Path path)
      throws IOException {
    Optional<byte[]> content = readBoundedRegularFile(
        path, MAX_MANIFEST_BYTES);
    if (content.isEmpty()) {
      return Optional.empty();
    }
    String manifest;
    try {
      manifest = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content.orElseThrow())).toString();
    } catch (CharacterCodingException malformed) {
      return Optional.empty();
    }
    if (manifest.indexOf('\r') >= 0) {
      return Optional.empty();
    }
    Map<String, String> properties = new HashMap<>();
    for (String line : manifest.split("\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      int separator = line.indexOf('=');
      if (separator <= 0) {
        return Optional.empty();
      }
      String name = line.substring(0, separator);
      String value = line.substring(separator + 1);
      if (!name.matches("[a-z0-9_.-]+")
          || properties.putIfAbsent(name, value) != null) {
        return Optional.empty();
      }
    }
    return Optional.of(Map.copyOf(properties));
  }

  private static long parseBoundedSize(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,8}")) {
      return -1;
    }
    try {
      long size = Long.parseLong(value);
      return size <= MAX_STAGE_ARTIFACT_BYTES ? size : -1;
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }

  private static Optional<byte[]> readBoundedRegularFile(Path path,
      long maximumBytes) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    long size = Files.size(path);
    if (size <= 0 || size > maximumBytes || size >= Integer.MAX_VALUE) {
      return Optional.empty();
    }
    byte[] content;
    try (InputStream input = Files.newInputStream(
        path, LinkOption.NOFOLLOW_LINKS)) {
      content = input.readNBytes((int) size + 1);
    }
    if (content.length != size
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.size(path) != size) {
      return Optional.empty();
    }
    return Optional.of(content);
  }

  private void enforceBounds(Path protectedEntry) throws IOException {
    Path namespace = layout.root().resolve(
        IrisPipelineCacheLayout.CACHE_NAMESPACE);
    if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }

    List<EntryStats> entries = new ArrayList<>();
    try (var paths = Files.find(namespace, 3,
        (path, attributes) -> attributes.isDirectory()
            && path.getNameCount() == namespace.getNameCount() + 3
            && path.getFileName().toString().matches("[0-9a-f]{64}"))) {
      for (Path entry : paths.toList()) {
        entries.add(new EntryStats(entry, directoryBytes(entry),
            Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)));
      }
    }

    entries.sort(Comparator.comparing(EntryStats::modified)
        .thenComparing(entry -> entry.directory().getFileName().toString()));
    long bytes = 0;
    for (EntryStats entry : entries) {
      bytes = Math.addExact(bytes, entry.bytes());
    }
    int count = entries.size();
    for (EntryStats entry : entries) {
      if (count <= maxEntries && bytes <= maxBytes) {
        break;
      }
      if (entry.directory().equals(protectedEntry)) {
        continue;
      }
      deleteEntry(entry.directory(), namespace);
      count--;
      bytes -= entry.bytes();
    }
  }

  private static long directoryBytes(Path directory) throws IOException {
    long bytes = 0;
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.toList()) {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          bytes = Math.addExact(bytes, Files.size(path));
        }
      }
    }
    return bytes;
  }

  private static void deleteEntry(Path directory, Path namespace)
      throws IOException {
    Path normalized = directory.toAbsolutePath().normalize();
    Path normalizedNamespace = namespace.toAbsolutePath().normalize();
    if (!normalized.startsWith(normalizedNamespace)
        || !normalized.getFileName().toString().matches("[0-9a-f]{64}")) {
      throw new IOException("refusing to prune non-cache path");
    }
    List<Path> paths;
    try (var walk = Files.walk(normalized)) {
      paths = walk.sorted(Comparator.reverseOrder()).toList();
    }
    for (Path path : paths) {
      Files.deleteIfExists(path);
    }
  }

  private static void writeAtomically(Path target, byte[] content)
      throws IOException {
    Files.createDirectories(target.getParent());
    Path temporary = Files.createTempFile(target.getParent(),
        "." + target.getFileName(), ".tmp");
    try {
      Files.write(temporary, content);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public record StoreResult(IrisPipelineCacheLayout.CachePaths paths,
                            boolean cacheHit) {
    public StoreResult {
      Objects.requireNonNull(paths, "paths");
    }
  }

  /** Immutable validated bytes passed across the native compile boundary. */
  public static final class VerifiedMslStage {
    private final String programKeySha256;
    private final IrisShaderStage stage;
    private final byte[] mslUtf8;
    private final String mslSha256;

    private VerifiedMslStage(String programKeySha256, IrisShaderStage stage,
        byte[] mslUtf8, String mslSha256) {
      this.programKeySha256 = Objects.requireNonNull(
          programKeySha256, "programKeySha256");
      this.stage = Objects.requireNonNull(stage, "stage");
      this.mslUtf8 = Objects.requireNonNull(mslUtf8, "mslUtf8").clone();
      this.mslSha256 = Objects.requireNonNull(mslSha256, "mslSha256");
    }

    public String programKeySha256() {
      return programKeySha256;
    }

    public IrisShaderStage stage() {
      return stage;
    }

    public byte[] mslUtf8() {
      return mslUtf8.clone();
    }

    public String mslSha256() {
      return mslSha256;
    }
  }

  /** Immutable validated SPIR-V bytes passed to reflection scanners. */
  public static final class VerifiedSpirvStage {
    private final String programKeySha256;
    private final IrisShaderStage stage;
    private final byte[] spirv;
    private final String spirvSha256;

    private VerifiedSpirvStage(String programKeySha256,
        IrisShaderStage stage, byte[] spirv, String spirvSha256) {
      this.programKeySha256 = Objects.requireNonNull(
          programKeySha256, "programKeySha256");
      this.stage = Objects.requireNonNull(stage, "stage");
      this.spirv = Objects.requireNonNull(spirv, "spirv").clone();
      this.spirvSha256 = Objects.requireNonNull(
          spirvSha256, "spirvSha256");
    }

    public String programKeySha256() {
      return programKeySha256;
    }

    public IrisShaderStage stage() {
      return stage;
    }

    public byte[] spirv() {
      return spirv.clone();
    }

    public String spirvSha256() {
      return spirvSha256;
    }
  }

  private record EntryStats(Path directory, long bytes, FileTime modified) {
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded content-addressed store for Stage 3 pipeline-state captures.
 * Original GLSL is never accepted or persisted by this API.
 */
public final class IrisPipelineStateCache {
  public static final String DIRECTORY = "pipeline-states-v1";
  private static final String FORMAT = "metalrender-iris-pipeline-state";
  private static final int SCHEMA = 1;
  private static final long MAX_STATE_BYTES = 1024L * 1024L;
  private static final long MAX_METADATA_BYTES = 64L * 1024L;

  private final Path root;

  public IrisPipelineStateCache(Path cacheRoot) {
    root = Objects.requireNonNull(cacheRoot, "cacheRoot")
        .toAbsolutePath().normalize()
        .resolve(IrisPipelineCacheLayout.CACHE_NAMESPACE)
        .resolve(DIRECTORY);
  }

  public synchronized StoreResult store(IrisShaderCacheKey shaderKey,
      IrisPipelineState state) throws IOException {
    Objects.requireNonNull(shaderKey, "shaderKey");
    Objects.requireNonNull(state, "state");
    IrisPipelineStateKey key = IrisPipelineStateKey.from(shaderKey, state);
    byte[] canonical = IrisPipelineStateKey.canonicalBytes(shaderKey, state);
    if (canonical.length <= 0 || canonical.length > MAX_STATE_BYTES) {
      throw new IOException("pipeline state exceeds cache bound");
    }
    String canonicalSha = sha256(canonical);
    StatePaths paths = paths(key);
    Optional<VerifiedState> existing = read(key);
    if (existing.isPresent()
        && existing.orElseThrow().shaderKey().equals(shaderKey)
        && existing.orElseThrow().canonicalSha256().equals(canonicalSha)) {
      return new StoreResult(key, true, existing.orElseThrow());
    }

    Files.createDirectories(paths.directory());
    writeAtomically(paths.canonical(), canonical);
    String metadata = "format=" + FORMAT + "\n"
        + "schema=" + SCHEMA + "\n"
        + "pipeline.key.sha256=" + key.sha256() + "\n"
        + "shader.key.sha256=" + shaderKey.sha256() + "\n"
        + "pass.kind=" + state.pass().kind().cacheName() + "\n"
        + "pass.id.sha256=" + state.pass().passIdSha256() + "\n"
        + "pass.fallback=" + state.pass().fallback() + "\n"
        + "state.canonical=state.bin\n"
        + "state.canonical.bytes=" + canonical.length + "\n"
        + "state.canonical.sha256=" + canonicalSha + "\n"
        + "vertex.buffers=" + state.vertexBuffers().size() + "\n"
        + "vertex.attributes=" + state.vertexAttributes().size() + "\n"
        + "color.attachments=" + state.colorAttachments().size() + "\n"
        + "specialization.scanned=" + state.specializationScanned() + "\n"
        + "function.constants=" + state.functionConstants().size() + "\n"
        + "pipeline.status=pending\n"
        + "source.original_glsl_persisted=false\n";
    writeAtomically(paths.metadata(),
        metadata.getBytes(StandardCharsets.US_ASCII));
    VerifiedState verified = read(key).orElseThrow(
        () -> new IOException("stored pipeline state failed verification"));
    return new StoreResult(key, false, verified);
  }

  public synchronized Optional<VerifiedState> read(IrisPipelineStateKey key)
      throws IOException {
    Objects.requireNonNull(key, "key");
    StatePaths paths = paths(key);
    Optional<byte[]> metadataBytes = readRegular(paths.metadata(),
        MAX_METADATA_BYTES);
    Optional<byte[]> canonicalBytes = readRegular(paths.canonical(),
        MAX_STATE_BYTES);
    if (metadataBytes.isEmpty() || canonicalBytes.isEmpty()) {
      return Optional.empty();
    }
    String metadata = new String(metadataBytes.orElseThrow(),
        StandardCharsets.US_ASCII);
    byte[] canonical = canonicalBytes.orElseThrow();
    String canonicalSha = sha256(canonical);
    String shaderSha = lineValue(metadata, "shader.key.sha256=");
    if (!hasLine(metadata, "format=" + FORMAT)
        || !hasLine(metadata, "schema=" + SCHEMA)
        || !hasLine(metadata, "pipeline.key.sha256=" + key.sha256())
        || shaderSha == null || !shaderSha.matches("[0-9a-f]{64}")
        || !hasLine(metadata, "state.canonical=state.bin")
        || !hasLine(metadata,
            "state.canonical.bytes=" + canonical.length)
        || !hasLine(metadata,
            "state.canonical.sha256=" + canonicalSha)
        || !hasLine(metadata, "specialization.scanned=true")
        || !hasLine(metadata, "pipeline.status=pending")
        || !hasLine(metadata, "source.original_glsl_persisted=false")) {
      return Optional.empty();
    }
    IrisShaderCacheKey shaderKey = new IrisShaderCacheKey(shaderSha);
    if (!shaderSha.equals(canonicalShaderKey(canonical))) {
      return Optional.empty();
    }
    IrisPipelineStateKey reconstructed = new IrisPipelineStateKey(
        sha256WithDomain(canonical));
    if (!reconstructed.equals(key)) {
      return Optional.empty();
    }
    return Optional.of(new VerifiedState(key, shaderKey, canonical.length,
        canonicalSha, paths));
  }

  public StatePaths paths(IrisPipelineStateKey key) {
    String digest = Objects.requireNonNull(key, "key").sha256();
    Path directory = root.resolve(digest.substring(0, 2))
        .resolve(digest.substring(2, 4)).resolve(digest);
    return new StatePaths(directory, directory.resolve("state.properties"),
        directory.resolve("state.bin"));
  }

  private static String sha256WithDomain(byte[] canonical) {
    MessageDigest digest = newSha256();
    byte[] domain = "metalrender.iris.pipeline-state.v1"
        .getBytes(StandardCharsets.US_ASCII);
    digest.update(java.nio.ByteBuffer.allocate(Long.BYTES)
        .putLong(domain.length).array());
    digest.update(domain);
    digest.update(java.nio.ByteBuffer.allocate(Long.BYTES)
        .putLong(canonical.length).array());
    digest.update(canonical);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String canonicalShaderKey(byte[] canonical) {
    if (canonical.length < Long.BYTES + 64) {
      return "";
    }
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(canonical);
    long length = buffer.getLong();
    if (length != 64 || length > buffer.remaining()) {
      return "";
    }
    byte[] encoded = new byte[(int) length];
    buffer.get(encoded);
    String value = new String(encoded, StandardCharsets.UTF_8);
    return value.matches("[0-9a-f]{64}") ? value : "";
  }

  private static Optional<byte[]> readRegular(Path path, long maximum)
      throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    long size = Files.size(path);
    if (size <= 0 || size > maximum) {
      return Optional.empty();
    }
    return Optional.of(Files.readAllBytes(path));
  }

  private static void writeAtomically(Path target, byte[] content)
      throws IOException {
    Path parent = target.getParent();
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent,
        target.getFileName().toString(), ".tmp");
    try {
      Files.write(temporary, content);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static boolean hasLine(String content, String expected) {
    return ("\n" + content + "\n").contains("\n" + expected + "\n");
  }

  private static String lineValue(String content, String prefix) {
    for (String line : content.split("\\n", -1)) {
      if (line.startsWith(prefix)) {
        return line.substring(prefix.length());
      }
    }
    return null;
  }

  private static String sha256(byte[] content) {
    return HexFormat.of().formatHex(newSha256().digest(content));
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256",
          impossible);
    }
  }

  public record StatePaths(Path directory, Path metadata, Path canonical) {
    public StatePaths {
      Objects.requireNonNull(directory, "directory");
      Objects.requireNonNull(metadata, "metadata");
      Objects.requireNonNull(canonical, "canonical");
    }
  }

  public record VerifiedState(IrisPipelineStateKey key,
                              IrisShaderCacheKey shaderKey,
                              long canonicalBytes,
                              String canonicalSha256,
                              StatePaths paths) {
    public VerifiedState {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(shaderKey, "shaderKey");
      Objects.requireNonNull(canonicalSha256, "canonicalSha256");
      Objects.requireNonNull(paths, "paths");
    }

    public String identityLine() {
      return key.sha256() + "|" + shaderKey.sha256() + "|"
          + canonicalSha256 + "\n";
    }
  }

  public record StoreResult(IrisPipelineStateKey key, boolean cacheHit,
                            VerifiedState verified) {
    public StoreResult {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(verified, "verified");
    }
  }
}

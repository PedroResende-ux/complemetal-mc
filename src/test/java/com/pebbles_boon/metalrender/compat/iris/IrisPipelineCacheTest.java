package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IrisPipelineCacheTest {
  private static final String SECRET_PROGRAM_NAME =
      "shader-pack-private-program-name";
  private static final String SECRET_VERTEX =
      "#version 450\n// shader-pack-private-vertex\nvoid main(){}";
  private static final String SECRET_FRAGMENT =
      "#version 450\n// shader-pack-private-fragment\nvoid main(){}";
  private static final byte[] TEST_SPIRV = ByteBuffer.allocate(20)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putInt(0x07230203)
      .putInt(0x00010000)
      .putInt(0)
      .putInt(1)
      .putInt(0)
      .array();
  private static final String TEST_VERTEX_MSL =
      "#include <metal_stdlib>\nusing namespace metal;\n"
          + "vertex float4 main0() { return float4(0.0); }\n";
  private static final String TEST_FRAGMENT_MSL =
      "#include <metal_stdlib>\nusing namespace metal;\n"
          + "fragment float4 main0() { return float4(1.0); }\n";

  @TempDir
  Path temporaryDirectory;

  @Test
  void layoutIsDeterministicAndContentAddressed() {
    IrisFinalShaderProgram program = program();
    IrisShaderCacheKey key = IrisShaderCacheKey.from(program);
    IrisPipelineCacheLayout layout =
        new IrisPipelineCacheLayout(temporaryDirectory.resolve("cache"));

    IrisPipelineCacheLayout.CachePaths first = layout.paths(key);
    IrisPipelineCacheLayout.CachePaths second = layout.paths(key);

    assertEquals(first, second);
    assertTrue(first.directory().toString().endsWith(
        "iris-metal-v1/" + key.sha256().substring(0, 2) + "/"
            + key.sha256().substring(2, 4) + "/" + key.sha256()));
    assertEquals("spirv/vertex.spv",
        slash(first.directory().relativize(first.spirv(
            IrisShaderStage.VERTEX))));
    assertEquals("msl/fragment.metal",
        slash(first.directory().relativize(first.msl(
            IrisShaderStage.FRAGMENT))));
    assertEquals("pipeline/metal.binarchive",
        slash(first.directory().relativize(first.metalBinaryArchive())));
  }

  @Test
  void storePersistsOnlyDerivedArtifactsAndRedactedManifest()
      throws Exception {
    IrisFinalShaderProgram program = program();
    IrisShaderTranslation translation = translation(program);
    IrisPipelineCache cache = new IrisPipelineCache(
        new IrisPipelineCacheLayout(temporaryDirectory.resolve("cache")));

    IrisPipelineCache.StoreResult result = cache.store(program, translation);
    IrisPipelineCacheLayout.CachePaths paths = result.paths();

    assertFalse(result.cacheHit());
    assertArrayEquals(TEST_SPIRV,
        Files.readAllBytes(paths.spirv(IrisShaderStage.VERTEX)));
    assertEquals(TEST_VERTEX_MSL,
        Files.readString(paths.msl(IrisShaderStage.VERTEX)));
    String manifest = Files.readString(paths.manifest());
    assertTrue(manifest.contains("source.original_glsl_persisted=false"));
    assertTrue(manifest.contains("stage.vertex.source.sha256="));
    assertTrue(manifest.contains("stage.vertex.spirv.sha256="));
    assertTrue(manifest.contains("stage.vertex.msl.sha256="));
    assertFalse(manifest.contains(SECRET_PROGRAM_NAME));
    assertFalse(manifest.contains(SECRET_VERTEX));
    assertFalse(manifest.contains(SECRET_FRAGMENT));
    String complete = Files.readString(paths.translationComplete(),
        StandardCharsets.US_ASCII);
    assertTrue(complete.contains("translation.status=complete"));
    assertTrue(complete.contains("pipeline.status=pending"));
    assertTrue(complete.contains(translation.key().sha256()));
    assertTrue(manifest.contains("pipeline.status=pending"));
    assertFalse(Files.exists(paths.metalBinaryArchive()));

    try (var files = Files.walk(paths.directory())) {
      List<Path> persisted = files.filter(Files::isRegularFile).toList();
      assertFalse(persisted.stream().anyMatch(path ->
          path.getFileName().toString().endsWith(".glsl")));
    }
  }

  @Test
  void mismatchedContentKeyIsRejectedBeforeWriting() {
    IrisFinalShaderProgram program = program();
    IrisFinalShaderProgram other = IrisFinalShaderProgram.fromGraphicsLink(
        "other", "v", null, null, null, "f");
    IrisShaderTranslation mismatched = new IrisShaderTranslation(
        IrisShaderCacheKey.from(other), "test-backend",
        IrisTranslationProfile.LWJGL_3_4_1_METAL_3_ARGUMENT_BUFFERS,
        artifacts());
    IrisPipelineCache cache = new IrisPipelineCache(
        new IrisPipelineCacheLayout(temporaryDirectory.resolve("cache")));

    assertThrows(IllegalArgumentException.class,
        () -> cache.store(program, mismatched));
  }

  @Test
  void validContentAddressIsAnImmutableHitAndPartialEntryIsRepaired()
      throws Exception {
    IrisFinalShaderProgram program = program();
    IrisShaderTranslation translation = translation(program);
    IrisPipelineCache cache = new IrisPipelineCache(
        new IrisPipelineCacheLayout(temporaryDirectory.resolve("cache")));

    IrisPipelineCache.StoreResult first = cache.store(program, translation);
    String manifest = Files.readString(first.paths().manifest());
    IrisPipelineCache.StoreResult hit = cache.store(program, translation);

    assertTrue(hit.cacheHit());
    assertEquals(manifest, Files.readString(hit.paths().manifest()));
    assertTrue(cache.lookup(program, translation.profile()).isPresent());

    Files.delete(hit.paths().msl(IrisShaderStage.FRAGMENT));
    assertTrue(cache.lookup(program, translation.profile()).isEmpty());
    IrisPipelineCache.StoreResult repaired = cache.store(program, translation);
    assertFalse(repaired.cacheHit());
    assertTrue(cache.lookup(program, translation.profile()).isPresent());

    Files.write(hit.paths().spirv(IrisShaderStage.VERTEX),
        new byte[] {3, 2, 35, 7});
    assertTrue(cache.lookup(program, translation.profile()).isEmpty());
    cache.store(program, translation);
    byte[] validLookingCorruption = TEST_SPIRV.clone();
    validLookingCorruption[12] ^= 1;
    Files.write(hit.paths().spirv(IrisShaderStage.VERTEX),
        validLookingCorruption);
    assertTrue(cache.lookup(program, translation.profile()).isEmpty());
    cache.store(program, translation);
    Files.writeString(hit.paths().msl(IrisShaderStage.FRAGMENT),
        "#version 450\nvoid main() {}\n");
    assertTrue(cache.lookup(program, translation.profile()).isEmpty());
    cache.store(program, translation);
    Files.writeString(hit.paths().msl(IrisShaderStage.FRAGMENT),
        TEST_FRAGMENT_MSL.replace("main0", "main1"));
    assertTrue(cache.lookup(program, translation.profile()).isEmpty());
  }

  @Test
  void oversizedCompletionMarkerIsRejectedBeforeReading()
      throws Exception {
    IrisFinalShaderProgram program = program();
    IrisShaderTranslation translation = translation(program);
    IrisPipelineCache cache = new IrisPipelineCache(
        new IrisPipelineCacheLayout(temporaryDirectory.resolve("cache")));
    IrisPipelineCache.StoreResult stored = cache.store(program, translation);

    Files.write(stored.paths().translationComplete(), new byte[8192]);

    assertTrue(cache.lookup(program, translation.profile()).isEmpty());
  }

  @Test
  void diskCacheEvictsOldContentAddressesToHonorEntryBound()
      throws Exception {
    IrisPipelineCache cache = new IrisPipelineCache(
        new IrisPipelineCacheLayout(temporaryDirectory.resolve("bounded")),
        1, 1024L * 1024L);
    IrisFinalShaderProgram firstProgram = program("first");
    IrisFinalShaderProgram secondProgram = program("second");
    IrisShaderTranslation firstTranslation = translation(firstProgram);
    IrisShaderTranslation secondTranslation = translation(secondProgram);

    IrisPipelineCache.StoreResult first =
        cache.store(firstProgram, firstTranslation);
    Files.setLastModifiedTime(first.paths().directory(),
        FileTime.fromMillis(1));
    cache.store(secondProgram, secondTranslation);

    assertTrue(cache.lookup(firstProgram, firstTranslation.profile()).isEmpty());
    assertTrue(cache.lookup(secondProgram, secondTranslation.profile())
        .isPresent());
  }

  private static IrisFinalShaderProgram program() {
    return IrisFinalShaderProgram.fromGraphicsLink(SECRET_PROGRAM_NAME,
        SECRET_VERTEX, null, null, null, SECRET_FRAGMENT);
  }

  private static IrisFinalShaderProgram program(String name) {
    return IrisFinalShaderProgram.fromGraphicsLink(name,
        SECRET_VERTEX, null, null, null, SECRET_FRAGMENT);
  }

  private static IrisShaderTranslation translation(
      IrisFinalShaderProgram program) {
    return new IrisShaderTranslation(IrisShaderCacheKey.from(program),
        "test-backend",
        IrisTranslationProfile.LWJGL_3_4_1_METAL_3_ARGUMENT_BUFFERS,
        artifacts());
  }

  private static EnumMap<IrisShaderStage,
      IrisShaderTranslation.StageArtifacts> artifacts() {
    EnumMap<IrisShaderStage, IrisShaderTranslation.StageArtifacts> artifacts =
        new EnumMap<>(IrisShaderStage.class);
    artifacts.put(IrisShaderStage.VERTEX,
        new IrisShaderTranslation.StageArtifacts(
            TEST_SPIRV, TEST_VERTEX_MSL));
    artifacts.put(IrisShaderStage.FRAGMENT,
        new IrisShaderTranslation.StageArtifacts(
            TEST_SPIRV, TEST_FRAGMENT_MSL));
    return artifacts;
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }
}

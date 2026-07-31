package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IrisPipelineCacheVerifiedMslTest {
  private static final IrisTranslationProfile PROFILE =
      new IrisTranslationProfile("verified-msl-test=1");
  private static final byte[] SPIRV = ByteBuffer.allocate(20)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putInt(0x07230203)
      .putInt(0x00010000)
      .putInt(0)
      .putInt(1)
      .putInt(0)
      .array();
  private static final String VERTEX_MSL =
      "#include <metal_stdlib>\nusing namespace metal;\n"
          + "vertex float4 main0() { return float4(0.0); }\n";
  private static final String FRAGMENT_MSL =
      "#include <metal_stdlib>\nusing namespace metal;\n"
          + "fragment float4 main0() { return float4(1.0); }\n";

  @TempDir
  Path temporaryDirectory;

  @Test
  void verifiedReadReturnsExactClonedUtf8AndDigestWhilePipelineIsPending()
      throws Exception {
    IrisFinalShaderProgram program = program();
    IrisPipelineCache cache = cache("valid");
    IrisPipelineCache.StoreResult stored =
        cache.store(program, translation(program));

    IrisPipelineCache.VerifiedMslStage verified =
        cache.readVerifiedMslStage(
            IrisShaderCacheKey.from(program, PROFILE), PROFILE,
            IrisShaderStage.VERTEX).orElseThrow();
    byte[] expected = VERTEX_MSL.getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(expected, verified.mslUtf8());
    assertEquals(rawSha256(expected), verified.mslSha256());
    byte[] callerCopy = verified.mslUtf8();
    callerCopy[0] ^= 1;
    assertArrayEquals(expected, verified.mslUtf8());
    assertTrue(Files.readString(stored.paths().manifest())
        .contains("pipeline.status=pending"));
    assertTrue(!Files.exists(stored.paths().metalBinaryArchive()));
  }

  @Test
  void verifiedReadRejectsContentManifestAndBoundTampering()
      throws Exception {
    IrisFinalShaderProgram program = program();
    IrisShaderTranslation translation = translation(program);
    IrisPipelineCache cache = cache("tamper");
    IrisPipelineCache.StoreResult stored = cache.store(program, translation);
    IrisShaderCacheKey key = translation.key();
    Path vertex = stored.paths().msl(IrisShaderStage.VERTEX);

    Files.writeString(vertex, VERTEX_MSL.replace("main0", "main1"));
    assertTrue(cache.readVerifiedMslStage(
        key, PROFILE, IrisShaderStage.VERTEX).isEmpty());

    cache.store(program, translation);
    String manifest = Files.readString(stored.paths().manifest());
    Files.writeString(stored.paths().manifest(), manifest
        + "stage.vertex.msl.sha256="
        + rawSha256(VERTEX_MSL.getBytes(StandardCharsets.UTF_8)) + "\n");
    assertTrue(cache.readVerifiedMslStage(
        key, PROFILE, IrisShaderStage.VERTEX).isEmpty());

    Files.writeString(stored.paths().manifest(), manifest);
    String actualSize = Integer.toString(
        VERTEX_MSL.getBytes(StandardCharsets.UTF_8).length);
    Files.writeString(stored.paths().manifest(), manifest.replace(
        "stage.vertex.msl.bytes=" + actualSize,
        "stage.vertex.msl.bytes=999999999"));
    assertTrue(cache.readVerifiedMslStage(
        key, PROFILE, IrisShaderStage.VERTEX).isEmpty());
  }

  @Test
  void verifiedReadDoesNotFollowMslSymlink() throws Exception {
    IrisFinalShaderProgram program = program();
    IrisShaderTranslation translation = translation(program);
    IrisPipelineCache cache = cache("symlink");
    IrisPipelineCache.StoreResult stored = cache.store(program, translation);
    Path target = stored.paths().msl(IrisShaderStage.VERTEX);
    Path replacement = temporaryDirectory.resolve("outside.metal");
    Files.writeString(replacement, VERTEX_MSL);
    Files.delete(target);
    Files.createSymbolicLink(target, replacement);

    assertTrue(cache.readVerifiedMslStage(
        translation.key(), PROFILE, IrisShaderStage.VERTEX).isEmpty());
  }

  @Test
  void compileValidationRejectsDeclaredAndActualMslAboveSixteenMiB()
      throws Exception {
    IrisFinalShaderProgram program = program();
    IrisShaderTranslation translation = translation(program);
    IrisPipelineCache cache = cache("compile-bound");
    IrisPipelineCache.StoreResult stored = cache.store(program, translation);
    Path manifestPath = stored.paths().manifest();
    Path vertexPath = stored.paths().msl(IrisShaderStage.VERTEX);
    String manifest = Files.readString(manifestPath);
    String actualSize = Integer.toString(
        VERTEX_MSL.getBytes(StandardCharsets.UTF_8).length);
    String overLimit = Long.toString(
        IrisPipelineCache.MAX_LIBRARY_VALIDATION_MSL_BYTES + 1);

    Files.writeString(manifestPath, manifest.replace(
        "stage.vertex.msl.bytes=" + actualSize,
        "stage.vertex.msl.bytes=" + overLimit));
    assertTrue(cache.readVerifiedMslStage(
        translation.key(), PROFILE, IrisShaderStage.VERTEX).isEmpty());

    Files.writeString(manifestPath, manifest);
    try (FileChannel channel = FileChannel.open(vertexPath,
        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      channel.position(IrisPipelineCache.MAX_LIBRARY_VALIDATION_MSL_BYTES);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }
    assertEquals(IrisPipelineCache.MAX_LIBRARY_VALIDATION_MSL_BYTES + 1,
        Files.size(vertexPath));
    assertTrue(cache.readVerifiedMslStage(
        translation.key(), PROFILE, IrisShaderStage.VERTEX).isEmpty());
  }

  private IrisPipelineCache cache(String name) {
    return new IrisPipelineCache(new IrisPipelineCacheLayout(
        temporaryDirectory.resolve(name)));
  }

  private static IrisFinalShaderProgram program() {
    return IrisFinalShaderProgram.fromGraphicsLink(
        "private-name", "private-vertex", null, null, null,
        "private-fragment");
  }

  private static IrisShaderTranslation translation(
      IrisFinalShaderProgram program) {
    EnumMap<IrisShaderStage, IrisShaderTranslation.StageArtifacts> stages =
        new EnumMap<>(IrisShaderStage.class);
    stages.put(IrisShaderStage.VERTEX,
        new IrisShaderTranslation.StageArtifacts(SPIRV, VERTEX_MSL));
    stages.put(IrisShaderStage.FRAGMENT,
        new IrisShaderTranslation.StageArtifacts(SPIRV, FRAGMENT_MSL));
    return new IrisShaderTranslation(
        IrisShaderCacheKey.from(program, PROFILE), "verified-test", PROFILE,
        stages);
  }

  private static String rawSha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(content));
  }
}

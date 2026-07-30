package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IrisTranslationCoordinatorTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void backgroundWorkerTranslatesAndExposesStableCounters()
      throws Exception {
    IrisTranslationProfile profile =
        new IrisTranslationProfile("test-translator=1");
    IrisShaderCaptureQueue queue =
        new IrisShaderCaptureQueue(4, 1000, 4000, profile);
    FakeBackend backend = new FakeBackend(profile);
    IrisPipelineCache cache = new IrisPipelineCache(
        new IrisPipelineCacheLayout(temporaryDirectory.resolve("cache")));
    IrisTranslationCoordinator coordinator =
        new IrisTranslationCoordinator(queue, backend, cache,
            temporaryDirectory.resolve("cache"));
    IrisFinalShaderProgram program = graphics("captured");
    assertEquals(IrisShaderCaptureQueue.Disposition.ACCEPTED,
        queue.offer(program).disposition());

    try {
      coordinator.start();
      await(() -> coordinator.snapshot().translated() == 1,
          Duration.ofSeconds(3));

      IrisTranslationCoordinator.Status status = coordinator.snapshot();
      assertTrue(status.enabled());
      assertTrue(status.running());
      assertEquals(0, status.queued());
      assertEquals(1, status.attempted());
      assertEquals(1, status.translated());
      assertEquals(0, status.cacheHits());
      assertEquals(0, status.failed());
      assertEquals(0, status.rejected());
      assertEquals(0, status.captureFailures());
      assertEquals("", status.lastFailure());
      assertFalse(status.cacheRoot().isBlank());
      assertTrue(cache.lookup(program, profile).isPresent());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void unsupportedGeometryIsCountedAndKeptOnIrisFallback()
      throws Exception {
    IrisTranslationProfile profile =
        new IrisTranslationProfile("test-translator=1");
    IrisShaderCaptureQueue queue =
        new IrisShaderCaptureQueue(4, 1000, 4000, profile);
    FakeBackend backend = new FakeBackend(profile);
    IrisTranslationCoordinator coordinator = new IrisTranslationCoordinator(
        queue, backend, new IrisPipelineCache(new IrisPipelineCacheLayout(
            temporaryDirectory.resolve("geometry-cache"))),
        temporaryDirectory.resolve("geometry-cache"));
    IrisFinalShaderProgram geometry =
        IrisFinalShaderProgram.fromGraphicsLink(
            "geometry", "vertex", "geometry", null, null, "fragment");
    queue.offer(geometry);

    try {
      coordinator.start();
      await(() -> coordinator.snapshot().failed() == 1,
          Duration.ofSeconds(3));

      IrisTranslationCoordinator.Status status = coordinator.snapshot();
      assertEquals(1, status.attempted());
      assertEquals(0, status.translated());
      assertEquals(1, status.failed());
      assertTrue(status.lastFailure().contains("geometry unsupported"));
      assertEquals(0, backend.calls.get());
    } finally {
      coordinator.close();
    }
  }

  private static IrisFinalShaderProgram graphics(String name) {
    return IrisFinalShaderProgram.fromGraphicsLink(
        name, "vertex", null, null, null, "fragment");
  }

  private static void await(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), "condition did not become true");
  }

  private static final class FakeBackend
      implements IrisShaderTranslatorBackend {
    private final IrisTranslationProfile profile;
    private final AtomicInteger calls = new AtomicInteger();

    private FakeBackend(IrisTranslationProfile profile) {
      this.profile = profile;
    }

    @Override
    public String id() {
      return "test-backend";
    }

    @Override
    public IrisTranslationProfile profile() {
      return profile;
    }

    @Override
    public Availability discover() {
      return new Availability(true, "test backend");
    }

    @Override
    public StageSupport support(IrisShaderStage stage) {
      return stage == IrisShaderStage.GEOMETRY
          ? StageSupport.UNSUPPORTED_KEEP_IRIS_OPENGL
          : StageSupport.SUPPORTED;
    }

    @Override
    public IrisShaderTranslation translate(IrisFinalShaderProgram program) {
      calls.incrementAndGet();
      EnumMap<IrisShaderStage, IrisShaderTranslation.StageArtifacts> stages =
          new EnumMap<>(IrisShaderStage.class);
      for (IrisShaderStage stage : IrisShaderStage.values()) {
        if (program.hasStage(stage)) {
          stages.put(stage, new IrisShaderTranslation.StageArtifacts(
              minimalSpirv(),
              "#include <metal_stdlib>\nusing namespace metal;\n"
                  + "// generated MSL\n"));
        }
      }
      return new IrisShaderTranslation(
          IrisShaderCacheKey.from(program, profile), id(), profile, stages);
    }

    private static byte[] minimalSpirv() {
      return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
          .putInt(0x07230203)
          .putInt(0x00010000)
          .putInt(0)
          .putInt(1)
          .putInt(0)
          .array();
    }
  }
}

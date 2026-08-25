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
  void onlyQueuedResourceReplacementIsRecoverableDuringOwnership() {
    assertTrue(IrisTranslationCoordinator.recoverableOwnershipInvalidation(
        "graph-native-resource-generation-stale"));
    assertTrue(IrisTranslationCoordinator.recoverableOwnershipInvalidation(
        "graph-frame-texture-generation-stale"));
    assertTrue(IrisTranslationCoordinator.recoverableOwnershipInvalidation(
        "graph-frame-display-generation-stale"));
    assertFalse(IrisTranslationCoordinator.recoverableOwnershipInvalidation(
        "graph-native-presentation-size-unsupported"));
    assertFalse(IrisTranslationCoordinator.recoverableOwnershipInvalidation(
        "graph-native-draw-pipeline-state-mismatch"));
    assertFalse(IrisTranslationCoordinator.recoverableOwnershipInvalidation(
        null));
    assertTrue(IrisTranslationCoordinator.retryableFullGraphCaptureAbort(
        "graph-frame-capture-backpressure"));
    assertFalse(IrisTranslationCoordinator.retryableFullGraphCaptureAbort(
        "graph-frame-resource-capture-incomplete"));
  }

  @Test
  void displayPresentationGenerationRejectsEveryStaleFrame() {
    assertTrue(IrisTranslationCoordinator
        .displayPresentationGenerationMatches(3, 3));
    assertFalse(IrisTranslationCoordinator
        .displayPresentationGenerationMatches(2, 3));
    assertFalse(IrisTranslationCoordinator
        .displayPresentationGenerationMatches(0, 0));
  }

  @Test
  void fullGraphOwnershipDisablesLegacyFinalCutoverCapture() {
    IrisSelectiveCutoverGate.Status armed =
        new IrisSelectiveCutoverGate.Status(
            IrisSelectiveCutoverGate.Mode.ARMED, 4, 2,
            false, 0, 0, 0, 0);
    assertTrue(IrisTranslationCoordinator.legacyCutoverCaptureEligible(
        false, 0, IrisRenderGraph.Phase.FINAL, armed));
    assertFalse(IrisTranslationCoordinator.legacyCutoverCaptureEligible(
        true, 0, IrisRenderGraph.Phase.FINAL, armed));
    assertFalse(IrisTranslationCoordinator.legacyCutoverCaptureEligible(
        false, 1, IrisRenderGraph.Phase.FINAL, armed));
    assertFalse(IrisTranslationCoordinator.legacyCutoverCaptureEligible(
        false, 0, IrisRenderGraph.Phase.COMPOSITE, armed));
  }

  @Test
  void fullGraphParityRequiresThreeConsecutivePassingFrames() {
    assertFalse(IrisTranslationCoordinator.fullGraphParityConverged(
        new IrisVisualParityGate.Status(4, 3, 1, 2,
            0.0015, 0.2, 27, false)));
    assertTrue(IrisTranslationCoordinator.fullGraphParityConverged(
        new IrisVisualParityGate.Status(6, 5, 1, 3,
            0.0015, 0.2, 27, false)));
  }

  @Test
  void preOwnershipCaptureAbortsRetryWithinStrictBound() {
    assertTrue(IrisTranslationCoordinator.fullGraphCaptureAbortRetryAllowed(1));
    assertTrue(IrisTranslationCoordinator.fullGraphCaptureAbortRetryAllowed(
        IrisTranslationCoordinator.DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS - 1L));
    assertFalse(IrisTranslationCoordinator.fullGraphCaptureAbortRetryAllowed(
        IrisTranslationCoordinator.DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS));
    assertFalse(IrisTranslationCoordinator.fullGraphCaptureAbortRetryAllowed(0));
  }

  @Test
  void packedHdrReadbackIsRestrictedToExactDiagnosticMode() {
    assertTrue(IrisTranslationCoordinator.fullGraphReadbackFormatSupported(
        "rgba8-unorm", false));
    assertFalse(IrisTranslationCoordinator.fullGraphReadbackFormatSupported(
        "rg11b10-float", false));
    assertTrue(IrisTranslationCoordinator.fullGraphReadbackFormatSupported(
        "rg11b10-float", true));
  }

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
      assertEquals("geometry-unsupported-keep-iris-opengl",
          status.translationFailureReasonSummary());
      assertEquals(0, backend.calls.get());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void exposesBoundedTranslationFailureDiagnostics() {
    IrisTranslationCoordinator.BoundedReasonSet reasons =
        new IrisTranslationCoordinator.BoundedReasonSet(4);
    reasons.add(IrisTranslationCoordinator.diagnosticFailureReason(
        new IrisShaderTranslationException(
            "shaderc failed for vertex: vertex.glsl:42: error: bad token")));

    assertEquals(1, reasons.size());
    assertTrue(reasons.complete());
    assertEquals(
        "shaderc_failed_for_vertex:_vertex.glsl:42:_error:_bad_token",
        reasons.summary());
    assertTrue(reasons.sha256().matches("[0-9a-f]{64}"));
  }

  @Test
  void boundedReasonsAreSortedNormalizedAndFailClosedWhenTruncated() {
    IrisTranslationCoordinator.BoundedReasonSet reasons =
        new IrisTranslationCoordinator.BoundedReasonSet(2);

    reasons.add(" z reason ");
    reasons.add("a reason");
    reasons.add("middle/reason");
    reasons.add("a reason");

    assertEquals(2, reasons.size());
    assertEquals("a_reason,middle/reason", reasons.summary());
    assertFalse(reasons.complete());
    assertTrue(reasons.sha256().matches("[0-9a-f]{64}"));
  }

  @Test
  void shadowPlanStatusRequiresObservedUnblockedExecution() {
    String emptyDigest = new IrisTranslationCoordinator.BoundedReasonSet(2)
        .sha256();

    assertTrue(new IrisTranslationCoordinator.ShadowPlanStatus(
        2, 2, 0, 14, 0, true, emptyDigest, "").complete());
    assertFalse(new IrisTranslationCoordinator.ShadowPlanStatus(
        2, 1, 1, 14, 1, true, emptyDigest,
        "resource-metadata-incomplete").complete());
    assertFalse(new IrisTranslationCoordinator.ShadowPlanStatus(
        0, 0, 0, 0, 0, true, emptyDigest, "").complete());
  }

  @Test
  void shadowReplayExecutionGateRequiresAllPhasesAndCleanAccounting() {
    String emptyDigest = new IrisTranslationCoordinator.BoundedReasonSet(2)
        .sha256();
    String phases = "SHADOW,GEOMETRY,COMPOSITE,FINAL";

    assertTrue(new IrisTranslationCoordinator.ShadowReplayStatus(
        true, 8, 4, 4, 4, 0, 0, 0, 4, true, 4, phases,
        "18446744073709551615", 1280, 720, 0, true, emptyDigest, "")
        .executionComplete());
    assertFalse(new IrisTranslationCoordinator.ShadowReplayStatus(
        true, 8, 4, 4, 3, 1, 0, 0, 4, true, 3,
        "SHADOW,GEOMETRY,FINAL", "9", 1280, 720, 1, true, emptyDigest,
        "native-replay-unsupported").executionComplete());
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

package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeIrisMetalGraphExecutorTest {
  @Test
  void acceptsOnlyExactSuccessfulNativeTelemetry() {
    IrisMetalGraphFramePacketEncoder.Frame frame = frame();
    NativeIrisMetalGraphExecutor executor = new NativeIrisMetalGraphExecutor(
        (packet, mip) -> new long[] {1, 1, 0, 0, 1, 0, 0});

    NativeIrisMetalGraphExecutor.Result result = executor.execute(frame);

    assertEquals(NativeIrisMetalGraphExecutor.Outcome.SUCCEEDED,
        result.outcome());
    assertEquals(1, result.steps());
    assertEquals(1, result.barriers());
  }

  @Test
  void rejectsInconsistentSuccessAndMapsUnsupportedReason() {
    IrisMetalGraphFramePacketEncoder.Frame frame = frame();
    NativeIrisMetalGraphExecutor.Result invalid =
        new NativeIrisMetalGraphExecutor(
            (packet, mip) -> new long[] {1, 2, 0, 0, 0, 0, 0})
            .execute(frame);
    assertEquals(NativeIrisMetalGraphExecutor.Outcome.FAILED,
        invalid.outcome());
    assertEquals("graph-native-success-invalid", invalid.reason());

    NativeIrisMetalGraphExecutor.Result unsupported =
        new NativeIrisMetalGraphExecutor(
            (packet, mip) -> new long[] {0, 0, 0, 0, 0, 0, 6})
            .execute(frame);
    assertEquals(NativeIrisMetalGraphExecutor.Outcome.UNSUPPORTED,
        unsupported.outcome());
    assertEquals("graph-native-draw-unsupported", unsupported.reason());
  }

  @Test
  void mapsDetailedNativeDrawRejectionReasons() {
    String[] expected = {
        "graph-native-draw-pipeline-unavailable",
        "graph-native-draw-pipeline-state-mismatch",
        "graph-native-draw-color-slot-mismatch",
        "graph-native-draw-color-target-mismatch",
        "graph-native-draw-color-coverage-mismatch",
        "graph-native-draw-depth-stencil-presence-mismatch",
        "graph-native-draw-depth-target-mismatch",
        "graph-native-draw-stencil-target-mismatch",
        "graph-native-draw-depth-stencil-alias-mismatch",
        "graph-native-draw-buffer-residency-mismatch",
        "graph-native-draw-texture-override-mismatch",
        "graph-native-draw-shared-texture-mismatch",
        "graph-native-draw-shared-texture-fence-timeout",
        "graph-native-draw-texture-upload-unsupported",
        "graph-native-draw-unused-texture-override",
        "graph-native-draw-argument-binding-unsupported",
        "graph-native-draw-index-buffer-missing",
        "graph-native-draw-index-range-invalid",
        "graph-native-draw-texture-subresource-unsupported",
        "graph-native-draw-texture-format-unsupported",
        "graph-native-draw-texture-byte-budget-exceeded"
    };
    for (int index = 0; index < expected.length; index++) {
      long reasonCode = index + 8L;
      NativeIrisMetalGraphExecutor.Result result =
          new NativeIrisMetalGraphExecutor((packet, mip) ->
              new long[] {0, 0, 0, 0, 0, 0, reasonCode})
              .execute(frame());
      assertEquals(NativeIrisMetalGraphExecutor.Outcome.UNSUPPORTED,
          result.outcome());
      assertEquals(expected[index], result.reason());
    }
  }

  @Test
  void mapsDetailedNativeGraphOperationRejectionReasons() {
    String[] expected = {
        "graph-native-readback-texture-missing",
        "graph-native-readback-multisample-unsupported",
        "graph-native-readback-format-unsupported",
        "graph-native-readback-row-size-unsupported",
        "graph-native-readback-byte-size-unsupported",
        "graph-native-clear-region-unsupported",
        "graph-native-clear-texture-missing",
        "graph-native-clear-color-format-unsupported",
        "graph-native-clear-depth-format-unsupported",
        "graph-native-clear-stencil-format-unsupported",
        "graph-native-copy-texture-missing",
        "graph-native-copy-format-mismatch",
        "graph-native-copy-multisample-unsupported",
        "graph-native-copy-mip-level-unsupported",
        "graph-native-copy-bounds-unsupported",
        "graph-native-mipmap-texture-missing",
        "graph-native-mipmap-levels-unavailable",
        "graph-native-mipmap-multisample-unsupported"
    };
    for (int index = 0; index < expected.length; index++) {
      long reasonCode = index + 29L;
      NativeIrisMetalGraphExecutor.Result result =
          new NativeIrisMetalGraphExecutor((packet, mip) ->
              new long[] {0, 0, 0, 0, 0, 0, reasonCode})
              .execute(frame());
      assertEquals(NativeIrisMetalGraphExecutor.Outcome.UNSUPPORTED,
          result.outcome());
      assertEquals(expected[index], result.reason());
    }
  }

  @Test
  void keepsValidationAndAsyncPresentationContractsDisjoint() {
    IrisMetalGraphFramePacketEncoder.Frame presentationFrame =
        new IrisMetalGraphFramePacketEncoder.Frame(3,
            List.of(new IrisMetalGraphFramePacketEncoder.Resource(0, 17)),
            List.of(new IrisMetalGraphFramePacketEncoder.Barrier(0x20)),
            IrisMetalGraphFramePacketEncoder.NO_READBACK, 0);
    NativeIrisMetalGraphExecutor executor = new NativeIrisMetalGraphExecutor(
        (packet, mip) -> new long[] {-1, 0, 0, 0, 0, 0, 0},
        () -> new byte[0],
        packet -> new long[] {1, 1, 0, 0, 1, 77, 0},
        token -> new long[] {1, token, 64, 32, 0},
        (token, width, height) -> token == 77 && width == 64
            && height == 32,
        token -> token == 77);

    assertEquals("graph-native-mode-invalid",
        executor.execute(presentationFrame).reason());
    assertEquals("graph-presentation-mode-invalid",
        executor.submitForPresentation(frame()).reason());

    NativeIrisMetalGraphExecutor.PresentationSubmission submission =
        executor.submitForPresentation(presentationFrame,
            IrisMetalGraphFramePacketEncoder.encode(presentationFrame));
    assertEquals(NativeIrisMetalGraphExecutor.Outcome.SUCCEEDED,
        submission.outcome());
    assertEquals(77, submission.token());

    assertEquals("graph-presentation-packet-invalid",
        executor.submitForPresentation(presentationFrame, new byte[0])
            .reason());

    NativeIrisMetalGraphExecutor.PresentationStatus status =
        executor.pollPresentation(submission.token());
    assertEquals(NativeIrisMetalGraphExecutor.PresentationState.READY,
        status.state());
    assertTrue(executor.promotePresentation(status));
    assertTrue(executor.discardPresentation(77));
    assertFalse(executor.promotePresentation(
        NativeIrisMetalGraphExecutor.PresentationStatus.failed(77,
            "failed")));
  }

  @Test
  void acceptsDimensionlessPendingStatusDuringNonblockingNativeSubmit() {
    NativeIrisMetalGraphExecutor executor = new NativeIrisMetalGraphExecutor(
        (packet, mip) -> new long[] {-1, 0, 0, 0, 0, 0, 0},
        () -> new byte[0],
        packet -> new long[] {-1, 0, 0, 0, 0, 0, 0},
        token -> new long[] {0, token, 0, 0, 0},
        (token, width, height) -> false,
        token -> false);

    NativeIrisMetalGraphExecutor.PresentationStatus status =
        executor.pollPresentation(77);

    assertEquals(NativeIrisMetalGraphExecutor.PresentationState.PENDING,
        status.state());
    assertEquals(77, status.token());
    assertEquals(0, status.width());
    assertEquals(0, status.height());
    assertFalse(executor.promotePresentation(status));
  }

  private static IrisMetalGraphFramePacketEncoder.Frame frame() {
    return new IrisMetalGraphFramePacketEncoder.Frame(3,
        List.of(new IrisMetalGraphFramePacketEncoder.Resource(0, 17)),
        List.of(new IrisMetalGraphFramePacketEncoder.Barrier(0x20)), -1);
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.StateValue;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class IrisVisualParityCaptureTest {
  @Test
  void diagnosticReadbackProgramRequiresExactNonEmptyMatch() {
    assertTrue(IrisVisualParityCapture.diagnosticProgramMatches(
        "composite4", "composite4"));
    assertFalse(IrisVisualParityCapture.diagnosticProgramMatches(
        "composite4", "composite3"));
    assertFalse(IrisVisualParityCapture.diagnosticProgramMatches(
        "", "composite4"));
  }

  @Test
  void capturesAndPairsTheExactFinalDraw() {
    byte[] expected = new byte[2 * 3 * 4];
    expected[5] = 42;
    IrisVisualParityCapture capture = new IrisVisualParityCapture(
        true, 2, (pending, width, height) -> expected.clone());
    IrisPipelineStateCapture.PendingState pending = pending(5, 2, 3);

    capture.beginDrawInvocation();
    capture.associate(pending, IrisRenderGraph.Phase.FINAL);
    capture.endDrawInvocation();

    IrisVisualParityCapture.CapturedFrame frame =
        capture.take(pending).orElseThrow();
    assertEquals(2, frame.width());
    assertEquals(3, frame.height());
    assertArrayEquals(expected, frame.rgba8());
    assertEquals(1, capture.status().scheduled());
    assertEquals(1, capture.status().captured());
    assertEquals(0, capture.status().failed());
  }

  @Test
  void ignoresDisabledAndNonFinalDraws() {
    IrisPipelineStateCapture.PendingState pending = pending(5, 1, 1);
    IrisVisualParityCapture disabled = new IrisVisualParityCapture(
        false, 1, (state, width, height) -> new byte[4]);
    IrisVisualParityCapture nonFinal = new IrisVisualParityCapture(
        true, 1, (state, width, height) -> new byte[4]);

    disabled.beginDrawInvocation();
    disabled.associate(pending, IrisRenderGraph.Phase.FINAL);
    disabled.endDrawInvocation();
    nonFinal.beginDrawInvocation();
    nonFinal.associate(pending, IrisRenderGraph.Phase.COMPOSITE);
    nonFinal.endDrawInvocation();

    assertEquals(0, disabled.status().scheduled());
    assertEquals(0, nonFinal.status().scheduled());
    assertFalse(disabled.take(pending).isPresent());
    assertFalse(nonFinal.take(pending).isPresent());
  }

  @Test
  void dropsTheOldestCompletedCaptureAtTheHardBound() {
    IrisVisualParityCapture capture = new IrisVisualParityCapture(
        true, 1, (state, width, height) -> new byte[4]);
    IrisPipelineStateCapture.PendingState first = pending(5, 1, 1);
    IrisPipelineStateCapture.PendingState second = pending(6, 1, 1);

    capture.beginDrawInvocation();
    capture.associate(first, IrisRenderGraph.Phase.FINAL);
    capture.endDrawInvocation();
    capture.beginDrawInvocation();
    capture.associate(second, IrisRenderGraph.Phase.FINAL);
    capture.endDrawInvocation();

    assertFalse(capture.take(first).isPresent());
    assertTrue(capture.take(second).isPresent());
    assertEquals(1, capture.status().dropped());
  }

  @Test
  void rejectsMalformedReadbackWithoutPublishingIt() {
    IrisVisualParityCapture capture = new IrisVisualParityCapture(
        true, 1, (state, width, height) -> new byte[3]);
    IrisPipelineStateCapture.PendingState pending = pending(5, 1, 1);

    capture.beginDrawInvocation();
    capture.associate(pending, IrisRenderGraph.Phase.FINAL);
    capture.endDrawInvocation();

    assertFalse(capture.take(pending).isPresent());
    assertEquals(1, capture.status().failed());
    assertEquals("visual-parity-readback-size-mismatch",
        capture.status().lastFailure());
  }

  @Test
  void fullGraphConsumerTakesStandaloneEndOfFrameCapture() {
    AtomicInteger sequence = new AtomicInteger();
    IrisVisualParityCapture capture = new IrisVisualParityCapture(
        true, 4, (state, width, height) -> {
          byte[] rgba = new byte[4];
          rgba[0] = (byte) sequence.incrementAndGet();
          return rgba;
        });
    IrisPipelineStateCapture.PendingState pending = pending(5, 1, 1);

    capture.beginDrawInvocation();
    capture.associate(pending, IrisRenderGraph.Phase.FINAL);
    capture.endDrawInvocation();
    capture.captureStandalone(pending);

    IrisVisualParityCapture.CapturedFrame latest =
        capture.takeLatest(pending).orElseThrow();
    assertEquals(2, Byte.toUnsignedInt(latest.rgba8()[0]));
    assertFalse(capture.take(pending).isPresent());
  }

  private static IrisPipelineStateCapture.PendingState pending(
      int glProgram, int width, int height) {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerProgram(glProgram);
    tracker.useProgram(glProgram);
    IrisProgramIdentityRegistry identities =
        new IrisProgramIdentityRegistry(2);
    IrisProgramIdentityRegistry.Registration registration =
        identities.register(glProgram,
            new IrisProgramIdentityRegistry.ProgramDescriptor(
                PassKind.LINKED_GRAPHICS, "visual-parity", false,
                List.of(new VertexBufferLayout(0, 12,
                    StepFunction.PER_VERTEX, 0)),
                List.of(new VertexAttribute(0, 0, 0,
                    new DataFormat("rgb32-float")))));
    return new IrisPipelineStateCapture.PendingState(registration,
        tracker.snapshotDraw(4), null,
        new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL),
        new IrisDynamicDrawState(
            StateValue.known(new IrisDynamicDrawState.Rect(
                0, 0, width, height)),
            StateValue.known(false),
            StateValue.known(new IrisDynamicDrawState.Rect(0, 0, 0, 0))),
        IrisVertexInputBindings.complete(List.of(), null),
        new IrisShadowReplayBufferSnapshot(true, List.of(), List.of(),
            Optional.empty(), Optional.empty(), Map.of(), Map.of(),
            List.of()));
  }
}

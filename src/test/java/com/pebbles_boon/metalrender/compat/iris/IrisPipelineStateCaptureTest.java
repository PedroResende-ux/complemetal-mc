package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisPipelineStateCaptureTest {
  @Test
  void capturesOnlyUniqueRegisteredIrisVariants() {
    IrisGlStateTracker tracker = new IrisGlStateTracker(4, 4, 4);
    IrisProgramIdentityRegistry identities =
        new IrisProgramIdentityRegistry(4);
    IrisPipelineStateCapture capture = new IrisPipelineStateCapture(
        tracker, identities, 4, 8);
    capture.initializeOpenGlDefaults();
    tracker.registerFramebuffer(2);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_FRAMEBUFFER, 2);
    tracker.drawBuffers(IrisGlStateTracker.GL_NONE);
    tracker.registerProgram(5);
    identities.register(5, descriptor());
    capture.useProgram(5);

    capture.draw(new IrisExecutionCommand.DrawArrays(
        0x0004, 2, 6, 3, 1, IrisExecutionCommand.Source.DIRECT_GL));
    capture.draw(0x0004);
    capture.draw(0x0001);

    assertEquals(2, capture.queued());
    assertEquals(3, capture.drawsObserved());
    assertEquals(2, capture.variantsAccepted());
    IrisPipelineStateCapture.PendingState first =
        capture.poll().orElseThrow();
    IrisExecutionCommand.DrawArrays command = assertInstanceOf(
        IrisExecutionCommand.DrawArrays.class, first.command());
    assertEquals(2, command.firstVertex());
    assertEquals(3, command.instanceCount());
    assertTrue(capture.poll().isPresent());
  }

  @Test
  void dispatchReportsFalseWhenProgramIdentityIsMissing() {
    IrisGlStateTracker tracker = new IrisGlStateTracker(4, 4, 4);
    IrisProgramIdentityRegistry identities =
        new IrisProgramIdentityRegistry(4);
    IrisPipelineStateCapture capture = new IrisPipelineStateCapture(
        tracker, identities, 4, 8);
    capture.initializeOpenGlDefaults();
    tracker.registerProgram(5);
    capture.useProgram(5);

    assertFalse(capture.dispatch(new IrisExecutionCommand.Dispatch(1, 1, 1)));
    assertEquals(0, capture.queued());
  }

  @Test
  void cutoverLookupIgnoresSequenceButRetainsProgramGeneration() {
    IrisGlStateTracker tracker = new IrisGlStateTracker(4, 4, 4);
    IrisProgramIdentityRegistry identities =
        new IrisProgramIdentityRegistry(4);
    IrisPipelineStateCapture capture = new IrisPipelineStateCapture(
        tracker, identities, 4, 8);
    capture.initializeOpenGlDefaults();
    tracker.registerFramebuffer(2);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_FRAMEBUFFER, 2);
    tracker.drawBuffers(IrisGlStateTracker.GL_NONE);
    tracker.registerProgram(5);
    identities.register(5, descriptor());
    capture.useProgram(5);

    IrisExecutionCommand.DrawArrays command =
        new IrisExecutionCommand.DrawArrays(0x0004, 0, 3, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL);
    IrisPipelineStateCapture.PendingState first =
        capture.captureDraw(command,
            IrisVertexInputBindings.unavailable("test")).orElseThrow();
    IrisPipelineStateCapture.PendingState second =
        capture.captureDraw(command,
            IrisVertexInputBindings.unavailable("test")).orElseThrow();
    assertNotEquals(first.snapshot().sequence(), second.snapshot().sequence());
    assertEquals(IrisPipelineStateCapture.lookupKey(first),
        IrisPipelineStateCapture.lookupKey(second));

    identities.delete(5);
    identities.register(5, descriptor());
    IrisPipelineStateCapture.PendingState replacement =
        capture.captureDraw(command,
            IrisVertexInputBindings.unavailable("test")).orElseThrow();
    assertNotEquals(IrisPipelineStateCapture.lookupKey(first),
        IrisPipelineStateCapture.lookupKey(replacement));
  }

  private static IrisProgramIdentityRegistry.ProgramDescriptor descriptor() {
    return new IrisProgramIdentityRegistry.ProgramDescriptor(
        PassKind.LINKED_GRAPHICS, "test", false,
        List.of(new VertexBufferLayout(0, 12, StepFunction.PER_VERTEX, 0)),
        List.of(new VertexAttribute(0, 0, 0,
            new DataFormat("rgb32-float"))));
  }
}

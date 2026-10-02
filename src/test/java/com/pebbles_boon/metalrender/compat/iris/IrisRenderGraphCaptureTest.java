package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class IrisRenderGraphCaptureTest {
  private static final int GL_TEXTURE_2D = 0x0DE1;
  private static final int GL_DEPTH_BUFFER_BIT = 0x00000100;
  private static final int GL_COLOR_BUFFER_BIT = 0x00004000;
  private static final int GL_NEAREST = 0x2600;

  @Test
  void freshDiagnosticCaptureUsesImmutableCpuSnapshot() {
    assertTrue(IrisRenderGraphCapture.preferGpuHandoffForFullReplay(false));
    assertFalse(IrisRenderGraphCapture.preferGpuHandoffForFullReplay(true));
  }

  @Test
  void unsupportedBufferClearInvalidatesFullReplayWithoutDroppingOpenGlPath() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(
        tracker);
    capture.beginFrame();
    capture.markUnsupportedFullReplayOperation(
        "graph-frame-buffer-clear-unimplemented");
    assertTrue(capture.hasActiveFrame());
    capture.endFrame();
    IrisRenderGraphCapture.PendingFrame frame = capture.poll().orElseThrow();
    assertFalse(frame.fullReplayCaptured());
  }

  @Test
  void activeFrameTracksWhetherGraphOperationsCanBeSuppressed() {
    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(
        new IrisGlStateTracker());
    assertFalse(capture.hasActiveFrame());

    capture.beginFrame();
    assertTrue(capture.hasActiveFrame());

    capture.endFrame();
    assertFalse(capture.hasActiveFrame());
  }

  @Test
  void unresolvedOperationDoesNotCountAsCapturedForSuppression() {
    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(
        new IrisGlStateTracker());
    capture.beginFrame();

    assertTrue(capture.memoryBarrier(1));
    assertFalse(capture.generateMipmaps(999, GL_TEXTURE_2D));

    capture.endFrame();
    assertEquals(1, capture.poll().orElseThrow().events().size());
  }

  @Test
  void overflowedFrameDoesNotReportResourceOperationsAsCaptured() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerFramebuffer(7);
    tracker.defineTexture(70, "rgba8-unorm", 1, 32, 16, 1, 1);
    tracker.defineTexture(71, "d32-float", 1, 32, 16, 1, 1);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_READ_FRAMEBUFFER, 7);
    tracker.bindTextureToUnit(GL_TEXTURE_2D, 0, 70);

    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(tracker);
    capture.beginFrame();
    for (int index = 0;
        index < IrisRenderGraphCapture.MAX_EVENTS_PER_FRAME; index++) {
      assertTrue(capture.memoryBarrier(index));
    }
    assertFalse(capture.memoryBarrier(IrisRenderGraphCapture.MAX_EVENTS_PER_FRAME));
    assertFalse(capture.hasActiveFrame());

    assertFalse(capture.copyBoundTexture(GL_TEXTURE_2D, 0, 0,
        0, 0, 1, 1, 0, 0, IrisGlStateTracker.GL_COLOR_ATTACHMENT0));
    assertFalse(capture.clearDepthTexture(71, 1.0, Optional.empty()));

    capture.endFrame();
    assertEquals(IrisRenderGraphCapture.MAX_EVENTS_PER_FRAME,
        capture.poll().orElseThrow().events().size());
  }

  @Test
  void fullReplayReservationBypassesRegularFrameSamplingInterval() {
    AtomicLong now = new AtomicLong(1_000_000_000L);
    AtomicBoolean fullReplay = new AtomicBoolean();
    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(
        new IrisGlStateTracker(), now::get, fullReplay::get);

    capture.beginFrame();
    assertEquals(1, capture.framesStarted());
    assertFalse(capture.captureFullGraphReplay());
    capture.endFrame();

    now.addAndGet(1_000_000L);
    capture.beginFrame();
    assertEquals(1, capture.framesStarted());

    fullReplay.set(true);
    capture.beginFrame();
    assertEquals(2, capture.framesStarted());
    assertTrue(capture.captureFullGraphReplay());
  }

  @Test
  void fullReplayByteBoundChargesUniqueRetainedPayloadOnce() {
    IrisGlTextureMirror.TextureMetadata metadata =
        new IrisGlTextureMirror.TextureMetadata("rgba8-unorm",
            2, 1, 1, 4, 1);
    IrisGlTextureMirror.TextureSnapshot inline =
        IrisGlTextureMirror.TextureSnapshot.fromReadback(
            41, 1, metadata, 0, 0,
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    IrisGlTextureMirror.TextureSnapshot shared =
        IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
            42, 1, "rgba8-unorm", 2, 1, 4, 901);

    assertEquals(8, IrisRenderGraphCapture.retainedFullReplayBytes(
        new IrisShadowReplayBufferSnapshot.RetainedCapture(),
        Map.of(41, inline, 42, shared)));
  }

  @Test
  void fullReplayRequiresEveryDrawResourceCaptureToBeComplete() {
    IrisShadowReplayBufferSnapshot completeBuffers =
        new IrisShadowReplayBufferSnapshot(true, List.of(), List.of(),
            Optional.empty(), Optional.empty(), Map.of(), Map.of(),
            List.of());
    IrisShadowReplayTextureSnapshot completeTextures =
        IrisShadowReplayTextureSnapshot.emptyEnabled();
    IrisShadowReplaySamplerSnapshot completeSamplers =
        IrisShadowReplaySamplerSnapshot.emptyEnabled();

    assertTrue(IrisRenderGraphCapture.fullReplayResourcesComplete(
        completeBuffers, completeTextures, completeSamplers));
    assertFalse(IrisRenderGraphCapture.fullReplayResourcesComplete(
        new IrisShadowReplayBufferSnapshot(true, List.of(), List.of(),
            Optional.empty(), Optional.empty(), Map.of(), Map.of(),
            List.of("vertex-buffer-snapshot-unavailable")),
        completeTextures, completeSamplers));
    assertFalse(IrisRenderGraphCapture.fullReplayResourcesComplete(
        completeBuffers,
        new IrisShadowReplayTextureSnapshot(true, Map.of(),
            List.of("sampled-texture-snapshot-unavailable")),
        completeSamplers));
    assertEquals("graph-frame-capture-backpressure",
        IrisRenderGraphCapture.fullReplayResourceAbortReason(
            completeBuffers,
            new IrisShadowReplayTextureSnapshot(true, Map.of(),
                List.of("sampled-texture-gpu-handoff-backpressure")),
            completeSamplers));
    assertFalse(IrisRenderGraphCapture.fullReplayResourcesComplete(
        completeBuffers, completeTextures,
        new IrisShadowReplaySamplerSnapshot(true, Map.of(),
            List.of("sampler-state-parameter-unsupported"))));
    assertEquals("graph-frame-resource-capture-incomplete",
        IrisRenderGraphCapture.fullReplayResourceAbortReason(
            completeBuffers, completeTextures,
            new IrisShadowReplaySamplerSnapshot(true, Map.of(),
                List.of("sampler-state-parameter-unsupported"))));
  }

  @Test
  void boundsRealResourceSamplesPerEligiblePhase() {
    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(
        new IrisGlStateTracker());
    capture.beginFrame();
    capture.phase(IrisRenderGraph.Phase.SHADOW);
    for (int index = 0;
         index < IrisRenderGraphCapture.MAX_REPLAY_SAMPLES_PER_PHASE;
         index++) {
      assertTrue(capture.reserveShadowReplaySample());
    }
    assertFalse(capture.reserveShadowReplaySample());
    capture.phase(IrisRenderGraph.Phase.PREPARE);
    assertFalse(capture.reserveShadowReplaySample());
    capture.phase(IrisRenderGraph.Phase.FINAL);
    assertTrue(capture.reserveShadowReplaySample());
    capture.resetShadowReplaySamples();
    capture.phase(IrisRenderGraph.Phase.SHADOW);
    assertTrue(capture.reserveShadowReplaySample());
  }

  @Test
  void retainsRepeatedDrawsButStillCoalescesRepeatedBarriers() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerProgram(5);
    tracker.useProgram(5);
    IrisProgramIdentityRegistry identities =
        new IrisProgramIdentityRegistry(2);
    IrisProgramIdentityRegistry.Registration registration =
        identities.register(5,
            new IrisProgramIdentityRegistry.ProgramDescriptor(
                PassKind.LINKED_GRAPHICS, "capture", false,
                List.of(new VertexBufferLayout(0, 12,
                    StepFunction.PER_VERTEX, 0)),
                List.of(new VertexAttribute(0, 0, 0,
                    new DataFormat("rgb32-float")))));
    IrisPipelineStateCapture.PendingState pending =
        new IrisPipelineStateCapture.PendingState(registration,
            tracker.snapshotDraw(4),
            new IrisGlResourceBindingSnapshot(5, Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()),
            new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
                IrisExecutionCommand.Source.DIRECT_GL),
            new IrisDynamicDrawState(
                IrisGlStateSnapshot.StateValue.known(
                    new IrisDynamicDrawState.Rect(0, 0, 16, 16)),
                IrisGlStateSnapshot.StateValue.known(false),
                IrisGlStateSnapshot.StateValue.known(
                    new IrisDynamicDrawState.Rect(0, 0, 0, 0))),
            IrisVertexInputBindings.complete(List.of(
                new IrisVertexInputBindings.BufferSlice(0, 7, 0, 36)),
                null));

    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(tracker);
    capture.beginFrame();
    capture.draw(pending);
    capture.draw(pending);
    capture.memoryBarrier(1);
    capture.memoryBarrier(1);
    capture.endFrame();

    IrisRenderGraphCapture.PendingFrame frame =
        capture.poll().orElseThrow();
    assertEquals(3, frame.events().size());
    assertEquals(2, frame.events().stream()
        .filter(IrisRenderGraphCapture.RawDraw.class::isInstance).count());
  }

  @Test
  void resolvesNamedFramebufferBlitsToAllocatedTextureResources() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.registerFramebuffer(7);
    tracker.registerFramebuffer(8);
    tracker.defineTexture(70, "rgba16-float", 1, 1280, 720, 1, 1);
    tracker.defineTexture(80, "rgba16-float", 1, 1280, 720, 1, 1);
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 70, 0));
    assertTrue(tracker.framebufferTexture2DForFramebuffer(8,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 80, 0));

    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(tracker);
    capture.beginFrame();
    capture.phase(IrisRenderGraph.Phase.COMPOSITE);
    capture.blitFramebuffer(7, 8, 0, 0, 1280, 720,
        0, 0, 1280, 720, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    capture.endFrame();

    IrisRenderGraphCapture.RawTransfer transfer =
        (IrisRenderGraphCapture.RawTransfer) capture.poll().orElseThrow()
            .events().getFirst();
    assertEquals(1, transfer.sources().size());
    assertEquals(1, transfer.destinations().size());
    assertEquals(IrisGlStateSnapshot.ResourceKind.TEXTURE,
        transfer.sources().getFirst().handle().kind());
    assertEquals(70, transfer.sources().getFirst().handle().name());
    assertEquals(80, transfer.destinations().getFirst().handle().name());
    assertEquals(1280, transfer.destinations().getFirst().width());
    assertTrue(transfer.command().complete());
    IrisTransferCommand.BlitFramebuffer command =
        (IrisTransferCommand.BlitFramebuffer) transfer.command();
    assertEquals(1280, command.sourceX1());
    assertEquals(GL_NEAREST, command.filter());
  }

  @Test
  void retainsExactClearAndCopyCommandsForNativeExecution() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerFramebuffer(7);
    tracker.registerFramebuffer(8);
    tracker.defineTexture(70, "rgba16-float", 1, 64, 32, 1, 1);
    tracker.defineTexture(80, "rgba16-float", 1, 64, 32, 1, 1);
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 70, 0));
    assertTrue(tracker.framebufferTexture2DForFramebuffer(8,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 80, 0));
    tracker.bindFramebuffer(IrisGlStateTracker.GL_READ_FRAMEBUFFER, 7);

    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(tracker);
    capture.beginFrame();
    capture.phase(IrisRenderGraph.Phase.COMPOSITE);
    capture.clearNamedFramebufferFloat(8, IrisClearCommand.GL_COLOR, 0,
        new float[] {0.25F, 0.5F, 0.75F, 1.0F});
    capture.clearNamedFramebufferFloat(8, IrisClearCommand.GL_COLOR, 0,
        new float[] {0.25F, 0.5F, 0.75F, 1.0F});
    capture.copyTexture(80, GL_TEXTURE_2D, 0, 4, 5, 6, 7, 8, 9,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0);
    capture.endFrame();

    List<IrisRenderGraphCapture.RawEvent> events = capture.poll()
        .orElseThrow().events();
    assertEquals(3, events.size());
    IrisRenderGraphCapture.RawClear clear =
        (IrisRenderGraphCapture.RawClear) events.get(0);
    assertEquals(8, clear.command().target().name());
    assertEquals(4, clear.command().rawValues().size());
    IrisRenderGraphCapture.RawClear repeatedClear =
        (IrisRenderGraphCapture.RawClear) events.get(1);
    assertEquals(8, repeatedClear.command().target().name());
    IrisRenderGraphCapture.RawTransfer transfer =
        (IrisRenderGraphCapture.RawTransfer) events.get(2);
    IrisTransferCommand.CopyTexSubImage2D copy =
        (IrisTransferCommand.CopyTexSubImage2D) transfer.command();
    assertEquals(7, copy.sourceFramebuffer().name());
    assertEquals(70, copy.sourceTexture().name());
    assertEquals(80, copy.destinationTexture().name());
    assertEquals(4, copy.destinationX());
    assertEquals(9, copy.height());
  }

  @Test
  void capturesLegacyDepthAndStencilClearValues() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerFramebuffer(7);
    tracker.defineTexture(71, "d32-float", 1, 64, 32, 1, 1);
    tracker.defineTexture(72, "s8-uint", 1, 64, 32, 1, 1);
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 71, 0));
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_STENCIL_ATTACHMENT, GL_TEXTURE_2D, 72, 0));
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER, 7);

    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(tracker);
    capture.beginFrame();
    capture.legacyClearDepth(0.375D);
    capture.legacyClearStencil(173);
    assertTrue(capture.legacyClearBoundFramebuffer(
        GL_DEPTH_BUFFER_BIT | 0x00000400));
    capture.endFrame();

    List<IrisRenderGraphCapture.RawEvent> events = capture.poll()
        .orElseThrow().events();
    assertEquals(2, events.size());
    IrisRenderGraphCapture.RawClear depth =
        (IrisRenderGraphCapture.RawClear) events.get(0);
    assertEquals(Double.doubleToRawLongBits(0.375D),
        depth.command().rawValues().getFirst());
    IrisRenderGraphCapture.RawClear stencil =
        (IrisRenderGraphCapture.RawClear) events.get(1);
    assertEquals(Integer.toUnsignedLong(173),
        stencil.command().rawValues().getFirst());
  }

  @Test
  void capturesLegacyBoundFramebufferClearAsTextureOperations() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerFramebuffer(7);
    tracker.defineTexture(70, "rgba8-unorm", 1, 64, 32, 1, 1);
    tracker.defineTexture(71, "d32-float", 1, 64, 32, 1, 1);
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 70, 0));
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 71, 0));
    assertTrue(tracker.drawBuffersForFramebuffer(7,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0));
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER, 7);

    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(tracker);
    capture.beginFrame();
    capture.legacyClearColor(0.25F, 0.5F, 0.75F, 1.0F);
    capture.legacyClearBoundFramebuffer(
        GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    capture.endFrame();

    List<IrisRenderGraphCapture.RawEvent> events = capture.poll()
        .orElseThrow().events();
    assertEquals(2, events.size());
    IrisRenderGraphCapture.RawClear color =
        (IrisRenderGraphCapture.RawClear) events.get(0);
    IrisRenderGraphCapture.RawClear depth =
        (IrisRenderGraphCapture.RawClear) events.get(1);
    assertEquals(70, color.command().target().name());
    assertEquals(Integer.toUnsignedLong(
        Float.floatToRawIntBits(0.25F)), color.command().rawValues().get(0));
    assertEquals(71, depth.command().target().name());
    assertEquals(Double.doubleToRawLongBits(1.0),
        depth.command().rawValues().getFirst());
  }

  @Test
  void partialLegacyClearIsNotEligibleForOpenGlSuppression() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerFramebuffer(9);
    tracker.defineTexture(90, "rgba8-unorm", 1, 32, 16, 1, 1);
    assertTrue(tracker.framebufferTexture2DForFramebuffer(9,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 90, 0));
    assertTrue(tracker.drawBuffersForFramebuffer(9,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0));
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER, 9);

    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(
        tracker, () -> 1_000_000_000L, () -> false);
    capture.beginFrame();
    capture.legacyClearColor(0.0F, 0.0F, 0.0F, 1.0F);

    assertFalse(capture.legacyClearBoundFramebuffer(
        GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT));
    capture.endFrame();

    IrisRenderGraphCapture.PendingFrame frame =
        capture.poll().orElseThrow();
    assertEquals(1, frame.events().size());
  }

  @Test
  void routesDepthTextureCopyFromDepthAttachmentInsteadOfReadColor() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerFramebuffer(7);
    tracker.defineTexture(70, "rgba8-unorm", 1, 64, 32, 1, 1);
    tracker.defineTexture(71, "d32-float", 1, 64, 32, 1, 1);
    tracker.defineTexture(80, "d32-float", 1, 64, 32, 1, 1);
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 70, 0));
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 71, 0));
    tracker.bindFramebuffer(IrisGlStateTracker.GL_READ_FRAMEBUFFER, 7);

    IrisRenderGraphCapture capture = new IrisRenderGraphCapture(tracker);
    capture.beginFrame();
    capture.phase(IrisRenderGraph.Phase.COMPOSITE);
    capture.copyTexture(80, GL_TEXTURE_2D, 0, 0, 0, 0, 0, 64, 32,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0);
    capture.endFrame();

    IrisRenderGraphCapture.RawTransfer transfer =
        (IrisRenderGraphCapture.RawTransfer) capture.poll().orElseThrow()
            .events().getFirst();
    assertEquals(1, transfer.sources().size());
    assertEquals(71, transfer.sources().getFirst().handle().name());
    assertEquals("d32-float", transfer.sources().getFirst().format());
    IrisTransferCommand.CopyTexSubImage2D copy =
        (IrisTransferCommand.CopyTexSubImage2D) transfer.command();
    assertEquals(71, copy.sourceTexture().name());
    assertEquals(80, copy.destinationTexture().name());
  }
}

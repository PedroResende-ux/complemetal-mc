package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ColorMask;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisGlStateTrackerTest {
  private static final int GL_TEXTURE_2D = 0x0DE1;
  private static final int GL_TRIANGLES = 0x0004;
  private static final int GL_FUNC_ADD = 0x8006;
  private static final int GL_FUNC_SUBTRACT = 0x800A;
  private static final int GL_ONE = 1;
  private static final int GL_ZERO = 0;
  private static final int GL_SRC_ALPHA = 0x0302;
  private static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
  private static final int GL_LEQUAL = 0x0203;
  private static final int GL_ALWAYS = 0x0207;
  private static final int GL_EQUAL = 0x0202;
  private static final int GL_KEEP = 0x1E00;
  private static final int GL_REPLACE = 0x1E01;
  private static final int GL_CCW = 0x0901;
  private static final int GL_FILL = 0x1B02;

  @Test
  void mergesGlobalStateWithFieldWiseIndexedOverrides() {
    IrisGlStateTracker tracker = completeGraphicsTracker(1);
    tracker.capability(IrisGlStateTracker.GL_BLEND, true);
    tracker.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
    tracker.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,
        GL_ONE, GL_ZERO);
    tracker.colorMask(true, true, true, true);

    tracker.capabilityIndexed(IrisGlStateTracker.GL_BLEND, 1, false);
    tracker.blendEquationSeparateIndexed(0, GL_FUNC_SUBTRACT, GL_FUNC_ADD);
    tracker.colorMaskIndexed(1, true, false, false, true);

    IrisGlStateSnapshot snapshot = tracker.snapshotDraw(GL_TRIANGLES);
    assertTrue(snapshot.complete(), snapshot.unknownFields().toString());
    assertTrue(snapshot.colorTargets().get(0).blend().enabled().value());
    assertEquals(GL_FUNC_SUBTRACT,
        snapshot.colorTargets().get(0).blend().rgbEquation().value());
    assertFalse(snapshot.colorTargets().get(1).blend().enabled().value());
    assertEquals(new ColorMask(true, false, false, true),
        snapshot.colorTargets().get(1).colorMask().value());
    assertEquals(GL_SRC_ALPHA,
        snapshot.colorTargets().get(1).blend().sourceRgb().value());

    // A non-indexed call updates that component for every draw buffer, while
    // unrelated indexed factor/mask fields remain independent.
    tracker.blendEquation(GL_FUNC_ADD);
    IrisGlStateSnapshot afterGlobal = tracker.snapshotDraw(GL_TRIANGLES);
    assertEquals(GL_FUNC_ADD,
        afterGlobal.colorTargets().get(0).blend().rgbEquation().value());
    assertFalse(afterGlobal.colorTargets().get(1).blend().enabled().value());
    assertEquals(new ColorMask(true, false, false, true),
        afterGlobal.colorTargets().get(1).colorMask().value());
  }

  @Test
  void recordsFullStencilMultisampleAndPrimitiveState() {
    IrisGlStateTracker tracker = completeGraphicsTracker(4);
    tracker.stencilFuncSeparate(IrisGlStateTracker.GL_FRONT, GL_EQUAL, 7,
        0x7F);
    tracker.stencilOpSeparate(IrisGlStateTracker.GL_FRONT, GL_REPLACE,
        GL_KEEP, GL_REPLACE);
    tracker.stencilMaskSeparate(IrisGlStateTracker.GL_FRONT, 0x0F);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_COVERAGE, true);
    tracker.sampleCoverage(0.5F, true);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_MASK, true);
    tracker.sampleMask(0, 0x0F0F0F0F);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_ALPHA_TO_COVERAGE, true);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_ALPHA_TO_ONE, false);
    tracker.capability(IrisGlStateTracker.GL_PRIMITIVE_RESTART, true);
    tracker.capability(IrisGlStateTracker.GL_PRIMITIVE_RESTART_FIXED_INDEX,
        false);

    IrisGlStateSnapshot snapshot = tracker.snapshotDraw(GL_TRIANGLES);
    assertTrue(snapshot.complete(), snapshot.unknownFields().toString());
    assertEquals(4, snapshot.multisample().rasterSampleCount().value());
    assertEquals(0.5F, snapshot.multisample().sampleCoverageValue().value());
    assertTrue(snapshot.multisample().sampleCoverageInvert().value());
    assertEquals(0x0F0F0F0F,
        snapshot.multisample().sampleMaskWord0().value());
    assertTrue(snapshot.multisample().alphaToCoverageEnabled().value());
    assertTrue(snapshot.primitive().restartEnabled().value());
    assertFalse(snapshot.primitive().fixedIndexRestartEnabled().value());
    assertEquals(GL_EQUAL, snapshot.stencil().front().function().value());
    assertEquals(7, snapshot.stencil().front().reference().value());
    assertEquals(0x0F, snapshot.stencil().front().writeMask().value());
    assertEquals(GL_ALWAYS, snapshot.stencil().back().function().value());
    assertEquals(1, snapshot.stencil().back().reference().value());
    assertEquals(0xFF, snapshot.stencil().back().writeMask().value());
  }

  @Test
  void framebufferAndTextureNamesAreGenerationSafe() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    ResourceHandle oldFramebuffer = tracker.registerFramebuffer(7);
    ResourceHandle oldTexture = tracker.registerTexture(11, "rgba8-unorm", 1);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        oldFramebuffer);
    assertTrue(tracker.framebufferTexture2D(
        IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 11, 0));
    tracker.drawBuffers(IrisGlStateTracker.GL_COLOR_ATTACHMENT0);

    assertTrue(tracker.deleteTexture(oldTexture));
    ResourceHandle newTexture = tracker.registerTexture(11, "rgba16-float", 4);
    assertNotEquals(oldTexture.generation(), newTexture.generation());
    IrisGlStateSnapshot oldAttachment = tracker.snapshotDraw(GL_TRIANGLES);
    assertEquals("rgba8-unorm", oldAttachment.colorTargets().get(0)
        .attachment().value().orElseThrow().format());
    assertEquals(oldTexture, oldAttachment.colorTargets().get(0)
        .attachment().value().orElseThrow().texture());
    assertFalse(tracker.deleteTexture(oldTexture));

    assertTrue(tracker.deleteFramebuffer(oldFramebuffer));
    ResourceHandle newFramebuffer = tracker.registerFramebuffer(7);
    assertNotEquals(oldFramebuffer.generation(), newFramebuffer.generation());
    assertFalse(tracker.resetFramebuffer(oldFramebuffer));
    assertFalse(tracker.deleteFramebuffer(oldFramebuffer));
    assertTrue(tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        newFramebuffer));
    tracker.drawBuffers(IrisGlStateTracker.GL_COLOR_ATTACHMENT0);
    IrisGlStateSnapshot cleanFramebuffer = tracker.snapshotDraw(GL_TRIANGLES);
    assertEquals(Optional.empty(), cleanFramebuffer.colorTargets().get(0)
        .attachment().value());

    long oldContext = newFramebuffer.contextGeneration();
    tracker.resetContext();
    ResourceHandle afterReset = tracker.registerFramebuffer(7);
    assertNotEquals(oldContext, afterReset.contextGeneration());
    assertFalse(tracker.deleteFramebuffer(newFramebuffer));
  }

  @Test
  void reportsUnknownStateAndDoesNotApplyImplicitGlDefaults() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    IrisGlStateSnapshot draw = tracker.snapshotDraw(GL_TRIANGLES);
    assertFalse(draw.complete());
    assertFalse(draw.program().isKnown());
    assertFalse(draw.depth().testEnabled().isKnown());
    assertFalse(draw.multisample().alphaToCoverageEnabled().isKnown());
    assertTrue(draw.unknownFields().stream()
        .anyMatch(value -> value.startsWith("program:")));
    assertTrue(draw.unknownFields().stream()
        .anyMatch(value -> value.startsWith("depth.testEnabled:")));

    tracker.registerProgram(3);
    tracker.useProgram(3);
    IrisGlStateSnapshot dispatch = tracker.snapshotDispatch();
    assertTrue(dispatch.complete(), dispatch.unknownFields().toString());

    tracker.useProgram(99);
    IrisGlStateSnapshot unknownProgram = tracker.snapshotDispatch();
    assertFalse(unknownProgram.complete());
    assertTrue(unknownProgram.program().unknownReason()
        .contains("has not been registered"));
  }

  @Test
  void explicitOpenGlDefaultsAreDistinctFromFailClosedReset() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    IrisGlStateSnapshot defaults = tracker.snapshotDraw(GL_TRIANGLES);
    assertTrue(defaults.program().isKnown());
    assertEquals(Optional.empty(), defaults.program().value());
    assertEquals(0, defaults.drawFramebuffer().value().name());
    assertFalse(defaults.colorTargets().get(0).blend().enabled().value());
    assertFalse(defaults.depth().testEnabled().value());
    assertTrue(defaults.depth().writeEnabled().value());
    assertFalse(defaults.stencil().testEnabled().value());
    assertFalse(defaults.raster().depthClampEnabled().value());
    assertFalse(defaults.raster().rasterizerDiscardEnabled().value());
    assertFalse(defaults.multisample().sampleMaskEnabled().value());
    assertFalse(defaults.primitive().restartEnabled().value());
    // The GL defaults do not reveal the platform default framebuffer format.
    assertFalse(defaults.colorTargets().get(0).attachment().isKnown());

    tracker.resetContext();
    IrisGlStateSnapshot reset = tracker.snapshotDraw(GL_TRIANGLES);
    assertFalse(reset.program().isKnown());
    assertFalse(reset.depth().testEnabled().isKnown());
    assertFalse(reset.primitive().restartEnabled().isKnown());
  }

  @Test
  void newCustomFramebufferUsesTheOpenGlColorAttachmentZeroDefault() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    ResourceHandle framebuffer = tracker.registerFramebuffer(7);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        framebuffer);

    IrisGlStateSnapshot snapshot = tracker.snapshotDraw(GL_TRIANGLES);
    assertTrue(snapshot.drawBuffers().isKnown());
    assertEquals(
        java.util.List.of(IrisGlStateTracker.GL_COLOR_ATTACHMENT0),
        snapshot.drawBuffers().value());
    assertEquals(IrisGlStateTracker.GL_COLOR_ATTACHMENT0,
        snapshot.colorTargets().getFirst().drawBuffer().value());
  }

  @Test
  void dsaUpdatesDoNotRebindAndTextureRedefinitionKeepsGeneration() {
    IrisGlStateTracker tracker = completeGraphicsTracker(1);
    ResourceHandle dsaFramebuffer = tracker.registerFramebuffer(20);
    ResourceHandle texture = tracker.defineTexture(30, "rgba8-unorm", 1);
    assertTrue(tracker.framebufferTexture2DForFramebuffer(20,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 30, 0));
    assertTrue(tracker.drawBuffersForFramebuffer(20,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0));

    // The DSA calls operated on FBO 20; FBO 7 remains current.
    assertEquals(7, tracker.snapshotDraw(GL_TRIANGLES)
        .drawFramebuffer().value().name());
    ResourceHandle redefined = tracker.defineTexture(30, "rgba16-float", 4);
    assertEquals(texture, redefined);

    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        dsaFramebuffer);
    IrisGlStateSnapshot snapshot = tracker.snapshotDraw(GL_TRIANGLES);
    assertEquals("rgba16-float", snapshot.colorTargets().get(0)
        .attachment().value().orElseThrow().format());
    assertEquals(4, snapshot.multisample().rasterSampleCount().value());
  }

  @Test
  void retainsExactTextureAllocationMetadataAcrossAttachmentLookup() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    ResourceHandle texture = tracker.defineTexture(44, "rgba16-float", 1,
        1920, 1080, 1, 6);
    IrisGlStateTracker.TextureMetadata metadata =
        tracker.textureMetadata(texture).orElseThrow();

    assertTrue(metadata.complete());
    assertEquals(1920, metadata.width());
    assertEquals(1080, metadata.height());
    assertEquals(6, metadata.mipLevels());
    assertTrue(tracker.deleteTexture(texture));
    assertTrue(tracker.textureMetadata(texture).isEmpty());
  }

  @Test
  void exposesCompleteGenerationSafeFramebufferAttachmentMetadata() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.registerFramebuffer(7);
    ResourceHandle color = tracker.defineTexture(44, "rgba16-float", 1,
        1920, 1080, 1, 1);
    assertTrue(tracker.framebufferTexture2DForFramebuffer(7,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 44, 0));

    IrisGlStateTracker.FramebufferMetadata metadata =
        tracker.framebufferMetadata(7).orElseThrow();
    assertTrue(metadata.complete());
    assertEquals(1, metadata.attachments().size());
    assertEquals(IrisGlStateTracker.GL_COLOR_ATTACHMENT0,
        metadata.attachments().getFirst().attachment());
    assertEquals(color,
        metadata.attachments().getFirst().texture().handle());
    assertEquals(1920, metadata.attachments().getFirst().texture().width());

    assertTrue(tracker.deleteTexture(color));
    assertFalse(tracker.framebufferMetadata(7).orElseThrow().complete());
    assertFalse(tracker.framebufferMetadata(0).orElseThrow().complete());
  }

  @Test
  void tracksReadAndDrawFramebufferBindingsIndependently() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    ResourceHandle read = tracker.registerFramebuffer(7);
    ResourceHandle draw = tracker.registerFramebuffer(8);
    ResourceHandle texture = tracker.defineTexture(44, "rgba8-unorm", 1,
        32, 16, 1, 1);

    tracker.bindFramebuffer(IrisGlStateTracker.GL_READ_FRAMEBUFFER, read);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER, draw);
    assertEquals(read, tracker.readFramebufferHandle().orElseThrow());
    assertEquals(draw, tracker.drawFramebufferHandle().orElseThrow());
    assertTrue(tracker.framebufferTexture2D(
        IrisGlStateTracker.GL_READ_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 44, 0));
    assertEquals(texture, tracker.framebufferMetadata(7).orElseThrow()
        .attachments().getFirst().texture().handle());

    assertTrue(tracker.deleteFramebuffer(read));
    assertEquals(0, tracker.readFramebufferHandle().orElseThrow().name());
    assertEquals(draw, tracker.drawFramebufferHandle().orElseThrow());

    tracker.bindFramebuffer(IrisGlStateTracker.GL_FRAMEBUFFER, 8);
    assertEquals(draw, tracker.readFramebufferHandle().orElseThrow());
    assertEquals(draw, tracker.drawFramebufferHandle().orElseThrow());
  }

  @Test
  void isBoundedAndRejectsMoreThanEightColorTargets() {
    IrisGlStateTracker tracker = new IrisGlStateTracker(1, 1, 1);
    tracker.registerProgram(1);
    tracker.registerProgram(2);
    tracker.useProgram(1);
    assertEquals(1, tracker.resourceEvictions());
    assertFalse(tracker.snapshotDispatch().complete());

    ResourceHandle framebuffer = tracker.registerFramebuffer(1);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        framebuffer);
    assertThrows(IllegalArgumentException.class,
        () -> tracker.capabilityIndexed(IrisGlStateTracker.GL_BLEND, 8, true));
    assertThrows(IllegalArgumentException.class,
        () -> tracker.drawBuffers(1, 2, 3, 4, 5, 6, 7, 8, 9));
  }

  @Test
  void rejectsInconsistentOrUnknownAttachmentSampleCounts() {
    IrisGlStateTracker tracker = completeGraphicsTracker(1);
    ResourceHandle framebuffer = tracker.registerFramebuffer(20);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        framebuffer);
    tracker.registerTexture(30, "rgba8-unorm", 1);
    tracker.registerTexture(31, "rgba8-unorm", 4);
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 30, 0);
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + 1, GL_TEXTURE_2D, 31, 0);
    tracker.drawBuffers(IrisGlStateTracker.GL_COLOR_ATTACHMENT0,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + 1);

    IrisGlStateSnapshot mismatch = tracker.snapshotDraw(GL_TRIANGLES);
    assertFalse(mismatch.multisample().rasterSampleCount().isKnown());
    assertTrue(mismatch.multisample().rasterSampleCount().unknownReason()
        .contains("inconsistent"));
    assertFalse(mismatch.complete());

    tracker.resetFramebuffer(framebuffer);
    tracker.registerTextureUnknownSampleCount(32, "rgba8-unorm",
        "texture storage sample count was not captured");
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 32, 0);
    tracker.drawBuffers(IrisGlStateTracker.GL_COLOR_ATTACHMENT0);
    IrisGlStateSnapshot unknown = tracker.snapshotDraw(GL_TRIANGLES);
    assertFalse(unknown.multisample().rasterSampleCount().isKnown());
    assertTrue(unknown.multisample().rasterSampleCount().unknownReason()
        .contains("was not captured"));
  }

  private static IrisGlStateTracker completeGraphicsTracker(int sampleCount) {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.registerProgram(3);
    tracker.useProgram(3);
    ResourceHandle framebuffer = tracker.registerFramebuffer(7);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        framebuffer);
    tracker.registerTexture(10, "rgba8-unorm", sampleCount);
    tracker.registerTexture(11, "rgba16-float", sampleCount);
    tracker.registerTexture(12, "depth32-float-stencil8", sampleCount);
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 10, 0);
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + 1, GL_TEXTURE_2D, 11, 0);
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, 12, 0);
    tracker.drawBuffers(IrisGlStateTracker.GL_COLOR_ATTACHMENT0,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + 1);

    tracker.capability(IrisGlStateTracker.GL_BLEND, false);
    tracker.blendEquation(GL_FUNC_ADD);
    tracker.blendFunc(GL_ONE, GL_ZERO);
    tracker.colorMask(true, true, true, true);
    tracker.capability(IrisGlStateTracker.GL_DEPTH_TEST, true);
    tracker.depthFunc(GL_LEQUAL);
    tracker.depthMask(true);
    tracker.capability(IrisGlStateTracker.GL_STENCIL_TEST, true);
    tracker.stencilFunc(GL_ALWAYS, 1, 0xFF);
    tracker.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    tracker.stencilMask(0xFF);
    tracker.capability(IrisGlStateTracker.GL_CULL_FACE, false);
    tracker.capability(IrisGlStateTracker.GL_DEPTH_CLAMP, false);
    tracker.capability(IrisGlStateTracker.GL_RASTERIZER_DISCARD, false);
    tracker.cullFace(IrisGlStateTracker.GL_BACK);
    tracker.frontFace(GL_CCW);
    tracker.polygonMode(IrisGlStateTracker.GL_FRONT_AND_BACK, GL_FILL);
    tracker.capability(IrisGlStateTracker.GL_POLYGON_OFFSET_POINT, false);
    tracker.capability(IrisGlStateTracker.GL_POLYGON_OFFSET_LINE, false);
    tracker.capability(IrisGlStateTracker.GL_POLYGON_OFFSET_FILL, false);
    tracker.polygonOffset(0.0F, 0.0F);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_COVERAGE, false);
    tracker.sampleCoverage(1.0F, false);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_MASK, false);
    tracker.sampleMask(0, -1);
    tracker.sampleMask(1, -1);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_ALPHA_TO_COVERAGE, false);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_ALPHA_TO_ONE, false);
    tracker.capability(IrisGlStateTracker.GL_PRIMITIVE_RESTART, false);
    tracker.capability(IrisGlStateTracker.GL_PRIMITIVE_RESTART_FIXED_INDEX,
        false);
    return tracker;
  }
}

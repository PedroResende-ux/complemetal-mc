package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendFactor;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.CompareOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.CullMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DepthClipMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.FillMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.FrontFace;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.FunctionConstant;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PrimitiveRestartMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PrimitiveTopology;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.ScalarType;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StencilOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateMapper.Complete;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateMapper.Unsupported;
import com.pebbles_boon.metalrender.compat.iris.IrisProgramIdentityRegistry.ProgramDescriptor;
import com.pebbles_boon.metalrender.compat.iris.IrisProgramIdentityRegistry.ResolvedProgram;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisPipelineStateMapperTest {
  private static final int PROGRAM = 31;
  private static final int GL_TEXTURE_2D = 0x0DE1;
  private static final int GL_FRONT = 0x0404;
  private static final int GL_BACK = 0x0405;
  private static final int GL_FRONT_AND_BACK = 0x0408;
  private static final int GL_CW = 0x0900;
  private static final int GL_POINT = 0x1B00;
  private static final int GL_LINE = 0x1B01;
  private static final int GL_TRIANGLES = 0x0004;
  private static final int GL_TRIANGLE_STRIP = 0x0005;
  private static final int GL_TRIANGLE_FAN = 0x0006;
  private static final int GL_LINE_LOOP = 0x0002;
  private static final int GL_LEQUAL = 0x0203;
  private static final int GL_EQUAL = 0x0202;
  private static final int GL_GREATER = 0x0204;
  private static final int GL_REPLACE = 0x1E01;
  private static final int GL_INCR = 0x1E02;
  private static final int GL_DECR = 0x1E03;
  private static final int GL_INVERT = 0x150A;
  private static final int GL_INCR_WRAP = 0x8507;
  private static final int GL_DECR_WRAP = 0x8508;
  private static final int GL_FUNC_SUBTRACT = 0x800A;
  private static final int GL_FUNC_REVERSE_SUBTRACT = 0x800B;
  private static final int GL_SRC_ALPHA = 0x0302;
  private static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
  private static final int GL_CONSTANT_ALPHA = 0x8003;
  private static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;

  @Test
  void mapsCompleteGraphicsStateAndMergesActiveMrtSlots() {
    IrisGlStateTracker tracker = completeGraphicsTracker(4);
    tracker.capability(IrisGlStateTracker.GL_BLEND, false);
    tracker.capabilityIndexed(IrisGlStateTracker.GL_BLEND, 0, true);
    tracker.blendEquationSeparateIndexed(0, GL_FUNC_SUBTRACT,
        GL_FUNC_REVERSE_SUBTRACT);
    tracker.blendFuncSeparateIndexed(0, GL_SRC_ALPHA,
        GL_ONE_MINUS_SRC_ALPHA, GL_CONSTANT_ALPHA,
        GL_ONE_MINUS_CONSTANT_ALPHA);
    tracker.colorMaskIndexed(0, true, false, true, false);
    tracker.colorMaskIndexed(1, false, true, false, true);

    tracker.depthFunc(GL_LEQUAL);
    tracker.depthMask(false);
    tracker.stencilFuncSeparate(GL_FRONT, GL_EQUAL, 7, 0x7F);
    tracker.stencilOpSeparate(GL_FRONT, GL_REPLACE, GL_INCR, GL_DECR);
    tracker.stencilMaskSeparate(GL_FRONT, 0x0F);
    tracker.stencilFuncSeparate(GL_BACK, GL_GREATER, 9, 0x3F);
    tracker.stencilOpSeparate(GL_BACK, GL_INVERT, GL_INCR_WRAP,
        GL_DECR_WRAP);
    tracker.stencilMaskSeparate(GL_BACK, 0xF0);

    tracker.capability(IrisGlStateTracker.GL_CULL_FACE, true);
    tracker.cullFace(GL_FRONT_AND_BACK);
    tracker.frontFace(GL_CW);
    tracker.capability(IrisGlStateTracker.GL_DEPTH_CLAMP, true);
    tracker.polygonMode(GL_FRONT, GL_LINE);
    tracker.polygonMode(GL_BACK, GL_POINT);
    tracker.capability(IrisGlStateTracker.GL_POLYGON_OFFSET_POINT, true);
    tracker.capability(IrisGlStateTracker.GL_POLYGON_OFFSET_FILL, true);
    tracker.polygonOffsetClamp(2.0F, 3.0F, 4.0F);

    tracker.capability(IrisGlStateTracker.GL_SAMPLE_COVERAGE, true);
    tracker.sampleCoverage(0.5F, true);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_MASK, true);
    tracker.sampleMask(0, 0xA);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_ALPHA_TO_COVERAGE, true);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_ALPHA_TO_ONE, true);
    tracker.capability(IrisGlStateTracker.GL_PRIMITIVE_RESTART, true);
    tracker.capability(IrisGlStateTracker.GL_PRIMITIVE_RESTART_FIXED_INDEX,
        true);

    ResolvedProgram program = graphicsProgram(List.of(
        IrisShaderStage.VERTEX, IrisShaderStage.FRAGMENT));
    List<FunctionConstant> constants = List.of(
        new FunctionConstant(IrisShaderStage.VERTEX, 2, ScalarType.INT32, 3),
        new FunctionConstant(IrisShaderStage.FRAGMENT, 7,
            ScalarType.FLOAT32, 0x3f000000L));
    Complete mapped = assertInstanceOf(Complete.class,
        IrisPipelineStateMapper.map(
            tracker.snapshotDraw(GL_TRIANGLE_STRIP), program, constants));
    IrisPipelineState state = mapped.state();

    assertTrue(mapped.metalExecutionSupported());
    assertEquals(program.pass(), state.pass());
    assertEquals(program.descriptor().vertexBuffers(), state.vertexBuffers());
    assertEquals(program.descriptor().vertexAttributes(),
        state.vertexAttributes());
    // glDrawBuffers order, rather than attachment number, defines fragment
    // output/MRT slots. The fixture binds attachment 1 before attachment 0.
    assertEquals("rgba16-float",
        state.colorAttachments().get(0).format().cacheName());
    assertEquals("rgba8-unorm",
        state.colorAttachments().get(1).format().cacheName());
    assertEquals(0x5, state.colorAttachments().get(0).writeMask());
    assertEquals(0xA, state.colorAttachments().get(1).writeMask());
    assertTrue(state.colorAttachments().get(0).blend().enabled());
    assertEquals(BlendOperation.SUBTRACT,
        state.colorAttachments().get(0).blend().rgb().operation());
    assertEquals(BlendFactor.SRC_ALPHA,
        state.colorAttachments().get(0).blend().rgb().source());
    assertEquals(BlendOperation.REVERSE_SUBTRACT,
        state.colorAttachments().get(0).blend().alpha().operation());
    assertEquals(BlendFactor.CONSTANT_ALPHA,
        state.colorAttachments().get(0).blend().alpha().source());
    assertFalse(state.colorAttachments().get(1).blend().enabled());

    assertEquals("d32-float-s8-uint",
        state.depthAttachmentFormat().orElseThrow().cacheName());
    assertEquals("d32-float-s8-uint",
        state.stencilAttachmentFormat().orElseThrow().cacheName());
    assertEquals(4, state.rasterSampleCount());
    assertEquals(-6L, state.sampleMask());
    assertTrue(state.sampleCoverageEnabled());
    assertEquals(0.5F, state.sampleCoverageValue());
    assertTrue(state.sampleCoverageInvert());
    assertTrue(state.alphaToCoverage());
    assertTrue(state.alphaToOne());

    assertEquals(CompareOperation.LESS_EQUAL, state.depth().compare());
    assertFalse(state.depth().writeEnabled());
    assertEquals(CompareOperation.EQUAL,
        state.stencil().front().compare());
    assertEquals(StencilOperation.REPLACE,
        state.stencil().front().stencilFail());
    assertEquals(StencilOperation.INCREMENT_CLAMP,
        state.stencil().front().depthFail());
    assertEquals(StencilOperation.DECREMENT_CLAMP,
        state.stencil().front().pass());
    assertEquals(StencilOperation.INVERT,
        state.stencil().back().stencilFail());
    assertEquals(StencilOperation.INCREMENT_WRAP,
        state.stencil().back().depthFail());
    assertEquals(StencilOperation.DECREMENT_WRAP,
        state.stencil().back().pass());

    assertEquals(CullMode.FRONT_AND_BACK, state.raster().cullMode());
    assertEquals(FrontFace.CLOCKWISE, state.raster().frontFace());
    assertEquals(FillMode.LINES, state.raster().frontFillMode());
    assertEquals(FillMode.POINTS, state.raster().backFillMode());
    assertEquals(DepthClipMode.CLAMP, state.raster().depthClipMode());
    assertEquals(IrisPipelineStateMapper.POLYGON_OFFSET_POINT_BIT
            | IrisPipelineStateMapper.POLYGON_OFFSET_FILL_BIT,
        state.raster().polygonOffsetModeMask());
    assertEquals(3.0F, state.raster().depthBias());
    assertEquals(2.0F, state.raster().slopeScale());
    assertEquals(4.0F, state.raster().depthBiasClamp());
    assertEquals(PrimitiveTopology.TRIANGLE_STRIP,
        state.primitive().topology());
    assertEquals(PrimitiveRestartMode.BOTH,
        state.primitive().restartMode());
    assertEquals(constants, state.functionConstants());
  }

  @Test
  void combinesBothSampleMaskWordsAndCanonicalizesInactiveBits() {
    IrisGlStateTracker tracker = completeGraphicsTracker(40);
    tracker.capability(IrisGlStateTracker.GL_SAMPLE_MASK, true);
    tracker.sampleMask(0, 0x89ABCDEF);
    tracker.sampleMask(1, 0x12345678);

    Complete mapped = assertInstanceOf(Complete.class,
        IrisPipelineStateMapper.map(tracker.snapshotDraw(GL_TRIANGLES),
            graphicsProgram(List.of(IrisShaderStage.VERTEX,
                IrisShaderStage.FRAGMENT)), List.of()));
    long captured = 0x1234567889ABCDEFL;
    long activeBits = (1L << 40) - 1L;
    assertEquals(captured | ~activeBits, mapped.state().sampleMask());
  }

  @Test
  void buildsCanonicalComputeStateAndValidatesBoundProgram() {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.registerProgram(PROGRAM);
    tracker.useProgram(PROGRAM);
    ResolvedProgram program = computeProgram();
    FunctionConstant constant = new FunctionConstant(IrisShaderStage.COMPUTE,
        3, ScalarType.UINT32, 9);

    Complete mapped = assertInstanceOf(Complete.class,
        IrisPipelineStateMapper.map(tracker.snapshotDispatch(), program,
            List.of(constant)));
    IrisPipelineState state = mapped.state();
    assertEquals(PassKind.COMPUTE, state.pass().kind());
    assertTrue(state.vertexBuffers().isEmpty());
    assertTrue(state.colorAttachments().isEmpty());
    assertEquals(1, state.rasterSampleCount());
    assertEquals(-1L, state.sampleMask());
    assertFalse(state.depth().testEnabled());
    assertFalse(state.raster().rasterizationEnabled());
    assertEquals(PrimitiveTopology.NONE, state.primitive().topology());
    assertEquals(List.of(constant), state.functionConstants());

    tracker.registerProgram(PROGRAM + 1);
    tracker.useProgram(PROGRAM + 1);
    Unsupported mismatch = assertInstanceOf(Unsupported.class,
        IrisPipelineStateMapper.map(tracker.snapshotDispatch(), program,
            List.of(constant)));
    assertEquals("bound-program-mismatch", mismatch.reason());
  }

  @Test
  void failsClosedForUnknownEnumsMissingAttachmentsAndOperationMismatch() {
    IrisGlStateTracker unknownCull = completeGraphicsTracker(1);
    unknownCull.capability(IrisGlStateTracker.GL_CULL_FACE, true);
    unknownCull.cullFace(0xDEAD);
    Unsupported cull = assertInstanceOf(Unsupported.class,
        IrisPipelineStateMapper.map(unknownCull.snapshotDraw(GL_TRIANGLES),
            graphicsProgram(List.of(IrisShaderStage.VERTEX,
                IrisShaderStage.FRAGMENT)), List.of()));
    assertEquals("unsupported-cull-mode-0xdead", cull.reason());

    IrisGlStateTracker missing = completeGraphicsTracker(1);
    missing.detachFramebufferAttachment(
        missing.snapshotDraw(GL_TRIANGLES).drawFramebuffer().value(),
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + 1);
    Unsupported attachment = assertInstanceOf(Unsupported.class,
        IrisPipelineStateMapper.map(missing.snapshotDraw(GL_TRIANGLES),
            graphicsProgram(List.of(IrisShaderStage.VERTEX,
                IrisShaderStage.FRAGMENT)), List.of()));
    assertEquals("active-color-attachment-missing-0",
        attachment.reason());

    Unsupported operation = assertInstanceOf(Unsupported.class,
        IrisPipelineStateMapper.map(
            completeGraphicsTracker(1).snapshotDispatch(),
            graphicsProgram(List.of(IrisShaderStage.VERTEX,
                IrisShaderStage.FRAGMENT)), List.of()));
    assertEquals("operation-pass-kind-mismatch", operation.reason());
  }

  @Test
  void mapsIndexExpansionTopologiesButBlocksMetalExecution() {
    ResolvedProgram program = graphicsProgram(List.of(
        IrisShaderStage.VERTEX, IrisShaderStage.FRAGMENT));

    Complete lineLoop = assertInstanceOf(Complete.class,
        IrisPipelineStateMapper.map(
            completeGraphicsTracker(1).snapshotDraw(GL_LINE_LOOP), program,
            List.of()));
    assertEquals(PrimitiveTopology.LINE_LOOP,
        lineLoop.state().primitive().topology());
    assertFalse(lineLoop.metalExecutionSupported());
    assertEquals(List.of("line-loop-requires-index-expansion"),
        lineLoop.executionBlockers());

    Complete triangleFan = assertInstanceOf(Complete.class,
        IrisPipelineStateMapper.map(
            completeGraphicsTracker(1).snapshotDraw(GL_TRIANGLE_FAN),
            program, List.of()));
    assertEquals(PrimitiveTopology.TRIANGLE_FAN,
        triangleFan.state().primitive().topology());
    assertFalse(triangleFan.metalExecutionSupported());
    assertEquals(List.of("triangle-fan-requires-index-expansion"),
        triangleFan.executionBlockers());
  }

  @Test
  void mapsGeometryStateButMarksMetalExecutionUnsupported() {
    ResolvedProgram geometry = graphicsProgram(List.of(
        IrisShaderStage.VERTEX, IrisShaderStage.GEOMETRY,
        IrisShaderStage.FRAGMENT));
    Complete mapped = assertInstanceOf(Complete.class,
        IrisPipelineStateMapper.map(
            completeGraphicsTracker(1).snapshotDraw(GL_TRIANGLES), geometry,
            List.of()));

    assertFalse(mapped.metalExecutionSupported());
    assertEquals(List.of("geometry-stage-not-supported"),
        mapped.executionBlockers());
    assertEquals(PrimitiveTopology.TRIANGLE,
        mapped.state().primitive().topology());
  }

  @Test
  void rejectsFunctionConstantsForAnUnlinkedStage() {
    Unsupported unsupported = assertInstanceOf(Unsupported.class,
        IrisPipelineStateMapper.map(
            completeGraphicsTracker(1).snapshotDraw(GL_TRIANGLES),
            graphicsProgram(List.of(IrisShaderStage.VERTEX,
                IrisShaderStage.FRAGMENT)),
            List.of(new FunctionConstant(IrisShaderStage.COMPUTE, 1,
                ScalarType.BOOL, 1))));
    assertEquals("function-constant-stage-not-linked",
        unsupported.reason());
  }

  private static IrisGlStateTracker completeGraphicsTracker(int sampleCount) {
    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerProgram(PROGRAM);
    tracker.useProgram(PROGRAM);

    IrisGlStateSnapshot.ResourceHandle framebuffer =
        tracker.registerFramebuffer(71);
    tracker.bindFramebuffer(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        framebuffer);
    tracker.registerTexture(81, "rgba8-unorm", sampleCount);
    tracker.registerTexture(82, "rgba16-float", sampleCount);
    tracker.registerTexture(83, "d32-float-s8-uint", sampleCount);
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 81, 0);
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + 1, GL_TEXTURE_2D, 82, 0);
    tracker.framebufferTexture2D(IrisGlStateTracker.GL_DRAW_FRAMEBUFFER,
        IrisGlStateTracker.GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, 83, 0);
    tracker.drawBuffers(IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + 1,
        IrisGlStateTracker.GL_COLOR_ATTACHMENT0);

    tracker.capability(IrisGlStateTracker.GL_DEPTH_TEST, true);
    tracker.capability(IrisGlStateTracker.GL_STENCIL_TEST, true);
    tracker.capability(IrisGlStateTracker.GL_RASTERIZER_DISCARD, false);
    tracker.sampleMask(0, -1);
    tracker.sampleMask(1, -1);
    return tracker;
  }

  private static ResolvedProgram graphicsProgram(
      List<IrisShaderStage> stages) {
    ProgramDescriptor descriptor = new ProgramDescriptor(
        PassKind.LINKED_GRAPHICS, "mapper-test-graphics", false,
        List.of(new VertexBufferLayout(0, 20, StepFunction.PER_VERTEX, 0)),
        List.of(new VertexAttribute(0, 0, 0,
            new DataFormat("rgb32-float"))));
    IrisProgramIdentityRegistry registry =
        new IrisProgramIdentityRegistry(4);
    return registry.resolve(registry.register(PROGRAM, descriptor),
        new IrisShaderCacheKey("a".repeat(64)), stages);
  }

  private static ResolvedProgram computeProgram() {
    ProgramDescriptor descriptor = new ProgramDescriptor(PassKind.COMPUTE,
        "mapper-test-compute", false, List.of(), List.of());
    IrisProgramIdentityRegistry registry =
        new IrisProgramIdentityRegistry(4);
    return registry.resolve(registry.register(PROGRAM, descriptor),
        new IrisShaderCacheKey("b".repeat(64)),
        List.of(IrisShaderStage.COMPUTE));
  }
}

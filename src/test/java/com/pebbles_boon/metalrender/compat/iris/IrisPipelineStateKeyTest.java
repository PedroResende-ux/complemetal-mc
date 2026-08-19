package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendEquation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendFactor;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.ColorAttachment;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.CompareOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.CullMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DepthClipMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DepthState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.FillMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.FrontFace;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.FunctionConstant;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassIdentity;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PrimitiveState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PrimitiveRestartMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PrimitiveTopology;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.RasterState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.ScalarType;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StencilFace;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StencilOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StencilState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class IrisPipelineStateKeyTest {
  private static final IrisShaderCacheKey SHADER_KEY =
      new IrisShaderCacheKey("1".repeat(64));
  private static final String PASS_ID = "2".repeat(64);

  @Test
  void canonicalizesUnorderedCollections() {
    IrisPipelineState ordered = baseState();
    IrisPipelineState reordered = new IrisPipelineState(ordered.pass(),
        reversed(ordered.vertexBuffers()), reversed(ordered.vertexAttributes()),
        reversed(ordered.colorAttachments()),
        ordered.depthAttachmentFormat(), ordered.stencilAttachmentFormat(),
        ordered.rasterSampleCount(), ordered.sampleMask(),
        ordered.sampleCoverageEnabled(), ordered.sampleCoverageValue(),
        ordered.sampleCoverageInvert(),
        ordered.alphaToCoverage(), ordered.alphaToOne(), ordered.depth(),
        ordered.stencil(), ordered.raster(), ordered.primitive(), true,
        reversed(ordered.functionConstants()));

    assertEquals(ordered, reordered);
    assertEquals(IrisPipelineStateKey.from(SHADER_KEY, ordered),
        IrisPipelineStateKey.from(SHADER_KEY, reordered));
  }

  @Test
  void everyPipelineFieldParticipatesInTheKey() {
    IrisPipelineState base = baseState();
    List<IrisPipelineState> variants = new ArrayList<>();
    variants.add(copy(base, new PassIdentity(PassKind.FULLSCREEN_GRAPHICS,
        PASS_ID, false), null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null));
    variants.add(copy(base, new PassIdentity(PassKind.LINKED_GRAPHICS,
        "3".repeat(64), false), null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null));
    variants.add(copy(base, new PassIdentity(PassKind.LINKED_GRAPHICS,
        PASS_ID, true), null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null));
    variants.add(copy(base, null,
        List.of(new VertexBufferLayout(0, 24, StepFunction.PER_VERTEX, 0),
            new VertexBufferLayout(1, 16, StepFunction.PER_INSTANCE, 2)),
        null, null, null, null, null, null, null, null, null, null, null,
        null, null, null));
    variants.add(copy(base, null, null,
        List.of(new VertexAttribute(0, 0, 4, new DataFormat("rgb32-float")),
            new VertexAttribute(1, 1, 0, new DataFormat("rgba8-unorm"))),
        null, null, null, null, null, null, null, null, null, null, null,
        null, null));
    variants.add(copy(base, null, null, null,
        List.of(new ColorAttachment(0, new DataFormat("rgba16-float"), 0xF,
            base.colorAttachments().getFirst().blend()),
            base.colorAttachments().get(1)),
        null, null, null, null, null, null, null, null, null, null, null,
        null));
    variants.add(copy(base, null, null, null,
        List.of(new ColorAttachment(0, new DataFormat("rgba8-unorm"), 0x7,
            base.colorAttachments().getFirst().blend()),
            base.colorAttachments().get(1)),
        null, null, null, null, null, null, null, null, null, null, null,
        null));
    variants.add(copy(base, null, null, null,
        List.of(new ColorAttachment(0, new DataFormat("rgba8-unorm"), 0xF,
            new BlendState(true,
                new BlendEquation(BlendOperation.ADD, BlendFactor.ONE,
                    BlendFactor.ONE_MINUS_SRC_ALPHA),
                new BlendEquation(BlendOperation.ADD, BlendFactor.ONE,
                    BlendFactor.ZERO))), base.colorAttachments().get(1)),
        null, null, null, null, null, null, null, null, null, null, null,
        null));
    variants.add(copy(base, null, null, null, null,
        Optional.of(new DataFormat("depth16-unorm")), null, null, null, null,
        null, null, null, null, null, null, null));
    variants.add(copy(base, null, null, null, null, null,
        Optional.of(new DataFormat("stencil8-uint")), null, null, null, null,
        null, null, null, null, null, null));
    variants.add(copy(base, null, null, null, null, null, null, 4, null,
        null, null, null, null, null, null, null, null));
    variants.add(copy(base, null, null, null, null, null, null, null, 0x7FL,
        null, null, null, null, null, null, null, null));
    variants.add(copyCoverage(base, true, 0.5F, false));
    variants.add(copyCoverage(base, true, 0.75F, false));
    variants.add(copyCoverage(base, true, 0.5F, true));
    variants.add(copy(base, null, null, null, null, null, null, null, null,
        true, null, null, null, null, null, null, null));
    variants.add(copy(base, null, null, null, null, null, null, null, null,
        null, true, null, null, null, null, null, null));
    variants.add(copy(base, null, null, null, null, null, null, null, null,
        null, null, new DepthState(true, CompareOperation.GREATER, true), null,
        null, null, null, null));
    StencilFace modifiedFace = new StencilFace(CompareOperation.EQUAL,
        StencilOperation.REPLACE, StencilOperation.KEEP,
        StencilOperation.INCREMENT_WRAP, 0x7F, 0x3F, 3);
    variants.add(copy(base, null, null, null, null, null, null, null, null,
        null, null, null, new StencilState(true, modifiedFace, modifiedFace),
        null, null, null, null));
    variants.add(copy(base, null, null, null, null, null, null, null, null,
        null, null, null, null,
        new RasterState(false, CullMode.FRONT, FrontFace.CLOCKWISE,
            FillMode.LINES, FillMode.POINTS, DepthClipMode.CLAMP, 0x3,
            1.0F, 2.0F, 3.0F),
        null, null, null));
    variants.add(copy(base, null, null, null, null, null, null, null, null,
        null, null, null, null, null,
        new PrimitiveState(PrimitiveTopology.TRIANGLE_STRIP,
            PrimitiveRestartMode.FIXED_INDEX, 0),
        null, null));
    variants.add(copy(base, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, true,
        List.of(new FunctionConstant(IrisShaderStage.FRAGMENT, 7,
            ScalarType.UINT32, 42L))));

    Set<IrisPipelineStateKey> keys = new HashSet<>();
    keys.add(IrisPipelineStateKey.from(SHADER_KEY, base));
    for (IrisPipelineState variant : variants) {
      IrisPipelineStateKey key = IrisPipelineStateKey.from(SHADER_KEY, variant);
      assertNotEquals(IrisPipelineStateKey.from(SHADER_KEY, base), key);
      keys.add(key);
    }
    assertEquals(variants.size() + 1, keys.size());
  }

  @Test
  void shaderIdentityParticipatesInThePipelineKey() {
    IrisPipelineState state = baseState();
    assertNotEquals(IrisPipelineStateKey.from(SHADER_KEY, state),
        IrisPipelineStateKey.from(
            new IrisShaderCacheKey("4".repeat(64)), state));
  }

  @Test
  void rejectsIncompleteOrAmbiguousStates() {
    IrisPipelineState base = baseState();
    assertThrows(IllegalArgumentException.class,
        () -> copy(base, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, false, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new DataFormat("UNKNOWN"));
    assertThrows(IllegalArgumentException.class,
        () -> new VertexBufferLayout(0, 16, StepFunction.PER_INSTANCE, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new RasterState(true, CullMode.BACK,
            FrontFace.COUNTER_CLOCKWISE, FillMode.FILL, FillMode.FILL,
            DepthClipMode.CLIP, 0, Float.NaN, 0.0F, 0.0F));
    assertThrows(IllegalArgumentException.class,
        () -> new IrisPipelineState(base.pass(),
            List.of(new VertexBufferLayout(0, 24, StepFunction.PER_VERTEX, 0)),
            List.of(new VertexAttribute(0, 1, 0,
                new DataFormat("rgb32-float"))),
            base.colorAttachments(), base.depthAttachmentFormat(),
            base.stencilAttachmentFormat(), 1, -1L, false, 1.0F, false,
            false, false,
            base.depth(), base.stencil(), base.raster(), base.primitive(), true,
            List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> copy(base,
            new PassIdentity(PassKind.COMPUTE, PASS_ID, false), List.of(),
            List.of(), List.of(), Optional.empty(), Optional.empty(), null,
            null, null, null, null, null, null,
            new PrimitiveState(PrimitiveTopology.TRIANGLE,
                PrimitiveRestartMode.NONE, 0), null,
            null));
    assertThrows(IllegalArgumentException.class,
        () -> copy(base, null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            new PrimitiveState(PrimitiveTopology.NONE,
                PrimitiveRestartMode.NONE, 0), null,
            null));
    assertThrows(IllegalArgumentException.class,
        () -> new FunctionConstant(IrisShaderStage.VERTEX, 0,
            ScalarType.BOOL, 2));
  }

  @Test
  void normalizesNegativeZeroAndDisabledDontCareFields() {
    IrisPipelineState base = baseState();
    RasterState negativeZero = new RasterState(true, CullMode.BACK,
        FrontFace.COUNTER_CLOCKWISE, FillMode.FILL, FillMode.FILL,
        DepthClipMode.CLIP, 0, -0.0F, -0.0F, -0.0F);
    IrisPipelineState normalized = copy(base, null, null, null, null, null,
        null, null, null, null, null, null, null, negativeZero, null, null,
        null);
    assertEquals(IrisPipelineStateKey.from(SHADER_KEY, base),
        IrisPipelineStateKey.from(SHADER_KEY, normalized));

    BlendState disabledWithNoise = new BlendState(false,
        new BlendEquation(BlendOperation.MAX, BlendFactor.SRC_COLOR,
            BlendFactor.DST_COLOR),
        new BlendEquation(BlendOperation.MIN, BlendFactor.SRC_ALPHA,
            BlendFactor.DST_ALPHA));
    assertEquals(BlendState.disabled(), disabledWithNoise);
  }

  private static IrisPipelineState baseState() {
    BlendState alphaBlend = new BlendState(true,
        new BlendEquation(BlendOperation.ADD, BlendFactor.SRC_ALPHA,
            BlendFactor.ONE_MINUS_SRC_ALPHA),
        new BlendEquation(BlendOperation.ADD, BlendFactor.ONE,
            BlendFactor.ONE_MINUS_SRC_ALPHA));
    return new IrisPipelineState(
        new PassIdentity(PassKind.LINKED_GRAPHICS, PASS_ID, false),
        List.of(new VertexBufferLayout(1, 16, StepFunction.PER_INSTANCE, 1),
            new VertexBufferLayout(0, 24, StepFunction.PER_VERTEX, 0)),
        List.of(new VertexAttribute(1, 1, 0,
                new DataFormat("rgba8-unorm")),
            new VertexAttribute(0, 0, 0, new DataFormat("rgb32-float"))),
        List.of(new ColorAttachment(1, new DataFormat("rgba16-float"), 0xF,
                BlendState.disabled()),
            new ColorAttachment(0, new DataFormat("rgba8-unorm"), 0xF,
                alphaBlend)),
        Optional.of(new DataFormat("depth32-float")), Optional.empty(), 1,
        -1L, false, 1.0F, false, false, false,
        new DepthState(true, CompareOperation.LESS_EQUAL, true),
        StencilState.disabled(),
        new RasterState(true, CullMode.BACK,
            FrontFace.COUNTER_CLOCKWISE, FillMode.FILL, FillMode.FILL,
            DepthClipMode.CLIP, 0, 0.0F, 0.0F, 0.0F),
        new PrimitiveState(PrimitiveTopology.TRIANGLE,
            PrimitiveRestartMode.NONE, 0), true,
        List.of());
  }

  private static IrisPipelineState copy(IrisPipelineState base,
      PassIdentity pass, List<VertexBufferLayout> vertexBuffers,
      List<VertexAttribute> vertexAttributes,
      List<ColorAttachment> colorAttachments,
      Optional<DataFormat> depthAttachment,
      Optional<DataFormat> stencilAttachment, Integer samples,
      Long sampleMask, Boolean alphaToCoverage, Boolean alphaToOne,
      DepthState depth, StencilState stencil, RasterState raster,
      PrimitiveState primitive, Boolean specializationScanned,
      List<FunctionConstant> constants) {
    return new IrisPipelineState(pass == null ? base.pass() : pass,
        vertexBuffers == null ? base.vertexBuffers() : vertexBuffers,
        vertexAttributes == null ? base.vertexAttributes() : vertexAttributes,
        colorAttachments == null ? base.colorAttachments() : colorAttachments,
        depthAttachment == null ? base.depthAttachmentFormat() : depthAttachment,
        stencilAttachment == null
            ? base.stencilAttachmentFormat() : stencilAttachment,
        samples == null ? base.rasterSampleCount() : samples,
        sampleMask == null ? base.sampleMask() : sampleMask,
        base.sampleCoverageEnabled(), base.sampleCoverageValue(),
        base.sampleCoverageInvert(),
        alphaToCoverage == null
            ? base.alphaToCoverage() : alphaToCoverage,
        alphaToOne == null ? base.alphaToOne() : alphaToOne,
        depth == null ? base.depth() : depth,
        stencil == null ? base.stencil() : stencil,
        raster == null ? base.raster() : raster,
        primitive == null ? base.primitive() : primitive,
        specializationScanned == null
            ? base.specializationScanned() : specializationScanned,
        constants == null ? base.functionConstants() : constants);
  }

  private static IrisPipelineState copyCoverage(IrisPipelineState base,
      boolean enabled, float value, boolean invert) {
    return new IrisPipelineState(base.pass(), base.vertexBuffers(),
        base.vertexAttributes(), base.colorAttachments(),
        base.depthAttachmentFormat(), base.stencilAttachmentFormat(),
        base.rasterSampleCount(), base.sampleMask(), enabled, value, invert,
        base.alphaToCoverage(), base.alphaToOne(), base.depth(),
        base.stencil(), base.raster(), base.primitive(),
        base.specializationScanned(), base.functionConstants());
  }

  private static <T> List<T> reversed(List<T> input) {
    ArrayList<T> copy = new ArrayList<>(input);
    java.util.Collections.reverse(copy);
    return copy;
  }
}

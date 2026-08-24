package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ColorTarget;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.StateValue;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.TextureAttachment;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendEquation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendFactor;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.ColorAttachment;
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
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StencilOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisProgramIdentityRegistry.ResolvedProgram;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fail-closed conversion from an observed Iris OpenGL operation to the exact
 * state used as a Metal pipeline-cache key.
 *
 * <p>This mapper is deliberately pure. It never queries OpenGL and it never
 * supplies an OpenGL default for an unobserved value. The caller must pass
 * specialization constants produced by an explicitly successful SPIR-V
 * specialization scan.</p>
 */
public final class IrisPipelineStateMapper {
  private static final int GL_NONE = 0;
  private static final int GL_POINTS = 0x0000;
  private static final int GL_LINES = 0x0001;
  private static final int GL_LINE_LOOP = 0x0002;
  private static final int GL_LINE_STRIP = 0x0003;
  private static final int GL_TRIANGLES = 0x0004;
  private static final int GL_TRIANGLE_STRIP = 0x0005;
  private static final int GL_TRIANGLE_FAN = 0x0006;
  private static final int GL_PATCHES = 0x000E;

  private static final int GL_NEVER = 0x0200;
  private static final int GL_LESS = 0x0201;
  private static final int GL_EQUAL = 0x0202;
  private static final int GL_LEQUAL = 0x0203;
  private static final int GL_GREATER = 0x0204;
  private static final int GL_NOTEQUAL = 0x0205;
  private static final int GL_GEQUAL = 0x0206;
  private static final int GL_ALWAYS = 0x0207;

  private static final int GL_SRC_COLOR = 0x0300;
  private static final int GL_ONE_MINUS_SRC_COLOR = 0x0301;
  private static final int GL_SRC_ALPHA = 0x0302;
  private static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
  private static final int GL_DST_ALPHA = 0x0304;
  private static final int GL_ONE_MINUS_DST_ALPHA = 0x0305;
  private static final int GL_DST_COLOR = 0x0306;
  private static final int GL_ONE_MINUS_DST_COLOR = 0x0307;
  private static final int GL_SRC_ALPHA_SATURATE = 0x0308;

  private static final int GL_FRONT = 0x0404;
  private static final int GL_BACK = 0x0405;
  private static final int GL_FRONT_AND_BACK = 0x0408;

  private static final int GL_CW = 0x0900;
  private static final int GL_CCW = 0x0901;

  private static final int GL_POINT = 0x1B00;
  private static final int GL_LINE = 0x1B01;
  private static final int GL_FILL = 0x1B02;

  private static final int GL_KEEP = 0x1E00;
  private static final int GL_REPLACE = 0x1E01;
  private static final int GL_INCR = 0x1E02;
  private static final int GL_DECR = 0x1E03;
  private static final int GL_INVERT = 0x150A;

  private static final int GL_CONSTANT_COLOR = 0x8001;
  private static final int GL_ONE_MINUS_CONSTANT_COLOR = 0x8002;
  private static final int GL_CONSTANT_ALPHA = 0x8003;
  private static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;
  private static final int GL_FUNC_ADD = 0x8006;
  private static final int GL_MIN = 0x8007;
  private static final int GL_MAX = 0x8008;
  private static final int GL_FUNC_SUBTRACT = 0x800A;
  private static final int GL_FUNC_REVERSE_SUBTRACT = 0x800B;
  private static final int GL_INCR_WRAP = 0x8507;
  private static final int GL_DECR_WRAP = 0x8508;
  private static final int GL_SRC1_ALPHA = 0x8589;
  private static final int GL_SRC1_COLOR = 0x88F9;
  private static final int GL_ONE_MINUS_SRC1_COLOR = 0x88FA;
  private static final int GL_ONE_MINUS_SRC1_ALPHA = 0x88FB;

  private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

  /** Bit positions used by {@link IrisPipelineState.RasterState}. */
  public static final int POLYGON_OFFSET_POINT_BIT = 1;
  public static final int POLYGON_OFFSET_LINE_BIT = 1 << 1;
  public static final int POLYGON_OFFSET_FILL_BIT = 1 << 2;

  private IrisPipelineStateMapper() {
  }

  /**
   * Maps one draw or dispatch. The specialization list is trusted only as the
   * output of a completed scan and is still checked against linked stages.
   */
  public static Result map(IrisGlStateSnapshot snapshot,
      ResolvedProgram program,
      List<FunctionConstant> scannedSpecializationConstants) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(program, "program");
    Objects.requireNonNull(scannedSpecializationConstants,
        "scannedSpecializationConstants");

    try {
      validateResolvedProgram(program);
      validateBoundProgram(snapshot, program);
      validateOperation(snapshot, program.pass().kind());
      validateStages(program);
      List<FunctionConstant> constants = validateConstants(program,
          scannedSpecializationConstants);

      if (program.pass().kind() == PassKind.COMPUTE) {
        IrisPipelineState state = computeState(program, constants);
        return new Complete(state, executionBlockers(program, state));
      }
      requireCompleteSnapshot(snapshot);
      IrisPipelineState state = graphicsState(snapshot, program, constants);
      return new Complete(state, executionBlockers(program, state));
    } catch (MappingFailure failure) {
      return new Unsupported(failure.reason);
    } catch (IllegalArgumentException failure) {
      // A malformed externally-constructed record must never create a cache
      // entry or escape as a render-thread exception.
      return new Unsupported("pipeline-state-validation-failed");
    }
  }

  private static IrisPipelineState computeState(ResolvedProgram program,
      List<FunctionConstant> constants) {
    return new IrisPipelineState(program.pass(), List.of(), List.of(),
        List.of(), Optional.empty(), Optional.empty(), 1, -1L, false, 1.0F,
        false, false, false,
        new IrisPipelineState.DepthState(false, CompareOperation.ALWAYS,
            false),
        IrisPipelineState.StencilState.disabled(),
        new IrisPipelineState.RasterState(false, CullMode.NONE,
            FrontFace.COUNTER_CLOCKWISE, FillMode.FILL, FillMode.FILL,
            DepthClipMode.CLIP, 0, 0.0F, 0.0F, 0.0F),
        new IrisPipelineState.PrimitiveState(PrimitiveTopology.NONE,
            PrimitiveRestartMode.NONE, 0),
        true, constants);
  }

  private static IrisPipelineState graphicsState(IrisGlStateSnapshot snapshot,
      ResolvedProgram program, List<FunctionConstant> constants) {
    int sampleCount = requireKnown(
        snapshot.multisample().rasterSampleCount(),
        "multisample.raster-sample-count");
    if (sampleCount <= 0 || sampleCount > 64) {
      throw failure("invalid-raster-sample-count");
    }

    List<ColorAttachment> colorAttachments = colorAttachments(snapshot,
        sampleCount);
    Optional<DataFormat> depthFormat = attachmentFormat(
        snapshot.depthAttachment(), sampleCount, "depth-attachment");
    Optional<DataFormat> stencilFormat = attachmentFormat(
        snapshot.stencilAttachment(), sampleCount, "stencil-attachment");

    IrisPipelineState.DepthState depth = depthState(snapshot.depth());
    IrisPipelineState.StencilState stencil = stencilState(snapshot.stencil());
    IrisPipelineState.RasterState raster = rasterState(snapshot.raster());
    IrisPipelineState.PrimitiveState primitive = primitiveState(
        snapshot.primitive());
    SampleState sample = sampleState(snapshot.multisample(), sampleCount);

    return new IrisPipelineState(program.pass(),
        program.descriptor().vertexBuffers(),
        program.descriptor().vertexAttributes(), colorAttachments,
        depthFormat, stencilFormat, sampleCount, sample.mask,
        sample.coverageEnabled, sample.coverageValue, sample.coverageInvert,
        sample.alphaToCoverage, sample.alphaToOne, depth, stencil, raster,
        primitive, true, constants);
  }

  private static List<ColorAttachment> colorAttachments(
      IrisGlStateSnapshot snapshot, int sampleCount) {
    ResourceHandle framebuffer = requireKnown(snapshot.drawFramebuffer(),
        "draw-framebuffer");
    if (framebuffer.kind() != ResourceKind.FRAMEBUFFER) {
      throw failure("draw-framebuffer-handle-kind-mismatch");
    }
    List<Integer> buffers = requireKnown(snapshot.drawBuffers(),
        "draw-buffers");
    if (buffers.size() > IrisPipelineState.MAX_COLOR_ATTACHMENTS
        || snapshot.colorTargets().size()
        != IrisPipelineState.MAX_COLOR_ATTACHMENTS) {
      throw failure("color-target-layout-invalid");
    }

    ArrayList<ColorAttachment> result = new ArrayList<>(buffers.size());
    Set<Integer> activeBuffers = new HashSet<>();
    for (int index = 0;
         index < IrisPipelineState.MAX_COLOR_ATTACHMENTS; index++) {
      ColorTarget target = snapshot.colorTargets().get(index);
      if (target.index() != index) {
        throw failure("color-target-layout-invalid");
      }
      int expectedBuffer = index < buffers.size()
          ? buffers.get(index) : GL_NONE;
      int drawBuffer = requireKnown(target.drawBuffer(),
          "color-target-" + index + "-draw-buffer");
      if (drawBuffer != expectedBuffer) {
        throw failure("color-target-draw-buffer-mismatch");
      }
      if (drawBuffer == GL_NONE) {
        continue;
      }
      if (drawBuffer < GL_COLOR_ATTACHMENT0
          || drawBuffer >= GL_COLOR_ATTACHMENT0
          + IrisPipelineState.MAX_COLOR_ATTACHMENTS) {
        throw failure("unsupported-draw-buffer-" + glHex(drawBuffer));
      }
      if (!activeBuffers.add(drawBuffer)) {
        throw failure("duplicate-active-draw-buffer");
      }

      Optional<TextureAttachment> attachment = requireKnown(
          target.attachment(), "color-target-" + index + "-attachment");
      if (attachment.isEmpty()) {
        throw failure("active-color-attachment-missing-" + index);
      }
      validateAttachmentSampleCount(attachment.orElseThrow(), sampleCount,
          "color-target-" + index);
      DataFormat format = dataFormat(attachment.orElseThrow().format(),
          "color-target-" + index);
      int writeMask = requireKnown(target.colorMask(),
          "color-target-" + index + "-write-mask").bits();
      result.add(new ColorAttachment(index, format, writeMask,
          blendState(target.blend(), index)));
    }
    return List.copyOf(result);
  }

  private static Optional<DataFormat> attachmentFormat(
      StateValue<Optional<TextureAttachment>> value, int sampleCount,
      String field) {
    Optional<TextureAttachment> attachment = requireKnown(value, field);
    if (attachment.isEmpty()) {
      return Optional.empty();
    }
    TextureAttachment present = attachment.orElseThrow();
    validateAttachmentSampleCount(present, sampleCount, field);
    return Optional.of(dataFormat(present.format(), field));
  }

  private static void validateAttachmentSampleCount(
      TextureAttachment attachment, int expected, String field) {
    int actual = requireKnown(attachment.sampleCount(),
        field + "-sample-count");
    if (actual != expected) {
      throw failure("attachment-sample-count-mismatch");
    }
  }

  private static DataFormat dataFormat(String value, String field) {
    try {
      return new DataFormat(value);
    } catch (IllegalArgumentException failure) {
      throw failure("invalid-" + field + "-format");
    }
  }

  private static IrisPipelineState.BlendState blendState(
      IrisGlStateSnapshot.BlendState state, int target) {
    boolean enabled = requireKnown(state.enabled(),
        "color-target-" + target + "-blend-enabled");
    if (!enabled) {
      return IrisPipelineState.BlendState.disabled();
    }
    BlendEquation rgb = new BlendEquation(
        blendOperation(requireKnown(state.rgbEquation(),
            "color-target-" + target + "-rgb-blend-equation")),
        blendFactor(requireKnown(state.sourceRgb(),
            "color-target-" + target + "-source-rgb-blend-factor")),
        blendFactor(requireKnown(state.destinationRgb(),
            "color-target-" + target
                + "-destination-rgb-blend-factor")));
    BlendEquation alpha = new BlendEquation(
        blendOperation(requireKnown(state.alphaEquation(),
            "color-target-" + target + "-alpha-blend-equation")),
        blendFactor(requireKnown(state.sourceAlpha(),
            "color-target-" + target + "-source-alpha-blend-factor")),
        blendFactor(requireKnown(state.destinationAlpha(),
            "color-target-" + target
                + "-destination-alpha-blend-factor")));
    return new IrisPipelineState.BlendState(true, rgb, alpha);
  }

  private static IrisPipelineState.DepthState depthState(
      IrisGlStateSnapshot.DepthState state) {
    boolean enabled = requireKnown(state.testEnabled(),
        "depth-test-enabled");
    CompareOperation compare = enabled
        ? compareOperation(requireKnown(state.function(), "depth-function"))
        : CompareOperation.ALWAYS;
    boolean writeEnabled = requireKnown(state.writeEnabled(),
        "depth-write-enabled");
    return new IrisPipelineState.DepthState(enabled, compare, writeEnabled);
  }

  private static IrisPipelineState.StencilState stencilState(
      IrisGlStateSnapshot.StencilState state) {
    boolean enabled = requireKnown(state.testEnabled(),
        "stencil-test-enabled");
    if (!enabled) {
      return IrisPipelineState.StencilState.disabled();
    }
    return new IrisPipelineState.StencilState(true,
        stencilFace(state.front(), "front"),
        stencilFace(state.back(), "back"));
  }

  private static IrisPipelineState.StencilFace stencilFace(
      IrisGlStateSnapshot.StencilFace face, String name) {
    return new IrisPipelineState.StencilFace(
        compareOperation(requireKnown(face.function(),
            "stencil-" + name + "-function")),
        stencilOperation(requireKnown(face.stencilFail(),
            "stencil-" + name + "-stencil-fail")),
        stencilOperation(requireKnown(face.depthFail(),
            "stencil-" + name + "-depth-fail")),
        stencilOperation(requireKnown(face.depthPass(),
            "stencil-" + name + "-depth-pass")),
        requireKnown(face.readMask(), "stencil-" + name + "-read-mask"),
        requireKnown(face.writeMask(), "stencil-" + name + "-write-mask"),
        requireKnown(face.reference(), "stencil-" + name + "-reference"));
  }

  private static IrisPipelineState.RasterState rasterState(
      IrisGlStateSnapshot.RasterState state) {
    boolean cullEnabled = requireKnown(state.cullEnabled(), "cull-enabled");
    CullMode cull = cullEnabled
        ? cullMode(requireKnown(state.cullMode(), "cull-mode"))
        : CullMode.NONE;
    FrontFace front = frontFace(requireKnown(state.frontFace(),
        "front-face"));
    DepthClipMode clip = requireKnown(state.depthClampEnabled(),
        "depth-clamp-enabled") ? DepthClipMode.CLAMP : DepthClipMode.CLIP;
    boolean rasterizationEnabled = !requireKnown(
        state.rasterizerDiscardEnabled(), "rasterizer-discard-enabled");
    FillMode frontFill = fillMode(requireKnown(state.polygonModeFront(),
        "front-polygon-mode"));
    FillMode backFill = fillMode(requireKnown(state.polygonModeBack(),
        "back-polygon-mode"));

    boolean pointOffset = requireKnown(state.polygonOffsetPointEnabled(),
        "point-polygon-offset-enabled");
    boolean lineOffset = requireKnown(state.polygonOffsetLineEnabled(),
        "line-polygon-offset-enabled");
    boolean fillOffset = requireKnown(state.polygonOffsetFillEnabled(),
        "fill-polygon-offset-enabled");
    int offsetMask = (pointOffset ? POLYGON_OFFSET_POINT_BIT : 0)
        | (lineOffset ? POLYGON_OFFSET_LINE_BIT : 0)
        | (fillOffset ? POLYGON_OFFSET_FILL_BIT : 0);
    float depthBias = 0.0F;
    float slopeScale = 0.0F;
    float clamp = 0.0F;
    if (offsetMask != 0) {
      // glPolygonOffset(factor, units) corresponds to Metal's
      // setDepthBias(depthBias, slopeScale, clamp) argument order.
      slopeScale = requireKnown(state.polygonOffsetFactor(),
          "polygon-offset-factor");
      depthBias = requireKnown(state.polygonOffsetUnits(),
          "polygon-offset-units");
      clamp = requireKnown(state.polygonOffsetClamp(),
          "polygon-offset-clamp");
    }
    return new IrisPipelineState.RasterState(rasterizationEnabled, cull,
        front, frontFill, backFill, clip, offsetMask, depthBias, slopeScale,
        clamp);
  }

  private static IrisPipelineState.PrimitiveState primitiveState(
      IrisGlStateSnapshot.PrimitiveState state) {
    PrimitiveTopology topology = primitiveTopology(requireKnown(state.mode(),
        "primitive-mode"));
    boolean explicit = requireKnown(state.restartEnabled(),
        "primitive-restart-enabled");
    boolean fixed = requireKnown(state.fixedIndexRestartEnabled(),
        "fixed-index-primitive-restart-enabled");
    PrimitiveRestartMode restart = explicit && fixed
        ? PrimitiveRestartMode.BOTH
        : explicit ? PrimitiveRestartMode.EXPLICIT_INDEX
        : fixed ? PrimitiveRestartMode.FIXED_INDEX
        : PrimitiveRestartMode.NONE;
    return new IrisPipelineState.PrimitiveState(topology, restart, 0);
  }

  private static SampleState sampleState(
      IrisGlStateSnapshot.MultisampleState state, int sampleCount) {
    boolean maskEnabled = requireKnown(state.sampleMaskEnabled(),
        "sample-mask-enabled");
    long mask = -1L;
    if (maskEnabled) {
      int word0 = requireKnown(state.sampleMaskWord0(),
          "sample-mask-word-0");
      int word1 = sampleCount > 32
          ? requireKnown(state.sampleMaskWord1(), "sample-mask-word-1")
          : -1;
      long captured = Integer.toUnsignedLong(word0) | ((long) word1 << 32);
      long activeBits = sampleCount == 64
          ? -1L : (1L << sampleCount) - 1L;
      // Bits above rasterSampleCount are semantically inactive. Canonicalize
      // them to one so equivalent GL masks share a pipeline-state key.
      mask = captured | ~activeBits;
    }

    boolean coverageEnabled = requireKnown(state.sampleCoverageEnabled(),
        "sample-coverage-enabled");
    float coverageValue = 1.0F;
    boolean coverageInvert = false;
    if (coverageEnabled) {
      coverageValue = requireKnown(state.sampleCoverageValue(),
          "sample-coverage-value");
      coverageInvert = requireKnown(state.sampleCoverageInvert(),
          "sample-coverage-invert");
    }
    boolean alphaToCoverage = requireKnown(state.alphaToCoverageEnabled(),
        "alpha-to-coverage-enabled");
    boolean alphaToOne = requireKnown(state.alphaToOneEnabled(),
        "alpha-to-one-enabled");
    return new SampleState(mask, coverageEnabled, coverageValue,
        coverageInvert, alphaToCoverage, alphaToOne);
  }

  private static void validateResolvedProgram(ResolvedProgram program) {
    if (program.generation() <= 0 || program.glProgram() <= 0) {
      throw failure("resolved-program-identity-invalid");
    }
    if (program.pass().kind() != program.descriptor().kind()
        || program.pass().fallback() != program.descriptor().fallback()
        || !program.pass().passIdSha256().equals(
            IrisProgramIdentityRegistry.passIdentitySha256(
                program.descriptor()))) {
      throw failure("resolved-program-pass-mismatch");
    }
  }

  private static void validateBoundProgram(IrisGlStateSnapshot snapshot,
      ResolvedProgram program) {
    Optional<ResourceHandle> optional = requireKnown(snapshot.program(),
        "program");
    if (optional.isEmpty()) {
      throw failure("no-program-bound");
    }
    ResourceHandle bound = optional.orElseThrow();
    if (bound.kind() != ResourceKind.PROGRAM) {
      throw failure("bound-program-handle-kind-mismatch");
    }
    // Tracker and shader registry use independent generation namespaces. The
    // exact GL name is therefore the only common identity available here.
    if (bound.name() != program.glProgram()) {
      throw failure("bound-program-mismatch");
    }
  }

  private static void validateOperation(IrisGlStateSnapshot snapshot,
      PassKind passKind) {
    boolean compute = passKind == PassKind.COMPUTE;
    if ((compute && snapshot.operation()
        != IrisGlStateSnapshot.Operation.DISPATCH)
        || (!compute && snapshot.operation()
        != IrisGlStateSnapshot.Operation.DRAW)) {
      throw failure("operation-pass-kind-mismatch");
    }
  }

  private static void validateStages(ResolvedProgram program) {
    List<IrisShaderStage> stages = program.stages();
    if (program.pass().kind() == PassKind.COMPUTE) {
      if (!stages.equals(List.of(IrisShaderStage.COMPUTE))) {
        throw failure("compute-stage-set-invalid");
      }
      return;
    }
    if (!stages.contains(IrisShaderStage.VERTEX)
        || stages.contains(IrisShaderStage.COMPUTE)) {
      throw failure("graphics-stage-set-invalid");
    }
    boolean tessControl = stages.contains(IrisShaderStage.TESS_CONTROL);
    boolean tessEvaluation = stages.contains(
        IrisShaderStage.TESS_EVALUATION);
    if (tessControl != tessEvaluation) {
      throw failure("tessellation-stage-pair-incomplete");
    }
  }

  private static List<FunctionConstant> validateConstants(
      ResolvedProgram program, List<FunctionConstant> input) {
    ArrayList<FunctionConstant> copy = new ArrayList<>(input.size());
    Set<String> identities = new HashSet<>();
    for (FunctionConstant constant : input) {
      if (constant == null) {
        throw failure("function-constant-null");
      }
      if (!program.stages().contains(constant.stage())) {
        throw failure("function-constant-stage-not-linked");
      }
      String identity = constant.stage().name() + ':'
          + constant.constantId();
      if (!identities.add(identity)) {
        throw failure("function-constant-duplicate");
      }
      copy.add(constant);
    }
    if (copy.size() > IrisPipelineState.MAX_FUNCTION_CONSTANTS) {
      throw failure("function-constant-capacity-exceeded");
    }
    return List.copyOf(copy);
  }

  private static void requireCompleteSnapshot(IrisGlStateSnapshot snapshot) {
    if (snapshot.unknownFields().isEmpty()) {
      return;
    }
    String first = snapshot.unknownFields().getFirst();
    int separator = first.indexOf(':');
    String field = separator < 0 ? first : first.substring(0, separator);
    if (!field.matches("[A-Za-z0-9_.\\[\\]-]{1,128}")) {
      throw failure("snapshot-incomplete");
    }
    throw failure("snapshot-incomplete-" + field.toLowerCase(
        java.util.Locale.ROOT).replace('[', '-').replace(']', '-'));
  }

  private static List<String> executionBlockers(ResolvedProgram program,
      IrisPipelineState state) {
    ArrayList<String> blockers = new ArrayList<>(8);
    if (program.stages().contains(IrisShaderStage.TESS_CONTROL)) {
      blockers.add("tessellation-stage-not-supported");
    }
    if (program.stages().contains(IrisShaderStage.GEOMETRY)) {
      blockers.add("geometry-stage-not-supported");
    }
    // LINE_LOOP and TRIANGLE_FAN are retained in the pipeline identity but
    // their draw-time indices are expanded exactly before Metal replay.
    if (state.primitive().topology() == PrimitiveTopology.PATCH) {
      blockers.add("patch-topology-not-supported");
    }
    if (state.primitive().restartMode()
        != IrisPipelineState.PrimitiveRestartMode.NONE) {
      blockers.add("primitive-restart-requires-index-expansion");
    }
    if (state.raster().cullMode() == IrisPipelineState.CullMode.FRONT_AND_BACK) {
      blockers.add("front-and-back-cull-not-supported");
    }
    if (state.raster().frontFillMode() != state.raster().backFillMode()) {
      blockers.add("asymmetric-polygon-mode-not-supported");
    }
    if (state.raster().frontFillMode() == IrisPipelineState.FillMode.POINTS
        || state.raster().backFillMode()
        == IrisPipelineState.FillMode.POINTS) {
      blockers.add("point-polygon-mode-not-supported");
    }
    if (state.sampleCoverageEnabled()) {
      blockers.add("sample-coverage-not-supported");
    }
    if (state.sampleMask() != -1L) {
      blockers.add("sample-mask-not-supported");
    }
    if (state.colorAttachments().stream().anyMatch(attachment ->
        attachment.blend().enabled()
            && (usesBlendConstant(attachment.blend().rgb())
                || usesBlendConstant(attachment.blend().alpha())))) {
      blockers.add("blend-constant-dynamic-state-unobserved");
    }
    return List.copyOf(blockers);
  }

  private static boolean usesBlendConstant(BlendEquation equation) {
    return usesBlendConstant(equation.source())
        || usesBlendConstant(equation.destination());
  }

  private static boolean usesBlendConstant(BlendFactor factor) {
    return factor == BlendFactor.CONSTANT_COLOR
        || factor == BlendFactor.ONE_MINUS_CONSTANT_COLOR
        || factor == BlendFactor.CONSTANT_ALPHA
        || factor == BlendFactor.ONE_MINUS_CONSTANT_ALPHA;
  }

  private static BlendOperation blendOperation(int gl) {
    return switch (gl) {
      case GL_FUNC_ADD -> BlendOperation.ADD;
      case GL_FUNC_SUBTRACT -> BlendOperation.SUBTRACT;
      case GL_FUNC_REVERSE_SUBTRACT -> BlendOperation.REVERSE_SUBTRACT;
      case GL_MIN -> BlendOperation.MIN;
      case GL_MAX -> BlendOperation.MAX;
      default -> throw failure("unsupported-blend-operation-" + glHex(gl));
    };
  }

  private static BlendFactor blendFactor(int gl) {
    return switch (gl) {
      case 0 -> BlendFactor.ZERO;
      case 1 -> BlendFactor.ONE;
      case GL_SRC_COLOR -> BlendFactor.SRC_COLOR;
      case GL_ONE_MINUS_SRC_COLOR -> BlendFactor.ONE_MINUS_SRC_COLOR;
      case GL_DST_COLOR -> BlendFactor.DST_COLOR;
      case GL_ONE_MINUS_DST_COLOR -> BlendFactor.ONE_MINUS_DST_COLOR;
      case GL_SRC_ALPHA -> BlendFactor.SRC_ALPHA;
      case GL_ONE_MINUS_SRC_ALPHA -> BlendFactor.ONE_MINUS_SRC_ALPHA;
      case GL_DST_ALPHA -> BlendFactor.DST_ALPHA;
      case GL_ONE_MINUS_DST_ALPHA -> BlendFactor.ONE_MINUS_DST_ALPHA;
      case GL_CONSTANT_COLOR -> BlendFactor.CONSTANT_COLOR;
      case GL_ONE_MINUS_CONSTANT_COLOR ->
          BlendFactor.ONE_MINUS_CONSTANT_COLOR;
      case GL_CONSTANT_ALPHA -> BlendFactor.CONSTANT_ALPHA;
      case GL_ONE_MINUS_CONSTANT_ALPHA ->
          BlendFactor.ONE_MINUS_CONSTANT_ALPHA;
      case GL_SRC_ALPHA_SATURATE -> BlendFactor.SRC_ALPHA_SATURATE;
      case GL_SRC1_COLOR -> BlendFactor.SRC1_COLOR;
      case GL_ONE_MINUS_SRC1_COLOR -> BlendFactor.ONE_MINUS_SRC1_COLOR;
      case GL_SRC1_ALPHA -> BlendFactor.SRC1_ALPHA;
      case GL_ONE_MINUS_SRC1_ALPHA -> BlendFactor.ONE_MINUS_SRC1_ALPHA;
      default -> throw failure("unsupported-blend-factor-" + glHex(gl));
    };
  }

  private static CompareOperation compareOperation(int gl) {
    return switch (gl) {
      case GL_NEVER -> CompareOperation.NEVER;
      case GL_LESS -> CompareOperation.LESS;
      case GL_EQUAL -> CompareOperation.EQUAL;
      case GL_LEQUAL -> CompareOperation.LESS_EQUAL;
      case GL_GREATER -> CompareOperation.GREATER;
      case GL_NOTEQUAL -> CompareOperation.NOT_EQUAL;
      case GL_GEQUAL -> CompareOperation.GREATER_EQUAL;
      case GL_ALWAYS -> CompareOperation.ALWAYS;
      default -> throw failure("unsupported-compare-operation-" + glHex(gl));
    };
  }

  private static StencilOperation stencilOperation(int gl) {
    return switch (gl) {
      case 0 -> StencilOperation.ZERO;
      case GL_KEEP -> StencilOperation.KEEP;
      case GL_REPLACE -> StencilOperation.REPLACE;
      case GL_INCR -> StencilOperation.INCREMENT_CLAMP;
      case GL_DECR -> StencilOperation.DECREMENT_CLAMP;
      case GL_INVERT -> StencilOperation.INVERT;
      case GL_INCR_WRAP -> StencilOperation.INCREMENT_WRAP;
      case GL_DECR_WRAP -> StencilOperation.DECREMENT_WRAP;
      default -> throw failure("unsupported-stencil-operation-" + glHex(gl));
    };
  }

  private static CullMode cullMode(int gl) {
    return switch (gl) {
      case GL_FRONT -> CullMode.FRONT;
      case GL_BACK -> CullMode.BACK;
      case GL_FRONT_AND_BACK -> CullMode.FRONT_AND_BACK;
      default -> throw failure("unsupported-cull-mode-" + glHex(gl));
    };
  }

  private static FrontFace frontFace(int gl) {
    return switch (gl) {
      case GL_CW -> FrontFace.CLOCKWISE;
      case GL_CCW -> FrontFace.COUNTER_CLOCKWISE;
      default -> throw failure("unsupported-front-face-" + glHex(gl));
    };
  }

  private static FillMode fillMode(int gl) {
    return switch (gl) {
      case GL_POINT -> FillMode.POINTS;
      case GL_LINE -> FillMode.LINES;
      case GL_FILL -> FillMode.FILL;
      default -> throw failure("unsupported-polygon-mode-" + glHex(gl));
    };
  }

  private static PrimitiveTopology primitiveTopology(int gl) {
    return switch (gl) {
      case GL_POINTS -> PrimitiveTopology.POINT;
      case GL_LINES -> PrimitiveTopology.LINE;
      case GL_LINE_LOOP -> PrimitiveTopology.LINE_LOOP;
      case GL_LINE_STRIP -> PrimitiveTopology.LINE_STRIP;
      case GL_TRIANGLES -> PrimitiveTopology.TRIANGLE;
      case GL_TRIANGLE_STRIP -> PrimitiveTopology.TRIANGLE_STRIP;
      case GL_TRIANGLE_FAN -> PrimitiveTopology.TRIANGLE_FAN;
      case GL_PATCHES -> throw failure(
          "unsupported-primitive-topology-patch-control-points-unobserved");
      default -> throw failure("unsupported-primitive-topology-" + glHex(gl));
    };
  }

  private static <T> T requireKnown(StateValue<T> value, String field) {
    if (!value.isKnown()) {
      throw failure("unknown-" + field);
    }
    return value.value();
  }

  private static String glHex(int value) {
    return "0x" + Integer.toUnsignedString(value, 16);
  }

  private static MappingFailure failure(String reason) {
    return new MappingFailure(reason);
  }

  /** Result of a fail-closed pipeline-state mapping attempt. */
  public sealed interface Result permits Complete, Unsupported {
  }

  /**
   * Complete cacheable state. Execution blockers do not make capture
   * ambiguous; they prevent a later Metal draw path from claiming support.
   */
  public record Complete(IrisPipelineState state,
                         List<String> executionBlockers) implements Result {
    public Complete {
      Objects.requireNonNull(state, "state");
      executionBlockers = List.copyOf(executionBlockers);
    }

    public boolean metalExecutionSupported() {
      return executionBlockers.isEmpty();
    }
  }

  /** A deterministic reason why no cacheable state can be constructed. */
  public record Unsupported(String reason) implements Result {
    public Unsupported {
      Objects.requireNonNull(reason, "reason");
      if (!reason.matches("[a-z0-9][a-z0-9._:-]{0,255}")) {
        throw new IllegalArgumentException("reason is not deterministic-safe");
      }
    }
  }

  private record SampleState(long mask, boolean coverageEnabled,
                             float coverageValue, boolean coverageInvert,
                             boolean alphaToCoverage, boolean alphaToOne) {
  }

  private static final class MappingFailure extends RuntimeException {
    private final String reason;

    private MappingFailure(String reason) {
      super(null, null, false, false);
      this.reason = reason;
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Complete immutable graphics or compute pipeline state observed for one Iris
 * program variant.
 *
 * <p>The model deliberately contains no Iris, Minecraft, OpenGL, or native
 * objects. It is safe to retain on the background cache worker and every value
 * has an explicit stable cache spelling.</p>
 */
public record IrisPipelineState(
    PassIdentity pass,
    List<VertexBufferLayout> vertexBuffers,
    List<VertexAttribute> vertexAttributes,
    List<ColorAttachment> colorAttachments,
    Optional<DataFormat> depthAttachmentFormat,
    Optional<DataFormat> stencilAttachmentFormat,
    int rasterSampleCount,
    long sampleMask,
    boolean sampleCoverageEnabled,
    float sampleCoverageValue,
    boolean sampleCoverageInvert,
    boolean alphaToCoverage,
    boolean alphaToOne,
    DepthState depth,
    StencilState stencil,
    RasterState raster,
    PrimitiveState primitive,
    boolean specializationScanned,
    List<FunctionConstant> functionConstants) {
  public static final int MAX_VERTEX_BUFFERS = 31;
  public static final int MAX_VERTEX_ATTRIBUTES = 31;
  public static final int MAX_COLOR_ATTACHMENTS = 8;
  public static final int MAX_FUNCTION_CONSTANTS = 256;

  public IrisPipelineState {
    Objects.requireNonNull(pass, "pass");
    depthAttachmentFormat = Objects.requireNonNull(
        depthAttachmentFormat, "depthAttachmentFormat");
    stencilAttachmentFormat = Objects.requireNonNull(
        stencilAttachmentFormat, "stencilAttachmentFormat");
    Objects.requireNonNull(depth, "depth");
    Objects.requireNonNull(stencil, "stencil");
    Objects.requireNonNull(raster, "raster");
    Objects.requireNonNull(primitive, "primitive");
    if (rasterSampleCount <= 0 || rasterSampleCount > 64) {
      throw new IllegalArgumentException(
          "raster sample count must be between 1 and 64");
    }
    sampleCoverageValue = finiteNormalized(sampleCoverageValue,
        "sampleCoverageValue");
    if (sampleCoverageValue < 0.0F || sampleCoverageValue > 1.0F) {
      throw new IllegalArgumentException(
          "sample coverage value must be in 0..1");
    }
    if (!sampleCoverageEnabled) {
      sampleCoverageValue = 1.0F;
      sampleCoverageInvert = false;
    }
    if (!specializationScanned) {
      throw new IllegalArgumentException(
          "specialization constants must be explicitly scanned");
    }

    vertexBuffers = sortedUnique(vertexBuffers,
        Comparator.comparingInt(VertexBufferLayout::bufferIndex),
        VertexBufferLayout::bufferIndex, MAX_VERTEX_BUFFERS,
        "vertex buffer");
    vertexAttributes = sortedUnique(vertexAttributes,
        Comparator.comparingInt(VertexAttribute::location),
        VertexAttribute::location, MAX_VERTEX_ATTRIBUTES,
        "vertex attribute");
    colorAttachments = sortedUnique(colorAttachments,
        Comparator.comparingInt(ColorAttachment::slot),
        ColorAttachment::slot, MAX_COLOR_ATTACHMENTS,
        "color attachment");
    functionConstants = sortedFunctionConstants(functionConstants);

    Set<Integer> bufferIndices = new HashSet<>();
    for (VertexBufferLayout buffer : vertexBuffers) {
      bufferIndices.add(buffer.bufferIndex());
    }
    for (VertexAttribute attribute : vertexAttributes) {
      if (!bufferIndices.contains(attribute.bufferIndex())) {
        throw new IllegalArgumentException(
            "vertex attribute references an absent buffer");
      }
    }
    if (pass.kind() == PassKind.COMPUTE
        && (!vertexBuffers.isEmpty() || !vertexAttributes.isEmpty()
        || !colorAttachments.isEmpty()
        || depthAttachmentFormat.isPresent()
        || stencilAttachmentFormat.isPresent()
        || primitive.topology() != PrimitiveTopology.NONE)) {
      throw new IllegalArgumentException(
          "compute state cannot contain graphics-only state");
    }
    if (pass.kind() == PassKind.COMPUTE
        && (rasterSampleCount != 1 || sampleMask != -1L
        || sampleCoverageEnabled || sampleCoverageValue != 1.0F
        || sampleCoverageInvert
        || alphaToCoverage || alphaToOne
        || depth.testEnabled() || depth.writeEnabled()
        || depth.compare() != CompareOperation.ALWAYS
        || stencil.enabled() || raster.rasterizationEnabled()
        || raster.cullMode() != CullMode.NONE
        || raster.frontFace() != FrontFace.COUNTER_CLOCKWISE
        || raster.frontFillMode() != FillMode.FILL
        || raster.backFillMode() != FillMode.FILL
        || raster.depthClipMode() != DepthClipMode.CLIP
        || raster.polygonOffsetModeMask() != 0
        || raster.depthBias() != 0.0F || raster.slopeScale() != 0.0F
        || raster.depthBiasClamp() != 0.0F
        || primitive.restartMode() != PrimitiveRestartMode.NONE
        || !functionConstants.stream().allMatch(
            value -> value.stage() == IrisShaderStage.COMPUTE))) {
      throw new IllegalArgumentException(
          "compute state contains non-canonical graphics state");
    }
    if (pass.kind() != PassKind.COMPUTE
        && primitive.topology() == PrimitiveTopology.NONE) {
      throw new IllegalArgumentException(
          "graphics state requires a primitive topology");
    }
  }

  private static <T> List<T> sortedUnique(List<T> input,
      Comparator<T> comparator, java.util.function.ToIntFunction<T> key,
      int maximum, String label) {
    Objects.requireNonNull(input, label + "s");
    if (input.size() > maximum) {
      throw new IllegalArgumentException(label + " count exceeds " + maximum);
    }
    ArrayList<T> copy = new ArrayList<>(input.size());
    for (T value : input) {
      copy.add(Objects.requireNonNull(value, label));
    }
    copy.sort(comparator);
    int previous = -1;
    for (T value : copy) {
      int current = key.applyAsInt(value);
      if (current < 0 || current == previous) {
        throw new IllegalArgumentException(
            label + " indices must be non-negative and unique");
      }
      previous = current;
    }
    return List.copyOf(copy);
  }

  private static List<FunctionConstant> sortedFunctionConstants(
      List<FunctionConstant> input) {
    Objects.requireNonNull(input, "functionConstants");
    if (input.size() > MAX_FUNCTION_CONSTANTS) {
      throw new IllegalArgumentException(
          "function constant count exceeds " + MAX_FUNCTION_CONSTANTS);
    }
    ArrayList<FunctionConstant> copy = new ArrayList<>(input.size());
    for (FunctionConstant value : input) {
      copy.add(Objects.requireNonNull(value, "functionConstant"));
    }
    copy.sort(Comparator.comparing(FunctionConstant::stage)
        .thenComparingInt(FunctionConstant::constantId));
    FunctionConstant previous = null;
    for (FunctionConstant value : copy) {
      if (previous != null && previous.stage() == value.stage()
          && previous.constantId() == value.constantId()) {
        throw new IllegalArgumentException(
            "function constant stage/id pairs must be unique");
      }
      previous = value;
    }
    return List.copyOf(copy);
  }

  public record PassIdentity(PassKind kind, String passIdSha256,
                             boolean fallback) {
    public PassIdentity {
      Objects.requireNonNull(kind, "kind");
      requireSha256(passIdSha256, "passIdSha256");
    }
  }

  public record DataFormat(String cacheName) {
    public DataFormat {
      requireCacheName(cacheName, "data format");
    }
  }

  public record VertexBufferLayout(int bufferIndex, int strideBytes,
                                   StepFunction stepFunction, int stepRate) {
    public VertexBufferLayout {
      if (bufferIndex < 0 || strideBytes <= 0 || strideBytes > 65_536
          || stepRate < 0) {
        throw new IllegalArgumentException("invalid vertex buffer layout");
      }
      Objects.requireNonNull(stepFunction, "stepFunction");
      if (stepFunction == StepFunction.PER_VERTEX && stepRate != 0) {
        throw new IllegalArgumentException(
            "per-vertex layouts must use step rate zero");
      }
      if (stepFunction == StepFunction.PER_INSTANCE && stepRate <= 0) {
        throw new IllegalArgumentException(
            "per-instance layouts require a positive step rate");
      }
    }
  }

  public record VertexAttribute(int location, int bufferIndex,
                                int offsetBytes, DataFormat format) {
    public VertexAttribute {
      if (location < 0 || bufferIndex < 0 || offsetBytes < 0) {
        throw new IllegalArgumentException("invalid vertex attribute");
      }
      Objects.requireNonNull(format, "format");
    }
  }

  public record ColorAttachment(int slot, DataFormat format, int writeMask,
                                BlendState blend) {
    public ColorAttachment {
      if (slot < 0 || (writeMask & ~0xF) != 0) {
        throw new IllegalArgumentException("invalid color attachment");
      }
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(blend, "blend");
    }
  }

  public record BlendState(boolean enabled, BlendEquation rgb,
                           BlendEquation alpha) {
    public BlendState {
      Objects.requireNonNull(rgb, "rgb");
      Objects.requireNonNull(alpha, "alpha");
      if (!enabled) {
        rgb = BlendEquation.disabledCanonical();
        alpha = BlendEquation.disabledCanonical();
      }
    }

    public static BlendState disabled() {
      BlendEquation canonical = BlendEquation.disabledCanonical();
      return new BlendState(false, canonical, canonical);
    }
  }

  public record BlendEquation(BlendOperation operation, BlendFactor source,
                              BlendFactor destination) {
    public BlendEquation {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(destination, "destination");
    }

    private static BlendEquation disabledCanonical() {
      return new BlendEquation(BlendOperation.ADD, BlendFactor.ONE,
          BlendFactor.ZERO);
    }
  }

  public record DepthState(boolean testEnabled, CompareOperation compare,
                           boolean writeEnabled) {
    public DepthState {
      Objects.requireNonNull(compare, "compare");
      if (!testEnabled) {
        compare = CompareOperation.ALWAYS;
      }
    }
  }

  public record StencilState(boolean enabled, StencilFace front,
                             StencilFace back) {
    public StencilState {
      Objects.requireNonNull(front, "front");
      Objects.requireNonNull(back, "back");
      if (!enabled) {
        front = StencilFace.disabledCanonical();
        back = StencilFace.disabledCanonical();
      }
    }

    public static StencilState disabled() {
      StencilFace face = StencilFace.disabledCanonical();
      return new StencilState(false, face, face);
    }
  }

  public record StencilFace(CompareOperation compare,
                            StencilOperation stencilFail,
                            StencilOperation depthFail,
                            StencilOperation pass, int readMask,
                            int writeMask, int reference) {
    public StencilFace {
      Objects.requireNonNull(compare, "compare");
      Objects.requireNonNull(stencilFail, "stencilFail");
      Objects.requireNonNull(depthFail, "depthFail");
      Objects.requireNonNull(pass, "pass");
    }

    private static StencilFace disabledCanonical() {
      return new StencilFace(CompareOperation.ALWAYS, StencilOperation.KEEP,
          StencilOperation.KEEP, StencilOperation.KEEP, 0xFF, 0xFF, 0);
    }
  }

  public record RasterState(boolean rasterizationEnabled, CullMode cullMode,
                            FrontFace frontFace, FillMode frontFillMode,
                            FillMode backFillMode,
                            DepthClipMode depthClipMode,
                            int polygonOffsetModeMask, float depthBias,
                            float slopeScale, float depthBiasClamp) {
    public RasterState {
      Objects.requireNonNull(cullMode, "cullMode");
      Objects.requireNonNull(frontFace, "frontFace");
      Objects.requireNonNull(frontFillMode, "frontFillMode");
      Objects.requireNonNull(backFillMode, "backFillMode");
      Objects.requireNonNull(depthClipMode, "depthClipMode");
      if ((polygonOffsetModeMask & ~0x7) != 0) {
        throw new IllegalArgumentException(
            "polygon offset mode mask must fit three bits");
      }
      depthBias = finiteNormalized(depthBias, "depthBias");
      slopeScale = finiteNormalized(slopeScale, "slopeScale");
      depthBiasClamp = finiteNormalized(depthBiasClamp, "depthBiasClamp");
    }
  }

  public record PrimitiveState(PrimitiveTopology topology,
                               PrimitiveRestartMode restartMode,
                               int patchControlPoints) {
    public PrimitiveState {
      Objects.requireNonNull(topology, "topology");
      Objects.requireNonNull(restartMode, "restartMode");
      if (topology == PrimitiveTopology.PATCH) {
        if (patchControlPoints <= 0 || patchControlPoints > 32) {
          throw new IllegalArgumentException(
              "patch topology requires 1..32 control points");
        }
      } else if (patchControlPoints != 0) {
        throw new IllegalArgumentException(
            "non-patch topology cannot have patch control points");
      }
    }
  }

  public record FunctionConstant(IrisShaderStage stage, int constantId,
                                 ScalarType type, long rawBits) {
    public FunctionConstant {
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(type, "type");
      if (constantId < 0) {
        throw new IllegalArgumentException(
            "function constant id must be non-negative");
      }
      if (type == ScalarType.BOOL && rawBits != 0L && rawBits != 1L) {
        throw new IllegalArgumentException(
            "boolean function constants must use raw bits 0 or 1");
      }
      if ((type == ScalarType.INT32 || type == ScalarType.UINT32
          || type == ScalarType.FLOAT32)
          && (rawBits & 0xffffffff00000000L) != 0) {
        throw new IllegalArgumentException(
            "32-bit function constant contains high bits");
      }
    }
  }

  public interface CacheNamed {
    String cacheName();
  }

  public enum PassKind implements CacheNamed {
    LINKED_GRAPHICS("linked-graphics"),
    FULLSCREEN_GRAPHICS("fullscreen-graphics"),
    COMPUTE("compute");

    private final String cacheName;

    PassKind(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum StepFunction implements CacheNamed {
    PER_VERTEX("per-vertex"),
    PER_INSTANCE("per-instance");

    private final String cacheName;

    StepFunction(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum BlendOperation implements CacheNamed {
    ADD("add"), SUBTRACT("subtract"), REVERSE_SUBTRACT("reverse-subtract"),
    MIN("min"), MAX("max");

    private final String cacheName;

    BlendOperation(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum BlendFactor implements CacheNamed {
    ZERO("zero"), ONE("one"), SRC_COLOR("src-color"),
    ONE_MINUS_SRC_COLOR("one-minus-src-color"), DST_COLOR("dst-color"),
    ONE_MINUS_DST_COLOR("one-minus-dst-color"), SRC_ALPHA("src-alpha"),
    ONE_MINUS_SRC_ALPHA("one-minus-src-alpha"), DST_ALPHA("dst-alpha"),
    ONE_MINUS_DST_ALPHA("one-minus-dst-alpha"),
    CONSTANT_COLOR("constant-color"),
    ONE_MINUS_CONSTANT_COLOR("one-minus-constant-color"),
    CONSTANT_ALPHA("constant-alpha"),
    ONE_MINUS_CONSTANT_ALPHA("one-minus-constant-alpha"),
    SRC_ALPHA_SATURATE("src-alpha-saturate"), SRC1_COLOR("src1-color"),
    ONE_MINUS_SRC1_COLOR("one-minus-src1-color"),
    SRC1_ALPHA("src1-alpha"),
    ONE_MINUS_SRC1_ALPHA("one-minus-src1-alpha");

    private final String cacheName;

    BlendFactor(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum CompareOperation implements CacheNamed {
    NEVER("never"), LESS("less"), EQUAL("equal"),
    LESS_EQUAL("less-equal"), GREATER("greater"), NOT_EQUAL("not-equal"),
    GREATER_EQUAL("greater-equal"), ALWAYS("always");

    private final String cacheName;

    CompareOperation(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum StencilOperation implements CacheNamed {
    KEEP("keep"), ZERO("zero"), REPLACE("replace"),
    INCREMENT_CLAMP("increment-clamp"), DECREMENT_CLAMP("decrement-clamp"),
    INVERT("invert"), INCREMENT_WRAP("increment-wrap"),
    DECREMENT_WRAP("decrement-wrap");

    private final String cacheName;

    StencilOperation(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum CullMode implements CacheNamed {
    NONE("none"), FRONT("front"), BACK("back"),
    FRONT_AND_BACK("front-and-back");

    private final String cacheName;

    CullMode(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum PrimitiveRestartMode implements CacheNamed {
    NONE("none"), EXPLICIT_INDEX("explicit-index"),
    FIXED_INDEX("fixed-index"), BOTH("both");

    private final String cacheName;

    PrimitiveRestartMode(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum FrontFace implements CacheNamed {
    CLOCKWISE("clockwise"), COUNTER_CLOCKWISE("counter-clockwise");

    private final String cacheName;

    FrontFace(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum FillMode implements CacheNamed {
    FILL("fill"), LINES("lines"), POINTS("points");

    private final String cacheName;

    FillMode(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum DepthClipMode implements CacheNamed {
    CLIP("clip"), CLAMP("clamp");

    private final String cacheName;

    DepthClipMode(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum PrimitiveTopology implements CacheNamed {
    POINT("point"), LINE("line"), LINE_LOOP("line-loop"),
    LINE_STRIP("line-strip"), TRIANGLE("triangle"),
    TRIANGLE_STRIP("triangle-strip"), TRIANGLE_FAN("triangle-fan"),
    PATCH("patch"), NONE("none");

    private final String cacheName;

    PrimitiveTopology(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum ScalarType implements CacheNamed {
    BOOL("bool"), INT32("int32"), UINT32("uint32"), FLOAT32("float32"),
    INT64("int64"), UINT64("uint64"), FLOAT64("float64");

    private final String cacheName;

    ScalarType(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  private static float finiteNormalized(float value, String label) {
    if (!Float.isFinite(value)) {
      throw new IllegalArgumentException(label + " must be finite");
    }
    return value == 0.0F ? 0.0F : value;
  }

  private static void requireSha256(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          label + " must be lowercase SHA-256");
    }
  }

  private static void requireCacheName(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException(label + " is not cache-safe");
    }
  }
}

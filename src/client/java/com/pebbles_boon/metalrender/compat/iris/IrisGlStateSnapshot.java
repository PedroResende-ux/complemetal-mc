package com.pebbles_boon.metalrender.compat.iris;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable OpenGL state observed at an Iris draw or compute dispatch.
 *
 * <p>Unknown values are represented explicitly. Consumers must not substitute
 * OpenGL defaults for them: a snapshot with a required unknown value is not a
 * safe input for a Metal pipeline cache.</p>
 */
public record IrisGlStateSnapshot(
    long sequence,
    Operation operation,
    StateValue<Optional<ResourceHandle>> program,
    StateValue<ResourceHandle> drawFramebuffer,
    StateValue<List<Integer>> drawBuffers,
    List<ColorTarget> colorTargets,
    StateValue<Optional<TextureAttachment>> depthAttachment,
    StateValue<Optional<TextureAttachment>> stencilAttachment,
    DepthState depth,
    StencilState stencil,
    RasterState raster,
    MultisampleState multisample,
    PrimitiveState primitive,
    List<String> unknownFields,
    long resourceEvictions) {

  public IrisGlStateSnapshot {
    if (sequence < 0 || resourceEvictions < 0) {
      throw new IllegalArgumentException("negative snapshot counter");
    }
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(program, "program");
    Objects.requireNonNull(drawFramebuffer, "drawFramebuffer");
    Objects.requireNonNull(drawBuffers, "drawBuffers");
    Objects.requireNonNull(depthAttachment, "depthAttachment");
    Objects.requireNonNull(stencilAttachment, "stencilAttachment");
    Objects.requireNonNull(depth, "depth");
    Objects.requireNonNull(stencil, "stencil");
    Objects.requireNonNull(raster, "raster");
    Objects.requireNonNull(multisample, "multisample");
    Objects.requireNonNull(primitive, "primitive");
    colorTargets = immutableNonNull(colorTargets, "colorTargets");
    unknownFields = immutableNonNull(unknownFields, "unknownFields");
  }

  /** Returns whether every state value required by this operation is known. */
  public boolean complete() {
    return unknownFields.isEmpty();
  }

  private static <T> List<T> immutableNonNull(List<T> values, String label) {
    Objects.requireNonNull(values, label);
    for (T value : values) {
      Objects.requireNonNull(value, label + " entry");
    }
    return List.copyOf(values);
  }

  public enum Operation {
    DRAW,
    DISPATCH
  }

  public static final int MAX_COLOR_ATTACHMENTS = 8;

  public enum ResourceKind {
    PROGRAM,
    FRAMEBUFFER,
    TEXTURE
  }

  /**
   * Generation-qualified GL name. GL names may be reused, so an integer name
   * alone cannot safely identify an object across deletion or context reset.
   */
  public record ResourceHandle(ResourceKind kind, int name, long generation,
                               long contextGeneration) {
    public ResourceHandle {
      Objects.requireNonNull(kind, "kind");
      if (name < 0 || generation < 0 || contextGeneration <= 0) {
        throw new IllegalArgumentException("invalid GL resource handle");
      }
      if (kind != ResourceKind.FRAMEBUFFER && name == 0) {
        throw new IllegalArgumentException(
            "only the default framebuffer may use GL name zero");
      }
    }
  }

  /** A value that is either known or carries an exact reason it is unknown. */
  public record StateValue<T>(T value, String unknownReason) {
    public StateValue {
      boolean hasReason = unknownReason != null;
      if (hasReason == (value != null)) {
        throw new IllegalArgumentException(
            "state value must contain exactly one of value or unknown reason");
      }
      if (hasReason && unknownReason.isBlank()) {
        throw new IllegalArgumentException("unknown reason must not be blank");
      }
    }

    public static <T> StateValue<T> known(T value) {
      return new StateValue<>(Objects.requireNonNull(value, "value"), null);
    }

    public static <T> StateValue<T> unknown(String reason) {
      return new StateValue<>(null, Objects.requireNonNull(reason, "reason"));
    }

    public boolean isKnown() {
      return unknownReason == null;
    }

    public T requireKnown() {
      if (!isKnown()) {
        throw new IllegalStateException(unknownReason);
      }
      return value;
    }
  }

  /** Texture identity and metadata captured at attachment time. */
  public record TextureAttachment(ResourceHandle texture, String format,
                                  StateValue<Integer> sampleCount,
                                  int textureTarget, int mipLevel) {
    public TextureAttachment {
      Objects.requireNonNull(texture, "texture");
      if (texture.kind() != ResourceKind.TEXTURE) {
        throw new IllegalArgumentException("attachment is not a texture");
      }
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(sampleCount, "sampleCount");
      if (format.isBlank() || format.length() > 128 || mipLevel < 0) {
        throw new IllegalArgumentException("invalid texture attachment");
      }
    }
  }

  /** Effective per-draw-buffer state after indexed overrides are applied. */
  public record ColorTarget(
      int index,
      StateValue<Integer> drawBuffer,
      StateValue<Optional<TextureAttachment>> attachment,
      BlendState blend,
      StateValue<ColorMask> colorMask) {
    public ColorTarget {
      if (index < 0 || index >= MAX_COLOR_ATTACHMENTS) {
        throw new IllegalArgumentException("invalid color target index");
      }
      Objects.requireNonNull(drawBuffer, "drawBuffer");
      Objects.requireNonNull(attachment, "attachment");
      Objects.requireNonNull(blend, "blend");
      Objects.requireNonNull(colorMask, "colorMask");
    }
  }

  public record BlendState(
      StateValue<Boolean> enabled,
      StateValue<Integer> rgbEquation,
      StateValue<Integer> alphaEquation,
      StateValue<Integer> sourceRgb,
      StateValue<Integer> destinationRgb,
      StateValue<Integer> sourceAlpha,
      StateValue<Integer> destinationAlpha) {
    public BlendState {
      Objects.requireNonNull(enabled, "enabled");
      Objects.requireNonNull(rgbEquation, "rgbEquation");
      Objects.requireNonNull(alphaEquation, "alphaEquation");
      Objects.requireNonNull(sourceRgb, "sourceRgb");
      Objects.requireNonNull(destinationRgb, "destinationRgb");
      Objects.requireNonNull(sourceAlpha, "sourceAlpha");
      Objects.requireNonNull(destinationAlpha, "destinationAlpha");
    }
  }

  public record ColorMask(boolean red, boolean green, boolean blue,
                          boolean alpha) {
    public int bits() {
      return (red ? 1 : 0) | (green ? 2 : 0) | (blue ? 4 : 0)
          | (alpha ? 8 : 0);
    }
  }

  public record DepthState(StateValue<Boolean> testEnabled,
                           StateValue<Integer> function,
                           StateValue<Boolean> writeEnabled) {
    public DepthState {
      Objects.requireNonNull(testEnabled, "testEnabled");
      Objects.requireNonNull(function, "function");
      Objects.requireNonNull(writeEnabled, "writeEnabled");
    }
  }

  public record StencilState(StateValue<Boolean> testEnabled,
                             StencilFace front, StencilFace back) {
    public StencilState {
      Objects.requireNonNull(testEnabled, "testEnabled");
      Objects.requireNonNull(front, "front");
      Objects.requireNonNull(back, "back");
    }
  }

  public record StencilFace(StateValue<Integer> function,
                            StateValue<Integer> reference,
                            StateValue<Integer> readMask,
                            StateValue<Integer> stencilFail,
                            StateValue<Integer> depthFail,
                            StateValue<Integer> depthPass,
                            StateValue<Integer> writeMask) {
    public StencilFace {
      Objects.requireNonNull(function, "function");
      Objects.requireNonNull(reference, "reference");
      Objects.requireNonNull(readMask, "readMask");
      Objects.requireNonNull(stencilFail, "stencilFail");
      Objects.requireNonNull(depthFail, "depthFail");
      Objects.requireNonNull(depthPass, "depthPass");
      Objects.requireNonNull(writeMask, "writeMask");
    }
  }

  public record MultisampleState(
      StateValue<Integer> rasterSampleCount,
      StateValue<Boolean> sampleCoverageEnabled,
      StateValue<Float> sampleCoverageValue,
      StateValue<Boolean> sampleCoverageInvert,
      StateValue<Boolean> sampleMaskEnabled,
      StateValue<Integer> sampleMaskWord0,
      StateValue<Integer> sampleMaskWord1,
      StateValue<Boolean> alphaToCoverageEnabled,
      StateValue<Boolean> alphaToOneEnabled) {
    public MultisampleState {
      Objects.requireNonNull(rasterSampleCount, "rasterSampleCount");
      Objects.requireNonNull(sampleCoverageEnabled,
          "sampleCoverageEnabled");
      Objects.requireNonNull(sampleCoverageValue, "sampleCoverageValue");
      Objects.requireNonNull(sampleCoverageInvert, "sampleCoverageInvert");
      Objects.requireNonNull(sampleMaskEnabled, "sampleMaskEnabled");
      Objects.requireNonNull(sampleMaskWord0, "sampleMaskWord0");
      Objects.requireNonNull(sampleMaskWord1, "sampleMaskWord1");
      Objects.requireNonNull(alphaToCoverageEnabled,
          "alphaToCoverageEnabled");
      Objects.requireNonNull(alphaToOneEnabled, "alphaToOneEnabled");
    }
  }

  public record PrimitiveState(StateValue<Integer> mode,
                               StateValue<Boolean> restartEnabled,
                               StateValue<Boolean> fixedIndexRestartEnabled) {
    public PrimitiveState {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(restartEnabled, "restartEnabled");
      Objects.requireNonNull(fixedIndexRestartEnabled,
          "fixedIndexRestartEnabled");
    }
  }

  public record RasterState(
      StateValue<Boolean> cullEnabled,
      StateValue<Integer> cullMode,
      StateValue<Integer> frontFace,
      StateValue<Boolean> depthClampEnabled,
      StateValue<Boolean> rasterizerDiscardEnabled,
      StateValue<Integer> polygonModeFront,
      StateValue<Integer> polygonModeBack,
      StateValue<Boolean> polygonOffsetPointEnabled,
      StateValue<Boolean> polygonOffsetLineEnabled,
      StateValue<Boolean> polygonOffsetFillEnabled,
      StateValue<Float> polygonOffsetFactor,
      StateValue<Float> polygonOffsetUnits,
      StateValue<Float> polygonOffsetClamp) {
    public RasterState {
      Objects.requireNonNull(cullEnabled, "cullEnabled");
      Objects.requireNonNull(cullMode, "cullMode");
      Objects.requireNonNull(frontFace, "frontFace");
      Objects.requireNonNull(depthClampEnabled, "depthClampEnabled");
      Objects.requireNonNull(rasterizerDiscardEnabled,
          "rasterizerDiscardEnabled");
      Objects.requireNonNull(polygonModeFront, "polygonModeFront");
      Objects.requireNonNull(polygonModeBack, "polygonModeBack");
      Objects.requireNonNull(polygonOffsetPointEnabled,
          "polygonOffsetPointEnabled");
      Objects.requireNonNull(polygonOffsetLineEnabled,
          "polygonOffsetLineEnabled");
      Objects.requireNonNull(polygonOffsetFillEnabled,
          "polygonOffsetFillEnabled");
      Objects.requireNonNull(polygonOffsetFactor, "polygonOffsetFactor");
      Objects.requireNonNull(polygonOffsetUnits, "polygonOffsetUnits");
      Objects.requireNonNull(polygonOffsetClamp, "polygonOffsetClamp");
    }
  }
}

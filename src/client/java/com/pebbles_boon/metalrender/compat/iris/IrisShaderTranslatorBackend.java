package com.pebbles_boon.metalrender.compat.iris;

/**
 * Pluggable GLSL-to-SPIR-V-to-MSL translator.
 *
 * <p>Discovery must be side-effect free. Translation is only called by a
 * future explicit opt-in coordinator; capture itself never executes a
 * backend.</p>
 */
public interface IrisShaderTranslatorBackend {
  String id();

  IrisTranslationProfile profile();

  Availability discover();

  IrisShaderTranslation translate(IrisFinalShaderProgram program)
      throws IrisShaderTranslationException;

  record Availability(boolean available, String detail) {
    public Availability {
      if (detail == null || detail.isBlank()) {
        throw new IllegalArgumentException("detail must not be blank");
      }
    }
  }

  default StageSupport support(IrisShaderStage stage) {
    return StageSupport.SUPPORTED;
  }

  enum StageSupport {
    SUPPORTED,
    UNSUPPORTED_KEEP_IRIS_OPENGL
  }
}

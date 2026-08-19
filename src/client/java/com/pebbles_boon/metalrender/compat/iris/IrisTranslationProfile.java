package com.pebbles_boon.metalrender.compat.iris;

import java.util.Objects;

/**
 * Every translation choice that can change SPIR-V or MSL output.
 *
 * <p>The canonical value is part of the content-addressed cache key, preventing
 * artifacts produced by different translator versions or options from
 * aliasing.</p>
 */
public record IrisTranslationProfile(String canonicalValue) {
  public static final IrisTranslationProfile
      LWJGL_3_4_1_METAL_3_ARGUMENT_BUFFERS =
      new IrisTranslationProfile(
          "metalrender-translator=4;lwjgl=3.4.1;shaderc-source=glsl;"
              + "shaderc-env=opengl4.5;spirv=1.0;"
              + "glsl-version-floor=330;"
              + "optimization=performance;auto-bind-uniforms=true;"
              + "auto-map-locations=true;entry=main;spvc=3.4.1;"
              + "msl-platform=macos;msl=3.0;argument-buffers=true;"
              + "argument-buffer-tier=2");

  public IrisTranslationProfile {
    Objects.requireNonNull(canonicalValue, "canonicalValue");
    if (canonicalValue.isBlank() || canonicalValue.length() > 512
        || canonicalValue.indexOf('\n') >= 0
        || canonicalValue.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("invalid translation profile");
    }
  }

  public String sha256() {
    return IrisShaderCacheKey.sha256(canonicalValue);
  }
}

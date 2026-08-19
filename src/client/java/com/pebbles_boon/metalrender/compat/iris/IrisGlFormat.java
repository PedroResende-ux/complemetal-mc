package com.pebbles_boon.metalrender.compat.iris;

import java.util.Map;
import java.util.Optional;

/** Stable cache spelling for sized OpenGL internal texture formats. */
public final class IrisGlFormat {
  private static final Map<Integer, String> FORMATS = Map.ofEntries(
      entry(0x8229, "r8-unorm"), entry(0x8F94, "r8-snorm"),
      entry(0x822B, "rg8-unorm"), entry(0x8F95, "rg8-snorm"),
      entry(0x8051, "rgb8-unorm"), entry(0x8F96, "rgb8-snorm"),
      entry(0x8058, "rgba8-unorm"), entry(0x8F97, "rgba8-snorm"),
      entry(0x822A, "r16-unorm"), entry(0x8F98, "r16-snorm"),
      entry(0x822C, "rg16-unorm"), entry(0x8F99, "rg16-snorm"),
      entry(0x8054, "rgb16-unorm"), entry(0x8F9A, "rgb16-snorm"),
      entry(0x805B, "rgba16-unorm"), entry(0x8F9B, "rgba16-snorm"),
      entry(0x822D, "r16-float"), entry(0x822F, "rg16-float"),
      entry(0x881B, "rgb16-float"), entry(0x881A, "rgba16-float"),
      entry(0x822E, "r32-float"), entry(0x8230, "rg32-float"),
      entry(0x8815, "rgb32-float"), entry(0x8814, "rgba32-float"),
      entry(0x8231, "r8-sint"), entry(0x8232, "r8-uint"),
      entry(0x8237, "rg8-sint"), entry(0x8238, "rg8-uint"),
      entry(0x8D8F, "rgb8-sint"), entry(0x8D7D, "rgb8-uint"),
      entry(0x8D8E, "rgba8-sint"), entry(0x8D7C, "rgba8-uint"),
      entry(0x8233, "r16-sint"), entry(0x8234, "r16-uint"),
      entry(0x8239, "rg16-sint"), entry(0x823A, "rg16-uint"),
      entry(0x8D89, "rgb16-sint"), entry(0x8D77, "rgb16-uint"),
      entry(0x8D88, "rgba16-sint"), entry(0x8D76, "rgba16-uint"),
      entry(0x8235, "r32-sint"), entry(0x8236, "r32-uint"),
      entry(0x823B, "rg32-sint"), entry(0x823C, "rg32-uint"),
      entry(0x8D83, "rgb32-sint"), entry(0x8D71, "rgb32-uint"),
      entry(0x8D82, "rgba32-sint"), entry(0x8D70, "rgba32-uint"),
      entry(0x8059, "rgb10a2-unorm"), entry(0x906F, "rgb10a2-uint"),
      entry(0x8C3A, "rg11b10-float"), entry(0x8C3D, "rgb9e5-float"),
      entry(0x81A5, "d16-unorm"), entry(0x81A6, "d24-unorm"),
      entry(0x8CAC, "d32-float"),
      entry(0x88F0, "d24-unorm-s8-uint"),
      entry(0x8CAD, "d32-float-s8-uint"),
      entry(0x8D48, "s8-uint"),
      entry(0x8057, "rgb5a1-unorm"), entry(0x8D62, "rgb565-unorm"),
      entry(0x8056, "rgba4-unorm")
  );

  private IrisGlFormat() {
  }

  public static Optional<String> cacheName(int internalFormat) {
    return Optional.ofNullable(FORMATS.get(internalFormat));
  }

  private static Map.Entry<Integer, String> entry(int gl, String name) {
    return Map.entry(gl, name);
  }
}

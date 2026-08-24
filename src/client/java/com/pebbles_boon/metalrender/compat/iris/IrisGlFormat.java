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

  /** Bytes in one uncompressed texel for formats whose storage is exact. */
  public static Optional<Integer> bytesPerPixel(int internalFormat) {
    return switch (internalFormat) {
      case 0x8229, 0x8F94, 0x8231, 0x8232, 0x8D48 -> Optional.of(1);
      case 0x822B, 0x8F95, 0x822A, 0x8F98, 0x822D, 0x8233, 0x8234,
          0x8237, 0x8238, 0x81A5, 0x8057, 0x8D62, 0x8056 ->
          Optional.of(2);
      case 0x8051, 0x8F96, 0x8D8F, 0x8D7D -> Optional.of(3);
      case 0x8058, 0x8F97, 0x822C, 0x8F99, 0x822E, 0x8235, 0x8236,
          0x8239, 0x823A, 0x8059, 0x906F, 0x8C3A, 0x8C3D, 0x81A6,
          0x8CAC, 0x88F0 -> Optional.of(4);
      case 0x8054, 0x8F9A, 0x881B, 0x8D89, 0x8D77 -> Optional.of(6);
      case 0x805B, 0x8F9B, 0x881A, 0x822F, 0x8230, 0x823B, 0x823C,
          0x8D88, 0x8D76, 0x8CAD -> Optional.of(8);
      case 0x8815, 0x8D83, 0x8D71 -> Optional.of(12);
      case 0x8814, 0x8D82, 0x8D70 -> Optional.of(16);
      default -> Optional.empty();
    };
  }

  /**
   * Returns the texel width only when GL can consume the source bytes without
   * numeric conversion. Packed and depth/stencil upload types remain blocked.
   */
  public static Optional<Integer> exactUploadBytesPerPixel(int internalFormat,
      int externalFormat, int type) {
    int components = switch (externalFormat) {
      case 0x1903, 0x8D94 -> 1; // RED / RED_INTEGER
      case 0x8227, 0x8228 -> 2; // RG / RG_INTEGER
      case 0x1907, 0x8D98 -> 3; // RGB / RGB_INTEGER
      case 0x1908, 0x8D99 -> 4; // RGBA / RGBA_INTEGER
      default -> 0;
    };
    int componentBytes = switch (type) {
      case 0x1400, 0x1401 -> 1; // BYTE / UNSIGNED_BYTE
      case 0x1402, 0x1403, 0x140B -> 2; // SHORT / USHORT / HALF_FLOAT
      case 0x1404, 0x1405, 0x1406 -> 4; // INT / UINT / FLOAT
      default -> 0;
    };
    if (components == 0 || componentBytes == 0
        || !sourceClassMatches(internalFormat, externalFormat, type)) {
      return Optional.empty();
    }
    int sourceBytes = components * componentBytes;
    return bytesPerPixel(internalFormat)
        .filter(bytes -> bytes == sourceBytes);
  }

  private static boolean sourceClassMatches(int internalFormat,
      int externalFormat, int type) {
    boolean integerExternal = externalFormat == 0x8D94
        || externalFormat == 0x8228 || externalFormat == 0x8D98
        || externalFormat == 0x8D99;
    return switch (internalFormat) {
      case 0x8229, 0x822B, 0x8051, 0x8058 ->
          !integerExternal && type == 0x1401;
      case 0x8F94, 0x8F95, 0x8F96, 0x8F97 ->
          !integerExternal && type == 0x1400;
      case 0x822A, 0x822C, 0x8054, 0x805B ->
          !integerExternal && type == 0x1403;
      case 0x8F98, 0x8F99, 0x8F9A, 0x8F9B ->
          !integerExternal && type == 0x1402;
      case 0x822D, 0x822F, 0x881B, 0x881A ->
          !integerExternal && type == 0x140B;
      case 0x822E, 0x8230, 0x8815, 0x8814 ->
          !integerExternal && type == 0x1406;
      case 0x8231, 0x8237, 0x8D8F, 0x8D8E ->
          integerExternal && type == 0x1400;
      case 0x8232, 0x8238, 0x8D7D, 0x8D7C ->
          integerExternal && type == 0x1401;
      case 0x8233, 0x8239, 0x8D89, 0x8D88 ->
          integerExternal && type == 0x1402;
      case 0x8234, 0x823A, 0x8D77, 0x8D76 ->
          integerExternal && type == 0x1403;
      case 0x8235, 0x823B, 0x8D83, 0x8D82 ->
          integerExternal && type == 0x1404;
      case 0x8236, 0x823C, 0x8D71, 0x8D70 ->
          integerExternal && type == 0x1405;
      default -> false;
    };
  }

  private static Map.Entry<Integer, String> entry(int gl, String name) {
    return Map.entry(gl, name);
  }
}

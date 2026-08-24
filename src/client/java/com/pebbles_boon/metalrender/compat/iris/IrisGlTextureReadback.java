package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;

/** Bounded 2D texture readback used only by opt-in offscreen replay QA. */
final class IrisGlTextureReadback {
  static final int GL_TEXTURE_2D = 0x0DE1;
  private static final int GL_TEXTURE_BINDING_2D = 0x8069;
  private static final ThreadLocal<String> LAST_FAILURE =
      ThreadLocal.withInitial(() -> "not-attempted");

  private IrisGlTextureReadback() {
  }

  static Optional<IrisGlTextureMirror.TextureSnapshot> capture(int texture,
      long generation, int target, int mipLevel, int layer,
      IrisGlTextureMirror mirror) {
    LAST_FAILURE.set("not-attempted");
    if (!IrisGlBufferMirror.isEnabled() || texture <= 0
        || target != GL_TEXTURE_2D || mipLevel < 0 || layer != 0) {
      return unavailable("capture-input-ineligible");
    }
    IrisGlTextureMirror.TextureMetadata metadata = mirror.metadata(texture,
        mipLevel, layer).orElse(null);
    if (metadata == null) {
      return unavailable("mirror-metadata-unavailable");
    }
    if (metadata.depthOrLayers() != 1) {
      return unavailable("layered-texture-unimplemented");
    }
    if (generation > 0 && metadata.generation() != generation) {
      return unavailable("mirror-generation-mismatch-expected-" + generation
          + "-actual-" + metadata.generation());
    }
    ReadFormat read = readFormat(metadata.format()).orElse(null);
    if (read == null) {
      return unavailable("read-format-unavailable-" + metadata.format());
    }
    String capturedFormat = captureFormat(metadata.format());
    if (read.bytesPerPixel() != metadata.bytesPerPixel()
        && capturedFormat.equals(metadata.format())) {
      return unavailable("read-bpp-mismatch-format-" + read.bytesPerPixel()
          + "-mirror-" + metadata.bytesPerPixel());
    }
    long imageBytes = (long) metadata.width() * metadata.height()
        * read.bytesPerPixel();
    if (imageBytes <= 0
        || imageBytes > IrisShadowReplayTextureSnapshot.MAX_TOTAL_BYTES
        || imageBytes > Integer.MAX_VALUE) {
      return unavailable("readback-byte-bound-" + imageBytes);
    }
    int previousTexture = 0;
    int previousPackBuffer = 0;
    int previousPackAlignment = 4;
    int previousPackRowLength = 0;
    int previousPackSkipRows = 0;
    int previousPackSkipPixels = 0;
    try {
      previousTexture = GL11C.glGetInteger(GL_TEXTURE_BINDING_2D);
      previousPackBuffer = GL11C.glGetInteger(
          GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
      previousPackAlignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
      previousPackRowLength = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH);
      previousPackSkipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS);
      previousPackSkipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS);
      GL11C.glBindTexture(target, texture);
      int actualWidth = GL11C.glGetTexLevelParameteri(target, mipLevel,
          GL11C.GL_TEXTURE_WIDTH);
      int actualHeight = GL11C.glGetTexLevelParameteri(target, mipLevel,
          GL11C.GL_TEXTURE_HEIGHT);
      if (actualWidth != metadata.width()
          || actualHeight != metadata.height()) {
        return unavailable("gl-extent-mismatch-expected-" + metadata.width()
            + "x" + metadata.height() + "-actual-" + actualWidth + "x"
            + actualHeight);
      }
      GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
      GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
      ByteBuffer destination = ByteBuffer.allocateDirect(
          Math.toIntExact(imageBytes)).order(ByteOrder.nativeOrder());
      GL11C.glGetTexImage(target, mipLevel, read.externalFormat(),
          read.type(), destination);
      byte[] bytes = new byte[destination.capacity()];
      destination.position(0);
      destination.get(bytes);
      long generationTag = generation > 0 ? generation
          : metadata.generation();
      IrisGlTextureMirror.TextureMetadata capturedMetadata =
          new IrisGlTextureMirror.TextureMetadata(capturedFormat,
              metadata.width(), metadata.height(), metadata.depthOrLayers(),
              read.bytesPerPixel(), generationTag);
      LAST_FAILURE.set("");
      return Optional.of(IrisGlTextureMirror.TextureSnapshot.fromReadback(
          texture, generationTag, capturedMetadata, layer, mipLevel, bytes));
    } catch (RuntimeException | LinkageError unavailable) {
      return unavailable("readback-exception-"
          + unavailable.getClass().getSimpleName());
    } finally {
      try {
        GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT,
            previousPackAlignment);
        GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH,
            previousPackRowLength);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS,
            previousPackSkipRows);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS,
            previousPackSkipPixels);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
        GL11C.glBindTexture(target, previousTexture);
      } catch (RuntimeException | LinkageError ignored) {
        // The primary capture failure remains fail-closed.
      }
    }
  }

  static String lastFailure() {
    return LAST_FAILURE.get();
  }

  private static Optional<IrisGlTextureMirror.TextureSnapshot> unavailable(
      String reason) {
    LAST_FAILURE.set(reason);
    return Optional.empty();
  }

  static Optional<ReadFormat> readFormat(String format) {
    return switch (format) {
      case "r8-unorm" -> format(0x1903, 0x1401, 1);
      case "rg8-unorm" -> format(0x8227, 0x1401, 2);
      case "rgb8-unorm" -> format(0x1907, 0x1401, 3);
      case "rgba8-unorm" -> format(0x1908, 0x1401, 4);
      case "r8-snorm" -> format(0x1903, 0x1400, 1);
      case "rg8-snorm" -> format(0x8227, 0x1400, 2);
      case "rgb8-snorm" -> format(0x1907, 0x1400, 3);
      case "rgba8-snorm" -> format(0x1908, 0x1400, 4);
      case "r16-unorm" -> format(0x1903, 0x1403, 2);
      case "rg16-unorm" -> format(0x8227, 0x1403, 4);
      case "rgb16-unorm" -> format(0x1907, 0x1403, 6);
      case "rgba16-unorm" -> format(0x1908, 0x1403, 8);
      case "r16-snorm" -> format(0x1903, 0x1402, 2);
      case "rg16-snorm" -> format(0x8227, 0x1402, 4);
      case "rgb16-snorm" -> format(0x1907, 0x1402, 6);
      case "rgba16-snorm" -> format(0x1908, 0x1402, 8);
      case "r16-float" -> format(0x1903, 0x140B, 2);
      case "rg16-float" -> format(0x8227, 0x140B, 4);
      case "rgb16-float" -> format(0x1907, 0x140B, 6);
      case "rgba16-float" -> format(0x1908, 0x140B, 8);
      case "r32-float" -> format(0x1903, 0x1406, 4);
      case "rg32-float" -> format(0x8227, 0x1406, 8);
      case "rgb32-float" -> format(0x1907, 0x1406, 12);
      case "rgba32-float" -> format(0x1908, 0x1406, 16);
      case "r8-sint" -> format(0x8D94, 0x1400, 1);
      case "rg8-sint" -> format(0x8228, 0x1400, 2);
      case "rgb8-sint" -> format(0x8D98, 0x1400, 3);
      case "rgba8-sint" -> format(0x8D99, 0x1400, 4);
      case "r8-uint" -> format(0x8D94, 0x1401, 1);
      case "rg8-uint" -> format(0x8228, 0x1401, 2);
      case "rgb8-uint" -> format(0x8D98, 0x1401, 3);
      case "rgba8-uint" -> format(0x8D99, 0x1401, 4);
      case "r16-sint" -> format(0x8D94, 0x1402, 2);
      case "rg16-sint" -> format(0x8228, 0x1402, 4);
      case "rgb16-sint" -> format(0x8D98, 0x1402, 6);
      case "rgba16-sint" -> format(0x8D99, 0x1402, 8);
      case "r16-uint" -> format(0x8D94, 0x1403, 2);
      case "rg16-uint" -> format(0x8228, 0x1403, 4);
      case "rgb16-uint" -> format(0x8D98, 0x1403, 6);
      case "rgba16-uint" -> format(0x8D99, 0x1403, 8);
      case "r32-sint" -> format(0x8D94, 0x1404, 4);
      case "rg32-sint" -> format(0x8228, 0x1404, 8);
      case "rgb32-sint" -> format(0x8D98, 0x1404, 12);
      case "rgba32-sint" -> format(0x8D99, 0x1404, 16);
      case "r32-uint" -> format(0x8D94, 0x1405, 4);
      case "rg32-uint" -> format(0x8228, 0x1405, 8);
      case "rgb32-uint" -> format(0x8D98, 0x1405, 12);
      case "rgba32-uint" -> format(0x8D99, 0x1405, 16);
      case "rgb10a2-unorm" -> format(0x1908, 0x8368, 4);
      case "rgb10a2-uint" -> format(0x8D99, 0x8368, 4);
      // Let OpenGL perform the packed-float conversion. The raw
      // 10F_11F_11F_REV word is not byte-compatible with Metal's packed
      // texture upload on the macOS 26 driver.
      case "rg11b10-float" -> format(0x1908, 0x140B, 8);
      case "rgb9e5-float" -> format(0x1907, 0x8C3E, 4);
      case "d16-unorm" -> format(0x1902, 0x1403, 2);
      case "d32-float" -> format(0x1902, 0x1406, 4);
      case "d24-unorm-s8-uint" -> format(0x84F9, 0x84FA, 4);
      case "d32-float-s8-uint" -> format(0x84F9, 0x8DAD, 8);
      case "s8-uint" -> format(0x1901, 0x1401, 1);
      default -> Optional.empty();
    };
  }

  static String captureFormat(String sourceFormat) {
    return "rg11b10-float".equals(sourceFormat)
        ? "rgba16-float" : sourceFormat;
  }

  private static Optional<ReadFormat> format(int external, int type,
      int bytes) {
    return Optional.of(new ReadFormat(external, type, bytes));
  }

  record ReadFormat(int externalFormat, int type, int bytesPerPixel) {
    ReadFormat {
      if (externalFormat <= 0 || type <= 0 || bytesPerPixel <= 0) {
        throw new IllegalArgumentException("invalid GL texture read format");
      }
    }
  }
}

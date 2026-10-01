package com.pebbles_boon.metalrender.render;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;

/**
 * Minecraft 1.21.1 texture bridge.
 *
 * <p>The 26.2 implementation relies on Mojang's newer {@code GpuTexture} and
 * fenced {@code GpuBuffer} readback APIs. Those APIs are not exposed the same
 * way in 1.21.1. This implementation deliberately uses the older GL texture
 * ID path that Minecraft 1.21.1 still exposes, reads RGBA pixels synchronously,
 * and uploads the result into the native Metal texture.</p>
 *
 * <p>Readbacks are intentionally throttled because they are synchronization
 * points. This is a compatibility bridge, not the final high-performance
 * texture path.</p>
 */
public final class MetalTextureManager {
  private static final net.minecraft.resources.ResourceLocation BLOCKS_ATLAS_ID =
      TextureAtlas.LOCATION_BLOCKS;

  private final long deviceHandle;
  private long blockAtlasTexture;
  private long lightmapTexture;
  private boolean blockAtlasLoaded;
  private boolean lightmapLoaded;
  private boolean usingFallbackBlockAtlas;
  private int blockAtlasWidth;
  private int blockAtlasHeight;
  private int lightmapWidth;
  private int lightmapHeight;
  private byte[] atlasUploadData;
  private byte[] lightmapUploadData;
  private long completedAtlasRevision;

  private static final AtomicLong ATLAS_DIRTY_REVISION =
      new AtomicLong(1L);
  public static volatile boolean atlasDirty = true;

  private static final int ATLAS_MIN_UPLOAD_INTERVAL = 2;
  private static final long ATLAS_MIN_UPLOAD_INTERVAL_NS = 250_000_000L;
  private static final int LIGHTMAP_MIN_UPLOAD_INTERVAL = 2;
  private static final long LIGHTMAP_MIN_GAME_TIME_DELTA = 4L;

  private int atlasFramesSinceUpload;
  private long lastAtlasUploadNanos = Long.MIN_VALUE;
  private int lightmapFramesSinceUpload;
  private long lastLightmapObservedGameTime = Long.MIN_VALUE;
  private long lastUploadedLightmapGameTime = Long.MIN_VALUE;

  public static void markAtlasDirty() {
    ATLAS_DIRTY_REVISION.incrementAndGet();
    atlasDirty = true;
  }

  public static long getAtlasDirtyRevision() {
    return ATLAS_DIRTY_REVISION.get();
  }

  public MetalTextureManager(long deviceHandle) {
    this.deviceHandle = deviceHandle;
  }

  public void loadBlockAtlas() {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getTextureManager() == null) {
        return;
      }

      AbstractTexture texture =
          mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
      if (texture == null) {
        usingFallbackBlockAtlas = true;
        blockAtlasLoaded = false;
        return;
      }

      int glTextureId = texture.getId();
      int[] dimensions = queryTextureSize(glTextureId);
      if (dimensions == null) {
        usingFallbackBlockAtlas = true;
        blockAtlasLoaded = false;
        return;
      }

      byte[] rgba = readTexturePixels(glTextureId, dimensions[0], dimensions[1]);
      if (rgba == null) {
        usingFallbackBlockAtlas = true;
        blockAtlasLoaded = false;
        return;
      }

      installBlockAtlas(dimensions[0], dimensions[1], rgba);
      blockAtlasLoaded = blockAtlasTexture != 0;
      usingFallbackBlockAtlas = !blockAtlasLoaded;
    } catch (Throwable error) {
      blockAtlasLoaded = false;
      usingFallbackBlockAtlas = true;
      MetalLogger.warn("1.21.1 block atlas readback failed: %s",
          error.getMessage());
    }
  }

  public void updateBlockAtlas() {
    if (!blockAtlasLoaded || blockAtlasTexture == 0) {
      return;
    }
    if (!atlasDirty) {
      return;
    }

    atlasFramesSinceUpload++;
    if (atlasFramesSinceUpload < ATLAS_MIN_UPLOAD_INTERVAL) {
      return;
    }

    long now = System.nanoTime();
    if (lastAtlasUploadNanos != Long.MIN_VALUE &&
        now - lastAtlasUploadNanos < ATLAS_MIN_UPLOAD_INTERVAL_NS) {
      return;
    }
    atlasFramesSinceUpload = 0;

    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getTextureManager() == null) {
        return;
      }

      AbstractTexture texture =
          mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
      if (texture == null) {
        return;
      }

      int glTextureId = texture.getId();
      int[] dimensions = queryTextureSize(glTextureId);
      if (dimensions == null) {
        return;
      }

      byte[] rgba = readTexturePixels(glTextureId, dimensions[0], dimensions[1]);
      if (rgba == null) {
        return;
      }

      installBlockAtlas(dimensions[0], dimensions[1], rgba);
      lastAtlasUploadNanos = now;
    } catch (Throwable error) {
      atlasDirty = true;
      MetalLogger.warn("1.21.1 block atlas update failed: %s",
          error.getMessage());
    }
  }

  public void updateLightmap() {
    if (lightmapTexture == 0) {
      return;
    }

    Minecraft mc = Minecraft.getInstance();
    long gameTime = mc != null && mc.level != null
        ? mc.level.getGameTime()
        : Long.MIN_VALUE;

    if (gameTime == lastLightmapObservedGameTime) {
      return;
    }
    lastLightmapObservedGameTime = gameTime;

    if (lastUploadedLightmapGameTime != Long.MIN_VALUE &&
        gameTime != Long.MIN_VALUE &&
        gameTime - lastUploadedLightmapGameTime <
            LIGHTMAP_MIN_GAME_TIME_DELTA) {
      return;
    }

    lightmapFramesSinceUpload++;
    if (lightmapFramesSinceUpload < LIGHTMAP_MIN_UPLOAD_INTERVAL) {
      return;
    }
    lightmapFramesSinceUpload = 0;

    uploadLightmap(gameTime);
  }

  public void loadLightmap() {
    lightmapFramesSinceUpload = 0;
    lastLightmapObservedGameTime = Long.MIN_VALUE;
    lastUploadedLightmapGameTime = Long.MIN_VALUE;
    uploadLightmap(Long.MIN_VALUE);
  }

  public boolean isBlockAtlasLoaded() {
    return blockAtlasLoaded;
  }

  public boolean isLightmapLoaded() {
    return lightmapLoaded;
  }

  public boolean isUsingFallbackBlockAtlas() {
    return usingFallbackBlockAtlas;
  }

  public boolean isAtlasReadbackPending() {
    // The 1.21.1 bridge uses synchronous GL readback.
    return false;
  }

  public long getCompletedAtlasRevision() {
    return completedAtlasRevision;
  }

  public long getBlockAtlasTexture() {
    return blockAtlasTexture;
  }

  public long getLightmapTexture() {
    return lightmapTexture;
  }

  private boolean uploadLightmap(long gameTime) {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.gameRenderer == null) {
        return false;
      }

      LightTexture lightTexture = mc.gameRenderer.lightTexture();
      if (!(lightTexture instanceof AccessorLightTexture accessor)) {
        return false;
      }

      DynamicTexture texture = accessor.complemetal$getLightTexture();
      if (texture == null) {
        return false;
      }

      int glTextureId = texture.getId();
      int[] dimensions = queryTextureSize(glTextureId);
      if (dimensions == null) {
        return false;
      }

      byte[] rgba = readTexturePixels(
          glTextureId, dimensions[0], dimensions[1]);
      if (rgba == null) {
        return false;
      }

      if (lightmapTexture == 0 ||
          dimensions[0] != lightmapWidth ||
          dimensions[1] != lightmapHeight) {
        long newTexture = NativeBridge.nCreateTexture2D(
            deviceHandle, dimensions[0], dimensions[1], rgba);
        if (newTexture == 0) {
          throw new IllegalStateException(
              "native lightmap texture creation returned zero");
        }
        if (lightmapTexture != 0 && lightmapTexture != newTexture) {
          NativeBridge.nDestroyTexture2D(lightmapTexture);
        }
        lightmapTexture = newTexture;
        lightmapWidth = dimensions[0];
        lightmapHeight = dimensions[1];
      } else if (!NativeBridge.nUpdateTexture2D(
          lightmapTexture, dimensions[0], dimensions[1], rgba)) {
        throw new IllegalStateException(
            "native lightmap texture update was not submitted");
      }

      lightmapLoaded = true;
      if (gameTime != Long.MIN_VALUE) {
        lastUploadedLightmapGameTime = gameTime;
      }
      return true;
    } catch (Throwable error) {
      MetalLogger.warn("1.21.1 lightmap readback failed: %s",
          error.getMessage());
      return false;
    }
  }

  private void installBlockAtlas(int width, int height, byte[] rgba) {
    if (blockAtlasTexture == 0 ||
        width != blockAtlasWidth || height != blockAtlasHeight) {
      long newTexture = NativeBridge.nCreateTexture2D(
          deviceHandle, width, height, rgba);
      if (newTexture == 0) {
        throw new IllegalStateException(
            "native block atlas texture creation returned zero");
      }
      if (blockAtlasTexture != 0 && blockAtlasTexture != newTexture) {
        NativeBridge.nDestroyTexture2D(blockAtlasTexture);
      }
      blockAtlasTexture = newTexture;
      blockAtlasWidth = width;
      blockAtlasHeight = height;
    } else if (!NativeBridge.nUpdateTexture2D(
        blockAtlasTexture, width, height, rgba)) {
      throw new IllegalStateException(
          "native block atlas texture update was not submitted");
    }

    blockAtlasLoaded = true;
    usingFallbackBlockAtlas = false;
    completedAtlasRevision = ATLAS_DIRTY_REVISION.get();
    atlasDirty = false;
  }

  private static int[] queryTextureSize(int glTextureId) {
    if (glTextureId <= 0) {
      return null;
    }

    int previousBinding =
        GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
    try {
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glTextureId);
      int width = GL11C.glGetTexLevelParameteri(
          GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
      int height = GL11C.glGetTexLevelParameteri(
          GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0) {
        return null;
      }
      return new int[] {width, height};
    } finally {
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousBinding);
    }
  }

  private static byte[] readTexturePixels(
      int glTextureId, int width, int height) {
    long bytes = (long) width * (long) height * 4L;
    if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
      return null;
    }

    int previousBinding =
        GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
    ByteBuffer buffer = BufferUtils.createByteBuffer((int) bytes);
    try {
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glTextureId);
      GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
      GL11C.glGetTexImage(
          GL11C.GL_TEXTURE_2D, 0,
          GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buffer);
      byte[] rgba = new byte[(int) bytes];
      buffer.rewind();
      buffer.get(rgba);
      return rgba;
    } finally {
      GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousBinding);
    }
  }

  public void destroy() {
    if (blockAtlasTexture != 0) {
      NativeBridge.nDestroyTexture2D(blockAtlasTexture);
      blockAtlasTexture = 0;
    }
    if (lightmapTexture != 0) {
      NativeBridge.nDestroyTexture2D(lightmapTexture);
      lightmapTexture = 0;
    }

    blockAtlasLoaded = false;
    lightmapLoaded = false;
    usingFallbackBlockAtlas = false;
    blockAtlasWidth = 0;
    blockAtlasHeight = 0;
    lightmapWidth = 0;
    lightmapHeight = 0;
    atlasUploadData = null;
    lightmapUploadData = null;
    atlasFramesSinceUpload = 0;
    lightmapFramesSinceUpload = 0;
    lastAtlasUploadNanos = Long.MIN_VALUE;
    lastLightmapObservedGameTime = Long.MIN_VALUE;
    lastUploadedLightmapGameTime = Long.MIN_VALUE;
    completedAtlasRevision = 0L;
    markAtlasDirty();
  }

}
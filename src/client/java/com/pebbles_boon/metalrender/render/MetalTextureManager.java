package com.pebbles_boon.metalrender.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;

public class MetalTextureManager {
  private static final net.minecraft.resources.Identifier BLOCKS_ATLAS_ID = TextureAtlas.LOCATION_BLOCKS;
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
  private byte[] atlasUploadData = null;
  private byte[] lightmapUploadData = null;
  private boolean atlasReadbackPending;
  private boolean lightmapReadbackPending;
  private ReadbackRequest atlasReadbackRequest;
  private ReadbackRequest lightmapReadbackRequest;
  private long readbackGeneration;
  private static final AtomicLong ATLAS_DIRTY_REVISION =
      new AtomicLong(1L);
  public static volatile boolean atlasDirty = true;

  private static final int ATLAS_MIN_UPLOAD_INTERVAL = 2;
  private static final long ATLAS_MIN_UPLOAD_INTERVAL_NS = 250_000_000L;
  private static final int LIGHTMAP_MIN_UPLOAD_INTERVAL = 2;
  private static final long LIGHTMAP_MIN_GAME_TIME_DELTA = 4L;
  private int atlasFramesSinceUpload = 0;
  private long lastAtlasUploadNanos = Long.MIN_VALUE;
  private int lightmapFramesSinceUpload = 0;
  private long lastLightmapObservedGameTime = Long.MIN_VALUE;
  private long lastUploadedLightmapGameTime = Long.MIN_VALUE;

  private static final class ReadbackRequest {
    private final GpuBuffer buffer;
    private final long generation;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile boolean cancelled;

    private ReadbackRequest(GpuBuffer buffer, long generation) {
      this.buffer = buffer;
      this.generation = generation;
    }
  }

  public static void markAtlasDirty() {
    ATLAS_DIRTY_REVISION.incrementAndGet();
    atlasDirty = true;
  }

  public MetalTextureManager(long deviceHandle) {
    this.deviceHandle = deviceHandle;
  }

  public void loadBlockAtlas() {
    try {
      if (atlasReadbackPending) {
        return;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getTextureManager() == null)
        return;
      AbstractTexture atlasTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
      if (atlasTexture == null) {
        MetalLogger.info("atlas not ready");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      GpuTexture gpuTexture = atlasTexture.getTexture();
      if (gpuTexture == null || gpuTexture.isClosed()) {
        MetalLogger.info("atlas GPU texture unavailable");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      int width = gpuTexture.getWidth(0);
      int height = gpuTexture.getHeight(0);
      if (width <= 0 || height <= 0) {
        MetalLogger.info("atlas bad dim %dx%d", width, height);
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      if (!requestAtlasReadback(gpuTexture, width, height)) {
        MetalLogger.error("atlas tex create fail");
      }
    } catch (Exception e) {
      MetalLogger.error("atlas load fail: %s", e.getMessage());
      blockAtlasLoaded = true;
      usingFallbackBlockAtlas = true;
    }
  }

  public void updateBlockAtlas() {
    if (blockAtlasTexture == 0 || usingFallbackBlockAtlas)
      return;
    if (!atlasDirty)
      return;
    if (atlasReadbackPending)
      return;
    atlasFramesSinceUpload++;

    if (atlasFramesSinceUpload < ATLAS_MIN_UPLOAD_INTERVAL)
      return;
    long now = System.nanoTime();
    if (lastAtlasUploadNanos != Long.MIN_VALUE &&
        now - lastAtlasUploadNanos < ATLAS_MIN_UPLOAD_INTERVAL_NS) {
      return;
    }
    atlasFramesSinceUpload = 0;

    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getTextureManager() == null)
        return;
      AbstractTexture atlasTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
      if (atlasTexture == null)
        return;
      GpuTexture gpuTexture = atlasTexture.getTexture();
      if (gpuTexture == null || gpuTexture.isClosed())
        return;
      int width = gpuTexture.getWidth(0);
      int height = gpuTexture.getHeight(0);
      if (width <= 0 || height <= 0)
        return;
      if (requestAtlasReadback(gpuTexture, width, height)) {
        lastAtlasUploadNanos = now;
      }
    } catch (Exception e) {
      // Keep the dirty bit set so a transient GL/resource reload race retries
      // later instead of permanently leaving Metal with a stale atlas.
      atlasDirty = true;
      MetalLogger.warn("atlas update failed: %s", e.getMessage());
    }
  }

  public void updateLightmap() {
    if (lightmapTexture == 0)
      return;
    Minecraft mc = Minecraft.getInstance();
    long gameTime = mc != null && mc.level != null ? mc.level.getGameTime()
        : Long.MIN_VALUE;
    if (gameTime == lastLightmapObservedGameTime)
      return;
    lastLightmapObservedGameTime = gameTime;
    if (lastUploadedLightmapGameTime != Long.MIN_VALUE &&
        gameTime != Long.MIN_VALUE &&
        gameTime - lastUploadedLightmapGameTime < LIGHTMAP_MIN_GAME_TIME_DELTA) {
      return;
    }
    lightmapFramesSinceUpload++;
    if (lightmapFramesSinceUpload < LIGHTMAP_MIN_UPLOAD_INTERVAL)
      return;
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

  public long getBlockAtlasTexture() {
    return blockAtlasTexture;
  }

  public long getLightmapTexture() {
    return lightmapTexture;
  }

  private boolean uploadLightmap(long gameTime) {
    try {
      if (lightmapReadbackPending) {
        return false;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.gameRenderer == null) {
        return false;
      }
      var lightmapView = mc.gameRenderer.levelLightmap();
      if (lightmapView == null) {
        return false;
      }
      GpuTexture gpuTexture = lightmapView.texture();
      if (gpuTexture == null || gpuTexture.isClosed()) {
        return false;
      }
      int width = gpuTexture.getWidth(0);
      int height = gpuTexture.getHeight(0);
      if (width <= 0 || height <= 0) {
        return false;
      }
      return requestLightmapReadback(
          gpuTexture, width, height, gameTime);
    } catch (Exception e) {
      MetalLogger.error("lightmap load fail: %s", e.getMessage());
      return false;
    }
  }

  private boolean requestAtlasReadback(
      GpuTexture texture, int width, int height) {
    int dataSize = rgbaDataSize(width, height);
    if (dataSize <= 0 || atlasReadbackPending) {
      return false;
    }
    GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
        () -> "MetalRender block-atlas readback",
        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
        dataSize);
    ReadbackRequest request =
        new ReadbackRequest(buffer, readbackGeneration);
    atlasReadbackRequest = request;
    atlasReadbackPending = true;
    long dirtyRevision = ATLAS_DIRTY_REVISION.get();
    try {
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
      encoder.copyTextureToBuffer(texture, buffer, 0,
          () -> completeAtlasReadback(
              request, width, height, dataSize, dirtyRevision),
          0);
      return true;
    } catch (Throwable error) {
      // copyTextureToBuffer may queue its fenced callback before surfacing a
      // later GL error. Cancel publication and defer fallback cleanup to the
      // same fence instead of deleting a PBO that the driver may still own.
      request.cancelled = true;
      queueFailedAtlasReadbackCleanup(request);
      MetalLogger.warn("atlas readback request failed: %s",
          error.getMessage());
      return false;
    }
  }

  private boolean requestLightmapReadback(
      GpuTexture texture, int width, int height, long gameTime) {
    int dataSize = rgbaDataSize(width, height);
    if (dataSize <= 0 || lightmapReadbackPending) {
      return false;
    }
    GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
        () -> "MetalRender lightmap readback",
        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
        dataSize);
    ReadbackRequest request =
        new ReadbackRequest(buffer, readbackGeneration);
    lightmapReadbackRequest = request;
    lightmapReadbackPending = true;
    try {
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
      encoder.copyTextureToBuffer(texture, buffer, 0,
          () -> completeLightmapReadback(
              request, width, height, dataSize, gameTime),
          0);
      return true;
    } catch (Throwable error) {
      request.cancelled = true;
      queueFailedLightmapReadbackCleanup(request);
      MetalLogger.warn("lightmap readback request failed: %s",
          error.getMessage());
      return false;
    }
  }

  private void completeAtlasReadback(
      ReadbackRequest request, int width, int height, int dataSize,
      long dirtyRevision) {
    try {
      if (request.cancelled ||
          request.generation != readbackGeneration) {
        return;
      }
      if (atlasUploadData == null || atlasUploadData.length < dataSize) {
        atlasUploadData = new byte[dataSize];
      }
      copyMappedBytes(request.buffer, atlasUploadData, dataSize);
      if (blockAtlasTexture == 0 || width != blockAtlasWidth ||
          height != blockAtlasHeight) {
        long newTexture = NativeBridge.nCreateTexture2D(
            deviceHandle, width, height, atlasUploadData);
        if (newTexture == 0) {
          throw new IllegalStateException(
              "native atlas texture creation returned zero");
        }
        if (blockAtlasTexture != 0 && blockAtlasTexture != newTexture) {
          NativeBridge.nDestroyTexture2D(blockAtlasTexture);
        }
        blockAtlasTexture = newTexture;
        blockAtlasWidth = width;
        blockAtlasHeight = height;
        MetalLogger.info("atlas ready: %dx%d h=%d",
            width, height, newTexture);
      } else {
        NativeBridge.nUpdateTexture2D(
            blockAtlasTexture, width, height, atlasUploadData);
      }
      blockAtlasLoaded = true;
      usingFallbackBlockAtlas = false;
      atlasDirty = ATLAS_DIRTY_REVISION.get() != dirtyRevision;
    } catch (Throwable error) {
      atlasDirty = true;
      MetalLogger.warn("atlas readback completion failed: %s",
          error.getMessage());
    } finally {
      finishAtlasReadback(request);
    }
  }

  private void completeLightmapReadback(
      ReadbackRequest request, int width, int height, int dataSize,
      long gameTime) {
    try {
      if (request.cancelled ||
          request.generation != readbackGeneration) {
        return;
      }
      if (lightmapUploadData == null ||
          lightmapUploadData.length < dataSize) {
        lightmapUploadData = new byte[dataSize];
      }
      copyMappedBytes(request.buffer, lightmapUploadData, dataSize);
      if (lightmapTexture == 0 || width != lightmapWidth ||
          height != lightmapHeight) {
        long newTexture = NativeBridge.nCreateTexture2D(
            deviceHandle, width, height, lightmapUploadData);
        if (newTexture == 0) {
          throw new IllegalStateException(
              "native lightmap texture creation returned zero");
        }
        if (lightmapTexture != 0 && lightmapTexture != newTexture) {
          NativeBridge.nDestroyTexture2D(lightmapTexture);
        }
        lightmapTexture = newTexture;
        lightmapWidth = width;
        lightmapHeight = height;
        MetalLogger.info("lightmap ready: %dx%d h=%d",
            width, height, newTexture);
      } else {
        NativeBridge.nUpdateTexture2D(
            lightmapTexture, width, height, lightmapUploadData);
      }
      lightmapLoaded = true;
      if (gameTime != Long.MIN_VALUE) {
        lastUploadedLightmapGameTime = gameTime;
      }
    } catch (Throwable error) {
      MetalLogger.warn("lightmap readback completion failed: %s",
          error.getMessage());
    } finally {
      finishLightmapReadback(request);
    }
  }

  private static void copyMappedBytes(
      GpuBuffer buffer, byte[] destination, int dataSize) {
    try (var mapped = buffer.map(0, dataSize, true, false)) {
      ByteBuffer source = mapped.data().duplicate();
      source.position(0);
      source.limit(dataSize);
      source.get(destination, 0, dataSize);
    }
  }

  private static int rgbaDataSize(int width, int height) {
    if (width <= 0 || height <= 0) {
      return -1;
    }
    long pixels = (long) width * (long) height;
    long bytes = pixels * 4L;
    return bytes > Integer.MAX_VALUE ? -1 : (int) bytes;
  }

  private void queueFailedAtlasReadbackCleanup(ReadbackRequest request) {
    try {
      RenderSystem.queueFencedTask(() -> finishAtlasReadback(request));
    } catch (Throwable cleanupError) {
      // Ambiguous ownership is safer as a one-shot leak than an in-flight PBO
      // deletion. destroy() invalidates the generation so no upload can occur.
      readbackGeneration++;
      MetalLogger.warn("atlas readback cleanup could not be fenced: %s",
          cleanupError.getMessage());
    }
  }

  private void queueFailedLightmapReadbackCleanup(ReadbackRequest request) {
    try {
      RenderSystem.queueFencedTask(() -> finishLightmapReadback(request));
    } catch (Throwable cleanupError) {
      readbackGeneration++;
      MetalLogger.warn("lightmap readback cleanup could not be fenced: %s",
          cleanupError.getMessage());
    }
  }

  private void finishAtlasReadback(ReadbackRequest request) {
    if (!request.finished.compareAndSet(false, true)) {
      return;
    }
    if (atlasReadbackRequest == request) {
      atlasReadbackRequest = null;
      atlasReadbackPending = false;
    }
    if (!request.buffer.isClosed()) {
      request.buffer.close();
    }
  }

  private void finishLightmapReadback(ReadbackRequest request) {
    if (!request.finished.compareAndSet(false, true)) {
      return;
    }
    if (lightmapReadbackRequest == request) {
      lightmapReadbackRequest = null;
      lightmapReadbackPending = false;
    }
    if (!request.buffer.isClosed()) {
      request.buffer.close();
    }
  }

  public void destroy() {
    readbackGeneration++;
    if (atlasReadbackRequest != null) {
      atlasReadbackRequest.cancelled = true;
    }
    if (lightmapReadbackRequest != null) {
      lightmapReadbackRequest.cancelled = true;
    }
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
    lastLightmapObservedGameTime = Long.MIN_VALUE;
    lastUploadedLightmapGameTime = Long.MIN_VALUE;
    atlasUploadData = null;
    lightmapUploadData = null;
    atlasFramesSinceUpload = 0;
    markAtlasDirty();
  }
}

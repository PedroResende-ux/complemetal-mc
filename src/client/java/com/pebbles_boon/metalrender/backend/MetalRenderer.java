package com.pebbles_boon.metalrender.backend;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;

public final class MetalRenderer implements RenderBackend {
  private long handle;
  private volatile boolean pipelinesReady;
  private boolean available;
  private float currentScale = 1.0f;
  private final MetalRendererBackendHandle backend;

  public static final class MetalRendererBackendHandle {
    private final MetalRenderer renderer;

    MetalRendererBackendHandle(MetalRenderer r) {
      this.renderer = r;
    }

    public long getDeviceHandle() {
      return NativeBridge.nGetDeviceHandle(renderer.handle);
    }

    public long getShaderLibraryHandle() {
      return NativeBridge.nGetShaderLibraryHandle(renderer.handle);
    }

    public long getInhousePipelineHandle() {
      return NativeBridge.nGetInhousePipelineHandle(renderer.handle);
    }

    public long getDefaultPipelineHandle() {
      return NativeBridge.nGetDefaultPipelineHandle(renderer.handle);
    }

    public long getEntityPipelineHandle() {
      return NativeBridge.nGetEntityPipelineHandle(renderer.handle);
    }

    public long getEntityTranslucentPipelineHandle() {
      return NativeBridge.nGetEntityTranslucentPipelineHandle(renderer.handle);
    }

    public long getEntityEmissivePipelineHandle() {
      return NativeBridge.nGetEntityEmissivePipelineHandle(renderer.handle);
    }
  }

  public MetalRenderer() {
    this.backend = new MetalRendererBackendHandle(this);
  }

  public void init(int width, int height) {
    this.currentScale = configuredScale();
    this.handle = NativeBridge.nInit(width, height, currentScale);
    if (this.handle != 0L) {
      this.available = true;
      applyTemporalScale(currentScale);
    }
  }

  public boolean isAvailable() {
    return available && handle != 0;
  }

  public MetalRendererBackendHandle getBackend() {
    return backend;
  }

  public void resize(int width, int height) {
    if (handle != 0) {
      currentScale = configuredScale();
      NativeBridge.nResize(handle, width, height, currentScale);
      applyTemporalScale(currentScale);
    }
  }

  public void refreshRuntimeScale(int width, int height) {
    float requested = configuredScale();
    if (handle != 0 && Math.abs(requested - currentScale) > 0.001f) {
      currentScale = requested;
      NativeBridge.nResize(handle, width, height, currentScale);
      applyTemporalScale(currentScale);
    }
  }

  private static float configuredScale() {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    return config != null && config.enableMetalFX
        ? MetalRenderConfig.resolutionScale()
        : 1.0f;
  }

  private static void applyTemporalScale(float scale) {
    try {
      NativeBridge.nSetTemporalScale(scale);
    } catch (UnsatisfiedLinkError ignored) {
      // Allows the Java 26.2 build to fail open with an older baseline native
      // during migration. Release validation requires JNI parity.
    }
  }

  public void beginFrame(float tickDelta) {
  }

  @Override
  public void beginFrame(float[] viewProj) {
  }

  private final float[] reusableMatrixArr = new float[16];

  public void setProjectionMatrix(Matrix4f proj) {
    if (handle == 0)
      return;
    proj.get(reusableMatrixArr);
    NativeBridge.nSetProjectionMatrix(handle, reusableMatrixArr);
  }

  public void setModelViewMatrix(Matrix4f mv) {
    if (handle == 0)
      return;
    mv.get(reusableMatrixArr);
    NativeBridge.nSetModelViewMatrix(handle, reusableMatrixArr);
  }

  public void setCameraPosition(double x, double y, double z) {
    if (handle != 0)
      NativeBridge.nSetCameraPosition(handle, x, y, z);
  }

  public void bindTexture(long textureHandle, int slot) {
    if (handle != 0)
      NativeBridge.nBindTexture(handle, textureHandle, slot);
  }

  public long getCurrentFrameContext() {
    return handle != 0 ? NativeBridge.nGetCurrentFrameContext(handle) : 0;
  }

  public long frameCtx() {
    return getCurrentFrameContext();
  }

  public int getGLTextureId() {
    return handle != 0 ? NativeBridge.nGetGLTextureId(handle) : 0;
  }

  public long getHandle() {
    return handle;
  }

  @Override
  public long createVertexBuffer(ByteBuffer data, int size, int stride) {
    if (data == null || size <= 0)
      return 0L;
    byte[] bytes = new byte[size];
    data.get(bytes);
    long buf = NativeBridge.nCreateBuffer(getBackend().getDeviceHandle(), size, 0);
    NativeBridge.nUploadBufferData(buf, bytes, 0, size);
    return buf;
  }

  @Override
  public long createIndexBuffer(ByteBuffer data, int size) {
    if (data == null || size <= 0)
      return 0L;
    byte[] bytes = new byte[size];
    data.get(bytes);
    long buf = NativeBridge.nCreateBuffer(getBackend().getDeviceHandle(), size, 0);
    NativeBridge.nUploadBufferData(buf, bytes, 0, size);
    return buf;
  }

  @Override
  public void destroyBuffer(long bufferHandle) {
    NativeBridge.nDestroyBuffer(bufferHandle);
  }

  @Override
  public void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex,
      int baseVertex) {
    long ctx = getCurrentFrameContext();
    if (ctx != 0) {
      NativeBridge.nDrawIndexedBuffer(ctx, vbo, ibo, indexCount, firstIndex);
    }
  }

  @Override
  public void endFrame() {
    if (handle != 0)
      NativeBridge.nEndFrame(handle);
  }

  public boolean isPassReady(int pass) {
    return available;
  }

  public boolean isOpaqueReady() {
    return isPassReady(0);
  }
}

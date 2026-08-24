package com.pebbles_boon.metalrender.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

public final class NativeBridge {
  public static final int IRIS_MSL_COMPILE_FAILED = -1;
  public static final int IRIS_MSL_COMPILE_UNSUPPORTED = 0;
  public static final int IRIS_MSL_COMPILE_COMPILED = 1;
  public static final int IRIS_MSL_COMPILE_DEFERRED = 2;

  private static final String LIBRARY_BASENAME = "libmetalrender.dylib";
  private static final String SHADER_LIBRARY_BASENAME = "shaders.metallib";
  private static final String[] LIBRARY_RESOURCES = {
      "/native/macos-arm64/" + LIBRARY_BASENAME,
      "/" + LIBRARY_BASENAME
  };
  private static final String[] SHADER_LIBRARY_RESOURCES = {
      "/native/macos-arm64/" + SHADER_LIBRARY_BASENAME,
      "/" + SHADER_LIBRARY_BASENAME
  };

  private record ResourcePayload(byte[] bytes, String resourceName,
                                 String sha256) {
  }

  private record PackagedNativePayload(Path libraryPath,
                                       Path shaderLibraryPath) {
  }

  public enum LoadState {
    NOT_TRIED,
    READY,
    UNSUPPORTED,
    FAILED
  }

  private static volatile LoadState loadState = LoadState.NOT_TRIED;
  private static volatile boolean libLoaded;
  private static volatile String loadedPath;
  private static volatile String loadFailure;

  private NativeBridge() {
  }

  public static synchronized void loadLibrary() {
    if (loadState == LoadState.READY) {
      return;
    }
    if (loadState == LoadState.UNSUPPORTED) {
      throw new UnsatisfiedLinkError(loadFailure);
    }

    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
    if (!os.contains("mac") || !(arch.contains("aarch64") || arch.contains("arm64"))) {
      markLoadFailure(LoadState.UNSUPPORTED,
          "MetalRender native backend requires macOS on Apple Silicon; found "
              + os + "/" + arch);
      throw new UnsatisfiedLinkError(loadFailure);
    }

    String explicitPath = System.getProperty("metalrender.native.path", "").trim();
    if (!explicitPath.isEmpty()) {
      try {
        Path candidate = Path.of(explicitPath).toAbsolutePath().normalize();
        System.load(candidate.toString());
        markLoaded(candidate.toString());
        return;
      } catch (Throwable t) {
        markLoadFailure(LoadState.FAILED,
            "explicit native path failed: " + safeMessage(t));
        throw asLinkError(loadFailure, t);
      }
    }

    // A complete packaged payload is the production path. Prefer its
    // checksum-paired dylib/metallib over java.library.path so an unrelated or
    // stale development library cannot shadow the release artifact.
    if (hasPackagedResource(LIBRARY_RESOURCES)
        && hasPackagedResource(SHADER_LIBRARY_RESOURCES)) {
      try {
        PackagedNativePayload extracted = extractPackagedPayload();
        System.load(extracted.libraryPath().toString());
        markLoaded(extracted.libraryPath().toString());
        return;
      } catch (Throwable packagedFailure) {
        markLoadFailure(LoadState.FAILED,
            "packaged native payload failed: " + safeMessage(packagedFailure));
        throw asLinkError(loadFailure, packagedFailure);
      }
    }

    Throwable libraryPathFailure = null;
    try {
      System.loadLibrary("metalrender");
      markLoaded("java.library.path:metalrender");
      return;
    } catch (Throwable t) {
      libraryPathFailure = t;
    }

    try {
      PackagedNativePayload extracted = extractPackagedPayload();
      System.load(extracted.libraryPath().toString());
      markLoaded(extracted.libraryPath().toString());
    } catch (Throwable extractionFailure) {
      String firstFailure = safeMessage(libraryPathFailure);
      markLoadFailure(LoadState.FAILED,
          "native load failed (java.library.path: " + firstFailure
              + "; packaged library: " + safeMessage(extractionFailure) + ")");
      throw asLinkError(loadFailure, extractionFailure);
    }
  }

  public static boolean isLibLoaded() {
    return libLoaded;
  }

  public static LoadState getLoadState() {
    return loadState;
  }

  public static String getLoadedPath() {
    return loadedPath;
  }

  public static String getLoadFailure() {
    return loadFailure;
  }

  private static boolean hasPackagedResource(String[] candidates) {
    for (String resource : candidates) {
      if (NativeBridge.class.getResource(resource) != null) {
        return true;
      }
    }
    return false;
  }

  private static PackagedNativePayload extractPackagedPayload()
      throws IOException, NoSuchAlgorithmException {
    ResourcePayload library = readRequiredResource(
        LIBRARY_BASENAME, LIBRARY_RESOURCES);
    ResourcePayload shaders = readRequiredResource(
        SHADER_LIBRARY_BASENAME, SHADER_LIBRARY_RESOURCES);
    validateMetallib(shaders);

    String version = implementationVersion();
    Path cacheRoot = Path.of(System.getProperty("user.home"), "Library", "Caches",
        "MetalRender", "native", version,
        library.sha256().substring(0, 16)
            + "-" + shaders.sha256().substring(0, 16));
    Files.createDirectories(cacheRoot);

    // The native backend resolves shaders.metallib relative to its own image.
    // Materialize the shader first so it is present before System.load invokes
    // any native initialization.
    Path shaderDestination = writeVerifiedResource(
        cacheRoot, SHADER_LIBRARY_BASENAME, shaders, false);
    Path libraryDestination = writeVerifiedResource(
        cacheRoot, LIBRARY_BASENAME, library, true);

    Path manifest = cacheRoot.resolve("payload.sha256");
    Files.writeString(manifest,
        library.sha256() + "  " + library.resourceName()
            + System.lineSeparator()
            + shaders.sha256() + "  " + shaders.resourceName()
            + System.lineSeparator());
    return new PackagedNativePayload(
        libraryDestination.toAbsolutePath().normalize(),
        shaderDestination.toAbsolutePath().normalize());
  }

  private static ResourcePayload readRequiredResource(
      String basename, String[] candidates)
      throws IOException, NoSuchAlgorithmException {
    for (String resource : candidates) {
      try (InputStream input = NativeBridge.class.getResourceAsStream(resource)) {
        if (input == null) {
          continue;
        }
        byte[] bytes = input.readAllBytes();
        if (bytes.length == 0) {
          throw new IOException("packaged " + basename + " is empty: " + resource);
        }
        String digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes));
        return new ResourcePayload(bytes, resource, digest);
      }
    }
    throw new IOException("no packaged " + basename + " resource");
  }

  private static void validateMetallib(ResourcePayload shaders)
      throws IOException {
    byte[] bytes = shaders.bytes();
    if (bytes.length < 4
        || bytes[0] != 'M'
        || bytes[1] != 'T'
        || bytes[2] != 'L'
        || bytes[3] != 'B') {
      throw new IOException("packaged " + SHADER_LIBRARY_BASENAME
          + " is not a Metal library: " + shaders.resourceName());
    }
  }

  private static Path writeVerifiedResource(
      Path cacheRoot, String basename, ResourcePayload payload,
      boolean executable)
      throws IOException, NoSuchAlgorithmException {
    Path destination = cacheRoot.resolve(basename);
    byte[] bytes = payload.bytes();
    if (!Files.exists(destination)
        || Files.size(destination) != bytes.length
        || !payload.sha256().equals(sha256(destination))) {
      Path temporary = Files.createTempFile(cacheRoot, basename, ".tmp");
      try {
        Files.write(temporary, bytes);
        if (executable) {
          makeExecutable(temporary);
        }
        try {
          Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
          Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
    if (executable) {
      makeExecutable(destination);
    }

    Path checksum = cacheRoot.resolve(basename + ".sha256");
    Files.writeString(checksum,
        payload.sha256() + "  " + payload.resourceName()
            + System.lineSeparator());
    return destination;
  }

  private static void makeExecutable(Path path) {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
      permissions.add(PosixFilePermission.OWNER_READ);
      permissions.add(PosixFilePermission.OWNER_WRITE);
      permissions.add(PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(path, permissions);
    } catch (IOException | UnsupportedOperationException ignored) {
      path.toFile().setReadable(true, true);
      path.toFile().setWritable(true, true);
      path.toFile().setExecutable(true, true);
    }
  }

  private static String sha256(Path path)
      throws IOException, NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(path)) {
      byte[] chunk = new byte[64 * 1024];
      int read;
      while ((read = input.read(chunk)) >= 0) {
        digest.update(chunk, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String implementationVersion() {
    String version = NativeBridge.class.getPackage().getImplementationVersion();
    if (version == null || version.isBlank()) {
      version = fabricMetadataVersion();
    }
    if (version == null || version.isBlank()) {
      version = System.getProperty("metalrender.version", "development");
    }
    return version.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  /**
   * Fabric's Knot class loader does not always expose JAR package manifest
   * attributes through {@link Package}. Resolve the same version from loader
   * metadata without adding a hard Fabric runtime dependency to the standalone
   * packaged-payload smoke test.
   */
  private static String fabricMetadataVersion() {
    try {
      ClassLoader classLoader = NativeBridge.class.getClassLoader();
      Class<?> loaderType = Class.forName(
          "net.fabricmc.loader.api.FabricLoader", false, classLoader);
      Object loader = loaderType.getMethod("getInstance").invoke(null);
      Object containerResult = loaderType
          .getMethod("getModContainer", String.class)
          .invoke(loader, "metalrender");
      if (!(containerResult instanceof Optional<?> optional)
          || optional.isEmpty()) {
        return null;
      }
      Class<?> containerType = Class.forName(
          "net.fabricmc.loader.api.ModContainer", false, classLoader);
      Object metadata = containerType.getMethod("getMetadata")
          .invoke(optional.orElseThrow());
      Class<?> metadataType = Class.forName(
          "net.fabricmc.loader.api.metadata.ModMetadata", false, classLoader);
      Object semanticVersion = metadataType.getMethod("getVersion")
          .invoke(metadata);
      Class<?> versionType = Class.forName(
          "net.fabricmc.loader.api.Version", false, classLoader);
      Object friendly = versionType.getMethod("getFriendlyString")
          .invoke(semanticVersion);
      return friendly instanceof String value ? value : null;
    } catch (ReflectiveOperationException | LinkageError ignored) {
      return null;
    }
  }

  private static void markLoaded(String path) {
    loadedPath = path;
    loadFailure = null;
    libLoaded = true;
    loadState = LoadState.READY;
  }

  private static void markLoadFailure(LoadState state, String message) {
    libLoaded = false;
    loadedPath = null;
    loadFailure = message;
    loadState = state;
    System.err.println("[MetalRender] " + message);
  }

  private static String safeMessage(Throwable throwable) {
    if (throwable == null) {
      return "unknown";
    }
    String message = throwable.getMessage();
    return throwable.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private static UnsatisfiedLinkError asLinkError(String message, Throwable cause) {
    UnsatisfiedLinkError error = new UnsatisfiedLinkError(message);
    error.initCause(cause);
    return error;
  }

  public static native boolean nIsAvailable();

  public static native long nInit(int width, int height, float scale);

  public static native void nResize(long handle, int width, int height,
      float scale);

  public static native void nBeginFrame(long handle, float[] proj, float[] view,
      float fogStart, float fogEnd);

  public static native void nOnWorldLoaded(long handle);

  public static native void nOnWorldUnloaded(long handle);

  public static native void nDestroy(long handle);

  public static native String nGetDeviceName();

  public static native boolean nSupportsIndirect();

  public static native boolean nSupportsMeshShaders();

  public static native void nSetCurrentThreadQoS(int qosClass);

  public static native long nCreateBufferWithHint(long deviceHandle, int sizeBytes,
      int storageMode, long oldHandle);

  public static native long nCreateBuffer(long deviceHandle, int sizeBytes,
      int storageMode);

  public static native void nUploadBufferData(long bufferHandle, byte[] data,
      int offset, int length);

  public static native void nUploadBufferDataDirect(long bufferHandle,
      java.nio.ByteBuffer data,
      int offset, int length);

  public static native void nDestroyBuffer(long bufferHandle);

  public static native void nSetPipelineState(long frameContext,
      long pipelineHandle);

  public static native void nSetChunkOffset(long frameContext, float x, float y,
      float z);

  public static native void nDrawIndexedBuffer(long frameContext,
      long vertexBuffer,
      long indexBuffer, int indexCount,
      int baseIndex);

  public static native void nDrawIndexedBatch(long frameContext,
      long indexBuffer,
      float[] drawData, int drawCount);

  public static native void nDrawBuffer(long frameContext, long vertexBuffer,
      int vertexCount, int baseVertex);

  public static native long nGetCurrentFrameContext(long handle);

  public static native void nEndFrame(long handle);

  public static native void nSetProjectionMatrix(long handle, float[] matrix);

  public static native void nSetModelViewMatrix(long handle, float[] matrix);

  public static native void nSetCameraPosition(long handle, double x, double y,
      double z);

  public static native void nSetFrameMatrices(long handle, float[] projMatrix,
      float[] mvMatrix, double camX,
      double camY, double camZ);

  public static native void nBindTexture(long handle, long textureHandle,
      int slot);

  public static native long nCreateTexture2D(long deviceHandle, int width,
      int height, byte[] pixelData);

  public static native void nDestroyTexture2D(long textureHandle);

  public static native boolean nUpdateTexture2D(long textureHandle, int width,
      int height, byte[] pixelData);

  public static native long nGetDeviceHandle(long handle);

  public static native long nGetShaderLibraryHandle(long handle);

  public static native long nGetInhousePipelineHandle(long handle);

  public static native long nGetDefaultPipelineHandle(long handle);

  public static native int nGetGLTextureId(long handle);

  public static native int nGetIOSurfaceWidth(long handle);

  public static native int nGetIOSurfaceHeight(long handle);

  public static native void nWaitForRender(long handle);

  public static native boolean nIsFrameReady(long handle);

  public static native void nRecycleUnpresentedFrames(long handle);

  public static native void nReleaseBoundPresentationSurface(long handle);

  public static native void nSetReuseTerrainFrame(boolean reuse);

  public static native boolean nBindIOSurfaceToTexture(long handle,
      int glTexture);

  public static native boolean nReadbackPixels(long handle,
      java.nio.ByteBuffer dest);

  public static native boolean nReadbackDepth(long handle,
      java.nio.ByteBuffer dest);

  public static native long nGetEntityPipelineHandle(long handle);

  public static native long nGetEntityTranslucentPipelineHandle(long handle);

  public static native long nGetEntityEmissivePipelineHandle(long handle);

  public static native long nGetParticlePipelineHandle(long handle);

  public static native void nSetEntityOverlay(long frameContext, float hurtTime,
      float whiteFlash, float alpha);

  public static native void nSetWaterFog(long frameContext, float waterFog);

  public static native void nSetSkyBrightness(long frameContext,
      float brightness);

  public static native void nSetEntityTintColor(long frameContext, float r,
      float g, float b, float a);

  public static native void nBindEntityTexture(long frameContext,
      long textureHandle);

  public static native void nDrawEntityBuffer(long frameContext,
      long vertexBuffer,
      int vertexCount, int baseVertex,
      int renderFlags);

  public static native void nDrawEntityBufferIndexed(long frameContext, long vertexBuffer,
      long indexBuffer, int indexCount, int baseIndex,
      int renderFlags);

  public static native void nSetDebugColor(long frameContext, float r, float g,
      float b, float a);

  public static native void nDrawLineBuffer(long frameContext,
      long vertexBuffer, int vertexCount);

  public static native void nDrawTriangleBuffer(long frameContext, long vertexBuffer, int vertexCount);

  public static native void nDrawOverlayQuad(long frameContext, float r,
      float g, float b, float a);

  public static native void nUploadCameraUniforms(long handle, float[] viewProj, float[] proj,
      float[] modelView, float[] cameraPos,
      float[] frustumPlanes, float screenW, float screenH,
      float nearPlane, float farPlane, int totalChunks);

  public static native void nUploadSubChunkData(long handle, java.nio.ByteBuffer directBuffer, int count);

  public static native void nUploadChunkUniforms(long handle, java.nio.ByteBuffer directBuffer,
      int count);

  public static native void nSetGPUDrivenEnabled(long handle, boolean enabled);

  public static native int nRunGPUCulling(long handle, int chunkCount);

  public static native void nExecuteIndirectDraws(long frameContext, long vertexBuffer, long indexBuffer);

  public static native int nGetGPUVisibleCount(long handle);

  public static native void nGetGPUCullStats(int[] outStats);

  public static native int nGetThermalState();
  public static native float nGetGpuFrameTimeMs();
  public static native void nSetTemporalScale(float scale);
  public static native boolean nAreResidencySetsSupported();
  public static native long nCreateResidencySet(long device);
  public static native void nUpdateResidencySet(long set, long[] textures);
  public static native void nDestroyResidencySet(long set);

  public static native void nSetRenderDistance(int distanceBlocks);

  public static native long nGetAvailableMemory();

  public static native int nGetHiZMipCount();

  public static native boolean nIsGPUDrivenActive();

  public static native boolean nAreMeshShadersActive();

  public static native void nRegisterChunkMeshBatch(int count, long[] batchData);

  public static native void nRegisterChunkMesh(int cx, int cy, int cz,
      long bufferHandle, int quadCount,
      int opaqueQuadCount,
      long visibilityMask, int[] facingQuadCounts);

  public static native void nUnregisterChunkMesh(int cx, int cy, int cz);

  public static native int nDrawAllVisibleChunks(long frameContext,
      long indexBuffer);

  public static native void nWatchdogReset();

  public static native void nFlushFrames();

  public static native void nClearAllChunkRegistrations();

  public static native void nFlushDeferredDeletions();

  public static native void nDrawDeferredWaterPass(long frameContext);

  public static native boolean nAreArgumentBuffersActive();

  public static native boolean nAreMemorylessTargetsActive();

  public static native void nSetFeatureFlags(boolean enableIndirectCommandBuffers,
      boolean enableMeshShaders, boolean enableArgumentBuffers,
      boolean enableProgrammableBlending);

  public static native void nConfigureRuntime(boolean enableMetal4,
      int memoryBudgetMB, int targetFrameRate, boolean tripleBuffering);

  public static native boolean nSupportsMetal4();

  public static native boolean nIsMetal4Active();

  public static native boolean nIsMetal4DrawPathActive();

  public static native String nGetBackendMode();

  /**
   * Process-lifetime monotonic counters used by release QA. They deliberately
   * do not reset when a renderer or world is recreated, so a late asynchronous
   * native failure cannot be hidden by lifecycle cleanup.
   */
  public static native long nGetGpuCommandBufferErrorCount();

  public static native long nGetInFlightFrameTimeoutCount();

  public static native long nGetNoIOSurfaceSlotSkipCount();

  /**
   * Compiles one bounded UTF-8 MSL stage into an ephemeral Metal library and
   * validates its {@code main0} function type. No pipeline or draw is created.
   */
  public static native int nValidateIrisMslLibrary(byte[] mslUtf8,
      int stageOrdinal);

  public static native boolean nIsIrisMslCompilerReady();

  public static native long nGetIrisMslCompileAttemptCount();

  public static native long nGetIrisMslCompileSuccessCount();

  public static native long nGetIrisMslCompileUnsupportedCount();

  public static native long nGetIrisMslCompileFailureCount();

  public static native long nGetIrisMslLiveLibraryCount();

  /** Device/OS/Metal-runtime identity used only as pipeline-cache key input. */
  public static native String nGetIrisMetal4PipelineCacheIdentity();

  public static native int nConfigureIrisMetal4PipelineCache(
      String archivePath);

  /** Creates and retains a pipeline object; this method never encodes a draw. */
  public static native int nCompileIrisMetal4Pipeline(String pipelineKey,
      String shaderKey, byte[] descriptor, byte[] vertexMsl,
      byte[] fragmentMsl, byte[] computeMsl);

  public static native boolean nFlushIrisMetal4PipelineCache();

  public static native long nGetIrisMetal4PipelineAttemptCount();

  public static native long nGetIrisMetal4PipelineCompileCount();

  public static native long nGetIrisMetal4PipelineCacheHitCount();

  public static native long nGetIrisMetal4PipelineFailureCount();

  public static native long nGetIrisMetal4PipelineStaleRecoveryCount();

  public static native long nGetIrisMetal4LivePipelineCount();

  public static native long nGetIrisMetal4PipelineDrawAttemptCount();

  /**
   * Executes a bounded offscreen MTL4 draw with a retained Iris pipeline and
   * compares every output pixel with the expected RGBA8 value.
   */
  public static native int nRunIrisMetal4ShadowParitySmoke(
      String pipelineKey, int width, int height, int expectedRgba,
      int channelTolerance);

  /**
   * Executes one strict MRX7 packet offscreen. The result is
   * {status, FNV-1a-64 output hash, width, height, reason code}; status is 1
   * for success, 0 for an unsupported runtime case, and -1 for
   * malformed/failure.
   */
  public static native long[] nRunIrisMetal4ShadowReplay(
      String pipelineKey, byte[] packet);

  /**
   * Executes the same strict packet into an IOSurface-backed RGBA8 target.
   * The successful result token identifies completion, not a CPU pixel hash.
   */
  public static native long[] nRunIrisMetal4FinalCutoverReplay(
      String pipelineKey, byte[] packet);

  /**
   * Copies one GL 2D input into a reusable IOSurface entirely on the GPU.
   * Kind 1 is RGBA8; kind 2 converts RG11B10F to RGBA16F.
   */
  public static native long nCaptureIrisMetal4InputSurface(
      int glTexture, int width, int height, int kind);

  /** Uploads one immutable RGBA8 mirror generation into resident Metal. */
  public static native long nUploadIrisMetal4InputTexture(
      int glTexture, long generation, int width, int height, byte[] rgba8);

  /** Uploads one content-addressed immutable draw buffer into resident Metal. */
  public static native long nUploadIrisMetal4InputBuffer(
      String sha256, byte[] bytes);

  /**
   * Creates or reuses one private Metal texture for a generation-qualified
   * Iris graph attachment. The returned token is opaque and calling-thread
   * scoped; zero means fail-open to Iris/OpenGL.
   */
  public static native long nEnsureIrisMetal4GraphTexture(
      long contextGeneration, int glTexture, long resourceGeneration,
      String format, int sampleCount, int width, int height,
      int depthOrLayers, int mipLevels, int usage);

  public static native int nGetIrisMetal4GraphTextureCount();

  public static native long nGetIrisMetal4GraphTextureBytes();

  public static native void nResetIrisMetal4GraphResources();

  /**
   * Executes one strict MGF9 clear/barrier/transfer/draw batch in one MTL4
   * command buffer. The result is {status, steps, clears, transfers,
   * barriers, readbackHash, reason}.
   */
  public static native long[] nRunIrisMetal4GraphFrame(byte[] packet,
      int diagnosticReadbackMipLevel);

  /** Submits one presentation-mode MGF9 frame without waiting for the GPU. */
  public static native long[] nSubmitIrisMetal4GraphFrame(byte[] packet);

  /**
   * Direct-buffer variant used by the production frame path to avoid a large
   * per-frame Java heap array and primitive-array pin.
   */
  public static native long[] nSubmitIrisMetal4GraphFrameDirect(
      ByteBuffer packet, int length);

  /**
   * Returns {status, token, width, height, reason} for an async frame.
   * A nonblocking PENDING probe may return zero width and height.
   */
  public static native long[] nGetIrisMetal4GraphPresentationStatus(
      long token);

  /** Promotes a completed async frame into the existing GL IOSurface binder. */
  public static native boolean nPromoteIrisMetal4GraphPresentation(
      long token, int width, int height);

  /** Discards one pending or completed async presentation token. */
  public static native boolean nDiscardIrisMetal4GraphPresentation(
      long token);

  /**
   * Returns process-lifetime full-graph timing counters:
   * {gpuSamples, lastGpuNs, totalGpuNs, maxGpuNs, feedbackErrors,
   * cpuSamples, lastCpuNs, totalCpuNs, maxCpuNs,
   * lastQueueNs, totalQueueNs, maxQueueNs}.
   */
  public static native long[] nGetIrisMetal4GraphTiming();

  /** Cumulative native CPU phase timing for submitted MGF9 frames. */
  public static native long[] nGetIrisMetal4GraphCpuProfile();

  /**
   * Starts a fresh exact-QA-only raw MTL4 commit-feedback sample window.
   * Returns false when the bounded native sample buffer cannot be prepared.
   */
  public static native boolean nResetIrisMetal4GraphPerformanceSamples();

  /**
   * Drains raw full-graph GPU nanoseconds. Element zero is the number of
   * samples dropped since the previous drain; remaining elements are samples.
   */
  public static native long[] nDrainIrisMetal4GraphPerformanceSamples();

  /** Takes the RGBA8 output of the last successful graph on this thread. */
  public static native byte[] nTakeIrisMetal4GraphFrameRgba8();

  /**
   * Binds the completed calling-thread cutover IOSurface to a GL rectangle.
   * The caller must have glTexture bound to GL_TEXTURE_RECTANGLE on the
   * current context before entering JNI. When deferIfBusy is true, native
   * submission contention leaves the promoted surface pending for a retry.
   */
  public static native boolean nBindIrisMetal4FinalCutoverSurface(
      int glTexture, int width, int height, boolean deferIfBusy);

  /** Fences the GL use of one bound cutover surface without waiting. */
  public static native boolean nFenceIrisMetal4FinalCutoverSurface(
      int glTexture);

  /** Drops a promoted surface that was not consumed by a GL binding. */
  public static native boolean nDiscardIrisMetal4FinalCutoverSurface();

  /** Releases calling-thread cutover surfaces after the GL texture is gone. */
  public static native void nResetIrisMetal4FinalCutoverSurface();

  /**
   * Takes and clears the RGBA8 bytes produced by the last successful replay
   * on this calling thread. Other output formats return an empty array.
   */
  public static native byte[] nTakeIrisMetal4ShadowReplayRgba8();

  public static native void nResetIrisMetal4Pipelines();

  public static native void nDrawOITPass(long frameContext);

  public static native long nCreateIndirectCommandBuffer(long deviceHandle,
      int maxCommands);
  public static native void nEncodeChunkDrawICBCmd(long icb, int cmdIndex,
      int sectionIndex, int instanceCount,
      long meshBuffer, long indexBuffer, int indexCount);
  public static native void nExecuteIndirectCommandBuffer(long frameContext,
      long icb);
  public static native void nDestroyIndirectCommandBuffer(long icb);
}

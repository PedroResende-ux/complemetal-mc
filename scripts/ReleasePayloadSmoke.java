import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class ReleasePayloadSmoke {
  private ReleasePayloadSmoke() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("expected isolated user.home path");
    }

    Path isolatedHome = Path.of(args[0]).toAbsolutePath().normalize();
    System.setProperty("user.home", isolatedHome.toString());

    NativeBridge.loadLibrary();
    if (!NativeBridge.isLibLoaded() || !NativeBridge.nIsAvailable()) {
      throw new IllegalStateException(
          "packaged native backend did not become available: "
              + NativeBridge.getLoadFailure());
    }
    if (NativeBridge.nIsIrisMslCompilerReady()) {
      throw new IllegalStateException(
          "Iris MSL compiler became ready before renderer initialization");
    }
    IrisMslCompileCounters preInitCounters = irisMslCompileCounters();
    byte[] preInitMsl = ("""
        #include <metal_stdlib>
        using namespace metal;
        fragment float4 main0() { return float4(1.0); }
        """).getBytes(StandardCharsets.UTF_8);
    int preInitResult =
        NativeBridge.nValidateIrisMslLibrary(preInitMsl, 4);
    if (preInitResult != NativeBridge.IRIS_MSL_COMPILE_DEFERRED) {
      throw new IllegalStateException(
          "pre-init Iris MSL validation was not deferred: " + preInitResult);
    }
    if (!irisMslCompileCounters().equals(preInitCounters)
        || NativeBridge.nGetIrisMslLiveLibraryCount() != 0) {
      throw new IllegalStateException(
          "pre-init Iris MSL validation changed terminal telemetry");
    }

    Path loadedLibrary = Path.of(NativeBridge.getLoadedPath())
        .toAbsolutePath().normalize();
    if (!loadedLibrary.startsWith(isolatedHome)) {
      throw new IllegalStateException(
          "native library was not extracted from the release JAR: "
              + loadedLibrary);
    }
    String implementationVersion =
        NativeBridge.class.getPackage().getImplementationVersion();
    if (implementationVersion == null || implementationVersion.isBlank()) {
      throw new IllegalStateException(
          "release JAR has no Implementation-Version");
    }
    String expectedCacheVersion =
        implementationVersion.replaceAll("[^A-Za-z0-9._-]", "_");
    Path versionDirectory = loadedLibrary.getParent().getParent();
    if (!expectedCacheVersion.equals(
        versionDirectory.getFileName().toString())) {
      throw new IllegalStateException(
          "native payload cache has the wrong version directory: "
              + versionDirectory);
    }
    Path shaderLibrary = loadedLibrary.resolveSibling("shaders.metallib");
    byte[] magic = Files.readAllBytes(shaderLibrary);
    if (magic.length < 4
        || magic[0] != 'M'
        || magic[1] != 'T'
        || magic[2] != 'L'
        || magic[3] != 'B') {
      throw new IllegalStateException(
          "extracted shaders.metallib is invalid: "
              + HexFormat.of().formatHex(magic, 0, Math.min(4, magic.length)));
    }

    long handle = NativeBridge.nInit(64, 64, 1.0f);
    if (handle == 0) {
      throw new IllegalStateException("native renderer initialization failed");
    }
    if (!NativeBridge.nIsIrisMslCompilerReady()) {
      throw new IllegalStateException(
          "Iris MSL compiler did not become ready after initialization");
    }

    try {
      IrisMslCompileCounters irisMslDelta = runIrisMslValidationSmoke();
      NativeFaultCounters faultBaseline = nativeFaultCounters();
      if (!faultBaseline.isZero()) {
        throw new IllegalStateException(
            "native fault counters were non-zero before payload smoke: "
                + faultBaseline);
      }
      NativeBridge.nConfigureRuntime(true, 128, 200, true);

      List<Long> arenaAllocations = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        long allocation = NativeBridge.nCreateBuffer(
            handle, 16 * 1024 * 1024, 0);
        if (allocation == 0) {
          throw new IllegalStateException(
              "bounded native arena stopped before its 128 MiB cap at "
                  + index + " allocations");
        }
        arenaAllocations.add(allocation);
      }
      if (NativeBridge.nCreateBuffer(
          handle, 16 * 1024 * 1024, 0) != 0) {
        throw new IllegalStateException(
            "native allocation exceeded the configured 128 MiB cap");
      }
      for (long allocation : arenaAllocations) {
        NativeBridge.nDestroyBuffer(allocation);
      }
      NativeBridge.nFlushDeferredDeletions();

      long publishedBuffer = NativeBridge.nCreateBuffer(
          handle, 4096, 0);
      long replacementBuffer = NativeBridge.nCreateBufferWithHint(
          handle, 4096, 0, publishedBuffer);
      if (publishedBuffer == 0 || replacementBuffer == 0) {
        throw new IllegalStateException(
            "native replacement-buffer allocation failed");
      }
      if (publishedBuffer == replacementBuffer) {
        throw new IllegalStateException(
            "replacement upload would overwrite a published/in-flight buffer");
      }
      NativeBridge.nDestroyBuffer(replacementBuffer);
      NativeBridge.nDestroyBuffer(publishedBuffer);

      long device = NativeBridge.nGetDeviceHandle(handle);
      if (device == 0) {
        throw new IllegalStateException("native Metal device is unavailable");
      }
      if (NativeBridge.nCreateTexture2D(
          0, 1, 1, new byte[4]) != 0
          || NativeBridge.nCreateTexture2D(
              device, 1, 1, null) != 0
          || NativeBridge.nCreateTexture2D(
              device, 0, 1, new byte[4]) != 0
          || NativeBridge.nCreateTexture2D(
              device, 2, 2, new byte[15]) != 0
          || NativeBridge.nCreateTexture2D(
              device, Integer.MAX_VALUE, 2, new byte[0]) != 0
          || NativeBridge.nCreateTexture2D(
              device + 1, 1, 1, new byte[4]) != 0) {
        throw new IllegalStateException(
            "invalid texture creation input was not rejected");
      }
      long texture = NativeBridge.nCreateTexture2D(
          device, 2, 2, new byte[16]);
      if (texture == 0) {
        throw new IllegalStateException(
            "valid native texture creation failed");
      }
      if (NativeBridge.nUpdateTexture2D(
              0, 2, 2, new byte[16])
          || NativeBridge.nUpdateTexture2D(
              texture, 3, 2, new byte[24])
          || NativeBridge.nUpdateTexture2D(
              texture, 2, 2, new byte[15])) {
        throw new IllegalStateException(
            "invalid native texture update was not rejected");
      }
      byte[] updatedPixels = new byte[16];
      java.util.Arrays.fill(updatedPixels, (byte) 0x7F);
      if (!NativeBridge.nUpdateTexture2D(
          texture, 2, 2, updatedPixels)) {
        throw new IllegalStateException(
            "valid native texture update was not submitted");
      }
      NativeBridge.nFlushFrames();
      NativeBridge.nDestroyTexture2D(texture);

      long pacingMillis = runFramePacingStress(handle, 1_000);

      String backendMode = NativeBridge.nGetBackendMode();
      if (backendMode == null || backendMode.isBlank()) {
        throw new IllegalStateException("native backend mode is empty");
      }
      boolean metal4Supported = NativeBridge.nSupportsMetal4();
      boolean metal4Active = NativeBridge.nIsMetal4Active();
      if (isMacOs26OrNewer()
          && (!metal4Supported || !metal4Active
              || !"METAL4_RUNTIME_VERIFIED_METAL3_RENDER".equals(
                  backendMode))) {
        throw new IllegalStateException(
            "macOS 26 release payload did not complete the Metal 4 runtime "
                + "probe: supported=" + metal4Supported
                + " active=" + metal4Active
                + " backend=" + backendMode);
      }
      if (NativeBridge.nIsMetal4DrawPathActive()) {
        throw new IllegalStateException(
            "this release must not advertise an unvalidated MTL4 draw path");
      }

      NativeBridge.nConfigureRuntime(false, 128, 200, true);
      if (NativeBridge.nIsMetal4Active()
          || !"METAL3".equals(NativeBridge.nGetBackendMode())) {
        throw new IllegalStateException(
            "Metal 3 compatibility mode did not activate cleanly: "
                + NativeBridge.nGetBackendMode());
      }
      long metal3PacingMillis = runFramePacingStress(handle, 240);

      NativeBridge.nConfigureRuntime(true, 128, 200, true);
      if (isMacOs26OrNewer()
          && (!NativeBridge.nIsMetal4Active()
              || !"METAL4_RUNTIME_VERIFIED_METAL3_RENDER".equals(
                  NativeBridge.nGetBackendMode()))) {
        throw new IllegalStateException(
            "Metal 4 runtime did not recover after Metal 3 compatibility "
                + "cycle: " + NativeBridge.nGetBackendMode());
      }
      NativeBridge.nFlushFrames();
      NativeFaultCounters faultEnd = nativeFaultCounters();
      NativeFaultCounters faultDelta = faultEnd.deltaFrom(faultBaseline);
      if (!faultDelta.isZero()) {
        throw new IllegalStateException(
            "native fault counters advanced during payload smoke: "
                + faultDelta);
      }
      System.out.printf(
          "release-payload-smoke: loaded=%s available=true metal4Supported=%s "
              + "metal4Active=%s metal4Draw=false backend=%s shaderBytes=%d "
              + "arenaMiB=128 pacingFrames=1000 pacingMs=%d "
              + "metal3Frames=240 metal3PacingMs=%d irisMslDelta=%s "
              + "irisMslLive=0 nativeFaultDelta=%s%n",
          loadedLibrary,
          metal4Supported,
          metal4Active,
          NativeBridge.nGetBackendMode(),
          magic.length,
          pacingMillis,
          metal3PacingMillis,
          irisMslDelta,
          faultDelta);
    } finally {
      NativeBridge.nDestroy(handle);
    }
    if (NativeBridge.nIsIrisMslCompilerReady()) {
      throw new IllegalStateException(
          "Iris MSL compiler remained ready after renderer destruction");
    }
  }

  private static IrisMslCompileCounters runIrisMslValidationSmoke() {
    IrisMslCompileCounters baseline = irisMslCompileCounters();
    if (NativeBridge.nGetIrisMslLiveLibraryCount() != 0) {
      throw new IllegalStateException(
          "Iris MSL validator retained a library before smoke");
    }

    byte[] vertexMsl = ("""
        #include <metal_stdlib>
        using namespace metal;
        struct VertexOut {
          float4 position [[position]];
        };
        vertex VertexOut main0(uint vertexId [[vertex_id]]) {
          VertexOut out;
          out.position = float4(float(vertexId), 0.0, 0.0, 1.0);
          return out;
        }
        """).getBytes(StandardCharsets.UTF_8);
    byte[] fragmentMsl = ("""
        #include <metal_stdlib>
        using namespace metal;
        fragment float4 main0() {
          return float4(1.0);
        }
        """).getBytes(StandardCharsets.UTF_8);
    byte[] invalidMsl = "not valid Metal Shading Language"
        .getBytes(StandardCharsets.UTF_8);

    int vertexResult = NativeBridge.nValidateIrisMslLibrary(vertexMsl, 0);
    int fragmentResult = NativeBridge.nValidateIrisMslLibrary(fragmentMsl, 4);
    int invalidResult = NativeBridge.nValidateIrisMslLibrary(invalidMsl, 4);
    int geometryResult = NativeBridge.nValidateIrisMslLibrary(vertexMsl, 3);
    if (vertexResult != NativeBridge.IRIS_MSL_COMPILE_COMPILED
        || fragmentResult != NativeBridge.IRIS_MSL_COMPILE_COMPILED) {
      throw new IllegalStateException(
          "valid Iris MSL stages did not compile: vertex=" + vertexResult
              + " fragment=" + fragmentResult);
    }
    if (invalidResult != NativeBridge.IRIS_MSL_COMPILE_FAILED) {
      throw new IllegalStateException(
          "invalid Iris MSL was not classified FAILED: " + invalidResult);
    }
    if (geometryResult != NativeBridge.IRIS_MSL_COMPILE_UNSUPPORTED) {
      throw new IllegalStateException(
          "geometry MSL stage was not classified UNSUPPORTED: "
              + geometryResult);
    }

    IrisMslCompileCounters delta = irisMslCompileCounters().deltaFrom(baseline);
    IrisMslCompileCounters expected =
        new IrisMslCompileCounters(4, 2, 1, 1);
    if (!delta.equals(expected)) {
      throw new IllegalStateException(
          "Iris MSL validator counter delta mismatch: expected=" + expected
              + " actual=" + delta);
    }
    if (NativeBridge.nGetIrisMslLiveLibraryCount() != 0) {
      throw new IllegalStateException(
          "Iris MSL validator retained an ephemeral library");
    }
    if (NativeBridge.nIsMetal4DrawPathActive()) {
      throw new IllegalStateException(
          "Iris MSL validation activated the unvalidated MTL4 draw path");
    }
    return delta;
  }

  private static long runFramePacingStress(long handle, int frameCount) {
    long startedAt = System.nanoTime();
    for (int frame = 0; frame < frameCount; frame++) {
      long frameContext = NativeBridge.nGetCurrentFrameContext(handle);
      if (frameContext == 0) {
        throw new IllegalStateException(
            "native frame pacing stalled before frame "
                + frame + " of " + frameCount);
      }
      NativeBridge.nEndFrame(handle);
      NativeBridge.nWaitForRender(handle);
      if (!NativeBridge.nIsFrameReady(handle)) {
        throw new IllegalStateException(
            "native frame did not complete before bounded wait at frame "
                + frame + " of " + frameCount);
      }
      NativeBridge.nRecycleUnpresentedFrames(handle);
    }
    long elapsedMillis =
        (System.nanoTime() - startedAt) / 1_000_000L;
    long maximumMillis = Math.max(2_000L, frameCount * 5L);
    if (elapsedMillis > maximumMillis) {
      throw new IllegalStateException(
          "native 200 Hz pacing stress exceeded "
              + maximumMillis + " ms: " + elapsedMillis + " ms");
    }
    return elapsedMillis;
  }

  private static boolean isMacOs26OrNewer() {
    if (!System.getProperty("os.name", "")
        .toLowerCase(java.util.Locale.ROOT).contains("mac")) {
      return false;
    }
    try {
      return Runtime.Version.parse(
          System.getProperty("os.version", "0")).feature() >= 26;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static NativeFaultCounters nativeFaultCounters() {
    return new NativeFaultCounters(
        NativeBridge.nGetGpuCommandBufferErrorCount(),
        NativeBridge.nGetInFlightFrameTimeoutCount(),
        NativeBridge.nGetNoIOSurfaceSlotSkipCount());
  }

  private static IrisMslCompileCounters irisMslCompileCounters() {
    return new IrisMslCompileCounters(
        NativeBridge.nGetIrisMslCompileAttemptCount(),
        NativeBridge.nGetIrisMslCompileSuccessCount(),
        NativeBridge.nGetIrisMslCompileUnsupportedCount(),
        NativeBridge.nGetIrisMslCompileFailureCount());
  }

  private record IrisMslCompileCounters(long attempts, long successes,
                                        long unsupported, long failures) {
    private IrisMslCompileCounters {
      if (attempts < 0 || successes < 0 || unsupported < 0 || failures < 0) {
        throw new IllegalStateException(
            "Iris MSL compile counter overflowed signed Java range");
      }
      if (successes + unsupported + failures > attempts) {
        throw new IllegalStateException(
            "Iris MSL terminal counters exceed attempts");
      }
    }

    private IrisMslCompileCounters deltaFrom(
        IrisMslCompileCounters baseline) {
      if (attempts < baseline.attempts
          || successes < baseline.successes
          || unsupported < baseline.unsupported
          || failures < baseline.failures) {
        throw new IllegalStateException(
            "Iris MSL compile counters are not monotonic");
      }
      return new IrisMslCompileCounters(
          attempts - baseline.attempts,
          successes - baseline.successes,
          unsupported - baseline.unsupported,
          failures - baseline.failures);
    }
  }

  private record NativeFaultCounters(long gpuCommandBufferErrors,
                                     long inFlightFrameTimeouts,
                                     long noIOSurfaceSlotSkips) {
    private NativeFaultCounters {
      if (gpuCommandBufferErrors < 0
          || inFlightFrameTimeouts < 0
          || noIOSurfaceSlotSkips < 0) {
        throw new IllegalStateException(
            "native fault counter overflowed signed Java range");
      }
    }

    private boolean isZero() {
      return gpuCommandBufferErrors == 0
          && inFlightFrameTimeouts == 0
          && noIOSurfaceSlotSkips == 0;
    }

    private NativeFaultCounters deltaFrom(NativeFaultCounters baseline) {
      if (gpuCommandBufferErrors < baseline.gpuCommandBufferErrors
          || inFlightFrameTimeouts < baseline.inFlightFrameTimeouts
          || noIOSurfaceSlotSkips < baseline.noIOSurfaceSlotSkips) {
        throw new IllegalStateException(
            "native fault counters are not monotonic");
      }
      return new NativeFaultCounters(
          gpuCommandBufferErrors - baseline.gpuCommandBufferErrors,
          inFlightFrameTimeouts - baseline.inFlightFrameTimeouts,
          noIOSurfaceSlotSkips - baseline.noIOSurfaceSlotSkips);
    }
  }
}

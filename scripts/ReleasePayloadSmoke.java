import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class ReleasePayloadSmoke {
  private static final int IRIS_METAL4_SUBMISSION_BURST = 24;
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
      String irisMetal4GraphResources = NativeBridge.nIsMetal4Active()
          ? runIrisMetal4GraphResourceSmoke()
          : "unsupported";
      String irisMetal4PipelineSmoke = NativeBridge.nIsMetal4Active()
          ? runIrisMetal4PipelineCacheSmoke(
              isolatedHome.resolve("iris-metal4-pipeline-smoke"))
          : "unsupported";

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
              + "irisMetal4GraphResources=%s "
              + "irisMslLive=0 irisMetal4Pipeline=%s "
              + "nativeFaultDelta=%s%n",
          loadedLibrary,
          metal4Supported,
          metal4Active,
          NativeBridge.nGetBackendMode(),
          magic.length,
          pacingMillis,
          metal3PacingMillis,
          irisMslDelta,
          irisMetal4GraphResources,
          irisMetal4PipelineSmoke,
          faultDelta);
    } finally {
      NativeBridge.nDestroy(handle);
    }
    if (NativeBridge.nIsIrisMslCompilerReady()) {
      throw new IllegalStateException(
          "Iris MSL compiler remained ready after renderer destruction");
    }
  }

  private static String runIrisMetal4GraphResourceSmoke() throws Exception {
    final int shaderRead = 1;
    final int renderTarget = 1 << 2;
    NativeBridge.nResetIrisMetal4GraphResources();
    if (NativeBridge.nGetIrisMetal4GraphTextureCount() != 0
        || NativeBridge.nGetIrisMetal4GraphTextureBytes() != 0
        || NativeBridge.nEnsureIrisMetal4GraphTexture(0, 41, 3,
            "rgba16-float", 1, 64, 32, 1, 1,
            shaderRead | renderTarget) != 0
        || NativeBridge.nEnsureIrisMetal4GraphTexture(7, 41, 3,
            "rgba16-float", 1, 64, 32, 1, 1, shaderRead) != 0) {
      throw new IllegalStateException(
          "Iris Metal graph resource reset/input validation failed");
    }
    long first = NativeBridge.nEnsureIrisMetal4GraphTexture(7, 41, 3,
        "rgba16-float", 1, 64, 32, 1, 4,
        shaderRead | renderTarget);
    long reused = NativeBridge.nEnsureIrisMetal4GraphTexture(7, 41, 3,
        "rgba16-float", 1, 64, 32, 1, 4,
        shaderRead | renderTarget);
    long firstBytes = NativeBridge.nGetIrisMetal4GraphTextureBytes();
    if (first <= 0 || reused != first
        || NativeBridge.nGetIrisMetal4GraphTextureCount() != 1
        || firstBytes <= 0) {
      throw new IllegalStateException(
          "Iris Metal graph texture was not reused exactly");
    }

    long resized = NativeBridge.nEnsureIrisMetal4GraphTexture(7, 41, 3,
        "rgba16-float", 1, 128, 64, 1, 4,
        shaderRead | renderTarget);
    if (resized <= 0 || resized == first
        || NativeBridge.nGetIrisMetal4GraphTextureCount() != 1
        || NativeBridge.nGetIrisMetal4GraphTextureBytes() <= 0) {
      throw new IllegalStateException(
          "Iris Metal graph texture resize did not replace one generation");
    }

    long nextGeneration = NativeBridge.nEnsureIrisMetal4GraphTexture(
        7, 41, 4, "rgba16-float", 1, 128, 64, 1, 4,
        shaderRead | renderTarget);
    long depth = NativeBridge.nEnsureIrisMetal4GraphTexture(
        7, 42, 1, "d32-float", 1, 128, 64, 1, 1,
        shaderRead | renderTarget);
    long residentBytes = NativeBridge.nGetIrisMetal4GraphTextureBytes();
    if (nextGeneration <= 0 || nextGeneration == resized || depth <= 0
        || NativeBridge.nGetIrisMetal4GraphTextureCount() != 3
        || residentBytes <= firstBytes) {
      throw new IllegalStateException(
          "Iris Metal graph generation/depth ownership failed");
    }
    long frameSource = NativeBridge.nEnsureIrisMetal4GraphTexture(
        9, 51, 1, "rgba8-unorm", 1, 16, 16, 1, 1,
        shaderRead | renderTarget);
    long frameDestination = NativeBridge.nEnsureIrisMetal4GraphTexture(
        9, 52, 1, "rgba8-unorm", 1, 16, 16, 1, 5,
        shaderRead | renderTarget);
    byte[] framePacket = minimalIrisMetal4GraphFramePacket(
        frameSource, frameDestination);
    long[] firstFrame = NativeBridge.nRunIrisMetal4GraphFrame(framePacket, 0);
    long[] secondFrame = NativeBridge.nRunIrisMetal4GraphFrame(framePacket, 0);
    if (frameSource <= 0 || frameDestination <= 0
        || firstFrame == null || firstFrame.length != 7
        || firstFrame[0] != 1 || firstFrame[1] != 5
        || firstFrame[2] != 1 || firstFrame[3] != 2
        || firstFrame[4] != 2 || firstFrame[5] == 0
        || firstFrame[6] != 0
        || !java.util.Arrays.equals(firstFrame, secondFrame)) {
      throw new IllegalStateException(
          "Iris Metal graph frame batch failed or was nondeterministic: "
              + java.util.Arrays.toString(firstFrame) + " / "
              + java.util.Arrays.toString(secondFrame));
    }
    byte[] presentationPacket = framePacket.clone();
    java.nio.ByteBuffer presentationHeader = java.nio.ByteBuffer.wrap(
        presentationPacket);
    presentationHeader.putInt(16, -1);
    presentationHeader.putInt(20, 1);
    long[] submitted = NativeBridge.nSubmitIrisMetal4GraphFrame(
        presentationPacket);
    if (submitted == null || submitted.length != 7 || submitted[0] != 1
        || submitted[1] != 5 || submitted[5] <= 0 || submitted[6] != 0) {
      throw new IllegalStateException(
          "Iris Metal async graph presentation submission failed: "
              + java.util.Arrays.toString(submitted));
    }
    long presentationToken = submitted[5];
    long presentationDeadline = System.nanoTime() + 5_000_000_000L;
    long[] presentationStatus;
    do {
      presentationStatus =
          NativeBridge.nGetIrisMetal4GraphPresentationStatus(
              presentationToken);
      if (presentationStatus != null && presentationStatus.length == 5
          && presentationStatus[0] == 1) {
        break;
      }
      Thread.sleep(1);
    } while (System.nanoTime() < presentationDeadline);
    if (presentationStatus == null || presentationStatus.length != 5
        || presentationStatus[0] != 1
        || presentationStatus[1] != presentationToken
        || presentationStatus[2] != 16 || presentationStatus[3] != 16
        || presentationStatus[4] != 0
        || !NativeBridge.nPromoteIrisMetal4GraphPresentation(
            presentationToken, 16, 16)
        || !NativeBridge.nDiscardIrisMetal4FinalCutoverSurface()) {
      throw new IllegalStateException(
          "Iris Metal async graph presentation did not retire: "
              + java.util.Arrays.toString(presentationStatus));
    }
    java.nio.ByteBuffer directPresentationPacket =
        java.nio.ByteBuffer.allocateDirect(presentationPacket.length);
    directPresentationPacket.put(presentationPacket).flip();
    long[] directSubmitted =
        NativeBridge.nSubmitIrisMetal4GraphFrameDirect(
            directPresentationPacket, presentationPacket.length);
    if (directSubmitted == null || directSubmitted.length != 7
        || directSubmitted[0] != 1 || directSubmitted[1] != 5
        || directSubmitted[5] <= 0 || directSubmitted[6] != 0) {
      throw new IllegalStateException(
          "Iris direct Metal graph presentation submission failed: "
              + java.util.Arrays.toString(directSubmitted));
    }
    long directPresentationToken = directSubmitted[5];
    presentationDeadline = System.nanoTime() + 5_000_000_000L;
    do {
      presentationStatus =
          NativeBridge.nGetIrisMetal4GraphPresentationStatus(
              directPresentationToken);
      if (presentationStatus != null && presentationStatus.length == 5
          && presentationStatus[0] == 1) {
        break;
      }
      Thread.sleep(1);
    } while (System.nanoTime() < presentationDeadline);
    if (presentationStatus == null || presentationStatus.length != 5
        || presentationStatus[0] != 1
        || presentationStatus[1] != directPresentationToken
        || presentationStatus[4] != 0
        || !NativeBridge.nDiscardIrisMetal4GraphPresentation(
            directPresentationToken)) {
      throw new IllegalStateException(
          "Iris direct Metal graph presentation did not retire: "
              + java.util.Arrays.toString(presentationStatus));
    }
    byte[] malformedFrame = framePacket.clone();
    malformedFrame[0] = 0;
    long[] rejectedFrame = NativeBridge.nRunIrisMetal4GraphFrame(
        malformedFrame, 0);
    if (rejectedFrame == null || rejectedFrame.length != 7
        || rejectedFrame[0] != -1 || rejectedFrame[6] != 0) {
      throw new IllegalStateException(
          "malformed Iris Metal graph frame was not rejected");
    }
    NativeBridge.nResetIrisMetal4GraphResources();
    if (NativeBridge.nGetIrisMetal4GraphTextureCount() != 0
        || NativeBridge.nGetIrisMetal4GraphTextureBytes() != 0) {
      throw new IllegalStateException(
          "Iris Metal graph resources survived lifecycle reset");
    }
    return "private-resident,reuse,resize,generation-safe,depth,"
        + "frame-batched-clear-barrier-copy-mipmap,async-presentation,reset";
  }

  private static byte[] minimalIrisMetal4GraphFramePacket(
      long sourceToken, long destinationToken) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(0x4d474639);
    out.writeInt(4);
    out.writeLong(9);
    out.writeInt(1);
    out.writeInt(-1);
    out.writeInt(2);
    out.writeInt(0);
    out.writeLong(sourceToken);
    out.writeInt(1);
    out.writeLong(destinationToken);
    out.writeInt(0); // input buffers
    out.writeInt(0); // input textures
    out.writeInt(5);

    out.writeInt(1);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(4);
    out.writeLong(Integer.toUnsignedLong(Float.floatToRawIntBits(0.25F)));
    out.writeLong(Integer.toUnsignedLong(Float.floatToRawIntBits(0.5F)));
    out.writeLong(Integer.toUnsignedLong(Float.floatToRawIntBits(1.0F)));
    out.writeLong(Integer.toUnsignedLong(Float.floatToRawIntBits(1.0F)));
    out.writeBoolean(false);

    out.writeInt(2);
    out.writeInt(0x28);

    out.writeInt(3);
    out.writeInt(0);
    out.writeInt(1);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(16);
    out.writeInt(16);

    out.writeInt(2);
    out.writeInt(0x28);

    out.writeInt(4);
    out.writeInt(1);
    out.flush();
    return bytes.toByteArray();
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

  private static String runIrisMetal4PipelineCacheSmoke(Path directory)
      throws Exception {
    Files.createDirectories(directory);
    Path archive = directory.resolve("pipelines.mtl4archive");
    String shaderKey = sha256("release-payload-iris-metal4-shader");
    String pipelineKey = sha256("release-payload-iris-metal4-pipeline");
    String deltaShaderKey = sha256(
        "release-payload-iris-metal4-delta-shader");
    String deltaPipelineKey = sha256(
        "release-payload-iris-metal4-delta-pipeline");
    byte[] vertexMsl = ("""
        #include <metal_stdlib>
        using namespace metal;
        struct VertexOut { float4 position [[position]]; };
        vertex VertexOut main0(uint vertexId [[vertex_id]]) {
          VertexOut out;
          float x = vertexId == 1 ? 3.0 : -1.0;
          float y = vertexId == 2 ? 3.0 : -1.0;
          // Match the fixed Iris SPIRV-Cross profile's vertex-Y reflection.
          out.position = float4(x, -y, 0.0, 1.0);
          return out;
        }
        """).getBytes(StandardCharsets.UTF_8);
    byte[] fragmentMsl = ("""
        #include <metal_stdlib>
        using namespace metal;
        fragment float4 main0() { return float4(0.25, 0.5, 1.0, 1.0); }
        """).getBytes(StandardCharsets.UTF_8);
    byte[] deltaFragmentMsl = ("""
        #include <metal_stdlib>
        using namespace metal;
        fragment float4 main0() { return float4(1.0, 0.5, 0.25, 1.0); }
        """).getBytes(StandardCharsets.UTF_8);
    byte[] descriptor = minimalIrisMetal4RenderDescriptor();

    int coldConfigure = NativeBridge.nConfigureIrisMetal4PipelineCache(
        archive.toString());
    int coldCompile = NativeBridge.nCompileIrisMetal4Pipeline(
        pipelineKey, shaderKey, descriptor, vertexMsl, fragmentMsl, null);
    if (coldConfigure != 1 || coldCompile != 1
        || !NativeBridge.nFlushIrisMetal4PipelineCache()
        || !Files.isRegularFile(archive) || Files.size(archive) <= 0) {
      throw new IllegalStateException(
          "Iris MTL4 cold pipeline/archive smoke failed: configure="
              + coldConfigure + " compile=" + coldCompile);
    }
    requireShadowParity(pipelineKey, "cold");
    requirePacketReplay(pipelineKey, "cold");
    requireGraphFrameDraw(pipelineKey, "cold");
    requireIrisMetal4PipelineCounters(1, 1, 0, 0, 0, 1, 49);

    NativeBridge.nResetIrisMetal4Pipelines();
    int warmConfigure = NativeBridge.nConfigureIrisMetal4PipelineCache(
        archive.toString());
    int warmCompile = NativeBridge.nCompileIrisMetal4Pipeline(
        pipelineKey, shaderKey, descriptor, vertexMsl, fragmentMsl, null);
    if (warmConfigure != 2 || warmCompile != 2) {
      throw new IllegalStateException(
          "Iris MTL4 warm pipeline lookup failed: configure="
              + warmConfigure + " compile=" + warmCompile);
    }
    requireShadowParity(pipelineKey, "warm");
    requirePacketReplay(pipelineKey, "warm");
    requireGraphFrameDraw(pipelineKey, "warm");
    requireIrisMetal4PipelineCounters(1, 0, 1, 0, 0, 1, 49);

    NativeBridge.nResetIrisMetal4Pipelines();
    int deltaConfigure = NativeBridge.nConfigureIrisMetal4PipelineCache(
        archive.toString());
    int retainedHit = NativeBridge.nCompileIrisMetal4Pipeline(
        pipelineKey, shaderKey, descriptor, vertexMsl, fragmentMsl, null);
    int deltaCompile = NativeBridge.nCompileIrisMetal4Pipeline(
        deltaPipelineKey, deltaShaderKey, descriptor, vertexMsl,
        deltaFragmentMsl, null);
    Path firstSegment = archive.resolveSibling(
        archive.getFileName() + ".segment-01");
    boolean deltaFlushed = NativeBridge.nFlushIrisMetal4PipelineCache();
    boolean segmentPresent = Files.isRegularFile(firstSegment);
    long segmentBytes = segmentPresent ? Files.size(firstSegment) : 0;
    if (deltaConfigure != 2 || retainedHit != 2 || deltaCompile != 1
        || !deltaFlushed || !segmentPresent || segmentBytes <= 0) {
      throw new IllegalStateException(
          "Iris MTL4 append-only archive segment failed: configure="
              + deltaConfigure + " retained=" + retainedHit
              + " delta=" + deltaCompile + " flushed=" + deltaFlushed
              + " segment=" + segmentPresent + "/" + segmentBytes
              + " files=" + Files.list(directory)
                  .map(path -> path.getFileName().toString()).sorted()
                  .toList());
    }
    requireIrisMetal4PipelineCounters(2, 1, 1, 0, 0, 2, 0);

    NativeBridge.nResetIrisMetal4Pipelines();
    int mergedConfigure = NativeBridge.nConfigureIrisMetal4PipelineCache(
        archive.toString());
    int mergedBaseHit = NativeBridge.nCompileIrisMetal4Pipeline(
        pipelineKey, shaderKey, descriptor, vertexMsl, fragmentMsl, null);
    int mergedDeltaHit = NativeBridge.nCompileIrisMetal4Pipeline(
        deltaPipelineKey, deltaShaderKey, descriptor, vertexMsl,
        deltaFragmentMsl, null);
    if (mergedConfigure != 2 || mergedBaseHit != 2
        || mergedDeltaHit != 2) {
      throw new IllegalStateException(
          "Iris MTL4 segmented archive lookup failed: configure="
              + mergedConfigure + " base=" + mergedBaseHit
              + " delta=" + mergedDeltaHit);
    }
    requireIrisMetal4PipelineCounters(2, 0, 2, 0, 0, 2, 0);

    NativeBridge.nResetIrisMetal4Pipelines();
    byte[] staleBytes = "intentionally-stale-mtl4-archive"
        .getBytes(StandardCharsets.US_ASCII);
    Files.write(archive, staleBytes);
    int staleConfigure = NativeBridge.nConfigureIrisMetal4PipelineCache(
        archive.toString());
    int rebuiltCompile = NativeBridge.nCompileIrisMetal4Pipeline(
        pipelineKey, shaderKey, descriptor, vertexMsl, fragmentMsl, null);
    if (staleConfigure != 3 || rebuiltCompile != 1
        || !Files.isRegularFile(
            archive.resolveSibling(archive.getFileName() + ".rejected"))
        || !NativeBridge.nFlushIrisMetal4PipelineCache()) {
      throw new IllegalStateException(
          "Iris MTL4 stale archive recovery failed: configure="
              + staleConfigure + " compile=" + rebuiltCompile);
    }
    requireShadowParity(pipelineKey, "stale-rebuilt");
    requirePacketReplay(pipelineKey, "stale-rebuilt");
    requireGraphFrameDraw(pipelineKey, "stale-rebuilt");
    requireIrisMetal4PipelineCounters(1, 1, 0, 0, 1, 1, 49);
    NativeBridge.nResetIrisMetal4Pipelines();
    return "cold-compiled,warm-hit,delta-segment-merged,stale-rebuilt,"
        + "cold/warm/rebuilt-shadow-parity-mrx7-and-mgf9-draw-pass";
  }

  private static void requireShadowParity(String pipelineKey,
      String profile) {
    int result = NativeBridge.nRunIrisMetal4ShadowParitySmoke(
        pipelineKey, 32, 32, 0x4080FFFF, 1);
    if (result != 1) {
      throw new IllegalStateException(
          "Iris MTL4 " + profile + " offscreen parity failed: " + result);
    }
  }

  private static void requirePacketReplay(String pipelineKey, String profile)
      throws Exception {
    byte[] packet = minimalIrisShadowReplayPacket();
    long[] expected = null;
    byte[] expectedPixels = null;
    for (int index = 0; index < IRIS_METAL4_SUBMISSION_BURST; index++) {
      long[] result = NativeBridge.nRunIrisMetal4ShadowReplay(pipelineKey,
          packet);
      byte[] pixels = NativeBridge.nTakeIrisMetal4ShadowReplayRgba8();
      if (result == null || result.length != 5 || result[0] != 1
          || result[1] == 0 || result[2] != 32 || result[3] != 32
          || result[4] != 0 || pixels == null
          || pixels.length != 32 * 32 * 4
          || (expected != null
              && !java.util.Arrays.equals(expected, result))
          || (expectedPixels != null
              && !java.util.Arrays.equals(expectedPixels, pixels))) {
        throw new IllegalStateException("Iris MTL4 " + profile
            + " MRX7 replay burst failed or was nondeterministic at "
            + index + ": " + java.util.Arrays.toString(result));
      }
      if (expected == null) {
        expected = result;
        expectedPixels = pixels;
      }
    }
    byte[] malformed = packet.clone();
    malformed[0] = 0;
    long[] rejected = NativeBridge.nRunIrisMetal4ShadowReplay(pipelineKey,
        malformed);
    byte[] rejectedPixels = NativeBridge.nTakeIrisMetal4ShadowReplayRgba8();
    if (rejected == null || rejected.length != 5 || rejected[0] != -1
        || rejected[4] != 0 || rejectedPixels == null
        || rejectedPixels.length != 0) {
      throw new IllegalStateException("Iris MTL4 " + profile
          + " malformed MRX7 packet was not rejected");
    }
  }

  private static void requireGraphFrameDraw(String pipelineKey,
      String profile) throws Exception {
    int shaderRead = 1;
    int renderTarget = 1 << 2;
    long target = NativeBridge.nEnsureIrisMetal4GraphTexture(
        10, 61, 1, "rgba8-unorm", 1, 32, 32, 1, 1,
        shaderRead | renderTarget);
    if (target <= 0) {
      throw new IllegalStateException("Iris MTL4 " + profile
          + " MGF9 draw target allocation failed");
    }
    byte[] packet = minimalIrisMetal4GraphDrawPacket(target, pipelineKey);
    long[] expected = null;
    for (int index = 0; index < IRIS_METAL4_SUBMISSION_BURST; index++) {
      long[] result = NativeBridge.nRunIrisMetal4GraphFrame(packet, 0);
      long expectedColorHash = -1652146098106913917L;
      if (result == null || result.length != 7 || result[0] != 1
          || result[1] != 3 || result[2] != 1 || result[3] != 0
          || result[4] != 1 || result[5] != expectedColorHash
          || result[6] != 0
          || (expected != null
              && !java.util.Arrays.equals(expected, result))) {
        NativeBridge.nResetIrisMetal4GraphResources();
        throw new IllegalStateException("Iris MTL4 " + profile
            + " MGF9 submission burst failed or was nondeterministic at "
            + index + ": " + java.util.Arrays.toString(result));
      }
      if (expected == null) {
        expected = result;
      }
    }
    NativeBridge.nResetIrisMetal4GraphResources();
  }

  private static byte[] minimalIrisMetal4GraphDrawPacket(long targetToken,
      String pipelineKey) throws Exception {
    byte[] replay = minimalIrisShadowReplayPacket(true, true);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(0x4d474639);
    out.writeInt(4);
    out.writeLong(10);
    out.writeInt(0);
    out.writeInt(-1);
    out.writeInt(1);
    out.writeInt(0);
    out.writeLong(targetToken);
    out.writeInt(1);
    out.writeInt(1);
    out.writeInt(16);
    out.write(new byte[16]);
    out.writeInt(1); // input textures
    out.writeInt(77);
    putAscii(out, "rgba8-unorm");
    out.writeInt(1);
    out.writeInt(1);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(4);
    out.writeInt(1); // immutable inline frame texture
    out.writeInt(4);
    out.writeInt(0x4080ffff);
    out.writeInt(3);

    out.writeInt(1);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(4);
    out.writeLong(0);
    out.writeLong(0);
    out.writeLong(0);
    out.writeLong(0);
    out.writeBoolean(false);

    out.writeInt(2);
    out.writeInt(0x28);

    out.writeInt(5);
    putAscii(out, pipelineKey);
    out.writeInt(replay.length);
    out.write(replay);
    out.writeInt(1);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(-1);
    out.writeInt(-1);
    out.writeInt(0);
    out.flush();
    return bytes.toByteArray();
  }

  private static byte[] minimalIrisShadowReplayPacket() throws Exception {
    return minimalIrisShadowReplayPacket(false, false);
  }

  private static byte[] minimalIrisShadowReplayPacket(
      boolean graphExternalBuffer) throws Exception {
    return minimalIrisShadowReplayPacket(graphExternalBuffer, false);
  }

  private static byte[] minimalIrisShadowReplayPacket(
      boolean graphExternalBuffer, boolean graphExternalTexture)
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(0x4d525837);
    out.writeInt(9);
    out.writeInt(32);
    out.writeInt(32);
    out.writeInt(0);  // viewport x
    out.writeInt(0);  // viewport y
    out.writeInt(32); // viewport width
    out.writeInt(32); // viewport height
    out.writeBoolean(false);
    out.writeInt(0);  // disabled scissor x
    out.writeInt(0);  // disabled scissor y
    out.writeInt(0);  // disabled scissor width
    out.writeInt(0);  // disabled scissor height
    out.writeInt(1);  // DrawArrays
    out.writeInt(4);  // triangles
    out.writeInt(0);  // first vertex
    out.writeInt(3);  // vertex count
    out.writeInt(1);  // instance count
    out.writeInt(0);  // base instance
    out.writeInt(graphExternalBuffer ? 1 : 0);
    if (graphExternalBuffer) {
      out.writeInt(3);  // MGF9 frame input reference
      out.writeInt(16);
      out.writeLong(1); // frame input index zero plus one
    }
    out.writeInt(0);  // vertex buffer bindings
    out.writeInt(-1); // index buffer
    out.writeInt(graphExternalTexture ? 1 : 0);
    if (graphExternalTexture) {
      out.writeInt(77);
      putAscii(out, "rgba8-unorm");
      out.writeInt(1);
      out.writeInt(1);
      out.writeInt(0);
      out.writeInt(0);
      out.writeInt(4);
      out.writeInt(4);  // MGF9 frame texture reference
      out.writeLong(1); // frame texture index zero plus one
    }
    out.writeInt(0);  // argument stages
    out.flush();
    return bytes.toByteArray();
  }

  private static void requireIrisMetal4PipelineCounters(long attempts,
      long compiled, long hits, long failures, long stale, long live,
      long drawAttempts) {
    if (NativeBridge.nGetIrisMetal4PipelineAttemptCount() != attempts
        || NativeBridge.nGetIrisMetal4PipelineCompileCount() != compiled
        || NativeBridge.nGetIrisMetal4PipelineCacheHitCount() != hits
        || NativeBridge.nGetIrisMetal4PipelineFailureCount() != failures
        || NativeBridge.nGetIrisMetal4PipelineStaleRecoveryCount() != stale
        || NativeBridge.nGetIrisMetal4LivePipelineCount() != live
        || NativeBridge.nGetIrisMetal4PipelineDrawAttemptCount()
        != drawAttempts) {
      throw new IllegalStateException(
          "Iris MTL4 pipeline telemetry mismatch: attempts="
              + NativeBridge.nGetIrisMetal4PipelineAttemptCount()
              + " compiled="
              + NativeBridge.nGetIrisMetal4PipelineCompileCount()
              + " hits=" + NativeBridge.nGetIrisMetal4PipelineCacheHitCount()
              + " failures="
              + NativeBridge.nGetIrisMetal4PipelineFailureCount()
              + " stale="
              + NativeBridge.nGetIrisMetal4PipelineStaleRecoveryCount()
              + " live=" + NativeBridge.nGetIrisMetal4LivePipelineCount()
              + " draw="
              + NativeBridge.nGetIrisMetal4PipelineDrawAttemptCount());
    }
  }

  private static byte[] minimalIrisMetal4RenderDescriptor()
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(0x4d525036);
    out.writeInt(1);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(1);
    out.writeInt(0);
    putAscii(out, "rgba8-unorm");
    out.writeInt(0xf);
    out.writeBoolean(false);
    for (int equation = 0; equation < 2; equation++) {
      out.writeInt(0);
      out.writeInt(1);
      out.writeInt(0);
    }
    out.writeBoolean(false);
    out.writeBoolean(false);
    out.writeInt(1);
    out.writeLong(-1L);
    out.writeBoolean(false);
    out.writeInt(Float.floatToRawIntBits(1.0F));
    out.writeBoolean(false);
    out.writeBoolean(false);
    out.writeBoolean(false);
    out.writeBoolean(false);
    out.writeInt(7);
    out.writeBoolean(false);
    out.writeBoolean(false);
    for (int face = 0; face < 2; face++) {
      out.writeInt(7);
      out.writeInt(0);
      out.writeInt(0);
      out.writeInt(0);
      out.writeInt(0xff);
      out.writeInt(0xff);
      out.writeInt(0);
    }
    out.writeBoolean(true);
    out.writeInt(2);
    out.writeInt(1);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(4);
    out.writeInt(0);
    out.writeInt(0);
    out.writeInt(0);
    out.flush();
    return bytes.toByteArray();
  }

  private static void putAscii(DataOutputStream out, String value)
      throws Exception {
    byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
    out.writeInt(encoded.length);
    out.write(encoded);
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
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

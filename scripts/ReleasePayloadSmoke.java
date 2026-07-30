import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

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

    try {
      NativeBridge.nConfigureRuntime(true, 512, 60, true);
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
      NativeBridge.nDestroyTexture2D(texture);

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
      System.out.printf(
          "release-payload-smoke: loaded=%s available=true metal4Supported=%s "
              + "metal4Active=%s metal4Draw=false backend=%s shaderBytes=%d%n",
          loadedLibrary,
          metal4Supported,
          metal4Active,
          backendMode,
          magic.length);
      NativeBridge.nFlushFrames();
    } finally {
      NativeBridge.nDestroy(handle);
    }
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
}

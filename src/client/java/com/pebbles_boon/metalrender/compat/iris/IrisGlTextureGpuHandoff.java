package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Bounded GL-to-IOSurface-to-Metal capture for direct FINAL replay inputs. */
final class IrisGlTextureGpuHandoff {
  private static final int GL_TEXTURE_2D = 0x0DE1;
  // Keep sixteen entries available for large immutable inputs regardless of
  // discovery order. Large inputs may use any remaining entry; they are still
  // bounded by the shared 72-texture / 256-MiB native limits. Small textures
  // beyond this quota are cheap inline fallbacks, while one displaced atlas
  // would otherwise add many MiB to every frame packet.
  private static final int MAX_RESIDENT_TEXTURES = 72;
  private static final int RESERVED_LARGE_RESIDENT_TEXTURES = 16;
  private static final int MAX_SMALL_RESIDENT_TEXTURES =
      MAX_RESIDENT_TEXTURES - RESERVED_LARGE_RESIDENT_TEXTURES;
  private static final int LARGE_RESIDENT_MINIMUM_BYTES = 1024 * 1024;
  private static final long MAX_RESIDENT_BYTES = 256L * 1024L * 1024L;
  private static final Map<ResidentKey,
      IrisGlTextureMirror.TextureSnapshot> RESIDENT =
          new LinkedHashMap<>();
  private static final ThreadLocal<String> LAST_CAPTURE_FAILURE =
      ThreadLocal.withInitial(() -> "");
  private static final ThreadLocal<String> LAST_RESIDENT_FAILURE =
      ThreadLocal.withInitial(() -> "");
  private static long residentBytes;
  private static int largeResidentTextures;

  private IrisGlTextureGpuHandoff() {
  }

  static synchronized Optional<IrisGlTextureMirror.TextureSnapshot>
      promoteResident(IrisGlTextureMirror.TextureSnapshot snapshot) {
    if (snapshot == null || snapshot.shared() || snapshot.graphReference()
        || snapshot.layer() != 0
        || snapshot.mipLevel() != 0
        || !snapshot.format().equals("rgba8-unorm")
        || snapshot.bytesPerPixel() != 4) {
      LAST_RESIDENT_FAILURE.set(snapshot == null ? "snapshot-missing"
          : "snapshot-ineligible-shared=" + snapshot.shared()
              + "-graph=" + snapshot.graphReference()
              + "-layer=" + snapshot.layer()
              + "-mip=" + snapshot.mipLevel()
              + "-format=" + snapshot.format()
              + "-bpp=" + snapshot.bytesPerPixel());
      return Optional.empty();
    }
    ResidentKey key = new ResidentKey(snapshot.texture(),
        snapshot.generation(), snapshot.width(), snapshot.height());
    IrisGlTextureMirror.TextureSnapshot cached = RESIDENT.get(key);
    if (cached != null) {
      LAST_RESIDENT_FAILURE.remove();
      return Optional.of(cached);
    }
    int candidateBytes = snapshot.byteLength();
    if (!residentCapacityAvailable(RESIDENT.size(), largeResidentTextures,
        residentBytes, candidateBytes)) {
      LAST_RESIDENT_FAILURE.set("resident-capacity-textures="
          + RESIDENT.size() + "-large=" + largeResidentTextures
          + "-bytes=" + residentBytes + "-candidate=" + candidateBytes);
      return Optional.empty();
    }
    try {
      if (!NativeBridge.isLibLoaded() || !NativeBridge.nIsMetal4Active()) {
        LAST_RESIDENT_FAILURE.set("metal4-unavailable");
        return Optional.empty();
      }
      long handle = NativeBridge.nUploadIrisMetal4InputTexture(
          snapshot.texture(), snapshot.generation(), snapshot.width(),
          snapshot.height(), snapshot.bytes());
      if (handle <= 0) {
        LAST_RESIDENT_FAILURE.set("native-upload-failed");
        return Optional.empty();
      }
      IrisGlTextureMirror.TextureSnapshot resident =
          IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
              snapshot.texture(), snapshot.generation(), snapshot.format(),
              snapshot.width(), snapshot.height(), snapshot.bytesPerPixel(),
              handle);
      RESIDENT.put(key, resident);
      residentBytes = Math.addExact(residentBytes, candidateBytes);
      if (largeResident(candidateBytes)) {
        largeResidentTextures++;
      }
      LAST_RESIDENT_FAILURE.remove();
      return Optional.of(resident);
    } catch (RuntimeException | LinkageError unavailable) {
      LAST_RESIDENT_FAILURE.set("resident-exception-"
          + unavailable.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  static synchronized Optional<IrisGlTextureMirror.TextureSnapshot>
      lookupResident(int texture, long generation, int mipLevel, int layer,
      IrisGlTextureMirror.TextureMetadata metadata) {
    if (texture <= 0 || generation <= 0 || mipLevel != 0 || layer != 0) {
      return Optional.empty();
    }
    if (metadata != null) {
      IrisGlTextureMirror.TextureSnapshot resident = RESIDENT.get(
          new ResidentKey(texture, generation, metadata.width(),
              metadata.height()));
      return Optional.ofNullable(resident);
    }
    return RESIDENT.entrySet().stream()
        .filter(entry -> entry.getKey().texture() == texture
            && entry.getKey().generation() == generation)
        .map(Map.Entry::getValue)
        .findFirst();
  }

  static synchronized void reset() {
    RESIDENT.clear();
    residentBytes = 0;
    largeResidentTextures = 0;
  }

  static boolean residentCapacityAvailable(int residentTextures,
      int largeTextures, long retainedBytes, int candidateBytes) {
    if (residentTextures < 0 || largeTextures < 0
        || largeTextures > residentTextures || retainedBytes < 0
        || candidateBytes <= 0 || residentTextures >= MAX_RESIDENT_TEXTURES
        || candidateBytes > MAX_RESIDENT_BYTES
        || retainedBytes > MAX_RESIDENT_BYTES - candidateBytes) {
      return false;
    }
    int smallTextures = residentTextures - largeTextures;
    return largeResident(candidateBytes)
        || smallTextures < MAX_SMALL_RESIDENT_TEXTURES;
  }

  private static boolean largeResident(int bytes) {
    return bytes >= LARGE_RESIDENT_MINIMUM_BYTES;
  }

  static Optional<IrisGlTextureMirror.TextureSnapshot> capture(
      int texture, long generation, int target, int mipLevel, int layer,
      IrisGlTextureMirror mirror, boolean allowPackedFloatHandoff) {
    if (texture <= 0 || generation <= 0 || target != GL_TEXTURE_2D
        || mipLevel != 0 || layer != 0) {
      return captureFailure("unsupported-target-or-subresource");
    }
    IrisGlTextureMirror.TextureMetadata metadata = mirror.metadata(texture,
        mipLevel, layer).orElse(null);
    if (metadata == null || metadata.generation() != generation
        || metadata.depthOrLayers() != 1
        || metadata.width() > IrisMetalShadowReplayPacketEncoder
            .MAX_TARGET_EXTENT
        || metadata.height() > IrisMetalShadowReplayPacketEncoder
            .MAX_TARGET_EXTENT) {
      return captureFailure("metadata-or-generation-invalid");
    }
    // The RG11B10F -> RGBA16F CGL conversion is sufficiently accurate for
    // the already parity-qualified visible FINAL bridge, but not byte-exact
    // enough to serve as the strict full-graph diagnostic input. Callers must
    // opt into that packed-float handoff explicitly.
    HandoffFormat format = handoffFormat(metadata.format(),
        allowPackedFloatHandoff);
    if (format == null) {
      return captureFailure("format-unsupported");
    }
    try {
      if (!NativeBridge.isLibLoaded() || !NativeBridge.nIsMetal4Active()) {
        return captureFailure("metal4-unavailable");
      }
      long handle = NativeBridge.nCaptureIrisMetal4InputSurface(texture,
          metadata.width(), metadata.height(), format.nativeKind());
      if (handle <= 0) {
        return captureFailure(nativeCaptureFailure(handle));
      }
      LAST_CAPTURE_FAILURE.remove();
      return Optional.of(IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
          texture, generation, format.metalFormat(), metadata.width(),
          metadata.height(), format.bytesPerPixel(), handle));
    } catch (RuntimeException | LinkageError unavailable) {
      return captureFailure("native-exception-"
          + unavailable.getClass().getSimpleName());
    }
  }

  static String lastCaptureFailure() {
    return LAST_CAPTURE_FAILURE.get();
  }

  static String lastResidentFailure() {
    return LAST_RESIDENT_FAILURE.get();
  }

  static long[] uniqueSharedHandles(
      Collection<IrisGlTextureMirror.TextureSnapshot> snapshots) {
    if (snapshots == null || snapshots.isEmpty()) {
      return new long[0];
    }
    return snapshots.stream().filter(java.util.Objects::nonNull)
        .filter(IrisGlTextureMirror.TextureSnapshot::shared)
        .mapToLong(IrisGlTextureMirror.TextureSnapshot::sharedHandle)
        .filter(handle -> handle > 0).distinct().sorted().toArray();
  }

  /** Releases dynamic capture leases that never reached a Metal packet. */
  static void abandonCapturedSurfaces(
      Collection<IrisGlTextureMirror.TextureSnapshot> snapshots) {
    if (!NativeBridge.isLibLoaded()) {
      return;
    }
    for (long handle : uniqueSharedHandles(snapshots)) {
      try {
        // Immutable resident handles intentionally return false here. The
        // native side releases only an unsubmitted dynamic surface lease.
        NativeBridge.nReleaseIrisMetal4InputSurface(handle);
      } catch (RuntimeException | LinkageError unavailable) {
        return;
      }
    }
  }

  private static <T> Optional<T> captureFailure(String reason) {
    LAST_CAPTURE_FAILURE.set(reason == null || reason.isBlank()
        ? "unknown" : reason);
    return Optional.empty();
  }

  static String nativeCaptureFailure(long code) {
    return switch ((int) code) {
      case -1 -> "native-state-or-argument-invalid";
      case -2 -> "native-row-layout-invalid";
      case -3 -> "native-surface-ring-exhausted";
      case -4 -> "native-iosurface-allocation-failed";
      case -5 -> "native-metal-texture-allocation-failed";
      case -6 -> "native-cgl-iosurface-bind-failed";
      case -7 -> "native-surface-token-collision";
      case -8 -> "native-prior-copy-fence-missing";
      case -9 -> "native-prior-copy-fence-timeout";
      case -10 -> "native-framebuffer-copy-or-fence-failed";
      case -11 -> "native-copy-fence-missing";
      case -12 -> "native-copy-fence-timeout";
      default -> "native-capture-failed-" + code;
    };
  }

  static boolean retryableCaptureFailure(String reason) {
    return "native-surface-ring-exhausted".equals(reason);
  }

  static boolean supportsDirectGpuHandoff(String format) {
    return handoffFormat(format, false) != null;
  }

  static boolean supportsDirectGpuHandoff(String format,
      boolean allowPackedFloatHandoff) {
    return handoffFormat(format, allowPackedFloatHandoff) != null;
  }

  private static HandoffFormat handoffFormat(String format,
      boolean allowPackedFloatHandoff) {
    if ("rgba8-unorm".equals(format)) {
      return new HandoffFormat(1, "rgba8-unorm", 4);
    }
    if (allowPackedFloatHandoff && "rg11b10-float".equals(format)) {
      return new HandoffFormat(2, "rgba16-float", 8);
    }
    return null;
  }

  private record HandoffFormat(int nativeKind, String metalFormat,
                               int bytesPerPixel) {
  }

  private record ResidentKey(int texture, long generation, int width,
                             int height) {
  }
}

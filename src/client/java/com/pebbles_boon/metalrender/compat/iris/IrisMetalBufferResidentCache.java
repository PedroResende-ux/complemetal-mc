package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded cache of immutable draw-buffer images retained by Metal. */
final class IrisMetalBufferResidentCache {
  private static final int MAX_RESIDENT_BUFFERS = 256;
  private static final long MAX_RESIDENT_BYTES = 64L * 1024L * 1024L;
  private static final int MAX_SOURCE_KEYS = 4_096;
  private static final Map<String, Resident> RESIDENT =
      new LinkedHashMap<>();
  private static final Map<SourceKey, Resident> SOURCES =
      new LinkedHashMap<>(64, 0.75F, true);
  private static long residentBytes;
  private static long sourceHits;
  private static long digestHits;
  private static long uploads;

  private IrisMetalBufferResidentCache() {
  }

  static synchronized Optional<IrisShadowReplayBufferSnapshot.BufferImage>
      promote(IrisShadowReplayBufferSnapshot.BufferImage image) {
    return promote(image, IrisMetalBufferResidentCache::upload);
  }

  static synchronized Optional<IrisShadowReplayBufferSnapshot.BufferImage>
      promote(IrisShadowReplayBufferSnapshot.BufferImage image,
      BufferUploader uploader) {
    if (image == null || image.shared() || image.byteLength() <= 0) {
      return Optional.empty();
    }
    Objects.requireNonNull(uploader, "uploader");
    SourceKey source = SourceKey.from(image);
    Resident sourceResident = source == null ? null : SOURCES.get(source);
    if (sourceResident != null) {
      if (sourceResident.byteLength() != image.byteLength()) {
        SOURCES.remove(source);
        return Optional.empty();
      }
      sourceHits++;
      return Optional.of(image.asShared(sourceResident.handle()));
    }
    byte[] bytes = image.ownedBytes();
    if (bytes.length != image.byteLength()) {
      return Optional.empty();
    }
    String digest = sha256(bytes);
    Resident cached = RESIDENT.get(digest);
    if (cached != null) {
      if (cached.byteLength() != bytes.length) {
        return Optional.empty();
      }
      retainSource(source, cached);
      digestHits++;
      return Optional.of(image.asShared(cached.handle()));
    }
    if (RESIDENT.size() >= MAX_RESIDENT_BUFFERS
        || residentBytes > MAX_RESIDENT_BYTES - bytes.length) {
      return Optional.empty();
    }
    try {
      long handle = uploader.upload(digest, bytes);
      if (handle <= 0) {
        return Optional.empty();
      }
      Resident uploaded = new Resident(handle, bytes.length);
      RESIDENT.put(digest, uploaded);
      retainSource(source, uploaded);
      residentBytes += bytes.length;
      uploads++;
      return Optional.of(image.asShared(handle));
    } catch (RuntimeException | LinkageError unavailable) {
      return Optional.empty();
    }
  }

  static synchronized void reset() {
    RESIDENT.clear();
    SOURCES.clear();
    residentBytes = 0;
    sourceHits = 0;
    digestHits = 0;
    uploads = 0;
  }

  /**
   * Reuses an immutable, generation-qualified Metal buffer without copying
   * the mirror bytes or hashing them again. A changed mirror generation is a
   * distinct key and therefore cannot alias older contents.
   */
  static synchronized Optional<IrisShadowReplayBufferSnapshot.BufferImage>
      lookup(int imageId, int glBuffer, long generation,
      long sourceOffsetBytes, int byteLength) {
    if (imageId < 0 || glBuffer <= 0 || generation <= 0
        || sourceOffsetBytes < 0 || byteLength <= 0) {
      return Optional.empty();
    }
    Resident resident = SOURCES.get(new SourceKey(glBuffer, generation,
        sourceOffsetBytes, byteLength));
    if (resident == null || resident.byteLength() != byteLength) {
      return Optional.empty();
    }
    sourceHits++;
    return Optional.of(new IrisShadowReplayBufferSnapshot.BufferImage(
        imageId, glBuffer, generation, sourceOffsetBytes, new byte[0],
        resident.handle(), byteLength));
  }

  static synchronized Status status() {
    return new Status(RESIDENT.size(), residentBytes, SOURCES.size(),
        sourceHits, digestHits, uploads);
  }

  private static long upload(String digest, byte[] bytes) {
    if (!NativeBridge.isLibLoaded() || !NativeBridge.nIsMetal4Active()) {
      return 0;
    }
    return NativeBridge.nUploadIrisMetal4InputBuffer(digest, bytes);
  }

  private static void retainSource(SourceKey source, Resident resident) {
    if (source == null) {
      return;
    }
    while (SOURCES.size() >= MAX_SOURCE_KEYS && !SOURCES.isEmpty()) {
      SOURCES.remove(SOURCES.keySet().iterator().next());
    }
    SOURCES.put(source, resident);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private record Resident(long handle, int byteLength) {
    private Resident {
      if (handle <= 0 || byteLength <= 0) {
        throw new IllegalArgumentException("invalid resident Metal buffer");
      }
    }
  }

  private record SourceKey(int glBuffer, long generation,
                           long sourceOffsetBytes, int byteLength) {
    private static SourceKey from(
        IrisShadowReplayBufferSnapshot.BufferImage image) {
      return image.glBuffer() <= 0 || image.generation() <= 0
          ? null : new SourceKey(image.glBuffer(), image.generation(),
              image.sourceOffsetBytes(), image.byteLength());
    }
  }

  @FunctionalInterface
  interface BufferUploader {
    long upload(String digest, byte[] bytes);
  }

  record Status(int residents, long residentBytes, int sourceKeys,
                long sourceHits, long digestHits, long uploads) {
  }
}

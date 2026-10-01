package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Copy-on-write CPU mirror of bounded, fully initialized GL texture levels. */
public final class IrisGlTextureMirror {
  public static final int DEFAULT_MAX_TEXTURES = 4_096;
  public static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
  public static final int DEFAULT_MAX_IMAGE_BYTES = 128 * 1024 * 1024;
  private static final IrisGlTextureMirror GLOBAL = new IrisGlTextureMirror(
      DEFAULT_MAX_TEXTURES, DEFAULT_MAX_TOTAL_BYTES,
      DEFAULT_MAX_IMAGE_BYTES);

  private final int maxTextures;
  private final long maxTotalBytes;
  private final int maxImageBytes;
  private final LinkedHashMap<Integer, Entry> entries =
      new LinkedHashMap<>(16, 0.75F, true);
  private long retainedBytes;
  private long nextGeneration = 1;
  private long evictions;
  private long rejectedWrites;

  public IrisGlTextureMirror(int maxTextures, long maxTotalBytes,
      int maxImageBytes) {
    if (maxTextures <= 0 || maxTotalBytes <= 0 || maxImageBytes <= 0
        || maxImageBytes > maxTotalBytes) {
      throw new IllegalArgumentException("invalid texture mirror bounds");
    }
    this.maxTextures = maxTextures;
    this.maxTotalBytes = maxTotalBytes;
    this.maxImageBytes = maxImageBytes;
  }

  public static IrisGlTextureMirror global() {
    return GLOBAL;
  }

  public static boolean isEnabled() {
    return IrisMetalFeatureFlags.enabled(
        "metalrender.experimental.irisMetalShadowReplay");
  }

  public synchronized boolean define(int texture, String format,
      int width, int height, int depthOrLayers, int mipLevels,
      int bytesPerPixel) {
    if (texture <= 0 || format == null || format.isBlank()
        || format.length() > 128 || width <= 0 || height <= 0
        || depthOrLayers <= 0 || mipLevels <= 0 || mipLevels > 32
        || bytesPerPixel <= 0 || bytesPerPixel > 32) {
      rejectedWrites++;
      return false;
    }
    Entry previous = entries.remove(texture);
    if (previous != null) {
      retainedBytes -= previous.retainedBytes;
    }
    if (!makeRoom(0, true, texture)) {
      rejectedWrites++;
      return false;
    }
    entries.put(texture, new Entry(format, width, height, depthOrLayers,
        mipLevels, bytesPerPixel, nextGeneration++));
    return true;
  }

  public synchronized boolean write(int texture, int mipLevel, int layer,
      int x, int y, int width, int height, int sourceRowPixels,
      ByteBuffer source) {
    Objects.requireNonNull(source, "source");
    Entry entry = entries.get(texture);
    if (entry == null || mipLevel < 0 || mipLevel >= entry.mipLevels
        || layer < 0 || layer >= entry.depthOrLayers || x < 0 || y < 0
        || width <= 0 || height <= 0 || sourceRowPixels < width) {
      rejectedWrites++;
      return false;
    }
    int mipWidth = Math.max(1, entry.width >> mipLevel);
    int mipHeight = Math.max(1, entry.height >> mipLevel);
    if (x > mipWidth || width > mipWidth - x || y > mipHeight
        || height > mipHeight - y) {
      rejectedWrites++;
      return false;
    }
    long rowBytesLong = (long) sourceRowPixels * entry.bytesPerPixel;
    long copyRowBytesLong = (long) width * entry.bytesPerPixel;
    long required = (long) (height - 1) * rowBytesLong + copyRowBytesLong;
    long imageBytesLong = (long) mipWidth * mipHeight * entry.bytesPerPixel;
    if (rowBytesLong > Integer.MAX_VALUE
        || copyRowBytesLong > Integer.MAX_VALUE
        || required > source.remaining() || imageBytesLong > maxImageBytes
        || imageBytesLong > Integer.MAX_VALUE) {
      rejectedWrites++;
      return false;
    }
    ImageKey key = new ImageKey(mipLevel, layer);
    Image prior = entry.images.get(key);
    boolean fullWrite = x == 0 && y == 0 && width == mipWidth
        && height == mipHeight;
    if (!fullWrite && prior == null) {
      rejectedWrites++;
      return false;
    }
    int imageBytes = Math.toIntExact(imageBytesLong);
    long delta = prior == null ? imageBytes : 0;
    if (!makeRoom(delta, false, texture)) {
      rejectedWrites++;
      return false;
    }
    byte[] image = prior == null ? new byte[imageBytes]
        : prior.bytes.clone();
    ByteBuffer input = source.duplicate();
    int sourceStart = input.position();
    int rowBytes = Math.toIntExact(rowBytesLong);
    int copyBytes = Math.toIntExact(copyRowBytesLong);
    int destinationStride = mipWidth * entry.bytesPerPixel;
    for (int row = 0; row < height; row++) {
      input.position(sourceStart + row * rowBytes);
      input.get(image, (y + row) * destinationStride
          + x * entry.bytesPerPixel, copyBytes);
    }
    entry.images.put(key, new Image(image));
    entry.generation = nextGeneration++;
    if (prior == null) {
      entry.retainedBytes += imageBytes;
      retainedBytes += imageBytes;
    }
    return true;
  }

  public synchronized boolean copy(int sourceTexture, int destinationTexture,
      int mipLevel, int sourceX, int sourceY, int destinationX,
      int destinationY, int width, int height) {
    Entry source = entries.get(sourceTexture);
    Entry destination = entries.get(destinationTexture);
    if (source == null || destination == null
        || source.bytesPerPixel != destination.bytesPerPixel
        || !source.format.equals(destination.format)) {
      rejectedWrites++;
      return false;
    }
    TextureSnapshot snapshot = snapshot(sourceTexture, source.generation,
        mipLevel, 0).orElse(null);
    if (snapshot == null) {
      rejectedWrites++;
      return false;
    }
    try {
      int sourceWidth = Math.max(1, source.width >> mipLevel);
      int sourceOffset = Math.addExact(sourceX,
          Math.multiplyExact(sourceY, sourceWidth));
      sourceOffset = Math.multiplyExact(sourceOffset, source.bytesPerPixel);
      if (sourceOffset < 0 || sourceOffset >= snapshot.bytes.length) {
        rejectedWrites++;
        return false;
      }
      ByteBuffer bytes = ByteBuffer.wrap(snapshot.bytes, sourceOffset,
          snapshot.bytes.length - sourceOffset).slice();
      return write(destinationTexture, mipLevel, 0, destinationX,
          destinationY, width, height, sourceWidth, bytes);
    } catch (ArithmeticException invalidRange) {
      rejectedWrites++;
      return false;
    }
  }

  public synchronized long generation(int texture) {
    Entry entry = entries.get(texture);
    return entry == null ? 0 : entry.generation;
  }

  public synchronized Optional<TextureSnapshot> snapshot(int texture,
      long generation, int mipLevel, int layer) {
    Entry entry = entries.get(texture);
    Image image = entry == null ? null
        : entry.images.get(new ImageKey(mipLevel, layer));
    if (entry == null || image == null || generation <= 0
        || entry.generation != generation) {
      return Optional.empty();
    }
    return Optional.of(new TextureSnapshot(texture, generation,
        entry.format, Math.max(1, entry.width >> mipLevel),
        Math.max(1, entry.height >> mipLevel), layer, mipLevel,
        entry.bytesPerPixel, image.bytes));
  }

  public synchronized Optional<TextureMetadata> metadata(int texture,
      int mipLevel, int layer) {
    Entry entry = entries.get(texture);
    if (entry == null || mipLevel < 0 || mipLevel >= entry.mipLevels
        || layer < 0 || layer >= entry.depthOrLayers) {
      return Optional.empty();
    }
    return Optional.of(new TextureMetadata(entry.format,
        Math.max(1, entry.width >> mipLevel),
        Math.max(1, entry.height >> mipLevel), entry.depthOrLayers,
        entry.bytesPerPixel, entry.generation));
  }

  public synchronized void delete(int texture) {
    Entry removed = entries.remove(texture);
    if (removed != null) {
      retainedBytes -= removed.retainedBytes;
    }
  }

  public synchronized void clear() {
    entries.clear();
    retainedBytes = 0;
  }

  public synchronized Status status() {
    return new Status(entries.size(), retainedBytes, evictions,
        rejectedWrites);
  }

  private boolean makeRoom(long bytes, boolean addingTexture,
      int protectedTexture) {
    while (((addingTexture && entries.size() >= maxTextures)
        || retainedBytes + bytes > maxTotalBytes) && !entries.isEmpty()) {
      Integer victim = entries.keySet().stream()
          .filter(texture -> texture != protectedTexture)
          .findFirst().orElse(null);
      if (victim == null) {
        return false;
      }
      Entry removed = entries.remove(victim);
      retainedBytes -= removed.retainedBytes;
      evictions++;
    }
    return (!addingTexture || entries.size() < maxTextures)
        && retainedBytes + bytes <= maxTotalBytes;
  }

  public static final class TextureSnapshot {
    private final int texture;
    private final long generation;
    private final String format;
    private final int width;
    private final int height;
    private final int layer;
    private final int mipLevel;
    private final int bytesPerPixel;
    private final byte[] bytes;
    private final long sharedHandle;
    private final boolean graphReference;

    private TextureSnapshot(int texture, long generation, String format,
        int width, int height, int layer, int mipLevel, int bytesPerPixel,
        byte[] bytes) {
      this(texture, generation, format, width, height, layer, mipLevel,
          bytesPerPixel, bytes, 0, false);
    }

    private TextureSnapshot(int texture, long generation, String format,
        int width, int height, int layer, int mipLevel, int bytesPerPixel,
        byte[] bytes, long sharedHandle) {
      this(texture, generation, format, width, height, layer, mipLevel,
          bytesPerPixel, bytes, sharedHandle, false);
    }

    private TextureSnapshot(int texture, long generation, String format,
        int width, int height, int layer, int mipLevel, int bytesPerPixel,
        byte[] bytes, long sharedHandle, boolean graphReference) {
      if (texture <= 0 || generation <= 0 || format == null
          || format.isBlank() || width <= 0 || height <= 0 || layer < 0
          || mipLevel < 0 || bytesPerPixel <= 0 || bytes == null
          || (graphReference
              ? sharedHandle != 0 || bytes.length != 0
              : (sharedHandle == 0) == (bytes.length == 0))) {
        throw new IllegalArgumentException("invalid texture snapshot");
      }
      this.texture = texture;
      this.generation = generation;
      this.format = format;
      this.width = width;
      this.height = height;
      this.layer = layer;
      this.mipLevel = mipLevel;
      this.bytesPerPixel = bytesPerPixel;
      this.bytes = bytes;
      this.sharedHandle = sharedHandle;
      this.graphReference = graphReference;
    }

    public int texture() { return texture; }
    public long generation() { return generation; }
    public String format() { return format; }
    public int width() { return width; }
    public int height() { return height; }
    public int layer() { return layer; }
    public int mipLevel() { return mipLevel; }
    public int bytesPerPixel() { return bytesPerPixel; }
    public int byteLength() { return bytes.length; }
    public byte[] bytes() { return bytes.clone(); }
    /** Package-private immutable payload access for trusted packet encoders. */
    byte[] ownedBytes() { return bytes; }
    public boolean shared() { return sharedHandle != 0; }
    public long sharedHandle() { return sharedHandle; }
    public boolean graphReference() { return graphReference; }

    static TextureSnapshot fromReadback(int texture, long generation,
        TextureMetadata metadata, int layer, int mipLevel, byte[] bytes) {
      Objects.requireNonNull(metadata, "metadata");
      Objects.requireNonNull(bytes, "bytes");
      int expected = Math.multiplyExact(
          Math.multiplyExact(metadata.width(), metadata.height()),
          metadata.bytesPerPixel());
      if (texture <= 0 || generation <= 0 || layer < 0 || mipLevel < 0
          || bytes.length != expected) {
        throw new IllegalArgumentException("invalid texture readback");
      }
      return new TextureSnapshot(texture, generation, metadata.format(),
          metadata.width(), metadata.height(), layer, mipLevel,
          metadata.bytesPerPixel(), bytes.clone());
    }

    static TextureSnapshot fromGpuHandoff(int texture, long generation,
        String format, int width, int height, int bytesPerPixel,
        long sharedHandle) {
      if (sharedHandle <= 0) {
        throw new IllegalArgumentException("invalid shared texture handle");
      }
      return new TextureSnapshot(texture, generation, format, width, height,
          0, 0, bytesPerPixel, new byte[0], sharedHandle);
    }

    /**
     * Metadata-only reference to an already initialized persistent Metal
     * graph attachment. It is deliberately impossible to encode as inline or
     * IOSurface data; a graph override must resolve it before submission.
     */
    static TextureSnapshot fromGraphReference(int texture, long generation,
        String format, int width, int height, int bytesPerPixel,
        int layer, int mipLevel) {
      return new TextureSnapshot(texture, generation, format, width, height,
          layer, mipLevel, bytesPerPixel, new byte[0], 0, true);
    }
  }

  public record TextureMetadata(String format, int width, int height,
                                int depthOrLayers, int bytesPerPixel,
                                long generation) {
    public TextureMetadata {
      Objects.requireNonNull(format, "format");
      if (format.isBlank() || width <= 0 || height <= 0
          || depthOrLayers <= 0 || bytesPerPixel <= 0 || generation <= 0) {
        throw new IllegalArgumentException("invalid texture metadata");
      }
    }
  }

  public record Status(int textures, long retainedBytes, long evictions,
                       long rejectedWrites) {
  }

  private static final class Entry {
    private final String format;
    private final int width;
    private final int height;
    private final int depthOrLayers;
    private final int mipLevels;
    private final int bytesPerPixel;
    private final Map<ImageKey, Image> images = new HashMap<>();
    private long generation;
    private long retainedBytes;

    private Entry(String format, int width, int height, int depthOrLayers,
        int mipLevels, int bytesPerPixel, long generation) {
      this.format = format;
      this.width = width;
      this.height = height;
      this.depthOrLayers = depthOrLayers;
      this.mipLevels = mipLevels;
      this.bytesPerPixel = bytesPerPixel;
      this.generation = generation;
    }
  }

  private record ImageKey(int mipLevel, int layer) {
  }

  private record Image(byte[] bytes) {
  }
}

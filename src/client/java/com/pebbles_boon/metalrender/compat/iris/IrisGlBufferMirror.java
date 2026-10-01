package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded CPU shadow of GL buffer uploads used only by opt-in Iris replay.
 *
 * <p>A snapshot succeeds only when every requested byte was observed and the
 * draw-time generation still matches. Eviction, partial initialization, and a
 * later upload all fail closed instead of replaying guessed data.</p>
 */
public final class IrisGlBufferMirror {
  public static final String ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalShadowReplay";
  public static final int DEFAULT_MAX_BUFFERS = 16_384;
  public static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
  public static final int DEFAULT_MAX_SINGLE_BUFFER_BYTES = 64 * 1024 * 1024;
  private static final int MAX_WRITTEN_RANGES = 4_096;
  private static final IrisGlBufferMirror GLOBAL = new IrisGlBufferMirror(
      DEFAULT_MAX_BUFFERS, DEFAULT_MAX_TOTAL_BYTES,
      DEFAULT_MAX_SINGLE_BUFFER_BYTES);

  private final int maxBuffers;
  private final long maxTotalBytes;
  private final int maxSingleBufferBytes;
  private final LinkedHashMap<Integer, Entry> entries =
      new LinkedHashMap<>(16, 0.75F, true);
  private long retainedBytes;
  private long nextGeneration = 1;
  private long evictions;
  private long rejectedWrites;

  public IrisGlBufferMirror(int maxBuffers, long maxTotalBytes,
      int maxSingleBufferBytes) {
    if (maxBuffers <= 0 || maxTotalBytes <= 0
        || maxSingleBufferBytes <= 0
        || maxSingleBufferBytes > maxTotalBytes) {
      throw new IllegalArgumentException("invalid GL buffer mirror bounds");
    }
    this.maxBuffers = maxBuffers;
    this.maxTotalBytes = maxTotalBytes;
    this.maxSingleBufferBytes = maxSingleBufferBytes;
  }

  public static IrisGlBufferMirror global() {
    return GLOBAL;
  }

  public static boolean isEnabled() {
    return IrisMetalFeatureFlags.enabled(ENABLED_PROPERTY);
  }

  /** Defines uninitialized storage, invalidating every prior byte range. */
  public synchronized boolean allocate(int glBuffer, long totalSize) {
    if (glBuffer <= 0 || totalSize <= 0 || totalSize > maxSingleBufferBytes
        || totalSize > Integer.MAX_VALUE) {
      rejectedWrites++;
      return false;
    }
    Entry previous = entries.remove(glBuffer);
    if (previous != null) {
      retainedBytes -= previous.bytes.length;
    }
    int size = Math.toIntExact(totalSize);
    if (!makeRoom(size, glBuffer)) {
      rejectedWrites++;
      return false;
    }
    entries.put(glBuffer, new Entry(new byte[size], nextGeneration++));
    retainedBytes += size;
    return true;
  }

  /** Mirrors one successful write without mutating the caller's buffer. */
  public synchronized boolean write(int glBuffer, long totalSize,
      long offsetBytes, long maximumWriteBytes, ByteBuffer source) {
    Objects.requireNonNull(source, "source");
    if (glBuffer <= 0 || totalSize <= 0 || totalSize > maxSingleBufferBytes
        || totalSize > Integer.MAX_VALUE || offsetBytes < 0
        || maximumWriteBytes < 0 || offsetBytes > totalSize) {
      rejectedWrites++;
      return false;
    }
    ByteBuffer input = source.duplicate();
    long available = Math.min((long) input.remaining(), maximumWriteBytes);
    if (available <= 0 || available > Integer.MAX_VALUE
        || available > totalSize - offsetBytes) {
      rejectedWrites++;
      return false;
    }
    int size = Math.toIntExact(totalSize);
    Entry entry = entries.get(glBuffer);
    if (entry == null || entry.bytes.length != size) {
      if (entry != null) {
        retainedBytes -= entry.bytes.length;
        entries.remove(glBuffer);
      }
      if (!makeRoom(size, glBuffer)) {
        rejectedWrites++;
        return false;
      }
      entry = new Entry(new byte[size], nextGeneration++);
      entries.put(glBuffer, entry);
      retainedBytes += size;
    } else {
      entry.generation = nextGeneration++;
    }
    int count = Math.toIntExact(available);
    input.limit(input.position() + count);
    input.get(entry.bytes, Math.toIntExact(offsetBytes), count);
    if (!entry.ranges.add(offsetBytes, offsetBytes + available)) {
      retainedBytes -= entry.bytes.length;
      entries.remove(glBuffer);
      rejectedWrites++;
      return false;
    }
    return true;
  }

  /** Mirrors a GPU-side buffer copy only when the source range is complete. */
  public synchronized boolean copy(int sourceBuffer, long sourceOffset,
      int destinationBuffer, long destinationSize, long destinationOffset,
      long length) {
    if (sourceBuffer <= 0 || destinationBuffer <= 0 || sourceOffset < 0
        || destinationOffset < 0 || length <= 0
        || destinationSize <= 0 || destinationSize > maxSingleBufferBytes
        || destinationSize > Integer.MAX_VALUE
        || destinationOffset > destinationSize
        || length > destinationSize - destinationOffset) {
      rejectedWrites++;
      return false;
    }
    Entry source = entries.get(sourceBuffer);
    if (source == null || sourceOffset > source.bytes.length
        || length > source.bytes.length - sourceOffset
        || !source.ranges.contains(sourceOffset, sourceOffset + length)) {
      rejectedWrites++;
      return false;
    }
    byte[] copy = new byte[Math.toIntExact(length)];
    System.arraycopy(source.bytes, Math.toIntExact(sourceOffset), copy, 0,
        copy.length);
    return write(destinationBuffer, destinationSize, destinationOffset,
        length, ByteBuffer.wrap(copy));
  }

  public synchronized long generation(int glBuffer) {
    Entry entry = entries.get(glBuffer);
    return entry == null ? 0 : entry.generation;
  }

  public synchronized long size(int glBuffer) {
    Entry entry = entries.get(glBuffer);
    return entry == null ? 0 : entry.bytes.length;
  }

  /**
   * Mirrors the zero-fill form of GL43C.glClearBufferSubData used by Iris'
   * ShaderStorageBuffer implementation. Only the exact R8/RED/BYTE form is
   * accepted here; unsupported formats must fail closed rather than mutate a
   * replay buffer with guessed conversion semantics.
   */
  public synchronized boolean clearZero(int glBuffer, long offsetBytes,
      long sizeBytes, int internalFormat, int format, int type, int[] values) {
    if (glBuffer <= 0 || offsetBytes < 0 || sizeBytes <= 0
        || internalFormat != 0x8229 /* GL_R8 */
        || format != 0x1903 /* GL_RED */
        || type != 0x1400 /* GL_BYTE */
        || values == null || values.length == 0 || values[0] != 0
        || offsetBytes > Integer.MAX_VALUE
        || sizeBytes > Integer.MAX_VALUE
        || offsetBytes + sizeBytes > Integer.MAX_VALUE) {
      rejectedWrites++;
      return false;
    }
    Entry entry = entries.get(glBuffer);
    if (entry == null || offsetBytes + sizeBytes > entry.bytes.length) {
      rejectedWrites++;
      return false;
    }
    int start = Math.toIntExact(offsetBytes);
    int length = Math.toIntExact(sizeBytes);
    Arrays.fill(entry.bytes, start, start + length, (byte) 0);
    if (!entry.ranges.add(offsetBytes, offsetBytes + sizeBytes)) {
      retainedBytes -= entry.bytes.length;
      entries.remove(glBuffer);
      rejectedWrites++;
      return false;
    }
    entry.generation = nextGeneration++;
    return true;
  }

  public synchronized Optional<BufferSnapshot> snapshot(int glBuffer,
      long generation, long offsetBytes, long lengthBytes) {
    Entry entry = entries.get(glBuffer);
    if (entry == null || generation <= 0 || entry.generation != generation
        || offsetBytes < 0 || lengthBytes <= 0
        || offsetBytes > entry.bytes.length
        || lengthBytes > entry.bytes.length - offsetBytes
        || !entry.ranges.contains(offsetBytes, offsetBytes + lengthBytes)) {
      return Optional.empty();
    }
    byte[] bytes = new byte[Math.toIntExact(lengthBytes)];
    System.arraycopy(entry.bytes, Math.toIntExact(offsetBytes), bytes, 0,
        bytes.length);
    return Optional.of(BufferSnapshot.owned(glBuffer, generation,
        offsetBytes, bytes));
  }

  /** Returns the entire allocation only when every byte was initialized. */
  public synchronized Optional<BufferSnapshot> snapshotWhole(int glBuffer,
      long generation) {
    Entry entry = entries.get(glBuffer);
    if (entry == null || generation <= 0 || entry.generation != generation
        || !entry.ranges.contains(0, entry.bytes.length)) {
      return Optional.empty();
    }
    return Optional.of(BufferSnapshot.owned(glBuffer, generation, 0,
        Arrays.copyOf(entry.bytes, entry.bytes.length)));
  }

  /** Compact exact-JAR diagnostic; does not copy retained bytes. */
  public synchronized String rangeDiagnostic(int glBuffer, long generation,
      long offsetBytes, long lengthBytes) {
    Entry entry = entries.get(glBuffer);
    if (entry == null) {
      return glBuffer + ":absent";
    }
    boolean rangeValid = offsetBytes >= 0 && lengthBytes > 0
        && offsetBytes <= entry.bytes.length
        && lengthBytes <= entry.bytes.length - offsetBytes;
    boolean covered = rangeValid
        && entry.ranges.contains(offsetBytes, offsetBytes + lengthBytes);
    return glBuffer + ":gen=" + generation + '/' + entry.generation
        + ":range=" + offsetBytes + '+' + lengthBytes + '/'
        + entry.bytes.length + ":covered=" + covered;
  }

  public synchronized void delete(int glBuffer) {
    Entry removed = entries.remove(glBuffer);
    if (removed != null) {
      retainedBytes -= removed.bytes.length;
    }
  }

  /** Invalidates all GL names when the owning OpenGL context is reset. */
  public synchronized void clear() {
    entries.clear();
    retainedBytes = 0;
  }

  public synchronized Status status() {
    return new Status(entries.size(), retainedBytes, evictions,
        rejectedWrites);
  }

  private boolean makeRoom(int requestedBytes, int protectedBuffer) {
    while ((entries.size() >= maxBuffers
        || retainedBytes + requestedBytes > maxTotalBytes)
        && !entries.isEmpty()) {
      Integer victim = entries.keySet().iterator().next();
      if (victim == protectedBuffer && entries.size() == 1) {
        return false;
      }
      Entry removed = entries.remove(victim);
      retainedBytes -= removed.bytes.length;
      evictions++;
    }
    return entries.size() < maxBuffers
        && retainedBytes + requestedBytes <= maxTotalBytes;
  }

  public static final class BufferSnapshot {
    private final int glBuffer;
    private final long generation;
    private final long offsetBytes;
    private final byte[] bytes;

    public BufferSnapshot(int glBuffer, long generation, long offsetBytes,
        byte[] bytes) {
      this(glBuffer, generation, offsetBytes, bytes, false);
    }

    private BufferSnapshot(int glBuffer, long generation, long offsetBytes,
        byte[] bytes, boolean owned) {
      if (glBuffer <= 0 || generation <= 0 || offsetBytes < 0) {
        throw new IllegalArgumentException("invalid GL buffer snapshot");
      }
      byte[] input = Objects.requireNonNull(bytes, "bytes");
      if (input.length == 0) {
        throw new IllegalArgumentException("empty GL buffer snapshot");
      }
      this.glBuffer = glBuffer;
      this.generation = generation;
      this.offsetBytes = offsetBytes;
      this.bytes = owned ? input : input.clone();
    }

    private static BufferSnapshot owned(int glBuffer, long generation,
        long offsetBytes, byte[] bytes) {
      return new BufferSnapshot(glBuffer, generation, offsetBytes, bytes,
          true);
    }

    public int glBuffer() {
      return glBuffer;
    }

    public long generation() {
      return generation;
    }

    public long offsetBytes() {
      return offsetBytes;
    }

    public byte[] bytes() {
      return bytes.clone();
    }

    /** Package-private immutable payload access for capture assembly. */
    byte[] ownedBytes() {
      return bytes;
    }
  }

  public record Status(int buffers, long retainedBytes, long evictions,
                       long rejectedWrites) {
  }

  private static final class Entry {
    private final byte[] bytes;
    private final WrittenRanges ranges = new WrittenRanges();
    private long generation;

    private Entry(byte[] bytes, long generation) {
      this.bytes = bytes;
      this.generation = generation;
    }
  }

  private static final class WrittenRanges {
    private final ArrayList<Range> values = new ArrayList<>();

    private boolean add(long start, long end) {
      int index = 0;
      while (index < values.size() && values.get(index).end < start) {
        index++;
      }
      long mergedStart = start;
      long mergedEnd = end;
      while (index < values.size() && values.get(index).start <= mergedEnd) {
        Range current = values.remove(index);
        mergedStart = Math.min(mergedStart, current.start);
        mergedEnd = Math.max(mergedEnd, current.end);
      }
      values.add(index, new Range(mergedStart, mergedEnd));
      return values.size() <= MAX_WRITTEN_RANGES;
    }

    private boolean contains(long start, long end) {
      for (Range range : values) {
        if (range.start > start) {
          return false;
        }
        if (range.start <= start && range.end >= end) {
          return true;
        }
      }
      return false;
    }
  }

  private record Range(long start, long end) {
  }
}

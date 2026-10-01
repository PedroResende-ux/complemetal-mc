package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.BufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.IndexedBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureBufferBinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable, draw-time CPU copy of every GL buffer range needed by one
 * prospective Metal replay command.
 *
 * <p>The copy is taken synchronously at capture time. That is intentional:
 * retaining only a GL name and reading it later on the translation worker
 * would race buffer reuse in the next draw or frame. Missing generations,
 * partial initialization, stale writes, and capacity limits are explicit
 * blockers and never produce guessed bytes.</p>
 */
public record IrisShadowReplayBufferSnapshot(
    boolean captureEnabled,
    List<BufferImage> images,
    List<VertexBufferRef> vertexBuffers,
    Optional<BufferRef> indexBuffer,
    Optional<BufferRef> indirectArguments,
    Map<IndexedBufferBinding, BufferRef> indexedBuffers,
    Map<Integer, BufferRef> textureBuffers,
    List<String> blockers) {
  public static final int MAX_IMAGES = 2_048;
  public static final long MAX_TOTAL_BYTES = 32L * 1024L * 1024L;
  public static final int MAX_BLOCKERS = 32;
  private static final java.util.concurrent.atomic.AtomicLong
      UNTRACKED_READBACK_GENERATION =
          new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);

  public IrisShadowReplayBufferSnapshot {
    Objects.requireNonNull(images, "images");
    Objects.requireNonNull(vertexBuffers, "vertexBuffers");
    Objects.requireNonNull(indexBuffer, "indexBuffer");
    Objects.requireNonNull(indirectArguments, "indirectArguments");
    Objects.requireNonNull(indexedBuffers, "indexedBuffers");
    Objects.requireNonNull(textureBuffers, "textureBuffers");
    Objects.requireNonNull(blockers, "blockers");
    if (images.size() > MAX_IMAGES || blockers.size() > MAX_BLOCKERS) {
      throw new IllegalArgumentException("shadow buffer snapshot exceeds bounds");
    }

    long bytes = 0;
    ArrayList<BufferImage> copiedImages = new ArrayList<>(images.size());
    for (int index = 0; index < images.size(); index++) {
      BufferImage image = Objects.requireNonNull(images.get(index), "image");
      if (image.id() != index) {
        throw new IllegalArgumentException("shadow buffer ids must be dense");
      }
      bytes = Math.addExact(bytes, image.byteLength());
      copiedImages.add(image);
    }
    if (bytes > MAX_TOTAL_BYTES) {
      throw new IllegalArgumentException("shadow buffer byte bound exceeded");
    }
    images = List.copyOf(copiedImages);

    ArrayList<VertexBufferRef> copiedVertices =
        new ArrayList<>(vertexBuffers);
    copiedVertices.sort(Comparator.comparingInt(VertexBufferRef::slot));
    for (int index = 0; index < copiedVertices.size(); index++) {
      VertexBufferRef reference = copiedVertices.get(index);
      if (reference.slot() != index) {
        throw new IllegalArgumentException(
            "shadow vertex buffer slots must be dense");
      }
      requireImage(reference.buffer(), images.size());
    }
    vertexBuffers = List.copyOf(copiedVertices);
    int imageCount = images.size();
    indexBuffer.ifPresent(reference -> requireImage(reference, imageCount));
    indirectArguments.ifPresent(
        reference -> requireImage(reference, imageCount));

    ArrayList<Map.Entry<IndexedBufferBinding, BufferRef>> indexed =
        new ArrayList<>(indexedBuffers.entrySet());
    indexed.sort(Map.Entry.<IndexedBufferBinding, BufferRef>comparingByKey(
        Comparator.comparingInt(IndexedBufferBinding::target)
            .thenComparingInt(IndexedBufferBinding::index)));
    LinkedHashMap<IndexedBufferBinding, BufferRef> copiedIndexed =
        new LinkedHashMap<>();
    for (Map.Entry<IndexedBufferBinding, BufferRef> entry : indexed) {
      requireImage(entry.getValue(), images.size());
      copiedIndexed.put(Objects.requireNonNull(entry.getKey(), "binding"),
          entry.getValue());
    }
    indexedBuffers = Map.copyOf(copiedIndexed);

    ArrayList<Integer> textureIds = new ArrayList<>(textureBuffers.keySet());
    textureIds.sort(Integer::compareTo);
    LinkedHashMap<Integer, BufferRef> copiedTextures = new LinkedHashMap<>();
    for (Integer texture : textureIds) {
      if (texture == null || texture <= 0) {
        throw new IllegalArgumentException("invalid texture-buffer name");
      }
      BufferRef reference = textureBuffers.get(texture);
      requireImage(reference, images.size());
      copiedTextures.put(texture, reference);
    }
    textureBuffers = Map.copyOf(copiedTextures);

    ArrayList<String> copiedBlockers = new ArrayList<>(blockers.size());
    String previous = null;
    for (String blocker : blockers.stream().sorted().toList()) {
      Objects.requireNonNull(blocker, "blocker");
      if (blocker.isBlank() || blocker.length() > 96
          || blocker.indexOf('\n') >= 0 || blocker.indexOf('\r') >= 0) {
        throw new IllegalArgumentException("invalid shadow replay blocker");
      }
      if (!blocker.equals(previous)) {
        copiedBlockers.add(blocker);
        previous = blocker;
      }
    }
    blockers = List.copyOf(copiedBlockers);
    if (!captureEnabled && (!images.isEmpty() || !vertexBuffers.isEmpty()
        || indexBuffer.isPresent() || indirectArguments.isPresent()
        || !indexedBuffers.isEmpty() || !textureBuffers.isEmpty()
        || !blockers.isEmpty())) {
      throw new IllegalArgumentException(
          "disabled shadow capture must not retain resources");
    }
  }

  public static IrisShadowReplayBufferSnapshot disabled() {
    return new IrisShadowReplayBufferSnapshot(false, List.of(), List.of(),
        Optional.empty(), Optional.empty(), Map.of(), Map.of(), List.of());
  }

  public static IrisShadowReplayBufferSnapshot capture(
      IrisExecutionCommand command,
      IrisVertexInputBindings vertexInputBindings,
      IrisGlResourceBindingSnapshot resourceBindings,
      IrisGlBufferMirror mirror) {
    return capture(command, vertexInputBindings, resourceBindings, mirror,
        null, null);
  }

  public static IrisShadowReplayBufferSnapshot capture(
      IrisExecutionCommand command,
      IrisVertexInputBindings vertexInputBindings,
      IrisGlResourceBindingSnapshot resourceBindings,
      IrisGlBufferMirror mirror,
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor,
      IrisGlGenericAttributeTracker genericAttributes) {
    return capture(command, vertexInputBindings, resourceBindings, mirror,
        descriptor, genericAttributes, null);
  }

  static IrisShadowReplayBufferSnapshot capture(
      IrisExecutionCommand command,
      IrisVertexInputBindings vertexInputBindings,
      IrisGlResourceBindingSnapshot resourceBindings,
      IrisGlBufferMirror mirror,
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor,
      IrisGlGenericAttributeTracker genericAttributes,
      RetainedCapture retainedCapture) {
    return capture(command, vertexInputBindings, resourceBindings, mirror,
        descriptor, genericAttributes, retainedCapture, null);
  }

  static IrisShadowReplayBufferSnapshot capture(
      IrisExecutionCommand command,
      IrisVertexInputBindings vertexInputBindings,
      IrisGlResourceBindingSnapshot resourceBindings,
      IrisGlBufferMirror mirror,
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor,
      IrisGlGenericAttributeTracker genericAttributes,
      RetainedCapture retainedCapture,
      IrisReplayCaptureRequirements requirements) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(vertexInputBindings, "vertexInputBindings");
    Objects.requireNonNull(mirror, "mirror");
    Builder builder = new Builder(mirror, retainedCapture);

    boolean computePipeline = descriptor != null
        && descriptor.pass().kind() == IrisPipelineState.PassKind.COMPUTE;
    if (computePipeline) {
      // Compute dispatches do not have vertex/index bindings.
    } else if (vertexInputBindings.complete()) {
      for (IrisVertexInputBindings.BufferSlice slice
          : vertexInputBindings.vertexBuffers()) {
        BufferRef reference = builder.captureRange(slice.glBuffer(),
            slice.mirrorGeneration(), slice.offsetBytes(),
            slice.lengthBytes(), "vertex-buffer");
        if (reference != null) {
          builder.vertexBuffers.add(new VertexBufferRef(slice.slot(),
              reference));
        }
      }
      vertexInputBindings.indexBuffer().ifPresent(slice -> {
        BufferRef reference = builder.captureRange(slice.glBuffer(),
            slice.mirrorGeneration(), slice.offsetBytes(),
            slice.lengthBytes(), "index-buffer");
        if (reference != null) {
          builder.indexBuffer = reference;
        }
      });
    } else {
      builder.blockers.add(vertexInputBindings.incompleteReason().isEmpty()
          ? "vertex-input-bindings-incomplete"
          : vertexInputBindings.incompleteReason());
    }

    if (descriptor != null) {
      Objects.requireNonNull(genericAttributes, "genericAttributes");
      builder.captureConstantVertexBuffers(descriptor, genericAttributes);
    }

    if (resourceBindings == null) {
      builder.blockers.add("resource-bindings-unavailable");
    } else {
      resourceBindings.indexedBuffers().entrySet().stream()
          .filter(entry -> entry.getValue().buffer() > 0)
          .filter(entry -> entry.getKey().target()
              == IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER
              || entry.getKey().target()
              == IrisGlResourceBindingTracker.GL_SHADER_STORAGE_BUFFER)
          .filter(entry -> requirements == null
              || requirements.indexedBuffers().contains(entry.getKey()))
          .sorted(Map.Entry.comparingByKey(Comparator
              .comparingInt(IndexedBufferBinding::target)
              .thenComparingInt(IndexedBufferBinding::index)))
          .forEach(entry -> {
            BufferBinding binding = entry.getValue();
            BufferRef reference = binding.rangeBound()
                ? builder.captureRange(binding.buffer(),
                    binding.mirrorGeneration(), binding.offsetBytes(),
                    binding.sizeBytes(), "indexed-buffer")
                : builder.captureWhole(binding.buffer(),
                    binding.mirrorGeneration(), "indexed-buffer");
            if (reference != null) {
              builder.indexedBuffers.put(entry.getKey(), reference);
            }
          });

      Set<Integer> boundTextureNames = new TreeSet<>();
      if (requirements != null) {
        boundTextureNames.addAll(requirements.textureBufferNames());
      } else {
        resourceBindings.textureUnits().values().forEach(binding -> {
          if (binding.texture() > 0) {
            boundTextureNames.add(binding.texture());
          }
        });
        resourceBindings.textureBufferUnits().values().forEach(binding -> {
          if (binding.texture() > 0) {
            boundTextureNames.add(binding.texture());
          }
        });
      }
      for (int texture : boundTextureNames) {
        TextureBufferBinding binding =
            resourceBindings.textureBuffers().get(texture);
        if (binding == null || binding.buffer() <= 0) {
          continue;
        }
        BufferRef reference = builder.captureWhole(binding.buffer(),
            binding.mirrorGeneration(), "texture-buffer");
        if (reference != null) {
          builder.textureBuffers.put(texture, reference);
        }
      }
    }

    if (command instanceof IrisExecutionCommand.IndirectDraw draw) {
      int commandBytes = draw.indexElementBytes() == 0 ? 16 : 20;
      try {
        long length = Math.multiplyExact((long) draw.drawCount(),
            commandBytes);
        long generation = mirror.generation(draw.indirectGlBuffer());
        builder.indirectArguments = builder.captureRange(
            draw.indirectGlBuffer(), generation, draw.offsetBytes(), length,
            "indirect-arguments");
      } catch (ArithmeticException overflow) {
        builder.blockers.add("indirect-arguments-range-overflow");
      }
    } else if (command instanceof IrisExecutionCommand.IndirectDispatch) {
      builder.blockers.add("indirect-dispatch-buffer-binding-unavailable");
    }
    return builder.freeze();
  }

  public boolean complete() {
    return captureEnabled && blockers.isEmpty();
  }

  /**
   * Blockers for resources that the draw command itself must consume.
   * Indexed UBO/SSBO and texture-buffer bindings are validated later against
   * the reflected shader argument table, because GL may retain unrelated
   * bindings from an earlier program.
   */
  public List<String> drawBlockers() {
    return blockers.stream()
        .filter(reason -> !reason.startsWith("indexed-buffer-")
            && !reason.startsWith("texture-buffer-"))
        .toList();
  }

  public boolean drawComplete() {
    return captureEnabled && drawBlockers().isEmpty();
  }

  public long totalBytes() {
    return images.stream().mapToLong(BufferImage::byteLength).sum();
  }

  /**
   * Promotes only geometry images to immutable Metal buffers. Uniform and
   * storage-buffer images stay inline because their contents may be dynamic.
   */
  public IrisShadowReplayBufferSnapshot promoteGeometryToMetal() {
    if (!captureEnabled || images.isEmpty()) {
      return this;
    }
    TreeSet<Integer> geometryImages = new TreeSet<>();
    vertexBuffers.forEach(reference ->
        geometryImages.add(reference.buffer().imageId()));
    indexBuffer.ifPresent(reference ->
        geometryImages.add(reference.imageId()));
    if (geometryImages.isEmpty()) {
      return this;
    }
    ArrayList<BufferImage> promoted = new ArrayList<>(images);
    boolean changed = false;
    for (Integer imageId : geometryImages) {
      BufferImage current = promoted.get(imageId);
      BufferImage resident = IrisMetalBufferResidentCache.promote(current)
          .orElse(current);
      promoted.set(imageId, resident);
      changed |= resident != current;
    }
    return changed ? new IrisShadowReplayBufferSnapshot(captureEnabled,
        promoted, vertexBuffers, indexBuffer, indirectArguments,
        indexedBuffers, textureBuffers, blockers) : this;
  }

  private static void requireImage(BufferRef reference, int imageCount) {
    Objects.requireNonNull(reference, "buffer reference");
    if (reference.imageId() >= imageCount) {
      throw new IllegalArgumentException(
          "buffer reference points outside snapshot");
    }
  }

  public static final class BufferImage {
    private final int id;
    private final int glBuffer;
    private final long generation;
    private final long sourceOffsetBytes;
    private final byte[] bytes;
    private final long sharedHandle;
    private final int byteLength;

    public BufferImage(int id, int glBuffer, long generation,
        long sourceOffsetBytes, byte[] bytes) {
      this(id, glBuffer, generation, sourceOffsetBytes, bytes, 0,
          Objects.requireNonNull(bytes, "bytes").length, false);
    }

    public BufferImage(int id, int glBuffer, long generation,
        long sourceOffsetBytes, byte[] bytes, long sharedHandle,
        int byteLength) {
      this(id, glBuffer, generation, sourceOffsetBytes, bytes, sharedHandle,
          byteLength, false);
    }

    private BufferImage(int id, int glBuffer, long generation,
        long sourceOffsetBytes, byte[] bytes, long sharedHandle,
        int byteLength, boolean owned) {
      boolean synthetic = glBuffer == 0 && generation == 0
          && sourceOffsetBytes == 0;
      if (id < 0 || !synthetic && (glBuffer <= 0 || generation <= 0
          || sourceOffsetBytes < 0) || sharedHandle < 0 || byteLength <= 0) {
        throw new IllegalArgumentException("invalid shadow buffer image");
      }
      byte[] input = Objects.requireNonNull(bytes, "bytes");
      if (sharedHandle == 0 && input.length != byteLength
          || sharedHandle > 0 && input.length != 0) {
        throw new IllegalArgumentException("invalid shadow buffer storage");
      }
      this.id = id;
      this.glBuffer = glBuffer;
      this.generation = generation;
      this.sourceOffsetBytes = sourceOffsetBytes;
      this.bytes = owned ? input : input.clone();
      this.sharedHandle = sharedHandle;
      this.byteLength = byteLength;
    }

    static BufferImage owned(int id, int glBuffer, long generation,
        long sourceOffsetBytes, byte[] bytes) {
      return new BufferImage(id, glBuffer, generation, sourceOffsetBytes,
          bytes, 0, Objects.requireNonNull(bytes, "bytes").length, true);
    }

    public int id() {
      return id;
    }

    public int glBuffer() {
      return glBuffer;
    }

    public long generation() {
      return generation;
    }

    public long sourceOffsetBytes() {
      return sourceOffsetBytes;
    }

    public byte[] bytes() {
      return bytes.clone();
    }

    /** Package-private immutable payload access for trusted packet encoders. */
    byte[] ownedBytes() {
      return bytes;
    }

    public long sharedHandle() {
      return sharedHandle;
    }

    public int byteLength() {
      return byteLength;
    }

    public boolean shared() {
      return sharedHandle > 0;
    }

    public BufferImage asShared(long handle) {
      if (handle <= 0) {
        throw new IllegalArgumentException("invalid shared buffer handle");
      }
      return new BufferImage(id, glBuffer, generation, sourceOffsetBytes,
          new byte[0], handle, byteLength, true);
    }
  }

  public record BufferRef(int imageId) {
    public BufferRef {
      if (imageId < 0) {
        throw new IllegalArgumentException("negative shadow buffer image id");
      }
    }
  }

  public record VertexBufferRef(int slot, BufferRef buffer) {
    public VertexBufferRef {
      if (slot < 0 || slot >= 31) {
        throw new IllegalArgumentException("invalid shadow vertex slot");
      }
      Objects.requireNonNull(buffer, "buffer");
    }
  }

  private record SnapshotKey(int glBuffer, long generation,
                             long offsetBytes, int lengthBytes) {
  }

  /**
   * One-frame immutable mirror snapshot table. A generation-qualified range
   * can be shared by multiple draw snapshots without another GL readback or
   * heap copy. The table never retains untracked readbacks: without a proven
   * generation, reuse could cross a GPU-side mutation that the mirror did not
   * observe.
   */
  static final class RetainedCapture {
    private final LinkedHashMap<SnapshotKey, byte[]> mirrorRanges =
        new LinkedHashMap<>();
    private long retainedBytes;
    private long lookups;
    private long hits;

    private byte[] lookup(int glBuffer, long generation, long offsetBytes,
        int lengthBytes) {
      lookups++;
      byte[] retained = mirrorRanges.get(new SnapshotKey(glBuffer,
          generation, offsetBytes, lengthBytes));
      if (retained != null) {
        hits++;
      }
      return retained;
    }

    private void retain(int glBuffer, long generation, long offsetBytes,
        byte[] bytes) {
      SnapshotKey key = new SnapshotKey(glBuffer, generation, offsetBytes,
          bytes.length);
      if (mirrorRanges.containsKey(key)) {
        return;
      }
      mirrorRanges.put(key, bytes);
      retainedBytes = Math.addExact(retainedBytes, bytes.length);
    }

    int entries() {
      return mirrorRanges.size();
    }

    long retainedBytes() {
      return retainedBytes;
    }

    long lookups() {
      return lookups;
    }

    long hits() {
      return hits;
    }
  }

  private static final class Builder {
    private final IrisGlBufferMirror mirror;
    private final RetainedCapture retainedCapture;
    private final LinkedHashMap<SnapshotKey, BufferRef> retained =
        new LinkedHashMap<>();
    private final ArrayList<BufferImage> images = new ArrayList<>();
    private final ArrayList<VertexBufferRef> vertexBuffers =
        new ArrayList<>();
    private final LinkedHashMap<IndexedBufferBinding, BufferRef>
        indexedBuffers = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, BufferRef> textureBuffers =
        new LinkedHashMap<>();
    private final TreeSet<String> blockers = new TreeSet<>();
    private BufferRef indexBuffer;
    private BufferRef indirectArguments;
    private long totalBytes;

    private Builder(IrisGlBufferMirror mirror,
        RetainedCapture retainedCapture) {
      this.mirror = mirror;
      this.retainedCapture = retainedCapture;
    }

    private BufferRef captureRange(int glBuffer, long generation,
        long offsetBytes, long lengthBytes, String category) {
      if (lengthBytes <= 0 || lengthBytes > MAX_TOTAL_BYTES) {
        blockers.add(category + "-snapshot-exceeds-draw-bound");
        return null;
      }
      SnapshotKey key = new SnapshotKey(glBuffer, generation, offsetBytes,
          Math.toIntExact(lengthBytes));
      BufferRef existing = retained.get(key);
      if (existing != null) {
        return existing;
      }
      if (residentGeometryCategory(category)) {
        Optional<BufferImage> resident = IrisMetalBufferResidentCache.lookup(
            images.size(), glBuffer, generation, offsetBytes,
            Math.toIntExact(lengthBytes));
        if (resident.isPresent()) {
          return retainResident(key, resident.orElseThrow(), category);
        }
      }
      if (generation > 0 && retainedCapture != null) {
        byte[] retained = retainedCapture.lookup(glBuffer, generation,
            offsetBytes, Math.toIntExact(lengthBytes));
        if (retained != null) {
          return retainOwned(glBuffer, generation, offsetBytes, retained,
              category);
        }
      }
      Optional<IrisGlBufferMirror.BufferSnapshot> snapshot = generation > 0
          ? mirror.snapshot(glBuffer, generation, offsetBytes, lengthBytes)
          : Optional.empty();
      if (snapshot.isPresent()) {
        return retain(snapshot, category);
      }
      Optional<byte[]> readback = IrisGlBufferReadback.range(glBuffer,
          offsetBytes, lengthBytes);
      if (readback.isPresent()) {
        return retainReadback(glBuffer, generation, offsetBytes,
            readback.orElseThrow(), category);
      }
      blockers.add(generation <= 0 ? category + "-generation-missing"
          : category + "-snapshot-unavailable");
      return null;
    }

    private static boolean residentGeometryCategory(String category) {
      return "vertex-buffer".equals(category)
          || "index-buffer".equals(category);
    }

    private BufferRef retainResident(SnapshotKey key, BufferImage image,
        String category) {
      if (!image.shared() || image.id() != images.size()) {
        blockers.add(category + "-resident-invalid");
        return null;
      }
      if (images.size() >= MAX_IMAGES) {
        blockers.add("buffer-image-capacity-exceeded");
        return null;
      }
      if (totalBytes > MAX_TOTAL_BYTES - image.byteLength()) {
        blockers.add("buffer-byte-capacity-exceeded");
        return null;
      }
      BufferRef reference = new BufferRef(image.id());
      images.add(image);
      retained.put(key, reference);
      totalBytes += image.byteLength();
      return reference;
    }

    private BufferRef captureWhole(int glBuffer, long generation,
        String category) {
      long size = mirror.size(glBuffer);
      if (generation > 0 && size > 0 && size <= MAX_TOTAL_BYTES) {
        if (retainedCapture != null) {
          byte[] retained = retainedCapture.lookup(glBuffer, generation, 0,
              Math.toIntExact(size));
          if (retained != null) {
            return retainOwned(glBuffer, generation, 0, retained, category);
          }
        }
        Optional<IrisGlBufferMirror.BufferSnapshot> snapshot =
            mirror.snapshotWhole(glBuffer, generation);
        if (snapshot.isPresent()) {
          return retain(snapshot, category);
        }
      }
      Optional<byte[]> readback = IrisGlBufferReadback.whole(glBuffer);
      if (readback.isPresent()) {
        return retainReadback(glBuffer, generation, 0,
            readback.orElseThrow(), category);
      }
      if (generation <= 0) {
        blockers.add(category + "-generation-missing");
      } else if (size <= 0 || size > MAX_TOTAL_BYTES) {
        blockers.add(category + "-snapshot-exceeds-draw-bound");
      } else {
        blockers.add(category + "-snapshot-unavailable");
      }
      return null;
    }

    private BufferRef retain(
        Optional<IrisGlBufferMirror.BufferSnapshot> candidate,
        String category) {
      if (candidate.isEmpty()) {
        blockers.add(category + "-snapshot-unavailable");
        return null;
      }
      IrisGlBufferMirror.BufferSnapshot snapshot = candidate.orElseThrow();
      byte[] bytes = snapshot.ownedBytes();
      if (retainedCapture != null) {
        retainedCapture.retain(snapshot.glBuffer(), snapshot.generation(),
            snapshot.offsetBytes(), bytes);
      }
      return retainOwned(snapshot.glBuffer(), snapshot.generation(),
          snapshot.offsetBytes(), bytes, category);
    }

    private BufferRef retainOwned(int glBuffer, long generation,
        long offsetBytes, byte[] bytes, String category) {
      SnapshotKey key = new SnapshotKey(glBuffer, generation, offsetBytes,
          bytes.length);
      BufferRef existing = retained.get(key);
      if (existing != null) {
        return existing;
      }
      if (images.size() >= MAX_IMAGES) {
        blockers.add("buffer-image-capacity-exceeded");
        return null;
      }
      if (totalBytes > MAX_TOTAL_BYTES - bytes.length) {
        blockers.add("buffer-byte-capacity-exceeded");
        return null;
      }
      BufferRef reference = new BufferRef(images.size());
      images.add(BufferImage.owned(reference.imageId(), glBuffer,
          generation, offsetBytes, bytes));
      retained.put(key, reference);
      totalBytes += bytes.length;
      return reference;
    }

    private BufferRef retainReadback(int glBuffer, long generation,
        long offsetBytes, byte[] bytes, String category) {
      long generationTag = generation > 0 ? generation
          : UNTRACKED_READBACK_GENERATION.getAndDecrement();
      if (generationTag <= 0) {
        blockers.add(category + "-generation-exhausted");
        return null;
      }
      SnapshotKey key = new SnapshotKey(glBuffer, generationTag,
          offsetBytes, bytes.length);
      BufferRef existing = retained.get(key);
      if (existing != null) {
        return existing;
      }
      if (images.size() >= MAX_IMAGES) {
        blockers.add("buffer-image-capacity-exceeded");
        return null;
      }
      if (totalBytes > MAX_TOTAL_BYTES - bytes.length) {
        blockers.add("buffer-byte-capacity-exceeded");
        return null;
      }
      BufferRef reference = new BufferRef(images.size());
      images.add(BufferImage.owned(reference.imageId(), glBuffer,
          generationTag, offsetBytes, bytes));
      retained.put(key, reference);
      totalBytes += bytes.length;
      return reference;
    }

    private void captureConstantVertexBuffers(
        IrisProgramIdentityRegistry.ProgramDescriptor descriptor,
        IrisGlGenericAttributeTracker genericAttributes) {
      java.util.HashSet<Integer> occupied = new java.util.HashSet<>();
      for (VertexBufferRef vertex : vertexBuffers) {
        occupied.add(vertex.slot());
      }
      for (IrisPipelineState.VertexBufferLayout layout
          : descriptor.vertexBuffers()) {
        if (layout.stepFunction()
            != IrisPipelineState.StepFunction.CONSTANT) {
          if (!occupied.contains(layout.bufferIndex())) {
            blockers.add("physical-vertex-buffer-slot-missing");
          }
          continue;
        }
        if (!occupied.add(layout.bufferIndex())) {
          blockers.add("constant-vertex-buffer-slot-conflict");
          continue;
        }
        try {
          byte[] bytes = genericAttributes.encode(layout,
              descriptor.vertexAttributes());
          if (images.size() >= MAX_IMAGES
              || totalBytes > MAX_TOTAL_BYTES - bytes.length) {
            blockers.add("constant-vertex-buffer-capacity-exceeded");
            continue;
          }
          BufferRef reference = new BufferRef(images.size());
          images.add(BufferImage.owned(reference.imageId(), 0, 0, 0,
              bytes));
          vertexBuffers.add(new VertexBufferRef(layout.bufferIndex(),
              reference));
          totalBytes += bytes.length;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
          blockers.add("constant-vertex-buffer-invalid");
        }
      }
      vertexBuffers.sort(Comparator.comparingInt(VertexBufferRef::slot));
      for (int index = 0; index < vertexBuffers.size(); index++) {
        if (vertexBuffers.get(index).slot() != index) {
          blockers.add("vertex-buffer-slots-incomplete");
          vertexBuffers.clear();
          break;
        }
      }
    }

    private IrisShadowReplayBufferSnapshot freeze() {
      ArrayList<String> finalBlockers = new ArrayList<>(blockers);
      if (finalBlockers.size() > MAX_BLOCKERS) {
        finalBlockers = new ArrayList<>(finalBlockers.subList(0,
            MAX_BLOCKERS - 1));
        finalBlockers.add("blocker-capacity-exceeded");
        finalBlockers.sort(String::compareTo);
      }
      return new IrisShadowReplayBufferSnapshot(true, images,
          vertexBuffers, Optional.ofNullable(indexBuffer),
          Optional.ofNullable(indirectArguments), indexedBuffers,
          textureBuffers, finalBlockers);
    }
  }
}

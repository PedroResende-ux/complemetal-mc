package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded mirror of legacy GL VAO/VBO bindings used by direct Iris draws. */
public final class IrisGlVertexArrayTracker {
  public static final int GL_ARRAY_BUFFER = 0x8892;
  public static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
  private static final int MAX_VERTEX_ARRAYS = 16_384;
  private static final int MAX_ATTRIBUTES = 31;
  private static final IrisGlVertexArrayTracker GLOBAL =
      new IrisGlVertexArrayTracker();

  private final LinkedHashMap<Integer, VertexArray> arrays =
      new LinkedHashMap<>(16, 0.75F, true);
  private final Map<Integer, Integer> targetBindings = new HashMap<>();
  private int currentVertexArray;
  private List<MojangVertexBuffer> mojangVertexBuffers;
  private String mojangVertexBufferFailure = "";

  public static IrisGlVertexArrayTracker global() {
    return GLOBAL;
  }

  public synchronized void reset() {
    arrays.clear();
    targetBindings.clear();
    currentVertexArray = 0;
    mojangVertexBuffers = null;
    mojangVertexBufferFailure = "";
    arrays.put(0, new VertexArray());
  }

  public synchronized void bindVertexArray(int vertexArray) {
    if (vertexArray < 0) {
      return;
    }
    currentVertexArray = vertexArray;
    mojangVertexBuffers = null;
    mojangVertexBufferFailure = "";
    arrays.computeIfAbsent(vertexArray, ignored -> new VertexArray());
    trimArrays();
  }

  public synchronized void deleteVertexArray(int vertexArray) {
    if (vertexArray <= 0) {
      return;
    }
    arrays.remove(vertexArray);
    if (currentVertexArray == vertexArray) {
      currentVertexArray = 0;
      arrays.computeIfAbsent(0, ignored -> new VertexArray());
    }
    mojangVertexBuffers = null;
    mojangVertexBufferFailure = "";
  }

  /** Records modern vertex-buffer bindings applied by Mojang's VAO cache. */
  public synchronized void bindMojangVertexBuffers(
      List<MojangVertexBuffer> buffers) {
    Objects.requireNonNull(buffers, "buffers");
    java.util.ArrayList<MojangVertexBuffer> copied =
        new java.util.ArrayList<>(buffers);
    copied.sort(java.util.Comparator.comparingInt(MojangVertexBuffer::slot));
    for (int index = 0; index < copied.size(); index++) {
      if (copied.get(index).slot() != index) {
        invalidateMojangVertexBuffers("mojang-vertex-buffer-slots-incomplete");
        return;
      }
    }
    mojangVertexBuffers = List.copyOf(copied);
    mojangVertexBufferFailure = "";
  }

  /** Retains an exact reason when the modern VAO binding cannot be mirrored. */
  public synchronized void invalidateMojangVertexBuffers(String reason) {
    Objects.requireNonNull(reason, "reason");
    if (reason.isBlank() || reason.length() > 96
        || reason.indexOf('\n') >= 0 || reason.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("invalid Mojang VAO failure");
    }
    mojangVertexBuffers = null;
    mojangVertexBufferFailure = reason;
  }

  public synchronized void bindBuffer(int target, int buffer) {
    if (target < 0 || buffer < 0) {
      return;
    }
    targetBindings.put(target, buffer);
    if (target == GL_ELEMENT_ARRAY_BUFFER) {
      current().elementBuffer = buffer;
    }
  }

  public synchronized int boundBuffer(int target) {
    if (target == GL_ELEMENT_ARRAY_BUFFER) {
      return current().elementBuffer;
    }
    return targetBindings.getOrDefault(target, 0);
  }

  public synchronized void vertexAttribute(int location, int size, int type,
      boolean normalized, int strideBytes, long offsetBytes,
      boolean integer) {
    if (location < 0 || location >= MAX_ATTRIBUTES || size <= 0 || size > 4
        || type < 0 || strideBytes < 0 || offsetBytes < 0) {
      return;
    }
    int buffer = targetBindings.getOrDefault(GL_ARRAY_BUFFER, 0);
    if (buffer <= 0) {
      return;
    }
    VertexArray vertexArray = current();
    mojangVertexBuffers = null;
    mojangVertexBufferFailure = "";
    Attribute prior = vertexArray.attributes.get(location);
    boolean enabled = prior != null && prior.enabled;
    vertexArray.attributes.put(location, new Attribute(buffer, size, type,
        normalized, strideBytes, offsetBytes, integer, enabled));
  }

  public synchronized void enableAttribute(int location) {
    if (location < 0 || location >= MAX_ATTRIBUTES) {
      return;
    }
    Attribute attribute = current().attributes.get(location);
    if (attribute != null) {
      attribute.enabled = true;
    }
  }

  public synchronized void deleteBuffer(int buffer) {
    if (buffer <= 0) {
      return;
    }
    targetBindings.replaceAll((target, bound) -> bound == buffer ? 0 : bound);
    for (VertexArray vertexArray : arrays.values()) {
      if (vertexArray.elementBuffer == buffer) {
        vertexArray.elementBuffer = 0;
      }
      vertexArray.attributes.entrySet().removeIf(
          entry -> entry.getValue().buffer == buffer);
    }
    if (mojangVertexBuffers != null && mojangVertexBuffers.stream()
        .anyMatch(vertex -> vertex.glBuffer() == buffer)) {
      invalidateMojangVertexBuffers("mojang-vertex-buffer-deleted");
    }
  }

  public synchronized IrisVertexInputBindings snapshot(
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    VertexArray vertexArray = current();
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    List<VertexBufferLayout> physicalLayouts = descriptor.vertexBuffers()
        .stream()
        .filter(layout -> layout.stepFunction()
            != IrisPipelineState.StepFunction.CONSTANT)
        .toList();
    if (mojangVertexBuffers != null) {
      return snapshotMojangBuffers(physicalLayouts, vertexArray);
    }
    if (!mojangVertexBufferFailure.isEmpty()) {
      return IrisVertexInputBindings.unavailable(mojangVertexBufferFailure);
    }
    java.util.ArrayList<IrisVertexInputBindings.BufferSlice> buffers =
        new java.util.ArrayList<>(physicalLayouts.size());
    for (VertexBufferLayout layout : physicalLayouts) {
      List<VertexAttribute> required = descriptor.vertexAttributes().stream()
          .filter(attribute -> attribute.bufferIndex()
              == layout.bufferIndex())
          .toList();
      if (required.isEmpty()) {
        return IrisVertexInputBindings.unavailable(
            "vao-buffer-has-no-attributes");
      }
      Integer glBuffer = null;
      for (VertexAttribute expected : required) {
        Attribute actual = vertexArray.attributes.get(expected.location());
        if (actual == null || !actual.enabled) {
          return IrisVertexInputBindings.unavailable(
              "vao-attribute-unavailable");
        }
        if (actual.offsetBytes != expected.offsetBytes()
            || actual.strideBytes != layout.strideBytes()) {
          return IrisVertexInputBindings.unavailable(
              "vao-attribute-layout-mismatch");
        }
        if (glBuffer == null) {
          glBuffer = actual.buffer;
        } else if (glBuffer.intValue() != actual.buffer) {
          return IrisVertexInputBindings.unavailable(
              "vao-buffer-alias-mismatch");
        }
      }
      long size = mirror.size(glBuffer);
      long generation = mirror.generation(glBuffer);
      if (size <= 0 || generation <= 0) {
        return IrisVertexInputBindings.unavailable(
            "vao-buffer-mirror-unavailable");
      }
      buffers.add(new IrisVertexInputBindings.BufferSlice(
          layout.bufferIndex(), glBuffer, 0, size, generation));
    }

    IrisVertexInputBindings.BufferSlice index = null;
    if (vertexArray.elementBuffer > 0) {
      long size = mirror.size(vertexArray.elementBuffer);
      long generation = mirror.generation(vertexArray.elementBuffer);
      if (size <= 0 || generation <= 0) {
        return IrisVertexInputBindings.unavailable(
            "vao-index-mirror-unavailable");
      }
      index = new IrisVertexInputBindings.BufferSlice(0,
          vertexArray.elementBuffer, 0, size, generation);
    }
    try {
      return IrisVertexInputBindings.complete(buffers, index);
    } catch (IllegalArgumentException invalid) {
      return IrisVertexInputBindings.unavailable(
          "vao-buffer-slots-incomplete");
    }
  }

  private IrisVertexInputBindings snapshotMojangBuffers(
      List<VertexBufferLayout> physicalLayouts, VertexArray vertexArray) {
    if (mojangVertexBuffers.size() != physicalLayouts.size()) {
      return IrisVertexInputBindings.unavailable(
          "mojang-vertex-buffer-count-mismatch");
    }
    java.util.ArrayList<IrisVertexInputBindings.BufferSlice> buffers =
        new java.util.ArrayList<>(physicalLayouts.size());
    for (int index = 0; index < physicalLayouts.size(); index++) {
      VertexBufferLayout layout = physicalLayouts.get(index);
      MojangVertexBuffer bound = mojangVertexBuffers.get(index);
      if (layout.bufferIndex() != bound.slot()
          || layout.strideBytes() != bound.strideBytes()) {
        return IrisVertexInputBindings.unavailable(
            "mojang-vertex-buffer-layout-mismatch");
      }
      buffers.add(new IrisVertexInputBindings.BufferSlice(bound.slot(),
          bound.glBuffer(), bound.offsetBytes(), bound.lengthBytes(),
          bound.mirrorGeneration()));
    }
    IrisVertexInputBindings.BufferSlice index = indexBuffer(vertexArray);
    if (vertexArray.elementBuffer > 0 && index == null) {
      return IrisVertexInputBindings.unavailable(
          "vao-index-mirror-unavailable");
    }
    return IrisVertexInputBindings.complete(buffers, index);
  }

  private static IrisVertexInputBindings.BufferSlice indexBuffer(
      VertexArray vertexArray) {
    if (vertexArray.elementBuffer <= 0) {
      return null;
    }
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    long size = mirror.size(vertexArray.elementBuffer);
    long generation = mirror.generation(vertexArray.elementBuffer);
    if (size <= 0 || generation <= 0) {
      return null;
    }
    return new IrisVertexInputBindings.BufferSlice(0,
        vertexArray.elementBuffer, 0, size, generation);
  }

  private VertexArray current() {
    VertexArray vertexArray = arrays.computeIfAbsent(currentVertexArray,
        ignored -> new VertexArray());
    trimArrays();
    return vertexArray;
  }

  private void trimArrays() {
    while (arrays.size() > MAX_VERTEX_ARRAYS) {
      Integer victim = arrays.keySet().stream()
          .filter(vertexArray -> vertexArray != currentVertexArray)
          .findFirst().orElse(null);
      if (victim == null) {
        return;
      }
      arrays.remove(victim);
    }
  }

  private static final class VertexArray {
    private final Map<Integer, Attribute> attributes = new HashMap<>();
    private int elementBuffer;
  }

  private static final class Attribute {
    private final int buffer;
    private final int size;
    private final int type;
    private final boolean normalized;
    private final int strideBytes;
    private final long offsetBytes;
    private final boolean integer;
    private boolean enabled;

    private Attribute(int buffer, int size, int type, boolean normalized,
        int strideBytes, long offsetBytes, boolean integer,
        boolean enabled) {
      this.buffer = buffer;
      this.size = size;
      this.type = type;
      this.normalized = normalized;
      this.strideBytes = strideBytes;
      this.offsetBytes = offsetBytes;
      this.integer = integer;
      this.enabled = enabled;
    }
  }

  /** One exact logical binding installed through {@code VertexArrayCache}. */
  public record MojangVertexBuffer(int slot, int glBuffer, long offsetBytes,
                                   long lengthBytes, long mirrorGeneration,
                                   int strideBytes) {
    public MojangVertexBuffer {
      if (slot < 0 || slot >= IrisPipelineState.MAX_VERTEX_BUFFERS
          || glBuffer <= 0 || offsetBytes < 0 || lengthBytes <= 0
          || mirrorGeneration < 0 || strideBytes <= 0) {
        throw new IllegalArgumentException("invalid Mojang vertex buffer");
      }
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapping;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded bridge for Sodium 0.6.13's direct OpenGL render-device path. */
public final class IrisSodiumGlStateBridge {
  private static final ConcurrentHashMap<Integer, MappingRange> MAPPINGS =
      new ConcurrentHashMap<>();
  private static final ThreadLocal<Integer> PRIMITIVE_MODE =
      new ThreadLocal<>();

  private IrisSodiumGlStateBridge() {
  }

  public static void bindVertexArray(int vertexArray) {
    IrisGlVertexArrayTracker.global().bindVertexArray(vertexArray);
  }

  public static void deleteVertexArray(int vertexArray) {
    IrisGlVertexArrayTracker.global().deleteVertexArray(vertexArray);
  }

  public static void bindBuffer(int target, int buffer) {
    IrisGlVertexArrayTracker.global().bindBuffer(target, buffer);
  }

  public static void deleteBuffer(int buffer) {
    IrisGlVertexArrayTracker.global().deleteBuffer(buffer);
    MAPPINGS.remove(buffer);
    IrisGlBufferMirror.global().delete(buffer);
  }

  public static void allocate(int buffer, long bytes) {
    if (buffer > 0 && IrisGlBufferMirror.isEnabled()) {
      IrisGlBufferMirror.global().allocate(buffer, bytes);
    }
  }

  public static void upload(int buffer, ByteBuffer bytes) {
    if (buffer <= 0 || bytes == null || !IrisGlBufferMirror.isEnabled()) {
      return;
    }
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    int size = Math.toIntExact(bytes.remaining());
    if (mirror.allocate(buffer, size)) {
      mirror.write(buffer, size, 0, size, bytes);
    }
  }

  public static void copy(GlBuffer source, GlBuffer destination,
      long readOffset, long writeOffset, long bytes) {
    if (!IrisGlBufferMirror.isEnabled() || source == null
        || destination == null || readOffset < 0 || writeOffset < 0
        || bytes <= 0 || bytes > Integer.MAX_VALUE) {
      return;
    }
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    // Persistent Sodium staging mappings can be written without an explicit
    // flush before the copy. Refresh the source mapping before taking the
    // mirror snapshot so region-arena uploads contain current mesh bytes.
    refresh(source.handle());
    long sourceGeneration = mirror.generation(source.handle());
    var snapshot = mirror.snapshot(source.handle(), sourceGeneration,
        readOffset, bytes).orElse(null);
    if (snapshot == null) {
      return;
    }
    mirror.write(destination.handle(), mirror.size(destination.handle()),
        writeOffset, bytes, ByteBuffer.wrap(snapshot.bytes()));
  }

  public static void mapped(GlBufferMapping mapping, long offset,
      long length) {
    if (!IrisGlBufferMirror.isEnabled() || mapping == null
        || offset < 0 || length <= 0 || length > Integer.MAX_VALUE) {
      return;
    }
    GlBuffer buffer = mapping.getBufferObject();
    MAPPINGS.put(buffer.handle(), new MappingRange(mapping, offset, length));
    refresh(buffer.handle());
  }

  public static void refreshMapping(GlBufferMapping mapping) {
    if (!IrisGlBufferMirror.isEnabled() || mapping == null) {
      return;
    }
    MappingRange range = MAPPINGS.get(mapping.getBufferObject().handle());
    if (range != null) {
      refresh(mapping.getBufferObject().handle(), range);
    }
  }

  public static void unmap(GlBufferMapping mapping) {
    if (mapping != null) {
      MAPPINGS.remove(mapping.getBufferObject().handle());
    }
  }

  public static void refresh(int buffer) {
    MappingRange range = MAPPINGS.get(buffer);
    if (range != null) {
      refresh(buffer, range);
    }
  }

  /**
   * Reconciles Sodium's state-cache with the actual GL context immediately
   * before a direct terrain submission. Sodium intentionally skips redundant
   * binds, so a Java-side observer cannot assume every bind call was replayed.
   */
  public static void synchronizeForDraw() {
    try {
      int program = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
      if (program > 0) {
        IrisPipelineStateCapture.global().useProgram(program);
      }

      int vao = GL30C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
      bindVertexArray(vao);

      int arrayBuffer =
          GL15C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
      bindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer);

      int elementBuffer =
          GL15C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
      bindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
    } catch (RuntimeException | LinkageError ignored) {
      // State synchronization is observational and fail-open.
    }
  }

  public static void refreshAllMappings() {
    if (!IrisGlBufferMirror.isEnabled()) {
      return;
    }
    for (Integer buffer : MAPPINGS.keySet()) {
      if (buffer != null && buffer > 0) {
        refresh(buffer);
      }
    }
  }

  private static void refresh(int buffer, MappingRange range) {
    ByteBuffer bytes = range.mapping().getMemoryBuffer();
    if (bytes == null || range.offset() > Integer.MAX_VALUE
        || range.length() > bytes.capacity()
        || range.offset() < 0) {
      return;
    }
    ByteBuffer view = bytes.duplicate();
    view.position(0);
    view.limit(Math.toIntExact(range.length()));
    long totalSize = IrisGlBufferMirror.global().size(buffer);
    if (totalSize <= 0 || range.offset() > totalSize
        || range.length() > totalSize - range.offset()) {
      return;
    }
    IrisGlBufferMirror.global().write(buffer, totalSize, range.offset(),
        range.length(), view);
  }

  public static void beginTessellation(int primitiveMode) {
    PRIMITIVE_MODE.set(primitiveMode);
  }

  public static void endTessellation() {
    PRIMITIVE_MODE.remove();
  }

  public static int primitiveMode() {
    Integer value = PRIMITIVE_MODE.get();
    return value == null ? -1 : value;
  }

  private record MappingRange(GlBufferMapping mapping, long offset,
                              long length) {
  }
}

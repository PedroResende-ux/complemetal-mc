package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;

/** Bounded render-thread GL readback used only by opt-in shadow replay QA. */
final class IrisGlBufferReadback {
  private IrisGlBufferReadback() {
  }

  static Optional<byte[]> range(int glBuffer, long offsetBytes,
      long lengthBytes) {
    if (!IrisGlBufferMirror.isEnabled() || glBuffer <= 0
        || offsetBytes < 0 || lengthBytes <= 0
        || lengthBytes > IrisShadowReplayBufferSnapshot.MAX_TOTAL_BYTES
        || lengthBytes > Integer.MAX_VALUE) {
      return Optional.empty();
    }
    int target = GL31C.GL_COPY_READ_BUFFER;
    int previous = 0;
    try {
      previous = GL11C.glGetInteger(target);
      GL15C.glBindBuffer(target, glBuffer);
      long size = GL32C.glGetBufferParameteri64(target,
          GL15C.GL_BUFFER_SIZE);
      if (!rangeWithin(size, offsetBytes, lengthBytes)) {
        return Optional.empty();
      }
      ByteBuffer destination = ByteBuffer.allocateDirect(
          Math.toIntExact(lengthBytes)).order(ByteOrder.nativeOrder());
      GL15C.glGetBufferSubData(target, offsetBytes, destination);
      byte[] bytes = new byte[destination.capacity()];
      destination.position(0);
      destination.get(bytes);
      return Optional.of(bytes);
    } catch (RuntimeException | LinkageError unavailable) {
      return Optional.empty();
    } finally {
      try {
        GL15C.glBindBuffer(target, previous);
      } catch (RuntimeException | LinkageError ignored) {
        // The primary capture failure remains fail-closed.
      }
    }
  }

  static Optional<byte[]> whole(int glBuffer) {
    if (!IrisGlBufferMirror.isEnabled() || glBuffer <= 0) {
      return Optional.empty();
    }
    int target = GL31C.GL_COPY_READ_BUFFER;
    int previous = 0;
    try {
      previous = GL11C.glGetInteger(target);
      GL15C.glBindBuffer(target, glBuffer);
      long size = GL32C.glGetBufferParameteri64(target,
          GL15C.GL_BUFFER_SIZE);
      if (!rangeWithin(size, 0, size)
          || size > IrisShadowReplayBufferSnapshot.MAX_TOTAL_BYTES) {
        return Optional.empty();
      }
      ByteBuffer destination = ByteBuffer.allocateDirect(Math.toIntExact(size))
          .order(ByteOrder.nativeOrder());
      GL15C.glGetBufferSubData(target, 0, destination);
      byte[] bytes = new byte[destination.capacity()];
      destination.position(0);
      destination.get(bytes);
      return Optional.of(bytes);
    } catch (RuntimeException | LinkageError unavailable) {
      return Optional.empty();
    } finally {
      try {
        GL15C.glBindBuffer(target, previous);
      } catch (RuntimeException | LinkageError ignored) {
        // The primary capture failure remains fail-closed.
      }
    }
  }

  static boolean rangeWithin(long totalSize, long offsetBytes,
      long lengthBytes) {
    return totalSize > 0 && totalSize <= Integer.MAX_VALUE
        && offsetBytes >= 0 && lengthBytes > 0
        && offsetBytes <= totalSize
        && lengthBytes <= totalSize - offsetBytes;
  }
}

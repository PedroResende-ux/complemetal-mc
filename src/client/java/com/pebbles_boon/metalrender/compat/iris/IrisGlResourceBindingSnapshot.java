package com.pebbles_boon.metalrender.compat.iris;

import java.util.Map;
import java.util.Objects;

/** Immutable OpenGL resource bindings observed for the current Iris program. */
public record IrisGlResourceBindingSnapshot(
    int glProgram,
    Map<String, Integer> uniformLocations,
    Map<Integer, UniformValue> uniformValues,
    Map<String, Integer> uniformBlockIndices,
    Map<Integer, Integer> uniformBlockBindings,
    Map<Integer, TextureUnitBinding> textureUnits,
    Map<Integer, TextureBufferBinding> textureBuffers,
    Map<Integer, ImageUnitBinding> imageUnits,
    Map<IndexedBufferBinding, BufferBinding> indexedBuffers) {
  public IrisGlResourceBindingSnapshot {
    if (glProgram <= 0) {
      throw new IllegalArgumentException("GL program must be positive");
    }
    uniformLocations = Map.copyOf(uniformLocations);
    uniformValues = Map.copyOf(uniformValues);
    uniformBlockIndices = Map.copyOf(uniformBlockIndices);
    uniformBlockBindings = Map.copyOf(uniformBlockBindings);
    textureUnits = Map.copyOf(textureUnits);
    textureBuffers = Map.copyOf(textureBuffers);
    imageUnits = Map.copyOf(imageUnits);
    indexedBuffers = Map.copyOf(indexedBuffers);
  }

  public enum UniformValueKind {
    OPENGL_DEFAULT_ZERO,
    SIGNED_INT,
    UNSIGNED_INT,
    FLOAT
  }

  public record UniformValue(UniformValueKind kind, int columns, int rows,
                             long[] rawBits) {
    public UniformValue {
      Objects.requireNonNull(kind, "kind");
      if (columns <= 0 || columns > 4 || rows <= 0 || rows > 4) {
        throw new IllegalArgumentException("invalid uniform shape");
      }
      rawBits = Objects.requireNonNull(rawBits, "rawBits").clone();
      if (rawBits.length != columns * rows) {
        throw new IllegalArgumentException("uniform value size mismatch");
      }
    }

    @Override
    public long[] rawBits() {
      return rawBits.clone();
    }

    public static UniformValue defaultZero() {
      return new UniformValue(UniformValueKind.OPENGL_DEFAULT_ZERO, 1, 1,
          new long[] {0});
    }
  }

  public record TextureUnitBinding(int target, int texture, int sampler) {
    public TextureUnitBinding {
      if (target < 0 || texture < 0 || sampler < 0) {
        throw new IllegalArgumentException("invalid texture-unit binding");
      }
    }
  }

  /** Buffer storage attached to a GL_TEXTURE_BUFFER texture object. */
  public record TextureBufferBinding(int target, int internalFormat,
                                     int buffer) {
    public TextureBufferBinding {
      if (target < 0 || internalFormat < 0 || buffer < 0) {
        throw new IllegalArgumentException("invalid texture-buffer binding");
      }
    }
  }

  public record ImageUnitBinding(int texture, int level, boolean layered,
                                 int layer, int access, int format) {
    public ImageUnitBinding {
      if (texture < 0 || level < 0 || layer < 0) {
        throw new IllegalArgumentException("invalid image-unit binding");
      }
    }
  }

  public record IndexedBufferBinding(int target, int index) {
    public IndexedBufferBinding {
      if (target < 0 || index < 0) {
        throw new IllegalArgumentException("invalid indexed buffer binding");
      }
    }
  }

  /** Exact indexed GL buffer binding, including glBindBufferRange slices. */
  public record BufferBinding(int buffer, long offsetBytes, long sizeBytes,
                              boolean rangeBound) {
    public BufferBinding {
      if (buffer < 0 || offsetBytes < 0 || sizeBytes < 0) {
        throw new IllegalArgumentException("invalid indexed buffer range");
      }
      if (!rangeBound && (offsetBytes != 0 || sizeBytes != 0)) {
        throw new IllegalArgumentException(
            "base buffer binding cannot carry a range");
      }
      if (rangeBound && buffer > 0 && sizeBytes == 0) {
        throw new IllegalArgumentException(
            "non-zero range binding requires a positive size");
      }
    }

    public static BufferBinding base(int buffer) {
      return new BufferBinding(buffer, 0, 0, false);
    }

    public static BufferBinding range(int buffer, long offsetBytes,
                                      long sizeBytes) {
      return new BufferBinding(buffer, offsetBytes, sizeBytes, true);
    }
  }
}

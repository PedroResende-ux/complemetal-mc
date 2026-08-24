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
    Map<Integer, TextureUnitBinding> textureBufferUnits,
    Map<Integer, TextureBufferBinding> textureBuffers,
    Map<Integer, ImageUnitBinding> imageUnits,
    Map<IndexedBufferBinding, BufferBinding> indexedBuffers) {
  /** Compatibility constructor for snapshots with no target collision. */
  public IrisGlResourceBindingSnapshot(int glProgram,
      Map<String, Integer> uniformLocations,
      Map<Integer, UniformValue> uniformValues,
      Map<String, Integer> uniformBlockIndices,
      Map<Integer, Integer> uniformBlockBindings,
      Map<Integer, TextureUnitBinding> textureUnits,
      Map<Integer, TextureBufferBinding> textureBuffers,
      Map<Integer, ImageUnitBinding> imageUnits,
      Map<IndexedBufferBinding, BufferBinding> indexedBuffers) {
    this(glProgram, uniformLocations, uniformValues, uniformBlockIndices,
        uniformBlockBindings, textureUnits, Map.of(), textureBuffers,
        imageUnits, indexedBuffers);
  }

  public IrisGlResourceBindingSnapshot {
    if (glProgram <= 0) {
      throw new IllegalArgumentException("GL program must be positive");
    }
    uniformLocations = Map.copyOf(uniformLocations);
    uniformValues = Map.copyOf(uniformValues);
    uniformBlockIndices = Map.copyOf(uniformBlockIndices);
    uniformBlockBindings = Map.copyOf(uniformBlockBindings);
    textureUnits = Map.copyOf(textureUnits);
    textureBufferUnits = Map.copyOf(textureBufferUnits);
    textureBuffers = Map.copyOf(textureBuffers);
    imageUnits = Map.copyOf(imageUnits);
    indexedBuffers = Map.copyOf(indexedBuffers);
  }

  /**
   * Resolves the binding for the reflected sampler dimension. OpenGL retains
   * one name per target on a texture unit, so a sampler2D and samplerBuffer
   * may legally both use unit zero without referring to the same object.
   */
  public TextureUnitBinding sampledTextureBinding(int unit,
      boolean bufferTexture) {
    if (!bufferTexture) {
      return textureUnits.get(unit);
    }
    TextureUnitBinding binding = textureBufferUnits.get(unit);
    return binding != null ? binding : textureUnits.get(unit);
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

  public record TextureUnitBinding(int target, int texture, int sampler,
                                   long mirrorGeneration,
                                   long samplerGeneration) {
    public TextureUnitBinding(int target, int texture, int sampler) {
      this(target, texture, sampler, 0, 0);
    }

    public TextureUnitBinding(int target, int texture, int sampler,
        long mirrorGeneration) {
      this(target, texture, sampler, mirrorGeneration, 0);
    }

    public TextureUnitBinding {
      if (target < 0 || texture < 0 || sampler < 0
          || mirrorGeneration < 0 || samplerGeneration < 0) {
        throw new IllegalArgumentException("invalid texture-unit binding");
      }
    }

    public TextureUnitBinding withMirrorGeneration(long generation) {
      return new TextureUnitBinding(target, texture, sampler, generation,
          samplerGeneration);
    }

    public TextureUnitBinding withMirrorGenerations(long textureGeneration,
        long resolvedSamplerGeneration) {
      return new TextureUnitBinding(target, texture, sampler,
          textureGeneration, resolvedSamplerGeneration);
    }
  }

  /** Buffer storage attached to a GL_TEXTURE_BUFFER texture object. */
  public record TextureBufferBinding(int target, int internalFormat,
                                     int buffer, long mirrorGeneration) {
    public TextureBufferBinding(int target, int internalFormat, int buffer) {
      this(target, internalFormat, buffer, 0);
    }

    public TextureBufferBinding {
      if (target < 0 || internalFormat < 0 || buffer < 0
          || mirrorGeneration < 0) {
        throw new IllegalArgumentException("invalid texture-buffer binding");
      }
    }

    public TextureBufferBinding withMirrorGeneration(long generation) {
      return new TextureBufferBinding(target, internalFormat, buffer,
          generation);
    }
  }

  public record ImageUnitBinding(int texture, int level, boolean layered,
                                 int layer, int access, int format,
                                 long mirrorGeneration) {
    public ImageUnitBinding(int texture, int level, boolean layered,
        int layer, int access, int format) {
      this(texture, level, layered, layer, access, format, 0);
    }

    public ImageUnitBinding {
      if (texture < 0 || level < 0 || layer < 0 || mirrorGeneration < 0) {
        throw new IllegalArgumentException("invalid image-unit binding");
      }
    }

    public ImageUnitBinding withMirrorGeneration(long generation) {
      return new ImageUnitBinding(texture, level, layered, layer, access,
          format, generation);
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
                              boolean rangeBound, long mirrorGeneration) {
    public BufferBinding(int buffer, long offsetBytes, long sizeBytes,
        boolean rangeBound) {
      this(buffer, offsetBytes, sizeBytes, rangeBound, 0);
    }

    public BufferBinding {
      if (buffer < 0 || offsetBytes < 0 || sizeBytes < 0
          || mirrorGeneration < 0) {
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
      return new BufferBinding(buffer, 0, 0, false, 0);
    }

    public static BufferBinding range(int buffer, long offsetBytes,
                                      long sizeBytes) {
      return new BufferBinding(buffer, offsetBytes, sizeBytes, true, 0);
    }

    public BufferBinding withMirrorGeneration(long generation) {
      return new BufferBinding(buffer, offsetBytes, sizeBytes, rangeBound,
          generation);
    }
  }
}

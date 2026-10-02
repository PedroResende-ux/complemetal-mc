package com.pebbles_boon.metalrender.compat.iris;

import java.util.Objects;

/** Immutable draw/dispatch arguments captured before Iris submits OpenGL. */
public sealed interface IrisExecutionCommand {
  int MAX_MULTI_DRAW_COUNT = 4_096;

  enum Source {
    DIRECT_GL,
    MOJANG_COMMAND_ENCODER,
    INDIRECT_BUFFER,
    SODIUM_COMMAND_LIST,
    UNSPECIFIED
  }

  sealed interface Draw extends IrisExecutionCommand
      permits DrawArrays, DrawIndexed, MultiDrawIndexed, IndirectDraw,
      UnknownDraw {
    int primitiveMode();
  }

  record DrawArrays(int primitiveMode, int firstVertex, int vertexCount,
                    int instanceCount, int baseInstance,
                    Source source) implements Draw {
    public DrawArrays {
      requirePrimitive(primitiveMode);
      if (firstVertex < 0 || vertexCount < 0 || instanceCount <= 0
          || baseInstance < 0) {
        throw new IllegalArgumentException("invalid array draw arguments");
      }
      Objects.requireNonNull(source, "source");
    }
  }

  record DrawIndexed(int primitiveMode, long indexOffsetBytes,
                     int indexCount, int indexElementBytes, int baseVertex,
                     int instanceCount, int baseInstance,
                     Source source) implements Draw {
    public DrawIndexed {
      requirePrimitive(primitiveMode);
      if (indexOffsetBytes < 0 || indexCount < 0
          || (indexElementBytes != 1 && indexElementBytes != 2
          && indexElementBytes != 4)
          || instanceCount <= 0 || baseInstance < 0) {
        throw new IllegalArgumentException("invalid indexed draw arguments");
      }
      Objects.requireNonNull(source, "source");
    }
  }

  record MultiDrawIndexed(int primitiveMode, int indexElementBytes,
                          long[] indexOffsetsBytes, int[] indexCounts,
                          int[] baseVertices, Source source) implements Draw {
    public MultiDrawIndexed {
      requirePrimitive(primitiveMode);
      if (indexElementBytes != 1 && indexElementBytes != 2
          && indexElementBytes != 4) {
        throw new IllegalArgumentException("invalid multi-draw index type");
      }
      indexOffsetsBytes = Objects.requireNonNull(indexOffsetsBytes,
          "indexOffsetsBytes").clone();
      indexCounts = Objects.requireNonNull(indexCounts,
          "indexCounts").clone();
      baseVertices = Objects.requireNonNull(baseVertices,
          "baseVertices").clone();
      if (indexOffsetsBytes.length == 0
          || indexOffsetsBytes.length > MAX_MULTI_DRAW_COUNT
          || indexOffsetsBytes.length != indexCounts.length
          || indexOffsetsBytes.length != baseVertices.length) {
        throw new IllegalArgumentException("invalid multi-draw cardinality");
      }
      for (int index = 0; index < indexOffsetsBytes.length; index++) {
        if (indexOffsetsBytes[index] < 0 || indexCounts[index] < 0) {
          throw new IllegalArgumentException("invalid multi-draw element");
        }
      }
      Objects.requireNonNull(source, "source");
    }

    @Override
    public long[] indexOffsetsBytes() {
      return indexOffsetsBytes.clone();
    }

    @Override
    public int[] indexCounts() {
      return indexCounts.clone();
    }

    @Override
    public int[] baseVertices() {
      return baseVertices.clone();
    }
  }

  record IndirectDraw(int primitiveMode, int indexElementBytes,
                      int indirectGlBuffer, long offsetBytes, int drawCount,
                      Source source) implements Draw {
    public IndirectDraw {
      requirePrimitive(primitiveMode);
      if (indexElementBytes != 0 && indexElementBytes != 1
          && indexElementBytes != 2 && indexElementBytes != 4) {
        throw new IllegalArgumentException("invalid indirect index type");
      }
      if (indirectGlBuffer <= 0 || offsetBytes < 0 || drawCount <= 0
          || drawCount > MAX_MULTI_DRAW_COUNT) {
        throw new IllegalArgumentException("invalid indirect draw");
      }
      Objects.requireNonNull(source, "source");
    }
  }

  record UnknownDraw(int primitiveMode) implements Draw {
    public UnknownDraw {
      requirePrimitive(primitiveMode);
    }
  }

  record Dispatch(int groupsX, int groupsY, int groupsZ,
                   int localSizeX, int localSizeY, int localSizeZ)
      implements IrisExecutionCommand {
    public Dispatch(int groupsX, int groupsY, int groupsZ) {
      this(groupsX, groupsY, groupsZ, 1, 1, 1);
    }

    public Dispatch {
      if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0
          || localSizeX <= 0 || localSizeY <= 0 || localSizeZ <= 0
          || (long) localSizeX * localSizeY * localSizeZ > 1024L) {
        throw new IllegalArgumentException(
            "invalid compute dispatch/workgroup dimensions");
      }
    }
  }

  record IndirectDispatch(long offsetBytes)
      implements IrisExecutionCommand {
    public IndirectDispatch {
      if (offsetBytes < 0) {
        throw new IllegalArgumentException("negative indirect dispatch");
      }
    }
  }

  record UnknownDispatch() implements IrisExecutionCommand {
  }

  private static void requirePrimitive(int primitiveMode) {
    if (primitiveMode < 0) {
      throw new IllegalArgumentException("negative primitive mode");
    }
  }
}

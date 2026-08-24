package com.pebbles_boon.metalrender.compat.iris;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Live GL vertex/index buffer slices captured for an immediate shadow draw. */
public record IrisVertexInputBindings(List<BufferSlice> vertexBuffers,
                                      Optional<BufferSlice> indexBuffer,
                                      String incompleteReason) {
  public IrisVertexInputBindings {
    Objects.requireNonNull(vertexBuffers, "vertexBuffers");
    Objects.requireNonNull(indexBuffer, "indexBuffer");
    Objects.requireNonNull(incompleteReason, "incompleteReason");
    if (incompleteReason.length() > 96 || incompleteReason.indexOf('\n') >= 0
        || incompleteReason.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("invalid buffer capture reason");
    }
    ArrayList<BufferSlice> copy = new ArrayList<>(vertexBuffers);
    copy.sort(Comparator.comparingInt(BufferSlice::slot));
    for (int index = 0; index < copy.size(); index++) {
      if (copy.get(index).slot() != index) {
        throw new IllegalArgumentException(
            "vertex buffer slots must be dense from zero");
      }
    }
    vertexBuffers = List.copyOf(copy);
  }

  public static IrisVertexInputBindings unavailable(String reason) {
    return new IrisVertexInputBindings(List.of(), Optional.empty(),
        Objects.requireNonNull(reason, "reason"));
  }

  public static IrisVertexInputBindings complete(List<BufferSlice> vertices,
      BufferSlice index) {
    return new IrisVertexInputBindings(vertices, Optional.ofNullable(index),
        "");
  }

  public boolean complete() {
    return incompleteReason.isEmpty();
  }

  public record BufferSlice(int slot, int glBuffer, long offsetBytes,
                            long lengthBytes, long mirrorGeneration) {
    public BufferSlice(int slot, int glBuffer, long offsetBytes,
        long lengthBytes) {
      this(slot, glBuffer, offsetBytes, lengthBytes, 0);
    }

    public BufferSlice {
      if (slot < 0 || slot >= 31 || glBuffer <= 0 || offsetBytes < 0
          || lengthBytes <= 0 || mirrorGeneration < 0) {
        throw new IllegalArgumentException("invalid live GL buffer slice");
      }
    }
  }
}

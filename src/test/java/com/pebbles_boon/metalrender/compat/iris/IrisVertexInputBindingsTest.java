package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisVertexInputBindingsTest {
  @Test
  void sortsAndDefensivelyRetainsDenseLiveSlices() {
    IrisVertexInputBindings.BufferSlice second =
        new IrisVertexInputBindings.BufferSlice(1, 42, 64, 128);
    IrisVertexInputBindings.BufferSlice first =
        new IrisVertexInputBindings.BufferSlice(0, 41, 0, 64);
    IrisVertexInputBindings bindings = IrisVertexInputBindings.complete(
        List.of(second, first), null);
    assertTrue(bindings.complete());
    assertEquals(41, bindings.vertexBuffers().get(0).glBuffer());
  }

  @Test
  void rejectsSparseSlotsAndInvalidRanges() {
    assertThrows(IllegalArgumentException.class,
        () -> IrisVertexInputBindings.complete(List.of(
            new IrisVertexInputBindings.BufferSlice(1, 41, 0, 16)), null));
    assertThrows(IllegalArgumentException.class,
        () -> new IrisVertexInputBindings.BufferSlice(0, 0, 0, 16));
  }
}

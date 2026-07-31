package com.pebbles_boon.metalrender.render.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class CustomChunkMesherFaceBucketTest {
  private static final int VERTEX_STRIDE = 16;
  private static final int QUAD_BYTES = 4 * VERTEX_STRIDE;

  @Test
  void physicallyGroupsOpaqueQuadsIntoStableFaceRanges() {
    int[] sourceBuckets = {6, 1, 4, 1, 0};
    long[] sourceMarkers = {10L, 11L, 12L, 13L, 14L};
    ByteBuffer vertices = ByteBuffer
        .allocateDirect((sourceBuckets.length + 1) * QUAD_BYTES)
        .order(ByteOrder.nativeOrder());

    for (int quad = 0; quad < sourceBuckets.length; quad++) {
      for (int vertex = 0; vertex < 4; vertex++) {
        vertices.putLong(sourceMarkers[quad]);
        vertices.putLong(
            ((long) sourceBuckets[quad] << 56) | vertex);
      }
    }
    vertices.flip();

    int[] counts = CustomChunkMesher.bucketQuadsByFacing(
        vertices, sourceBuckets.length);

    assertArrayEquals(
        new int[] {1, 2, 0, 0, 1, 0, 1},
        java.util.Arrays.copyOf(counts, 7));
    int[] expectedBuckets = {0, 1, 1, 4, 6};
    long[] expectedMarkers = {14L, 11L, 13L, 12L, 10L};
    for (int quad = 0; quad < expectedBuckets.length; quad++) {
      int base = quad * QUAD_BYTES;
      assertEquals(expectedMarkers[quad], vertices.getLong(base));
      assertEquals(expectedBuckets[quad],
          (int) ((vertices.getLong(base + Long.BYTES) >>> 56) & 0xFF));
    }

    int opaqueBytes = sourceBuckets.length * QUAD_BYTES;
    assertEquals(opaqueBytes, vertices.position());
    assertEquals(opaqueBytes, vertices.limit());
    vertices.limit(vertices.capacity());
    for (int vertex = 0; vertex < 4; vertex++) {
      vertices.putLong(99L);
      vertices.putLong(vertex);
    }
    vertices.flip();
    assertEquals((sourceBuckets.length + 1) * QUAD_BYTES, vertices.limit());
    assertEquals(99L, vertices.getLong(opaqueBytes));
  }
}

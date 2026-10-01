package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IrisGlBufferMirrorTest {
  @Test
  void requiresCompleteRangeAndExactGeneration() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(4, 128, 64);
    assertTrue(mirror.write(7, 16, 4, 4,
        ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
    long firstGeneration = mirror.generation(7);

    assertTrue(mirror.snapshot(7, firstGeneration, 4, 4).isPresent());
    assertFalse(mirror.snapshot(7, firstGeneration, 0, 8).isPresent());
    assertTrue(mirror.write(7, 16, 0, 4,
        ByteBuffer.wrap(new byte[] {5, 6, 7, 8})));
    assertFalse(mirror.snapshot(7, firstGeneration, 4, 4).isPresent());
    long secondGeneration = mirror.generation(7);
    assertArrayEquals(new byte[] {5, 6, 7, 8, 1, 2, 3, 4},
        mirror.snapshot(7, secondGeneration, 0, 8).orElseThrow().bytes());
  }

  @Test
  void mirrorsOnlyInitializedGpuCopyRanges() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(4, 128, 64);
    assertTrue(mirror.write(1, 16, 0, 8,
        ByteBuffer.wrap(new byte[] {0, 1, 2, 3, 4, 5, 6, 7})));
    assertTrue(mirror.copy(1, 2, 2, 16, 4, 4));
    long generation = mirror.generation(2);
    assertArrayEquals(new byte[] {2, 3, 4, 5},
        mirror.snapshot(2, generation, 4, 4).orElseThrow().bytes());
    assertFalse(mirror.copy(1, 8, 3, 16, 0, 4));
  }

  @Test
  void mirrorsIrisShaderStorageZeroClearAndBumpsGeneration() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(4, 64, 32);
    assertTrue(mirror.write(7, 16, 0, 16,
        ByteBuffer.wrap(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16
        })));
    long before = mirror.generation(7);

    assertTrue(mirror.clearZero(7, 4, 8, 0x8229, 0x1903, 0x1400,
        new int[] {0}));
    long after = mirror.generation(7);
    assertTrue(after > before);
    assertFalse(mirror.snapshot(7, before, 0, 16).isPresent());
    assertArrayEquals(new byte[] {
        1, 2, 3, 4, 0, 0, 0, 0,
        0, 0, 0, 0, 13, 14, 15, 16
    }, mirror.snapshot(7, after, 0, 16).orElseThrow().bytes());
    assertFalse(mirror.clearZero(7, 0, 4, 0x8229, 0x1903, 0x1405,
        new int[] {0}));
  }

  @Test
  void evictionAndDeletionInvalidateSnapshots() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(2, 16, 8);
    assertTrue(mirror.write(1, 8, 0, 8, ByteBuffer.allocate(8)));
    long generation = mirror.generation(1);
    assertTrue(mirror.write(2, 8, 0, 8, ByteBuffer.allocate(8)));
    assertTrue(mirror.write(3, 8, 0, 8, ByteBuffer.allocate(8)));
    assertFalse(mirror.snapshot(1, generation, 0, 8).isPresent());
    assertEquals(1, mirror.status().evictions());
    mirror.delete(3);
    assertEquals(1, mirror.status().buffers());
  }

  @Test
  void wholeSnapshotsRequireEveryByteAndClearInvalidatesContextNames() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(2, 32, 16);
    assertTrue(mirror.write(4, 8, 0, 4,
        ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
    long partialGeneration = mirror.generation(4);
    assertFalse(mirror.snapshotWhole(4, partialGeneration).isPresent());
    assertTrue(mirror.write(4, 8, 4, 4,
        ByteBuffer.wrap(new byte[] {5, 6, 7, 8})));
    long completeGeneration = mirror.generation(4);
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
        mirror.snapshotWhole(4, completeGeneration).orElseThrow().bytes());
    mirror.clear();
    assertFalse(mirror.snapshotWhole(4, completeGeneration).isPresent());
    assertEquals(0, mirror.status().buffers());
    assertEquals(0, mirror.status().retainedBytes());
  }
}

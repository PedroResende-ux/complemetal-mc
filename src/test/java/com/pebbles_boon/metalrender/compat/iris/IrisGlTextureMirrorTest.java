package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IrisGlTextureMirrorTest {
  @Test
  void retainsFullUploadsAndCopyOnWriteSubRegions() {
    IrisGlTextureMirror mirror = new IrisGlTextureMirror(4, 256, 128);
    assertTrue(mirror.define(7, "rgba8-unorm", 4, 2, 1, 1, 4));
    byte[] original = sequence(32);
    assertTrue(mirror.write(7, 0, 0, 0, 0, 4, 2, 4,
        ByteBuffer.wrap(original)));
    long firstGeneration = mirror.generation(7);
    IrisGlTextureMirror.TextureSnapshot first = mirror.snapshot(7,
        firstGeneration, 0, 0).orElseThrow();

    byte[] patch = new byte[] {101, 102, 103, 104};
    assertTrue(mirror.write(7, 0, 0, 1, 1, 1, 1, 1,
        ByteBuffer.wrap(patch)));
    long secondGeneration = mirror.generation(7);
    assertFalse(mirror.snapshot(7, firstGeneration, 0, 0).isPresent());
    assertArrayEquals(original, first.bytes());
    byte[] expected = original.clone();
    System.arraycopy(patch, 0, expected, 20, 4);
    assertArrayEquals(expected,
        mirror.snapshot(7, secondGeneration, 0, 0).orElseThrow().bytes());
  }

  @Test
  void rejectsPartialInitializationAndCopiesCompleteImages() {
    IrisGlTextureMirror mirror = new IrisGlTextureMirror(4, 256, 128);
    assertTrue(mirror.define(8, "rgba8-unorm", 2, 1, 1, 1, 4));
    assertFalse(mirror.write(8, 0, 0, 1, 0, 1, 1, 1,
        ByteBuffer.wrap(new byte[4])));
    assertTrue(mirror.define(9, "rgba8-unorm", 4, 1, 1, 1, 4));
    assertTrue(mirror.write(9, 0, 0, 0, 0, 4, 1, 4,
        ByteBuffer.wrap(sequence(16))));
    assertTrue(mirror.copy(9, 8, 0, 1, 0, 0, 0, 2, 1));
    assertArrayEquals(new byte[] {4, 5, 6, 7, 8, 9, 10, 11},
        mirror.snapshot(8, mirror.generation(8), 0, 0)
            .orElseThrow().bytes());
  }

  private static byte[] sequence(int length) {
    byte[] values = new byte[length];
    for (int index = 0; index < length; index++) {
      values[index] = (byte) index;
    }
    return values;
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisGlBufferReadbackTest {
  @Test
  void validatesRangesWithoutOverflow() {
    assertTrue(IrisGlBufferReadback.rangeWithin(64, 16, 32));
    assertTrue(IrisGlBufferReadback.rangeWithin(64, 0, 64));
    assertFalse(IrisGlBufferReadback.rangeWithin(64, 48, 32));
    assertFalse(IrisGlBufferReadback.rangeWithin(Long.MAX_VALUE,
        Long.MAX_VALUE - 4, 8));
    assertFalse(IrisGlBufferReadback.rangeWithin(64, -1, 1));
  }
}

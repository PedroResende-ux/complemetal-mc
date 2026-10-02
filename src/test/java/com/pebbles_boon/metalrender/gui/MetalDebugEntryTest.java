package com.pebbles_boon.metalrender.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

final class MetalDebugEntryTest {
  @Test
  void onePointTwentyOnePointOneAdapterIsSafeNoOp() {
    assertDoesNotThrow(MetalDebugEntry::register);
    assertDoesNotThrow(() -> MetalDebugEntry.show(null));
  }
}

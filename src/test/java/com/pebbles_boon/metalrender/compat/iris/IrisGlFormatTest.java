package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisGlFormatTest {
  @Test
  void mapsExactPackColorAndDepthFormats() {
    assertEquals("rgba8-unorm",
        IrisGlFormat.cacheName(0x8058).orElseThrow());
    assertEquals("rgba16-float",
        IrisGlFormat.cacheName(0x881A).orElseThrow());
    assertEquals("d32-float",
        IrisGlFormat.cacheName(0x8CAC).orElseThrow());
    assertTrue(IrisGlFormat.cacheName(-1).isEmpty());
  }
}

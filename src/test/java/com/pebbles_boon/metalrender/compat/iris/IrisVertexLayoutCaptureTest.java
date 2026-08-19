package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class IrisVertexLayoutCaptureTest {
  @Test
  void gpuFormatNamesHaveStableCacheSpelling() {
    assertEquals("rgba16-float",
        IrisVertexLayoutCapture.formatCacheName("RGBA16_FLOAT"));
    assertEquals("d32-float-s8-uint",
        IrisVertexLayoutCapture.formatCacheName("D32_FLOAT_S8_UINT"));
  }
}

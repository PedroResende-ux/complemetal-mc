package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisGlTextureReadbackTest {
  @Test
  void mapsExactColorAndDepthStorageFormatsToGlReadbackTypes() {
    IrisGlTextureReadback.ReadFormat rgba16 =
        IrisGlTextureReadback.readFormat("rgba16-float").orElseThrow();
    assertEquals(0x1908, rgba16.externalFormat());
    assertEquals(0x140B, rgba16.type());
    assertEquals(8, rgba16.bytesPerPixel());
    IrisGlTextureReadback.ReadFormat packedHdr =
        IrisGlTextureReadback.readFormat("rg11b10-float").orElseThrow();
    assertEquals(0x1908, packedHdr.externalFormat());
    assertEquals(0x140B, packedHdr.type());
    assertEquals(8, packedHdr.bytesPerPixel());
    assertEquals("rgba16-float",
        IrisGlTextureReadback.captureFormat("rg11b10-float"));
    assertTrue(IrisGlTextureReadback.readFormat("rgba8-uint").isPresent());
    IrisGlTextureReadback.ReadFormat depth32 =
        IrisGlTextureReadback.readFormat("d32-float").orElseThrow();
    assertEquals(0x1902, depth32.externalFormat());
    assertEquals(0x1406, depth32.type());
    assertEquals(4, depth32.bytesPerPixel());
    assertTrue(IrisGlTextureReadback.readFormat(
        "d32-float-s8-uint").isPresent());
    assertTrue(IrisGlTextureReadback.readFormat("d24-unorm").isEmpty());
  }
}

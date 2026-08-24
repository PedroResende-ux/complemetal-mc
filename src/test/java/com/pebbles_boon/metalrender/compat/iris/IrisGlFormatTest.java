package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisGlFormatTest {
  @Test
  void acceptsOnlyByteExactRgba8Uploads() {
    assertEquals(4, IrisGlFormat.bytesPerPixel(0x8058).orElseThrow());
    assertEquals(4, IrisGlFormat.exactUploadBytesPerPixel(
        0x8058, 0x1908, 0x1401).orElseThrow());
    assertTrue(IrisGlFormat.exactUploadBytesPerPixel(
        0x8058, 0x1907, 0x1401).isEmpty());
    assertTrue(IrisGlFormat.exactUploadBytesPerPixel(
        0x8058, 0x1908, 0x1406).isEmpty());
  }

  @Test
  void distinguishesIntegerAndNormalizedUploadClasses() {
    assertEquals(8, IrisGlFormat.exactUploadBytesPerPixel(
        0x8D76, 0x8D99, 0x1403).orElseThrow());
    assertTrue(IrisGlFormat.exactUploadBytesPerPixel(
        0x8D76, 0x1908, 0x1403).isEmpty());
  }
}

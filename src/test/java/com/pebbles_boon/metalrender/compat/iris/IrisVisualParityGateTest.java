package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisVisualParityGateTest {
  @Test
  void requiresTheWholeBoundedValidationWindow() {
    IrisVisualParityGate gate = new IrisVisualParityGate(
        new IrisVisualParityGate.Thresholds(1, 0.0, 1.0, 3, 3));
    byte[] iris = rgba(4, 10);
    byte[] metal = iris.clone();
    metal[0]++;

    assertTrue(gate.compare(iris, metal, 2, 2).passed());
    assertTrue(gate.compare(iris, metal, 2, 2).passed());
    assertFalse(gate.status().validated());
    assertTrue(gate.compare(iris, metal, 2, 2).passed());
    assertTrue(gate.status().validated());
  }

  @Test
  void mismatchFailsClosedUntilTheWindowIsReset() {
    IrisVisualParityGate gate = new IrisVisualParityGate(
        new IrisVisualParityGate.Thresholds(0, 0.0, 0.0, 2, 2));
    byte[] iris = rgba(1, 0);
    byte[] different = rgba(1, 0);
    different[2] = 4;

    assertFalse(gate.compare(iris, different, 1, 1).passed());
    gate.compare(iris, iris, 1, 1);
    gate.compare(iris, iris, 1, 1);
    assertFalse(gate.status().validated());
    gate.reset();
    gate.compare(iris, iris, 1, 1);
    gate.compare(iris, iris, 1, 1);
    assertTrue(gate.status().validated());
  }

  @Test
  void rejectsUnboundedOrMalformedPayloads() {
    IrisVisualParityGate gate = new IrisVisualParityGate(
        new IrisVisualParityGate.Thresholds(0, 0.0, 0.0, 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> gate.compare(new byte[4], new byte[8], 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> gate.compare(new byte[0], new byte[0],
            IrisVisualParityGate.MAX_PIXELS, 2));
  }

  @Test
  void rowFlipDiagnosticReversesWholeRgbaRows() {
    byte[] metal = new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 14, 15, 16
    };

    assertArrayEquals(new byte[] {
        9, 10, 11, 12, 13, 14, 15, 16,
        1, 2, 3, 4, 5, 6, 7, 8
    }, IrisTranslationCoordinator.flipRgba8Rows(metal, 2, 2));
  }

  private static byte[] rgba(int pixels, int value) {
    byte[] result = new byte[pixels * 4];
    java.util.Arrays.fill(result, (byte) value);
    return result;
  }
}

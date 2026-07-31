package com.pebbles_boon.metalrender.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MetalWorldFrameGateTest {
  @Test
  void activeRendererWithoutEitherIrisPauseCanEncode() {
    assertTrue(MetalWorldFrameGate.canEncode(true, false, false));
  }

  @Test
  void inactiveRendererCannotEncode() {
    assertFalse(MetalWorldFrameGate.canEncode(false, false, false));
  }

  @Test
  void liveIrisCompatibilityModeCannotEncode() {
    assertFalse(MetalWorldFrameGate.canEncode(true, true, false));
  }

  @Test
  void appliedIrisPauseBlocksTheShaderDisableTransitionGap() {
    assertFalse(MetalWorldFrameGate.canEncode(true, false, true));
  }

  @Test
  void bothIrisSignalsRemainBlocked() {
    assertFalse(MetalWorldFrameGate.canEncode(true, true, true));
  }
}

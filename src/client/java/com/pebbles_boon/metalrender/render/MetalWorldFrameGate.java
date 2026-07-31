package com.pebbles_boon.metalrender.render;

/**
 * Keeps live Iris state and the renderer's applied compatibility latch on the
 * same side of the Metal frame boundary.
 */
public final class MetalWorldFrameGate {
  private MetalWorldFrameGate() {
  }

  public static boolean canEncode(boolean rendererActive,
      boolean liveIrisCompatibilityMode,
      boolean irisPauseApplied) {
    return rendererActive && !liveIrisCompatibilityMode &&
        !irisPauseApplied;
  }
}

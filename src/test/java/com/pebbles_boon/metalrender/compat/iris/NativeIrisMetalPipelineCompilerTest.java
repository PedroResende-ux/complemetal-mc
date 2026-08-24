package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NativeIrisMetalPipelineCompilerTest {
  @Test
  void defersInitialMetal3WhileMetal4WasRequested() {
    assertTrue(NativeIrisMetalPipelineCompiler.shouldDeferInactiveMetal4(
        "METAL3", true));
  }

  @Test
  void keepsProbePendingTransient() {
    assertTrue(NativeIrisMetalPipelineCompiler.shouldDeferInactiveMetal4(
        "METAL3_FALLBACK_METAL4_PROBE_PENDING", true));
  }

  @Test
  void leavesExplicitFallbackModesTerminal() {
    assertFalse(NativeIrisMetalPipelineCompiler.shouldDeferInactiveMetal4(
        "METAL3_FALLBACK_NO_METAL4", true));
    assertFalse(NativeIrisMetalPipelineCompiler.shouldDeferInactiveMetal4(
        "METAL3_FALLBACK_METAL4_PROBE_FAILED", true));
    assertFalse(NativeIrisMetalPipelineCompiler.shouldDeferInactiveMetal4(
        "METAL3", false));
  }
}

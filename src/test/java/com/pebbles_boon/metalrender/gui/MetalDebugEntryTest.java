package com.pebbles_boon.metalrender.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.MetalRenderClient;
import org.junit.jupiter.api.Test;

final class MetalDebugEntryTest {
  private static final String VERSION = "0.2.1+mc26.2";

  @Test
  void unloadedConfigReportsInitializationInsteadOfAFalseFailure() {
    var snapshot = new MetalDebugEntry.StatusSnapshot(
        false,
        false,
        MetalRenderClient.InitState.FAILED,
        "stale failure",
        false,
        true,
        true,
        true,
        false);

    assertEquals(MetalDebugEntry.Status.INITIALIZING,
        MetalDebugEntry.resolve(snapshot));
  }

  @Test
  void disabledConfigTakesPriorityOverRuntimeAndIrisState() {
    var snapshot = snapshot(false, MetalRenderClient.InitState.FAILED,
        "native failed", false, true, true, true, false);

    assertEquals(MetalDebugEntry.Status.DISABLED,
        MetalDebugEntry.resolve(snapshot));
  }

  @Test
  void pendingInitializationHasAnExplicitStatus() {
    for (MetalRenderClient.InitState state : new MetalRenderClient.InitState[] {
        MetalRenderClient.InitState.NOT_TRIED,
        MetalRenderClient.InitState.INITIALIZING }) {
      assertEquals(MetalDebugEntry.Status.INITIALIZING,
          MetalDebugEntry.resolve(snapshot(true, state, null,
              false, false, false, false, false)));
    }
  }

  @Test
  void terminalInitializationStatesReportVanillaFallback() {
    for (MetalRenderClient.InitState state : new MetalRenderClient.InitState[] {
        MetalRenderClient.InitState.FAILED,
        MetalRenderClient.InitState.UNSUPPORTED }) {
      assertEquals(MetalDebugEntry.Status.FALLBACK,
          MetalDebugEntry.resolve(snapshot(true, state, "reason",
              false, false, false, false, false)));
    }
  }

  @Test
  void readyRuntimeWithoutAWorldDoesNotReportFailure() {
    assertEquals(MetalDebugEntry.Status.NO_WORLD,
        MetalDebugEntry.resolve(snapshot(true,
            MetalRenderClient.InitState.READY, null,
            true, true, false, false, false)));
  }

  @Test
  void appliedIrisPauseReportsTheSafeOpenGlPath() {
    assertEquals(MetalDebugEntry.Status.IRIS_PAUSED,
        MetalDebugEntry.resolve(snapshot(true,
            MetalRenderClient.InitState.READY, null,
            true, true, true, true, false)));
  }

  @Test
  void fullyReadyRendererReportsActive() {
    assertEquals(MetalDebugEntry.Status.ACTIVE,
        MetalDebugEntry.resolve(snapshot(true,
            MetalRenderClient.InitState.READY, null,
            true, true, true, false, true)));
  }

  @Test
  void brokenReadyInvariantReportsFallback() {
    assertEquals(MetalDebugEntry.Status.FALLBACK,
        MetalDebugEntry.resolve(snapshot(true,
            MetalRenderClient.InitState.READY, null,
            false, true, true, false, false)));
    assertEquals(MetalDebugEntry.Status.FALLBACK,
        MetalDebugEntry.resolve(snapshot(true,
            MetalRenderClient.InitState.READY, null,
            true, true, true, false, false)));
  }

  @Test
  void formattedLineIsDeterministicAndExplainsIris() {
    var snapshot = snapshot(true, MetalRenderClient.InitState.READY, null,
        true, true, true, true, false);
    String first = MetalDebugEntry.format(
        MetalDebugEntry.resolve(snapshot), snapshot, VERSION);
    String second = MetalDebugEntry.format(
        MetalDebugEntry.resolve(snapshot), snapshot, VERSION);

    assertEquals(first, second);
    assertTrue(first.contains("Complemetal " + VERSION));
    assertTrue(first.contains("Iris/OpenGL compatibility"));
    assertFalse(first.toLowerCase().contains("concoction"));
  }

  @Test
  void fallbackReasonIsBoundedForTheDebugOverlay() {
    String failure = "native failure ".repeat(20);
    var snapshot = snapshot(true, MetalRenderClient.InitState.FAILED,
        failure, false, false, false, false, false);
    String line = MetalDebugEntry.format(MetalDebugEntry.Status.FALLBACK,
        snapshot, VERSION);

    assertTrue(line.contains("vanilla fallback - native failure"));
    assertTrue(line.endsWith("..."));
    assertTrue(line.length() < 160);
  }

  private static MetalDebugEntry.StatusSnapshot snapshot(
      boolean configEnabled,
      MetalRenderClient.InitState initState,
      String initFailure,
      boolean runtimeEnabled,
      boolean worldRendererPresent,
      boolean worldLoaded,
      boolean irisPauseApplied,
      boolean rendererReady) {
    return new MetalDebugEntry.StatusSnapshot(
        true,
        configEnabled,
        initState,
        initFailure,
        runtimeEnabled,
        worldRendererPresent,
        worldLoaded,
        irisPauseApplied,
        rendererReady);
  }
}

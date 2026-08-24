package com.pebbles_boon.metalrender.display;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class DisplayLifecycleTrackerTest {
  @AfterEach
  void resetTracker() {
    DisplayLifecycleTracker.reset();
  }

  @Test
  void initialObservationDoesNotResetPresentation() {
    var transition = DisplayLifecycleTracker.analyze(null, state(100, 1, 2,
        960, 540, 1920, 1080, 2.0F, 200, true, false));

    assertTrue(transition.initialized());
    assertFalse(transition.requiresPresentationReset());
    assertFalse(transition.requiresRuntimeRefresh());
  }

  @Test
  void monitorAndBackingMigrationRequiresSafeReset() {
    var previous = state(100, 1, 2, 960, 540, 960, 540, 1.0F, 200,
        true, false);
    var current = state(200, 3, 4, 960, 540, 1920, 1080, 2.0F, 120,
        true, false);

    var transition = DisplayLifecycleTracker.analyze(previous, current);

    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.MONITOR_TOPOLOGY));
    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.MONITOR));
    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.FRAMEBUFFER_SIZE));
    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.CONTENT_SCALE));
    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.REFRESH_RATE));
    assertTrue(transition.requiresPresentationReset());
    assertTrue(transition.requiresRuntimeRefresh());
  }

  @Test
  void refreshOnlyUpdatesRuntimeWithoutDiscardingSurface() {
    var previous = state(100, 1, 2, 960, 540, 960, 540, 1.0F, 120,
        true, false);
    var current = state(200, 1, 2, 960, 540, 960, 540, 1.0F, 200,
        true, false);

    var transition = DisplayLifecycleTracker.analyze(previous, current);

    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.REFRESH_RATE));
    assertFalse(transition.requiresPresentationReset());
    assertTrue(transition.requiresRuntimeRefresh());
  }

  @Test
  void longTickGapCreatesWakeBoundary() {
    var previous = state(100, 1, 2, 960, 540, 960, 540, 1.0F, 200,
        true, false);
    var current = state(100 + DisplayLifecycleTracker.RESUME_GAP_MILLIS,
        1, 2, 960, 540, 960, 540, 1.0F, 200, true, false);

    var transition = DisplayLifecycleTracker.analyze(previous, current);

    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.RESUME_GAP));
    assertTrue(transition.requiresPresentationReset());
    assertTrue(transition.requiresRuntimeRefresh());
  }

  @Test
  void backwardClockCorrectionDoesNotCreateResumeBoundary() {
    var previous = state(10_000, 1, 2, 960, 540, 960, 540, 1.0F, 200,
        true, false);
    var current = state(1_000, 1, 2, 960, 540, 960, 540, 1.0F, 200,
        true, false);

    var transition = DisplayLifecycleTracker.analyze(previous, current);

    assertFalse(transition.changes().contains(
        DisplayLifecycleTracker.Change.RESUME_GAP));
    assertFalse(transition.changed());
  }

  @Test
  void visibilityAndIconifyTransitionsResetPresentation() {
    var previous = state(100, 1, 2, 960, 540, 960, 540, 1.0F, 200,
        true, false);
    var current = state(200, 1, 2, 960, 540, 960, 540, 1.0F, 200,
        false, true);

    var transition = DisplayLifecycleTracker.analyze(previous, current);

    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.VISIBILITY));
    assertTrue(transition.changes().contains(
        DisplayLifecycleTracker.Change.ICONIFIED));
    assertTrue(transition.requiresPresentationReset());
  }

  private static DisplayLifecycleTracker.DisplayState state(long time,
      long topology, long monitor, int windowWidth, int windowHeight,
      int framebufferWidth, int framebufferHeight, float scale,
      int refreshRate, boolean visible, boolean iconified) {
    return new DisplayLifecycleTracker.DisplayState(time, 10, topology,
        monitor, "test-monitor", 0, 0, windowWidth, windowHeight,
        framebufferWidth, framebufferHeight, scale, scale, refreshRate,
        false, visible, iconified, true);
  }
}

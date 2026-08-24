package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisDynamicDrawStateTrackerTest {
  @Test
  void remainsIncompleteUntilTheContextDependentViewportIsObserved() {
    IrisDynamicDrawStateTracker tracker =
        new IrisDynamicDrawStateTracker();
    tracker.initializeOpenGlDefaults();
    assertFalse(tracker.snapshot().completeFor(
        IrisGlStateSnapshot.Operation.DRAW));
    assertTrue(tracker.snapshot().completeFor(
        IrisGlStateSnapshot.Operation.DISPATCH));

    tracker.viewport(2, 3, 1280, 720);
    assertTrue(tracker.snapshot().completeFor(
        IrisGlStateSnapshot.Operation.DRAW));
    assertEquals(1280, tracker.snapshot().viewport().value().width());
  }

  @Test
  void enabledScissorRequiresAnObservedRectangleAfterReset() {
    IrisDynamicDrawStateTracker tracker =
        new IrisDynamicDrawStateTracker();
    tracker.resetContext();
    tracker.viewport(0, 0, 64, 64);
    tracker.scissorEnabled(true);
    assertFalse(tracker.snapshot().completeFor(
        IrisGlStateSnapshot.Operation.DRAW));
    tracker.scissor(4, 5, 20, 21);
    assertTrue(tracker.snapshot().completeFor(
        IrisGlStateSnapshot.Operation.DRAW));
  }
}

package com.pebbles_boon.metalrender.display;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class DisplayPresentationTrackerTest {
  @AfterEach
  void resetTracker() {
    DisplayPresentationTracker.reset();
  }

  @Test
  void recordsCompletedWindowPresentationCadence() {
    DisplayPresentationTracker.record(77, 1_000, 2_000);
    DisplayPresentationTracker.record(77, 6_000, 7_000);
    DisplayPresentationTracker.record(77, 11_000, 12_000);

    var snapshot = DisplayPresentationTracker.snapshot();

    assertEquals(3, snapshot.totalPresentCalls());
    assertEquals(3, snapshot.sequencePresentCalls());
    assertEquals(0, snapshot.invalidSamples());
    assertEquals(77, snapshot.windowHandle());
    assertArrayEquals(new long[] {5_000, 5_000},
        snapshot.completionIntervalsNanos());
    assertArrayEquals(new long[] {1_000, 1_000, 1_000},
        snapshot.presentDurationsNanos());
    assertTrue(snapshot.measuredPresentsPerSecond() > 0.0);
  }

  @Test
  void rejectsImpossibleTimestamps() {
    DisplayPresentationTracker.record(0, 1_000, 2_000);
    DisplayPresentationTracker.record(7, 2_000, 2_000);

    var snapshot = DisplayPresentationTracker.snapshot();

    assertEquals(0, snapshot.totalPresentCalls());
    assertEquals(2, snapshot.invalidSamples());
  }

  @Test
  void windowReplacementStartsANewCadenceSequence() {
    DisplayPresentationTracker.record(7, 1_000, 2_000);
    DisplayPresentationTracker.record(7, 6_000, 7_000);
    DisplayPresentationTracker.record(8, 10_000, 11_000);

    var snapshot = DisplayPresentationTracker.snapshot();

    assertEquals(3, snapshot.totalPresentCalls());
    assertEquals(1, snapshot.sequencePresentCalls());
    assertEquals(8, snapshot.windowHandle());
    assertArrayEquals(new long[0], snapshot.completionIntervalsNanos());
    assertArrayEquals(new long[] {1_000}, snapshot.presentDurationsNanos());
    assertEquals(0.0, snapshot.measuredPresentsPerSecond());
  }
}

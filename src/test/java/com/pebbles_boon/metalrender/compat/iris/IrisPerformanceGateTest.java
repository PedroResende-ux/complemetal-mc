package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class IrisPerformanceGateTest {
  private static final String SCENARIO = "b".repeat(64);

  @Test
  void passesOnlyComparableSamplesWithSustainedBenefit() {
    IrisPerformanceGate gate = new IrisPerformanceGate(5, 0.10, 0.10);
    for (int frame = 0; frame < 5; frame++) {
      gate.add(IrisPerformanceGate.Side.OPENGL, SCENARIO,
          10_000_000L + frame * 100_000L, 8_000_000L, false);
      gate.add(IrisPerformanceGate.Side.METAL, SCENARIO,
          8_000_000L + frame * 100_000L, 6_000_000L, false);
    }
    IrisPerformanceGate.Result result = gate.evaluate(true, true);
    assertEquals(IrisPerformanceGate.State.PASSED, result.state());
    assertEquals(5, result.openGl().samples());
  }

  @Test
  void rejectsVisualLifecycleScenarioAndStutterRegressions() {
    IrisPerformanceGate scenario = new IrisPerformanceGate(1, 0.0, 0.0);
    scenario.add(IrisPerformanceGate.Side.OPENGL, SCENARIO, 10, 8, false);
    scenario.add(IrisPerformanceGate.Side.METAL, "c".repeat(64), 8, 7,
        false);
    assertEquals("scenario-mismatch", scenario.evaluate(true, true).reason());

    IrisPerformanceGate visual = populated(false, false);
    assertEquals("visual-parity-not-passed",
        visual.evaluate(false, true).reason());
    assertEquals("lifecycle-not-passed",
        visual.evaluate(true, false).reason());

    IrisPerformanceGate stutter = populated(false, true);
    assertEquals("benefit-gate-not-met",
        stutter.evaluate(true, true).reason());
  }

  @Test
  void boundsAndSeparatesEachSampleSeries() {
    IrisPerformanceGate gate = new IrisPerformanceGate(1, 0.0, 0.0);
    gate.add(IrisPerformanceGate.Side.OPENGL, SCENARIO, 10, 0, false);
    assertThrows(IllegalStateException.class,
        () -> gate.add(IrisPerformanceGate.Side.OPENGL, SCENARIO, 9, 0,
            false));
    assertEquals(IrisPerformanceGate.State.PENDING,
        gate.evaluate(true, true).state());
  }

  @Test
  void rejectsCpuOrGpuTailRegressionDespiteMedianBenefit() {
    IrisPerformanceGate gate = new IrisPerformanceGate(
        2, 0.10, 0.10, 0.05);
    gate.add(IrisPerformanceGate.Side.OPENGL, SCENARIO, 10, 8, false);
    gate.add(IrisPerformanceGate.Side.OPENGL, SCENARIO, 12, 9, false);
    gate.add(IrisPerformanceGate.Side.METAL, SCENARIO, 8, 7, false);
    gate.add(IrisPerformanceGate.Side.METAL, SCENARIO, 10, 12, false);

    IrisPerformanceGate.Result result = gate.evaluate(true, true);
    assertEquals(IrisPerformanceGate.State.REJECTED, result.state());
    assertEquals("benefit-gate-not-met", result.reason());
  }

  private static IrisPerformanceGate populated(boolean baselineStutter,
      boolean metalStutter) {
    IrisPerformanceGate gate = new IrisPerformanceGate(1, 0.1, 0.1);
    gate.add(IrisPerformanceGate.Side.OPENGL, SCENARIO, 10, 8,
        baselineStutter);
    gate.add(IrisPerformanceGate.Side.METAL, SCENARIO, 8, 6,
        metalStutter);
    return gate;
  }
}

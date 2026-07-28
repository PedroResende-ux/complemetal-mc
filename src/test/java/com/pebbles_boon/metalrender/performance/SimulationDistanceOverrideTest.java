package com.pebbles_boon.metalrender.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SimulationDistanceOverrideTest {
  @Test
  void keepsPreferredDistanceWhileApplyingTemporaryCap() {
    SimulationDistanceOverride override = new SimulationDistanceOverride(5);

    assertEquals(5, override.enable(16));
    assertTrue(override.isActive());
    assertEquals(16, override.preferredOr(5));
    assertEquals(5, override.liveOr(16));

    assertEquals(16, override.disable(5));
    assertFalse(override.isActive());
  }

  @Test
  void tracksIntentionalChangesMadeWhileOverrideIsActive() {
    SimulationDistanceOverride override = new SimulationDistanceOverride(5);
    override.enable(12);

    assertEquals(5, override.enable(20));
    assertEquals(20, override.preferredOr(5));
    assertEquals(20, override.disable(5));
  }

  @Test
  void settingsCanUpdateOrDisableTheOverride() {
    SimulationDistanceOverride override = new SimulationDistanceOverride(5);

    assertEquals(5, override.setPreferred(24, true));
    assertEquals(24, override.preferredOr(5));
    assertEquals(10, override.setPreferred(10, false));
    assertFalse(override.isActive());
  }
}

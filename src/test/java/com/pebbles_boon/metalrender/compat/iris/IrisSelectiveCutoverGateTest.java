package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class IrisSelectiveCutoverGateTest {
  private static final String PIPELINE = "a".repeat(64);

  @Test
  void suppressesOpenGlOnlyAfterCurrentFrameMetalEncodeSucceeds() {
    IrisSelectiveCutoverGate gate = new IrisSelectiveCutoverGate(
        Set.of(IrisRenderGraph.Phase.FINAL));
    gate.beginFrame(1, 7);
    assertFalse(gate.beforeDraw(IrisRenderGraph.Phase.FINAL, PIPELINE,
        true, true).candidate());
    gate.arm(true);
    IrisSelectiveCutoverGate.Ticket ticket = gate.beforeDraw(
        IrisRenderGraph.Phase.FINAL, PIPELINE, true, true);
    assertTrue(ticket.candidate());
    assertTrue(gate.metalEncodeCompleted(ticket, true, true));
    assertEquals(IrisSelectiveCutoverGate.Mode.ACTIVE,
        gate.status().mode());
    assertEquals(1, gate.status().openGlSuppressions());
  }

  @Test
  void encodeFailureFallsBackForTheRestOfTheFrame() {
    IrisSelectiveCutoverGate gate = new IrisSelectiveCutoverGate(
        Set.of(IrisRenderGraph.Phase.FINAL));
    gate.beginFrame(1, 3);
    gate.arm(true);
    IrisSelectiveCutoverGate.Ticket failed = gate.beforeDraw(
        IrisRenderGraph.Phase.FINAL, PIPELINE, true, true);
    assertFalse(gate.metalEncodeCompleted(failed, false, false));
    assertTrue(gate.status().frameFallback());
    assertFalse(gate.beforeDraw(IrisRenderGraph.Phase.FINAL, PIPELINE,
        true, true).candidate());

    gate.beginFrame(2, 3);
    assertTrue(gate.beforeDraw(IrisRenderGraph.Phase.FINAL, PIPELINE,
        true, true).candidate());
  }

  @Test
  void lifecycleChangeReturnsToShadowAndRejectsStaleTickets() {
    IrisSelectiveCutoverGate gate = new IrisSelectiveCutoverGate(
        Set.of(IrisRenderGraph.Phase.FINAL));
    gate.beginFrame(1, 1);
    gate.arm(true);
    IrisSelectiveCutoverGate.Ticket stale = gate.beforeDraw(
        IrisRenderGraph.Phase.FINAL, PIPELINE, true, true);
    gate.beginFrame(2, 2);
    assertEquals(IrisSelectiveCutoverGate.Mode.SHADOW,
        gate.status().mode());
    assertEquals(1, gate.status().lifecycleResets());
    assertThrows(IllegalStateException.class,
        () -> gate.metalEncodeCompleted(stale, true, true));
  }
}

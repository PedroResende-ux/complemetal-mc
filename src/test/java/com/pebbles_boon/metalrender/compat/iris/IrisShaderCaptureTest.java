package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class IrisShaderCaptureTest {
  @Test
  void graphicsBeginUsesTheBoundedCaptureQueue() {
    IrisShaderCaptureQueue queue =
        new IrisShaderCaptureQueue(2, 100, 200);

    IrisShaderCapture.captureGraphicsBegin(queue, "final", "vertex", null,
        "fragment");

    IrisFinalShaderProgram program = queue.poll().orElseThrow().program();
    assertEquals("final", program.programName());
    assertEquals("vertex", program.source(IrisShaderStage.VERTEX));
    assertEquals("fragment", program.source(IrisShaderStage.FRAGMENT));
    assertFalse(program.hasStage(IrisShaderStage.GEOMETRY));
    assertFalse(queue.poll().isPresent());
  }

  @Test
  void computeBeginUsesTheSameBoundedCaptureQueue() {
    IrisShaderCaptureQueue queue =
        new IrisShaderCaptureQueue(1, 100, 100);

    IrisShaderCapture.captureComputeBegin(queue, "shadowcomp", "compute");

    IrisFinalShaderProgram program = queue.poll().orElseThrow().program();
    assertEquals("shadowcomp", program.programName());
    assertEquals("compute", program.source(IrisShaderStage.COMPUTE));
    assertEquals(1, program.sources().size());
    assertFalse(queue.poll().isPresent());
  }

  @Test
  void graphicsCaptureRemainsCountBounded() {
    IrisShaderCaptureQueue queue =
        new IrisShaderCaptureQueue(1, 100, 100);

    IrisShaderCapture.captureGraphicsBegin(queue, "first", "v", null, "f");
    IrisShaderCapture.captureGraphicsBegin(queue, "second", "v", null, "f");

    assertEquals(1, queue.size());
    assertEquals(1, queue.rejectedPrograms());
  }
}

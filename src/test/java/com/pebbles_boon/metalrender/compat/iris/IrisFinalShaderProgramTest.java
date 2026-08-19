package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisFinalShaderProgramTest {
  @Test
  void programBuilderGraphicsFactoryRetainsOnlySuppliedGraphicsStages() {
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromProgramBuilderGraphics("composite", "v",
            "g", "f");

    assertEquals("composite", program.programName());
    assertEquals("v", program.source(IrisShaderStage.VERTEX));
    assertEquals("g", program.source(IrisShaderStage.GEOMETRY));
    assertEquals("f", program.source(IrisShaderStage.FRAGMENT));
    assertFalse(program.hasStage(IrisShaderStage.TESS_CONTROL));
    assertFalse(program.hasStage(IrisShaderStage.TESS_EVALUATION));
    assertFalse(program.hasStage(IrisShaderStage.COMPUTE));
    assertEquals(3, program.retainedChars() - program.programName().length());
  }

  @Test
  void programBuilderComputeFactoryRetainsOnlyComputeStage() {
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromProgramBuilderCompute("shadowcomp", "c");

    assertEquals("shadowcomp", program.programName());
    assertEquals("c", program.source(IrisShaderStage.COMPUTE));
    assertTrue(program.hasStage(IrisShaderStage.COMPUTE));
    assertNull(program.source(IrisShaderStage.VERTEX));
    assertEquals(1, program.sources().size());
  }
}

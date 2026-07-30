package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisShaderCacheKeyTest {
  @Test
  void capturesExactFinalLinkStringsInTheirCorrectStages() {
    String vertex = new String("#version 450\nvoid vertexFinal(){}");
    String geometry = new String("#version 450\nvoid geometryFinal(){}");
    String tessControl = new String("#version 450\nvoid controlFinal(){}");
    String tessEvaluation =
        new String("#version 450\nvoid evaluationFinal(){}");
    String fragment = new String("#version 450\nvoid fragmentFinal(){}");

    IrisFinalShaderProgram program = IrisFinalShaderProgram.fromGraphicsLink(
        "gbuffers_terrain", vertex, geometry, tessControl, tessEvaluation,
        fragment);

    assertSame(vertex, program.source(IrisShaderStage.VERTEX));
    assertSame(geometry, program.source(IrisShaderStage.GEOMETRY));
    assertSame(tessControl, program.source(IrisShaderStage.TESS_CONTROL));
    assertSame(tessEvaluation,
        program.source(IrisShaderStage.TESS_EVALUATION));
    assertSame(fragment, program.source(IrisShaderStage.FRAGMENT));
    assertNull(program.source(IrisShaderStage.COMPUTE));
  }

  @Test
  void digestIsDeterministicAndIncludesNameAndAllStageSlots() {
    IrisFinalShaderProgram baseline = IrisFinalShaderProgram.of(
        "program", "vertex", "control", "evaluation", "geometry",
        "fragment", "compute");
    IrisShaderCacheKey first = IrisShaderCacheKey.from(baseline);
    IrisShaderCacheKey second = IrisShaderCacheKey.from(
        IrisFinalShaderProgram.of("program", "vertex", "control",
            "evaluation", "geometry", "fragment", "compute"));

    assertEquals(first, second);
    assertTrue(first.sha256().matches("[0-9a-f]{64}"));
    assertNotEquals(first, IrisShaderCacheKey.from(
        IrisFinalShaderProgram.of("renamed", "vertex", "control",
            "evaluation", "geometry", "fragment", "compute")));
    assertNotEquals(first, IrisShaderCacheKey.from(
        IrisFinalShaderProgram.of("program", "vertex", "control",
            "evaluation", "geometry", "fragment", "changed")));
  }

  @Test
  void nullAndEmptyStagesCannotAlias() {
    IrisFinalShaderProgram absent =
        IrisFinalShaderProgram.fromGraphicsLink(
            "program", "vertex", null, null, null, "fragment");
    IrisFinalShaderProgram empty =
        IrisFinalShaderProgram.fromGraphicsLink(
            "program", "vertex", "", null, null, "fragment");

    assertNotEquals(IrisShaderCacheKey.from(absent),
        IrisShaderCacheKey.from(empty));
  }

  @Test
  void translationProfileIsPartOfTheContentAddress() {
    IrisFinalShaderProgram program =
        IrisFinalShaderProgram.fromGraphicsLink(
            "program", "vertex", null, null, null, "fragment");

    assertNotEquals(IrisShaderCacheKey.from(program,
            new IrisTranslationProfile("translator=one;spirv=1.0")),
        IrisShaderCacheKey.from(program,
            new IrisTranslationProfile("translator=two;spirv=1.0")));
  }
}

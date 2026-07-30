package com.pebbles_boon.metalrender.compat.iris;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable capture of the final GLSL strings after Iris transformations.
 *
 * <p>Instances only live in the bounded in-memory capture queue. The cache
 * writer deliberately has no API for persisting these GLSL strings.</p>
 */
public final class IrisFinalShaderProgram {
  private final String programName;
  private final EnumMap<IrisShaderStage, String> sources;
  private final long retainedChars;

  private IrisFinalShaderProgram(String programName,
      Map<IrisShaderStage, String> sources) {
    this.programName = Objects.requireNonNull(programName, "programName");
    this.sources = new EnumMap<>(IrisShaderStage.class);
    this.sources.putAll(sources);

    long chars = programName.length();
    for (String source : this.sources.values()) {
      if (source != null) {
        chars = Math.addExact(chars, source.length());
      }
    }
    retainedChars = chars;
  }

  /**
   * Captures the exact String arguments accepted by Iris 26.2
   * {@code ShaderCreator.link}.
   */
  public static IrisFinalShaderProgram fromGraphicsLink(String programName,
      String vertex, String geometry, String tessControl, String tessEvaluation,
      String fragment) {
    EnumMap<IrisShaderStage, String> sources =
        new EnumMap<>(IrisShaderStage.class);
    putIfPresent(sources, IrisShaderStage.VERTEX, vertex);
    putIfPresent(sources, IrisShaderStage.GEOMETRY, geometry);
    putIfPresent(sources, IrisShaderStage.TESS_CONTROL, tessControl);
    putIfPresent(sources, IrisShaderStage.TESS_EVALUATION, tessEvaluation);
    putIfPresent(sources, IrisShaderStage.FRAGMENT, fragment);
    return new IrisFinalShaderProgram(programName, sources);
  }

  /**
   * Complete six-stage factory for future compute capture points and tests.
   */
  public static IrisFinalShaderProgram of(String programName, String vertex,
      String tessControl, String tessEvaluation, String geometry,
      String fragment, String compute) {
    EnumMap<IrisShaderStage, String> sources =
        new EnumMap<>(IrisShaderStage.class);
    putIfPresent(sources, IrisShaderStage.VERTEX, vertex);
    putIfPresent(sources, IrisShaderStage.TESS_CONTROL, tessControl);
    putIfPresent(sources, IrisShaderStage.TESS_EVALUATION, tessEvaluation);
    putIfPresent(sources, IrisShaderStage.GEOMETRY, geometry);
    putIfPresent(sources, IrisShaderStage.FRAGMENT, fragment);
    putIfPresent(sources, IrisShaderStage.COMPUTE, compute);
    return new IrisFinalShaderProgram(programName, sources);
  }

  public String programName() {
    return programName;
  }

  /**
   * Returns the exact captured source or {@code null} when the stage was not
   * supplied to Iris.
   */
  public String source(IrisShaderStage stage) {
    return sources.get(Objects.requireNonNull(stage, "stage"));
  }

  public boolean hasStage(IrisShaderStage stage) {
    return sources.containsKey(Objects.requireNonNull(stage, "stage"));
  }

  public Map<IrisShaderStage, String> sources() {
    return Collections.unmodifiableMap(sources);
  }

  /**
   * Conservative queue accounting unit. Java strings retain at most two bytes
   * per character, so bounding this value also bounds captured source memory.
   */
  public long retainedChars() {
    return retainedChars;
  }

  private static void putIfPresent(EnumMap<IrisShaderStage, String> sources,
      IrisShaderStage stage, String source) {
    if (source != null) {
      sources.put(stage, source);
    }
  }
}

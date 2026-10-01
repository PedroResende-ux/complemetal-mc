package com.pebbles_boon.metalrender.compat.iris;

/**
 * Shader stages understood by the Iris-to-Metal translation cache.
 *
 * <p>The declaration order is the canonical order used for hashing and cache
 * manifests. Iris 1.8.12's graphics {@code ShaderCreator.link} currently passes
 * five stages; compute is retained in the model for Iris compute programs and
 * the complete Metal translation pipeline.</p>
 */
public enum IrisShaderStage {
  VERTEX("vertex", "vert"),
  TESS_CONTROL("tess-control", "tesc"),
  TESS_EVALUATION("tess-evaluation", "tese"),
  GEOMETRY("geometry", "geom"),
  FRAGMENT("fragment", "frag"),
  COMPUTE("compute", "comp");

  private final String cacheName;
  private final String compilerSuffix;

  IrisShaderStage(String cacheName, String compilerSuffix) {
    this.cacheName = cacheName;
    this.compilerSuffix = compilerSuffix;
  }

  public String cacheName() {
    return cacheName;
  }

  public String compilerSuffix() {
    return compilerSuffix;
  }
}

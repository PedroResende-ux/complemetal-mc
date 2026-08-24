package com.pebbles_boon.metalrender.compat.iris;

import java.util.Map;

/** Fail-open boundary for creating Stage 6 pipeline objects without drawing. */
interface IrisMetalPipelineCompiler extends AutoCloseable {
  enum Readiness {
    READY,
    DEFERRED,
    UNSUPPORTED
  }

  enum Outcome {
    COMPILED,
    CACHE_HIT,
    DEFERRED,
    UNSUPPORTED,
    FAILED
  }

  Readiness readiness();

  String deviceCompilerSha256();

  Outcome compile(IrisMetalPipelineKey key, IrisShaderCacheKey shaderKey,
      IrisPipelineState state, Map<IrisShaderStage, byte[]> mslStages);

  boolean flush();

  NativeStatus nativeStatus();

  @Override
  default void close() {
  }

  record NativeStatus(long attempts, long compiled, long cacheHits,
                      long failures, long staleArchivesRecovered,
                      long livePipelines, long drawAttempts) {
  }
}

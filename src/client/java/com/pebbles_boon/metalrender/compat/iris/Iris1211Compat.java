package com.pebbles_boon.metalrender.compat.iris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/**
 * Iris 1.8.12 integration boundary for Minecraft 1.21.1.
 * This class performs detection/source access only; it does not replace Iris
 * rendering yet.
 */
public final class Iris1211Compat {
  private Iris1211Compat() {}

  public static boolean activeShaderPipeline() {
    try {
      if (!IrisApi.getInstance().isShaderPackInUse()) return false;
      WorldRenderingPipeline pipeline =
          Iris.getPipelineManager().getPipelineNullable();
      return pipeline instanceof IrisRenderingPipeline;
    } catch (Throwable ignored) {
      return false;
    }
  }

  public static IrisRenderingPipeline pipeline() {
    if (!activeShaderPipeline()) return null;
    try {
      WorldRenderingPipeline pipeline =
          Iris.getPipelineManager().getPipelineNullable();
      return pipeline instanceof IrisRenderingPipeline p ? p : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  public record ProgramFallbackResolverAccessor(
      net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver resolver) {
  }
}

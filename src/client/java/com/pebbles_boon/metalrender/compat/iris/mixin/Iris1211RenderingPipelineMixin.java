package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Narrow 1.21.1/Iris 1.8.12 accessor. Mirrors the private fields that a known
 * Sodium 0.6.13 + Iris 1.8.12 integration uses: resolver, render targets,
 * flipped texture sets and pack directives.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class Iris1211RenderingPipelineMixin
    implements Iris1211RenderingPipelineAccessor {
  @Shadow @Final
  private ProgramFallbackResolver resolver;

  @Shadow @Final
  private RenderTargets renderTargets;

  @Shadow @Final
  private ImmutableSet<Integer> flippedAfterPrepare;

  @Shadow @Final
  private ImmutableSet<Integer> flippedAfterTranslucent;

  @Shadow @Final
  private PackDirectives packDirectives;

  @Override
  public ProgramFallbackResolver complemetal$getResolver() {
    return resolver;
  }

  @Override
  public RenderTargets complemetal$getRenderTargets() {
    return renderTargets;
  }

  @Override
  public ImmutableSet<Integer> complemetal$getFlippedAfterPrepare() {
    return flippedAfterPrepare;
  }

  @Override
  public ImmutableSet<Integer> complemetal$getFlippedAfterTranslucent() {
    return flippedAfterTranslucent;
  }

  @Override
  public PackDirectives complemetal$getPackDirectives() {
    return packDirectives;
  }
}

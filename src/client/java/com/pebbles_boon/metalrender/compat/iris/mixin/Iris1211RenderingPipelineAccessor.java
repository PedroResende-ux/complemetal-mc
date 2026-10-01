package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.targets.RenderTargets;

public interface Iris1211RenderingPipelineAccessor {
  RenderTargets complemetal$getRenderTargets();
  ImmutableSet<Integer> complemetal$getFlippedAfterPrepare();
  ImmutableSet<Integer> complemetal$getFlippedAfterTranslucent();
  ProgramFallbackResolver complemetal$getResolver();
  PackDirectives complemetal$getPackDirectives();
}

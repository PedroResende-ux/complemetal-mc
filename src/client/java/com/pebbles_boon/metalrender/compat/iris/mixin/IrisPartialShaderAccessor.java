package com.pebbles_boon.metalrender.compat.iris.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Optional accessor for the successfully linked Iris GL program name. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.PartialShader",
    remap = false)
public interface IrisPartialShaderAccessor {
  @Accessor("program")
  int metalrender$getGlProgram();
}

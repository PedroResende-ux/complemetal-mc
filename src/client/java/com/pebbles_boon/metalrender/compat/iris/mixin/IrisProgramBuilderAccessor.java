package com.pebbles_boon.metalrender.compat.iris.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Optional accessor for the program linked by Iris ProgramBuilder. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramBuilder",
    remap = false)
public interface IrisProgramBuilderAccessor {
  @Accessor("program")
  int metalrender$getGlProgram();
}

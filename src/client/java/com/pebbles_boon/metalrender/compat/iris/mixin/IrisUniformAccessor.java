package com.pebbles_boon.metalrender.compat.iris.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the location shared by Iris' direct uniform uploader classes. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.uniform.Uniform", remap = false)
public interface IrisUniformAccessor {
  @Accessor("location")
  int metalrender$location();
}

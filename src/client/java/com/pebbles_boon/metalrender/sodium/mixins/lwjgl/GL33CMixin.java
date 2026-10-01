package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes Sodium's direct sampler-object binding. */
@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL33C")
public class GL33CMixin {
  @Inject(method = "glBindSampler", at = @At("HEAD"), remap = false)
  private static void metalrender$bindSampler(int unit, int sampler,
      CallbackInfo ci) {
    IrisGlResourceBindingTracker.global().bindSamplerToUnit(unit, sampler);
  }
}

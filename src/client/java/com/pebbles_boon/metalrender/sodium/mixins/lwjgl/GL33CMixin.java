package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlSamplerMirror;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes Sodium's direct sampler-object binding. */
@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL33C")
public class GL33CMixin {
  @Inject(method = "glDeleteSamplers", at = @At("RETURN"),
      remap = false, require = 0)
  private static void metalrender$deleteSamplers(IntBuffer samplers,
      CallbackInfo ci) {
    if (samplers == null) {
      return;
    }
    for (int index = samplers.position(); index < samplers.limit(); index++) {
      int sampler = samplers.get(index);
      IrisGlResourceBindingTracker.global().deleteSampler(sampler);
      IrisGlSamplerMirror.global().deleteSampler(sampler);
    }
  }

  @Inject(method = "glDeleteSamplers", at = @At("RETURN"),
      remap = false, require = 0)
  private static void metalrender$deleteSampler(int sampler,
      CallbackInfo ci) {
    IrisGlResourceBindingTracker.global().deleteSampler(sampler);
    IrisGlSamplerMirror.global().deleteSampler(sampler);
  }

  @Inject(method = "glBindSampler", at = @At("HEAD"), remap = false)
  private static void metalrender$bindSampler(int unit, int sampler,
      CallbackInfo ci) {
    IrisGlResourceBindingTracker.global().bindSamplerToUnit(unit, sampler);
  }
}

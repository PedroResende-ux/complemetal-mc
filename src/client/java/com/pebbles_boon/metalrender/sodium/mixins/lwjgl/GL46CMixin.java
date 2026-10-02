package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the texture-buffer bind used directly by Iris/Sodium terrain
 * shader setup.
 */
@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL46C")
public class GL46CMixin {
  @Inject(method = "glBindTexture", at = @At("HEAD"), remap = false,
      require = 0)
  private static void metalrender$bindTexture(int target, int texture,
      CallbackInfo ci) {
    IrisGlResourceBindingTracker.global().bindTexture(target, texture);
  }
}

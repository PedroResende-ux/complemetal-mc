package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.display.DisplayPresentationTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures the completed GLFW window present rather than internal draw FPS. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlSurface")
public abstract class GlSurfaceMixin {
  @Shadow
  @Final
  private long windowHandle;

  @Unique
  private long metalrender$presentStartedNanos;

  @Inject(method = "present", at = @At("HEAD"), require = 0)
  private void metalrender$beginPresent(CallbackInfo callback) {
    metalrender$presentStartedNanos = System.nanoTime();
  }

  @Inject(method = "present", at = @At("TAIL"), require = 0)
  private void metalrender$completePresent(CallbackInfo callback) {
    DisplayPresentationTracker.record(windowHandle,
        metalrender$presentStartedNanos, System.nanoTime());
  }
}

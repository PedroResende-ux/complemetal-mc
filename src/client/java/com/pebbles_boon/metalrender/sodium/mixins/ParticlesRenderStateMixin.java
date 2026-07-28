package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses vanilla particle submission only after the current Metal frame
 * has begun and the matching extraction-side capture succeeded.
 */
@Mixin(ParticlesRenderState.class)
public abstract class ParticlesRenderStateMixin {
  @Inject(method = "submit", at = @At("HEAD"), cancellable = true,
      require = 1)
  private void metalrender$suppressVanillaParticles(
      SubmitNodeCollector submitNodeCollector,
      CameraRenderState cameraRenderState, CallbackInfo ci) {
    if (MetalRenderHookState.canReplaceParticles()) {
      ci.cancel();
    }
  }
}

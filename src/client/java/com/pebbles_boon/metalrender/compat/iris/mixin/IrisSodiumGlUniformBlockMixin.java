package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Sodium 0.6.13 UBO bindings that bypass Mojang GL wrappers. */
@Pseudo
@Mixin(value = GlUniformBlock.class, remap = false)
public abstract class IrisSodiumGlUniformBlockMixin {
  @Shadow @Final private int binding;

  @Inject(method = "bindBuffer", at = @At("TAIL"), require = 0,
      remap = false)
  private void metalrender$bindBuffer(GlBuffer buffer, CallbackInfo ci) {
    if (buffer == null) {
      return;
    }
    try {
      IrisGlResourceBindingTracker.global().bindBufferBase(
          0x8A11, binding, buffer.handle());
    } catch (RuntimeException ignored) {
      // UBO tracking is observational; preserve Sodium behavior.
    }
  }
}

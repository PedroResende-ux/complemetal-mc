package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures Sodium 0.6.13 program binds that bypass Mojang's
 * GlStateManager and call GL20C directly.
 */
@Pseudo
@Mixin(value = GlProgram.class, remap = false)
public abstract class IrisSodiumGlProgramMixin {
  @Shadow public abstract int handle();

  @Inject(method = "bind", at = @At("TAIL"), require = 0,
      remap = false)
  private void metalrender$bind(CallbackInfo ci) {
    int program = handle();
    if (program > 0) {
      IrisPipelineStateCapture.global().useProgram(program);
    }
  }

  @Inject(method = "unbind", at = @At("TAIL"), require = 0,
      remap = false)
  private void metalrender$unbind(CallbackInfo ci) {
    IrisPipelineStateCapture.global().useProgram(0);
  }

  @Inject(method = "delete", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$delete(CallbackInfo ci) {
    try {
      IrisShaderCapture.deleteProgram(handle());
    } catch (RuntimeException | LinkageError ignored) {
      // Program teardown must never break Sodium's normal cleanup path.
    }
  }
}

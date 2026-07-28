package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses vanilla entity submission only when the extraction-side Metal
 * capture for this exact frame completed successfully.
 */
@Mixin(LevelRenderer.class)
public abstract class EntityRenderMixin {
  @Inject(method = "submitEntities", at = @At("HEAD"), cancellable = true,
      require = 1)
  private void metalrender$suppressVanillaEntities(PoseStack matrices,
      LevelRenderState renderStates, SubmitNodeCollector queue,
      CallbackInfo ci) {
    if (MetalRenderHookState.canReplaceEntities()) {
      ci.cancel();
    }
  }
}

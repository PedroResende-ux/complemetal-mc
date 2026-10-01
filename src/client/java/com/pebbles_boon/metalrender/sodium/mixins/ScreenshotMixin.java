package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records screenshot captures through the stable Minecraft 1.21.1 helper.
 */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
  @Inject(method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;)"
      + "Lcom/mojang/blaze3d/platform/NativeImage;",
      at = @At("RETURN"), require = 0)
  private static void metalrender$recordScreenshotFrame(RenderTarget target,
      CallbackInfoReturnable<NativeImage> callback) {
    MetalRenderHookState.recordScreenshotCapture();
  }
}

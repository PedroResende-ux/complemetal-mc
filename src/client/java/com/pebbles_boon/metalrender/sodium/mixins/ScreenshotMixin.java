package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import java.util.function.Consumer;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records whether the exact framebuffer submitted to screenshot readback
 * contains the Metal composite.
 */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
  @Inject(method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/"
      + "RenderTarget;Ljava/util/function/Consumer;)V", at = @At("HEAD"))
  private static void metalrender$recordScreenshotFrame(RenderTarget target,
      Consumer<NativeImage> callback, CallbackInfo ci) {
    MetalRenderHookState.recordScreenshotCapture();
  }
}

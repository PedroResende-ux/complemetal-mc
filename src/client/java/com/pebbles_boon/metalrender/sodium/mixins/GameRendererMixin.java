package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.gui.MetalGuiRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
  @Inject(method = "renderLevel",
      at = @At(value = "INVOKE",
          target = "Lnet/minecraft/client/renderer/LevelRenderer;render(" +
              "Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;" +
              "Lnet/minecraft/client/DeltaTracker;Z" +
              "Lnet/minecraft/client/renderer/state/level/CameraRenderState;" +
              "Lorg/joml/Matrix4fc;" +
              "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;" +
              "Lorg/joml/Vector4f;Z)V",
          shift = At.Shift.AFTER),
      require = 1)
  private void metalrender$presentBeforeHand(DeltaTracker deltaTracker,
      CallbackInfo ci) {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    if (config == null || !config.enableFastTerrainReplacement) {
      return;
    }
    if (!MetalRenderHookState.canPresentFrame()) {
      return;
    }
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.metalActive()) {
      return;
    }
    try {
      if (!worldRenderer.forceBlitNow()) {
        MetalRenderHookState.failOpen("presentation-blit", null);
        return;
      }
      MetalRenderHookState.markPresentationSucceeded();
      if (MetalGuiRenderer.isOverlayModeEnabled()) {
        MetalGuiRenderer.getInstance().endFrame();
      }
    } catch (Throwable error) {
      MetalRenderHookState.failOpen("presentation", error);
    }
  }
}

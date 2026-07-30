package com.pebbles_boon.metalrender.sodium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.GpuSampler;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public class WorldRendererTerrainMixin {
  @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/"
      + "ChunkSectionsToRender;renderGroup(Lnet/minecraft/"
      + "client/renderer/chunk/ChunkSectionLayerGroup;Lcom/"
      + "mojang/blaze3d/textures/GpuSampler;)V"), require = 2, allow = 2)
  private void metalrender$replaceTerrainGroups(ChunkSectionsToRender sections,
      ChunkSectionLayerGroup group,
      GpuSampler sampler, Operation<Void> original) {
    boolean replaceOpaque =
        group == ChunkSectionLayerGroup.OPAQUE &&
        MetalRenderHookState.canReplaceTerrain();
    if (!replaceOpaque) {
      original.call(sections, group, sampler);
    }
    if (group != ChunkSectionLayerGroup.OPAQUE ||
        !MetalRenderHookState.canPresentFrame()) {
      return;
    }

    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.metalActive()) {
      return;
    }
    try {
      if (worldRenderer.forceBlitNow()) {
        MetalRenderHookState.markPresentationSucceeded();
      } else {
        MetalRenderHookState.markPresentationAttemptFailed(
            "opaque-pass-composite", null);
      }
    } catch (Throwable error) {
      MetalRenderHookState.markPresentationAttemptFailed(
          "opaque-pass-composite", error);
    }
  }
}

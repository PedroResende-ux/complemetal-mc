package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.compat.IrisCompatibility;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 0.6.13 / Minecraft 1.21.1 terrain cutover.
 *
 * <p>Sodium renders terrain through DefaultChunkRenderer.render(...). Once
 * Complemetal has prepared a usable Metal frame, suppress all Sodium terrain
 * passes so they cannot overwrite the Metal terrain before presentation.</p>
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class WorldRendererTerrainMixin {
  @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 1)
  private void metalrender$replaceSodiumTerrain(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      CallbackInfo ci) {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();

    if (config == null
        || !config.enableFastTerrainReplacement
        || worldRenderer == null
        || !worldRenderer.metalActive()
        || worldRenderer.isIrisCompatibilityPaused()
        || IrisCompatibility.requiresShaderCompatibilityMode()
        || !MetalRenderClient.isEnabled()) {
      return;
    }

    if (!MetalRenderHookState.isFramePrepared()
        || worldRenderer.getLastDrawnChunkCount() <= 0
        || !MetalRenderHookState.canReplaceTerrain()) {
      return;
    }

    ci.cancel();
  }
}

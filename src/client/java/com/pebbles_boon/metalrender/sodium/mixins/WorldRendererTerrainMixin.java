package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.compat.IrisCompatibility;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 0.6.13 / Minecraft 1.21.1 terrain cutover.
 *
 * <p>RenderSectionManager.renderLayer(...) is the high-level point where
 * Sodium submits a complete terrain pass. Intercepting here avoids coupling
 * Complemetal to DefaultChunkRenderer's lower-level graphics ABI.</p>
 *
 * <p>Metal remains fail-open: Sodium is only cancelled after a Metal frame was
 * prepared and at least one Metal terrain draw was successfully encoded.</p>
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class WorldRendererTerrainMixin {
  @Unique
  private Viewport metalrender$viewport;

  @Inject(method = "update", at = @At("HEAD"), require = 1)
  private void metalrender$captureViewport(
      Camera camera, Viewport viewport, int frame, boolean spectator,
      CallbackInfo ci) {
    metalrender$viewport = viewport;
  }

  @Inject(method = "renderLayer", at = @At("HEAD"),
      cancellable = true, require = 1)
  private void metalrender$replaceTerrain(
      ChunkRenderMatrices matrices,
      TerrainRenderPass pass,
      double x, double y, double z,
      CallbackInfo ci) {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();

    if (config == null
        || !config.enableFastTerrainReplacement
        || !MetalRenderClient.isEnabled()
        || worldRenderer == null
        || !worldRenderer.metalActive()
        || worldRenderer.isIrisCompatibilityPaused()
        || IrisCompatibility.requiresShaderCompatibilityMode()
        || metalrender$viewport == null) {
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

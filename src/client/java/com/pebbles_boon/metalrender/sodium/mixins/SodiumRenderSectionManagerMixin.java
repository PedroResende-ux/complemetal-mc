package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Synchronizes Complemetal's independent terrain mesher with Sodium's rebuild
 * scheduler. Sodium may schedule section rebuilds for visibility changes and
 * renderer-driven invalidation that do not originate in ClientLevel's block
 * update callback.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
    remap = false)
public abstract class SodiumRenderSectionManagerMixin {
  @Inject(method = "scheduleRebuild", at = @At("RETURN"), require = 1)
  private void metalrender$mirrorRebuildSchedule(
      int x, int y, int z, boolean important, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) {
      return;
    }

    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.isReady()) {
      return;
    }

    worldRenderer.scheduleSectionRebuild(x, y, z);
  }
}

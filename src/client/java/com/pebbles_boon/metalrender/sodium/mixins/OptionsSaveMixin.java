package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents the FPS-priority mode's temporary live simulation-distance cap from
 * replacing the player's stored preference when any vanilla screen saves the
 * global options file.
 */
@Mixin(Options.class)
public abstract class OptionsSaveMixin {
  @Inject(method = "save", at = @At("HEAD"), require = 1)
  private void metalrender$persistPreferredSimulationDistance(CallbackInfo ci) {
    MetalRenderClient.prepareOptionsSave((Options) (Object) this);
  }

  @Inject(method = "save", at = @At("RETURN"), require = 1)
  private void metalrender$restoreLiveSimulationDistance(CallbackInfo ci) {
    MetalRenderClient.finishOptionsSave((Options) (Object) this);
  }
}

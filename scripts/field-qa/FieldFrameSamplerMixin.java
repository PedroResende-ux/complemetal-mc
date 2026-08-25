package com.pebbles_boon.metalrender.fieldqa.mixin;

import com.pebbles_boon.metalrender.fieldqa.FieldFrameSampler;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public final class FieldFrameSamplerMixin {
  @Inject(method = "runTick", at = @At("TAIL"))
  private void complemetal$fieldQaSampleFrame(boolean tick, CallbackInfo ci) {
    FieldFrameSampler.recordFrame(System.nanoTime());
  }
}

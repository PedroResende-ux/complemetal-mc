package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import net.irisshaders.iris.pipeline.programs.GlUniformMatrix3f;
import org.joml.Matrix3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Iris' Sodium normal-matrix uniform, which writes GL30C directly. */
@Pseudo
@Mixin(value = GlUniformMatrix3f.class, remap = false)
public abstract class IrisGlUniformMatrix3fMixin {
  @Shadow @Final protected int index;

  @Inject(method = "set", at = @At("TAIL"), require = 0,
      remap = false)
  private void metalrender$set(Matrix3fc value, CallbackInfo ci) {
    if (value == null) {
      return;
    }
    float[] raw = new float[9];
    value.get(raw);
    IrisGlResourceBindingTracker.global().uniformMatrix(index, 3, raw);
  }
}

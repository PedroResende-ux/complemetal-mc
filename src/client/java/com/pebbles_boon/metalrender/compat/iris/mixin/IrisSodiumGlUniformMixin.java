package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors Sodium 0.6.13 direct uniform writes into the replay snapshot. */
@Mixin(value = {
    GlUniformFloat.class,
    GlUniformFloat2v.class,
    GlUniformFloat3v.class,
    GlUniformFloat4v.class,
    GlUniformInt.class,
    GlUniformMatrix4f.class
}, remap = false)
public abstract class IrisSodiumGlUniformMixin<T> {
  @Shadow @Final protected int index;

  @Unique
  private IrisGlResourceBindingTracker metalrender$tracker() {
    return IrisGlResourceBindingTracker.global();
  }

  @Inject(method = "set", at = @At("TAIL"), require = 0, remap = false)
  private void metalrender$setFloat(Float value, CallbackInfo ci) {
    if (value != null) {
      metalrender$tracker().uniformFloats(index, value);
    }
  }

  @Inject(method = "setFloat", at = @At("TAIL"), require = 0, remap = false)
  private void metalrender$setFloatPrimitive(float value, CallbackInfo ci) {
    metalrender$tracker().uniformFloats(index, value);
  }

  @Inject(method = "set", at = @At("TAIL"), require = 0, remap = false)
  private void metalrender$setFloat2(float[] value, CallbackInfo ci) {
    if (value != null) {
      metalrender$tracker().uniformFloats(index, value);
    }
  }

  @Inject(method = "set", at = @At("TAIL"), require = 0, remap = false)
  private void metalrender$setFloat3(float[] value, CallbackInfo ci) {
    if (value != null) {
      metalrender$tracker().uniformFloats(index, value);
    }
  }

  @Inject(method = "set", at = @At("TAIL"), require = 0, remap = false)
  private void metalrender$setFloat4(float[] value, CallbackInfo ci) {
    if (value != null) {
      metalrender$tracker().uniformFloats(index, value);
    }
  }

  @Inject(method = "set", at = @At("TAIL"), require = 0, remap = false)
  private void metalrender$setInt(Integer value, CallbackInfo ci) {
    if (value != null) {
      metalrender$tracker().uniformInts(index, value);
    }
  }

  @Inject(method = "set", at = @At("TAIL"), require = 0, remap = false)
  private void metalrender$setMatrix(Matrix4fc value, CallbackInfo ci) {
    if (value != null) {
      float[] raw = new float[16];
      value.get(raw);
      metalrender$tracker().uniformMatrix(index, 4, raw);
    }
  }
}

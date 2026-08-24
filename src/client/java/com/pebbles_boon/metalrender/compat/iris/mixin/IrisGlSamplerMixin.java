package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.pebbles_boon.metalrender.compat.iris.IrisGlSamplerMirror;
import java.util.OptionalDouble;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors Mojang sampler objects after their exact GL initialization. */
@Mixin(GlSampler.class)
public abstract class IrisGlSamplerMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void metalrender$defineSampler(AddressMode addressModeU,
      AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter,
      int maxAnisotropy, OptionalDouble maxLod, CallbackInfo ci) {
    int sampler = ((GlSampler) (Object) this).getId();
    IrisGlSamplerMirror mirror = IrisGlSamplerMirror.global();
    mirror.defineSampler(sampler);
    mirror.samplerParameteri(sampler, IrisGlSamplerMirror.GL_TEXTURE_WRAP_S,
        addressModeU == AddressMode.REPEAT ? 0x2901 : 0x812F);
    mirror.samplerParameteri(sampler, IrisGlSamplerMirror.GL_TEXTURE_WRAP_T,
        addressModeV == AddressMode.REPEAT ? 0x2901 : 0x812F);
    mirror.samplerParameteri(sampler,
        IrisGlSamplerMirror.GL_TEXTURE_MIN_FILTER,
        minFilter == FilterMode.NEAREST ? 0x2702 : 0x2703);
    mirror.samplerParameteri(sampler,
        IrisGlSamplerMirror.GL_TEXTURE_MAG_FILTER,
        magFilter == FilterMode.NEAREST ? 0x2600 : 0x2601);
    if (maxAnisotropy > 1) {
      mirror.samplerParameterf(sampler,
          IrisGlSamplerMirror.GL_TEXTURE_MAX_ANISOTROPY, maxAnisotropy);
    }
    if (maxLod.isPresent()) {
      mirror.samplerParameterf(sampler,
          IrisGlSamplerMirror.GL_TEXTURE_MAX_LOD,
          (float) maxLod.getAsDouble());
    }
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void metalrender$deleteSampler(CallbackInfo ci) {
    IrisGlSamplerMirror.global().deleteSampler(
        ((GlSampler) (Object) this).getId());
  }
}

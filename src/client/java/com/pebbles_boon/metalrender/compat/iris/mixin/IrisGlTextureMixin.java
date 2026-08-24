package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlTexture;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlTextureMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlSamplerMirror;
import java.util.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Mojang-owned texture formats used by RenderPass framebuffers. */
@Mixin(GlTexture.class)
public abstract class IrisGlTextureMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void metalrender$defineTexture(int usage, String label,
      GpuFormat format, int width, int height, int depthOrLayers,
      int mipLevels, int id, FrameBufferCache frameBufferCache,
      CallbackInfo ci) {
    if (id > 0) {
      String cacheFormat = format.name().toLowerCase(Locale.ROOT)
          .replace('_', '-');
      IrisPipelineStateCapture.global().tracker().defineTexture(id,
          cacheFormat, 1,
          width, height, depthOrLayers, mipLevels);
      IrisGlSamplerMirror.global().defineTexture(id);
      if (IrisGlBufferMirror.isEnabled()) {
        IrisGlTextureMirror.global().define(id, cacheFormat, width, height,
            depthOrLayers, mipLevels, format.blockSize());
      }
    }
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void metalrender$deleteTextureMirror(CallbackInfo ci) {
    IrisGlResourceBindingTracker.global().deleteTexture(
        ((GlTexture) (Object) this).glId());
    if (IrisGlBufferMirror.isEnabled()) {
      IrisGlTextureMirror.global().delete(((GlTexture) (Object) this).glId());
    }
    IrisGlSamplerMirror.global().deleteTexture(
        ((GlTexture) (Object) this).glId());
  }
}

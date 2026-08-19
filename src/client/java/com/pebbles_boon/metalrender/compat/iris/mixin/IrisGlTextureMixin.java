package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlTexture;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
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
      IrisPipelineStateCapture.global().tracker().defineTexture(id,
          format.name().toLowerCase(Locale.ROOT).replace('_', '-'), 1);
    }
  }
}

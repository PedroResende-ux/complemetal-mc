package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.FrameBufferAttachment;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import java.util.List;
import java.util.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes Mojang's DSA framebuffer cache used by Iris final passes. */
@Mixin(FrameBufferCache.class)
public abstract class IrisFrameBufferCacheMixin {
  private static final int GL_TEXTURE_2D = 0x0DE1;

  @Inject(method = "getFbo", at = @At("RETURN"))
  private void metalrender$captureFbo(DirectStateAccess access,
      List<FrameBufferAttachment> colors, FrameBufferAttachment depthStencil,
      CallbackInfoReturnable<Integer> callback) {
    int framebuffer = callback.getReturnValue();
    if (framebuffer <= 0) {
      return;
    }
    IrisGlStateTracker tracker = IrisPipelineStateCapture.global().tracker();
    var handle = tracker.defineFramebuffer(framebuffer);
    tracker.resetFramebuffer(handle);

    int colorCount = Math.min(colors.size(),
        IrisGlStateTracker.MAX_COLOR_ATTACHMENTS);
    int[] drawBuffers = new int[colorCount];
    for (int index = 0; index < colorCount; index++) {
      FrameBufferAttachment attachment = colors.get(index);
      if (attachment == null) {
        drawBuffers[index] = IrisGlStateTracker.GL_NONE;
        continue;
      }
      metalrender$defineAttachment(tracker, attachment);
      int attachmentPoint = IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + index;
      tracker.framebufferTexture2DForFramebuffer(framebuffer, attachmentPoint,
          GL_TEXTURE_2D, attachment.glId(), attachment.fboMipLevel());
      drawBuffers[index] = attachmentPoint;
    }
    tracker.drawBuffersForFramebuffer(framebuffer, drawBuffers);

    if (depthStencil != null) {
      GpuFormat format = metalrender$format(depthStencil);
      metalrender$defineAttachment(tracker, depthStencil);
      int attachmentPoint = format != null && format.hasDepthAspect()
          && format.hasStencilAspect()
          ? IrisGlStateTracker.GL_DEPTH_STENCIL_ATTACHMENT
          : format != null && format.hasStencilAspect()
              ? IrisGlStateTracker.GL_STENCIL_ATTACHMENT
              : IrisGlStateTracker.GL_DEPTH_ATTACHMENT;
      tracker.framebufferTexture2DForFramebuffer(framebuffer, attachmentPoint,
          GL_TEXTURE_2D, depthStencil.glId(), depthStencil.fboMipLevel());
    }
  }

  private static void metalrender$defineAttachment(IrisGlStateTracker tracker,
      FrameBufferAttachment attachment) {
    GpuFormat format = metalrender$format(attachment);
    if (format != null && attachment.glId() > 0) {
      tracker.defineTexture(attachment.glId(),
          format.name().toLowerCase(Locale.ROOT).replace('_', '-'), 1);
    }
  }

  private static GpuFormat metalrender$format(
      FrameBufferAttachment attachment) {
    if (attachment instanceof GlTexture texture) {
      return texture.getFormat();
    }
    if (attachment instanceof GlTextureView view) {
      return view.texture().getFormat();
    }
    return null;
  }
}

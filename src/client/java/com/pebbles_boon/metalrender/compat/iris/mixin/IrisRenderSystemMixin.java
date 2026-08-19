package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisGlFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import java.nio.ByteBuffer;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional exact-Iris hooks for DSA state and compute dispatches. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.IrisRenderSystem", remap = false)
public abstract class IrisRenderSystemMixin {
  private static IrisPipelineStateCapture metalrender$capture() {
    return IrisPipelineStateCapture.global();
  }

  private static IrisGlStateTracker metalrender$state() {
    return metalrender$capture().tracker();
  }

  @Inject(method = "createFramebuffer", at = @At("RETURN"),
      require = 0, remap = false)
  private static void metalrender$createFramebuffer(
      CallbackInfoReturnable<Integer> callback) {
    int framebuffer = callback.getReturnValue();
    if (framebuffer > 0) {
      metalrender$state().defineFramebuffer(framebuffer);
    }
  }

  @Inject(method = "texImage2D", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$texImage2D(int texture, int target,
      int level, int internalFormat, int width, int height, int border,
      int format, int type, ByteBuffer pixels, CallbackInfo ci) {
    if (texture > 0 && level == 0) {
      String cacheFormat = IrisGlFormat.cacheName(internalFormat)
          .orElseGet(() -> "gl-0x" + Integer.toHexString(internalFormat));
      metalrender$state().defineTexture(texture, cacheFormat, 1);
    }
  }

  @Inject(method = "framebufferTexture2D", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$framebufferTexture2D(int framebuffer,
      int target, int attachment, int textureTarget, int texture,
      int mipLevel, CallbackInfo ci) {
    metalrender$state().framebufferTexture2DForFramebuffer(framebuffer,
        attachment, textureTarget, texture, mipLevel);
  }

  @Inject(method = "drawBuffers", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$drawBuffers(int framebuffer, int[] buffers,
      CallbackInfo ci) {
    metalrender$state().drawBuffersForFramebuffer(framebuffer, buffers);
  }

  @Inject(method = "enableBufferBlend", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$enableBufferBlend(int index,
      CallbackInfo ci) {
    metalrender$state().capabilityIndexed(IrisGlStateTracker.GL_BLEND, index,
        true);
  }

  @Inject(method = "disableBufferBlend", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$disableBufferBlend(int index,
      CallbackInfo ci) {
    metalrender$state().capabilityIndexed(IrisGlStateTracker.GL_BLEND, index,
        false);
  }

  @Inject(method = "blendFuncSeparatei", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$blendFuncSeparateIndexed(int index,
      int sourceRgb, int destinationRgb, int sourceAlpha,
      int destinationAlpha, CallbackInfo ci) {
    metalrender$state().blendFuncSeparateIndexed(index, sourceRgb,
        destinationRgb, sourceAlpha, destinationAlpha);
  }

  @Inject(method = "dispatchCompute(III)V", at = @At("HEAD"), require = 0,
      remap = false)
  private static void metalrender$dispatchCompute(int x, int y, int z,
      CallbackInfo ci) {
    metalrender$capture().dispatch();
  }

  @Inject(method = "dispatchCompute(Lorg/joml/Vector3i;)V",
      at = @At("HEAD"), require = 0, remap = false)
  private static void metalrender$dispatchComputeVector(Vector3i groups,
      CallbackInfo ci) {
    metalrender$capture().dispatch();
  }

  @Inject(method = "dispatchComputeIndirect", at = @At("HEAD"), require = 0,
      remap = false)
  private static void metalrender$dispatchComputeIndirect(long offset,
      CallbackInfo ci) {
    metalrender$capture().dispatch();
  }
}

package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes the Mojang OpenGL facade without changing or cancelling any call.
 * All hooks are constant-time state mutations or bounded queue offers.
 */
@Mixin(GlStateManager.class)
public abstract class IrisGlStateManagerMixin {
  private static IrisPipelineStateCapture metalrender$capture() {
    return IrisPipelineStateCapture.global();
  }

  private static IrisGlStateTracker metalrender$state() {
    return metalrender$capture().tracker();
  }

  @Inject(method = "_glUseProgram", at = @At("TAIL"))
  private static void metalrender$useProgram(int program, CallbackInfo ci) {
    metalrender$capture().useProgram(program);
  }

  @Inject(method = "glDeleteProgram", at = @At("TAIL"))
  private static void metalrender$deleteProgram(int program, CallbackInfo ci) {
    IrisShaderCapture.deleteProgram(program);
  }

  @Inject(method = "_enableDepthTest", at = @At("TAIL"))
  private static void metalrender$enableDepth(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_DEPTH_TEST, true);
  }

  @Inject(method = "_disableDepthTest", at = @At("TAIL"))
  private static void metalrender$disableDepth(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_DEPTH_TEST, false);
  }

  @Inject(method = "_depthFunc", at = @At("TAIL"))
  private static void metalrender$depthFunc(int function, CallbackInfo ci) {
    metalrender$state().depthFunc(function);
  }

  @Inject(method = "_depthMask", at = @At("TAIL"))
  private static void metalrender$depthMask(boolean enabled, CallbackInfo ci) {
    metalrender$state().depthMask(enabled);
  }

  @Inject(method = "_enableBlend", at = @At("TAIL"))
  private static void metalrender$enableBlend(int index, CallbackInfo ci) {
    if (index == 0) {
      metalrender$state().capability(IrisGlStateTracker.GL_BLEND, true);
    } else {
      metalrender$state().capabilityIndexed(IrisGlStateTracker.GL_BLEND,
          index, true);
    }
  }

  @Inject(method = "_disableBlend", at = @At("TAIL"))
  private static void metalrender$disableBlend(int index, CallbackInfo ci) {
    if (index == 0) {
      metalrender$state().capability(IrisGlStateTracker.GL_BLEND, false);
    } else {
      metalrender$state().capabilityIndexed(IrisGlStateTracker.GL_BLEND,
          index, false);
    }
  }

  @Inject(method = {"_blendFuncSeparate", "glBlendFuncSeparate"},
      at = @At("TAIL"))
  private static void metalrender$blendFuncSeparate(int sourceRgb,
      int destinationRgb, int sourceAlpha, int destinationAlpha,
      CallbackInfo ci) {
    metalrender$state().blendFuncSeparate(sourceRgb, destinationRgb,
        sourceAlpha, destinationAlpha);
  }

  @Inject(method = {"_blendEquationSeparate", "glBlendEquationSeparate"},
      at = @At("TAIL"))
  private static void metalrender$blendEquationSeparate(int rgb, int alpha,
      CallbackInfo ci) {
    metalrender$state().blendEquationSeparate(rgb, alpha);
  }

  @Inject(method = "_enableCull", at = @At("TAIL"))
  private static void metalrender$enableCull(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_CULL_FACE, true);
  }

  @Inject(method = "_disableCull", at = @At("TAIL"))
  private static void metalrender$disableCull(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_CULL_FACE, false);
  }

  @Inject(method = "_polygonMode", at = @At("TAIL"))
  private static void metalrender$polygonMode(int face, int mode,
      CallbackInfo ci) {
    metalrender$state().polygonMode(face, mode);
  }

  @Inject(method = "_enablePolygonOffset", at = @At("TAIL"))
  private static void metalrender$enablePolygonOffset(CallbackInfo ci) {
    metalrender$state().capability(
        IrisGlStateTracker.GL_POLYGON_OFFSET_FILL, true);
  }

  @Inject(method = "_disablePolygonOffset", at = @At("TAIL"))
  private static void metalrender$disablePolygonOffset(CallbackInfo ci) {
    metalrender$state().capability(
        IrisGlStateTracker.GL_POLYGON_OFFSET_FILL, false);
  }

  @Inject(method = "_polygonOffset", at = @At("TAIL"))
  private static void metalrender$polygonOffset(float factor, float units,
      CallbackInfo ci) {
    metalrender$state().polygonOffset(factor, units);
  }

  @Inject(method = "_colorMask(I)V", at = @At("TAIL"))
  private static void metalrender$colorMask(int mask, CallbackInfo ci) {
    metalrender$state().colorMask((mask & 1) != 0, (mask & 2) != 0,
        (mask & 4) != 0, (mask & 8) != 0);
  }

  @Inject(method = "_colorMask(II)V", at = @At("TAIL"))
  private static void metalrender$colorMaskIndexed(int index, int mask,
      CallbackInfo ci) {
    metalrender$state().colorMaskIndexed(index, (mask & 1) != 0,
        (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
  }

  @Inject(method = "glGenFramebuffers", at = @At("RETURN"))
  private static void metalrender$genFramebuffer(
      CallbackInfoReturnable<Integer> callback) {
    int framebuffer = callback.getReturnValue();
    if (framebuffer > 0) {
      metalrender$state().defineFramebuffer(framebuffer);
    }
  }

  @Inject(method = "_glBindFramebuffer", at = @At("TAIL"))
  private static void metalrender$bindFramebuffer(int target, int framebuffer,
      CallbackInfo ci) {
    metalrender$state().bindFramebuffer(target, framebuffer);
  }

  @Inject(method = "_glFramebufferTexture2D", at = @At("TAIL"))
  private static void metalrender$framebufferTexture2D(int target,
      int attachment, int textureTarget, int texture, int mipLevel,
      CallbackInfo ci) {
    metalrender$state().framebufferTexture2D(target, attachment,
        textureTarget, texture, mipLevel);
  }

  @Inject(method = "_glDeleteFramebuffers", at = @At("TAIL"))
  private static void metalrender$deleteFramebuffer(int framebuffer,
      CallbackInfo ci) {
    metalrender$state().deleteFramebuffer(framebuffer);
  }

  @Inject(method = "_deleteTexture", at = @At("TAIL"))
  private static void metalrender$deleteTexture(int texture, CallbackInfo ci) {
    metalrender$state().deleteTexture(texture);
  }

  @Inject(method = "_drawElements", at = @At("HEAD"))
  private static void metalrender$drawElements(int mode, int count, int type,
      long indices, CallbackInfo ci) {
    metalrender$capture().draw(mode);
  }

  @Inject(method = "_drawArrays", at = @At("HEAD"))
  private static void metalrender$drawArrays(int mode, int first, int count,
      CallbackInfo ci) {
    metalrender$capture().draw(mode);
  }
}

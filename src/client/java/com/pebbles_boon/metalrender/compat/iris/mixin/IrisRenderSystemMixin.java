package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisGlFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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

  private static IrisGlResourceBindingTracker metalrender$resources() {
    return IrisGlResourceBindingTracker.global();
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

  @Inject(method = "bindTextureToUnit", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindTextureToUnit(int target, int unit,
      int texture, CallbackInfo ci) {
    metalrender$resources().bindTextureToUnit(target, unit, texture);
  }

  @Inject(method = "bindSamplerToUnit", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindSamplerToUnit(int unit, int sampler,
      CallbackInfo ci) {
    metalrender$resources().bindSamplerToUnit(unit, sampler);
  }

  @Inject(method = "bindImageTexture", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindImageTexture(int unit, int texture,
      int level, boolean layered, int layer, int access, int format,
      CallbackInfo ci) {
    metalrender$resources().bindImageTexture(unit, texture, level, layered,
        layer, access, format);
  }

  @Inject(method = "bindBufferBase", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindBufferBase(int target, Integer index,
      int buffer, CallbackInfo ci) {
    if (index != null) {
      metalrender$resources().bindBufferBase(target, index, buffer);
    }
  }

  @Inject(method = "getUniformBlockIndex", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniformBlockIndex(int program, String name,
      CallbackInfoReturnable<Integer> callback) {
    metalrender$resources().uniformBlockIndex(program, name,
        callback.getReturnValue());
  }

  @Inject(method = "uniformBlockBinding", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniformBlockBinding(int program,
      int blockIndex, int binding, CallbackInfo ci) {
    metalrender$resources().uniformBlockBinding(program, blockIndex, binding);
  }

  @Inject(method = "uniform1f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform1f(int location, float x,
      CallbackInfo ci) {
    metalrender$resources().uniformFloats(location, x);
  }

  @Inject(method = "uniform2f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform2f(int location, float x, float y,
      CallbackInfo ci) {
    metalrender$resources().uniformFloats(location, x, y);
  }

  @Inject(method = "uniform3f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform3f(int location, float x, float y,
      float z, CallbackInfo ci) {
    metalrender$resources().uniformFloats(location, x, y, z);
  }

  @Inject(method = "uniform4f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform4f(int location, float x, float y,
      float z, float w, CallbackInfo ci) {
    metalrender$resources().uniformFloats(location, x, y, z, w);
  }

  @Inject(method = "uniform2i", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform2i(int location, int x, int y,
      CallbackInfo ci) {
    metalrender$resources().uniformInts(location, x, y);
  }

  @Inject(method = "uniform3i", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform3i(int location, int x, int y,
      int z, CallbackInfo ci) {
    metalrender$resources().uniformInts(location, x, y, z);
  }

  @Inject(method = "uniform4i", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform4i(int location, int x, int y,
      int z, int w, CallbackInfo ci) {
    metalrender$resources().uniformInts(location, x, y, z, w);
  }

  @Inject(method = "uniformMatrix3fv(IZLjava/nio/FloatBuffer;)V",
      at = @At("RETURN"), require = 0, remap = false)
  private static void metalrender$uniformMatrix3(int location,
      boolean transpose, FloatBuffer values, CallbackInfo ci) {
    metalrender$resources().uniformMatrix(location, 3, values);
  }

  @Inject(method = "uniformMatrix3fv(IZ[F)V", at = @At("RETURN"),
      require = 0, remap = false)
  private static void metalrender$uniformMatrix3Array(int location,
      boolean transpose, float[] values, CallbackInfo ci) {
    metalrender$resources().uniformMatrix(location, 3, values);
  }

  @Inject(method = "uniformMatrix4fv(IZLjava/nio/FloatBuffer;)V",
      at = @At("RETURN"), require = 0, remap = false)
  private static void metalrender$uniformMatrix4(int location,
      boolean transpose, FloatBuffer values, CallbackInfo ci) {
    metalrender$resources().uniformMatrix(location, 4, values);
  }

  @Inject(method = "uniformMatrix4fv(IZ[F)V", at = @At("RETURN"),
      require = 0, remap = false)
  private static void metalrender$uniformMatrix4Array(int location,
      boolean transpose, float[] values, CallbackInfo ci) {
    metalrender$resources().uniformMatrix(location, 4, values);
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

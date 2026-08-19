package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.pebbles_boon.metalrender.compat.iris.IrisGlRenderPassState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures draws that Mojang's command encoder submits directly to LWJGL. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class IrisGlCommandEncoderMixin {
  @Redirect(method = "trySetup", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL33C;glBindBufferRange(IIIJJ)V"),
      require = 0)
  private void metalrender$bindUniformBufferRange(int target, int index,
      int buffer, long offset, long size) {
    metalrender$bindBufferRange(target, index, buffer, offset, size);
  }

  @Redirect(method = "trySetup", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL33C;glBindTexture:(II)V"), require = 0)
  private void metalrender$bindTexture(int target, int texture) {
    GL33C.glBindTexture(target, texture);
    IrisGlResourceBindingTracker.global().bindTexture(target, texture);
  }

  @Redirect(method = "trySetup", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL33C;glBindSampler:(II)V"), require = 0)
  private void metalrender$bindSampler(int unit, int sampler) {
    GL33C.glBindSampler(unit, sampler);
    IrisGlResourceBindingTracker.global().bindSamplerToUnit(unit, sampler);
  }

  @Redirect(method = "trySetup", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL33C;glTexBuffer:(III)V"), require = 0)
  private void metalrender$texBuffer(int target, int internalFormat,
      int buffer) {
    GL33C.glTexBuffer(target, internalFormat, buffer);
    IrisGlResourceBindingTracker.global().texBuffer(target, internalFormat,
        buffer);
  }

  @Redirect(method = "lambda$executeDrawMultiple$0",
      at = @At(value = "INVOKE",
          target = "Lorg/lwjgl/opengl/GL33C;glBindBufferRange(IIIJJ)V"),
      require = 0)
  private static void metalrender$bindDrawMultipleUniformBufferRange(
      int target, int index, int buffer, long offset, long size) {
    metalrender$bindBufferRange(target, index, buffer, offset, size);
  }

  @Inject(method = "drawFromBuffers", at = @At("HEAD"))
  private void metalrender$drawFromBuffers(@Coerce Object pass,
      int baseVertex, int firstIndex, int indexCount, IndexType indexType,
      GlRenderPipeline pipeline, int instanceCount, int baseInstance,
      CallbackInfo ci) {
    IrisPipelineStateCapture.global().draw(GlConst.toGl(
        pipeline.info().getPrimitiveTopology()));
  }

  @Inject(method = "executeDraws", at = @At(value = "INVOKE",
      target = "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;validateDraw(Lcom/mojang/blaze3d/opengl/GlRenderPass;Lcom/mojang/blaze3d/IndexType;)V",
      shift = At.Shift.AFTER))
  private void metalrender$executeDraws(@Coerce Object pass,
      IndexType indexType, PointerBuffer indices, IntBuffer counts,
      IntBuffer baseVertices, int drawCount, CallbackInfo ci) {
    metalrender$capturePass(pass);
  }

  @Inject(method = "executeDrawIndirect", at = @At(value = "INVOKE",
      target = "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;validateDraw(Lcom/mojang/blaze3d/opengl/GlRenderPass;Lcom/mojang/blaze3d/IndexType;)V",
      shift = At.Shift.AFTER))
  private void metalrender$executeDrawIndirect(@Coerce Object pass,
      IndexType indexType, GlBuffer indirectBuffer, long offset,
      int drawCount, CallbackInfo ci) {
    metalrender$capturePass(pass);
  }

  private static void metalrender$capturePass(Object pass) {
    int primitiveMode = ((IrisGlRenderPassState) pass)
        .metalrender$primitiveMode();
    IrisPipelineStateCapture.global().draw(primitiveMode);
  }

  private static void metalrender$bindBufferRange(int target, int index,
      int buffer, long offset, long size) {
    GL33C.glBindBufferRange(target, index, buffer, offset, size);
    IrisGlResourceBindingTracker.global().bindBufferRange(target, index,
        buffer, offset, size);
  }
}

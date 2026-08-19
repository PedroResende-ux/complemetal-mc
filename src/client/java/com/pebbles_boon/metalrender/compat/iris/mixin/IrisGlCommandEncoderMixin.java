package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.pebbles_boon.metalrender.compat.iris.IrisGlRenderPassState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures draws that Mojang's command encoder submits directly to LWJGL. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class IrisGlCommandEncoderMixin {
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
}

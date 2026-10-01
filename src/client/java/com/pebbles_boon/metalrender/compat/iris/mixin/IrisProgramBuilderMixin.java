package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris 1.8.12-compatible ProgramBuilder source capture; experimental only.
 *
 * <p>Capture runs only after Iris has successfully compiled and linked the
 * normal OpenGL program. It does not cancel, alter, hash, translate, or persist
 * the supplied GLSL on the render thread.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramBuilder",
    remap = false)
public abstract class IrisProgramBuilderMixin {
  @Inject(
      method = "begin(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
          + "Ljava/lang/String;Lcom/google/common/collect/ImmutableSet;)"
          + "Lnet/irisshaders/iris/gl/program/ProgramBuilder;",
      at = @At("RETURN"), require = 0, remap = false)
  private static void metalrender$captureFinalGraphics(String name,
      String vertex, String geometry, String fragment,
      ImmutableSet<Integer> reservedTextureUnits,
      CallbackInfoReturnable<?> callback) {
    Object result = callback.getReturnValue();
    if (result instanceof IrisProgramBuilderAccessor builder) {
      IrisShaderCapture.captureFullscreenGraphicsProgram(
          builder.metalrender$getGlProgram(), name, vertex, geometry,
          fragment, DefaultVertexFormat.POSITION_TEX);
    }
  }

  @Inject(
      method = "beginCompute(Ljava/lang/String;Ljava/lang/String;"
          + "Lcom/google/common/collect/ImmutableSet;)"
          + "Lnet/irisshaders/iris/gl/program/ProgramBuilder;",
      at = @At("RETURN"), require = 0, remap = false)
  private static void metalrender$captureFinalCompute(String name,
      String compute, ImmutableSet<Integer> reservedTextureUnits,
      CallbackInfoReturnable<?> callback) {
    Object result = callback.getReturnValue();
    if (result instanceof IrisProgramBuilderAccessor builder) {
      IrisShaderCapture.captureComputeProgram(builder.metalrender$getGlProgram(),
          name, compute);
    }
  }
}

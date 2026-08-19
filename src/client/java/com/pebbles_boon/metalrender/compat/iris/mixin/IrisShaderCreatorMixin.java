package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional ABI-specific hook for Iris 26.2.
 *
 * <p>The injection runs only after {@code link} returns successfully. It does
 * not cancel or alter the normal OpenGL compile/link path.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator",
    remap = false)
public abstract class IrisShaderCreatorMixin {
  @Inject(method = "link", at = @At("RETURN"), require = 0, remap = false)
  private static void metalrender$captureFinalGlsl(String name, String vertex,
      String geometry, String tessControl, String tessEvaluation,
      String fragment, VertexFormat vertexFormat, boolean fallback,
      CallbackInfoReturnable<?> callback) {
    Object linked = callback.getReturnValue();
    if (linked instanceof IrisPartialShaderAccessor accessor) {
      IrisShaderCapture.captureLinkedGraphicsProgram(
          accessor.metalrender$getGlProgram(), name, vertex, geometry,
          tessControl, tessEvaluation, fragment, vertexFormat, fallback);
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the final GLSL from Iris' optional DH generic-object program.
 *
 * <p>The program uses one per-vertex buffer plus five independent instanced
 * buffers. MetalRender's current {@code VertexFormat} bridge describes one
 * buffer only, so this hook deliberately captures sources without registering
 * a false pipeline layout. A later multi-buffer DH bridge can promote this to
 * executable pipeline capture.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.IrisGenericRenderProgram",
    remap = false)
public abstract class IrisDhGenericRenderProgramMixin {
  @Inject(
      method = "<init>(Ljava/lang/String;ZZ"
          + "Lnet/irisshaders/iris/gl/blending/BlendModeOverride;"
          + "[Lnet/irisshaders/iris/gl/blending/BufferBlendOverride;"
          + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
          + "Ljava/lang/String;Ljava/lang/String;"
          + "Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;"
          + "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;)V",
      at = @At("RETURN"), require = 0, remap = false)
  private void metalrender$captureFinalSources(String name,
      boolean shadowPass, boolean translucent,
      @Coerce Object blendModeOverride,
      @Coerce Object[] bufferBlendOverrides,
      String vertex, String tessControl, String tessEvaluation,
      String geometry, String fragment, @Coerce Object customUniforms,
      @Coerce Object pipeline, CallbackInfo ci) {
    IrisShaderCapture.captureGraphicsLink("dh-generic:" + name, vertex,
        geometry, tessControl, tessEvaluation, fragment);
  }
}

package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional capture for Iris' Distant Horizons terrain program.
 *
 * <p>Iris links this program directly with LWJGL, bypassing both
 * {@code ShaderCreator.link} and {@code ProgramBuilder}. The vertex layout
 * below matches the interleaved LOD attributes that Iris binds for its
 * 1.21.1 Distant Horizons terrain program: u16x4 position at byte 0,
 * normalized u8x4 color at byte 8, and u8x4 Iris metadata at byte 12.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.IrisLodRenderProgram",
    remap = false)
public abstract class IrisDhLodRenderProgramMixin {
  @Unique
  private static final VertexFormat METALRENDER_DH_LOD_VERTEX_FORMAT =
      VertexFormat.builder()
          .add("vPosition", metalrender$element(
              VertexFormatElement.Type.USHORT,
              VertexFormatElement.Usage.POSITION, 4))
          .add("iris_color", metalrender$element(
              VertexFormatElement.Type.UBYTE,
              VertexFormatElement.Usage.COLOR, 4))
          .add("irisExtra", metalrender$element(
              VertexFormatElement.Type.UBYTE,
              VertexFormatElement.Usage.GENERIC, 4))
          .build();

  @Unique
  private static VertexFormatElement metalrender$element(
      VertexFormatElement.Type type, VertexFormatElement.Usage usage,
      int count) {
    return VertexFormatElement.register(
        VertexFormatElement.findNextId(), 0, type, usage, count);
  }

  @Shadow
  @Final
  private int id;

  @Inject(
      method = "<init>(Ljava/lang/String;ZZ"
          + "Lnet/irisshaders/iris/gl/blending/BlendModeOverride;"
          + "[Lnet/irisshaders/iris/gl/blending/BufferBlendOverride;"
          + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
          + "Ljava/lang/String;Ljava/lang/String;"
          + "Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;"
          + "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;)V",
      at = @At("RETURN"), require = 0, remap = false)
  private void metalrender$captureLinkedProgram(String name,
      boolean shadowPass, boolean translucent,
      @Coerce Object blendModeOverride,
      @Coerce Object[] bufferBlendOverrides,
      String vertex, String tessControl, String tessEvaluation,
      String geometry, String fragment, @Coerce Object customUniforms,
      @Coerce Object pipeline, CallbackInfo ci) {
    IrisShaderCapture.captureLinkedGraphicsProgram(id, "dh-lod:" + name,
        vertex, geometry, tessControl, tessEvaluation, fragment,
        METALRENDER_DH_LOD_VERTEX_FORMAT, false);
  }

  /** Mirrors the direct GL43C.glUseProgram call into the bounded tracker. */
  @Inject(method = "bind()V", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$bind(CallbackInfo ci) {
    IrisPipelineStateCapture.global().useProgram(id);
  }

  @Inject(method = "unbind()V", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$unbind(CallbackInfo ci) {
    IrisPipelineStateCapture.global().useProgram(0);
  }

  /** Mirrors the direct GL43C.glDeleteProgram call for generation safety. */
  @Inject(method = "free()V", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$deleteProgram(CallbackInfo ci) {
    IrisShaderCapture.deleteProgram(id);
  }
}

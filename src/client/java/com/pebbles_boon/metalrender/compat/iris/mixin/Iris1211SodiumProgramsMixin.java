package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures the exact shaderpack GLSL Iris feeds into Sodium 0.6.13 after
 * TransformPatcher.patchSodium(...). This is the useful 1.21.1 terrain shader
 * boundary: unlike ProgramBuilder, Sodium terrain programs are built directly
 * from this transformed source map.
 */
@Pseudo
@Mixin(value = SodiumPrograms.class, remap = false)
public abstract class Iris1211SodiumProgramsMixin {
  @Inject(method = "transformShaders", at = @At("RETURN"), require = 0)
  private static void complemetal$captureSodiumTerrainShaders(
      ProgramSource source,
      AlphaTest alphaTest,
      ProgramSet programSet,
      boolean shadow,
      CallbackInfoReturnable<Map<PatchShaderType, String>> callback) {
    if (source == null || callback.getReturnValue() == null
        || !IrisShaderCapture.isEnabled()) {
      return;
    }

    String name = source.getName();
    if (name == null || name.isBlank()) {
      return;
    }

    // Capture the terrain family only. Iris' SodiumPrograms handles terrain,
    // water, entities and shadows through the same transformer.
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    if (!(lower.contains("terrain") || lower.contains("water"))) {
      return;
    }

    Map<PatchShaderType, String> transformed = callback.getReturnValue();
    String vertex = transformed.get(PatchShaderType.VERTEX);
    String tessControl = transformed.get(PatchShaderType.TESS_CONTROL);
    String tessEvaluation = transformed.get(PatchShaderType.TESS_EVALUATION);
    String geometry = transformed.get(PatchShaderType.GEOMETRY);
    String fragment = transformed.get(PatchShaderType.FRAGMENT);

    if (vertex == null && tessControl == null && tessEvaluation == null
        && geometry == null && fragment == null) {
      return;
    }

    IrisShaderCapture.captureGraphicsLink(
        "iris-sodium:" + name + (shadow ? ":shadow" : ""),
        vertex, geometry, tessControl, tessEvaluation, fragment);
  }
}

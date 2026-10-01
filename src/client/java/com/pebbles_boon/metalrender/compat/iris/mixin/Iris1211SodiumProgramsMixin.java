package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.google.common.collect.ImmutableSet;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import java.util.Map;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.client.gl.GlObject;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures the exact shaderpack GLSL Iris feeds into Sodium 0.6.13 after
 * TransformPatcher.patchSodium(...), then pairs that source with the linked
 * Sodium OpenGL program so Complemetal can associate the translated artifact
 * with the real draw-state registration.
 */
@Pseudo
@Mixin(value = SodiumPrograms.class, remap = false)
public abstract class Iris1211SodiumProgramsMixin {
  private static final ThreadLocal<PendingSources> PENDING_SOURCES =
      new ThreadLocal<>();

  @Inject(method = "createGlShaders", at = @At("RETURN"), require = 0)
  private void complemetal$rememberTransformedSodiumShaders(
      String passName,
      Map<PatchShaderType, String> transformed,
      CallbackInfoReturnable<Map<PatchShaderType, GlShader>> callback) {
    if (!IrisShaderCapture.isEnabled()
        || transformed == null || transformed.isEmpty()) {
      PENDING_SOURCES.remove();
      return;
    }

    Map<PatchShaderType, String> copy = Map.copyOf(transformed);
    PENDING_SOURCES.set(new PendingSources(passName, copy));
  }

  @Inject(method = "createShader", at = @At("RETURN"), require = 0)
  private void complemetal$captureLinkedSodiumProgram(
      IrisRenderingPipeline pipeline,
      SodiumPrograms.Pass pass,
      ProgramSource source,
      AlphaTest alphaTest,
      CustomUniforms customUniforms,
      Supplier<ImmutableSet<Integer>> flipState,
      Map<PatchShaderType, GlShader> transformed,
      CallbackInfoReturnable<?> callback) {
    PendingSources pending = PENDING_SOURCES.get();
    PENDING_SOURCES.remove();

    if (!IrisShaderCapture.isEnabled()
        || pending == null
        || source == null
        || callback.getReturnValue() == null
        || !(callback.getReturnValue() instanceof GlObject glObject)) {
      return;
    }

    String name = source.getName();
    if (name == null || name.isBlank()) {
      return;
    }
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    if (!(lower.contains("terrain") || lower.contains("water"))) {
      return;
    }
    if (!pending.matches(pass, name)) {
      return;
    }

    Map<PatchShaderType, String> sources = pending.sources();
    String vertex = sources.get(PatchShaderType.VERTEX);
    String tessControl = sources.get(PatchShaderType.TESS_CONTROL);
    String tessEvaluation = sources.get(PatchShaderType.TESS_EVAL);
    String geometry = sources.get(PatchShaderType.GEOMETRY);
    String fragment = sources.get(PatchShaderType.FRAGMENT);
    if (vertex == null && tessControl == null && tessEvaluation == null
        && geometry == null && fragment == null) {
      return;
    }

    try {
      IrisShaderCapture.captureLinkedGraphicsProgram(
          glObject.handle(),
          "iris-sodium:" + name,
          vertex,
          geometry,
          tessControl,
          tessEvaluation,
          fragment,
          IrisVertexFormats.TERRAIN,
          false);
    } catch (Throwable ignored) {
      // The capture boundary is explicitly fail-open. Iris/Sodium keeps the
      // linked OpenGL program even when Complemetal cannot mirror it.
    }
  }

  private record PendingSources(String passName,
                                Map<PatchShaderType, String> sources) {
    private boolean matches(SodiumPrograms.Pass pass, String sourceName) {
      if (sourceName.equals(passName)) {
        return true;
      }
      String passLower = pass.name().toLowerCase(java.util.Locale.ROOT);
      String sourceLower = sourceName.toLowerCase(java.util.Locale.ROOT);
      if (sourceLower.contains(passLower)) {
        return true;
      }

      // Iris 1.21.1 maps SodiumPrograms.TRANSLUCENT to ProgramId.Water,
      // whose shaderpack source name is gbuffers_water rather than a name
      // containing "translucent". Match the canonical ProgramId source name
      // as well so the water terrain program is not silently dropped.
      try {
        String originalId = pass.getOriginalId().getSourceName()
            .toLowerCase(java.util.Locale.ROOT);
        return sourceLower.contains(originalId);
      } catch (RuntimeException | LinkageError ignored) {
        return false;
      }
    }
  }
}

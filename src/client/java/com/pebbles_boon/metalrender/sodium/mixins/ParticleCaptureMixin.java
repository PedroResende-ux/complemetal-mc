package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.particle.MetalParticleRenderer;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.ParticleGroupAccessor;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.ParticleManagerAccessor;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.NoRenderParticleGroup;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public class ParticleCaptureMixin {
  @Unique
  private int metalrender$captureFrameCount = 0;

  @Inject(method = "extract", at = @At("TAIL"), require = 1)
  private void metalrender$captureParticles(ParticlesRenderState renderState,
      Frustum frustum, Camera camera,
      float tickDelta, CallbackInfo ci) {
    MetalRenderHookState.markParticleCaptureIncomplete();
    if (!MetalRenderClient.isEnabled())
      return;
    MetalRenderConfig config = MetalRenderClient.getConfig();
    if (config == null || !config.enableExperimentalFeatureReplacement)
      return;
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.metalActive())
      return;
    MetalParticleRenderer particleRenderer = worldRenderer.getParticleRenderer();
    if (particleRenderer == null)
      return;
    particleRenderer.discardCapturedParticles();
    if (!particleRenderer.isActive())
      return;
    try {
      particleRenderer.capture((ParticleEngine) (Object) this, camera,
          tickDelta);
      ParticleManagerAccessor accessor = (ParticleManagerAccessor) (Object) this;
      Map<ParticleRenderType, ParticleGroup<?>> particlesMap = accessor.metalrender$getParticles();
      if (particlesMap == null) {
        particleRenderer.discardCapturedParticles();
        return;
      }

      for (ParticleGroup<?> group : particlesMap.values()) {
        if (group == null || group.isEmpty() ||
            group instanceof NoRenderParticleGroup) {
          continue;
        }
        if (!(group instanceof QuadParticleGroup)) {
          particleRenderer.discardCapturedParticles();
          return;
        }
      }

      for (ParticleGroup<?> group : particlesMap.values()) {
        if (group instanceof QuadParticleGroup && !group.isEmpty()) {
          boolean capturedCompletely = particleRenderer.captureParticleList(
              ((ParticleGroupAccessor) (Object) group)
                  .metalrender$getParticles(),
              frustum, camera, tickDelta);
          if (!capturedCompletely) {
            particleRenderer.discardCapturedParticles();
            return;
          }
        }
      }
      MetalRenderHookState.markParticleCaptureComplete();
    } catch (Throwable e) {
      particleRenderer.discardCapturedParticles();
      MetalRenderHookState.failOpenParticles("particle-capture", e);
      if (metalrender$captureFrameCount < 5) {
        MetalLogger.error("[particlemix] cap fail: %s", e.getMessage());
      }
      return;
    }
    metalrender$captureFrameCount++;
    if (MetalRenderConfig.isDeepDebugActive() &&
        (metalrender$captureFrameCount <= 3 ||
            metalrender$captureFrameCount % 500 == 0)) {
      MetalLogger.info(
          "[particlemix] cap frame %d",
          metalrender$captureFrameCount);
    }
  }
}

package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.1 frame boundary for Complemetal.
 *
 * <p>The Metal frame starts at the beginning of LevelRenderer.renderLevel().
 * When fast terrain replacement is active, presentation happens immediately
 * after the vanilla/Sodium cutout terrain layer and before entities are drawn.
 * The depth buffer is restored from Metal at that same point.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererBlitMixin {
  @Unique
  private boolean metalrender$frameActive;

  @Unique
  private boolean metalrender$frameEnded;

  @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
  private void metalrender$beginWorldFrame(
      DeltaTracker deltaTracker,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f positionMatrix,
      Matrix4f projectionMatrix,
      CallbackInfo ci) {
    MetalRenderHookState.beginFrameAttempt();
    metalrender$frameActive = false;
    metalrender$frameEnded = false;

    if (!MetalRenderHookState.isGraphicsBackendSupported()
        || !MetalRenderClient.isEnabled()) {
      return;
    }

    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (worldRenderer == null || renderer == null
        || renderer.getHandle() == 0
        || !worldRenderer.metalActive()
        || worldRenderer.isIrisCompatibilityPaused()) {
      return;
    }

    try {
      float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(true);
      CapturedMatrices.capture(projectionMatrix, positionMatrix,
          camera.position().x, camera.position().y, camera.position().z);

      worldRenderer.beginFrame(camera, tickDelta,
          projectionMatrix, positionMatrix);

      if (renderer.frameCtx() == 0) {
        MetalRenderHookState.failOpen("begin-frame-context", null);
        return;
      }

      MetalRenderHookState.markFramePrepared();
      metalrender$frameActive = true;
    } catch (Throwable error) {
      MetalRenderHookState.failOpen("world-frame-encode", error);
    }
  }

  @Inject(method = "renderLevel",
      at = @At(value = "INVOKE",
          target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
          ordinal = 2,
          shift = At.Shift.AFTER),
      require = 0)
  private void metalrender$presentBeforeEntities(
      DeltaTracker deltaTracker,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f positionMatrix,
      Matrix4f projectionMatrix,
      CallbackInfo ci) {
    if (!metalrender$frameActive || metalrender$frameEnded) {
      return;
    }

    // When the experimental terrain replacement is disabled, retain the
    // upstream frame lifetime. Nothing is presented into the vanilla buffer.
    var config = MetalRenderClient.getConfig();
    if (config == null || !config.enableFastTerrainReplacement) {
      return;
    }

    try {
      MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
      Minecraft mc = Minecraft.getInstance();
      if (worldRenderer == null || !worldRenderer.metalActive()
          || mc == null || mc.getWindow() == null) {
        MetalRenderHookState.failOpen("world-renderer-missing-before-entities", null);
        worldRenderer = null;
        return;
      }

      // The Sodium terrain mixin has already verified that this Metal frame
      // has usable geometry. End native encoding before transferring ownership
      // back to the Minecraft OpenGL framebuffer.
      worldRenderer.endFrame();
      metalrender$frameEnded = true;
      MetalRenderHookState.markFrameFinished();

      if (!MetalRenderHookState.canPresentFrame()) {
        return;
      }

      if (!worldRenderer.forceBlitNow()) {
        MetalRenderHookState.markPresentationAttemptFailed(
            "terrain-presentation-before-entities", null);
        return;
      }

      // Keep vanilla entities/block entities depth-tested against Metal terrain.
      // This is a compatibility path; the readback cost is avoided completely
      // when fast terrain replacement is not active.
      worldRenderer.forceBlitDepthNow(
          mc.getWindow().getWidth(), mc.getWindow().getHeight());
      MetalRenderHookState.markPresentationSucceeded();
    } catch (Throwable error) {
      MetalLogger.warn("1.21.1 terrain presentation before entities failed: %s",
          error.getMessage());
      MetalRenderHookState.failOpen(
          "terrain-presentation-before-entities", error);
      metalrender$frameEnded = true;
      metalrender$frameActive = false;
    }
  }

  @Inject(method = "renderLevel", at = @At("RETURN"), require = 0)
  private void metalrender$finishWorldFrameFallback(
      DeltaTracker deltaTracker,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f positionMatrix,
      Matrix4f projectionMatrix,
      CallbackInfo ci) {
    if (!metalrender$frameActive || metalrender$frameEnded) {
      return;
    }

    try {
      MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
      if (worldRenderer != null && worldRenderer.metalActive()) {
        worldRenderer.endFrame();
        MetalRenderHookState.markFrameFinished();
      } else {
        MetalRenderHookState.failOpen("world-renderer-missing-at-end", null);
      }
    } catch (Throwable error) {
      MetalLogger.warn("1.21.1 world-frame cleanup failed: %s",
          error.getMessage());
      MetalRenderHookState.failOpen("world-frame-cleanup", error);
    } finally {
      metalrender$frameEnded = true;
      metalrender$frameActive = false;
    }
  }
}

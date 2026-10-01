package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
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
 * <p>1.21.1 keeps the world pass in LevelRenderer.renderLevel(...). This is
 * the closest equivalent to the 26.2 LevelRenderer.render(...) hook and
 * occurs before GameRenderer continues with the hand/UI passes.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererBlitMixin {
  @Unique
  private boolean metalrender$frameActive;

  @Inject(method = "renderLevel", at = @At("HEAD"), require = 1)
  private void metalrender$beginWorldFrame(
      DeltaTracker deltaTracker,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f frustumMatrix,
      Matrix4f projectionMatrix,
      CallbackInfo ci) {
    MetalRenderHookState.beginFrameAttempt();
    metalrender$frameActive = false;

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
      CapturedMatrices.capture(projectionMatrix, frustumMatrix,
          camera.position().x, camera.position().y, camera.position().z);

      worldRenderer.beginFrame(camera, tickDelta,
          projectionMatrix, frustumMatrix);

      if (renderer.frameCtx() == 0) {
        MetalRenderHookState.failOpen("begin-frame-context", null);
        return;
      }

      MetalRenderHookState.markFramePrepared();
      metalrender$frameActive = true;
    } catch (Throwable error) {
      MetalRenderHookState.failOpen("world-frame-encode", error);
      metalrender$frameActive = false;
    }
  }

  @Inject(method = "renderLevel", at = @At("RETURN"), require = 1)
  private void metalrender$finishWorldFrame(
      DeltaTracker deltaTracker,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f projectionMatrix,
      Matrix4f positionMatrix,
      CallbackInfo ci) {
    if (!metalrender$frameActive) {
      return;
    }

    try {
      MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
      if (worldRenderer == null || !worldRenderer.metalActive()) {
        MetalRenderHookState.failOpen("world-renderer-missing-at-end", null);
        return;
      }

      worldRenderer.endFrame();
      MetalRenderHookState.markFrameFinished();

      if (MetalRenderHookState.canPresentFrame()) {
        if (worldRenderer.forceBlitNow()) {
          MetalRenderHookState.markPresentationSucceeded();
        } else {
          MetalRenderHookState.markPresentationAttemptFailed(
              "level-render-presentation", null);
        }
      }
    } catch (Throwable error) {
      MetalLogger.warn("1.21.1 world-frame finish failed: %s",
          error.getMessage());
      MetalRenderHookState.failOpen("world-frame-finish", error);
    } finally {
      metalrender$frameActive = false;
    }
  }
}

package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity extraction moved from LevelRenderer to LevelExtractor in 26.2.
 */
@Mixin(LevelExtractor.class)
public abstract class EntityCaptureMixin {
  @Unique
  private int metalrender$entityCaptureCount;
  @Unique
  private long metalrender$entityCaptureFrame;
  @Unique
  private final Matrix4f metalrender$reusableModelMatrix = new Matrix4f();

  @Inject(method = "extractVisibleEntities", at = @At("TAIL"), require = 1)
  private void metalrender$captureEntities(Camera camera, Frustum frustum,
      DeltaTracker deltaTracker, LevelRenderState levelRenderState,
      CallbackInfo ci) {
    MetalRenderHookState.markEntityCaptureIncomplete();
    if (!MetalRenderClient.isEnabled()) {
      return;
    }
    MetalRenderConfig config = MetalRenderClient.getConfig();
    if (config == null || !config.enableExperimentalFeatureReplacement) {
      return;
    }
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.metalActive()) {
      return;
    }
    MetalEntityRenderer entityRenderer = worldRenderer.getEntityRenderer();
    if (entityRenderer == null) {
      return;
    }
    entityRenderer.clearCapturedEntities();
    if (!entityRenderer.isActive()) {
      return;
    }
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft == null || minecraft.level == null) {
      return;
    }

    float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(true);
    try {
      metalrender$entityCaptureFrame++;
      int capturedThisFrame = 0;
      boolean complete = true;
      Entity focused = camera.entity();
      for (Entity entity : minecraft.level.entitiesForRendering()) {
        if (entity == null || entity.isRemoved()) {
          continue;
        }
        if (entity == focused && !camera.isDetached()) {
          continue;
        }
        if (!frustum.isVisible(entity.getBoundingBox())) {
          continue;
        }
        metalrender$reusableModelMatrix.identity();
        if (!entityRenderer.captureEntity(entity, tickDelta,
            metalrender$reusableModelMatrix)) {
          complete = false;
          break;
        }
        capturedThisFrame++;
      }

      if (!complete) {
        entityRenderer.clearCapturedEntities();
        if (MetalRenderConfig.isDeepDebugActive() &&
            (metalrender$entityCaptureFrame <= 5 ||
                metalrender$entityCaptureFrame % 600 == 0)) {
          MetalLogger.warn(
              "[entitymix] capture capacity reached after %d entities; " +
                  "discarding partial Metal capture and keeping vanilla",
              capturedThisFrame);
        }
        return;
      }
      metalrender$entityCaptureCount += capturedThisFrame;
      MetalRenderHookState.markEntityCaptureComplete();
      if (MetalRenderConfig.isDeepDebugActive() &&
          capturedThisFrame > 0 &&
          (metalrender$entityCaptureFrame <= 5 ||
              metalrender$entityCaptureFrame % 600 == 0)) {
        MetalLogger.info("[entitymix] complete=%d (total=%d)",
            capturedThisFrame, metalrender$entityCaptureCount);
      }
    } catch (Throwable error) {
      entityRenderer.clearCapturedEntities();
      MetalRenderHookState.failOpenEntities("entity-capture", error);
    }
  }
}

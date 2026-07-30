package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.compat.IrisCompatibility;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererBlitMixin {
  @Unique
  private final Matrix4f metalrender$projection = new Matrix4f();
  @Unique
  private final Matrix4f metalrender$modelView = new Matrix4f();
  @Unique
  private boolean metalrender$frameActive;
  @Unique
  private int metalrender$beginFrameCount;
  @Unique
  private int metalrender$endFrameCount;

  @Inject(method = "render", at = @At("HEAD"), require = 1)
  private void metalrender$beginWorldFrame(
      GraphicsResourceAllocator allocator, DeltaTracker tickCounter,
      boolean renderBlockOutline, CameraRenderState cameraRenderState,
      Matrix4fc positionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor,
      boolean renderEntityOutline, CallbackInfo ci) {
    MetalRenderHookState.beginFrameAttempt();
    metalrender$frameActive = false;
    if (!MetalRenderHookState.isGraphicsBackendSupported() ||
        !MetalRenderClient.isEnabled()) {
      return;
    }
    if (IrisCompatibility.requiresShaderCompatibilityMode()) {
      MetalRenderer renderer = MetalRenderClient.getRenderer();
      if (renderer != null && renderer.getHandle() != 0) {
        NativeBridge.nRecycleUnpresentedFrames(renderer.getHandle());
      }
      return;
    }
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.metalActive()) {
      return;
    }
    try {
      MetalRenderer renderer = MetalRenderClient.getRenderer();
      if (renderer == null || renderer.getHandle() == 0) {
        return;
      }
      // A ready frame left at the start of the next LevelRenderer frame missed
      // its presentation opportunity. Recycle it whether safe hybrid mode,
      // screenshot suppression, or a transient blit failure caused the miss.
      // In-flight and OpenGL-bound surfaces remain protected.
      NativeBridge.nRecycleUnpresentedFrames(renderer.getHandle());
      Minecraft mc = Minecraft.getInstance();
      Camera camera = mc.gameRenderer.mainCamera();
      if (camera == null || camera.position() == null) {
        return;
      }
      float tickDelta = tickCounter.getGameTimeDeltaPartialTick(true);
      if (cameraRenderState != null &&
          cameraRenderState.projectionMatrix != null) {
        metalrender$projection.set(cameraRenderState.projectionMatrix);
      } else {
        metalrender$projection.set(positionMatrix);
      }
      Vec3 camPos = (cameraRenderState != null && cameraRenderState.pos != null)
          ? cameraRenderState.pos
          : camera.position();
      if (cameraRenderState != null &&
          cameraRenderState.viewRotationMatrix != null) {
        metalrender$modelView.set(cameraRenderState.viewRotationMatrix);
      } else {
        metalrender$modelView.identity();
        metalrender$modelView.rotateX((float) Math.toRadians(camera.xRot()));
        metalrender$modelView.rotateY(
            (float) Math.toRadians(camera.yRot() + 180.0f));
      }
      CapturedMatrices.capture(metalrender$projection, metalrender$modelView,
          camPos.x, camPos.y, camPos.z);
      worldRenderer.beginFrame(camera, tickDelta, metalrender$projection,
          metalrender$modelView);
      if (renderer.frameCtx() == 0) {
        MetalRenderHookState.failOpen("begin-frame-context", null);
        return;
      }
      com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance().startRender();
      MetalRenderHookState.markFramePrepared();
      metalrender$frameActive = true;
      metalrender$beginFrameCount++;
      if (metalrender$beginFrameCount <= 3) {
        MetalLogger.info("[blitmix] begin hook #%d",
            metalrender$beginFrameCount);
      }
      worldRenderer.endFrame();
      MetalRenderHookState.markFrameFinished();
      metalrender$frameActive = false;
      com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance().endRender();
      metalrender$endFrameCount++;
      if (metalrender$endFrameCount <= 3) {
        MetalLogger.info("[blitmix] encoded hook #%d",
            metalrender$endFrameCount);
      }
    } catch (Throwable e) {
      if (metalrender$frameActive) {
        try {
          com.pebbles_boon.metalrender.performance.MetalRenderProfiler
              .getInstance().endRender();
        } catch (Throwable ignored) {
        }
      }
      metalrender$frameActive = false;
      MetalRenderHookState.failOpen("world-frame-encode", e);
      MetalLogger.error("[blitmix] frame encode fail: %s", e.getMessage());
    }
  }
}

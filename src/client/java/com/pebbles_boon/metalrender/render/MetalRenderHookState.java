package com.pebbles_boon.metalrender.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * Coordinates the vanilla extraction hooks with the Metal replacement.
 *
 * <p>The renderer is deliberately fail-open across frames: a vanilla draw is
 * only suppressed after the current Metal frame was prepared and at least one
 * previous frame was presented successfully. Capture failures retain vanilla
 * in the current frame. A late encode or presentation failure cannot restore
 * submissions already skipped in that same frame, but resets the handshake so
 * the following frame returns to vanilla.</p>
 */
public final class MetalRenderHookState {
  private enum GraphicsBackend {
    UNKNOWN,
    OPENGL,
    UNSUPPORTED
  }

  private static GraphicsBackend graphicsBackend = GraphicsBackend.UNKNOWN;
  private static String graphicsBackendName = "not initialized";
  private static boolean backendDiagnosticLogged;
  private static boolean unknownBackendDiagnosticLogged;
  private static long frameId;
  private static boolean framePrepared;
  private static boolean frameFinished;
  private static boolean framePresented;
  private static boolean presentationReady;
  private static boolean entityCaptureComplete;
  private static boolean particleCaptureComplete;
  private static boolean pendingEntityCaptureComplete;
  private static boolean pendingParticleCaptureComplete;
  private static int screenshotFallbackFrames;
  private static long lastFailureLogNanos;
  private static MetalWorldRenderer activeWorldRenderer;

  private MetalRenderHookState() {
  }

  /**
   * Returns true only for Mojang's OpenGL device. Vulkan is intentionally
   * rejected because the current IOSurface bridge requires a CGL context.
   */
  public static boolean isGraphicsBackendSupported() {
    if (graphicsBackend == GraphicsBackend.OPENGL) {
      return true;
    }
    if (graphicsBackend == GraphicsBackend.UNSUPPORTED) {
      return false;
    }

    String backendDescription;
    try {
      var device = RenderSystem.tryGetDevice();
      backendDescription = device == null || device.getDeviceInfo() == null
          ? null
          : device.getDeviceInfo().backendName();
    } catch (Throwable error) {
      return false;
    }
    if (backendDescription == null || backendDescription.isBlank()) {
      return false;
    }

    String normalized = backendDescription.toLowerCase(Locale.ROOT);
    graphicsBackendName = backendDescription;
    if (normalized.contains("vulkan") || normalized.contains(".vk.")) {
      graphicsBackend = GraphicsBackend.UNSUPPORTED;
      logBackendDiagnostic(false);
      return false;
    }
    if (normalized.contains("opengl")) {
      graphicsBackend = GraphicsBackend.OPENGL;
      logBackendDiagnostic(true);
      return true;
    }

    if (!unknownBackendDiagnosticLogged) {
      unknownBackendDiagnosticLogged = true;
      MetalLogger.warn(
          "graphics backend %s is unknown; keeping vanilla renderer active",
          graphicsBackendName);
    }
    return false;
  }

  public static String graphicsBackendName() {
    return graphicsBackendName;
  }

  public static void resetSession() {
    framePrepared = false;
    frameFinished = false;
    framePresented = false;
    presentationReady = false;
    entityCaptureComplete = false;
    particleCaptureComplete = false;
    pendingEntityCaptureComplete = false;
    pendingParticleCaptureComplete = false;
    screenshotFallbackFrames = 0;
  }

  public static void beginFrameAttempt() {
    MetalWorldRenderer currentWorldRenderer =
        MetalRenderClient.getWorldRenderer();
    if (currentWorldRenderer != activeWorldRenderer) {
      resetSession();
      activeWorldRenderer = currentWorldRenderer;
    }
    frameId++;
    framePrepared = false;
    frameFinished = false;
    framePresented = false;
    entityCaptureComplete = pendingEntityCaptureComplete;
    particleCaptureComplete = pendingParticleCaptureComplete;
    pendingEntityCaptureComplete = false;
    pendingParticleCaptureComplete = false;
    if (isScreenshotRequested()) {
      screenshotFallbackFrames = 4;
    } else if (screenshotFallbackFrames > 0) {
      screenshotFallbackFrames--;
    }
  }

  public static void markFramePrepared() {
    if (isGraphicsBackendSupported()) {
      framePrepared = true;
    }
  }

  public static void markFrameFinished() {
    frameFinished = framePrepared;
  }

  public static void markEntityCaptureComplete() {
    pendingEntityCaptureComplete = true;
  }

  public static void markEntityCaptureIncomplete() {
    pendingEntityCaptureComplete = false;
  }

  public static void markParticleCaptureComplete() {
    pendingParticleCaptureComplete = true;
  }

  public static void markParticleCaptureIncomplete() {
    pendingParticleCaptureComplete = false;
  }

  public static void markPresentationSucceeded() {
    if (framePrepared && frameFinished) {
      presentationReady = true;
      framePresented = true;
    }
  }

  public static void failOpen(String stage, Throwable error) {
    framePrepared = false;
    frameFinished = false;
    framePresented = false;
    presentationReady = false;
    entityCaptureComplete = false;
    particleCaptureComplete = false;
    pendingEntityCaptureComplete = false;
    pendingParticleCaptureComplete = false;
    logHookFailure(stage, error);
  }

  public static void failOpenEntities(String stage, Throwable error) {
    entityCaptureComplete = false;
    pendingEntityCaptureComplete = false;
    logHookFailure(stage, error);
  }

  public static void failOpenParticles(String stage, Throwable error) {
    particleCaptureComplete = false;
    pendingParticleCaptureComplete = false;
    logHookFailure(stage, error);
  }

  public static void markPresentationAttemptFailed(String stage,
      Throwable error) {
    presentationReady = false;
    framePresented = false;
    logHookFailure(stage, error);
  }

  private static void logHookFailure(String stage, Throwable error) {
    long now = System.nanoTime();
    if (now - lastFailureLogNanos < 1_000_000_000L) {
      return;
    }
    lastFailureLogNanos = now;
    String reason = error == null ? "hook unavailable"
        : error.getClass().getSimpleName() + ": " + error.getMessage();
    MetalLogger.warn(
        "Metal hook '%s' failed open on frame %d (%s); vanilla stays active",
        stage, frameId, reason);
  }

  public static boolean canReplaceTerrain() {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    return config != null && config.enableFastTerrainReplacement &&
        screenshotFallbackFrames == 0 &&
        isMetalFrameUsable() && presentationReady;
  }

  public static boolean canReplaceEntities() {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    return config != null && config.enableExperimentalFeatureReplacement &&
        canReplaceTerrain() && entityCaptureComplete;
  }

  public static boolean canReplaceParticles() {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    return config != null && config.enableExperimentalFeatureReplacement &&
        canReplaceTerrain() && particleCaptureComplete;
  }

  public static boolean canPresentFrame() {
    return !framePresented && screenshotFallbackFrames == 0 &&
        isMetalFrameUsable() && frameFinished;
  }

  private static boolean isMetalFrameUsable() {
    return framePrepared && isMetalSessionUsable();
  }

  private static boolean isMetalSessionUsable() {
    if (!isGraphicsBackendSupported() || !MetalRenderClient.isEnabled()) {
      return false;
    }
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    return worldRenderer != null && worldRenderer.metalActive() &&
        worldRenderer.areTexturesReady();
  }

  private static boolean isScreenshotRequested() {
    try {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft != null && minecraft.options != null &&
          minecraft.options.keyScreenshot != null &&
          minecraft.options.keyScreenshot.isDown();
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static void logBackendDiagnostic(boolean supported) {
    if (backendDiagnosticLogged) {
      return;
    }
    backendDiagnosticLogged = true;
    if (supported) {
      MetalLogger.info("graphics backend: %s (Metal IOSurface bridge enabled)",
          graphicsBackendName);
    } else {
      MetalLogger.warn(
          "graphics backend: %s; MetalRender is disabled for this session " +
              "because the IOSurface bridge currently requires OpenGL",
          graphicsBackendName);
    }
  }
}

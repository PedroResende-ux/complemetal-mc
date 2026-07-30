package com.pebbles_boon.metalrender.compat;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional Iris integration without a hard runtime dependency.
 *
 * <p>MetalRender's terrain replacement is composited after Minecraft's
 * OpenGL world pass. An active Iris shader pack needs to own that terrain pass
 * so its g-buffer, shadow and post-processing stages see the complete scene.
 * Consequently the safe compatibility policy is to keep the Metal runtime
 * initialized, but suspend Metal terrain encoding/presentation while a shader
 * pack is active.</p>
 */
public final class IrisCompatibility {
  private static final String IRIS_API_CLASS =
      "net.irisshaders.iris.api.v0.IrisApi";

  private static volatile boolean lookupAttempted;
  private static volatile boolean lookupFailed;
  private static volatile Object irisApi;
  private static volatile Method shaderPackInUseMethod;
  private static volatile Method renderingShadowPassMethod;
  private static volatile Boolean lastCompatibilityMode;

  private IrisCompatibility() {
  }

  public static boolean isIrisLoaded() {
    try {
      return FabricLoader.getInstance().isModLoaded("iris");
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * Returns true when Iris must retain exclusive ownership of terrain output.
   *
   * <p>If Iris is present but its public API cannot be queried, this method
   * fails closed to vanilla/Iris rendering instead of risking a broken shader
   * frame.</p>
   */
  public static boolean requiresShaderCompatibilityMode() {
    if (!isIrisLoaded()) {
      updateLoggedState(false, "Iris not installed");
      return false;
    }

    ensureApiLookup();
    boolean compatibilityMode = true;
    String reason = "Iris API unavailable";
    Method method = shaderPackInUseMethod;
    Object api = irisApi;
    if (!lookupFailed && method != null && api != null) {
      try {
        boolean shaderPackActive = Boolean.TRUE.equals(method.invoke(api));
        boolean shadowPassActive = renderingShadowPassMethod != null &&
            Boolean.TRUE.equals(renderingShadowPassMethod.invoke(api));
        compatibilityMode = shaderPackActive || shadowPassActive;
        reason = compatibilityMode
            ? (shadowPassActive
                ? "Iris shadow pass active"
                : "Iris shader pack active")
            : "Iris installed, shader pack disabled";
      } catch (Throwable error) {
        lookupFailed = true;
        reason = "Iris API query failed";
        MetalLogger.warn(
            "Iris shader state query failed; keeping Iris-safe terrain path: %s",
            safeMessage(error));
      }
    }
    updateLoggedState(compatibilityMode, reason);
    return compatibilityMode;
  }

  public static String statusDescription() {
    if (!isIrisLoaded()) {
      return "Not installed";
    }
    if (requiresShaderCompatibilityMode()) {
      return lookupFailed
          ? "Installed - compatibility mode (API unavailable)"
          : "Shader pack active - vanilla/Iris terrain";
    }
    return "Installed - shader pack disabled";
  }

  private static void ensureApiLookup() {
    if (lookupAttempted) {
      return;
    }
    synchronized (IrisCompatibility.class) {
      if (lookupAttempted) {
        return;
      }
      lookupAttempted = true;
      try {
        Class<?> apiClass = Class.forName(IRIS_API_CLASS, false,
            IrisCompatibility.class.getClassLoader());
        Method getInstance = apiClass.getMethod("getInstance");
        Object api = getInstance.invoke(null);
        Method shaderPackMethod = apiClass.getMethod("isShaderPackInUse");
        Method shadowPassMethod = apiClass.getMethod("isRenderingShadowPass");
        irisApi = api;
        shaderPackInUseMethod = shaderPackMethod;
        renderingShadowPassMethod = shadowPassMethod;
        lookupFailed = false;
        MetalLogger.info("Iris public API detected; shader compatibility guard ready");
      } catch (Throwable error) {
        lookupFailed = true;
        MetalLogger.warn(
            "Iris public API unavailable; keeping Iris-safe terrain path: %s",
            safeMessage(error));
      }
    }
  }

  private static void updateLoggedState(boolean compatibilityMode,
      String reason) {
    Boolean previous = lastCompatibilityMode;
    if (previous != null && previous == compatibilityMode) {
      return;
    }
    lastCompatibilityMode = compatibilityMode;
    if (compatibilityMode) {
      MetalLogger.info(
          "Iris compatibility mode enabled (%s); Metal terrain presentation suspended",
          reason);
    } else if (isIrisLoaded()) {
      MetalLogger.info(
          "Iris compatibility mode disabled (%s); Metal terrain may resume",
          reason);
    }
  }

  private static String safeMessage(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return root.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }
}

package com.pebbles_boon.metalrender;

import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.command.MetalRenderCommands;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.culling.AsyncCullTask;
import com.pebbles_boon.metalrender.gui.MetalDebugEntry;
import com.pebbles_boon.metalrender.gui.MetalRenderProfilerOverlay;
import com.pebbles_boon.metalrender.gui.MetalRenderSettingsScreen;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.performance.SimulationDistanceOverride;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.unified.MetalRenderCoordinator;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.sodium.backend.SodiumMetalInterface;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

public class MetalRenderClient implements ClientModInitializer {
  private static final int FPS_PRIORITY_SIMULATION_DISTANCE = 5;
  private static final SimulationDistanceOverride SIMULATION_DISTANCE_OVERRIDE =
      new SimulationDistanceOverride(FPS_PRIORITY_SIMULATION_DISTANCE);

  public enum InitState {
    NOT_TRIED,
    INITIALIZING,
    READY,
    UNSUPPORTED,
    FAILED
  }

  private static MetalRenderClient instance;
  private static MetalRenderer renderer;
  private static MetalRenderConfig config;
  private static MetalRenderCoordinator coordinator;
  private static MeshShaderBackend meshShaderBackend;
  private static SodiumMetalInterface sodiumInterface;
  private static MetalWorldRenderer worldRenderer;
  private static boolean metalUp;
  private static boolean cfgWasOn;
  private static boolean cfgSyncPending;
  private static boolean runtimeApplyPending;
  private static boolean levelRendererRefreshPending;
  private static boolean worldRendererRefreshPending;
  private static boolean debugEntryStatusSet;
  private static volatile InitState initState = InitState.NOT_TRIED;
  private static volatile String initFailure;
  private static boolean terminalShutdown;
  private static int displayTargetPollTicks;
  private static int lastConfiguredTargetFrameRate = -1;

  @Override
  public void onInitializeClient() {
    if (StartupBlocker.shouldBlockStartup()) {
      return;
    }
    instance = this;
    terminalShutdown = false;
    MetalLogger.info("metalrender ready");
    startIrisTranslationIfEnabled();
    config = MetalRenderConfig.load();
    cfgWasOn = config != null && config.enableMetalRendering;
    MetalDebugEntry.register();
    MetalRenderProfilerOverlay.register();
    if (MetalRenderConfig.isDeepDebugActive()) {
      MetalLogger.info("deep debug on");
    }
    MetalRenderCommands.register();
    if (!config.enableMetalRendering) {
      MetalLogger.info("metalrender off");
    }

    ClientTickEvents.START_CLIENT_TICK.register(client -> {
      var mc = Minecraft.getInstance();
      if (!debugEntryStatusSet && mc != null) {
        MetalDebugEntry.show(mc);
        debugEntryStatusSet = true;
      }
      if (cfgSyncPending) {
        cfgSyncPending = false;
        syncCfg(mc);
      }
      applyDeferredRuntimeChanges(mc);
      requestDisplayTargetRefreshIfNeeded();
      applyFpsPriorityMode(mc);
      syncCfg(mc);
      if (config != null && config.enableMetalRendering && renderer == null &&
          mc != null && initState == InitState.NOT_TRIED) {
        initMetal(mc);
      }
    });
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> shutdownForClientExit());
  }

  public static void requestDeferredApply(boolean requestCfgSync,
      boolean refreshLevelRenderer,
      boolean refreshWorldRenderer) {
    runtimeApplyPending = true;
    cfgSyncPending |= requestCfgSync;
    levelRendererRefreshPending |= refreshLevelRenderer;
    worldRendererRefreshPending |= refreshWorldRenderer;
  }

  private static void applyDeferredRuntimeChanges(Minecraft mc) {
    if (!runtimeApplyPending || config == null) {
      return;
    }

    runtimeApplyPending = false;
    if (NativeBridge.isLibLoaded()) {
      boolean useArgBufs = config.enableArgumentBuffers || config.enableIndirectCommandBuffers;
      NativeBridge.nSetFeatureFlags(config.enableIndirectCommandBuffers,
          config.enableMeshShaders, useArgBufs,
          config.enableProgrammableBlending);
      if (!configureNativeRuntime()) {
        shutdownRenderer();
        MetalRenderHookState.resetSession();
        levelRendererRefreshPending = false;
        worldRendererRefreshPending = false;
        return;
      }
    }

    MetalWorldRenderer wr = worldRenderer;
    if (wr != null) {
      wr.applyFeatureConfig(config);
    }

    if (levelRendererRefreshPending && mc != null && mc.levelRenderer != null) {
      mc.levelRenderer.resetLevelRenderData();
    }
    levelRendererRefreshPending = false;

    if (worldRendererRefreshPending) {
      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nFlushFrames();
      }
      if (wr != null) {
        wr.onConfigScreenClosed();
      }
    }
    worldRendererRefreshPending = false;
  }

  public static void syncCfg(Minecraft mc) {
    boolean cfgOn = config != null && config.enableMetalRendering;
    if (cfgOn == cfgWasOn || mc == null) {
      return;
    }
    cfgWasOn = cfgOn;

    if (!cfgOn) {
      shutdownRenderer();
      initState = InitState.NOT_TRIED;
      return;
    }

    if (renderer == null || !metalUp) {
      initMetal(mc);
    } else if (worldRenderer == null) {
      worldRenderer = new MetalWorldRenderer();
    }

    if (worldRenderer != null && mc.level != null) {
      worldRenderer.onWorldLoad();
      worldRenderer.onConfigScreenClosed();
    }
  }

  private static void applyFpsPriorityMode(Minecraft mc) {
    if (mc == null || mc.options == null || config == null) {
      return;
    }
    try {
      if (config.prioritizeFpsOverTps) {
        int currentDistance = mc.options.simulationDistance().get();
        int liveDistance =
            SIMULATION_DISTANCE_OVERRIDE.enable(currentDistance);
        if (currentDistance != liveDistance) {
          mc.options.simulationDistance().set(liveDistance);
        }
      } else {
        restoreFpsPriorityMode(mc, true);
      }
    } catch (Exception e) {
      MetalLogger.warn("FPS priority update failed: %s", e.getMessage());
    }
  }

  private static void restoreFpsPriorityMode(Minecraft mc,
      boolean persistRestoredValue) {
    if (mc == null || mc.options == null ||
        !SIMULATION_DISTANCE_OVERRIDE.isActive()) {
      return;
    }
    int currentDistance = mc.options.simulationDistance().get();
    int restoredDistance =
        SIMULATION_DISTANCE_OVERRIDE.disable(currentDistance);
    mc.options.simulationDistance().set(restoredDistance);
    if (persistRestoredValue) {
      mc.options.save();
    }
  }

  public static int getPreferredSimulationDistance(Options options) {
    if (options == null) {
      return FPS_PRIORITY_SIMULATION_DISTANCE;
    }
    return SIMULATION_DISTANCE_OVERRIDE.preferredOr(
        options.simulationDistance().get());
  }

  public static void applySimulationDistanceFromSettings(Options options,
      int requestedDistance) {
    if (options == null) {
      return;
    }
    options.simulationDistance().set(requestedDistance);
    int normalizedDistance = options.simulationDistance().get();
    boolean overrideEnabled =
        config != null && config.prioritizeFpsOverTps;
    int liveDistance = SIMULATION_DISTANCE_OVERRIDE.setPreferred(
        normalizedDistance, overrideEnabled);
    options.simulationDistance().set(liveDistance);
  }

  /**
   * Persists all vanilla options while keeping the FPS-priority clamp live.
   * The temporary value of five chunks is never written as the player's
   * simulation-distance preference.
   */
  public static void saveOptionsPreservingFpsPriority(Options options) {
    if (options == null) {
      return;
    }
    options.save();
  }

  public static void prepareOptionsSave(Options options) {
    if (options == null || !SIMULATION_DISTANCE_OVERRIDE.isActive()) {
      return;
    }
    int currentDistance = options.simulationDistance().get();
    options.simulationDistance().set(
        SIMULATION_DISTANCE_OVERRIDE.preferredOr(currentDistance));
  }

  public static void finishOptionsSave(Options options) {
    if (options == null || !SIMULATION_DISTANCE_OVERRIDE.isActive()) {
      return;
    }
    int persistedDistance = options.simulationDistance().get();
    options.simulationDistance().set(
        SIMULATION_DISTANCE_OVERRIDE.liveOr(persistedDistance));
  }

  private static void drainRenderer() {
    if (renderer == null || !renderer.isAvailable() ||
        !NativeBridge.isLibLoaded()) {
      return;
    }
    try {
      NativeBridge.nFlushFrames();
      NativeBridge.nWaitForRender(renderer.getHandle());
    } catch (Throwable t) {
      MetalLogger.warn("drain fail: %s", t.getMessage());
    }
  }

  public static void openSettingsScreen(Minecraft mc) {
    if (mc == null) {
      return;
    }
    try {
      mc.execute(() -> mc.gui.setScreen(new MetalRenderSettingsScreen(mc.gui.screen())));
      MetalLogger.info("settings screen opened");
    } catch (Exception e) {
      MetalLogger.warn("settings screen open failed: %s", e.getMessage());
    }
  }

  private static void initMetal(Minecraft mc) {
    if (initState == InitState.INITIALIZING || initState == InitState.READY) {
      return;
    }
    initState = InitState.INITIALIZING;
    initFailure = null;

    if (!StartupBlocker.isPlatformSupported()) {
      initState = InitState.UNSUPPORTED;
      initFailure = StartupBlocker.unsupportedReason();
      metalUp = false;
      MetalLogger.warn("%s; vanilla renderer remains active", initFailure);
      return;
    }

    if (!MetalRenderHookState.isGraphicsBackendSupported()) {
      initState = InitState.UNSUPPORTED;
      initFailure = "graphics backend " + MetalRenderHookState.graphicsBackendName()
          + " is not supported by the IOSurface bridge";
      metalUp = false;
      MetalLogger.warn("%s; vanilla renderer remains active", initFailure);
      return;
    }

    try {
      NativeBridge.loadLibrary();
    } catch (Throwable e) {
      MetalLogger.error("lib load fail.", e);
      initState = NativeBridge.getLoadState() == NativeBridge.LoadState.UNSUPPORTED
          ? InitState.UNSUPPORTED
          : InitState.FAILED;
      initFailure = NativeBridge.getLoadFailure();
      return;
    }

    try {
      if (!MetalHardwareChecker.isMetalSupported()) {
        MetalLogger.warn("no metal");
        initState = InitState.UNSUPPORTED;
        initFailure = "Metal device unavailable";
        return;
      }

      renderer = new MetalRenderer();
      var win = mc.getWindow();
      int w = win != null ? win.getWidth() : 0;
      int h = win != null ? win.getHeight() : 0;
      renderer.init(w, h);
      metalUp = renderer.isAvailable();
      if (!metalUp) {
        initState = InitState.FAILED;
        initFailure = "native renderer initialization returned no handle";
        return;
      }

      if (!configureNativeRuntime()) {
        throw new IllegalStateException(initFailure != null
            ? initFailure
            : "native runtime configuration failed");
      }
      coordinator = new MetalRenderCoordinator();
      coordinator.initialize();
      worldRenderer = new MetalWorldRenderer();
      meshShaderBackend = new MeshShaderBackend();
      meshShaderBackend.initialize();
      logStartDiag(mc);
      initState = InitState.READY;
      MetalLogger.info("Metal renderer ready: " + MetalHardwareChecker.getDeviceName());
    } catch (Throwable e) {
      MetalLogger.error("init fail", e);
      InitState failureState = initState == InitState.UNSUPPORTED
          ? InitState.UNSUPPORTED
          : InitState.FAILED;
      String configuredFailure = initFailure;
      shutdownRenderer();
      metalUp = false;
      initState = failureState;
      initFailure = configuredFailure != null
          ? configuredFailure
          : e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  public static MetalRenderClient getInstance() {
    return instance;
  }

  public static MetalRenderer getRenderer() {
    return renderer;
  }

  public static MetalRenderConfig getConfig() {
    return config;
  }

  public static MetalRenderCoordinator getCoordinator() {
    return coordinator;
  }

  public static MeshShaderBackend getMeshShaderBackend() {
    return meshShaderBackend;
  }

  public static boolean isMetalAvailable() {
    return metalUp;
  }

  public static boolean isEnabled() {
    return config != null && config.enableMetalRendering && metalUp &&
        renderer != null && renderer.isAvailable();
  }

  public static MetalWorldRenderer getWorldRenderer() {
    return worldRenderer;
  }

  public static SodiumMetalInterface getSodiumInterface() {
    if (sodiumInterface == null) {
      sodiumInterface = new SodiumMetalInterface();
    }
    return sodiumInterface;
  }

  public static boolean isSodiumLoaded() {
    return FabricLoader.getInstance().isModLoaded("sodium");
  }

  public static InitState getInitState() {
    return initState;
  }

  public static String getInitFailure() {
    return initFailure;
  }

  public static boolean reloadConfig() {
    try {
      MetalRenderConfig loaded = MetalRenderConfig.load();
      if (config == null) {
        config = loaded;
      } else {
        config.copyFrom(loaded);
      }
      requestDeferredApply(true, true, true);
      return true;
    } catch (Throwable error) {
      MetalLogger.warn("config reload failed: %s", error.getMessage());
      return false;
    }
  }

  public static void resetConfig() {
    MetalRenderConfig defaults = MetalRenderConfig.defaults();
    if (config == null) {
      config = defaults;
    } else {
      config.copyFrom(defaults);
    }
    config.save();
    requestDeferredApply(true, true, true);
  }

  public static boolean restartRenderer(Minecraft mc) {
    if (mc == null || config == null || !config.enableMetalRendering) {
      return false;
    }
    shutdownRenderer();
    initState = InitState.NOT_TRIED;
    initFailure = null;
    initMetal(mc);
    if (initState == InitState.READY && worldRenderer != null &&
        mc.level != null) {
      worldRenderer.onWorldLoad();
      worldRenderer.onConfigScreenClosed();
    }
    return initState == InitState.READY;
  }

  public static void requestNativeRetry() {
    if (initState == InitState.FAILED) {
      initState = InitState.NOT_TRIED;
      initFailure = null;
    }
  }

  private static boolean configureNativeRuntime() {
    if (!NativeBridge.isLibLoaded() || config == null) {
      return true;
    }
    try {
      int targetFrameRate = effectiveTargetFrameRate();
      NativeBridge.nConfigureRuntime(config.enableMetal4, config.maxMemoryMB,
          targetFrameRate, config.enableTripleBuffering);
      if (targetFrameRate != lastConfiguredTargetFrameRate) {
        MetalLogger.info("native frame target: %d FPS%s",
            targetFrameRate,
            config.autoTargetFrameRate ? " (display/cap)" : " (manual)");
        lastConfiguredTargetFrameRate = targetFrameRate;
      }
      if (config.requireMetal4 && !NativeBridge.nIsMetal4Active()) {
        initState = InitState.UNSUPPORTED;
        initFailure = "Metal 4 was required but native backend selected "
            + NativeBridge.nGetBackendMode();
        MetalLogger.warn("%s; falling back to vanilla", initFailure);
        return false;
      }
    } catch (UnsatisfiedLinkError oldNative) {
      MetalLogger.warn(
          "native runtime configuration API unavailable; rebuild the 26.2 dylib");
      if (config.requireMetal4) {
        initState = InitState.FAILED;
        initFailure =
            "Metal 4 is required, but the loaded native library is outdated";
        return false;
      }
    } catch (Throwable error) {
      initState = InitState.FAILED;
      initFailure = "native runtime configuration failed: "
          + error.getClass().getSimpleName() + ": " + error.getMessage();
      MetalLogger.warn("%s; falling back to vanilla", initFailure);
      return false;
    }

    if (renderer != null && mcWindowAvailable()) {
      Minecraft mc = Minecraft.getInstance();
      renderer.refreshRuntimeScale(mc.getWindow().getWidth(),
          mc.getWindow().getHeight());
    }
    return true;
  }

  private static boolean mcWindowAvailable() {
    Minecraft mc = Minecraft.getInstance();
    return mc != null && mc.getWindow() != null;
  }

  public static int effectiveTargetFrameRate() {
    int configured = config != null ? config.targetFrameRate : 60;
    if (config == null || !config.autoTargetFrameRate) {
      return configured;
    }

    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.getWindow() == null) {
      return configured;
    }
    int target = configured;
    try {
      int refreshRate = mc.getWindow().getRefreshRate();
      if (refreshRate >= 30) {
        target = refreshRate;
      }
      if (mc.options != null && mc.options.framerateLimit() != null) {
        int frameLimit = mc.options.framerateLimit().get();
        if (frameLimit >= 30) {
          target = Math.min(target, frameLimit);
        }
      }
    } catch (Throwable error) {
      MetalLogger.warn("display refresh detection failed: %s",
          error.getMessage());
    }
    return Math.max(30, Math.min(1000, target));
  }

  private static void requestDisplayTargetRefreshIfNeeded() {
    if (config == null || !config.autoTargetFrameRate ||
        initState != InitState.READY || !NativeBridge.isLibLoaded()) {
      displayTargetPollTicks = 0;
      return;
    }
    if (++displayTargetPollTicks < 120) {
      return;
    }
    displayTargetPollTicks = 0;
    if (effectiveTargetFrameRate() != lastConfiguredTargetFrameRate) {
      runtimeApplyPending = true;
    }
  }

  private static void shutdownRenderer() {
    if (worldRenderer != null) {
      drainRenderer();
      try {
        worldRenderer.shutdown();
      } catch (Throwable error) {
        MetalLogger.warn("world renderer shutdown failed: %s",
            error.getMessage());
      }
      worldRenderer = null;
    }
    if (meshShaderBackend != null) {
      try {
        meshShaderBackend.shutdown();
      } catch (Throwable error) {
        MetalLogger.warn("mesh backend shutdown failed: %s",
            error.getMessage());
      }
      meshShaderBackend = null;
    }
    if (coordinator != null) {
      coordinator.shutdown();
      coordinator = null;
    }
    if (sodiumInterface != null) {
      sodiumInterface.shutdown();
      sodiumInterface = null;
    }
    if (renderer != null) {
      long handle = renderer.getHandle();
      if (handle != 0 && NativeBridge.isLibLoaded()) {
        try {
          NativeBridge.nFlushDeferredDeletions();
          NativeBridge.nDestroy(handle);
        } catch (Throwable error) {
          MetalLogger.warn("renderer destroy fail: %s", error.getMessage());
        }
      }
      renderer = null;
    }
    metalUp = false;
    displayTargetPollTicks = 0;
    lastConfiguredTargetFrameRate = -1;
  }

  private static void shutdownForClientExit() {
    if (terminalShutdown) {
      return;
    }
    terminalShutdown = true;
    try {
      restoreFpsPriorityMode(Minecraft.getInstance(), true);
    } catch (Throwable error) {
      MetalLogger.warn("simulation distance restore failed: %s",
          error.getMessage());
    }
    MetalRenderHookState.resetSession();
    // Stop validation before nDestroy can tear down the Metal device used by
    // an in-flight ephemeral MTLLibrary compile.
    IrisTranslationCoordinator.stop();
    shutdownRenderer();
    AsyncCullTask.shutdown();
    instance = null;
  }

  private static void startIrisTranslationIfEnabled() {
    try {
      String configuredRoot = System.getProperty(
          "metalrender.experimental.irisMetalCacheRoot");
      Path cacheRoot = configuredRoot == null || configuredRoot.isBlank()
          ? FabricLoader.getInstance().getGameDir()
              .resolve(".cache").resolve("metalrender")
          : Path.of(configuredRoot);
      if (IrisTranslationCoordinator.startIfEnabled(cacheRoot)) {
        MetalLogger.info(
            "Iris-to-Metal translation and graph worker enabled");
      }
    } catch (Throwable error) {
      MetalLogger.warn(
          "Iris translation setup failed open; Iris OpenGL remains active (%s)",
          error.getClass().getSimpleName());
    }
  }

  private static void logStartDiag(Minecraft mc) {
    try {
      var o = mc.options;
      var cfg = config;
      int fpsCap = o != null && o.framerateLimit() != null
          ? o.framerateLimit().get()
          : -1;
      boolean vsync = o != null && o.enableVsync() != null &&
          Boolean.TRUE.equals(o.enableVsync().get());
      int rd = o != null && o.renderDistance() != null
          ? o.renderDistance().get()
          : -1;
      int sd = o != null && o.simulationDistance() != null
          ? o.simulationDistance().get()
          : -1;

      boolean meshOk = NativeBridge.nSupportsMeshShaders();
      boolean indOk = NativeBridge.nSupportsIndirect();
      boolean meshOn = NativeBridge.nAreMeshShadersActive();
      boolean gpuOn = NativeBridge.nIsGPUDrivenActive();

      MetalLogger.info(
          "starting: supportsMesh=%s supportsIndirect=%s meshActive=%s " +
              "gpuDriven=%s cfg(mesh=%s icb=%s argBuf=%s) fpsLimit=%d vsync=%s " +
              "rd=%d sd=%d",
          meshOk, indOk, meshOn, gpuOn, cfg != null && cfg.enableMeshShaders,
          cfg != null && cfg.enableIndirectCommandBuffers,
          cfg != null && cfg.enableArgumentBuffers, fpsCap, vsync, rd, sd);

      if (cfg != null && cfg.enableMeshShaders && !meshOn) {
        MetalLogger.warn("starting: asked for mesh shader but refused " +
            "check capability fallback path selection.");
      }
      if (cfg != null && cfg.enableIndirectCommandBuffers && !indOk) {
        MetalLogger.warn("starting: icb unsupported");
      }
      if (vsync || (fpsCap > 0 && fpsCap <= 60)) {
        MetalLogger.warn("starting: fps capped (vsync=%s cap=%d)",
            vsync, fpsCap);
      }
    } catch (Throwable t) {
      MetalLogger.warn("starting diag fail: %s", t.getMessage());
    }
  }
}

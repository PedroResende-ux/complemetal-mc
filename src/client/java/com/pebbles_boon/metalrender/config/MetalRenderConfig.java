package com.pebbles_boon.metalrender.config;

public final class MetalRenderConfig {
  public static final int SCHEMA_VERSION = 4;

  public boolean enableMetalRendering = true;
  public boolean enableMetal4 = true;
  public boolean requireMetal4 = false;
  public boolean enableFastTerrainReplacement = false;
  public boolean enableExperimentalFeatureReplacement = false;
  public boolean enableMetalFX = false;
  public boolean enableSimpleLighting = true;
  public boolean enableDebugOverlay = false;
  public boolean debugPinkBlockTint = false;
  public int leafCullingMode = 1;
  public int biomeTransitionDetail = 2;
  public int targetFrameRate = 60;
  public boolean autoTargetFrameRate = true;
  public boolean prioritizeFpsOverTps = false;
  public int maxMemoryMB = 2048;
  public boolean enableTripleBuffering = true;
  public boolean enableMemoryPressureFallback = true;
  public boolean enableBurstThreadMode = false;
  public boolean enableMeshShaders = false;
  public boolean enableArgumentBuffers = true;
  public boolean enableClusterFrustumCulling = false;
  public boolean enableHiZCull = false;
  public boolean enableGpuTranslucencySort = false;

  public boolean hiddenFluidCulling = true;
  public boolean improvedFluidShaping = false;
  public boolean closestPointEntitySort = false;

  public boolean enableProgrammableBlending = false;
  public boolean enableIndirectCommandBuffers = false;
  private static volatile boolean mirrorUploads = false;
  private static volatile boolean swapOpaque = false;
  private static volatile boolean swapCutout = false;
  private static volatile boolean swapTranslucent = false;
  private static volatile float resolutionScale = 1.0f;
  private static volatile boolean deepDebugActive = false;
  private static volatile boolean debugPinkBlockTintEnabled = false;

  private static java.nio.file.Path configFile() {
    return net.fabricmc.loader.api.FabricLoader.getInstance()
        .getConfigDir().resolve("metalrender.json");
  }

  private static java.nio.file.Path deepDebugFlagFile() {
    return net.fabricmc.loader.api.FabricLoader.getInstance()
        .getConfigDir().resolve("metalrender-debug-next-run.flag");
  }

  private static void activateOneRunDeepDebugIfRequested() {
    java.nio.file.Path flagPath = deepDebugFlagFile();
    try {
      deepDebugActive = java.nio.file.Files.exists(flagPath);
      if (deepDebugActive) {
        java.nio.file.Files.deleteIfExists(flagPath);
      }
    } catch (Exception e) {
      deepDebugActive = false;
      com.pebbles_boon.metalrender.util.MetalLogger.warn(
          "deep debug flag could not be read: %s", e.getMessage());
    }
  }

  public static MetalRenderConfig load() {
    activateOneRunDeepDebugIfRequested();
    MetalRenderConfig cfg = new MetalRenderConfig();

    try {
      java.nio.file.Path path = configFile();
      if (java.nio.file.Files.exists(path)) {
        String raw = java.nio.file.Files.readString(path);
        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();

        if (obj.has("enableMetal4"))
          cfg.enableMetal4 = obj.get("enableMetal4").getAsBoolean();
        if (obj.has("requireMetal4"))
          cfg.requireMetal4 = obj.get("requireMetal4").getAsBoolean();
        if (obj.has("enableFastTerrainReplacement"))
          cfg.enableFastTerrainReplacement =
              obj.get("enableFastTerrainReplacement").getAsBoolean();
        if (obj.has("enableExperimentalFeatureReplacement"))
          cfg.enableExperimentalFeatureReplacement =
              obj.get("enableExperimentalFeatureReplacement").getAsBoolean();
        if (obj.has("enableMetalFX"))
          cfg.enableMetalFX = obj.get("enableMetalFX").getAsBoolean();
        if (obj.has("enableMetalRendering"))
          cfg.enableMetalRendering = obj.get("enableMetalRendering").getAsBoolean();
        if (obj.has("enableSimpleLighting"))
          cfg.enableSimpleLighting = obj.get("enableSimpleLighting").getAsBoolean();
        if (obj.has("enableDebugOverlay"))
          cfg.enableDebugOverlay = obj.get("enableDebugOverlay").getAsBoolean();
        if (obj.has("debugPinkBlockTint"))
          cfg.debugPinkBlockTint = obj.get("debugPinkBlockTint").getAsBoolean();
        if (obj.has("leafCullingMode"))
          cfg.leafCullingMode = obj.get("leafCullingMode").getAsInt();
        if (obj.has("biomeTransitionDetail"))
          cfg.biomeTransitionDetail = obj.get("biomeTransitionDetail").getAsInt();
        if (obj.has("targetFrameRate"))
          cfg.targetFrameRate = obj.get("targetFrameRate").getAsInt();
        if (obj.has("autoTargetFrameRate"))
          cfg.autoTargetFrameRate = obj.get("autoTargetFrameRate").getAsBoolean();
        if (obj.has("prioritizeFpsOverTps"))
          cfg.prioritizeFpsOverTps = obj.get("prioritizeFpsOverTps").getAsBoolean();
        if (obj.has("maxMemoryMB"))
          cfg.maxMemoryMB = obj.get("maxMemoryMB").getAsInt();
        if (obj.has("enableTripleBuffering"))
          cfg.enableTripleBuffering = obj.get("enableTripleBuffering").getAsBoolean();
        if (obj.has("enableMemoryPressureFallback"))
          cfg.enableMemoryPressureFallback = obj.get("enableMemoryPressureFallback").getAsBoolean();
        if (obj.has("enableBurstThreadMode"))
          cfg.enableBurstThreadMode = obj.get("enableBurstThreadMode").getAsBoolean();
        if (obj.has("enableMeshShaders"))
          cfg.enableMeshShaders = obj.get("enableMeshShaders").getAsBoolean();
        if (obj.has("enableArgumentBuffers"))
          cfg.enableArgumentBuffers = obj.get("enableArgumentBuffers").getAsBoolean();
        if (obj.has("enableProgrammableBlending"))
          cfg.enableProgrammableBlending = obj.get("enableProgrammableBlending").getAsBoolean();

        if (obj.has("enableIndirectCommandBuffers"))
          cfg.enableIndirectCommandBuffers = obj.get("enableIndirectCommandBuffers").getAsBoolean();
        if (obj.has("enableClusterFrustumCulling"))
          cfg.enableClusterFrustumCulling = obj.get("enableClusterFrustumCulling").getAsBoolean();
        if (obj.has("enableHiZCull"))
          cfg.enableHiZCull = obj.get("enableHiZCull").getAsBoolean();
        if (obj.has("enableGpuTranslucencySort"))
          cfg.enableGpuTranslucencySort = obj.get("enableGpuTranslucencySort").getAsBoolean();

        if (obj.has("hiddenFluidCulling"))
          cfg.hiddenFluidCulling = obj.get("hiddenFluidCulling").getAsBoolean();
        if (obj.has("improvedFluidShaping"))
          cfg.improvedFluidShaping = obj.get("improvedFluidShaping").getAsBoolean();
        if (obj.has("closestPointEntitySort"))
          cfg.closestPointEntitySort = obj.get("closestPointEntitySort").getAsBoolean();

        if (obj.has("savedResolutionScale"))
          resolutionScale = clamp(obj.get("savedResolutionScale").getAsFloat(), 0.20f, 1.5f);
      }
    } catch (Exception e) {
      com.pebbles_boon.metalrender.util.MetalLogger.warn(
          "config load failed, using validated defaults: %s", e.getMessage());
    }

    cfg.validate();
    setDebugPinkBlockTint(cfg.debugPinkBlockTint);

    cfg.loadFeatureFlags();
    loadFromSystemProperties();
    return cfg;
  }

  private MetalRenderConfig() {
  }

  private void validate() {
    leafCullingMode = clamp(leafCullingMode, 0, 2);
    biomeTransitionDetail = clamp(biomeTransitionDetail, 0, 7);
    targetFrameRate = clamp(targetFrameRate, 30, 1000);
    maxMemoryMB = clamp(maxMemoryMB, 512, 2048);
    resolutionScale = clamp(resolutionScale, 0.20f, 1.0f);

    // These paths are validation-locked in normal builds. Each explicit JVM
    // property is a complete opt-in for development; no hidden JSON toggle or
    // second feature flag is required.
    enableMeshShaders =
        getBool("metalrender.experimental.meshShaders", false);
    enableHiZCull = getBool("metalrender.experimental.hiz", false);
    enableFastTerrainReplacement =
        getBool("metalrender.experimental.fastTerrainReplacement", false);
    // Entity/particle replacement still relies on a legacy raw OpenGL
    // texture readback that is unsafe on the macOS 26 Apple driver. Keep the
    // path release-locked until it is migrated to fenced GpuTexture readback.
    enableExperimentalFeatureReplacement = false;
    if (requireMetal4) {
      enableMetal4 = true;
    }
  }

  public void save() {
    validate();
    try {
      com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
      obj.addProperty("schemaVersion", SCHEMA_VERSION);
      obj.addProperty("enableMetalRendering", enableMetalRendering);
      obj.addProperty("enableMetal4", enableMetal4);
      obj.addProperty("requireMetal4", requireMetal4);
      obj.addProperty("enableFastTerrainReplacement",
          enableFastTerrainReplacement);
      obj.addProperty("enableExperimentalFeatureReplacement",
          enableExperimentalFeatureReplacement);
      obj.addProperty("enableMetalFX", enableMetalFX);
      obj.addProperty("enableSimpleLighting", enableSimpleLighting);
      obj.addProperty("enableDebugOverlay", enableDebugOverlay);
      obj.addProperty("debugPinkBlockTint", debugPinkBlockTint);
      obj.addProperty("leafCullingMode", leafCullingMode);
      obj.addProperty("biomeTransitionDetail", biomeTransitionDetail);
      obj.addProperty("targetFrameRate", targetFrameRate);
      obj.addProperty("autoTargetFrameRate", autoTargetFrameRate);
      obj.addProperty("prioritizeFpsOverTps", prioritizeFpsOverTps);
      obj.addProperty("maxMemoryMB", maxMemoryMB);
      obj.addProperty("enableTripleBuffering", enableTripleBuffering);
      obj.addProperty("enableMemoryPressureFallback", enableMemoryPressureFallback);
      obj.addProperty("enableBurstThreadMode", enableBurstThreadMode);
      obj.addProperty("enableMeshShaders", enableMeshShaders);
      obj.addProperty("enableArgumentBuffers", enableArgumentBuffers);
      obj.addProperty("enableProgrammableBlending", enableProgrammableBlending);
      obj.addProperty("enableIndirectCommandBuffers", enableIndirectCommandBuffers);
      obj.addProperty("enableClusterFrustumCulling", enableClusterFrustumCulling);
      obj.addProperty("enableHiZCull", enableHiZCull);
      obj.addProperty("enableGpuTranslucencySort", enableGpuTranslucencySort);
      obj.addProperty("hiddenFluidCulling", hiddenFluidCulling);
      obj.addProperty("improvedFluidShaping", improvedFluidShaping);
      obj.addProperty("closestPointEntitySort", closestPointEntitySort);

      obj.addProperty("savedResolutionScale", resolutionScale);
      java.nio.file.Path path = configFile();
      java.nio.file.Files.createDirectories(path.getParent());
      String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj);
      java.nio.file.Path temporary = java.nio.file.Files.createTempFile(
          path.getParent(), "metalrender-", ".json.tmp");
      try {
        java.nio.file.Files.writeString(temporary, json);
        try {
          java.nio.file.Files.move(temporary, path,
              java.nio.file.StandardCopyOption.ATOMIC_MOVE,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
          java.nio.file.Files.move(temporary, path,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        java.nio.file.Files.deleteIfExists(temporary);
      }
    } catch (Exception e) {
      com.pebbles_boon.metalrender.util.MetalLogger.warn(
          "config save failed: %s", e.getMessage());
    }
  }

  public static boolean mirrorUploads() {
    return mirrorUploads;
  }

  public static boolean swapOpaque() {
    return swapOpaque;
  }

  public static boolean swapCutout() {
    return swapCutout;
  }

  public static boolean swapTranslucent() {
    return swapTranslucent;
  }

  public static float resolutionScale() {
    return resolutionScale;
  }

  public static boolean isDeepDebugActive() {
    return deepDebugActive;
  }

  public static boolean debugPinkBlockTint() {
    return debugPinkBlockTintEnabled;
  }

  public static boolean isOneRunDeepDebugRequested() {
    try {
      return java.nio.file.Files.exists(deepDebugFlagFile());
    } catch (Exception e) {
      return false;
    }
  }

  public static void setOneRunDeepDebugRequested(boolean enabled) {
    java.nio.file.Path flagPath = deepDebugFlagFile();
    try {
      if (enabled) {
        java.nio.file.Files.createDirectories(flagPath.getParent());
        java.nio.file.Files.writeString(flagPath, "enabled\n");
      } else {
        java.nio.file.Files.deleteIfExists(flagPath);
      }
    } catch (Exception e) {
      com.pebbles_boon.metalrender.util.MetalLogger.warn(
          "deep debug flag update failed: %s", e.getMessage());
    }
  }

  public static void setMirrorUploads(boolean v) {
    mirrorUploads = v;
  }

  public static void setSwapOpaque(boolean v) {
    swapOpaque = v;
  }

  public static void setSwapCutout(boolean v) {
    swapCutout = v;
  }

  public static void setSwapTranslucent(boolean v) {
    swapTranslucent = v;
  }

  public static void setResolutionScale(float v) {
    resolutionScale = clamp(v, 0.20f, 1.0f);
  }

  public static void setDebugPinkBlockTint(boolean v) {
    debugPinkBlockTintEnabled = v;
  }

  public static void loadFromSystemProperties() {
    mirrorUploads = getBool("metalrender.mirror", mirrorUploads);
    swapOpaque = getBool("metalrender.swap.opaque", swapOpaque);
    swapCutout = getBool("metalrender.swap.cutout", swapCutout);
    swapTranslucent = getBool("metalrender.swap.translucent", swapTranslucent);
    resolutionScale = clamp(
        getFloat("metalrender.render.resolutionScale", resolutionScale),
        0.20f, 1.0f);
  }

  public void loadFeatureFlags() {
    enableMetalRendering =
        getBool("metalrender.enabled", enableMetalRendering);
    enableMetal4 = getBool("metalrender.feature.metal4", enableMetal4);
    enableIndirectCommandBuffers = getBool("metalrender.feature.icb", enableIndirectCommandBuffers);
    enableMeshShaders = getBool("metalrender.feature.mesh", enableMeshShaders);
    enableArgumentBuffers = getBool("metalrender.feature.argbuf", enableArgumentBuffers);
    enableProgrammableBlending = getBool("metalrender.feature.oit", enableProgrammableBlending);

    if (enableMeshShaders && com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker.supportsMeshShaders()) {
      enableIndirectCommandBuffers = true;
    }
    validate();
  }

  public void copyFrom(MetalRenderConfig other) {
    if (other == null) {
      return;
    }
    enableMetalRendering = other.enableMetalRendering;
    enableMetal4 = other.enableMetal4;
    requireMetal4 = other.requireMetal4;
    enableFastTerrainReplacement = other.enableFastTerrainReplacement;
    enableExperimentalFeatureReplacement =
        other.enableExperimentalFeatureReplacement;
    enableMetalFX = other.enableMetalFX;
    enableSimpleLighting = other.enableSimpleLighting;
    enableDebugOverlay = other.enableDebugOverlay;
    debugPinkBlockTint = other.debugPinkBlockTint;
    leafCullingMode = other.leafCullingMode;
    biomeTransitionDetail = other.biomeTransitionDetail;
    targetFrameRate = other.targetFrameRate;
    autoTargetFrameRate = other.autoTargetFrameRate;
    prioritizeFpsOverTps = other.prioritizeFpsOverTps;
    maxMemoryMB = other.maxMemoryMB;
    enableTripleBuffering = other.enableTripleBuffering;
    enableMemoryPressureFallback = other.enableMemoryPressureFallback;
    enableBurstThreadMode = other.enableBurstThreadMode;
    enableMeshShaders = other.enableMeshShaders;
    enableArgumentBuffers = other.enableArgumentBuffers;
    enableClusterFrustumCulling = other.enableClusterFrustumCulling;
    enableHiZCull = other.enableHiZCull;
    enableGpuTranslucencySort = other.enableGpuTranslucencySort;
    hiddenFluidCulling = other.hiddenFluidCulling;
    improvedFluidShaping = other.improvedFluidShaping;
    closestPointEntitySort = other.closestPointEntitySort;
    enableProgrammableBlending = other.enableProgrammableBlending;
    enableIndirectCommandBuffers = other.enableIndirectCommandBuffers;
    validate();
  }

  public static MetalRenderConfig defaults() {
    MetalRenderConfig defaults = new MetalRenderConfig();
    resolutionScale = 1.0f;
    defaults.validate();
    return defaults;
  }

  private static boolean getBool(String key, boolean def) {
    String v = System.getProperty(key);
    if (v == null)
      return def;
    return "1".equals(v) || Boolean.parseBoolean(v);
  }

  private static float getFloat(String key, float def) {
    String v = System.getProperty(key);
    if (v == null)
      return def;
    try {
      return Float.parseFloat(v);
    } catch (NumberFormatException ex) {
      return def;
    }
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  private static float clamp(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}

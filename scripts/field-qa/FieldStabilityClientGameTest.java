package com.pebbles_boon.metalrender.fieldqa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.compat.IrisCompatibility;
import com.pebbles_boon.metalrender.compat.iris.IrisMetalFeatureFlags;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import com.pebbles_boon.metalrender.display.DisplayLifecycleTracker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/**
 * Production-client field regression for the release-safe Iris path.
 *
 * <p>The driver runs from a standalone QA mod beside the exact publishable
 * Complemetal JAR. It uses a real Minecraft client, integrated servers, Iris,
 * Complementary and the user's complete compatibility mod set.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class FieldStabilityClientGameTest
    implements FabricClientGameTest {
  private static final int STARTUP_TIMEOUT_FRAMES = 3_600;
  private static final int WORLD_TIMEOUT_FRAMES = 12_000;
  private static final int RELOAD_TIMEOUT_FRAMES = 18_000;
  private static final int PERFORMANCE_TIMEOUT_FRAMES = 36_000;
  private static final long BLACK_WINDOW_NANOS = 12_000_000_000L;
  private static final long PERFORMANCE_WINDOW_NANOS = 30_000_000_000L;
  private static final long FLIGHT_STEP_NANOS = 600_000_000L;
  private static final int MINIMUM_PERFORMANCE_SAMPLES = 300;
  private static final BlockPos MINE_BLOCK = new BlockPos(0, 100, 0);
  private static final Set<String> REQUIRED_MODS = Set.of(
      "complemetal", "fabric-api", "fabric-language-kotlin", "sodium",
      "iris", "immediatelyfast", "entityculling", "ferritecore",
      "lithium", "modmenu", "placeholder-api",
      "yet_another_config_lib_v3", "zoomify");
  private static final List<String> IRIS_METAL_FLAGS = List.of(
      "metalrender.experimental.irisMetalPipeline",
      "metalrender.experimental.irisMetalTranslation",
      "metalrender.experimental.irisMetalLibraryValidation",
      "metalrender.experimental.irisMetalPipelineCompilation",
      "metalrender.experimental.irisMetalShadowReplay",
      "metalrender.experimental.irisMetalVisualParity",
      "metalrender.experimental.irisMetalFinalCutover",
      "metalrender.experimental.irisMetalGraphResources",
      "metalrender.experimental.irisMetalGraphExecution",
      "metalrender.experimental.irisMetalGraphOwnership");

  private final List<ScreenshotEvidence> screenshots = new ArrayList<>();
  private final List<String> completedScenarios = new ArrayList<>();
  private FrameMetrics stationaryMetrics;
  private FrameMetrics flightMetrics;
  private DisplayEvidence displayEvidence;
  private long startedNanos;
  private long pausedMetalFrameBaseline;
  private long pausedPresentationBaseline;

  @Override
  public void runTest(ClientGameTestContext context) {
    startedNanos = System.nanoTime();
    String side = requiredProperty("complemetal.fieldQa.side");
    require(side.equals("enabled") || side.equals("baseline"),
        "invalid field-QA side: " + side);
    boolean baseline = side.equals("baseline");
    verifyRuntimeIdentity();
    verifyCompatibilityMods();
    verifyReleaseSafeFlags();
    waitForComplemetalStartup(context, baseline);
    require(IrisApi.getInstance().getConfig().areShadersEnabled(),
        "Iris shaders are disabled in the field profile");
    context.runOnClient(client -> {
      client.options.renderDistance().set(15);
      client.options.simulationDistance().set(13);
      client.options.enableVsync().set(false);
      client.options.framerateLimit().set(260);
    });

    try (TestSingleplayerContext world =
             context.worldBuilder().setUseConsistentSettings(true).create()) {
      configureWorld(world, "noon", "clear");
      waitForWorldAndShaders(context, baseline);
      buildCompatibilityScene(world);
      waitForScene(context);
      verifyPausedMetalOwnership(context, baseline, true);
      completedScenarios.add("overworld-entities-fluids");

      long blackWindowStart = System.nanoTime();
      context.waitFor(client ->
          System.nanoTime() - blackWindowStart >= BLACK_WINDOW_NANOS,
          WORLD_TIMEOUT_FRAMES);
      takeVerifiedScreenshot(context, side + "-after-12-seconds");
      completedScenarios.add("black-frame-regression-window");

      applyBiome(world, "minecraft:desert");
      waitWallClock(context, 1_000_000_000L, WORLD_TIMEOUT_FRAMES);
      takeVerifiedScreenshot(context, side + "-desert-biome");
      applyBiome(world, "minecraft:snowy_plains");
      waitWallClock(context, 1_000_000_000L, WORLD_TIMEOUT_FRAMES);
      takeVerifiedScreenshot(context, side + "-snowy-biome");
      completedScenarios.add("overworld-biome-transitions");

      displayEvidence = enterHighRefreshFullscreen(context);
      takeVerifiedScreenshot(context, side + "-fullscreen-entry");
      verifyPausedMetalOwnership(context, baseline, false);
      completedScenarios.add("fullscreen-200hz");

      waitWallClock(context, 3_000_000_000L, WORLD_TIMEOUT_FRAMES);
      stationaryMetrics = captureStationaryPerformance(context);
      takeVerifiedScreenshot(context, side + "-fullscreen-30s-performance");
      completedScenarios.add("stationary-performance");

      exerciseZoomify(context);
      takeVerifiedScreenshot(context, side + "-zoomify");
      completedScenarios.add("zoomify-key-path");

      flightMetrics = exerciseFlightTraversal(context, world);
      takeVerifiedScreenshot(context, side + "-flight-traversal");
      completedScenarios.add("flight-and-chunk-streaming");

      exerciseMining(context, world);
      takeVerifiedScreenshot(context, side + "-mining");
      completedScenarios.add("survival-mining");

      if (!baseline) {
        verifySafeLiveReload(context);
        takeVerifiedScreenshot(context, side + "-safe-live-reload");
        completedScenarios.add("complemetal-live-reload");
      }

      verifyResourceReload(context, baseline);
      takeVerifiedScreenshot(context, side + "-resource-reload");
      completedScenarios.add("resource-reload");

      visitDimension(context, world, Level.NETHER,
          "minecraft:the_nether", "minecraft:netherrack", side + "-nether");
      visitDimension(context, world, Level.END,
          "minecraft:the_end", "minecraft:end_stone", side + "-end");
      visitDimension(context, world, Level.OVERWORLD,
          "minecraft:overworld", "minecraft:grass_block",
          side + "-overworld-return");
      completedScenarios.add("nether-end-overworld-cycle");
      verifyPausedMetalOwnership(context, baseline, false);
    }

    context.waitFor(client -> client.level == null, WORLD_TIMEOUT_FRAMES);
    try (TestSingleplayerContext secondWorld =
             context.worldBuilder().setUseConsistentSettings(false).create()) {
      configureWorld(secondWorld, "midnight", "rain");
      waitForWorldAndShaders(context, baseline);
      buildSecondWorldScene(secondWorld);
      waitWallClock(context, 3_000_000_000L, WORLD_TIMEOUT_FRAMES);
      takeVerifiedScreenshot(context, side + "-second-world-night-rain");
      completedScenarios.add("second-world-night-weather");
      verifyPausedMetalOwnership(context, baseline, false);
    }

    context.waitFor(client -> client.level == null, WORLD_TIMEOUT_FRAMES);
    verifyNativeFaults(baseline);
    require(System.nanoTime() - startedNanos >= 60_000_000_000L,
        "field QA did not sustain the client for at least one minute");
    settleRetainedHeap(context);
    writeResult(side, baseline);
    System.out.printf(Locale.ROOT,
        "COMPLEMETAL_FIELD_QA PASS side=%s fps=%.2f onePercentLow=%.2f "
            + "p99=%.2fms fullscreen=%dx%d@%dHz duration=%.1fs%n",
        side, stationaryMetrics.averageFps(),
        stationaryMetrics.onePercentLowFps(),
        stationaryMetrics.p99Nanos() / 1_000_000.0,
        displayEvidence.framebufferWidth(),
        displayEvidence.framebufferHeight(), displayEvidence.refreshHz(),
        (System.nanoTime() - startedNanos) / 1_000_000_000.0);
  }

  private static void verifyRuntimeIdentity() {
    FabricLoader loader = FabricLoader.getInstance();
    require(!loader.isDevelopmentEnvironment(),
        "field QA must run in a production Fabric environment");
    Path expected = Path.of(requiredProperty("complemetal.fieldQa.expectedPath"))
        .toAbsolutePath().normalize();
    String expectedSha =
        requiredProperty("complemetal.fieldQa.expectedSha256");
    ModContainer container = loader.getModContainer("complemetal")
        .orElseThrow(() -> new AssertionError("Complemetal is not loaded"));
    require(container.getOrigin().getPaths().stream()
            .map(path -> path.toAbsolutePath().normalize())
            .anyMatch(expected::equals),
        "Fabric did not load the prepared release JAR: " + expected);
    require(expectedSha.equals(sha256(expected)),
        "prepared release JAR SHA-256 changed before field QA");
    require(System.getProperty("java.version", "").startsWith("25."),
        "field QA requires Java 25");
  }

  private static void verifyCompatibilityMods() {
    FabricLoader loader = FabricLoader.getInstance();
    for (String modId : REQUIRED_MODS) {
      require(loader.isModLoaded(modId),
          "required compatibility mod is not loaded: " + modId);
    }
  }

  private static void verifyReleaseSafeFlags() {
    require(!Boolean.getBoolean("metalrender.irisMetal.enabled"),
        "global experimental Iris/Metal opt-in leaked into field QA");
    for (String flag : IRIS_METAL_FLAGS) {
      require(!IrisMetalFeatureFlags.enabled(flag),
          "release-safe field profile enabled experimental flag " + flag);
    }
    require(!IrisTranslationCoordinator.status().running(),
        "experimental Iris translation worker started in stable defaults");
  }

  private static void waitForComplemetalStartup(
      ClientGameTestContext context, boolean baseline) {
    context.waitFor(client -> MetalRenderClient.getConfig() != null
            && (baseline || MetalRenderClient.getInitState()
                != MetalRenderClient.InitState.NOT_TRIED),
        STARTUP_TIMEOUT_FRAMES);
    if (baseline) {
      require(!MetalRenderClient.getConfig().enableMetalRendering,
          "baseline did not disable Complemetal");
      require(!MetalRenderClient.isEnabled(),
          "baseline initialized the renderer");
      require(!NativeBridge.isLibLoaded(),
          "baseline loaded the native Metal bridge");
      return;
    }
    require(MetalRenderClient.getInitState() == MetalRenderClient.InitState.READY,
        "Metal runtime failed to initialize: " + MetalRenderClient.getInitFailure());
    require(MetalRenderClient.isEnabled(), "Complemetal is not enabled");
    require(NativeBridge.isLibLoaded(), "native Metal bridge is not loaded");
    require(NativeBridge.nSupportsMetal4(), "test Mac does not support Metal 4");
    require(NativeBridge.nIsMetal4Active(), "Metal 4 runtime is not active");
    require(!NativeBridge.nIsMetal4DrawPathActive(),
        "unvalidated native MTL4 draw encoder became active");
  }

  private static void configureWorld(TestSingleplayerContext world,
      String time, String weather) {
    world.getServer().runCommand("time set " + time);
    world.getServer().runCommand("weather " + weather);
    world.getServer().runOnServer(server -> {
      GameRules rules = server.getGameRules();
      rules.set(GameRules.ADVANCE_TIME, false, server);
      rules.set(GameRules.ADVANCE_WEATHER, false, server);
      rules.set(GameRules.KEEP_INVENTORY, true, server);
    });
  }

  private static void waitForWorldAndShaders(ClientGameTestContext context,
      boolean baseline) {
    context.waitFor(client -> client.level != null && client.player != null
            && client.levelRenderer != null && client.levelRenderer.viewArea() != null,
        WORLD_TIMEOUT_FRAMES);
    context.waitFor(client -> IrisApi.getInstance().isShaderPackInUse(),
        WORLD_TIMEOUT_FRAMES);
    require(requiredProperty("complemetal.fieldQa.shaderPack")
            .equals(Iris.getCurrentPackName()),
        "Iris loaded unexpected shader pack: " + Iris.getCurrentPackName());
    if (!baseline) {
      context.waitFor(client -> irisOwnsVisibleFrame(), WORLD_TIMEOUT_FRAMES);
    }
  }

  private static boolean irisOwnsVisibleFrame() {
    MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
    if (!IrisCompatibility.requiresShaderCompatibilityMode()
        || renderer == null || !renderer.isWorldLoaded()
        || !renderer.isIrisCompatibilityPaused() || renderer.areTexturesReady()
        || renderer.metalActive()
        || renderer.getChunkMesher().getMeshCount() != 0) {
      return false;
    }
    var backend = MetalRenderClient.getRenderer();
    return backend != null && backend.getHandle() != 0
        && !NativeBridge.nIsFrameReady(backend.getHandle());
  }

  private static void buildCompatibilityScene(TestSingleplayerContext world) {
    world.getServer().runCommand("gamemode creative @a");
    world.getServer().runCommand("fill -20 96 -20 20 96 20 minecraft:grass_block");
    world.getServer().runCommand("fill -20 97 -20 20 112 20 minecraft:air");
    world.getServer().runCommand("setblock 2 97 4 minecraft:chest");
    world.getServer().runCommand("setblock -5 97 5 minecraft:water");
    world.getServer().runCommand("setblock 6 97 5 minecraft:lava");
    world.getServer().runCommand("setblock -7 97 5 minecraft:glass");
    world.getServer().runCommand("setblock 4 97 5 minecraft:tinted_glass");
    world.getServer().runCommand(
        "summon minecraft:armor_stand 2 97 1 {CustomName:'\"QA Armor Stand\"'}");
    world.getServer().runCommand("summon minecraft:pig -3 97 2");
    world.getServer().runCommand("summon minecraft:minecart 5 97 -2");
    world.getServer().runCommand("tp @a 0 100 -10 0 12");
  }

  private static void waitForScene(ClientGameTestContext context) {
    context.waitFor(client -> client.level != null
            && client.level.getBlockState(new BlockPos(2, 97, 4)).getBlock()
                == Blocks.CHEST
            && client.level.getFluidState(new BlockPos(-5, 97, 5))
                .isSourceOfType(Fluids.WATER)
            && client.level.getFluidState(new BlockPos(6, 97, 5))
                .isSourceOfType(Fluids.LAVA)
            && hasEntity(client.level, EntityTypes.ARMOR_STAND)
            && hasEntity(client.level, EntityTypes.PIG)
            && hasEntity(client.level, EntityTypes.MINECART),
        WORLD_TIMEOUT_FRAMES);
    context.runOnClient(client -> {
      client.particleEngine.clearParticles();
      for (int index = 0; index < 128; index++) {
        client.particleEngine.createParticle(ParticleTypes.END_ROD,
            ((index % 16) - 7.5) * 0.45, 99.0 + (index % 5) * 0.3,
            1.0 + index / 64.0, 0.0, 0.01, 0.0);
      }
    });
  }

  private static boolean hasEntity(ClientLevel level,
      net.minecraft.world.entity.EntityType<?> type) {
    for (var entity : level.entitiesForRendering()) {
      if (entity.getType() == type) {
        return true;
      }
    }
    return false;
  }

  private static void applyBiome(TestSingleplayerContext world,
      String biome) {
    world.getServer().runCommand(
        "fillbiome -16 80 -16 15 111 15 " + biome);
  }

  private DisplayEvidence enterHighRefreshFullscreen(
      ClientGameTestContext context) {
    DisplayLifecycleTracker.DisplayTarget target = context.computeOnClient(
        client -> DisplayLifecycleTracker.displays().stream()
            .max(Comparator
                .comparingInt(DisplayLifecycleTracker.DisplayTarget::refreshRate)
                .thenComparingInt(display ->
                    display.modeWidth() * display.modeHeight()))
            .orElseThrow(() ->
                new AssertionError("no GLFW display is connected")));
    int minimumRefresh = Integer.parseInt(
        requiredProperty("complemetal.fieldQa.minimumRefreshHz"));
    require(target.refreshRate() >= minimumRefresh,
        "no display satisfies the requested refresh rate: best is "
            + target.refreshRate() + "Hz");
    int windowWidth = Math.min(1280, target.workWidth());
    int windowHeight = Math.min(720, target.workHeight());
    int x = target.workX() + Math.max(0, (target.workWidth() - windowWidth) / 2);
    int y = target.workY() + Math.max(0, (target.workHeight() - windowHeight) / 2);
    context.runOnClient(client -> {
      Window window = client.getWindow();
      if (window.isFullscreen()) {
        window.toggleFullScreen();
        window.updateFullscreenIfChanged();
      }
      window.setWindowed(windowWidth, windowHeight);
      GLFW.glfwSetWindowPos(window.handle(), x, y);
      GLFW.glfwShowWindow(window.handle());
      GLFW.glfwFocusWindow(window.handle());
      GLFW.glfwPollEvents();
    });
    context.waitFor(client -> {
      Window window = client.getWindow();
      return !window.isFullscreen() && window.findBestMonitor() != null
          && window.findBestMonitor().monitor() == target.handle();
    }, WORLD_TIMEOUT_FRAMES);
    context.runOnClient(client -> {
      client.options.enableVsync().set(false);
      client.options.framerateLimit().set(260);
      Window window = client.getWindow();
      window.toggleFullScreen();
      window.updateFullscreenIfChanged();
    });
    context.waitFor(client -> {
      Window window = client.getWindow();
      int[] geometry = directWindowGeometry(window);
      return window.isFullscreen()
          && GLFW.glfwGetWindowMonitor(window.handle()) == target.handle()
          && geometry[2] >= 1920 && geometry[3] >= 1080
          && window.getRefreshRate() >= minimumRefresh;
    }, WORLD_TIMEOUT_FRAMES);
    return context.computeOnClient(client -> {
      Window window = client.getWindow();
      int[] geometry = directWindowGeometry(window);
      require(window.findBestMonitor() != null
              && window.findBestMonitor().monitor() == target.handle(),
          "fullscreen moved to a different display");
      require(window.getRefreshRate() >= minimumRefresh,
          "fullscreen refresh fell below " + minimumRefresh + "Hz");
      require(geometry[2] >= 1920 && geometry[3] >= 1080,
          "fullscreen framebuffer is below 1920x1080: "
              + geometry[2] + "x" + geometry[3]);
      return new DisplayEvidence(target.name(), geometry[0], geometry[1],
          geometry[2], geometry[3],
          window.getRefreshRate(), true);
    });
  }

  private static int[] directWindowGeometry(Window window) {
    try (MemoryStack stack = MemoryStack.stackPush()) {
      IntBuffer windowWidth = stack.mallocInt(1);
      IntBuffer windowHeight = stack.mallocInt(1);
      IntBuffer framebufferWidth = stack.mallocInt(1);
      IntBuffer framebufferHeight = stack.mallocInt(1);
      GLFW.glfwGetWindowSize(window.handle(), windowWidth, windowHeight);
      GLFW.glfwGetFramebufferSize(
          window.handle(), framebufferWidth, framebufferHeight);
      return new int[] {windowWidth.get(0), windowHeight.get(0),
          framebufferWidth.get(0), framebufferHeight.get(0)};
    }
  }

  private static FrameMetrics captureStationaryPerformance(
      ClientGameTestContext context) {
    FieldFrameSampler.begin();
    context.waitFor(client ->
            FieldFrameSampler.elapsedNanos() >= PERFORMANCE_WINDOW_NANOS,
        PERFORMANCE_TIMEOUT_FRAMES);
    FieldFrameSampler.end();
    FrameMetrics result = metrics(FieldFrameSampler.snapshot());
    require(result.samples() >= MINIMUM_PERFORMANCE_SAMPLES,
        "too few fullscreen performance frames: " + result.samples());
    require(result.averageFps() >= 15.0,
        "fullscreen shader performance collapsed below 15 FPS: "
            + result.averageFps());
    require(result.blackoutClassStutters() == 0,
        "render loop contained a one-second blackout-class stall");
    return result;
  }

  private static void exerciseZoomify(ClientGameTestContext context) {
    KeyMapping zoom = context.computeOnClient(client ->
        Arrays.stream(client.options.keyMappings)
            .filter(mapping -> mapping.getName().toLowerCase(Locale.ROOT)
                .contains("zoomify"))
            .findFirst().orElse(null));
    require(zoom != null, "Zoomify key mapping was not registered");
    context.runOnClient(client -> zoom.setDown(true));
    try {
      waitWallClock(context, 1_500_000_000L, WORLD_TIMEOUT_FRAMES);
    } finally {
      context.runOnClient(client -> zoom.setDown(false));
    }
  }

  private static FrameMetrics exerciseFlightTraversal(
      ClientGameTestContext context, TestSingleplayerContext world) {
    world.getServer().runCommand("gamemode spectator @a");
    FieldFrameSampler.begin();
    for (int step = 0; step < 18; step++) {
      int x = step * 48;
      int z = (step % 2 == 0 ? 1 : -1) * (24 + step * 7);
      int yaw = Math.floorMod(step * 37, 360);
      world.getServer().runCommand(String.format(Locale.ROOT,
          "execute as @a in minecraft:overworld run tp @s %d 128 %d %d 18",
          x, z, yaw));
      waitWallClock(context, FLIGHT_STEP_NANOS, WORLD_TIMEOUT_FRAMES);
    }
    FieldFrameSampler.end();
    FrameMetrics result = metrics(FieldFrameSampler.snapshot());
    require(result.samples() >= 120,
        "flight traversal produced too few rendered frames");
    require(result.averageFps() >= 8.0,
        "flight traversal collapsed below 8 FPS");
    return result;
  }

  private static void exerciseMining(ClientGameTestContext context,
      TestSingleplayerContext world) {
    world.getServer().runCommand("gamemode survival @a");
    world.getServer().runCommand("tp @a 0 100 -3 0 0");
    context.waitFor(client -> client.level != null && client.player != null
            && Math.abs(client.player.getX() - 0.5) < 2.0
            && Math.abs(client.player.getZ() + 2.5) < 2.0
            && client.level.hasChunkAt(MINE_BLOCK),
        WORLD_TIMEOUT_FRAMES);
    waitWallClock(context, 1_000_000_000L, WORLD_TIMEOUT_FRAMES);
    world.getServer().runCommand(
        "item replace entity @a weapon.mainhand with minecraft:netherite_pickaxe");
    world.getServer().runCommand(
        "setblock 0 100 0 minecraft:stone destroy");
    context.waitFor(client -> client.level != null
            && client.level.getBlockState(MINE_BLOCK).getBlock() == Blocks.STONE,
        WORLD_TIMEOUT_FRAMES);
    boolean started = context.computeOnClient(client ->
        client.gameMode != null
            && client.gameMode.startDestroyBlock(MINE_BLOCK, Direction.NORTH));
    require(started, "client did not start the mining action");
    long deadline = System.nanoTime() + 12_000_000_000L;
    while (System.nanoTime() < deadline
        && !context.computeOnClient(client ->
            client.level.getBlockState(MINE_BLOCK).isAir())) {
      context.runOnClient(client ->
          client.gameMode.continueDestroyBlock(MINE_BLOCK, Direction.NORTH));
      waitWallClock(context, 60_000_000L, WORLD_TIMEOUT_FRAMES);
    }
    context.runOnClient(client -> client.gameMode.stopDestroyBlock());
    require(context.computeOnClient(client ->
            client.level.getBlockState(MINE_BLOCK).isAir()),
        "mining did not destroy the target block");
    world.getServer().runCommand("gamemode creative @a");
  }

  private static void verifySafeLiveReload(ClientGameTestContext context) {
    ClientLevel before = context.computeOnClient(client -> client.level);
    context.runOnClient(client -> {
      require(client.levelRenderer.viewArea() != null,
          "ViewArea was null before live reload");
      require(MetalRenderClient.rebuildLevelRenderer(client),
          "safe level renderer rebuild failed");
      require(client.levelRenderer.viewArea() != null,
          "live reload left ViewArea null immediately");
    });
    waitWallClock(context, 12_000_000_000L, WORLD_TIMEOUT_FRAMES);
    context.runOnClient(client -> {
      require(client.level == before, "live reload replaced the active world");
      require(client.levelRenderer != null
              && client.levelRenderer.viewArea() != null,
          "ViewArea became null during the post-reload render window");
    });
  }

  private static void verifyResourceReload(ClientGameTestContext context,
      boolean baseline) {
    CompletableFuture<Void> reload = context.computeOnClient(
        client -> client.reloadResourcePacks());
    context.waitFor(client -> reload.isDone(), RELOAD_TIMEOUT_FRAMES);
    require(!reload.isCancelled() && !reload.isCompletedExceptionally(),
        "resource reload failed");
    reload.join();
    context.waitFor(client -> client.level != null
            && client.levelRenderer != null
            && client.levelRenderer.viewArea() != null
            && IrisApi.getInstance().isShaderPackInUse()
            && (baseline || irisOwnsVisibleFrame()),
        RELOAD_TIMEOUT_FRAMES);
  }

  private void visitDimension(ClientGameTestContext context,
      TestSingleplayerContext world, net.minecraft.resources.ResourceKey<Level> level,
      String dimension, String floorBlock, String screenshotName) {
    ClientLevel previous = context.computeOnClient(client -> client.level);
    world.getServer().runCommand("execute in " + dimension
        + " run fill -12 74 -12 12 74 12 " + floorBlock);
    world.getServer().runCommand("execute as @a in " + dimension
        + " run tp @s 0 78 0 0 18");
    context.waitFor(client -> client.level != null && client.level != previous
            && client.level.dimension() == level,
        WORLD_TIMEOUT_FRAMES);
    waitWallClock(context, 2_000_000_000L, WORLD_TIMEOUT_FRAMES);
    takeVerifiedScreenshot(context, screenshotName);
  }

  private static void buildSecondWorldScene(TestSingleplayerContext world) {
    world.getServer().runCommand("gamemode creative @a");
    world.getServer().runCommand("fill -12 96 -12 12 96 12 minecraft:snow_block");
    world.getServer().runCommand("fill -12 97 -12 12 108 12 minecraft:air");
    world.getServer().runCommand("fillbiome -16 80 -16 15 111 15 minecraft:taiga");
    world.getServer().runCommand("summon minecraft:cow -2 97 3");
    world.getServer().runCommand("summon minecraft:sheep 2 97 3");
    world.getServer().runCommand("tp @a 0 101 -9 0 12");
  }

  private void verifyPausedMetalOwnership(ClientGameTestContext context,
      boolean baseline, boolean establishBaseline) {
    if (baseline) {
      return;
    }
    context.runOnClient(client -> {
      require(irisOwnsVisibleFrame(),
          "Iris did not retain exclusive visible-frame ownership");
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      require(renderer.getChunkMesher().getMeshCount() == 0,
          "Iris mode retained duplicate Metal meshes");
      require(!renderer.areTexturesReady(),
          "Iris mode retained duplicate Metal texture mirrors");
      if (establishBaseline) {
        pausedMetalFrameBaseline = renderer.getFrameCount();
        pausedPresentationBaseline =
            MetalRenderHookState.successfulPresentationCount();
      } else {
        require(renderer.getFrameCount() == pausedMetalFrameBaseline,
            "Metal continued encoding duplicate frames while Iris was active");
        require(MetalRenderHookState.successfulPresentationCount()
                == pausedPresentationBaseline,
            "Metal presented a frame while Iris owned the shader graph");
      }
    });
  }

  private void takeVerifiedScreenshot(ClientGameTestContext context,
      String name) {
    Path path = context.takeScreenshot("complemetal-field-qa-" + name);
    ScreenshotEvidence evidence = analyzeScreenshot(path);
    require(evidence.width() >= 640 && evidence.height() >= 360,
        "screenshot is unexpectedly small: " + path);
    require(evidence.meanLuminance() >= 0.005,
        "screenshot is black: " + path);
    require(evidence.luminanceStdDev() >= 0.008,
        "screenshot is visually uniform: " + path);
    require(evidence.uniqueColors() >= 32,
        "screenshot has too few colors: " + path);
    require(evidence.blackPixelFraction() < 0.985,
        "screenshot is almost entirely black: " + path);
    screenshots.add(evidence);
  }

  private static ScreenshotEvidence analyzeScreenshot(Path path) {
    try {
      require(Files.isRegularFile(path) && Files.size(path) > 0,
          "screenshot was not written: " + path);
      BufferedImage image = ImageIO.read(path.toFile());
      require(image != null, "screenshot is not a decodable PNG: " + path);
      int stepX = Math.max(1, image.getWidth() / 480);
      int stepY = Math.max(1, image.getHeight() / 270);
      long count = 0;
      long black = 0;
      double sum = 0.0;
      double squared = 0.0;
      Set<Integer> colors = new HashSet<>();
      for (int y = 0; y < image.getHeight(); y += stepY) {
        for (int x = 0; x < image.getWidth(); x += stepX) {
          int rgb = image.getRGB(x, y);
          double red = ((rgb >>> 16) & 0xff) / 255.0;
          double green = ((rgb >>> 8) & 0xff) / 255.0;
          double blue = (rgb & 0xff) / 255.0;
          double luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
          sum += luminance;
          squared += luminance * luminance;
          if (luminance < 0.01) {
            black++;
          }
          if (colors.size() < 8192) {
            colors.add(rgb & 0x00ffffff);
          }
          count++;
        }
      }
      double mean = sum / count;
      double variance = Math.max(0.0, squared / count - mean * mean);
      return new ScreenshotEvidence(path.toAbsolutePath().normalize(),
          image.getWidth(), image.getHeight(), colors.size(), mean,
          Math.sqrt(variance), black / (double) count, sha256(path));
    } catch (Exception error) {
      throw new AssertionError("could not analyze screenshot " + path, error);
    }
  }

  private static void waitWallClock(ClientGameTestContext context,
      long durationNanos, int timeoutFrames) {
    long start = System.nanoTime();
    context.waitFor(client -> System.nanoTime() - start >= durationNanos,
        timeoutFrames);
  }

  private static FrameMetrics metrics(FieldFrameSampler.Snapshot snapshot) {
    long[] values = snapshot.frameNanos();
    require(values.length > 0, "frame sampler recorded no frames");
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    long total = 0;
    int over50ms = 0;
    int over100ms = 0;
    int overOneSecond = 0;
    for (long value : values) {
      total = Math.addExact(total, value);
      if (value >= 50_000_000L) {
        over50ms++;
      }
      if (value >= 100_000_000L) {
        over100ms++;
      }
      if (value >= 1_000_000_000L) {
        overOneSecond++;
      }
    }
    long p50 = percentile(sorted, 0.50);
    long p95 = percentile(sorted, 0.95);
    long p99 = percentile(sorted, 0.99);
    double fps = values.length * 1_000_000_000.0 / total;
    double onePercentLow = 1_000_000_000.0 / Math.max(1L, p99);
    return new FrameMetrics(values.length, total, fps, onePercentLow,
        p50, p95, p99, sorted[sorted.length - 1], over50ms, over100ms,
        overOneSecond, snapshot.droppedSamples());
  }

  private static long percentile(long[] sorted, double fraction) {
    int index = Math.max(0, Math.min(sorted.length - 1,
        (int) Math.ceil(sorted.length * fraction) - 1));
    return sorted[index];
  }

  private static void verifyNativeFaults(boolean baseline) {
    if (baseline) {
      return;
    }
    NativeBridge.nFlushFrames();
    require(NativeBridge.nGetGpuCommandBufferErrorCount() == 0,
        "native GPU command-buffer errors were recorded");
    require(NativeBridge.nGetInFlightFrameTimeoutCount() == 0,
        "native in-flight timeouts were recorded");
    require(NativeBridge.nGetNoIOSurfaceSlotSkipCount() == 0,
        "unsafe IOSurface slot skips were recorded");
  }

  private static void settleRetainedHeap(ClientGameTestContext context) {
    System.gc();
    waitWallClock(context, 1_500_000_000L, WORLD_TIMEOUT_FRAMES);
    System.gc();
    waitWallClock(context, 1_500_000_000L, WORLD_TIMEOUT_FRAMES);
  }

  private void writeResult(String side, boolean baseline) {
    JsonObject root = new JsonObject();
    root.addProperty("schemaVersion", 1);
    root.addProperty("status", "PASS");
    root.addProperty("side", side);
    root.addProperty("minecraft", "26.2");
    root.addProperty("shaderPack", Iris.getCurrentPackName());
    root.addProperty("durationNanos", System.nanoTime() - startedNanos);
    root.addProperty("experimentalIrisMetalEnabled", false);
    root.addProperty("metalRuntimeEnabled", !baseline);
    root.add("display", displayEvidence.toJson());
    root.add("stationaryPerformance", stationaryMetrics.toJson());
    root.add("flightPerformance", flightMetrics.toJson());
    JsonObject mods = new JsonObject();
    FabricLoader loader = FabricLoader.getInstance();
    REQUIRED_MODS.stream().sorted().forEach(modId -> mods.addProperty(modId,
        loader.getModContainer(modId).orElseThrow().getMetadata()
            .getVersion().getFriendlyString()));
    root.add("mods", mods);
    JsonArray scenarios = new JsonArray();
    completedScenarios.forEach(scenarios::add);
    root.add("scenarios", scenarios);
    JsonArray visual = new JsonArray();
    screenshots.forEach(item -> visual.add(item.toJson()));
    root.add("screenshots", visual);
    Runtime runtime = Runtime.getRuntime();
    JsonObject memory = new JsonObject();
    memory.addProperty("fullGcSettled", true);
    memory.addProperty("heapUsedBytes",
        runtime.totalMemory() - runtime.freeMemory());
    memory.addProperty("heapCommittedBytes", runtime.totalMemory());
    memory.addProperty("heapMaxBytes", runtime.maxMemory());
    root.add("memory", memory);
    if (!baseline) {
      JsonObject nativeFaults = new JsonObject();
      nativeFaults.addProperty("gpuCommandBufferErrors",
          NativeBridge.nGetGpuCommandBufferErrorCount());
      nativeFaults.addProperty("inFlightTimeouts",
          NativeBridge.nGetInFlightFrameTimeoutCount());
      nativeFaults.addProperty("unsafeSurfaceSlotSkips",
          NativeBridge.nGetNoIOSurfaceSlotSkipCount());
      root.add("nativeFaults", nativeFaults);
    }
    Path result = Path.of(requiredProperty("complemetal.fieldQa.resultPath"))
        .toAbsolutePath();
    Path temporary = result.resolveSibling(result.getFileName() + ".tmp");
    try {
      Files.createDirectories(result.getParent());
      Files.writeString(temporary, root.toString() + "\n",
          StandardCharsets.UTF_8);
      Files.move(temporary, result, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception error) {
      throw new AssertionError("could not write field-QA result " + result,
          error);
    }
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name, "").trim();
    require(!value.isEmpty(), "missing required property " + name);
    return value;
  }

  private static String sha256(Path path) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.newInputStream(path)) {
        byte[] buffer = new byte[1024 * 1024];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
          if (read > 0) {
            digest.update(buffer, 0, read);
          }
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception error) {
      throw new AssertionError("could not hash " + path, error);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private record DisplayEvidence(String monitor, int logicalWidth,
                                 int logicalHeight, int framebufferWidth,
                                 int framebufferHeight, int refreshHz,
                                 boolean fullscreen) {
    private JsonObject toJson() {
      JsonObject result = new JsonObject();
      result.addProperty("monitor", monitor);
      result.addProperty("logicalWidth", logicalWidth);
      result.addProperty("logicalHeight", logicalHeight);
      result.addProperty("framebufferWidth", framebufferWidth);
      result.addProperty("framebufferHeight", framebufferHeight);
      result.addProperty("refreshHz", refreshHz);
      result.addProperty("fullscreen", fullscreen);
      return result;
    }
  }

  private record FrameMetrics(int samples, long durationNanos,
                              double averageFps, double onePercentLowFps,
                              long p50Nanos, long p95Nanos, long p99Nanos,
                              long maximumNanos, int framesOver50ms,
                              int framesOver100ms, int blackoutClassStutters,
                              int droppedSamples) {
    private JsonObject toJson() {
      JsonObject result = new JsonObject();
      result.addProperty("samples", samples);
      result.addProperty("durationNanos", durationNanos);
      result.addProperty("averageFps", averageFps);
      result.addProperty("onePercentLowFps", onePercentLowFps);
      result.addProperty("p50Nanos", p50Nanos);
      result.addProperty("p95Nanos", p95Nanos);
      result.addProperty("p99Nanos", p99Nanos);
      result.addProperty("maximumNanos", maximumNanos);
      result.addProperty("framesOver50ms", framesOver50ms);
      result.addProperty("framesOver100ms", framesOver100ms);
      result.addProperty("blackoutClassStutters", blackoutClassStutters);
      result.addProperty("droppedSamples", droppedSamples);
      return result;
    }
  }

  private record ScreenshotEvidence(Path path, int width, int height,
                                    int uniqueColors, double meanLuminance,
                                    double luminanceStdDev,
                                    double blackPixelFraction, String sha256) {
    private JsonObject toJson() {
      JsonObject result = new JsonObject();
      result.addProperty("path", path.toString());
      result.addProperty("width", width);
      result.addProperty("height", height);
      result.addProperty("uniqueColors", uniqueColors);
      result.addProperty("meanLuminance", meanLuminance);
      result.addProperty("luminanceStdDev", luminanceStdDev);
      result.addProperty("blackPixelFraction", blackPixelFraction);
      result.addProperty("sha256", sha256);
      return result;
    }
  }
}

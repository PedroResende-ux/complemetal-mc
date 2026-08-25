package com.pebbles_boon.metalrender.gametest;

import com.mojang.blaze3d.platform.Window;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalTextureManager;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/**
 * End-to-end macOS smoke test for the safe Metal 4 hybrid path.
 */
@SuppressWarnings("UnstableApiUsage")
public final class MetalRenderClientGameTest implements FabricClientGameTest {
  private static final int STARTUP_TIMEOUT_TICKS = 1_200;
  private static final int WORLD_TIMEOUT_TICKS = 2_400;
  private static final int RELOAD_TIMEOUT_TICKS = 2_400;
  private static final int MIN_METAL_FRAMES = 30;
  private static final int FRAME_ADVANCE_PER_GATE = 8;
  private static final BlockPos CHEST_POS = new BlockPos(2, 99, 4);
  private static final BlockPos WATER_POS = new BlockPos(-5, 100, 5);
  private static final BlockPos LAVA_POS = new BlockPos(6, 100, 5);
  private static final BlockPos GLASS_POS = new BlockPos(-7, 100, 5);
  private static final BlockPos TINTED_GLASS_POS =
      new BlockPos(4, 100, 5);
  private static final BlockPos NETHER_FLOOR_POS =
      new BlockPos(0, 74, 0);
  private static final BlockPos END_FLOOR_POS =
      new BlockPos(0, 74, 0);
  private static final Identifier STONE_TEXTURE =
      Identifier.withDefaultNamespace("textures/block/stone.png");
  private static final boolean BASELINE =
      Boolean.getBoolean("metalrender.gametest.baseline");
  private static final boolean EXPECT_METAL4 =
      !"false".equalsIgnoreCase(
          System.getProperty("metalrender.feature.metal4", "true"));
  private static final boolean REQUIRE_RETINA =
      Boolean.getBoolean("metalrender.gametest.requireRetina");
  private static final boolean TEST_ICONIFY =
      Boolean.getBoolean("metalrender.gametest.iconify");

  @Override
  public void runTest(ClientGameTestContext context) {
    if (BASELINE) {
      runVanillaBaseline(context);
      return;
    }

    context.waitFor(client -> {
      MetalRenderClient.InitState state = MetalRenderClient.getInitState();
      return state == MetalRenderClient.InitState.READY
          || state == MetalRenderClient.InitState.UNSUPPORTED
          || state == MetalRenderClient.InitState.FAILED;
    }, STARTUP_TIMEOUT_TICKS);

    require(MetalRenderClient.getInitState()
            == MetalRenderClient.InitState.READY,
        "Metal renderer did not initialize: "
            + MetalRenderClient.getInitFailure());
    require(MetalRenderClient.isEnabled(),
        "Metal renderer is not enabled after initialization");
    require(NativeBridge.isLibLoaded(),
        "packaged native bridge was not loaded");
    require(!NativeBridge.nIsMetal4DrawPathActive(),
        "unvalidated native MTL4 draw encoding must stay disabled");
    if (EXPECT_METAL4) {
      require(NativeBridge.nSupportsMetal4(),
          "Metal 4 is not supported on the test device");
      require(NativeBridge.nIsMetal4Active(),
          "Metal 4 runtime objects are not active");
      require("METAL4_RUNTIME_VERIFIED_METAL3_RENDER".equals(
              NativeBridge.nGetBackendMode()),
          "unexpected Metal 4 hybrid backend mode: "
              + NativeBridge.nGetBackendMode());
    } else {
      require(!NativeBridge.nIsMetal4Active(),
          "Metal 4 runtime stayed active in compatibility-mode test");
      require("METAL3".equals(NativeBridge.nGetBackendMode()),
          "unexpected Metal 3 compatibility backend mode: "
              + NativeBridge.nGetBackendMode());
    }

    MetalRenderConfig config = MetalRenderClient.getConfig();
    require(config != null, "MetalRender config was not loaded");
    require(config.enableMetal4 == EXPECT_METAL4,
        "Metal 4 config does not match the requested test mode");
    require(!config.enableFastTerrainReplacement,
        "fast terrain replacement must be off in safe defaults");
    require(!config.enableExperimentalFeatureReplacement,
        "experimental entity/particle replacement must be off in safe defaults");

    TestWorldSave worldSave;
    ClientLevel levelBeforeClose;
    try (TestSingleplayerContext singleplayer =
             context.worldBuilder().setUseConsistentSettings(true).create()) {
      configureDeterministicWorld(singleplayer);

      context.waitFor(client -> {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        return client.level != null
            && client.player != null
            && renderer != null
            && renderer.isWorldLoaded()
            && renderer.areTexturesReady()
            && renderer.getFrameCount() >= MIN_METAL_FRAMES;
      }, WORLD_TIMEOUT_TICKS);

      context.runOnClient(client -> {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        require(client.levelRenderer != null,
            "vanilla level renderer is unavailable");
        require(renderer != null && renderer.isWorldLoaded(),
            "Metal world renderer did not enter the loaded state");
        require(renderer.areTexturesReady(),
            "Metal texture handoff did not become ready");
        require(renderer.getFrameCount() >= MIN_METAL_FRAMES,
            "Metal frame lifecycle did not advance");
        require(client.level.dimension() == Level.OVERWORLD,
            "initial client level is not the Overworld");
      });

      verifyLiveLevelRendererReload(context);

      int entityRecoveryBefore = bulkRecoveryCount(context);
      long entityBuildEpochBefore = currentBuildEpoch(context);
      buildEntityScene(singleplayer);
      verifyEntitiesAndBlockEntity(context);
      waitForBulkUpdateRecovery(
          context, entityRecoveryBefore, true);
      requireSingleCleanRebuild(
          context, entityBuildEpochBefore, "entity fixture");
      verifyWindowLifecycle(context);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-entities-block-entities");

      long[] fluidMeshRevisionsBefore =
          captureMeshSectionRevisions(context,
              6, -1, 0, 0, 0);
      buildFluidScene(singleplayer);
      verifyFluidsAndTranslucency(context);
      waitForMeshSections(context, fluidMeshRevisionsBefore,
          6, -1, 0, 0, 0);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-fluids-translucency");

      verifyWeatherAndParticles(context, singleplayer);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-weather-particles");

      singleplayer.getServer().runCommand("weather clear");
      context.waitFor(client -> client.level != null
          && client.level.getRainLevel(1.0F) < 0.05F,
          WORLD_TIMEOUT_TICKS);
      verifyResourceReload(context);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-after-resource-reload");

      verifyDimensionLifecycle(context, singleplayer);

      worldSave = singleplayer.getWorldSave();
      levelBeforeClose = context.computeOnClient(client -> client.level);
    }

    verifyWorldUnload(context);

    try (TestSingleplayerContext reopened = worldSave.open()) {
      reopened.getServer().runCommand("weather clear");
      context.waitFor(client -> {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        return client.level != null
            && client.level != levelBeforeClose
            && client.level.dimension() == Level.OVERWORLD
            && client.level.getBlockEntity(CHEST_POS)
                instanceof ChestBlockEntity
            && renderer != null
            && renderer.isWorldLoaded()
            && renderer.areTexturesReady()
            && renderer.getFrameCount() >= MIN_METAL_FRAMES;
      }, WORLD_TIMEOUT_TICKS);

      context.runOnClient(client -> {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        require(client.level != levelBeforeClose,
            "reopened world reused the detached ClientLevel instance");
        require(client.level.getBlockEntity(CHEST_POS)
                instanceof ChestBlockEntity,
            "block entity did not persist across world close/reopen");
        require(hasEntity(client.level, EntityTypes.ARMOR_STAND),
            "armor stand did not persist across world close/reopen");
        require(renderer != null && renderer.isWorldLoaded()
                && renderer.areTexturesReady(),
            "Metal renderer did not reattach after reopening the save");
      });

      takeVerifiedScreenshot(context,
          "metalrender-26.2-reopened-world");
    }
    verifyWorldUnload(context);
    context.runOnClient(client -> {
      NativeBridge.nFlushFrames();
      require(NativeBridge.nGetGpuCommandBufferErrorCount() == 0,
          "GPU command-buffer errors were recorded during active QA");
      require(NativeBridge.nGetInFlightFrameTimeoutCount() == 0,
          "in-flight frame timeouts were recorded during active QA");
      require(NativeBridge.nGetNoIOSurfaceSlotSkipCount() == 0,
          "unsafe IOSurface slot skips were recorded during active QA");
    });
  }

  private static void verifyWindowLifecycle(
      ClientGameTestContext context) {
    int[] originalWindow = context.computeOnClient(client -> {
      Window window = client.getWindow();
      require(!window.isFullscreen(),
          "window lifecycle test must start windowed");
      return new int[] {
          window.getScreenWidth(),
          window.getScreenHeight(),
          window.getWidth(),
          window.getHeight()
      };
    });

    verifyRetinaAndNativeSurface(context, true);
    int frameBeforeResize = currentFrameCount(context);
    context.runOnClient(client ->
        client.getWindow().setWindowed(960, 540));
    context.waitFor(client -> {
      Window window = client.getWindow();
      return !window.isFullscreen()
          && window.getScreenWidth() == 960
          && window.getScreenHeight() == 540
          && nativeSurfaceMatches(window);
    }, WORLD_TIMEOUT_TICKS);
    waitForFrameAdvance(
        context, frameBeforeResize, FRAME_ADVANCE_PER_GATE);
    verifyRetinaAndNativeSurface(context, true);
    takeVerifiedScreenshot(context,
        "metalrender-26.2-" + backendTestMode() + "-windowed-resize");

    if (placeWindowOnPrimaryMonitor(context)) {
      int frameBeforeFullscreen = currentFrameCount(context);
      context.runOnClient(client -> {
        Window window = client.getWindow();
        window.toggleFullScreen();
        window.updateFullscreenIfChanged();
      });
      context.waitFor(client -> {
        Window window = client.getWindow();
        return window.isFullscreen()
            && window.getWidth() > 0
            && window.getHeight() > 0
            && nativeSurfaceMatches(window);
      }, WORLD_TIMEOUT_TICKS);
      waitForFrameAdvance(
          context, frameBeforeFullscreen, FRAME_ADVANCE_PER_GATE);
      verifyRetinaAndNativeSurface(context, false);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-" + backendTestMode() + "-fullscreen");
    } else {
      System.out.println(
          "METALRENDER_STABLE_QA fullscreen=skipped "
              + "reason=no-glfw-monitor");
    }

    int frameBeforeRestore = currentFrameCount(context);
    context.runOnClient(client ->
        client.getWindow().setWindowed(
            originalWindow[0], originalWindow[1]));
    context.waitFor(client -> {
      Window window = client.getWindow();
      return !window.isFullscreen()
          && window.getScreenWidth() == originalWindow[0]
          && window.getScreenHeight() == originalWindow[1]
          && nativeSurfaceMatches(window);
    }, WORLD_TIMEOUT_TICKS);
    waitForFrameAdvance(
        context, frameBeforeRestore, FRAME_ADVANCE_PER_GATE);
    verifyRetinaAndNativeSurface(context, true);

    if (TEST_ICONIFY) {
      int frameBeforeIconify = currentFrameCount(context);
      context.runOnClient(client ->
          GLFW.glfwIconifyWindow(client.getWindow().handle()));
      context.waitFor(client -> client.getWindow().isIconified(),
          WORLD_TIMEOUT_TICKS);
      context.runOnClient(client ->
          GLFW.glfwRestoreWindow(client.getWindow().handle()));
      context.waitFor(client -> !client.getWindow().isIconified()
          && nativeSurfaceMatches(client.getWindow()),
          WORLD_TIMEOUT_TICKS);
      waitForFrameAdvance(
          context, frameBeforeIconify, FRAME_ADVANCE_PER_GATE);
    }

    context.runOnClient(client -> {
      Window window = client.getWindow();
      require(window.getWidth() == originalWindow[2]
              && window.getHeight() == originalWindow[3],
          "framebuffer size did not return to its original dimensions");
      require(nativeSurfaceMatches(window),
          "Metal IOSurface did not recover after iconify/restore");
    });
  }

  private static void verifyLiveLevelRendererReload(
      ClientGameTestContext context) {
    ClientLevel levelBefore =
        context.computeOnClient(client -> client.level);
    context.runOnClient(client -> {
      require(client.levelRenderer.viewArea() != null,
          "ViewArea was unavailable before live reload");
      require(MetalRenderClient.rebuildLevelRenderer(client),
          "live level renderer rebuild failed");
      require(client.levelRenderer.viewArea() != null,
          "live reload left ViewArea null");
    });
    context.waitFor(client -> client.level == levelBefore
        && client.levelRenderer != null
        && client.levelRenderer.viewArea() != null,
        WORLD_TIMEOUT_TICKS);
    waitForWorldTicks(context, 20);
    context.runOnClient(client -> require(
        client.levelRenderer.viewArea() != null,
        "ViewArea became null after the post-reload render window"));
  }

  private static boolean placeWindowOnPrimaryMonitor(
      ClientGameTestContext context) {
    return context.computeOnClient(client -> {
      Window window = client.getWindow();
      // GLFW defines the primary monitor's virtual-desktop origin as (0, 0).
      // Moving there is more robust in GameTest than querying monitor handles:
      // on macOS glfwGetPrimaryMonitor may transiently return NULL even while
      // an onscreen window and its content scale are already available.
      GLFW.glfwSetWindowPos(window.handle(), 0, 0);
      return GLFW.glfwGetPrimaryMonitor() != 0
          && window.findBestMonitor() != null;
    });
  }

  private static void verifyRetinaAndNativeSurface(
      ClientGameTestContext context, boolean requireRetinaAtThisStage) {
    context.runOnClient(client -> {
      Window window = client.getWindow();
      require(window.getScreenWidth() > 0
              && window.getScreenHeight() > 0,
          "logical window dimensions are invalid");
      double scaleX =
          (double) window.getWidth() / window.getScreenWidth();
      double scaleY =
          (double) window.getHeight() / window.getScreenHeight();
      double[] contentScale = windowContentScale(window);
      require(scaleX >= 1.0 && scaleX <= 4.0
              && scaleY >= 1.0 && scaleY <= 4.0,
          "framebuffer scale is outside the supported range: "
              + scaleX + "x" + scaleY);
      require(Math.abs(scaleX - scaleY) < 0.05,
          "framebuffer scale is inconsistent between axes: "
              + scaleX + "x" + scaleY);
      if (REQUIRE_RETINA && requireRetinaAtThisStage) {
        require(contentScale[0] >= 1.5 && contentScale[1] >= 1.5
                && scaleX >= 1.5 && scaleY >= 1.5,
            "stable QA requested a Retina framebuffer but measured "
                + scaleX + "x" + scaleY + " framebuffer scale and "
                + contentScale[0] + "x" + contentScale[1]
                + " GLFW content scale");
      }
      require(nativeSurfaceMatches(window),
          "Metal IOSurface dimensions do not match the framebuffer");
      System.out.printf(
          "METALRENDER_STABLE_QA window=%dx%d framebuffer=%dx%d "
              + "framebufferScale=%.2fx%.2f contentScale=%.2fx%.2f "
              + "fullscreen=%s mode=%s%n",
          window.getScreenWidth(), window.getScreenHeight(),
          window.getWidth(), window.getHeight(),
          scaleX, scaleY, contentScale[0], contentScale[1],
          window.isFullscreen(), backendTestMode());
    });
  }

  private static double[] windowContentScale(Window window) {
    try (MemoryStack stack = MemoryStack.stackPush()) {
      FloatBuffer xScale = stack.mallocFloat(1);
      FloatBuffer yScale = stack.mallocFloat(1);
      GLFW.glfwGetWindowContentScale(
          window.handle(), xScale, yScale);
      return new double[] {xScale.get(0), yScale.get(0)};
    }
  }

  private static boolean nativeSurfaceMatches(Window window) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    return renderer != null
        && renderer.getHandle() != 0
        && NativeBridge.nGetIOSurfaceWidth(renderer.getHandle())
            == window.getWidth()
        && NativeBridge.nGetIOSurfaceHeight(renderer.getHandle())
            == window.getHeight();
  }

  private static String backendTestMode() {
    return EXPECT_METAL4 ? "metal4-hybrid" : "metal3";
  }

  private static void runVanillaBaseline(ClientGameTestContext context) {
    context.waitFor(client -> MetalRenderClient.getConfig() != null,
        STARTUP_TIMEOUT_TICKS);
    require(!MetalRenderClient.getConfig().enableMetalRendering,
        "baseline requires -Dmetalrender.enabled=false");
    require(!MetalRenderClient.isEnabled(),
        "Metal renderer must stay disabled in baseline mode");
    require(!NativeBridge.isLibLoaded(),
        "baseline unexpectedly loaded the Metal native bridge");

    try (TestSingleplayerContext singleplayer =
             context.worldBuilder().setUseConsistentSettings(true).create()) {
      configureDeterministicWorld(singleplayer);
      context.waitFor(client -> client.level != null
          && client.player != null
          && client.levelRenderer != null, WORLD_TIMEOUT_TICKS);
      long gameTimeBefore =
          context.computeOnClient(client -> client.level.getGameTime());
      context.waitFor(client ->
              client.level.getGameTime() >= gameTimeBefore + 20,
          WORLD_TIMEOUT_TICKS);

      buildEntityScene(singleplayer);
      context.waitFor(client -> client.level != null
          && client.level.getBlockEntity(CHEST_POS)
              instanceof ChestBlockEntity
          && hasEntity(client.level, EntityTypes.ARMOR_STAND)
          && hasEntity(client.level, EntityTypes.PIG)
          && hasEntity(client.level, EntityTypes.MINECART),
          WORLD_TIMEOUT_TICKS);
      waitForWorldTicks(context, 40);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-vanilla-entities-block-entities");

      buildFluidScene(singleplayer);
      context.waitFor(client -> client.level != null
          && client.level.getFluidState(WATER_POS)
              .isSourceOfType(Fluids.WATER)
          && client.level.getFluidState(LAVA_POS)
              .isSourceOfType(Fluids.LAVA)
          && client.level.getBlockState(GLASS_POS).getBlock()
              == Blocks.GLASS
          && client.level.getBlockState(TINTED_GLASS_POS).getBlock()
              == Blocks.TINTED_GLASS,
          WORLD_TIMEOUT_TICKS);
      waitForWorldTicks(context, 40);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-vanilla-fluids-translucency");

      verifyVanillaWeatherAndParticles(context, singleplayer);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-vanilla-weather-particles");

      verifyVanillaEndBaseline(context, singleplayer);

      takeVerifiedScreenshot(context,
          "metalrender-26.2-vanilla-baseline");
    }
  }

  private static void verifyVanillaEndBaseline(
      ClientGameTestContext context,
      TestSingleplayerContext singleplayer) {
    ClientLevel overworldLevel =
        context.computeOnClient(client -> client.level);
    singleplayer.getServer().runCommand(
        "execute as @a in minecraft:the_end "
            + "run tp @s 0 80 0");
    context.waitFor(client -> client.level != null
        && client.level != overworldLevel
        && client.level.dimension() == Level.END,
        WORLD_TIMEOUT_TICKS);
    prepareEndScene(context, singleplayer);
    context.waitFor(client -> client.level != null
        && client.level.dimension() == Level.END
        && client.level.getBlockState(END_FLOOR_POS).getBlock()
            == Blocks.END_STONE,
        WORLD_TIMEOUT_TICKS);
    waitForWorldTicks(context, 60);
    takeVerifiedScreenshot(context,
        "metalrender-26.2-vanilla-end-dimension");

    ClientLevel endLevel =
        context.computeOnClient(client -> client.level);
    singleplayer.getServer().runCommand(
        "execute as @a in minecraft:overworld "
            + "run tp @s 0 104 -14 0 12");
    context.waitFor(client -> client.level != null
        && client.level != endLevel
        && client.level.dimension() == Level.OVERWORLD
        && client.level.getBlockEntity(CHEST_POS)
            instanceof ChestBlockEntity,
        WORLD_TIMEOUT_TICKS);
    waitForWorldTicks(context, 20);
  }

  private static void verifyVanillaWeatherAndParticles(
      ClientGameTestContext context,
      TestSingleplayerContext singleplayer) {
    singleplayer.getServer().runCommand("weather rain");
    context.waitFor(client -> client.level != null
        && client.level.isRaining()
        && client.level.getRainLevel(1.0F) > 0.2F,
        WORLD_TIMEOUT_TICKS);

    int created = context.computeOnClient(client -> {
      client.particleEngine.clearParticles();
      int successful = 0;
      for (int index = 0; index < 96; index++) {
        double x = ((index % 12) - 5.5) * 0.65;
        double y = 100.0 + ((index / 12) % 4) * 0.65;
        double z = 0.5 + (index / 48) * 2.0;
        if (client.particleEngine.createParticle(
                ParticleTypes.END_ROD, x, y, z,
                0.0, 0.01, 0.0) != null) {
          successful++;
        }
      }
      return successful;
    });
    require(created >= 64,
        "vanilla particle providers created only "
            + created + " of 96 particles");
    context.waitFor(client ->
        particleTotal(client.particleEngine.countParticles()) >= 32,
        200);
  }

  private static void configureDeterministicWorld(
      TestSingleplayerContext singleplayer) {
    singleplayer.getServer().runCommand("difficulty peaceful");
    singleplayer.getServer().runOnServer(server -> {
      GameRules rules = server.getGameRules();
      rules.set(GameRules.ADVANCE_TIME, false, server);
      rules.set(GameRules.ADVANCE_WEATHER, false, server);
      rules.set(GameRules.SPAWN_MOBS, false, server);
      rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);
      require(!rules.get(GameRules.ADVANCE_TIME),
          "advance_time gamerule did not disable");
      require(!rules.get(GameRules.ADVANCE_WEATHER),
          "advance_weather gamerule did not disable");
      require(!rules.get(GameRules.SPAWN_MOBS),
          "spawn_mobs gamerule did not disable");
      require(rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) == 0,
          "fire spread gamerule did not disable");
    });
    singleplayer.getServer().runCommand("time set noon");
    singleplayer.getServer().runCommand("weather clear");
    singleplayer.getServer().runCommand("gamemode spectator @a");
    singleplayer.getServer().runCommand("tp @a 0 104 -14 0 12");
  }

  private static void buildEntityScene(
      TestSingleplayerContext singleplayer) {
    singleplayer.getServer().runCommand(
        "fill -16 98 -18 16 115 16 minecraft:air");
    singleplayer.getServer().runCommand(
        "fill -16 98 -18 16 98 16 minecraft:smooth_stone");
    singleplayer.getServer().runCommand(
        "kill @e[type=!minecraft:player]");
    singleplayer.getServer().runCommand(
        "setblock 2 99 4 minecraft:chest");
    singleplayer.getServer().runCommand(
        "summon minecraft:armor_stand -1 99 4");
    singleplayer.getServer().runCommand(
        "summon minecraft:pig 0 99 5");
    singleplayer.getServer().runCommand(
        "summon minecraft:minecart 4 99 6");
    singleplayer.getServer().runCommand("tp @a 0 104 -14 0 12");
  }

  private static void buildFluidScene(
      TestSingleplayerContext singleplayer) {
    // Solid glass volumes are carved out by the fluid fills. This guarantees
    // source fluids plus visible translucent boundaries without fluid spread.
    singleplayer.getServer().runCommand(
        "fill -7 99 3 -3 102 7 minecraft:glass");
    singleplayer.getServer().runCommand(
        "fill -6 100 4 -4 101 6 minecraft:water");
    singleplayer.getServer().runCommand(
        "fill 4 99 3 8 102 7 minecraft:tinted_glass");
    singleplayer.getServer().runCommand(
        "fill 5 100 4 7 101 6 minecraft:lava");
  }

  private static void verifyEntitiesAndBlockEntity(
      ClientGameTestContext context) {
    int frameBefore = currentFrameCount(context);
    context.waitFor(client -> client.level != null
        && client.level.getBlockEntity(CHEST_POS)
            instanceof ChestBlockEntity
        && hasEntity(client.level, EntityTypes.ARMOR_STAND)
        && hasEntity(client.level, EntityTypes.PIG)
        && hasEntity(client.level, EntityTypes.MINECART),
        WORLD_TIMEOUT_TICKS);
    waitForFrameAdvance(context, frameBefore, FRAME_ADVANCE_PER_GATE);

    context.runOnClient(client -> {
      ChestBlockEntity chest =
          (ChestBlockEntity) client.level.getBlockEntity(CHEST_POS);
      require(chest != null,
          "client did not receive the chest block entity");
      require(client.getBlockEntityRenderDispatcher().getRenderer(chest)
              != null,
          "chest has no client block-entity renderer");
      require(hasEntity(client.level, EntityTypes.ARMOR_STAND),
          "armor stand is missing from client render entities");
      require(hasEntity(client.level, EntityTypes.PIG),
          "pig is missing from client render entities");
      require(hasEntity(client.level, EntityTypes.MINECART),
          "minecart is missing from client render entities");
      requireEntityRenderer(client, client.level, EntityTypes.ARMOR_STAND,
          "armor stand");
      requireEntityRenderer(client, client.level, EntityTypes.PIG, "pig");
      requireEntityRenderer(client, client.level, EntityTypes.MINECART,
          "minecart");
    });
  }

  private static void verifyFluidsAndTranslucency(
      ClientGameTestContext context) {
    int frameBefore = currentFrameCount(context);
    context.waitFor(client -> client.level != null
        && client.level.getFluidState(WATER_POS)
            .isSourceOfType(Fluids.WATER)
        && client.level.getFluidState(LAVA_POS)
            .isSourceOfType(Fluids.LAVA)
        && client.level.getBlockState(GLASS_POS).getBlock() == Blocks.GLASS
        && client.level.getBlockState(TINTED_GLASS_POS).getBlock()
            == Blocks.TINTED_GLASS,
        WORLD_TIMEOUT_TICKS);
    waitForFrameAdvance(context, frameBefore, FRAME_ADVANCE_PER_GATE);

    context.runOnClient(client -> {
      var water = client.level.getFluidState(WATER_POS);
      var lava = client.level.getFluidState(LAVA_POS);
      require(water.isSourceOfType(Fluids.WATER),
          "water source did not reach the client");
      require(lava.isSourceOfType(Fluids.LAVA),
          "lava source did not reach the client");
      require(client.level.getBlockState(GLASS_POS).getBlock()
              == Blocks.GLASS,
          "glass boundary is missing from translucent scene");
      require(client.level.getBlockState(TINTED_GLASS_POS).getBlock()
              == Blocks.TINTED_GLASS,
          "tinted-glass boundary is missing from translucent scene");
      var fluidModels =
          client.getModelManager().getFluidStateModelSet();
      require(fluidModels.get(water).layer().translucent(),
          "water model is not assigned to a translucent render layer");
      require(fluidModels.get(lava) != null,
          "lava fluid model is unavailable");
      require(!fluidModels.get(lava).layer().translucent(),
          "lava model is not assigned to the opaque render layer");
      require(client.getModelManager().getBlockStateModelSet()
              .get(client.level.getBlockState(GLASS_POS)) != null,
          "glass block model is unavailable");
      require(client.getModelManager().getBlockStateModelSet()
              .get(client.level.getBlockState(TINTED_GLASS_POS)) != null,
          "tinted-glass block model is unavailable");
    });
  }

  private static void verifyWeatherAndParticles(
      ClientGameTestContext context,
      TestSingleplayerContext singleplayer) {
    singleplayer.getServer().runCommand("weather rain");
    context.waitFor(client -> client.level != null
        && client.level.isRaining()
        && client.level.getRainLevel(1.0F) > 0.2F,
        WORLD_TIMEOUT_TICKS);

    int created = context.computeOnClient(client -> {
      client.particleEngine.clearParticles();
      int successful = 0;
      for (int index = 0; index < 96; index++) {
        double x = ((index % 12) - 5.5) * 0.65;
        double y = 100.0 + ((index / 12) % 4) * 0.65;
        double z = 0.5 + (index / 48) * 2.0;
        if (client.particleEngine.createParticle(
                ParticleTypes.END_ROD, x, y, z,
                0.0, 0.01, 0.0) != null) {
          successful++;
        }
      }
      return successful;
    });
    require(created >= 64,
        "particle providers created only " + created + " of 96 particles");

    int frameBefore = currentFrameCount(context);
    context.waitFor(client ->
        particleTotal(client.particleEngine.countParticles()) >= 32,
        200);
    waitForFrameAdvance(context, frameBefore, 2);
    context.runOnClient(client -> {
      require(client.level.isRaining()
              && client.level.getRainLevel(1.0F) > 0.2F,
          "rain state disappeared before the weather render gate");
      require(particleTotal(client.particleEngine.countParticles()) >= 32,
          "created particles never entered the client particle engine");
    });
  }

  private static void verifyResourceReload(
      ClientGameTestContext context) {
    context.runOnClient(client ->
        require(client.getResourceManager().getResource(STONE_TEXTURE)
                .isPresent(),
            "vanilla stone texture is unavailable before reload"));

    FluidStateModelSet fluidModelsBefore =
        context.computeOnClient(client ->
            client.getModelManager().getFluidStateModelSet());
    long atlasRevisionBefore =
        MetalTextureManager.getAtlasDirtyRevision();
    long gpuErrorsBefore =
        NativeBridge.nGetGpuCommandBufferErrorCount();
    require(gpuErrorsBefore == 0,
        "GPU command-buffer errors existed before resource reload");
    int frameBefore = currentFrameCount(context);
    CompletableFuture<Void> reload = context.computeOnClient(
        client -> client.reloadResourcePacks());
    context.waitFor(client -> reload.isDone(), RELOAD_TIMEOUT_TICKS);
    require(!reload.isCancelled(),
        "client resource reload was cancelled");
    require(!reload.isCompletedExceptionally(),
        "client resource reload completed exceptionally");
    reload.join();
    long reloadAtlasRevision =
        MetalTextureManager.getAtlasDirtyRevision();
    require(reloadAtlasRevision > atlasRevisionBefore,
        "resource reload did not mark the Metal atlas dirty");

    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return client.level != null
          && client.gui.overlay() == null
          && client.getResourceManager().getResource(STONE_TEXTURE)
              .isPresent()
          && client.getModelManager().getFluidStateModelSet()
              != fluidModelsBefore
          && renderer != null
          && renderer.isWorldLoaded()
          && renderer.areTexturesReady()
          && !renderer.getTextureManager().isAtlasReadbackPending()
          && renderer.getTextureManager().getCompletedAtlasRevision()
              >= reloadAtlasRevision
          && renderer.getFrameCount()
              >= frameBefore + FRAME_ADVANCE_PER_GATE;
    }, RELOAD_TIMEOUT_TICKS);

    context.runOnClient(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      require(client.getResourceManager().getResource(STONE_TEXTURE)
              .isPresent(),
          "vanilla stone texture disappeared after resource reload");
      require(client.getModelManager().getFluidStateModelSet()
              != fluidModelsBefore,
          "resource reload did not replace the baked fluid-model set");
      require(renderer != null && renderer.isWorldLoaded()
              && renderer.areTexturesReady(),
          "Metal renderer did not recover after resource reload");
      require(!renderer.getTextureManager().isAtlasReadbackPending()
              && renderer.getTextureManager().getCompletedAtlasRevision()
                  >= reloadAtlasRevision,
          "Metal atlas handoff did not complete after resource reload");
      NativeBridge.nFlushFrames();
      require(NativeBridge.nGetGpuCommandBufferErrorCount()
              == gpuErrorsBefore,
          "Metal atlas upload reported a GPU command-buffer error");
    });
  }

  private static void verifyDimensionLifecycle(
      ClientGameTestContext context,
      TestSingleplayerContext singleplayer) {
    ClientLevel overworldLevel =
        context.computeOnClient(client -> client.level);
    singleplayer.getServer().runCommand(
        "execute as @a in minecraft:the_nether "
            + "run tp @s 0 80 0");
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return client.level != null
          && client.level != overworldLevel
          && client.level.dimension() == Level.NETHER
          && renderer != null
          && renderer.isWorldLoaded()
          && renderer.areTexturesReady()
          && renderer.getFrameCount() >= MIN_METAL_FRAMES;
    }, WORLD_TIMEOUT_TICKS);

    long[] netherMeshRevisionsBefore =
        captureMeshSectionRevisions(context,
            4, -1, 0, -1, 0);
    singleplayer.getServer().runCommand(
        "execute in minecraft:the_nether "
            + "run fill -12 74 -12 12 90 12 minecraft:air");
    singleplayer.getServer().runCommand(
        "execute in minecraft:the_nether "
            + "run fill -12 74 -12 12 74 12 minecraft:crimson_nylium");
    singleplayer.getServer().runCommand(
        "execute as @a in minecraft:the_nether "
            + "run tp @s 0 80 -8 0 20");
    context.waitFor(client -> client.level != null
        && client.level.dimension() == Level.NETHER
        && client.level.getBlockState(NETHER_FLOOR_POS).getBlock()
            == Blocks.CRIMSON_NYLIUM,
        WORLD_TIMEOUT_TICKS);
    waitForWorldTicks(context, 60);
    waitForMeshSections(context, netherMeshRevisionsBefore,
        4, -1, 0, -1, 0);
    int netherFrame = currentFrameCount(context);
    waitForFrameAdvance(context, netherFrame, FRAME_ADVANCE_PER_GATE);
    takeVerifiedScreenshot(context,
        "metalrender-26.2-nether-dimension");

    ClientLevel netherLevel =
        context.computeOnClient(client -> client.level);
    singleplayer.getServer().runCommand(
        "execute as @a in minecraft:the_end "
            + "run tp @s 0 80 0");
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return client.level != null
          && client.level != netherLevel
          && client.level.dimension() == Level.END
          && renderer != null
          && renderer.isWorldLoaded()
          && renderer.areTexturesReady()
          && renderer.getFrameCount() >= MIN_METAL_FRAMES;
    }, WORLD_TIMEOUT_TICKS);

    long[] endMeshRevisionsBefore =
        captureMeshSectionRevisions(context,
            4, -1, 0, -1, 0);
    prepareEndScene(context, singleplayer);
    context.waitFor(client -> client.level != null
        && client.level.dimension() == Level.END
        && client.level.getBlockState(END_FLOOR_POS).getBlock()
            == Blocks.END_STONE,
        WORLD_TIMEOUT_TICKS);
    waitForWorldTicks(context, 60);
    waitForMeshSections(context, endMeshRevisionsBefore,
        4, -1, 0, -1, 0);
    int endFrame = currentFrameCount(context);
    waitForFrameAdvance(context, endFrame, FRAME_ADVANCE_PER_GATE);
    takeVerifiedScreenshot(context,
        "metalrender-26.2-end-dimension");

    ClientLevel endLevel =
        context.computeOnClient(client -> client.level);
    singleplayer.getServer().runCommand(
        "execute as @a in minecraft:overworld "
            + "run tp @s 0 104 -14 0 12");
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return client.level != null
          && client.level != endLevel
          && client.level.dimension() == Level.OVERWORLD
          && client.level.getBlockEntity(CHEST_POS)
              instanceof ChestBlockEntity
          && renderer != null
          && renderer.isWorldLoaded()
          && renderer.areTexturesReady()
          && renderer.getFrameCount() >= MIN_METAL_FRAMES;
    }, WORLD_TIMEOUT_TICKS);

    context.runOnClient(client -> {
      require(client.level != endLevel,
          "dimension return reused the detached End ClientLevel");
      require(client.level.getBlockEntity(CHEST_POS)
              instanceof ChestBlockEntity,
          "Overworld render scene did not survive Nether/End round-trip");
    });
    takeVerifiedScreenshot(context,
        "metalrender-26.2-overworld-after-dimensions");
  }

  private static void prepareEndScene(
      ClientGameTestContext context,
      TestSingleplayerContext singleplayer) {
    // The dragon is spawned asynchronously after the first End ticks. Wait
    // before removing all non-player entities, then clear the generated exit
    // portal so Metal and vanilla render the same deterministic fixture.
    waitForWorldTicks(context, 40);
    singleplayer.getServer().runCommand(
        "execute in minecraft:the_end "
            + "run kill @e[type=!minecraft:player]");
    waitForWorldTicks(context, 20);
    singleplayer.getServer().runCommand(
        "execute in minecraft:the_end "
            + "run fill -12 74 -12 12 90 12 minecraft:air");
    singleplayer.getServer().runCommand(
        "execute in minecraft:the_end "
            + "run fill -12 74 -12 12 74 12 minecraft:end_stone");
    singleplayer.getServer().runCommand(
        "execute as @a in minecraft:the_end "
            + "run tp @s 0 80 -8 0 20");
  }

  private static void waitForWorldTicks(
      ClientGameTestContext context, long ticks) {
    long start = context.computeOnClient(
        client -> client.level.getGameTime());
    context.waitFor(client -> client.level != null
        && client.level.getGameTime() >= start + ticks,
        WORLD_TIMEOUT_TICKS);
  }

  private static int bulkRecoveryCount(
      ClientGameTestContext context) {
    return context.computeOnClient(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return renderer != null
          ? renderer.getBulkUpdateRecoveryCount()
          : 0;
    });
  }

  private static void waitForBulkUpdateRecovery(
      ClientGameTestContext context, int recoveryCountBefore,
      boolean requireNewRecovery) {
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      if (renderer == null
          || renderer.isPresentationSuppressedForRecovery()) {
        return false;
      }
      return !requireNewRecovery
          || renderer.getBulkUpdateRecoveryCount() > recoveryCountBefore;
    }, WORLD_TIMEOUT_TICKS);
  }

  private static long[] captureMeshSectionRevisions(
      ClientGameTestContext context,
      int sectionY, int minSectionX, int maxSectionX,
      int minSectionZ, int maxSectionZ) {
    return context.computeOnClient(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      require(renderer != null,
          "Metal renderer is unavailable while capturing mesh revisions");
      var mesher = renderer.getChunkMesher();
      int count = (maxSectionX - minSectionX + 1)
          * (maxSectionZ - minSectionZ + 1);
      long[] revisions = new long[count * 2];
      int index = 0;
      for (int sectionX = minSectionX;
           sectionX <= maxSectionX; sectionX++) {
        for (int sectionZ = minSectionZ;
             sectionZ <= maxSectionZ; sectionZ++) {
          revisions[index++] = mesher.getBuildEpoch();
          revisions[index++] = mesher.getRequestedMeshRevision(
              sectionX, sectionY, sectionZ);
        }
      }
      return revisions;
    });
  }

  private static void waitForMeshSections(
      ClientGameTestContext context, long[] revisionsBefore,
      int sectionY, int minSectionX, int maxSectionX,
      int minSectionZ, int maxSectionZ) {
    int expectedRevisionCount =
        (maxSectionX - minSectionX + 1)
            * (maxSectionZ - minSectionZ + 1) * 2;
    require(revisionsBefore != null
            && revisionsBefore.length == expectedRevisionCount,
        "mesh revision baseline does not match the requested section range");
    long baselineEpoch = revisionsBefore[0];
    for (int index = 2; index < revisionsBefore.length; index += 2) {
      require(revisionsBefore[index] == baselineEpoch,
          "mesh revision baseline spans multiple build epochs");
    }
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      if (renderer == null
          || renderer.isPresentationSuppressedForRecovery()) {
        return false;
      }
      var mesher = renderer.getChunkMesher();
      int revisionIndex = 0;
      for (int sectionX = minSectionX;
           sectionX <= maxSectionX; sectionX++) {
        for (int sectionZ = minSectionZ;
             sectionZ <= maxSectionZ; sectionZ++) {
          if (!mesher.isMeshRevisionCompleteAfter(
                  sectionX, sectionY, sectionZ,
                  revisionsBefore[revisionIndex++],
                  revisionsBefore[revisionIndex++])) {
            return false;
          }
        }
      }
      return true;
    }, WORLD_TIMEOUT_TICKS);
    context.runOnClient(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      require(renderer != null,
          "Metal renderer is unavailable after mesh rebuild");
      long currentEpoch = renderer.getChunkMesher().getBuildEpoch();
      require(currentEpoch >= baselineEpoch
              && currentEpoch <= baselineEpoch + 1,
          "mesh recovery repeated full clears while rebuilding sections");
    });
    int completedFrame = currentFrameCount(context);
    waitForFrameAdvance(context, completedFrame, FRAME_ADVANCE_PER_GATE);
  }

  private static long currentBuildEpoch(
      ClientGameTestContext context) {
    return context.computeOnClient(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      require(renderer != null,
          "Metal renderer is unavailable while capturing build epoch");
      return renderer.getChunkMesher().getBuildEpoch();
    });
  }

  private static void requireSingleCleanRebuild(
      ClientGameTestContext context, long epochBefore,
      String fixtureName) {
    long epochAfter = currentBuildEpoch(context);
    require(epochAfter == epochBefore + 1,
        fixtureName + " did not perform exactly one clean mesh rebuild");
  }

  private static void verifyWorldUnload(
      ClientGameTestContext context) {
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return client.level == null
          && renderer != null
          && !renderer.isWorldLoaded()
          && !renderer.areTexturesReady()
          && renderer.getLoadingModePendingCount() == 0
          && renderer.getLoadingModeMeshCount() == 0
          && renderer.getFrameCount() == 0;
    }, WORLD_TIMEOUT_TICKS);
  }

  private static boolean hasEntity(ClientLevel level,
      EntityType<?> type) {
    return findEntity(level, type) != null;
  }

  private static Entity findEntity(ClientLevel level,
      EntityType<?> type) {
    if (level == null) {
      return null;
    }
    for (Entity entity : level.entitiesForRendering()) {
      if (entity.getType() == type) {
        return entity;
      }
    }
    return null;
  }

  private static void requireEntityRenderer(
      net.minecraft.client.Minecraft client, ClientLevel level,
      EntityType<?> type, String name) {
    Entity entity = findEntity(level, type);
    require(entity != null,
        name + " is missing from client render entities");
    require(client.getEntityRenderDispatcher().getRenderer(entity) != null,
        name + " has no client entity renderer");
    require(client.getEntityRenderDispatcher().extractEntity(entity, 1.0F)
            != null,
        name + " renderer did not extract a render state");
  }

  private static int currentFrameCount(
      ClientGameTestContext context) {
    return context.computeOnClient(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      require(renderer != null,
          "Metal renderer is unavailable while reading frame count");
      return renderer.getFrameCount();
    });
  }

  private static void waitForFrameAdvance(
      ClientGameTestContext context, int frameBefore, int amount) {
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return renderer != null
          && renderer.getFrameCount() >= frameBefore + amount;
    }, WORLD_TIMEOUT_TICKS);
  }

  private static int particleTotal(String count) {
    int marker = count.lastIndexOf('T');
    require(marker >= 0,
        "unrecognized particle-count format: " + count);
    String total = count.substring(marker + 1).trim();
    try {
      return Integer.parseInt(total);
    } catch (NumberFormatException error) {
      throw new AssertionError(
          "unrecognized particle-count format: " + count, error);
    }
  }

  private static Path takeVerifiedScreenshot(
      ClientGameTestContext context, String name) {
    long[] before = context.computeOnClient(client -> new long[] {
        MetalRenderHookState.screenshotCaptureCount(),
        MetalRenderHookState.successfulPresentationCount()
    });
    Path screenshot = context.takeScreenshot(name);
    long[] after = context.computeOnClient(client -> new long[] {
        MetalRenderHookState.screenshotCaptureCount(),
        MetalRenderHookState.lastScreenshotUsedMetal() ? 1L : 0L,
        MetalRenderHookState.lastScreenshotPresentationCount(),
        MetalRenderHookState.successfulPresentationCount()
    });
    require(after[0] == before[0] + 1,
        "screenshot did not produce exactly one tracked readback: "
            + name);
    if (BASELINE) {
      require(after[1] == 0 && after[2] == 0,
          "vanilla baseline unexpectedly captured a Metal frame: "
              + name);
    } else {
      require(after[1] == 1,
          "active screenshot did not capture a Metal-composited frame: "
              + name);
      require(after[2] > before[1] && after[3] > before[1],
          "active screenshot was not tied to a new successful Metal "
              + "presentation: " + name);
    }
    require(Files.isRegularFile(screenshot),
        "client game-test screenshot was not written: " + screenshot);
    require(fileSize(screenshot) > 0,
        "client game-test screenshot is empty: " + screenshot);
    verifyScreenshotPixels(screenshot);
    return screenshot;
  }

  private static void verifyScreenshotPixels(Path path) {
    try {
      BufferedImage image = ImageIO.read(path.toFile());
      require(image != null,
          "screenshot is not a readable image: " + path);
      require(image.getWidth() >= 64 && image.getHeight() >= 64,
          "screenshot dimensions are unexpectedly small: "
              + image.getWidth() + "x" + image.getHeight());

      Set<Integer> sampledColors = new HashSet<>();
      int xStep = Math.max(1, image.getWidth() / 64);
      int yStep = Math.max(1, image.getHeight() / 64);
      for (int y = 0; y < image.getHeight(); y += yStep) {
        for (int x = 0; x < image.getWidth(); x += xStep) {
          sampledColors.add(image.getRGB(x, y));
        }
      }
      require(sampledColors.size() >= 8,
          "screenshot is visually blank or uniform (sampled colors="
              + sampledColors.size() + "): " + path);
      if (path.getFileName().toString().contains("fluids-translucency")) {
        verifyLavaFixtureCoverage(image, path);
      }
    } catch (AssertionError error) {
      throw error;
    } catch (Exception error) {
      throw new AssertionError(
          "could not decode screenshot " + path + ": "
              + error.getMessage(), error);
    }
  }

  private static void verifyLavaFixtureCoverage(
      BufferedImage image, Path path) {
    int minX = Math.round(image.getWidth() * 0.28F);
    int maxX = Math.round(image.getWidth() * 0.46F);
    int minY = Math.round(image.getHeight() * 0.40F);
    int maxY = Math.round(image.getHeight() * 0.68F);
    int warmPixels = 0;
    int roiPixels = Math.max(1, (maxX - minX) * (maxY - minY));
    for (int y = minY; y < maxY; y++) {
      for (int x = minX; x < maxX; x++) {
        int color = image.getRGB(x, y);
        int red = (color >>> 16) & 0xFF;
        int green = (color >>> 8) & 0xFF;
        int blue = color & 0xFF;
        if (red > 50
            && red * 4 > green * 5
            && red * 7 > blue * 10) {
          warmPixels++;
        }
      }
    }
    require(warmPixels * 100 >= roiPixels * 9,
        "lava fixture collapsed or disappeared (warm pixels="
            + warmPixels + ", roi pixels=" + roiPixels + "): " + path);
  }

  private static long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (Exception error) {
      throw new AssertionError(
          "could not inspect screenshot " + path + ": "
              + error.getMessage(), error);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}

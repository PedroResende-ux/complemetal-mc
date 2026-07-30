package com.pebbles_boon.metalrender.gametest;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import java.awt.image.BufferedImage;
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
import net.minecraft.world.level.material.Fluids;

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
  private static final Identifier STONE_TEXTURE =
      Identifier.withDefaultNamespace("textures/block/stone.png");
  private static final boolean BASELINE =
      Boolean.getBoolean("metalrender.gametest.baseline");

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
    require(NativeBridge.nSupportsMetal4(),
        "Metal 4 is not supported on the test device");
    require(NativeBridge.nIsMetal4Active(),
        "Metal 4 runtime objects are not active");
    require(!NativeBridge.nIsMetal4DrawPathActive(),
        "unvalidated native MTL4 draw encoding must stay disabled");
    require("METAL4_RUNTIME_VERIFIED_METAL3_RENDER".equals(
            NativeBridge.nGetBackendMode()),
        "unexpected backend mode: " + NativeBridge.nGetBackendMode());

    MetalRenderConfig config = MetalRenderClient.getConfig();
    require(config != null, "MetalRender config was not loaded");
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

      buildEntityScene(singleplayer);
      verifyEntitiesAndBlockEntity(context);
      takeVerifiedScreenshot(context,
          "metalrender-26.2-entities-block-entities");

      buildFluidScene(singleplayer);
      verifyFluidsAndTranslucency(context);
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
      singleplayer.getServer().runCommand("time set noon");
      singleplayer.getServer().runCommand("weather clear");
      context.waitFor(client -> client.level != null
          && client.player != null
          && client.levelRenderer != null, WORLD_TIMEOUT_TICKS);
      long gameTimeBefore =
          context.computeOnClient(client -> client.level.getGameTime());
      context.waitFor(client ->
              client.level.getGameTime() >= gameTimeBefore + 20,
          WORLD_TIMEOUT_TICKS);

      takeVerifiedScreenshot(context,
          "metalrender-26.2-vanilla-baseline");
    }
  }

  private static void configureDeterministicWorld(
      TestSingleplayerContext singleplayer) {
    singleplayer.getServer().runCommand("difficulty peaceful");
    singleplayer.getServer().runCommand("gamerule doDaylightCycle false");
    singleplayer.getServer().runCommand("gamerule doWeatherCycle false");
    singleplayer.getServer().runCommand("gamerule doMobSpawning false");
    singleplayer.getServer().runCommand("gamerule doFireTick false");
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
        "summon minecraft:minecart 0 99 8");
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
      require(fluidModels.get(lava).layer().translucent(),
          "lava model is not assigned to a translucent render layer");
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
    int frameBefore = currentFrameCount(context);
    CompletableFuture<Void> reload = context.computeOnClient(
        client -> client.reloadResourcePacks());
    context.waitFor(client -> reload.isDone(), RELOAD_TIMEOUT_TICKS);
    require(!reload.isCancelled(),
        "client resource reload was cancelled");
    require(!reload.isCompletedExceptionally(),
        "client resource reload completed exceptionally");
    reload.join();

    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return client.level != null
          && client.getResourceManager().getResource(STONE_TEXTURE)
              .isPresent()
          && client.getModelManager().getFluidStateModelSet()
              != fluidModelsBefore
          && renderer != null
          && renderer.isWorldLoaded()
          && renderer.areTexturesReady()
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
    int netherFrame = currentFrameCount(context);
    waitForFrameAdvance(context, netherFrame, FRAME_ADVANCE_PER_GATE);
    takeVerifiedScreenshot(context,
        "metalrender-26.2-nether-dimension");

    ClientLevel netherLevel =
        context.computeOnClient(client -> client.level);
    singleplayer.getServer().runCommand(
        "execute as @a in minecraft:overworld "
            + "run tp @s 0 104 -14 0 12");
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return client.level != null
          && client.level != netherLevel
          && client.level.dimension() == Level.OVERWORLD
          && client.level.getBlockEntity(CHEST_POS)
              instanceof ChestBlockEntity
          && renderer != null
          && renderer.isWorldLoaded()
          && renderer.areTexturesReady()
          && renderer.getFrameCount() >= MIN_METAL_FRAMES;
    }, WORLD_TIMEOUT_TICKS);

    context.runOnClient(client -> {
      require(client.level != netherLevel,
          "dimension return reused the detached Nether ClientLevel");
      require(client.level.getBlockEntity(CHEST_POS)
              instanceof ChestBlockEntity,
          "Overworld render scene did not survive dimension round-trip");
    });
    takeVerifiedScreenshot(context,
        "metalrender-26.2-overworld-after-nether");
  }

  private static void verifyWorldUnload(
      ClientGameTestContext context) {
    context.waitFor(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      return client.level == null
          && renderer != null
          && !renderer.isWorldLoaded()
          && !renderer.areTexturesReady()
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
    Path screenshot = context.takeScreenshot(name);
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
    } catch (AssertionError error) {
      throw error;
    } catch (Exception error) {
      throw new AssertionError(
          "could not decode screenshot " + path + ": "
              + error.getMessage(), error);
    }
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

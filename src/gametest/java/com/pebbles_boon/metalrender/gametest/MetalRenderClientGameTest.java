package com.pebbles_boon.metalrender.gametest;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * End-to-end macOS smoke test for the safe Metal 4 hybrid path.
 */
@SuppressWarnings("UnstableApiUsage")
public final class MetalRenderClientGameTest implements FabricClientGameTest {
  private static final int STARTUP_TIMEOUT_TICKS = 1_200;
  private static final int WORLD_TIMEOUT_TICKS = 2_400;
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
    require("METAL4_HYBRID_METAL3_RENDER".equals(
            NativeBridge.nGetBackendMode()),
        "unexpected backend mode: " + NativeBridge.nGetBackendMode());

    MetalRenderConfig config = MetalRenderClient.getConfig();
    require(config != null, "MetalRender config was not loaded");
    require(!config.enableFastTerrainReplacement,
        "fast terrain replacement must be off in safe defaults");
    require(!config.enableExperimentalFeatureReplacement,
        "experimental entity/particle replacement must be off in safe defaults");

    try (TestSingleplayerContext singleplayer =
             context.worldBuilder().setUseConsistentSettings(true).create()) {
      singleplayer.getServer().runCommand("time set noon");
      singleplayer.getServer().runCommand("weather clear");

      context.waitFor(client -> {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        return client.level != null
            && client.player != null
            && renderer != null
            && renderer.isWorldLoaded()
            && renderer.areTexturesReady()
            && renderer.getFrameCount() >= 30;
      }, WORLD_TIMEOUT_TICKS);

      context.waitTicks(60);
      context.runOnClient(client -> {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        require(client.levelRenderer != null,
            "vanilla level renderer is unavailable");
        require(renderer != null && renderer.isWorldLoaded(),
            "Metal world renderer did not enter the loaded state");
        require(renderer.areTexturesReady(),
            "Metal texture handoff did not become ready");
        require(renderer.getFrameCount() >= 30,
            "Metal frame lifecycle did not advance");
      });

      Path screenshot =
          context.takeScreenshot("metalrender-26.2-metal4-hybrid-world");
      require(Files.isRegularFile(screenshot),
          "client game-test screenshot was not written: " + screenshot);
      require(fileSize(screenshot) > 0,
          "client game-test screenshot is empty: " + screenshot);
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
      context.waitTicks(120);

      Path screenshot =
          context.takeScreenshot("metalrender-26.2-vanilla-baseline");
      require(Files.isRegularFile(screenshot),
          "baseline screenshot was not written: " + screenshot);
      require(fileSize(screenshot) > 0,
          "baseline screenshot is empty: " + screenshot);
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

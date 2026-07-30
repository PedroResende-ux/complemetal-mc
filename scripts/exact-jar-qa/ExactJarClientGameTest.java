package com.pebbles_boon.metalrender.exactjarqa;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;

/**
 * Black-box release smoke test. This class is compiled into a standalone
 * QA-driver mod and never placed on Minecraft's classpath as source-set output.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ExactJarClientGameTest implements FabricClientGameTest {
  private static final int STARTUP_TIMEOUT_TICKS = 1_800;
  private static final int WORLD_TIMEOUT_TICKS = 3_600;
  private static final int SHADER_TIMEOUT_TICKS = 3_600;
  private static final long EXPECTED_COMPLEMENTARY_PROGRAMS = 76;

  @Override
  public void runTest(ClientGameTestContext context) {
    FabricLoader loader = FabricLoader.getInstance();
    require(!loader.isDevelopmentEnvironment(),
        "exact-JAR QA must run in a production Fabric environment");
    require(loader.isModLoaded("metalrender"), "MetalRender is not loaded");
    require(loader.isModLoaded("fabric-api"), "Fabric API is not loaded");
    require(loader.isModLoaded("sodium"), "Sodium is not loaded");
    require(loader.isModLoaded("iris"), "Iris is not loaded");

    Path exactJar = exactReleaseJar();
    String expectedSha = requiredProperty("metalrender.exactJar.expectedSha256");
    String cacheExpectation =
        requiredProperty("metalrender.exactJar.cacheExpectation");
    require(cacheExpectation.equals("cold") || cacheExpectation.equals("warm"),
        "invalid cache expectation: " + cacheExpectation);
    require(expectedSha.equals(sha256(exactJar)),
        "loaded release JAR SHA-256 differs from the prepared artifact");
    require(System.getProperty("java.version", "").startsWith("25."),
        "exact-JAR QA requires Java 25, got " + System.getProperty("java.version"));

    context.waitFor(client -> {
      MetalRenderClient.InitState state = MetalRenderClient.getInitState();
      return state == MetalRenderClient.InitState.READY
          || state == MetalRenderClient.InitState.UNSUPPORTED
          || state == MetalRenderClient.InitState.FAILED;
    }, STARTUP_TIMEOUT_TICKS);

    require(MetalRenderClient.getInitState() == MetalRenderClient.InitState.READY,
        "Metal renderer did not initialize: " + MetalRenderClient.getInitFailure());
    require(MetalRenderClient.isEnabled(),
        "Metal renderer is not enabled after initialization");
    require(NativeBridge.isLibLoaded(),
        "native bridge was not loaded from the release JAR");
    String expectedNativeCacheVersion = loader.getModContainer("metalrender")
        .orElseThrow().getMetadata().getVersion().getFriendlyString()
        .replaceAll("[^A-Za-z0-9._-]", "_");
    Path loadedNative = Path.of(NativeBridge.getLoadedPath())
        .toAbsolutePath().normalize();
    require(loadedNative.getParent() != null
            && loadedNative.getParent().getParent() != null
            && expectedNativeCacheVersion.equals(
                loadedNative.getParent().getParent()
                    .getFileName().toString()),
        "native payload cache is not versioned as "
            + expectedNativeCacheVersion + ": " + loadedNative);
    require(NativeBridge.nSupportsMetal4(),
        "Metal 4 is not supported on the QA device");
    require(NativeBridge.nIsMetal4Active(),
        "Metal 4 queue/allocator runtime is not active");
    require(!NativeBridge.nIsMetal4DrawPathActive(),
        "unvalidated native MTL4 draw encoding must remain disabled");
    require("METAL4_RUNTIME_VERIFIED_METAL3_RENDER".equals(
        NativeBridge.nGetBackendMode()),
        "unexpected backend mode: " + NativeBridge.nGetBackendMode());

    MetalRenderConfig config = MetalRenderClient.getConfig();
    require(config != null, "MetalRender config was not loaded");
    require(!config.enableFastTerrainReplacement,
        "fast terrain replacement must be off in release-safe defaults");
    require(!config.enableExperimentalFeatureReplacement,
        "entity/particle replacement must be release-locked off");

    IrisApi iris = IrisApi.getInstance();
    require(iris.getConfig().areShadersEnabled(),
        "Iris shaders were not enabled by the isolated QA profile");

    Path shadersOn;
    Path shadersOff;
    Path shadersReenabled;
    long frames;
    long framesWithShadersOff;
    long metalPresentationsWithShadersOff;
    try (TestSingleplayerContext singleplayer =
             context.worldBuilder().setUseConsistentSettings(true).create()) {
      singleplayer.getServer().runCommand("time set noon");
      singleplayer.getServer().runCommand("weather clear");

      context.waitFor(client -> client.level != null
          && client.player != null
          && client.levelRenderer != null, WORLD_TIMEOUT_TICKS);
      context.waitFor(client -> IrisApi.getInstance().isShaderPackInUse(),
          SHADER_TIMEOUT_TICKS);
      String expectedShaderPack =
          requiredProperty("metalrender.exactJar.shaderPack");
      require(expectedShaderPack.equals(Iris.getCurrentPackName()),
          "Iris loaded " + Iris.getCurrentPackName()
              + " instead of " + expectedShaderPack);
      context.waitFor(client -> irisOwnsRenderGraph(), WORLD_TIMEOUT_TICKS);
      RendererSnapshot initialIrisStart = snapshot(context);
      context.waitTicks(120);
      long initialScreenshotCapture = screenshotCaptureCount(context);
      shadersOn = context.takeScreenshot(
          "metalrender-exact-jar-" + cacheExpectation + "-shaders-on");
      requireScreenshot(shadersOn);
      requireScreenshotFrame(context, initialScreenshotCapture, false,
          "initial Iris screenshot");
      RendererSnapshot initialIrisEnd = snapshot(context);
      requirePausedSnapshot(initialIrisStart, initialIrisEnd,
          "initial Iris shader render");

      context.runOnClient(client ->
          IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false));
      context.waitFor(client -> !IrisApi.getInstance().isShaderPackInUse(),
          SHADER_TIMEOUT_TICKS);
      RendererSnapshot metalStart = snapshot(context);
      context.waitFor(client -> {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        return renderer != null
            && renderer.isWorldLoaded()
            && renderer.areTexturesReady()
            && renderer.getLastDrawnChunkCount() > 0
            && MetalRenderHookState.successfulPresentationCount()
                >= metalStart.presentations() + 30;
      }, WORLD_TIMEOUT_TICKS);
      context.waitTicks(120);
      RendererSnapshot beforeMetalScreenshot = snapshot(context);
      require(beforeMetalScreenshot.lastDrawnChunks() > 0,
          "Metal presentation contained no drawn chunks before screenshot");
      long metalScreenshotCapture = screenshotCaptureCount(context);
      shadersOff = context.takeScreenshot(
          "metalrender-exact-jar-" + cacheExpectation + "-shaders-off");
      requireScreenshot(shadersOff);
      requireScreenshotFrame(context, metalScreenshotCapture, true,
          "shaders-off screenshot");
      RendererSnapshot afterMetalScreenshot = snapshot(context);
      require(afterMetalScreenshot.presentations()
              > beforeMetalScreenshot.presentations(),
          "shaders-off screenshot was not tied to a successful Metal "
              + "presentation");
      require(afterMetalScreenshot.lastDrawnChunks() > 0,
          "shaders-off screenshot completed with no Metal chunks drawn");
      framesWithShadersOff =
          afterMetalScreenshot.frameCount() - metalStart.frameCount();
      metalPresentationsWithShadersOff =
          afterMetalScreenshot.presentations() - metalStart.presentations();
      require(framesWithShadersOff >= 30
              && metalPresentationsWithShadersOff >= 30,
          "Metal frame/presentation telemetry did not advance while shaders "
              + "were off");

      context.runOnClient(client ->
          IrisApi.getInstance().getConfig().setShadersEnabledAndApply(true));
      context.waitFor(client -> IrisApi.getInstance().isShaderPackInUse(),
          SHADER_TIMEOUT_TICKS);
      require(expectedShaderPack.equals(Iris.getCurrentPackName()),
          "Iris reenabled an unexpected shader pack: "
              + Iris.getCurrentPackName());
      context.waitFor(client -> irisOwnsRenderGraph(), WORLD_TIMEOUT_TICKS);
      RendererSnapshot reenabledIrisStart = snapshot(context);
      context.waitTicks(120);
      long reenabledScreenshotCapture = screenshotCaptureCount(context);
      shadersReenabled =
          context.takeScreenshot("metalrender-exact-jar-" + cacheExpectation
              + "-shaders-reenabled");
      requireScreenshot(shadersReenabled);
      requireScreenshotFrame(context, reenabledScreenshotCapture, false,
          "re-enabled Iris screenshot");
      RendererSnapshot reenabledIrisEnd = snapshot(context);
      requirePausedSnapshot(reenabledIrisStart, reenabledIrisEnd,
          "re-enabled Iris shader render");
      frames = reenabledIrisEnd.frameCount();

      VisualMetrics visualMetrics =
          analyzeScreenshots(shadersOn, shadersOff, shadersReenabled);
      context.waitFor(client -> {
        IrisTranslationCoordinator.Status status =
            IrisTranslationCoordinator.status();
        return status.running() && status.queued() == 0
            && status.attempted() > 0
            && (cacheExpectation.equals("cold")
                ? status.translated() > 0
                : status.cacheHits() > 0);
      }, SHADER_TIMEOUT_TICKS);
      IrisTranslationCoordinator.Status translationStatus =
          context.computeOnClient(
              client -> IrisTranslationCoordinator.status());
      require(translationStatus.running(),
          "Iris final-GLSL translation worker is not running");
      require(translationStatus.attempted() > 0
              && translationStatus.translated()
                  + translationStatus.cacheHits() > 0,
          "no final Iris GLSL program reached the SPIR-V/MSL cache");
      require(translationStatus.attempted()
              == EXPECTED_COMPLEMENTARY_PROGRAMS,
          "unexpected final Iris GLSL program count for the exact "
              + "Complementary workload: expected "
              + EXPECTED_COMPLEMENTARY_PROGRAMS + ", got "
              + translationStatus.attempted());
      require(translationStatus.failed() == 0,
          "Iris GLSL translation reported failures: "
              + translationStatus.failed());
      require(translationStatus.rejected() == 0,
          "Iris GLSL capture rejected programs: "
              + translationStatus.rejected());
      require(translationStatus.captureFailures() == 0,
          "Iris GLSL capture reported failures: "
              + translationStatus.captureFailures());
      require(translationStatus.queued() == 0,
          "Iris GLSL translation queue did not drain");
      require(translationStatus.attempted()
              == translationStatus.translated()
                  + translationStatus.cacheHits(),
          "Iris GLSL translation counters do not account for every attempt");
      require(!cacheExpectation.equals("cold")
              || (translationStatus.translated()
                      == translationStatus.attempted()
                  && translationStatus.cacheHits() == 0),
          "cold-cache run did not translate every captured program");
      require(!cacheExpectation.equals("warm")
              || (translationStatus.cacheHits()
                      == translationStatus.attempted()
                  && translationStatus.translated() == 0),
          "warm-cache run did not reuse every captured program");
      writeEvidence(exactJar, expectedSha, frames, framesWithShadersOff,
          metalPresentationsWithShadersOff, shadersOn, shadersOff,
          shadersReenabled, visualMetrics, translationStatus,
          cacheExpectation);
    }
  }

  private static boolean irisOwnsRenderGraph() {
    MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
    if (renderer == null || !renderer.isWorldLoaded()
        || !renderer.isIrisCompatibilityPaused()
        || renderer.areTexturesReady()
        || renderer.getChunkMesher().getMeshCount() != 0) {
      return false;
    }
    var backend = MetalRenderClient.getRenderer();
    return backend != null && backend.getHandle() != 0
        && !NativeBridge.nIsFrameReady(backend.getHandle());
  }

  private static RendererSnapshot snapshot(ClientGameTestContext context) {
    return context.computeOnClient(client -> {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      require(renderer != null && renderer.isWorldLoaded(),
          "Metal world renderer is unavailable");
      var backend = MetalRenderClient.getRenderer();
      boolean nativeFrameReady = backend != null && backend.getHandle() != 0
          && NativeBridge.nIsFrameReady(backend.getHandle());
      return new RendererSnapshot(renderer.getFrameCount(),
          MetalRenderHookState.successfulPresentationCount(),
          renderer.getLastDrawnChunkCount(),
          renderer.getChunkMesher().getMeshCount(),
          renderer.isIrisCompatibilityPaused(), renderer.areTexturesReady(),
          nativeFrameReady);
    });
  }

  private static long screenshotCaptureCount(ClientGameTestContext context) {
    return context.computeOnClient(
        client -> MetalRenderHookState.screenshotCaptureCount());
  }

  private static void requireScreenshotFrame(ClientGameTestContext context,
      long beforeCaptureCount, boolean expectedMetal, String stage) {
    ScreenshotSnapshot screenshot = context.computeOnClient(client ->
        new ScreenshotSnapshot(MetalRenderHookState.screenshotCaptureCount(),
            MetalRenderHookState.lastScreenshotUsedMetal(),
            MetalRenderHookState.lastScreenshotPresentationCount()));
    require(screenshot.captureCount() == beforeCaptureCount + 1,
        stage + " did not produce exactly one tracked framebuffer readback");
    require(screenshot.usedMetal() == expectedMetal,
        stage + (expectedMetal
            ? " did not capture the Metal-composited frame"
            : " unexpectedly captured a Metal-composited frame"));
    require(!expectedMetal || screenshot.presentationCount() > 0,
        stage + " did not identify its successful Metal presentation");
  }

  private static void requirePausedSnapshot(RendererSnapshot before,
      RendererSnapshot after, String stage) {
    require(before.irisPaused() && after.irisPaused(),
        stage + " did not keep the Iris compatibility pause active");
    require(!before.texturesReady() && !after.texturesReady(),
        stage + " retained duplicate Metal texture mirrors");
    require(before.meshCount() == 0 && after.meshCount() == 0,
        stage + " retained duplicate Metal chunk meshes");
    require(!before.nativeFrameReady() && !after.nativeFrameReady(),
        stage + " left a Metal frame ready for presentation");
    require(before.frameCount() == after.frameCount(),
        stage + " continued Metal frame encoding");
    require(before.presentations() == after.presentations(),
        stage + " continued Metal presentation");
  }

  private record RendererSnapshot(int frameCount, long presentations,
                                  int lastDrawnChunks, int meshCount,
                                  boolean irisPaused,
                                  boolean texturesReady,
                                  boolean nativeFrameReady) {
  }

  private record ScreenshotSnapshot(long captureCount, boolean usedMetal,
                                    long presentationCount) {
  }

  private static Path exactReleaseJar() {
    try {
      URI codeSource = MetalRenderClient.class.getProtectionDomain()
          .getCodeSource().getLocation().toURI();
      Path actual = Path.of(codeSource).toRealPath();
      Path expected = Path.of(requiredProperty(
          "metalrender.exactJar.expectedPath")).toRealPath();
      require(actual.equals(expected),
          "MetalRender loaded from " + actual + " instead of " + expected);
      require(actual.getFileName().toString().endsWith(".jar"),
          "MetalRender code source is not a JAR: " + actual);
      return actual;
    } catch (Exception error) {
      throw new AssertionError(
          "could not verify exact release JAR code source", error);
    }
  }

  private static void writeEvidence(Path exactJar, String exactSha,
      long frames, long framesWithShadersOff,
      long metalPresentationsWithShadersOff, Path shadersOn, Path shadersOff,
      Path shadersReenabled, VisualMetrics visualMetrics,
      IrisTranslationCoordinator.Status translationStatus,
      String cacheExpectation) {
    Path result = Path.of(requiredProperty(
        "metalrender.exactJar.driverEvidencePath")).toAbsolutePath();
    Path temporary = result.resolveSibling(result.getFileName() + ".tmp");
    String shaderPack =
        requiredProperty("metalrender.exactJar.shaderPack");
    String version = FabricLoader.getInstance()
        .getModContainer("metalrender").orElseThrow()
        .getMetadata().getVersion().getFriendlyString();
    String json = String.format(Locale.ROOT, """
        {
          "status": "PASS",
          "environment": "production-fabric",
          "javaMajor": 25,
          "metalrenderVersion": %s,
          "exactJarPath": %s,
          "exactJarSha256": %s,
          "cacheExpectation": %s,
          "backendMode": %s,
          "metal4Active": true,
          "metal4DrawPathActive": false,
          "sodiumLoaded": true,
          "irisLoaded": true,
          "shaderPack": %s,
          "shaderPackInitiallyLoaded": true,
          "shaderPackDisabledAndApplied": true,
          "shaderPackReenabledAndApplied": true,
          "frameCount": %d,
          "metalFramesWhileShaderPackDisabled": %d,
          "successfulPresentationsWhileShaderPackDisabled": %d,
          "irisTranslation": {
            "running": true,
            "attempted": %d,
            "translated": %d,
            "cacheHits": %d,
            "failed": %d,
            "rejected": %d,
            "captureFailures": %d,
            "queuedAtEvidence": %d,
            "pipelineStatus": "pending"
          },
          "visualChecks": {
            "width": %d,
            "height": %d,
            "minimumSampledUniqueColors": %d,
            "minimumLuminanceStdDev": %.6f,
            "shadersOnVsOffMad": %.6f,
            "shadersOffVsReenabledMad": %.6f,
            "shadersOnVsReenabledMad": %.6f
          },
          "screenshots": {
            "shadersOn": %s,
            "shadersOff": %s,
            "shadersReenabled": %s
          }
        }
        """,
        quote(version),
        quote(exactJar.toString()),
        quote(exactSha),
        quote(cacheExpectation),
        quote(NativeBridge.nGetBackendMode()),
        quote(shaderPack),
        frames,
        framesWithShadersOff,
        metalPresentationsWithShadersOff,
        translationStatus.attempted(),
        translationStatus.translated(),
        translationStatus.cacheHits(),
        translationStatus.failed(),
        translationStatus.rejected(),
        translationStatus.captureFailures(),
        translationStatus.queued(),
        visualMetrics.width(),
        visualMetrics.height(),
        visualMetrics.minimumUniqueColors(),
        visualMetrics.minimumLuminanceStdDev(),
        visualMetrics.onVsOffMad(),
        visualMetrics.offVsReenabledMad(),
        visualMetrics.onVsReenabledMad(),
        quote(shadersOn.toAbsolutePath().toString()),
        quote(shadersOff.toAbsolutePath().toString()),
        quote(shadersReenabled.toAbsolutePath().toString()));
    try {
      Files.createDirectories(result.getParent());
      Files.writeString(temporary, json, StandardCharsets.UTF_8);
      Files.move(temporary, result, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception error) {
      throw new AssertionError("could not write exact-JAR evidence", error);
    }
  }

  private static void requireScreenshot(Path screenshot) {
    try {
      require(Files.isRegularFile(screenshot),
          "screenshot was not written: " + screenshot);
      require(Files.size(screenshot) > 0,
          "screenshot is empty: " + screenshot);
    } catch (Exception error) {
      throw new AssertionError(
          "could not verify screenshot " + screenshot, error);
    }
  }

  private static VisualMetrics analyzeScreenshots(Path shadersOn,
      Path shadersOff, Path shadersReenabled) {
    try {
      BufferedImage on = ImageIO.read(shadersOn.toFile());
      BufferedImage off = ImageIO.read(shadersOff.toFile());
      BufferedImage reenabled = ImageIO.read(shadersReenabled.toFile());
      require(on != null && off != null && reenabled != null,
          "one or more screenshots are not decodable PNG images");
      require(on.getWidth() == off.getWidth()
              && on.getWidth() == reenabled.getWidth()
              && on.getHeight() == off.getHeight()
              && on.getHeight() == reenabled.getHeight(),
          "shader-toggle screenshots have mismatched dimensions");
      require(on.getWidth() >= 640 && on.getHeight() >= 360,
          "shader-toggle screenshots are unexpectedly small");

      SampleStats onStats = sampleStats(on);
      SampleStats offStats = sampleStats(off);
      SampleStats reenabledStats = sampleStats(reenabled);
      int minimumUniqueColors = Math.min(onStats.uniqueColors(),
          Math.min(offStats.uniqueColors(), reenabledStats.uniqueColors()));
      double minimumLuminanceStdDev = Math.min(onStats.luminanceStdDev(),
          Math.min(offStats.luminanceStdDev(),
              reenabledStats.luminanceStdDev()));
      require(minimumUniqueColors >= 64,
          "shader-toggle screenshot is visually uniform or corrupted");
      require(minimumLuminanceStdDev >= 0.02,
          "shader-toggle screenshot has insufficient luminance variation");

      double onVsOff = meanAbsoluteDifference(on, off);
      double offVsReenabled = meanAbsoluteDifference(off, reenabled);
      double onVsReenabled = meanAbsoluteDifference(on, reenabled);
      double shaderTransition = Math.min(onVsOff, offVsReenabled);
      require(shaderTransition >= 0.015,
          "disabling Iris did not produce a material image change");
      require(onVsReenabled < shaderTransition,
          "re-enabled Iris output is not closer to the initial Iris output "
              + "than to the shaders-off image");
      return new VisualMetrics(on.getWidth(), on.getHeight(),
          minimumUniqueColors, minimumLuminanceStdDev, onVsOff,
          offVsReenabled, onVsReenabled);
    } catch (AssertionError error) {
      throw error;
    } catch (Exception error) {
      throw new AssertionError("could not compare shader-toggle screenshots",
          error);
    }
  }

  private static SampleStats sampleStats(BufferedImage image) {
    Set<Integer> colors = new HashSet<>();
    double luminanceSum = 0.0;
    double luminanceSquareSum = 0.0;
    int samples = 0;
    for (int gridY = 0; gridY < 36; gridY++) {
      int y = Math.min(image.getHeight() - 1,
          (gridY * image.getHeight() + image.getHeight() / 2) / 36);
      for (int gridX = 0; gridX < 64; gridX++) {
        int x = Math.min(image.getWidth() - 1,
            (gridX * image.getWidth() + image.getWidth() / 2) / 64);
        int rgb = image.getRGB(x, y) & 0x00ffffff;
        colors.add(rgb);
        double red = ((rgb >>> 16) & 0xff) / 255.0;
        double green = ((rgb >>> 8) & 0xff) / 255.0;
        double blue = (rgb & 0xff) / 255.0;
        double luminance = red * 0.2126 + green * 0.7152 + blue * 0.0722;
        luminanceSum += luminance;
        luminanceSquareSum += luminance * luminance;
        samples++;
      }
    }
    double mean = luminanceSum / samples;
    double variance = Math.max(0.0,
        luminanceSquareSum / samples - mean * mean);
    return new SampleStats(colors.size(), Math.sqrt(variance));
  }

  private static double meanAbsoluteDifference(BufferedImage left,
      BufferedImage right) {
    long absoluteDifference = 0;
    long channelSamples = 0;
    for (int gridY = 0; gridY < 36; gridY++) {
      int y = Math.min(left.getHeight() - 1,
          (gridY * left.getHeight() + left.getHeight() / 2) / 36);
      for (int gridX = 0; gridX < 64; gridX++) {
        int x = Math.min(left.getWidth() - 1,
            (gridX * left.getWidth() + left.getWidth() / 2) / 64);
        int leftRgb = left.getRGB(x, y);
        int rightRgb = right.getRGB(x, y);
        absoluteDifference += Math.abs(
            ((leftRgb >>> 16) & 0xff) - ((rightRgb >>> 16) & 0xff));
        absoluteDifference += Math.abs(
            ((leftRgb >>> 8) & 0xff) - ((rightRgb >>> 8) & 0xff));
        absoluteDifference += Math.abs(
            (leftRgb & 0xff) - (rightRgb & 0xff));
        channelSamples += 3;
      }
    }
    return absoluteDifference / (channelSamples * 255.0);
  }

  private record SampleStats(int uniqueColors, double luminanceStdDev) {
  }

  private record VisualMetrics(int width, int height,
                               int minimumUniqueColors,
                               double minimumLuminanceStdDev,
                               double onVsOffMad,
                               double offVsReenabledMad,
                               double onVsReenabledMad) {
  }

  private static String sha256(Path path) {
    try (InputStream input = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception error) {
      throw new AssertionError("could not hash " + path, error);
    }
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    require(value != null && !value.isBlank(),
        "missing required system property " + name);
    return value;
  }

  private static String quote(String value) {
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\"";
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}

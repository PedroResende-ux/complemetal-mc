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
  private static final long EXPECTED_COMPLEMENTARY_PROGRAMS = 231;
  private static final long EXPECTED_COMPLEMENTARY_STAGES = 462;

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
    String backendExpectation =
        requiredProperty("metalrender.exactJar.backend");
    require(cacheExpectation.equals("cold") || cacheExpectation.equals("warm"),
        "invalid cache expectation: " + cacheExpectation);
    require(Boolean.getBoolean(
            "metalrender.experimental.irisMetalLibraryValidation"),
        "exact-JAR QA requires Iris MSL library validation opt-in");
    require(backendExpectation.equals("metal4")
            || backendExpectation.equals("metal3"),
        "invalid backend expectation: " + backendExpectation);
    boolean expectMetal4 = backendExpectation.equals("metal4");
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
    require(!NativeBridge.nIsMetal4DrawPathActive(),
        "unvalidated native MTL4 draw encoding must remain disabled");
    if (expectMetal4) {
      require(NativeBridge.nSupportsMetal4(),
          "Metal 4 is not supported on the QA device");
      require(NativeBridge.nIsMetal4Active(),
          "Metal 4 queue/allocator runtime is not active");
      require("METAL4_RUNTIME_VERIFIED_METAL3_RENDER".equals(
          NativeBridge.nGetBackendMode()),
          "unexpected Metal 4 hybrid backend mode: "
              + NativeBridge.nGetBackendMode());
    } else {
      require(!NativeBridge.nIsMetal4Active(),
          "Metal 4 runtime remained active in the Metal 3 QA profile");
      require("METAL3".equals(NativeBridge.nGetBackendMode()),
          "unexpected Metal 3 backend mode: "
              + NativeBridge.nGetBackendMode());
    }

    MetalRenderConfig config = MetalRenderClient.getConfig();
    require(config != null, "MetalRender config was not loaded");
    require(config.enableMetal4 == expectMetal4,
        "MetalRender config did not apply backend expectation "
            + backendExpectation);
    require(!config.enableFastTerrainReplacement,
        "fast terrain replacement must be off in release-safe defaults");
    require(!config.enableExperimentalFeatureReplacement,
        "entity/particle replacement must be release-locked off");

    IrisApi iris = IrisApi.getInstance();
    require(iris.getConfig().areShadersEnabled(),
        "Iris shaders were not enabled by the isolated QA profile");

    NativeFaultCounters nativeFaultBaseline = nativeFaultCounters();
    require(nativeFaultBaseline.isZero(),
        "native fault counters were non-zero before gameplay: "
            + nativeFaultBaseline);

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
      singleplayer.getServer().runCommand("gamemode spectator @a");
      visitDimension(singleplayer, context, "minecraft:the_nether", 82);
      visitDimension(singleplayer, context, "minecraft:the_end", 82);
      visitDimension(singleplayer, context, "minecraft:overworld", 102);
      singleplayer.getServer().runCommand("gamemode creative @a");
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
      context.waitTicks(100);
      IrisTranslationCoordinator.Status earlyPipelineStatus =
          context.computeOnClient(
              client -> IrisTranslationCoordinator.status());
      System.out.println("[MetalRender exact-JAR early pipeline] accepted="
          + earlyPipelineStatus.pipelineVariantsAccepted()
          + ", pending=" + earlyPipelineStatus.pipelineStatesPending()
          + ", attempted=" + earlyPipelineStatus.pipelineStatesAttempted()
          + ", succeeded=" + earlyPipelineStatus.pipelineStatesSucceeded()
          + ", unsupported="
          + earlyPipelineStatus.pipelineStatesUnsupported()
          + ", failed=" + earlyPipelineStatus.pipelineStatesFailed()
          + ", incomplete="
          + earlyPipelineStatus.pipelineIncompleteVariants()
          + ", lastFailure="
          + earlyPipelineStatus.pipelineStateLastFailure());
      context.waitFor(client -> {
        IrisTranslationCoordinator.Status status =
            IrisTranslationCoordinator.status();
        return status.running() && status.queued() == 0
            && status.libraryValidationEnabled()
            && status.libraryValidationReady()
            && status.libraryValidationComplete()
            && status.compiledArtifactSetComplete()
            && status.libraryStagesPending() == 0
            && status.libraryStagesInFlight() == 0;
      }, SHADER_TIMEOUT_TICKS);
      context.waitTicks(100);
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
      requireMslLibraryValidation(translationStatus, cacheExpectation);
      requireResourceReflection(translationStatus);
      System.out.println("[MetalRender exact-JAR] programs="
          + translationStatus.attempted() + ", stages="
          + translationStatus.libraryStagesAttempted()
          + ", pipelineDraws="
          + translationStatus.pipelineDrawsObserved()
          + ", pipelineDispatches="
          + translationStatus.pipelineDispatchesObserved()
          + ", pipelineVariants="
          + translationStatus.pipelineVariantsAccepted()
          + ", pipelineMapped="
          + translationStatus.pipelineStatesSucceeded()
          + ", pipelineUnsupported="
          + translationStatus.pipelineStatesUnsupported()
          + ", pipelineIncomplete="
          + translationStatus.pipelineIncompleteVariants()
          + ", resourcePrograms="
          + translationStatus.resourceProgramsSucceeded()
          + ", resourceStages="
          + translationStatus.resourceStagesReflected()
          + ", resourceBindings="
          + translationStatus.resourceBindingsReflected()
          + ", bindingVariants="
          + translationStatus.resourceBindingVariantsSucceeded() + "/"
          + translationStatus.resourceBindingVariantsAttempted()
          + ", bindingIncomplete="
          + translationStatus.resourceBindingVariantsIncomplete()
          + ", bindingReasons="
          + translationStatus.resourceBindingIncompleteReasonSummary()
          + ", pipelineLastFailure="
          + translationStatus.pipelineStateLastFailure());
      requirePipelineStateCapture(translationStatus);
      requireResourceBindings(translationStatus);
      context.runOnClient(client -> NativeBridge.nFlushFrames());
      NativeFaultCounters nativeFaultEnd =
          context.computeOnClient(client -> nativeFaultCounters());
      NativeFaultCounters nativeFaultDelta =
          nativeFaultEnd.deltaFrom(nativeFaultBaseline);
      require(nativeFaultDelta.isZero(),
          "native GPU/IOSurface fault counters advanced during exact-JAR QA: "
              + nativeFaultDelta);
      writeEvidence(exactJar, expectedSha, frames, framesWithShadersOff,
          metalPresentationsWithShadersOff, shadersOn, shadersOff,
          shadersReenabled, visualMetrics, translationStatus,
          cacheExpectation, backendExpectation, nativeFaultBaseline,
          nativeFaultEnd, nativeFaultDelta);
    }
  }

  private static void visitDimension(TestSingleplayerContext singleplayer,
      ClientGameTestContext context, String dimension, int y) {
    singleplayer.getServer().runCommand("execute as @a in " + dimension
        + " run tp @s 0 " + y + " 0");
    context.waitFor(client -> client.level != null
        && client.level.dimension().identifier().toString().equals(dimension),
        WORLD_TIMEOUT_TICKS);
    context.waitFor(client -> IrisApi.getInstance().isShaderPackInUse(),
        SHADER_TIMEOUT_TICKS);
    context.waitFor(client -> irisOwnsRenderGraph(), WORLD_TIMEOUT_TICKS);
    singleplayer.getServer().runCommand("execute in " + dimension
        + " run setblock 0 " + (y - 2) + " 0 minecraft:stone");
    context.waitTicks(60);
  }

  private static void requirePipelineStateCapture(
      IrisTranslationCoordinator.Status status) {
    require(status.pipelineDrawsObserved() > 0,
        "no registered Iris draw was observed");
    require(status.pipelineDispatchesObserved() >= 0,
        "Iris compute dispatch counter is invalid");
    require(status.pipelineVariantsAccepted() > 0,
        "no Iris pipeline-state variant was accepted");
    require(status.pipelineVariantsRejected() == 0,
        "Iris pipeline-state queue rejected variants: "
            + status.pipelineVariantsRejected());
    require(status.pipelineIncompleteVariants() == 0,
        "Iris pipeline-state capture observed incomplete variants: "
            + status.pipelineIncompleteVariants());
    require(status.pipelineStatesPending() == 0,
        "Iris pipeline-state queue did not drain");
    require(status.pipelineStatesAttempted()
            == status.pipelineVariantsAccepted(),
        "Iris pipeline-state attempts do not cover every accepted variant");
    require(status.pipelineStatesSucceeded()
            == status.pipelineStatesAttempted(),
        "not every Iris pipeline-state variant produced a verified key: "
            + status.pipelineStatesSucceeded() + "/"
            + status.pipelineStatesAttempted());
    require(status.pipelineStatesUnsupported() == 0,
        "Iris pipeline-state mapping reported unsupported variants: "
            + status.pipelineStatesUnsupported() + " ("
            + status.pipelineStateLastFailure() + ")");
    require(status.pipelineStatesFailed() == 0,
        "Iris pipeline-state mapping failed: "
            + status.pipelineStatesFailed() + " ("
            + status.pipelineStateLastFailure() + ")");
    require(status.pipelineStatesExecutionBlocked()
            <= status.pipelineStatesSucceeded(),
        "Metal execution blocker count exceeds captured states: "
            + status.pipelineStatesExecutionBlocked() + "/"
            + status.pipelineStatesSucceeded());
    require(status.pipelineStateSetComplete(),
        "pipeline-state identity set exceeded its verified bound");
    require(status.pipelineStateSetSha256().matches("[0-9a-f]{64}"),
        "pipeline-state digest is invalid: "
            + status.pipelineStateSetSha256());
    require(status.pipelineStateCaptureComplete(),
        "Iris pipeline-state capture gate is incomplete");
  }

  private static void requireResourceReflection(
      IrisTranslationCoordinator.Status status) {
    require(status.resourceReflectionComplete(),
        "Iris SPIR-V resource reflection did not complete: "
            + status.resourceLayoutLastFailure());
    require(status.resourceProgramsAttempted()
            == EXPECTED_COMPLEMENTARY_PROGRAMS,
        "unexpected reflected resource program count: "
            + status.resourceProgramsAttempted());
    require(status.resourceProgramsSucceeded()
            == EXPECTED_COMPLEMENTARY_PROGRAMS,
        "not every Iris program produced a resource layout: "
            + status.resourceProgramsSucceeded());
    require(status.resourceStagesReflected()
            == EXPECTED_COMPLEMENTARY_STAGES,
        "unexpected reflected resource stage count: "
            + status.resourceStagesReflected());
    require(status.resourceBindingsReflected() > 0,
        "no Iris shader resource binding was reflected");
    require(status.resourceLayoutIdentityCount()
            == EXPECTED_COMPLEMENTARY_PROGRAMS,
        "resource-layout identity set does not cover every program");
    require(status.resourceLayoutSetSha256().matches("[0-9a-f]{64}"),
        "resource-layout identity digest is invalid");
  }

  private static void requireResourceBindings(
      IrisTranslationCoordinator.Status status) {
    require(status.resourceBindingCaptureComplete(),
        "Iris runtime resource binding parity is incomplete: "
            + status.resourceBindingIncompleteReasonSummary());
    require(status.resourceBindingVariantsAttempted()
            == status.pipelineStatesSucceeded(),
        "resource binding snapshots do not cover every mapped variant: "
            + status.resourceBindingVariantsAttempted() + "/"
            + status.pipelineStatesSucceeded());
    require(status.resourceBindingVariantsSucceeded()
            == status.resourceBindingVariantsAttempted(),
        "not every resource binding snapshot passed parity: "
            + status.resourceBindingVariantsSucceeded() + "/"
            + status.resourceBindingVariantsAttempted());
    require(status.resourceBindingVariantsIncomplete() == 0,
        "incomplete resource binding snapshots were observed");
    require(status.resourceBindingsMatched() > 0,
        "no reflected Iris resource was matched to a runtime binding");
    require(status.resourceBindingIncompleteReasonSetComplete(),
        "resource binding reason set exceeded its verified bound");
  }

  private static void requireMslLibraryValidation(
      IrisTranslationCoordinator.Status status, String cacheExpectation) {
    require(status.libraryValidationEnabled(),
        "Iris MSL library validation is not enabled");
    require(status.libraryValidationReady(),
        "Iris MSL library validator is not ready");
    require(status.libraryValidationComplete(),
        "Iris MSL library validation did not complete");
    require(status.libraryProgramsAttempted()
            == EXPECTED_COMPLEMENTARY_PROGRAMS,
        "unexpected MSL library program count: expected "
            + EXPECTED_COMPLEMENTARY_PROGRAMS + ", got "
            + status.libraryProgramsAttempted());
    require(status.libraryProgramsSucceeded()
            == EXPECTED_COMPLEMENTARY_PROGRAMS,
        "not every captured program produced validated Metal libraries: "
            + status.libraryProgramsSucceeded());
    require(status.libraryProgramsUnsupported() == 0,
        "MSL library validation reported unsupported programs: "
            + status.libraryProgramsUnsupported());
    require(status.libraryProgramsFailed() == 0,
        "MSL library validation reported failed programs: "
            + status.libraryProgramsFailed());
    require(status.libraryStagesAttempted() == EXPECTED_COMPLEMENTARY_STAGES,
        "unexpected MSL library stage count: expected "
            + EXPECTED_COMPLEMENTARY_STAGES + ", got "
            + status.libraryStagesAttempted());
    require(status.libraryStagesSucceeded() == EXPECTED_COMPLEMENTARY_STAGES,
        "not every generated MSL stage produced a validated MTLLibrary: "
            + status.libraryStagesSucceeded());
    require(status.libraryStagesUnsupported() == 0,
        "MSL library validation reported unsupported stages: "
            + status.libraryStagesUnsupported());
    require(status.libraryStagesFailed() == 0,
        "MSL library validation reported failed stages: "
            + status.libraryStagesFailed());
    require(status.libraryStagesRejected() == 0,
        "MSL library validation rejected stages: "
            + status.libraryStagesRejected());
    require(status.libraryStagesPending() == 0,
        "MSL library validation left pending stages: "
            + status.libraryStagesPending());
    require(status.libraryStagesInFlight() == 0,
        "MSL library validation left in-flight stages: "
            + status.libraryStagesInFlight());
    require(status.libraryStagesAttempted()
            == status.libraryStagesSucceeded()
                + status.libraryStagesUnsupported()
                + status.libraryStagesFailed()
                + status.libraryStagesPending()
                + status.libraryStagesInFlight(),
        "MSL library stage counters do not account for every attempt");
    long expectedFromTranslation = cacheExpectation.equals("cold")
        ? EXPECTED_COMPLEMENTARY_STAGES : 0;
    long expectedFromCache = cacheExpectation.equals("warm")
        ? EXPECTED_COMPLEMENTARY_STAGES : 0;
    require(status.libraryStagesFromTranslation() == expectedFromTranslation,
        cacheExpectation + " run validated an unexpected translated-stage "
            + "count: " + status.libraryStagesFromTranslation());
    require(status.libraryStagesFromCache() == expectedFromCache,
        cacheExpectation + " run validated an unexpected cached-stage count: "
            + status.libraryStagesFromCache());
    require(status.libraryStagesFromTranslation()
            + status.libraryStagesFromCache()
            == status.libraryStagesAttempted(),
        "MSL library source counters do not account for every stage");
    require(status.libraryLiveLibraries() == 0,
        "MSL library validation retained native libraries: "
            + status.libraryLiveLibraries());
    require(NativeBridge.nGetIrisMslCompileAttemptCount()
            == EXPECTED_COMPLEMENTARY_STAGES,
        "native MSL compile attempt count is not exact: "
            + NativeBridge.nGetIrisMslCompileAttemptCount());
    require(NativeBridge.nGetIrisMslCompileSuccessCount()
            == EXPECTED_COMPLEMENTARY_STAGES,
        "native MSL compile success count is not exact: "
            + NativeBridge.nGetIrisMslCompileSuccessCount());
    require(NativeBridge.nGetIrisMslCompileUnsupportedCount() == 0,
        "native MSL compiler reported unsupported stages: "
            + NativeBridge.nGetIrisMslCompileUnsupportedCount());
    require(NativeBridge.nGetIrisMslCompileFailureCount() == 0,
        "native MSL compiler reported failed stages: "
            + NativeBridge.nGetIrisMslCompileFailureCount());
    require(NativeBridge.nGetIrisMslLiveLibraryCount() == 0,
        "native MSL compiler retained MTLLibrary instances: "
            + NativeBridge.nGetIrisMslLiveLibraryCount());
    require(status.compiledArtifactSetSha256().matches("[0-9a-f]{64}"),
        "MSL compiled-artifact digest is invalid: "
            + status.compiledArtifactSetSha256());
    require(status.compiledArtifactSetComplete(),
        "MSL compiled-artifact identity set exceeded its verified bound");
    require(status.libraryValidationLastFailure().isEmpty(),
        "MSL library validation retained a failure: "
            + status.libraryValidationLastFailure());
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

  private static NativeFaultCounters nativeFaultCounters() {
    return new NativeFaultCounters(
        NativeBridge.nGetGpuCommandBufferErrorCount(),
        NativeBridge.nGetInFlightFrameTimeoutCount(),
        NativeBridge.nGetNoIOSurfaceSlotSkipCount());
  }

  private record NativeFaultCounters(long gpuCommandBufferErrors,
                                     long inFlightFrameTimeouts,
                                     long noIOSurfaceSlotSkips) {
    private NativeFaultCounters {
      require(gpuCommandBufferErrors >= 0
              && inFlightFrameTimeouts >= 0
              && noIOSurfaceSlotSkips >= 0,
          "native fault counter overflowed signed Java range");
    }

    private boolean isZero() {
      return gpuCommandBufferErrors == 0
          && inFlightFrameTimeouts == 0
          && noIOSurfaceSlotSkips == 0;
    }

    private NativeFaultCounters deltaFrom(NativeFaultCounters baseline) {
      require(gpuCommandBufferErrors >= baseline.gpuCommandBufferErrors
              && inFlightFrameTimeouts >= baseline.inFlightFrameTimeouts
              && noIOSurfaceSlotSkips >= baseline.noIOSurfaceSlotSkips,
          "native fault counters are not monotonic");
      return new NativeFaultCounters(
          gpuCommandBufferErrors - baseline.gpuCommandBufferErrors,
          inFlightFrameTimeouts - baseline.inFlightFrameTimeouts,
          noIOSurfaceSlotSkips - baseline.noIOSurfaceSlotSkips);
    }
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
      String cacheExpectation, String backendExpectation,
      NativeFaultCounters nativeFaultBaseline,
      NativeFaultCounters nativeFaultEnd,
      NativeFaultCounters nativeFaultDelta) {
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
          "schemaVersion": 5,
          "status": "PASS",
          "environment": "production-fabric",
          "javaMajor": 25,
          "metalrenderVersion": %s,
          "exactJarPath": %s,
          "exactJarSha256": %s,
          "cacheExpectation": %s,
          "backendExpectation": %s,
          "backendMode": %s,
          "metal4Active": %s,
          "metal4DrawPathActive": %s,
          "sodiumLoaded": true,
          "irisLoaded": true,
          "irisRuntimeOwnership": {
            "evidenceKind": "indirect-runtime-ownership-check",
            "assertedDrawBackend": "OPENGL",
            "measuredAtRuntime": true,
            "passed": true
          },
          "generatedMslExecutionBoundary": {
            "runtimeTelemetryAvailable": false,
            "assertedStaticBoundary": "library-compile-resolve-release-only"
          },
          "shaderPack": %s,
          "shaderPackInitiallyLoaded": true,
          "shaderPackDisabledAndApplied": true,
          "shaderPackReenabledAndApplied": true,
          "dimensionRoute": [
            "minecraft:overworld",
            "minecraft:the_nether",
            "minecraft:the_end",
            "minecraft:overworld"
          ],
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
          "irisPipelineStateCapture": {
            "complete": %s,
            "drawsObserved": %d,
            "dispatchesObserved": %d,
            "variantsAccepted": %d,
            "variantsRejected": %d,
            "incompleteVariants": %d,
            "statesPending": %d,
            "statesAttempted": %d,
            "statesSucceeded": %d,
            "cacheHits": %d,
            "statesUnsupported": %d,
            "unsupportedReasonCount": %d,
            "unsupportedReasonSetComplete": %s,
            "unsupportedReasonSetSha256": %s,
            "unsupportedReasonSummary": %s,
            "statesFailed": %d,
            "executionBlocked": %d,
            "stateIdentityCount": %d,
            "stateSetSha256": %s,
            "stateSetComplete": %s,
            "lastFailure": %s,
            "pipelineStatus": "pending",
            "irisOpenGlActive": true
          },
          "irisResourceReflection": {
            "complete": %s,
            "programsAttempted": %d,
            "programsSucceeded": %d,
            "programsUnsupported": %d,
            "programsFailed": %d,
            "stagesReflected": %d,
            "bindingsReflected": %d,
            "layoutIdentityCount": %d,
            "layoutSetComplete": %s,
            "layoutSetSha256": %s,
            "lastFailure": %s,
            "bindingVariantsAttempted": %d,
            "bindingVariantsSucceeded": %d,
            "bindingVariantsIncomplete": %d,
            "runtimeBindingsMatched": %d,
            "bindingIncompleteReasonCount": %d,
            "bindingIncompleteReasonSetComplete": %s,
            "bindingIncompleteReasonSetSha256": %s,
            "bindingIncompleteReasonSummary": %s,
            "runtimeBindingsCaptured": true
          },
          "generatedMslLibraryValidation": {
            "enabled": %s,
            "ready": %s,
            "complete": %s,
            "mode": "compile-resolve-release-only",
            "executionBoundary": "library-compile-resolve-release-only",
            "stageCounterSemantics": "coordinator-lifetime-monotonic",
            "nativeCounterSemantics": "process-lifetime-monotonic",
            "liveLibrarySemantics": "instantaneous-gauge",
            "programsAttempted": %d,
            "programsSucceeded": %d,
            "programsUnsupported": %d,
            "programsFailed": %d,
            "stagesAttempted": %d,
            "stagesSucceeded": %d,
            "stagesUnsupported": %d,
            "stagesFailed": %d,
            "stagesRejected": %d,
            "stagesPending": %d,
            "stagesInFlight": %d,
            "stagesFromTranslation": %d,
            "stagesFromCache": %d,
            "nativeCompileAttempts": %d,
            "nativeCompileSuccesses": %d,
            "nativeCompileUnsupported": %d,
            "nativeCompileFailures": %d,
            "compiledArtifactSetSha256": %s,
            "compiledArtifactSetComplete": %s,
            "lastFailure": %s,
            "statusLiveLibraries": %d,
            "liveLibrariesAtEvidence": %d
          },
          "nativeFaultCounters": {
            "semantics": "process-lifetime-monotonic",
            "baseline": {
              "gpuCommandBufferErrors": %d,
              "inFlightFrameTimeouts": %d,
              "noIOSurfaceSlotSkips": %d
            },
            "end": {
              "gpuCommandBufferErrors": %d,
              "inFlightFrameTimeouts": %d,
              "noIOSurfaceSlotSkips": %d
            },
            "delta": {
              "gpuCommandBufferErrors": %d,
              "inFlightFrameTimeouts": %d,
              "noIOSurfaceSlotSkips": %d
            }
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
        quote(backendExpectation),
        quote(NativeBridge.nGetBackendMode()),
        Boolean.toString(NativeBridge.nIsMetal4Active()),
        Boolean.toString(NativeBridge.nIsMetal4DrawPathActive()),
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
        Boolean.toString(translationStatus.pipelineStateCaptureComplete()),
        translationStatus.pipelineDrawsObserved(),
        translationStatus.pipelineDispatchesObserved(),
        translationStatus.pipelineVariantsAccepted(),
        translationStatus.pipelineVariantsRejected(),
        translationStatus.pipelineIncompleteVariants(),
        translationStatus.pipelineStatesPending(),
        translationStatus.pipelineStatesAttempted(),
        translationStatus.pipelineStatesSucceeded(),
        translationStatus.pipelineStateCacheHits(),
        translationStatus.pipelineStatesUnsupported(),
        translationStatus.pipelineStateUnsupportedReasonCount(),
        Boolean.toString(
            translationStatus.pipelineStateUnsupportedReasonSetComplete()),
        quote(translationStatus.pipelineStateUnsupportedReasonSetSha256()),
        quote(translationStatus.pipelineStateUnsupportedReasonSummary()),
        translationStatus.pipelineStatesFailed(),
        translationStatus.pipelineStatesExecutionBlocked(),
        translationStatus.pipelineStateIdentityCount(),
        quote(translationStatus.pipelineStateSetSha256()),
        Boolean.toString(translationStatus.pipelineStateSetComplete()),
        quote(translationStatus.pipelineStateLastFailure()),
        Boolean.toString(translationStatus.resourceReflectionComplete()),
        translationStatus.resourceProgramsAttempted(),
        translationStatus.resourceProgramsSucceeded(),
        translationStatus.resourceProgramsUnsupported(),
        translationStatus.resourceProgramsFailed(),
        translationStatus.resourceStagesReflected(),
        translationStatus.resourceBindingsReflected(),
        translationStatus.resourceLayoutIdentityCount(),
        Boolean.toString(translationStatus.resourceLayoutSetComplete()),
        quote(translationStatus.resourceLayoutSetSha256()),
        quote(translationStatus.resourceLayoutLastFailure()),
        translationStatus.resourceBindingVariantsAttempted(),
        translationStatus.resourceBindingVariantsSucceeded(),
        translationStatus.resourceBindingVariantsIncomplete(),
        translationStatus.resourceBindingsMatched(),
        translationStatus.resourceBindingIncompleteReasonCount(),
        Boolean.toString(
            translationStatus.resourceBindingIncompleteReasonSetComplete()),
        quote(translationStatus.resourceBindingIncompleteReasonSetSha256()),
        quote(translationStatus.resourceBindingIncompleteReasonSummary()),
        Boolean.toString(translationStatus.libraryValidationEnabled()),
        Boolean.toString(translationStatus.libraryValidationReady()),
        Boolean.toString(translationStatus.libraryValidationComplete()),
        translationStatus.libraryProgramsAttempted(),
        translationStatus.libraryProgramsSucceeded(),
        translationStatus.libraryProgramsUnsupported(),
        translationStatus.libraryProgramsFailed(),
        translationStatus.libraryStagesAttempted(),
        translationStatus.libraryStagesSucceeded(),
        translationStatus.libraryStagesUnsupported(),
        translationStatus.libraryStagesFailed(),
        translationStatus.libraryStagesRejected(),
        translationStatus.libraryStagesPending(),
        translationStatus.libraryStagesInFlight(),
        translationStatus.libraryStagesFromTranslation(),
        translationStatus.libraryStagesFromCache(),
        NativeBridge.nGetIrisMslCompileAttemptCount(),
        NativeBridge.nGetIrisMslCompileSuccessCount(),
        NativeBridge.nGetIrisMslCompileUnsupportedCount(),
        NativeBridge.nGetIrisMslCompileFailureCount(),
        quote(translationStatus.compiledArtifactSetSha256()),
        Boolean.toString(translationStatus.compiledArtifactSetComplete()),
        quote(translationStatus.libraryValidationLastFailure()),
        translationStatus.libraryLiveLibraries(),
        NativeBridge.nGetIrisMslLiveLibraryCount(),
        nativeFaultBaseline.gpuCommandBufferErrors(),
        nativeFaultBaseline.inFlightFrameTimeouts(),
        nativeFaultBaseline.noIOSurfaceSlotSkips(),
        nativeFaultEnd.gpuCommandBufferErrors(),
        nativeFaultEnd.inFlightFrameTimeouts(),
        nativeFaultEnd.noIOSurfaceSlotSkips(),
        nativeFaultDelta.gpuCommandBufferErrors(),
        nativeFaultDelta.inFlightFrameTimeouts(),
        nativeFaultDelta.noIOSurfaceSlotSkips(),
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

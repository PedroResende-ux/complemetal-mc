package com.pebbles_boon.metalrender.exactjarqa;

import com.mojang.blaze3d.platform.Window;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisMetalFeatureFlags;
import com.pebbles_boon.metalrender.compat.iris.IrisStage9PerformanceSampler;
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
import java.util.Arrays;
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
import org.lwjgl.glfw.GLFW;

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
  private static final int MINIMUM_PERFORMANCE_SAMPLES = 600;
  private static final long PERFORMANCE_STUTTER_NANOS = 100_000_000L;
  private static final int LIFECYCLE_PRESENTATIONS_PER_TRANSITION = 20;
  private static final int MAXIMUM_LIFECYCLE_INVALIDATIONS = 16;
  private static volatile long lastReadinessDiagnosticNanos;

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
    require(IrisMetalFeatureFlags.enabled(
            "metalrender.experimental.irisMetalLibraryValidation"),
        "exact-JAR QA requires Iris MSL library validation opt-in");
    require(IrisMetalFeatureFlags.enabled(
            "metalrender.experimental.irisMetalPipelineCompilation"),
        "exact-JAR QA requires Iris Metal pipeline compilation opt-in");
    require(IrisMetalFeatureFlags.enabled(
            "metalrender.experimental.irisMetalShadowReplay"),
        "exact-JAR QA requires Iris Metal shadow replay opt-in");
    require(IrisMetalFeatureFlags.enabled(
            "metalrender.experimental.irisMetalVisualParity"),
        "exact-JAR QA requires Iris/Metal visual parity opt-in");
    boolean diagnosticDisableFinalCutover = Boolean.getBoolean(
        "metalrender.exactJar.diagnosticDisableFinalCutover");
    require(IrisMetalFeatureFlags.enabled(
            "metalrender.experimental.irisMetalFinalCutover")
            || diagnosticDisableFinalCutover,
        "exact-JAR QA requires Iris FINAL cutover opt-in");
    if (diagnosticDisableFinalCutover) {
      System.out.println(
          "[MetalRender exact-JAR diagnostic] FINAL cutover disabled; "
              + "this run cannot qualify as release acceptance");
    }
    require(IrisMetalFeatureFlags.enabled(
            "metalrender.experimental.irisMetalGraphResources"),
        "exact-JAR QA requires Iris Metal graph resource opt-in");
    require(IrisMetalFeatureFlags.enabled(
            "metalrender.experimental.irisMetalGraphExecution"),
        "exact-JAR QA requires full Iris Metal graph execution opt-in");
    require(backendExpectation.equals("metal4")
            || backendExpectation.equals("metal3"),
        "invalid backend expectation: " + backendExpectation);
    boolean expectMetal4 = backendExpectation.equals("metal4");
    boolean requireGraphOwnership = Boolean.getBoolean(
        "metalrender.exactJar.requireGraphOwnership");
    require(!requireGraphOwnership || expectMetal4,
        "full graph ownership requires the Metal 4 QA profile");
    require(IrisMetalFeatureFlags.enabled(
            "metalrender.experimental.irisMetalGraphOwnership")
            == requireGraphOwnership,
        "graph ownership runtime opt-in does not match QA expectation");
    String performanceSide = System.getProperty(
        IrisStage9PerformanceSampler.SIDE_PROPERTY, "").trim()
        .toLowerCase(Locale.ROOT);
    int performanceSamples = integerProperty(
        "metalrender.exactJar.performanceSamples", 0);
    require(performanceSide.isEmpty() || performanceSide.equals("opengl")
            || performanceSide.equals("metal"),
        "invalid Stage 9 performance side: " + performanceSide);
    require(performanceSide.isEmpty()
            ? performanceSamples == 0
            : performanceSamples >= MINIMUM_PERFORMANCE_SAMPLES
                && performanceSamples
                    <= IrisStage9PerformanceSampler.MAX_SAMPLES,
        "invalid Stage 9 performance sample count: " + performanceSamples);
    require(IrisStage9PerformanceSampler.enabled()
            == !performanceSide.isEmpty(),
        "Stage 9 sampler activation does not match the exact-JAR profile");
    require(!performanceSide.equals("metal")
            || expectMetal4 && requireGraphOwnership,
        "Metal performance capture requires full Metal 4 graph ownership");
    require(!performanceSide.equals("opengl")
            || !expectMetal4 && !requireGraphOwnership,
        "OpenGL performance capture requires the Metal 3 fallback profile");
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
    Path shadersCutover;
    long frames;
    long framesWithShadersOff;
    long metalPresentationsWithShadersOff;
    try (TestSingleplayerContext singleplayer =
             context.worldBuilder().setUseConsistentSettings(true).create()) {
      singleplayer.getServer().runCommand("time set noon");
      singleplayer.getServer().runCommand("weather clear");
      singleplayer.getServer().runCommand(
          "gamerule doDaylightCycle false");
      singleplayer.getServer().runCommand(
          "gamerule doWeatherCycle false");

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
          + ", captureFailures=" + earlyPipelineStatus.captureFailures()
          + ", captureFailureReasons="
          + IrisShaderCapture.captureFailureReasonSummary()
          + ", translationFailureReasons="
          + earlyPipelineStatus.translationFailureReasonSummary()
          + ", lastFailure="
          + earlyPipelineStatus.pipelineStateLastFailure());
      IrisTranslationCoordinator.RenderGraphStatus earlyGraphStatus =
          context.computeOnClient(
              client -> IrisTranslationCoordinator.renderGraphStatus());
      System.out.println("[MetalRender exact-JAR early graph] frames="
          + earlyGraphStatus.framesCompleted() + "/"
          + earlyGraphStatus.framesStarted() + ", pending="
          + earlyGraphStatus.framesPending() + ", graphs="
          + earlyGraphStatus.graphsSucceeded() + "/"
          + earlyGraphStatus.graphsAttempted() + ", unsupported="
          + earlyGraphStatus.graphsUnsupported() + ", failed="
          + earlyGraphStatus.graphsFailed() + ", barriers="
          + earlyGraphStatus.barriersRepresented() + ", clears="
          + earlyGraphStatus.clearsRepresented() + ", transfers="
          + earlyGraphStatus.transfersRepresented() + ", pingPong="
          + earlyGraphStatus.pingPongResourcesRepresented() + ", phases="
          + earlyGraphStatus.phaseSummary() + ", lastFailure="
          + earlyGraphStatus.lastFailure());
      context.waitFor(client -> acceptanceSettled(
          expectMetal4, requireGraphOwnership),
          SHADER_TIMEOUT_TICKS);
      context.waitTicks(100);
      IrisTranslationCoordinator.AcceptanceStatus acceptanceStatus =
          context.computeOnClient(
              client -> IrisTranslationCoordinator.acceptanceStatus());
      IrisTranslationCoordinator.Status translationStatus =
          acceptanceStatus.translation();
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
      IrisTranslationCoordinator.RenderGraphStatus renderGraphStatus =
          acceptanceStatus.renderGraph();
      requireRenderGraph(renderGraphStatus);
      IrisTranslationCoordinator.MetalGraphResourceStatus graphResourceStatus =
          acceptanceStatus.graphResources();
      requireMetalGraphResources(graphResourceStatus, renderGraphStatus,
          expectMetal4);
      IrisTranslationCoordinator.FullGraphStatus fullGraphStatus =
          acceptanceStatus.fullGraph();
      requireFullGraph(fullGraphStatus, expectMetal4,
          requireGraphOwnership);
      IrisTranslationCoordinator.ShadowPlanStatus shadowPlanStatus =
          acceptanceStatus.shadowPlan();
      requireShadowPlanCapture(shadowPlanStatus, renderGraphStatus);
      IrisTranslationCoordinator.MetalPipelineCacheStatus
          metalPipelineStatus = acceptanceStatus.metalPipelines();
      requireMetalPipelineCache(metalPipelineStatus, expectMetal4,
          cacheExpectation);
      IrisTranslationCoordinator.ShadowReplayStatus shadowReplayStatus =
          acceptanceStatus.shadowReplay();
      requireShadowReplay(shadowReplayStatus, metalPipelineStatus,
          expectMetal4, requireGraphOwnership);
      IrisTranslationCoordinator.VisualParityStatus visualParityStatus =
          acceptanceStatus.visualParity();
      requireVisualParity(visualParityStatus, expectMetal4);
      LifecycleEvidence lifecycleEvidence = LifecycleEvidence.notRequired();
      if (expectMetal4) {
        if (requireGraphOwnership) {
          context.waitFor(client -> {
            IrisTranslationCoordinator.FullGraphStatus ownership =
                IrisTranslationCoordinator.fullGraphStatus();
            return ownership.performanceEligible()
                && ownership.ownershipFramesPresented() >= 30;
          }, SHADER_TIMEOUT_TICKS);
          lifecycleEvidence = verifyStage9WindowLifecycle(context);
          writeLifecycleEvidence(lifecycleEvidence);
        } else {
          context.waitFor(client -> {
            IrisTranslationCoordinator.CutoverStatus cutover =
                IrisTranslationCoordinator.cutoverStatus();
            return cutover.activeAndHealthy()
                && cutover.presentations() >= 30;
          }, SHADER_TIMEOUT_TICKS);
        }
        context.waitTicks(20);
        long cutoverScreenshotCapture = screenshotCaptureCount(context);
        shadersCutover = context.takeScreenshot(
            "metalrender-exact-jar-" + cacheExpectation
                + (requireGraphOwnership
                    ? "-shaders-metal-full-graph"
                    : "-shaders-metal-final-cutover"));
        requireScreenshot(shadersCutover);
        requireScreenshotFrame(context, cutoverScreenshotCapture, false,
            "Metal FINAL cutover screenshot");
      } else {
        shadersCutover = shadersReenabled;
      }
      IrisTranslationCoordinator.CutoverStatus cutoverStatus =
          context.computeOnClient(
              client -> IrisTranslationCoordinator.cutoverStatus());
      requireFinalCutover(cutoverStatus, expectMetal4,
          requireGraphOwnership);
      CutoverVisualMetrics cutoverVisual = validateCutoverScreenshot(
          shadersReenabled, shadersCutover, expectMetal4);
      if (!performanceSide.isEmpty()) {
        PerformanceEvidence performanceEvidence = captureStage9Performance(
            context, performanceSide, performanceSamples, expectedSha,
            expectedShaderPack);
        writePerformanceEvidence(performanceEvidence);
      }
      if (requireGraphOwnership) {
        fullGraphStatus = context.computeOnClient(
            client -> IrisTranslationCoordinator.fullGraphStatus());
        requireFullGraph(fullGraphStatus, true, true);
        cutoverStatus = context.computeOnClient(
            client -> IrisTranslationCoordinator.cutoverStatus());
        requireFinalCutover(cutoverStatus, true, true);
        require(lifecycleEvidence.status().equals("PASS"),
            "Stage 9 lifecycle evidence was not completed");
      }
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
          + ", graph=" + renderGraphStatus.graphsSucceeded() + "/"
          + renderGraphStatus.graphsAttempted()
          + ", graphNodes=" + renderGraphStatus.nodesRepresented()
          + ", graphEdges=" + renderGraphStatus.edgesRepresented()
          + ", graphPhases=" + renderGraphStatus.phaseSummary()
          + ", graphResources=" + graphResourceStatus.plansComplete() + "/"
          + graphResourceStatus.plansObserved()
          + ", fullGraph=" + fullGraphStatus.framesSucceeded() + "/"
          + fullGraphStatus.framesAttempted() + ", fullGraphDraws="
          + fullGraphStatus.draws() + ", fullGraphOwnership="
          + fullGraphStatus.ownershipMode()
          + ", graphNativeTextures="
          + graphResourceStatus.nativeTextureCount()
          + ", graphNativeBytes=" + graphResourceStatus.nativeTextureBytes()
          + ", shadowPlans=" + shadowPlanStatus.structurallyComplete()
          + "/" + shadowPlanStatus.plansObserved()
          + ", shadowBlocked=" + shadowPlanStatus.blocked()
          + ", shadowSteps=" + shadowPlanStatus.executionSteps()
          + ", metalPipelines="
          + metalPipelineStatus.pipelineIdentityCount() + "/"
          + metalPipelineStatus.candidatesObserved()
          + ", metalPipelineCompiled=" + metalPipelineStatus.compiled()
          + ", metalPipelineCacheHits=" + metalPipelineStatus.cacheHits()
          + ", shadowReplay=" + shadowReplayStatus.drawsSucceeded() + "/"
          + shadowReplayStatus.drawsAttempted()
          + ", shadowReplayBlocked=" + shadowReplayStatus.drawsBlocked()
          + ", shadowReplayPhases="
          + shadowReplayStatus.successfulPhaseSummary()
          + ", visualParity=" + visualParityStatus.framesPassed() + "/"
          + visualParityStatus.framesCompared()
          + ", visualParityValidated="
          + visualParityStatus.validated()
          + ", cutover=" + cutoverStatus.presentations() + "/"
          + cutoverStatus.openGlDrawsSuppressed()
          + ", cutoverBridge=" + cutoverStatus.bridge()
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
          renderGraphStatus, graphResourceStatus, shadowPlanStatus,
          fullGraphStatus, metalPipelineStatus,
          shadowReplayStatus, visualParityStatus, cutoverStatus,
          shadersCutover, cutoverVisual,
          cacheExpectation,
          backendExpectation,
          nativeFaultBaseline,
          nativeFaultEnd, nativeFaultDelta);
    }
  }

  private static void visitDimension(TestSingleplayerContext singleplayer,
      ClientGameTestContext context, String dimension, int y) {
    singleplayer.getServer().runCommand("execute as @a in " + dimension
        + " run tp @s 0 " + y + " 0 0 20");
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

  private static void requireRenderGraph(
      IrisTranslationCoordinator.RenderGraphStatus status) {
    require(status.complete(),
        "Iris render graph did not complete: " + status.lastFailure());
    require(status.framesRejected() == 0,
        "Iris render graph rejected frames: " + status.framesRejected());
    require(status.graphsUnsupported() == 0,
        "Iris render graph contains unsupported frames: "
            + status.graphsUnsupported());
    require(status.graphsFailed() == 0,
        "Iris render graph processing failed: " + status.graphsFailed());
    require(status.clearsRepresented() > 0,
        "Iris render graph captured no executable clear commands");
    require(status.graphIdentityCount() > 0,
        "Iris render graph produced no content identity");
    require(status.graphSetSha256().matches("[0-9a-f]{64}"),
        "Iris render graph digest is invalid");
  }

  private static void requireMetalGraphResources(
      IrisTranslationCoordinator.MetalGraphResourceStatus status,
      IrisTranslationCoordinator.RenderGraphStatus graph,
      boolean expectMetal4) {
    require(status.enabled(), "Iris Metal graph resources are not enabled");
    require(status.plansObserved() == graph.graphsSucceeded(),
        "Metal attachment plans do not cover every graph: "
            + status.plansObserved() + "/" + graph.graphsSucceeded());
    require(status.plansComplete() == status.plansObserved()
            && status.plansBlocked() == 0,
        "Metal attachment plans are incomplete: " + status.blockerSummary());
    require(status.allocationsRequested() > 0,
        "Metal attachment plans requested no writable textures");
    require(status.blockerCount() == 0 && status.blockerSetComplete(),
        "Metal attachment blocker set is not empty/complete");
    if (expectMetal4) {
      require(status.complete(),
          "Metal 4 graph attachments did not become resident: "
              + status.blockerSummary());
    } else {
      require(status.safeUnsupportedFallback(),
          "Metal 3 graph attachment path did not remain a safe fallback");
    }
  }

  private static void requireFullGraph(
      IrisTranslationCoordinator.FullGraphStatus status,
      boolean expectMetal4, boolean requireGraphOwnership) {
    require(status.enabled(), "full Iris Metal graph execution is disabled");
    if (expectMetal4 && requireGraphOwnership) {
      require(status.framesPending() <= 2,
          "full Iris Metal graph production queue exceeded its bound: "
              + status.framesPending());
    } else {
      require(!status.captureActive() && status.framesPending() == 0,
          "full Iris Metal graph queue did not drain");
    }
    require(status.blockerSetComplete(),
        "full Iris Metal graph blocker set exceeded its bound");
    if (!expectMetal4) {
      require(!status.ownershipOptedIn() && !status.performanceEligible(),
          "forced Metal 3 unexpectedly enabled graph ownership");
      require(status.ownershipMode().equals("OPENGL_VISIBLE_VALIDATION"),
          "unexpected Metal 3 graph ownership mode: "
              + status.ownershipMode());
      require(!status.runtimeAvailable() && !status.validated(),
          "forced Metal 3 unexpectedly validated the MTL4 graph");
      require(status.captureRequests() == 0 && status.framesObserved() == 0
              && status.framesPlanned() == 0
              && status.framesAttempted() == 0
              && status.framesSucceeded() == 0
              && status.framesUnsupported() == 0
              && status.framesFailed() == 0
              && status.blockerCount() == 0,
          "forced Metal 3 did not keep the full graph draw-free");
      return;
    }
    require(status.runtimeAvailable(),
        "Metal 4 full graph runtime is unavailable");
    require(status.validated(),
        "full Iris Metal graph did not validate: "
            + status.blockerSummary() + " last=" + status.lastFailure());
    require(status.framesSucceeded() >= status.minimumSuccessFrames()
            && status.framesAttempted() == status.framesSucceeded()
            && status.framesUnsupported() == 0
            && status.framesFailed() == 0,
        "full graph frame accounting is not clean");
    require(status.operations() > 0 && status.draws() > 0
            && status.clears() > 0 && status.transfers() > 0
            && status.barriers() > 0,
        "full graph did not execute every operation category");
    require(status.initializedResources() > 0
            && !status.lastOutputHashUnsigned().equals("0")
            && status.blockerCount() == 0,
        "full graph residency/output evidence is incomplete");
    if (!requireGraphOwnership) {
      require(!status.ownershipOptedIn() && !status.performanceEligible(),
          "validation-only full graph must not claim ownership");
      require(status.ownershipMode().equals("OPENGL_VISIBLE_VALIDATION"),
          "unexpected validation graph ownership mode: "
              + status.ownershipMode());
      require(status.ownershipSubmissions() == 0
              && status.ownershipReady() == 0
              && status.ownershipFramesArmed() == 0
              && status.ownershipFramesPresented() == 0
              && status.ownershipFramesReused() == 0
              && status.ownershipFramesInvalidated() == 0
              && status.ownershipCommandsSuppressed() == 0
              && status.ownershipFailures() == 0
              && status.ownershipLastFailure().isEmpty(),
          "validation-only graph retained ownership telemetry");
      return;
    }
    require(status.ownershipOptedIn() && status.performanceEligible(),
        "full Metal graph ownership is not performance eligible: "
            + status.ownershipLastFailure());
    require(status.ownershipMode().equals("METAL_FULL_GRAPH_OWNERSHIP"),
        "unexpected Stage 9 ownership mode: " + status.ownershipMode());
    require(status.ownershipSubmissions() > 0
            && status.ownershipReady() > 0
            && status.ownershipFramesArmed() >= 30
            && status.ownershipFramesPresented() >= 30
            && status.ownershipCommandsSuppressed() > 0,
        "Stage 9 ownership telemetry is incomplete");
    require(status.framesPlanned() == status.framesAttempted()
            + status.ownershipSubmissions()
            + status.ownershipFramesInvalidated() + status.framesPending(),
        "Stage 9 validation/presentation frame accounting is inconsistent");
    require(status.ownershipFramesReused()
            <= status.ownershipFramesPresented(),
        "Stage 9 surface reuse exceeds presented frames");
    require(status.ownershipFailures() == 0
            && status.ownershipLastFailure().isEmpty(),
        "Stage 9 ownership recorded a failure: "
            + status.ownershipLastFailure());
  }

  private static void requireMetalPipelineCache(
      IrisTranslationCoordinator.MetalPipelineCacheStatus status,
      boolean expectMetal4, String cacheExpectation) {
    require(status.enabled(), "Iris Metal pipeline cache is not enabled");
    require(status.executionBlockedCandidates() == 0,
        "Iris pipeline candidates still have Metal execution blockers: "
            + status.executionBlockedCandidates());
    require(status.pending() == 0,
        "Iris Metal pipeline queue did not drain: " + status.pending());
    if (!expectMetal4) {
      require(status.safeUnsupportedFallback(),
          "forced Metal 3 did not retain the safe pipeline fallback: "
              + status.lastFailure());
      return;
    }
    require(status.complete(),
        "Iris MTL4 pipeline cache did not complete: "
            + status.lastFailure());
    require(status.pipelineSetSha256().matches("[0-9a-f]{64}"),
        "Iris MTL4 pipeline digest is invalid");
    require(status.failureReasonCount() == 0,
        "Iris MTL4 pipeline failures were retained");
    require(status.nativeStatus().staleArchivesRecovered() >= 0,
        "Iris MTL4 stale recovery counter is invalid");
    if (cacheExpectation.equals("cold")) {
      require(status.compiled() == status.candidatesObserved()
              && status.cacheHits() == 0
              && status.newVariantsCompiled() == status.compiled()
              && status.knownArchiveMisses() == 0,
          "cold MTL4 run did not compile every pipeline candidate");
    } else {
      require(status.knownArchiveMisses() == 0
              && status.compiled() == status.newVariantsCompiled()
              && status.cacheHits() + status.newVariantsCompiled()
                  == status.candidatesObserved(),
          "warm MTL4 run missed a previously known archived pipeline");
    }
  }

  private static boolean shadowReplaySettled(boolean expectMetal4) {
    IrisTranslationCoordinator.ShadowReplayStatus status =
        IrisTranslationCoordinator.shadowReplayStatus();
    IrisTranslationCoordinator.VisualParityStatus parity =
        IrisTranslationCoordinator.visualParityStatus();
    if (!status.enabled() || status.drawsObserved() <= 0
        || status.drawsAttempted() != status.drawsSucceeded()
            + status.drawsUnsupported() + status.drawsFailed()) {
      return false;
    }
    return expectMetal4 ? status.drawsSucceeded() > 0
        && status.drawsUnsupported() == 0 && status.drawsFailed() == 0
        && requiredReplayPhaseCoverage(status) && parity.validated()
        : status.drawsBlocked() > 0 && status.drawsAttempted() == 0
            && parity.samplesHandled()
                >= IrisRenderGraphCapture.MAX_REPLAY_SAMPLES_PER_PHASE;
  }

  private static boolean requiredReplayPhaseCoverage(
      IrisTranslationCoordinator.ShadowReplayStatus status) {
    String phases = status.successfulPhaseSummary();
    return status.successfulPhaseCount() >= 4
        && phases.contains(IrisRenderGraph.Phase.SHADOW.name())
        && phases.contains(IrisRenderGraph.Phase.GEOMETRY.name())
        && phases.contains(IrisRenderGraph.Phase.COMPOSITE.name())
        && phases.contains(IrisRenderGraph.Phase.FINAL.name());
  }

  private static boolean acceptanceSettled(
      boolean expectMetal4, boolean requireGraphOwnership) {
    IrisTranslationCoordinator.Status translation =
        IrisTranslationCoordinator.status();
    IrisTranslationCoordinator.RenderGraphStatus graph =
        IrisTranslationCoordinator.renderGraphStatus();
    IrisTranslationCoordinator.MetalPipelineCacheStatus pipelines =
        IrisTranslationCoordinator.metalPipelineCacheStatus();
    IrisTranslationCoordinator.ShadowReplayStatus replay =
        IrisTranslationCoordinator.shadowReplayStatus();
    IrisTranslationCoordinator.FullGraphStatus fullGraph =
        IrisTranslationCoordinator.fullGraphStatus();
    boolean translationReady = translation.running()
        && translation.queued() == 0
        && translation.libraryValidationEnabled()
        && translation.libraryValidationReady()
        && translation.libraryValidationComplete()
        && translation.compiledArtifactSetComplete()
        && translation.libraryStagesPending() == 0
        && translation.libraryStagesInFlight() == 0;
    boolean graphReady = graph.complete();
    boolean replayReady = shadowReplaySettled(expectMetal4);
    boolean pipelinesReady = expectMetal4 ? pipelines.complete()
        : pipelines.safeUnsupportedFallback();
    boolean fullGraphReady = expectMetal4
        ? requireGraphOwnership
            ? fullGraph.validated() && fullGraph.performanceEligible()
                && fullGraph.ownershipFramesPresented() >= 30
                && fullGraph.ownershipFailures() == 0
            : fullGraph.validated()
        : fullGraph.enabled() && !fullGraph.runtimeAvailable()
            && fullGraph.framesAttempted() == 0;
    if (!(translationReady && graphReady && replayReady && pipelinesReady
        && fullGraphReady)) {
      long now = System.nanoTime();
      if (now - lastReadinessDiagnosticNanos >= 5_000_000_000L) {
        lastReadinessDiagnosticNanos = now;
        System.out.println("[MetalRender exact-JAR readiness] translation="
            + translationReady + " queue=" + translation.queued()
            + " programs=" + translation.attempted() + "/"
            + translation.libraryProgramsSucceeded()
            + " stages=" + translation.libraryStagesSucceeded() + "/"
            + translation.libraryStagesAttempted() + " pending="
            + translation.libraryStagesPending() + " inFlight="
            + translation.libraryStagesInFlight() + " live="
            + translation.libraryLiveLibraries() + " artifactSet="
            + translation.compiledArtifactSetComplete()
            + " graph=" + graphReady + " frames="
            + graph.framesCompleted() + "/" + graph.framesStarted()
            + " rejected=" + graph.framesRejected() + " pending="
            + graph.framesPending() + " frozen=" + graph.captureFrozen()
            + " graphs=" + graph.graphsSucceeded() + "/"
            + graph.graphsAttempted() + " unsupported="
            + graph.graphsUnsupported() + " failed=" + graph.graphsFailed()
            + " nodes=" + graph.nodesRepresented() + " edges="
            + graph.edgesRepresented() + " barriers="
            + graph.barriersRepresented() + " clears="
            + graph.clearsRepresented() + " transfers="
            + graph.transfersRepresented() + " pingPong="
            + graph.pingPongResourcesRepresented() + " phases="
            + graph.phaseSummary() + " graphFailure=" + graph.lastFailure()
            + " pipelines=" + pipelinesReady + " candidates="
            + pipelines.candidatesObserved() + " compiled="
            + pipelines.compiled() + " hits=" + pipelines.cacheHits()
            + " readiness=" + pipelines.readiness() + " backend="
            + NativeBridge.nGetBackendMode() + " nativeAttempts="
            + pipelines.nativeStatus().attempts()
            + " pending=" + pipelines.pending() + " failed="
            + pipelines.failed() + " pipelineFailure="
            + pipelines.lastFailure() + " replay=" + replayReady
            + " observed=" + replay.drawsObserved() + " ready="
            + replay.drawsReady() + " attempted="
            + replay.drawsAttempted() + " succeeded="
            + replay.drawsSucceeded() + " unsupported="
            + replay.drawsUnsupported() + " failed="
            + replay.drawsFailed() + " blocked=" + replay.drawsBlocked()
            + " phases=" + replay.successfulPhaseSummary()
            + " replayBlockers=" + replay.blockerSummary());
        System.out.println("[MetalRender exact-JAR full graph readiness] ready="
            + fullGraphReady + " active=" + fullGraph.captureActive()
            + " requests=" + fullGraph.captureRequests() + " observed="
            + fullGraph.framesObserved() + " planned="
            + fullGraph.framesPlanned() + " pending="
            + fullGraph.framesPending() + " attempted="
            + fullGraph.framesAttempted() + " succeeded="
            + fullGraph.framesSucceeded() + " unsupported="
            + fullGraph.framesUnsupported() + " failed="
            + fullGraph.framesFailed() + " draws=" + fullGraph.draws()
            + " operations=" + fullGraph.operations() + " mode="
            + fullGraph.ownershipMode() + " submissions="
            + fullGraph.ownershipSubmissions() + " ready="
            + fullGraph.ownershipReady() + " armed="
            + fullGraph.ownershipFramesArmed() + " presented="
            + fullGraph.ownershipFramesPresented() + " reused="
            + fullGraph.ownershipFramesReused() + " suppressed="
            + fullGraph.ownershipCommandsSuppressed() + " ownershipFailed="
            + fullGraph.ownershipFailures() + " ownershipLast="
            + fullGraph.ownershipLastFailure() + " blockers="
            + fullGraph.blockerSummary() + " last="
            + fullGraph.lastFailure());
      }
    }
    return translationReady && graphReady && replayReady && pipelinesReady
        && fullGraphReady;
  }

  private static void requireShadowReplay(
      IrisTranslationCoordinator.ShadowReplayStatus status,
      IrisTranslationCoordinator.MetalPipelineCacheStatus pipelines,
      boolean expectMetal4, boolean requireGraphOwnership) {
    require(status.enabled(), "Iris native shadow replay is not enabled");
    require(status.drawsObserved() > 0,
        "Iris native shadow replay observed no sampled draws");
    require(status.drawsAttempted() == status.drawsSucceeded()
            + status.drawsUnsupported() + status.drawsFailed(),
        "Iris native shadow replay counters do not account for attempts");
    require(status.candidateCount() == status.drawsAttempted(),
        "Iris native shadow replay candidate count is inconsistent");
    require(status.candidateSetComplete(),
        "Iris native shadow replay exceeded its candidate bound");
    require(status.blockerSetComplete(),
        "Iris native shadow replay blocker diagnostics were truncated");
    require(status.blockerSetSha256().matches("[0-9a-f]{64}"),
        "Iris native shadow replay blocker digest is invalid");
    require((status.blockerCount() == 0)
            == status.blockerSummary().isEmpty(),
        "Iris native shadow replay blocker count and summary disagree");
    require(NativeBridge.nIsMetal4DrawPathActive()
            == requireGraphOwnership,
        requireGraphOwnership
            ? "Stage 9 ownership did not activate the visible MTL4 draw path"
            : "offscreen shadow replay activated the visible MTL4 draw path");
    if (!expectMetal4) {
      require(status.drawsAttempted() == 0
              && status.drawsSucceeded() == 0
              && status.drawsFailed() == 0
              && status.drawsBlocked() > 0
              && pipelines.nativeStatus().drawAttempts() == 0,
          "forced Metal 3 did not retain a draw-free replay fallback");
      return;
    }
    require(status.drawsSucceeded() > 0,
        "no captured Iris draw completed through offscreen MTL4 replay");
    require(status.drawsUnsupported() == 0,
        "offscreen MTL4 Iris replay retained unsupported draws: "
            + status.blockerSummary());
    require(requiredReplayPhaseCoverage(status),
        "offscreen MTL4 Iris replay missed a required phase: "
            + status.successfulPhaseSummary());
    require(status.drawsFailed() == 0,
        "offscreen MTL4 Iris replay reported failures");
    require(!status.lastColorHashUnsigned().equals("0")
            && status.lastWidth() > 0 && status.lastHeight() > 0,
        "offscreen MTL4 Iris replay produced no validated color hash");
    require(pipelines.nativeStatus().drawAttempts()
            >= status.drawsSucceeded(),
        "native MTL4 draw telemetry is below successful replay count");
  }

  private static void requireVisualParity(
      IrisTranslationCoordinator.VisualParityStatus status,
      boolean expectMetal4) {
    require(status.enabled(), "Iris/Metal visual parity is not enabled");
    require(status.captureScheduled() > 0,
        "visual parity scheduled no FINAL readbacks");
    require(status.captureSucceeded() > 0,
        "visual parity captured no OpenGL FINAL output");
    require(status.captureFailed() == 0 && status.captureDropped() == 0,
        "visual parity OpenGL capture failed or overflowed: "
            + status.captureLastFailure());
    require(status.blockerSetComplete(),
        "visual parity blocker diagnostics were truncated");
    require(status.blockerSetSha256().matches("[0-9a-f]{64}"),
        "visual parity blocker digest is invalid");
    if (!expectMetal4) {
      require(!status.validated() && status.framesCompared() == 0
              && status.samplesHandled()
                  >= IrisRenderGraphCapture.MAX_REPLAY_SAMPLES_PER_PHASE,
          "forced Metal 3 did not retain a bounded parity fallback");
      return;
    }
    require(status.validated(),
        "FINAL Iris/Metal visual parity did not validate: "
            + status.blockerSummary());
    require(status.framesCompared() >= status.minimumFrames()
            && status.framesPassed() == status.framesCompared()
            && status.framesFailed() == 0
            && status.consecutivePasses()
                >= status.requiredConsecutivePasses(),
        "FINAL Iris/Metal visual parity window is incomplete");
    require(status.missingOpenGlFrames() == 0
            && status.missingMetalFrames() == 0
            && status.dimensionMismatches() == 0
            && status.replayFailures() == 0
            && status.blockerCount() == 0,
        "FINAL Iris/Metal visual parity retained blockers: "
            + status.blockerSummary());
    require(status.orientation().equals("native-row-order"),
        "visual parity used an unexpected coordinate orientation");
  }

  private static void requireFinalCutover(
      IrisTranslationCoordinator.CutoverStatus status,
      boolean expectMetal4, boolean requireGraphOwnership) {
    require(status.enabled(), "Iris FINAL cutover is not enabled");
    require(status.failureReasonSetComplete(),
        "Iris FINAL cutover diagnostics were truncated");
    require(status.failureReasonSetSha256().matches("[0-9a-f]{64}"),
        "Iris FINAL cutover failure digest is invalid");
    require(status.bridge().equals("iosurface-gpu-handoff"),
        "unexpected Iris FINAL cutover bridge: " + status.bridge());
    if (!expectMetal4) {
      require(status.mode().equals("SHADOW")
              && status.presentations() == 0
              && status.openGlDrawsSuppressed() == 0
              && status.metalAttempts() == 0
              && status.gpuInputTextures() == 0
              && status.gpuInputBytes() == 0
              && status.cpuInputTextures() == 0
              && status.cpuInputBytes() == 0
              && status.gpuInputBuffers() == 0
              && status.gpuInputBufferBytes() == 0
              && status.cpuInputBuffers() == 0
              && status.cpuInputBufferBytes() == 0,
          "forced Metal 3 did not retain the OpenGL FINAL fallback");
      return;
    }
    if (requireGraphOwnership) {
      require(NativeBridge.nIsMetal4DrawPathActive(),
          "Stage 9 graph ownership never activated the native draw path");
      require(!status.frameFallback() && status.failures() == 0,
          "selective cutover reported a failure before Stage 9 ownership: "
              + status.lastFailure());
      return;
    }
    require(status.activeAndHealthy(),
        "Iris FINAL cutover is not active and healthy: "
            + status.lastFailure() + " / " + status.failureReasonSummary());
    require(status.presentations() >= 30,
        "Iris FINAL cutover did not replace enough real frames");
    require(status.gpuInputTextures() >= status.presentations() * 3
            && status.gpuInputBytes() > 0
            && status.cpuInputTextures() == 0
            && status.cpuInputBytes() == 0,
        "Iris FINAL cutover did not keep all sampled inputs on the GPU");
    require(status.gpuInputBuffers() >= status.presentations() * 2
            && status.gpuInputBufferBytes() > 0
            && status.cpuInputBuffers() == 0
            && status.cpuInputBufferBytes() == 0,
        "Iris FINAL cutover did not keep all geometry buffers resident: gpu="
            + status.gpuInputBuffers() + "/"
            + status.gpuInputBufferBytes() + "B cpu="
            + status.cpuInputBuffers() + "/"
            + status.cpuInputBufferBytes() + "B presentations="
            + status.presentations());
    require(status.drawsObserved() >= status.drawsEligible()
            && status.drawsEligible() == status.metalAttempts()
            && status.metalAttempts() == status.metalSucceeded()
            && status.metalSucceeded() == status.presentations()
            && status.presentations() == status.openGlDrawsSuppressed(),
        "Iris FINAL cutover counters do not account for every replacement");
    require(status.frameFallbacks() == 0 && status.lifecycleResets() == 0,
        "Iris FINAL cutover unexpectedly entered rollback during baseline QA");
  }

  private static void requireShadowPlanCapture(
      IrisTranslationCoordinator.ShadowPlanStatus status,
      IrisTranslationCoordinator.RenderGraphStatus graph) {
    require(status.plansObserved() > 0,
        "no transient Iris shadow execution plan was retained");
    require(status.plansObserved()
            == status.structurallyComplete() + status.blocked(),
        "shadow plan counters do not account for every plan");
    require(status.plansObserved() == graph.graphsSucceeded(),
        "shadow plan count does not match successful render graphs");
    require(status.executionSteps() >= status.plansObserved(),
        "shadow execution plan did not retain commands");
    require(status.blockerSetComplete(),
        "shadow blocker diagnostics exceeded their hard capacity");
    require(status.blockerSetSha256().matches("[0-9a-f]{64}"),
        "shadow blocker-set digest is invalid");
    require((status.blockerCount() == 0)
            == status.blockerSummary().isEmpty(),
        "shadow blocker count and summary disagree");
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

  private static LifecycleEvidence verifyStage9WindowLifecycle(
      ClientGameTestContext context) {
    int[] original = context.computeOnClient(client -> {
      Window window = client.getWindow();
      require(!window.isFullscreen(),
          "Stage 9 lifecycle must start in a window");
      require(window.getScreenWidth() > 0 && window.getScreenHeight() > 0
              && window.getWidth() > 0 && window.getHeight() > 0,
          "Stage 9 lifecycle started with invalid window dimensions");
      return new int[] {window.getScreenWidth(), window.getScreenHeight(),
          window.getWidth(), window.getHeight()};
    });
    int resizedWidth = original[0] == 960 && original[1] == 540 ? 800 : 960;
    int resizedHeight = original[0] == 960 && original[1] == 540 ? 450 : 540;
    IrisTranslationCoordinator.FullGraphStatus started =
        currentFullGraphStatus(context);
    require(started.performanceEligible() && started.ownershipFailures() == 0,
        "Stage 9 ownership was not healthy before lifecycle testing");
    long presentationStart = started.ownershipFramesPresented();
    long suppressionStart = started.ownershipCommandsSuppressed();
    long invalidationStart = started.ownershipFramesInvalidated();
    long failures = started.ownershipFailures();

    context.runOnClient(client ->
        client.getWindow().setWindowed(resizedWidth, resizedHeight));
    context.waitFor(client -> {
      Window window = client.getWindow();
      return !window.isFullscreen()
          && window.getScreenWidth() == resizedWidth
          && window.getScreenHeight() == resizedHeight
          && window.getWidth() > 0 && window.getHeight() > 0;
    }, WORLD_TIMEOUT_TICKS);
    IrisTranslationCoordinator.FullGraphStatus afterResize =
        waitForOwnershipAdvance(context, presentationStart, failures,
            "window resize");

    context.runOnClient(client -> {
      Window window = client.getWindow();
      GLFW.glfwSetWindowPos(window.handle(), 0, 0);
      window.toggleFullScreen();
      window.updateFullscreenIfChanged();
    });
    context.waitFor(client -> {
      Window window = client.getWindow();
      return window.isFullscreen()
          && window.getWidth() > 0 && window.getHeight() > 0;
    }, WORLD_TIMEOUT_TICKS);
    IrisTranslationCoordinator.FullGraphStatus afterFullscreen =
        waitForOwnershipAdvance(context,
            afterResize.ownershipFramesPresented(), failures, "fullscreen");

    context.runOnClient(client ->
        client.getWindow().setWindowed(original[0], original[1]));
    context.waitFor(client -> {
      Window window = client.getWindow();
      return !window.isFullscreen()
          && window.getScreenWidth() == original[0]
          && window.getScreenHeight() == original[1]
          && window.getWidth() == original[2]
          && window.getHeight() == original[3];
    }, WORLD_TIMEOUT_TICKS);
    IrisTranslationCoordinator.FullGraphStatus afterRestore =
        waitForOwnershipAdvance(context,
            afterFullscreen.ownershipFramesPresented(), failures,
            "windowed restore");

    boolean surfaceSuspended = context.computeOnClient(client -> {
      Window window = client.getWindow();
      GLFW.glfwHideWindow(window.handle());
      GLFW.glfwPollEvents();
      return GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_VISIBLE)
          == GLFW.GLFW_FALSE;
    });
    require(surfaceSuspended,
        "GLFW did not acknowledge the Stage 9 surface suspension");
    context.runOnClient(client -> {
      Window window = client.getWindow();
      GLFW.glfwShowWindow(window.handle());
      GLFW.glfwRestoreWindow(window.handle());
    });
    context.waitFor(client -> {
      Window window = client.getWindow();
      return GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_VISIBLE)
              == GLFW.GLFW_TRUE
          && !window.isIconified() && !window.isFullscreen()
          && window.getScreenWidth() == original[0]
          && window.getScreenHeight() == original[1]
          && window.getWidth() == original[2]
          && window.getHeight() == original[3];
    }, WORLD_TIMEOUT_TICKS);
    IrisTranslationCoordinator.FullGraphStatus completed =
        waitForOwnershipAdvance(context,
            afterRestore.ownershipFramesPresented(), failures,
            "surface suspend/restore");
    long presentationDelta =
        completed.ownershipFramesPresented() - presentationStart;
    long suppressionDelta =
        completed.ownershipCommandsSuppressed() - suppressionStart;
    long invalidationDelta =
        completed.ownershipFramesInvalidated() - invalidationStart;
    require(presentationDelta
            >= 4L * LIFECYCLE_PRESENTATIONS_PER_TRANSITION,
        "Stage 9 lifecycle did not present enough recovery frames");
    require(suppressionDelta > 0,
        "Stage 9 lifecycle stopped suppressing upstream OpenGL commands");
    require(completed.ownershipFailures() == failures,
        "Stage 9 ownership failures advanced during lifecycle testing");
    require(invalidationDelta >= 0
            && invalidationDelta <= MAXIMUM_LIFECYCLE_INVALIDATIONS,
        "Stage 9 lifecycle invalidated too many queued frames: "
            + invalidationDelta);
    System.out.println("METALRENDER_STAGE9_LIFECYCLE PASS"
        + " resize=true fullscreen=true restore=true"
        + " surfaceSuspendRestore=true"
        + " presentations=" + presentationDelta
        + " suppressed=" + suppressionDelta
        + " invalidated=" + invalidationDelta + " failures=0");
    return new LifecycleEvidence("PASS", true, true, true, true,
        original[0], original[1], original[2], original[3],
        resizedWidth, resizedHeight, presentationDelta, suppressionDelta,
        invalidationDelta, completed.ownershipFailures() - failures);
  }

  private static IrisTranslationCoordinator.FullGraphStatus
      waitForOwnershipAdvance(ClientGameTestContext context,
          long presentationBaseline, long failureBaseline, String stage) {
    context.waitFor(client -> {
      IrisTranslationCoordinator.FullGraphStatus status =
          IrisTranslationCoordinator.fullGraphStatus();
      require(status.ownershipFailures() == failureBaseline,
          "Stage 9 ownership failed during " + stage + ": "
              + status.ownershipLastFailure());
      return status.performanceEligible()
          && status.ownershipFramesPresented()
              >= presentationBaseline
                  + LIFECYCLE_PRESENTATIONS_PER_TRANSITION;
    }, WORLD_TIMEOUT_TICKS);
    return currentFullGraphStatus(context);
  }

  private static IrisTranslationCoordinator.FullGraphStatus
      currentFullGraphStatus(ClientGameTestContext context) {
    return context.computeOnClient(
        client -> IrisTranslationCoordinator.fullGraphStatus());
  }

  private static PerformanceEvidence captureStage9Performance(
      ClientGameTestContext context, String side, int requestedSamples,
      String exactSha, String shaderPack) {
    context.waitTicks(120);
    context.runOnClient(client -> IrisStage9PerformanceSampler.reset());
    context.waitFor(client -> {
      IrisStage9PerformanceSampler.Snapshot snapshot =
          IrisStage9PerformanceSampler.snapshot();
      require(snapshot.side().equals(side),
          "Stage 9 sampler reported the wrong side: " + snapshot.side());
      require(snapshot.instrumentationErrors() == 0,
          "Stage 9 timing instrumentation failed");
      require(snapshot.metalFeedbackErrors() == 0,
          "MTL4 commit feedback reported an error");
      require(snapshot.droppedGpuSamples() == 0,
          "Stage 9 GPU timing samples were dropped: "
              + snapshot.droppedGpuSamples());
      return snapshot.pairedSamples() >= requestedSamples;
    }, SHADER_TIMEOUT_TICKS);
    IrisStage9PerformanceSampler.Snapshot snapshot =
        context.computeOnClient(
            client -> IrisStage9PerformanceSampler.snapshot());
    require(snapshot.cpuNanos().length >= requestedSamples
            && snapshot.gpuNanos().length >= requestedSamples,
        "Stage 9 sampler did not retain the requested raw samples");
    long[] cpu = Arrays.copyOf(snapshot.cpuNanos(), requestedSamples);
    long[] gpu = Arrays.copyOf(snapshot.gpuNanos(), requestedSamples);
    String scenario = context.computeOnClient(client -> {
      Window window = client.getWindow();
      require(client.level != null
              && client.level.dimension().identifier().toString()
                  .equals("minecraft:overworld"),
          "performance capture left the fixed Overworld scene");
      return "schema=stage9-v1\n"
          + "minecraft=26.2\n"
          + "exactJarSha256=" + exactSha + "\n"
          + "shaderPack=" + shaderPack + "\n"
          + "dimension=minecraft:overworld\n"
          + "framebuffer=" + window.getWidth() + "x" + window.getHeight()
          + "\nwindow=" + window.getScreenWidth() + "x"
          + window.getScreenHeight() + "\n"
          + "renderDistance=8\nsimulationDistance=5\n"
          + "time=noon\nweather=clear\n"
          + "player=0,102,0\ncameraYawPitch=0,20\n";
    });
    long cpuStutters = countAtLeast(cpu, PERFORMANCE_STUTTER_NANOS);
    long gpuStutters = countAtLeast(gpu, PERFORMANCE_STUTTER_NANOS);
    FrameMetrics metrics = new FrameMetrics(requestedSamples,
        percentile(cpu, 0.50), percentile(cpu, 0.95),
        percentile(cpu, 0.99), percentile(gpu, 0.50),
        percentile(gpu, 0.95), percentile(gpu, 0.99),
        Math.max(cpuStutters, gpuStutters));
    PerformanceEvidence evidence = new PerformanceEvidence("PASS", side,
        scenario, sha256(scenario), cpu, gpu, metrics,
        snapshot.droppedGpuSamples(), snapshot.instrumentationErrors(),
        cpuStutters, gpuStutters, snapshot.metalFeedbackErrors());
    System.out.println("METALRENDER_STAGE9_PERFORMANCE PASS side=" + side
        + " samples=" + requestedSamples + " scenario="
        + evidence.scenarioSha256() + " cpuP50Ms="
        + nanosToMillis(metrics.cpuP50Nanos()) + " cpuP95Ms="
        + nanosToMillis(metrics.cpuP95Nanos()) + " gpuP50Ms="
        + nanosToMillis(metrics.gpuP50Nanos()) + " gpuP95Ms="
        + nanosToMillis(metrics.gpuP95Nanos()));
    return evidence;
  }

  private static long percentile(long[] values, double percentile) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    int index = (int) Math.ceil(percentile * sorted.length) - 1;
    return sorted[Math.max(0, index)];
  }

  private static long countAtLeast(long[] values, long threshold) {
    return Arrays.stream(values).filter(value -> value >= threshold).count();
  }

  private static double nanosToMillis(long nanos) {
    return nanos / 1_000_000.0;
  }

  private static void writeLifecycleEvidence(LifecycleEvidence evidence) {
    String json = String.format(Locale.ROOT, """
        {
          "schemaVersion": 1,
          "status": %s,
          "resizePassed": %s,
          "fullscreenPassed": %s,
          "windowedRestorePassed": %s,
          "surfaceSuspendRestorePassed": %s,
          "originalWindowWidth": %d,
          "originalWindowHeight": %d,
          "originalFramebufferWidth": %d,
          "originalFramebufferHeight": %d,
          "resizedWindowWidth": %d,
          "resizedWindowHeight": %d,
          "ownershipPresentationDelta": %d,
          "openGlSuppressionDelta": %d,
          "ownershipInvalidationDelta": %d,
          "ownershipFailureDelta": %d
        }
        """, quote(evidence.status()), evidence.resizePassed(),
        evidence.fullscreenPassed(), evidence.windowedRestorePassed(),
        evidence.surfaceSuspendRestorePassed(), evidence.originalWindowWidth(),
        evidence.originalWindowHeight(), evidence.originalFramebufferWidth(),
        evidence.originalFramebufferHeight(), evidence.resizedWindowWidth(),
        evidence.resizedWindowHeight(), evidence.ownershipPresentationDelta(),
        evidence.openGlSuppressionDelta(),
        evidence.ownershipInvalidationDelta(),
        evidence.ownershipFailureDelta());
    writeAtomicJson("metalrender.exactJar.lifecycleEvidencePath", json);
  }

  private static void writePerformanceEvidence(
      PerformanceEvidence evidence) {
    FrameMetrics metrics = evidence.metrics();
    String json = String.format(Locale.ROOT, """
        {
          "schemaVersion": 1,
          "status": %s,
          "side": %s,
          "scenarioSha256": %s,
          "scenarioDescriptor": %s,
          "samples": %d,
          "cpuNanos": %s,
          "gpuNanos": %s,
          "metrics": {
            "cpuP50Nanos": %d,
            "cpuP95Nanos": %d,
            "cpuP99Nanos": %d,
            "gpuP50Nanos": %d,
            "gpuP95Nanos": %d,
            "gpuP99Nanos": %d,
            "stutters": %d
          },
          "droppedGpuSamples": %d,
          "instrumentationErrors": %d,
          "cpuStutters": %d,
          "gpuStutters": %d,
          "metalFeedbackErrors": %d
        }
        """, quote(evidence.status()), quote(evidence.side()),
        quote(evidence.scenarioSha256()),
        quote(evidence.scenarioDescriptor()), evidence.cpuNanos().length,
        longArrayJson(evidence.cpuNanos()),
        longArrayJson(evidence.gpuNanos()), metrics.cpuP50Nanos(),
        metrics.cpuP95Nanos(), metrics.cpuP99Nanos(),
        metrics.gpuP50Nanos(), metrics.gpuP95Nanos(),
        metrics.gpuP99Nanos(), metrics.stutters(),
        evidence.droppedGpuSamples(), evidence.instrumentationErrors(),
        evidence.cpuStutters(), evidence.gpuStutters(),
        evidence.metalFeedbackErrors());
    writeAtomicJson("metalrender.exactJar.performanceEvidencePath", json);
  }

  private static String longArrayJson(long[] values) {
    StringBuilder result = new StringBuilder(values.length * 12 + 2);
    result.append('[');
    for (int index = 0; index < values.length; index++) {
      if (index != 0) {
        result.append(',');
      }
      result.append(values[index]);
    }
    return result.append(']').toString();
  }

  private static void writeAtomicJson(String pathProperty, String json) {
    Path result = Path.of(requiredProperty(pathProperty)).toAbsolutePath();
    Path temporary = result.resolveSibling(result.getFileName() + ".tmp");
    try {
      Files.createDirectories(result.getParent());
      Files.writeString(temporary, json, StandardCharsets.UTF_8);
      Files.move(temporary, result, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception error) {
      throw new AssertionError("could not write exact-JAR sidecar " + result,
          error);
    }
  }

  private record LifecycleEvidence(String status, boolean resizePassed,
                                   boolean fullscreenPassed,
                                   boolean windowedRestorePassed,
                                   boolean surfaceSuspendRestorePassed,
                                   int originalWindowWidth,
                                   int originalWindowHeight,
                                   int originalFramebufferWidth,
                                   int originalFramebufferHeight,
                                   int resizedWindowWidth,
                                   int resizedWindowHeight,
                                   long ownershipPresentationDelta,
                                   long openGlSuppressionDelta,
                                   long ownershipInvalidationDelta,
                                   long ownershipFailureDelta) {
    private static LifecycleEvidence notRequired() {
      return new LifecycleEvidence("NOT_REQUIRED", false, false, false,
          false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
  }

  private record FrameMetrics(int samples, long cpuP50Nanos,
                              long cpuP95Nanos, long cpuP99Nanos,
                              long gpuP50Nanos, long gpuP95Nanos,
                              long gpuP99Nanos, long stutters) {
  }

  private record PerformanceEvidence(String status, String side,
                                     String scenarioDescriptor,
                                     String scenarioSha256,
                                     long[] cpuNanos, long[] gpuNanos,
                                     FrameMetrics metrics,
                                     long droppedGpuSamples,
                                     long instrumentationErrors,
                                     long cpuStutters, long gpuStutters,
                                     long metalFeedbackErrors) {
    private PerformanceEvidence {
      cpuNanos = cpuNanos.clone();
      gpuNanos = gpuNanos.clone();
      require(cpuNanos.length == metrics.samples()
              && gpuNanos.length == metrics.samples(),
          "performance evidence arrays do not match their metrics");
    }

    @Override
    public long[] cpuNanos() {
      return cpuNanos.clone();
    }

    @Override
    public long[] gpuNanos() {
      return gpuNanos.clone();
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
      IrisTranslationCoordinator.RenderGraphStatus renderGraphStatus,
      IrisTranslationCoordinator.MetalGraphResourceStatus graphResourceStatus,
      IrisTranslationCoordinator.ShadowPlanStatus shadowPlanStatus,
      IrisTranslationCoordinator.FullGraphStatus fullGraphStatus,
      IrisTranslationCoordinator.MetalPipelineCacheStatus metalPipelineStatus,
      IrisTranslationCoordinator.ShadowReplayStatus shadowReplayStatus,
      IrisTranslationCoordinator.VisualParityStatus visualParityStatus,
      IrisTranslationCoordinator.CutoverStatus cutoverStatus,
      Path shadersCutover, CutoverVisualMetrics cutoverVisual,
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
    boolean graphOwnershipRequired = Boolean.getBoolean(
        "metalrender.exactJar.requireGraphOwnership");
    String renderGraphJson = String.format(Locale.ROOT, """
        {
          "complete": %s,
          "framesStarted": %d,
          "framesCompleted": %d,
          "framesRejected": %d,
          "framesPending": %d,
          "captureFrozen": %s,
          "graphsAttempted": %d,
          "graphsSucceeded": %d,
          "graphsUnsupported": %d,
          "graphsFailed": %d,
          "resourcesRepresented": %d,
          "nodesRepresented": %d,
          "edgesRepresented": %d,
          "barriersRepresented": %d,
          "clearsRepresented": %d,
          "transfersRepresented": %d,
          "pingPongResourcesRepresented": %d,
          "phaseSummary": %s,
          "graphIdentityCount": %d,
          "graphSetComplete": %s,
          "graphSetSha256": %s,
          "unsupportedReasonCount": %d,
          "unsupportedReasonSetComplete": %s,
          "unsupportedReasonSetSha256": %s,
          "lastFailure": %s,
          "offscreenGeneratedMslReplayObserved": %s,
          "visibleMetalExecution": %s
        }
        """,
        Boolean.toString(renderGraphStatus.complete()),
        renderGraphStatus.framesStarted(), renderGraphStatus.framesCompleted(),
        renderGraphStatus.framesRejected(), renderGraphStatus.framesPending(),
        Boolean.toString(renderGraphStatus.captureFrozen()),
        renderGraphStatus.graphsAttempted(), renderGraphStatus.graphsSucceeded(),
        renderGraphStatus.graphsUnsupported(), renderGraphStatus.graphsFailed(),
        renderGraphStatus.resourcesRepresented(),
        renderGraphStatus.nodesRepresented(), renderGraphStatus.edgesRepresented(),
        renderGraphStatus.barriersRepresented(),
        renderGraphStatus.clearsRepresented(),
        renderGraphStatus.transfersRepresented(),
        renderGraphStatus.pingPongResourcesRepresented(),
        quote(renderGraphStatus.phaseSummary()),
        renderGraphStatus.graphIdentityCount(),
        Boolean.toString(renderGraphStatus.graphSetComplete()),
        quote(renderGraphStatus.graphSetSha256()),
        renderGraphStatus.unsupportedReasonCount(),
        Boolean.toString(renderGraphStatus.unsupportedReasonSetComplete()),
        quote(renderGraphStatus.unsupportedReasonSetSha256()),
        quote(renderGraphStatus.lastFailure()),
        Boolean.toString(shadowReplayStatus.drawsSucceeded() > 0),
        Boolean.toString(cutoverStatus.presentations() > 0
            || fullGraphStatus.ownershipFramesPresented() > 0));
    String graphResourceJson = String.format(Locale.ROOT, """
        {
          "enabled": %s,
          "runtimeAvailable": %s,
          "complete": %s,
          "safeUnsupportedFallback": %s,
          "plansObserved": %d,
          "plansComplete": %d,
          "plansBlocked": %d,
          "allocationsRequested": %d,
          "nativeAttempts": %d,
          "nativeSucceeded": %d,
          "nativeFailed": %d,
          "nativeTextureCount": %d,
          "nativeTextureBytes": %d,
          "blockerCount": %d,
          "blockerSetComplete": %s,
          "blockerSetSha256": %s,
          "blockerSummary": %s,
          "storageMode": "private",
          "identity": "context-generation/gl-name/resource-generation"
        }
        """,
        Boolean.toString(graphResourceStatus.enabled()),
        Boolean.toString(graphResourceStatus.runtimeAvailable()),
        Boolean.toString(graphResourceStatus.complete()),
        Boolean.toString(graphResourceStatus.safeUnsupportedFallback()),
        graphResourceStatus.plansObserved(),
        graphResourceStatus.plansComplete(),
        graphResourceStatus.plansBlocked(),
        graphResourceStatus.allocationsRequested(),
        graphResourceStatus.nativeAttempts(),
        graphResourceStatus.nativeSucceeded(),
        graphResourceStatus.nativeFailed(),
        graphResourceStatus.nativeTextureCount(),
        graphResourceStatus.nativeTextureBytes(),
        graphResourceStatus.blockerCount(),
        Boolean.toString(graphResourceStatus.blockerSetComplete()),
        quote(graphResourceStatus.blockerSetSha256()),
        quote(graphResourceStatus.blockerSummary()));
    String fullGraphJson = String.format(Locale.ROOT, """
        {
          "enabled": %s,
          "runtimeAvailable": %s,
          "captureActive": %s,
          "captureRequests": %d,
          "framesObserved": %d,
          "framesPlanned": %d,
          "framesPending": %d,
          "framesAttempted": %d,
          "framesSucceeded": %d,
          "framesUnsupported": %d,
          "framesFailed": %d,
          "operations": %d,
          "draws": %d,
          "clears": %d,
          "transfers": %d,
          "barriers": %d,
          "capturedBytes": %d,
          "initializedResources": %d,
          "lastOutputHashUnsigned": %s,
          "minimumSuccessFrames": %d,
          "validated": %s,
          "ownershipMode": %s,
          "performanceEligible": %s,
          "ownershipOptedIn": %s,
          "ownershipSubmissions": %d,
          "ownershipReady": %d,
          "ownershipFramesArmed": %d,
          "ownershipFramesPresented": %d,
          "ownershipFramesReused": %d,
          "ownershipFramesInvalidated": %d,
          "ownershipCommandsSuppressed": %d,
          "ownershipFailures": %d,
          "ownershipLastFailure": %s,
          "blockerCount": %d,
          "blockerSetComplete": %s,
          "blockerSetSha256": %s,
          "blockerSummary": %s,
          "lastFailure": %s
        }
        """,
        Boolean.toString(fullGraphStatus.enabled()),
        Boolean.toString(fullGraphStatus.runtimeAvailable()),
        Boolean.toString(fullGraphStatus.captureActive()),
        fullGraphStatus.captureRequests(), fullGraphStatus.framesObserved(),
        fullGraphStatus.framesPlanned(), fullGraphStatus.framesPending(),
        fullGraphStatus.framesAttempted(), fullGraphStatus.framesSucceeded(),
        fullGraphStatus.framesUnsupported(), fullGraphStatus.framesFailed(),
        fullGraphStatus.operations(), fullGraphStatus.draws(),
        fullGraphStatus.clears(), fullGraphStatus.transfers(),
        fullGraphStatus.barriers(), fullGraphStatus.capturedBytes(),
        fullGraphStatus.initializedResources(),
        quote(fullGraphStatus.lastOutputHashUnsigned()),
        fullGraphStatus.minimumSuccessFrames(),
        Boolean.toString(fullGraphStatus.validated()),
        quote(fullGraphStatus.ownershipMode()),
        Boolean.toString(fullGraphStatus.performanceEligible()),
        Boolean.toString(fullGraphStatus.ownershipOptedIn()),
        fullGraphStatus.ownershipSubmissions(),
        fullGraphStatus.ownershipReady(),
        fullGraphStatus.ownershipFramesArmed(),
        fullGraphStatus.ownershipFramesPresented(),
        fullGraphStatus.ownershipFramesReused(),
        fullGraphStatus.ownershipFramesInvalidated(),
        fullGraphStatus.ownershipCommandsSuppressed(),
        fullGraphStatus.ownershipFailures(),
        quote(fullGraphStatus.ownershipLastFailure()),
        fullGraphStatus.blockerCount(),
        Boolean.toString(fullGraphStatus.blockerSetComplete()),
        quote(fullGraphStatus.blockerSetSha256()),
        quote(fullGraphStatus.blockerSummary()),
        quote(fullGraphStatus.lastFailure()));
    String shadowPlanJson = String.format(Locale.ROOT, """
        {
          "complete": %s,
          "plansObserved": %d,
          "structurallyComplete": %d,
          "blocked": %d,
          "executionSteps": %d,
          "blockerCount": %d,
          "blockerSetComplete": %s,
          "blockerSetSha256": %s,
          "blockerSummary": %s,
          "offscreenIrisReplayExecuted": %s,
          "openGlDrawsSuppressed": %d
        }
        """,
        Boolean.toString(shadowPlanStatus.complete()),
        shadowPlanStatus.plansObserved(),
        shadowPlanStatus.structurallyComplete(), shadowPlanStatus.blocked(),
        shadowPlanStatus.executionSteps(), shadowPlanStatus.blockerCount(),
        Boolean.toString(shadowPlanStatus.blockerSetComplete()),
        quote(shadowPlanStatus.blockerSetSha256()),
        quote(shadowPlanStatus.blockerSummary()),
        Boolean.toString(shadowReplayStatus.drawsSucceeded() > 0),
        graphOwnershipRequired
            ? fullGraphStatus.ownershipCommandsSuppressed()
            : cutoverStatus.openGlDrawsSuppressed());
    String shadowReplayJson = String.format(Locale.ROOT, """
        {
          "enabled": %s,
          "executionComplete": %s,
          "drawsObserved": %d,
          "drawsReady": %d,
          "drawsAttempted": %d,
          "drawsSucceeded": %d,
          "drawsUnsupported": %d,
          "drawsFailed": %d,
          "drawsBlocked": %d,
          "candidateCount": %d,
          "candidateSetComplete": %s,
          "successfulPhaseCount": %d,
          "successfulPhaseSummary": %s,
          "lastColorHashUnsigned": %s,
          "lastWidth": %d,
          "lastHeight": %d,
          "blockerCount": %d,
          "blockerSetComplete": %s,
          "blockerSetSha256": %s,
          "blockerSummary": %s,
          "offscreenOnly": true,
          "openGlDrawsSuppressed": 0,
          "visualParityValidated": %s
        }
        """,
        Boolean.toString(shadowReplayStatus.enabled()),
        Boolean.toString(shadowReplayStatus.executionComplete()),
        shadowReplayStatus.drawsObserved(), shadowReplayStatus.drawsReady(),
        shadowReplayStatus.drawsAttempted(),
        shadowReplayStatus.drawsSucceeded(),
        shadowReplayStatus.drawsUnsupported(),
        shadowReplayStatus.drawsFailed(), shadowReplayStatus.drawsBlocked(),
        shadowReplayStatus.candidateCount(),
        Boolean.toString(shadowReplayStatus.candidateSetComplete()),
        shadowReplayStatus.successfulPhaseCount(),
        quote(shadowReplayStatus.successfulPhaseSummary()),
        quote(shadowReplayStatus.lastColorHashUnsigned()),
        shadowReplayStatus.lastWidth(), shadowReplayStatus.lastHeight(),
        shadowReplayStatus.blockerCount(),
        Boolean.toString(shadowReplayStatus.blockerSetComplete()),
        quote(shadowReplayStatus.blockerSetSha256()),
        quote(shadowReplayStatus.blockerSummary()),
        Boolean.toString(visualParityStatus.validated()));
    String visualParityJson = String.format(Locale.ROOT, """
        {
          "enabled": %s,
          "validated": %s,
          "channelTolerance": %d,
          "maxDifferentPixelRatio": %.8f,
          "maxRootMeanSquareError": %.8f,
          "minimumFrames": %d,
          "requiredConsecutivePasses": %d,
          "captureScheduled": %d,
          "captureSucceeded": %d,
          "captureFailed": %d,
          "captureDropped": %d,
          "capturePending": %d,
          "captureLastFailure": %s,
          "samplesHandled": %d,
          "missingOpenGlFrames": %d,
          "missingMetalFrames": %d,
          "dimensionMismatches": %d,
          "replayFailures": %d,
          "framesCompared": %d,
          "framesPassed": %d,
          "framesFailed": %d,
          "consecutivePasses": %d,
          "worstDifferentPixelRatio": %.8f,
          "worstRootMeanSquareError": %.8f,
          "worstChannelDelta": %d,
          "orientation": %s,
          "blockerCount": %d,
          "blockerSetComplete": %s,
          "blockerSetSha256": %s,
          "blockerSummary": %s,
          "comparisonTarget": "iris-opengl-final-vs-offscreen-metal-final"
        }
        """,
        Boolean.toString(visualParityStatus.enabled()),
        Boolean.toString(visualParityStatus.validated()),
        visualParityStatus.channelTolerance(),
        visualParityStatus.maxDifferentPixelRatio(),
        visualParityStatus.maxRootMeanSquareError(),
        visualParityStatus.minimumFrames(),
        visualParityStatus.requiredConsecutivePasses(),
        visualParityStatus.captureScheduled(),
        visualParityStatus.captureSucceeded(),
        visualParityStatus.captureFailed(), visualParityStatus.captureDropped(),
        visualParityStatus.capturePending(),
        quote(visualParityStatus.captureLastFailure()),
        visualParityStatus.samplesHandled(),
        visualParityStatus.missingOpenGlFrames(),
        visualParityStatus.missingMetalFrames(),
        visualParityStatus.dimensionMismatches(),
        visualParityStatus.replayFailures(),
        visualParityStatus.framesCompared(), visualParityStatus.framesPassed(),
        visualParityStatus.framesFailed(),
        visualParityStatus.consecutivePasses(),
        visualParityStatus.worstDifferentPixelRatio(),
        visualParityStatus.worstRootMeanSquareError(),
        visualParityStatus.worstChannelDelta(),
        quote(visualParityStatus.orientation()),
        visualParityStatus.blockerCount(),
        Boolean.toString(visualParityStatus.blockerSetComplete()),
        quote(visualParityStatus.blockerSetSha256()),
        quote(visualParityStatus.blockerSummary()));
    String metalPipelineJson = String.format(Locale.ROOT, """
        {
          "activeGatePassed": %s,
          "complete": %s,
          "safeUnsupportedFallback": %s,
          "enabled": %s,
          "readiness": %s,
          "deviceCompilerSha256": %s,
          "candidatesObserved": %d,
          "executionBlockedCandidates": %d,
          "pending": %d,
          "attempted": %d,
          "compiled": %d,
          "cacheHits": %d,
          "newVariantsCompiled": %d,
          "knownArchiveMisses": %d,
          "unsupported": %d,
          "failed": %d,
          "queueRejected": %d,
          "archiveFlushed": %s,
          "archiveFlushFailures": %d,
          "pipelineIdentityCount": %d,
          "pipelineIdentitySetComplete": %s,
          "pipelineSetSha256": %s,
          "failureReasonCount": %d,
          "failureReasonSetComplete": %s,
          "failureReasonSetSha256": %s,
          "lastFailure": %s,
          "nativeAttempts": %d,
          "nativeCompiled": %d,
          "nativeCacheHits": %d,
          "nativeFailures": %d,
          "nativeStaleArchivesRecovered": %d,
          "nativeLivePipelines": %d,
          "nativeDrawAttempts": %d,
          "offscreenGeneratedMslReplayObserved": %s,
          "visibleMetalExecution": %s
        }
        """,
        Boolean.toString(backendExpectation.equals("metal4")
            ? metalPipelineStatus.complete()
            : metalPipelineStatus.safeUnsupportedFallback()),
        Boolean.toString(metalPipelineStatus.complete()),
        Boolean.toString(metalPipelineStatus.safeUnsupportedFallback()),
        Boolean.toString(metalPipelineStatus.enabled()),
        quote(metalPipelineStatus.readiness()),
        quote(metalPipelineStatus.deviceCompilerSha256()),
        metalPipelineStatus.candidatesObserved(),
        metalPipelineStatus.executionBlockedCandidates(),
        metalPipelineStatus.pending(), metalPipelineStatus.attempted(),
        metalPipelineStatus.compiled(), metalPipelineStatus.cacheHits(),
        metalPipelineStatus.newVariantsCompiled(),
        metalPipelineStatus.knownArchiveMisses(),
        metalPipelineStatus.unsupported(), metalPipelineStatus.failed(),
        metalPipelineStatus.queueRejected(),
        Boolean.toString(metalPipelineStatus.archiveFlushed()),
        metalPipelineStatus.archiveFlushFailures(),
        metalPipelineStatus.pipelineIdentityCount(),
        Boolean.toString(metalPipelineStatus.pipelineIdentitySetComplete()),
        quote(metalPipelineStatus.pipelineSetSha256()),
        metalPipelineStatus.failureReasonCount(),
        Boolean.toString(metalPipelineStatus.failureReasonSetComplete()),
        quote(metalPipelineStatus.failureReasonSetSha256()),
        quote(metalPipelineStatus.lastFailure()),
        metalPipelineStatus.nativeStatus().attempts(),
        metalPipelineStatus.nativeStatus().compiled(),
        metalPipelineStatus.nativeStatus().cacheHits(),
        metalPipelineStatus.nativeStatus().failures(),
        metalPipelineStatus.nativeStatus().staleArchivesRecovered(),
        metalPipelineStatus.nativeStatus().livePipelines(),
        metalPipelineStatus.nativeStatus().drawAttempts(),
        Boolean.toString(shadowReplayStatus.drawsSucceeded() > 0),
        Boolean.toString(cutoverStatus.presentations() > 0
            || fullGraphStatus.ownershipFramesPresented() > 0));
    String cutoverJson = String.format(Locale.ROOT, """
        {
          "enabled": %s,
          "active": %s,
          "mode": %s,
          "currentFrame": %d,
          "contextGeneration": %d,
          "frameFallback": %s,
          "drawsObserved": %d,
          "drawsEligible": %d,
          "metalAttempts": %d,
          "metalSucceeded": %d,
          "presentations": %d,
          "openGlDrawsSuppressed": %d,
          "frameFallbacks": %d,
          "lifecycleResets": %d,
          "failures": %d,
          "bridge": %s,
          "performanceEligible": false,
          "gpuInputTextures": %d,
          "gpuInputBytes": %d,
          "cpuInputTextures": %d,
          "cpuInputBytes": %d,
          "gpuInputBuffers": %d,
          "gpuInputBufferBytes": %d,
          "cpuInputBuffers": %d,
          "cpuInputBufferBytes": %d,
          "pipelineLookupCount": %d,
          "failureReasonCount": %d,
          "failureReasonSetComplete": %s,
          "failureReasonSetSha256": %s,
          "failureReasonSummary": %s,
          "lastFailure": %s,
          "screenshotCaptured": %s,
          "screenshotSampledUniqueColors": %d,
          "screenshotLuminanceStdDev": %.8f,
          "screenshotReferenceMad": %.8f,
          "screenshotPath": %s
        }
        """,
        Boolean.toString(cutoverStatus.enabled()),
        Boolean.toString(cutoverStatus.activeAndHealthy()),
        quote(cutoverStatus.mode()), cutoverStatus.currentFrame(),
        cutoverStatus.contextGeneration(),
        Boolean.toString(cutoverStatus.frameFallback()),
        cutoverStatus.drawsObserved(), cutoverStatus.drawsEligible(),
        cutoverStatus.metalAttempts(), cutoverStatus.metalSucceeded(),
        cutoverStatus.presentations(),
        cutoverStatus.openGlDrawsSuppressed(),
        cutoverStatus.frameFallbacks(), cutoverStatus.lifecycleResets(),
        cutoverStatus.failures(), quote(cutoverStatus.bridge()),
        cutoverStatus.gpuInputTextures(), cutoverStatus.gpuInputBytes(),
        cutoverStatus.cpuInputTextures(), cutoverStatus.cpuInputBytes(),
        cutoverStatus.gpuInputBuffers(),
        cutoverStatus.gpuInputBufferBytes(),
        cutoverStatus.cpuInputBuffers(),
        cutoverStatus.cpuInputBufferBytes(),
        cutoverStatus.pipelineLookupCount(),
        cutoverStatus.failureReasonCount(),
        Boolean.toString(cutoverStatus.failureReasonSetComplete()),
        quote(cutoverStatus.failureReasonSetSha256()),
        quote(cutoverStatus.failureReasonSummary()),
        quote(cutoverStatus.lastFailure()),
        Boolean.toString(cutoverVisual.screenshotCaptured()),
        cutoverVisual.sampledUniqueColors(),
        cutoverVisual.luminanceStdDev(), cutoverVisual.referenceMad(),
        quote(shadersCutover.toAbsolutePath().toString()));
    String json = String.format(Locale.ROOT, """
        {
          "schemaVersion": 14,
          "status": "PASS",
          "environment": "production-fabric",
          "javaMajor": 25,
          "metalrenderVersion": %s,
          "exactJarPath": %s,
          "exactJarSha256": %s,
          "cacheExpectation": %s,
          "backendExpectation": %s,
          "graphOwnershipRequired": %s,
          "backendMode": %s,
          "metal4Active": %s,
          "metal4DrawPathActive": %s,
          "sodiumLoaded": true,
          "irisLoaded": true,
          "irisRuntimeOwnership": {
            "evidenceKind": "indirect-runtime-ownership-check",
            "assertedDrawBackend": %s,
            "measuredAtRuntime": true,
            "passed": true
          },
          "generatedMslExecutionBoundary": {
            "runtimeTelemetryAvailable": true,
            "assertedStaticBoundary": %s,
            "nativeDrawAttempts": %d,
            "visibleOpenGlDrawsSuppressed": %d
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
            "pipelineStatus": %s
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
            "pipelineStatus": %s,
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
          "irisRenderGraph": %s,
          "irisMetalGraphResources": %s,
          "irisMetalFullGraph": %s,
          "irisShadowExecutionPlan": %s,
          "irisMetalShadowReplay": %s,
          "irisMetalVisualParity": %s,
          "irisMetalFinalCutover": %s,
          "irisMetalPipelineCache": %s,
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
            "shadersReenabled": %s,
            "metalFinalCutover": %s
          }
        }
        """,
        quote(version),
        quote(exactJar.toString()),
        quote(exactSha),
        quote(cacheExpectation),
        quote(backendExpectation),
        Boolean.toString(graphOwnershipRequired),
        quote(NativeBridge.nGetBackendMode()),
        Boolean.toString(NativeBridge.nIsMetal4Active()),
        Boolean.toString(NativeBridge.nIsMetal4DrawPathActive()),
        quote(graphOwnershipRequired
            ? "METAL4_FULL_GRAPH_OWNERSHIP"
            : cutoverStatus.presentations() > 0
                ? "METAL4_FINAL_CUTOVER_WITH_OPENGL_FALLBACK" : "OPENGL"),
        quote(graphOwnershipRequired
            ? "full-graph-metal-ownership"
            : cutoverStatus.presentations() > 0
                ? "selective-final-cutover"
                : "offscreen-shadow-replay-only"),
        metalPipelineStatus.nativeStatus().drawAttempts(),
        graphOwnershipRequired
            ? fullGraphStatus.ownershipCommandsSuppressed()
            : cutoverStatus.openGlDrawsSuppressed(),
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
        quote(backendExpectation.equals("metal4")
            ? "cached" : "unsupported-safe-fallback"),
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
        quote(backendExpectation.equals("metal4")
            ? "cached" : "unsupported-safe-fallback"),
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
        renderGraphJson,
        graphResourceJson,
        fullGraphJson,
        shadowPlanJson,
        shadowReplayJson,
        visualParityJson,
        cutoverJson,
        metalPipelineJson,
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
        quote(shadersReenabled.toAbsolutePath().toString()),
        quote(shadersCutover.toAbsolutePath().toString()));
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

  private static CutoverVisualMetrics validateCutoverScreenshot(
      Path irisReference, Path cutover, boolean expectMetal4) {
    try {
      BufferedImage reference = ImageIO.read(irisReference.toFile());
      BufferedImage candidate = ImageIO.read(cutover.toFile());
      require(reference != null && candidate != null,
          "cutover screenshot is not a decodable PNG image");
      require(reference.getWidth() == candidate.getWidth()
              && reference.getHeight() == candidate.getHeight(),
          "cutover screenshot dimensions differ from Iris reference");
      SampleStats stats = sampleStats(candidate);
      require(stats.uniqueColors() >= 64
              && stats.luminanceStdDev() >= 0.02,
          "cutover screenshot is visually uniform or corrupted");
      double difference = meanAbsoluteDifference(reference, candidate);
      if (expectMetal4) {
        // The paired per-draw parity gate is authoritative. This looser whole-
        // frame check catches presentation flips, channel swaps and stale
        // frames while tolerating normal animation between screenshots.
        require(difference < 0.20,
            "visible Metal FINAL cutover diverged from the Iris scene");
      } else {
        require(difference == 0.0,
            "Metal 3 fallback unexpectedly changed its reference image");
      }
      return new CutoverVisualMetrics(stats.uniqueColors(),
          stats.luminanceStdDev(), difference, expectMetal4);
    } catch (AssertionError error) {
      throw error;
    } catch (Exception error) {
      throw new AssertionError("could not validate cutover screenshot", error);
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

  private record CutoverVisualMetrics(int sampledUniqueColors,
                                      double luminanceStdDev,
                                      double referenceMad,
                                      boolean screenshotCaptured) {
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

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new AssertionError("could not hash Stage 9 scenario", error);
    }
  }

  private static int integerProperty(String name, int fallback) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      throw new AssertionError("invalid integer system property " + name,
          error);
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

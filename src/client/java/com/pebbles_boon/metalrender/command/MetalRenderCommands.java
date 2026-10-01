package com.pebbles_boon.metalrender.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.compat.IrisCompatibility;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Minecraft;
import com.mojang.brigadier.CommandDispatcher;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

public final class MetalRenderCommands {

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public static void register() {
        // NeoForge owns the client command dispatcher; registration is event-driven.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
            dispatcher.register(commandTree("complemetal"));
            dispatcher.register(commandTree("cm"));
            dispatcher.register(commandTree("metalrender"));
            dispatcher.register(commandTree("mr"));
        });
        MetalLogger.info("Complemetal client commands registered");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> commandTree(String root) {
        return literal(root)
                            .then(literal("help").executes(ctx -> {
                                sendHelp(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("status").executes(ctx -> {
                                sendStatus(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("cache")
                                    .then(literal("clear")
                                      .requires(source -> true)
                                      .executes(ctx -> {
                                        cacheClear(ctx.getSource());
                                        return 1;
                                    })))

                            .then(literal("reload")
                              .requires(source -> true)
                              .executes(ctx -> {
                                reloadWorld(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("restart")
                              .requires(source -> true)
                              .executes(ctx -> {
                                restart(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("config")
                                    .then(literal("open").executes(ctx -> {
                                        openConfigScreen(ctx.getSource());
                                        return 1;
                                    }))
                                    .then(literal("save").executes(ctx -> {
                                        MetalRenderConfig cfg = MetalRenderClient.getConfig();
                                        if (cfg != null)
                                            cfg.save();
                                        msg(ctx.getSource(), "§aComplemetal config saved");
                                        return 1;
                                    }))
                                    .then(literal("reload")
                                      .requires(source -> true)
                                      .executes(ctx -> {
                                        boolean ok = MetalRenderClient.reloadConfig();
                                        msg(ctx.getSource(), ok
                                            ? "§aComplemetal config reloaded"
                                            : "§cComplemetal config reload failed; see log");
                                        return 1;
                                    }))
                                    .then(literal("reset")
                                      .requires(source -> true)
                                      .executes(ctx -> {
                                        resetConfig(ctx.getSource());
                                        return 1;
                                    })))

                            .then(literal("performance")
                                    .then(literal("reset")
                                      .requires(source -> true)
                                      .executes(ctx -> {
                                        MetalRenderConfig.setResolutionScale(1.0f);
                                        MetalRenderClient.requestDeferredApply(false, false, true);
                                        msg(ctx.getSource(),
                                                "§ePerformance scaling reset");
                                        return 1;
                                    })))

                            .then(literal("profile").executes(ctx -> {
                                com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance().toggleVisible();
                                boolean nowVisible = com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance().isVisible();
                                msg(ctx.getSource(), nowVisible
                                    ? "§aComplemetal profiler enabled"
                                    : "§eComplemetal profiler disabled");
                                return 1;
                            }))

                            .executes(ctx -> {
                                sendHelp(ctx.getSource());
                                return 1;
                            });
    }

    private static void msg(CommandSourceStack src, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(text));
        }
    }

    private static void sendHelp(CommandSourceStack src) {
        msg(src, "§6§l--- Complemetal Commands ---");
        msg(src, "§e/complemetal status §7- Show renderer and backend status");
        msg(src, "§e/complemetal cache clear §7- Clear generated terrain meshes");
        msg(src, "§e/complemetal reload §7- Rebuild level render data");
        msg(src, "§e/complemetal restart §7- Restart the Metal renderer session");
        msg(src, "§e/complemetal config open|save|reload|reset §7- Configuration");
        msg(src, "§e/complemetal performance reset §7- Reset dynamic scaling");
        msg(src, "§e/complemetal profile §7- Toggle profiler overlay");
        msg(src, "§7Aliases: §e/cm, /metalrender, /mr");
    }

    private static void openConfigScreen(CommandSourceStack src) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                msg(src, "§cMinecraft client is unavailable");
                return;
            }
            MetalRenderClient.openSettingsScreen(mc);
            msg(src, "§aOpened Complemetal settings");
        } catch (Exception e) {
            msg(src, "§cfailed to open config screen: " + e.getMessage());
        }
    }

    private static void sendStatus(CommandSourceStack src) {
        boolean available = MetalRenderClient.isMetalAvailable();
        MetalRenderConfig cfg = MetalRenderClient.getConfig();
        boolean enabled = cfg != null && cfg.enableMetalRendering;

        msg(src, "§6§l--- Complemetal Status ---");
        msg(src, "§7Enabled: " + (enabled ? "§aYes" : "§cNo"));
        msg(src, "§7Hardware: "
                + (available ? "§a" + MetalHardwareChecker.getDeviceName() : "§cUnavailable"));
        msg(src, "§7Resolution scale: §f" + String.format("%.2fx", MetalRenderConfig.resolutionScale()));
        msg(src, "§7Metal frame target: §f"
                + MetalRenderClient.effectiveTargetFrameRate() + " FPS"
                + (cfg != null && cfg.autoTargetFrameRate
                    ? " §7(display/cap)" : " §7(manual)"));
        msg(src, "§7Terrain replacement: "
                + (cfg != null && cfg.enableFastTerrainReplacement
                    ? "§eExperimental fast" : "§aSafe alpha overlay"));
        msg(src, "§7Iris: §f" + IrisCompatibility.statusDescription());
        msg(src, "§7Successful Metal presentations: §f"
                + MetalRenderHookState.successfulPresentationCount());
        IrisTranslationCoordinator.Status irisTranslation =
                IrisTranslationCoordinator.status();
        msg(src, "§7Iris GLSL translation: "
                + (irisTranslation.running()
                    ? "§eExperimental §7(attempted=" + irisTranslation.attempted()
                        + ", translated=" + irisTranslation.translated()
                        + ", cacheHits=" + irisTranslation.cacheHits()
                        + ", failed=" + irisTranslation.failed()
                        + ", rejected=" + irisTranslation.rejected()
                        + ", captureFailures="
                        + irisTranslation.captureFailures() + ")"
                    : irisTranslation.enabled()
                        ? "§eEnabled, worker unavailable"
                        : "§7Disabled"));
        if (!irisTranslation.translationFailureReasonSummary().isEmpty()) {
            msg(src, "§7Iris translation failure reasons: §f"
                    + irisTranslation.translationFailureReasonSummary()
                    + " §7(count="
                    + irisTranslation.translationFailureReasonCount()
                    + ", "
                    + (irisTranslation.translationFailureReasonSetComplete()
                        ? "complete" : "truncated")
                    + ")");
        }
        if (!com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture
                .captureFailureReasonSummary().isEmpty()) {
            msg(src, "§7Iris capture failure reasons: §f"
                    + com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture
                        .captureFailureReasonSummary());
        }
        if (irisTranslation.libraryValidationEnabled()) {
            msg(src, "§7Iris MSL library validation: "
                    + (irisTranslation.libraryValidationComplete()
                        ? "§aComplete"
                        : irisTranslation.libraryValidationReady()
                            ? "§eRunning"
                            : "§eDeferred")
                    + " §7(programs="
                    + irisTranslation.libraryProgramsAttempted() + "/"
                    + irisTranslation.libraryProgramsSucceeded() + "/"
                    + irisTranslation.libraryProgramsUnsupported() + "/"
                    + irisTranslation.libraryProgramsFailed()
                    + ", stages="
                    + irisTranslation.libraryStagesAttempted() + "/"
                    + irisTranslation.libraryStagesSucceeded() + "/"
                    + irisTranslation.libraryStagesUnsupported() + "/"
                    + irisTranslation.libraryStagesFailed()
                    + ", pending="
                    + irisTranslation.libraryStagesPending()
                    + ", inFlight="
                    + irisTranslation.libraryStagesInFlight()
                    + ", live="
                    + (irisTranslation.libraryLiveLibraries() < 0
                        ? "unavailable"
                        : irisTranslation.libraryLiveLibraries())
                    + ")");
            msg(src, "§7Iris compiled artifact digest: §f"
                    + irisTranslation.compiledArtifactSetSha256()
                    + " §7("
                    + (irisTranslation.compiledArtifactSetComplete()
                        ? "complete" : "incomplete")
                    + ")");
        } else {
            msg(src, "§7Iris MSL library validation: §7Disabled");
        }
        msg(src, "§7Iris pipeline-state capture: "
                + (irisTranslation.pipelineStateCaptureComplete()
                    ? "§aComplete" : "§eCollecting/fallback")
                + " §7(draws=" + irisTranslation.pipelineDrawsObserved()
                + ", dispatches="
                + irisTranslation.pipelineDispatchesObserved()
                + ", variants="
                + irisTranslation.pipelineVariantsAccepted()
                + ", mapped="
                + irisTranslation.pipelineStatesSucceeded()
                + ", unsupported="
                + irisTranslation.pipelineStatesUnsupported()
                + ", incomplete="
                + irisTranslation.pipelineIncompleteVariants()
                + ", pending="
                + irisTranslation.pipelineStatesPending() + ")");
        msg(src, "§7Iris pipeline-state digest: §f"
                + irisTranslation.pipelineStateSetSha256() + " §7("
                + (irisTranslation.pipelineStateSetComplete()
                    ? "complete" : "incomplete") + ")");
        msg(src, "§7Iris resource reflection: "
                + (irisTranslation.resourceReflectionComplete()
                    ? "§aComplete" : "§eCollecting/fallback")
                + " §7(programs="
                + irisTranslation.resourceProgramsSucceeded() + "/"
                + irisTranslation.resourceProgramsAttempted()
                + ", stages="
                + irisTranslation.resourceStagesReflected()
                + ", bindings="
                + irisTranslation.resourceBindingsReflected()
                + ", unsupported="
                + irisTranslation.resourceProgramsUnsupported() + ")");
        msg(src, "§7Iris runtime resource parity: "
                + (irisTranslation.resourceBindingCaptureComplete()
                    ? "§aComplete" : "§eCollecting/fallback")
                + " §7(variants="
                + irisTranslation.resourceBindingVariantsSucceeded() + "/"
                + irisTranslation.resourceBindingVariantsAttempted()
                + ", matched="
                + irisTranslation.resourceBindingsMatched()
                + ", incomplete="
                + irisTranslation.resourceBindingVariantsIncomplete()
                + ")");
        msg(src, "§7Iris resource-layout digest: §f"
                + irisTranslation.resourceLayoutSetSha256() + " §7("
                + (irisTranslation.resourceLayoutSetComplete()
                    ? "complete" : "incomplete") + ")");
        IrisTranslationCoordinator.RenderGraphStatus graph =
                IrisTranslationCoordinator.renderGraphStatus();
        msg(src, "§7Iris render graph: "
                + (graph.complete() ? "§aComplete" : "§eCollecting/fallback")
                + " §7(graphs=" + graph.graphsSucceeded() + "/"
                + graph.graphsAttempted() + ", nodes="
                + graph.nodesRepresented() + ", edges="
                + graph.edgesRepresented() + ", barriers="
                + graph.barriersRepresented() + ", clears="
                + graph.clearsRepresented() + ", transfers="
                + graph.transfersRepresented() + ", pingPong="
                + graph.pingPongResourcesRepresented() + ")");
        msg(src, "§7Iris render-graph phases: §f" + graph.phaseSummary()
                + " §7(digest=" + graph.graphSetSha256() + ")");
        IrisTranslationCoordinator.MetalGraphResourceStatus graphResources =
                IrisTranslationCoordinator.metalGraphResourceStatus();
        msg(src, "§7Iris Metal graph attachments: "
                + (graphResources.complete() ? "§aPrivate/resident"
                    : graphResources.safeUnsupportedFallback()
                        ? "§7Metal 3 safe fallback"
                        : graphResources.enabled()
                            ? "§eCollecting/fallback" : "§7Disabled")
                + " §7(plans=" + graphResources.plansComplete() + "/"
                + graphResources.plansObserved() + ", allocations="
                + graphResources.allocationsRequested() + ", native="
                + graphResources.nativeTextureCount() + ", bytes="
                + graphResources.nativeTextureBytes() + ", blockers="
                + graphResources.blockerCount() + ")");
        if (!graphResources.blockerSummary().isEmpty()) {
            msg(src, "§7Iris Metal attachment blockers: §f"
                    + graphResources.blockerSummary());
        }
        IrisTranslationCoordinator.FullGraphStatus fullGraph =
                IrisTranslationCoordinator.fullGraphStatus();
        msg(src, "§7Iris full Metal graph: "
                + (fullGraph.validated() ? "§aValidated offscreen"
                    : fullGraph.enabled() ? "§eCollecting/fallback"
                    : "§7Disabled")
                + " §7(frames=" + fullGraph.framesSucceeded() + "/"
                + fullGraph.framesAttempted() + ", planned="
                + fullGraph.framesPlanned() + ", pending="
                + fullGraph.framesPending() + ", draws="
                + fullGraph.draws() + ", operations="
                + fullGraph.operations() + ", initialized="
                + fullGraph.initializedResources() + ", hash="
                + fullGraph.lastOutputHashUnsigned() + ", ownership="
                + fullGraph.ownershipMode() + ", invalidated="
                + fullGraph.ownershipFramesInvalidated() + ")");
        if (!fullGraph.blockerSummary().isEmpty()) {
            msg(src, "§7Iris full graph blockers: §f"
                    + fullGraph.blockerSummary());
        }
        IrisTranslationCoordinator.ShadowPlanStatus shadow =
                IrisTranslationCoordinator.shadowPlanStatus();
        msg(src, "§7Iris shadow replay plan: "
                + (shadow.complete() ? "§aStructurally complete"
                    : "§eCollecting/fallback")
                + " §7(plans=" + shadow.structurallyComplete() + "/"
                + shadow.plansObserved() + ", steps="
                + shadow.executionSteps() + ", blockers="
                + shadow.blockerCount() + ")");
        if (!shadow.blockerSummary().isEmpty()) {
            msg(src, "§7Iris shadow replay blockers: §f"
                    + shadow.blockerSummary());
        }
        IrisTranslationCoordinator.ShadowBufferStatus shadowBuffers =
                IrisTranslationCoordinator.shadowBufferStatus();
        msg(src, "§7Iris shadow replay buffers: "
                + (shadowBuffers.complete() ? "§aComplete"
                    : shadowBuffers.enabled() ? "§eCollecting/fallback"
                    : "§7Disabled")
                + " §7(plans=" + shadowBuffers.completePlans() + "/"
                + shadowBuffers.plansObserved() + ", images="
                + shadowBuffers.bufferImages() + ", bytes="
                + shadowBuffers.bufferBytes() + ", blockers="
                + shadowBuffers.blockerCount() + ")");
        if (!shadowBuffers.blockerSummary().isEmpty()) {
            msg(src, "§7Iris shadow buffer blockers: §f"
                    + shadowBuffers.blockerSummary());
        }
        IrisTranslationCoordinator.ShadowArgumentStatus shadowArguments =
                IrisTranslationCoordinator.shadowArgumentStatus();
        msg(src, "§7Iris MSL argument tables: "
                + (shadowArguments.complete() ? "§aComplete"
                    : shadowArguments.enabled() ? "§eCollecting/fallback"
                    : "§7Disabled")
                + " §7(plans=" + shadowArguments.completePlans() + "/"
                + shadowArguments.plansObserved() + ", arguments="
                + shadowArguments.argumentsResolved() + ", inlineBytes="
                + shadowArguments.inlineUniformBytes() + ", blockers="
                + shadowArguments.blockerCount() + ")");
        if (!shadowArguments.blockerSummary().isEmpty()) {
            msg(src, "§7Iris MSL argument blockers: §f"
                    + shadowArguments.blockerSummary());
        }
        IrisTranslationCoordinator.ShadowReplayStatus shadowReplay =
                IrisTranslationCoordinator.shadowReplayStatus();
        msg(src, "§7Iris native Metal 4 replay: "
                + (shadowReplay.executionComplete()
                    ? "§aExecution coverage complete"
                    : shadowReplay.enabled() ? "§eShadow-only/fallback"
                    : "§7Disabled")
                + " §7(draws=" + shadowReplay.drawsSucceeded() + "/"
                + shadowReplay.drawsAttempted() + ", ready="
                + shadowReplay.drawsReady() + ", blocked="
                + shadowReplay.drawsBlocked() + ", phases="
                + shadowReplay.successfulPhaseSummary() + ", hash="
                + shadowReplay.lastColorHashUnsigned() + ", target="
                + shadowReplay.lastWidth() + "x"
                + shadowReplay.lastHeight() + ")");
        if (!shadowReplay.blockerSummary().isEmpty()) {
            msg(src, "§7Iris native replay blockers: §f"
                    + shadowReplay.blockerSummary());
        }
        msg(src, "§7Iris pipeline-state unsupported reasons: §f"
                + (irisTranslation.pipelineStateUnsupportedReasonSummary()
                    .isEmpty()
                    ? "none"
                    : irisTranslation
                        .pipelineStateUnsupportedReasonSummary())
                + " §7(count="
                + irisTranslation.pipelineStateUnsupportedReasonCount()
                + ", digest="
                + irisTranslation
                    .pipelineStateUnsupportedReasonSetSha256()
                + ", "
                + (irisTranslation
                    .pipelineStateUnsupportedReasonSetComplete()
                    ? "complete" : "truncated")
                + ")");
        if (irisTranslation.running()) {
            msg(src,
                    "§7Iris Metal execution: §eShadow-only; visual parity and cutover are disarmed; Iris OpenGL active");
        }
        msg(src, "§7Init state: §f" + MetalRenderClient.getInitState());
        if (MetalRenderClient.getInitFailure() != null) {
            msg(src, "§7Fallback reason: §e" + MetalRenderClient.getInitFailure());
        }
        if (NativeBridge.isLibLoaded()) {
            try {
                msg(src, "§7Native backend: §f" + NativeBridge.nGetBackendMode());
                msg(src, "§7Metal 4 runtime: " + (NativeBridge.nIsMetal4Active()
                    ? "§aReady" : "§eUnavailable"));
                msg(src, "§7Metal 4 draw path: "
                    + (NativeBridge.nIsMetal4DrawPathActive()
                        ? "§aActive" : "§eCompatibility"));
            } catch (UnsatisfiedLinkError ignored) {
                msg(src, "§7Native backend: §elegacy dylib");
            }
        }

        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            msg(src, "§7Mesh count: §f" + wr.getChunkMesher().getMeshCount());
            msg(src, "§7Pending: §f" + wr.getChunkMesher().getPendingCount());
        }
    }

    private static void cacheClear(CommandSourceStack src) {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            wr.getChunkMesher().clearAllMeshes();
            msg(src, "§aTerrain mesh cache cleared");
        } else {
            msg(src, "§cWorld renderer is unavailable");
        }
    }

    private static void reloadWorld(CommandSourceStack src) {
        try {
            Minecraft mc = Minecraft.getInstance();
            boolean vanillaRebuilt = MetalRenderClient.rebuildLevelRenderer(mc);
            MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
            if (wr != null) {
                wr.getChunkMesher().clearAllMeshes();
            }
            msg(src, vanillaRebuilt
                ? "§aLevel render data reloaded"
                : "§eMetal cache cleared; no active level to rebuild");
        } catch (Exception e) {
            msg(src, "§creload failed: " + e.getMessage());
        }
    }

    private static void restart(CommandSourceStack src) {
        try {
            boolean restarted = MetalRenderClient.restartRenderer(Minecraft.getInstance());
            msg(src, restarted
                ? "§aComplemetal restarted"
                : "§cComplemetal restart failed; see /complemetal status");
        } catch (Exception e) {
            msg(src, "§cRestart fail: " + e.getMessage());
        }
    }

    private static void resetConfig(CommandSourceStack src) {
        MetalRenderClient.resetConfig();
        invalidateAllMeshes();
        msg(src, "§eComplemetal settings restored to defaults");
    }

    private static String fmtPx(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static void invalidateAllMeshes() {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            wr.getChunkMesher().clearAllMeshes();
        }
    }

    private MetalRenderCommands() {
    }
}

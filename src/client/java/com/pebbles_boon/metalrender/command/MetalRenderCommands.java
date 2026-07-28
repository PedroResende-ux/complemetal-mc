package com.pebbles_boon.metalrender.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

public final class MetalRenderCommands {

    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(commandTree("metalrender"));
            dispatcher.register(commandTree("mr"));
        });
        MetalLogger.info("MetalRender client commands registered");
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> commandTree(String root) {
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
                                      .requires(FabricClientCommandSource::attended)
                                      .executes(ctx -> {
                                        cacheClear(ctx.getSource());
                                        return 1;
                                    })))

                            .then(literal("reload")
                              .requires(FabricClientCommandSource::attended)
                              .executes(ctx -> {
                                reloadWorld(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("restart")
                              .requires(FabricClientCommandSource::attended)
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
                                        msg(ctx.getSource(), "§aMetalRender config saved");
                                        return 1;
                                    }))
                                    .then(literal("reload")
                                      .requires(FabricClientCommandSource::attended)
                                      .executes(ctx -> {
                                        boolean ok = MetalRenderClient.reloadConfig();
                                        msg(ctx.getSource(), ok
                                            ? "§aMetalRender config reloaded"
                                            : "§cMetalRender config reload failed; see log");
                                        return 1;
                                    }))
                                    .then(literal("reset")
                                      .requires(FabricClientCommandSource::attended)
                                      .executes(ctx -> {
                                        resetConfig(ctx.getSource());
                                        return 1;
                                    })))

                            .then(literal("performance")
                                    .then(literal("reset")
                                      .requires(FabricClientCommandSource::attended)
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
                                    ? "§aMetalRender profiler enabled"
                                    : "§eMetalRender profiler disabled");
                                return 1;
                            }))

                            .executes(ctx -> {
                                sendHelp(ctx.getSource());
                                return 1;
                            });
    }

    private static void msg(FabricClientCommandSource src, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(text));
        }
    }

    private static void sendHelp(FabricClientCommandSource src) {
        msg(src, "§6§l--- MetalRender Commands ---");
        msg(src, "§e/metalrender status §7- Show renderer and backend status");
        msg(src, "§e/metalrender cache clear §7- Clear generated terrain meshes");
        msg(src, "§e/metalrender reload §7- Rebuild level render data");
        msg(src, "§e/metalrender restart §7- Restart the Metal renderer session");
        msg(src, "§e/metalrender config open|save|reload|reset §7- Configuration");
        msg(src, "§e/metalrender performance reset §7- Reset dynamic scaling");
        msg(src, "§e/metalrender profile §7- Toggle profiler overlay");
        msg(src, "§7Alias: §e/mr");
    }

    private static void openConfigScreen(FabricClientCommandSource src) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                msg(src, "§cMinecraft client is unavailable");
                return;
            }
            MetalRenderClient.openSettingsScreen(mc);
            msg(src, "§aOpened MetalRender settings");
        } catch (Exception e) {
            msg(src, "§cfailed to open config screen: " + e.getMessage());
        }
    }

    private static void sendStatus(FabricClientCommandSource src) {
        boolean available = MetalRenderClient.isMetalAvailable();
        MetalRenderConfig cfg = MetalRenderClient.getConfig();
        boolean enabled = cfg != null && cfg.enableMetalRendering;

        msg(src, "§6§l--- MetalRender Status ---");
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

    private static void cacheClear(FabricClientCommandSource src) {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            wr.getChunkMesher().clearAllMeshes();
            msg(src, "§aTerrain mesh cache cleared");
        } else {
            msg(src, "§cWorld renderer is unavailable");
        }
    }

    private static void reloadWorld(FabricClientCommandSource src) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.levelRenderer != null) {
                mc.levelRenderer.resetLevelRenderData();
            }
            MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
            if (wr != null) {
                wr.getChunkMesher().clearAllMeshes();
            }
            msg(src, "§aLevel render data reloaded");
        } catch (Exception e) {
            msg(src, "§creload failed: " + e.getMessage());
        }
    }

    private static void restart(FabricClientCommandSource src) {
        try {
            boolean restarted = MetalRenderClient.restartRenderer(Minecraft.getInstance());
            msg(src, restarted
                ? "§aMetalRender restarted"
                : "§cMetalRender restart failed; see /metalrender status");
        } catch (Exception e) {
            msg(src, "§cRestart fail: " + e.getMessage());
        }
    }

    private static void resetConfig(FabricClientCommandSource src) {
        MetalRenderClient.resetConfig();
        invalidateAllMeshes();
        msg(src, "§eMetalRender settings restored to defaults");
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

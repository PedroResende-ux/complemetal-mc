package com.pebbles_boon.metalrender.gui;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public final class MetalDebugEntry implements DebugScreenEntry {
  private static final Identifier DBG_GRP = Identifier.fromNamespaceAndPath("metalrender", "debug_group");
  private static final Identifier DBG_ID = Identifier.fromNamespaceAndPath("metalrender", "debug");

  enum Status {
    DISABLED(ChatFormatting.GRAY),
    INITIALIZING(ChatFormatting.YELLOW),
    FALLBACK(ChatFormatting.RED),
    NO_WORLD(ChatFormatting.GRAY),
    IRIS_PAUSED(ChatFormatting.GOLD),
    ACTIVE(ChatFormatting.BLUE);

    private final ChatFormatting color;

    Status(ChatFormatting color) {
      this.color = color;
    }
  }

  record StatusSnapshot(
      boolean configLoaded,
      boolean configEnabled,
      MetalRenderClient.InitState initState,
      @Nullable String initFailure,
      boolean runtimeEnabled,
      boolean worldRendererPresent,
      boolean worldLoaded,
      boolean irisPauseApplied,
      boolean rendererReady) {
  }

  public static void register() {
    try {
      var reg = registry();
      reg.put(DBG_ID, new MetalDebugEntry());
      reg.put(DebugScreenEntries.SYSTEM_SPECS, new SysSpecEntry());
    } catch (ReflectiveOperationException err) {
      MetalLogger.error("debug entry registration failed", err);
    }
  }

  public static void show(@Nullable Minecraft mc) {
    if (mc == null) {
      return;
    }
    mc.debugEntries.setStatus(DBG_ID, DebugScreenEntryStatus.IN_OVERLAY);
  }

  static boolean rendOn() {
    var cfg = MetalRenderClient.getConfig();
    var wr = MetalRenderClient.getWorldRenderer();
    return cfg != null && cfg.enableMetalRendering &&
        MetalRenderClient.isEnabled() && wr != null && wr.isReady();
  }

  @Override
  public void display(DebugScreenDisplayer dsp, @Nullable Level lvl,
      @Nullable LevelChunk chunk,
      @Nullable LevelChunk otherChunk) {
    dsp.addToGroup(DBG_GRP, line());
  }

  private static String line() {
    StatusSnapshot current = snapshot();
    return format(resolve(current), current, dispVer());
  }

  static Status resolve(StatusSnapshot snapshot) {
    if (!snapshot.configLoaded()) {
      return Status.INITIALIZING;
    }
    if (!snapshot.configEnabled()) {
      return Status.DISABLED;
    }

    return switch (snapshot.initState()) {
      case NOT_TRIED, INITIALIZING -> Status.INITIALIZING;
      case FAILED, UNSUPPORTED -> Status.FALLBACK;
      case READY -> {
        if (!snapshot.runtimeEnabled() || !snapshot.worldRendererPresent()) {
          yield Status.FALLBACK;
        }
        if (!snapshot.worldLoaded()) {
          yield Status.NO_WORLD;
        }
        if (snapshot.irisPauseApplied()) {
          yield Status.IRIS_PAUSED;
        }
        yield snapshot.rendererReady() ? Status.ACTIVE : Status.FALLBACK;
      }
    };
  }

  static String format(Status status, StatusSnapshot snapshot,
      String version) {
    String detail = switch (status) {
      case DISABLED -> "disabled in config";
      case INITIALIZING -> "initializing";
      case FALLBACK -> fallbackDetail(snapshot.initFailure());
      case NO_WORLD -> "ready - no world loaded";
      case IRIS_PAUSED -> "terrain paused - Iris/OpenGL compatibility";
      case ACTIVE -> "rendering";
    };
    return "%sMetalRender %s%s%s".formatted(
        status.color,
        version,
        status == Status.ACTIVE ? " " : ": ",
        detail);
  }

  private static StatusSnapshot snapshot() {
    var cfg = MetalRenderClient.getConfig();
    var wr = MetalRenderClient.getWorldRenderer();
    boolean worldLoaded = wr != null && wr.isWorldLoaded();
    return new StatusSnapshot(
        cfg != null,
        cfg != null && cfg.enableMetalRendering,
        MetalRenderClient.getInitState(),
        MetalRenderClient.getInitFailure(),
        MetalRenderClient.isEnabled(),
        wr != null,
        worldLoaded,
        wr != null && wr.isIrisCompatibilityPaused(),
        wr != null && wr.isReady());
  }

  private static String fallbackDetail(@Nullable String failure) {
    if (failure == null || failure.isBlank()) {
      return "vanilla fallback";
    }
    String normalized = failure.replaceAll("\\s+", " ").trim();
    int maxLength = 96;
    if (normalized.length() > maxLength) {
      normalized = normalized.substring(0, maxLength - 3) + "...";
    }
    return "vanilla fallback - " + normalized;
  }

  private static Map<Identifier, DebugScreenEntry> registry()
      throws ReflectiveOperationException {
    Field f = DebugScreenEntries.class.getDeclaredField("ENTRIES_BY_ID");
    f.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<Identifier, DebugScreenEntry> reg = (Map<Identifier, DebugScreenEntry>) f.get(null);
    return reg;
  }

  private static String dispVer() {
    var v = FabricLoader.getInstance()
        .getModContainer("metalrender")
        .map(c -> c.getMetadata().getVersion().getFriendlyString())
        .orElse("unknown");
    return !v.isEmpty() && (v.charAt(0) == 'v' || v.charAt(0) == 'V')
        ? v.substring(1)
        : v;
  }

  private static final class SysSpecEntry implements DebugScreenEntry {
    private static final Identifier SYS_GRP = Identifier.withDefaultNamespace("system");

    @Override
    public void display(DebugScreenDisplayer dsp, @Nullable Level lvl,
        @Nullable LevelChunk chunk,
        @Nullable LevelChunk otherChunk) {
      GpuDevice dev = RenderSystem.getDevice();
      Minecraft mc = Minecraft.getInstance();
      List<String> rows = new ArrayList<>(5);
      rows.add(String.format(Locale.ROOT, "Java: %s",
          System.getProperty("java.version")));
      rows.add(String.format(Locale.ROOT, "CPU: %s", GLX._getCpuInfo()));
      var info = dev.getDeviceInfo();
      rows.add(String.format(Locale.ROOT, "Display: %dx%d (%s)",
          mc.getWindow().getWidth(),
          mc.getWindow().getHeight(), info.vendorName()));
      rows.add(info.name());
      if (!rendOn()) {
        rows.add(String.format(Locale.ROOT, "%s %s", info.backendName(),
            info.driverInfo()));
      }
      dsp.addToGroup(SYS_GRP, rows);
    }
  }
}

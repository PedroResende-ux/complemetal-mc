package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.compat.IrisCompatibility;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Minimal Minecraft 1.21.1 settings surface. The upstream 26.2 screen uses
 * GuiGraphicsExtractor and the newer mouse event API, so it is kept out of the
 * first 1.21.1 renderer cutover.
 */
public final class MetalRenderSettingsScreen extends Screen {
  private final Screen parent;
  private MetalRenderConfig config;

  public MetalRenderSettingsScreen(Screen parent) {
    super(Component.literal("Complemetal Settings"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    config = MetalRenderClient.getConfig();
    if (config == null) config = MetalRenderConfig.load();

    int cx = width / 2;
    int y = height / 2 - 60;

    addRenderableWidget(Button.builder(Component.literal(metalStatus()),
        b -> { config.enableMetalRendering = !config.enableMetalRendering;
               config.save();
               MetalRenderClient.requestDeferredApply(true, true, true);
               rebuildWidgets(); })
        .bounds(cx - 120, y, 240, 20).build());

    addRenderableWidget(Button.builder(Component.literal(fastTerrainStatus()),
        b -> { config.enableFastTerrainReplacement =
                  !config.enableFastTerrainReplacement;
               config.save();
               MetalRenderClient.requestDeferredApply(false, false, true);
               rebuildWidgets(); })
        .bounds(cx - 120, y + 28, 240, 20).build());

    addRenderableWidget(Button.builder(Component.literal(
        "Resolution: " + String.format(java.util.Locale.ROOT, "%.2fx",
            MetalRenderConfig.resolutionScale())),
        b -> { float current = MetalRenderConfig.resolutionScale();
               MetalRenderConfig.setResolutionScale(
                   current >= 0.99f ? 0.85f : current >= 0.84f ? 0.70f : 1.0f);
               config.save();
               MetalRenderClient.requestDeferredApply(false, false, true);
               rebuildWidgets(); })
        .bounds(cx - 120, y + 56, 240, 20).build());

    addRenderableWidget(Button.builder(Component.literal("Reload config"),
        b -> { MetalRenderClient.reloadConfig();
               config = MetalRenderClient.getConfig();
               rebuildWidgets(); })
        .bounds(cx - 120, y + 84, 116, 20).build());

    addRenderableWidget(Button.builder(Component.literal("Reset"),
        b -> { MetalRenderClient.resetConfig();
               config = MetalRenderClient.getConfig();
               rebuildWidgets(); })
        .bounds(cx + 4, y + 84, 116, 20).build());

    addRenderableWidget(Button.builder(Component.literal("Done"),
        b -> onClose()).bounds(cx - 120, y + 120, 240, 20).build());
  }

  @Override
  public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
    renderBackground(gui, mouseX, mouseY, partialTick);
    int cx = width / 2;
    gui.drawCenteredString(font, "Complemetal - Metal / Minecraft 1.21.1",
        cx, 30, 0xFFFFFFFF);
    gui.drawCenteredString(font, "GPU: " + safeGpu(), cx, 48, 0xFFAAAAAA);
    gui.drawCenteredString(font, "Iris: " + IrisCompatibility.statusDescription(),
        cx, height - 34, 0xFFAAAAAA);
    super.render(gui, mouseX, mouseY, partialTick);
  }

  private String metalStatus() {
    return "Metal Rendering: " +
        (config != null && config.enableMetalRendering ? "ON" : "OFF");
  }

  private String fastTerrainStatus() {
    return "Fast Terrain Replacement: " +
        (config != null && config.enableFastTerrainReplacement ? "ON" : "OFF");
  }

  private String safeGpu() {
    try {
      String value = MetalHardwareChecker.getDeviceName();
      return value == null || value.isBlank() ? "Unknown" : value;
    } catch (Throwable error) {
      return "Unavailable";
    }
  }

  @Override
  protected void rebuildWidgets() {
    clearWidgets();
    init();
  }

  @Override
  public void onClose() {
    if (config != null) config.save();
    Minecraft.getInstance().setScreen(parent);
  }
}

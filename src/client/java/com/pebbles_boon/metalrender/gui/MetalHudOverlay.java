package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class MetalHudOverlay {
  public static void register() {
    NeoForge.EVENT_BUS.addListener(MetalHudOverlay::render);
  }

  private static void render(RenderGuiEvent.Post event) {
    if (!MetalRenderClient.isEnabled()) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
    if (mc == null || mc.font == null || wr == null || !wr.isReady()) {
      return;
    }

    var gui = event.getGuiGraphics();
    gui.drawString(mc.font, "Complemetal ACTIVE", 10, 10, 0xFFFF00FF, true);
    if (wr.isLoadingMode()) {
      String text = "Loading: " + wr.getLoadingModePendingCount()
          + " pending / " + wr.getLoadingModeMeshCount() + " built";
      gui.drawString(mc.font, text, 10, 22, 0xFFFFAA00, true);
    }
  }

  private MetalHudOverlay() {}
}

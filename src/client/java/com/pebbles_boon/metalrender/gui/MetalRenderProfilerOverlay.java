package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.performance.MetalRenderProfiler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class MetalRenderProfilerOverlay {
  private static final int BG_COLOR = 0x80213648;
  private static final int TEXT_COLOR = 0xFF89CFF0;
  private static final int HEADER_COLOR = 0xFFA8DFF0;
  private static final int LINE_HEIGHT = 11;
  private static final int PADDING_X = 4;
  private static final int PADDING_Y = 2;
  private static final int OFFSET_X = 4;
  private static final int OFFSET_Y = 4;

  public static void register() {
    NeoForge.EVENT_BUS.addListener(MetalRenderProfilerOverlay::render);
  }

  private static void render(RenderGuiEvent.Post event) {
    MetalRenderProfiler profiler = MetalRenderProfiler.getInstance();
    if (!profiler.isVisible()) {
      return;
    }

    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.font == null) {
      return;
    }

    MetalRenderProfiler.ProfileSnapshot snapshot = profiler.getSnapshot();
    if (snapshot == null) {
      return;
    }

    GuiGraphics context = event.getGuiGraphics();
    String[] lines = snapshot.toLines();
    String header = "Complemetal Profiler";
    int maxWidth = mc.font.width(header);
    for (String line : lines) {
      int w = mc.font.width(line);
      if (w > maxWidth) {
        maxWidth = w;
      }
    }

    int boxWidth = maxWidth + PADDING_X * 2;
    int boxHeight = LINE_HEIGHT * (lines.length + 1) + PADDING_Y * 2;
    int x = OFFSET_X;
    int y = OFFSET_Y;

    context.fill(x, y, x + boxWidth, y + boxHeight, BG_COLOR);
    context.drawString(mc.font, header, x + PADDING_X,
        y + PADDING_Y, HEADER_COLOR, true);

    for (int i = 0; i < lines.length; i++) {
      context.drawString(mc.font, lines[i], x + PADDING_X,
          y + PADDING_Y + LINE_HEIGHT * (i + 1), TEXT_COLOR, true);
    }
  }

  private MetalRenderProfilerOverlay() {
  }
}

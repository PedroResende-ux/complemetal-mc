package com.pebbles_boon.metalrender.gui;

import net.minecraft.client.Minecraft;

/**
 * The debug-screen extension used by upstream Complemetal targets Minecraft
 * 26.2's debug entry API. 1.21.1 keeps the native F3 overlay implementation;
 * this adapter is intentionally inert until that API is ported.
 */
public final class MetalDebugEntry {
  public static void register() {
  }

  public static void show(Minecraft minecraft) {
  }

  private MetalDebugEntry() {}
}

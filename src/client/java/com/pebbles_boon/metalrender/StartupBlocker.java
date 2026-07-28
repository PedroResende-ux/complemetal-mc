package com.pebbles_boon.metalrender;

import java.util.Locale;

public final class StartupBlocker {
  public static final boolean ALWAYS_SHOW = false;
  private static final boolean WINDOWS = System.getProperty("os.name", "")
      .toLowerCase(Locale.ROOT)
      .contains("win");
  private static final boolean MACOS = System.getProperty("os.name", "")
      .toLowerCase(Locale.ROOT)
      .contains("mac");
  private static final boolean APPLE_SILICON = System.getProperty("os.arch", "")
      .toLowerCase(Locale.ROOT)
      .matches(".*(aarch64|arm64).*");

  private StartupBlocker() {
  }

  public static boolean isWindows() {
    return WINDOWS;
  }

  public static boolean shouldBlockStartup() {
    // Unsupported platforms must still complete Fabric initialization so the
    // commands/status UI can explain the vanilla fallback. The old hard block
    // made the mod disappear without a useful diagnostic.
    return ALWAYS_SHOW;
  }

  public static boolean isPlatformSupported() {
    return MACOS && APPLE_SILICON;
  }

  public static String unsupportedReason() {
    if (!MACOS) {
      return "MetalRender requires macOS";
    }
    if (!APPLE_SILICON) {
      return "MetalRender requires Apple Silicon";
    }
    return null;
  }
}

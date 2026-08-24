package com.pebbles_boon.metalrender.compat.iris;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;

/** Production defaults and explicit overrides for the Iris Metal path. */
public final class IrisMetalFeatureFlags {
  public static final String ENABLED_PROPERTY =
      "metalrender.irisMetal.enabled";
  private static final String GRAPH_OWNERSHIP_PROPERTY =
      "metalrender.experimental.irisMetalGraphOwnership";
  private static final String METAL4_REQUESTED_PROPERTY =
      "metalrender.feature.metal4";
  private static final Pattern STABLE_VERSION = Pattern.compile(
      "[0-9]+\\.[0-9]+\\.[0-9]+\\+mc[0-9]+(?:\\.[0-9]+)*");

  private IrisMetalFeatureFlags() {
  }

  /**
   * Resolves one legacy fine-grained flag. An explicit fine-grained value has
   * priority, followed by the global user override and finally the packaged
   * stable-release default.
   */
  public static boolean enabled(String property) {
    Objects.requireNonNull(property, "property");
    String explicit = System.getProperty(property);
    if (explicit != null) {
      return Boolean.parseBoolean(explicit);
    }
    String global = System.getProperty(ENABLED_PROPERTY);
    if (global != null) {
      return Boolean.parseBoolean(global);
    }
    if (GRAPH_OWNERSHIP_PROPERTY.equals(property)
        && "false".equalsIgnoreCase(System.getProperty(
            METAL4_REQUESTED_PROPERTY))) {
      return false;
    }
    return stableReleaseDefaultEnabled(
        packagedReleaseVersion(),
        System.getProperty("os.name", ""),
        System.getProperty("os.arch", ""),
        System.getProperty("os.version", ""));
  }

  private static String packagedReleaseVersion() {
    try {
      FabricLoader loader = FabricLoader.getInstance();
      if (loader.isDevelopmentEnvironment()) {
        return null;
      }
      return loader.getModContainer("metalrender")
          .map(container -> container.getMetadata().getVersion()
              .getFriendlyString())
          .orElse(null);
    } catch (RuntimeException | LinkageError unavailable) {
      return IrisMetalFeatureFlags.class.getPackage()
          .getImplementationVersion();
    }
  }

  static boolean stableReleaseDefaultEnabled(String implementationVersion,
      String osName, String osArch, String osVersion) {
    if (implementationVersion == null
        || !STABLE_VERSION.matcher(implementationVersion).matches()
        || !"mac os x".equals(osName.toLowerCase(Locale.ROOT))) {
      return false;
    }
    String normalizedArch = osArch.toLowerCase(Locale.ROOT);
    if (!"aarch64".equals(normalizedArch)
        && !"arm64".equals(normalizedArch)) {
      return false;
    }
    int separator = osVersion.indexOf('.');
    String majorText = separator < 0 ? osVersion
        : osVersion.substring(0, separator);
    try {
      return Integer.parseInt(majorText) >= 26;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import java.util.Objects;
import net.neoforged.fml.ModList;

/** Production defaults and explicit overrides for the Iris Metal path. */
public final class IrisMetalFeatureFlags {
  public static final String ENABLED_PROPERTY =
      "metalrender.irisMetal.enabled";
  private static final String GRAPH_OWNERSHIP_PROPERTY =
      "metalrender.experimental.irisMetalGraphOwnership";
  private static final String METAL4_REQUESTED_PROPERTY =
      "metalrender.feature.metal4";
  private IrisMetalFeatureFlags() {
  }

  /**
   * Resolves one legacy fine-grained flag. An explicit fine-grained value has
   * priority, followed by the global user override. Production builds remain
   * fail-open by default: the draw-interception path captures live OpenGL
   * resources and must never be enabled merely because a build has a stable
   * version number. Exact-JAR/development runs opt in explicitly.
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
      return ModList.get().getModContainerById("complemetal")
          .map(container -> container.getModInfo().getVersion().toString())
          .orElse(null);
    } catch (RuntimeException | LinkageError unavailable) {
      return IrisMetalFeatureFlags.class.getPackage()
          .getImplementationVersion();
    }
  }

  static boolean stableReleaseDefaultEnabled(String implementationVersion,
      String osName, String osArch, String osVersion) {
    // Field evidence from 0.4.0 showed that automatic opt-in could suppress
    // Iris' visible OpenGL FINAL pass after an incomplete graph capture and
    // could spend more than a second capturing one frame. Hardware/version
    // eligibility is not a correctness proof, so production defaults stay
    // off until an explicit startup flag selects the experimental path.
    return false;
  }
}

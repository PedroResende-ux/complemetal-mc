package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisMetalFeatureFlagsTest {
  @Test
  void packagedReleaseNeverImplicitlyEnablesDrawInterception() {
    assertFalse(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
        "0.3.0+mc26.2", "Mac OS X", "aarch64", "26.6"));
    assertFalse(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
        "1.0.1+mc26.2", "Mac OS X", "arm64", "27"));

    assertFalse(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
        "0.3.0-alpha.1+mc26.2", "Mac OS X", "aarch64", "26.6"));
    assertFalse(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
        "0.3.0+mc26.2", "Mac OS X", "x86_64", "26.6"));
    assertFalse(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
        "0.3.0+mc26.2", "Mac OS X", "aarch64", "15.7"));
    assertFalse(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
        "0.3.0+mc26.2", "Linux", "aarch64", "26.6"));
    assertFalse(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
        null, "Mac OS X", "aarch64", "26.6"));
  }

  @Test
  void explicitDevelopmentOverridesRemainAvailable() {
    String property = IrisTranslationCoordinator.TRANSLATION_ENABLED_PROPERTY;
    String previousFine = System.getProperty(property);
    String previousGlobal = System.getProperty(
        IrisMetalFeatureFlags.ENABLED_PROPERTY);
    try {
      System.setProperty(property, "true");
      assertTrue(IrisMetalFeatureFlags.enabled(property));
      System.setProperty(property, "false");
      assertFalse(IrisMetalFeatureFlags.enabled(property));

      System.clearProperty(property);
      System.setProperty(IrisMetalFeatureFlags.ENABLED_PROPERTY, "true");
      assertTrue(IrisMetalFeatureFlags.enabled(property));
    } finally {
      restoreProperty(property, previousFine);
      restoreProperty(IrisMetalFeatureFlags.ENABLED_PROPERTY,
          previousGlobal);
    }
  }

  private static void restoreProperty(String property, String value) {
    if (value == null) {
      System.clearProperty(property);
    } else {
      System.setProperty(property, value);
    }
  }
}

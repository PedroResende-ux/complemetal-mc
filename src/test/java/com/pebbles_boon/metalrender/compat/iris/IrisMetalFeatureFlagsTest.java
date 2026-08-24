package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisMetalFeatureFlagsTest {
  @Test
  void enablesOnlyPackagedStableAppleSiliconMacOs26Releases() {
    assertTrue(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
        "0.3.0+mc26.2", "Mac OS X", "aarch64", "26.6"));
    assertTrue(IrisMetalFeatureFlags.stableReleaseDefaultEnabled(
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
}

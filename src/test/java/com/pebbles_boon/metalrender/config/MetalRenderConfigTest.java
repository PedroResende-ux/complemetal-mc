package com.pebbles_boon.metalrender.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MetalRenderConfigTest {
  @Test
  void releaseDefaultsPreferMetal4WithSafeFeatureFallbacks() {
    MetalRenderConfig config = MetalRenderConfig.defaults();

    assertTrue(config.enableMetalRendering);
    assertTrue(config.enableMetal4);
    assertTrue(config.enableArgumentBuffers);
    assertTrue(config.autoTargetFrameRate);
    assertFalse(config.enableFastTerrainReplacement);
    assertFalse(config.enableExperimentalFeatureReplacement);
    assertFalse(config.enableMeshShaders);
    assertFalse(config.enableHiZCull);
    assertEquals(1.0f, MetalRenderConfig.resolutionScale());
  }

  @Test
  void copiedValuesAreValidated() {
    MetalRenderConfig source = MetalRenderConfig.defaults();
    source.targetFrameRate = -1;
    source.maxMemoryMB = Integer.MAX_VALUE;
    source.requireMetal4 = true;
    source.enableMetal4 = false;
    source.enableMeshShaders = true;
    source.enableHiZCull = true;

    MetalRenderConfig destination = MetalRenderConfig.defaults();
    destination.copyFrom(source);

    assertEquals(30, destination.targetFrameRate);
    assertEquals(2048, destination.maxMemoryMB);
    assertTrue(destination.enableMetal4);
    assertFalse(destination.enableMeshShaders);
    assertFalse(destination.enableHiZCull);
  }

  @Test
  void explicitExperimentalPropertiesRespectReleaseSafetyLocks() {
    String fastTerrain = "metalrender.experimental.fastTerrainReplacement";
    String featureReplacement =
        "metalrender.experimental.featureReplacement";
    String meshShaders = "metalrender.experimental.meshShaders";
    String hiz = "metalrender.experimental.hiz";
    try {
      System.setProperty(fastTerrain, "true");
      System.setProperty(featureReplacement, "true");
      System.setProperty(meshShaders, "true");
      System.setProperty(hiz, "true");

      MetalRenderConfig config = MetalRenderConfig.defaults();

      assertTrue(config.enableFastTerrainReplacement);
      assertFalse(config.enableExperimentalFeatureReplacement);
      assertTrue(config.enableMeshShaders);
      assertTrue(config.enableHiZCull);
    } finally {
      System.clearProperty(fastTerrain);
      System.clearProperty(featureReplacement);
      System.clearProperty(meshShaders);
      System.clearProperty(hiz);
    }
  }
}

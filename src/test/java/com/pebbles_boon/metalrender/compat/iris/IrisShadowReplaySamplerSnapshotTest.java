package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureUnitBinding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class IrisShadowReplaySamplerSnapshotTest {
  @Test
  void resolvesTextureOwnedStateWhenNoSamplerObjectIsBound() {
    IrisGlSamplerMirror mirror = new IrisGlSamplerMirror(4);
    mirror.defineTexture(7);
    mirror.textureParameteri(7, IrisGlSamplerMirror.GL_TEXTURE_MAG_FILTER,
        0x2600);
    long generation = mirror.textureGeneration(7);

    IrisShadowReplaySamplerSnapshot captured =
        IrisShadowReplaySamplerSnapshot.capture(resources(
            new TextureUnitBinding(0x0DE1, 7, 0, 1, generation)), mirror);

    assertTrue(captured.complete(), captured.blockers().toString());
    assertTrue(captured.textureUnits().get(2).textureOwned());
    assertEquals(0x2600,
        captured.textureUnits().get(2).state().magFilter());
  }

  @Test
  void rejectsStaleSamplerObjectState() {
    IrisGlSamplerMirror mirror = new IrisGlSamplerMirror(4);
    mirror.defineSampler(44);
    long staleGeneration = mirror.samplerGeneration(44);
    mirror.samplerParameterf(44,
        IrisGlSamplerMirror.GL_TEXTURE_MAX_ANISOTROPY, 4.0F);

    IrisShadowReplaySamplerSnapshot captured =
        IrisShadowReplaySamplerSnapshot.capture(resources(
            new TextureUnitBinding(0x0DE1, 7, 44, 1,
                staleGeneration)), mirror);

    assertFalse(captured.complete());
    assertTrue(captured.textureUnits().isEmpty());
    assertEquals(List.of("sampler-state-snapshot-unavailable"),
        captured.blockers());
  }

  private static IrisGlResourceBindingSnapshot resources(
      TextureUnitBinding binding) {
    return new IrisGlResourceBindingSnapshot(1, Map.of(), Map.of(), Map.of(),
        Map.of(), Map.of(2, binding), Map.of(), Map.of(), Map.of());
  }
}

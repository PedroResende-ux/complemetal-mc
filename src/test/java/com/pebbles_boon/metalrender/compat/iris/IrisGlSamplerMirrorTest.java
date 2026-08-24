package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IrisGlSamplerMirrorTest {
  @Test
  void retainsExactKnownSamplerParametersByGeneration() {
    IrisGlSamplerMirror mirror = new IrisGlSamplerMirror(4);
    mirror.defineSampler(31);
    mirror.samplerParameteri(31,
        IrisGlSamplerMirror.GL_TEXTURE_MIN_FILTER, 0x2600);
    mirror.samplerParameteri(31, IrisGlSamplerMirror.GL_TEXTURE_WRAP_S,
        0x812F);
    mirror.samplerParameterf(31,
        IrisGlSamplerMirror.GL_TEXTURE_MAX_ANISOTROPY, 8.0F);
    long generation = mirror.samplerGeneration(31);

    IrisGlSamplerMirror.Snapshot snapshot = mirror.samplerSnapshot(31,
        generation).orElseThrow();
    assertTrue(snapshot.state().complete());
    assertEquals(0x2600, snapshot.state().minFilter());
    assertEquals(0x812F, snapshot.state().wrapS());
    assertEquals(8.0F, snapshot.state().maxAnisotropy());

    mirror.samplerParameteri(31,
        IrisGlSamplerMirror.GL_TEXTURE_MAG_FILTER, 0x2600);
    assertFalse(mirror.samplerSnapshot(31, generation).isPresent());
  }

  @Test
  void retainsTextureMipLevelRange() {
    IrisGlSamplerMirror mirror = new IrisGlSamplerMirror(4);
    mirror.defineTexture(9);
    mirror.textureParameteri(9, IrisGlSamplerMirror.GL_TEXTURE_BASE_LEVEL, 4);
    mirror.textureParameteri(9, IrisGlSamplerMirror.GL_TEXTURE_MAX_LEVEL, 8);

    IrisGlSamplerMirror.SamplerState state = mirror.textureSnapshot(9,
        mirror.textureGeneration(9)).orElseThrow().state();
    assertTrue(state.complete());
    assertEquals(4, state.baseLevel());
    assertEquals(8, state.maxLevel());
  }

  @Test
  void recordsUnsupportedSamplingParametersFailClosed() {
    IrisGlSamplerMirror mirror = new IrisGlSamplerMirror(4);
    mirror.defineTexture(9);
    mirror.textureParameteri(9, 0x8E42, 0x1903); // GL_TEXTURE_SWIZZLE_R

    IrisGlSamplerMirror.SamplerState state = mirror.textureSnapshot(9,
        mirror.textureGeneration(9)).orElseThrow().state();
    assertFalse(state.complete());
    assertEquals(java.util.List.of(0x8E42), state.unsupportedParameters());
  }
}

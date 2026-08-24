package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.FloatBuffer;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class IrisGlResourceBindingTrackerTest {
  private static final int GL_TEXTURE_BUFFER = 0x8C2A;

  @Test
  void keepsTextureTargetsIndependentOnTheSameUnit() {
    IrisGlResourceBindingTracker tracker =
        new IrisGlResourceBindingTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerProgram(7);
    tracker.useProgram(7);
    tracker.bindTextureToUnit(IrisGlResourceBindingTracker.GL_TEXTURE_2D,
        0, 41);
    tracker.bindTextureToUnit(GL_TEXTURE_BUFFER, 0, 42);

    IrisGlResourceBindingSnapshot snapshot = tracker.snapshot();
    assertNotNull(snapshot);
    assertEquals(IrisGlResourceBindingTracker.GL_TEXTURE_2D,
        snapshot.textureUnits().get(0).target());
    assertEquals(41, snapshot.textureUnits().get(0).texture());
    assertEquals(GL_TEXTURE_BUFFER,
        snapshot.textureBufferUnits().get(0).target());
    assertEquals(42, snapshot.textureBufferUnits().get(0).texture());

    tracker.activeTexture(IrisGlResourceBindingTracker.GL_TEXTURE0);
    assertEquals(42,
        tracker.activeTextureBinding(GL_TEXTURE_BUFFER).texture());
  }

  @Test
  void explicitTwoDimensionalUnbindIsNotReplacedByAnotherTarget() {
    IrisGlResourceBindingTracker tracker =
        new IrisGlResourceBindingTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerProgram(8);
    tracker.useProgram(8);
    tracker.bindTextureToUnit(IrisGlResourceBindingTracker.GL_TEXTURE_2D,
        0, 0);
    tracker.bindTextureToUnit(GL_TEXTURE_BUFFER, 0, 52);

    IrisGlResourceBindingSnapshot snapshot = tracker.snapshot();
    assertNotNull(snapshot);
    assertEquals(0, snapshot.textureUnits().get(0).texture());
  }

  @Test
  void deletingTextureClearsEveryTargetAndTextureBufferMetadata() {
    IrisGlResourceBindingTracker tracker =
        new IrisGlResourceBindingTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerProgram(9);
    tracker.useProgram(9);
    tracker.bindTextureToUnit(IrisGlResourceBindingTracker.GL_TEXTURE_2D,
        0, 61);
    tracker.bindTextureToUnit(GL_TEXTURE_BUFFER, 1, 61);
    tracker.activeTexture(IrisGlResourceBindingTracker.GL_TEXTURE0 + 1);
    tracker.texBuffer(GL_TEXTURE_BUFFER, 0x822E, 71);

    tracker.deleteTexture(61);

    IrisGlResourceBindingSnapshot snapshot = tracker.snapshot();
    assertNotNull(snapshot);
    assertEquals(0, snapshot.textureUnits().get(0).texture());
    assertEquals(0, snapshot.textureUnits().get(1).texture());
    assertEquals(false, snapshot.textureBuffers().containsKey(61));
  }

  @Test
  void capturesDirectFourByFourMatrixFromCurrentBufferPosition() {
    IrisGlResourceBindingTracker tracker =
        new IrisGlResourceBindingTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerProgram(10);
    tracker.uniformLocation(10, "iris_LightmapTextureMatrix", 3);
    tracker.useProgram(10);
    FloatBuffer values = FloatBuffer.allocate(18);
    values.put(0, -1.0F);
    for (int index = 0; index < 16; index++) {
      values.put(index + 1, index == 0 || index == 5 || index == 10
          ? 1.0F / 256.0F : index == 15 ? 1.0F
          : index >= 12 && index <= 14 ? 1.0F / 32.0F : 0.0F);
    }
    values.put(17, -2.0F);
    values.position(1);
    values.limit(17);

    tracker.uniformMatrix(3, 4, values);

    IrisGlResourceBindingSnapshot snapshot = tracker.snapshot();
    assertNotNull(snapshot);
    IrisGlResourceBindingSnapshot.UniformValue matrix =
        snapshot.uniformValues().get(3);
    assertNotNull(matrix);
    assertEquals(4, matrix.columns());
    assertEquals(4, matrix.rows());
    long[] rawBits = matrix.rawBits();
    assertEquals(16, rawBits.length);
    assertEquals(Float.floatToRawIntBits(1.0F / 256.0F),
        (int) rawBits[0]);
    assertEquals(Float.floatToRawIntBits(1.0F / 32.0F),
        (int) rawBits[12]);
    assertEquals(Float.floatToRawIntBits(1.0F),
        (int) rawBits[15]);
    assertEquals(Map.of("iris_LightmapTextureMatrix", 3),
        snapshot.uniformLocations());
  }
}

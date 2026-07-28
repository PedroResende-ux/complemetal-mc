package com.pebbles_boon.metalrender.culling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class FrustumCullerTest {
  @Test
  void identityFrustumAcceptsInsideAndRejectsOutside() {
    FrustumCuller culler = new FrustumCuller();
    culler.update(new Matrix4f(), new Matrix4f(), new Vector3f());

    assertTrue(culler.testBoundingBox(-0.5f, -0.5f, -0.5f,
        0.5f, 0.5f, 0.5f));
    assertFalse(culler.testBoundingBox(2.0f, -0.5f, -0.5f,
        3.0f, 0.5f, 0.5f));
  }

  @Test
  void snapshotIsNotMutatedWithWorkerReuse() {
    FrustumCuller worker = new FrustumCuller();
    worker.update(new Matrix4f(), new Matrix4f(), new Vector3f());
    FrustumCuller published = worker.snapshot();

    worker.update(new Matrix4f().scaling(0.0f), new Matrix4f(), new Vector3f());

    assertFalse(published.testBoundingBox(100.0f, 100.0f, 100.0f,
        101.0f, 101.0f, 101.0f));
    assertTrue(worker.testBoundingBox(100.0f, 100.0f, 100.0f,
        101.0f, 101.0f, 101.0f));
  }
}

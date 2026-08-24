package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisGlGenericAttributeTrackerTest {
  @Test
  void encodesDefaultAndTrackedValuesForConstantMetalLayout() {
    IrisGlGenericAttributeTracker tracker =
        new IrisGlGenericAttributeTracker();
    tracker.vertexAttribute4f(3, 4.5F, -2.0F, 8.0F, 7.0F);
    IrisPipelineState.VertexBufferLayout layout =
        new IrisPipelineState.VertexBufferLayout(1, 32,
            IrisPipelineState.StepFunction.CONSTANT, 0);
    byte[] encoded = tracker.encode(layout, List.of(
        new IrisPipelineState.VertexAttribute(2, 1, 0,
            new IrisPipelineState.DataFormat("rgba32-float")),
        new IrisPipelineState.VertexAttribute(3, 1, 16,
            new IrisPipelineState.DataFormat("rgba32-sint"))));

    ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.nativeOrder());
    assertEquals(0.0F, bytes.getFloat(0));
    assertEquals(1.0F, bytes.getFloat(12));
    assertEquals(4, bytes.getInt(16));
    assertEquals(-2, bytes.getInt(20));
    assertEquals(8, bytes.getInt(24));
    assertEquals(7, bytes.getInt(28));
  }
}

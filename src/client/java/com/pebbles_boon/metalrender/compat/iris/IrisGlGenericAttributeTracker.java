package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/** Mirrors OpenGL current generic vertex values used by disabled arrays. */
public final class IrisGlGenericAttributeTracker {
  private static final int MAX_ATTRIBUTES =
      IrisPipelineState.MAX_VERTEX_ATTRIBUTES;
  private static final IrisGlGenericAttributeTracker GLOBAL =
      new IrisGlGenericAttributeTracker();

  private final float[][] values = new float[MAX_ATTRIBUTES][4];

  public IrisGlGenericAttributeTracker() {
    reset();
  }

  public static IrisGlGenericAttributeTracker global() {
    return GLOBAL;
  }

  /** OpenGL initializes every current generic value to (0, 0, 0, 1). */
  public synchronized void reset() {
    for (float[] value : values) {
      value[0] = 0.0F;
      value[1] = 0.0F;
      value[2] = 0.0F;
      value[3] = 1.0F;
    }
  }

  public synchronized void vertexAttribute4f(int location, float x, float y,
      float z, float w) {
    if (location < 0 || location >= MAX_ATTRIBUTES
        || !Float.isFinite(x) || !Float.isFinite(y)
        || !Float.isFinite(z) || !Float.isFinite(w)) {
      return;
    }
    values[location][0] = x;
    values[location][1] = y;
    values[location][2] = z;
    values[location][3] = w;
  }

  /** Encodes one MTLVertexStepFunctionConstant buffer in native byte order. */
  public synchronized byte[] encode(
      IrisPipelineState.VertexBufferLayout layout,
      List<IrisPipelineState.VertexAttribute> attributes) {
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(attributes, "attributes");
    if (layout.stepFunction() != IrisPipelineState.StepFunction.CONSTANT) {
      throw new IllegalArgumentException("vertex layout is not constant");
    }
    ByteBuffer bytes = ByteBuffer.allocate(layout.strideBytes())
        .order(ByteOrder.nativeOrder());
    boolean populated = false;
    for (IrisPipelineState.VertexAttribute attribute : attributes) {
      if (attribute.bufferIndex() != layout.bufferIndex()) {
        continue;
      }
      Format format = Format.parse(attribute.format().cacheName());
      int end = Math.addExact(attribute.offsetBytes(),
          format.components * Integer.BYTES);
      if (end > bytes.capacity()) {
        throw new IllegalArgumentException(
            "constant vertex attribute exceeds its layout");
      }
      float[] value = values[attribute.location()];
      bytes.position(attribute.offsetBytes());
      for (int component = 0; component < format.components; component++) {
        switch (format.kind) {
          case FLOAT -> bytes.putFloat(value[component]);
          case SINT -> bytes.putInt((int) value[component]);
          case UINT -> bytes.putInt((int) Math.max(0.0F, value[component]));
        }
      }
      populated = true;
    }
    if (!populated) {
      throw new IllegalArgumentException(
          "constant vertex layout has no attributes");
    }
    return bytes.array();
  }

  private enum Kind {
    FLOAT,
    SINT,
    UINT
  }

  private record Format(int components, Kind kind) {
    private static Format parse(String name) {
      int components = name.startsWith("rgba") ? 4
          : name.startsWith("rgb") ? 3
          : name.startsWith("rg") ? 2
          : name.startsWith("r") ? 1 : 0;
      Kind kind = name.endsWith("32-float") ? Kind.FLOAT
          : name.endsWith("32-sint") ? Kind.SINT
          : name.endsWith("32-uint") ? Kind.UINT : null;
      if (components == 0 || kind == null) {
        throw new IllegalArgumentException(
            "unsupported generic vertex format");
      }
      return new Format(components, kind);
    }
  }
}

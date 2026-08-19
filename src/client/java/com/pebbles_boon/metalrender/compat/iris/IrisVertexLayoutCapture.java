package com.pebbles_boon.metalrender.compat.iris;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Converts Mojang's immutable vertex format into cache-safe plain data. */
public final class IrisVertexLayoutCapture {
  private IrisVertexLayoutCapture() {
  }

  public static Layout capture(VertexFormat format) {
    Objects.requireNonNull(format, "format");
    int stepRate = format.getStepRate();
    IrisPipelineState.StepFunction stepFunction = stepRate == 0
        ? IrisPipelineState.StepFunction.PER_VERTEX
        : IrisPipelineState.StepFunction.PER_INSTANCE;
    List<IrisPipelineState.VertexBufferLayout> buffers = List.of(
        new IrisPipelineState.VertexBufferLayout(0, format.getVertexSize(),
            stepFunction, stepRate));
    ArrayList<IrisPipelineState.VertexAttribute> attributes =
        new ArrayList<>();
    List<VertexFormatElement> elements = format.getElements();
    for (int location = 0; location < elements.size(); location++) {
      VertexFormatElement element = elements.get(location);
      attributes.add(new IrisPipelineState.VertexAttribute(location, 0,
          element.offset(), new IrisPipelineState.DataFormat(
              formatCacheName(element.format().name()))));
    }
    return new Layout(buffers, attributes);
  }

  static String formatCacheName(String enumName) {
    return Objects.requireNonNull(enumName, "enumName")
        .toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public record Layout(
      List<IrisPipelineState.VertexBufferLayout> buffers,
      List<IrisPipelineState.VertexAttribute> attributes) {
    public Layout {
      buffers = List.copyOf(buffers);
      attributes = List.copyOf(attributes);
    }
  }
}

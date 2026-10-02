package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisVertexLayoutCaptureTest {
  private static VertexFormatElement element(
      VertexFormatElement.Type type, VertexFormatElement.Usage usage,
      int count) {
    return VertexFormatElement.register(
        VertexFormatElement.findNextId(), 0, type, usage, count);
  }

  @Test
  void gpuFormatNamesHaveStableCacheSpelling() {
    assertEquals("rgba16-float",
        IrisVertexLayoutCapture.formatCacheName("RGBA16_FLOAT"));
    assertEquals("d32-float-s8-uint",
        IrisVertexLayoutCapture.formatCacheName("D32_FLOAT_S8_UINT"));
  }

  @Test
  void linkedNamesAndLocationsMatchIrisVertexFormatBinding() {
    VertexFormat format = VertexFormat.builder()
        .add("Position", element(VertexFormatElement.Type.FLOAT,
            VertexFormatElement.Usage.POSITION, 3))
        .add("iris_Entity", element(VertexFormatElement.Type.USHORT,
            VertexFormatElement.Usage.GENERIC, 4))
        .add("mc_midTexCoord", element(VertexFormatElement.Type.FLOAT,
            VertexFormatElement.Usage.GENERIC, 2))
        .build();

    assertEquals(List.of(
        new IrisVertexLayoutCapture.ShaderInput("iris_Position", 0,
            new IrisPipelineState.DataFormat("rgb32-float")),
        new IrisVertexLayoutCapture.ShaderInput("iris_Entity", 1,
            new IrisPipelineState.DataFormat("rgba16-uint")),
        new IrisVertexLayoutCapture.ShaderInput("mc_midTexCoord", 2,
            new IrisPipelineState.DataFormat("rg32-float"))),
        IrisVertexLayoutCapture.capture(format, false).shaderInputs());
    assertEquals("Position",
        IrisVertexLayoutCapture.capture(format, true).shaderInputs()
            .getFirst().linkedName());
  }

  @Test
  void integerShaderInputUsesRawMetalFormatForNormalizedStorage() {
    VertexFormat format = VertexFormat.builder()
        .add("Position", element(VertexFormatElement.Type.FLOAT,
            VertexFormatElement.Usage.POSITION, 3))
        .add("a_LightAndData", element(VertexFormatElement.Type.UBYTE,
            VertexFormatElement.Usage.COLOR, 4))
        .add("Color", element(VertexFormatElement.Type.UBYTE,
            VertexFormatElement.Usage.COLOR, 4))
        .build();
    IrisVertexLayoutCapture.Layout captured =
        IrisVertexLayoutCapture.capture(format, false);

    IrisVertexLayoutCapture.Layout resolved =
        IrisVertexLayoutCapture.resolveShaderInputFormats("""
            #version 330 core
            layout(location = 0) in vec3 iris_Position;
            // in vec4 a_LightAndData;
            layout(location = 1) flat in highp uvec4 a_LightAndData;
            in vec4 iris_Color;
            """, captured);

    assertEquals("rgba8-uint",
        resolved.shaderInputs().get(1).format().cacheName());
    assertEquals("rgba8-uint",
        resolved.attributes().get(1).format().cacheName());
    assertEquals("rgba8-unorm",
        resolved.shaderInputs().get(2).format().cacheName());
    assertEquals(captured.buffers(), resolved.buffers());
    assertEquals(captured.attributes().get(1).offsetBytes(),
        resolved.attributes().get(1).offsetBytes());
  }

  @Test
  void linkedOpenGlAttributesOverrideGuessedNamesAndDropInactiveElements() {
    VertexFormat format = VertexFormat.builder()
        .add("Position", element(VertexFormatElement.Type.FLOAT,
            VertexFormatElement.Usage.POSITION, 3))
        .add("Color", element(VertexFormatElement.Type.UBYTE,
            VertexFormatElement.Usage.COLOR, 4))
        .add("UV0", element(VertexFormatElement.Type.FLOAT,
            VertexFormatElement.Usage.UV, 2))
        .build();
    IrisVertexLayoutCapture.Layout linked =
        IrisVertexLayoutCapture.withLinkedAttributes(
            IrisVertexLayoutCapture.capture(format, false), List.of(
                new IrisVertexLayoutCapture.LinkedAttribute("Position", 0),
                new IrisVertexLayoutCapture.LinkedAttribute("texCoord", 2)));

    assertEquals(List.of(
        new IrisVertexLayoutCapture.ShaderInput("Position", 0,
            new IrisPipelineState.DataFormat("rgb32-float")),
        new IrisVertexLayoutCapture.ShaderInput("texCoord", 2,
            new IrisPipelineState.DataFormat("rg32-float"))),
        linked.shaderInputs());
    assertEquals(List.of(0, 2), linked.attributes().stream()
        .map(IrisPipelineState.VertexAttribute::location).toList());
    IrisVertexLayoutCapture.Layout generic =
        IrisVertexLayoutCapture.withLinkedAttributes(
            IrisVertexLayoutCapture.capture(format, false), List.of(
                new IrisVertexLayoutCapture.LinkedAttribute(
                    "missing", 7, 0x8B51)));
    assertEquals(List.of(new IrisPipelineState.VertexAttribute(7, 1, 0,
        new IrisPipelineState.DataFormat("rgb32-float"))),
        generic.attributes());
    assertEquals(new IrisPipelineState.VertexBufferLayout(1, 16,
            IrisPipelineState.StepFunction.CONSTANT, 0),
        generic.buffers().get(1));
    assertEquals(List.of(new IrisVertexLayoutCapture.ShaderInput(
        "missing", 7,
        new IrisPipelineState.DataFormat("rgb32-float"))),
        generic.shaderInputs());
  }

  @Test
  void linkedNameMapsSparseOpenGlLocationToPhysicalFormatElement() {
    VertexFormat format = VertexFormat.builder()
        .add("Position", element(VertexFormatElement.Type.FLOAT,
            VertexFormatElement.Usage.POSITION, 3))
        .add("Color", element(VertexFormatElement.Type.UBYTE,
            VertexFormatElement.Usage.COLOR, 4))
        .build();
    IrisVertexLayoutCapture.Layout linked =
        IrisVertexLayoutCapture.withLinkedAttributes(
            IrisVertexLayoutCapture.capture(format, true), List.of(
                new IrisVertexLayoutCapture.LinkedAttribute("Color", 7)));

    assertEquals(7, linked.attributes().getFirst().location());
    assertEquals("rgba8-unorm",
        linked.attributes().getFirst().format().cacheName());
    assertEquals(12, linked.attributes().getFirst().offsetBytes());
  }

  @Test
  void linkedIntegerGenericAttributeRetainsShaderAbiWithoutPhysicalElement() {
    VertexFormat format = VertexFormat.builder()
        .add("Position", element(VertexFormatElement.Type.FLOAT,
            VertexFormatElement.Usage.POSITION, 3))
        .build();
    IrisVertexLayoutCapture.Layout linked =
        IrisVertexLayoutCapture.withLinkedAttributes(
            IrisVertexLayoutCapture.capture(format, false), List.of(
                new IrisVertexLayoutCapture.LinkedAttribute(
                    "mc_Entity", 4, 0x8DC8)));

    assertEquals(IrisPipelineState.StepFunction.CONSTANT,
        linked.buffers().get(1).stepFunction());
    assertEquals(4, linked.attributes().getFirst().location());
    assertEquals(1, linked.attributes().getFirst().bufferIndex());
    assertEquals("rgba32-uint",
        linked.shaderInputs().getFirst().format().cacheName());
  }
}

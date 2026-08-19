package com.pebbles_boon.metalrender.compat.iris.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState;
import com.pebbles_boon.metalrender.compat.iris.IrisVertexLayoutCapture;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisDhLodRenderProgramMixinTest {
  @Test
  void terrainLayoutMatchesDistantHorizons320Abi() throws Exception {
    Field field = IrisDhLodRenderProgramMixin.class.getDeclaredField(
        "METALRENDER_DH_LOD_VERTEX_FORMAT");
    field.setAccessible(true);
    VertexFormat format = (VertexFormat) field.get(null);

    IrisVertexLayoutCapture.Layout layout =
        IrisVertexLayoutCapture.capture(format);

    assertEquals(List.of(new IrisPipelineState.VertexBufferLayout(0, 16,
        IrisPipelineState.StepFunction.PER_VERTEX, 0)), layout.buffers());
    assertEquals(List.of(
        new IrisPipelineState.VertexAttribute(0, 0, 0,
            new IrisPipelineState.DataFormat("rgba16-uint")),
        new IrisPipelineState.VertexAttribute(1, 0, 8,
            new IrisPipelineState.DataFormat("rgba8-unorm")),
        new IrisPipelineState.VertexAttribute(2, 0, 12,
            new IrisPipelineState.DataFormat("rgba8-uint"))),
        layout.attributes());
  }
}

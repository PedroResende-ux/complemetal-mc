package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.ArgumentBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.StageLayout;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DescriptorAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisMetalVertexBindingLayoutTest {
  @Test
  void movesVertexBufferZeroAwayFromDescriptorSetZero() {
    IrisPipelineState original = IrisMetalPipelineKeyTest.state();
    IrisMslArgumentLayout arguments = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.VERTEX, List.of(
            new ArgumentBinding(new DescriptorAddress(0, 0),
                ResourceKind.UNIFORM_BUFFER, 0, 0, -1)))));

    IrisMetalVertexBindingLayout layout =
        IrisMetalVertexBindingLayout.resolve(original, arguments);

    assertNotEquals(0, layout.metalBufferIndex(0));
    assertEquals(layout.metalBufferIndex(0),
        layout.state().vertexAttributes().getFirst().bufferIndex());
  }

  @Test
  void preservesAFreeCapturedIndex() {
    IrisPipelineState original = IrisMetalPipelineKeyTest.state();
    IrisMslArgumentLayout arguments = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.FRAGMENT, List.of(
            new ArgumentBinding(new DescriptorAddress(0, 0),
                ResourceKind.UNIFORM_BUFFER, 0, 0, -1)))));

    IrisMetalVertexBindingLayout layout =
        IrisMetalVertexBindingLayout.resolve(original, arguments);

    assertEquals(0, layout.metalBufferIndex(0));
  }
}

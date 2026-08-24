package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.StateValue;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisMetalShadowReplayPacketEncoderTest {
  @Test
  void encodesOneDeterministicGenerationSafeDrawPacket() {
    IrisShadowReplayBufferSnapshot buffers = new IrisShadowReplayBufferSnapshot(
        true, List.of(new IrisShadowReplayBufferSnapshot.BufferImage(
            0, 7, 1, 0, new byte[48])),
        List.of(new IrisShadowReplayBufferSnapshot.VertexBufferRef(0,
            new IrisShadowReplayBufferSnapshot.BufferRef(0))),
        Optional.empty(), Optional.empty(), Map.of(), Map.of(),
        List.of("indexed-buffer-snapshot-unavailable"));
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new IrisMslArgumentLayout.StageLayout(IrisShaderStage.VERTEX,
            List.of(new IrisMslArgumentLayout.ArgumentBinding(
                new IrisSpirvResourceLayout.UniformLocation(0, 0),
                IrisSpirvResourceLayout.ResourceKind.UNIFORM,
                0, 0, -1)))));
    IrisMetalVertexBindingLayout vertexLayout =
        IrisMetalVertexBindingLayout.resolve(
            IrisMetalPipelineKeyTest.state(), msl);
    IrisShadowReplayArgumentTable table = new IrisShadowReplayArgumentTable(
        List.of(new IrisShadowReplayArgumentTable.StageTable(
            IrisShaderStage.VERTEX, List.of(
                new IrisShadowReplayArgumentTable.BoundArgument(0, 0,
                    new IrisShadowReplayArgumentTable.InlineUniform(
                        new byte[64]))))), List.of());
    IrisDynamicDrawState dynamic = new IrisDynamicDrawState(
        StateValue.known(new IrisDynamicDrawState.Rect(0, 0, 16, 16)),
        StateValue.known(false),
        StateValue.known(new IrisDynamicDrawState.Rect(0, 0, 0, 0)));

    byte[] first = IrisMetalShadowReplayPacketEncoder.encode(
        new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL), dynamic, buffers,
        new IrisShadowReplayTextureSnapshot(true, Map.of(),
            List.of("sampled-texture-snapshot-unavailable")), vertexLayout,
        table, 16, 16);
    byte[] second = IrisMetalShadowReplayPacketEncoder.encode(
        new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL), dynamic, buffers,
        new IrisShadowReplayTextureSnapshot(true, Map.of(),
            List.of("sampled-texture-snapshot-unavailable")), vertexLayout,
        table, 16, 16);
    IrisShadowReplayBufferSnapshot withUnused =
        new IrisShadowReplayBufferSnapshot(true, List.of(
            new IrisShadowReplayBufferSnapshot.BufferImage(
                0, 7, 1, 0, new byte[48]),
            new IrisShadowReplayBufferSnapshot.BufferImage(
                1, 8, 2, 0, new byte[96])),
            buffers.vertexBuffers(), Optional.empty(), Optional.empty(),
            Map.of(), Map.of(), List.of());
    byte[] pruned = IrisMetalShadowReplayPacketEncoder.encode(
        new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL), dynamic, withUnused,
        IrisShadowReplayTextureSnapshot.emptyEnabled(), vertexLayout,
        table, 16, 16);

    assertTrue(java.util.Arrays.equals(first, second));
    assertTrue(java.util.Arrays.equals(first, pruned));
    ByteBuffer header = ByteBuffer.wrap(first);
    assertEquals(IrisMetalShadowReplayPacketEncoder.MAGIC, header.getInt());
    assertEquals(IrisMetalShadowReplayPacketEncoder.SCHEMA, header.getInt());
    assertEquals(16, header.getInt());
    assertEquals(16, header.getInt());
  }

  @Test
  void refusesUnknownDrawArguments() {
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new IrisMslArgumentLayout.StageLayout(IrisShaderStage.FRAGMENT,
            List.of(new IrisMslArgumentLayout.ArgumentBinding(
                new IrisSpirvResourceLayout.DescriptorAddress(0, 0),
                IrisSpirvResourceLayout.ResourceKind.UNIFORM_BUFFER,
                0, 0, -1)))));
    IrisMetalVertexBindingLayout layout =
        IrisMetalVertexBindingLayout.resolve(
            IrisMetalPipelineKeyTest.state(), msl);
    IrisDynamicDrawState dynamic = new IrisDynamicDrawState(
        StateValue.known(new IrisDynamicDrawState.Rect(0, 0, 1, 1)),
        StateValue.known(false), StateValue.known(
            new IrisDynamicDrawState.Rect(0, 0, 0, 0)));
    assertThrows(IllegalArgumentException.class, () ->
        IrisMetalShadowReplayPacketEncoder.encode(
            new IrisExecutionCommand.UnknownDraw(4), dynamic,
            new IrisShadowReplayBufferSnapshot(true, List.of(), List.of(),
                Optional.empty(), Optional.empty(), Map.of(), Map.of(),
                List.of()),
            IrisShadowReplayTextureSnapshot.emptyEnabled(), layout,
            new IrisShadowReplayArgumentTable(List.of(
                new IrisShadowReplayArgumentTable.StageTable(
                    IrisShaderStage.FRAGMENT, List.of())), List.of()), 1, 1));
  }

  @Test
  void retainsTextureBufferStorageInThePrunedPacket() {
    IrisShadowReplayBufferSnapshot buffers =
        new IrisShadowReplayBufferSnapshot(true, List.of(
            new IrisShadowReplayBufferSnapshot.BufferImage(
                0, 7, 1, 0, new byte[48]),
            new IrisShadowReplayBufferSnapshot.BufferImage(
                1, 71, 2, 0, new byte[16])),
            List.of(new IrisShadowReplayBufferSnapshot.VertexBufferRef(0,
                new IrisShadowReplayBufferSnapshot.BufferRef(0))),
            Optional.empty(), Optional.empty(), Map.of(),
            Map.of(92, new IrisShadowReplayBufferSnapshot.BufferRef(1)),
            List.of());
    IrisShadowReplayArgumentTable arguments =
        new IrisShadowReplayArgumentTable(List.of(
            new IrisShadowReplayArgumentTable.StageTable(
                IrisShaderStage.VERTEX, List.of(
                    new IrisShadowReplayArgumentTable.BoundArgument(0, 3,
                        new IrisShadowReplayArgumentTable.TextureBufferImage(
                            1, 0x822E))))), List.of());

    assertEquals(2,
        IrisMetalShadowReplayPacketEncoder.requiredBufferImages(
            buffers, arguments).size());
  }

  @Test
  void requiresGraphOverrideForMetadataOnlyTextureReference() {
    IrisShadowReplayBufferSnapshot buffers = new IrisShadowReplayBufferSnapshot(
        true, List.of(new IrisShadowReplayBufferSnapshot.BufferImage(
            0, 7, 1, 0, new byte[48])),
        List.of(new IrisShadowReplayBufferSnapshot.VertexBufferRef(0,
            new IrisShadowReplayBufferSnapshot.BufferRef(0))),
        Optional.empty(), Optional.empty(), Map.of(), Map.of(), List.of());
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new IrisMslArgumentLayout.StageLayout(IrisShaderStage.VERTEX,
            List.of())));
    IrisMetalVertexBindingLayout layout =
        IrisMetalVertexBindingLayout.resolve(
            IrisMetalPipelineKeyTest.state(), msl);
    IrisShadowReplayArgumentTable arguments =
        new IrisShadowReplayArgumentTable(List.of(
            new IrisShadowReplayArgumentTable.StageTable(
                IrisShaderStage.FRAGMENT, List.of(
                    new IrisShadowReplayArgumentTable.BoundArgument(0, 3,
                        new IrisShadowReplayArgumentTable.TextureImage(19))))),
            List.of());
    IrisShadowReplayTextureSnapshot textures =
        new IrisShadowReplayTextureSnapshot(true, Map.of(19,
            IrisGlTextureMirror.TextureSnapshot.fromGraphReference(
                19, 3, "rgba16-float", 4, 2, 8, 0, 0)), List.of());
    IrisDynamicDrawState dynamic = new IrisDynamicDrawState(
        StateValue.known(new IrisDynamicDrawState.Rect(0, 0, 4, 2)),
        StateValue.known(false),
        StateValue.known(new IrisDynamicDrawState.Rect(0, 0, 0, 0)));
    IrisExecutionCommand command = new IrisExecutionCommand.DrawArrays(
        4, 0, 3, 1, 0, IrisExecutionCommand.Source.DIRECT_GL);

    assertThrows(IllegalArgumentException.class, () ->
        IrisMetalShadowReplayPacketEncoder.encode(command, dynamic, buffers,
            textures, layout, arguments, 4, 2));
    assertTrue(IrisMetalShadowReplayPacketEncoder.encode(command, dynamic,
        buffers, textures, layout, arguments, 4, 2,
        java.util.Set.of(19)).length > 0);
  }
}

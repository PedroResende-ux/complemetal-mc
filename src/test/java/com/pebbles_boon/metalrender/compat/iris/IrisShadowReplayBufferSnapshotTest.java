package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.BufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.IndexedBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureUnitBinding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class IrisShadowReplayBufferSnapshotTest {
  @Test
  void capturesExactDrawTimeVertexAndIndexedRanges() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(8, 256, 64);
    assertTrue(mirror.write(11, 16, 0, 16,
        ByteBuffer.wrap(sequence(16))));
    assertTrue(mirror.write(12, 16, 0, 16,
        ByteBuffer.wrap(sequence(16))));
    long vertexGeneration = mirror.generation(11);
    long uniformGeneration = mirror.generation(12);

    IndexedBufferBinding uniformSlot = new IndexedBufferBinding(
        IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER, 3);
    IrisGlResourceBindingSnapshot resources = snapshot(
        Map.of(uniformSlot,
            new BufferBinding(12, 4, 8, true, uniformGeneration)),
        Map.of(), Map.of());
    IrisVertexInputBindings vertices = IrisVertexInputBindings.complete(
        List.of(new IrisVertexInputBindings.BufferSlice(0, 11, 2, 8,
            vertexGeneration)), null);

    IrisShadowReplayBufferSnapshot captured =
        IrisShadowReplayBufferSnapshot.capture(
            new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
                IrisExecutionCommand.Source.DIRECT_GL),
            vertices, resources, mirror);

    assertTrue(captured.complete());
    assertEquals(2, captured.images().size());
    assertEquals(16, captured.totalBytes());
    assertArrayEquals(new byte[] {2, 3, 4, 5, 6, 7, 8, 9},
        captured.images().get(0).bytes());
    assertArrayEquals(new byte[] {4, 5, 6, 7, 8, 9, 10, 11},
        captured.images().get(1).bytes());
    assertEquals(1,
        captured.indexedBuffers().get(uniformSlot).imageId());
  }

  @Test
  void failsClosedOnStaleAndPartiallyInitializedBuffers() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(8, 256, 64);
    assertTrue(mirror.write(21, 16, 0, 8,
        ByteBuffer.wrap(sequence(8))));
    long staleGeneration = mirror.generation(21);
    assertTrue(mirror.write(21, 16, 8, 8,
        ByteBuffer.wrap(sequence(8))));

    IndexedBufferBinding storageSlot = new IndexedBufferBinding(
        IrisGlResourceBindingTracker.GL_SHADER_STORAGE_BUFFER, 1);
    IrisShadowReplayBufferSnapshot captured =
        IrisShadowReplayBufferSnapshot.capture(
            new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
                IrisExecutionCommand.Source.DIRECT_GL),
            IrisVertexInputBindings.complete(List.of(
                new IrisVertexInputBindings.BufferSlice(0, 21, 0, 8,
                    staleGeneration)), null),
            snapshot(Map.of(storageSlot,
                new BufferBinding(22, 0, 0, false, 0)), Map.of(), Map.of()),
            mirror);

    assertFalse(captured.complete());
    assertEquals(List.of("indexed-buffer-generation-missing",
            "vertex-buffer-snapshot-unavailable"),
        captured.blockers());
    assertEquals(List.of("vertex-buffer-snapshot-unavailable"),
        captured.drawBlockers());
    assertFalse(captured.drawComplete());
  }

  @Test
  void deduplicatesRangesAndCapturesOnlyBoundTextureBuffers() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(8, 256, 64);
    assertTrue(mirror.write(31, 8, 0, 8,
        ByteBuffer.wrap(sequence(8))));
    long generation = mirror.generation(31);
    IndexedBufferBinding uniformSlot = new IndexedBufferBinding(
        IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER, 0);
    Map<Integer, TextureBufferBinding> textureBuffers = Map.of(
        71, new TextureBufferBinding(0x8C2A, 0x8058, 31, generation),
        72, new TextureBufferBinding(0x8C2A, 0x8058, 31, generation));
    Map<Integer, TextureUnitBinding> textureUnits = Map.of(
        2, new TextureUnitBinding(0x8C2A, 71, 0));

    IrisShadowReplayBufferSnapshot captured =
        IrisShadowReplayBufferSnapshot.capture(
            new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
                IrisExecutionCommand.Source.DIRECT_GL),
            IrisVertexInputBindings.complete(List.of(), null),
            snapshot(Map.of(uniformSlot,
                new BufferBinding(31, 0, 0, false, generation)),
                textureUnits, textureBuffers), mirror);

    assertTrue(captured.complete());
    assertEquals(1, captured.images().size());
    assertEquals(1, captured.textureBuffers().size());
    assertTrue(captured.textureBuffers().containsKey(71));
    assertEquals(captured.indexedBuffers().get(uniformSlot),
        captured.textureBuffers().get(71));
  }

  @Test
  void appendsGenericOpenGlValuesAsConstantMetalVertexBuffer() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(8, 256, 64);
    assertTrue(mirror.write(41, 16, 0, 16,
        ByteBuffer.wrap(sequence(16))));
    IrisProgramIdentityRegistry.ProgramDescriptor descriptor =
        new IrisProgramIdentityRegistry.ProgramDescriptor(
            IrisPipelineState.PassKind.LINKED_GRAPHICS, "generic", false,
            List.of(
                new IrisPipelineState.VertexBufferLayout(0, 16,
                    IrisPipelineState.StepFunction.PER_VERTEX, 0),
                new IrisPipelineState.VertexBufferLayout(1, 16,
                    IrisPipelineState.StepFunction.CONSTANT, 0)),
            List.of(
                new IrisPipelineState.VertexAttribute(0, 0, 0,
                    new IrisPipelineState.DataFormat("rgba32-float")),
                new IrisPipelineState.VertexAttribute(2, 1, 0,
                    new IrisPipelineState.DataFormat("rgba32-float"))));
    IrisGlGenericAttributeTracker generic =
        new IrisGlGenericAttributeTracker();
    generic.vertexAttribute4f(2, 3.0F, 4.0F, 5.0F, 6.0F);

    IrisShadowReplayBufferSnapshot captured =
        IrisShadowReplayBufferSnapshot.capture(
            new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
                IrisExecutionCommand.Source.DIRECT_GL),
            IrisVertexInputBindings.complete(List.of(
                new IrisVertexInputBindings.BufferSlice(0, 41, 0, 16,
                    mirror.generation(41))), null),
            snapshot(Map.of(), Map.of(), Map.of()), mirror, descriptor,
            generic);

    assertTrue(captured.complete());
    assertEquals(List.of(0, 1), captured.vertexBuffers().stream()
        .map(IrisShadowReplayBufferSnapshot.VertexBufferRef::slot).toList());
    IrisShadowReplayBufferSnapshot.BufferImage constant =
        captured.images().get(1);
    assertEquals(0, constant.glBuffer());
    ByteBuffer values = ByteBuffer.wrap(constant.bytes())
        .order(ByteOrder.nativeOrder());
    assertEquals(3.0F, values.getFloat(0));
    assertEquals(6.0F, values.getFloat(12));
  }

  @Test
  void sharedBufferImageKeepsLogicalLengthWithoutInlinePayload() {
    IrisShadowReplayBufferSnapshot.BufferImage inline =
        new IrisShadowReplayBufferSnapshot.BufferImage(
            0, 51, 7, 4, sequence(24));

    IrisShadowReplayBufferSnapshot.BufferImage shared =
        inline.asShared(91);

    assertTrue(shared.shared());
    assertEquals(91, shared.sharedHandle());
    assertEquals(24, shared.byteLength());
    assertEquals(0, shared.bytes().length);
    assertEquals(51, shared.glBuffer());
    assertEquals(7, shared.generation());
    assertEquals(4, shared.sourceOffsetBytes());
  }

  @Test
  void reusesGenerationQualifiedOwnedRangeWithinOneFrame() {
    IrisGlBufferMirror mirror = new IrisGlBufferMirror(8, 256, 64);
    assertTrue(mirror.write(61, 16, 0, 16,
        ByteBuffer.wrap(sequence(16))));
    IrisVertexInputBindings vertices = IrisVertexInputBindings.complete(
        List.of(new IrisVertexInputBindings.BufferSlice(0, 61, 0, 16,
            mirror.generation(61))), null);
    IrisShadowReplayBufferSnapshot.RetainedCapture retained =
        new IrisShadowReplayBufferSnapshot.RetainedCapture();

    IrisShadowReplayBufferSnapshot first =
        IrisShadowReplayBufferSnapshot.capture(
            new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
                IrisExecutionCommand.Source.DIRECT_GL),
            vertices, snapshot(Map.of(), Map.of(), Map.of()), mirror,
            null, null, retained);
    IrisShadowReplayBufferSnapshot second =
        IrisShadowReplayBufferSnapshot.capture(
            new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
                IrisExecutionCommand.Source.DIRECT_GL),
            vertices, snapshot(Map.of(), Map.of(), Map.of()), mirror,
            null, null, retained);

    assertTrue(first.complete());
    assertTrue(second.complete());
    assertSame(first.images().getFirst().ownedBytes(),
        second.images().getFirst().ownedBytes());
    assertEquals(1, retained.entries());
    assertTrue(retained.hits() >= 1);
  }

  private static IrisGlResourceBindingSnapshot snapshot(
      Map<IndexedBufferBinding, BufferBinding> indexed,
      Map<Integer, TextureUnitBinding> textureUnits,
      Map<Integer, TextureBufferBinding> textureBuffers) {
    return new IrisGlResourceBindingSnapshot(1, Map.of(), Map.of(), Map.of(),
        Map.of(), textureUnits, textureBuffers, Map.of(), indexed);
  }

  private static byte[] sequence(int length) {
    byte[] values = new byte[length];
    for (int index = 0; index < length; index++) {
      values[index] = (byte) index;
    }
    return values;
  }
}

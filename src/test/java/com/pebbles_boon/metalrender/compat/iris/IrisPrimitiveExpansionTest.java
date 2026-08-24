package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisPrimitiveExpansionTest {
  @Test
  void expandsArrayLineLoopToClosedIndexedLineStrip() {
    IrisExecutionCommand.DrawArrays source =
        new IrisExecutionCommand.DrawArrays(0x0002, 4, 3, 2, 7,
            IrisExecutionCommand.Source.DIRECT_GL);

    IrisPrimitiveExpansion.Result result = IrisPrimitiveExpansion.expand(
        source, emptyEnabled());

    assertTrue(result.expanded());
    IrisExecutionCommand.DrawIndexed command = assertInstanceOf(
        IrisExecutionCommand.DrawIndexed.class, result.command());
    assertEquals(0x0003, command.primitiveMode());
    assertEquals(4, command.indexCount());
    assertEquals(4, command.indexElementBytes());
    assertEquals(2, command.instanceCount());
    assertEquals(7, command.baseInstance());
    assertArrayEquals(new int[] {4, 5, 6, 4},
        syntheticIndices(result.buffers()));
  }

  @Test
  void expandsIndexedTriangleFanUsingCapturedSourceOffset() {
    ByteBuffer sourceBytes = ByteBuffer.allocate(8)
        .order(ByteOrder.nativeOrder());
    sourceBytes.putShort((short) 7).putShort((short) 9)
        .putShort((short) 11).putShort((short) 13);
    IrisShadowReplayBufferSnapshot buffers = indexedSnapshot(8,
        sourceBytes.array());
    IrisExecutionCommand.DrawIndexed source =
        new IrisExecutionCommand.DrawIndexed(0x0006, 8, 4, 2, -3, 1, 0,
            IrisExecutionCommand.Source.MOJANG_COMMAND_ENCODER);

    IrisPrimitiveExpansion.Result result = IrisPrimitiveExpansion.expand(
        source, buffers);

    assertTrue(result.expanded());
    IrisExecutionCommand.DrawIndexed command = assertInstanceOf(
        IrisExecutionCommand.DrawIndexed.class, result.command());
    assertEquals(0x0004, command.primitiveMode());
    assertEquals(6, command.indexCount());
    assertEquals(-3, command.baseVertex());
    assertArrayEquals(new int[] {7, 9, 11, 7, 11, 13},
        syntheticIndices(result.buffers()));
  }

  @Test
  void widensUnsignedByteIndicesForNativeMetal() {
    IrisShadowReplayBufferSnapshot buffers = indexedSnapshot(0,
        new byte[] {0, 127, (byte) 255});
    IrisExecutionCommand.DrawIndexed source =
        new IrisExecutionCommand.DrawIndexed(0x0004, 0, 3, 1, 0, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL);

    IrisPrimitiveExpansion.Result result = IrisPrimitiveExpansion.expand(
        source, buffers);

    IrisExecutionCommand.DrawIndexed command = assertInstanceOf(
        IrisExecutionCommand.DrawIndexed.class, result.command());
    assertEquals(4, command.indexElementBytes());
    assertArrayEquals(new int[] {0, 127, 255},
        syntheticIndices(result.buffers()));
  }

  @Test
  void expandsEveryMultiDrawIntoOneBoundedSyntheticIndexImage() {
    ByteBuffer sourceBytes = ByteBuffer.allocate(12)
        .order(ByteOrder.nativeOrder());
    for (int value = 1; value <= 6; value++) {
      sourceBytes.putShort((short) value);
    }
    IrisExecutionCommand.MultiDrawIndexed source =
        new IrisExecutionCommand.MultiDrawIndexed(0x0002, 2,
            new long[] {0, 6}, new int[] {3, 3}, new int[] {0, 5},
            IrisExecutionCommand.Source.MOJANG_COMMAND_ENCODER);

    IrisPrimitiveExpansion.Result result = IrisPrimitiveExpansion.expand(
        source, indexedSnapshot(0, sourceBytes.array()));

    IrisExecutionCommand.MultiDrawIndexed command = assertInstanceOf(
        IrisExecutionCommand.MultiDrawIndexed.class, result.command());
    assertEquals(0x0003, command.primitiveMode());
    assertArrayEquals(new long[] {0, 16}, command.indexOffsetsBytes());
    assertArrayEquals(new int[] {4, 4}, command.indexCounts());
    assertArrayEquals(new int[] {1, 2, 3, 1, 4, 5, 6, 4},
        syntheticIndices(result.buffers()));
  }

  @Test
  void failsClosedWhenCapturedIndexRangeDoesNotCoverDraw() {
    IrisExecutionCommand.DrawIndexed source =
        new IrisExecutionCommand.DrawIndexed(0x0006, 0, 4, 2, 0, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL);

    IrisPrimitiveExpansion.Result result = IrisPrimitiveExpansion.expand(
        source, indexedSnapshot(0, new byte[4]));

    assertFalse(result.expanded());
    assertEquals(source, result.command());
    assertEquals(List.of("primitive-index-expansion-range-invalid"),
        result.buffers().drawBlockers());
    assertFalse(result.buffers().drawComplete());
  }

  private static IrisShadowReplayBufferSnapshot emptyEnabled() {
    return new IrisShadowReplayBufferSnapshot(true, List.of(), List.of(),
        Optional.empty(), Optional.empty(), Map.of(), Map.of(), List.of());
  }

  private static IrisShadowReplayBufferSnapshot indexedSnapshot(
      long sourceOffset, byte[] bytes) {
    IrisShadowReplayBufferSnapshot.BufferRef reference =
        new IrisShadowReplayBufferSnapshot.BufferRef(0);
    return new IrisShadowReplayBufferSnapshot(true, List.of(
        new IrisShadowReplayBufferSnapshot.BufferImage(0, 17, 3,
            sourceOffset, bytes)), List.of(), Optional.of(reference),
        Optional.empty(), Map.of(), Map.of(), List.of());
  }

  private static int[] syntheticIndices(
      IrisShadowReplayBufferSnapshot buffers) {
    IrisShadowReplayBufferSnapshot.BufferImage image = buffers.images()
        .get(buffers.indexBuffer().orElseThrow().imageId());
    ByteBuffer bytes = ByteBuffer.wrap(image.bytes())
        .order(ByteOrder.nativeOrder());
    int[] values = new int[bytes.remaining() / Integer.BYTES];
    for (int index = 0; index < values.length; index++) {
      values[index] = bytes.getInt();
    }
    return values;
  }
}

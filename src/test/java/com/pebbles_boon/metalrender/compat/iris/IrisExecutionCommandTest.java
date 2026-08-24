package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class IrisExecutionCommandTest {
  @Test
  void mapsMinecraftNullIndexConventionToArrayDraw() {
    IrisExecutionCommand.DrawArrays command = assertInstanceOf(
        IrisExecutionCommand.DrawArrays.class,
        IrisMojangDrawCommand.fromBuffers(4, 7, 99, 6, 0, 2, 3));
    assertEquals(7, command.firstVertex());
    assertEquals(6, command.vertexCount());
    assertEquals(2, command.instanceCount());
    assertEquals(3, command.baseInstance());
  }

  @Test
  void mapsMinecraftIndexedAndArrayIndirectConventions() {
    IrisExecutionCommand.DrawIndexed indexed = assertInstanceOf(
        IrisExecutionCommand.DrawIndexed.class,
        IrisMojangDrawCommand.fromBuffers(4, -2, 5, 9, 2, 1, 0));
    assertEquals(10, indexed.indexOffsetBytes());
    assertEquals(-2, indexed.baseVertex());

    IrisExecutionCommand.IndirectDraw arrays =
        IrisMojangDrawCommand.indirect(4, 0, 12, 32, 2);
    assertEquals(0, arrays.indexElementBytes());
  }

  @Test
  void defensivelyCopiesBoundedMultiDrawArguments() {
    long[] offsets = {0, 12};
    int[] counts = {3, 6};
    int[] bases = {0, 4};
    IrisExecutionCommand.MultiDrawIndexed command =
        new IrisExecutionCommand.MultiDrawIndexed(4, 2, offsets, counts,
            bases, IrisExecutionCommand.Source.MOJANG_COMMAND_ENCODER);
    offsets[0] = 99;
    counts[0] = 99;
    assertEquals(0, command.indexOffsetsBytes()[0]);
    assertEquals(3, command.indexCounts()[0]);
  }

  @Test
  void rejectsUnboundedOrInvalidExecutionArguments() {
    assertThrows(IllegalArgumentException.class,
        () -> new IrisExecutionCommand.DrawIndexed(4, 0, 3, 3, 0, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL));
    assertThrows(IllegalArgumentException.class,
        () -> new IrisExecutionCommand.Dispatch(0, 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> new IrisExecutionCommand.MultiDrawIndexed(4, 2,
            new long[IrisExecutionCommand.MAX_MULTI_DRAW_COUNT + 1],
            new int[IrisExecutionCommand.MAX_MULTI_DRAW_COUNT + 1],
            new int[IrisExecutionCommand.MAX_MULTI_DRAW_COUNT + 1],
            IrisExecutionCommand.Source.MOJANG_COMMAND_ENCODER));
  }
}

package com.pebbles_boon.metalrender.compat.iris;

/**
 * Converts Minecraft's nullable-index draw convention into explicit replay
 * commands. In {@code GlCommandEncoder}, a null index type means an array draw
 * and {@code baseVertex} is passed to OpenGL as {@code firstVertex}.
 */
public final class IrisMojangDrawCommand {
  private IrisMojangDrawCommand() {
  }

  public static IrisExecutionCommand.Draw fromBuffers(int primitiveMode,
      int baseVertex, int firstIndex, int count, int indexElementBytes,
      int instanceCount, int baseInstance) {
    if (indexElementBytes == 0) {
      return new IrisExecutionCommand.DrawArrays(primitiveMode, baseVertex,
          count, instanceCount, baseInstance,
          IrisExecutionCommand.Source.MOJANG_COMMAND_ENCODER);
    }
    long indexOffset = Math.multiplyExact((long) firstIndex,
        indexElementBytes);
    return new IrisExecutionCommand.DrawIndexed(primitiveMode, indexOffset,
        count, indexElementBytes, baseVertex, instanceCount, baseInstance,
        IrisExecutionCommand.Source.MOJANG_COMMAND_ENCODER);
  }

  public static IrisExecutionCommand.IndirectDraw indirect(int primitiveMode,
      int indexElementBytes, int indirectGlBuffer, long offsetBytes,
      int drawCount) {
    return new IrisExecutionCommand.IndirectDraw(primitiveMode,
        indexElementBytes, indirectGlBuffer, offsetBytes, drawCount,
        IrisExecutionCommand.Source.INDIRECT_BUFFER);
  }
}

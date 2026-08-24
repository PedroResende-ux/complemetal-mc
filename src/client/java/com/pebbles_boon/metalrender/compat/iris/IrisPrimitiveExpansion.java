package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Converts OpenGL-only primitive/index encodings into exact Metal draw input.
 *
 * <p>Metal has no {@code GL_LINE_LOOP}, {@code GL_TRIANGLE_FAN}, or unsigned
 * byte index type. The conversion is performed against the immutable
 * draw-time buffer snapshot, before geometry can be promoted to a resident
 * Metal buffer. It never reads a live GL object and fails closed when the
 * captured range cannot prove the requested indices.</p>
 */
public final class IrisPrimitiveExpansion {
  static final int GL_LINE_LOOP = 0x0002;
  static final int GL_LINE_STRIP = 0x0003;
  static final int GL_TRIANGLES = 0x0004;
  static final int GL_TRIANGLE_FAN = 0x0006;
  private static final int OUTPUT_INDEX_BYTES = Integer.BYTES;

  private IrisPrimitiveExpansion() {
  }

  public static Result expand(IrisExecutionCommand command,
      IrisShadowReplayBufferSnapshot buffers) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(buffers, "buffers");
    if (!buffers.captureEnabled() || !requiresExpansion(command)) {
      return new Result(command, buffers, false);
    }
    try {
      if (command instanceof IrisExecutionCommand.DrawArrays draw) {
        return expandArrays(draw, buffers);
      }
      if (command instanceof IrisExecutionCommand.DrawIndexed draw) {
        return expandIndexed(draw, buffers);
      }
      if (command instanceof IrisExecutionCommand.MultiDrawIndexed draw) {
        return expandMultiIndexed(draw, buffers);
      }
      return blocked(command, buffers,
          "primitive-index-expansion-command-unsupported");
    } catch (ArithmeticException | IllegalArgumentException failure) {
      return blocked(command, buffers,
          "primitive-index-expansion-range-invalid");
    }
  }

  private static boolean requiresExpansion(IrisExecutionCommand command) {
    if (!(command instanceof IrisExecutionCommand.Draw draw)) {
      return false;
    }
    if (draw.primitiveMode() == GL_LINE_LOOP
        || draw.primitiveMode() == GL_TRIANGLE_FAN) {
      return true;
    }
    if (command instanceof IrisExecutionCommand.DrawIndexed indexed) {
      return indexed.indexElementBytes() == 1;
    }
    if (command instanceof IrisExecutionCommand.MultiDrawIndexed indexed) {
      return indexed.indexElementBytes() == 1;
    }
    return false;
  }

  private static Result expandArrays(IrisExecutionCommand.DrawArrays draw,
      IrisShadowReplayBufferSnapshot buffers) {
    int outputCount = expandedCount(draw.primitiveMode(), draw.vertexCount());
    if (outputCount == 0) {
      return blocked(draw, buffers, "primitive-index-expansion-empty-draw");
    }
    ByteBuffer output = allocateOutput(outputCount);
    if (draw.primitiveMode() == GL_LINE_LOOP) {
      for (int index = 0; index < draw.vertexCount(); index++) {
        putVertex(output, draw.firstVertex(), index);
      }
      output.putInt(draw.firstVertex());
    } else if (draw.primitiveMode() == GL_TRIANGLE_FAN) {
      for (int triangle = 1; triangle < draw.vertexCount() - 1; triangle++) {
        output.putInt(draw.firstVertex());
        putVertex(output, draw.firstVertex(), triangle);
        putVertex(output, draw.firstVertex(), triangle + 1);
      }
    } else {
      throw new IllegalArgumentException("array topology does not expand");
    }
    IrisShadowReplayBufferSnapshot expanded = appendIndexImage(buffers,
        output.array());
    IrisExecutionCommand.DrawIndexed command =
        new IrisExecutionCommand.DrawIndexed(
            effectivePrimitive(draw.primitiveMode()), 0, outputCount,
            OUTPUT_INDEX_BYTES, 0, draw.instanceCount(), draw.baseInstance(),
            draw.source());
    return new Result(command, expanded, true);
  }

  private static Result expandIndexed(IrisExecutionCommand.DrawIndexed draw,
      IrisShadowReplayBufferSnapshot buffers) {
    SourceIndices source = sourceIndices(buffers, draw.indexOffsetBytes(),
        draw.indexCount(), draw.indexElementBytes());
    int outputCount = expandedCount(draw.primitiveMode(), draw.indexCount());
    if (outputCount == 0) {
      return blocked(draw, buffers, "primitive-index-expansion-empty-draw");
    }
    ByteBuffer output = allocateOutput(outputCount);
    putExpanded(output, draw.primitiveMode(), source);
    IrisShadowReplayBufferSnapshot expanded = appendIndexImage(buffers,
        output.array());
    IrisExecutionCommand.DrawIndexed command =
        new IrisExecutionCommand.DrawIndexed(
            effectivePrimitive(draw.primitiveMode()), 0, outputCount,
            OUTPUT_INDEX_BYTES, draw.baseVertex(), draw.instanceCount(),
            draw.baseInstance(), draw.source());
    return new Result(command, expanded, true);
  }

  private static Result expandMultiIndexed(
      IrisExecutionCommand.MultiDrawIndexed draw,
      IrisShadowReplayBufferSnapshot buffers) {
    long[] sourceOffsets = draw.indexOffsetsBytes();
    int[] sourceCounts = draw.indexCounts();
    int[] bases = draw.baseVertices();
    int[] outputCounts = new int[sourceCounts.length];
    long[] outputOffsets = new long[sourceCounts.length];
    long totalCount = 0;
    for (int index = 0; index < sourceCounts.length; index++) {
      outputCounts[index] = expandedCount(draw.primitiveMode(),
          sourceCounts[index]);
      if (outputCounts[index] == 0) {
        return blocked(draw, buffers,
            "primitive-index-expansion-empty-draw");
      }
      outputOffsets[index] = Math.multiplyExact(totalCount,
          OUTPUT_INDEX_BYTES);
      totalCount = Math.addExact(totalCount, outputCounts[index]);
    }
    if (totalCount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("expanded index count overflow");
    }
    ByteBuffer output = allocateOutput((int) totalCount);
    for (int index = 0; index < sourceCounts.length; index++) {
      SourceIndices source = sourceIndices(buffers, sourceOffsets[index],
          sourceCounts[index], draw.indexElementBytes());
      putExpanded(output, draw.primitiveMode(), source);
    }
    IrisShadowReplayBufferSnapshot expanded = appendIndexImage(buffers,
        output.array());
    IrisExecutionCommand.MultiDrawIndexed command =
        new IrisExecutionCommand.MultiDrawIndexed(
            effectivePrimitive(draw.primitiveMode()), OUTPUT_INDEX_BYTES,
            outputOffsets, outputCounts, bases, draw.source());
    return new Result(command, expanded, true);
  }

  private static int expandedCount(int primitiveMode, int inputCount) {
    if (inputCount < 0) {
      throw new IllegalArgumentException("negative input count");
    }
    return switch (primitiveMode) {
      case GL_LINE_LOOP -> inputCount < 2 ? 0
          : Math.addExact(inputCount, 1);
      case GL_TRIANGLE_FAN -> inputCount < 3 ? 0
          : Math.multiplyExact(inputCount - 2, 3);
      default -> inputCount;
    };
  }

  private static int effectivePrimitive(int primitiveMode) {
    return switch (primitiveMode) {
      case GL_LINE_LOOP -> GL_LINE_STRIP;
      case GL_TRIANGLE_FAN -> GL_TRIANGLES;
      default -> primitiveMode;
    };
  }

  private static void putExpanded(ByteBuffer output, int primitiveMode,
      SourceIndices source) {
    if (primitiveMode == GL_LINE_LOOP) {
      for (int index = 0; index < source.count(); index++) {
        output.putInt(source.get(index));
      }
      output.putInt(source.get(0));
      return;
    }
    if (primitiveMode == GL_TRIANGLE_FAN) {
      int center = source.get(0);
      for (int triangle = 1; triangle < source.count() - 1; triangle++) {
        output.putInt(center);
        output.putInt(source.get(triangle));
        output.putInt(source.get(triangle + 1));
      }
      return;
    }
    for (int index = 0; index < source.count(); index++) {
      output.putInt(source.get(index));
    }
  }

  private static SourceIndices sourceIndices(
      IrisShadowReplayBufferSnapshot buffers, long absoluteOffset,
      int count, int elementBytes) {
    IrisShadowReplayBufferSnapshot.BufferRef reference = buffers.indexBuffer()
        .orElseThrow(() -> new IllegalArgumentException(
            "index buffer was not captured"));
    IrisShadowReplayBufferSnapshot.BufferImage image =
        buffers.images().get(reference.imageId());
    if (image.shared()) {
      throw new IllegalArgumentException(
          "resident index buffer cannot be expanded on CPU");
    }
    long relativeOffset = Math.subtractExact(absoluteOffset,
        image.sourceOffsetBytes());
    long required = Math.multiplyExact((long) count, elementBytes);
    if (relativeOffset < 0 || relativeOffset > image.byteLength()
        || required > image.byteLength() - relativeOffset
        || relativeOffset > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("captured index range is incomplete");
    }
    return new SourceIndices(ByteBuffer.wrap(image.ownedBytes())
        .order(ByteOrder.nativeOrder()), (int) relativeOffset, count,
        elementBytes);
  }

  private static ByteBuffer allocateOutput(int indexCount) {
    int bytes = Math.multiplyExact(indexCount, OUTPUT_INDEX_BYTES);
    if (bytes <= 0
        || bytes > IrisShadowReplayBufferSnapshot.MAX_TOTAL_BYTES) {
      throw new IllegalArgumentException("expanded index output too large");
    }
    return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
  }

  private static void putVertex(ByteBuffer output, int first, int delta) {
    long value = Math.addExact((long) first, delta);
    if (value > 0xffff_ffffL) {
      throw new IllegalArgumentException("vertex index overflow");
    }
    output.putInt((int) value);
  }

  private static IrisShadowReplayBufferSnapshot appendIndexImage(
      IrisShadowReplayBufferSnapshot buffers, byte[] bytes) {
    if (buffers.images().size() >= IrisShadowReplayBufferSnapshot.MAX_IMAGES
        || buffers.totalBytes()
            > IrisShadowReplayBufferSnapshot.MAX_TOTAL_BYTES - bytes.length) {
      throw new IllegalArgumentException("expanded index capacity exceeded");
    }
    ArrayList<IrisShadowReplayBufferSnapshot.BufferImage> images =
        new ArrayList<>(buffers.images());
    IrisShadowReplayBufferSnapshot.BufferRef reference =
        new IrisShadowReplayBufferSnapshot.BufferRef(images.size());
    images.add(IrisShadowReplayBufferSnapshot.BufferImage.owned(
        reference.imageId(), 0, 0, 0, bytes));
    return copy(buffers, images, Optional.of(reference), buffers.blockers());
  }

  private static Result blocked(IrisExecutionCommand command,
      IrisShadowReplayBufferSnapshot buffers, String reason) {
    TreeSet<String> blockers = new TreeSet<>(buffers.blockers());
    if (blockers.size() < IrisShadowReplayBufferSnapshot.MAX_BLOCKERS) {
      blockers.add(reason);
    } else if (!blockers.contains(reason)) {
      blockers.remove(blockers.last());
      blockers.add("primitive-index-expansion-blocker-capacity-exceeded");
    }
    IrisShadowReplayBufferSnapshot blocked = copy(buffers, buffers.images(),
        buffers.indexBuffer(), List.copyOf(blockers));
    return new Result(command, blocked, false);
  }

  private static IrisShadowReplayBufferSnapshot copy(
      IrisShadowReplayBufferSnapshot source,
      List<IrisShadowReplayBufferSnapshot.BufferImage> images,
      Optional<IrisShadowReplayBufferSnapshot.BufferRef> indexBuffer,
      List<String> blockers) {
    return new IrisShadowReplayBufferSnapshot(source.captureEnabled(), images,
        source.vertexBuffers(), indexBuffer, source.indirectArguments(),
        source.indexedBuffers(), source.textureBuffers(), blockers);
  }

  private record SourceIndices(ByteBuffer bytes, int offset, int count,
                               int elementBytes) {
    private SourceIndices {
      Objects.requireNonNull(bytes, "bytes");
      if (offset < 0 || count < 0
          || elementBytes != 1 && elementBytes != 2 && elementBytes != 4) {
        throw new IllegalArgumentException("invalid source index view");
      }
    }

    private int get(int index) {
      if (index < 0 || index >= count) {
        throw new IndexOutOfBoundsException(index);
      }
      int position = Math.addExact(offset,
          Math.multiplyExact(index, elementBytes));
      return switch (elementBytes) {
        case 1 -> Byte.toUnsignedInt(bytes.get(position));
        case 2 -> Short.toUnsignedInt(bytes.getShort(position));
        case 4 -> bytes.getInt(position);
        default -> throw new IllegalStateException("invalid index width");
      };
    }
  }

  public record Result(IrisExecutionCommand command,
                       IrisShadowReplayBufferSnapshot buffers,
                       boolean expanded) {
    public Result {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(buffers, "buffers");
    }
  }
}

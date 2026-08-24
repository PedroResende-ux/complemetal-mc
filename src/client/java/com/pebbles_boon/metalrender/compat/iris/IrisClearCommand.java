package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact clear/load operation retained by one transient execution plan. */
public record IrisClearCommand(ResourceHandle target, int buffer,
                               int drawBuffer, ValueKind valueKind,
                               List<Long> rawValues,
                               Optional<Rect> region) {
  public static final int GL_COLOR = 0x1800;
  public static final int GL_DEPTH = 0x1801;
  public static final int GL_STENCIL = 0x1802;
  public static final int GL_DEPTH_STENCIL = 0x84F9;

  public IrisClearCommand {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(valueKind, "valueKind");
    rawValues = List.copyOf(rawValues);
    region = Objects.requireNonNull(region, "region");
    if (drawBuffer < 0 || rawValues.size() != expectedValues(buffer)
        || !valueKind.supports(buffer)) {
      throw new IllegalArgumentException("invalid clear command");
    }
    for (Long value : rawValues) {
      Objects.requireNonNull(value, "clear value");
    }
  }

  public static IrisClearCommand colorFloat(ResourceHandle target,
      int drawBuffer, float red, float green, float blue, float alpha,
      Optional<Rect> region) {
    return new IrisClearCommand(target, GL_COLOR, drawBuffer,
        ValueKind.FLOAT32, List.of(
            Integer.toUnsignedLong(Float.floatToRawIntBits(red)),
            Integer.toUnsignedLong(Float.floatToRawIntBits(green)),
            Integer.toUnsignedLong(Float.floatToRawIntBits(blue)),
            Integer.toUnsignedLong(Float.floatToRawIntBits(alpha))), region);
  }

  public static IrisClearCommand colorSignedInt(ResourceHandle target,
      int drawBuffer, int[] values) {
    return new IrisClearCommand(target, GL_COLOR, drawBuffer,
        ValueKind.SINT32, ints(values), Optional.empty());
  }

  public static IrisClearCommand colorUnsignedInt(ResourceHandle target,
      int drawBuffer, int[] values) {
    return new IrisClearCommand(target, GL_COLOR, drawBuffer,
        ValueKind.UINT32, ints(values), Optional.empty());
  }

  public static IrisClearCommand depth(ResourceHandle target, double value,
      Optional<Rect> region) {
    return new IrisClearCommand(target, GL_DEPTH, 0, ValueKind.FLOAT64,
        List.of(Double.doubleToRawLongBits(value)), region);
  }

  public static IrisClearCommand depthFloat(ResourceHandle target,
      float value) {
    return new IrisClearCommand(target, GL_DEPTH, 0, ValueKind.FLOAT32,
        List.of(Integer.toUnsignedLong(Float.floatToRawIntBits(value))),
        Optional.empty());
  }

  public static IrisClearCommand stencil(ResourceHandle target, int value) {
    return new IrisClearCommand(target, GL_STENCIL, 0, ValueKind.SINT32,
        List.of(Integer.toUnsignedLong(value)), Optional.empty());
  }

  private static List<Long> ints(int[] values) {
    Objects.requireNonNull(values, "values");
    if (values.length != 4) {
      throw new IllegalArgumentException("color clear needs four values");
    }
    return java.util.Arrays.stream(values)
        .mapToObj(Integer::toUnsignedLong).toList();
  }

  private static int expectedValues(int buffer) {
    return switch (buffer) {
      case GL_COLOR -> 4;
      case GL_DEPTH, GL_STENCIL -> 1;
      case GL_DEPTH_STENCIL -> 2;
      default -> throw new IllegalArgumentException("unknown clear buffer");
    };
  }

  public enum ValueKind {
    FLOAT32,
    FLOAT64,
    SINT32,
    UINT32;

    private boolean supports(int buffer) {
      return switch (buffer) {
        case GL_COLOR -> this != FLOAT64;
        case GL_DEPTH -> this == FLOAT32 || this == FLOAT64;
        case GL_STENCIL -> this == SINT32 || this == UINT32;
        case GL_DEPTH_STENCIL -> this == FLOAT32 || this == FLOAT64;
        default -> false;
      };
    }
  }

  public record Rect(int x, int y, int width, int height) {
    public Rect {
      if (width <= 0 || height <= 0) {
        throw new IllegalArgumentException("invalid clear rectangle");
      }
    }
  }
}

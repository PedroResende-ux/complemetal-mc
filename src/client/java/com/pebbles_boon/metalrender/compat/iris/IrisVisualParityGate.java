package com.pebbles_boon.metalrender.compat.iris;

import java.util.Objects;

/** Bounded RGBA8 comparison window used before any Iris pass cutover. */
public final class IrisVisualParityGate {
  public static final int MAX_PIXELS = 16_777_216;

  private final Thresholds thresholds;
  private long framesCompared;
  private long framesPassed;
  private long framesFailed;
  private int consecutivePasses;
  private double worstDifferentPixelRatio;
  private double worstRootMeanSquareError;
  private int worstChannelDelta;

  public IrisVisualParityGate(Thresholds thresholds) {
    this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
  }

  public synchronized Comparison compare(byte[] irisRgba, byte[] metalRgba,
      int width, int height) {
    Objects.requireNonNull(irisRgba, "irisRgba");
    Objects.requireNonNull(metalRgba, "metalRgba");
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("invalid comparison dimensions");
    }
    long pixelCount = Math.multiplyExact((long) width, (long) height);
    if (pixelCount > MAX_PIXELS) {
      throw new IllegalArgumentException("comparison exceeds pixel bound");
    }
    int byteCount = Math.toIntExact(Math.multiplyExact(pixelCount, 4L));
    if (irisRgba.length != byteCount || metalRgba.length != byteCount) {
      throw new IllegalArgumentException("RGBA payload size mismatch");
    }

    long differentPixels = 0;
    long sumSquaredError = 0;
    int maximumDelta = 0;
    for (int pixel = 0; pixel < byteCount; pixel += 4) {
      boolean different = false;
      for (int channel = 0; channel < 4; channel++) {
        int delta = Math.abs(Byte.toUnsignedInt(irisRgba[pixel + channel])
            - Byte.toUnsignedInt(metalRgba[pixel + channel]));
        maximumDelta = Math.max(maximumDelta, delta);
        sumSquaredError += (long) delta * delta;
        if (delta > thresholds.channelTolerance()) {
          different = true;
        }
      }
      if (different) {
        differentPixels++;
      }
    }
    double differentRatio = differentPixels / (double) pixelCount;
    double rmse = Math.sqrt(sumSquaredError / (pixelCount * 4.0));
    boolean passed = differentRatio <= thresholds.maxDifferentPixelRatio()
        && rmse <= thresholds.maxRootMeanSquareError();
    framesCompared++;
    if (passed) {
      framesPassed++;
      consecutivePasses++;
    } else {
      framesFailed++;
      consecutivePasses = 0;
    }
    worstDifferentPixelRatio = Math.max(worstDifferentPixelRatio,
        differentRatio);
    worstRootMeanSquareError = Math.max(worstRootMeanSquareError, rmse);
    worstChannelDelta = Math.max(worstChannelDelta, maximumDelta);
    return new Comparison(passed, width, height, differentPixels,
        differentRatio, rmse, maximumDelta);
  }

  public synchronized Status status() {
    boolean validated = framesCompared >= thresholds.minimumFrames()
        && consecutivePasses >= thresholds.requiredConsecutivePasses()
        && framesFailed == 0;
    return new Status(framesCompared, framesPassed, framesFailed,
        consecutivePasses, worstDifferentPixelRatio,
        worstRootMeanSquareError, worstChannelDelta, validated);
  }

  public synchronized void reset() {
    framesCompared = 0;
    framesPassed = 0;
    framesFailed = 0;
    consecutivePasses = 0;
    worstDifferentPixelRatio = 0.0;
    worstRootMeanSquareError = 0.0;
    worstChannelDelta = 0;
  }

  public record Thresholds(int channelTolerance,
                           double maxDifferentPixelRatio,
                           double maxRootMeanSquareError,
                           int minimumFrames,
                           int requiredConsecutivePasses) {
    public Thresholds {
      if (channelTolerance < 0 || channelTolerance > 255
          || !Double.isFinite(maxDifferentPixelRatio)
          || maxDifferentPixelRatio < 0.0
          || maxDifferentPixelRatio > 1.0
          || !Double.isFinite(maxRootMeanSquareError)
          || maxRootMeanSquareError < 0.0
          || minimumFrames <= 0 || requiredConsecutivePasses <= 0
          || requiredConsecutivePasses > minimumFrames) {
        throw new IllegalArgumentException("invalid visual parity threshold");
      }
    }
  }

  public record Comparison(boolean passed, int width, int height,
                           long differentPixels,
                           double differentPixelRatio,
                           double rootMeanSquareError,
                           int maximumChannelDelta) {
  }

  public record Status(long framesCompared, long framesPassed,
                       long framesFailed, int consecutivePasses,
                       double worstDifferentPixelRatio,
                       double worstRootMeanSquareError,
                       int worstChannelDelta, boolean validated) {
  }
}

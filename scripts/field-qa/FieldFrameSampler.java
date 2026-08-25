package com.pebbles_boon.metalrender.fieldqa;

import java.util.Arrays;

/** Render-loop sampler shipped only in the isolated field-QA driver. */
public final class FieldFrameSampler {
  private static final int MAX_SAMPLES = 120_000;
  private static final long[] SAMPLES = new long[MAX_SAMPLES];
  private static boolean capturing;
  private static long previousFrameNanos;
  private static long startedNanos;
  private static int sampleCount;
  private static int droppedSamples;

  private FieldFrameSampler() {
  }

  public static synchronized void begin() {
    capturing = true;
    previousFrameNanos = System.nanoTime();
    startedNanos = previousFrameNanos;
    sampleCount = 0;
    droppedSamples = 0;
  }

  public static synchronized void end() {
    capturing = false;
  }

  public static synchronized void recordFrame(long nowNanos) {
    if (!capturing) {
      previousFrameNanos = nowNanos;
      return;
    }
    long delta = nowNanos - previousFrameNanos;
    previousFrameNanos = nowNanos;
    if (delta <= 0) {
      return;
    }
    if (sampleCount < SAMPLES.length) {
      SAMPLES[sampleCount++] = delta;
    } else {
      droppedSamples++;
    }
  }

  public static synchronized int sampleCount() {
    return sampleCount;
  }

  public static synchronized long elapsedNanos() {
    return capturing ? Math.max(0L, System.nanoTime() - startedNanos) : 0L;
  }

  public static synchronized Snapshot snapshot() {
    return new Snapshot(Arrays.copyOf(SAMPLES, sampleCount), droppedSamples);
  }

  public record Snapshot(long[] frameNanos, int droppedSamples) {
    public Snapshot {
      frameNanos = frameNanos.clone();
    }
  }
}

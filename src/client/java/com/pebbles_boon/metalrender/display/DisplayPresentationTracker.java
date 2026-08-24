package com.pebbles_boon.metalrender.display;

import java.util.Arrays;

/** Bounded timestamps around the real GLFW-backed window present call. */
public final class DisplayPresentationTracker {
  private static final int CAPACITY = 8_192;
  private static final long[] COMPLETION_INTERVALS = new long[CAPACITY];
  private static final long[] PRESENT_DURATIONS = new long[CAPACITY];

  private static int intervalCursor;
  private static int intervalSize;
  private static int durationCursor;
  private static int durationSize;
  private static long totalPresentCalls;
  private static long sequencePresentCalls;
  private static long invalidSamples;
  private static long windowHandle;
  private static long firstCompletionNanos;
  private static long lastCompletionNanos;

  private DisplayPresentationTracker() {
  }

  /** Called at the tail of the actual OpenGL surface present operation. */
  public static synchronized void record(long presentedWindowHandle,
      long startedNanos, long completedNanos) {
    if (presentedWindowHandle == 0 || startedNanos <= 0
        || completedNanos <= startedNanos) {
      invalidSamples++;
      return;
    }
    if (windowHandle != 0 && windowHandle != presentedWindowHandle) {
      clearCadenceSequence();
    }
    windowHandle = presentedWindowHandle;
    appendDuration(completedNanos - startedNanos);
    if (lastCompletionNanos > 0 && completedNanos > lastCompletionNanos) {
      appendInterval(completedNanos - lastCompletionNanos);
    }
    if (firstCompletionNanos == 0) {
      firstCompletionNanos = completedNanos;
    }
    lastCompletionNanos = completedNanos;
    sequencePresentCalls++;
    totalPresentCalls++;
  }

  public static synchronized Snapshot snapshot() {
    return new Snapshot(totalPresentCalls, sequencePresentCalls,
        invalidSamples, windowHandle, firstCompletionNanos, lastCompletionNanos,
        chronological(COMPLETION_INTERVALS, intervalCursor, intervalSize),
        chronological(PRESENT_DURATIONS, durationCursor, durationSize));
  }

  public static synchronized void reset() {
    intervalCursor = 0;
    intervalSize = 0;
    durationCursor = 0;
    durationSize = 0;
    totalPresentCalls = 0;
    sequencePresentCalls = 0;
    invalidSamples = 0;
    windowHandle = 0;
    firstCompletionNanos = 0;
    lastCompletionNanos = 0;
    Arrays.fill(COMPLETION_INTERVALS, 0L);
    Arrays.fill(PRESENT_DURATIONS, 0L);
  }

  private static void clearCadenceSequence() {
    intervalCursor = 0;
    intervalSize = 0;
    durationCursor = 0;
    durationSize = 0;
    sequencePresentCalls = 0;
    firstCompletionNanos = 0;
    lastCompletionNanos = 0;
  }

  private static void appendInterval(long value) {
    COMPLETION_INTERVALS[intervalCursor] = value;
    intervalCursor = (intervalCursor + 1) % CAPACITY;
    intervalSize = Math.min(CAPACITY, intervalSize + 1);
  }

  private static void appendDuration(long value) {
    PRESENT_DURATIONS[durationCursor] = value;
    durationCursor = (durationCursor + 1) % CAPACITY;
    durationSize = Math.min(CAPACITY, durationSize + 1);
  }

  private static long[] chronological(long[] source, int cursor, int size) {
    long[] result = new long[size];
    int first = (cursor - size + source.length) % source.length;
    for (int index = 0; index < size; index++) {
      result[index] = source[(first + index) % source.length];
    }
    return result;
  }

  public record Snapshot(long totalPresentCalls, long sequencePresentCalls,
                         long invalidSamples, long windowHandle,
                         long firstCompletionNanos, long lastCompletionNanos,
                         long[] completionIntervalsNanos,
                         long[] presentDurationsNanos) {
    public Snapshot {
      if (totalPresentCalls < 0 || sequencePresentCalls < 0
          || sequencePresentCalls > totalPresentCalls || invalidSamples < 0
          || firstCompletionNanos < 0 || lastCompletionNanos < 0) {
        throw new IllegalArgumentException("invalid presentation snapshot");
      }
      completionIntervalsNanos = completionIntervalsNanos.clone();
      presentDurationsNanos = presentDurationsNanos.clone();
    }

    @Override
    public long[] completionIntervalsNanos() {
      return completionIntervalsNanos.clone();
    }

    @Override
    public long[] presentDurationsNanos() {
      return presentDurationsNanos.clone();
    }

    public double measuredPresentsPerSecond() {
      if (sequencePresentCalls < 2
          || lastCompletionNanos <= firstCompletionNanos) {
        return 0.0;
      }
      return (sequencePresentCalls - 1) * 1_000_000_000.0
          / (lastCompletionNanos - firstCompletionNanos);
    }
  }
}

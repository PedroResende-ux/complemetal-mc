package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class IrisStage9PerformanceSamplerTest {
  @Test
  void snapshotOwnsDefensiveRawSampleCopies() {
    long[] cpu = {10, 20};
    long[] gpu = {8, 16};
    IrisStage9PerformanceSampler.Snapshot snapshot =
        new IrisStage9PerformanceSampler.Snapshot(
            "metal", cpu, gpu, 0, 0, 1, 0, 0);

    cpu[0] = 99;
    gpu[0] = 99;
    long[] returnedCpu = snapshot.cpuNanos();
    returnedCpu[1] = 99;

    assertArrayEquals(new long[] {10, 20}, snapshot.cpuNanos());
    assertArrayEquals(new long[] {8, 16}, snapshot.gpuNanos());
    assertEquals(2, snapshot.pairedSamples());
  }

  @Test
  void disabledSnapshotMustBeEmptyAndInvalidSamplesAreRejected() {
    assertEquals(0, new IrisStage9PerformanceSampler.Snapshot(
        "none", new long[0], new long[0], 0, 0, 0, 0, 0)
        .pairedSamples());
    assertThrows(IllegalArgumentException.class,
        () -> new IrisStage9PerformanceSampler.Snapshot(
            "none", new long[] {1}, new long[0], 0, 0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new IrisStage9PerformanceSampler.Snapshot(
            "opengl", new long[] {0}, new long[] {1}, 0, 0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new IrisStage9PerformanceSampler.Snapshot(
            "unknown", new long[] {1}, new long[] {1}, 0, 0, 0, 0, 0));
  }
}

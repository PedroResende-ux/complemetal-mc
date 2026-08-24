package com.pebbles_boon.metalrender.compat.iris;

import java.util.Arrays;
import java.util.Objects;

/** Bounded, scenario-matched A/B frame-time acceptance gate for Stage 9. */
public final class IrisPerformanceGate {
  public static final int MAX_SAMPLES = 36_000;

  private final int minimumSamples;
  private final double requiredP50Improvement;
  private final double requiredP95Improvement;
  private final double maximumTailRegression;
  private final Series openGl;
  private final Series metal;

  public IrisPerformanceGate(int minimumSamples,
      double requiredP50Improvement, double requiredP95Improvement) {
    this(minimumSamples, requiredP50Improvement, requiredP95Improvement,
        0.05);
  }

  public IrisPerformanceGate(int minimumSamples,
      double requiredP50Improvement, double requiredP95Improvement,
      double maximumTailRegression) {
    if (minimumSamples <= 0 || minimumSamples > MAX_SAMPLES
        || !validRatio(requiredP50Improvement)
        || !validRatio(requiredP95Improvement)
        || !validRatio(maximumTailRegression)) {
      throw new IllegalArgumentException("invalid performance gate");
    }
    this.minimumSamples = minimumSamples;
    this.requiredP50Improvement = requiredP50Improvement;
    this.requiredP95Improvement = requiredP95Improvement;
    this.maximumTailRegression = maximumTailRegression;
    openGl = new Series(minimumSamples);
    metal = new Series(minimumSamples);
  }

  public synchronized void add(Side side, String scenarioSha256,
      long frameCpuNanos, long frameGpuNanos, boolean pipelineStutter) {
    Objects.requireNonNull(side, "side");
    IrisRenderGraph.requireSha(scenarioSha256, "scenarioSha256");
    if (frameCpuNanos <= 0 || frameGpuNanos < 0) {
      throw new IllegalArgumentException("invalid frame timing sample");
    }
    series(side).add(scenarioSha256, frameCpuNanos, frameGpuNanos,
        pipelineStutter);
  }

  public synchronized Result evaluate(boolean visualParityPassed,
      boolean lifecyclePassed) {
    if (!openGl.complete() || !metal.complete()) {
      return Result.pending("insufficient-samples");
    }
    if (!openGl.scenario.equals(metal.scenario)) {
      return Result.rejected("scenario-mismatch");
    }
    if (!visualParityPassed) {
      return Result.rejected("visual-parity-not-passed");
    }
    if (!lifecyclePassed) {
      return Result.rejected("lifecycle-not-passed");
    }
    Metrics baseline = openGl.metrics();
    Metrics candidate = metal.metrics();
    double p50Improvement = improvement(baseline.cpuP50Nanos(),
        candidate.cpuP50Nanos());
    double p95Improvement = improvement(baseline.cpuP95Nanos(),
        candidate.cpuP95Nanos());
    double cpuP99Regression = regression(baseline.cpuP99Nanos(),
        candidate.cpuP99Nanos());
    double gpuP95Regression = regression(baseline.gpuP95Nanos(),
        candidate.gpuP95Nanos());
    double gpuP99Regression = regression(baseline.gpuP99Nanos(),
        candidate.gpuP99Nanos());
    if (p50Improvement < requiredP50Improvement
        || p95Improvement < requiredP95Improvement
        || cpuP99Regression > maximumTailRegression
        || gpuP95Regression > maximumTailRegression
        || gpuP99Regression > maximumTailRegression
        || candidate.stutters() > baseline.stutters()) {
      return new Result(State.REJECTED, "benefit-gate-not-met", baseline,
          candidate, p50Improvement, p95Improvement, cpuP99Regression,
          gpuP95Regression, gpuP99Regression);
    }
    return new Result(State.PASSED, "passed", baseline, candidate,
        p50Improvement, p95Improvement, cpuP99Regression,
        gpuP95Regression, gpuP99Regression);
  }

  private Series series(Side side) {
    return side == Side.OPENGL ? openGl : metal;
  }

  private static boolean validRatio(double value) {
    return Double.isFinite(value) && value >= 0.0 && value < 1.0;
  }

  private static double improvement(long baseline, long candidate) {
    return (baseline - candidate) / (double) baseline;
  }

  private static double regression(long baseline, long candidate) {
    if (baseline == 0) {
      return candidate == 0 ? 0.0 : Double.POSITIVE_INFINITY;
    }
    return (candidate - baseline) / (double) baseline;
  }

  public enum Side {
    OPENGL,
    METAL
  }

  public enum State {
    PENDING,
    REJECTED,
    PASSED
  }

  public record Metrics(int samples, long cpuP50Nanos, long cpuP95Nanos,
                        long cpuP99Nanos, long gpuP50Nanos,
                        long gpuP95Nanos, long gpuP99Nanos,
                        long stutters) {
  }

  public record Result(State state, String reason, Metrics openGl,
                       Metrics metal, double p50Improvement,
                       double p95Improvement, double cpuP99Regression,
                       double gpuP95Regression, double gpuP99Regression) {
    private static Result pending(String reason) {
      return new Result(State.PENDING, reason, null, null, 0.0, 0.0,
          0.0, 0.0, 0.0);
    }

    private static Result rejected(String reason) {
      return new Result(State.REJECTED, reason, null, null, 0.0, 0.0,
          0.0, 0.0, 0.0);
    }
  }

  private static final class Series {
    private final long[] cpu;
    private final long[] gpu;
    private int size;
    private long stutters;
    private String scenario;

    private Series(int capacity) {
      cpu = new long[capacity];
      gpu = new long[capacity];
    }

    private void add(String candidateScenario, long cpuNanos, long gpuNanos,
        boolean stutter) {
      if (scenario == null) {
        scenario = candidateScenario;
      } else if (!scenario.equals(candidateScenario)) {
        throw new IllegalArgumentException("mixed performance scenarios");
      }
      if (size >= cpu.length) {
        throw new IllegalStateException("performance sample capacity reached");
      }
      cpu[size] = cpuNanos;
      gpu[size] = gpuNanos;
      size++;
      if (stutter) {
        stutters++;
      }
    }

    private boolean complete() {
      return size == cpu.length;
    }

    private Metrics metrics() {
      long[] sortedCpu = Arrays.copyOf(cpu, size);
      long[] sortedGpu = Arrays.copyOf(gpu, size);
      Arrays.sort(sortedCpu);
      Arrays.sort(sortedGpu);
      return new Metrics(size, percentile(sortedCpu, 0.50),
          percentile(sortedCpu, 0.95), percentile(sortedCpu, 0.99),
          percentile(sortedGpu, 0.50), percentile(sortedGpu, 0.95),
          percentile(sortedGpu, 0.99), stutters);
    }

    private static long percentile(long[] sorted, double percentile) {
      int index = (int) Math.ceil(percentile * sorted.length) - 1;
      return sorted[Math.max(0, index)];
    }
  }
}

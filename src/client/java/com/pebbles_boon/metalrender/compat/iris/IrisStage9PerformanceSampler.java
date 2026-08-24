package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.lwjgl.opengl.GL33C;

/**
 * Exact-JAR-only raw frame sampler for the matched Stage 9 A/B gate.
 *
 * <p>The OpenGL side measures the complete Iris level-rendering interval with
 * a non-blocking {@code GL_TIME_ELAPSED} query ring. The Metal side reads the
 * per-command-buffer MTL4 commit feedback already exposed by the native graph
 * executor. No query is created and no timing call is made unless the exact
 * QA launch explicitly selects a side.</p>
 */
public final class IrisStage9PerformanceSampler {
  public static final String SIDE_PROPERTY =
      "metalrender.exactJar.performanceSide";
  public static final int MAX_SAMPLES = IrisPerformanceGate.MAX_SAMPLES;
  private static final int QUERY_SLOTS = 64;
  private static final long STUTTER_NANOS = 100_000_000L;
  private static final Side SIDE = Side.configured();
  private static final long[] CPU_NANOS = new long[MAX_SAMPLES];
  private static final long[] GPU_NANOS = new long[MAX_SAMPLES];
  private static final int[] QUERY_IDS = new int[QUERY_SLOTS];
  private static final boolean[] QUERY_PENDING = new boolean[QUERY_SLOTS];
  private static final long[] QUERY_EPOCHS = new long[QUERY_SLOTS];

  private static long epoch = 1;
  private static long cpuStartedNanos;
  private static int cpuSamples;
  private static int gpuSamples;
  private static int queryCursor;
  private static int activeQuerySlot = -1;
  private static long droppedGpuSamples;
  private static long instrumentationErrors;
  private static long cpuStutters;
  private static long gpuStutters;
  private static long metalFeedbackErrors;

  private IrisStage9PerformanceSampler() {
  }

  public static boolean enabled() {
    return SIDE != Side.NONE;
  }

  /** Called on the render thread immediately before Iris starts a frame. */
  public static void beginFrame() {
    if (SIDE == Side.NONE) {
      return;
    }
    if (SIDE == Side.OPENGL) {
      pollOpenGlQueries();
    }
    cpuStartedNanos = System.nanoTime();
    if (SIDE == Side.OPENGL) {
      beginOpenGlQuery();
    }
  }

  /** Called after graph capture/submission and Iris finalization complete. */
  public static void endFrame() {
    if (SIDE == Side.NONE || cpuStartedNanos == 0) {
      return;
    }
    if (SIDE == Side.OPENGL) {
      endOpenGlQuery();
    }
    long elapsed = System.nanoTime() - cpuStartedNanos;
    cpuStartedNanos = 0;
    if (elapsed > 0 && cpuSamples < MAX_SAMPLES) {
      CPU_NANOS[cpuSamples++] = elapsed;
      if (elapsed >= STUTTER_NANOS) {
        cpuStutters++;
      }
    }
    if (SIDE == Side.OPENGL) {
      pollOpenGlQueries();
    }
  }

  /** Starts a fresh bounded measurement window without blocking the GPU. */
  public static void reset() {
    if (SIDE == Side.NONE) {
      return;
    }
    epoch = epoch == Long.MAX_VALUE ? 1 : epoch + 1;
    cpuStartedNanos = 0;
    cpuSamples = 0;
    gpuSamples = 0;
    droppedGpuSamples = 0;
    instrumentationErrors = 0;
    cpuStutters = 0;
    gpuStutters = 0;
    metalFeedbackErrors = 0;
    if (SIDE == Side.METAL) {
      try {
        if (!NativeBridge.nResetIrisMetal4GraphPerformanceSamples()) {
          instrumentationErrors++;
        }
      } catch (RuntimeException | LinkageError failure) {
        instrumentationErrors++;
      }
    } else if (SIDE == Side.OPENGL) {
      pollOpenGlQueries();
    }
  }

  public static Snapshot snapshot() {
    if (SIDE == Side.OPENGL) {
      pollOpenGlQueries();
    } else if (SIDE == Side.METAL) {
      pollMetalFeedback();
    }
    return new Snapshot(SIDE.name().toLowerCase(Locale.ROOT),
        Arrays.copyOf(CPU_NANOS, cpuSamples),
        Arrays.copyOf(GPU_NANOS, gpuSamples), droppedGpuSamples,
        instrumentationErrors, cpuStutters, gpuStutters,
        metalFeedbackErrors);
  }

  private static void beginOpenGlQuery() {
    if (activeQuerySlot >= 0 || gpuSamples >= MAX_SAMPLES) {
      return;
    }
    ensureOpenGlQueries();
    for (int offset = 0; offset < QUERY_SLOTS; offset++) {
      int slot = (queryCursor + offset) % QUERY_SLOTS;
      if (QUERY_IDS[slot] == 0 || QUERY_PENDING[slot]) {
        continue;
      }
      try {
        GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, QUERY_IDS[slot]);
        activeQuerySlot = slot;
        queryCursor = (slot + 1) % QUERY_SLOTS;
      } catch (RuntimeException | LinkageError failure) {
        instrumentationErrors++;
      }
      return;
    }
    droppedGpuSamples++;
  }

  private static void endOpenGlQuery() {
    if (activeQuerySlot < 0) {
      return;
    }
    int slot = activeQuerySlot;
    activeQuerySlot = -1;
    try {
      GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
      QUERY_PENDING[slot] = true;
      QUERY_EPOCHS[slot] = epoch;
    } catch (RuntimeException | LinkageError failure) {
      instrumentationErrors++;
      QUERY_PENDING[slot] = false;
    }
  }

  private static void ensureOpenGlQueries() {
    if (QUERY_IDS[0] != 0) {
      return;
    }
    try {
      for (int index = 0; index < QUERY_SLOTS; index++) {
        QUERY_IDS[index] = GL33C.glGenQueries();
      }
    } catch (RuntimeException | LinkageError failure) {
      Arrays.fill(QUERY_IDS, 0);
      instrumentationErrors++;
    }
  }

  private static void pollOpenGlQueries() {
    for (int slot = 0; slot < QUERY_SLOTS; slot++) {
      if (!QUERY_PENDING[slot] || QUERY_IDS[slot] == 0) {
        continue;
      }
      try {
        if (GL33C.glGetQueryObjecti(QUERY_IDS[slot],
                GL33C.GL_QUERY_RESULT_AVAILABLE) == 0) {
          continue;
        }
        long nanos = GL33C.glGetQueryObjectui64(QUERY_IDS[slot],
            GL33C.GL_QUERY_RESULT);
        QUERY_PENDING[slot] = false;
        if (QUERY_EPOCHS[slot] == epoch && nanos > 0
            && gpuSamples < MAX_SAMPLES) {
          GPU_NANOS[gpuSamples++] = nanos;
          if (nanos >= STUTTER_NANOS) {
            gpuStutters++;
          }
        }
      } catch (RuntimeException | LinkageError failure) {
        QUERY_PENDING[slot] = false;
        instrumentationErrors++;
      }
    }
  }

  private static void pollMetalFeedback() {
    try {
      long[] timing = NativeBridge.nGetIrisMetal4GraphTiming();
      if (timing == null || timing.length != 12 || timing[0] < 0
          || timing[1] < 0 || timing[4] < 0) {
        instrumentationErrors++;
        return;
      }
      metalFeedbackErrors = timing[4];
      long[] drained =
          NativeBridge.nDrainIrisMetal4GraphPerformanceSamples();
      if (drained == null || drained.length == 0 || drained[0] < 0) {
        instrumentationErrors++;
        return;
      }
      droppedGpuSamples += drained[0];
      for (int index = 1; index < drained.length; index++) {
        long nanos = drained[index];
        if (nanos <= 0) {
          instrumentationErrors++;
          continue;
        }
        if (gpuSamples >= MAX_SAMPLES) {
          droppedGpuSamples++;
          continue;
        }
        GPU_NANOS[gpuSamples++] = nanos;
        if (nanos >= STUTTER_NANOS) {
          gpuStutters++;
        }
      }
    } catch (RuntimeException | LinkageError failure) {
      instrumentationErrors++;
    }
  }

  private enum Side {
    NONE,
    OPENGL,
    METAL;

    private static Side configured() {
      String value = System.getProperty(SIDE_PROPERTY, "").trim()
          .toLowerCase(Locale.ROOT);
      return switch (value) {
        case "" -> NONE;
        case "opengl" -> OPENGL;
        case "metal" -> METAL;
        default -> throw new IllegalArgumentException(
            "invalid Stage 9 performance side: " + value);
      };
    }
  }

  public record Snapshot(String side, long[] cpuNanos, long[] gpuNanos,
                         long droppedGpuSamples,
                         long instrumentationErrors, long cpuStutters,
                         long gpuStutters, long metalFeedbackErrors) {
    public Snapshot {
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(cpuNanos, "cpuNanos");
      Objects.requireNonNull(gpuNanos, "gpuNanos");
      cpuNanos = cpuNanos.clone();
      gpuNanos = gpuNanos.clone();
      boolean disabled = side.equals("none");
      if (!disabled && !side.equals("opengl") && !side.equals("metal")
          || cpuNanos.length > MAX_SAMPLES || gpuNanos.length > MAX_SAMPLES
          || Arrays.stream(cpuNanos).anyMatch(value -> value <= 0)
          || Arrays.stream(gpuNanos).anyMatch(value -> value <= 0)
          || droppedGpuSamples < 0 || instrumentationErrors < 0
          || cpuStutters < 0 || gpuStutters < 0
          || metalFeedbackErrors < 0
          || disabled && (cpuNanos.length != 0 || gpuNanos.length != 0
              || droppedGpuSamples != 0 || instrumentationErrors != 0
              || cpuStutters != 0 || gpuStutters != 0
              || metalFeedbackErrors != 0)) {
        throw new IllegalArgumentException(
            "invalid Stage 9 performance snapshot");
      }
    }

    @Override
    public long[] cpuNanos() {
      return cpuNanos.clone();
    }

    @Override
    public long[] gpuNanos() {
      return gpuNanos.clone();
    }

    public int pairedSamples() {
      return Math.min(cpuNanos.length, gpuNanos.length);
    }
  }
}

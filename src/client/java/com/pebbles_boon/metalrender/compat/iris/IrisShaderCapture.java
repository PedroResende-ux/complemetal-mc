package com.pebbles_boon.metalrender.compat.iris;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fail-open entry point called by the optional Iris mixin.
 */
public final class IrisShaderCapture {
  public static final String ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalPipeline";

  private static final IrisShaderCaptureQueue QUEUE =
      IrisShaderCaptureQueue.createDefault();
  private static final AtomicLong CAPTURE_FAILURES = new AtomicLong();

  private IrisShaderCapture() {
  }

  public static boolean isEnabled() {
    return Boolean.getBoolean(ENABLED_PROPERTY);
  }

  /**
   * Captures only; it never invokes shaderc, SPIRV-Cross, Metal, or a process.
   * Any capture failure is swallowed so Iris continues its normal OpenGL link.
   */
  public static void captureGraphicsLink(String name, String vertex,
      String geometry, String tessControl, String tessEvaluation,
      String fragment) {
    if (!isEnabled()) {
      return;
    }
    try {
      IrisFinalShaderProgram program =
          IrisFinalShaderProgram.fromGraphicsLink(name, vertex, geometry,
              tessControl, tessEvaluation, fragment);
      QUEUE.offer(program);
    } catch (RuntimeException error) {
      // Keep Iris' link thread free of logger locks and I/O. Diagnostics are
      // exposed through the coordinator status instead.
      CAPTURE_FAILURES.incrementAndGet();
    }
  }

  /**
   * Intended for a future explicitly enabled background translation owner.
   */
  public static Optional<IrisShaderCaptureQueue.CapturedProgram> poll() {
    return QUEUE.poll();
  }

  public static int queuedPrograms() {
    return QUEUE.size();
  }

  public static long rejectedPrograms() {
    return QUEUE.rejectedPrograms();
  }

  public static long captureFailures() {
    return CAPTURE_FAILURES.get();
  }

  static IrisShaderCaptureQueue captureQueue() {
    return QUEUE;
  }
}

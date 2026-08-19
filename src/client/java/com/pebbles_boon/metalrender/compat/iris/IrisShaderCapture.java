package com.pebbles_boon.metalrender.compat.iris;

import com.mojang.blaze3d.vertex.VertexFormat;
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
    captureGraphicsLink(QUEUE, name, vertex, geometry, tessControl,
        tessEvaluation, fragment);
  }

  public static void captureLinkedGraphicsProgram(int glProgram, String name,
      String vertex, String geometry, String tessControl,
      String tessEvaluation, String fragment, VertexFormat vertexFormat,
      boolean fallback) {
    if (!isEnabled()) {
      return;
    }
    try {
      IrisFinalShaderProgram program =
          IrisFinalShaderProgram.fromGraphicsLink(name, vertex, geometry,
              tessControl, tessEvaluation, fragment);
      IrisVertexLayoutCapture.Layout layout =
          IrisVertexLayoutCapture.capture(vertexFormat);
      enqueueRegistered(program, glProgram,
          new IrisProgramIdentityRegistry.ProgramDescriptor(
              IrisPipelineState.PassKind.LINKED_GRAPHICS, name, fallback,
              layout.buffers(), layout.attributes()));
    } catch (RuntimeException error) {
      CAPTURE_FAILURES.incrementAndGet();
    }
  }

  /**
   * Captures the final transformed graphics sources accepted by Iris'
   * {@code ProgramBuilder.begin}. The reserved texture-unit set is pipeline
   * binding state and is intentionally outside this source-only capture.
   */
  public static void captureGraphicsBegin(String name, String vertex,
      String geometry, String fragment) {
    if (!isEnabled()) {
      return;
    }
    captureGraphicsBegin(QUEUE, name, vertex, geometry, fragment);
  }

  public static void captureFullscreenGraphicsProgram(int glProgram,
      String name, String vertex, String geometry, String fragment,
      VertexFormat vertexFormat) {
    if (!isEnabled()) {
      return;
    }
    try {
      IrisFinalShaderProgram program =
          IrisFinalShaderProgram.fromProgramBuilderGraphics(name, vertex,
              geometry, fragment);
      IrisVertexLayoutCapture.Layout layout =
          IrisVertexLayoutCapture.capture(vertexFormat);
      enqueueRegistered(program, glProgram,
          new IrisProgramIdentityRegistry.ProgramDescriptor(
              IrisPipelineState.PassKind.FULLSCREEN_GRAPHICS, name, false,
              layout.buffers(), layout.attributes()));
    } catch (RuntimeException error) {
      CAPTURE_FAILURES.incrementAndGet();
    }
  }

  /**
   * Captures the final transformed compute source accepted by Iris'
   * {@code ProgramBuilder.beginCompute}.
   */
  public static void captureComputeBegin(String name, String compute) {
    if (!isEnabled()) {
      return;
    }
    captureComputeBegin(QUEUE, name, compute);
  }

  public static void captureComputeProgram(int glProgram, String name,
      String compute) {
    if (!isEnabled()) {
      return;
    }
    try {
      IrisFinalShaderProgram program =
          IrisFinalShaderProgram.fromProgramBuilderCompute(name, compute);
      enqueueRegistered(program, glProgram,
          new IrisProgramIdentityRegistry.ProgramDescriptor(
              IrisPipelineState.PassKind.COMPUTE, name, false, java.util.List.of(),
              java.util.List.of()));
    } catch (RuntimeException error) {
      CAPTURE_FAILURES.incrementAndGet();
    }
  }

  public static void deleteProgram(int glProgram) {
    IrisPipelineStateCapture.global().deleteProgram(glProgram);
    IrisProgramIdentityRegistry.global().delete(glProgram);
  }

  private static void enqueueRegistered(IrisFinalShaderProgram program,
      int glProgram,
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor) {
    IrisProgramIdentityRegistry registry =
        IrisProgramIdentityRegistry.global();
    IrisPipelineStateCapture.global().registerProgram(glProgram);
    IrisProgramIdentityRegistry.Registration registration =
        registry.register(glProgram, descriptor);
    IrisShaderCaptureQueue.Offer offer = QUEUE.offer(program, registration);
    if (offer.disposition()
        != IrisShaderCaptureQueue.Disposition.ACCEPTED) {
      registry.delete(glProgram);
      IrisPipelineStateCapture.global().deleteProgram(glProgram);
    }
  }

  static void captureGraphicsLink(IrisShaderCaptureQueue queue, String name,
      String vertex, String geometry, String tessControl,
      String tessEvaluation, String fragment) {
    try {
      IrisFinalShaderProgram program =
          IrisFinalShaderProgram.fromGraphicsLink(name, vertex, geometry,
              tessControl, tessEvaluation, fragment);
      queue.offer(program);
    } catch (RuntimeException error) {
      // Keep Iris' link thread free of logger locks and I/O. Diagnostics are
      // exposed through the coordinator status instead.
      CAPTURE_FAILURES.incrementAndGet();
    }
  }

  static void captureGraphicsBegin(IrisShaderCaptureQueue queue, String name,
      String vertex, String geometry, String fragment) {
    try {
      queue.offer(IrisFinalShaderProgram.fromProgramBuilderGraphics(name,
          vertex, geometry, fragment));
    } catch (RuntimeException error) {
      CAPTURE_FAILURES.incrementAndGet();
    }
  }

  static void captureComputeBegin(IrisShaderCaptureQueue queue, String name,
      String compute) {
    try {
      queue.offer(IrisFinalShaderProgram.fromProgramBuilderCompute(name,
          compute));
    } catch (RuntimeException error) {
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

package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * Bounded render-thread capture of the OpenGL output paired with one sampled
 * Iris draw. Capture is opt-in and never changes or cancels the OpenGL draw.
 */
public final class IrisVisualParityCapture {
  public static final String ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalVisualParity";
  static final String DIAGNOSTIC_GRAPH_READBACK_PROGRAM_PROPERTY =
      "metalrender.exactJar.diagnosticGraphReadbackProgram";
  public static final int MAX_PENDING_CAPTURES = 16;

  private static final IrisVisualParityCapture GLOBAL =
      new IrisVisualParityCapture(IrisMetalFeatureFlags.enabled(
          ENABLED_PROPERTY),
          MAX_PENDING_CAPTURES, IrisVisualParityCapture::readOpenGlRgba8);

  private final boolean enabled;
  private final int capacity;
  private final Readback readback;
  private final ThreadLocal<ArrayDeque<Invocation>> invocations =
      ThreadLocal.withInitial(ArrayDeque::new);
  private final ArrayDeque<CompletedCapture> completed = new ArrayDeque<>();
  private final AtomicLong scheduled = new AtomicLong();
  private final AtomicLong captured = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicReference<String> lastFailure =
      new AtomicReference<>("");

  IrisVisualParityCapture(boolean enabled, int capacity, Readback readback) {
    this.enabled = enabled;
    if (capacity <= 0 || capacity > MAX_PENDING_CAPTURES) {
      throw new IllegalArgumentException("invalid visual capture capacity");
    }
    this.capacity = capacity;
    this.readback = Objects.requireNonNull(readback, "readback");
  }

  public static IrisVisualParityCapture global() {
    return GLOBAL;
  }

  public static boolean isOptedIn() {
    return IrisGlBufferMirror.isEnabled()
        && IrisMetalFeatureFlags.enabled(ENABLED_PROPERTY);
  }

  public void beginDrawInvocation() {
    if (enabled) {
      invocations.get().push(new Invocation());
    }
  }

  public void associate(IrisPipelineStateCapture.PendingState pending,
      IrisRenderGraph.Phase phase) {
    Objects.requireNonNull(pending, "pending");
    Objects.requireNonNull(phase, "phase");
    boolean diagnosticReadback = diagnosticGraphReadback(pending);
    if (!enabled || phase != IrisRenderGraph.Phase.FINAL
        && !diagnosticReadback
        || !pending.replayBuffers().captureEnabled()) {
      return;
    }
    ArrayDeque<Invocation> stack = invocations.get();
    if (stack.isEmpty()) {
      recordFailure("visual-parity-draw-invocation-missing");
      return;
    }
    Invocation invocation = stack.peek();
    if (invocation.pending != null) {
      recordFailure("visual-parity-draw-already-associated");
      return;
    }
    IrisDynamicDrawState dynamic = pending.dynamicState();
    if (!dynamic.viewport().isKnown()) {
      recordFailure("visual-parity-viewport-unavailable");
      return;
    }
    IrisDynamicDrawState.Rect viewport = dynamic.viewport().value();
    if (viewport.x() != 0 || viewport.y() != 0 || viewport.width() <= 0
        || viewport.height() <= 0) {
      recordFailure("visual-parity-viewport-unsupported");
      return;
    }
    long pixels = (long) viewport.width() * viewport.height();
    if (pixels > IrisVisualParityGate.MAX_PIXELS) {
      recordFailure("visual-parity-pixel-bound-exceeded");
      return;
    }
    invocation.pending = pending;
    invocation.width = viewport.width();
    invocation.height = viewport.height();
    scheduled.incrementAndGet();
  }

  static boolean diagnosticGraphReadback(
      IrisPipelineStateCapture.PendingState pending) {
    if (System.getProperty("metalrender.exactJar.expectedPath") == null) {
      return false;
    }
    String requested = System.getProperty(
        DIAGNOSTIC_GRAPH_READBACK_PROGRAM_PROPERTY, "");
    String actual = pending.registration().resolved()
        .map(value -> value.descriptor().programName()).orElse("");
    return diagnosticProgramMatches(requested, actual);
  }

  static boolean diagnosticProgramMatches(String requested, String actual) {
    return requested != null && !requested.isEmpty()
        && requested.equals(actual);
  }

  public void endDrawInvocation() {
    if (!enabled) {
      return;
    }
    ArrayDeque<Invocation> stack = invocations.get();
    if (stack.isEmpty()) {
      return;
    }
    Invocation invocation = stack.pop();
    if (stack.isEmpty()) {
      invocations.remove();
    }
    if (invocation.pending == null) {
      return;
    }
    try {
      byte[] rgba = readback.capture(invocation.pending,
          invocation.width, invocation.height);
      int required = Math.toIntExact(Math.multiplyExact(
          (long) invocation.width * invocation.height, 4L));
      if (rgba == null || rgba.length != required) {
        recordFailure("visual-parity-readback-size-mismatch");
        return;
      }
      synchronized (completed) {
        if (completed.size() >= capacity) {
          completed.removeFirst();
          dropped.incrementAndGet();
        }
        completed.addLast(new CompletedCapture(invocation.pending,
            new CapturedFrame(invocation.width, invocation.height, rgba)));
      }
      captured.incrementAndGet();
    } catch (RuntimeException | LinkageError error) {
      recordFailure("visual-parity-readback-failed");
    }
  }

  /** Captures the final framebuffer after the complete Iris frame returns. */
  public void captureStandalone(
      IrisPipelineStateCapture.PendingState pending) {
    Objects.requireNonNull(pending, "pending");
    if (!enabled || !pending.replayBuffers().captureEnabled()) {
      return;
    }
    IrisDynamicDrawState dynamic = pending.dynamicState();
    if (!dynamic.viewport().isKnown()) {
      recordFailure("visual-parity-viewport-unavailable");
      return;
    }
    IrisDynamicDrawState.Rect viewport = dynamic.viewport().value();
    if (viewport.x() != 0 || viewport.y() != 0 || viewport.width() <= 0
        || viewport.height() <= 0
        || (long) viewport.width() * viewport.height()
            > IrisVisualParityGate.MAX_PIXELS) {
      recordFailure("visual-parity-viewport-unsupported");
      return;
    }
    scheduled.incrementAndGet();
    try {
      byte[] rgba = readback.capture(pending, viewport.width(),
          viewport.height());
      int required = Math.toIntExact(Math.multiplyExact(
          (long) viewport.width() * viewport.height(), 4L));
      if (rgba == null || rgba.length != required) {
        recordFailure("visual-parity-readback-size-mismatch");
        return;
      }
      synchronized (completed) {
        if (completed.size() >= capacity) {
          completed.removeFirst();
          dropped.incrementAndGet();
        }
        completed.addLast(new CompletedCapture(pending,
            new CapturedFrame(viewport.width(), viewport.height(), rgba)));
      }
      captured.incrementAndGet();
    } catch (RuntimeException | LinkageError error) {
      recordFailure("visual-parity-readback-failed");
    }
  }

  /** Captures one generated GL mip level for exact graph diagnostics. */
  Optional<CapturedFrame> captureTextureMipRgba8(int texture, int target,
      int mipLevel) {
    if (!enabled || texture <= 0 || target != GL11C.GL_TEXTURE_2D
        || mipLevel < 0) {
      return Optional.empty();
    }
    scheduled.incrementAndGet();
    int previousTexture = 0;
    int previousPackBuffer = 0;
    int previousAlignment = 4;
    int previousRowLength = 0;
    int previousSkipRows = 0;
    int previousSkipPixels = 0;
    ByteBuffer buffer = null;
    try {
      previousTexture = GL11C.glGetInteger(0x8069);
      previousPackBuffer = GL11C.glGetInteger(
          GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
      previousAlignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
      previousRowLength = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH);
      previousSkipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS);
      previousSkipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS);
      GL11C.glBindTexture(target, texture);
      int width = GL11C.glGetTexLevelParameteri(target, mipLevel,
          GL11C.GL_TEXTURE_WIDTH);
      int height = GL11C.glGetTexLevelParameteri(target, mipLevel,
          GL11C.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0
          || (long) width * height > IrisVisualParityGate.MAX_PIXELS) {
        recordFailure("visual-parity-mip-extent-unavailable");
        return Optional.empty();
      }
      int byteCount = Math.toIntExact(Math.multiplyExact(
          (long) width * height, 4L));
      GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
      GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
      buffer = MemoryUtil.memAlloc(byteCount);
      GL11C.glGetTexImage(target, mipLevel, GL11C.GL_RGBA,
          GL11C.GL_UNSIGNED_BYTE, buffer);
      byte[] rgba8 = new byte[byteCount];
      buffer.get(0, rgba8);
      captured.incrementAndGet();
      return Optional.of(new CapturedFrame(width, height, rgba8));
    } catch (RuntimeException | LinkageError error) {
      recordFailure("visual-parity-mip-readback-failed");
      return Optional.empty();
    } finally {
      if (buffer != null) {
        MemoryUtil.memFree(buffer);
      }
      try {
        GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, previousAlignment);
        GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, previousRowLength);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, previousSkipRows);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS,
            previousSkipPixels);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
        GL11C.glBindTexture(target, previousTexture);
      } catch (RuntimeException | LinkageError ignored) {
        // The primary diagnostic failure remains fail-closed.
      }
    }
  }

  public Optional<CapturedFrame> take(
      IrisPipelineStateCapture.PendingState pending) {
    Objects.requireNonNull(pending, "pending");
    synchronized (completed) {
      Iterator<CompletedCapture> iterator = completed.iterator();
      while (iterator.hasNext()) {
        CompletedCapture value = iterator.next();
        if (value.pending == pending) {
          iterator.remove();
          return Optional.of(value.frame);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Returns the newest capture for a draw and discards older snapshots of the
   * same target. Full-graph validation uses this after captureStandalone(), so
   * it observes the attachment after every later Iris operation in the frame
   * rather than the immediate post-draw image queued by endDrawInvocation().
   */
  public Optional<CapturedFrame> takeLatest(
      IrisPipelineStateCapture.PendingState pending) {
    Objects.requireNonNull(pending, "pending");
    CapturedFrame latest = null;
    synchronized (completed) {
      Iterator<CompletedCapture> iterator = completed.iterator();
      while (iterator.hasNext()) {
        CompletedCapture value = iterator.next();
        if (value.pending == pending) {
          latest = value.frame;
          iterator.remove();
        }
      }
    }
    return Optional.ofNullable(latest);
  }

  public void reset() {
    invocations.remove();
    synchronized (completed) {
      completed.clear();
    }
    lastFailure.set("");
  }

  public Status status() {
    int pending;
    synchronized (completed) {
      pending = completed.size();
    }
    return new Status(enabled, scheduled.get(), captured.get(), failed.get(),
        dropped.get(), pending, lastFailure.get());
  }

  private void recordFailure(String reason) {
    failed.incrementAndGet();
    lastFailure.set(reason);
  }

  private static byte[] readOpenGlRgba8(
      IrisPipelineStateCapture.PendingState pending, int width, int height) {
    IrisGlStateSnapshot snapshot = pending.snapshot();
    if (!snapshot.drawFramebuffer().isKnown()
        || !snapshot.drawBuffers().isKnown()) {
      throw new IllegalStateException("visual parity framebuffer unavailable");
    }
    int framebuffer = snapshot.drawFramebuffer().value().name();
    int drawBuffer = snapshot.drawBuffers().value().stream()
        .filter(value -> value != GL11C.GL_NONE)
        .findFirst().orElseThrow();
    int byteCount = Math.toIntExact(Math.multiplyExact(
        (long) width * height, 4L));
    int priorFramebuffer = GL11C.glGetInteger(
        GL30C.GL_READ_FRAMEBUFFER_BINDING);
    int priorReadBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
    int priorPackBuffer = GL11C.glGetInteger(
        GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
    int priorAlignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
    int priorRowLength = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH);
    int priorSkipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS);
    int priorSkipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS);
    ByteBuffer buffer = MemoryUtil.memAlloc(byteCount);
    try {
      GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
      GL11C.glReadBuffer(drawBuffer);
      GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
      GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
      GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
      GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA,
          GL11C.GL_UNSIGNED_BYTE, buffer);
      byte[] result = new byte[byteCount];
      buffer.get(0, result);
      return result;
    } finally {
      GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, priorAlignment);
      GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, priorRowLength);
      GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, priorSkipRows);
      GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, priorSkipPixels);
      GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, priorPackBuffer);
      GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, priorFramebuffer);
      GL11C.glReadBuffer(priorReadBuffer);
      MemoryUtil.memFree(buffer);
    }
  }

  public record CapturedFrame(int width, int height, byte[] rgba8) {
    public CapturedFrame {
      if (width <= 0 || height <= 0 || rgba8 == null
          || rgba8.length != (long) width * height * 4) {
        throw new IllegalArgumentException("invalid visual parity frame");
      }
    }
  }

  public record Status(boolean enabled, long scheduled, long captured,
                       long failed, long dropped, int pending,
                       String lastFailure) {
  }

  @FunctionalInterface
  interface Readback {
    byte[] capture(IrisPipelineStateCapture.PendingState pending,
                   int width, int height);
  }

  private static final class Invocation {
    private IrisPipelineStateCapture.PendingState pending;
    private int width;
    private int height;
  }

  private record CompletedCapture(
      IrisPipelineStateCapture.PendingState pending, CapturedFrame frame) {
  }
}

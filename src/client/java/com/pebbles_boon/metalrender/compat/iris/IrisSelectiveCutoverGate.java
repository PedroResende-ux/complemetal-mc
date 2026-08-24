package com.pebbles_boon.metalrender.compat.iris;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Per-frame fail-open gate. It never permits an OpenGL draw to be skipped
 * until the corresponding Metal encode has succeeded in the same frame.
 */
public final class IrisSelectiveCutoverGate {
  private final Set<IrisRenderGraph.Phase> eligiblePhases;
  private Mode mode = Mode.SHADOW;
  private long contextGeneration = -1;
  private long currentFrame = -1;
  private boolean frameFallback;
  private long metalEncodes;
  private long openGlSuppressions;
  private long frameFallbacks;
  private long lifecycleResets;

  public IrisSelectiveCutoverGate(Set<IrisRenderGraph.Phase> eligiblePhases) {
    Objects.requireNonNull(eligiblePhases, "eligiblePhases");
    if (eligiblePhases.isEmpty()) {
      throw new IllegalArgumentException("cutover requires an eligible phase");
    }
    this.eligiblePhases = Set.copyOf(EnumSet.copyOf(eligiblePhases));
  }

  public synchronized void beginFrame(long frame, long generation) {
    if (frame < 0 || generation <= 0 || frame <= currentFrame) {
      throw new IllegalArgumentException("invalid cutover frame identity");
    }
    if (contextGeneration != -1 && contextGeneration != generation) {
      mode = Mode.SHADOW;
      lifecycleResets++;
    }
    contextGeneration = generation;
    currentFrame = frame;
    frameFallback = false;
  }

  public synchronized void arm(boolean visualParityValidated) {
    if (!visualParityValidated || currentFrame < 0) {
      return;
    }
    if (mode == Mode.SHADOW) {
      mode = Mode.ARMED;
    }
  }

  public synchronized Ticket beforeDraw(IrisRenderGraph.Phase phase,
      String pipelineKey, boolean pipelineReady,
      boolean resourceBindingsComplete) {
    Objects.requireNonNull(phase, "phase");
    IrisRenderGraph.requireSha(pipelineKey, "pipelineKey");
    boolean candidate = currentFrame >= 0 && !frameFallback
        && (mode == Mode.ARMED || mode == Mode.ACTIVE)
        && eligiblePhases.contains(phase) && pipelineReady
        && resourceBindingsComplete;
    return new Ticket(currentFrame, contextGeneration, phase, pipelineKey,
        candidate);
  }

  /** Returns true only when the paired OpenGL draw may now be suppressed. */
  public synchronized boolean metalEncodeCompleted(Ticket ticket,
      boolean encoded, boolean commandAccepted) {
    Objects.requireNonNull(ticket, "ticket");
    requireCurrent(ticket);
    if (!ticket.candidate()) {
      return false;
    }
    metalEncodes++;
    if (!encoded || !commandAccepted) {
      fallbackCurrentFrame();
      return false;
    }
    mode = Mode.ACTIVE;
    openGlSuppressions++;
    return true;
  }

  public synchronized void asynchronousFailure() {
    fallbackCurrentFrame();
    mode = Mode.SHADOW;
  }

  public synchronized Status status() {
    return new Status(mode, currentFrame, contextGeneration, frameFallback,
        metalEncodes, openGlSuppressions, frameFallbacks, lifecycleResets);
  }

  private void requireCurrent(Ticket ticket) {
    if (ticket.frame() != currentFrame
        || ticket.contextGeneration() != contextGeneration) {
      throw new IllegalStateException("stale cutover ticket");
    }
  }

  private void fallbackCurrentFrame() {
    if (!frameFallback) {
      frameFallback = true;
      frameFallbacks++;
    }
  }

  public enum Mode {
    SHADOW,
    ARMED,
    ACTIVE
  }

  public record Ticket(long frame, long contextGeneration,
                       IrisRenderGraph.Phase phase, String pipelineKey,
                       boolean candidate) {
  }

  public record Status(Mode mode, long currentFrame, long contextGeneration,
                       boolean frameFallback, long metalEncodes,
                       long openGlSuppressions, long frameFallbacks,
                       long lifecycleResets) {
  }
}

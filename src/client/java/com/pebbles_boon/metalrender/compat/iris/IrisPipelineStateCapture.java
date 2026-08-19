package com.pebbles_boon.metalrender.compat.iris;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded render-thread capture bridge for unique Iris GL pipeline variants.
 * It performs no disk I/O, shader hashing, translation, or native calls.
 */
public final class IrisPipelineStateCapture {
  public static final int DEFAULT_QUEUE_CAPACITY = 8_192;
  public static final int DEFAULT_RECENT_VARIANT_CAPACITY = 16_384;
  private static final IrisPipelineStateCapture GLOBAL =
      new IrisPipelineStateCapture(new IrisGlStateTracker(),
          IrisProgramIdentityRegistry.global(), DEFAULT_QUEUE_CAPACITY,
          DEFAULT_RECENT_VARIANT_CAPACITY);

  private final IrisGlStateTracker tracker;
  private final IrisGlResourceBindingTracker resourceBindings;
  private final IrisProgramIdentityRegistry identities;
  private final int queueCapacity;
  private final int recentVariantCapacity;
  private final ConcurrentLinkedQueue<PendingState> queue =
      new ConcurrentLinkedQueue<>();
  private final AtomicInteger queued = new AtomicInteger();
  private final LinkedHashMap<StateSignature, Boolean> recentVariants =
      new LinkedHashMap<>();
  private final AtomicLong drawsObserved = new AtomicLong();
  private final AtomicLong dispatchesObserved = new AtomicLong();
  private final AtomicLong variantsAccepted = new AtomicLong();
  private final AtomicLong variantsRejected = new AtomicLong();
  private final AtomicLong incompleteVariants = new AtomicLong();
  private volatile int currentGlProgram;

  IrisPipelineStateCapture(IrisGlStateTracker tracker,
      IrisProgramIdentityRegistry identities, int queueCapacity,
      int recentVariantCapacity) {
    this.tracker = Objects.requireNonNull(tracker, "tracker");
    resourceBindings = IrisGlResourceBindingTracker.global();
    this.identities = Objects.requireNonNull(identities, "identities");
    if (queueCapacity <= 0 || recentVariantCapacity < queueCapacity) {
      throw new IllegalArgumentException("invalid pipeline capture bounds");
    }
    this.queueCapacity = queueCapacity;
    this.recentVariantCapacity = recentVariantCapacity;
  }

  public static IrisPipelineStateCapture global() {
    return GLOBAL;
  }

  public IrisGlStateTracker tracker() {
    return tracker;
  }

  public void initializeOpenGlDefaults() {
    tracker.initializeOpenGlDefaults();
    resourceBindings.initializeOpenGlDefaults();
    currentGlProgram = 0;
  }

  public void registerProgram(int glProgram) {
    tracker.registerProgram(glProgram);
    resourceBindings.registerProgram(glProgram);
  }

  public void deleteProgram(int glProgram) {
    tracker.deleteProgram(glProgram);
    resourceBindings.deleteProgram(glProgram);
    if (currentGlProgram == glProgram) {
      // OpenGL retains a deleted current program until unbound. Keep the
      // numeric binding so any final draw stays associated with its exact
      // registration; identity deletion happens only after the GL hook.
    }
  }

  public void useProgram(int glProgram) {
    currentGlProgram = glProgram;
    tracker.useProgram(glProgram);
    resourceBindings.useProgram(glProgram);
  }

  public void draw(int primitiveMode) {
    int glProgram = currentGlProgram;
    Optional<IrisProgramIdentityRegistry.Registration> registration =
        identities.lookup(glProgram);
    if (registration.isEmpty()) {
      return;
    }
    drawsObserved.incrementAndGet();
    capture(registration.orElseThrow(), tracker.snapshotDraw(primitiveMode));
  }

  public void dispatch() {
    int glProgram = currentGlProgram;
    Optional<IrisProgramIdentityRegistry.Registration> registration =
        identities.lookup(glProgram);
    if (registration.isEmpty()) {
      return;
    }
    dispatchesObserved.incrementAndGet();
    capture(registration.orElseThrow(), tracker.snapshotDispatch());
  }

  private void capture(
      IrisProgramIdentityRegistry.Registration registration,
      IrisGlStateSnapshot snapshot) {
    PendingState pending = new PendingState(registration, snapshot,
        resourceBindings.snapshot());
    IrisRenderGraphCapture.global().draw(pending);
    offer(pending);
  }

  private void offer(PendingState pending) {
    IrisProgramIdentityRegistry.Registration registration =
        pending.registration();
    IrisGlStateSnapshot snapshot = pending.snapshot();
    StateSignature signature = new StateSignature(registration.generation(),
        snapshot.operation(), snapshot.program(), snapshot.drawFramebuffer(),
        snapshot.drawBuffers(), snapshot.colorTargets(),
        snapshot.depthAttachment(), snapshot.stencilAttachment(),
        snapshot.depth(), snapshot.stencil(), snapshot.raster(),
        snapshot.multisample(), snapshot.primitive(),
        snapshot.unknownFields(), snapshot.resourceEvictions());
    synchronized (recentVariants) {
      if (recentVariants.containsKey(signature)) {
        return;
      }
      recentVariants.put(signature, Boolean.TRUE);
      while (recentVariants.size() > recentVariantCapacity) {
        recentVariants.remove(recentVariants.entrySet().iterator().next()
            .getKey());
      }
    }
    int count = queued.incrementAndGet();
    if (count > queueCapacity) {
      queued.decrementAndGet();
      variantsRejected.incrementAndGet();
      return;
    }
    if (!snapshot.complete()) {
      incompleteVariants.incrementAndGet();
    }
    queue.offer(pending);
    variantsAccepted.incrementAndGet();
  }

  public Optional<PendingState> poll() {
    PendingState state = queue.poll();
    if (state == null) {
      return Optional.empty();
    }
    queued.decrementAndGet();
    return Optional.of(state);
  }

  public int queued() {
    return queued.get();
  }

  public long drawsObserved() {
    return drawsObserved.get();
  }

  public long dispatchesObserved() {
    return dispatchesObserved.get();
  }

  public long variantsAccepted() {
    return variantsAccepted.get();
  }

  public long variantsRejected() {
    return variantsRejected.get();
  }

  public long incompleteVariants() {
    return incompleteVariants.get();
  }

  public record PendingState(
      IrisProgramIdentityRegistry.Registration registration,
      IrisGlStateSnapshot snapshot,
      IrisGlResourceBindingSnapshot resourceBindings) {
    public PendingState {
      Objects.requireNonNull(registration, "registration");
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }

  private record StateSignature(long registrationGeneration,
                                IrisGlStateSnapshot.Operation operation,
      IrisGlStateSnapshot.StateValue<java.util.Optional<
          IrisGlStateSnapshot.ResourceHandle>> program,
      IrisGlStateSnapshot.StateValue<IrisGlStateSnapshot.ResourceHandle>
          framebuffer,
      IrisGlStateSnapshot.StateValue<java.util.List<Integer>> drawBuffers,
      java.util.List<IrisGlStateSnapshot.ColorTarget> colorTargets,
      IrisGlStateSnapshot.StateValue<java.util.Optional<
          IrisGlStateSnapshot.TextureAttachment>> depthAttachment,
      IrisGlStateSnapshot.StateValue<java.util.Optional<
          IrisGlStateSnapshot.TextureAttachment>> stencilAttachment,
      IrisGlStateSnapshot.DepthState depth,
      IrisGlStateSnapshot.StencilState stencil,
      IrisGlStateSnapshot.RasterState raster,
      IrisGlStateSnapshot.MultisampleState multisample,
      IrisGlStateSnapshot.PrimitiveState primitive,
      java.util.List<String> unknownFields,
      long resourceEvictions) {
  }
}

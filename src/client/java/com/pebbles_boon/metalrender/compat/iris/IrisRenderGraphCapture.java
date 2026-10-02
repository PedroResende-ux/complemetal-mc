package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Phase;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Bounded render-thread capture of Iris pass ordering and resource routing. */
public final class IrisRenderGraphCapture {
  public static final int MAX_EVENTS_PER_FRAME = 32_768;
  public static final int FRAME_QUEUE_CAPACITY = 16;
  public static final int MAX_REPLAY_SAMPLES_PER_PHASE = 4;
  public static final int MAX_FULL_GRAPH_FINAL_TARGETS = 16;
  public static final long MAX_FULL_REPLAY_CAPTURE_BYTES =
      384L * 1024L * 1024L;
  private static final int GL_DEPTH_BUFFER_BIT = 0x00000100;
  private static final int GL_STENCIL_BUFFER_BIT = 0x00000400;
  private static final int GL_COLOR_BUFFER_BIT = 0x00004000;
  private static final long MIN_FRAME_INTERVAL_NANOS = 500_000_000L;
  private static final IrisRenderGraphCapture GLOBAL =
      new IrisRenderGraphCapture(IrisPipelineStateCapture.global().tracker());
  private static final java.util.Set<Integer> EXACT_UNTRACKED_TEXTURES =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final int MAX_EXACT_UNTRACKED_TEXTURES = 96;

  private final IrisGlStateTracker state;
  private final ConcurrentLinkedQueue<PendingFrame> queue =
      new ConcurrentLinkedQueue<>();
  private final AtomicInteger queued = new AtomicInteger();
  private final AtomicLong framesStarted = new AtomicLong();
  private final AtomicLong framesCompleted = new AtomicLong();
  private final AtomicLong framesRejected = new AtomicLong();
  private final EnumMap<Phase, Integer> replaySamples =
      new EnumMap<>(Phase.class);
  private final LongSupplier nanoTime;
  private final BooleanSupplier fullGraphReservation;
  private FrameBuilder current;
  private Phase phase = Phase.UNKNOWN;
  private long lastFrameStartNanos;
  private boolean frozen;
  private float legacyClearRed;
  private float legacyClearGreen;
  private float legacyClearBlue;
  private float legacyClearAlpha;
  private long fullReplayFramesCompleted;

  IrisRenderGraphCapture(IrisGlStateTracker state) {
    this(state, System::nanoTime,
        IrisTranslationCoordinator::reserveFullGraphCapture);
  }

  IrisRenderGraphCapture(IrisGlStateTracker state, LongSupplier nanoTime,
      BooleanSupplier fullGraphReservation) {
    this.state = Objects.requireNonNull(state, "state");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.fullGraphReservation = Objects.requireNonNull(
        fullGraphReservation, "fullGraphReservation");
  }

  public static IrisRenderGraphCapture global() {
    return GLOBAL;
  }

  public synchronized void beginFrame() {
    if (frozen) {
      return;
    }
    if (current != null) {
      framesRejected.incrementAndGet();
      abortFullReplay(current);
    }
    if (queued.get() >= FRAME_QUEUE_CAPACITY) {
      current = null;
      phase = Phase.UNKNOWN;
      return;
    }
    long now = nanoTime.getAsLong();
    boolean fullReplay = fullGraphReservation.getAsBoolean();
    if (!fullReplay && lastFrameStartNanos != 0
        && now - lastFrameStartNanos < MIN_FRAME_INTERVAL_NANOS) {
      current = null;
      phase = Phase.UNKNOWN;
      return;
    }
    lastFrameStartNanos = now;
    current = new FrameBuilder(fullReplay,
        fullReplay && !IrisTranslationCoordinator
            .fullGraphOwnershipCaptureActive(), now);
    phase = Phase.BEGIN;
    framesStarted.incrementAndGet();
  }

  public synchronized void phase(Phase next) {
    phase = Objects.requireNonNull(next, "phase");
    if (current != null) {
      current.phases.add(next);
    }
  }

  public synchronized Phase currentPhase() {
    return phase;
  }

  /**
   * Returns whether the current render thread is inside a frame that can
   * actually retain captured graph operations. Ownership cancellation must not
   * suppress OpenGL after capture backpressure or an aborted frame has already
   * discarded this builder.
   */
  public synchronized boolean hasActiveFrame() {
    return current != null && !current.overflowed;
  }

  public synchronized void draw(
      IrisPipelineStateCapture.PendingState pending) {
    recordPipelineEvent(pending, true);
  }

  public synchronized void dispatch(
      IrisPipelineStateCapture.PendingState pending) {
    recordPipelineEvent(pending, false);
  }

  private void recordPipelineEvent(
      IrisPipelineStateCapture.PendingState pending, boolean graphics) {
    if (current == null) {
      return;
    }
    if (current.fullReplay) {
      String resourceAbortReason = fullReplayResourceAbortReason(
          pending.replayBuffers(), pending.replayTextures(),
          pending.replaySamplers());
      if (!resourceAbortReason.isEmpty()) {
        current.fullReplayComplete = false;
        current.preferFullReplayAbortReason(resourceAbortReason);
      }
      try {
        current.fullReplayBytes = retainedFullReplayBytes(
            current.fullReplayBuffers, current.fullReplayTextures);
        if (current.fullReplayBytes > MAX_FULL_REPLAY_CAPTURE_BYTES) {
          current.overflowed = true;
          current.fullReplayComplete = false;
          current.preferFullReplayAbortReason(
              "graph-frame-capture-byte-capacity-exceeded");
        }
      } catch (ArithmeticException overflow) {
        current.overflowed = true;
        current.fullReplayComplete = false;
        current.preferFullReplayAbortReason(
            "graph-frame-capture-byte-capacity-exceeded");
      }
      if (graphics && current.fullReplayParity && (phase == Phase.FINAL
          || IrisVisualParityCapture.diagnosticGraphReadback(pending))
          && pending.replayBuffers().captureEnabled()) {
        Optional<ResourceHandle> output = primaryColorOutput(pending);
        if (output.isPresent()) {
          ResourceHandle handle = output.orElseThrow();
          if (!current.finalReplayTargets.containsKey(handle)
              && current.finalReplayTargets.size()
                  >= MAX_FULL_GRAPH_FINAL_TARGETS) {
            current.fullReplayComplete = false;
          } else {
            current.finalReplayTargets.put(handle, pending);
          }
        }
      }
    }
    ArrayList<RawResource> reads = new ArrayList<>();
    ArrayList<RawResource> writes = new ArrayList<>();
    IrisGlResourceBindingSnapshot bindings = pending.resourceBindings();
    if (bindings != null) {
      bindings.textureUnits().values().forEach(binding ->
          addTexture(reads, binding.texture()));
      bindings.imageUnits().values().forEach(binding -> {
        int texture = binding.texture();
        if (texture <= 0) {
          return;
        }
        if (binding.access() == 0x88B9) {
          addTexture(writes, texture);
        } else if (binding.access() == 0x88BA) {
          addTexture(reads, texture);
          addTexture(writes, texture);
        } else {
          addTexture(reads, texture);
        }
      });
    }
    IrisGlStateSnapshot snapshot = pending.snapshot();
    if (graphics && snapshot.operation()
        == IrisGlStateSnapshot.Operation.DRAW) {
      for (IrisGlStateSnapshot.ColorTarget target : snapshot.colorTargets()) {
        addAttachment(writes, target.attachment());
      }
      addAttachment(writes, snapshot.depthAttachment());
      addAttachment(writes, snapshot.stencilAttachment());
    }
    if (graphics) {
      add(new RawDraw(phase, pending, distinct(reads), distinct(writes)));
    } else {
      add(new RawDispatch(phase, pending, distinct(reads), distinct(writes)));
    }
  }

  /**
   * Records that the frame contains an operation the Metal graph cannot
   * represent. The original OpenGL operation must still execute; this only
   * prevents an incomplete graph from being accepted for ownership.
   */
  public synchronized void markUnsupportedFullReplayOperation(String reason) {
    if (current != null && current.fullReplay) {
      current.fullReplayComplete = false;
      current.preferFullReplayAbortReason(
          reason == null || reason.isBlank()
              ? "graph-frame-operation-unsupported"
              : reason);
    }
  }

  public synchronized boolean memoryBarrier(int bits) {
    if (current != null && bits >= 0) {
      return add(new RawBarrier(phase, bits));
    }
    return false;
  }

  public synchronized boolean blitFramebuffer(int source, int destination) {
    return blitFramebuffer(source, destination, GL_COLOR_BUFFER_BIT
        | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
  }

  public synchronized boolean blitFramebuffer(int source, int destination,
      int mask) {
    return blitFramebuffer(source, destination, mask,
        new IrisTransferCommand.Unknown(IrisRenderGraph.NodeKind.BLIT));
  }

  public synchronized boolean blitFramebuffer(int source, int destination,
      int sourceX0, int sourceY0, int sourceX1, int sourceY1,
      int destinationX0, int destinationY0,
      int destinationX1, int destinationY1, int mask, int filter) {
    ResourceHandle sourceHandle = state.framebufferHandle(source).orElse(null);
    ResourceHandle destinationHandle = state.framebufferHandle(destination)
        .orElse(null);
    if (sourceHandle == null || destinationHandle == null) {
      return false;
    }
    return blitFramebuffer(source, destination, mask,
        new IrisTransferCommand.BlitFramebuffer(sourceHandle,
            destinationHandle, sourceX0, sourceY0, sourceX1, sourceY1,
            destinationX0, destinationY0, destinationX1, destinationY1,
            mask, filter));
  }

  private boolean blitFramebuffer(int source, int destination, int mask,
      IrisTransferCommand command) {
    if (current == null) {
      return false;
    }
    List<RawResource> reads = framebufferTextures(source, mask);
    List<RawResource> writes = framebufferTextures(destination, mask);
    if (reads.isEmpty()) {
      RawResource unresolved = framebuffer(source);
      reads = unresolved == null ? List.of() : List.of(unresolved);
    }
    if (writes.isEmpty()) {
      RawResource unresolved = framebuffer(destination);
      writes = unresolved == null ? List.of() : List.of(unresolved);
    }
    if (!reads.isEmpty() || !writes.isEmpty()) {
      return add(new RawTransfer(phase, reads, writes, command));
    }
    return false;
  }

  public synchronized boolean copyTexture(int destinationTexture, int target,
      int level, int destinationX, int destinationY, int sourceX,
      int sourceY, int width, int height, int readBuffer) {
    if (current == null) {
      return false;
    }
    RawResource destination = texture(destinationTexture);
    ResourceHandle sourceFramebuffer = state.readFramebufferHandle()
        .orElse(null);
    int copyMask = destination == null ? 0
        : copyAspectMask(destination.format());
    ResourceHandle sourceTexture = sourceFramebuffer == null ? null
        : framebufferCopyTexture(sourceFramebuffer.name(), readBuffer,
            copyMask);
    if (destination != null && sourceFramebuffer != null && copyMask != 0) {
      List<RawResource> reads = framebufferTextures(
          sourceFramebuffer.name(), copyMask);
      if (reads.isEmpty()) {
        RawResource unresolved = framebuffer(sourceFramebuffer.name());
        reads = unresolved == null ? List.of() : List.of(unresolved);
      }
      IrisTransferCommand command = sourceTexture == null
          ? new IrisTransferCommand.Unknown(
              IrisRenderGraph.NodeKind.COPY_TEXTURE)
          : new IrisTransferCommand.CopyTexSubImage2D(sourceFramebuffer,
              sourceTexture, destination.handle(), target, level,
              destinationX, destinationY, sourceX, sourceY, width, height);
      return add(new RawTransfer(phase, reads, List.of(destination), command));
    }
    return false;
  }

  public synchronized boolean copyBoundTexture(int target, int level,
      int internalFormat, int sourceX, int sourceY, int width, int height,
      int border, int readBuffer) {
    IrisGlResourceBindingSnapshot.TextureUnitBinding active =
        IrisGlResourceBindingTracker.global().activeTextureBinding(target);
    if (active == null || current == null) {
      return false;
    }
    RawResource destination = texture(active.texture());
    ResourceHandle sourceFramebuffer = state.readFramebufferHandle()
        .orElse(null);
    int copyMask = destination == null ? 0
        : copyAspectMask(destination.format());
    ResourceHandle sourceTexture = sourceFramebuffer == null ? null
        : framebufferCopyTexture(sourceFramebuffer.name(), readBuffer,
            copyMask);
    if (destination != null && sourceFramebuffer != null && copyMask != 0) {
      List<RawResource> reads = framebufferTextures(
          sourceFramebuffer.name(), copyMask);
      if (reads.isEmpty()) {
        RawResource unresolved = framebuffer(sourceFramebuffer.name());
        reads = unresolved == null ? List.of() : List.of(unresolved);
      }
      IrisTransferCommand command = sourceTexture == null
          ? new IrisTransferCommand.Unknown(
              IrisRenderGraph.NodeKind.COPY_TEXTURE)
          : new IrisTransferCommand.CopyTexImage2D(sourceFramebuffer,
              sourceTexture, destination.handle(), target, level,
              internalFormat, sourceX, sourceY, width, height, border);
      return add(new RawTransfer(phase, reads, List.of(destination), command));
    }
    return false;
  }

  private ResourceHandle framebufferCopyTexture(int framebuffer,
      int readBuffer, int mask) {
    IrisGlStateTracker.FramebufferMetadata metadata =
        state.framebufferMetadata(framebuffer).orElse(null);
    if (metadata == null || !metadata.complete()) {
      return null;
    }
    if (mask == GL_COLOR_BUFFER_BIT) {
      return metadata.attachments().stream()
          .filter(attachment -> attachment.attachment() == readBuffer)
          .map(attachment -> attachment.texture().handle())
          .findFirst().orElse(null);
    }
    ResourceHandle depth = metadata.attachments().stream()
        .filter(attachment -> attachment.attachment()
            == IrisGlStateTracker.GL_DEPTH_ATTACHMENT)
        .map(attachment -> attachment.texture().handle())
        .findFirst().orElse(null);
    ResourceHandle stencil = metadata.attachments().stream()
        .filter(attachment -> attachment.attachment()
            == IrisGlStateTracker.GL_STENCIL_ATTACHMENT)
        .map(attachment -> attachment.texture().handle())
        .findFirst().orElse(null);
    boolean needsDepth = (mask & GL_DEPTH_BUFFER_BIT) != 0;
    boolean needsStencil = (mask & GL_STENCIL_BUFFER_BIT) != 0;
    if (needsDepth && needsStencil) {
      return depth != null && depth.equals(stencil) ? depth : null;
    }
    return needsDepth ? depth : needsStencil ? stencil : null;
  }

  private static int copyAspectMask(String format) {
    boolean depth = format.startsWith("d16-")
        || format.startsWith("d24-") || format.startsWith("d32-");
    boolean stencil = format.startsWith("s8-")
        || format.contains("-s8-");
    if (depth || stencil) {
      return (depth ? GL_DEPTH_BUFFER_BIT : 0)
          | (stencil ? GL_STENCIL_BUFFER_BIT : 0);
    }
    return !format.equals("framebuffer")
        && !format.equals("runtime-texture") ? GL_COLOR_BUFFER_BIT : 0;
  }

  public synchronized boolean generateMipmaps(int texture, int target) {
    if (current == null) {
      return false;
    }
    RawResource resource = texture(texture);
    if (resource != null) {
      boolean added = add(new RawTransfer(phase, List.of(resource), List.of(resource),
          new IrisTransferCommand.GenerateMipmaps(resource.handle(),
              target)));
      if (added) {
        captureDiagnosticGeneratedMip(resource.handle(), texture, target);
      }
      return added;
    }
    return false;
  }

  private void captureDiagnosticGeneratedMip(ResourceHandle handle,
      int texture, int target) {
    if (!current.fullReplay
        || System.getProperty("metalrender.exactJar.expectedPath") == null) {
      return;
    }
    int mipLevel = Integer.getInteger(
        "metalrender.exactJar.diagnosticGraphReadbackMipLevel", -1);
    if (mipLevel < 0) {
      return;
    }
    if (!current.diagnosticReplayTargets.containsKey(handle)
        && current.diagnosticReplayTargets.size()
            >= MAX_FULL_GRAPH_FINAL_TARGETS) {
      current.fullReplayComplete = false;
      return;
    }
    IrisVisualParityCapture.global().captureTextureMipRgba8(texture, target,
        mipLevel).ifPresent(frame ->
            current.diagnosticReplayTargets.put(handle, frame));
  }

  public synchronized boolean clearNamedFramebufferFloat(int framebuffer,
      int buffer, int drawBuffer, float[] values) {
    Objects.requireNonNull(values, "values");
    ResourceHandle target = state.framebufferHandle(framebuffer).orElse(null);
    if (target == null) {
      return false;
    }
    IrisClearCommand command;
    if (buffer == IrisClearCommand.GL_COLOR && values.length >= 4) {
      command = IrisClearCommand.colorFloat(target, drawBuffer, values[0],
          values[1], values[2], values[3], Optional.empty());
    } else if (buffer == IrisClearCommand.GL_DEPTH && values.length >= 1) {
      command = IrisClearCommand.depthFloat(target, values[0]);
    } else {
      return false;
    }
    return clearFramebuffer(framebuffer, command);
  }

  public synchronized boolean clearNamedFramebufferSignedInt(int framebuffer,
      int buffer, int drawBuffer, int[] values) {
    Objects.requireNonNull(values, "values");
    ResourceHandle target = state.framebufferHandle(framebuffer).orElse(null);
    if (target == null) {
      return false;
    }
    IrisClearCommand command;
    if (buffer == IrisClearCommand.GL_COLOR && values.length >= 4) {
      command = IrisClearCommand.colorSignedInt(target, drawBuffer,
          java.util.Arrays.copyOf(values, 4));
    } else if (buffer == IrisClearCommand.GL_STENCIL && values.length >= 1) {
      command = IrisClearCommand.stencil(target, values[0]);
    } else {
      return false;
    }
    return clearFramebuffer(framebuffer, command);
  }

  public synchronized boolean clearNamedFramebufferUnsignedInt(int framebuffer,
      int buffer, int drawBuffer, int[] values) {
    Objects.requireNonNull(values, "values");
    ResourceHandle target = state.framebufferHandle(framebuffer).orElse(null);
    if (target == null || buffer != IrisClearCommand.GL_COLOR
        || values.length < 4) {
      return false;
    }
    return clearFramebuffer(framebuffer, IrisClearCommand.colorUnsignedInt(target,
        drawBuffer, java.util.Arrays.copyOf(values, 4)));
  }

  public synchronized boolean clearColorTexture(int texture, float red,
      float green, float blue, float alpha, Optional<IrisClearCommand.Rect>
          region) {
    RawResource resource = texture(texture);
    if (current == null || resource == null) {
      return false;
    }
    IrisClearCommand command = IrisClearCommand.colorFloat(resource.handle(),
        0, red, green, blue, alpha, region);
    return add(new RawClear(phase, command, List.of(resource)));
  }

  public synchronized boolean clearDepthTexture(int texture, double depth,
      Optional<IrisClearCommand.Rect> region) {
    RawResource resource = texture(texture);
    if (current == null || resource == null) {
      return false;
    }
    IrisClearCommand command = IrisClearCommand.depth(resource.handle(),
        depth, region);
    return add(new RawClear(phase, command, List.of(resource)));
  }

  /** Tracks the value consumed by the next legacy {@code glClear}. */
  public synchronized void legacyClearColor(float red, float green,
      float blue, float alpha) {
    if (!Float.isFinite(red) || !Float.isFinite(green)
        || !Float.isFinite(blue) || !Float.isFinite(alpha)) {
      return;
    }
    legacyClearRed = red;
    legacyClearGreen = green;
    legacyClearBlue = blue;
    legacyClearAlpha = alpha;
  }

  /**
   * Captures Iris' direct {@code GlStateManager._clear} against the currently
   * bound framebuffer. Color attachments are resolved through the effective
   * draw-buffer routing; depth/stencil use their exact generation-qualified
   * texture attachments. An unresolved target invalidates only a full replay,
   * never normal OpenGL rendering.
   */
  public synchronized boolean legacyClearBoundFramebuffer(int mask) {
    if (current == null) {
      return false;
    }
    boolean captured = false;
    IrisGlStateSnapshot snapshot = state.snapshotDraw(0x0004);
    boolean complete = true;
    if ((mask & GL_COLOR_BUFFER_BIT) != 0) {
      boolean colorObserved = false;
      for (IrisGlStateSnapshot.ColorTarget target
          : snapshot.colorTargets()) {
        if (!target.drawBuffer().isKnown()) {
          complete = false;
          continue;
        }
        if (target.drawBuffer().value() == 0) {
          continue;
        }
        if (!target.attachment().isKnown()
            || target.attachment().value().isEmpty()) {
          complete = false;
          continue;
        }
        ResourceHandle handle = target.attachment().value().orElseThrow()
            .texture();
        RawResource resource = texture(handle);
        if (resource == null) {
          complete = false;
          continue;
        }
        colorObserved = true;
        captured |= add(new RawClear(phase, IrisClearCommand.colorFloat(handle, 0,
            legacyClearRed, legacyClearGreen, legacyClearBlue,
            legacyClearAlpha, Optional.empty()), List.of(resource)));
      }
      complete &= colorObserved;
    }
    if ((mask & GL_DEPTH_BUFFER_BIT) != 0) {
      boolean depthCaptured = legacyAttachmentClear(snapshot.depthAttachment(),
          true, false);
      complete &= depthCaptured;
      captured |= depthCaptured;
    }
    if ((mask & GL_STENCIL_BUFFER_BIT) != 0) {
      boolean stencilCaptured = legacyAttachmentClear(
          snapshot.stencilAttachment(), false, true);
      complete &= stencilCaptured;
      captured |= stencilCaptured;
    }
    if (!complete && current.fullReplay) {
      current.fullReplayComplete = false;
    }
    return captured && complete;
  }

  private boolean legacyAttachmentClear(
      IrisGlStateSnapshot.StateValue<Optional<
          IrisGlStateSnapshot.TextureAttachment>> attachment,
      boolean depth, boolean stencil) {
    if (!attachment.isKnown() || attachment.value().isEmpty()) {
      return false;
    }
    ResourceHandle handle = attachment.value().orElseThrow().texture();
    RawResource resource = texture(handle);
    if (resource == null) {
      return false;
    }
    IrisClearCommand command = depth
        ? IrisClearCommand.depth(handle, 1.0, Optional.empty())
        : IrisClearCommand.stencil(handle, 0);
    return add(new RawClear(phase, command, List.of(resource)));
  }

  private boolean clearFramebuffer(int framebuffer,
      IrisClearCommand command) {
    if (current == null) {
      return false;
    }
    List<RawResource> writes = framebufferTexturesForClear(framebuffer,
        command.buffer(), command.drawBuffer());
    if (writes.isEmpty()) {
      RawResource unresolved = framebuffer(framebuffer);
      writes = unresolved == null ? List.of() : List.of(unresolved);
    }
    if (!writes.isEmpty()) {
      return add(new RawClear(phase, command, writes));
    }
    return false;
  }

  public synchronized void endFrame() {
    FrameBuilder finished = current;
    current = null;
    phase = Phase.UNKNOWN;
    if (finished == null) {
      return;
    }
    if (finished.events.isEmpty() || finished.overflowed) {
      framesRejected.incrementAndGet();
      abortFullReplay(finished);
      return;
    }
    int count = queued.incrementAndGet();
    if (count > FRAME_QUEUE_CAPACITY) {
      queued.decrementAndGet();
      abortFullReplay(finished);
      return;
    }
    boolean fullReplayCaptured = finished.fullReplay
        && finished.fullReplayComplete;
    if (finished.fullReplay && !fullReplayCaptured) {
      abortFullReplay(finished);
    }
    ArrayList<FinalOutputCapture> finalOutputCaptures = new ArrayList<>();
    if (fullReplayCaptured && finished.fullReplayParity) {
      finished.diagnosticReplayTargets.forEach((texture, frame) ->
          finalOutputCaptures.add(new FinalOutputCapture(texture, frame)));
      IrisVisualParityCapture parity = IrisVisualParityCapture.global();
      for (Map.Entry<ResourceHandle,
          IrisPipelineStateCapture.PendingState> entry
          : finished.finalReplayTargets.entrySet()) {
        Optional<IrisVisualParityCapture.CapturedFrame> frame =
            parity.takeLatest(entry.getValue());
        if (frame.isEmpty()) {
          // Transfers do not have a draw callback; retain a bounded fallback
          // for a final target whose last writer was not a captured draw.
          parity.captureStandalone(entry.getValue());
          frame = parity.takeLatest(entry.getValue());
        }
        frame.ifPresent(value -> finalOutputCaptures.add(
            new FinalOutputCapture(entry.getKey(), value)));
      }
    }
    queue.offer(new PendingFrame(finished.events, finished.phases,
        fullReplayCaptured,
        fullReplayCaptured ? finished.fullReplayBytes : 0,
        fullReplayCaptured ? finalOutputCaptures : List.of(),
        fullReplayCaptured
            ? List.copyOf(finished.fullReplayTextures.values()) : List.of()));
    if (fullReplayCaptured) {
      fullReplayFramesCompleted++;
      if (System.getProperty("metalrender.exactJar.expectedPath") != null
          && (fullReplayFramesCompleted == 1
              || fullReplayFramesCompleted
                  % IrisTranslationCoordinator
                      .FULL_GRAPH_TELEMETRY_INTERVAL_FRAMES == 0)) {
        IrisShadowReplayBufferSnapshot.RetainedCapture retained =
            finished.fullReplayBuffers;
        MetalLogger.info(
            "Iris full Metal capture buffer reuse: entries=%d bytes=%.2fMiB lookups=%d hits=%d",
            retained.entries(), retained.retainedBytes()
                / (1024.0 * 1024.0), retained.lookups(), retained.hits());
        IrisMetalBufferResidentCache.Status resident =
            IrisMetalBufferResidentCache.status();
        MetalLogger.info(
            "Iris full Metal resident buffer cache: buffers=%d bytes=%.2fMiB sourceKeys=%d sourceHits=%d digestHits=%d uploads=%d",
            resident.residents(), resident.residentBytes()
                / (1024.0 * 1024.0), resident.sourceKeys(),
            resident.sourceHits(), resident.digestHits(), resident.uploads());
      }
      IrisTranslationCoordinator.recordFullGraphCaptureNanos(
          nanoTime.getAsLong() - finished.fullReplayStartedNanos);
    }
    framesCompleted.incrementAndGet();
  }

  public Optional<PendingFrame> poll() {
    PendingFrame frame = queue.poll();
    if (frame == null) {
      return Optional.empty();
    }
    queued.decrementAndGet();
    return Optional.of(frame);
  }

  public int queued() {
    return queued.get();
  }

  public long framesStarted() {
    return framesStarted.get();
  }

  public long framesCompleted() {
    return framesCompleted.get();
  }

  public long framesRejected() {
    return framesRejected.get();
  }

  public synchronized void freeze() {
    frozen = true;
    abortFullReplay(current);
    current = null;
    phase = Phase.UNKNOWN;
  }

  private static void abortFullReplay(FrameBuilder frame) {
    if (frame == null || !frame.fullReplay) {
      return;
    }
    IrisGlTextureGpuHandoff.abandonCapturedSurfaces(
        frame.fullReplayTextures.values());
    IrisTranslationCoordinator.fullGraphCaptureAborted(
        frame.fullReplayAbortReason);
  }

  public synchronized boolean frozen() {
    return frozen;
  }

  /** Reserves a bounded real-resource sample only inside a captured frame. */
  public synchronized boolean reserveShadowReplaySample() {
    if (current == null || frozen || !replayPhase(phase)) {
      return false;
    }
    int captured = replaySamples.getOrDefault(phase, 0);
    if (captured >= MAX_REPLAY_SAMPLES_PER_PHASE) {
      return false;
    }
    replaySamples.put(phase, captured + 1);
    return true;
  }

  /** True for every draw in the one bounded full-frame replay candidate. */
  public synchronized boolean captureFullGraphReplay() {
    return current != null && !frozen && current.fullReplay
        && !current.overflowed;
  }

  /**
   * Captures draw buffers against the frame-local immutable range table.
   * Repeated uses of one mirror generation share their owned byte array and
   * avoid another synchronous GL readback/copy.
   */
  public synchronized IrisShadowReplayBufferSnapshot
      captureFullReplayBuffers(IrisExecutionCommand command,
      IrisVertexInputBindings vertexInputBindings,
      IrisGlResourceBindingSnapshot resources, IrisGlBufferMirror mirror,
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor,
      IrisGlGenericAttributeTracker genericAttributes,
      IrisReplayCaptureRequirements requirements) {
    if (!captureFullGraphReplay()) {
      return IrisShadowReplayBufferSnapshot.disabled();
    }
    return IrisShadowReplayBufferSnapshot.capture(command,
        vertexInputBindings, resources, mirror, descriptor,
        genericAttributes, current.fullReplayBuffers, requirements);
  }

  /**
   * Captures each texture generation at most once for a full replay frame.
   * Reusing the first immutable/shared snapshot prevents a later OpenGL pass
   * from overwriting an earlier draw's input before deferred Metal execution.
   */
  public synchronized IrisShadowReplayTextureSnapshot
      captureFullReplayTextures(IrisGlResourceBindingSnapshot resources,
      IrisGlTextureMirror mirror) {
    return captureFullReplayTextures(resources, mirror, "");
  }

  public synchronized IrisShadowReplayTextureSnapshot
      captureFullReplayTextures(IrisGlResourceBindingSnapshot resources,
      IrisGlTextureMirror mirror, String programName) {
    return captureFullReplayTextures(resources, mirror, programName, null);
  }

  public synchronized IrisShadowReplayTextureSnapshot
      captureFullReplayTextures(IrisGlResourceBindingSnapshot resources,
      IrisGlTextureMirror mirror, String programName,
      IrisReplayCaptureRequirements requirements) {
    if (!captureFullGraphReplay()) {
      return IrisShadowReplayTextureSnapshot.disabled();
    }
    String diagnosticProgram = System.getProperty(
        "metalrender.exactJar.diagnosticFreshTextureProgram", "");
    boolean diagnosticFreshCapture = !diagnosticProgram.isEmpty()
        && programName.equals(diagnosticProgram);
    return IrisShadowReplayTextureSnapshot.capture(resources, mirror,
        preferGpuHandoffForFullReplay(diagnosticFreshCapture),
        diagnosticFreshCapture ? null : current.fullReplayTextures,
        requirements == null ? null : requirements.textureNames(),
        diagnosticFreshCapture ? null
            : (texture, generation, mipLevel, layer, metadata) ->
                state.textureHandle(texture).flatMap(handle ->
                    IrisTranslationCoordinator
                        .initializedMetalGraphTextureReference(handle,
                            generation, mipLevel, layer, metadata)));
  }

  /**
   * A diagnostic cut must be an immutable point-in-time image. The normal
   * per-frame retained map makes an IOSurface handoff immutable by preventing
   * a second capture of the same GL texture. A deliberately fresh cut bypasses
   * that map, so use bounded CPU readback instead of aliasing the resident
   * IOSurface slot that a later draw can overwrite before deferred replay.
   */
  static boolean preferGpuHandoffForFullReplay(
      boolean diagnosticFreshCapture) {
    return !diagnosticFreshCapture;
  }

  public synchronized void resetShadowReplaySamples() {
    replaySamples.clear();
  }

  private static boolean replayPhase(Phase value) {
    return value == Phase.SHADOW || value == Phase.GEOMETRY
        || value == Phase.COMPOSITE || value == Phase.FINAL;
  }

  static Optional<ResourceHandle> primaryColorOutput(
      IrisPipelineStateCapture.PendingState pending) {
    Objects.requireNonNull(pending, "pending");
    for (IrisGlStateSnapshot.ColorTarget target
        : pending.snapshot().colorTargets()) {
      if (!target.drawBuffer().isKnown()
          || target.drawBuffer().value() == 0
          || !target.attachment().isKnown()
          || target.attachment().value().isEmpty()) {
        continue;
      }
      return Optional.of(target.attachment().value().orElseThrow().texture());
    }
    return Optional.empty();
  }

  private boolean add(RawEvent event) {
    if (current == null || current.events.size() >= MAX_EVENTS_PER_FRAME) {
      if (current != null) {
        current.overflowed = true;
      }
      return false;
    }
    if (event instanceof RawBarrier && event.equals(current.lastSignature)) {
      return true;
    }
    current.events.add(event);
    current.lastSignature = event instanceof RawBarrier ? event : null;
    current.phases.add(event.phase());
    return true;
  }

  private void addTexture(List<RawResource> resources, int name) {
    RawResource resource = texture(name);
    if (resource != null) {
      resources.add(resource);
    } else if (name > 0 && System.getProperty(
        "metalrender.exactJar.expectedPath") != null
        && EXACT_UNTRACKED_TEXTURES.size() < MAX_EXACT_UNTRACKED_TEXTURES
        && EXACT_UNTRACKED_TEXTURES.add(name)) {
      IrisGlTextureMirror.TextureMetadata metadata =
          IrisGlTextureMirror.global().metadata(name, 0, 0).orElse(null);
      MetalLogger.info(
          "exact-JAR FULL graph untracked sampled texture: t%d mirror=%s",
          name, metadata == null ? "missing"
              : metadata.format() + '/' + metadata.width() + 'x'
                  + metadata.height() + "/layers"
                  + metadata.depthOrLayers() + "/mips?"
                  + "/g" + metadata.generation());
    }
  }

  private RawResource texture(int name) {
    return state.textureMetadata(name)
        .map(metadata -> new RawResource(metadata.handle(),
            metadata.format(), metadata.sampleCount(), metadata.width(),
            metadata.height(), metadata.depthOrLayers(),
            metadata.mipLevels()))
        .orElse(null);
  }

  private RawResource texture(ResourceHandle handle) {
    return state.textureMetadata(handle)
        .map(metadata -> new RawResource(metadata.handle(),
            metadata.format(), metadata.sampleCount(), metadata.width(),
            metadata.height(), metadata.depthOrLayers(),
            metadata.mipLevels()))
        .orElse(null);
  }

  private RawResource framebuffer(int name) {
    return state.framebufferHandle(name)
        .map(handle -> new RawResource(handle, "framebuffer", 0,
            0, 0, 0, 0))
        .orElse(null);
  }

  private List<RawResource> framebufferTextures(int name, int mask) {
    IrisGlStateTracker.FramebufferMetadata framebuffer =
        state.framebufferMetadata(name).orElse(null);
    if (framebuffer == null || !framebuffer.complete()) {
      return List.of();
    }
    LinkedHashMap<ResourceHandle, RawResource> resources =
        new LinkedHashMap<>();
    for (IrisGlStateTracker.FramebufferAttachmentMetadata attachment
        : framebuffer.attachments()) {
      if (!includedByBlitMask(attachment.attachment(), mask)) {
        continue;
      }
      IrisGlStateTracker.TextureMetadata metadata = attachment.texture();
      resources.putIfAbsent(metadata.handle(), new RawResource(
          metadata.handle(), metadata.format(), metadata.sampleCount(),
          metadata.width(), metadata.height(), metadata.depthOrLayers(),
          metadata.mipLevels()));
    }
    return List.copyOf(resources.values());
  }

  private List<RawResource> framebufferTexturesForClear(int name,
      int buffer, int drawBuffer) {
    IrisGlStateTracker.FramebufferMetadata framebuffer =
        state.framebufferMetadata(name).orElse(null);
    if (framebuffer == null || !framebuffer.complete()) {
      return List.of();
    }
    ArrayList<RawResource> resources = new ArrayList<>();
    for (IrisGlStateTracker.FramebufferAttachmentMetadata attachment
        : framebuffer.attachments()) {
      boolean included = switch (buffer) {
        case IrisClearCommand.GL_COLOR -> attachment.attachment()
            == IrisGlStateTracker.GL_COLOR_ATTACHMENT0 + drawBuffer;
        case IrisClearCommand.GL_DEPTH -> attachment.attachment()
            == IrisGlStateTracker.GL_DEPTH_ATTACHMENT;
        case IrisClearCommand.GL_STENCIL -> attachment.attachment()
            == IrisGlStateTracker.GL_STENCIL_ATTACHMENT;
        case IrisClearCommand.GL_DEPTH_STENCIL -> attachment.attachment()
            == IrisGlStateTracker.GL_DEPTH_ATTACHMENT
            || attachment.attachment()
                == IrisGlStateTracker.GL_STENCIL_ATTACHMENT;
        default -> false;
      };
      if (!included) {
        continue;
      }
      IrisGlStateTracker.TextureMetadata metadata = attachment.texture();
      resources.add(new RawResource(metadata.handle(), metadata.format(),
          metadata.sampleCount(), metadata.width(), metadata.height(),
          metadata.depthOrLayers(), metadata.mipLevels()));
    }
    return distinct(resources);
  }

  private static boolean includedByBlitMask(int attachment, int mask) {
    if (attachment >= IrisGlStateTracker.GL_COLOR_ATTACHMENT0
        && attachment < IrisGlStateTracker.GL_COLOR_ATTACHMENT0
            + IrisGlStateTracker.MAX_COLOR_ATTACHMENTS) {
      return (mask & GL_COLOR_BUFFER_BIT) != 0;
    }
    if (attachment == IrisGlStateTracker.GL_DEPTH_ATTACHMENT) {
      return (mask & GL_DEPTH_BUFFER_BIT) != 0;
    }
    if (attachment == IrisGlStateTracker.GL_STENCIL_ATTACHMENT) {
      return (mask & GL_STENCIL_BUFFER_BIT) != 0;
    }
    return false;
  }

  private void addAttachment(List<RawResource> resources,
      IrisGlStateSnapshot.StateValue<Optional<
          IrisGlStateSnapshot.TextureAttachment>> value) {
    if (!value.isKnown()) {
      return;
    }
    value.value().ifPresent(attachment -> {
      IrisGlStateTracker.TextureMetadata metadata =
          state.textureMetadata(attachment.texture()).orElse(null);
      resources.add(metadata == null
          ? new RawResource(attachment.texture(), attachment.format(),
              attachment.sampleCount().isKnown()
                  ? attachment.sampleCount().value() : 0,
              0, 0, 0, 0)
          : new RawResource(attachment.texture(), metadata.format(),
              metadata.sampleCount(), metadata.width(), metadata.height(),
              metadata.depthOrLayers(), metadata.mipLevels()));
    });
  }

  private static List<RawResource> distinct(List<RawResource> input) {
    LinkedHashMap<ResourceHandle, RawResource> distinct =
        new LinkedHashMap<>();
    for (RawResource resource : input) {
      distinct.merge(resource.handle(), resource,
          (first, second) -> "runtime-texture".equals(first.format())
              ? second : first);
    }
    return List.copyOf(distinct.values());
  }

  public record PendingFrame(List<RawEvent> events,
                             java.util.Set<Phase> phases,
                             boolean fullReplayCaptured,
                             long fullReplayBytes,
                             List<FinalOutputCapture> finalOutputCaptures,
                             List<IrisGlTextureMirror.TextureSnapshot>
                                 fullReplayTextures) {
    public PendingFrame(List<RawEvent> events,
        java.util.Set<Phase> phases) {
      this(events, phases, false, 0, List.of(), List.of());
    }

    public PendingFrame {
      events = List.copyOf(events);
      phases = java.util.Set.copyOf(phases);
      finalOutputCaptures = List.copyOf(finalOutputCaptures);
      fullReplayTextures = List.copyOf(fullReplayTextures);
      if (events.isEmpty()) {
        throw new IllegalArgumentException("render graph frame is empty");
      }
      if (fullReplayBytes < 0 || !fullReplayCaptured
          && (fullReplayBytes != 0 || !finalOutputCaptures.isEmpty()
              || !fullReplayTextures.isEmpty())) {
        throw new IllegalArgumentException("invalid full replay frame");
      }
    }
  }

  public record FinalOutputCapture(
      ResourceHandle texture,
      IrisVisualParityCapture.CapturedFrame frame) {
    public FinalOutputCapture {
      Objects.requireNonNull(texture, "texture");
      Objects.requireNonNull(frame, "frame");
      if (texture.kind() != IrisGlStateSnapshot.ResourceKind.TEXTURE) {
        throw new IllegalArgumentException("final output is not a texture");
      }
    }
  }

  public sealed interface RawEvent permits RawDraw, RawDispatch, RawClear,
      RawBarrier, RawTransfer {
    Phase phase();
  }

  public record RawClear(Phase phase, IrisClearCommand command,
                         List<RawResource> writes) implements RawEvent {
    public RawClear {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(command, "command");
      writes = List.copyOf(writes);
      if (writes.isEmpty()) {
        throw new IllegalArgumentException("clear has no destination");
      }
    }
  }

  public record RawDraw(Phase phase,
                        IrisPipelineStateCapture.PendingState pending,
                        List<RawResource> reads,
                        List<RawResource> writes) implements RawEvent {
    public RawDraw {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(pending, "pending");
      reads = List.copyOf(reads);
      writes = List.copyOf(writes);
    }
  }

  public record RawDispatch(Phase phase,
                           IrisPipelineStateCapture.PendingState pending,
                           List<RawResource> reads,
                           List<RawResource> writes) implements RawEvent {
    public RawDispatch {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(pending, "pending");
      if (pending.snapshot().operation()
          != IrisGlStateSnapshot.Operation.DISPATCH) {
        throw new IllegalArgumentException("dispatch event has non-dispatch state");
      }
      reads = List.copyOf(reads);
      writes = List.copyOf(writes);
    }
  }

  public record RawBarrier(Phase phase, int bits) implements RawEvent {
    public RawBarrier {
      Objects.requireNonNull(phase, "phase");
      if (bits < 0) {
        throw new IllegalArgumentException("negative barrier bits");
      }
    }
  }

  public record RawTransfer(Phase phase, List<RawResource> sources,
                            List<RawResource> destinations,
                            IrisTransferCommand command)
      implements RawEvent {
    public RawTransfer {
      Objects.requireNonNull(phase, "phase");
      sources = List.copyOf(sources);
      destinations = List.copyOf(destinations);
      Objects.requireNonNull(command, "command");
      if (sources.isEmpty() && destinations.isEmpty()) {
        throw new IllegalArgumentException("transfer has no resource");
      }
    }

    public RawTransfer(Phase phase, IrisRenderGraph.NodeKind kind,
        List<RawResource> sources, List<RawResource> destinations) {
      this(phase, sources, destinations,
          new IrisTransferCommand.Unknown(kind));
    }

    public IrisRenderGraph.NodeKind kind() {
      return command.kind();
    }
  }

  public record RawResource(ResourceHandle handle, String format,
                            int sampleCount, int width, int height,
                            int depthOrLayers, int mipLevels) {
    public RawResource(ResourceHandle handle, String format,
        int sampleCount) {
      this(handle, format, sampleCount, 0, 0, 0, 0);
    }

    public RawResource {
      Objects.requireNonNull(handle, "handle");
      Objects.requireNonNull(format, "format");
      if (sampleCount < 0 || width < 0 || height < 0
          || depthOrLayers < 0 || mipLevels < 0) {
        throw new IllegalArgumentException("negative resource metadata");
      }
    }
  }

  private static final class FrameBuilder {
    private final ArrayList<RawEvent> events = new ArrayList<>();
    private final java.util.EnumSet<Phase> phases =
        java.util.EnumSet.noneOf(Phase.class);
    private final boolean fullReplay;
    private final boolean fullReplayParity;
    private final long fullReplayStartedNanos;
    private final Map<Integer, IrisGlTextureMirror.TextureSnapshot>
        fullReplayTextures = new LinkedHashMap<>();
    private final IrisShadowReplayBufferSnapshot.RetainedCapture
        fullReplayBuffers =
            new IrisShadowReplayBufferSnapshot.RetainedCapture();
    private final Map<ResourceHandle, IrisPipelineStateCapture.PendingState>
        finalReplayTargets = new LinkedHashMap<>();
    private final Map<ResourceHandle, IrisVisualParityCapture.CapturedFrame>
        diagnosticReplayTargets = new LinkedHashMap<>();
    private boolean fullReplayComplete;
    private String fullReplayAbortReason = "";
    private long fullReplayBytes;
    private boolean overflowed;
    private Object lastSignature;

    private FrameBuilder(boolean fullReplay, boolean fullReplayParity,
        long fullReplayStartedNanos) {
      this.fullReplay = fullReplay;
      this.fullReplayParity = fullReplayParity;
      this.fullReplayStartedNanos = fullReplayStartedNanos;
      fullReplayComplete = fullReplay;
    }

    private void preferFullReplayAbortReason(String reason) {
      if (reason == null || reason.isBlank()) {
        return;
      }
      if (fullReplayAbortReason.isEmpty()
          || fullReplayAbortReason.equals(
              "graph-frame-resource-capture-incomplete")
          && reason.equals("graph-frame-capture-backpressure")) {
        fullReplayAbortReason = reason;
      }
    }
  }

  static long retainedFullReplayBytes(
      IrisShadowReplayBufferSnapshot.RetainedCapture buffers,
      Map<Integer, IrisGlTextureMirror.TextureSnapshot> textures) {
    long bytes = Objects.requireNonNull(buffers, "buffers").retainedBytes();
    for (IrisGlTextureMirror.TextureSnapshot texture
        : Objects.requireNonNull(textures, "textures").values()) {
      if (!texture.shared()) {
        bytes = Math.addExact(bytes, texture.byteLength());
      }
    }
    return bytes;
  }

  static boolean fullReplayResourcesComplete(
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayTextureSnapshot textures,
      IrisShadowReplaySamplerSnapshot samplers) {
    return fullReplayResourceAbortReason(buffers, textures, samplers)
        .isEmpty();
  }

  static String fullReplayResourceAbortReason(
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayTextureSnapshot textures,
      IrisShadowReplaySamplerSnapshot samplers) {
    Objects.requireNonNull(buffers, "buffers");
    Objects.requireNonNull(textures, "textures");
    Objects.requireNonNull(samplers, "samplers");
    if (buffers.drawComplete() && textures.complete()
        && samplers.complete()) {
      return "";
    }
    if (textures.blockers().stream().anyMatch(
        reason -> reason.endsWith("-gpu-handoff-backpressure"))) {
      return "graph-frame-capture-backpressure";
    }
    return "graph-frame-resource-capture-incomplete";
  }

}

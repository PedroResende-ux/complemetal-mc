package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Phase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded render-thread capture of Iris pass ordering and resource routing. */
public final class IrisRenderGraphCapture {
  public static final int MAX_EVENTS_PER_FRAME = 32_768;
  public static final int FRAME_QUEUE_CAPACITY = 16;
  private static final long MIN_FRAME_INTERVAL_NANOS = 500_000_000L;
  private static final IrisRenderGraphCapture GLOBAL =
      new IrisRenderGraphCapture(IrisPipelineStateCapture.global().tracker());

  private final IrisGlStateTracker state;
  private final ConcurrentLinkedQueue<PendingFrame> queue =
      new ConcurrentLinkedQueue<>();
  private final AtomicInteger queued = new AtomicInteger();
  private final AtomicLong framesStarted = new AtomicLong();
  private final AtomicLong framesCompleted = new AtomicLong();
  private final AtomicLong framesRejected = new AtomicLong();
  private FrameBuilder current;
  private Phase phase = Phase.UNKNOWN;
  private long lastFrameStartNanos;
  private boolean frozen;

  IrisRenderGraphCapture(IrisGlStateTracker state) {
    this.state = Objects.requireNonNull(state, "state");
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
    }
    if (queued.get() >= FRAME_QUEUE_CAPACITY) {
      current = null;
      phase = Phase.UNKNOWN;
      return;
    }
    long now = System.nanoTime();
    if (lastFrameStartNanos != 0
        && now - lastFrameStartNanos < MIN_FRAME_INTERVAL_NANOS) {
      current = null;
      phase = Phase.UNKNOWN;
      return;
    }
    lastFrameStartNanos = now;
    current = new FrameBuilder();
    phase = Phase.BEGIN;
    framesStarted.incrementAndGet();
  }

  public synchronized void phase(Phase next) {
    phase = Objects.requireNonNull(next, "phase");
    if (current != null) {
      current.phases.add(next);
    }
  }

  public synchronized void draw(
      IrisPipelineStateCapture.PendingState pending) {
    if (current == null) {
      return;
    }
    ArrayList<RawResource> reads = new ArrayList<>();
    IrisGlResourceBindingSnapshot bindings = pending.resourceBindings();
    if (bindings != null) {
      bindings.textureUnits().values().forEach(binding ->
          addTexture(reads, binding.texture()));
      bindings.imageUnits().values().forEach(binding ->
          addTexture(reads, binding.texture()));
    }
    ArrayList<RawResource> writes = new ArrayList<>();
    IrisGlStateSnapshot snapshot = pending.snapshot();
    if (snapshot.operation() == IrisGlStateSnapshot.Operation.DRAW) {
      for (IrisGlStateSnapshot.ColorTarget target : snapshot.colorTargets()) {
        addAttachment(writes, target.attachment());
      }
      addAttachment(writes, snapshot.depthAttachment());
      addAttachment(writes, snapshot.stencilAttachment());
    }
    add(new RawDraw(phase, pending, distinct(reads), distinct(writes)));
  }

  public synchronized void memoryBarrier(int bits) {
    if (current != null && bits >= 0) {
      add(new RawBarrier(phase, bits));
    }
  }

  public synchronized void blitFramebuffer(int source, int destination) {
    if (current == null) {
      return;
    }
    add(new RawTransfer(phase, IrisRenderGraph.NodeKind.BLIT,
        framebuffer(source), framebuffer(destination)));
  }

  public synchronized void copyTexture(int destinationTexture) {
    if (current == null) {
      return;
    }
    add(new RawTransfer(phase, IrisRenderGraph.NodeKind.COPY_TEXTURE,
        null, texture(destinationTexture)));
  }

  public synchronized void copyBoundTexture() {
    IrisGlResourceBindingSnapshot.TextureUnitBinding active =
        IrisGlResourceBindingTracker.global().activeTextureBinding();
    if (active != null) {
      copyTexture(active.texture());
    }
  }

  public synchronized void generateMipmaps(int texture) {
    if (current == null) {
      return;
    }
    RawResource resource = texture(texture);
    add(new RawTransfer(phase,
        IrisRenderGraph.NodeKind.GENERATE_MIPMAPS, resource, resource));
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
      return;
    }
    int count = queued.incrementAndGet();
    if (count > FRAME_QUEUE_CAPACITY) {
      queued.decrementAndGet();
      return;
    }
    queue.offer(new PendingFrame(finished.events, finished.phases));
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
    current = null;
    phase = Phase.UNKNOWN;
  }

  public synchronized boolean frozen() {
    return frozen;
  }

  private void add(RawEvent event) {
    if (current.events.size() >= MAX_EVENTS_PER_FRAME) {
      current.overflowed = true;
      return;
    }
    Object signature = signature(event);
    if (signature.equals(current.lastSignature)) {
      return;
    }
    current.events.add(event);
    current.lastSignature = signature;
    current.phases.add(event.phase());
  }

  private static Object signature(RawEvent event) {
    if (event instanceof RawDraw draw) {
      IrisGlStateSnapshot snapshot = draw.pending().snapshot();
      return new RawDrawSignature(draw.phase(),
          draw.pending().registration().generation(), snapshot.operation(),
          snapshot.program(), snapshot.drawFramebuffer(),
          snapshot.drawBuffers(), snapshot.colorTargets(),
          snapshot.depthAttachment(), snapshot.stencilAttachment(),
          snapshot.depth(), snapshot.stencil(), snapshot.raster(),
          snapshot.multisample(), snapshot.primitive(),
          snapshot.unknownFields(), draw.reads(), draw.writes());
    }
    return event;
  }

  private void addTexture(List<RawResource> resources, int name) {
    RawResource resource = texture(name);
    if (resource != null) {
      resources.add(resource);
    }
  }

  private RawResource texture(int name) {
    return state.textureHandle(name)
        .map(handle -> new RawResource(handle, "runtime-texture", 0))
        .orElse(null);
  }

  private RawResource framebuffer(int name) {
    return state.framebufferHandle(name)
        .map(handle -> new RawResource(handle, "framebuffer", 0))
        .orElse(null);
  }

  private static void addAttachment(List<RawResource> resources,
      IrisGlStateSnapshot.StateValue<Optional<
          IrisGlStateSnapshot.TextureAttachment>> value) {
    if (!value.isKnown()) {
      return;
    }
    value.value().ifPresent(attachment -> resources.add(new RawResource(
        attachment.texture(), attachment.format(),
        attachment.sampleCount().isKnown()
            ? attachment.sampleCount().value() : 0)));
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
                             java.util.Set<Phase> phases) {
    public PendingFrame {
      events = List.copyOf(events);
      phases = java.util.Set.copyOf(phases);
      if (events.isEmpty()) {
        throw new IllegalArgumentException("render graph frame is empty");
      }
    }
  }

  public sealed interface RawEvent permits RawDraw, RawBarrier, RawTransfer {
    Phase phase();
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

  public record RawBarrier(Phase phase, int bits) implements RawEvent {
    public RawBarrier {
      Objects.requireNonNull(phase, "phase");
      if (bits < 0) {
        throw new IllegalArgumentException("negative barrier bits");
      }
    }
  }

  public record RawTransfer(Phase phase, IrisRenderGraph.NodeKind kind,
                            RawResource source,
                            RawResource destination) implements RawEvent {
    public RawTransfer {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(kind, "kind");
      if (!kind.transfer()) {
        throw new IllegalArgumentException("non-transfer graph event");
      }
      if (source == null && destination == null) {
        throw new IllegalArgumentException("transfer has no resource");
      }
    }
  }

  public record RawResource(ResourceHandle handle, String format,
                            int sampleCount) {
    public RawResource {
      Objects.requireNonNull(handle, "handle");
      Objects.requireNonNull(format, "format");
      if (sampleCount < 0) {
        throw new IllegalArgumentException("negative sample count");
      }
    }
  }

  private static final class FrameBuilder {
    private final ArrayList<RawEvent> events = new ArrayList<>();
    private final java.util.EnumSet<Phase> phases =
        java.util.EnumSet.noneOf(Phase.class);
    private boolean overflowed;
    private Object lastSignature;
  }

  private record RawDrawSignature(Phase phase, long programGeneration,
      IrisGlStateSnapshot.Operation operation,
      IrisGlStateSnapshot.StateValue<Optional<ResourceHandle>> program,
      IrisGlStateSnapshot.StateValue<ResourceHandle> framebuffer,
      IrisGlStateSnapshot.StateValue<List<Integer>> drawBuffers,
      List<IrisGlStateSnapshot.ColorTarget> colorTargets,
      IrisGlStateSnapshot.StateValue<Optional<
          IrisGlStateSnapshot.TextureAttachment>> depthAttachment,
      IrisGlStateSnapshot.StateValue<Optional<
          IrisGlStateSnapshot.TextureAttachment>> stencilAttachment,
      IrisGlStateSnapshot.DepthState depth,
      IrisGlStateSnapshot.StencilState stencil,
      IrisGlStateSnapshot.RasterState raster,
      IrisGlStateSnapshot.MultisampleState multisample,
      IrisGlStateSnapshot.PrimitiveState primitive,
      List<String> unknownFields,
      List<RawResource> reads, List<RawResource> writes) {
  }
}

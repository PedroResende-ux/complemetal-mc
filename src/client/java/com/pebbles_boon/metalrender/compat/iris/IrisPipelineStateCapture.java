package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

/**
 * Bounded render-thread capture bridge for unique Iris GL pipeline variants.
 * It performs no disk I/O, shader hashing, translation, or native calls.
 */
public final class IrisPipelineStateCapture {
  public static final int DEFAULT_QUEUE_CAPACITY = 8_192;
  public static final int DEFAULT_RECENT_VARIANT_CAPACITY = 16_384;
  private static final AtomicBoolean EXACT_HAND_VERTEX_LOGGED =
      new AtomicBoolean();
  private static final IrisPipelineStateCapture GLOBAL =
      new IrisPipelineStateCapture(new IrisGlStateTracker(),
          IrisProgramIdentityRegistry.global(), DEFAULT_QUEUE_CAPACITY,
          DEFAULT_RECENT_VARIANT_CAPACITY);

  private final IrisGlStateTracker tracker;
  private final IrisGlResourceBindingTracker resourceBindings;
  private final IrisDynamicDrawStateTracker dynamicState;
  private final IrisProgramIdentityRegistry identities;
  private final int queueCapacity;
  private final int recentVariantCapacity;
  private final ConcurrentLinkedQueue<PendingState> queue =
      new ConcurrentLinkedQueue<>();
  private final AtomicInteger queued = new AtomicInteger();
  private final LinkedHashMap<PipelineLookupKey, Boolean> recentVariants =
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
    dynamicState = IrisDynamicDrawStateTracker.global();
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
    IrisMetalCutoverPresenter.global().reset();
    IrisGlTextureGpuHandoff.reset();
    IrisMetalBufferResidentCache.reset();
    tracker.initializeOpenGlDefaults();
    resourceBindings.initializeOpenGlDefaults();
    dynamicState.initializeOpenGlDefaults();
    IrisGlBufferMirror.global().clear();
    IrisGlTextureMirror.global().clear();
    IrisGlSamplerMirror.global().clear();
    IrisGlVertexArrayTracker.global().reset();
    IrisGlGenericAttributeTracker.global().reset();
    IrisRenderGraphCapture.global().resetShadowReplaySamples();
    IrisVisualParityCapture.global().reset();
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
    if (primitiveMode < 0) {
      return;
    }
    draw(new IrisExecutionCommand.UnknownDraw(primitiveMode));
  }

  public void draw(IrisExecutionCommand.Draw command) {
    draw(command, IrisVertexInputBindings.unavailable(
        "direct-gl-buffer-state-not-captured"));
  }

  /** Captures a legacy/direct GL draw from the current generation-safe VAO. */
  public void drawDirect(IrisExecutionCommand.Draw command) {
    captureDrawDirect(command);
  }

  /**
   * Captures a legacy/direct GL draw and returns the exact immutable state to
   * a same-thread selective-cutover caller. No Metal work is performed here.
   */
  public Optional<PendingState> captureDrawDirect(
      IrisExecutionCommand.Draw command) {
    Objects.requireNonNull(command, "command");
    int glProgram = currentGlProgram;
    Optional<IrisProgramIdentityRegistry.Registration> registration =
        identities.lookup(glProgram);
    if (registration.isEmpty()) {
      return Optional.empty();
    }
    IrisProgramIdentityRegistry.Registration resolved =
        registration.orElseThrow();
    return captureDraw(command, IrisGlVertexArrayTracker.global().snapshot(
        resolved.descriptor()));
  }

  /**
   * Captures any generation-safe direct GL execution command, including
   * Sodium's multi-draw batch representation.
   */
  public Optional<PendingState> captureDrawDirect(
      IrisExecutionCommand command) {
    Objects.requireNonNull(command, "command");
    int glProgram = currentGlProgram;
    Optional<IrisProgramIdentityRegistry.Registration> registration =
        identities.lookup(glProgram);
    if (registration.isEmpty()) {
      return Optional.empty();
    }
    IrisProgramIdentityRegistry.Registration resolved =
        registration.orElseThrow();
    drawsObserved.incrementAndGet();
    IrisVertexInputBindings inputs =
        IrisGlVertexArrayTracker.global().snapshot(resolved.descriptor());
    PreparedDirectCapture prepared = prepareDirectCapture(command, inputs,
        resolved.descriptor());
    return Optional.of(capture(resolved,
        tracker.snapshotDraw(prepared.command().primitiveMode()),
        prepared.command(), prepared.vertexInputs()));
  }

  private PreparedDirectCapture prepareDirectCapture(
      IrisExecutionCommand command, IrisVertexInputBindings inputs,
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor) {
    if (!(command instanceof IrisExecutionCommand.MultiDrawIndexed multi)
        || multi.source() != IrisExecutionCommand.Source.SODIUM_COMMAND_LIST) {
      return new PreparedDirectCapture(command, inputs);
    }
    if (!inputs.complete() || inputs.vertexBuffers().isEmpty()
        || inputs.indexBuffer().isEmpty()) {
      return new PreparedDirectCapture(command, inputs);
    }

    IrisPipelineState.VertexBufferLayout vertexLayout =
        descriptor.vertexBuffers().stream()
            .filter(layout -> layout.bufferIndex() == 0)
            .findFirst().orElse(null);
    IrisVertexInputBindings.BufferSlice vertexSlice =
        inputs.vertexBuffers().stream()
            .filter(slice -> slice.slot() == 0)
            .findFirst().orElse(null);
    IrisVertexInputBindings.BufferSlice indexSlice =
        inputs.indexBuffer().orElse(null);
    if (vertexLayout == null || vertexSlice == null || indexSlice == null
        || vertexLayout.strideBytes() <= 0) {
      return new PreparedDirectCapture(command, inputs);
    }

    long[] offsets = multi.indexOffsetsBytes();
    int[] counts = multi.indexCounts();
    int[] bases = multi.baseVertices();
    int indexBytes = multi.indexElementBytes();
    long minIndexOffset = Long.MAX_VALUE;
    long maxIndexEnd = Long.MIN_VALUE;
    for (int draw = 0; draw < offsets.length; draw++) {
      long offset = offsets[draw];
      long bytes = Math.multiplyExact((long) counts[draw], indexBytes);
      long end = Math.addExact(offset, bytes);
      if (offset < indexSlice.offsetBytes()
          || end > Math.addExact(indexSlice.offsetBytes(),
              indexSlice.lengthBytes())) {
        return new PreparedDirectCapture(command, inputs);
      }
      if (bytes > 0) {
        minIndexOffset = Math.min(minIndexOffset, offset);
        maxIndexEnd = Math.max(maxIndexEnd, end);
      }
    }
    if (minIndexOffset == Long.MAX_VALUE || maxIndexEnd <= minIndexOffset) {
      return new PreparedDirectCapture(command, inputs);
    }

    long indexLength = maxIndexEnd - minIndexOffset;
    Optional<IrisGlBufferMirror.BufferSnapshot> indexSnapshot =
        IrisGlBufferMirror.global().snapshot(
            indexSlice.glBuffer(), indexSlice.mirrorGeneration(),
            minIndexOffset, indexLength);
    if (indexSnapshot.isEmpty()) {
      return new PreparedDirectCapture(command, inputs);
    }

    long minVertex = Long.MAX_VALUE;
    long maxVertex = Long.MIN_VALUE;
    byte[] indexBytesData = indexSnapshot.orElseThrow().ownedBytes();
    ByteBuffer indexData = ByteBuffer.wrap(indexBytesData)
        .order(ByteOrder.LITTLE_ENDIAN);
    for (int draw = 0; draw < offsets.length; draw++) {
      int count = counts[draw];
      long relative = offsets[draw] - minIndexOffset;
      for (int element = 0; element < count; element++) {
        long byteOffset = relative + (long) element * indexBytes;
        if (byteOffset < 0
            || byteOffset > indexBytesData.length - indexBytes) {
          return new PreparedDirectCapture(command, inputs);
        }
        long raw = switch (indexBytes) {
          case 1 -> Byte.toUnsignedInt(
              indexData.get(Math.toIntExact(byteOffset)));
          case 2 -> Short.toUnsignedInt(
              indexData.getShort(Math.toIntExact(byteOffset)));
          case 4 -> Integer.toUnsignedLong(
              indexData.getInt(Math.toIntExact(byteOffset)));
          default -> throw new IllegalArgumentException(
              "unsupported Sodium index width");
        };
        long vertex = raw + bases[draw];
        if (vertex < 0) {
          return new PreparedDirectCapture(command, inputs);
        }
        minVertex = Math.min(minVertex, vertex);
        maxVertex = Math.max(maxVertex, vertex);
      }
    }
    if (minVertex == Long.MAX_VALUE || maxVertex < minVertex) {
      return new PreparedDirectCapture(command, inputs);
    }

    long stride = vertexLayout.strideBytes();
    long vertexStart = Math.addExact(vertexSlice.offsetBytes(),
        Math.multiplyExact(minVertex, stride));
    long vertexEndExclusive = Math.addExact(
        vertexSlice.offsetBytes(),
        Math.multiplyExact(Math.addExact(maxVertex, 1), stride));
    long vertexBufferEnd = Math.addExact(vertexSlice.offsetBytes(),
        vertexSlice.lengthBytes());
    if (vertexStart < vertexSlice.offsetBytes()
        || vertexEndExclusive > vertexBufferEnd) {
      return new PreparedDirectCapture(command, inputs);
    }

    long[] rebasedOffsets = new long[offsets.length];
    int[] rebasedBases = new int[bases.length];
    long minBase = minVertex;
    if (minBase < Integer.MIN_VALUE || minBase > Integer.MAX_VALUE) {
      return new PreparedDirectCapture(command, inputs);
    }
    int rebase = Math.toIntExact(minBase);
    for (int draw = 0; draw < offsets.length; draw++) {
      rebasedOffsets[draw] = Math.subtractExact(offsets[draw],
          minIndexOffset);
      rebasedBases[draw] = Math.subtractExact(bases[draw], rebase);
    }

    IrisVertexInputBindings compactInputs =
        new IrisVertexInputBindings(
            List.of(new IrisVertexInputBindings.BufferSlice(
                vertexSlice.slot(), vertexSlice.glBuffer(), vertexStart,
                vertexEndExclusive - vertexStart,
                vertexSlice.mirrorGeneration())),
            Optional.of(new IrisVertexInputBindings.BufferSlice(
                indexSlice.slot(), indexSlice.glBuffer(), minIndexOffset,
                indexLength, indexSlice.mirrorGeneration())),
            "");

    IrisExecutionCommand.MultiDrawIndexed compactCommand =
        new IrisExecutionCommand.MultiDrawIndexed(
            multi.primitiveMode(), indexBytes, rebasedOffsets, counts,
            rebasedBases, multi.source());
    return new PreparedDirectCapture(compactCommand, compactInputs);
  }

  private record PreparedDirectCapture(
      IrisExecutionCommand command,
      IrisVertexInputBindings vertexInputs) {
  }

  public void draw(IrisExecutionCommand.Draw command,
      IrisVertexInputBindings vertexInputBindings) {
    captureDraw(command, vertexInputBindings);
  }

  /** Returns the state paired with this exact draw for same-thread cutover. */
  public Optional<PendingState> captureDraw(
      IrisExecutionCommand.Draw command,
      IrisVertexInputBindings vertexInputBindings) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(vertexInputBindings, "vertexInputBindings");
    int glProgram = currentGlProgram;
    Optional<IrisProgramIdentityRegistry.Registration> registration =
        identities.lookup(glProgram);
    if (registration.isEmpty()) {
      return Optional.empty();
    }
    drawsObserved.incrementAndGet();
    return Optional.of(capture(registration.orElseThrow(),
        tracker.snapshotDraw(command.primitiveMode()), command,
        vertexInputBindings));
  }

  public void dispatch() {
    dispatch(new IrisExecutionCommand.UnknownDispatch());
  }

  public IrisExecutionCommand.Dispatch captureDispatchCommand(
      int groupsX, int groupsY, int groupsZ) {
    int program = currentGlProgram;
    if (program <= 0) {
      throw new IllegalArgumentException("compute dispatch has no program");
    }
    try (MemoryStack stack = MemoryStack.stackPush()) {
      IntBuffer workgroup = stack.mallocInt(3);
      GL20C.glGetProgramiv(program, GL43C.GL_COMPUTE_WORK_GROUP_SIZE,
          workgroup);
      int localX = workgroup.get(0);
      int localY = workgroup.get(1);
      int localZ = workgroup.get(2);
      return new IrisExecutionCommand.Dispatch(groupsX, groupsY, groupsZ,
          localX, localY, localZ);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException(
          "compute workgroup size unavailable", failure);
    }
  }

  public void dispatch(IrisExecutionCommand command) {
    Objects.requireNonNull(command, "command");
    if (command instanceof IrisExecutionCommand.Draw) {
      throw new IllegalArgumentException("draw command used for dispatch");
    }
    int glProgram = currentGlProgram;
    Optional<IrisProgramIdentityRegistry.Registration> registration =
        identities.lookup(glProgram);
    if (registration.isEmpty()) {
      return;
    }
    dispatchesObserved.incrementAndGet();
    capture(registration.orElseThrow(), tracker.snapshotDispatch(), command,
        IrisVertexInputBindings.complete(java.util.List.of(), null));
  }

  private PendingState capture(
      IrisProgramIdentityRegistry.Registration registration,
      IrisGlStateSnapshot snapshot, IrisExecutionCommand command,
      IrisVertexInputBindings vertexInputBindings) {
    IrisGlResourceBindingSnapshot capturedBindings =
        resourceBindings.snapshot();
    IrisRenderGraph.Phase phase =
        IrisRenderGraphCapture.global().currentPhase();
    boolean draw = command instanceof IrisExecutionCommand.Draw;
    boolean dispatch = command instanceof IrisExecutionCommand.Dispatch;
    boolean sampledReplay = IrisGlBufferMirror.isEnabled() && draw
        && IrisRenderGraphCapture.global().reserveShadowReplaySample();
    boolean cutoverReplay = IrisGlBufferMirror.isEnabled() && draw
        && IrisTranslationCoordinator.cutoverCaptureRequested(phase,
            registration, snapshot);
    boolean graphReplay = IrisGlBufferMirror.isEnabled()
        && (draw || dispatch)
        && IrisRenderGraphCapture.global().captureFullGraphReplay();
    boolean captureReplay = sampledReplay || cutoverReplay || graphReplay;
    Optional<IrisReplayCaptureRequirements> captureRequirements =
        graphReplay ? IrisTranslationCoordinator.replayCaptureRequirements(
            registration, capturedBindings) : Optional.empty();
    IrisShadowReplayBufferSnapshot replayBuffers =
        graphReplay
            ? IrisRenderGraphCapture.global().captureFullReplayBuffers(
                command, vertexInputBindings, capturedBindings,
                IrisGlBufferMirror.global(), registration.descriptor(),
                IrisGlGenericAttributeTracker.global(),
                captureRequirements.orElse(null))
            : captureReplay
            ? IrisShadowReplayBufferSnapshot.capture(command,
                vertexInputBindings, capturedBindings,
                IrisGlBufferMirror.global(), registration.descriptor(),
                IrisGlGenericAttributeTracker.global())
            : IrisShadowReplayBufferSnapshot.disabled();
    if (captureReplay && draw) {
      IrisPrimitiveExpansion.Result expansion =
          IrisPrimitiveExpansion.expand(command, replayBuffers);
      command = expansion.command();
      replayBuffers = expansion.buffers();
      if (graphReplay) {
        logExactHandVertexData(registration.descriptor(), command,
            replayBuffers);
        replayBuffers = replayBuffers.promoteGeometryToMetal();
      }
    }
    IrisShadowReplayTextureSnapshot replayTextures =
        graphReplay
            ? IrisRenderGraphCapture.global().captureFullReplayTextures(
                capturedBindings, IrisGlTextureMirror.global(),
                registration.descriptor().programName(),
                captureRequirements.orElse(null))
            : captureReplay
            ? cutoverReplay
                ? IrisShadowReplayTextureSnapshot.captureForFinalCutover(
                    capturedBindings, IrisGlTextureMirror.global())
                : IrisShadowReplayTextureSnapshot.capture(capturedBindings,
                    IrisGlTextureMirror.global(), false)
            : IrisShadowReplayTextureSnapshot.disabled();
    IrisShadowReplaySamplerSnapshot replaySamplers =
        captureReplay
            ? graphReplay && captureRequirements.isPresent()
                ? IrisShadowReplaySamplerSnapshot.capture(capturedBindings,
                    IrisGlSamplerMirror.global(), captureRequirements
                        .orElseThrow().samplerUnits())
                : IrisShadowReplaySamplerSnapshot.capture(capturedBindings,
                    IrisGlSamplerMirror.global())
            : IrisShadowReplaySamplerSnapshot.disabled();
    PendingState pending = new PendingState(registration, snapshot,
        capturedBindings, command, dynamicState.snapshot(),
        vertexInputBindings, replayBuffers, replayTextures, replaySamplers);
    // A full-graph candidate needs the immediate post-draw OpenGL image for
    // every FINAL target. The ordinary four-sample phase budget may already
    // be exhausted before Iris reaches its actual final program, while the
    // same attachment can be cleared by frame cleanup before endFrame().
    if (sampledReplay || graphReplay
        && !IrisTranslationCoordinator.fullGraphOwnershipCaptureActive()) {
      IrisVisualParityCapture.global().associate(pending, phase);
    }
    if (command instanceof IrisExecutionCommand.Draw) {
      IrisRenderGraphCapture.global().draw(pending);
    } else {
      IrisRenderGraphCapture.global().dispatch(pending);
    }
    offer(pending);
    return pending;
  }

  private static void logExactHandVertexData(
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor,
      IrisExecutionCommand command,
      IrisShadowReplayBufferSnapshot buffers) {
    if (System.getProperty("metalrender.exactJar.expectedPath") == null
        || !descriptor.programName().startsWith("hand_")
        || !(command instanceof IrisExecutionCommand.DrawIndexed draw)
        || !EXACT_HAND_VERTEX_LOGGED.compareAndSet(false, true)) {
      return;
    }
    try {
      IrisPipelineState.VertexBufferLayout layout = descriptor.vertexBuffers()
          .stream().filter(candidate -> candidate.bufferIndex() == 0)
          .findFirst().orElse(null);
      IrisShadowReplayBufferSnapshot.VertexBufferRef vertexRef =
          buffers.vertexBuffers().stream()
              .filter(candidate -> candidate.slot() == 0)
              .findFirst().orElse(null);
      if (layout == null || vertexRef == null
          || vertexRef.buffer().imageId() >= buffers.images().size()) {
        MetalLogger.info("exact-JAR hand vertex data unavailable");
        return;
      }
      IrisShadowReplayBufferSnapshot.BufferImage vertexImage =
          buffers.images().get(vertexRef.buffer().imageId());
      byte[] vertexBytes = vertexImage.bytes();
      LinkedHashSet<Integer> vertexIndices = new LinkedHashSet<>();
      String indices = exactHandIndices(draw, buffers, vertexIndices);
      if (vertexIndices.isEmpty()) {
        int available = Math.min(8,
            vertexBytes.length / layout.strideBytes());
        for (int index = 0; index < available; index++) {
          vertexIndices.add(index);
        }
      }
      StringBuilder decoded = new StringBuilder();
      for (Integer vertex : vertexIndices.stream().limit(12).toList()) {
        int base = Math.multiplyExact(vertex, layout.strideBytes());
        if (base < 0 || base > vertexBytes.length - layout.strideBytes()) {
          continue;
        }
        if (!decoded.isEmpty()) {
          decoded.append(';');
        }
        decoded.append('v').append(vertex).append('[');
        boolean first = true;
        for (IrisPipelineState.VertexAttribute attribute
            : descriptor.vertexAttributes()) {
          if (attribute.bufferIndex() != 0) {
            continue;
          }
          String value = exactHandAttribute(vertexBytes,
              base + attribute.offsetBytes(), attribute.format().cacheName());
          if (value.isEmpty()) {
            continue;
          }
          if (!first) {
            decoded.append(',');
          }
          first = false;
          decoded.append('l').append(attribute.location()).append('=')
              .append(value);
        }
        decoded.append(']');
      }
      MetalLogger.info("exact-JAR hand vertex data: program=%s command=%s "
              + "stride=%d vertexBytes=%d sourceOffset=%d indices=%s "
              + "vertices=%s",
          descriptor.programName(), command.getClass().getSimpleName(),
          layout.strideBytes(), vertexBytes.length,
          vertexImage.sourceOffsetBytes(), indices,
          decoded.isEmpty() ? "none" : decoded);
    } catch (RuntimeException diagnosticFailure) {
      MetalLogger.info("exact-JAR hand vertex data failed: %s",
          diagnosticFailure.getClass().getSimpleName());
    }
  }

  private static String exactHandIndices(
      IrisExecutionCommand.DrawIndexed draw,
      IrisShadowReplayBufferSnapshot buffers,
      LinkedHashSet<Integer> vertexIndices) {
    if (buffers.indexBuffer().isEmpty()) {
      return "none";
    }
    IrisShadowReplayBufferSnapshot.BufferRef reference =
        buffers.indexBuffer().orElseThrow();
    IrisShadowReplayBufferSnapshot.BufferImage image =
        buffers.images().get(reference.imageId());
    byte[] bytes = image.bytes();
    long relative = draw.indexOffsetBytes() - image.sourceOffsetBytes();
    if (relative < 0 || relative > Integer.MAX_VALUE) {
      return "out-of-range";
    }
    ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    StringBuilder result = new StringBuilder();
    int count = Math.min(draw.indexCount(), 24);
    for (int index = 0; index < count; index++) {
      long offset = relative + (long) index * draw.indexElementBytes();
      if (offset < 0 || offset > bytes.length - draw.indexElementBytes()) {
        break;
      }
      int raw = switch (draw.indexElementBytes()) {
        case 1 -> Byte.toUnsignedInt(input.get((int) offset));
        case 2 -> Short.toUnsignedInt(input.getShort((int) offset));
        case 4 -> input.getInt((int) offset);
        default -> throw new IllegalArgumentException("invalid index width");
      };
      int vertex = Math.addExact(raw, draw.baseVertex());
      if (!result.isEmpty()) {
        result.append(',');
      }
      result.append(vertex);
      if (vertex >= 0 && vertexIndices.size() < 12) {
        vertexIndices.add(vertex);
      }
    }
    return result.isEmpty() ? "none" : result.toString();
  }

  private static String exactHandAttribute(byte[] bytes, int offset,
      String format) {
    ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    return switch (format) {
      case "rgb32-float" -> floats(input, offset, 3);
      case "rg32-float" -> floats(input, offset, 2);
      case "rgba8-unorm", "rgba8-snorm" -> unsignedBytes(input, offset, 4);
      case "rg16-sint" -> signedShorts(input, offset, 2);
      case "rgba16-uint" -> unsignedShorts(input, offset, 4);
      default -> "";
    };
  }

  private static String floats(ByteBuffer input, int offset, int count) {
    if (offset < 0 || offset > input.capacity() - count * Float.BYTES) {
      return "range";
    }
    StringBuilder value = new StringBuilder();
    for (int index = 0; index < count; index++) {
      if (index > 0) {
        value.append('/');
      }
      value.append(String.format(Locale.ROOT, "%.4f",
          input.getFloat(offset + index * Float.BYTES)));
    }
    return value.toString();
  }

  private static String unsignedBytes(ByteBuffer input, int offset,
      int count) {
    if (offset < 0 || offset > input.capacity() - count) {
      return "range";
    }
    StringBuilder value = new StringBuilder();
    for (int index = 0; index < count; index++) {
      if (index > 0) {
        value.append('/');
      }
      value.append(Byte.toUnsignedInt(input.get(offset + index)));
    }
    return value.toString();
  }

  private static String signedShorts(ByteBuffer input, int offset,
      int count) {
    if (offset < 0 || offset > input.capacity() - count * Short.BYTES) {
      return "range";
    }
    StringBuilder value = new StringBuilder();
    for (int index = 0; index < count; index++) {
      if (index > 0) {
        value.append('/');
      }
      short raw = input.getShort(offset + index * Short.BYTES);
      value.append(raw).append('u').append(Short.toUnsignedInt(raw));
    }
    return value.toString();
  }

  private static String unsignedShorts(ByteBuffer input, int offset,
      int count) {
    if (offset < 0 || offset > input.capacity() - count * Short.BYTES) {
      return "range";
    }
    StringBuilder value = new StringBuilder();
    for (int index = 0; index < count; index++) {
      if (index > 0) {
        value.append('/');
      }
      value.append(Short.toUnsignedInt(
          input.getShort(offset + index * Short.BYTES)));
    }
    return value.toString();
  }

  private void offer(PendingState pending) {
    IrisProgramIdentityRegistry.Registration registration =
        pending.registration();
    IrisGlStateSnapshot snapshot = pending.snapshot();
    PipelineLookupKey signature = lookupKey(pending);
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

  /**
   * Stable runtime lookup identity for a compiled pipeline. The monotonically
   * increasing snapshot sequence is intentionally excluded; generation-
   * qualified GL objects keep lifecycle changes fail-closed.
   */
  public static PipelineLookupKey lookupKey(PendingState pending) {
    Objects.requireNonNull(pending, "pending");
    return lookupKey(pending.registration(), pending.snapshot());
  }

  static PipelineLookupKey lookupKey(
      IrisProgramIdentityRegistry.Registration registration,
      IrisGlStateSnapshot snapshot) {
    Objects.requireNonNull(registration, "registration");
    Objects.requireNonNull(snapshot, "snapshot");
    return new PipelineLookupKey(registration.generation(),
        snapshot.operation(), snapshot.program(), snapshot.drawFramebuffer(),
        snapshot.drawBuffers(), snapshot.colorTargets(),
        snapshot.depthAttachment(), snapshot.stencilAttachment(),
        snapshot.depth(), snapshot.stencil(), snapshot.raster(),
        snapshot.multisample(), snapshot.primitive(),
        snapshot.unknownFields(), snapshot.resourceEvictions());
  }

  public record PendingState(
      IrisProgramIdentityRegistry.Registration registration,
      IrisGlStateSnapshot snapshot,
      IrisGlResourceBindingSnapshot resourceBindings,
      IrisExecutionCommand command,
      IrisDynamicDrawState dynamicState,
      IrisVertexInputBindings vertexInputBindings,
      IrisShadowReplayBufferSnapshot replayBuffers,
      IrisShadowReplayTextureSnapshot replayTextures,
      IrisShadowReplaySamplerSnapshot replaySamplers) {
    public PendingState(
        IrisProgramIdentityRegistry.Registration registration,
        IrisGlStateSnapshot snapshot,
        IrisGlResourceBindingSnapshot resourceBindings,
        IrisExecutionCommand command,
        IrisDynamicDrawState dynamicState,
        IrisVertexInputBindings vertexInputBindings) {
      this(registration, snapshot, resourceBindings, command, dynamicState,
          vertexInputBindings, IrisShadowReplayBufferSnapshot.disabled(),
          IrisShadowReplayTextureSnapshot.disabled(),
          IrisShadowReplaySamplerSnapshot.disabled());
    }

    public PendingState(
        IrisProgramIdentityRegistry.Registration registration,
        IrisGlStateSnapshot snapshot,
        IrisGlResourceBindingSnapshot resourceBindings,
        IrisExecutionCommand command,
        IrisDynamicDrawState dynamicState,
        IrisVertexInputBindings vertexInputBindings,
        IrisShadowReplayBufferSnapshot replayBuffers) {
      this(registration, snapshot, resourceBindings, command, dynamicState,
          vertexInputBindings, replayBuffers,
          IrisShadowReplayTextureSnapshot.disabled(),
          IrisShadowReplaySamplerSnapshot.disabled());
    }

    public PendingState(
        IrisProgramIdentityRegistry.Registration registration,
        IrisGlStateSnapshot snapshot,
        IrisGlResourceBindingSnapshot resourceBindings,
        IrisExecutionCommand command,
        IrisDynamicDrawState dynamicState,
        IrisVertexInputBindings vertexInputBindings,
        IrisShadowReplayBufferSnapshot replayBuffers,
        IrisShadowReplayTextureSnapshot replayTextures) {
      this(registration, snapshot, resourceBindings, command, dynamicState,
          vertexInputBindings, replayBuffers, replayTextures,
          IrisShadowReplaySamplerSnapshot.disabled());
    }

    public PendingState {
      Objects.requireNonNull(registration, "registration");
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(dynamicState, "dynamicState");
      Objects.requireNonNull(vertexInputBindings, "vertexInputBindings");
      Objects.requireNonNull(replayBuffers, "replayBuffers");
      Objects.requireNonNull(replayTextures, "replayTextures");
      Objects.requireNonNull(replaySamplers, "replaySamplers");
    }
  }

  public record PipelineLookupKey(long registrationGeneration,
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
    public PipelineLookupKey {
      if (registrationGeneration <= 0 || resourceEvictions < 0) {
        throw new IllegalArgumentException("invalid pipeline lookup key");
      }
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(program, "program");
      Objects.requireNonNull(framebuffer, "framebuffer");
      Objects.requireNonNull(drawBuffers, "drawBuffers");
      colorTargets = List.copyOf(colorTargets);
      Objects.requireNonNull(depthAttachment, "depthAttachment");
      Objects.requireNonNull(stencilAttachment, "stencilAttachment");
      Objects.requireNonNull(depth, "depth");
      Objects.requireNonNull(stencil, "stencil");
      Objects.requireNonNull(raster, "raster");
      Objects.requireNonNull(multisample, "multisample");
      Objects.requireNonNull(primitive, "primitive");
      unknownFields = List.copyOf(unknownFields);
    }
  }
}

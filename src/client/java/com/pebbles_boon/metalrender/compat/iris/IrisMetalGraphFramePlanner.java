package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.util.MetalLogger;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.ColorAttachment;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Access;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Resource;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.ResourceUse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Strict transient bridge from one captured Iris execution plan to MGF9.
 * Nothing is guessed: unsupported transfers, missing live snapshots, target
 * ambiguity, and reads from uninitialized Metal attachments reject the whole
 * candidate frame before JNI submission.
 */
public final class IrisMetalGraphFramePlanner {
  private static final String EXACT_JAR_DIAGNOSTIC_CUT_NODE_PROPERTY =
      "metalrender.exactJar.diagnosticFullGraphCutNode";
  private static final String EXACT_JAR_DIAGNOSTIC_CUT_TEXTURE_PROPERTY =
      "metalrender.exactJar.diagnosticFullGraphCutTexture";
  private static final String EXACT_JAR_DIAGNOSTIC_READBACK_NODE_PROPERTY =
      "metalrender.exactJar.diagnosticGraphReadbackNode";
  private static final AtomicLong EXACT_BUFFER_DIAGNOSTIC_FRAMES =
      new AtomicLong();
  private static final AtomicBoolean EXACT_GRAPH_RESOURCE_INVENTORY_LOGGED =
      new AtomicBoolean();
  private static final Set<String> EXACT_EXTERNAL_TEXTURE_ROUTES =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final int MAX_EXACT_EXTERNAL_TEXTURE_ROUTES = 96;

  private IrisMetalGraphFramePlanner() {
  }

  public static Result build(IrisRenderExecutionPlan plan,
      List<NativeIrisMetalGraphResources.Binding> nativeBindings,
      Set<Long> initializedTokens, DrawResolver drawResolver) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(nativeBindings, "nativeBindings");
    Objects.requireNonNull(initializedTokens, "initializedTokens");
    Objects.requireNonNull(drawResolver, "drawResolver");
    try {
      return buildStrict(plan, nativeBindings, initializedTokens,
          drawResolver);
    } catch (UnsupportedFrame unsupported) {
      return new Unsupported(unsupported.reason);
    } catch (IllegalArgumentException | ArithmeticException failure) {
      if (System.getProperty("metalrender.exactJar.expectedPath") != null) {
        String message = failure.getMessage();
        MetalLogger.warn(
            "exact-JAR FULL graph packet validation failed: %s: %s",
            failure.getClass().getSimpleName(),
            message == null || message.isBlank() ? "no-detail"
                : message.replace('\n', ' ').replace('\r', ' '));
      }
      return new Unsupported("graph-frame-packet-invalid");
    }
  }

  private static Result buildStrict(IrisRenderExecutionPlan plan,
      List<NativeIrisMetalGraphResources.Binding> nativeBindings,
      Set<Long> initializedTokens, DrawResolver drawResolver) {
    List<String> structural = plan.structuralBlockers();
    if (!structural.isEmpty()) {
      throw unsupported("graph-frame-" + structural.getFirst());
    }
    Map<Integer, ResourceHandle> handlesById = new HashMap<>();
    Map<ResourceHandle, Integer> idsByHandle = new HashMap<>();
    Map<Integer, List<Integer>> idsByTextureName = new HashMap<>();
    long contextGeneration = 0;
    for (IrisRenderExecutionPlan.ResourceBinding binding
        : plan.resourceBindings()) {
      ResourceHandle handle = binding.handle();
      if (contextGeneration == 0) {
        contextGeneration = handle.contextGeneration();
      } else if (contextGeneration != handle.contextGeneration()) {
        throw unsupported("graph-frame-context-generation-mismatch");
      }
      handlesById.put(binding.resourceId(), handle);
      idsByHandle.put(handle, binding.resourceId());
      if (handle.kind() == ResourceKind.TEXTURE) {
        idsByTextureName.computeIfAbsent(handle.name(), ignored ->
            new ArrayList<>()).add(binding.resourceId());
      }
    }
    if (contextGeneration <= 0) {
      throw unsupported("graph-frame-context-generation-unavailable");
    }
    logExactGraphResourceInventory(plan, handlesById);

    Map<Integer, Long> tokensById = new HashMap<>();
    HashSet<Long> distinctTokens = new HashSet<>();
    ArrayList<IrisMetalGraphFramePacketEncoder.Resource> frameResources =
        new ArrayList<>(nativeBindings.size());
    for (NativeIrisMetalGraphResources.Binding binding : nativeBindings) {
      if (!handlesById.containsKey(binding.resourceId())
          || !distinctTokens.add(binding.token())
          || tokensById.put(binding.resourceId(), binding.token()) != null) {
        throw unsupported("graph-frame-native-binding-invalid");
      }
      frameResources.add(new IrisMetalGraphFramePacketEncoder.Resource(
          binding.resourceId(), binding.token()));
    }
    if (frameResources.isEmpty()) {
      throw unsupported("graph-frame-has-no-native-resources");
    }

    HashSet<Integer> initialized = new HashSet<>();
    tokensById.forEach((resourceId, token) -> {
      if (initializedTokens.contains(token)) {
        initialized.add(resourceId);
      }
    });
    HashSet<Integer> written = new HashSet<>();
    ArrayList<IrisMetalGraphFramePacketEncoder.Operation> operations =
        new ArrayList<>(plan.steps().size());
    FrameInputBufferRegistry inputBuffers = new FrameInputBufferRegistry();
    FrameInputTextureRegistry inputTextures =
        new FrameInputTextureRegistry();
    int readbackResourceId = IrisMetalGraphFramePacketEncoder.NO_READBACK;
    int diagnosticReadbackNode = Integer.getInteger(
        EXACT_JAR_DIAGNOSTIC_READBACK_NODE_PROPERTY, -1);

    for (IrisRenderExecutionPlan.Step step : plan.steps()) {
      if (step instanceof IrisRenderExecutionPlan.ClearStep clear) {
        List<Integer> targets = clearTargets(plan, clear, tokensById,
            idsByHandle);
        for (Integer target : targets) {
          operations.add(clearOperation(plan.graph().resources().get(target),
              target, clear.command()));
          initialized.add(target);
          written.add(target);
          if (clear.phase() == IrisRenderGraph.Phase.FINAL
              && colorFormat(plan.graph().resources().get(target).format())) {
            readbackResourceId = target;
          }
        }
        continue;
      }
      if (step instanceof IrisRenderExecutionPlan.BarrierStep barrier) {
        operations.add(new IrisMetalGraphFramePacketEncoder.Barrier(
            barrier.barrierBits()));
        continue;
      }
      if (step instanceof IrisRenderExecutionPlan.TransferStep transfer) {
        List<IrisMetalGraphFramePacketEncoder.Operation> encoded =
            transferOperations(plan, transfer, tokensById, initialized,
                idsByHandle);
        operations.addAll(encoded);
        for (ResourceUse use : transfer.resources()) {
          if (use.access().writes() && tokensById.containsKey(
              use.resourceId())) {
            initialized.add(use.resourceId());
            written.add(use.resourceId());
            if (transfer.phase() == IrisRenderGraph.Phase.FINAL
                && readbackResourceId
                    == IrisMetalGraphFramePacketEncoder.NO_READBACK
                && colorFormat(plan.graph().resources()
                    .get(use.resourceId()).format())) {
              readbackResourceId = use.resourceId();
            }
          }
        }
        if (transfer.nodeId() == diagnosticReadbackNode) {
          List<Integer> candidates = transfer.resources().stream()
              .map(ResourceUse::resourceId)
              .filter(tokensById::containsKey).distinct().toList();
          if (candidates.size() != 1) {
            throw unsupported("graph-frame-diagnostic-readback-ambiguous");
          }
          readbackResourceId = candidates.getFirst();
          break;
        }
        continue;
      }

      IrisRenderExecutionPlan.PipelineStep pipeline =
          (IrisRenderExecutionPlan.PipelineStep) step;
      boolean compute = pipeline.kind() == IrisRenderGraph.NodeKind.DISPATCH;
      if (!compute && pipeline.kind() != IrisRenderGraph.NodeKind.DRAW) {
        throw unsupported("graph-frame-pipeline-kind-unsupported");
      }
      TargetExtent extent = compute
          ? new TargetExtent(1, 1)
          : targetExtent(plan, pipeline, tokensById);
      DrawResult resolvedResult = drawResolver.resolve(pipeline,
          extent.width(), extent.height());
      if (resolvedResult instanceof UnsupportedDraw blocked) {
        throw unsupported(blocked.reason());
      }
      ResolvedDraw resolved = ((CompleteDraw) resolvedResult).draw();

      if (compute) {
        if (!(pipeline.pending().command()
            instanceof IrisExecutionCommand.Dispatch dispatch)) {
          throw unsupported("graph-frame-compute-command-invalid");
        }
        List<ResourceUse> nodeUses = plan.graph().nodes()
            .get(pipeline.nodeId()).resources();
        for (ResourceUse use : nodeUses) {
          if (use.access().reads() && !initialized.contains(use.resourceId())) {
            throw unsupported("graph-frame-compute-read-uninitialized");
          }
        }
        Set<Integer> storageImageWriteTextures = pipeline.pending()
            .resourceBindings().imageUnits().values().stream()
            .filter(binding -> binding.texture() > 0
                && binding.access() != 0x88B8)
            .map(IrisGlResourceBindingSnapshot.ImageUnitBinding::texture)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<Integer, Integer> overrides = textureOverrides(plan, pipeline,
            tokensById, idsByTextureName, initialized, Set.of(),
            resolved.sampledTextureNames(), storageImageWriteTextures);
        Map<Integer, Integer> externalBuffers = inputBuffers.register(
            resolved.requiredBufferImages());
        Map<Integer, Integer> externalTextures = inputTextures.register(
            resolved.requiredTextures(), overrides.keySet());
        List<Integer> graphResources = nodeUses.stream()
            .filter(use -> tokensById.containsKey(use.resourceId()))
            .map(ResourceUse::resourceId)
            .distinct().sorted().toList();
        operations.add(new IrisMetalGraphFramePacketEncoder.Compute(
            resolved.pipelineKeySha256(),
            resolved.replayPacket(overrides.keySet(), externalBuffers,
                externalTextures),
            dispatch.groupsX(), dispatch.groupsY(), dispatch.groupsZ(),
            graphResources));
        for (ResourceUse use : nodeUses) {
          if (use.access().writes()) {
            initialized.add(use.resourceId());
            written.add(use.resourceId());
          }
        }
        if (pipeline.nodeId() == diagnosticReadbackNode) {
          break;
        }
        continue;
      }

      DrawTargets targets = drawTargets(plan, pipeline, resolved.state(),
          tokensById, idsByHandle);
      // A persistent graph attachment has no defined contents on its first
      // Metal frame, while MTL4 render passes below intentionally use Load to
      // preserve pixels outside the draw.  Bootstrap each first-use target to
      // deterministic GL-compatible defaults.  The graph remains validation
      // only until visual parity passes, so temporal shader-pack resources can
      // warm up without ever replacing the visible OpenGL frame.
      for (Integer target : targets.allTargets().stream().sorted().toList()) {
        if (initialized.add(target)) {
          operations.add(bootstrapClearOperation(
              plan.graph().resources().get(target), target));
          written.add(target);
        }
      }
      Set<Integer> storageImageWriteTextures = pipeline.pending()
          .resourceBindings().imageUnits().values().stream()
          .filter(binding -> binding.texture() > 0
              && binding.access() != 0x88B8)
          .map(IrisGlResourceBindingSnapshot.ImageUnitBinding::texture)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      validateDrawResourceRouting(plan, pipeline, tokensById, initialized,
          targets.allTargets(), handlesById,
          resolved.sampledTextureNames(), storageImageWriteTextures);
      Map<Integer, Integer> overrides = textureOverrides(plan, pipeline,
          tokensById, idsByTextureName, initialized, targets.allTargets(),
          resolved.sampledTextureNames(), storageImageWriteTextures);
      Map<Integer, Integer> externalBuffers = inputBuffers.register(
          resolved.requiredBufferImages());
      Map<Integer, Integer> externalTextures = inputTextures.register(
          resolved.requiredTextures(), overrides.keySet());
      operations.add(new IrisMetalGraphFramePacketEncoder.Draw(
          resolved.pipelineKeySha256(),
          resolved.replayPacket(overrides.keySet(), externalBuffers,
              externalTextures),
          targets.colors(), targets.depthResourceId(),
          targets.stencilResourceId(), overrides));
      initialized.addAll(targets.allTargets());
      written.addAll(targets.allTargets());
      if ((pipeline.phase() == IrisRenderGraph.Phase.FINAL
          || pipeline.nodeId() == diagnosticReadbackNode)
          && !targets.colors().isEmpty()) {
        readbackResourceId = targets.colors().stream()
            .filter(target -> target.slot() == 0)
            .map(IrisMetalGraphFramePacketEncoder.ColorTarget::resourceId)
            .findFirst().orElse(targets.colors().getFirst().resourceId());
      }
      if (pipeline.nodeId() == diagnosticReadbackNode) {
        break;
      }
    }
    if (operations.isEmpty()) {
      throw unsupported("graph-frame-has-no-operations");
    }
    if (readbackResourceId >= 0) {
      Resource output = plan.graph().resources().get(readbackResourceId);
      if (!colorFormat(output.format()) || output.sampleCount() != 1) {
        readbackResourceId = IrisMetalGraphFramePacketEncoder.NO_READBACK;
      }
    }
    inputBuffers.logExactDiagnostic();
    inputTextures.logExactDiagnostic();
    IrisMetalGraphFramePacketEncoder.Frame frame =
        new IrisMetalGraphFramePacketEncoder.Frame(contextGeneration,
            frameResources, inputBuffers.inputs(), inputTextures.inputs(),
            operations,
            readbackResourceId,
            IrisMetalGraphFramePacketEncoder.NO_PRESENTATION);
    return new Complete(frame, written, readbackResourceId,
        inputTextures.renderThreadSubmissionRequired());
  }

  private static void logExactGraphResourceInventory(
      IrisRenderExecutionPlan plan,
      Map<Integer, ResourceHandle> handlesById) {
    if (System.getProperty("metalrender.exactJar.expectedPath") == null
        || !EXACT_GRAPH_RESOURCE_INVENTORY_LOGGED.compareAndSet(false,
            true)) {
      return;
    }
    plan.graph().resources().stream()
        .filter(resource -> resource.kind()
            == IrisRenderGraph.ResourceKind.TEXTURE)
        .filter(resource -> resource.format().equals("rgb8-snorm")
            || resource.format().equals("rgba16-float")
            || resource.format().equals("rgb16-float"))
        .forEach(resource -> {
          ResourceHandle handle = handlesById.get(resource.id());
          String reads = plan.graph().nodes().stream()
              .filter(node -> node.resources().stream().anyMatch(use ->
                  use.resourceId() == resource.id()
                      && use.access().reads()))
              .map(node -> node.phase() + ":" + node.id())
              .collect(java.util.stream.Collectors.joining(","));
          String writes = plan.graph().nodes().stream()
              .filter(node -> node.resources().stream().anyMatch(use ->
                  use.resourceId() == resource.id()
                      && use.access().writes()))
              .map(node -> node.phase() + ":" + node.id())
              .collect(java.util.stream.Collectors.joining(","));
          MetalLogger.info(
              "exact-JAR FULL graph tracked temporal texture: r%d t%s format=%s/%dx%d reads=%s writes=%s",
              resource.id(), handle == null ? "missing"
                  : Integer.toString(handle.name()), resource.format(),
              resource.width(), resource.height(),
              reads.isEmpty() ? "none" : reads,
              writes.isEmpty() ? "none" : writes);
        });
  }

  private static List<Integer> clearTargets(IrisRenderExecutionPlan plan,
      IrisRenderExecutionPlan.ClearStep clear, Map<Integer, Long> tokensById,
      Map<ResourceHandle, Integer> idsByHandle) {
    if (clear.command().region().isPresent()) {
      throw unsupported("graph-frame-partial-clear-unimplemented");
    }
    ArrayList<Integer> candidates = new ArrayList<>();
    if (clear.command().target().kind() == ResourceKind.TEXTURE) {
      Integer id = idsByHandle.get(clear.command().target());
      if (id != null && tokensById.containsKey(id)) {
        candidates.add(id);
      }
    } else {
      for (ResourceUse use : clear.resources()) {
        if (use.access().writes() && tokensById.containsKey(use.resourceId())) {
          candidates.add(use.resourceId());
        }
      }
    }
    candidates.removeIf(id -> !clearFormatMatches(
        plan.graph().resources().get(id).format(), clear.command().buffer()));
    if (candidates.size() != 1) {
      throw unsupported("graph-frame-clear-target-ambiguous");
    }
    return List.copyOf(candidates);
  }

  private static IrisMetalGraphFramePacketEncoder.Clear clearOperation(
      Resource resource, int resourceId, IrisClearCommand command) {
    IrisMetalGraphFramePacketEncoder.Aspect aspect = switch (command.buffer()) {
      case IrisClearCommand.GL_COLOR ->
          IrisMetalGraphFramePacketEncoder.Aspect.COLOR;
      case IrisClearCommand.GL_DEPTH ->
          IrisMetalGraphFramePacketEncoder.Aspect.DEPTH;
      case IrisClearCommand.GL_STENCIL ->
          IrisMetalGraphFramePacketEncoder.Aspect.STENCIL;
      case IrisClearCommand.GL_DEPTH_STENCIL ->
          IrisMetalGraphFramePacketEncoder.Aspect.DEPTH_STENCIL;
      default -> throw unsupported("graph-frame-clear-aspect-unsupported");
    };
    if (!clearFormatMatches(resource.format(), command.buffer())) {
      throw unsupported("graph-frame-clear-format-mismatch");
    }
    return new IrisMetalGraphFramePacketEncoder.Clear(resourceId, aspect,
        command.valueKind(), command.rawValues(), command.region());
  }

  private static IrisMetalGraphFramePacketEncoder.Clear
      bootstrapClearOperation(Resource resource, int resourceId) {
    String format = resource.format();
    if (depthFormat(format) && stencilFormat(format)) {
      return new IrisMetalGraphFramePacketEncoder.Clear(resourceId,
          IrisMetalGraphFramePacketEncoder.Aspect.DEPTH_STENCIL,
          IrisClearCommand.ValueKind.FLOAT32,
          List.of(Integer.toUnsignedLong(Float.floatToRawIntBits(1.0F)),
              0L), Optional.empty());
    }
    if (depthFormat(format)) {
      return new IrisMetalGraphFramePacketEncoder.Clear(resourceId,
          IrisMetalGraphFramePacketEncoder.Aspect.DEPTH,
          IrisClearCommand.ValueKind.FLOAT32,
          List.of(Integer.toUnsignedLong(Float.floatToRawIntBits(1.0F))),
          Optional.empty());
    }
    if (stencilFormat(format)) {
      return new IrisMetalGraphFramePacketEncoder.Clear(resourceId,
          IrisMetalGraphFramePacketEncoder.Aspect.STENCIL,
          IrisClearCommand.ValueKind.SINT32, List.of(0L), Optional.empty());
    }
    if (!colorFormat(format)) {
      throw unsupported("graph-frame-bootstrap-format-unsupported");
    }
    return new IrisMetalGraphFramePacketEncoder.Clear(resourceId,
        IrisMetalGraphFramePacketEncoder.Aspect.COLOR,
        IrisClearCommand.ValueKind.FLOAT32,
        List.of(0L, 0L, 0L, 0L), Optional.empty());
  }

  private static List<IrisMetalGraphFramePacketEncoder.Operation>
      transferOperations(IrisRenderExecutionPlan plan,
      IrisRenderExecutionPlan.TransferStep transfer,
      Map<Integer, Long> tokensById, Set<Integer> initialized,
      Map<ResourceHandle, Integer> idsByHandle) {
    IrisTransferCommand command = transfer.command();
    if (command instanceof IrisTransferCommand.GenerateMipmaps) {
      List<Integer> resources = transfer.resources().stream()
          .map(ResourceUse::resourceId).filter(tokensById::containsKey)
          .distinct().toList();
      if (resources.size() != 1) {
        throw unsupported("graph-frame-mipmap-target-ambiguous");
      }
      if (!initialized.contains(resources.getFirst())) {
        throw unsupported("graph-frame-transfer-source-uninitialized");
      }
      return List.of(new IrisMetalGraphFramePacketEncoder.GenerateMipmaps(
          resources.getFirst()));
    }
    List<Integer> sources = transfer.resources().stream()
        .filter(use -> use.access().reads())
        .map(ResourceUse::resourceId).filter(tokensById::containsKey)
        .toList();
    List<Integer> destinations = transfer.resources().stream()
        .filter(use -> use.access().writes())
        .map(ResourceUse::resourceId).filter(tokensById::containsKey)
        .toList();
    if (sources.stream().anyMatch(source -> !initialized.contains(source))) {
      throw unsupported("graph-frame-transfer-source-uninitialized");
    }
    if (command instanceof IrisTransferCommand.CopyTexImage2D copy) {
      return List.of(copyTexture(plan, sources, destinations,
          idsByHandle.get(copy.sourceTexture()),
          idsByHandle.get(copy.destinationTexture()), 0, copy.level(),
          copy.sourceX(), copy.sourceY(), 0, 0, copy.width(),
          copy.height()));
    }
    if (command instanceof IrisTransferCommand.CopyTexSubImage2D copy) {
      return List.of(copyTexture(plan, sources, destinations,
          idsByHandle.get(copy.sourceTexture()),
          idsByHandle.get(copy.destinationTexture()), 0, copy.level(),
          copy.sourceX(), copy.sourceY(), copy.destinationX(),
          copy.destinationY(), copy.width(), copy.height()));
    }
    if (!(command instanceof IrisTransferCommand.BlitFramebuffer blit)) {
      throw unsupported("graph-frame-transfer-unimplemented");
    }
    int sourceWidth = blit.sourceX1() - blit.sourceX0();
    int sourceHeight = blit.sourceY1() - blit.sourceY0();
    int destinationWidth = blit.destinationX1() - blit.destinationX0();
    int destinationHeight = blit.destinationY1() - blit.destinationY0();
    if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0
        || destinationHeight <= 0 || sourceWidth != destinationWidth
        || sourceHeight != destinationHeight) {
      throw unsupported("graph-frame-scaled-or-flipped-blit-unimplemented");
    }
    ArrayList<IrisMetalGraphFramePacketEncoder.Operation> operations =
        new ArrayList<>();
    ArrayList<Integer> unmatched = new ArrayList<>(destinations);
    for (Integer source : sources) {
      Resource sourceResource = plan.graph().resources().get(source);
      Integer destination = unmatched.stream().filter(candidate ->
          plan.graph().resources().get(candidate).format()
              .equals(sourceResource.format())).findFirst().orElse(null);
      if (destination == null) {
        continue;
      }
      Resource destinationResource = plan.graph().resources().get(destination);
      if (!aspectIncluded(sourceResource.format(), blit.mask())) {
        continue;
      }
      operations.add(new IrisMetalGraphFramePacketEncoder.CopyTexture(source,
          destination, 0, 0, blit.sourceX0(), blit.sourceY0(),
          blit.destinationX0(), blit.destinationY0(), sourceWidth,
          sourceHeight));
      unmatched.remove(destination);
      if (sourceResource.sampleCount() != 1
          || destinationResource.sampleCount() != 1) {
        throw unsupported("graph-frame-multisample-blit-unimplemented");
      }
    }
    if (operations.isEmpty() || !unmatched.isEmpty()) {
      throw unsupported("graph-frame-blit-routing-ambiguous");
    }
    return List.copyOf(operations);
  }

  private static IrisMetalGraphFramePacketEncoder.CopyTexture copyTexture(
      IrisRenderExecutionPlan plan, List<Integer> sources,
      List<Integer> destinations, Integer exactSource,
      Integer exactDestination, int sourceLevel, int destinationLevel,
      int sourceX, int sourceY, int destinationX, int destinationY,
      int width, int height) {
    if (exactSource == null || exactDestination == null
        || !sources.contains(exactSource)
        || !destinations.contains(exactDestination)) {
      throw unsupported("graph-frame-copy-resource-routing-unavailable");
    }
    String sourceFormat = plan.graph().resources().get(exactSource).format();
    String destinationFormat = plan.graph().resources().get(
        exactDestination).format();
    if (!sourceFormat.equals(destinationFormat)) {
      throw unsupported("graph-frame-copy-format-mismatch-"
          + sourceFormat + "-to-" + destinationFormat);
    }
    if (destinations.size() != 1
        || destinations.getFirst().intValue() != exactDestination) {
      throw unsupported("graph-frame-copy-destination-ambiguous");
    }
    return new IrisMetalGraphFramePacketEncoder.CopyTexture(
        exactSource, exactDestination, sourceLevel,
        destinationLevel, sourceX, sourceY, destinationX, destinationY,
        width, height);
  }

  private static TargetExtent targetExtent(IrisRenderExecutionPlan plan,
      IrisRenderExecutionPlan.PipelineStep pipeline,
      Map<Integer, Long> tokensById) {
    int width = 0;
    int height = 0;
    int samples = 0;
    for (ResourceUse use : plan.graph().nodes().get(pipeline.nodeId())
        .resources()) {
      if (!use.access().writes() || !tokensById.containsKey(use.resourceId())) {
        continue;
      }
      Resource resource = plan.graph().resources().get(use.resourceId());
      if (width == 0) {
        width = resource.width();
        height = resource.height();
        samples = resource.sampleCount();
      } else if (width != resource.width() || height != resource.height()
          || samples != resource.sampleCount()) {
        throw unsupported("graph-frame-render-target-extent-mismatch");
      }
    }
    if (width <= 0 || height <= 0) {
      throw unsupported("graph-frame-render-target-unavailable");
    }
    return new TargetExtent(width, height);
  }

  private static DrawTargets drawTargets(IrisRenderExecutionPlan plan,
      IrisRenderExecutionPlan.PipelineStep pipeline, IrisPipelineState state,
      Map<Integer, Long> tokensById,
      Map<ResourceHandle, Integer> idsByHandle) {
    IrisGlStateSnapshot snapshot = pipeline.pending().snapshot();
    ArrayList<IrisMetalGraphFramePacketEncoder.ColorTarget> colors =
        new ArrayList<>();
    HashSet<Integer> allTargets = new HashSet<>();
    for (ColorAttachment expected : state.colorAttachments()) {
      IrisGlStateSnapshot.ColorTarget target = snapshot.colorTargets().stream()
          .filter(candidate -> candidate.index() == expected.slot())
          .findFirst().orElse(null);
      if (target == null || !target.attachment().isKnown()
          || target.attachment().value().isEmpty()) {
        throw unsupported("graph-frame-color-target-unavailable");
      }
      IrisGlStateSnapshot.TextureAttachment attachment =
          target.attachment().value().orElseThrow();
      if (attachment.mipLevel() != 0) {
        throw unsupported("graph-frame-attachment-mip-unimplemented");
      }
      Integer resourceId = idsByHandle.get(attachment.texture());
      if (resourceId == null || !tokensById.containsKey(resourceId)
          || !expected.format().cacheName().equals(
              plan.graph().resources().get(resourceId).format())) {
        throw unsupported("graph-frame-color-target-mismatch");
      }
      colors.add(new IrisMetalGraphFramePacketEncoder.ColorTarget(
          expected.slot(), resourceId));
      allTargets.add(resourceId);
    }
    int depth = attachmentResource(snapshot.depthAttachment(),
        state.depthAttachmentFormat(), plan, tokensById, idsByHandle,
        "depth");
    int stencil = attachmentResource(snapshot.stencilAttachment(),
        state.stencilAttachmentFormat(), plan, tokensById, idsByHandle,
        "stencil");
    if (depth >= 0) {
      allTargets.add(depth);
    }
    if (stencil >= 0) {
      allTargets.add(stencil);
    }
    if (colors.isEmpty() && depth < 0 && stencil < 0) {
      throw unsupported("graph-frame-draw-has-no-target");
    }
    return new DrawTargets(colors, depth, stencil, allTargets);
  }

  private static int attachmentResource(IrisGlStateSnapshot.StateValue<
      Optional<IrisGlStateSnapshot.TextureAttachment>> captured,
      Optional<IrisPipelineState.DataFormat> expected,
      IrisRenderExecutionPlan plan, Map<Integer, Long> tokensById,
      Map<ResourceHandle, Integer> idsByHandle, String label) {
    if (expected.isEmpty()) {
      if (captured.isKnown() && captured.value().isPresent()) {
        throw unsupported("graph-frame-unexpected-" + label + "-target");
      }
      return -1;
    }
    if (!captured.isKnown() || captured.value().isEmpty()) {
      throw unsupported("graph-frame-" + label + "-target-unavailable");
    }
    IrisGlStateSnapshot.TextureAttachment attachment =
        captured.value().orElseThrow();
    if (attachment.mipLevel() != 0) {
      throw unsupported("graph-frame-attachment-mip-unimplemented");
    }
    Integer resourceId = idsByHandle.get(attachment.texture());
    if (resourceId == null || !tokensById.containsKey(resourceId)
        || !expected.orElseThrow().cacheName().equals(
            plan.graph().resources().get(resourceId).format())) {
      throw unsupported("graph-frame-" + label + "-target-mismatch");
    }
    return resourceId;
  }

  private static Map<Integer, Integer> textureOverrides(
      IrisRenderExecutionPlan plan,
      IrisRenderExecutionPlan.PipelineStep pipeline,
      Map<Integer, Long> tokensById, Map<Integer, List<Integer>> idsByName,
      Set<Integer> initialized, Set<Integer> targets,
      Set<Integer> sampledTextureNames,
      Set<Integer> storageImageWriteTextures) {
    int diagnosticCutNode = Integer.getInteger(
        EXACT_JAR_DIAGNOSTIC_CUT_NODE_PROPERTY, -1);
    int diagnosticCutTexture = Integer.getInteger(
        EXACT_JAR_DIAGNOSTIC_CUT_TEXTURE_PROPERTY, -1);
    if (pipeline.nodeId() == diagnosticCutNode
        && diagnosticCutTexture < 0) {
      if (System.getProperty("metalrender.exactJar.expectedPath") != null) {
        MetalLogger.info(
            "exact-JAR FULL graph diagnostic cut: phase=%s node=%d sampled=%s",
            pipeline.phase(), pipeline.nodeId(), sampledTextureNames);
      }
      return Map.of();
    }
    LinkedHashMap<Integer, Integer> overrides = new LinkedHashMap<>();
    for (Integer glName : sampledTextureNames.stream().sorted().toList()) {
      boolean textureCutApplies = diagnosticTextureCutApplies(
          pipeline.nodeId(), diagnosticCutNode, glName,
          diagnosticCutTexture);
      if (textureCutApplies) {
        if (System.getProperty("metalrender.exactJar.expectedPath") != null) {
          MetalLogger.info(
              "exact-JAR FULL graph diagnostic texture cut: phase=%s node=%d texture=%d",
              pipeline.phase(), pipeline.nodeId(), glName);
        }
        continue;
      }
      boolean storageImageWrite =
          storageImageWriteTextures.contains(glName);
      List<Integer> candidates = idsByName.getOrDefault(glName, List.of())
          .stream().filter(tokensById::containsKey)
          .filter(resourceId -> storageImageWrite
              || initialized.contains(resourceId))
          .toList();
      if (candidates.size() > 1) {
        throw unsupported("graph-frame-texture-override-ambiguous");
      }
      if (candidates.size() == 1) {
        int resourceId = candidates.getFirst();
        if (targets.contains(resourceId)) {
          throw unsupported("graph-frame-render-feedback-unimplemented");
        }
        Resource resource = plan.graph().resources().get(resourceId);
        IrisGlTextureMirror.TextureSnapshot captured = pipeline.pending()
            .replayTextures().textures().get(glName);
        if (!textureOverrideCompatible(resource, captured)) {
          MetalLogger.warn(
              "Iris full Metal graph texture override mismatch: phase=%s node=%d resource=%d texture=%d graph=%s/%dx%d snapshot=%s/%dx%d/g%d/m%d/l%d",
              pipeline.phase(), pipeline.nodeId(), resourceId, glName,
              resource.format(), resource.width(), resource.height(),
              captured.format(), captured.width(), captured.height(),
              captured.generation(), captured.mipLevel(), captured.layer());
          throw unsupported("graph-frame-texture-override-mismatch");
        }
        overrides.put(glName, resourceId);
      } else if (System.getProperty(
          "metalrender.exactJar.expectedPath") != null) {
        List<Integer> graphCandidates = idsByName.getOrDefault(glName,
            List.of()).stream().filter(tokensById::containsKey).toList();
        String route = "t" + glName + ':' + pipeline.phase() + ":node"
            + pipeline.nodeId() + ":graph=" + graphCandidates
            + ":initialized=" + graphCandidates.stream()
                .filter(initialized::contains).toList();
        if (EXACT_EXTERNAL_TEXTURE_ROUTES.size()
                < MAX_EXACT_EXTERNAL_TEXTURE_ROUTES
            && EXACT_EXTERNAL_TEXTURE_ROUTES.add(route)) {
          MetalLogger.info(
              "exact-JAR FULL graph external texture route: %s", route);
        }
      }
    }
    return Map.copyOf(overrides);
  }

  static boolean diagnosticTextureCutApplies(int pipelineNode,
      int diagnosticCutNode, int texture, int diagnosticCutTexture) {
    return texture == diagnosticCutTexture
        && (diagnosticCutNode < 0 || pipelineNode == diagnosticCutNode);
  }

  /**
   * The GL-to-IOSurface bridge expands packed RG11B10F into RGBA16F because
   * CGL cannot expose the packed attachment directly. Once the same logical
   * graph resource is Metal-owned it stays RG11B10F; both views provide float
   * samples and whole-frame parity remains the final authority.
   */
  static boolean textureOverrideCompatible(Resource resource,
      IrisGlTextureMirror.TextureSnapshot captured) {
    if (captured == null) {
      return false;
    }
    boolean formatCompatible = resource.format().equals(captured.format())
        || resource.format().equals("rg11b10-float")
            && captured.format().equals("rgba16-float");
    return formatCompatible && captured.mipLevel() == 0
        && captured.layer() == 0 && resource.width() == captured.width()
        && resource.height() == captured.height();
  }

  private static void validateDrawResourceRouting(
      IrisRenderExecutionPlan plan,
      IrisRenderExecutionPlan.PipelineStep pipeline,
      Map<Integer, Long> tokensById, Set<Integer> initialized,
      Set<Integer> targets, Map<Integer, ResourceHandle> handlesById,
      Set<Integer> sampledTextureNames,
      Set<Integer> storageImageWriteTextures) {
    for (Integer target : targets) {
      if (!initialized.contains(target)) {
        throw unsupported("graph-frame-render-target-uninitialized");
      }
    }
    for (ResourceUse use : plan.graph().nodes().get(pipeline.nodeId())
        .resources()) {
      if (!tokensById.containsKey(use.resourceId())) {
        continue;
      }
      if (use.access().writes() && !targets.contains(use.resourceId())) {
        ResourceHandle writeHandle = handlesById.get(use.resourceId());
        if (writeHandle == null
            || writeHandle.kind() != ResourceKind.TEXTURE
            || !storageImageWriteTextures.contains(writeHandle.name())) {
          throw unsupported("graph-frame-storage-image-write-unimplemented");
        }
      }
      if (!use.access().reads() || initialized.contains(use.resourceId())) {
        continue;
      }
      ResourceHandle handle = handlesById.get(use.resourceId());
      if (handle == null || handle.kind() != ResourceKind.TEXTURE) {
        throw unsupported("graph-frame-initial-resource-unavailable");
      }
      if (!initialTextureSnapshotRequired(handle, sampledTextureNames)) {
        continue;
      }
      IrisGlTextureMirror.TextureSnapshot snapshot = pipeline.pending()
          .replayTextures().textures().get(handle.name());
      Resource resource = plan.graph().resources().get(use.resourceId());
      if (!initialTextureSnapshotCompatible(snapshot, resource)) {
        MetalLogger.warn(
            "Iris full Metal graph initial texture snapshot unavailable: phase=%s node=%d resource=%d texture=%d handleGeneration=%d expected=%s/%dx%d snapshot=%s",
            pipeline.phase(), pipeline.nodeId(), use.resourceId(),
            handle.name(), handle.generation(), resource.format(),
            resource.width(), resource.height(), snapshot == null
                ? "missing"
                : snapshot.format() + "/" + snapshot.width() + "x"
                    + snapshot.height() + "/g" + snapshot.generation()
                    + "/m" + snapshot.mipLevel() + "/l"
                    + snapshot.layer());
        throw unsupported("graph-frame-initial-texture-snapshot-unavailable");
      }
    }
  }

  static boolean initialTextureSnapshotRequired(ResourceHandle handle,
      Set<Integer> sampledTextureNames) {
    return handle != null && handle.kind() == ResourceKind.TEXTURE
        && sampledTextureNames.contains(handle.name());
  }

  /**
   * GL object generations and mirror-content generations deliberately use
   * separate counters.  Object identity is already protected by the graph
   * resource handle; initial packet data only needs to match the allocation
   * view consumed by this draw.
   */
  static boolean initialTextureSnapshotCompatible(
      IrisGlTextureMirror.TextureSnapshot snapshot, Resource resource) {
    boolean formatCompatible = snapshot != null
        && (snapshot.format().equals(resource.format())
            || resource.format().equals("rg11b10-float")
                && snapshot.format().equals("rgba16-float"));
    return snapshot != null && snapshot.mipLevel() == 0
        && snapshot.layer() == 0 && formatCompatible
        && snapshot.width() == resource.width()
        && snapshot.height() == resource.height();
  }

  private static boolean clearFormatMatches(String format, int buffer) {
    return switch (buffer) {
      case IrisClearCommand.GL_COLOR -> colorFormat(format);
      case IrisClearCommand.GL_DEPTH -> depthFormat(format);
      case IrisClearCommand.GL_STENCIL -> stencilFormat(format);
      case IrisClearCommand.GL_DEPTH_STENCIL ->
          depthFormat(format) && stencilFormat(format);
      default -> false;
    };
  }

  private static boolean colorFormat(String format) {
    return !depthFormat(format) && !stencilFormat(format)
        && !"framebuffer".equals(format)
        && !"runtime-texture".equals(format);
  }

  private static boolean depthFormat(String format) {
    return format.startsWith("d16-") || format.startsWith("d24-")
        || format.startsWith("d32-");
  }

  private static boolean stencilFormat(String format) {
    return format.startsWith("s8-") || format.contains("-s8-");
  }

  private static boolean aspectIncluded(String format, int mask) {
    if (depthFormat(format) || stencilFormat(format)) {
      boolean depth = depthFormat(format)
          && (mask & IrisTransferCommand.GL_DEPTH_BUFFER_BIT) != 0;
      boolean stencil = stencilFormat(format)
          && (mask & IrisTransferCommand.GL_STENCIL_BUFFER_BIT) != 0;
      return depth || stencil;
    }
    return (mask & IrisTransferCommand.GL_COLOR_BUFFER_BIT) != 0;
  }

  private static UnsupportedFrame unsupported(String reason) {
    return new UnsupportedFrame(reason);
  }

  @FunctionalInterface
  public interface DrawResolver {
    DrawResult resolve(IrisRenderExecutionPlan.PipelineStep pipeline,
        int targetWidth, int targetHeight);
  }

  public sealed interface DrawResult permits CompleteDraw, UnsupportedDraw {
  }

  public record CompleteDraw(ResolvedDraw draw) implements DrawResult {
    public CompleteDraw {
      Objects.requireNonNull(draw, "draw");
    }
  }

  public record UnsupportedDraw(String reason) implements DrawResult {
    public UnsupportedDraw {
      requireReason(reason);
    }
  }

  public static final class ResolvedDraw {
    private final String pipelineKeySha256;
    private final IrisPipelineState state;
    private final Set<Integer> sampledTextureNames;
    private final List<IrisShadowReplayBufferSnapshot.BufferImage>
        requiredBufferImages;
    private final List<IrisGlTextureMirror.TextureSnapshot>
        requiredTextures;
    private final ReplayPacketEncoder replayPacketEncoder;

    public ResolvedDraw(String pipelineKeySha256,
        IrisPipelineState state, byte[] replayPacket,
        Set<Integer> sampledTextureNames) {
      this(pipelineKeySha256, state, sampledTextureNames,
          List.of(), List.of(),
          (ignoredTextures, ignoredBuffers, ignoredInputTextures) ->
              replayPacket.clone());
      validatePacket(replayPacket);
    }

    public ResolvedDraw(String pipelineKeySha256,
        IrisPipelineState state, Set<Integer> sampledTextureNames,
        List<IrisShadowReplayBufferSnapshot.BufferImage>
            requiredBufferImages,
        ReplayPacketEncoder replayPacketEncoder) {
      this(pipelineKeySha256, state, sampledTextureNames,
          requiredBufferImages, List.of(), replayPacketEncoder);
    }

    public ResolvedDraw(String pipelineKeySha256,
        IrisPipelineState state, Set<Integer> sampledTextureNames,
        List<IrisShadowReplayBufferSnapshot.BufferImage>
            requiredBufferImages,
        List<IrisGlTextureMirror.TextureSnapshot> requiredTextures,
        ReplayPacketEncoder replayPacketEncoder) {
      IrisRenderGraph.requireSha(pipelineKeySha256, "pipelineKeySha256");
      this.pipelineKeySha256 = pipelineKeySha256;
      this.state = Objects.requireNonNull(state, "state");
      this.sampledTextureNames = Set.copyOf(sampledTextureNames);
      this.requiredBufferImages = List.copyOf(requiredBufferImages);
      this.requiredTextures = List.copyOf(requiredTextures);
      this.replayPacketEncoder = Objects.requireNonNull(replayPacketEncoder,
          "replayPacketEncoder");
      if (this.sampledTextureNames.size()
          > IrisShadowReplayTextureSnapshot.MAX_TEXTURES
          || this.sampledTextureNames.stream().anyMatch(name -> name <= 0)) {
        throw new IllegalArgumentException("invalid sampled texture set");
      }
      HashSet<Integer> imageIds = new HashSet<>();
      for (IrisShadowReplayBufferSnapshot.BufferImage image
          : this.requiredBufferImages) {
        Objects.requireNonNull(image, "requiredBufferImage");
        if (!imageIds.add(image.id())) {
          throw new IllegalArgumentException(
              "duplicate required buffer image");
        }
      }
      HashSet<Integer> textureNames = new HashSet<>();
      for (IrisGlTextureMirror.TextureSnapshot texture
          : this.requiredTextures) {
        Objects.requireNonNull(texture, "requiredTexture");
        if (!this.sampledTextureNames.contains(texture.texture())
            || !textureNames.add(texture.texture())) {
          throw new IllegalArgumentException(
              "invalid required texture image");
        }
      }
    }

    public String pipelineKeySha256() {
      return pipelineKeySha256;
    }

    public IrisPipelineState state() {
      return state;
    }

    public Set<Integer> sampledTextureNames() {
      return sampledTextureNames;
    }

    public byte[] replayPacket() {
      return replayPacket(Set.of(), Map.of(), Map.of());
    }

    List<IrisShadowReplayBufferSnapshot.BufferImage>
        requiredBufferImages() {
      return requiredBufferImages;
    }

    List<IrisGlTextureMirror.TextureSnapshot> requiredTextures() {
      return requiredTextures;
    }

    byte[] replayPacket(Set<Integer> externalTextureNames,
        Map<Integer, Integer> externalBufferIndices,
        Map<Integer, Integer> externalTextureIndices) {
      Set<Integer> external = Set.copyOf(externalTextureNames);
      if (!sampledTextureNames.containsAll(external)) {
        throw new IllegalArgumentException(
            "external texture is not sampled by draw");
      }
      Map<Integer, Integer> buffers = Map.copyOf(externalBufferIndices);
      if (buffers.size() != requiredBufferImages.size()
          || requiredBufferImages.stream().anyMatch(
              image -> !buffers.containsKey(image.id()))) {
        throw new IllegalArgumentException(
            "incomplete external buffer table");
      }
      Map<Integer, Integer> textures = Map.copyOf(externalTextureIndices);
      if (textures.keySet().stream().anyMatch(name ->
          !sampledTextureNames.contains(name)
              || external.contains(name))) {
        throw new IllegalArgumentException(
            "invalid external frame texture table");
      }
      byte[] packet = Objects.requireNonNull(
          replayPacketEncoder.encode(external, buffers, textures),
          "replayPacket");
      validatePacket(packet);
      return packet;
    }

    private static void validatePacket(byte[] replayPacket) {
      Objects.requireNonNull(replayPacket, "replayPacket");
      if (replayPacket.length <= 0 || replayPacket.length
          > IrisMetalShadowReplayPacketEncoder.MAX_PACKET_BYTES) {
        throw new IllegalArgumentException("invalid graph replay packet");
      }
    }
  }

  @FunctionalInterface
  public interface ReplayPacketEncoder {
    byte[] encode(Set<Integer> externalTextureNames,
        Map<Integer, Integer> externalBufferIndices,
        Map<Integer, Integer> externalTextureIndices);
  }

  public sealed interface Result permits Complete, Unsupported {
  }

  public record Complete(IrisMetalGraphFramePacketEncoder.Frame frame,
                         Set<Integer> writtenResourceIds,
                         int readbackResourceId,
                         boolean renderThreadSubmissionRequired)
      implements Result {
    public Complete(IrisMetalGraphFramePacketEncoder.Frame frame,
        Set<Integer> writtenResourceIds, int readbackResourceId) {
      this(frame, writtenResourceIds, readbackResourceId, false);
    }

    public Complete {
      Objects.requireNonNull(frame, "frame");
      writtenResourceIds = Set.copyOf(writtenResourceIds);
      if (writtenResourceIds.isEmpty()
          || readbackResourceId < IrisMetalGraphFramePacketEncoder.NO_READBACK) {
        throw new IllegalArgumentException("invalid graph frame plan");
      }
    }
  }

  public record Unsupported(String reason) implements Result {
    public Unsupported {
      requireReason(reason);
    }
  }

  /**
   * Deduplicates generation-qualified immutable draw buffers once per MGF9
   * frame. A GL mirror generation is a content lifetime boundary, so equal
   * keys are safe to share across draws; synthetic expansion buffers remain
   * draw-local.
   */
  private static final class FrameInputBufferRegistry {
    private final LinkedHashMap<FrameInputBufferKey, Integer> byKey =
        new LinkedHashMap<>();
    private final ArrayList<IrisMetalGraphFramePacketEncoder.InputBuffer>
        inputs = new ArrayList<>();
    private long references;
    private long referencedBytes;
    private long dedupHits;

    private Map<Integer, Integer> register(
        List<IrisShadowReplayBufferSnapshot.BufferImage> images) {
      LinkedHashMap<Integer, Integer> result = new LinkedHashMap<>();
      for (IrisShadowReplayBufferSnapshot.BufferImage image : images) {
        references++;
        referencedBytes = Math.addExact(referencedBytes,
            image.byteLength());
        int frameIndex;
        if (image.glBuffer() == 0) {
          frameIndex = append(image);
        } else {
          FrameInputBufferKey key = new FrameInputBufferKey(
              image.glBuffer(), image.generation(),
              image.sourceOffsetBytes(), image.byteLength(),
              image.sharedHandle());
          Integer existing = byKey.get(key);
          if (existing == null) {
            existing = append(image);
            byKey.put(key, existing);
          } else {
            dedupHits++;
          }
          frameIndex = existing;
        }
        if (result.put(image.id(), frameIndex) != null) {
          throw new IllegalArgumentException(
              "duplicate draw buffer image id");
        }
      }
      return Map.copyOf(result);
    }

    private int append(
        IrisShadowReplayBufferSnapshot.BufferImage image) {
      if (inputs.size() >= IrisShadowReplayBufferSnapshot.MAX_IMAGES) {
        throw new IllegalArgumentException("graph input buffer capacity");
      }
      int index = inputs.size();
      inputs.add(new IrisMetalGraphFramePacketEncoder.InputBuffer(index,
          image));
      return index;
    }

    private List<IrisMetalGraphFramePacketEncoder.InputBuffer> inputs() {
      return List.copyOf(inputs);
    }

    private void logExactDiagnostic() {
      if (System.getProperty("metalrender.exactJar.expectedPath") == null) {
        return;
      }
      long frame = EXACT_BUFFER_DIAGNOSTIC_FRAMES.incrementAndGet();
      if (frame != 1 && frame
          % IrisTranslationCoordinator.FULL_GRAPH_TELEMETRY_INTERVAL_FRAMES
          != 0) {
        return;
      }
      long uniqueBytes = inputs.stream().mapToLong(input ->
          input.image().byteLength()).sum();
      long inlineBytes = inputs.stream()
          .filter(input -> !input.image().shared())
          .mapToLong(input -> input.image().byteLength()).sum();
      long sharedBytes = uniqueBytes - inlineBytes;
      long synthetic = inputs.stream()
          .filter(input -> input.image().glBuffer() == 0).count();
      String top = inputs.stream().sorted(Comparator.comparingInt(
              (IrisMetalGraphFramePacketEncoder.InputBuffer input) ->
                  input.image().byteLength()).reversed())
          .limit(12).map(input -> {
            IrisShadowReplayBufferSnapshot.BufferImage image =
                input.image();
            return "i" + input.bufferId() + ":b" + image.glBuffer()
                + "/g" + image.generation() + '@'
                + image.sourceOffsetBytes() + '+' + image.byteLength()
                + (image.shared() ? "/shared" : "/inline");
          }).reduce((left, right) -> left + "," + right).orElse("none");
      MetalLogger.info(
          "Iris full Metal frame buffers: refs=%d refBytes=%.2fMiB unique=%d uniqueBytes=%.2fMiB inline=%.2fMiB shared=%.2fMiB dedupHits=%d synthetic=%d top=%s",
          references, referencedBytes / (1024.0 * 1024.0), inputs.size(),
          uniqueBytes / (1024.0 * 1024.0),
          inlineBytes / (1024.0 * 1024.0),
          sharedBytes / (1024.0 * 1024.0), dedupHits, synthetic, top);
    }
  }

  /** Deduplicates immutable non-graph sampled textures once per MGF frame. */
  private static final class FrameInputTextureRegistry {
    private final LinkedHashMap<FrameInputTextureKey, Integer> byKey =
        new LinkedHashMap<>();
    private final ArrayList<IrisMetalGraphFramePacketEncoder.InputTexture>
        inputs = new ArrayList<>();
    private long references;
    private long referencedBytes;
    private long dedupHits;
    private long sharedReferences;
    private boolean sharedInputObserved;

    private Map<Integer, Integer> register(
        List<IrisGlTextureMirror.TextureSnapshot> images,
        Set<Integer> graphTextureNames) {
      LinkedHashMap<Integer, Integer> result = new LinkedHashMap<>();
      for (IrisGlTextureMirror.TextureSnapshot image : images) {
        if (graphTextureNames.contains(image.texture())) {
          continue;
        }
        if (image.graphReference()) {
          throw unsupported("graph-frame-texture-generation-stale");
        }
        if (image.shared()) {
          sharedInputObserved = true;
          sharedReferences++;
          continue;
        }
        references++;
        referencedBytes = Math.addExact(referencedBytes,
            image.byteLength());
        FrameInputTextureKey key = new FrameInputTextureKey(
            image.texture(), image.generation(), image.format(),
            image.width(), image.height(), image.layer(), image.mipLevel(),
            image.bytesPerPixel());
        Integer existing = byKey.get(key);
        if (existing == null) {
          existing = append(image);
          byKey.put(key, existing);
        } else {
          dedupHits++;
        }
        if (result.put(image.texture(), existing) != null) {
          throw new IllegalArgumentException(
              "duplicate draw input texture name");
        }
      }
      return Map.copyOf(result);
    }

    private int append(IrisGlTextureMirror.TextureSnapshot image) {
      if (inputs.size() >= IrisShadowReplayTextureSnapshot.MAX_TEXTURES) {
        throw new IllegalArgumentException("graph input texture capacity");
      }
      int index = inputs.size();
      inputs.add(new IrisMetalGraphFramePacketEncoder.InputTexture(index,
          image));
      return index;
    }

    private List<IrisMetalGraphFramePacketEncoder.InputTexture> inputs() {
      return List.copyOf(inputs);
    }

    private boolean renderThreadSubmissionRequired() {
      // Native IOSurface inputs are frame-slotted, fence-complete snapshots.
      // Their token table is process-wide and every slot remains immutable
      // until the Metal submission feedback retires, so JNI submission no
      // longer needs the CGL render thread even when this frame uses them.
      return false;
    }

    private void logExactDiagnostic() {
      if (System.getProperty("metalrender.exactJar.expectedPath") == null) {
        return;
      }
      long frame = EXACT_BUFFER_DIAGNOSTIC_FRAMES.get();
      if (frame != 1 && frame
          % IrisTranslationCoordinator.FULL_GRAPH_TELEMETRY_INTERVAL_FRAMES
          != 0) {
        return;
      }
      long uniqueBytes = inputs.stream().mapToLong(input ->
          input.image().byteLength()).sum();
      String top = inputs.stream().sorted(Comparator.comparingInt(
              (IrisMetalGraphFramePacketEncoder.InputTexture input) ->
                  input.image().byteLength()).reversed())
          .limit(12).map(input -> {
            IrisGlTextureMirror.TextureSnapshot image = input.image();
            return "i" + input.textureId() + ":t" + image.texture()
                + "/g" + image.generation() + '/' + image.format() + '/'
                + image.width() + 'x' + image.height() + '/'
                + image.bytesPerPixel() + "bpp/"
                + (image.graphReference() ? "graph"
                    : image.shared() ? "shared" : "inline");
          }).reduce((left, right) -> left + "," + right).orElse("none");
      MetalLogger.info(
          "Iris full Metal frame textures: refs=%d sharedRefs=%d refBytes=%.2fMiB unique=%d uniqueBytes=%.2fMiB dedupHits=%d top=%s",
          references, sharedReferences,
          referencedBytes / (1024.0 * 1024.0), inputs.size(),
          uniqueBytes / (1024.0 * 1024.0), dedupHits, top);
    }
  }

  private record FrameInputBufferKey(int glBuffer, long generation,
                                     long sourceOffsetBytes,
                                     int byteLength, long sharedHandle) {
  }

  private record FrameInputTextureKey(int glTexture, long generation,
                                      String format, int width, int height,
                                      int layer, int mipLevel,
                                      int bytesPerPixel) {
  }

  private record TargetExtent(int width, int height) {
  }

  private record DrawTargets(
      List<IrisMetalGraphFramePacketEncoder.ColorTarget> colors,
      int depthResourceId, int stencilResourceId,
      Set<Integer> allTargets) {
    private DrawTargets {
      colors = List.copyOf(colors);
      allTargets = Set.copyOf(allTargets);
    }
  }

  private static final class UnsupportedFrame extends RuntimeException {
    private final String reason;

    private UnsupportedFrame(String reason) {
      super(null, null, false, false);
      requireReason(reason);
      this.reason = reason;
    }
  }

  private static void requireReason(String reason) {
    Objects.requireNonNull(reason, "reason");
    if (reason.isBlank() || reason.length() > 128
        || reason.indexOf('\n') >= 0 || reason.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("invalid graph frame blocker");
    }
  }
}

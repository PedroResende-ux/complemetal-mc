package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.NodeKind;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Phase;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.ResourceUse;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One transient frame replay plan. Unlike {@link IrisRenderGraph}, this may
 * contain generation-qualified live GL bindings and is never persisted or
 * included in a content key.
 */
public record IrisRenderExecutionPlan(IrisRenderGraph graph,
                                      List<Step> steps,
                                      List<ResourceBinding> resourceBindings) {
  public IrisRenderExecutionPlan {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(steps, "steps");
    Objects.requireNonNull(resourceBindings, "resourceBindings");
    if (steps.isEmpty() || steps.size() > IrisRenderGraph.MAX_NODES * 8L) {
      throw new IllegalArgumentException("invalid execution step count");
    }
    ArrayList<Step> copy = new ArrayList<>(steps.size());
    for (int index = 0; index < steps.size(); index++) {
      Step step = Objects.requireNonNull(steps.get(index), "step");
      if (step.sequence() != index || step.nodeId() >= graph.nodes().size()) {
        throw new IllegalArgumentException("invalid execution step identity");
      }
      IrisRenderGraph.Node node = graph.nodes().get(step.nodeId());
      if (step.kind() != node.kind() || step.phase() != node.phase()) {
        throw new IllegalArgumentException("execution step/graph mismatch");
      }
      copy.add(step);
    }
    steps = List.copyOf(copy);
    boolean[] occupied = new boolean[graph.resources().size()];
    ArrayList<ResourceBinding> bindings = new ArrayList<>(
        resourceBindings.size());
    for (ResourceBinding binding : resourceBindings) {
      Objects.requireNonNull(binding, "resource binding");
      if (binding.resourceId() >= occupied.length
          || occupied[binding.resourceId()]) {
        throw new IllegalArgumentException("invalid resource binding id");
      }
      IrisRenderGraph.Resource graphResource = graph.resources()
          .get(binding.resourceId());
      boolean texture = binding.handle().kind()
          == IrisGlStateSnapshot.ResourceKind.TEXTURE;
      if (texture != (graphResource.kind()
          == IrisRenderGraph.ResourceKind.TEXTURE)) {
        throw new IllegalArgumentException("resource binding kind mismatch");
      }
      occupied[binding.resourceId()] = true;
      bindings.add(binding);
    }
    bindings.sort(java.util.Comparator.comparingInt(
        ResourceBinding::resourceId));
    resourceBindings = List.copyOf(bindings);
  }

  public IrisRenderExecutionPlan(IrisRenderGraph graph, List<Step> steps) {
    this(graph, steps, List.of());
  }

  public List<String> structuralBlockers() {
    ArrayList<String> blockers = new ArrayList<>();
    if (resourceBindings.size() != graph.resources().size()) {
      addDistinct(blockers, "resource-identities-incomplete");
    }
    for (IrisRenderGraph.Resource resource : graph.resources()) {
      if ("runtime-texture".equals(resource.format())
          || "framebuffer".equals(resource.format())
          || resource.kind() == IrisRenderGraph.ResourceKind.TEXTURE
          && !resource.allocationComplete()) {
        addDistinct(blockers, "resource-metadata-incomplete");
      }
    }
    for (Step step : steps) {
      if (step instanceof TransferStep transfer
          && !transfer.command().complete()) {
        addDistinct(blockers, "transfer-parameters-incomplete");
      }
      if (!(step instanceof PipelineStep pipeline)) {
        continue;
      }
      IrisExecutionCommand command = pipeline.pending().command();
      if (command instanceof IrisExecutionCommand.UnknownDraw
          || command instanceof IrisExecutionCommand.UnknownDispatch) {
        addDistinct(blockers, "execution-arguments-unknown");
      } else if (command instanceof IrisExecutionCommand.DrawIndexed draw
          && draw.indexElementBytes() == 1) {
        addDistinct(blockers, "uint8-indices-require-expansion");
      } else if (command instanceof IrisExecutionCommand.MultiDrawIndexed draw
          && draw.indexElementBytes() == 1) {
        addDistinct(blockers, "uint8-indices-require-expansion");
      } else if (command instanceof IrisExecutionCommand.IndirectDraw) {
        addDistinct(blockers, "indirect-buffer-mirroring-required");
      } else if (command instanceof IrisExecutionCommand.IndirectDispatch) {
        addDistinct(blockers, "indirect-buffer-mirroring-required");
      }
      if (pipeline.pending().resourceBindings() == null) {
        addDistinct(blockers, "resource-bindings-unavailable");
      }
      if (!pipeline.pending().dynamicState().completeFor(
          pipeline.pending().snapshot().operation())) {
        addDistinct(blockers, "dynamic-draw-state-incomplete");
      }
      boolean needsVertexBuffers = !pipeline.pending().registration()
          .descriptor().vertexAttributes().isEmpty();
      boolean indexed = command instanceof IrisExecutionCommand.DrawIndexed
          || command instanceof IrisExecutionCommand.MultiDrawIndexed
          || command instanceof IrisExecutionCommand.IndirectDraw draw
          && draw.indexElementBytes() != 0;
      IrisVertexInputBindings inputs =
          pipeline.pending().vertexInputBindings();
      if ((needsVertexBuffers || indexed) && !inputs.complete()) {
        addDistinct(blockers, "vertex-input-bindings-incomplete");
      } else if (needsVertexBuffers && inputs.vertexBuffers().isEmpty()) {
        addDistinct(blockers, "vertex-buffers-unavailable");
      } else if (indexed && inputs.indexBuffer().isEmpty()
          && pipeline.pending().replayBuffers().indexBuffer().isEmpty()) {
        addDistinct(blockers, "index-buffer-unavailable");
      }
    }
    blockers.sort(String::compareTo);
    return List.copyOf(blockers);
  }

  public boolean structurallyComplete() {
    return structuralBlockers().isEmpty();
  }

  /** Distinct fail-closed reasons from synchronous draw-time buffer copies. */
  public List<String> bufferCaptureBlockers() {
    ArrayList<String> blockers = new ArrayList<>();
    boolean sampled = false;
    for (Step step : steps) {
      if (!(step instanceof PipelineStep pipeline)) {
        continue;
      }
      IrisShadowReplayBufferSnapshot snapshot =
          pipeline.pending().replayBuffers();
      if (snapshot.captureEnabled()) {
        sampled = true;
        snapshot.blockers().forEach(reason -> addDistinct(blockers, reason));
      }
    }
    if (!sampled) {
      addDistinct(blockers, "shadow-buffer-capture-has-no-samples");
    }
    blockers.sort(String::compareTo);
    return List.copyOf(blockers);
  }

  public boolean bufferCaptureEnabled() {
    for (Step step : steps) {
      if (step instanceof PipelineStep pipeline) {
        if (pipeline.pending().replayBuffers().captureEnabled()) {
          return true;
        }
      }
    }
    return false;
  }

  public boolean bufferCaptureComplete() {
    return bufferCaptureEnabled() && bufferCaptureBlockers().isEmpty();
  }

  public long capturedBufferImages() {
    return steps.stream()
        .filter(PipelineStep.class::isInstance)
        .map(PipelineStep.class::cast)
        .mapToLong(step -> step.pending().replayBuffers().images().size())
        .sum();
  }

  public long capturedBufferBytes() {
    return steps.stream()
        .filter(PipelineStep.class::isInstance)
        .map(PipelineStep.class::cast)
        .mapToLong(step -> step.pending().replayBuffers().totalBytes())
        .sum();
  }

  private static void addDistinct(List<String> values, String value) {
    if (!values.contains(value)) {
      values.add(value);
    }
  }

  public record ResourceBinding(int resourceId, ResourceHandle handle) {
    public ResourceBinding {
      if (resourceId < 0) {
        throw new IllegalArgumentException("negative graph resource id");
      }
      Objects.requireNonNull(handle, "handle");
    }
  }

  public sealed interface Step permits PipelineStep, ClearStep, BarrierStep,
      TransferStep {
    int sequence();

    int nodeId();

    NodeKind kind();

    Phase phase();
  }

  public record ClearStep(int sequence, int nodeId, Phase phase,
                          IrisClearCommand command,
                          List<ResourceUse> resources) implements Step {
    public ClearStep {
      if (sequence < 0 || nodeId < 0) {
        throw new IllegalArgumentException("invalid clear step");
      }
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(command, "command");
      resources = List.copyOf(resources);
      if (resources.isEmpty()
          || resources.stream().anyMatch(use -> !use.access().writes())) {
        throw new IllegalArgumentException("clear needs writable resources");
      }
    }

    @Override
    public NodeKind kind() {
      return NodeKind.CLEAR;
    }
  }

  public record PipelineStep(int sequence, int nodeId, NodeKind kind,
                             Phase phase,
                             IrisPipelineStateCapture.PendingState pending)
      implements Step {
    public PipelineStep {
      if (sequence < 0 || nodeId < 0
          || (kind != NodeKind.DRAW && kind != NodeKind.DISPATCH)) {
        throw new IllegalArgumentException("invalid pipeline step");
      }
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(pending, "pending");
    }
  }

  public record BarrierStep(int sequence, int nodeId, Phase phase,
                            int barrierBits) implements Step {
    public BarrierStep {
      if (sequence < 0 || nodeId < 0 || barrierBits < 0) {
        throw new IllegalArgumentException("invalid barrier step");
      }
      Objects.requireNonNull(phase, "phase");
    }

    @Override
    public NodeKind kind() {
      return NodeKind.BARRIER;
    }
  }

  public record TransferStep(int sequence, int nodeId, NodeKind kind,
                             Phase phase, List<ResourceUse> resources,
                             IrisTransferCommand command)
      implements Step {
    public TransferStep {
      if (sequence < 0 || nodeId < 0 || !kind.transfer()) {
        throw new IllegalArgumentException("invalid transfer step");
      }
      Objects.requireNonNull(phase, "phase");
      resources = List.copyOf(resources);
      Objects.requireNonNull(command, "command");
      if (command.kind() != kind) {
        throw new IllegalArgumentException("transfer command/kind mismatch");
      }
    }

    public TransferStep(int sequence, int nodeId, NodeKind kind,
        Phase phase, List<ResourceUse> resources) {
      this(sequence, nodeId, kind, phase, resources,
          new IrisTransferCommand.Unknown(kind));
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Access;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Edge;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Node;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.NodeKind;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Resource;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.ResourceUse;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture.RawBarrier;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture.RawClear;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture.RawDraw;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture.RawEvent;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture.RawResource;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture.RawTransfer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves a raw frame into a content-keyed, topology-compressed graph. */
public final class IrisRenderGraphBuilder {
  private static final String ZERO_SHA256 = "0".repeat(64);
  private final IrisSpecializationStateReader specializationReader;
  private final Map<String, IrisSpecializationStateReader.Result>
      specializationCache = new HashMap<>();

  public IrisRenderGraphBuilder(
      IrisSpecializationStateReader specializationReader) {
    this.specializationReader = Objects.requireNonNull(
        specializationReader, "specializationReader");
  }

  public Result build(IrisRenderGraphCapture.PendingFrame frame) {
    Objects.requireNonNull(frame, "frame");
    LinkedHashMap<ResourceHandle, MutableResource> resources =
        new LinkedHashMap<>();
    LinkedHashMap<NodeSignature, Integer> nodeIds = new LinkedHashMap<>();
    ArrayList<Node> nodes = new ArrayList<>();
    ArrayList<IrisRenderExecutionPlan.Step> executionSteps =
        new ArrayList<>(frame.events().size());
    LinkedHashSet<Edge> edges = new LinkedHashSet<>();
    Integer previous = null;
    for (RawEvent event : frame.events()) {
      ResolvedNode resolved;
      if (event instanceof RawDraw draw) {
        BuildNodeResult result = draw(draw, resources);
        if (result instanceof UnsupportedNode unsupported) {
          return new Unsupported(unsupported.reason());
        }
        resolved = ((CompleteNode) result).node();
      } else if (event instanceof RawClear clear) {
        resolved = new ResolvedNode(NodeKind.CLEAR, clear.phase(),
            ZERO_SHA256, ZERO_SHA256, 0,
            uses(List.of(), clear.writes(), resources));
      } else if (event instanceof RawBarrier barrier) {
        resolved = new ResolvedNode(NodeKind.BARRIER, barrier.phase(),
            ZERO_SHA256, ZERO_SHA256, barrier.bits(), List.of());
      } else {
        resolved = transfer((RawTransfer) event, resources);
      }
      NodeSignature signature = new NodeSignature(resolved.kind(),
          resolved.phase(), resolved.shaderKey(), resolved.pipelineKey(),
          resolved.barrierBits(), resolved.uses());
      Integer nodeId = nodeIds.get(signature);
      if (nodeId == null) {
        nodeId = nodes.size();
        nodeIds.put(signature, nodeId);
        nodes.add(new Node(nodeId, resolved.kind(), resolved.phase(),
            resolved.shaderKey(), resolved.pipelineKey(),
            resolved.barrierBits(), resolved.uses()));
      }
      if (previous != null && previous.intValue() != nodeId) {
        edges.add(new Edge(previous, nodeId));
      }
      int sequence = executionSteps.size();
      if (event instanceof RawDraw draw) {
        executionSteps.add(new IrisRenderExecutionPlan.PipelineStep(sequence,
            nodeId, resolved.kind(), resolved.phase(), draw.pending()));
      } else if (event instanceof RawClear clear) {
        executionSteps.add(new IrisRenderExecutionPlan.ClearStep(sequence,
            nodeId, resolved.phase(), clear.command(), resolved.uses()));
      } else if (event instanceof RawBarrier barrier) {
        executionSteps.add(new IrisRenderExecutionPlan.BarrierStep(sequence,
            nodeId, resolved.phase(), barrier.bits()));
      } else {
        executionSteps.add(new IrisRenderExecutionPlan.TransferStep(sequence,
            nodeId, resolved.kind(), resolved.phase(), resolved.uses(),
            ((RawTransfer) event).command()));
      }
      previous = nodeId;
    }
    ArrayList<Resource> finalResources = new ArrayList<>(resources.size());
    ArrayList<IrisRenderExecutionPlan.ResourceBinding> resourceBindings =
        new ArrayList<>(resources.size());
    for (MutableResource resource : resources.values()) {
      finalResources.add(resource.freeze());
      resourceBindings.add(new IrisRenderExecutionPlan.ResourceBinding(
          resource.id, resource.handle));
    }
    if (nodes.isEmpty()) {
      return new Unsupported("render-graph-has-no-nodes");
    }
    IrisRenderGraph graph = new IrisRenderGraph(finalResources, nodes,
        List.copyOf(edges));
    return new Complete(graph,
        new IrisRenderExecutionPlan(graph, executionSteps,
            resourceBindings));
  }

  private BuildNodeResult draw(RawDraw draw,
      LinkedHashMap<ResourceHandle, MutableResource> resources) {
    IrisProgramIdentityRegistry.ResolvedProgram program = draw.pending()
        .registration().resolved().orElse(null);
    if (program == null) {
      return new UnsupportedNode("graph-program-identity-unresolved");
    }
    IrisSpecializationStateReader.Result specialization =
        specializationCache.computeIfAbsent(program.shaderKey().sha256(),
            ignored -> specializationReader.read(program));
    if (specialization
        instanceof IrisSpecializationStateReader.Unsupported unsupported) {
      return new UnsupportedNode(
          "graph-specialization-" + unsupported.reason());
    }
    IrisPipelineStateMapper.Result mapping = IrisPipelineStateMapper.map(
        draw.pending().snapshot(), program,
        ((IrisSpecializationStateReader.Complete) specialization)
            .constants());
    if (mapping instanceof IrisPipelineStateMapper.Unsupported unsupported) {
      return new UnsupportedNode("graph-pipeline-" + unsupported.reason());
    }
    IrisPipelineState state =
        ((IrisPipelineStateMapper.Complete) mapping).state();
    List<ResourceUse> uses = uses(draw.reads(), draw.writes(), resources);
    NodeKind kind = draw.pending().snapshot().operation()
        == IrisGlStateSnapshot.Operation.DISPATCH
        ? NodeKind.DISPATCH : NodeKind.DRAW;
    return new CompleteNode(new ResolvedNode(kind, draw.phase(),
        program.shaderKey().sha256(),
        IrisPipelineStateKey.from(program.shaderKey(), state).sha256(),
        0, uses));
  }

  private static ResolvedNode transfer(RawTransfer transfer,
      LinkedHashMap<ResourceHandle, MutableResource> resources) {
    List<ResourceUse> uses = uses(transfer.sources(),
        transfer.destinations(), resources);
    return new ResolvedNode(transfer.kind(), transfer.phase(), ZERO_SHA256,
        ZERO_SHA256, 0, uses);
  }

  private static List<ResourceUse> uses(List<RawResource> reads,
      List<RawResource> writes,
      LinkedHashMap<ResourceHandle, MutableResource> resources) {
    HashMap<Integer, Access> access = new HashMap<>();
    for (RawResource read : reads) {
      access.put(resource(read, resources), Access.READ);
    }
    for (RawResource write : writes) {
      access.merge(resource(write, resources), Access.WRITE,
          (oldValue, ignored) -> oldValue == Access.READ
              ? Access.READ_WRITE : oldValue);
    }
    return access.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> new ResourceUse(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static int resource(RawResource raw,
      LinkedHashMap<ResourceHandle, MutableResource> resources) {
    MutableResource resource = resources.get(raw.handle());
    if (resource == null) {
      resource = new MutableResource(resources.size(), resourceKind(raw),
          raw.format(), raw.sampleCount(), raw.width(), raw.height(),
          raw.depthOrLayers(), raw.mipLevels(), raw.handle());
      resources.put(raw.handle(), resource);
    } else {
      resource.merge(raw);
    }
    return resource.id;
  }

  private static ResourceKind resourceKind(RawResource raw) {
    return raw.handle().kind() == IrisGlStateSnapshot.ResourceKind.FRAMEBUFFER
        ? ResourceKind.FRAMEBUFFER : ResourceKind.TEXTURE;
  }

  public sealed interface Result permits Complete, Unsupported {
  }

  public record Complete(IrisRenderGraph graph,
                         IrisRenderExecutionPlan executionPlan)
      implements Result {
    public Complete {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(executionPlan, "executionPlan");
      if (executionPlan.graph() != graph) {
        throw new IllegalArgumentException("execution plan graph mismatch");
      }
    }
  }

  public record Unsupported(String reason) implements Result {
    public Unsupported {
      Objects.requireNonNull(reason, "reason");
    }
  }

  private sealed interface BuildNodeResult
      permits CompleteNode, UnsupportedNode {
  }

  private record CompleteNode(ResolvedNode node) implements BuildNodeResult {
  }

  private record UnsupportedNode(String reason) implements BuildNodeResult {
  }

  private record ResolvedNode(NodeKind kind, IrisRenderGraph.Phase phase,
                              String shaderKey, String pipelineKey,
                              int barrierBits, List<ResourceUse> uses) {
  }

  private record NodeSignature(NodeKind kind, IrisRenderGraph.Phase phase,
                               String shaderKey, String pipelineKey,
                               int barrierBits, List<ResourceUse> uses) {
  }

  private static final class MutableResource {
    private final int id;
    private final ResourceHandle handle;
    private final ResourceKind kind;
    private String format;
    private int sampleCount;
    private int width;
    private int height;
    private int depthOrLayers;
    private int mipLevels;

    private MutableResource(int id, ResourceKind kind, String format,
                            int sampleCount, int width, int height,
                            int depthOrLayers, int mipLevels,
                            ResourceHandle handle) {
      this.id = id;
      this.handle = Objects.requireNonNull(handle, "handle");
      this.kind = kind;
      this.format = format;
      this.sampleCount = sampleCount;
      this.width = width;
      this.height = height;
      this.depthOrLayers = depthOrLayers;
      this.mipLevels = mipLevels;
    }

    private void merge(RawResource raw) {
      if ("runtime-texture".equals(format)
          && !"runtime-texture".equals(raw.format())) {
        format = raw.format();
      }
      if (sampleCount == 0 && raw.sampleCount() > 0) {
        sampleCount = raw.sampleCount();
      }
      if (width == 0 && raw.width() > 0) {
        width = raw.width();
        height = raw.height();
        depthOrLayers = raw.depthOrLayers();
        mipLevels = raw.mipLevels();
      } else if (raw.width() > 0 && (width != raw.width()
          || height != raw.height()
          || depthOrLayers != raw.depthOrLayers()
          || mipLevels != raw.mipLevels())) {
        width = height = depthOrLayers = mipLevels = 0;
      }
    }

    private Resource freeze() {
      return new Resource(id, kind, format, sampleCount, width, height,
          depthOrLayers, mipLevels);
    }
  }
}

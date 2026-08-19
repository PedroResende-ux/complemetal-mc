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
      if (previous != null) {
        edges.add(new Edge(previous, nodeId));
      }
      previous = nodeId;
    }
    ArrayList<Resource> finalResources = new ArrayList<>(resources.size());
    for (MutableResource resource : resources.values()) {
      finalResources.add(resource.freeze());
    }
    if (nodes.isEmpty()) {
      return new Unsupported("render-graph-has-no-nodes");
    }
    return new Complete(new IrisRenderGraph(finalResources, nodes,
        List.copyOf(edges)));
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
    ArrayList<ResourceUse> uses = new ArrayList<>(2);
    if (transfer.source() != null) {
      uses.add(new ResourceUse(resource(transfer.source(), resources),
          Access.READ));
    }
    if (transfer.destination() != null) {
      int destination = resource(transfer.destination(), resources);
      int existing = -1;
      for (int index = 0; index < uses.size(); index++) {
        if (uses.get(index).resourceId() == destination) {
          existing = index;
          break;
        }
      }
      if (existing >= 0) {
        uses.set(existing, new ResourceUse(destination, Access.READ_WRITE));
      } else {
        uses.add(new ResourceUse(destination, Access.WRITE));
      }
    }
    uses.sort(java.util.Comparator.comparingInt(ResourceUse::resourceId));
    return new ResolvedNode(transfer.kind(), transfer.phase(), ZERO_SHA256,
        ZERO_SHA256, 0, List.copyOf(uses));
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
          raw.format(), raw.sampleCount());
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

  public record Complete(IrisRenderGraph graph) implements Result {
    public Complete {
      Objects.requireNonNull(graph, "graph");
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
    private final ResourceKind kind;
    private String format;
    private int sampleCount;

    private MutableResource(int id, ResourceKind kind, String format,
                            int sampleCount) {
      this.id = id;
      this.kind = kind;
      this.format = format;
      this.sampleCount = sampleCount;
    }

    private void merge(RawResource raw) {
      if ("runtime-texture".equals(format)
          && !"runtime-texture".equals(raw.format())) {
        format = raw.format();
      }
      if (sampleCount == 0 && raw.sampleCount() > 0) {
        sampleCount = raw.sampleCount();
      }
    }

    private Resource freeze() {
      return new Resource(id, kind, format, sampleCount);
    }
  }
}

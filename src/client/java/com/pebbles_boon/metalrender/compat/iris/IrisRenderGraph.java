package com.pebbles_boon.metalrender.compat.iris;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, GL-name-free topology of one captured Iris frame. */
public record IrisRenderGraph(List<Resource> resources, List<Node> nodes,
                              List<Edge> edges) {
  public static final int MAX_RESOURCES = 16_384;
  public static final int MAX_NODES = 32_768;
  public static final int MAX_EDGES = 65_536;

  public IrisRenderGraph {
    resources = sorted(resources, Comparator.comparingInt(Resource::id),
        MAX_RESOURCES, "resource");
    nodes = sorted(nodes, Comparator.comparingInt(Node::id), MAX_NODES,
        "node");
    edges = sorted(edges, Comparator.comparingInt(Edge::fromNode)
        .thenComparingInt(Edge::toNode), MAX_EDGES, "edge");
    requireDense(resources.stream().mapToInt(Resource::id).toArray(),
        "resource");
    requireDense(nodes.stream().mapToInt(Node::id).toArray(), "node");
    for (Node node : nodes) {
      for (ResourceUse use : node.resources()) {
        if (use.resourceId() >= resources.size()) {
          throw new IllegalArgumentException(
              "node references an absent graph resource");
        }
      }
    }
    Set<Long> uniqueEdges = new HashSet<>();
    for (Edge edge : edges) {
      if (edge.fromNode() >= nodes.size() || edge.toNode() >= nodes.size()) {
        throw new IllegalArgumentException("edge references an absent node");
      }
      long key = ((long) edge.fromNode() << 32)
          | Integer.toUnsignedLong(edge.toNode());
      if (!uniqueEdges.add(key)) {
        throw new IllegalArgumentException("duplicate graph edge");
      }
    }
  }

  public IrisRenderGraphKey key() {
    return IrisRenderGraphKey.from(this);
  }

  public EnumSet<Phase> phases() {
    EnumSet<Phase> phases = EnumSet.noneOf(Phase.class);
    nodes.forEach(node -> phases.add(node.phase()));
    return phases;
  }

  public long barrierCount() {
    return nodes.stream().filter(node -> node.kind() == NodeKind.BARRIER)
        .count();
  }

  public long clearCount() {
    return nodes.stream().filter(node -> node.kind() == NodeKind.CLEAR)
        .count();
  }

  public long transferCount() {
    return nodes.stream().filter(node -> node.kind().transfer()).count();
  }

  public long pingPongResourceCount() {
    boolean[] read = new boolean[resources.size()];
    boolean[] written = new boolean[resources.size()];
    for (Node node : nodes) {
      for (ResourceUse use : node.resources()) {
        if (use.access().reads()) {
          read[use.resourceId()] = true;
        }
        if (use.access().writes()) {
          written[use.resourceId()] = true;
        }
      }
    }
    long count = 0;
    for (int index = 0; index < read.length; index++) {
      if (read[index] && written[index]) {
        count++;
      }
    }
    return count;
  }

  private static <T> List<T> sorted(List<T> input, Comparator<T> order,
      int maximum, String label) {
    Objects.requireNonNull(input, label + "s");
    if (input.size() > maximum) {
      throw new IllegalArgumentException(label + " count exceeds " + maximum);
    }
    ArrayList<T> copy = new ArrayList<>(input.size());
    for (T value : input) {
      copy.add(Objects.requireNonNull(value, label));
    }
    copy.sort(order);
    return List.copyOf(copy);
  }

  private static void requireDense(int[] values, String label) {
    for (int index = 0; index < values.length; index++) {
      if (values[index] != index) {
        throw new IllegalArgumentException(label + " ids must be dense");
      }
    }
  }

  public record Resource(int id, ResourceKind kind, String format,
                         int sampleCount, int width, int height,
                         int depthOrLayers, int mipLevels) {
    public Resource(int id, ResourceKind kind, String format,
        int sampleCount) {
      this(id, kind, format, sampleCount, 0, 0, 0, 0);
    }

    public Resource {
      if (id < 0 || sampleCount < 0 || width < 0 || height < 0
          || depthOrLayers < 0 || mipLevels < 0) {
        throw new IllegalArgumentException("invalid graph resource");
      }
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(format, "format");
      if (format.isBlank() || format.length() > 128) {
        throw new IllegalArgumentException("invalid graph resource format");
      }
    }

    public boolean allocationComplete() {
      return kind == ResourceKind.TEXTURE && sampleCount > 0 && width > 0
          && height > 0 && depthOrLayers > 0 && mipLevels > 0;
    }
  }

  public record Node(int id, NodeKind kind, Phase phase,
                     String shaderKeySha256, String pipelineKeySha256,
                     int barrierBits, List<ResourceUse> resources) {
    public Node {
      if (id < 0 || barrierBits < 0) {
        throw new IllegalArgumentException("invalid graph node");
      }
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(phase, "phase");
      requireSha(shaderKeySha256, "shaderKeySha256");
      requireSha(pipelineKeySha256, "pipelineKeySha256");
      resources = sorted(resources, Comparator
          .comparingInt(ResourceUse::resourceId)
          .thenComparing(ResourceUse::access), MAX_RESOURCES,
          "resource use");
      if (kind != NodeKind.BARRIER && barrierBits != 0) {
        throw new IllegalArgumentException(
            "only barrier nodes can contain barrier bits");
      }
    }
  }

  public record ResourceUse(int resourceId, Access access) {
    public ResourceUse {
      if (resourceId < 0) {
        throw new IllegalArgumentException("negative resource id");
      }
      Objects.requireNonNull(access, "access");
    }
  }

  public record Edge(int fromNode, int toNode) {
    public Edge {
      if (fromNode < 0 || toNode < 0) {
        throw new IllegalArgumentException("negative graph edge");
      }
    }
  }

  public enum ResourceKind {
    TEXTURE,
    FRAMEBUFFER
  }

  public enum Access {
    READ(true, false),
    WRITE(false, true),
    READ_WRITE(true, true);

    private final boolean reads;
    private final boolean writes;

    Access(boolean reads, boolean writes) {
      this.reads = reads;
      this.writes = writes;
    }

    public boolean reads() {
      return reads;
    }

    public boolean writes() {
      return writes;
    }
  }

  public enum NodeKind {
    DRAW(false),
    DISPATCH(false),
    CLEAR(false),
    BARRIER(false),
    BLIT(true),
    COPY_TEXTURE(true),
    GENERATE_MIPMAPS(true);

    private final boolean transfer;

    NodeKind(boolean transfer) {
      this.transfer = transfer;
    }

    public boolean transfer() {
      return transfer;
    }
  }

  public enum Phase {
    BEGIN,
    SHADOW,
    PREPARE,
    GEOMETRY,
    DEFERRED,
    COMPOSITE,
    FINAL,
    COLORSPACE,
    UNKNOWN
  }

  static void requireSha(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be SHA-256");
    }
  }
}

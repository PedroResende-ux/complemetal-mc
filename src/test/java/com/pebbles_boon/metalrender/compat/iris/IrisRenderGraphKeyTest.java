package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisRenderGraphKeyTest {
  private static final String SHADER = "1".repeat(64);
  private static final String PIPELINE = "2".repeat(64);

  @Test
  void keyIsDeterministicAndSensitiveToDependencies() {
    IrisRenderGraph first = graph(List.of(new IrisRenderGraph.Edge(0, 1)));
    IrisRenderGraph same = graph(List.of(new IrisRenderGraph.Edge(0, 1)));
    IrisRenderGraph reversed = graph(List.of(new IrisRenderGraph.Edge(1, 0)));

    assertEquals(first.key(), same.key());
    assertNotEquals(first.key(), reversed.key());
  }

  @Test
  void identifiesResourcesReadAndWrittenAcrossTheGraph() {
    assertEquals(1, graph(List.of(new IrisRenderGraph.Edge(0, 1)))
        .pingPongResourceCount());
  }

  private static IrisRenderGraph graph(List<IrisRenderGraph.Edge> edges) {
    return new IrisRenderGraph(
        List.of(new IrisRenderGraph.Resource(0,
            IrisRenderGraph.ResourceKind.TEXTURE, "rgba16-float", 1)),
        List.of(
            new IrisRenderGraph.Node(0, IrisRenderGraph.NodeKind.DRAW,
                IrisRenderGraph.Phase.GEOMETRY, SHADER, PIPELINE, 0,
                List.of(new IrisRenderGraph.ResourceUse(0,
                    IrisRenderGraph.Access.WRITE))),
            new IrisRenderGraph.Node(1, IrisRenderGraph.NodeKind.DRAW,
                IrisRenderGraph.Phase.COMPOSITE, SHADER, PIPELINE, 0,
                List.of(new IrisRenderGraph.ResourceUse(0,
                    IrisRenderGraph.Access.READ)))),
        edges);
  }
}

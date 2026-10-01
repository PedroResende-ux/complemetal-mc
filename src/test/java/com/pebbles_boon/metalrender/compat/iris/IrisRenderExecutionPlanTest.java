package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisRenderExecutionPlanTest {
  private static final String SHADER = "1".repeat(64);
  private static final String PIPELINE = "2".repeat(64);

  @Test
  void keepsTransientArgumentsOutOfTheGraphIdentity() {
    IrisRenderExecutionPlan first = plan(
        new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL), List.of());
    IrisRenderExecutionPlan second = plan(
        new IrisExecutionCommand.DrawArrays(4, 7, 9, 2, 1,
            IrisExecutionCommand.Source.DIRECT_GL), List.of());

    assertEquals(first.graph().key(), second.graph().key());
    assertTrue(first.structurallyComplete());
    assertTrue(second.structurallyComplete());
  }

  @Test
  void doesNotRejectLegacyIndirectDispatchMarker() {
    IrisRenderExecutionPlan plan = plan(
        new IrisExecutionCommand.IndirectDispatch(0), List.of());
    assertTrue(plan.structurallyComplete());
    assertFalse(plan.structuralBlockers().contains(
        "indirect-buffer-mirroring-required"));
  }

  @Test
  void reportsReplayBlockersWithoutInventingMissingData() {
    IrisRenderGraph.Resource incomplete = new IrisRenderGraph.Resource(0,
        IrisRenderGraph.ResourceKind.TEXTURE, "runtime-texture", 0);
    IrisRenderExecutionPlan plan = plan(
        new IrisExecutionCommand.UnknownDraw(4), List.of(incomplete));
    assertFalse(plan.structurallyComplete());
    assertEquals(List.of("execution-arguments-unknown",
            "resource-identities-incomplete",
            "resource-metadata-incomplete"),
        plan.structuralBlockers());
  }

  @Test
  void acceptsSyntheticIndexBufferCreatedByPrimitiveExpansion() {
    IrisPrimitiveExpansion.Result expanded = IrisPrimitiveExpansion.expand(
        new IrisExecutionCommand.DrawArrays(0x0002, 0, 3, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL),
        new IrisShadowReplayBufferSnapshot(true, List.of(), List.of(),
            Optional.empty(), Optional.empty(), Map.of(), Map.of(),
            List.of()));

    IrisRenderExecutionPlan plan = plan(expanded.command(), List.of(),
        expanded.buffers());

    assertTrue(expanded.expanded());
    assertTrue(plan.structurallyComplete());
  }

  private static IrisRenderExecutionPlan plan(IrisExecutionCommand command,
      List<IrisRenderGraph.Resource> resources) {
    return plan(command, resources, IrisShadowReplayBufferSnapshot.disabled());
  }

  private static IrisRenderExecutionPlan plan(IrisExecutionCommand command,
      List<IrisRenderGraph.Resource> resources,
      IrisShadowReplayBufferSnapshot replayBuffers) {
    List<IrisRenderGraph.ResourceUse> uses = resources.isEmpty()
        ? List.of() : List.of(new IrisRenderGraph.ResourceUse(0,
            IrisRenderGraph.Access.READ));
    IrisRenderGraph.Node node = new IrisRenderGraph.Node(0,
        IrisRenderGraph.NodeKind.DRAW, IrisRenderGraph.Phase.FINAL,
        SHADER, PIPELINE, 0, uses);
    IrisRenderGraph graph = new IrisRenderGraph(resources, List.of(node),
        List.of());

    IrisGlStateTracker tracker = new IrisGlStateTracker();
    tracker.initializeOpenGlDefaults();
    tracker.registerProgram(9);
    tracker.useProgram(9);
    IrisProgramIdentityRegistry identities =
        new IrisProgramIdentityRegistry(2);
    IrisProgramIdentityRegistry.Registration registration =
        identities.register(9,
            new IrisProgramIdentityRegistry.ProgramDescriptor(
                PassKind.LINKED_GRAPHICS, "plan", false,
                List.of(new VertexBufferLayout(0, 12,
                    StepFunction.PER_VERTEX, 0)),
                List.of(new VertexAttribute(0, 0, 0,
                    new DataFormat("rgb32-float")))));
    IrisGlResourceBindingSnapshot bindings =
        new IrisGlResourceBindingSnapshot(9, Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    IrisPipelineStateCapture.PendingState pending =
        new IrisPipelineStateCapture.PendingState(registration,
            tracker.snapshotDraw(4), bindings, command,
            new IrisDynamicDrawState(
                IrisGlStateSnapshot.StateValue.known(
                    new IrisDynamicDrawState.Rect(0, 0, 16, 16)),
                IrisGlStateSnapshot.StateValue.known(false),
                IrisGlStateSnapshot.StateValue.known(
                    new IrisDynamicDrawState.Rect(0, 0, 0, 0))),
            IrisVertexInputBindings.complete(
                List.of(new IrisVertexInputBindings.BufferSlice(
                    0, 41, 0, 48)), null), replayBuffers);
    IrisRenderExecutionPlan.PipelineStep step =
        new IrisRenderExecutionPlan.PipelineStep(0, 0,
            IrisRenderGraph.NodeKind.DRAW, IrisRenderGraph.Phase.FINAL,
            pending);
    return new IrisRenderExecutionPlan(graph, List.of(step));
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceKind;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class IrisMetalGraphFramePlannerTest {
  @Test
  void scopesDiagnosticTextureCutToRequestedNode() {
    assertTrue(IrisMetalGraphFramePlanner.diagnosticTextureCutApplies(
        23, 23, 714, 714));
    assertFalse(IrisMetalGraphFramePlanner.diagnosticTextureCutApplies(
        22, 23, 714, 714));
    assertFalse(IrisMetalGraphFramePlanner.diagnosticTextureCutApplies(
        23, 23, 725, 714));
    assertTrue(IrisMetalGraphFramePlanner.diagnosticTextureCutApplies(
        22, -1, 714, 714));
  }

  @Test
  void detectsRepeatedFormatsBeforeBlitAttachmentPairing() {
    IrisRenderGraph.Resource first = new IrisRenderGraph.Resource(0,
        IrisRenderGraph.ResourceKind.TEXTURE, "rgba8-unorm", 1,
        16, 16, 1, 1);
    IrisRenderGraph.Resource second = new IrisRenderGraph.Resource(1,
        IrisRenderGraph.ResourceKind.TEXTURE, "rgba8-unorm", 1,
        16, 16, 1, 1);
    IrisRenderGraph.Resource distinct = new IrisRenderGraph.Resource(2,
        IrisRenderGraph.ResourceKind.TEXTURE, "rgba16-float", 1,
        16, 16, 1, 1);

    IrisRenderExecutionPlan plan = plan(
        new IrisExecutionCommand.DrawArrays(4, 0, 3, 1, 0,
            IrisExecutionCommand.Source.DIRECT_GL),
        List.of(first, second, distinct));

    assertTrue(IrisMetalGraphFramePlanner.hasRepeatedFormat(plan,
        List.of(0, 1)));
    assertFalse(IrisMetalGraphFramePlanner.hasRepeatedFormat(plan,
        List.of(0, 2)));
  }

  @Test
  void acceptsMatchingInitialTextureAcrossIndependentGenerationDomains() {
    IrisGlTextureMirror mirror = new IrisGlTextureMirror(4, 1_024, 1_024);
    assertTrue(mirror.define(31, "rgba8-unorm", 4, 2, 1, 1, 4));
    assertTrue(mirror.write(31, 0, 0, 0, 0, 4, 2, 4,
        ByteBuffer.allocate(32)));
    IrisGlTextureMirror.TextureSnapshot snapshot = mirror.snapshot(31,
        mirror.generation(31), 0, 0).orElseThrow();
    ResourceHandle object = new ResourceHandle(ResourceKind.TEXTURE,
        31, snapshot.generation() + 99, 9);
    IrisRenderGraph.Resource resource = new IrisRenderGraph.Resource(0,
        IrisRenderGraph.ResourceKind.TEXTURE, "rgba8-unorm", 1,
        4, 2, 1, 1);

    assertNotEquals(object.generation(), snapshot.generation());
    assertTrue(IrisMetalGraphFramePlanner.initialTextureSnapshotCompatible(
        snapshot, resource));
  }

  @Test
  void acceptsProvenRg11b10IosurfaceExpansionForGraphOverride() {
    IrisRenderGraph.Resource resource = new IrisRenderGraph.Resource(0,
        IrisRenderGraph.ResourceKind.TEXTURE, "rg11b10-float", 1,
        4, 2, 1, 1);
    IrisGlTextureMirror.TextureSnapshot expanded =
        IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(31, 7,
            "rgba16-float", 4, 2, 8, 99);

    assertTrue(IrisMetalGraphFramePlanner.textureOverrideCompatible(
        resource, expanded));
  }

  @Test
  void requiresInitialSnapshotOnlyForReflectedSampledTexture() {
    ResourceHandle texture = new ResourceHandle(ResourceKind.TEXTURE,
        724, 3, 9);

    assertFalse(IrisMetalGraphFramePlanner.initialTextureSnapshotRequired(
        texture, Set.of(713)));
    assertTrue(IrisMetalGraphFramePlanner.initialTextureSnapshotRequired(
        texture, Set.of(724)));
  }

  @Test
  void requiresInitialSnapshotForReflectedStorageImageTexture() {
    ResourceHandle texture = new ResourceHandle(ResourceKind.TEXTURE,
        724, 3, 9);

    assertTrue(IrisMetalGraphFramePlanner.initialTextureSnapshotRequired(
        texture, Set.of(), Set.of(724)));
    assertFalse(IrisMetalGraphFramePlanner.initialTextureSnapshotRequired(
        texture, Set.of(713), Set.of(713)));
  }

  @Test
  void plansExactFinalClearIntoPersistentMetalAttachment() {
    ResourceHandle texture = new ResourceHandle(ResourceKind.TEXTURE,
        31, 4, 9);
    IrisRenderGraph.Resource resource = new IrisRenderGraph.Resource(0,
        IrisRenderGraph.ResourceKind.TEXTURE, "rgba8-unorm", 1,
        64, 32, 1, 1);
    IrisRenderGraph.ResourceUse write = new IrisRenderGraph.ResourceUse(0,
        IrisRenderGraph.Access.WRITE);
    IrisRenderGraph.Node node = new IrisRenderGraph.Node(0,
        IrisRenderGraph.NodeKind.CLEAR, IrisRenderGraph.Phase.FINAL,
        "0".repeat(64), "0".repeat(64), 0, List.of(write));
    IrisRenderGraph graph = new IrisRenderGraph(List.of(resource),
        List.of(node), List.of());
    IrisClearCommand command = IrisClearCommand.colorFloat(texture, 0,
        0.1F, 0.2F, 0.3F, 1.0F, Optional.empty());
    IrisRenderExecutionPlan plan = new IrisRenderExecutionPlan(graph,
        List.of(new IrisRenderExecutionPlan.ClearStep(0, 0,
            IrisRenderGraph.Phase.FINAL, command, List.of(write))),
        List.of(new IrisRenderExecutionPlan.ResourceBinding(0, texture)));

    IrisMetalGraphFramePlanner.Complete complete = assertInstanceOf(
        IrisMetalGraphFramePlanner.Complete.class,
        IrisMetalGraphFramePlanner.build(plan,
            List.of(new NativeIrisMetalGraphResources.Binding(0, 77)),
            Set.of(), (pipeline, width, height) -> {
              throw new AssertionError("clear frame must not resolve a draw");
            }));

    assertEquals(9, complete.frame().contextGeneration());
    assertEquals(0, complete.readbackResourceId());
    assertEquals(Set.of(0), complete.writtenResourceIds());
    assertInstanceOf(IrisMetalGraphFramePacketEncoder.Clear.class,
        complete.frame().operations().getFirst());
  }

  @Test
  void keepsFinalRenderTargetAsPresentationAcrossTemporalCopy() {
    ResourceHandle output = new ResourceHandle(ResourceKind.TEXTURE,
        31, 4, 9);
    ResourceHandle temporal = new ResourceHandle(ResourceKind.TEXTURE,
        32, 5, 9);
    ResourceHandle framebuffer = new ResourceHandle(ResourceKind.FRAMEBUFFER,
        7, 2, 9);
    List<IrisRenderGraph.Resource> resources = List.of(
        new IrisRenderGraph.Resource(0, IrisRenderGraph.ResourceKind.TEXTURE,
            "rgba8-unorm", 1, 64, 32, 1, 1),
        new IrisRenderGraph.Resource(1, IrisRenderGraph.ResourceKind.TEXTURE,
            "rgba8-unorm", 1, 64, 32, 1, 1));
    IrisRenderGraph.ResourceUse outputWrite =
        new IrisRenderGraph.ResourceUse(0, IrisRenderGraph.Access.WRITE);
    List<IrisRenderGraph.ResourceUse> copyUses = List.of(
        new IrisRenderGraph.ResourceUse(0, IrisRenderGraph.Access.READ),
        new IrisRenderGraph.ResourceUse(1, IrisRenderGraph.Access.WRITE));
    IrisRenderGraph graph = new IrisRenderGraph(resources, List.of(
        new IrisRenderGraph.Node(0, IrisRenderGraph.NodeKind.CLEAR,
            IrisRenderGraph.Phase.FINAL, "0".repeat(64), "0".repeat(64),
            0, List.of(outputWrite)),
        new IrisRenderGraph.Node(1, IrisRenderGraph.NodeKind.COPY_TEXTURE,
            IrisRenderGraph.Phase.FINAL, "0".repeat(64), "0".repeat(64),
            0, copyUses)), List.of());
    IrisClearCommand clear = IrisClearCommand.colorFloat(output, 0,
        0.1F, 0.2F, 0.3F, 1.0F, Optional.empty());
    IrisTransferCommand copy = new IrisTransferCommand.CopyTexSubImage2D(
        framebuffer, output, temporal, 0x0DE1, 0, 0, 0,
        0, 0, 64, 32);
    IrisRenderExecutionPlan plan = new IrisRenderExecutionPlan(graph,
        List.of(
            new IrisRenderExecutionPlan.ClearStep(0, 0,
                IrisRenderGraph.Phase.FINAL, clear, List.of(outputWrite)),
            new IrisRenderExecutionPlan.TransferStep(1, 1,
                IrisRenderGraph.NodeKind.COPY_TEXTURE,
                IrisRenderGraph.Phase.FINAL, copyUses, copy)),
        List.of(new IrisRenderExecutionPlan.ResourceBinding(0, output),
            new IrisRenderExecutionPlan.ResourceBinding(1, temporal)));

    IrisMetalGraphFramePlanner.Complete complete = assertInstanceOf(
        IrisMetalGraphFramePlanner.Complete.class,
        IrisMetalGraphFramePlanner.build(plan, List.of(
                new NativeIrisMetalGraphResources.Binding(0, 77),
                new NativeIrisMetalGraphResources.Binding(1, 78)),
            Set.of(), (pipeline, width, height) -> {
              throw new AssertionError("clear/copy frame has no draw");
            }));

    assertEquals(0, complete.readbackResourceId());
    assertEquals(Set.of(0, 1), complete.writtenResourceIds());
    assertInstanceOf(IrisMetalGraphFramePacketEncoder.CopyTexture.class,
        complete.frame().operations().getLast());
  }

  @Test
  void rejectsTransferThatReadsUninitializedPrivateAttachment() {
    ResourceHandle source = new ResourceHandle(ResourceKind.TEXTURE,
        41, 1, 3);
    ResourceHandle destination = new ResourceHandle(ResourceKind.TEXTURE,
        42, 1, 3);
    ResourceHandle sourceFramebuffer = new ResourceHandle(
        ResourceKind.FRAMEBUFFER, 7, 1, 3);
    List<IrisRenderGraph.Resource> resources = List.of(
        new IrisRenderGraph.Resource(0, IrisRenderGraph.ResourceKind.TEXTURE,
            "rgba8-unorm", 1, 16, 16, 1, 1),
        new IrisRenderGraph.Resource(1, IrisRenderGraph.ResourceKind.TEXTURE,
            "rgba8-unorm", 1, 16, 16, 1, 1));
    List<IrisRenderGraph.ResourceUse> uses = List.of(
        new IrisRenderGraph.ResourceUse(0, IrisRenderGraph.Access.READ),
        new IrisRenderGraph.ResourceUse(1, IrisRenderGraph.Access.WRITE));
    IrisRenderGraph.Node node = new IrisRenderGraph.Node(0,
        IrisRenderGraph.NodeKind.COPY_TEXTURE,
        IrisRenderGraph.Phase.COMPOSITE, "0".repeat(64),
        "0".repeat(64), 0, uses);
    IrisRenderGraph graph = new IrisRenderGraph(resources, List.of(node),
        List.of());
    IrisTransferCommand command = new IrisTransferCommand.CopyTexSubImage2D(
        sourceFramebuffer, source, destination, 0x0DE1, 0, 0, 0,
        0, 0, 16, 16);
    IrisRenderExecutionPlan plan = new IrisRenderExecutionPlan(graph,
        List.of(new IrisRenderExecutionPlan.TransferStep(0, 0,
            IrisRenderGraph.NodeKind.COPY_TEXTURE,
            IrisRenderGraph.Phase.COMPOSITE, uses, command)),
        List.of(new IrisRenderExecutionPlan.ResourceBinding(0, source),
            new IrisRenderExecutionPlan.ResourceBinding(1, destination)));

    IrisMetalGraphFramePlanner.Unsupported unsupported = assertInstanceOf(
        IrisMetalGraphFramePlanner.Unsupported.class,
        IrisMetalGraphFramePlanner.build(plan, List.of(
                new NativeIrisMetalGraphResources.Binding(0, 91),
                new NativeIrisMetalGraphResources.Binding(1, 92)),
            Set.of(), (pipeline, width, height) -> {
              throw new AssertionError("transfer frame has no draw");
            }));
    assertEquals("graph-frame-transfer-source-uninitialized",
        unsupported.reason());
  }
}

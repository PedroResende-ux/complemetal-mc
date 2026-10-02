package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceKind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisMetalGraphResourcePlanTest {
  @Test
  void allocatesOnlyWrittenTextureAttachmentsWithExactIdentityAndUsage() {
    ResourceHandle sampled = new ResourceHandle(ResourceKind.TEXTURE,
        41, 3, 7);
    ResourceHandle target = new ResourceHandle(ResourceKind.TEXTURE,
        42, 5, 7);
    IrisRenderGraph.Resource sampledResource = new IrisRenderGraph.Resource(
        0, IrisRenderGraph.ResourceKind.TEXTURE, "rgba8-unorm", 1,
        64, 64, 1, 1);
    IrisRenderGraph.Resource targetResource = new IrisRenderGraph.Resource(
        1, IrisRenderGraph.ResourceKind.TEXTURE, "rgba16-float", 1,
        128, 72, 1, 4);
    IrisRenderGraph.ResourceUse write = new IrisRenderGraph.ResourceUse(1,
        IrisRenderGraph.Access.WRITE);
    IrisRenderGraph.Node clear = new IrisRenderGraph.Node(0,
        IrisRenderGraph.NodeKind.CLEAR, IrisRenderGraph.Phase.COMPOSITE,
        "0".repeat(64), "0".repeat(64), 0, List.of(write));
    IrisRenderGraph graph = new IrisRenderGraph(
        List.of(sampledResource, targetResource), List.of(clear), List.of());
    IrisClearCommand command = IrisClearCommand.colorFloat(target, 0,
        0.0f, 0.0f, 0.0f, 1.0f, Optional.empty());
    IrisRenderExecutionPlan plan = new IrisRenderExecutionPlan(graph,
        List.of(new IrisRenderExecutionPlan.ClearStep(0, 0,
            IrisRenderGraph.Phase.COMPOSITE, command, List.of(write))),
        List.of(new IrisRenderExecutionPlan.ResourceBinding(0, sampled),
            new IrisRenderExecutionPlan.ResourceBinding(1, target)));

    IrisMetalGraphResourcePlan.Complete complete = assertInstanceOf(
        IrisMetalGraphResourcePlan.Complete.class,
        IrisMetalGraphResourcePlan.build(plan));
    IrisMetalGraphResourcePlan.Allocation allocation =
        complete.plan().allocations().getFirst();
    assertEquals(1, complete.plan().allocations().size());
    assertEquals(1, allocation.resourceId());
    assertEquals(target, allocation.handle());
    assertEquals("rgba16-float", allocation.format());
    assertEquals(128, allocation.width());
    assertEquals(72, allocation.height());
    assertEquals(4, allocation.mipLevels());
    assertEquals(IrisMetalGraphResourcePlan.USAGE_RENDER_TARGET,
        allocation.usage());
  }

  @Test
  void marksFullSurfaceMsaaColorResolveResourcesAsRenderTargets() {
    ResourceHandle source = new ResourceHandle(ResourceKind.TEXTURE,
        51, 8, 7);
    ResourceHandle destination = new ResourceHandle(ResourceKind.TEXTURE,
        52, 9, 7);
    IrisRenderGraph.Resource sourceResource = new IrisRenderGraph.Resource(
        0, IrisRenderGraph.ResourceKind.TEXTURE, "rgba8-unorm", 4,
        64, 64, 1, 1);
    IrisRenderGraph.Resource destinationResource =
        new IrisRenderGraph.Resource(1, IrisRenderGraph.ResourceKind.TEXTURE,
            "rgba8-unorm", 1, 64, 64, 1, 1);
    IrisRenderGraph.ResourceUse read = new IrisRenderGraph.ResourceUse(0,
        IrisRenderGraph.Access.READ);
    IrisRenderGraph.ResourceUse write = new IrisRenderGraph.ResourceUse(1,
        IrisRenderGraph.Access.WRITE);
    IrisRenderGraph.Node blit = new IrisRenderGraph.Node(0,
        IrisRenderGraph.NodeKind.BLIT, IrisRenderGraph.Phase.COMPOSITE,
        "0".repeat(64), "0".repeat(64), 0, List.of(read, write));
    IrisRenderGraph graph = new IrisRenderGraph(
        List.of(sourceResource, destinationResource), List.of(blit), List.of());
    IrisTransferCommand command = new IrisTransferCommand.BlitFramebuffer(
        new ResourceHandle(ResourceKind.FRAMEBUFFER, 61, 1, 7),
        new ResourceHandle(ResourceKind.FRAMEBUFFER, 62, 1, 7),
        0, 0, 64, 64, 0, 0, 64, 64,
        IrisTransferCommand.GL_COLOR_BUFFER_BIT,
        IrisTransferCommand.GL_NEAREST);
    IrisRenderExecutionPlan plan = new IrisRenderExecutionPlan(graph,
        List.of(new IrisRenderExecutionPlan.TransferStep(0, 0,
            IrisRenderGraph.NodeKind.BLIT, IrisRenderGraph.Phase.COMPOSITE,
            List.of(read, write), command)),
        List.of(new IrisRenderExecutionPlan.ResourceBinding(0, source),
            new IrisRenderExecutionPlan.ResourceBinding(1, destination)));

    IrisMetalGraphResourcePlan.Complete complete = assertInstanceOf(
        IrisMetalGraphResourcePlan.Complete.class,
        IrisMetalGraphResourcePlan.build(plan));

    assertEquals(2, complete.plan().allocations().size());
    assertEquals(IrisMetalGraphResourcePlan.USAGE_SHADER_READ
        | IrisMetalGraphResourcePlan.USAGE_RENDER_TARGET,
        complete.plan().allocations().get(0).usage());
    assertEquals(IrisMetalGraphResourcePlan.USAGE_RENDER_TARGET
        | IrisMetalGraphResourcePlan.USAGE_TRANSFER_DESTINATION,
        complete.plan().allocations().get(1).usage());
  }

  @Test
  void failsClosedWhenTransientResourceIdentitiesAreMissing() {
    ResourceHandle target = new ResourceHandle(ResourceKind.TEXTURE,
        42, 5, 7);
    IrisRenderGraph.Resource resource = new IrisRenderGraph.Resource(0,
        IrisRenderGraph.ResourceKind.TEXTURE, "rgba8-unorm", 1,
        16, 16, 1, 1);
    IrisRenderGraph.ResourceUse write = new IrisRenderGraph.ResourceUse(0,
        IrisRenderGraph.Access.WRITE);
    IrisRenderGraph.Node clear = new IrisRenderGraph.Node(0,
        IrisRenderGraph.NodeKind.CLEAR, IrisRenderGraph.Phase.FINAL,
        "0".repeat(64), "0".repeat(64), 0, List.of(write));
    IrisRenderGraph graph = new IrisRenderGraph(List.of(resource),
        List.of(clear), List.of());
    IrisClearCommand command = IrisClearCommand.colorFloat(target, 0,
        0.0f, 0.0f, 0.0f, 1.0f, Optional.empty());
    IrisRenderExecutionPlan plan = new IrisRenderExecutionPlan(graph,
        List.of(new IrisRenderExecutionPlan.ClearStep(0, 0,
            IrisRenderGraph.Phase.FINAL, command, List.of(write))));

    IrisMetalGraphResourcePlan.Unsupported unsupported = assertInstanceOf(
        IrisMetalGraphResourcePlan.Unsupported.class,
        IrisMetalGraphResourcePlan.build(plan));
    assertEquals("resource-identities-incomplete", unsupported.reason());
  }

  @Test
  void expandsMutableLevelZeroTextureForCapturedMipmapGeneration() {
    ResourceHandle texture = new ResourceHandle(ResourceKind.TEXTURE,
        43, 6, 7);
    IrisRenderGraph.Resource resource = new IrisRenderGraph.Resource(0,
        IrisRenderGraph.ResourceKind.TEXTURE, "rgba8-unorm", 1,
        1024, 256, 1, 1);
    IrisRenderGraph.ResourceUse readWrite = new IrisRenderGraph.ResourceUse(0,
        IrisRenderGraph.Access.READ_WRITE);
    IrisRenderGraph.Node mipmaps = new IrisRenderGraph.Node(0,
        IrisRenderGraph.NodeKind.GENERATE_MIPMAPS,
        IrisRenderGraph.Phase.COMPOSITE, "0".repeat(64), "0".repeat(64),
        0, List.of(readWrite));
    IrisRenderGraph graph = new IrisRenderGraph(List.of(resource),
        List.of(mipmaps), List.of());
    IrisRenderExecutionPlan plan = new IrisRenderExecutionPlan(graph,
        List.of(new IrisRenderExecutionPlan.TransferStep(0, 0,
            IrisRenderGraph.NodeKind.GENERATE_MIPMAPS,
            IrisRenderGraph.Phase.COMPOSITE, List.of(readWrite),
            new IrisTransferCommand.GenerateMipmaps(texture, 0x0DE1))),
        List.of(new IrisRenderExecutionPlan.ResourceBinding(0, texture)));

    IrisMetalGraphResourcePlan.Complete complete = assertInstanceOf(
        IrisMetalGraphResourcePlan.Complete.class,
        IrisMetalGraphResourcePlan.build(plan));

    assertEquals(11,
        complete.plan().allocations().getFirst().mipLevels());
  }

  @Test
  void computesFullMipChainForRectangularAndSinglePixelTextures() {
    assertEquals(1, IrisMetalGraphResourcePlan.fullMipChainLevels(1, 1));
    assertEquals(12,
        IrisMetalGraphResourcePlan.fullMipChainLevels(2048, 1024));
    assertEquals(3, IrisMetalGraphResourcePlan.fullMipChainLevels(3, 4));
  }
}

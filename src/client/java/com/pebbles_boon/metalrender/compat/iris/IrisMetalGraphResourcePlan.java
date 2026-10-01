package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.NodeKind;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Resource;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persistent Metal attachment allocation plan for one transient Iris graph. */
public record IrisMetalGraphResourcePlan(List<Allocation> allocations) {
  public static final int USAGE_SHADER_READ = 1;
  public static final int USAGE_SHADER_WRITE = 1 << 1;
  public static final int USAGE_RENDER_TARGET = 1 << 2;
  /** Ownership marker only; Metal blit destinations need no usage flag. */
  public static final int USAGE_TRANSFER_DESTINATION = 1 << 3;

  public IrisMetalGraphResourcePlan {
    allocations = List.copyOf(allocations);
    if (allocations.isEmpty()
        || allocations.size() > IrisRenderGraph.MAX_RESOURCES) {
      throw new IllegalArgumentException("invalid graph allocation count");
    }
    boolean[] occupied = new boolean[IrisRenderGraph.MAX_RESOURCES];
    for (Allocation allocation : allocations) {
      Objects.requireNonNull(allocation, "allocation");
      if (occupied[allocation.resourceId()]) {
        throw new IllegalArgumentException("duplicate graph allocation");
      }
      occupied[allocation.resourceId()] = true;
    }
  }

  public static Result build(IrisRenderExecutionPlan plan) {
    Objects.requireNonNull(plan, "plan");
    List<String> structural = plan.structuralBlockers();
    if (!structural.isEmpty()) {
      return new Unsupported(structural.getFirst());
    }
    IrisGlStateSnapshot.ResourceHandle[] handles =
        new IrisGlStateSnapshot.ResourceHandle[plan.graph().resources().size()];
    for (IrisRenderExecutionPlan.ResourceBinding binding
        : plan.resourceBindings()) {
      handles[binding.resourceId()] = binding.handle();
    }
    int[] usages = new int[handles.length];
    int[] requiredMipLevels = new int[handles.length];
    for (Resource resource : plan.graph().resources()) {
      requiredMipLevels[resource.id()] = resource.mipLevels();
    }
    for (IrisRenderGraph.Node node : plan.graph().nodes()) {
      for (IrisRenderGraph.ResourceUse use : node.resources()) {
        int usage = 0;
        if (use.access().reads()) {
          usage |= USAGE_SHADER_READ;
        }
        if (use.access().writes()) {
          if (node.kind() == NodeKind.CLEAR) {
            usage |= USAGE_RENDER_TARGET;
          } else if (node.kind() == NodeKind.DISPATCH) {
            usage |= USAGE_SHADER_WRITE;
          } else if (node.kind().transfer()) {
            usage |= USAGE_TRANSFER_DESTINATION;
          }
          // DRAW writes are classified below from the actual GL attachment
          // list. A storage-image WRITE is also a ResourceUse.WRITE, but it
          // must not acquire render-target usage merely because it happens
          // inside a graphics pipeline.
        }
        usages[use.resourceId()] |= usage;
      }
    }
    // Only actual framebuffer attachments need render-target usage.
    // Graph resources written only through image units stay shader-writable
    // without unnecessarily requesting attachment support from Metal.
    for (IrisRenderExecutionPlan.Step step : plan.steps()) {
      if (!(step instanceof IrisRenderExecutionPlan.PipelineStep pipeline)
          || pipeline.kind() != NodeKind.DRAW) {
        continue;
      }
      IrisGlStateSnapshot snapshot = pipeline.pending().snapshot();
      for (IrisGlStateSnapshot.ColorTarget target
          : snapshot.colorTargets()) {
        target.attachment().valueIfKnown().ifPresent(attachment ->
            markRenderTarget(plan, usages, attachment.texture()));
      }
      if (snapshot.depthAttachment().isKnown()) {
        snapshot.depthAttachment().value().ifPresent(attachment ->
            markRenderTarget(plan, usages, attachment.texture()));
      }
      if (snapshot.stencilAttachment().isKnown()) {
        snapshot.stencilAttachment().value().ifPresent(attachment ->
            markRenderTarget(plan, usages, attachment.texture()));
      }
    }

    // Graphics passes can write storage images without using them as
    // framebuffer attachments. Mark those exact image-bound textures as
    // shader-writable so their persistent Metal allocation is created with
    // MTLTextureUsageShaderWrite.
    for (IrisRenderExecutionPlan.Step step : plan.steps()) {
      if (!(step instanceof IrisRenderExecutionPlan.PipelineStep pipeline)) {
        continue;
      }
      IrisGlResourceBindingSnapshot bindings =
          pipeline.pending().resourceBindings();
      if (bindings == null) {
        continue;
      }
      for (IrisGlResourceBindingSnapshot.ImageUnitBinding binding
          : bindings.imageUnits().values()) {
        if (binding.texture() <= 0 || binding.access() == 0x88B8) {
          continue;
        }
        for (IrisRenderExecutionPlan.ResourceBinding resourceBinding
            : plan.resourceBindings()) {
          IrisGlStateSnapshot.ResourceHandle handle =
              resourceBinding.handle();
          if (handle.kind()
              == IrisGlStateSnapshot.ResourceKind.TEXTURE
              && handle.name() == binding.texture()) {
            usages[resourceBinding.resourceId()] |= USAGE_SHADER_WRITE;
          }
        }
      }
    }

    // Mutable OpenGL textures are commonly defined with only level zero and
    // acquire the rest of their storage when glGenerateMipmap runs. The graph
    // snapshot therefore legitimately reports one level even though the
    // captured transfer needs a complete Metal mip chain. Expand only the
    // generation target; unrelated attachments retain their exact metadata.
    for (IrisRenderExecutionPlan.Step step : plan.steps()) {
      if (!(step instanceof IrisRenderExecutionPlan.TransferStep transfer)
          || !(transfer.command()
              instanceof IrisTransferCommand.GenerateMipmaps mipmaps)) {
        continue;
      }
      int resourceId = -1;
      for (int candidate = 0; candidate < handles.length; candidate++) {
        if (mipmaps.texture().equals(handles[candidate])) {
          resourceId = candidate;
          break;
        }
      }
      if (resourceId < 0) {
        return new Unsupported("graph-mipmap-resource-identity-missing");
      }
      Resource resource = plan.graph().resources().get(resourceId);
      if (resource.sampleCount() != 1 || resource.depthOrLayers() != 1) {
        return new Unsupported("graph-mipmap-resource-shape-unsupported");
      }
      requiredMipLevels[resourceId] = Math.max(
          requiredMipLevels[resourceId], fullMipChainLevels(
              resource.width(), resource.height()));
    }
    ArrayList<Allocation> allocations = new ArrayList<>();
    for (Resource resource : plan.graph().resources()) {
      if ((usages[resource.id()] & (USAGE_SHADER_WRITE
          | USAGE_RENDER_TARGET | USAGE_TRANSFER_DESTINATION)) == 0) {
        continue;
      }
      if (resource.kind() != ResourceKind.TEXTURE
          || handles[resource.id()] == null) {
        return new Unsupported("graph-write-resource-not-texture");
      }
      allocations.add(new Allocation(resource.id(), handles[resource.id()],
          resource.format(), resource.sampleCount(), resource.width(),
          resource.height(), resource.depthOrLayers(),
          requiredMipLevels[resource.id()], usages[resource.id()]));
    }
    return allocations.isEmpty()
        ? new Unsupported("graph-has-no-metal-attachments")
        : new Complete(new IrisMetalGraphResourcePlan(allocations));
  }

  private static void markRenderTarget(
      IrisRenderExecutionPlan plan, int[] usages,
      IrisGlStateSnapshot.ResourceHandle handle) {
    if (handle == null
        || handle.kind() != IrisGlStateSnapshot.ResourceKind.TEXTURE) {
      return;
    }
    for (IrisRenderExecutionPlan.ResourceBinding binding
        : plan.resourceBindings()) {
      if (binding.handle().equals(handle)) {
        usages[binding.resourceId()] |= USAGE_RENDER_TARGET;
        return;
      }
    }
  }

  static int fullMipChainLevels(int width, int height) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("invalid mip extent");
    }
    return Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(width,
        height));
  }

  public record Allocation(int resourceId,
                           IrisGlStateSnapshot.ResourceHandle handle,
                           String format, int sampleCount,
                           int width, int height, int depthOrLayers,
                           int mipLevels, int usage) {
    public Allocation {
      if (resourceId < 0 || resourceId >= IrisRenderGraph.MAX_RESOURCES
          || handle == null
          || handle.kind() != IrisGlStateSnapshot.ResourceKind.TEXTURE
          || format == null || format.isBlank() || format.length() > 128
          || sampleCount <= 0 || width <= 0 || height <= 0
          || depthOrLayers <= 0 || mipLevels <= 0
          || (usage & (USAGE_SHADER_WRITE | USAGE_RENDER_TARGET
              | USAGE_TRANSFER_DESTINATION)) == 0
          || (usage & ~(USAGE_SHADER_READ | USAGE_SHADER_WRITE
              | USAGE_RENDER_TARGET | USAGE_TRANSFER_DESTINATION)) != 0) {
        throw new IllegalArgumentException("invalid Metal graph allocation");
      }
    }
  }

  public sealed interface Result permits Complete, Unsupported {
  }

  public record Complete(IrisMetalGraphResourcePlan plan) implements Result {
    public Complete {
      Objects.requireNonNull(plan, "plan");
    }
  }

  public record Unsupported(String reason) implements Result {
    public Unsupported {
      Objects.requireNonNull(reason, "reason");
      if (reason.isBlank() || reason.length() > 128) {
        throw new IllegalArgumentException("invalid resource-plan blocker");
      }
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisExecutionCommand;
import com.pebbles_boon.metalrender.compat.iris.IrisGlVertexArrayTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisSodiumGlStateBridge;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import com.pebbles_boon.metalrender.compat.iris.IrisVisualParityCapture;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Sodium 0.6.13's final direct multi-draw submission. */
@Pseudo
@Mixin(targets =
    "net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice$ImmediateDrawCommandList",
    remap = false)
public abstract class IrisSodiumImmediateDrawCommandListMixin {
  @Inject(method = "multiDrawElementsBaseVertex", at = @At("HEAD"),
      require = 0, remap = false, cancellable = true)
  private void metalrender$multiDraw(MultiDrawBatch batch,
      GlIndexType indexType, CallbackInfo ci) {
    int primitiveMode = IrisSodiumGlStateBridge.primitiveMode();
    if (primitiveMode < 0 || batch == null || indexType == null) {
      return;
    }

    int count = batch.size;
    if (count <= 0
        || count > com.pebbles_boon.metalrender.compat.iris
            .IrisExecutionCommand.MAX_MULTI_DRAW_COUNT) {
      return;
    }

    IrisVisualParityCapture.global().beginDrawInvocation();
    IrisSodiumGlStateBridge.refreshAllMappings();

    long[] offsets = new long[count];
    int[] counts = new int[count];
    int[] bases = new int[count];

    try {
      for (int draw = 0; draw < count; draw++) {
        long pointerAddress =
            batch.pElementPointer + (long) draw * Pointer.POINTER_SIZE;
        long offset = MemoryUtil.memGetAddress(pointerAddress);
        int elementCount =
            MemoryUtil.memGetInt(batch.pElementCount + (long) draw * 4L);
        int baseVertex =
            MemoryUtil.memGetInt(batch.pBaseVertex + (long) draw * 4L);

        if (offset < 0 || elementCount < 0) {
          IrisVisualParityCapture.global().endDrawInvocation();
          return;
        }

        offsets[draw] = offset;
        counts[draw] = elementCount;
        bases[draw] = baseVertex;
      }

      var pending = IrisPipelineStateCapture.global().captureDraw(
          new IrisExecutionCommand.MultiDrawIndexed(
              primitiveMode,
              indexType.getStride(),
              offsets,
              counts,
              bases,
              IrisExecutionCommand.Source.UNSPECIFIED),
          IrisGlVertexArrayTracker.global().snapshot(
              IrisPipelineStateCapture.global().currentProgramDescriptor()));

      if (pending.isPresent()
          && IrisTranslationCoordinator.tryFullGraphCutover(
              pending.orElseThrow())) {
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-sodium-multidraw-unresolved")) {
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
    } catch (RuntimeException error) {
      // Preserve Sodium's GL path. The next graph validation stage will report
      // an incomplete capture instead of replacing an unproven terrain draw.
    }
  }

  @Inject(method = "multiDrawElementsBaseVertex", at = @At("RETURN"),
      require = 0, remap = false)
  private void metalrender$multiDrawComplete(MultiDrawBatch batch,
      GlIndexType indexType, CallbackInfo ci) {
    IrisVisualParityCapture.global().endDrawInvocation();
  }
}

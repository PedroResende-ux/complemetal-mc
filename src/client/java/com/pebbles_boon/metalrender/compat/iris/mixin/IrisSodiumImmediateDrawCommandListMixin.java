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
  private static final ThreadLocal<Boolean> METALRENDER_DRAW_ACTIVE =
      new ThreadLocal<>();

  public static boolean metalrender$isHighLevelDrawActive() {
    return METALRENDER_DRAW_ACTIVE.get() != null;
  }

  @Inject(method = "multiDrawElementsBaseVertex", at = @At("HEAD"),
      require = 0, remap = false, cancellable = true)
  private void metalrender$multiDraw(MultiDrawBatch batch,
      GlIndexType indexType, CallbackInfo ci) {
    int primitiveMode = IrisSodiumGlStateBridge.primitiveMode();
    if (primitiveMode < 0 || batch == null || indexType == null) {
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-sodium-multidraw-input-invalid")) {
        ci.cancel();
      }
      return;
    }

    int count = batch.size();
    if (count <= 0
        || count > com.pebbles_boon.metalrender.compat.iris
            .IrisExecutionCommand.MAX_MULTI_DRAW_COUNT) {
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-sodium-multidraw-count-invalid")) {
        ci.cancel();
      }
      return;
    }

    IrisVisualParityCapture.global().beginDrawInvocation();
    METALRENDER_DRAW_ACTIVE.set(Boolean.TRUE);
    IrisSodiumGlStateBridge.synchronizeForDraw();
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
          if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-sodium-multidraw-entry-invalid")) {
            metalrender$finishDraw();
            ci.cancel();
          } else {
            metalrender$finishDraw();
          }
          return;
        }

        offsets[draw] = offset;
        counts[draw] = elementCount;
        bases[draw] = baseVertex;
      }

      var pending = IrisPipelineStateCapture.global().captureDrawDirect(
          new IrisExecutionCommand.MultiDrawIndexed(
              primitiveMode,
              indexType.getStride(),
              offsets,
              counts,
              bases,
              IrisExecutionCommand.Source.SODIUM_COMMAND_LIST));

      if (pending.isPresent()
          && IrisTranslationCoordinator.tryFullGraphCutover(
              pending.orElseThrow())) {
        metalrender$finishDraw();
        ci.cancel();
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-sodium-multidraw-unresolved")) {
        metalrender$finishDraw();
        ci.cancel();
      }
    } catch (RuntimeException error) {
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-sodium-multidraw-capture-failed")) {
        metalrender$finishDraw();
        ci.cancel();
      } else {
        // Preserve Sodium's normal GL path when graph ownership is not active.
        metalrender$finishDraw();
      }
    }
  }

  @Inject(method = "endTessellating", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$endTessellating(CallbackInfo ci) {
    IrisSodiumGlStateBridge.endTessellation();
  }

  @Inject(method = "multiDrawElementsBaseVertex", at = @At("RETURN"),
      require = 0, remap = false)
  private void metalrender$multiDrawComplete(MultiDrawBatch batch,
      GlIndexType indexType, CallbackInfo ci) {
    metalrender$finishDraw();
  }

  private static void metalrender$finishDraw() {
    if (METALRENDER_DRAW_ACTIVE.get() != null) {
      METALRENDER_DRAW_ACTIVE.remove();
      IrisVisualParityCapture.global().endDrawInvocation();
    }
  }
}

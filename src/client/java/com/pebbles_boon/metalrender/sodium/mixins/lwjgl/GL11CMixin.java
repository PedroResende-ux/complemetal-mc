package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.backend.GLIntercept;
import com.pebbles_boon.metalrender.compat.iris.IrisExecutionCommand;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import com.pebbles_boon.metalrender.compat.iris.IrisVisualParityCapture;
import com.pebbles_boon.metalrender.compat.iris.mixin.IrisGlStateManagerMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the classic LWJGL draw entry points used by Minecraft 1.21.1 and
 * Sodium 0.6.13. The underlying GL call remains untouched unless the
 * validated Metal cutover gate explicitly cancels it.
 */
@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL11C")
public class GL11CMixin {
  private static final ThreadLocal<Boolean> METALRENDER_DRAW_ACTIVE =
      new ThreadLocal<>();

  @Inject(method = "glDrawElements", at = @At("HEAD"), remap = false,
      cancellable = true)
  private static void metalrender$onDrawElements(int mode, int count, int type,
      long indicesOffset, CallbackInfo ci) {
    if (com.pebbles_boon.metalrender.config.MetalRenderConfig.swapOpaque()
        || com.pebbles_boon.metalrender.config.MetalRenderConfig.swapCutout()
        || com.pebbles_boon.metalrender.config.MetalRenderConfig
            .swapTranslucent()) {
      GLIntercept.onDrawElements(mode, count, type, indicesOffset, null);
    }

    if (IrisGlStateManagerMixin.metalrender$isMojangDrawActive()
        || !IrisShaderCapture.isEnabled() || count <= 0) {
      return;
    }

    int bytes = switch (type) {
      case 0x1401 -> 1;
      case 0x1403 -> 2;
      case 0x1405 -> 4;
      default -> 0;
    };
    IrisVisualParityCapture.global().beginDrawInvocation();
    METALRENDER_DRAW_ACTIVE.set(Boolean.TRUE);
    if (bytes == 0) {
      IrisPipelineStateCapture.global().draw(mode);
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-lwjgl-index-type-unsupported")) {
        metalrender$finishDraw();
        ci.cancel();
      }
      return;
    }

    try {
      var pending = IrisPipelineStateCapture.global().captureDrawDirect(
          new IrisExecutionCommand.DrawIndexed(
              mode, indicesOffset, count, bytes, 0, 1, 0,
              IrisExecutionCommand.Source.DIRECT_GL));
      if (pending.isPresent()
          && (IrisTranslationCoordinator.tryFullGraphCutover(
              pending.orElseThrow())
              || IrisTranslationCoordinator.tryFinalCutover(
                  pending.orElseThrow()))) {
        metalrender$finishDraw();
        ci.cancel();
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-lwjgl-indexed-unresolved")) {
        metalrender$finishDraw();
        ci.cancel();
      }
    } catch (RuntimeException error) {
      metalrender$finishDraw();
    }
  }

  @Inject(method = "glDrawElements", at = @At("RETURN"), remap = false,
      require = 0)
  private static void metalrender$onDrawElementsComplete(int mode, int count,
      int type, long indicesOffset, CallbackInfo ci) {
    if (!IrisGlStateManagerMixin.metalrender$isMojangDrawActive()) {
      metalrender$finishDraw();
    }
  }

  @Inject(method = "glDrawArrays", at = @At("HEAD"), remap = false,
      cancellable = true)
  private static void metalrender$onDrawArrays(int mode, int first, int count,
      CallbackInfo ci) {
    if (!IrisShaderCapture.isEnabled() || count <= 0) {
      return;
    }

    IrisVisualParityCapture.global().beginDrawInvocation();
    METALRENDER_DRAW_ACTIVE.set(Boolean.TRUE);
    try {
      var pending = IrisPipelineStateCapture.global().captureDrawDirect(
          new IrisExecutionCommand.DrawArrays(mode, first, count, 1, 0,
              IrisExecutionCommand.Source.DIRECT_GL));
      if (pending.isPresent()
          && (IrisTranslationCoordinator.tryFullGraphCutover(
              pending.orElseThrow())
              || IrisTranslationCoordinator.tryFinalCutover(
                  pending.orElseThrow()))) {
        metalrender$finishDraw();
        ci.cancel();
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-lwjgl-arrays-unresolved")) {
        metalrender$finishDraw();
        ci.cancel();
      }
    } catch (RuntimeException error) {
      metalrender$finishDraw();
    }
  }

  @Inject(method = "glDrawArrays", at = @At("RETURN"), remap = false,
      require = 0)
  private static void metalrender$onDrawArraysComplete(int mode, int first,
      int count, CallbackInfo ci) {
    metalrender$finishDraw();
  }

  private static void metalrender$finishDraw() {
    if (METALRENDER_DRAW_ACTIVE.get() != null) {
      METALRENDER_DRAW_ACTIVE.remove();
      IrisVisualParityCapture.global().endDrawInvocation();
    }
  }
}

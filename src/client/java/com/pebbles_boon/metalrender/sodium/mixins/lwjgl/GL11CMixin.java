package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.backend.GLIntercept;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisExecutionCommand;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import com.pebbles_boon.metalrender.compat.iris.IrisVisualParityCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL11C")
public class GL11CMixin {
  @Inject(method = "glDrawElements", at = @At("HEAD"), remap = false)
  private static void metalrender$onDrawElements(int mode, int count, int type,
      long indicesOffset,
      CallbackInfo ci) {
    if (com.pebbles_boon.metalrender.config.MetalRenderConfig.swapOpaque() ||
        com.pebbles_boon.metalrender.config.MetalRenderConfig.swapCutout() ||
        com.pebbles_boon.metalrender.config.MetalRenderConfig
            .swapTranslucent()) {
      GLIntercept.onDrawElements(mode, count, type, indicesOffset, null);
    }
    private static final ThreadLocal<Boolean> METALRENDER_DRAW_ACTIVE =
      new ThreadLocal<>();

  @Inject(method = "glDrawArrays", at = @At("HEAD"), remap = false,
      cancellable = true)
  private static void metalrender$onDrawArrays(int mode, int first, int count,
      CallbackInfo ci) {
    if (count <= 0) return;
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
        METALRENDER_DRAW_ACTIVE.remove();
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
    } catch (RuntimeException ignored) {
      METALRENDER_DRAW_ACTIVE.remove();
      IrisVisualParityCapture.global().endDrawInvocation();
    }
  }

  @Inject(method = "glDrawArrays", at = @At("RETURN"), remap = false,
      require = 0)
  private static void metalrender$onDrawArraysComplete(int mode, int first,
      int count, CallbackInfo ci) {
    if (METALRENDER_DRAW_ACTIVE.get() != null) {
      METALRENDER_DRAW_ACTIVE.remove();
      IrisVisualParityCapture.global().endDrawInvocation();
    }
  }
}

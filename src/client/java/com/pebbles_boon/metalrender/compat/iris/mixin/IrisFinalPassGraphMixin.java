package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Phase;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks final-pass work nested inside the composite frame tail. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer",
    remap = false)
public abstract class IrisFinalPassGraphMixin {
  @Inject(method = "renderFinalPass", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$finalPass(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.FINAL);
  }

  @Inject(method = "renderFinalPass", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$finalPassComplete(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.COMPOSITE);
  }

  /**
   * Iris restores every composite buffer flipped during the frame with a
   * direct LWJGL copy from its alt texture back to main. That call bypasses
   * IrisRenderSystem, so capture it here as the temporal-history edge of the
   * full graph. Once ownership is armed the equivalent Metal blit is already
   * in the submitted graph and the OpenGL copy must not execute as well.
   */
  @Redirect(method = "renderFinalPass", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL46C;glCopyTexSubImage2D(IIIIIIII)V"),
      require = 0, remap = false)
  private void metalrender$copyTemporalHistory(int target, int level,
      int destinationX, int destinationY, int sourceX, int sourceY,
      int width, int height) {
    IrisGlResourceBindingSnapshot.TextureUnitBinding destination =
        IrisGlResourceBindingTracker.global().activeTextureBinding(target);
    boolean suppress;
    if (destination != null && destination.texture() > 0) {
      IrisRenderGraphCapture.global().copyTexture(destination.texture(),
          target, level, destinationX, destinationY, sourceX, sourceY,
          width, height, GL11C.glGetInteger(GL11C.GL_READ_BUFFER));
      suppress = IrisTranslationCoordinator.suppressFullGraphOperation();
    } else {
      suppress = IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-temporal-copy-destination-unavailable");
    }
    if (!suppress) {
      GL46C.glCopyTexSubImage2D(target, level, destinationX, destinationY,
          sourceX, sourceY, width, height);
    }
  }
}

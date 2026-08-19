package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Phase;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}

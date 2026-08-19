package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Phase;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Distinguishes shadow-map rendering from the following prepare passes. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowRendererGraphMixin {
  @Inject(method = "renderShadows", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$shadow(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.SHADOW);
  }

  @Inject(method = "renderShadows", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$shadowComplete(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.PREPARE);
  }
}

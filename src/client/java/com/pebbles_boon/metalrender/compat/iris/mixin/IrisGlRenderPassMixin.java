package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.pebbles_boon.metalrender.compat.iris.IrisGlRenderPassState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains only the public pipeline topology on a package-private pass. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
public abstract class IrisGlRenderPassMixin implements IrisGlRenderPassState {
  @Unique
  private int metalrender$primitiveMode;

  @Inject(method = "setPipeline", at = @At("RETURN"))
  private void metalrender$setPipeline(RenderPipeline pipeline,
      CallbackInfo ci) {
    metalrender$primitiveMode = GlConst.toGl(
        pipeline.getPrimitiveTopology());
  }

  @Override
  public int metalrender$primitiveMode() {
    return metalrender$primitiveMode;
  }
}

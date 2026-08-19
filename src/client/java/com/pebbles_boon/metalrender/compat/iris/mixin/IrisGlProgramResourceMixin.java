package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the UBO names and binding points created by GlProgram/Iris. */
@Mixin(GlProgram.class)
public abstract class IrisGlProgramResourceMixin {
  @Shadow @Final private int programId;
  @Shadow @Final private Map<String, Uniform> uniformsByName;

  @Inject(method = "setupBindGroupLayouts", at = @At("RETURN"))
  private void metalrender$captureUniformBlocks(
      List<BindGroupLayout> layouts, CallbackInfo ci) {
    for (Map.Entry<String, Uniform> entry : uniformsByName.entrySet()) {
      if (!(entry.getValue() instanceof Uniform.Ubo ubo)) {
        continue;
      }
      captureCandidate(entry.getKey(), ubo.blockBinding());
      if (!entry.getKey().startsWith("iris_")) {
        captureCandidate("iris_" + entry.getKey(), ubo.blockBinding());
      }
    }
  }

  private void captureCandidate(String name, int binding) {
    int blockIndex = GL33C.glGetUniformBlockIndex(programId, name);
    if (blockIndex < 0) {
      return;
    }
    IrisGlResourceBindingTracker tracker =
        IrisGlResourceBindingTracker.global();
    tracker.uniformBlockIndex(programId, name, blockIndex);
    tracker.uniformBlockBinding(programId, blockIndex, binding);
  }
}

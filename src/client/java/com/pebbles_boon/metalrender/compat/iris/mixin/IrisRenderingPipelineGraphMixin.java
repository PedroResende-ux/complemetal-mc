package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraph.Phase;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisStage9PerformanceSampler;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the major ordered phases of one Iris world-rendering frame. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline",
    remap = false)
public abstract class IrisRenderingPipelineGraphMixin {
  @Inject(method = "beginLevelRendering", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$beginFrame(CallbackInfo ci) {
    IrisStage9PerformanceSampler.beginFrame();
    IrisTranslationCoordinator.drainPreparedGraphExecution();
    IrisTranslationCoordinator.beginFullGraphOwnershipFrame();
    IrisTranslationCoordinator.beginCutoverFrame();
    IrisRenderGraphCapture.global().beginFrame();
    IrisRenderGraphCapture.global().phase(Phase.BEGIN);
  }

  @Inject(method = "beginLevelRendering", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$beginComplete(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.GEOMETRY);
  }

  @Inject(method = "renderShadows", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$prepare(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.PREPARE);
  }

  @Inject(method = "renderShadows", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$prepareComplete(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.GEOMETRY);
  }

  @Inject(method = "beginTranslucents", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$deferred(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.DEFERRED);
  }

  @Inject(method = "beginTranslucents", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$deferredComplete(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.GEOMETRY);
  }

  @Inject(method = "finalizeLevelRendering", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$composite(CallbackInfo ci) {
    IrisRenderGraphCapture.global().phase(Phase.COMPOSITE);
  }

  @Inject(method = "finalizeLevelRendering", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$endFrame(CallbackInfo ci) {
    IrisRenderGraphCapture.global().endFrame();
    IrisTranslationCoordinator.endFullGraphOwnershipFrame();
    IrisStage9PerformanceSampler.endFrame();
  }
}

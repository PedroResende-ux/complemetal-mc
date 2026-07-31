package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mirrors successful vanilla section compiles into Metal's rebuild queue.
 *
 * <p>Vanilla compilation is intentionally never cancelled: its meshes remain
 * warm so a failed Metal frame can immediately fall back without holes.</p>
 */
@Mixin(targets =
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$" +
        "RenderSection$CompileTask",
    remap = false)
public abstract class VanillaSectionCompileTaskMixin {
  @Inject(method = "doTask", at = @At("RETURN"), require = 1)
  private void metalrender$mirrorSuccessfulCompile(
      SectionBufferBuilderPack buffers,
      CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir) {
    if (!MetalRenderClient.isEnabled() ||
        cir.getReturnValue() !=
            SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.SUCCESSFUL) {
      return;
    }
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null) {
      return;
    }
    BlockPos origin =
        ((SectionRenderDispatcher.RenderSection.SectionTask) (Object) this)
            .getRenderOrigin();
    if (origin != null) {
      worldRenderer.scheduleCompiledSectionRebuild(
          origin.getX(), origin.getY(), origin.getZ());
    }
  }
}

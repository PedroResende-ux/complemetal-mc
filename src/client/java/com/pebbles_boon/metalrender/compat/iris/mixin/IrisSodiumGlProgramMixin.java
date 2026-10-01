package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import java.util.function.IntFunction;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL32C;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures Sodium 0.6.13 program binds that bypass Mojang's
 * GlStateManager and call GL20C directly.
 */
@Pseudo
@Mixin(value = GlProgram.class, remap = false)
public abstract class IrisSodiumGlProgramMixin {
  @Shadow public abstract int handle();

  @Inject(method = "bindUniform", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$bindUniform(String name, IntFunction<?> factory,
      CallbackInfoReturnable<?> callback) {
    try {
      int program = handle();
      int location = GL20C.glGetUniformLocation(program, name);
      IrisGlResourceBindingTracker.global().uniformLocation(program, name,
          location);
    } catch (RuntimeException | LinkageError ignored) {
      // Resource metadata capture is observational.
    }
  }

  @Inject(method = "bindUniformOptional", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$bindUniformOptional(String name,
      IntFunction<?> factory, CallbackInfoReturnable<?> callback) {
    try {
      int program = handle();
      int location = GL20C.glGetUniformLocation(program, name);
      IrisGlResourceBindingTracker.global().uniformLocation(program, name,
          location);
    } catch (RuntimeException | LinkageError ignored) {
      // Resource metadata capture is observational.
    }
  }

  @Inject(method = "bindUniformBlock", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$bindUniformBlock(String name, int bindingPoint,
      CallbackInfoReturnable<?> callback) {
    try {
      int program = handle();
      int blockIndex = GL32C.glGetUniformBlockIndex(program, name);
      if (blockIndex >= 0) {
        IrisGlResourceBindingTracker tracker =
            IrisGlResourceBindingTracker.global();
        tracker.uniformBlockIndex(program, name, blockIndex);
        tracker.uniformBlockBinding(program, blockIndex, bindingPoint);
      }
    } catch (RuntimeException | LinkageError ignored) {
      // Resource metadata capture is observational.
    }
  }

  @Inject(method = "bindUniformBlockOptional", at = @At("RETURN"),
      require = 0, remap = false)
  private void metalrender$bindUniformBlockOptional(String name,
      int bindingPoint, CallbackInfoReturnable<?> callback) {
    try {
      int program = handle();
      int blockIndex = GL32C.glGetUniformBlockIndex(program, name);
      if (blockIndex >= 0) {
        IrisGlResourceBindingTracker tracker =
            IrisGlResourceBindingTracker.global();
        tracker.uniformBlockIndex(program, name, blockIndex);
        tracker.uniformBlockBinding(program, blockIndex, bindingPoint);
      }
    } catch (RuntimeException | LinkageError ignored) {
      // Resource metadata capture is observational.
    }
  }

  @Inject(method = "bind", at = @At("TAIL"), require = 0,
      remap = false)
  private void metalrender$bind(CallbackInfo ci) {
    int program = handle();
    if (program > 0) {
      IrisPipelineStateCapture.global().useProgram(program);
    }
  }

  @Inject(method = "unbind", at = @At("TAIL"), require = 0,
      remap = false)
  private void metalrender$unbind(CallbackInfo ci) {
    IrisPipelineStateCapture.global().useProgram(0);
  }

  @Inject(method = "delete", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$delete(CallbackInfo ci) {
    try {
      IrisShaderCapture.deleteProgram(handle());
    } catch (RuntimeException | LinkageError ignored) {
      // Program teardown must never break Sodium's normal cleanup path.
    }
  }
}

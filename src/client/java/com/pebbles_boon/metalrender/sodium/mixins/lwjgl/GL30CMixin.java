package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.compat.iris.IrisGlVertexArrayTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the classic OpenGL VAO lifecycle used directly by Sodium/Iris.
 * Mojang's GlStateManager path is handled separately by the Iris mixin.
 */
@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL30C")
public class GL30CMixin {
  @Inject(method = "glBindVertexArray", at = @At("HEAD"), remap = false)
  private static void metalrender$bindVertexArray(int vertexArray,
      CallbackInfo ci) {
    IrisGlVertexArrayTracker.global().bindVertexArray(vertexArray);
  }

  @Inject(method = "glDeleteVertexArrays", at = @At("HEAD"), remap = false,
      require = 0)
  private static void metalrender$deleteVertexArray(int array,
      CallbackInfo ci) {
    IrisGlVertexArrayTracker.global().deleteVertexArray(array);
  }

  @Inject(method = "glDeleteVertexArrays", at = @At("HEAD"), remap = false,
      require = 0)
  private static void metalrender$deleteVertexArrays(int[] arrays,
      CallbackInfo ci) {
    if (arrays == null) {
      return;
    }
    for (int array : arrays) {
      IrisGlVertexArrayTracker.global().deleteVertexArray(array);
    }
  }
}

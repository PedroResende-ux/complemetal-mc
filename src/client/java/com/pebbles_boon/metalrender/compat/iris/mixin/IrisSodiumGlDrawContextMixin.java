package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Observes Sodium's direct per-region uniform updates used by Iris terrain. */
@Pseudo
@Mixin(targets =
    "net.caffeinemc.mods.sodium.client.gpu.device.context.GLDrawContext",
    remap = false)
public abstract class IrisSodiumGlDrawContextMixin {
  @Redirect(method = "setContext", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL46C;glGetUniformLocation(ILjava/lang/CharSequence;)I"),
      require = 0, remap = false)
  private int metalrender$uniformLocation(int program, CharSequence name) {
    int location = GL46C.glGetUniformLocation(program, name);
    IrisGlResourceBindingTracker.global().uniformLocation(program, name,
        location);
    return location;
  }

  @Redirect(method = "updateData", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL46C;glUniform3f(IFFF)V"),
      require = 0, remap = false)
  private void metalrender$uniform3f(int location, float x, float y,
      float z) {
    GL46C.glUniform3f(location, x, y, z);
    IrisGlResourceBindingTracker.global().uniformFloats(location, x, y, z);
  }

  @Redirect(method = "updateData", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL46C;glUniform1i(II)V"),
      require = 0, remap = false)
  private void metalrender$uniform1i(int location, int value) {
    GL46C.glUniform1i(location, value);
    IrisGlResourceBindingTracker.global().uniformInts(location, value);
  }

  @Redirect(method = "updateData", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL46C;glUniform1ui(II)V"),
      require = 0, remap = false)
  private void metalrender$uniform1ui(int location, int value) {
    GL46C.glUniform1ui(location, value);
    IrisGlResourceBindingTracker.global().uniformUInts(location, value);
  }
}

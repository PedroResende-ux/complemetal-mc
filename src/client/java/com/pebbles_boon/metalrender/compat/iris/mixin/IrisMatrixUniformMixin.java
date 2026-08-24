package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import java.nio.FloatBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors mat4 uploads that Iris sends straight to LWJGL. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.uniform.MatrixUniform",
    remap = false)
public abstract class IrisMatrixUniformMixin {
  @Shadow @Final private FloatBuffer buffer;

  @Inject(method = "updateValue()V", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL46C;glUniformMatrix4fv(IZLjava/nio/FloatBuffer;)V",
      shift = At.Shift.AFTER), require = 0, remap = false)
  private void metalrender$captureDirectMatrix(CallbackInfo ci) {
    int location = ((IrisUniformAccessor) (Object) this)
        .metalrender$location();
    IrisGlResourceBindingTracker.global().uniformMatrix(location, 4,
        buffer);
  }
}

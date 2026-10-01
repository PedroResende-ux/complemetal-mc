package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisGlVertexArrayTracker;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferTarget;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Mirrors Sodium 0.6.13's direct VAO attribute setup. */
@Pseudo
@Mixin(targets =
    "net.caffeinemc.mods.sodium.client.gl.tessellation.GlAbstractTessellation",
    remap = false)
public abstract class IrisSodiumGlTessellationMixin {
  @Redirect(method = "bindAttributes", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL30C;glVertexAttribIPointer(IIIIJ)V"),
      require = 0, remap = false)
  private void metalrender$integerAttribute(int index, int size, int type,
      int stride, long pointer) {
    GL30C.glVertexAttribIPointer(index, size, type, stride, pointer);
    IrisGlVertexArrayTracker.global().vertexAttribute(index, size, type, false,
        stride, pointer, true);
  }

  @Redirect(method = "bindAttributes", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL20C;glVertexAttribPointer(IIIZIJ)V"),
      require = 0, remap = false)
  private void metalrender$attribute(int index, int size, int type,
      boolean normalized, int stride, long pointer) {
    GL20C.glVertexAttribPointer(index, size, type, normalized, stride,
        pointer);
    IrisGlVertexArrayTracker.global().vertexAttribute(index, size, type,
        normalized, stride, pointer, false);
  }

  @Redirect(method = "bindAttributes", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL20C;glEnableVertexAttribArray(I)V"),
      require = 0, remap = false)
  private void metalrender$enableAttribute(int index) {
    GL20C.glEnableVertexAttribArray(index);
    IrisGlVertexArrayTracker.global().enableAttribute(index);
  }
}

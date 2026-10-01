package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.backend.GLIntercept;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlVertexArrayTracker;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors classic LWJGL buffer operations used by Minecraft 1.21.1/Iris. */
@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL15C")
public class GL15CMixin {
  @Inject(method = "glBindBuffer", at = @At("HEAD"), remap = false)
  private static void metalrender$onBindBuffer(int target, int buffer,
      CallbackInfo ci) {
    IrisGlVertexArrayTracker.global().bindBuffer(target, buffer);
    if (com.pebbles_boon.metalrender.config.MetalRenderConfig
        .mirrorUploads()) {
      GLIntercept.onBindBuffer(target, buffer);
    }
  }

  @Inject(method = "glBufferData", at = @At("HEAD"), remap = false)
  private static void metalrender$onBufferDataBB(int target, ByteBuffer data,
      int usage, CallbackInfo ci) {
    if (data == null || !IrisGlBufferMirror.isEnabled()) {
      if (com.pebbles_boon.metalrender.config.MetalRenderConfig
          .mirrorUploads()) {
        GLIntercept.onBufferData(target, data, usage, 32);
      }
      return;
    }
    int buffer = IrisGlVertexArrayTracker.global().boundBuffer(target);
    int length = data.remaining();
    if (buffer > 0 && length > 0) {
      IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
      if (mirror.allocate(buffer, length)) {
        mirror.write(buffer, length, 0, length, data);
      }
    }
    if (com.pebbles_boon.metalrender.config.MetalRenderConfig
        .mirrorUploads()) {
      GLIntercept.onBufferData(target, data, usage, 32);
    }
  }

  @Inject(method = "glBufferData", at = @At("HEAD"), remap = false,
      require = 0)
  private static void metalrender$onBufferDataSize(int target, long size,
      int usage, CallbackInfo ci) {
    if (IrisGlBufferMirror.isEnabled()) {
      int buffer = IrisGlVertexArrayTracker.global().boundBuffer(target);
      if (buffer > 0 && size > 0) {
        IrisGlBufferMirror.global().allocate(buffer, size);
      }
    }
  }

  @Inject(method = "glBufferSubData", at = @At("HEAD"), remap = false)
  private static void metalrender$onBufferSubData(int target, long offset,
      ByteBuffer data, CallbackInfo ci) {
    if (!IrisGlBufferMirror.isEnabled() || data == null) return;
    int buffer = IrisGlVertexArrayTracker.global().boundBuffer(target);
    if (buffer > 0 && offset >= 0) {
      IrisGlBufferMirror.global().write(buffer,
          IrisGlBufferMirror.global().size(buffer), offset, data.remaining(),
          data);
    }
  }

  @Inject(method = "glDeleteBuffers", at = @At("HEAD"), remap = false)
  private static void metalrender$onDeleteBuffers(IntBuffer buffers,
      CallbackInfo ci) {
    if (buffers == null) return;
    for (int index = buffers.position(); index < buffers.limit(); index++) {
      int buffer = buffers.get(index);
      IrisGlVertexArrayTracker.global().deleteBuffer(buffer);
      IrisGlBufferMirror.global().delete(buffer);
      if (com.pebbles_boon.metalrender.config.MetalRenderConfig
          .mirrorUploads()) {
        GLIntercept.onDeleteBuffer(buffer);
      }
    }
  }
}

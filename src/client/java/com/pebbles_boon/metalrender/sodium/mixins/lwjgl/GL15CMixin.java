package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.backend.GLIntercept;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlVertexArrayTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import com.pebbles_boon.metalrender.compat.iris.mixin.IrisGlStateManagerMixin;
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
  private static void metalrender$onBufferDataIntercept(int target,
      ByteBuffer data, int usage, CallbackInfo ci) {
    if (IrisGlStateManagerMixin.metalrender$isMojangBufferDataActive()) {
      return;
    }
    if (com.pebbles_boon.metalrender.config.MetalRenderConfig
        .mirrorUploads()) {
      GLIntercept.onBufferData(target, data, usage, 32);
    }
  }

  @Inject(method = "glBufferData", at = @At("RETURN"), remap = false,
      require = 0)
  private static void metalrender$onBufferDataBB(int target, ByteBuffer data,
      int usage, CallbackInfo ci) {
    if (IrisGlStateManagerMixin.metalrender$isMojangBufferDataActive()
        || !IrisGlBufferMirror.isEnabled()) {
      return;
    }
    int buffer = IrisGlVertexArrayTracker.global().boundBuffer(target);
    if (buffer <= 0) {
      return;
    }
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    if (data == null || !data.hasRemaining()) {
      mirror.delete(buffer);
      return;
    }
    int length = data.remaining();
    if (!mirror.allocate(buffer, length)
        || !mirror.write(buffer, length, 0, length, data)) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-lwjgl-buffer-upload-mirror-rejected");
    }
  }

  @Inject(method = "glBufferData", at = @At("RETURN"), remap = false,
      require = 0)
  private static void metalrender$onBufferDataSize(int target, long size,
      int usage, CallbackInfo ci) {
    if (IrisGlStateManagerMixin.metalrender$isMojangBufferDataActive()
        || !IrisGlBufferMirror.isEnabled()) {
      return;
    }
    int buffer = IrisGlVertexArrayTracker.global().boundBuffer(target);
    if (buffer <= 0) {
      return;
    }
    if (size <= 0) {
      IrisGlBufferMirror.global().delete(buffer);
      return;
    }
    if (!IrisGlBufferMirror.global().allocate(buffer, size)) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-lwjgl-buffer-allocation-mirror-rejected");
    }
  }

  @Inject(method = "glBufferSubData", at = @At("RETURN"), remap = false,
      require = 0)
  private static void metalrender$onBufferSubData(int target, long offset,
      ByteBuffer data, CallbackInfo ci) {
    if (IrisGlStateManagerMixin.metalrender$isMojangBufferSubDataActive()
        || !IrisGlBufferMirror.isEnabled() || data == null
        || !data.hasRemaining()) {
      return;
    }
    int buffer = IrisGlVertexArrayTracker.global().boundBuffer(target);
    if (buffer <= 0 || offset < 0) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-lwjgl-buffer-subdata-binding-unavailable");
      return;
    }
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    long size = mirror.size(buffer);
    if (size <= 0
        || !mirror.write(buffer, size, offset, data.remaining(), data)) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-lwjgl-buffer-subdata-mirror-rejected");
    }
  }

  @Inject(method = "glDeleteBuffers", at = @At("RETURN"), remap = false,
      require = 0)
  private static void metalrender$onDeleteBuffers(IntBuffer buffers,
      CallbackInfo ci) {
    if (buffers == null) return;
    for (int index = buffers.position(); index < buffers.limit(); index++) {
      int buffer = buffers.get(index);
      IrisGlVertexArrayTracker.global().deleteBuffer(buffer);
      com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker
          .global().deleteBuffer(buffer);
      IrisGlBufferMirror.global().delete(buffer);
      if (com.pebbles_boon.metalrender.config.MetalRenderConfig
          .mirrorUploads()) {
        GLIntercept.onDeleteBuffer(buffer);
      }
    }
  }
}

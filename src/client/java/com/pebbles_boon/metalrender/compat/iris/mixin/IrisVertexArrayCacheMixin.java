package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.VertexArrayCache;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlVertexArrayTracker;
import java.util.ArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the modern ARB vertex-binding path used by Iris fullscreen draws. */
@Mixin(targets = {
    "com.mojang.blaze3d.opengl.VertexArrayCache$Separate",
    "com.mojang.blaze3d.opengl.VertexArrayCache$Emulated"
})
public abstract class IrisVertexArrayCacheMixin {
  @Inject(method = "bindVertexArray", at = @At("RETURN"), require = 0)
  private void metalrender$bindVertexBuffers(VertexFormat[] formats,
      GpuBufferSlice[] slices, VertexArrayCache.VertexArray previous,
      CallbackInfoReturnable<VertexArrayCache.VertexArray> callback) {
    IrisGlVertexArrayTracker tracker = IrisGlVertexArrayTracker.global();
    if (formats == null || slices == null || formats.length != slices.length) {
      tracker.invalidateMojangVertexBuffers(
          "mojang-vertex-buffer-array-mismatch");
      return;
    }
    ArrayList<IrisGlVertexArrayTracker.MojangVertexBuffer> captured =
        new ArrayList<>();
    for (int slot = 0; slot < slices.length; slot++) {
      VertexFormat format = formats[slot];
      GpuBufferSlice slice = slices[slot];
      if (format == null && slice == null) {
        continue;
      }
      if (format == null || slice == null
          || !(slice.buffer() instanceof GlBuffer buffer)) {
        tracker.invalidateMojangVertexBuffers(
            "mojang-vertex-buffer-unavailable");
        return;
      }
      captured.add(new IrisGlVertexArrayTracker.MojangVertexBuffer(slot,
          buffer.handle(), slice.offset(), slice.length(),
          IrisGlBufferMirror.global().generation(buffer.handle()),
          format.getVertexSize()));
    }
    tracker.bindMojangVertexBuffers(captured);
  }
}

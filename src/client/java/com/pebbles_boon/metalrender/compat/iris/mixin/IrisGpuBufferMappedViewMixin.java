package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors the final bytes of Mojang mapped buffer slices on close. */
@Mixin(GpuBufferSlice.MappedView.class)
public abstract class IrisGpuBufferMappedViewMixin {
  @Inject(method = "close", at = @At("HEAD"), require = 0)
  private void metalrender$closeMappedSlice(CallbackInfo ci) {
    if (!IrisGlBufferMirror.isEnabled()) {
      return;
    }
    GpuBufferSlice.MappedView view =
        (GpuBufferSlice.MappedView) (Object) this;
    GpuBufferSlice slice = view.slice();
    if (!(slice.buffer() instanceof GlBuffer buffer)
        || slice.length() <= 0 || slice.length() > Integer.MAX_VALUE) {
      return;
    }
    ByteBuffer bytes = view.data().duplicate();
    bytes.clear();
    int length = Math.toIntExact(slice.length());
    if (bytes.remaining() < length) {
      return;
    }
    bytes.limit(bytes.position() + length);
    IrisGlBufferMirror.global().write(buffer.handle(), buffer.size(),
        slice.offset(), slice.length(), bytes);
  }
}

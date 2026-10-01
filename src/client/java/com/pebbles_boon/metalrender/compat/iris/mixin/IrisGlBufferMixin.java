package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlMappedBufferAccess;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors Mojang's OpenGL buffer storage and exposes the persistent mapping
 * used by GpuBufferSlice.MappedView on Minecraft 1.21.1.
 */
@Mixin(GlBuffer.class)
public abstract class IrisGlBufferMixin implements IrisGlMappedBufferAccess {
  @Shadow protected ByteBuffer mappedBuffer;

  @Inject(method = "<init>", at = @At("RETURN"), require = 0)
  private void metalrender$defineMirror(Supplier<String> label,
      DirectStateAccess dsa, int usage, long size, int handle,
      boolean canPersistentMap, CallbackInfo ci) {
    if (IrisGlBufferMirror.isEnabled()) {
      IrisGlBufferMirror.global().allocate(handle, size);
    }
  }

  @Inject(method = "close", at = @At("HEAD"), require = 0)
  private void metalrender$deleteMirror(CallbackInfo ci) {
    if (IrisGlBufferMirror.isEnabled()) {
      IrisGlBufferMirror.global().delete(((GlBuffer) (Object) this).handle());
    }
  }

  @Override
  public ByteBuffer metalrender$mappedBufferView() {
    ByteBuffer current = mappedBuffer;
    return current == null ? null : current.duplicate();
  }
}

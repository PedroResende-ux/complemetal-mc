package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisSodiumGlStateBridge;
import net.caffeinemc.mods.sodium.client.gl.array.GlVertexArray;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapping;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlImmutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferTarget;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapFlags;
import net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice;
import net.caffeinemc.mods.sodium.client.gl.util.EnumBitField;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mirrors Sodium 0.6.13's direct GLRenderDevice state into Complemetal. */
@Pseudo
@Mixin(targets =
    "net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice$ImmediateCommandList",
    remap = false)
public abstract class IrisSodiumGlRenderDeviceMixin {
  @Inject(method = "bindVertexArray", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$bindVertexArray(GlVertexArray array,
      CallbackInfo ci) {
    try {
      IrisSodiumGlStateBridge.bindVertexArray(array.handle());
    } catch (RuntimeException ignored) {
      // Observational path is fail-open.
    }
  }

  @Inject(method = "unbindVertexArray", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$unbindVertexArray(CallbackInfo ci) {
    IrisSodiumGlStateBridge.bindVertexArray(0);
  }

  @Inject(method = "bindBuffer", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$bindBuffer(GlBufferTarget target, GlBuffer buffer,
      CallbackInfo ci) {
    try {
      IrisSodiumGlStateBridge.bindBuffer(target.getTargetParameter(),
          buffer.handle());
    } catch (RuntimeException ignored) {
      // Observational path is fail-open.
    }
  }

  @Inject(method = "uploadData", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$uploadData(GlMutableBuffer buffer,
      ByteBuffer bytes, GlBufferUsage usage, CallbackInfo ci) {
    try {
      IrisSodiumGlStateBridge.upload(buffer.handle(), bytes);
    } catch (RuntimeException ignored) {
      // Future replay will fail closed if the mirror is incomplete.
    }
  }

  @Inject(method = "createImmutableBuffer", at = @At("RETURN"),
      require = 0, remap = false)
  private void metalrender$createImmutableBuffer(long size,
      EnumBitField<?> flags, CallbackInfoReturnable<GlImmutableBuffer> callback) {
    GlImmutableBuffer buffer = callback.getReturnValue();
    if (buffer != null) {
      try {
        IrisSodiumGlStateBridge.allocate(buffer.handle(), size);
      } catch (RuntimeException ignored) {
        // Mapped staging remains valid; replay will fail closed if needed.
      }
    }
  }

  @Inject(method = "allocateStorage", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$allocateStorage(GlMutableBuffer buffer, long size,
      GlBufferUsage usage, CallbackInfo ci) {
    IrisSodiumGlStateBridge.allocate(buffer.handle(), size);
  }

  @Inject(method = "copyBufferSubData", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$copyBufferSubData(GlBuffer source,
      GlBuffer destination, long readOffset, long writeOffset, long bytes,
      CallbackInfo ci) {
    try {
      IrisSodiumGlStateBridge.copy(source, destination, readOffset, writeOffset,
          bytes);
    } catch (RuntimeException ignored) {
      // Preserve Sodium behavior; incomplete mirrors block replay later.
    }
  }

  @Inject(method = "mapBuffer", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$mapBuffer(GlBuffer buffer, long offset,
      long length, EnumBitField<GlBufferMapFlags> flags,
      CallbackInfoReturnable<GlBufferMapping> callback) {
    IrisSodiumGlStateBridge.mapped(callback.getReturnValue(), offset, length);
  }

  @Inject(method = "flushMappedRange", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$flushMappedRange(GlBufferMapping mapping,
      int offset, int length, CallbackInfo ci) {
    IrisSodiumGlStateBridge.refreshMapping(mapping);
  }

  @Inject(method = "unmap", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$unmap(GlBufferMapping mapping, CallbackInfo ci) {
    IrisSodiumGlStateBridge.refreshMapping(mapping);
  }

  @Inject(method = "unmap", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$unmapComplete(GlBufferMapping mapping,
      CallbackInfo ci) {
    IrisSodiumGlStateBridge.unmap(mapping);
  }

  @Inject(method = "deleteBuffer", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$deleteBuffer(GlBuffer buffer, CallbackInfo ci) {
    try {
      int handle = buffer.handle();
      IrisSodiumGlStateBridge.deleteBuffer(handle);
    } catch (RuntimeException ignored) {
      // Sodium owns the actual deletion lifecycle.
    }
  }

  @Inject(method = "beginTessellating", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$beginTessellating(
      net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation tessellation,
      CallbackInfoReturnable<?> callback) {
    try {
      IrisSodiumGlStateBridge.beginTessellation(
          tessellation.getPrimitiveType().getId());
    } catch (RuntimeException ignored) {
      IrisSodiumGlStateBridge.beginTessellation(-1);
    }
  }

  @Inject(method = "deleteVertexArray", at = @At("HEAD"), require = 0,
      remap = false)
  private void metalrender$deleteVertexArray(GlVertexArray array,
      CallbackInfo ci) {
    try {
      if (array != null) {
        IrisSodiumGlStateBridge.deleteVertexArray(array.handle());
      }
    } catch (RuntimeException ignored) {
      // Observational path is fail-open.
    }
  }

  @Inject(method = "createTessellation", at = @At("RETURN"), require = 0,
      remap = false)
  private void metalrender$createdTessellation(
      net.caffeinemc.mods.sodium.client.gl.tessellation.GlPrimitiveType primitiveType,
      net.caffeinemc.mods.sodium.client.gl.tessellation.TessellationBinding[] bindings,
      CallbackInfoReturnable<?> callback) {
    // Attribute bindings are mirrored by IrisSodiumGlTessellationMixin while
    // GlAbstractTessellation installs them into the actual VAO.
  }
}

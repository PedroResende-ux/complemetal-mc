package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisExecutionCommand;
import com.pebbles_boon.metalrender.compat.iris.IrisGlFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisGlGenericAttributeTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlSamplerMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlTextureMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional exact-Iris hooks for DSA state and compute dispatches. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.IrisRenderSystem", remap = false)
public abstract class IrisRenderSystemMixin {
  private static IrisPipelineStateCapture metalrender$capture() {
    return IrisPipelineStateCapture.global();
  }

  private static IrisGlStateTracker metalrender$state() {
    return metalrender$capture().tracker();
  }

  private static IrisGlResourceBindingTracker metalrender$resources() {
    return IrisGlResourceBindingTracker.global();
  }

  private static com.pebbles_boon.metalrender.compat.iris.IrisGlVertexArrayTracker
      metalrender$vertices() {
    return com.pebbles_boon.metalrender.compat.iris
        .IrisGlVertexArrayTracker.global();
  }

  @Inject(method = "clearColor", at = @At("HEAD"), require = 0,
      remap = false)
  private static void metalrender$clearColor(float red, float green,
      float blue, float alpha, CallbackInfo ci) {
    IrisRenderGraphCapture.global().legacyClearColor(red, green, blue, alpha);
  }

  @Inject(method = "vertexAttrib4f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$genericVertexAttribute(int location,
      float x, float y, float z, float w, CallbackInfo ci) {
    IrisGlGenericAttributeTracker.global().vertexAttribute4f(location,
        x, y, z, w);
  }

  @Inject(method = "bindBuffer", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindBuffer(int target, int buffer,
      CallbackInfo ci) {
    metalrender$vertices().bindBuffer(target, buffer);
  }

  @Inject(method = "bufferData", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bufferData(int target, float[] values,
      int usage, CallbackInfo ci) {
    if (!com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror
        .isEnabled() || values == null || values.length == 0
        || values.length > Integer.MAX_VALUE / Float.BYTES) {
      return;
    }
    int buffer = metalrender$vertices().boundBuffer(target);
    int bytes = values.length * Float.BYTES;
    ByteBuffer encoded = ByteBuffer.allocate(bytes)
        .order(ByteOrder.nativeOrder());
    encoded.asFloatBuffer().put(values);
    com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror mirror =
        com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror.global();
    if (mirror.allocate(buffer, bytes)) {
      mirror.write(buffer, bytes, 0, bytes, encoded);
    }
  }

  @Inject(method = "bufferStorage(I[FI)I", at = @At("RETURN"),
      require = 0, remap = false)
  private static void metalrender$bufferStorage(int target, float[] values,
      int flags, CallbackInfoReturnable<Integer> callback) {
    if (!com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror
        .isEnabled() || values == null || values.length == 0
        || values.length > Integer.MAX_VALUE / Float.BYTES) {
      return;
    }
    int buffer = callback.getReturnValue();
    int bytes = values.length * Float.BYTES;
    ByteBuffer encoded = ByteBuffer.allocate(bytes)
        .order(ByteOrder.nativeOrder());
    encoded.asFloatBuffer().put(values);
    com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror mirror =
        com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror.global();
    if (mirror.allocate(buffer, bytes)) {
      mirror.write(buffer, bytes, 0, bytes, encoded);
    }
  }

  @Inject(method = "bufferStorage(IJI)V", at = @At("RETURN"),
      require = 0, remap = false)
  private static void metalrender$bufferStorageSize(int target, long size,
      int flags, CallbackInfo ci) {
    if (com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror
        .isEnabled()) {
      com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror.global()
          .allocate(metalrender$vertices().boundBuffer(target), size);
    }
  }

  @Inject(method = "deleteBuffers", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$deleteBuffer(int buffer, CallbackInfo ci) {
    metalrender$vertices().deleteBuffer(buffer);
    com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror.global()
        .delete(buffer);
  }

  @Inject(method = "memoryBarrier", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$memoryBarrier(int barriers,
      CallbackInfo ci) {
    IrisRenderGraphCapture.global().memoryBarrier(barriers);
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "blitFramebuffer", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$blitFramebuffer(int source, int destination,
      int sourceX0, int sourceY0, int sourceX1, int sourceY1,
      int destinationX0, int destinationY0, int destinationX1,
      int destinationY1, int mask, int filter, CallbackInfo ci) {
    IrisRenderGraphCapture.global().blitFramebuffer(source, destination,
        sourceX0, sourceY0, sourceX1, sourceY1, destinationX0,
        destinationY0, destinationX1, destinationY1, mask, filter);
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "copyTexImage2D", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$copyTexImage2D(int target, int level,
      int internalFormat, int x, int y, int width, int height, int border,
      CallbackInfo ci) {
    IrisRenderGraphCapture.global().copyBoundTexture(target, level,
        internalFormat, x, y, width, height, border,
        org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL11C.GL_READ_BUFFER));
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "copyTexSubImage2D", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$copyTexSubImage2D(int destination,
      int target, int level, int destinationX, int destinationY, int sourceX,
      int sourceY, int width, int height, CallbackInfo ci) {
    IrisRenderGraphCapture.global().copyTexture(destination, target, level,
        destinationX, destinationY, sourceX, sourceY, width, height,
        org.lwjgl.opengl.GL11C.glGetInteger(
            org.lwjgl.opengl.GL11C.GL_READ_BUFFER));
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "generateMipmaps", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$generateMipmaps(int texture, int target,
      CallbackInfo ci) {
    IrisRenderGraphCapture.global().generateMipmaps(texture, target);
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "clearBufferfv", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$clearBufferFloat(int framebuffer,
      int buffer, int drawBuffer, float[] values, CallbackInfo ci) {
    IrisRenderGraphCapture.global().clearNamedFramebufferFloat(framebuffer,
        buffer, drawBuffer, values);
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "clearBufferiv", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$clearBufferSignedInt(int framebuffer,
      int buffer, int drawBuffer, int[] values, CallbackInfo ci) {
    IrisRenderGraphCapture.global().clearNamedFramebufferSignedInt(
        framebuffer, buffer, drawBuffer, values);
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "clearBufferuiv", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$clearBufferUnsignedInt(int framebuffer,
      int buffer, int drawBuffer, int[] values, CallbackInfo ci) {
    IrisRenderGraphCapture.global().clearNamedFramebufferUnsignedInt(
        framebuffer, buffer, drawBuffer, values);
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "clearBufferSubData", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$clearBufferSubData(int target,
      int internalFormat, long offset, long size, int format, int type,
      int[] values, CallbackInfo ci) {
    if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
        "graph-ownership-buffer-clear-unimplemented")) {
      ci.cancel();
    }
  }

  @Inject(method = "createFramebuffer", at = @At("RETURN"),
      require = 0, remap = false)
  private static void metalrender$createFramebuffer(
      CallbackInfoReturnable<Integer> callback) {
    int framebuffer = callback.getReturnValue();
    if (framebuffer > 0) {
      metalrender$state().defineFramebuffer(framebuffer);
    }
  }

  @Inject(method = "texImage2D", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$texImage2D(int texture, int target,
      int level, int internalFormat, int width, int height, int border,
      int format, int type, ByteBuffer pixels, CallbackInfo ci) {
    if (texture > 0 && level == 0) {
      IrisGlSamplerMirror.global().defineTexture(texture);
      String cacheFormat = IrisGlFormat.cacheName(internalFormat)
          .orElseGet(() -> "gl-0x" + Integer.toHexString(internalFormat));
      metalrender$state().defineTexture(texture, cacheFormat, 1,
          width, height, 1, Math.max(1, level + 1));
      if (com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror
          .isEnabled()) {
        int bytesPerPixel = IrisGlFormat.bytesPerPixel(internalFormat)
            .orElse(0);
        if (bytesPerPixel > 0 && IrisGlTextureMirror.global().define(texture,
            cacheFormat, width, height, 1, Math.max(1, level + 1),
            bytesPerPixel) && pixels != null
            && IrisGlFormat.exactUploadBytesPerPixel(internalFormat, format,
                type).orElse(0) == bytesPerPixel
            && ((long) width * bytesPerPixel) % 4 == 0) {
          IrisGlTextureMirror.global().write(texture, level, 0, 0, 0,
              width, height, width, pixels);
        }
      }
    }
  }

  @Inject(method = "texImage1D", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$texImage1D(int texture, int target,
      int level, int internalFormat, int width, int border, int format,
      int type, ByteBuffer pixels, CallbackInfo ci) {
    if (texture <= 0 || level < 0 || width <= 0) {
      return;
    }
    int bytesPerPixel = IrisGlFormat.exactUploadBytesPerPixel(
        internalFormat, format, type).orElse(0);
    if (bytesPerPixel <= 0) {
      bytesPerPixel = IrisGlFormat.bytesPerPixel(internalFormat).orElse(0);
    }
    if (bytesPerPixel <= 0) {
      return;
    }
    String cacheFormat = IrisGlFormat.cacheName(internalFormat)
        .orElseGet(() -> "gl-0x" + Integer.toHexString(internalFormat));
    if (!IrisGlTextureMirror.global().define(texture, cacheFormat, width, 1,
        1, Math.max(1, level + 1), bytesPerPixel)) {
      return;
    }
    if (pixels != null && level == 0
        && pixels.remaining() >= width * bytesPerPixel) {
      IrisGlTextureMirror.global().write(texture, level, 0, 0, 0, width, 1,
          width, pixels);
    }
  }

  @Inject(method = "texImage3D", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$texImage3D(int texture, int target,
      int level, int internalFormat, int width, int height, int depth,
      int border, int format, int type, ByteBuffer pixels,
      CallbackInfo ci) {
    if (texture <= 0 || level < 0 || width <= 0 || height <= 0
        || depth <= 0) {
      return;
    }
    int bytesPerPixel = IrisGlFormat.exactUploadBytesPerPixel(
        internalFormat, format, type).orElse(0);
    if (bytesPerPixel <= 0) {
      bytesPerPixel = IrisGlFormat.bytesPerPixel(internalFormat).orElse(0);
    }
    if (bytesPerPixel <= 0) {
      return;
    }
    String cacheFormat = IrisGlFormat.cacheName(internalFormat)
        .orElseGet(() -> "gl-0x" + Integer.toHexString(internalFormat));
    if (!IrisGlTextureMirror.global().define(texture, cacheFormat, width,
        height, depth, Math.max(1, level + 1), bytesPerPixel)) {
      return;
    }
    if (pixels == null || level != 0) {
      return;
    }

    long layerBytes = (long) width * height * bytesPerPixel;
    if (layerBytes > Integer.MAX_VALUE
        || layerBytes > pixels.remaining()) {
      return;
    }
    for (int layer = 0; layer < depth; layer++) {
      long start = (long) pixels.position() + layer * layerBytes;
      if (start > Integer.MAX_VALUE
          || start < 0
          || start + layerBytes > (long) pixels.limit()) {
        return;
      }
      ByteBuffer slice = pixels.duplicate();
      slice.position(Math.toIntExact(start));
      slice.limit(Math.toIntExact(start + layerBytes));
      IrisGlTextureMirror.global().write(texture, level, layer, 0, 0, width,
          height, width, slice.slice());
    }
  }

  @Inject(method = "framebufferTexture2D", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$framebufferTexture2D(int framebuffer,
      int target, int attachment, int textureTarget, int texture,
      int mipLevel, CallbackInfo ci) {
    metalrender$state().framebufferTexture2DForFramebuffer(framebuffer,
        attachment, textureTarget, texture, mipLevel);
  }

  @Inject(method = "drawBuffers", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$drawBuffers(int framebuffer, int[] buffers,
      CallbackInfo ci) {
    metalrender$state().drawBuffersForFramebuffer(framebuffer, buffers);
  }

  @Inject(method = "enableBufferBlend", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$enableBufferBlend(int index,
      CallbackInfo ci) {
    metalrender$state().capabilityIndexed(IrisGlStateTracker.GL_BLEND, index,
        true);
  }

  @Inject(method = "disableBufferBlend", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$disableBufferBlend(int index,
      CallbackInfo ci) {
    metalrender$state().capabilityIndexed(IrisGlStateTracker.GL_BLEND, index,
        false);
  }

  @Inject(method = "blendFuncSeparatei", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$blendFuncSeparateIndexed(int index,
      int sourceRgb, int destinationRgb, int sourceAlpha,
      int destinationAlpha, CallbackInfo ci) {
    metalrender$state().blendFuncSeparateIndexed(index, sourceRgb,
        destinationRgb, sourceAlpha, destinationAlpha);
  }

  @Inject(method = "bindTextureToUnit", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindTextureToUnit(int target, int unit,
      int texture, CallbackInfo ci) {
    metalrender$resources().bindTextureToUnit(target, unit, texture);
  }

  @Inject(method = "createTexture", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$createTexture(int target,
      CallbackInfoReturnable<Integer> callback) {
    IrisGlSamplerMirror.global().defineTexture(callback.getReturnValue());
  }

  @Inject(method = "texParameteri", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$textureParameteri(int texture, int target,
      int pname, int value, CallbackInfo ci) {
    IrisGlSamplerMirror.global().textureParameteri(texture, pname, value);
  }

  @Inject(method = "texParameterf", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$textureParameterf(int texture, int target,
      int pname, float value, CallbackInfo ci) {
    IrisGlSamplerMirror.global().textureParameterf(texture, pname, value);
  }

  @Inject(method = "texParameteriv", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$textureParameteriv(int texture, int target,
      int pname, int[] values, CallbackInfo ci) {
    IrisGlSamplerMirror.global().textureParameteriv(texture, pname, values);
  }

  @Inject(method = "texParameterivDirect", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$textureParameterivDirect(int target,
      int pname, int[] values, CallbackInfo ci) {
    com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot
        .TextureUnitBinding binding = metalrender$resources()
            .activeTextureBinding(target);
    if (binding != null && binding.texture() > 0) {
      IrisGlSamplerMirror.global().textureParameteriv(binding.texture(),
          pname, values);
    }
  }

  @Inject(method = "bindSamplerToUnit", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindSamplerToUnit(int unit, int sampler,
      CallbackInfo ci) {
    metalrender$resources().bindSamplerToUnit(unit, sampler);
  }

  @Inject(method = "genSampler", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$generateSampler(
      CallbackInfoReturnable<Integer> callback) {
    IrisGlSamplerMirror.global().defineSampler(callback.getReturnValue());
  }

  @Inject(method = "destroySampler", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$destroySampler(int sampler,
      CallbackInfo ci) {
    IrisGlSamplerMirror.global().deleteSampler(sampler);
  }

  @Inject(method = "unbindAllSamplers", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$unbindAllSamplers(CallbackInfo ci) {
    metalrender$resources().unbindAllSamplers();
  }

  @Inject(method = "samplerParameteri", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$samplerParameteri(int sampler, int pname,
      int value, CallbackInfo ci) {
    IrisGlSamplerMirror.global().samplerParameteri(sampler, pname, value);
  }

  @Inject(method = "samplerParameterf", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$samplerParameterf(int sampler, int pname,
      float value, CallbackInfo ci) {
    IrisGlSamplerMirror.global().samplerParameterf(sampler, pname, value);
  }

  @Inject(method = "samplerParameteriv", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$samplerParameteriv(int sampler, int pname,
      int[] values, CallbackInfo ci) {
    IrisGlSamplerMirror.global().samplerParameteriv(sampler, pname, values);
  }

  @Inject(method = "bindImageTexture", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindImageTexture(int unit, int texture,
      int level, boolean layered, int layer, int access, int format,
      CallbackInfo ci) {
    metalrender$resources().bindImageTexture(unit, texture, level, layered,
        layer, access, format);
  }

  @Inject(method = "bindBufferBase", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$bindBufferBase(int target, Integer index,
      int buffer, CallbackInfo ci) {
    if (index != null) {
      metalrender$resources().bindBufferBase(target, index, buffer);
    }
  }

  @Inject(method = "getUniformBlockIndex", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniformBlockIndex(int program, String name,
      CallbackInfoReturnable<Integer> callback) {
    metalrender$resources().uniformBlockIndex(program, name,
        callback.getReturnValue());
  }

  @Inject(method = "uniformBlockBinding", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniformBlockBinding(int program,
      int blockIndex, int binding, CallbackInfo ci) {
    metalrender$resources().uniformBlockBinding(program, blockIndex, binding);
  }

  @Inject(method = "uniform1f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform1f(int location, float x,
      CallbackInfo ci) {
    metalrender$resources().uniformFloats(location, x);
  }

  @Inject(method = "uniform2f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform2f(int location, float x, float y,
      CallbackInfo ci) {
    metalrender$resources().uniformFloats(location, x, y);
  }

  @Inject(method = "uniform3f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform3f(int location, float x, float y,
      float z, CallbackInfo ci) {
    metalrender$resources().uniformFloats(location, x, y, z);
  }

  @Inject(method = "uniform4f", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform4f(int location, float x, float y,
      float z, float w, CallbackInfo ci) {
    metalrender$resources().uniformFloats(location, x, y, z, w);
  }

  @Inject(method = "uniform2i", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform2i(int location, int x, int y,
      CallbackInfo ci) {
    metalrender$resources().uniformInts(location, x, y);
  }

  @Inject(method = "uniform3i", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform3i(int location, int x, int y,
      int z, CallbackInfo ci) {
    metalrender$resources().uniformInts(location, x, y, z);
  }

  @Inject(method = "uniform4i", at = @At("RETURN"), require = 0,
      remap = false)
  private static void metalrender$uniform4i(int location, int x, int y,
      int z, int w, CallbackInfo ci) {
    metalrender$resources().uniformInts(location, x, y, z, w);
  }

  @Inject(method = "uniformMatrix3fv(IZLjava/nio/FloatBuffer;)V",
      at = @At("RETURN"), require = 0, remap = false)
  private static void metalrender$uniformMatrix3(int location,
      boolean transpose, FloatBuffer values, CallbackInfo ci) {
    metalrender$resources().uniformMatrix(location, 3, values);
  }

  @Inject(method = "uniformMatrix3fv(IZ[F)V", at = @At("RETURN"),
      require = 0, remap = false)
  private static void metalrender$uniformMatrix3Array(int location,
      boolean transpose, float[] values, CallbackInfo ci) {
    metalrender$resources().uniformMatrix(location, 3, values);
  }

  @Inject(method = "uniformMatrix4fv(IZLjava/nio/FloatBuffer;)V",
      at = @At("RETURN"), require = 0, remap = false)
  private static void metalrender$uniformMatrix4(int location,
      boolean transpose, FloatBuffer values, CallbackInfo ci) {
    metalrender$resources().uniformMatrix(location, 4, values);
  }

  @Inject(method = "uniformMatrix4fv(IZ[F)V", at = @At("RETURN"),
      require = 0, remap = false)
  private static void metalrender$uniformMatrix4Array(int location,
      boolean transpose, float[] values, CallbackInfo ci) {
    metalrender$resources().uniformMatrix(location, 4, values);
  }

  @Inject(method = "dispatchCompute(III)V", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$dispatchCompute(int x, int y, int z,
      CallbackInfo ci) {
    try {
      metalrender$capture().dispatch(
          new IrisExecutionCommand.Dispatch(x, y, z));
    } catch (IllegalArgumentException error) {
      metalrender$capture().dispatch();
    }
    if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
        "graph-ownership-compute-dispatch-unimplemented")) {
      ci.cancel();
    }
  }

  @Inject(method = "dispatchCompute(Lorg/joml/Vector3i;)V",
      at = @At("HEAD"), require = 0, remap = false, cancellable = true)
  private static void metalrender$dispatchComputeVector(Vector3i groups,
      CallbackInfo ci) {
    metalrender$dispatchCompute(groups.x, groups.y, groups.z, ci);
  }

  @Inject(method = "dispatchComputeIndirect", at = @At("HEAD"), require = 0,
      remap = false, cancellable = true)
  private static void metalrender$dispatchComputeIndirect(long offset,
      CallbackInfo ci) {
    try {
      metalrender$capture().dispatch(
          new IrisExecutionCommand.IndirectDispatch(offset));
    } catch (IllegalArgumentException error) {
      metalrender$capture().dispatch();
    }
    if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
        "graph-ownership-indirect-dispatch-unimplemented")) {
      ci.cancel();
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.pebbles_boon.metalrender.compat.iris.IrisExecutionCommand;
import com.pebbles_boon.metalrender.compat.iris.IrisDynamicDrawStateTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlTextureMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot;
import com.pebbles_boon.metalrender.compat.iris.IrisGlVertexArrayTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisGlSamplerMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisVisualParityCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.system.MemoryUtil;

/**
 * Observes the Mojang OpenGL facade. Calls remain untouched until the
 * separately validated full-graph ownership gate arms one complete frame.
 */
@Mixin(GlStateManager.class)
public abstract class IrisGlStateManagerMixin {
  private static final ThreadLocal<Boolean> METALRENDER_MOJANG_DRAW_SCOPE =
      new ThreadLocal<>();
  private static final ThreadLocal<Integer> METALRENDER_MOJANG_BUFFER_DATA_SCOPE =
      ThreadLocal.withInitial(() -> 0);
  private static final ThreadLocal<Integer> METALRENDER_MOJANG_BUFFER_SUBDATA_SCOPE =
      ThreadLocal.withInitial(() -> 0);

  public static boolean metalrender$isMojangBufferDataActive() {
    return METALRENDER_MOJANG_BUFFER_DATA_SCOPE.get() > 0;
  }

  public static boolean metalrender$isMojangBufferSubDataActive() {
    return METALRENDER_MOJANG_BUFFER_SUBDATA_SCOPE.get() > 0;
  }

  private static void metalrender$enterBufferDataScope() {
    METALRENDER_MOJANG_BUFFER_DATA_SCOPE.set(
        METALRENDER_MOJANG_BUFFER_DATA_SCOPE.get() + 1);
  }

  private static void metalrender$exitBufferDataScope() {
    int depth = METALRENDER_MOJANG_BUFFER_DATA_SCOPE.get() - 1;
    if (depth <= 0) {
      METALRENDER_MOJANG_BUFFER_DATA_SCOPE.remove();
    } else {
      METALRENDER_MOJANG_BUFFER_DATA_SCOPE.set(depth);
    }
  }

  private static IrisPipelineStateCapture metalrender$capture() {
    return IrisPipelineStateCapture.global();
  }

  private static IrisGlStateTracker metalrender$state() {
    return metalrender$capture().tracker();
  }

  private static IrisGlResourceBindingTracker metalrender$resources() {
    return IrisGlResourceBindingTracker.global();
  }

  private static IrisDynamicDrawStateTracker metalrender$dynamic() {
    return IrisDynamicDrawStateTracker.global();
  }

  private static IrisGlVertexArrayTracker metalrender$vertices() {
    return IrisGlVertexArrayTracker.global();
  }

  @Inject(method = "_clear", at = @At("HEAD"), cancellable = true)
  private static void metalrender$clear(int mask, boolean checkError,
      CallbackInfo ci) {
    boolean captured = IrisRenderGraphCapture.global()
        .legacyClearBoundFramebuffer(mask);
    if (captured
        && IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "_glBindVertexArray", at = @At("TAIL"))
  private static void metalrender$bindVertexArray(int vertexArray,
      CallbackInfo ci) {
    metalrender$vertices().bindVertexArray(vertexArray);
  }

  @Inject(method = "_glBindBuffer", at = @At("TAIL"))
  private static void metalrender$bindBuffer(int target, int buffer,
      CallbackInfo ci) {
    metalrender$vertices().bindBuffer(target, buffer);
  }

  @Inject(method = "_glBufferData(ILjava/nio/ByteBuffer;I)V",
      at = @At("HEAD"))
  private static void metalrender$bufferDataEnter(int target,
      ByteBuffer bytes, int usage, CallbackInfo ci) {
    metalrender$enterBufferDataScope();
  }

  @Inject(method = "_glBufferData(ILjava/nio/ByteBuffer;I)V",
      at = @At("RETURN"))
  private static void metalrender$bufferData(int target, ByteBuffer bytes,
      int usage, CallbackInfo ci) {
    try {
      if (!IrisGlBufferMirror.isEnabled()) {
        return;
      }
      int buffer = metalrender$vertices().boundBuffer(target);
      if (buffer <= 0) {
        return;
      }
      IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
      if (bytes == null || !bytes.hasRemaining()) {
        // glBufferData replaces the previous storage even when the supplied
        // ByteBuffer is null/empty. Drop the old shadow rather than allowing
        // stale geometry to satisfy a later replay lookup.
        mirror.delete(buffer);
        return;
      }
      int length = bytes.remaining();
      if (!mirror.allocate(buffer, length)
          || !mirror.write(buffer, length, 0, length, bytes)) {
        IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
            "graph-frame-buffer-upload-mirror-rejected");
      }
    } finally {
      metalrender$exitBufferDataScope();
    }
  }

  @Inject(method = "_glBufferData(IJI)V", at = @At("HEAD"))
  private static void metalrender$bufferDataSizeEnter(int target, long size,
      int usage, CallbackInfo ci) {
    metalrender$enterBufferDataScope();
  }

  @Inject(method = "_glBufferSubData", at = @At("HEAD"),
      require = 0)
  private static void metalrender$bufferSubDataEnter(int target, int offset,
      ByteBuffer bytes, CallbackInfo ci) {
    METALRENDER_MOJANG_BUFFER_SUBDATA_SCOPE.set(
        METALRENDER_MOJANG_BUFFER_SUBDATA_SCOPE.get() + 1);
  }

  @Inject(method = "_glBufferSubData", at = @At("RETURN"),
      require = 0)
  private static void metalrender$bufferSubDataExit(int target, int offset,
      ByteBuffer bytes, CallbackInfo ci) {
    int depth = METALRENDER_MOJANG_BUFFER_SUBDATA_SCOPE.get() - 1;
    if (depth <= 0) {
      METALRENDER_MOJANG_BUFFER_SUBDATA_SCOPE.remove();
    } else {
      METALRENDER_MOJANG_BUFFER_SUBDATA_SCOPE.set(depth);
    }
  }

  @Inject(method = "_glBufferSubData", at = @At("RETURN"),
      require = 0)
  private static void metalrender$bufferSubData(int target, int offset,
      ByteBuffer bytes, CallbackInfo ci) {
    if (!IrisGlBufferMirror.isEnabled() || bytes == null
        || !bytes.hasRemaining()) {
      return;
    }
    int buffer = metalrender$vertices().boundBuffer(target);
    if (buffer <= 0) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-buffer-subdata-binding-unavailable");
      return;
    }
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    long size = mirror.size(buffer);
    if (size <= 0 || offset < 0
        || !mirror.write(buffer, size, offset, bytes.remaining(), bytes)) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-buffer-subdata-mirror-rejected");
    }
  }

  @Inject(method = "_glBufferData(IJI)V", at = @At("RETURN"))
  private static void metalrender$bufferDataSize(int target, long size,
      int usage, CallbackInfo ci) {
    if (IrisGlBufferMirror.isEnabled()) {
      int buffer = metalrender$vertices().boundBuffer(target);
      if (buffer > 0 && !IrisGlBufferMirror.global().allocate(buffer, size)) {
        IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
            "graph-frame-buffer-allocation-mirror-rejected");
      }
    }
    metalrender$exitBufferDataScope();
  }

  @Inject(method = "_glDeleteVertexArrays", at = @At("TAIL"),
      require = 0)
  private static void metalrender$deleteVertexArray(int vertexArray,
      CallbackInfo ci) {
    metalrender$vertices().deleteVertexArray(vertexArray);
  }

  @Inject(method = "_glDeleteBuffers", at = @At("TAIL"))
  private static void metalrender$deleteBuffer(int buffer, CallbackInfo ci) {
    metalrender$vertices().deleteBuffer(buffer);
    metalrender$resources().deleteBuffer(buffer);
    IrisGlBufferMirror.global().delete(buffer);
  }

  @Inject(method = "_vertexAttribPointer", at = @At("TAIL"))
  private static void metalrender$vertexAttribute(int location, int size,
      int type, boolean normalized, int stride, long pointer,
      CallbackInfo ci) {
    metalrender$vertices().vertexAttribute(location, size, type, normalized,
        stride, pointer, false);
  }

  @Inject(method = "_vertexAttribIPointer", at = @At("TAIL"))
  private static void metalrender$integerVertexAttribute(int location,
      int size, int type, int stride, long pointer, CallbackInfo ci) {
    metalrender$vertices().vertexAttribute(location, size, type, false,
        stride, pointer, true);
  }

  @Inject(method = "_enableVertexAttribArray", at = @At("TAIL"))
  private static void metalrender$enableVertexAttribute(int location,
      CallbackInfo ci) {
    metalrender$vertices().enableAttribute(location);
  }

  @Inject(method = "_viewport", at = @At("TAIL"))
  private static void metalrender$viewport(int x, int y, int width,
      int height, CallbackInfo ci) {
    metalrender$dynamic().viewport(x, y, width, height);
  }

  @Inject(method = "_enableScissorTest", at = @At("TAIL"))
  private static void metalrender$enableScissor(CallbackInfo ci) {
    metalrender$dynamic().scissorEnabled(true);
  }

  @Inject(method = "_disableScissorTest", at = @At("TAIL"))
  private static void metalrender$disableScissor(CallbackInfo ci) {
    metalrender$dynamic().scissorEnabled(false);
  }

  @Inject(method = "_scissorBox", at = @At("TAIL"))
  private static void metalrender$scissor(int x, int y, int width,
      int height, CallbackInfo ci) {
    metalrender$dynamic().scissor(x, y, width, height);
  }

  @Inject(method = "_glUseProgram", at = @At("TAIL"))
  private static void metalrender$useProgram(int program, CallbackInfo ci) {
    metalrender$capture().useProgram(program);
  }

  @Inject(method = "_glGetUniformLocation", at = @At("RETURN"))
  private static void metalrender$uniformLocation(int program,
      CharSequence name, CallbackInfoReturnable<Integer> callback) {
    metalrender$resources().uniformLocation(program, name,
        callback.getReturnValue());
  }

  @Inject(method = "_glUniform1i", at = @At("TAIL"))
  private static void metalrender$uniform1i(int location, int value,
      CallbackInfo ci) {
    metalrender$resources().uniformInts(location, value);
  }

  @Inject(method = "glDeleteProgram", at = @At("TAIL"))
  private static void metalrender$deleteProgram(int program, CallbackInfo ci) {
    IrisShaderCapture.deleteProgram(program);
  }

  @Inject(method = "_enableDepthTest", at = @At("TAIL"))
  private static void metalrender$enableDepth(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_DEPTH_TEST, true);
  }

  @Inject(method = "_disableDepthTest", at = @At("TAIL"))
  private static void metalrender$disableDepth(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_DEPTH_TEST, false);
  }

  @Inject(method = "_depthFunc", at = @At("TAIL"))
  private static void metalrender$depthFunc(int function, CallbackInfo ci) {
    metalrender$state().depthFunc(function);
  }

  @Inject(method = "_depthMask", at = @At("TAIL"))
  private static void metalrender$depthMask(boolean enabled, CallbackInfo ci) {
    metalrender$state().depthMask(enabled);
  }

  /** Minecraft 1.21.1 exposes blend enable as a global state. */
  @Inject(method = "_enableBlend", at = @At("TAIL"), require = 0)
  private static void metalrender$enableBlendGlobal(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_BLEND, true);
  }

  /** Minecraft 1.21.1 exposes blend disable as a global state. */
  @Inject(method = "_disableBlend", at = @At("TAIL"), require = 0)
  private static void metalrender$disableBlendGlobal(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_BLEND, false);
  }

  @Inject(method = "_blendFuncSeparate", at = @At("TAIL"))
  private static void metalrender$blendFuncSeparate(int sourceRgb,
      int destinationRgb, int sourceAlpha, int destinationAlpha,
      CallbackInfo ci) {
    metalrender$state().blendFuncSeparate(sourceRgb, destinationRgb,
        sourceAlpha, destinationAlpha);
  }

  /** Minecraft 1.21.1 stores one blend equation for both RGB and alpha. */
  @Inject(method = "_blendEquation", at = @At("TAIL"), require = 0)
  private static void metalrender$blendEquation(int equation,
      CallbackInfo ci) {
    metalrender$state().blendEquationSeparate(equation, equation);
  }

  @Inject(method = "_enableCull", at = @At("TAIL"))
  private static void metalrender$enableCull(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_CULL_FACE, true);
  }

  @Inject(method = "_disableCull", at = @At("TAIL"))
  private static void metalrender$disableCull(CallbackInfo ci) {
    metalrender$state().capability(IrisGlStateTracker.GL_CULL_FACE, false);
  }

  @Inject(method = "_polygonMode", at = @At("TAIL"))
  private static void metalrender$polygonMode(int face, int mode,
      CallbackInfo ci) {
    metalrender$state().polygonMode(face, mode);
  }

  @Inject(method = "_enablePolygonOffset", at = @At("TAIL"))
  private static void metalrender$enablePolygonOffset(CallbackInfo ci) {
    metalrender$state().capability(
        IrisGlStateTracker.GL_POLYGON_OFFSET_FILL, true);
  }

  @Inject(method = "_disablePolygonOffset", at = @At("TAIL"))
  private static void metalrender$disablePolygonOffset(CallbackInfo ci) {
    metalrender$state().capability(
        IrisGlStateTracker.GL_POLYGON_OFFSET_FILL, false);
  }

  @Inject(method = "_polygonOffset", at = @At("TAIL"))
  private static void metalrender$polygonOffset(float factor, float units,
      CallbackInfo ci) {
    metalrender$state().polygonOffset(factor, units);
  }

  /** Minecraft 1.21.1 exposes a single four-boolean color mask. */
  @Inject(method = "_colorMask(ZZZZ)V", at = @At("TAIL"), require = 0)
  private static void metalrender$colorMaskGlobal(boolean red, boolean green,
      boolean blue, boolean alpha, CallbackInfo ci) {
    metalrender$state().colorMask(red, green, blue, alpha);
  }

  @Inject(method = "glGenFramebuffers", at = @At("RETURN"))
  private static void metalrender$genFramebuffer(
      CallbackInfoReturnable<Integer> callback) {
    int framebuffer = callback.getReturnValue();
    if (framebuffer > 0) {
      metalrender$state().defineFramebuffer(framebuffer);
    }
  }

  @Inject(method = "_glBindFramebuffer", at = @At("TAIL"))
  private static void metalrender$bindFramebuffer(int target, int framebuffer,
      CallbackInfo ci) {
    metalrender$state().bindFramebuffer(target, framebuffer);
  }

  @Inject(method = "_glFramebufferTexture2D", at = @At("TAIL"))
  private static void metalrender$framebufferTexture2D(int target,
      int attachment, int textureTarget, int texture, int mipLevel,
      CallbackInfo ci) {
    metalrender$state().framebufferTexture2D(target, attachment,
        textureTarget, texture, mipLevel);
  }

  @Inject(method = "_glDeleteFramebuffers", at = @At("TAIL"))
  private static void metalrender$deleteFramebuffer(int framebuffer,
      CallbackInfo ci) {
    metalrender$state().deleteFramebuffer(framebuffer);
  }

  /**
   * Mirrors the classic 1.21.1 texture allocation entry point. The texture
   * name is recovered from the active texture unit because this API has no
   * texture-id argument.
   */
  @Inject(method = "_texImage2D", at = @At("RETURN"))
  private static void metalrender$texImage2D(int target, int level,
      int internalFormat, int width, int height, int border,
      int format, int type, IntBuffer pixels, CallbackInfo ci) {
    if (!IrisGlTextureMirror.isEnabled() || target != 0x0DE1
        || level != 0 || width <= 0 || height <= 0) {
      return;
    }
    IrisGlResourceBindingSnapshot.TextureUnitBinding binding =
        metalrender$resources().activeTextureBinding(target);
    if (binding == null || binding.texture() <= 0) {
      return;
    }
    int texture = binding.texture();
    int bytesPerPixel = IrisGlFormat.bytesPerPixel(internalFormat).orElse(0);
    if (bytesPerPixel <= 0) {
      bytesPerPixel = IrisGlFormat.exactUploadBytesPerPixel(
          internalFormat, format, type).orElse(0);
    }
    if (bytesPerPixel <= 0) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-texture-allocation-format-unmirrored");
      return;
    }
    String cacheFormat = IrisGlFormat.cacheName(internalFormat)
        .orElseGet(() -> "gl-0x" + Integer.toHexString(internalFormat));
    if (!IrisGlTextureMirror.global().define(texture, cacheFormat,
        width, height, 1, 1, bytesPerPixel)) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-texture-allocation-mirror-rejected");
      return;
    }
    if (pixels == null || bytesPerPixel != Integer.BYTES) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-texture-allocation-pixels-unavailable");
      return;
    }
    long required = (long) width * height * bytesPerPixel;
    if (required > pixels.remaining() * (long) Integer.BYTES
        || required > Integer.MAX_VALUE) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-texture-allocation-pixels-size-invalid");
      return;
    }
    ByteBuffer encoded = ByteBuffer.allocateDirect(
        Math.toIntExact(required)).order(ByteOrder.nativeOrder());
    IntBuffer copy = encoded.asIntBuffer();
    IntBuffer source = pixels.duplicate();
    source.limit(source.position() + Math.toIntExact(
        required / Integer.BYTES));
    copy.put(source);
    encoded.limit(Math.toIntExact(required));
    if (!IrisGlTextureMirror.global().write(texture, 0, 0, 0, 0,
        width, height, width, encoded)) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-texture-allocation-upload-rejected");
    }
  }

  /**
   * Mirrors both direct native-memory uploads (the normal NativeImage path)
   * and PBO-backed uploads when a pixel-unpack buffer is bound.
   */
  @Inject(method = "_texSubImage2D", at = @At("RETURN"), require = 0)
  private static void metalrender$texSubImage2D(int target, int level,
      int xOffset, int yOffset, int width, int height, int format,
      int type, long pixels, CallbackInfo ci) {
    if (!IrisGlTextureMirror.isEnabled() || target != 0x0DE1
        || level < 0 || width <= 0 || height <= 0 || pixels < 0) {
      return;
    }

    IrisGlResourceBindingSnapshot.TextureUnitBinding binding =
        metalrender$resources().activeTextureBinding(target);
    if (binding == null || binding.texture() <= 0) {
      return;
    }
    int texture = binding.texture();
    IrisGlTextureMirror mirror = IrisGlTextureMirror.global();
    var metadata = mirror.metadata(texture, level, 0).orElse(null);
    if (metadata == null) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-texture-upload-metadata-unavailable");
      return;
    }

    int bytesPerPixel = metadata.bytesPerPixel();
    long rowBytes = (long) width * bytesPerPixel;
    long required = rowBytes * height;
    if (rowBytes <= 0 || required <= 0 || required > Integer.MAX_VALUE
        || (rowBytes & 3L) != 0) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-texture-upload-range-invalid");
      return;
    }

    try {
      int pbo = GL15C.glGetInteger(GL15C.GL_PIXEL_UNPACK_BUFFER_BINDING);
      if (pbo == 0 && pixels == 0) {
        IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
            "graph-frame-texture-upload-pixels-unavailable");
        return;
      }
      ByteBuffer source;
      if (pbo > 0) {
        long generation = IrisGlBufferMirror.global().generation(pbo);
        var snapshot = IrisGlBufferMirror.global().snapshot(
            pbo, generation, pixels, required).orElse(null);
        if (snapshot == null) {
          IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
              "graph-frame-texture-upload-pbo-range-unmirrored");
          return;
        }
        source = ByteBuffer.wrap(snapshot.bytes());
      } else {
        source = MemoryUtil.memByteBuffer(pixels, Math.toIntExact(required));
      }
      if (!mirror.write(texture, level, 0, xOffset, yOffset, width, height,
          width, source)) {
        IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
            "graph-frame-texture-upload-mirror-rejected");
      }
    } catch (RuntimeException ignored) {
      IrisRenderGraphCapture.global().markUnsupportedFullReplayOperation(
          "graph-frame-texture-upload-mirror-failed");
    }
  }

  @Inject(method = "_deleteTexture", at = @At("TAIL"))
  private static void metalrender$deleteTexture(int texture, CallbackInfo ci) {
    metalrender$state().deleteTexture(texture);
    metalrender$resources().deleteTexture(texture);
    IrisGlTextureMirror.global().delete(texture);
    IrisGlSamplerMirror.global().deleteTexture(texture);
  }

  @Inject(method = "_activeTexture", at = @At("TAIL"))
  private static void metalrender$activeTexture(int texture, CallbackInfo ci) {
    metalrender$resources().activeTexture(texture);
  }

  @Inject(method = "_bindTexture", at = @At("TAIL"))
  private static void metalrender$bindTexture(int texture, CallbackInfo ci) {
    metalrender$resources().bindTexture(texture);
  }

  @Inject(method = "_texParameter", at = @At("TAIL"))
  private static void metalrender$textureParameter(int target, int pname,
      int value, CallbackInfo ci) {
    IrisGlResourceBindingSnapshot.TextureUnitBinding binding =
        metalrender$resources().activeTextureBinding(target);
    if (binding != null && binding.texture() > 0) {
      IrisGlSamplerMirror.global().textureParameteri(binding.texture(),
          pname, value);
    }
  }

  private static void metalrender$finishMojangDraw() {
    METALRENDER_MOJANG_DRAW_SCOPE.remove();
    IrisVisualParityCapture.global().endDrawInvocation();
  }

  @Inject(method = "_drawElements", at = @At("HEAD"), cancellable = true)
  private static void metalrender$drawElements(int mode, int count, int type,
      long indices, CallbackInfo ci) {
    if (IrisSodiumImmediateDrawCommandListMixin
        .metalrender$isHighLevelDrawActive()) {
      return;
    }
    METALRENDER_MOJANG_DRAW_SCOPE.set(Boolean.TRUE);
    IrisVisualParityCapture.global().beginDrawInvocation();
    int bytes = switch (type) {
      case 0x1401 -> 1;
      case 0x1403 -> 2;
      case 0x1405 -> 4;
      default -> 0;
    };
    if (bytes == 0) {
      metalrender$capture().draw(mode);
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-direct-index-type-unsupported")) {
        metalrender$finishMojangDraw();
        ci.cancel();
      }
      return;
    }
    try {
      var pending = metalrender$capture().captureDrawDirect(
          new IrisExecutionCommand.DrawIndexed(
              mode, indices, count, bytes, 0, 1, 0,
              IrisExecutionCommand.Source.DIRECT_GL));
      if (pending.isPresent()
          && (IrisTranslationCoordinator.tryFullGraphCutover(
              pending.orElseThrow())
              || IrisTranslationCoordinator.tryFinalCutover(
                  pending.orElseThrow()))) {
        metalrender$finishMojangDraw();
        ci.cancel();
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-direct-indexed-unresolved")) {
        metalrender$finishMojangDraw();
        ci.cancel();
      }
    } catch (RuntimeException error) {
      metalrender$capture().draw(mode);
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-direct-indexed-invalid")) {
        metalrender$finishMojangDraw();
        ci.cancel();
      }
    }
  }

  
  @Inject(method = "_drawArrays", at = @At("HEAD"), cancellable = true,
      require = 0)
  private static void metalrender$drawArrays(int mode, int first, int count,
      CallbackInfo ci) {
    if (IrisSodiumImmediateDrawCommandListMixin
        .metalrender$isHighLevelDrawActive()) {
      return;
    }
    METALRENDER_MOJANG_DRAW_SCOPE.set(Boolean.TRUE);
    IrisVisualParityCapture.global().beginDrawInvocation();
    if (first < 0 || count < 0) {
      metalrender$capture().draw(mode);
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-direct-array-invalid")) {
        metalrender$finishMojangDraw();
        ci.cancel();
      }
      return;
    }
    try {
      var pending = metalrender$capture().captureDrawDirect(
          new IrisExecutionCommand.DrawArrays(
              mode, first, count, 1, 0,
              IrisExecutionCommand.Source.DIRECT_GL));
      if (pending.isPresent()
          && (IrisTranslationCoordinator.tryFullGraphCutover(
              pending.orElseThrow())
              || IrisTranslationCoordinator.tryFinalCutover(
                  pending.orElseThrow()))) {
        metalrender$finishMojangDraw();
        ci.cancel();
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-direct-array-unresolved")) {
        metalrender$finishMojangDraw();
        ci.cancel();
      }
    } catch (RuntimeException error) {
      metalrender$capture().draw(mode);
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-direct-array-capture-invalid")) {
        metalrender$finishMojangDraw();
        ci.cancel();
      }
    }
  }

  @Inject(method = "_drawArrays", at = @At("RETURN"), require = 0)
  private static void metalrender$drawArraysComplete(int mode, int first,
      int count, CallbackInfo ci) {
    if (IrisSodiumImmediateDrawCommandListMixin
        .metalrender$isHighLevelDrawActive()) {
      return;
    }
    metalrender$finishMojangDraw();
  }

  @Inject(method = "_drawElements", at = @At("RETURN"))
  private static void metalrender$drawElementsComplete(int mode, int count,
      int type, long indices, CallbackInfo ci) {
    if (IrisSodiumImmediateDrawCommandListMixin
        .metalrender$isHighLevelDrawActive()) {
      return;
    }
    metalrender$finishMojangDraw();
  }


}

package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.pebbles_boon.metalrender.compat.iris.IrisExecutionCommand;
import com.pebbles_boon.metalrender.compat.iris.IrisClearCommand;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlTextureMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlRenderPassState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingTracker;
import com.pebbles_boon.metalrender.compat.iris.IrisMojangDrawCommand;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineStateCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisRenderGraphCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisVertexInputBindings;
import com.pebbles_boon.metalrender.compat.iris.IrisVisualParityCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures draws that Mojang's command encoder submits directly to LWJGL. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class IrisGlCommandEncoderMixin {
  private static final ThreadLocal<Integer> METALRENDER_DRAW_CAPTURE_SCOPE =
      ThreadLocal.withInitial(() -> 0);
  private static final ThreadLocal<Boolean> DRAW_FROM_BUFFERS_SCOPE =
      new ThreadLocal<>();
  private static final ThreadLocal<Boolean> DRAW_FROM_BUFFERS_NESTED =
      new ThreadLocal<>();
  private static final ThreadLocal<Boolean> EXECUTE_DRAWS_SCOPE =
      new ThreadLocal<>();
  private static final ThreadLocal<Boolean> EXECUTE_DRAW_INDIRECT_SCOPE =
      new ThreadLocal<>();

  static boolean metalrender$isHighLevelDrawCaptureActive() {
    return METALRENDER_DRAW_CAPTURE_SCOPE.get() > 0;
  }

  private static void metalrender$enterDrawCaptureScope() {
    METALRENDER_DRAW_CAPTURE_SCOPE.set(
        METALRENDER_DRAW_CAPTURE_SCOPE.get() + 1);
  }

  private static void metalrender$exitDrawCaptureScope() {
    int depth = METALRENDER_DRAW_CAPTURE_SCOPE.get();
    if (depth <= 1) {
      METALRENDER_DRAW_CAPTURE_SCOPE.remove();
    } else {
      METALRENDER_DRAW_CAPTURE_SCOPE.set(depth - 1);
    }
  }

  private static void metalrender$discardDrawFromBuffersScope() {
    if (Boolean.TRUE.equals(DRAW_FROM_BUFFERS_SCOPE.get())) {
      metalrender$exitDrawCaptureScope();
      DRAW_FROM_BUFFERS_SCOPE.remove();
    }
  }

  private static void metalrender$discardExecuteDrawsScope() {
    if (Boolean.TRUE.equals(EXECUTE_DRAWS_SCOPE.get())) {
      metalrender$exitDrawCaptureScope();
      EXECUTE_DRAWS_SCOPE.remove();
    }
  }

  private static void metalrender$discardExecuteDrawIndirectScope() {
    if (Boolean.TRUE.equals(EXECUTE_DRAW_INDIRECT_SCOPE.get())) {
      metalrender$exitDrawCaptureScope();
      EXECUTE_DRAW_INDIRECT_SCOPE.remove();
    }
  }

  @Inject(method = "clearColorTexture", at = @At("HEAD"), require = 0,
      cancellable = true)
  private void metalrender$clearColorTexture(GpuTexture destination,
      Vector4fc color, CallbackInfo ci) {
    if (destination instanceof GlTexture texture && color != null) {
      IrisRenderGraphCapture.global().clearColorTexture(texture.glId(),
          color.x(), color.y(), color.z(), color.w(), Optional.empty());
    }
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
      at = @At("HEAD"), require = 0, cancellable = true)
  private void metalrender$clearColorAndDepthTextures(GpuTexture colorTexture,
      Vector4fc color, GpuTexture depthTexture, double depth,
      CallbackInfo ci) {
    if (colorTexture instanceof GlTexture glColor && color != null) {
      IrisRenderGraphCapture.global().clearColorTexture(glColor.glId(),
          color.x(), color.y(), color.z(), color.w(), Optional.empty());
    }
    if (depthTexture instanceof GlTexture glDepth) {
      IrisRenderGraphCapture.global().clearDepthTexture(glDepth.glId(), depth,
          Optional.empty());
    }
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;DIIII)V",
      at = @At("HEAD"), require = 0, cancellable = true)
  private void metalrender$clearColorAndDepthTexturesRegion(
      GpuTexture colorTexture, Vector4fc color, GpuTexture depthTexture,
      double depth, int x, int y, int width, int height, CallbackInfo ci) {
    Optional<IrisClearCommand.Rect> region = width > 0 && height > 0
        ? Optional.of(new IrisClearCommand.Rect(x, y, width, height))
        : Optional.empty();
    if (colorTexture instanceof GlTexture glColor && color != null) {
      IrisRenderGraphCapture.global().clearColorTexture(glColor.glId(),
          color.x(), color.y(), color.z(), color.w(), region);
    }
    if (depthTexture instanceof GlTexture glDepth) {
      IrisRenderGraphCapture.global().clearDepthTexture(glDepth.glId(), depth,
          region);
    }
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "clearDepthTexture", at = @At("HEAD"), require = 0,
      cancellable = true)
  private void metalrender$clearDepthTexture(GpuTexture destination,
      double depth, CallbackInfo ci) {
    if (destination instanceof GlTexture texture) {
      IrisRenderGraphCapture.global().clearDepthTexture(texture.glId(), depth,
          Optional.empty());
    }
    if (IrisTranslationCoordinator.suppressFullGraphOperation()) {
      ci.cancel();
    }
  }

  @Inject(method = "writeToBuffer", at = @At("RETURN"), require = 0)
  private void metalrender$writeToBuffer(GpuBufferSlice destination,
      ByteBuffer source, CallbackInfo ci) {
    if (!IrisGlBufferMirror.isEnabled()
        || !(destination.buffer() instanceof GlBuffer buffer)) {
      return;
    }
    IrisGlBufferMirror.global().write(buffer.handle(), buffer.size(),
        destination.offset(), destination.length(), source);
  }

  @Inject(method = "copyToBuffer", at = @At("RETURN"), require = 0)
  private void metalrender$copyToBuffer(GpuBufferSlice source,
      GpuBufferSlice destination, CallbackInfo ci) {
    if (!IrisGlBufferMirror.isEnabled()
        || !(source.buffer() instanceof GlBuffer sourceBuffer)
        || !(destination.buffer() instanceof GlBuffer destinationBuffer)) {
      return;
    }
    IrisGlBufferMirror.global().copy(sourceBuffer.handle(), source.offset(),
        destinationBuffer.handle(), destinationBuffer.size(),
        destination.offset(), source.length());
  }

  @Inject(method = "writeToTexture", at = @At("RETURN"), require = 0)
  private void metalrender$writeToTexture(GpuTexture destination,
      ByteBuffer source, int mipLevel, int depthOrLayer, int destinationX,
      int destinationY, int width, int height, CallbackInfo ci) {
    if (!IrisGlBufferMirror.isEnabled()
        || !(destination instanceof GlTexture texture)) {
      return;
    }
    IrisGlTextureMirror.global().write(texture.glId(), mipLevel,
        depthOrLayer, destinationX, destinationY, width, height, width,
        source);
  }

  @Inject(method = "copyBufferToTexture", at = @At("RETURN"), require = 0)
  private void metalrender$copyBufferToTexture(GpuBufferSlice source,
      int sourceX, int sourceY, int sourceWidth, int sourceHeight,
      GpuTexture destination, int destinationX, int destinationY,
      int copyWidth, int copyHeight, int mipLevel, int arrayLayer,
      CallbackInfo ci) {
    if (!IrisGlBufferMirror.isEnabled()
        || !(source.buffer() instanceof GlBuffer sourceBuffer)
        || !(destination instanceof GlTexture destinationTexture)
        || sourceWidth <= 0 || copyWidth <= 0 || copyHeight <= 0) {
      return;
    }
    try {
      int texelBytes = destination.getFormat().blockSize();
      long firstTexel = Math.addExact(sourceX,
          Math.multiplyExact((long) sourceY, sourceWidth));
      long sourceOffset = Math.addExact(source.offset(),
          Math.multiplyExact(firstTexel, texelBytes));
      long length = Math.addExact(
          Math.multiplyExact((long) copyHeight - 1, sourceWidth),
          copyWidth);
      length = Math.multiplyExact(length, texelBytes);
      IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
      IrisGlBufferMirror.BufferSnapshot snapshot = mirror.snapshot(
          sourceBuffer.handle(), mirror.generation(sourceBuffer.handle()),
          sourceOffset, length).orElse(null);
      if (snapshot != null) {
        IrisGlTextureMirror.global().write(destinationTexture.glId(),
            mipLevel, arrayLayer, destinationX, destinationY, copyWidth,
            copyHeight, sourceWidth, ByteBuffer.wrap(snapshot.bytes()));
      }
    } catch (ArithmeticException invalidRange) {
      // The mirror remains incomplete and the future replay fails closed.
    }
  }

  @Inject(method = "copyTextureToTexture", at = @At("RETURN"), require = 0)
  private void metalrender$copyTextureToTexture(GpuTexture source,
      GpuTexture destination, int mipLevel, int destinationX,
      int destinationY, int sourceX, int sourceY, int width, int height,
      CallbackInfo ci) {
    if (IrisGlBufferMirror.isEnabled()
        && source instanceof GlTexture sourceTexture
        && destination instanceof GlTexture destinationTexture) {
      IrisGlTextureMirror.global().copy(sourceTexture.glId(),
          destinationTexture.glId(), mipLevel, sourceX, sourceY,
          destinationX, destinationY, width, height);
    }
  }

  @Redirect(method = "trySetup", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL33C;glBindBufferRange(IIIJJ)V"),
      require = 0)
  private void metalrender$bindUniformBufferRange(int target, int index,
      int buffer, long offset, long size) {
    metalrender$bindBufferRange(target, index, buffer, offset, size);
  }

  @Redirect(method = "trySetup", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL33C;glBindTexture(II)V"), require = 0)
  private void metalrender$bindTexture(int target, int texture) {
    GL33C.glBindTexture(target, texture);
    IrisGlResourceBindingTracker.global().bindTexture(target, texture);
  }

  @Redirect(method = "trySetup", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL33C;glBindSampler(II)V"), require = 0)
  private void metalrender$bindSampler(int unit, int sampler) {
    GL33C.glBindSampler(unit, sampler);
    IrisGlResourceBindingTracker.global().bindSamplerToUnit(unit, sampler);
  }

  @Redirect(method = "trySetup", at = @At(value = "INVOKE",
      target = "Lorg/lwjgl/opengl/GL33C;glTexBuffer(III)V"), require = 0)
  private void metalrender$texBuffer(int target, int internalFormat,
      int buffer) {
    GL33C.glTexBuffer(target, internalFormat, buffer);
    IrisGlResourceBindingTracker.global().texBuffer(target, internalFormat,
        buffer);
  }

  @Redirect(method = "lambda$executeDrawMultiple$0",
      at = @At(value = "INVOKE",
          target = "Lorg/lwjgl/opengl/GL33C;glBindBufferRange(IIIJJ)V"),
      require = 0)
  private static void metalrender$bindDrawMultipleUniformBufferRange(
      int target, int index, int buffer, long offset, long size) {
    metalrender$bindBufferRange(target, index, buffer, offset, size);
  }

  @Inject(method = "drawFromBuffers", at = @At("HEAD"), cancellable = true)
  private void metalrender$drawFromBuffers(@Coerce Object pass,
      int baseVertex, int firstIndex, int indexCount, IndexType indexType,
      GlRenderPipeline pipeline, int instanceCount, CallbackInfo ci) {
    if (metalrender$isHighLevelDrawCaptureActive()) {
      DRAW_FROM_BUFFERS_NESTED.set(Boolean.TRUE);
      return;
    }
    IrisVisualParityCapture.global().beginDrawInvocation();
    int primitiveMode = GlConst.toGl(
        pipeline.info().getPrimitiveTopology());
    try {
      var pending = IrisPipelineStateCapture.global().captureDraw(
          IrisMojangDrawCommand.fromBuffers(primitiveMode, baseVertex,
              firstIndex, indexCount, indexType == null ? 0 : indexType.bytes,
              instanceCount, baseInstance),
          metalrender$vertexInputs(pass));
      if (pending.isPresent()) {
        metalrender$enterDrawCaptureScope();
        DRAW_FROM_BUFFERS_SCOPE.set(Boolean.TRUE);
        if (IrisTranslationCoordinator.tryFullGraphCutover(
                pending.orElseThrow())
            || IrisTranslationCoordinator.tryFinalCutover(
                pending.orElseThrow())) {
          metalrender$discardDrawFromBuffersScope();
          IrisVisualParityCapture.global().endDrawInvocation();
          ci.cancel();
        }
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-mojang-draw-unresolved")) {
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
    } catch (ArithmeticException | IllegalArgumentException error) {
      IrisPipelineStateCapture.global().draw(primitiveMode);
    }
  }

  @Inject(method = "drawFromBuffers", at = @At("RETURN"))
  private void metalrender$drawFromBuffersComplete(@Coerce Object pass,
      int baseVertex, int firstIndex, int indexCount, IndexType indexType,
      GlRenderPipeline pipeline, int instanceCount, CallbackInfo ci) {
    if (DRAW_FROM_BUFFERS_NESTED.get() != null) {
      DRAW_FROM_BUFFERS_NESTED.remove();
      return;
    }
    metalrender$discardDrawFromBuffersScope();
    IrisVisualParityCapture.global().endDrawInvocation();
  }

  @Inject(method = "executeDraws", require = 0, at = @At(value = "INVOKE",
      target = "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;validateDraw(Lcom/mojang/blaze3d/opengl/GlRenderPass;Lcom/mojang/blaze3d/IndexType;)V",
      shift = At.Shift.AFTER), cancellable = true)
  private void metalrender$executeDraws(@Coerce Object pass,
      IndexType indexType, PointerBuffer indices, IntBuffer counts,
      IntBuffer baseVertices, int drawCount, CallbackInfo ci) {
    IrisVisualParityCapture.global().beginDrawInvocation();
    int primitiveMode = metalrender$primitiveMode(pass);
    if (indexType == null) {
      if (metalrender$captureMultiDrawArrays(pass, primitiveMode, counts,
          baseVertices, drawCount)) {
        metalrender$discardExecuteDrawsScope();
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
      return;
    }
    if (drawCount <= 0
        || drawCount > IrisExecutionCommand.MAX_MULTI_DRAW_COUNT
        || indices == null || counts == null || baseVertices == null
        || indices.remaining() < drawCount || counts.remaining() < drawCount
        || baseVertices.remaining() < drawCount) {
      IrisPipelineStateCapture.global().draw(primitiveMode);
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-multi-draw-invalid")) {
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
      return;
    }
    long[] offsets = new long[drawCount];
    int[] elementCounts = new int[drawCount];
    int[] bases = new int[drawCount];
    int indicesPosition = indices.position();
    int countsPosition = counts.position();
    int basesPosition = baseVertices.position();
    for (int draw = 0; draw < drawCount; draw++) {
      offsets[draw] = indices.get(indicesPosition + draw);
      elementCounts[draw] = counts.get(countsPosition + draw);
      bases[draw] = baseVertices.get(basesPosition + draw);
    }
    try {
      var pending = IrisPipelineStateCapture.global().captureDraw(
          new IrisExecutionCommand.MultiDrawIndexed(primitiveMode,
              indexType.bytes, offsets, elementCounts, bases,
              IrisExecutionCommand.Source.MOJANG_COMMAND_ENCODER),
              metalrender$vertexInputs(pass));
      if (pending.isPresent()) {
        metalrender$enterDrawCaptureScope();
        EXECUTE_DRAWS_SCOPE.set(Boolean.TRUE);
        if (IrisTranslationCoordinator.tryFullGraphCutover(
                pending.orElseThrow())) {
          metalrender$discardExecuteDrawsScope();
          IrisVisualParityCapture.global().endDrawInvocation();
          ci.cancel();
        }
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-multi-draw-unresolved")) {
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
    } catch (IllegalArgumentException error) {
      IrisPipelineStateCapture.global().draw(primitiveMode);
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-multi-draw-capture-failed")) {
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
    }
  }

  @Inject(method = "executeDraws", require = 0, at = @At("RETURN"))
  private void metalrender$executeDrawsComplete(@Coerce Object pass,
      IndexType indexType, PointerBuffer indices, IntBuffer counts,
      IntBuffer baseVertices, int drawCount, CallbackInfo ci) {
    metalrender$discardExecuteDrawsScope();
    IrisVisualParityCapture.global().endDrawInvocation();
  }

  @Inject(method = "executeDrawIndirect", require = 0, at = @At(value = "INVOKE",
      target = "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;validateDraw(Lcom/mojang/blaze3d/opengl/GlRenderPass;Lcom/mojang/blaze3d/IndexType;)V",
      shift = At.Shift.AFTER), cancellable = true)
  private void metalrender$executeDrawIndirect(@Coerce Object pass,
      IndexType indexType, GlBuffer indirectBuffer, long offset,
      int drawCount, CallbackInfo ci) {
    IrisVisualParityCapture.global().beginDrawInvocation();
    int primitiveMode = metalrender$primitiveMode(pass);
    try {
      var pending = IrisPipelineStateCapture.global().captureDraw(
          IrisMojangDrawCommand.indirect(primitiveMode,
              indexType == null ? 0 : indexType.bytes,
              indirectBuffer.handle(), offset, drawCount),
              metalrender$vertexInputs(pass));
      if (pending.isPresent()) {
        metalrender$enterDrawCaptureScope();
        EXECUTE_DRAW_INDIRECT_SCOPE.set(Boolean.TRUE);
        if (IrisTranslationCoordinator.tryFullGraphCutover(
                pending.orElseThrow())) {
          metalrender$discardExecuteDrawIndirectScope();
          IrisVisualParityCapture.global().endDrawInvocation();
          ci.cancel();
        }
      } else if (pending.isEmpty()
          && IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
              "graph-ownership-indirect-draw-unresolved")) {
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
    } catch (IllegalArgumentException error) {
      IrisPipelineStateCapture.global().draw(primitiveMode);
      if (IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-indirect-draw-capture-failed")) {
        IrisVisualParityCapture.global().endDrawInvocation();
        ci.cancel();
      }
    }
  }

  @Inject(method = "executeDrawIndirect", require = 0, at = @At("RETURN"))
  private void metalrender$executeDrawIndirectComplete(@Coerce Object pass,
      IndexType indexType, GlBuffer indirectBuffer, long offset,
      int drawCount, CallbackInfo ci) {
    metalrender$discardExecuteDrawIndirectScope();
    IrisVisualParityCapture.global().endDrawInvocation();
  }

  private static boolean metalrender$captureMultiDrawArrays(Object pass,
      int primitiveMode, IntBuffer counts, IntBuffer firstVertices,
      int drawCount) {
    if (drawCount <= 0
        || drawCount > IrisExecutionCommand.MAX_MULTI_DRAW_COUNT
        || counts == null || firstVertices == null
        || counts.remaining() < drawCount
        || firstVertices.remaining() < drawCount) {
      IrisPipelineStateCapture.global().draw(primitiveMode);
      return IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
          "graph-ownership-multi-draw-arrays-invalid");
    }
    int countsPosition = counts.position();
    int firstVerticesPosition = firstVertices.position();
    for (int draw = 0; draw < drawCount; draw++) {
      int count = counts.get(countsPosition + draw);
      int firstVertex = firstVertices.get(firstVerticesPosition + draw);
      if (count < 0 || firstVertex < 0) {
        IrisPipelineStateCapture.global().draw(primitiveMode);
        return IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
            "graph-ownership-multi-draw-arrays-invalid");
      }
    }
    IrisVertexInputBindings inputs = metalrender$vertexInputs(pass);
    boolean suppress = false;
    boolean allCaptured = true;
    for (int draw = 0; draw < drawCount; draw++) {
      var pending = IrisPipelineStateCapture.global().captureDraw(
          new IrisExecutionCommand.DrawArrays(primitiveMode,
              firstVertices.get(firstVerticesPosition + draw),
              counts.get(countsPosition + draw), 1, 0,
              IrisExecutionCommand.Source.MOJANG_COMMAND_ENCODER), inputs);
      if (pending.isPresent()) {
        suppress |= IrisTranslationCoordinator.tryFullGraphCutover(
            pending.orElseThrow());
      } else {
        allCaptured = false;
        suppress |= IrisTranslationCoordinator.suppressUnsupportedFullGraphDraw(
            "graph-ownership-multi-draw-arrays-unresolved");
      }
    }
    if (allCaptured) {
      metalrender$enterDrawCaptureScope();
      EXECUTE_DRAWS_SCOPE.set(Boolean.TRUE);
    }
    return suppress;
  }

  private static int metalrender$primitiveMode(Object pass) {
    return ((IrisGlRenderPassState) pass).metalrender$primitiveMode();
  }

  private static IrisVertexInputBindings metalrender$vertexInputs(
      Object pass) {
    return ((IrisGlRenderPassState) pass).metalrender$vertexInputBindings();
  }

  private static void metalrender$bindBufferRange(int target, int index,
      int buffer, long offset, long size) {
    GL33C.glBindBufferRange(target, index, buffer, offset, size);
    IrisGlResourceBindingTracker.global().bindBufferRange(target, index,
        buffer, offset, size);
  }
}

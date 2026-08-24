package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.pebbles_boon.metalrender.compat.iris.IrisGlRenderPassState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlBufferMirror;
import com.pebbles_boon.metalrender.compat.iris.IrisGlMappedBufferAccess;
import com.pebbles_boon.metalrender.compat.iris.IrisVertexInputBindings;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Optional;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains only the public pipeline topology on a package-private pass. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
public abstract class IrisGlRenderPassMixin implements IrisGlRenderPassState {
  @Shadow @Final protected GpuBufferSlice[] vertexBuffers;
  @Shadow protected GpuBuffer indexBuffer;

  @Unique
  private int metalrender$primitiveMode;

  @Inject(method = "setPipeline", at = @At("RETURN"))
  private void metalrender$setPipeline(RenderPipeline pipeline,
      CallbackInfo ci) {
    metalrender$primitiveMode = GlConst.toGl(
        pipeline.getPrimitiveTopology());
  }

  @Override
  public int metalrender$primitiveMode() {
    return metalrender$primitiveMode;
  }

  @Override
  public IrisVertexInputBindings metalrender$vertexInputBindings() {
    try {
      ArrayList<IrisVertexInputBindings.BufferSlice> vertices =
          new ArrayList<>();
      for (int slot = 0; slot < vertexBuffers.length; slot++) {
        GpuBufferSlice slice = vertexBuffers[slot];
        if (slice == null) {
          break;
        }
        if (!(slice.buffer() instanceof GlBuffer glBuffer)) {
          return IrisVertexInputBindings.unavailable(
              "non-gl-vertex-buffer");
        }
        int handle = glBuffer.handle();
        metalrender$refreshMappedRange(glBuffer, slice.offset(),
            slice.length());
        vertices.add(new IrisVertexInputBindings.BufferSlice(slot, handle,
            slice.offset(), slice.length(),
            IrisGlBufferMirror.global().generation(handle)));
      }
      IrisVertexInputBindings.BufferSlice index = null;
      if (indexBuffer != null) {
        if (!(indexBuffer instanceof GlBuffer glBuffer)) {
          return IrisVertexInputBindings.unavailable("non-gl-index-buffer");
        }
        metalrender$refreshMappedRange(glBuffer, 0, glBuffer.size());
        index = new IrisVertexInputBindings.BufferSlice(0, glBuffer.handle(),
            0, glBuffer.size(), IrisGlBufferMirror.global().generation(
                glBuffer.handle()));
      }
      return new IrisVertexInputBindings(vertices,
          Optional.ofNullable(index), "");
    } catch (RuntimeException error) {
      return IrisVertexInputBindings.unavailable(
          "gl-buffer-slice-invalid");
    }
  }

  @Unique
  private static void metalrender$refreshMappedRange(GlBuffer buffer,
      long offset, long length) {
    if (!IrisGlBufferMirror.isEnabled()
        || !(buffer instanceof IrisGlMappedBufferAccess access)
        || offset < 0 || length <= 0 || length > Integer.MAX_VALUE
        || offset > buffer.size() || length > buffer.size() - offset) {
      return;
    }
    ByteBuffer mapped = access.metalrender$mappedBufferView();
    if (mapped == null || offset > mapped.capacity()
        || length > mapped.capacity() - offset) {
      return;
    }
    ByteBuffer range = mapped.duplicate();
    range.position(Math.toIntExact(offset));
    range.limit(Math.toIntExact(offset + length));
    IrisGlBufferMirror.global().write(buffer.handle(), buffer.size(), offset,
        length, range.slice());
  }
}

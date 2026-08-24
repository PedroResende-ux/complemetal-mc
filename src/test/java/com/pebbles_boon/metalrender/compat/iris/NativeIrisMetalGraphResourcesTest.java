package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceKind;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeIrisMetalGraphResourcesTest {
  @Test
  void forwardsExactAllocationAndReturnsOpaqueBinding() {
    RecordingNative nativeAccess = new RecordingNative();
    NativeIrisMetalGraphResources resources =
        new NativeIrisMetalGraphResources(nativeAccess);
    ResourceHandle handle = new ResourceHandle(ResourceKind.TEXTURE,
        71, 9, 12);
    IrisMetalGraphResourcePlan plan = new IrisMetalGraphResourcePlan(List.of(
        new IrisMetalGraphResourcePlan.Allocation(4, handle,
            "rgba16-float", 1, 1920, 1080, 1, 5,
            IrisMetalGraphResourcePlan.USAGE_SHADER_READ
                | IrisMetalGraphResourcePlan.USAGE_RENDER_TARGET)));

    NativeIrisMetalGraphResources.Complete complete = assertInstanceOf(
        NativeIrisMetalGraphResources.Complete.class,
        resources.ensure(plan));
    assertEquals(List.of(new NativeIrisMetalGraphResources.Binding(4, 101)),
        complete.bindings());
    assertEquals(1, complete.nativeTextureCount());
    assertEquals(4096, complete.nativeTextureBytes());
    assertEquals("12/71/9/rgba16-float/1/1920/1080/1/5/5",
        nativeAccess.request);
  }

  @Test
  void failsOpenOnNativeAllocationFailureAndResetsSafely() {
    RecordingNative nativeAccess = new RecordingNative();
    nativeAccess.token = 0;
    NativeIrisMetalGraphResources resources =
        new NativeIrisMetalGraphResources(nativeAccess);
    ResourceHandle handle = new ResourceHandle(ResourceKind.TEXTURE,
        7, 1, 2);
    IrisMetalGraphResourcePlan plan = new IrisMetalGraphResourcePlan(List.of(
        new IrisMetalGraphResourcePlan.Allocation(0, handle, "rgba8-unorm",
            1, 16, 16, 1, 1,
            IrisMetalGraphResourcePlan.USAGE_RENDER_TARGET)));

    NativeIrisMetalGraphResources.Unsupported unsupported = assertInstanceOf(
        NativeIrisMetalGraphResources.Unsupported.class,
        resources.ensure(plan));
    assertEquals("native-graph-texture-allocation-failed-r0-16x16-s1-u4",
        unsupported.reason());
    resources.reset();
    assertEquals(1, nativeAccess.resets);
  }

  private static final class RecordingNative
      implements NativeIrisMetalGraphResources.NativeAccess {
    private long token = 101;
    private String request = "";
    private int resets;

    @Override
    public boolean runtimeAvailable() {
      return true;
    }

    @Override
    public long ensure(long contextGeneration, int glTexture,
        long resourceGeneration, String format, int sampleCount,
        int width, int height, int depthOrLayers, int mipLevels, int usage) {
      request = contextGeneration + "/" + glTexture + "/"
          + resourceGeneration + "/" + format + "/" + sampleCount + "/"
          + width + "/" + height + "/" + depthOrLayers + "/"
          + mipLevels + "/" + usage;
      return token;
    }

    @Override
    public int count() {
      return token == 0 ? 0 : 1;
    }

    @Override
    public long bytes() {
      return token == 0 ? 0 : 4096;
    }

    @Override
    public void reset() {
      resets++;
    }
  }
}

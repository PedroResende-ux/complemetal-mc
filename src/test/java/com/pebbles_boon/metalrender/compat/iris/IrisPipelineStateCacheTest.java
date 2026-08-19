package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.BlendState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.ColorAttachment;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.CompareOperation;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.CullMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DepthClipMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DepthState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.FillMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.FrontFace;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassIdentity;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PrimitiveState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PrimitiveRestartMode;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PrimitiveTopology;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.RasterState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StencilState;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IrisPipelineStateCacheTest {
  @TempDir
  Path temporary;

  @Test
  void storesVerifiesAndHitsWithoutOriginalGlsl() throws Exception {
    IrisPipelineStateCache cache = new IrisPipelineStateCache(temporary);
    IrisShaderCacheKey shaderKey = new IrisShaderCacheKey("1".repeat(64));
    IrisPipelineState state = state();
    IrisPipelineStateCache.StoreResult cold = cache.store(shaderKey, state);
    IrisPipelineStateCache.StoreResult warm = cache.store(shaderKey, state);

    assertFalse(cold.cacheHit());
    assertTrue(warm.cacheHit());
    assertTrue(cache.read(cold.key()).isPresent());
    String metadata = Files.readString(cold.verified().paths().metadata());
    assertTrue(metadata.contains("pipeline.status=pending"));
    assertTrue(metadata.contains("source.original_glsl_persisted=false"));
  }

  @Test
  void rejectsTamperedCanonicalState() throws Exception {
    IrisPipelineStateCache cache = new IrisPipelineStateCache(temporary);
    IrisPipelineStateCache.StoreResult stored = cache.store(
        new IrisShaderCacheKey("2".repeat(64)), state());
    Files.writeString(stored.verified().paths().canonical(), "tampered");
    assertTrue(cache.read(stored.key()).isEmpty());
  }

  private static IrisPipelineState state() {
    return new IrisPipelineState(
        new PassIdentity(PassKind.LINKED_GRAPHICS, "3".repeat(64), false),
        List.of(new VertexBufferLayout(0, 12, StepFunction.PER_VERTEX, 0)),
        List.of(new VertexAttribute(0, 0, 0,
            new DataFormat("rgb32-float"))),
        List.of(new ColorAttachment(0, new DataFormat("rgba8-unorm"), 15,
            BlendState.disabled())), Optional.of(new DataFormat("d32-float")),
        Optional.empty(), 1, -1L, false, 1.0F, false, false, false,
        new DepthState(true, CompareOperation.LESS, true),
        StencilState.disabled(),
        new RasterState(true, CullMode.BACK, FrontFace.COUNTER_CLOCKWISE,
            FillMode.FILL, FillMode.FILL, DepthClipMode.CLIP, 0, 0, 0, 0),
        new PrimitiveState(PrimitiveTopology.TRIANGLE,
            PrimitiveRestartMode.NONE, 0), true,
        List.of());
  }
}

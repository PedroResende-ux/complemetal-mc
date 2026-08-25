package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureUnitBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureBufferBinding;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class IrisShadowReplayTextureSnapshotTest {
  @Test
  void limitsDirectIosurfaceHandoffToProvenRgba8Layout() {
    assertTrue(IrisGlTextureGpuHandoff.supportsDirectGpuHandoff(
        "rgba8-unorm"));
    assertFalse(IrisGlTextureGpuHandoff.supportsDirectGpuHandoff(
        "rg11b10-float"));
    assertTrue(IrisGlTextureGpuHandoff.supportsDirectGpuHandoff(
        "rg11b10-float", true));
    assertFalse(IrisGlTextureGpuHandoff.supportsDirectGpuHandoff(
        "rgba16-float", true));
    assertEquals("native-surface-ring-exhausted",
        IrisGlTextureGpuHandoff.nativeCaptureFailure(-3));
    assertTrue(IrisGlTextureGpuHandoff.retryableCaptureFailure(
        IrisGlTextureGpuHandoff.nativeCaptureFailure(-3)));
    assertFalse(IrisGlTextureGpuHandoff.retryableCaptureFailure(
        IrisGlTextureGpuHandoff.nativeCaptureFailure(-10)));
    assertEquals("native-framebuffer-copy-or-fence-failed",
        IrisGlTextureGpuHandoff.nativeCaptureFailure(-10));
  }

  @Test
  void routesOnlySmallRgbaAssetsToGenerationResidentUploads() {
    assertTrue(IrisShadowReplayTextureSnapshot.preferSmallResidentTexture(
        new IrisGlTextureMirror.TextureMetadata("rgba8-unorm",
            64, 64, 1, 4, 3)));
    assertFalse(IrisShadowReplayTextureSnapshot.preferSmallResidentTexture(
        new IrisGlTextureMirror.TextureMetadata("rgba16-float",
            64, 64, 1, 8, 3)));
    assertFalse(IrisShadowReplayTextureSnapshot.preferSmallResidentTexture(
        new IrisGlTextureMirror.TextureMetadata("rgba8-unorm",
            1280, 720, 1, 4, 3)));
    assertTrue(IrisShadowReplayTextureSnapshot.preferSmallResidentTexture(
        new IrisGlTextureMirror.TextureMetadata("rgba8-unorm",
            16, 16, 1, 4, 3)));
  }

  @Test
  void reservesBoundedResidentSlotsForLargeTextures() {
    assertTrue(IrisGlTextureGpuHandoff.residentCapacityAvailable(
        64, 0, 64L * 1024L, 16 * 1024 * 1024));
    assertFalse(IrisGlTextureGpuHandoff.residentCapacityAvailable(
        64, 0, 64L * 1024L, 16 * 1024));
    assertTrue(IrisGlTextureGpuHandoff.residentCapacityAvailable(
        71, 7, 128L * 1024L * 1024L, 16 * 1024 * 1024));
    assertFalse(IrisGlTextureGpuHandoff.residentCapacityAvailable(
        72, 8, 128L * 1024L * 1024L, 16 * 1024 * 1024));
    assertTrue(IrisGlTextureGpuHandoff.residentCapacityAvailable(
        8, 8, 128L * 1024L * 1024L, 16 * 1024 * 1024));
    assertFalse(IrisGlTextureGpuHandoff.residentCapacityAvailable(
        56, 0, 128L * 1024L * 1024L, 16 * 1024));
    assertTrue(IrisGlTextureGpuHandoff.residentCapacityAvailable(
        56, 0, 128L * 1024L * 1024L, 16 * 1024 * 1024));
    assertFalse(IrisGlTextureGpuHandoff.residentCapacityAvailable(
        1, 0, 256L * 1024L * 1024L - 1024L, 2048));
  }

  @Test
  void upgradesRetainedInlineBytesToTheirEquivalentResidentHandle() {
    IrisGlTextureMirror.TextureMetadata metadata =
        new IrisGlTextureMirror.TextureMetadata("rgba8-unorm",
            2, 1, 1, 4, 9);
    IrisGlTextureMirror.TextureSnapshot inline =
        IrisGlTextureMirror.TextureSnapshot.fromReadback(
            23, 9, metadata, 0, 0,
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    IrisGlTextureMirror.TextureSnapshot resident =
        IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
            23, 9, "rgba8-unorm", 2, 1, 4, 901);
    LinkedHashMap<Integer, IrisGlTextureMirror.TextureSnapshot> retained =
        new LinkedHashMap<>(Map.of(23, inline));

    assertTrue(IrisShadowReplayTextureSnapshot.upgradeRetainedResident(
        retained, 23, inline, inline, resident));
    assertEquals(resident, retained.get(23));
    assertEquals(0, retained.get(23).byteLength());
  }

  @Test
  void refusesToReplaceRetainedBytesWithAnUnrelatedSharedCapture() {
    IrisGlTextureMirror.TextureMetadata metadata =
        new IrisGlTextureMirror.TextureMetadata("rgba8-unorm",
            2, 1, 1, 4, 9);
    IrisGlTextureMirror.TextureSnapshot inline =
        IrisGlTextureMirror.TextureSnapshot.fromReadback(
            24, 9, metadata, 0, 0,
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    IrisGlTextureMirror.TextureSnapshot wrongGeneration =
        IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
            24, 10, "rgba8-unorm", 2, 1, 4, 902);
    LinkedHashMap<Integer, IrisGlTextureMirror.TextureSnapshot> retained =
        new LinkedHashMap<>(Map.of(24, inline));

    assertFalse(IrisShadowReplayTextureSnapshot.upgradeRetainedResident(
        retained, 24, inline, inline, wrongGeneration));
    assertEquals(inline, retained.get(24));
  }

  @Test
  void retainsTheDrawTimeImageAfterTheLiveTextureChanges() {
    IrisGlTextureMirror mirror = new IrisGlTextureMirror(4, 256, 128);
    assertTrue(mirror.define(17, "rgba8-unorm", 2, 1, 1, 1, 4));
    byte[] drawBytes = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    assertTrue(mirror.write(17, 0, 0, 0, 0, 2, 1, 2,
        ByteBuffer.wrap(drawBytes)));
    long drawGeneration = mirror.generation(17);

    IrisShadowReplayTextureSnapshot retained =
        IrisShadowReplayTextureSnapshot.capture(resources(
            new TextureUnitBinding(0x0DE1, 17, 0, drawGeneration)), mirror);

    assertTrue(mirror.write(17, 0, 0, 0, 0, 2, 1, 2,
        ByteBuffer.wrap(new byte[8])));
    assertTrue(retained.complete(), retained.blockers().toString());
    assertEquals(8, retained.totalBytes());
    assertArrayEquals(drawBytes, retained.textures().get(17).bytes());
  }

  @Test
  void rejectsAStaleResourceGenerationInsteadOfReadingNewerPixels() {
    IrisGlTextureMirror mirror = new IrisGlTextureMirror(4, 256, 128);
    assertTrue(mirror.define(18, "rgba8-unorm", 1, 1, 1, 1, 4));
    assertTrue(mirror.write(18, 0, 0, 0, 0, 1, 1, 1,
        ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
    long staleGeneration = mirror.generation(18);
    assertTrue(mirror.write(18, 0, 0, 0, 0, 1, 1, 1,
        ByteBuffer.wrap(new byte[] {5, 6, 7, 8})));

    IrisShadowReplayTextureSnapshot retained =
        IrisShadowReplayTextureSnapshot.capture(resources(
            new TextureUnitBinding(0x0DE1, 18, 0, staleGeneration)), mirror);

    assertFalse(retained.complete());
    assertTrue(retained.textures().isEmpty());
    assertEquals(List.of("sampled-texture-snapshot-unavailable"),
        retained.blockers());
  }

  @Test
  void retainsBoundedGpuHandoffWithoutCpuPixelBytes() {
    IrisGlTextureMirror.TextureSnapshot shared =
        IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
            19, 3, "rgba16-float", 4, 2, 8, 77);

    IrisShadowReplayTextureSnapshot retained =
        new IrisShadowReplayTextureSnapshot(true, Map.of(19, shared),
            List.of());

    assertTrue(retained.complete());
    assertEquals(0, retained.totalBytes());
    assertTrue(retained.textures().get(19).shared());
    assertEquals(77, retained.textures().get(19).sharedHandle());
    assertArrayEquals(new byte[0], retained.textures().get(19).bytes());
  }

  @Test
  void deduplicatesCapturedSurfaceHandlesBeforeAbandonment() {
    IrisGlTextureMirror.TextureSnapshot first =
        IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
            31, 1, "rgba8-unorm", 4, 2, 4, 703);
    IrisGlTextureMirror.TextureSnapshot sameHandle =
        IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
            32, 1, "rgba8-unorm", 4, 2, 4, 703);
    IrisGlTextureMirror.TextureSnapshot second =
        IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
            33, 1, "rgba8-unorm", 4, 2, 4, 701);
    IrisGlTextureMirror.TextureMetadata metadata =
        new IrisGlTextureMirror.TextureMetadata("rgba8-unorm",
            1, 1, 1, 4, 1);
    IrisGlTextureMirror.TextureSnapshot inline =
        IrisGlTextureMirror.TextureSnapshot.fromReadback(
            34, 1, metadata, 0, 0, new byte[] {1, 2, 3, 4});

    assertArrayEquals(new long[] {701, 703},
        IrisGlTextureGpuHandoff.uniqueSharedHandles(
            List.of(first, sameHandle, second, inline)));
  }

  @Test
  void resolvesInitializedGraphTextureWithoutReadingPixels() {
    IrisGlTextureMirror mirror = new IrisGlTextureMirror(4, 256, 128);
    assertTrue(mirror.define(21, "rgba16-float", 4, 2, 1, 1, 8));
    long generation = mirror.generation(21);
    LinkedHashMap<Integer, IrisGlTextureMirror.TextureSnapshot> frame =
        new LinkedHashMap<>();

    IrisShadowReplayTextureSnapshot retained =
        IrisShadowReplayTextureSnapshot.capture(resources(
                new TextureUnitBinding(0x0DE1, 21, 0, generation)),
            mirror, true, frame, Set.of(21),
            (texture, capturedGeneration, mipLevel, layer, metadata) ->
                Optional.of(IrisGlTextureMirror.TextureSnapshot
                    .fromGraphReference(texture, capturedGeneration,
                        metadata.format(), metadata.width(),
                        metadata.height(), metadata.bytesPerPixel(), layer,
                        mipLevel)));

    IrisGlTextureMirror.TextureSnapshot reference =
        retained.textures().get(21);
    assertTrue(retained.complete(), retained.blockers().toString());
    assertTrue(reference.graphReference());
    assertFalse(reference.shared());
    assertEquals(0, reference.byteLength());
    assertEquals(0, retained.totalBytes());
    assertEquals(reference, frame.get(21));
  }

  @Test
  void staleTextureBufferTargetDoesNotHideCurrentTwoDimensionalBinding() {
    IrisGlTextureMirror mirror = new IrisGlTextureMirror(4, 256, 128);
    assertTrue(mirror.define(20, "rgba8-unorm", 1, 1, 1, 1, 4));
    assertTrue(mirror.write(20, 0, 0, 0, 0, 1, 1, 1,
        ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
    long generation = mirror.generation(20);
    IrisGlResourceBindingSnapshot resources =
        new IrisGlResourceBindingSnapshot(1, Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(0, new TextureUnitBinding(0x0DE1, 20, 0,
                generation)), Map.of(20, new TextureBufferBinding(
                    0x8C2A, 0x822E, 71, 1)), Map.of(), Map.of());

    IrisShadowReplayTextureSnapshot retained =
        IrisShadowReplayTextureSnapshot.capture(resources, mirror);

    assertTrue(retained.complete(), retained.blockers().toString());
    assertTrue(retained.textures().containsKey(20));
  }

  private static IrisGlResourceBindingSnapshot resources(
      TextureUnitBinding binding) {
    return new IrisGlResourceBindingSnapshot(1, Map.of(), Map.of(), Map.of(),
        Map.of(), Map.of(0, binding), Map.of(), Map.of(), Map.of());
  }
}

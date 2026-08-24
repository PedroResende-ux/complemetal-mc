package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureUnitBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.UniformValue;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.UniformValueKind;
import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.ArgumentBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.StageLayout;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.Access;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DescriptorAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ScalarKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StorageClass;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.TypeRef;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.UniformLocation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisShadowReplayArgumentTableTest {
  @Test
  void mapsPlainUniformToTheExactAutomaticMslId() {
    UniformLocation address = new UniformLocation(0, 7);
    IrisProgramResourceLayout semantic = semantic(address,
        ResourceKind.UNIFORM, "exposure");
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.FRAGMENT, List.of(
            new ArgumentBinding(address, ResourceKind.UNIFORM,
                0, 4, -1)))));
    int bits = Float.floatToRawIntBits(1.25F);
    IrisGlResourceBindingSnapshot live = live(
        Map.of("exposure", 3),
        Map.of(3, new UniformValue(UniformValueKind.FLOAT, 1, 1,
            new long[] {Integer.toUnsignedLong(bits)})), Map.of());

    IrisShadowReplayArgumentTable table =
        IrisShadowReplayArgumentTable.resolve(semantic, msl, live,
            emptyBuffers());

    assertTrue(table.complete(), table.blockers().toString());
    assertEquals(1, table.argumentCount());
    IrisShadowReplayArgumentTable.BoundArgument argument =
        table.stages().getFirst().arguments().getFirst();
    assertEquals(4, argument.id());
    assertArrayEquals(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bits).array(),
        ((IrisShadowReplayArgumentTable.InlineUniform) argument.value())
            .bytes());
  }

  @Test
  void ignoresUnavailableRetainedGlBindingsThatTheProgramDoesNotUse() {
    UniformLocation address = new UniformLocation(0, 7);
    IrisProgramResourceLayout semantic = semantic(address,
        ResourceKind.UNIFORM, "exposure");
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.FRAGMENT, List.of(
            new ArgumentBinding(address, ResourceKind.UNIFORM,
                0, 4, -1)))));
    int bits = Float.floatToRawIntBits(1.0F);
    IrisGlResourceBindingSnapshot live = live(
        Map.of("exposure", 3),
        Map.of(3, new UniformValue(UniformValueKind.FLOAT, 1, 1,
            new long[] {Integer.toUnsignedLong(bits)})), Map.of());
    IrisShadowReplayBufferSnapshot buffers =
        new IrisShadowReplayBufferSnapshot(true, List.of(), List.of(),
            Optional.empty(), Optional.empty(), Map.of(), Map.of(),
            List.of("indexed-buffer-snapshot-unavailable"));
    IrisShadowReplayTextureSnapshot textures =
        new IrisShadowReplayTextureSnapshot(true, Map.of(),
            List.of("sampled-texture-snapshot-unavailable"));
    IrisShadowReplaySamplerSnapshot samplers =
        new IrisShadowReplaySamplerSnapshot(true, Map.of(),
            List.of("sampler-state-parameter-unsupported"));

    IrisShadowReplayArgumentTable table =
        IrisShadowReplayArgumentTable.resolve(semantic, msl, live, buffers,
            textures, samplers);

    assertTrue(table.complete(), table.blockers().toString());
    assertEquals(1, table.argumentCount());
  }

  @Test
  void padsThreeByThreeMatricesToMetalColumnAlignment() {
    long[] raw = new long[9];
    for (int index = 0; index < raw.length; index++) {
      raw[index] = Integer.toUnsignedLong(
          Float.floatToRawIntBits(index + 1));
    }
    byte[] encoded = IrisShadowReplayArgumentTable.encodeUniform(
        new UniformValue(UniformValueKind.FLOAT, 3, 3, raw));

    assertEquals(48, encoded.length);
    ByteBuffer values = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(Float.floatToRawIntBits(1), values.getInt(0));
    assertEquals(Float.floatToRawIntBits(4), values.getInt(16));
    assertEquals(Float.floatToRawIntBits(7), values.getInt(32));
    assertEquals(0, values.getInt(12));
  }

  @Test
  void refusesToInventContentForARealSampledTexture() {
    DescriptorAddress address = new DescriptorAddress(0, 2);
    IrisProgramResourceLayout semantic = semantic(address,
        ResourceKind.SAMPLED_IMAGE, "albedo");
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.FRAGMENT, List.of(
            new ArgumentBinding(address, ResourceKind.SAMPLED_IMAGE,
                0, 1, 2)))));
    IrisGlResourceBindingSnapshot live = live(Map.of("albedo", 5),
        Map.of(5, new UniformValue(UniformValueKind.SIGNED_INT, 1, 1,
            new long[] {3})),
        Map.of(3, new TextureUnitBinding(0x0DE1, 77, 0)));

    IrisShadowReplayArgumentTable table =
        IrisShadowReplayArgumentTable.resolve(semantic, msl, live,
            emptyBuffers());

    assertFalse(table.complete());
    assertEquals(List.of("sampled-texture-snapshot-unavailable"),
        table.blockers());
  }

  @Test
  void bindsCapturedTextureAndSamplerToTheExactMslIds() {
    DescriptorAddress address = new DescriptorAddress(0, 2);
    IrisProgramResourceLayout semantic = semantic(address,
        ResourceKind.SAMPLED_IMAGE, "albedo");
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.FRAGMENT, List.of(
            new ArgumentBinding(address, ResourceKind.SAMPLED_IMAGE,
                0, 1, 2)))));
    IrisGlTextureMirror textureMirror = new IrisGlTextureMirror(4, 256, 128);
    assertTrue(textureMirror.define(77, "rgba8-unorm", 1, 1, 1, 1, 4));
    assertTrue(textureMirror.write(77, 0, 0, 0, 0, 1, 1, 1,
        ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
    IrisGlSamplerMirror samplerMirror = new IrisGlSamplerMirror(4);
    samplerMirror.defineTexture(77);
    samplerMirror.textureParameteri(77,
        IrisGlSamplerMirror.GL_TEXTURE_MAG_FILTER, 0x2600);
    IrisGlResourceBindingSnapshot live = live(Map.of("albedo", 5),
        Map.of(5, new UniformValue(UniformValueKind.SIGNED_INT, 1, 1,
            new long[] {3})),
        Map.of(3, new TextureUnitBinding(0x0DE1, 77, 0,
            textureMirror.generation(77),
            samplerMirror.textureGeneration(77))));

    IrisShadowReplayArgumentTable table =
        IrisShadowReplayArgumentTable.resolve(semantic, msl, live,
            emptyBuffers(), IrisShadowReplayTextureSnapshot.capture(live,
                textureMirror), IrisShadowReplaySamplerSnapshot.capture(live,
                samplerMirror));

    assertTrue(table.complete(), table.blockers().toString());
    assertEquals(2, table.argumentCount());
    assertTrue(table.stages().getFirst().arguments().get(1).value()
        instanceof IrisShadowReplayArgumentTable.CapturedSampler);
  }

  @Test
  void textureOnlySampledImageDoesNotInventASamplerBinding() {
    DescriptorAddress address = new DescriptorAddress(0, 6);
    IrisProgramResourceLayout semantic = semantic(address,
        ResourceKind.SAMPLED_IMAGE, "sectionTimeInfo");
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.FRAGMENT, List.of(
            new ArgumentBinding(address, ResourceKind.SAMPLED_IMAGE,
                0, 3, -1)))));
    IrisGlTextureMirror textureMirror = new IrisGlTextureMirror(4, 256, 128);
    assertTrue(textureMirror.define(91, "rgba8-sint", 1, 1, 1, 1, 4));
    assertTrue(textureMirror.write(91, 0, 0, 0, 0, 1, 1, 1,
        ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
    IrisGlResourceBindingSnapshot live = live(
        Map.of("sectionTimeInfo", 8),
        Map.of(8, new UniformValue(UniformValueKind.SIGNED_INT, 1, 1,
            new long[] {4})),
        Map.of(4, new TextureUnitBinding(0x0DE1, 91, 0,
            textureMirror.generation(91), 0)));

    IrisShadowReplayArgumentTable table =
        IrisShadowReplayArgumentTable.resolve(semantic, msl, live,
            emptyBuffers(), IrisShadowReplayTextureSnapshot.capture(live,
                textureMirror), IrisShadowReplaySamplerSnapshot.emptyEnabled());

    assertTrue(table.complete(), table.blockers().toString());
    assertEquals(1, table.argumentCount());
    assertEquals(3, table.stages().getFirst().arguments().getFirst().id());
    assertTrue(table.stages().getFirst().arguments().getFirst().value()
        instanceof IrisShadowReplayArgumentTable.TextureImage);
  }

  @Test
  void bindsSamplerBufferFromItsTargetSpecificUnitBinding() {
    DescriptorAddress address = new DescriptorAddress(0, 8);
    IrisSpirvResourceLayout.ImageType image =
        new IrisSpirvResourceLayout.ImageType(
            Optional.of(new IrisSpirvResourceLayout.ScalarType(
                ScalarKind.FLOAT, 32)),
            IrisSpirvResourceLayout.ImageDimension.BUFFER,
            IrisSpirvResourceLayout.ImageDepth.NON_DEPTH, false, false,
            IrisSpirvResourceLayout.ImageSampling.SAMPLED,
            IrisSpirvResourceLayout.ImageFormat.UNKNOWN,
            IrisSpirvResourceLayout.DeclaredImageAccess.NONE);
    IrisSpirvResourceLayout.ResourceBinding resource =
        new IrisSpirvResourceLayout.ResourceBinding(address,
            ResourceKind.SAMPLED_IMAGE, StorageClass.UNIFORM_CONSTANT,
            Access.READ_ONLY, new TypeRef(List.of(),
                new IrisSpirvResourceLayout.SampledImageType(image)));
    IrisProgramResourceLayout semantic = new IrisProgramResourceLayout(
        List.of(new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.VERTEX,
            new IrisSpirvResourceLayout(List.of(resource),
                Map.of(address, "u_SectionTimeInfo"), true))));
    IrisMslArgumentLayout msl = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.VERTEX, List.of(
            new ArgumentBinding(address, ResourceKind.SAMPLED_IMAGE,
                0, 3, -1)))));
    IrisGlResourceBindingSnapshot live =
        new IrisGlResourceBindingSnapshot(1,
            Map.of("u_SectionTimeInfo", 5),
            Map.of(5, new UniformValue(UniformValueKind.SIGNED_INT, 1, 1,
                new long[] {0})), Map.of(), Map.of(),
            Map.of(0, new TextureUnitBinding(
                IrisGlResourceBindingTracker.GL_TEXTURE_2D, 91, 0)),
            Map.of(0, new TextureUnitBinding(
                IrisGlResourceBindingTracker.GL_TEXTURE_BUFFER, 92, 0)),
            Map.of(92, new TextureBufferBinding(
                IrisGlResourceBindingTracker.GL_TEXTURE_BUFFER, 0x822E,
                71)), Map.of(), Map.of());
    IrisShadowReplayBufferSnapshot buffers =
        new IrisShadowReplayBufferSnapshot(true, List.of(
            new IrisShadowReplayBufferSnapshot.BufferImage(
                0, 71, 1, 0, new byte[16])), List.of(), Optional.empty(),
            Optional.empty(), Map.of(),
            Map.of(92, new IrisShadowReplayBufferSnapshot.BufferRef(0)),
            List.of());

    IrisShadowReplayArgumentTable table =
        IrisShadowReplayArgumentTable.resolve(semantic, msl, live, buffers,
            IrisShadowReplayTextureSnapshot.emptyEnabled(),
            IrisShadowReplaySamplerSnapshot.emptyEnabled());

    assertTrue(table.complete(), table.blockers().toString());
    IrisShadowReplayArgumentTable.TextureBufferImage bound =
        (IrisShadowReplayArgumentTable.TextureBufferImage) table.stages()
            .getFirst().arguments().getFirst().value();
    assertEquals(0, bound.imageId());
    assertEquals(0x822E, bound.internalFormat());
  }

  private static IrisProgramResourceLayout semantic(
      IrisSpirvResourceLayout.ResourceAddress address, ResourceKind kind,
      String name) {
    TypeRef type = TypeRef.scalar(ScalarKind.FLOAT, 32);
    IrisSpirvResourceLayout stage = new IrisSpirvResourceLayout(List.of(
        new IrisSpirvResourceLayout.ResourceBinding(address, kind,
            kind == ResourceKind.UNIFORM ? StorageClass.UNIFORM
                : StorageClass.UNIFORM_CONSTANT,
            Access.READ_ONLY, type)), Map.of(address, name), true);
    return new IrisProgramResourceLayout(List.of(
        new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.FRAGMENT, stage)));
  }

  private static IrisGlResourceBindingSnapshot live(
      Map<String, Integer> locations, Map<Integer, UniformValue> values,
      Map<Integer, TextureUnitBinding> textures) {
    return new IrisGlResourceBindingSnapshot(1, locations, values, Map.of(),
        Map.of(), textures, Map.of(), Map.of(), Map.of());
  }

  private static IrisShadowReplayBufferSnapshot emptyBuffers() {
    return new IrisShadowReplayBufferSnapshot(true, List.of(), List.of(),
        Optional.empty(), Optional.empty(), Map.of(), Map.of(), List.of());
  }
}

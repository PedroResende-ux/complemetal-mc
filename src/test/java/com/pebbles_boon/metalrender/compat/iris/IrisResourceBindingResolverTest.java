package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.ImageUnitBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.IndexedBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.BufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureUnitBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.UniformValue;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.UniformValueKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.Access;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DescriptorAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ScalarKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StorageClass;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.UniformLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisResourceBindingResolverTest {
  @Test
  void resolvesEveryReflectedResourceClass() {
    IrisProgramResourceLayout layout = completeLayout();
    Map<String, Integer> locations = Map.of(
        "plain", 10, "sampled", 11, "image", 12);
    Map<Integer, UniformValue> values = Map.of(
        10, UniformValue.defaultZero(),
        11, integer(2),
        12, integer(1));
    IrisGlResourceBindingSnapshot snapshot =
        new IrisGlResourceBindingSnapshot(7, locations, values,
            Map.of("ubo", 3), Map.of(3, 4),
            Map.of(2, new TextureUnitBinding(0x0DE1, 50, 0)),
            Map.of(),
            Map.of(1, new ImageUnitBinding(51, 0, true, 0,
                0x88BA, 0x8058)),
            Map.of(
                new IndexedBufferBinding(
                    IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER, 4),
                BufferBinding.range(60, 256, 1024),
                new IndexedBufferBinding(
                    IrisGlResourceBindingTracker.GL_SHADER_STORAGE_BUFFER,
                    5), BufferBinding.base(61)));

    IrisResourceBindingResolver.Complete complete = assertInstanceOf(
        IrisResourceBindingResolver.Complete.class,
        IrisResourceBindingResolver.resolve(layout, snapshot));
    assertEquals(5, complete.matchedResources());
  }

  @Test
  void failsClosedWhenAReflectedTextureHasNoLiveBinding() {
    IrisGlResourceBindingSnapshot snapshot =
        new IrisGlResourceBindingSnapshot(7,
            Map.of("plain", 10, "sampled", 11, "image", 12),
            Map.of(10, UniformValue.defaultZero(), 11, integer(2),
                12, integer(1)),
            Map.of("ubo", 3), Map.of(3, 4), Map.of(),
            Map.of(),
            Map.of(1, new ImageUnitBinding(51, 0, true, 0,
                0x88BA, 0x8058)),
            Map.of(
                new IndexedBufferBinding(
                    IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER, 4),
                BufferBinding.range(60, 256, 1024),
                new IndexedBufferBinding(
                    IrisGlResourceBindingTracker.GL_SHADER_STORAGE_BUFFER,
                    5), BufferBinding.base(61)));
    IrisResourceBindingResolver.Incomplete incomplete = assertInstanceOf(
        IrisResourceBindingResolver.Incomplete.class,
        IrisResourceBindingResolver.resolve(completeLayout(), snapshot));
    assertEquals("fragment:sampled-texture-missing:sampled:unit-2",
        incomplete.reason());
  }

  @Test
  void acceptsAnExplicitOpenGlZeroTextureForMetalFallbackBinding() {
    IrisGlResourceBindingSnapshot snapshot =
        new IrisGlResourceBindingSnapshot(7,
            Map.of("plain", 10, "sampled", 11, "image", 12),
            Map.of(10, UniformValue.defaultZero(), 11, integer(2),
                12, integer(1)),
            Map.of("ubo", 3), Map.of(3, 4),
            Map.of(2, new TextureUnitBinding(0x0DE1, 0, 0)), Map.of(),
            Map.of(1, new ImageUnitBinding(51, 0, true, 0,
                0x88BA, 0x8058)),
            Map.of(
                new IndexedBufferBinding(
                    IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER, 4),
                BufferBinding.range(60, 256, 1024),
                new IndexedBufferBinding(
                    IrisGlResourceBindingTracker.GL_SHADER_STORAGE_BUFFER,
                    5), BufferBinding.base(61)));

    IrisResourceBindingResolver.Complete complete = assertInstanceOf(
        IrisResourceBindingResolver.Complete.class,
        IrisResourceBindingResolver.resolve(completeLayout(), snapshot));
    assertEquals(5, complete.matchedResources());
  }

  @Test
  void resolvesSamplerBufferIndependentlyFromTwoDimensionalUnitZero() {
    DescriptorAddress address = new DescriptorAddress(0, 9);
    IrisSpirvResourceLayout.ImageType image =
        new IrisSpirvResourceLayout.ImageType(
            Optional.of(new IrisSpirvResourceLayout.ScalarType(
                ScalarKind.FLOAT, 32)),
            IrisSpirvResourceLayout.ImageDimension.BUFFER,
            IrisSpirvResourceLayout.ImageDepth.NON_DEPTH, false, false,
            IrisSpirvResourceLayout.ImageSampling.SAMPLED,
            IrisSpirvResourceLayout.ImageFormat.UNKNOWN,
            IrisSpirvResourceLayout.DeclaredImageAccess.NONE);
    ResourceBinding bufferSampler = new ResourceBinding(address,
        ResourceKind.SAMPLED_IMAGE, StorageClass.UNIFORM_CONSTANT,
        Access.READ_ONLY, new IrisSpirvResourceLayout.TypeRef(List.of(),
            new IrisSpirvResourceLayout.SampledImageType(image)));
    IrisSpirvResourceLayout reflected = new IrisSpirvResourceLayout(
        List.of(bufferSampler), Map.of(address, "u_SectionTimeInfo"), true);
    IrisProgramResourceLayout layout = new IrisProgramResourceLayout(List.of(
        new IrisProgramResourceLayout.StageLayout(IrisShaderStage.VERTEX,
            reflected)));
    IrisGlResourceBindingSnapshot snapshot =
        new IrisGlResourceBindingSnapshot(7,
            Map.of("u_SectionTimeInfo", 3), Map.of(3, integer(0)),
            Map.of(), Map.of(),
            Map.of(0, new TextureUnitBinding(
                IrisGlResourceBindingTracker.GL_TEXTURE_2D, 50, 0)),
            Map.of(0, new TextureUnitBinding(
                IrisGlResourceBindingTracker.GL_TEXTURE_BUFFER, 70, 0)),
            Map.of(70, new TextureBufferBinding(
                IrisGlResourceBindingTracker.GL_TEXTURE_BUFFER, 0x822E,
                71)), Map.of(), Map.of());

    IrisResourceBindingResolver.Complete complete = assertInstanceOf(
        IrisResourceBindingResolver.Complete.class,
        IrisResourceBindingResolver.resolve(layout, snapshot));
    assertEquals(1, complete.matchedResources());
  }

  private static UniformValue integer(int value) {
    return new UniformValue(UniformValueKind.SIGNED_INT, 1, 1,
        new long[] {Integer.toUnsignedLong(value)});
  }

  private static IrisProgramResourceLayout completeLayout() {
    List<ResourceBinding> resources = List.of(
        binding(new UniformLocation(0, 0), ResourceKind.UNIFORM,
            StorageClass.UNIFORM),
        binding(new DescriptorAddress(0, 1), ResourceKind.SAMPLED_IMAGE,
            StorageClass.UNIFORM_CONSTANT),
        binding(new DescriptorAddress(0, 2), ResourceKind.STORAGE_IMAGE,
            StorageClass.UNIFORM_CONSTANT),
        binding(new DescriptorAddress(0, 4), ResourceKind.UNIFORM_BUFFER,
            StorageClass.UNIFORM),
        binding(new DescriptorAddress(0, 5), ResourceKind.STORAGE_BUFFER,
            StorageClass.STORAGE_BUFFER));
    LinkedHashMap<ResourceAddress, String> names = new LinkedHashMap<>();
    names.put(resources.get(0).address(), "plain");
    names.put(resources.get(1).address(), "sampled");
    names.put(resources.get(2).address(), "image");
    names.put(resources.get(3).address(), "ubo");
    names.put(resources.get(4).address(), "ssbo");
    IrisSpirvResourceLayout stage = new IrisSpirvResourceLayout(resources,
        names, true);
    return new IrisProgramResourceLayout(List.of(
        new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.FRAGMENT, stage)));
  }

  private static ResourceBinding binding(ResourceAddress address,
      ResourceKind kind, StorageClass storage) {
    return new ResourceBinding(address, kind, storage, Access.READ_ONLY,
        IrisSpirvResourceLayout.TypeRef.scalar(ScalarKind.FLOAT, 32));
  }
}

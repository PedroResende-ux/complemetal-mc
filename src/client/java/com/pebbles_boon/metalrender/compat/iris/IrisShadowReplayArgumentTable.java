package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.BufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.IndexedBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.UniformValue;
import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.ArgumentBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Resolves one draw's live GL resources to exact SPIRV-Cross MSL ids. */
public record IrisShadowReplayArgumentTable(List<StageTable> stages,
                                            List<String> blockers) {
  public static final int MAX_ARGUMENTS = 8_192;
  public static final int MAX_INLINE_UNIFORM_BYTES = 1 * 1024 * 1024;
  public static final int MAX_BLOCKERS = 32;

  public IrisShadowReplayArgumentTable {
    Objects.requireNonNull(stages, "stages");
    Objects.requireNonNull(blockers, "blockers");
    ArrayList<StageTable> copiedStages = new ArrayList<>(stages);
    copiedStages.sort(Comparator.comparing(StageTable::stage));
    if (copiedStages.stream().mapToInt(stage -> stage.arguments().size())
        .sum() > MAX_ARGUMENTS) {
      throw new IllegalArgumentException("MSL argument table exceeds bound");
    }
    stages = List.copyOf(copiedStages);
    blockers = blockers.stream().distinct().sorted().limit(MAX_BLOCKERS)
        .toList();
  }

  public static IrisShadowReplayArgumentTable resolve(
      IrisProgramResourceLayout semantic,
      IrisMslArgumentLayout msl,
      IrisGlResourceBindingSnapshot live,
      IrisShadowReplayBufferSnapshot buffers) {
    return resolve(semantic, msl, live, buffers,
        IrisShadowReplayTextureSnapshot.emptyEnabled(),
        IrisShadowReplaySamplerSnapshot.emptyEnabled());
  }

  public static IrisShadowReplayArgumentTable resolve(
      IrisProgramResourceLayout semantic,
      IrisMslArgumentLayout msl,
      IrisGlResourceBindingSnapshot live,
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayTextureSnapshot textures) {
    return resolve(semantic, msl, live, buffers, textures,
        IrisShadowReplaySamplerSnapshot.emptyEnabled());
  }

  public static IrisShadowReplayArgumentTable resolve(
      IrisProgramResourceLayout semantic,
      IrisMslArgumentLayout msl,
      IrisGlResourceBindingSnapshot live,
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayTextureSnapshot textures,
      IrisShadowReplaySamplerSnapshot samplers) {
    Objects.requireNonNull(semantic, "semantic");
    Objects.requireNonNull(msl, "msl");
    Objects.requireNonNull(buffers, "buffers");
    Objects.requireNonNull(textures, "textures");
    Objects.requireNonNull(samplers, "samplers");
    Builder builder = new Builder(live, buffers, textures, samplers);
    if (!msl.semanticallyMatches(semantic)) {
      builder.blockers.add("msl-argument-layout-semantic-mismatch");
      return builder.freeze();
    }
    if (live == null) {
      builder.blockers.add("resource-bindings-unavailable");
      return builder.freeze();
    }

    for (IrisProgramResourceLayout.StageLayout semanticStage
        : semantic.stages()) {
      IrisMslArgumentLayout.StageLayout mslStage = msl.stages().stream()
          .filter(stage -> stage.stage() == semanticStage.stage())
          .findFirst().orElseThrow();
      Map<ResourceAddress, ArgumentBinding> byAddress = new HashMap<>();
      mslStage.bindings().forEach(binding ->
          byAddress.put(binding.address(), binding));
      ArrayList<BoundArgument> arguments = new ArrayList<>();
      for (ResourceBinding resource : semanticStage.layout().resources()) {
        ArgumentBinding argument = byAddress.get(resource.address());
        if (argument == null) {
          builder.blockers.add("msl-argument-id-missing");
          continue;
        }
        String name = semanticStage.layout()
            .diagnosticName(resource.address()).orElse(null);
        builder.bind(resource, argument, name, arguments);
      }
      builder.stages.add(new StageTable(semanticStage.stage(), arguments));
    }
    return builder.freeze();
  }

  public boolean complete() {
    return blockers.isEmpty();
  }

  public int argumentCount() {
    return stages.stream().mapToInt(stage -> stage.arguments().size()).sum();
  }

  public int inlineUniformBytes() {
    return stages.stream().flatMap(stage -> stage.arguments().stream())
        .filter(argument -> argument.value() instanceof InlineUniform)
        .map(argument -> (InlineUniform) argument.value())
        .mapToInt(value -> value.bytes().length).sum();
  }

  public record StageTable(IrisShaderStage stage,
                           List<BoundArgument> arguments) {
    public StageTable {
      Objects.requireNonNull(stage, "stage");
      ArrayList<BoundArgument> copied = new ArrayList<>(arguments);
      copied.sort(Comparator.comparingInt(BoundArgument::argumentBufferIndex)
          .thenComparingInt(BoundArgument::id));
      long previous = -1;
      for (BoundArgument argument : copied) {
        long key = ((long) argument.argumentBufferIndex() << 32)
            | Integer.toUnsignedLong(argument.id());
        if (key == previous) {
          throw new IllegalArgumentException("duplicate bound MSL argument");
        }
        previous = key;
      }
      arguments = List.copyOf(copied);
    }
  }

  public record BoundArgument(int argumentBufferIndex, int id,
                              ArgumentValue value) {
    public BoundArgument {
      if (argumentBufferIndex < 0
          || argumentBufferIndex
          > IrisMslArgumentLayout.MAX_ARGUMENT_BUFFER_INDEX
          || id < 0 || id > IrisMslArgumentLayout.MAX_ARGUMENT_ID) {
        throw new IllegalArgumentException("invalid bound MSL argument");
      }
      Objects.requireNonNull(value, "value");
    }
  }

  public sealed interface ArgumentValue permits InlineUniform,
      BufferImage, TextureBufferImage, TextureImage, CanonicalZeroTexture,
      CapturedSampler, CanonicalDefaultSampler {
  }

  public record InlineUniform(byte[] bytes) implements ArgumentValue {
    public InlineUniform {
      bytes = Objects.requireNonNull(bytes, "bytes").clone();
      if (bytes.length == 0 || bytes.length > MAX_INLINE_UNIFORM_BYTES) {
        throw new IllegalArgumentException("invalid inline uniform bytes");
      }
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public record BufferImage(int imageId) implements ArgumentValue {
    public BufferImage {
      if (imageId < 0) {
        throw new IllegalArgumentException("negative buffer image id");
      }
    }
  }

  /** Buffer image plus the sized GL texel format used for a texture view. */
  public record TextureBufferImage(int imageId, int internalFormat)
      implements ArgumentValue {
    public TextureBufferImage {
      if (imageId < 0 || internalFormat <= 0) {
        throw new IllegalArgumentException("invalid texture-buffer image");
      }
    }
  }

  public record TextureImage(int glTexture) implements ArgumentValue {
    public TextureImage {
      if (glTexture <= 0) {
        throw new IllegalArgumentException("invalid texture image name");
      }
    }
  }

  /** Texture bound to an image unit; native replay requires shader-write usage. */
  public record StorageTextureImage(int glTexture) implements ArgumentValue {
    public StorageTextureImage {
      if (glTexture <= 0) {
        throw new IllegalArgumentException("invalid storage texture image name");
      }
    }
  }

  public record CanonicalZeroTexture() implements ArgumentValue {
  }

  public record CapturedSampler(
      IrisGlSamplerMirror.SamplerState state) implements ArgumentValue {
    public CapturedSampler {
      Objects.requireNonNull(state, "state");
      if (!state.complete()) {
        throw new IllegalArgumentException("incomplete captured sampler");
      }
    }
  }

  public record CanonicalDefaultSampler() implements ArgumentValue {
  }

  static byte[] encodeUniform(UniformValue value) {
    Objects.requireNonNull(value, "value");
    int rows = value.rows();
    int columns = value.columns();
    int vectorStride = rows <= 1 ? Integer.BYTES
        : rows == 2 ? 2 * Integer.BYTES : 4 * Integer.BYTES;
    int bytes = columns == 1 ? vectorStride : columns * vectorStride;
    ByteBuffer encoded = ByteBuffer.allocate(bytes)
        .order(ByteOrder.LITTLE_ENDIAN);
    long[] raw = value.rawBits();
    for (int column = 0; column < columns; column++) {
      int columnOffset = column * vectorStride;
      for (int row = 0; row < rows; row++) {
        encoded.putInt(columnOffset + row * Integer.BYTES,
            (int) raw[column * rows + row]);
      }
    }
    return encoded.array();
  }

  private static final class Builder {
    private final IrisGlResourceBindingSnapshot live;
    private final IrisShadowReplayBufferSnapshot buffers;
    private final IrisShadowReplayTextureSnapshot textures;
    private final IrisShadowReplaySamplerSnapshot samplers;
    private final ArrayList<StageTable> stages = new ArrayList<>();
    private final TreeSet<String> blockers = new TreeSet<>();
    private int inlineBytes;

    private Builder(IrisGlResourceBindingSnapshot live,
        IrisShadowReplayBufferSnapshot buffers,
        IrisShadowReplayTextureSnapshot textures,
        IrisShadowReplaySamplerSnapshot samplers) {
      this.live = live;
      this.buffers = buffers;
      this.textures = textures;
      this.samplers = samplers;
      if (!buffers.captureEnabled()) {
        blockers.add("shadow-buffer-capture-disabled");
      }
      blockers.addAll(buffers.drawBlockers());
      if (!textures.captureEnabled()) {
        blockers.add("shadow-texture-capture-disabled");
      }
      if (!samplers.captureEnabled()) {
        blockers.add("shadow-sampler-capture-disabled");
      }
    }

    private void bind(ResourceBinding resource, ArgumentBinding argument,
        String name, List<BoundArgument> output) {
      switch (resource.kind()) {
        case UNIFORM -> uniform(argument, name, output);
        case UNIFORM_BUFFER -> uniformBuffer(argument, name, output);
        case STORAGE_BUFFER -> storageBuffer(resource, argument, output);
        case SAMPLED_IMAGE, TEXTURE, SAMPLER -> sampled(resource, argument,
            name, output);
        case STORAGE_IMAGE -> storageImage(argument, name, output);
      }
    }

    private void uniform(ArgumentBinding argument, String name,
        List<BoundArgument> output) {
      Integer location = location(name);
      UniformValue value = location == null ? null
          : live.uniformValues().get(location);
      if (value == null) {
        blockers.add("uniform-value-unavailable");
        return;
      }
      byte[] encoded = encodeUniform(value);
      if (inlineBytes > MAX_INLINE_UNIFORM_BYTES - encoded.length) {
        blockers.add("inline-uniform-byte-capacity-exceeded");
        return;
      }
      inlineBytes += encoded.length;
      output.add(new BoundArgument(argument.argumentBufferIndex(),
          argument.primaryId(), new InlineUniform(encoded)));
    }

    private void uniformBuffer(ArgumentBinding argument, String name,
        List<BoundArgument> output) {
      if (name == null) {
        blockers.add("uniform-buffer-name-unavailable");
        return;
      }
      Integer block = live.uniformBlockIndices().get(name);
      Integer binding = block == null ? null
          : live.uniformBlockBindings().get(block);
      if (binding == null) {
        blockers.add("uniform-buffer-binding-unavailable");
        return;
      }
      bindBuffer(argument, new IndexedBufferBinding(
          IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER, binding), output,
          "uniform-buffer-image-unavailable");
    }

    private void storageImage(ArgumentBinding argument, String name,
        List<BoundArgument> output) {
      Integer location = location(name);
      UniformValue unitValue = location == null
          ? null : live.uniformValues().get(location);
      if (unitValue == null || unitValue.rawBits().length != 1) {
        blockers.add("storage-image-unit-unavailable");
        return;
      }
      int unit = (int) unitValue.rawBits()[0];
      IrisGlResourceBindingSnapshot.ImageUnitBinding binding =
          live.imageUnits().get(unit);
      if (binding == null || binding.texture() <= 0) {
        blockers.add("storage-image-binding-unavailable");
        return;
      }
      if (binding.layered()) {
        blockers.add("layered-image-snapshot-unavailable");
        return;
      }
      IrisGlTextureMirror.TextureSnapshot snapshot =
          textures.textures().get(binding.texture());
      if (snapshot == null
          || snapshot.mipLevel() != binding.level()
          || snapshot.layer() != binding.layer()) {
        blockers.add("storage-image-snapshot-unavailable");
        return;
      }
      output.add(new BoundArgument(argument.argumentBufferIndex(),
          argument.primaryId(),
          new StorageTextureImage(binding.texture())));
    }

    private void storageBuffer(ResourceBinding resource,
        ArgumentBinding argument, List<BoundArgument> output) {
      bindBuffer(argument, new IndexedBufferBinding(
          IrisGlResourceBindingTracker.GL_SHADER_STORAGE_BUFFER,
          resource.address().index()), output,
          "storage-buffer-image-unavailable");
    }

    private void bindBuffer(ArgumentBinding argument,
        IndexedBufferBinding indexed, List<BoundArgument> output,
        String failure) {
      BufferBinding liveBinding = live.indexedBuffers().get(indexed);
      IrisShadowReplayBufferSnapshot.BufferRef reference =
          buffers.indexedBuffers().get(indexed);
      if (liveBinding == null || liveBinding.buffer() <= 0
          || reference == null
          || reference.imageId() >= buffers.images().size()) {
        blockers.add(failure);
        return;
      }
      output.add(new BoundArgument(argument.argumentBufferIndex(),
          argument.primaryId(), new BufferImage(reference.imageId())));
    }

    private void sampled(ResourceBinding resource, ArgumentBinding argument,
        String name, List<BoundArgument> output) {
      Integer location = location(name);
      UniformValue unitValue = location == null ? null
          : live.uniformValues().get(location);
      if (unitValue == null || unitValue.rawBits().length != 1) {
        blockers.add("sampled-unit-unavailable");
        return;
      }
      int unit = (int) unitValue.rawBits()[0];
      boolean bufferTexture =
          IrisResourceBindingResolver.isBufferTexture(resource);
      IrisGlResourceBindingSnapshot.TextureUnitBinding texture =
          live.sampledTextureBinding(unit, bufferTexture);
      if (texture == null) {
        blockers.add("sampled-texture-binding-unavailable");
        return;
      }
      if (bufferTexture && texture.texture() != 0
          && resource.kind() != ResourceKind.SAMPLER) {
        TextureBufferBinding storage = live.textureBuffers().get(
            texture.texture());
        IrisShadowReplayBufferSnapshot.BufferRef reference =
            buffers.textureBuffers().get(texture.texture());
        if (storage == null || storage.buffer() <= 0 || reference == null
            || reference.imageId() >= buffers.images().size()) {
          blockers.add("sampled-buffer-image-unavailable");
          return;
        }
        output.add(new BoundArgument(argument.argumentBufferIndex(),
            argument.primaryId(), new TextureBufferImage(
                reference.imageId(), storage.internalFormat())));
        // samplerBuffer/textureBuffer fetches do not consume an MSL sampler
        // state; SPIRV-Cross exposes only the texture-buffer argument.
        return;
      }
      if (texture.texture() != 0
          && resource.kind() != ResourceKind.SAMPLER) {
        if (!textures.textures().containsKey(texture.texture())) {
          blockers.add("sampled-texture-snapshot-unavailable");
          return;
        }
        output.add(new BoundArgument(argument.argumentBufferIndex(),
            argument.primaryId(), new TextureImage(texture.texture())));
      } else if (texture.texture() == 0
          && resource.kind() != ResourceKind.SAMPLER) {
        output.add(new BoundArgument(argument.argumentBufferIndex(),
            argument.primaryId(), new CanonicalZeroTexture()));
      }
      boolean samplerRequired = resource.kind() == ResourceKind.SAMPLER
          || resource.kind() == ResourceKind.SAMPLED_IMAGE
              && argument.secondaryId() >= 0;
      int samplerId = resource.kind() == ResourceKind.SAMPLED_IMAGE
          ? argument.secondaryId() : argument.primaryId();
      if (samplerRequired) {
        if (texture.texture() != 0 || texture.sampler() != 0) {
          IrisGlSamplerMirror.Snapshot captured =
              samplers.textureUnits().get(unit);
          if (captured == null || !captured.state().complete()) {
            blockers.add("sampler-state-snapshot-unavailable");
            return;
          }
          output.add(new BoundArgument(argument.argumentBufferIndex(),
              samplerId, new CapturedSampler(captured.state())));
        } else {
          output.add(new BoundArgument(argument.argumentBufferIndex(),
              samplerId, new CanonicalDefaultSampler()));
        }
      }
    }

    private Integer location(String name) {
      return name == null ? null : live.uniformLocations().get(name);
    }

    private IrisShadowReplayArgumentTable freeze() {
      ArrayList<String> finalBlockers = new ArrayList<>(blockers);
      if (finalBlockers.size() > MAX_BLOCKERS) {
        finalBlockers = new ArrayList<>(finalBlockers.subList(0,
            MAX_BLOCKERS - 1));
        finalBlockers.add("blocker-capacity-exceeded");
      }
      return new IrisShadowReplayArgumentTable(stages, finalBlockers);
    }
  }
}

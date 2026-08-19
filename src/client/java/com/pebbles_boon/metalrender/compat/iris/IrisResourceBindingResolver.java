package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.IndexedBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.BufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageDimension;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.SampledImageType;
import java.util.Objects;

/** Strictly proves that reflected SPIR-V resources have live GL bindings. */
public final class IrisResourceBindingResolver {
  private IrisResourceBindingResolver() {
  }

  public static Result resolve(IrisProgramResourceLayout layout,
                               IrisGlResourceBindingSnapshot snapshot) {
    Objects.requireNonNull(layout, "layout");
    if (snapshot == null) {
      return new Incomplete("runtime-binding-snapshot-unavailable");
    }
    int matched = 0;
    for (IrisProgramResourceLayout.StageLayout stage : layout.stages()) {
      for (ResourceBinding resource : stage.layout().resources()) {
        String name = stage.layout().diagnosticName(resource.address())
            .orElse(null);
        String failure = resolveOne(resource, name, snapshot);
        if (failure != null) {
          return new Incomplete(stage.stage().cacheName() + ':' + failure);
        }
        matched++;
      }
    }
    return new Complete(matched);
  }

  private static String resolveOne(ResourceBinding resource, String name,
      IrisGlResourceBindingSnapshot snapshot) {
    return switch (resource.kind()) {
      case UNIFORM -> uniform(name, snapshot);
      case SAMPLED_IMAGE, TEXTURE, SAMPLER -> sampled(resource, name,
          snapshot);
      case STORAGE_IMAGE -> image(name, snapshot);
      case UNIFORM_BUFFER -> uniformBuffer(name, snapshot);
      case STORAGE_BUFFER -> storageBuffer(resource, snapshot);
    };
  }

  private static String uniform(String name,
      IrisGlResourceBindingSnapshot snapshot) {
    Integer location = location(name, snapshot);
    if (location == null) {
      return detail("uniform-location-missing", name);
    }
    return snapshot.uniformValues().containsKey(location)
        ? null : "uniform-value-missing";
  }

  private static String sampled(ResourceBinding resource, String name,
      IrisGlResourceBindingSnapshot snapshot) {
    Integer location = location(name, snapshot);
    if (location == null) {
      return detail("sampled-location-missing", name);
    }
    IrisGlResourceBindingSnapshot.UniformValue value =
        snapshot.uniformValues().get(location);
    if (value == null || value.rawBits().length != 1) {
      return "sampled-unit-missing";
    }
    int unit = (int) value.rawBits()[0];
    IrisGlResourceBindingSnapshot.TextureUnitBinding binding =
        snapshot.textureUnits().get(unit);
    if (binding == null || binding.texture() <= 0) {
      return detail("sampled-texture-missing", name) + ":unit-" + unit;
    }
    if (isBufferTexture(resource)) {
      IrisGlResourceBindingSnapshot.TextureBufferBinding textureBuffer =
          snapshot.textureBuffers().get(binding.texture());
      if (textureBuffer == null || textureBuffer.buffer() <= 0) {
        return detail("sampled-buffer-storage-missing", name)
            + ":unit-" + unit;
      }
    }
    return null;
  }

  private static String image(String name,
      IrisGlResourceBindingSnapshot snapshot) {
    Integer location = location(name, snapshot);
    if (location == null) {
      return detail("image-location-missing", name);
    }
    IrisGlResourceBindingSnapshot.UniformValue value =
        snapshot.uniformValues().get(location);
    if (value == null || value.rawBits().length != 1) {
      return "image-unit-missing";
    }
    int unit = (int) value.rawBits()[0];
    IrisGlResourceBindingSnapshot.ImageUnitBinding binding =
        snapshot.imageUnits().get(unit);
    return binding != null && binding.texture() > 0
        ? null : "image-texture-missing";
  }

  private static String uniformBuffer(String name,
      IrisGlResourceBindingSnapshot snapshot) {
    if (name == null) {
      return "uniform-buffer-name-missing";
    }
    Integer blockIndex = snapshot.uniformBlockIndices().get(name);
    if (blockIndex == null) {
      return "uniform-buffer-index-missing";
    }
    Integer binding = snapshot.uniformBlockBindings().get(blockIndex);
    if (binding == null) {
      return "uniform-buffer-binding-missing";
    }
    BufferBinding buffer = snapshot.indexedBuffers().get(new IndexedBufferBinding(
        IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER, binding));
    return buffer != null && buffer.buffer() > 0
        ? null : "uniform-buffer-missing";
  }

  private static String storageBuffer(ResourceBinding resource,
      IrisGlResourceBindingSnapshot snapshot) {
    BufferBinding buffer = snapshot.indexedBuffers().get(new IndexedBufferBinding(
        IrisGlResourceBindingTracker.GL_SHADER_STORAGE_BUFFER,
        resource.address().index()));
    return buffer != null && buffer.buffer() > 0
        ? null : "storage-buffer-missing";
  }

  private static Integer location(String name,
      IrisGlResourceBindingSnapshot snapshot) {
    return name == null ? null : snapshot.uniformLocations().get(name);
  }

  private static String detail(String reason, String name) {
    return reason + ':' + (name == null || name.isBlank()
        ? "unnamed" : name);
  }

  private static boolean isBufferTexture(ResourceBinding resource) {
    if (resource.baseType() instanceof SampledImageType sampled) {
      return sampled.imageType().dimension() == ImageDimension.BUFFER;
    }
    return resource.baseType() instanceof ImageType image
        && image.dimension() == ImageDimension.BUFFER;
  }

  public sealed interface Result permits Complete, Incomplete {
  }

  public record Complete(int matchedResources) implements Result {
    public Complete {
      if (matchedResources < 0) {
        throw new IllegalArgumentException("negative matched resource count");
      }
    }
  }

  public record Incomplete(String reason) implements Result {
    public Incomplete {
      Objects.requireNonNull(reason, "reason");
    }
  }
}

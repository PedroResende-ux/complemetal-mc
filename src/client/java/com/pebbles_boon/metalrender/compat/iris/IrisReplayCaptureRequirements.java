package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.IndexedBufferBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisGlResourceBindingSnapshot.TextureUnitBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.ArgumentBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Exact draw-time GL resources required by one reflected Iris program. */
record IrisReplayCaptureRequirements(
    Set<IndexedBufferBinding> indexedBuffers,
    Set<Integer> textureNames,
    Set<Integer> textureBufferNames,
    Set<Integer> samplerUnits) {
  IrisReplayCaptureRequirements {
    indexedBuffers = Set.copyOf(indexedBuffers);
    textureNames = positiveNames(textureNames, "texture name");
    textureBufferNames = positiveNames(textureBufferNames,
        "texture-buffer name");
    samplerUnits = Set.copyOf(samplerUnits);
    if (samplerUnits.stream().anyMatch(unit -> unit == null || unit < 0
        || unit >= IrisShadowReplaySamplerSnapshot.MAX_UNITS)) {
      throw new IllegalArgumentException("invalid sampler unit");
    }
  }

  static Optional<IrisReplayCaptureRequirements> resolve(
      IrisProgramResourceLayout layout,
      IrisMslArgumentLayout msl,
      IrisGlResourceBindingSnapshot live) {
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(msl, "msl");
    if (live == null) {
      return Optional.empty();
    }
    TreeSet<IndexedBufferBinding> indexed = new TreeSet<>(Comparator
        .comparingInt(IndexedBufferBinding::target)
        .thenComparingInt(IndexedBufferBinding::index));
    TreeSet<Integer> textures = new TreeSet<>();
    TreeSet<Integer> textureBuffers = new TreeSet<>();
    TreeSet<Integer> samplers = new TreeSet<>();
    for (IrisProgramResourceLayout.StageLayout stage : layout.stages()) {
      IrisMslArgumentLayout.StageLayout mslStage = msl.stages().stream()
          .filter(candidate -> candidate.stage() == stage.stage())
          .findFirst().orElse(null);
      if (mslStage == null) {
        return Optional.empty();
      }
      Map<IrisSpirvResourceLayout.ResourceAddress, ArgumentBinding>
          arguments = new HashMap<>();
      mslStage.bindings().forEach(binding ->
          arguments.put(binding.address(), binding));
      for (ResourceBinding resource : stage.layout().resources()) {
        ArgumentBinding argument = arguments.get(resource.address());
        if (argument == null) {
          return Optional.empty();
        }
        String name = stage.layout().diagnosticName(resource.address())
            .orElse(null);
        switch (resource.kind()) {
          case UNIFORM -> {
            // Plain uniforms are copied from the live value table.
          }
          case UNIFORM_BUFFER -> {
            if (name == null) {
              return Optional.empty();
            }
            Integer block = live.uniformBlockIndices().get(name);
            Integer binding = block == null ? null
                : live.uniformBlockBindings().get(block);
            if (binding == null) {
              return Optional.empty();
            }
            indexed.add(new IndexedBufferBinding(
                IrisGlResourceBindingTracker.GL_UNIFORM_BUFFER, binding));
          }
          case STORAGE_BUFFER -> indexed.add(new IndexedBufferBinding(
              IrisGlResourceBindingTracker.GL_SHADER_STORAGE_BUFFER,
              resource.address().index()));
          case SAMPLED_IMAGE, TEXTURE, SAMPLER -> {
            if (name == null) {
              return Optional.empty();
            }
            Integer location = live.uniformLocations().get(name);
            IrisGlResourceBindingSnapshot.UniformValue value =
                location == null ? null : live.uniformValues().get(location);
            if (value == null || value.rawBits().length != 1) {
              return Optional.empty();
            }
            long rawUnit = value.rawBits()[0];
            if (rawUnit < 0
                || rawUnit >= IrisShadowReplaySamplerSnapshot.MAX_UNITS) {
              return Optional.empty();
            }
            int unit = (int) rawUnit;
            boolean bufferTexture =
                IrisResourceBindingResolver.isBufferTexture(resource);
            TextureUnitBinding texture = live.sampledTextureBinding(unit,
                bufferTexture);
            if (texture == null) {
              return Optional.empty();
            }
            if (resource.kind() != ResourceKind.SAMPLER
                && texture.texture() > 0) {
              (bufferTexture ? textureBuffers : textures)
                  .add(texture.texture());
            }
            if (resource.kind() == ResourceKind.SAMPLER
                || resource.kind() == ResourceKind.SAMPLED_IMAGE
                    && argument.secondaryId() >= 0) {
              samplers.add(unit);
            }
          }
          case STORAGE_IMAGE -> {
            // Storage images are not replayable yet. Preserve the existing
            // fail-open full capture so the argument gate reports the blocker.
            return Optional.empty();
          }
        }
      }
    }
    return Optional.of(new IrisReplayCaptureRequirements(indexed, textures,
        textureBuffers, samplers));
  }

  private static Set<Integer> positiveNames(Set<Integer> values,
      String label) {
    LinkedHashSet<Integer> copy = new LinkedHashSet<>();
    for (Integer value : values) {
      if (value == null || value <= 0) {
        throw new IllegalArgumentException("invalid " + label);
      }
      copy.add(value);
    }
    return Set.copyOf(copy);
  }
}

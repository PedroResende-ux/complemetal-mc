package com.pebbles_boon.metalrender.compat.iris;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Draw-time retained sampler state resolved for each live texture unit. */
public record IrisShadowReplaySamplerSnapshot(
    boolean captureEnabled,
    Map<Integer, IrisGlSamplerMirror.Snapshot> textureUnits,
    List<String> blockers) {
  public static final int MAX_UNITS = 256;
  public static final int MAX_BLOCKERS = 32;

  public IrisShadowReplaySamplerSnapshot {
    Objects.requireNonNull(textureUnits, "textureUnits");
    Objects.requireNonNull(blockers, "blockers");
    if (textureUnits.size() > MAX_UNITS || blockers.size() > MAX_BLOCKERS) {
      throw new IllegalArgumentException("sampler snapshot exceeds bounds");
    }
    TreeMap<Integer, IrisGlSamplerMirror.Snapshot> sorted = new TreeMap<>();
    for (Map.Entry<Integer, IrisGlSamplerMirror.Snapshot> entry
        : textureUnits.entrySet()) {
      if (entry.getKey() < 0 || entry.getKey() >= MAX_UNITS) {
        throw new IllegalArgumentException("invalid sampler texture unit");
      }
      sorted.put(entry.getKey(), Objects.requireNonNull(entry.getValue(),
          "sampler snapshot"));
    }
    textureUnits = Map.copyOf(sorted);
    blockers = blockers.stream().distinct().sorted().toList();
    if (!captureEnabled && (!textureUnits.isEmpty() || !blockers.isEmpty())) {
      throw new IllegalArgumentException("disabled sampler capture not empty");
    }
  }

  public static IrisShadowReplaySamplerSnapshot disabled() {
    return new IrisShadowReplaySamplerSnapshot(false, Map.of(), List.of());
  }

  public static IrisShadowReplaySamplerSnapshot emptyEnabled() {
    return new IrisShadowReplaySamplerSnapshot(true, Map.of(), List.of());
  }

  public static IrisShadowReplaySamplerSnapshot capture(
      IrisGlResourceBindingSnapshot resources, IrisGlSamplerMirror mirror) {
    return capture(resources, mirror, null);
  }

  static IrisShadowReplaySamplerSnapshot capture(
      IrisGlResourceBindingSnapshot resources, IrisGlSamplerMirror mirror,
      java.util.Set<Integer> requiredUnits) {
    Objects.requireNonNull(mirror, "mirror");
    if (resources == null) {
      return new IrisShadowReplaySamplerSnapshot(true, Map.of(),
          List.of("resource-bindings-unavailable"));
    }
    TreeSet<String> blockers = new TreeSet<>();
    LinkedHashMap<Integer, IrisGlSamplerMirror.Snapshot> snapshots =
        new LinkedHashMap<>();
    TreeMap<Integer, IrisGlResourceBindingSnapshot.TextureUnitBinding> sorted =
        new TreeMap<>(resources.textureUnits());
    java.util.Set<Integer> sampledUnits =
        IrisShadowReplayTextureSnapshot.sampledUnits(resources);
    for (Map.Entry<Integer, IrisGlResourceBindingSnapshot.TextureUnitBinding>
        entry : sorted.entrySet()) {
      int unit = entry.getKey();
      if (requiredUnits == null && !sampledUnits.contains(unit)
          || requiredUnits != null && !requiredUnits.contains(unit)) {
        continue;
      }
      IrisGlResourceBindingSnapshot.TextureUnitBinding binding =
          entry.getValue();
      if (binding.texture() == 0 && binding.sampler() == 0) {
        continue;
      }
      IrisGlSamplerMirror.Snapshot snapshot = binding.sampler() > 0
          ? mirror.samplerSnapshot(binding.sampler(),
              binding.samplerGeneration()).orElse(null)
          : mirror.textureSnapshot(binding.texture(),
              binding.samplerGeneration()).orElse(null);
      if (snapshot == null) {
        blockers.add("sampler-state-snapshot-unavailable");
        continue;
      }
      if (!snapshot.state().complete()) {
        blockers.add("sampler-state-parameter-unsupported");
      }
      snapshots.put(unit, snapshot);
    }
    ArrayList<String> reasons = new ArrayList<>(blockers);
    if (reasons.size() > MAX_BLOCKERS) {
      reasons = new ArrayList<>(reasons.subList(0, MAX_BLOCKERS - 1));
      reasons.add("blocker-capacity-exceeded");
    }
    return new IrisShadowReplaySamplerSnapshot(true, snapshots, reasons);
  }

  public boolean complete() {
    return captureEnabled && blockers.isEmpty();
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Draw-time retained views of immutable uploaded texture images. */
public record IrisShadowReplayTextureSnapshot(
    boolean captureEnabled,
    Map<Integer, IrisGlTextureMirror.TextureSnapshot> textures,
    List<String> blockers) {
  public static final int MAX_TEXTURES = 256;
  public static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
  public static final int MAX_BLOCKERS = 32;
  static final int MAX_SMALL_RESIDENT_TEXTURE_PIXELS = 256 * 256;
  private static final int MAX_READBACK_DIAGNOSTICS = 64;
  private static final java.util.Set<String> READBACK_DIAGNOSTICS =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final java.util.Set<String> GPU_HANDOFF_DIAGNOSTICS =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  public IrisShadowReplayTextureSnapshot {
    Objects.requireNonNull(textures, "textures");
    Objects.requireNonNull(blockers, "blockers");
    if (textures.size() > MAX_TEXTURES || blockers.size() > MAX_BLOCKERS) {
      throw new IllegalArgumentException("texture snapshot exceeds bounds");
    }
    ArrayList<Integer> names = new ArrayList<>(textures.keySet());
    names.sort(Integer::compareTo);
    LinkedHashMap<Integer, IrisGlTextureMirror.TextureSnapshot> copied =
        new LinkedHashMap<>();
    long bytes = 0;
    for (Integer name : names) {
      IrisGlTextureMirror.TextureSnapshot snapshot =
          Objects.requireNonNull(textures.get(name), "texture snapshot");
      if (name == null || name <= 0 || snapshot.texture() != name) {
        throw new IllegalArgumentException("invalid texture snapshot name");
      }
      bytes = Math.addExact(bytes, snapshot.byteLength());
      copied.put(name, snapshot);
    }
    if (bytes > MAX_TOTAL_BYTES) {
      throw new IllegalArgumentException("texture snapshot byte bound exceeded");
    }
    textures = Map.copyOf(copied);
    blockers = blockers.stream().distinct().sorted().toList();
    if (!captureEnabled && (!textures.isEmpty() || !blockers.isEmpty())) {
      throw new IllegalArgumentException(
          "disabled texture capture must be empty");
    }
  }

  public static IrisShadowReplayTextureSnapshot disabled() {
    return new IrisShadowReplayTextureSnapshot(false, Map.of(), List.of());
  }

  public static IrisShadowReplayTextureSnapshot emptyEnabled() {
    return new IrisShadowReplayTextureSnapshot(true, Map.of(), List.of());
  }

  public static IrisShadowReplayTextureSnapshot capture(
      IrisGlResourceBindingSnapshot resources, IrisGlTextureMirror mirror) {
    return capture(resources, mirror, false);
  }

  public static IrisShadowReplayTextureSnapshot capture(
      IrisGlResourceBindingSnapshot resources, IrisGlTextureMirror mirror,
      boolean preferGpuHandoff) {
    return capture(resources, mirror, preferGpuHandoff, false, null, null,
        null);
  }

  /**
   * Captures FINAL-cutover inputs using every validated cross-API handoff.
   * Packed HDR attachments are converted into an RGBA16F IOSurface on the
   * GPU. Full-graph parity deliberately uses the stricter overload above so
   * its diagnostic baseline remains byte-stable across the Apple GL driver.
   */
  static IrisShadowReplayTextureSnapshot captureForFinalCutover(
      IrisGlResourceBindingSnapshot resources, IrisGlTextureMirror mirror) {
    return capture(resources, mirror, true, true, null, null, null);
  }

  static IrisShadowReplayTextureSnapshot capture(
      IrisGlResourceBindingSnapshot resources, IrisGlTextureMirror mirror,
      boolean preferGpuHandoff,
      Map<Integer, IrisGlTextureMirror.TextureSnapshot> retained) {
    return capture(resources, mirror, preferGpuHandoff, false, retained,
        null, null);
  }

  static IrisShadowReplayTextureSnapshot capture(
      IrisGlResourceBindingSnapshot resources, IrisGlTextureMirror mirror,
      boolean preferGpuHandoff,
      Map<Integer, IrisGlTextureMirror.TextureSnapshot> retained,
      java.util.Set<Integer> requiredTextureNames) {
    return capture(resources, mirror, preferGpuHandoff, false, retained,
        requiredTextureNames, null);
  }

  static IrisShadowReplayTextureSnapshot capture(
      IrisGlResourceBindingSnapshot resources, IrisGlTextureMirror mirror,
      boolean preferGpuHandoff,
      Map<Integer, IrisGlTextureMirror.TextureSnapshot> retained,
      java.util.Set<Integer> requiredTextureNames,
      GraphReferenceResolver graphReferenceResolver) {
    return capture(resources, mirror, preferGpuHandoff, false, retained,
        requiredTextureNames, graphReferenceResolver);
  }

  private static IrisShadowReplayTextureSnapshot capture(
      IrisGlResourceBindingSnapshot resources, IrisGlTextureMirror mirror,
      boolean preferGpuHandoff, boolean allowPackedFloatHandoff,
      Map<Integer, IrisGlTextureMirror.TextureSnapshot> retained,
      java.util.Set<Integer> requiredTextureNames,
      GraphReferenceResolver graphReferenceResolver) {
    Objects.requireNonNull(mirror, "mirror");
    if (resources == null) {
      return new IrisShadowReplayTextureSnapshot(true, Map.of(),
          List.of("resource-bindings-unavailable"));
    }
    java.util.TreeMap<Integer, TextureReference> references =
        new java.util.TreeMap<>();
    java.util.TreeSet<String> blockers = new java.util.TreeSet<>();
    java.util.Set<Integer> sampledUnits = sampledUnits(resources);
    for (Map.Entry<Integer, IrisGlResourceBindingSnapshot.TextureUnitBinding>
        entry : resources.textureUnits().entrySet()) {
      if (!sampledUnits.contains(entry.getKey())) {
        continue;
      }
      IrisGlResourceBindingSnapshot.TextureUnitBinding binding =
          entry.getValue();
      int texture = binding.texture();
      if (requiredTextureNames != null
          && !requiredTextureNames.contains(texture)) {
        continue;
      }
      IrisGlResourceBindingSnapshot.TextureBufferBinding bufferTexture =
          resources.textureBuffers().get(texture);
      if (texture <= 0 || bufferTexture != null
          && bufferTexture.target() == binding.target()
          || references.containsKey(texture)) {
        continue;
      }
      references.put(texture, new TextureReference(texture,
          binding.mirrorGeneration(), binding.target(), 0, 0,
          "sampled-texture"));
    }
    for (IrisGlResourceBindingSnapshot.ImageUnitBinding binding
        : resources.imageUnits().values()) {
      if (requiredTextureNames != null
          && !requiredTextureNames.contains(binding.texture())) {
        continue;
      }
      if (binding.texture() <= 0) {
        continue;
      }
      TextureReference existingReference = references.get(binding.texture());
      if (existingReference != null) {
        if (existingReference.mipLevel() != binding.level()
            || existingReference.layer() != binding.layer()) {
          blockers.add("mixed-texture-subresource-snapshot-unavailable");
        }
        continue;
      }
      if (binding.layered()) {
        blockers.add("layered-image-snapshot-unavailable");
        continue;
      }
      references.put(binding.texture(), new TextureReference(
          binding.texture(), binding.mirrorGeneration(), 0, binding.level(),
          binding.layer(), "storage-image"));
    }
    if (references.size() > MAX_TEXTURES) {
      blockers.add("texture-snapshot-capacity-exceeded");
    }
    LinkedHashMap<Integer, IrisGlTextureMirror.TextureSnapshot> snapshots =
        new LinkedHashMap<>();
    int retainedCount = 0;
    for (TextureReference reference : references.values()) {
      if (retainedCount++ >= MAX_TEXTURES) {
        break;
      }
      retain(mirror, reference.texture(), reference.generation(),
          reference.target(), reference.mipLevel(), reference.layer(),
          snapshots, blockers, reference.category(), preferGpuHandoff,
          allowPackedFloatHandoff, retained, graphReferenceResolver);
    }
    long bytes = snapshots.values().stream()
        .mapToLong(IrisGlTextureMirror.TextureSnapshot::byteLength).sum();
    if (bytes > MAX_TOTAL_BYTES) {
      snapshots.clear();
      blockers.add("texture-snapshot-byte-capacity-exceeded");
    }
    ArrayList<String> reasons = new ArrayList<>(blockers);
    if (reasons.size() > MAX_BLOCKERS) {
      reasons = new ArrayList<>(reasons.subList(0, MAX_BLOCKERS - 1));
      reasons.add("blocker-capacity-exceeded");
    }
    return new IrisShadowReplayTextureSnapshot(true, snapshots, reasons);
  }

  private static void retain(IrisGlTextureMirror mirror, int texture,
      long generation, int target, int mipLevel, int layer,
      Map<Integer, IrisGlTextureMirror.TextureSnapshot> snapshots,
      java.util.Set<String> blockers, String category,
      boolean preferGpuHandoff, boolean allowPackedFloatHandoff,
      Map<Integer, IrisGlTextureMirror.TextureSnapshot> retained,
      GraphReferenceResolver graphReferenceResolver) {
    if (generation <= 0) {
      blockers.add(category + "-generation-missing");
      return;
    }
    IrisGlTextureMirror.TextureSnapshot retainedSnapshot = retained == null
        ? null : retained.get(texture);
    IrisGlTextureMirror.TextureSnapshot snapshot = retainedSnapshot;
    if (snapshot != null && (snapshot.generation() != generation
        || snapshot.mipLevel() != mipLevel || snapshot.layer() != layer)) {
      blockers.add(category + "-lifecycle-changed-during-frame");
      return;
    }
    IrisGlTextureMirror.TextureMetadata metadata = mirror.metadata(
        texture, mipLevel, layer).orElse(null);
    if (snapshot == null && graphReferenceResolver != null
        && metadata != null) {
      snapshot = graphReferenceResolver.resolve(texture, generation,
          mipLevel, layer, metadata).orElse(null);
      if (snapshot != null && (!snapshot.graphReference()
          || snapshot.texture() != texture
          || snapshot.generation() != generation
          || snapshot.mipLevel() != mipLevel
          || snapshot.layer() != layer)) {
        blockers.add(category + "-graph-reference-invalid");
        return;
      }
    }
    if (snapshot == null) {
      snapshot = mirror.snapshot(texture, generation, mipLevel, layer)
          .orElse(null);
    }
    if (snapshot == null && preferGpuHandoff) {
      snapshot = IrisGlTextureGpuHandoff.lookupResident(texture, generation,
          mipLevel, layer, metadata).orElse(null);
    }
    if (snapshot != null && !snapshot.graphReference()
        && preferGpuHandoff) {
      boolean smallResident = preferSmallResidentTexture(metadata);
      if (!snapshot.shared() && smallResident) {
        IrisTranslationCoordinator.dumpExactTextureArtifact(snapshot);
      }
      if (!snapshot.shared()) {
        IrisGlTextureMirror.TextureSnapshot inlineSnapshot = snapshot;
        java.util.Optional<IrisGlTextureMirror.TextureSnapshot> resident =
            IrisGlTextureGpuHandoff.promoteResident(inlineSnapshot);
        if (resident.isPresent()) {
          snapshot = resident.orElseThrow();
          // A retained inline snapshot is the immutable draw-time authority.
          // The resident upload contains those exact bytes, so replacing only
          // that representation is safe. Do this before the final
          // putIfAbsent; otherwise it restores the old multi-megabyte CPU
          // payload forever.
          upgradeRetainedResident(retained, texture, retainedSnapshot,
              inlineSnapshot, snapshot);
        }
      }
      if (!snapshot.shared() && !smallResident) {
        // The immutable resident table is deliberately bounded. If it fills
        // before a large texture is encountered, keeping the mirror snapshot
        // would silently turn that texture into a multi-megabyte CPU upload
        // every frame. Fall through to the frame-slotted IOSurface handoff so
        // large supported inputs remain GPU-to-GPU regardless of discovery
        // order or warm-cache startup state.
        java.util.Optional<IrisGlTextureMirror.TextureSnapshot> gpuCapture =
            IrisGlTextureGpuHandoff.capture(texture, generation,
                target, mipLevel, layer, mirror, allowPackedFloatHandoff);
        if (gpuCapture.isPresent()) {
          snapshot = gpuCapture.orElseThrow();
        } else if (IrisGlTextureGpuHandoff.supportsDirectGpuHandoff(
            snapshot.format(), allowPackedFloatHandoff)) {
          String failure = IrisGlTextureGpuHandoff.lastCaptureFailure();
          String residentFailure =
              IrisGlTextureGpuHandoff.lastResidentFailure();
          if (System.getProperty(
                  "metalrender.exactJar.expectedPath") != null) {
            String key = texture + ":" + generation + ":" + residentFailure
                + ":" + failure;
            if (GPU_HANDOFF_DIAGNOSTICS.size() < MAX_READBACK_DIAGNOSTICS
                && GPU_HANDOFF_DIAGNOSTICS.add(key)) {
              MetalLogger.info(
                  "exact-JAR large GPU handoff unavailable: texture=%d generation=%d target=0x%s metadata=%s residentFailure=%s captureFailure=%s",
                  texture, generation, Integer.toHexString(target),
                  snapshot.format() + "/" + snapshot.width() + "x"
                      + snapshot.height(), residentFailure, failure);
            }
          }
          if (retained != null
              && IrisGlTextureGpuHandoff.retryableCaptureFailure(failure)) {
            blockers.add(category + "-gpu-handoff-backpressure");
            return;
          }
        }
      }
    }
    // Small immutable inputs are cheaper and more reliable as one-time,
    // generation-keyed resident uploads. Large framebuffer attachments stay
    // on the IOSurface path.
    if (snapshot == null && preferGpuHandoff
        && preferSmallResidentTexture(metadata)) {
      snapshot = IrisGlTextureReadback.capture(texture, generation, target,
          mipLevel, layer, mirror).orElse(null);
      if (snapshot != null) {
        IrisTranslationCoordinator.dumpExactTextureArtifact(snapshot);
        snapshot = IrisGlTextureGpuHandoff.promoteResident(snapshot)
            .orElse(snapshot);
      } else if (System.getProperty(
          "metalrender.exactJar.expectedPath") != null) {
        MetalLogger.info(
            "exact-JAR small resident readback unavailable: texture=%d generation=%d target=0x%s metadata=%s failure=%s",
            texture, generation, Integer.toHexString(target),
            metadata == null ? "missing" : metadata.format() + "/"
                + metadata.width() + "x" + metadata.height(),
            IrisGlTextureReadback.lastFailure());
      }
    }
    if (snapshot == null && preferGpuHandoff) {
      java.util.Optional<IrisGlTextureMirror.TextureSnapshot> gpuCapture =
          IrisGlTextureGpuHandoff.capture(texture, generation, target,
              mipLevel, layer, mirror, allowPackedFloatHandoff);
      snapshot = gpuCapture.orElse(null);
      if (snapshot == null && metadata != null
          && IrisGlTextureGpuHandoff.supportsDirectGpuHandoff(
              metadata.format(), allowPackedFloatHandoff)) {
        String failure = IrisGlTextureGpuHandoff.lastCaptureFailure();
        if (System.getProperty(
                "metalrender.exactJar.expectedPath") != null) {
          String key = "initial:" + texture + ":" + generation + ":"
              + failure;
          if (GPU_HANDOFF_DIAGNOSTICS.size() < MAX_READBACK_DIAGNOSTICS
              && GPU_HANDOFF_DIAGNOSTICS.add(key)) {
            MetalLogger.info(
                "exact-JAR initial GPU handoff unavailable: texture=%d generation=%d target=0x%s metadata=%s captureFailure=%s",
                texture, generation, Integer.toHexString(target),
                metadata.format() + "/" + metadata.width() + "x"
                    + metadata.height(), failure);
          }
        }
        if (retained != null
            && IrisGlTextureGpuHandoff.retryableCaptureFailure(failure)) {
          blockers.add(category + "-gpu-handoff-backpressure");
          return;
        }
      }
    }
    if (snapshot == null) {
      snapshot = IrisGlTextureReadback.capture(texture, generation, target,
          mipLevel, layer, mirror).orElse(null);
      if (snapshot != null && preferGpuHandoff
          && !snapshot.graphReference() && !snapshot.shared()) {
        snapshot = IrisGlTextureGpuHandoff.promoteResident(snapshot)
            .orElse(snapshot);
      }
    }
    if (snapshot == null) {
      String failure = IrisGlTextureReadback.lastFailure();
      String diagnosticKey = texture + ":" + generation + ":" + failure;
      if (READBACK_DIAGNOSTICS.size() < MAX_READBACK_DIAGNOSTICS
          && READBACK_DIAGNOSTICS.add(diagnosticKey)) {
        MetalLogger.warn(
            "Iris texture readback unavailable: texture=%d generation=%d target=0x%s mip=%d layer=%d gpuPreferred=%s metadata=%s failure=%s",
            texture, generation, Integer.toHexString(target), mipLevel,
            layer, preferGpuHandoff, metadata == null ? "missing"
                : metadata.format() + "/" + metadata.width() + "x"
                    + metadata.height() + "/layers"
                    + metadata.depthOrLayers() + "/bpp"
                    + metadata.bytesPerPixel() + "/g"
                    + metadata.generation(), failure);
      }
      blockers.add(category + "-snapshot-unavailable");
      return;
    }
    if (retained != null) {
      retained.putIfAbsent(texture, snapshot);
      snapshot = retained.get(texture);
    }
    snapshots.put(texture, snapshot);
  }

  static boolean preferSmallResidentTexture(
      IrisGlTextureMirror.TextureMetadata metadata) {
    return metadata != null && metadata.depthOrLayers() == 1
        && metadata.format().equals("rgba8-unorm")
        && metadata.width() <= 64 && metadata.height() <= 64
        && (long) metadata.width() * metadata.height()
            <= MAX_SMALL_RESIDENT_TEXTURE_PIXELS;
  }

  static boolean upgradeRetainedResident(
      Map<Integer, IrisGlTextureMirror.TextureSnapshot> retained,
      int texture,
      IrisGlTextureMirror.TextureSnapshot retainedSnapshot,
      IrisGlTextureMirror.TextureSnapshot inlineSnapshot,
      IrisGlTextureMirror.TextureSnapshot residentSnapshot) {
    if (retained == null || retainedSnapshot == null
        || retained.get(texture) != retainedSnapshot
        || retainedSnapshot != inlineSnapshot
        || inlineSnapshot.shared() || inlineSnapshot.graphReference()
        || inlineSnapshot.byteLength() <= 0
        || !residentSnapshot.shared() || residentSnapshot.graphReference()
        || residentSnapshot.byteLength() != 0
        || inlineSnapshot.texture() != texture
        || residentSnapshot.texture() != texture
        || inlineSnapshot.generation() != residentSnapshot.generation()
        || inlineSnapshot.width() != residentSnapshot.width()
        || inlineSnapshot.height() != residentSnapshot.height()
        || inlineSnapshot.layer() != residentSnapshot.layer()
        || inlineSnapshot.mipLevel() != residentSnapshot.mipLevel()
        || inlineSnapshot.bytesPerPixel()
            != residentSnapshot.bytesPerPixel()
        || !inlineSnapshot.format().equals(residentSnapshot.format())) {
      return false;
    }
    retained.put(texture, residentSnapshot);
    return true;
  }

  public boolean complete() {
    return captureEnabled && blockers.isEmpty();
  }

  public long totalBytes() {
    return textures.values().stream()
        .mapToLong(IrisGlTextureMirror.TextureSnapshot::byteLength).sum();
  }

  static java.util.Set<Integer> sampledUnits(
      IrisGlResourceBindingSnapshot resources) {
    java.util.TreeSet<Integer> units = new java.util.TreeSet<>();
    if (resources.uniformValues().isEmpty()) {
      units.addAll(resources.textureUnits().keySet());
      return units;
    }
    resources.uniformValues().values().forEach(value -> {
      if (value.columns() != 1 || value.rows() != 1
          || value.kind()
              == IrisGlResourceBindingSnapshot.UniformValueKind.FLOAT) {
        return;
      }
      long raw = value.rawBits()[0];
      if (raw >= 0 && raw < IrisShadowReplaySamplerSnapshot.MAX_UNITS
          && resources.textureUnits().containsKey((int) raw)) {
        units.add((int) raw);
      }
    });
    return units;
  }

  private record TextureReference(int texture, long generation, int target,
                                  int mipLevel, int layer, String category) {
  }

  @FunctionalInterface
  interface GraphReferenceResolver {
    java.util.Optional<IrisGlTextureMirror.TextureSnapshot> resolve(
        int texture, long generation, int mipLevel, int layer,
        IrisGlTextureMirror.TextureMetadata metadata);
  }
}

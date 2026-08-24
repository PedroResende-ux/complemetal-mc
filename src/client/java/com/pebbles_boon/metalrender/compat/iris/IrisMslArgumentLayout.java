package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Exact SPIRV-Cross assignment of resources to MSL argument-buffer ids. */
public record IrisMslArgumentLayout(List<StageLayout> stages) {
  private static final byte[] KEY_DOMAIN =
      "metalrender.iris.msl-argument-layout.v1"
          .getBytes(StandardCharsets.US_ASCII);
  public static final int MAX_ARGUMENT_ID = 65_535;
  public static final int MAX_ARGUMENT_BUFFER_INDEX = 30;

  public IrisMslArgumentLayout {
    Objects.requireNonNull(stages, "stages");
    if (stages.isEmpty()) {
      throw new IllegalArgumentException("MSL argument layout needs a stage");
    }
    ArrayList<StageLayout> copied = new ArrayList<>(stages.size());
    EnumSet<IrisShaderStage> unique =
        EnumSet.noneOf(IrisShaderStage.class);
    for (StageLayout stage : stages) {
      StageLayout checked = Objects.requireNonNull(stage, "stage layout");
      if (!unique.add(checked.stage())) {
        throw new IllegalArgumentException("duplicate MSL argument stage");
      }
      copied.add(checked);
    }
    copied.sort(Comparator.comparing(StageLayout::stage));
    stages = List.copyOf(copied);
  }

  public int resourceCount() {
    return stages.stream().mapToInt(stage -> stage.bindings().size()).sum();
  }

  /** Proves that automatic MSL ids cover the exact semantic SPIR-V layout. */
  public boolean semanticallyMatches(IrisProgramResourceLayout semantic) {
    Objects.requireNonNull(semantic, "semantic");
    if (stages.size() != semantic.stages().size()) {
      return false;
    }
    for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
      StageLayout msl = stages.get(stageIndex);
      IrisProgramResourceLayout.StageLayout spirv =
          semantic.stages().get(stageIndex);
      if (msl.stage() != spirv.stage()
          || msl.bindings().size() != spirv.layout().resources().size()) {
        return false;
      }
      for (IrisSpirvResourceLayout.ResourceBinding resource
          : spirv.layout().resources()) {
        boolean matched = msl.bindings().stream().anyMatch(binding ->
            binding.address().equals(resource.address())
                && binding.kind() == resource.kind());
        if (!matched) {
          return false;
        }
      }
    }
    return true;
  }

  public String sha256() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeInt(stages.size());
      for (StageLayout stage : stages) {
        putString(output, stage.stage().cacheName());
        output.writeInt(stage.bindings().size());
        for (ArgumentBinding binding : stage.bindings()) {
          putString(output, binding.address().kind().cacheName());
          output.writeInt(binding.address().descriptorSet());
          output.writeInt(binding.address().index());
          putString(output, binding.kind().cacheName());
          output.writeInt(binding.argumentBufferIndex());
          output.writeInt(binding.primaryId());
          output.writeInt(binding.secondaryId());
        }
      }
      output.flush();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(KEY_DOMAIN);
      digest.update(bytes.toByteArray());
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("cannot hash MSL argument layout",
          impossible);
    }
  }

  private static void putString(DataOutputStream output, String value)
      throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  public record StageLayout(IrisShaderStage stage,
                            List<ArgumentBinding> bindings) {
    public StageLayout {
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(bindings, "bindings");
      ArrayList<ArgumentBinding> copied = new ArrayList<>(bindings);
      copied.sort(Comparator
          .comparingInt(ArgumentBinding::argumentBufferIndex)
          .thenComparingInt(ArgumentBinding::primaryId)
          .thenComparing(binding -> binding.kind().cacheName()));
      HashSet<ResourceAddress> addresses = new HashSet<>();
      HashSet<Long> occupiedIds = new HashSet<>();
      for (ArgumentBinding binding : copied) {
        if (!addresses.add(binding.address())) {
          throw new IllegalArgumentException(
              "duplicate semantic MSL resource address");
        }
        requireFree(occupiedIds, binding.argumentBufferIndex(),
            binding.primaryId());
        if (binding.secondaryId() >= 0) {
          requireFree(occupiedIds, binding.argumentBufferIndex(),
              binding.secondaryId());
        }
      }
      bindings = List.copyOf(copied);
    }

    private static void requireFree(HashSet<Long> occupied,
        int argumentBuffer, int id) {
      long key = ((long) argumentBuffer << 32)
          | Integer.toUnsignedLong(id);
      if (!occupied.add(key)) {
        throw new IllegalArgumentException("duplicate MSL argument id");
      }
    }
  }

  public record ArgumentBinding(ResourceAddress address, ResourceKind kind,
                                int argumentBufferIndex, int primaryId,
                                int secondaryId) {
    public ArgumentBinding {
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(kind, "kind");
      if (argumentBufferIndex < 0
          || argumentBufferIndex > MAX_ARGUMENT_BUFFER_INDEX
          || primaryId < 0 || primaryId > MAX_ARGUMENT_ID
          || secondaryId < -1 || secondaryId > MAX_ARGUMENT_ID
          || secondaryId == primaryId) {
        throw new IllegalArgumentException("invalid MSL argument binding");
      }
      if (kind != ResourceKind.SAMPLED_IMAGE && secondaryId >= 0) {
        throw new IllegalArgumentException(
            "only combined sampled images may have a secondary id");
      }
    }
  }
}

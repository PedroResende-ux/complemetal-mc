package com.pebbles_boon.metalrender.compat.iris;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory translated artifacts. Original GLSL is intentionally absent.
 */
public final class IrisShaderTranslation {
  private final IrisShaderCacheKey key;
  private final String backendId;
  private final IrisTranslationProfile profile;
  private final EnumMap<IrisShaderStage, StageArtifacts> stages;

  public IrisShaderTranslation(IrisShaderCacheKey key, String backendId,
      IrisTranslationProfile profile,
      Map<IrisShaderStage, StageArtifacts> stages) {
    this.key = Objects.requireNonNull(key, "key");
    this.backendId = validateBackendId(backendId);
    this.profile = Objects.requireNonNull(profile, "profile");
    this.stages = new EnumMap<>(IrisShaderStage.class);
    this.stages.putAll(Objects.requireNonNull(stages, "stages"));
  }

  public IrisShaderCacheKey key() {
    return key;
  }

  public String backendId() {
    return backendId;
  }

  public IrisTranslationProfile profile() {
    return profile;
  }

  public Map<IrisShaderStage, StageArtifacts> stages() {
    return Collections.unmodifiableMap(stages);
  }

  public StageArtifacts stage(IrisShaderStage stage) {
    return stages.get(Objects.requireNonNull(stage, "stage"));
  }

  private static String validateBackendId(String id) {
    Objects.requireNonNull(id, "backendId");
    if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("backendId is not cache-safe");
    }
    return id;
  }

  public static final class StageArtifacts {
    private final byte[] spirv;
    private final String msl;

    public StageArtifacts(byte[] spirv, String msl) {
      this.spirv = Objects.requireNonNull(spirv, "spirv").clone();
      this.msl = Objects.requireNonNull(msl, "msl");
      if (this.spirv.length == 0 || this.spirv.length % Integer.BYTES != 0) {
        throw new IllegalArgumentException(
            "SPIR-V must be non-empty and 32-bit word aligned");
      }
    }

    public byte[] spirv() {
      return spirv.clone();
    }

    public String msl() {
      return msl;
    }
  }
}

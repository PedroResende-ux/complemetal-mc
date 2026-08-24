package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Device/compiler-specific identity for one fully described Metal pipeline. */
public record IrisMetalPipelineKey(String sha256) {
  private static final byte[] DOMAIN =
      "metalrender.iris.metal4-pipeline.v1"
          .getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ARGUMENT_LAYOUT_DOMAIN =
      "metalrender.iris.metal4-pipeline.v2"
          .getBytes(StandardCharsets.US_ASCII);

  public IrisMetalPipelineKey {
    requireSha256(sha256, "sha256");
  }

  public static IrisMetalPipelineKey from(String deviceCompilerSha256,
      IrisShaderCacheKey shaderKey, IrisPipelineStateKey stateKey,
      IrisProgramResourceLayoutKey resourceLayoutKey,
      IrisTranslationProfile profile) {
    requireSha256(deviceCompilerSha256, "deviceCompilerSha256");
    Objects.requireNonNull(shaderKey, "shaderKey");
    Objects.requireNonNull(stateKey, "stateKey");
    Objects.requireNonNull(resourceLayoutKey, "resourceLayoutKey");
    Objects.requireNonNull(profile, "profile");
    MessageDigest digest = newSha256();
    put(digest, DOMAIN);
    put(digest, deviceCompilerSha256);
    put(digest, shaderKey.sha256());
    put(digest, stateKey.sha256());
    put(digest, resourceLayoutKey.sha256());
    put(digest, profile.sha256());
    return new IrisMetalPipelineKey(
        HexFormat.of().formatHex(digest.digest()));
  }

  public static IrisMetalPipelineKey from(String deviceCompilerSha256,
      IrisShaderCacheKey shaderKey, IrisPipelineStateKey stateKey,
      IrisProgramResourceLayoutKey resourceLayoutKey,
      IrisMslArgumentLayout argumentLayout,
      IrisTranslationProfile profile) {
    requireSha256(deviceCompilerSha256, "deviceCompilerSha256");
    Objects.requireNonNull(shaderKey, "shaderKey");
    Objects.requireNonNull(stateKey, "stateKey");
    Objects.requireNonNull(resourceLayoutKey, "resourceLayoutKey");
    Objects.requireNonNull(argumentLayout, "argumentLayout");
    Objects.requireNonNull(profile, "profile");
    MessageDigest digest = newSha256();
    put(digest, ARGUMENT_LAYOUT_DOMAIN);
    put(digest, deviceCompilerSha256);
    put(digest, shaderKey.sha256());
    put(digest, stateKey.sha256());
    put(digest, resourceLayoutKey.sha256());
    put(digest, argumentLayout.sha256());
    put(digest, profile.sha256());
    return new IrisMetalPipelineKey(
        HexFormat.of().formatHex(digest.digest()));
  }

  static String sha256(String value) {
    MessageDigest digest = newSha256();
    put(digest, Objects.requireNonNull(value, "value"));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void put(MessageDigest digest, String value) {
    put(digest, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void put(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value.length)
        .array());
    digest.update(value);
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireSha256(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be SHA-256");
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Content-addressed key for an Iris final shader program.
 *
 * <p>The digest uses length-prefixed UTF-8 fields, so null, empty, and
 * concatenated values cannot alias. Program name and all six stage slots are
 * included in a fixed order.</p>
 */
public record IrisShaderCacheKey(String sha256) {
  private static final byte[] DOMAIN =
      "metalrender.iris.final-glsl.v1".getBytes(StandardCharsets.US_ASCII);

  public IrisShaderCacheKey {
    Objects.requireNonNull(sha256, "sha256");
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "sha256 must be 64 lowercase hexadecimal characters");
    }
  }

  public static IrisShaderCacheKey from(IrisFinalShaderProgram program) {
    return from(program,
        IrisTranslationProfile.LWJGL_3_4_1_METAL_3_ARGUMENT_BUFFERS);
  }

  public static IrisShaderCacheKey from(IrisFinalShaderProgram program,
      IrisTranslationProfile profile) {
    Objects.requireNonNull(program, "program");
    Objects.requireNonNull(profile, "profile");
    MessageDigest digest = newSha256();
    putBytes(digest, DOMAIN);
    putString(digest, profile.canonicalValue());
    putString(digest, program.programName());
    for (IrisShaderStage stage : IrisShaderStage.values()) {
      putString(digest, stage.cacheName());
      putNullableString(digest, program.source(stage));
    }
    return new IrisShaderCacheKey(HexFormat.of().formatHex(digest.digest()));
  }

  static String sha256(String value) {
    MessageDigest digest = newSha256();
    putString(digest, Objects.requireNonNull(value, "value"));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }

  private static void putNullableString(MessageDigest digest, String value) {
    if (value == null) {
      digest.update((byte) 0);
      return;
    }
    digest.update((byte) 1);
    putString(digest, value);
  }

  private static void putString(MessageDigest digest, String value) {
    putBytes(digest, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void putBytes(MessageDigest digest, byte[] bytes) {
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
    digest.update(bytes);
  }
}

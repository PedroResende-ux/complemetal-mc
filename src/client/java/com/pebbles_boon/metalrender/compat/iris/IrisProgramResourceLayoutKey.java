package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Content identity of the ordered per-stage Metal argument layouts. */
public record IrisProgramResourceLayoutKey(String sha256) {
  private static final byte[] DOMAIN =
      "metalrender.iris.program-resource-layout.v1"
          .getBytes(StandardCharsets.US_ASCII);

  public IrisProgramResourceLayoutKey {
    Objects.requireNonNull(sha256, "sha256");
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("invalid resource-layout digest");
    }
  }

  public static IrisProgramResourceLayoutKey from(
      IrisProgramResourceLayout layout) {
    Objects.requireNonNull(layout, "layout");
    MessageDigest digest = newSha256();
    put(digest, DOMAIN);
    digest.update(ByteBuffer.allocate(Integer.BYTES)
        .putInt(layout.stages().size()).array());
    for (IrisProgramResourceLayout.StageLayout stage : layout.stages()) {
      put(digest, stage.stage().cacheName().getBytes(
          StandardCharsets.US_ASCII));
      put(digest, stage.layout().key().sha256().getBytes(
          StandardCharsets.US_ASCII));
    }
    return new IrisProgramResourceLayoutKey(
        HexFormat.of().formatHex(digest.digest()));
  }

  private static void put(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Long.BYTES)
        .putLong(value.length).array());
    digest.update(value);
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}

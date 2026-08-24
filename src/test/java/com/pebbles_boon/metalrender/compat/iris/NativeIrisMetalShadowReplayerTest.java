package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

final class NativeIrisMetalShadowReplayerTest {
  private static final String KEY = "a".repeat(64);

  @Test
  void acceptsOnlyExactSuccessfulNativeResult() {
    byte[] rgba8 = new byte[32 * 24 * 4];
    rgba8[7] = 42;
    NativeIrisMetalShadowReplayer replayer =
        new NativeIrisMetalShadowReplayer(
            (key, packet) -> new long[] {1, -7, 32, 24},
            () -> rgba8);

    NativeIrisMetalShadowReplayer.Result result =
        replayer.replay(KEY, new byte[] {1}, 32, 24);

    assertEquals(NativeIrisMetalShadowReplayer.Outcome.SUCCEEDED,
        result.outcome());
    assertEquals(-7, result.colorHash());
    assertEquals(32, result.width());
    assertEquals(24, result.height());
    assertArrayEquals(rgba8, result.rgba8());
  }

  @Test
  void mapsUnsupportedAndMalformedResultsWithoutThrowing() {
    NativeIrisMetalShadowReplayer unsupported =
        new NativeIrisMetalShadowReplayer(
            (key, packet) -> new long[] {0, 0, 32, 24, 3});
    NativeIrisMetalShadowReplayer malformed =
        new NativeIrisMetalShadowReplayer(
            (key, packet) -> new long[] {1, 0, 31, 24});

    assertEquals(NativeIrisMetalShadowReplayer.Outcome.UNSUPPORTED,
        unsupported.replay(KEY, new byte[] {1}, 32, 24).outcome());
    assertEquals("native-texture-format-unsupported",
        unsupported.replay(KEY, new byte[] {1}, 32, 24).reason());
    assertEquals("invalid-native-success-result",
        malformed.replay(KEY, new byte[] {1}, 32, 24).reason());
  }

  @Test
  void rejectsUnknownNativeUnsupportedReasonCode() {
    NativeIrisMetalShadowReplayer replayer =
        new NativeIrisMetalShadowReplayer(
            (key, packet) -> new long[] {0, 0, 32, 24, 99});

    assertEquals("invalid-native-unsupported-result",
        replayer.replay(KEY, new byte[] {1}, 32, 24).reason());
  }

  @Test
  void containsLinkageFailuresAndRejectsInvalidRequests() {
    NativeIrisMetalShadowReplayer replayer =
        new NativeIrisMetalShadowReplayer((key, packet) -> {
          throw new UnsatisfiedLinkError("missing");
        });

    assertEquals("native-replay-exception",
        replayer.replay(KEY, new byte[] {1}, 1, 1).reason());
    assertThrows(IllegalArgumentException.class,
        () -> replayer.replay(KEY, new byte[0], 1, 1));
  }

  @Test
  void rejectsMalformedNativeRgba8Readback() {
    NativeIrisMetalShadowReplayer replayer =
        new NativeIrisMetalShadowReplayer(
            (key, packet) -> new long[] {1, 7, 2, 2, 0},
            () -> new byte[15]);

    assertEquals(NativeIrisMetalShadowReplayer.Outcome.FAILED,
        replayer.replay(KEY, new byte[] {1}, 2, 2).outcome());
    assertEquals("invalid-native-rgba8-result",
        replayer.replay(KEY, new byte[] {1}, 2, 2).reason());
  }

  @Test
  void validatesDirectPresentationCompletionWithoutCpuPixels() {
    NativeIrisMetalShadowReplayer replayer =
        new NativeIrisMetalShadowReplayer(
            (key, packet) -> new long[] {1, 7, 32, 24, 0},
            (key, packet) -> new long[] {1, 19, 32, 24, 0},
            () -> new byte[0]);

    NativeIrisMetalShadowReplayer.DirectResult result =
        replayer.replayForPresentation(KEY, new byte[] {1}, 32, 24);

    assertEquals(NativeIrisMetalShadowReplayer.Outcome.SUCCEEDED,
        result.outcome());
    assertEquals(19, result.completionToken());
    assertEquals(32, result.width());
    assertEquals(24, result.height());
  }

  @Test
  void rejectsMalformedDirectPresentationCompletion() {
    NativeIrisMetalShadowReplayer replayer =
        new NativeIrisMetalShadowReplayer(
            (key, packet) -> new long[] {1, 7, 32, 24, 0},
            (key, packet) -> new long[] {1, 0, 32, 24, 0},
            () -> new byte[0]);

    assertEquals("invalid-native-direct-success-result",
        replayer.replayForPresentation(
            KEY, new byte[] {1}, 32, 24).reason());
  }
}

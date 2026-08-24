package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.util.Objects;

/** Strict fail-open wrapper around one native offscreen Iris draw replay. */
final class NativeIrisMetalShadowReplayer {
  private final NativeCall nativeCall;
  private final DirectNativeCall directNativeCall;
  private final PixelCall pixelCall;

  NativeIrisMetalShadowReplayer() {
    this(NativeBridge::nRunIrisMetal4ShadowReplay,
        NativeBridge::nRunIrisMetal4FinalCutoverReplay,
        NativeBridge::nTakeIrisMetal4ShadowReplayRgba8);
  }

  NativeIrisMetalShadowReplayer(NativeCall nativeCall) {
    this(nativeCall, nativeCall::run, () -> new byte[0]);
  }

  NativeIrisMetalShadowReplayer(NativeCall nativeCall, PixelCall pixelCall) {
    this(nativeCall, nativeCall::run, pixelCall);
  }

  NativeIrisMetalShadowReplayer(NativeCall nativeCall,
      DirectNativeCall directNativeCall, PixelCall pixelCall) {
    this.nativeCall = Objects.requireNonNull(nativeCall, "nativeCall");
    this.directNativeCall = Objects.requireNonNull(directNativeCall,
        "directNativeCall");
    this.pixelCall = Objects.requireNonNull(pixelCall, "pixelCall");
  }

  static boolean isOptedIn() {
    return IrisGlBufferMirror.isEnabled();
  }

  Result replay(String pipelineKey, byte[] packet,
      int expectedWidth, int expectedHeight) {
    validateRequest(pipelineKey, packet, expectedWidth, expectedHeight);
    try {
      long[] nativeResult = nativeCall.run(pipelineKey, packet);
      if (nativeResult == null
          || (nativeResult.length != 4 && nativeResult.length != 5)) {
        return Result.failed("invalid-native-result");
      }
      long status = nativeResult[0];
      long hash = nativeResult[1];
      long width = nativeResult[2];
      long height = nativeResult[3];
      long reasonCode = nativeResult.length == 5 ? nativeResult[4] : 0;
      if (status == 1) {
        if (hash == 0 || width != expectedWidth || height != expectedHeight
            || reasonCode != 0) {
          return Result.failed("invalid-native-success-result");
        }
        byte[] rgba8 = pixelCall.take();
        int required = Math.toIntExact(Math.multiplyExact(
            (long) expectedWidth * expectedHeight, 4L));
        if (rgba8 == null || (rgba8.length != 0
            && rgba8.length != required)) {
          return Result.failed("invalid-native-rgba8-result");
        }
        return new Result(Outcome.SUCCEEDED, hash,
            Math.toIntExact(width), Math.toIntExact(height), "", rgba8);
      }
      if (status == 0) {
        boolean dimensionsValid = width == 0 && height == 0
            || width == expectedWidth && height == expectedHeight;
        if (hash != 0 || !dimensionsValid) {
          return Result.failed("invalid-native-unsupported-result");
        }
        String reason = unsupportedReason(reasonCode);
        if (reason == null) {
          return Result.failed("invalid-native-unsupported-result");
        }
        return new Result(Outcome.UNSUPPORTED, 0,
            Math.toIntExact(width), Math.toIntExact(height),
            reason, new byte[0]);
      }
      if (status == -1 && hash == 0 && reasonCode == 0) {
        return Result.failed("native-replay-failed");
      }
      return Result.failed("invalid-native-result");
    } catch (RuntimeException | LinkageError error) {
      return Result.failed("native-replay-exception");
    }
  }

  DirectResult replayForPresentation(String pipelineKey, byte[] packet,
      int expectedWidth, int expectedHeight) {
    validateRequest(pipelineKey, packet, expectedWidth, expectedHeight);
    try {
      long[] nativeResult = directNativeCall.run(pipelineKey, packet);
      if (nativeResult == null
          || (nativeResult.length != 4 && nativeResult.length != 5)) {
        return DirectResult.failed("invalid-native-direct-result");
      }
      long status = nativeResult[0];
      long completionToken = nativeResult[1];
      long width = nativeResult[2];
      long height = nativeResult[3];
      long reasonCode = nativeResult.length == 5 ? nativeResult[4] : 0;
      if (status == 1) {
        if (completionToken == 0 || width != expectedWidth
            || height != expectedHeight || reasonCode != 0) {
          return DirectResult.failed("invalid-native-direct-success-result");
        }
        return new DirectResult(Outcome.SUCCEEDED, completionToken,
            Math.toIntExact(width), Math.toIntExact(height), "");
      }
      if (status == 0) {
        boolean dimensionsValid = width == 0 && height == 0
            || width == expectedWidth && height == expectedHeight;
        if (completionToken != 0 || !dimensionsValid) {
          return DirectResult.failed(
              "invalid-native-direct-unsupported-result");
        }
        String reason = unsupportedReason(reasonCode);
        if (reason == null) {
          return DirectResult.failed(
              "invalid-native-direct-unsupported-result");
        }
        return new DirectResult(Outcome.UNSUPPORTED, 0,
            Math.toIntExact(width), Math.toIntExact(height), reason);
      }
      if (status == -1 && completionToken == 0 && reasonCode == 0) {
        return DirectResult.failed("native-direct-replay-failed");
      }
      return DirectResult.failed("invalid-native-direct-result");
    } catch (RuntimeException | LinkageError error) {
      return DirectResult.failed("native-direct-replay-exception");
    }
  }

  private static void validateRequest(String pipelineKey, byte[] packet,
      int expectedWidth, int expectedHeight) {
    IrisRenderGraph.requireSha(pipelineKey, "pipelineKey");
    Objects.requireNonNull(packet, "packet");
    if (packet.length == 0
        || packet.length > IrisMetalShadowReplayPacketEncoder.MAX_PACKET_BYTES
        || expectedWidth <= 0
        || expectedWidth
            > IrisMetalShadowReplayPacketEncoder.MAX_TARGET_EXTENT
        || expectedHeight <= 0
        || expectedHeight
            > IrisMetalShadowReplayPacketEncoder.MAX_TARGET_EXTENT) {
      throw new IllegalArgumentException("invalid shadow replay request");
    }
  }

  private static String unsupportedReason(long reasonCode) {
    return switch ((int) reasonCode) {
      case 0 -> "native-replay-unsupported";
      case 1 -> "native-runtime-unavailable";
      case 2 -> "native-pipeline-state-unsupported";
      case 3 -> "native-texture-format-unsupported";
      case 4 -> "native-render-target-format-unsupported";
      case 5 -> "native-argument-setup-unsupported";
      case 6 -> "native-index-range-unsupported";
      case 7 -> "native-output-format-unsupported";
      case 8 -> "native-direct-presentation-format-unsupported";
      case 9 -> "native-buffer-handoff-unavailable";
      case 10 -> "native-submission-retirement-capacity";
      default -> null;
    };
  }

  enum Outcome {
    SUCCEEDED,
    UNSUPPORTED,
    FAILED
  }

  record Result(Outcome outcome, long colorHash, int width, int height,
                String reason, byte[] rgba8) {
    Result {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(rgba8, "rgba8");
      if (width < 0 || height < 0 || reason.length() > 96) {
        throw new IllegalArgumentException("invalid shadow replay result");
      }
      if (outcome == Outcome.SUCCEEDED
          && (colorHash == 0 || width == 0 || height == 0
              || !reason.isEmpty()
              || rgba8.length != 0
                  && rgba8.length != (long) width * height * 4)) {
        throw new IllegalArgumentException("invalid successful replay");
      }
      if (outcome != Outcome.SUCCEEDED
          && (colorHash != 0 || reason.isEmpty() || rgba8.length != 0)) {
        throw new IllegalArgumentException("invalid failed replay");
      }
    }

    static Result failed(String reason) {
      return new Result(Outcome.FAILED, 0, 0, 0, reason, new byte[0]);
    }
  }

  record DirectResult(Outcome outcome, long completionToken, int width,
                      int height, String reason) {
    DirectResult {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(reason, "reason");
      if (width < 0 || height < 0 || reason.length() > 96) {
        throw new IllegalArgumentException("invalid direct replay result");
      }
      if (outcome == Outcome.SUCCEEDED
          && (completionToken == 0 || width == 0 || height == 0
              || !reason.isEmpty())) {
        throw new IllegalArgumentException("invalid direct replay success");
      }
      if (outcome != Outcome.SUCCEEDED
          && (completionToken != 0 || reason.isEmpty())) {
        throw new IllegalArgumentException("invalid direct replay failure");
      }
    }

    static DirectResult failed(String reason) {
      return new DirectResult(Outcome.FAILED, 0, 0, 0, reason);
    }
  }

  @FunctionalInterface
  interface NativeCall {
    long[] run(String pipelineKey, byte[] packet);
  }

  @FunctionalInterface
  interface DirectNativeCall {
    long[] run(String pipelineKey, byte[] packet);
  }

  @FunctionalInterface
  interface PixelCall {
    byte[] take();
  }
}

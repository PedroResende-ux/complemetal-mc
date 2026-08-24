package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Fail-open wrapper for one complete MGF9 Metal 4 command buffer. */
final class NativeIrisMetalGraphExecutor {
  static final String ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalGraphExecution";
  static final String OWNERSHIP_PROPERTY =
      "metalrender.experimental.irisMetalGraphOwnership";
  private final NativeCall nativeCall;
  private final PixelCall pixelCall;
  private final PresentationSubmitCall presentationSubmitCall;
  private final DirectPresentationSubmitCall directPresentationSubmitCall;
  private final PresentationStatusCall presentationStatusCall;
  private final PresentationPromoteCall presentationPromoteCall;
  private final PresentationDiscardCall presentationDiscardCall;
  private final AtomicLong packetSamples = new AtomicLong();
  private final AtomicLong lastPacketBytes = new AtomicLong();
  private final AtomicLong totalPacketBytes = new AtomicLong();
  private final AtomicLong maximumPacketBytes = new AtomicLong();

  NativeIrisMetalGraphExecutor() {
    this(NativeBridge::nRunIrisMetal4GraphFrame,
        NativeBridge::nTakeIrisMetal4GraphFrameRgba8,
        NativeBridge::nSubmitIrisMetal4GraphFrame,
        NativeBridge::nSubmitIrisMetal4GraphFrameDirect,
        NativeBridge::nGetIrisMetal4GraphPresentationStatus,
        NativeBridge::nPromoteIrisMetal4GraphPresentation,
        NativeBridge::nDiscardIrisMetal4GraphPresentation);
  }

  NativeIrisMetalGraphExecutor(NativeCall nativeCall) {
    this(nativeCall, () -> new byte[0]);
  }

  NativeIrisMetalGraphExecutor(NativeCall nativeCall, PixelCall pixelCall) {
    this(nativeCall, pixelCall,
        packet -> new long[] {-1, 0, 0, 0, 0, 0, 0},
        token -> new long[] {-1, token, 0, 0, 1},
        (token, width, height) -> false, token -> false);
  }

  NativeIrisMetalGraphExecutor(NativeCall nativeCall, PixelCall pixelCall,
      PresentationSubmitCall presentationSubmitCall,
      PresentationStatusCall presentationStatusCall,
      PresentationPromoteCall presentationPromoteCall,
      PresentationDiscardCall presentationDiscardCall) {
    this(nativeCall, pixelCall, presentationSubmitCall, null,
        presentationStatusCall, presentationPromoteCall,
        presentationDiscardCall);
  }

  NativeIrisMetalGraphExecutor(NativeCall nativeCall, PixelCall pixelCall,
      PresentationSubmitCall presentationSubmitCall,
      DirectPresentationSubmitCall directPresentationSubmitCall,
      PresentationStatusCall presentationStatusCall,
      PresentationPromoteCall presentationPromoteCall,
      PresentationDiscardCall presentationDiscardCall) {
    this.nativeCall = Objects.requireNonNull(nativeCall, "nativeCall");
    this.pixelCall = Objects.requireNonNull(pixelCall, "pixelCall");
    this.presentationSubmitCall = Objects.requireNonNull(
        presentationSubmitCall, "presentationSubmitCall");
    this.directPresentationSubmitCall = directPresentationSubmitCall;
    this.presentationStatusCall = Objects.requireNonNull(
        presentationStatusCall, "presentationStatusCall");
    this.presentationPromoteCall = Objects.requireNonNull(
        presentationPromoteCall, "presentationPromoteCall");
    this.presentationDiscardCall = Objects.requireNonNull(
        presentationDiscardCall, "presentationDiscardCall");
  }

  static boolean isOptedIn() {
    return NativeIrisMetalGraphResources.isOptedIn()
        && NativeIrisMetalShadowReplayer.isOptedIn()
        && IrisMetalFeatureFlags.enabled(ENABLED_PROPERTY);
  }

  static boolean isOwnershipOptedIn() {
    return isOptedIn()
        && IrisMetalFeatureFlags.enabled(OWNERSHIP_PROPERTY);
  }

  Result execute(IrisMetalGraphFramePacketEncoder.Frame frame) {
    Objects.requireNonNull(frame, "frame");
    if (frame.presentationResourceId()
        != IrisMetalGraphFramePacketEncoder.NO_PRESENTATION) {
      return Result.failed("graph-native-mode-invalid");
    }
    byte[] packet = IrisMetalGraphFramePacketEncoder.encode(frame);
    recordPacketBytes(packet.length);
    try {
      int diagnosticReadbackMipLevel = Math.max(0, Integer.getInteger(
          "metalrender.exactJar.diagnosticGraphReadbackMipLevel", -1));
      long[] nativeResult = nativeCall.run(packet,
          diagnosticReadbackMipLevel);
      if (nativeResult == null || nativeResult.length != 7) {
        return Result.failed("graph-native-result-invalid");
      }
      long status = nativeResult[0];
      long steps = nativeResult[1];
      long clears = nativeResult[2];
      long transfers = nativeResult[3];
      long barriers = nativeResult[4];
      long hash = nativeResult[5];
      long reasonCode = nativeResult[6];
      if (status == 1) {
        if (steps != frame.operations().size() || clears < 0
            || transfers < 0 || barriers < 0 || reasonCode != 0
            || (frame.readbackResourceId()
                != IrisMetalGraphFramePacketEncoder.NO_READBACK && hash == 0)) {
          return Result.failed("graph-native-success-invalid");
        }
        byte[] rgba8 = pixelCall.take();
        if (rgba8 == null || rgba8.length
            > IrisVisualParityGate.MAX_PIXELS * 4L) {
          return Result.failed("graph-native-rgba8-invalid");
        }
        return new Result(Outcome.SUCCEEDED, steps, clears, transfers,
            barriers, hash, "", rgba8);
      }
      if (status == 0 && steps == 0 && clears == 0 && transfers == 0
          && barriers == 0 && hash == 0) {
        String reason = unsupportedReason(reasonCode);
        return reason == null ? Result.failed("graph-native-result-invalid")
            : new Result(Outcome.UNSUPPORTED, 0, 0, 0, 0, 0, reason,
                new byte[0]);
      }
      if (status == -1 && steps == 0 && clears == 0 && transfers == 0
          && barriers == 0 && hash == 0) {
        String reason = failureReason(reasonCode);
        return new Result(Outcome.FAILED, 0, 0, 0, 0, 0, reason,
            new byte[0]);
      }
      return Result.failed("graph-native-result-invalid");
    } catch (RuntimeException | LinkageError failure) {
      return Result.failed("graph-native-exception");
    }
  }

  PacketStats packetStats() {
    return new PacketStats(packetSamples.get(), lastPacketBytes.get(),
        totalPacketBytes.get(), maximumPacketBytes.get());
  }

  private void recordPacketBytes(long packetBytes) {
    lastPacketBytes.set(packetBytes);
    totalPacketBytes.addAndGet(packetBytes);
    long maximum = maximumPacketBytes.get();
    while (maximum < packetBytes
        && !maximumPacketBytes.compareAndSet(maximum, packetBytes)) {
      maximum = maximumPacketBytes.get();
    }
    packetSamples.incrementAndGet();
  }

  PresentationSubmission submitForPresentation(
      IrisMetalGraphFramePacketEncoder.Frame frame) {
    return submitForPresentation(frame,
        IrisMetalGraphFramePacketEncoder.encode(frame));
  }

  PresentationSubmission submitForPresentation(
      IrisMetalGraphFramePacketEncoder.Frame frame, byte[] packet) {
    Objects.requireNonNull(frame, "frame");
    Objects.requireNonNull(packet, "packet");
    if (frame.readbackResourceId()
            != IrisMetalGraphFramePacketEncoder.NO_READBACK
        || frame.presentationResourceId()
            == IrisMetalGraphFramePacketEncoder.NO_PRESENTATION) {
      return PresentationSubmission.failed("graph-presentation-mode-invalid");
    }
    if (packet.length <= 0
        || packet.length > IrisMetalGraphFramePacketEncoder.MAX_PACKET_BYTES) {
      return PresentationSubmission.failed("graph-presentation-packet-invalid");
    }
    recordPacketBytes(packet.length);
    try {
      return decodePresentationSubmission(frame,
          presentationSubmitCall.submit(packet));
    } catch (RuntimeException | LinkageError failure) {
      return PresentationSubmission.failed(
          "graph-presentation-native-exception");
    }
  }

  private static PresentationSubmission decodePresentationSubmission(
      IrisMetalGraphFramePacketEncoder.Frame frame, long[] nativeResult) {
    if (nativeResult == null || nativeResult.length != 7) {
      return PresentationSubmission.failed(
          "graph-presentation-native-result-invalid");
    }
    long status = nativeResult[0];
    long steps = nativeResult[1];
    long clears = nativeResult[2];
    long transfers = nativeResult[3];
    long barriers = nativeResult[4];
    long token = nativeResult[5];
    long reasonCode = nativeResult[6];
    if (status == 1 && steps == frame.operations().size() && steps > 0
        && clears >= 0 && transfers >= 0 && barriers >= 0 && token > 0
        && reasonCode == 0) {
      return new PresentationSubmission(Outcome.SUCCEEDED, token, steps,
          clears, transfers, barriers, "");
    }
    if (status == 0 && steps == 0 && clears == 0 && transfers == 0
        && barriers == 0 && token == 0) {
      String reason = unsupportedReason(reasonCode);
      return reason == null
          ? PresentationSubmission.failed(
              "graph-presentation-native-result-invalid")
          : new PresentationSubmission(Outcome.UNSUPPORTED, 0, 0, 0, 0,
              0, reason);
    }
    if (status == -1 && steps == 0 && clears == 0 && transfers == 0
        && barriers == 0 && token == 0) {
      return PresentationSubmission.failed(failureReason(reasonCode));
    }
    return PresentationSubmission.failed(
        "graph-presentation-native-result-invalid");
  }

  PresentationSubmission submitForPresentation(
      IrisMetalGraphFramePacketEncoder.Frame frame,
      IrisMetalGraphFramePacketEncoder.DirectPacket packet) {
    Objects.requireNonNull(frame, "frame");
    Objects.requireNonNull(packet, "packet");
    if (frame.readbackResourceId()
            != IrisMetalGraphFramePacketEncoder.NO_READBACK
        || frame.presentationResourceId()
            == IrisMetalGraphFramePacketEncoder.NO_PRESENTATION) {
      return PresentationSubmission.failed("graph-presentation-mode-invalid");
    }
    if (packet.length() <= 0
        || packet.length()
            > IrisMetalGraphFramePacketEncoder.MAX_PACKET_BYTES) {
      return PresentationSubmission.failed(
          "graph-presentation-packet-invalid");
    }
    recordPacketBytes(packet.length());
    try {
      long[] nativeResult = directPresentationSubmitCall == null
          ? presentationSubmitCall.submit(packet.copyBytes())
          : directPresentationSubmitCall.submit(packet.nativeBuffer(),
              packet.length());
      return decodePresentationSubmission(frame, nativeResult);
    } catch (RuntimeException | LinkageError failure) {
      return PresentationSubmission.failed(
          "graph-presentation-native-exception");
    }
  }

  PresentationStatus pollPresentation(long token) {
    if (token <= 0) {
      return PresentationStatus.failed(token,
          "graph-presentation-token-invalid");
    }
    try {
      long[] nativeResult = presentationStatusCall.status(token);
      if (nativeResult == null || nativeResult.length != 5
          || nativeResult[1] != token) {
        return PresentationStatus.failed(token,
            "graph-presentation-status-invalid");
      }
      if (nativeResult[0] == 0 && nativeResult[4] == 0) {
        if (!validPendingDimensions(nativeResult[2], nativeResult[3])) {
          return PresentationStatus.failed(token,
              "graph-presentation-status-invalid");
        }
        return new PresentationStatus(PresentationState.PENDING, token,
            (int) nativeResult[2], (int) nativeResult[3], "");
      }
      if (nativeResult[0] == 1 && nativeResult[4] == 0) {
        if (!validReadyDimensions(nativeResult[2], nativeResult[3])) {
          return PresentationStatus.failed(token,
              "graph-presentation-status-invalid");
        }
        return new PresentationStatus(PresentationState.READY, token,
            (int) nativeResult[2], (int) nativeResult[3], "");
      }
      return PresentationStatus.failed(token,
          "graph-presentation-native-failed-" + nativeResult[4]);
    } catch (RuntimeException | LinkageError failure) {
      return PresentationStatus.failed(token,
          "graph-presentation-status-exception");
    }
  }

  private static boolean validPendingDimensions(long width, long height) {
    return width >= 0 && width <= Integer.MAX_VALUE
        && height >= 0 && height <= Integer.MAX_VALUE
        && ((width == 0 && height == 0) || (width > 0 && height > 0));
  }

  private static boolean validReadyDimensions(long width, long height) {
    return width > 0 && width <= Integer.MAX_VALUE
        && height > 0 && height <= Integer.MAX_VALUE;
  }

  boolean promotePresentation(PresentationStatus status) {
    Objects.requireNonNull(status, "status");
    if (status.state() != PresentationState.READY) {
      return false;
    }
    try {
      return presentationPromoteCall.promote(status.token(), status.width(),
          status.height());
    } catch (RuntimeException | LinkageError failure) {
      return false;
    }
  }

  boolean discardPresentation(long token) {
    if (token <= 0) {
      return false;
    }
    try {
      return presentationDiscardCall.discard(token);
    } catch (RuntimeException | LinkageError failure) {
      return false;
    }
  }

  private static String unsupportedReason(long reasonCode) {
    return switch ((int) reasonCode) {
      case 1 -> "graph-native-runtime-unavailable";
      case 2 -> "graph-native-resource-generation-stale";
      case 3 -> "graph-native-operation-unsupported";
      case 6 -> "graph-native-draw-unsupported";
      case 7 -> "graph-native-submission-retirement-capacity";
      case 8 -> "graph-native-draw-pipeline-unavailable";
      case 9 -> "graph-native-draw-pipeline-state-mismatch";
      case 10 -> "graph-native-draw-color-slot-mismatch";
      case 11 -> "graph-native-draw-color-target-mismatch";
      case 12 -> "graph-native-draw-color-coverage-mismatch";
      case 13 -> "graph-native-draw-depth-stencil-presence-mismatch";
      case 14 -> "graph-native-draw-depth-target-mismatch";
      case 15 -> "graph-native-draw-stencil-target-mismatch";
      case 16 -> "graph-native-draw-depth-stencil-alias-mismatch";
      case 17 -> "graph-native-draw-buffer-residency-mismatch";
      case 18 -> "graph-native-draw-texture-override-mismatch";
      case 19 -> "graph-native-draw-shared-texture-mismatch";
      case 20 -> "graph-native-draw-shared-texture-fence-timeout";
      case 21 -> "graph-native-draw-texture-upload-unsupported";
      case 22 -> "graph-native-draw-unused-texture-override";
      case 23 -> "graph-native-draw-argument-binding-unsupported";
      case 24 -> "graph-native-draw-index-buffer-missing";
      case 25 -> "graph-native-draw-index-range-invalid";
      case 26 -> "graph-native-draw-texture-subresource-unsupported";
      case 27 -> "graph-native-draw-texture-format-unsupported";
      case 28 -> "graph-native-draw-texture-byte-budget-exceeded";
      case 29 -> "graph-native-readback-texture-missing";
      case 30 -> "graph-native-readback-multisample-unsupported";
      case 31 -> "graph-native-readback-format-unsupported";
      case 32 -> "graph-native-readback-row-size-unsupported";
      case 33 -> "graph-native-readback-byte-size-unsupported";
      case 34 -> "graph-native-clear-region-unsupported";
      case 35 -> "graph-native-clear-texture-missing";
      case 36 -> "graph-native-clear-color-format-unsupported";
      case 37 -> "graph-native-clear-depth-format-unsupported";
      case 38 -> "graph-native-clear-stencil-format-unsupported";
      case 39 -> "graph-native-copy-texture-missing";
      case 40 -> "graph-native-copy-format-mismatch";
      case 41 -> "graph-native-copy-multisample-unsupported";
      case 42 -> "graph-native-copy-mip-level-unsupported";
      case 43 -> "graph-native-copy-bounds-unsupported";
      case 44 -> "graph-native-mipmap-texture-missing";
      case 45 -> "graph-native-mipmap-levels-unavailable";
      case 46 -> "graph-native-mipmap-multisample-unsupported";
      case 47 -> "graph-native-presentation-texture-missing";
      case 48 -> "graph-native-presentation-multisample-unsupported";
      case 49 -> "graph-native-presentation-format-unsupported";
      case 50 -> "graph-native-presentation-size-unsupported";
      case 51 -> "graph-native-presentation-queue-full";
      case 52 -> "graph-native-frame-input-resident-missing";
      case 53 -> "graph-native-draw-external-buffer-index-mismatch";
      case 54 -> "graph-native-draw-external-buffer-missing";
      case 55 -> "graph-native-draw-external-buffer-length-mismatch";
      case 56 -> "graph-native-draw-external-texture-index-mismatch";
      case 57 -> "graph-native-draw-external-texture-missing";
      case 58 -> "graph-native-draw-external-texture-metadata-mismatch";
      default -> null;
    };
  }

  private static String failureReason(long reasonCode) {
    return switch ((int) reasonCode) {
      case 0 -> "graph-native-packet-rejected";
      case 4 -> "graph-native-gpu-completion-failed";
      case 5 -> "graph-native-setup-failed";
      default -> "graph-native-failed";
    };
  }

  enum Outcome {
    SUCCEEDED,
    UNSUPPORTED,
    FAILED
  }

  enum PresentationState {
    PENDING,
    READY,
    FAILED
  }

  record PresentationSubmission(Outcome outcome, long token, long steps,
                                long clears, long transfers, long barriers,
                                String reason) {
    PresentationSubmission {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(reason, "reason");
      if (token < 0 || steps < 0 || clears < 0 || transfers < 0
          || barriers < 0 || reason.length() > 128
          || (outcome == Outcome.SUCCEEDED
              && (token == 0 || steps == 0 || !reason.isEmpty()))
          || (outcome != Outcome.SUCCEEDED
              && (token != 0 || steps != 0 || clears != 0 || transfers != 0
                  || barriers != 0 || reason.isEmpty()))) {
        throw new IllegalArgumentException(
            "inconsistent graph presentation submission");
      }
    }

    static PresentationSubmission failed(String reason) {
      return new PresentationSubmission(Outcome.FAILED, 0, 0, 0, 0, 0,
          reason);
    }
  }

  record PacketStats(long samples, long lastBytes, long totalBytes,
                     long maximumBytes) {
    double lastMiB() {
      return lastBytes / 1_048_576.0;
    }

    double averageMiB() {
      return samples == 0 ? 0.0
          : totalBytes / (double) samples / 1_048_576.0;
    }

    double maximumMiB() {
      return maximumBytes / 1_048_576.0;
    }
  }

  record PresentationStatus(PresentationState state, long token, int width,
                            int height, String reason) {
    PresentationStatus {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(reason, "reason");
      boolean dimensionsAbsent = width == 0 && height == 0;
      boolean dimensionsPresent = width > 0 && height > 0;
      boolean invalidFailed = state == PresentationState.FAILED
          && (!dimensionsAbsent || reason.isEmpty());
      boolean invalidPending = state == PresentationState.PENDING
          && (token == 0 || (!dimensionsAbsent && !dimensionsPresent)
              || !reason.isEmpty());
      boolean invalidReady = state == PresentationState.READY
          && (token == 0 || !dimensionsPresent || !reason.isEmpty());
      if (token < 0 || width < 0 || height < 0 || reason.length() > 128
          || invalidFailed || invalidPending || invalidReady) {
        throw new IllegalArgumentException(
            "inconsistent graph presentation status");
      }
    }

    static PresentationStatus failed(long token, String reason) {
      return new PresentationStatus(PresentationState.FAILED,
          Math.max(0, token), 0, 0, reason);
    }
  }

  record Result(Outcome outcome, long steps, long clears, long transfers,
                long barriers, long outputHash, String reason,
                byte[] rgba8) {
    Result {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(reason, "reason");
      rgba8 = Objects.requireNonNull(rgba8, "rgba8").clone();
      if (steps < 0 || clears < 0 || transfers < 0 || barriers < 0
          || reason.length() > 128) {
        throw new IllegalArgumentException("invalid graph native result");
      }
      if ((outcome == Outcome.SUCCEEDED
          && (steps == 0 || !reason.isEmpty()))
          || (outcome != Outcome.SUCCEEDED
          && (steps != 0 || clears != 0 || transfers != 0 || barriers != 0
              || outputHash != 0 || reason.isEmpty()))) {
        throw new IllegalArgumentException("inconsistent graph result");
      }
      if (outcome != Outcome.SUCCEEDED && rgba8.length != 0) {
        throw new IllegalArgumentException("failed graph retained pixels");
      }
    }

    @Override
    public byte[] rgba8() {
      return rgba8.clone();
    }

    static Result failed(String reason) {
      return new Result(Outcome.FAILED, 0, 0, 0, 0, 0, reason,
          new byte[0]);
    }
  }

  @FunctionalInterface
  interface NativeCall {
    long[] run(byte[] packet, int diagnosticReadbackMipLevel);
  }

  @FunctionalInterface
  interface PixelCall {
    byte[] take();
  }

  @FunctionalInterface
  interface PresentationSubmitCall {
    long[] submit(byte[] packet);
  }

  @FunctionalInterface
  interface DirectPresentationSubmitCall {
    long[] submit(ByteBuffer packet, int length);
  }

  @FunctionalInterface
  interface PresentationStatusCall {
    long[] status(long token);
  }

  @FunctionalInterface
  interface PresentationPromoteCall {
    boolean promote(long token, int width, int height);
  }

  @FunctionalInterface
  interface PresentationDiscardCall {
    boolean discard(long token);
  }
}

package com.pebbles_boon.metalrender.compat.iris;

/**
 * Validation-only boundary for compiling generated MSL into an ephemeral
 * Metal library.
 *
 * <p>Implementations must not retain a library, create a pipeline, submit a
 * draw, or expose a native handle. {@link Result#DEFERRED} means the runtime
 * is not ready yet and the caller should keep the bounded job queued.</p>
 */
public interface IrisMslLibraryValidator {
  enum Readiness {
    READY,
    DEFERRED
  }

  enum Result {
    COMPILED,
    UNSUPPORTED,
    FAILED,
    DEFERRED
  }

  Readiness readiness();

  Result validate(byte[] mslUtf8, IrisShaderStage stage);

  /**
   * Diagnostic leak guard. A validation-only implementation should report
   * zero after every completed call.
   */
  long liveLibraryCount();

  static IrisMslLibraryValidator deferred() {
    return DeferredValidator.INSTANCE;
  }

  enum DeferredValidator implements IrisMslLibraryValidator {
    INSTANCE;

    @Override
    public Readiness readiness() {
      return Readiness.DEFERRED;
    }

    @Override
    public Result validate(byte[] mslUtf8, IrisShaderStage stage) {
      return Result.DEFERRED;
    }

    @Override
    public long liveLibraryCount() {
      return 0;
    }
  }
}

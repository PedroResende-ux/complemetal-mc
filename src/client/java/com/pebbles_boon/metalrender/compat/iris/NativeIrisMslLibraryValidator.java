package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.util.Objects;

/**
 * Native validation adapter. It deliberately refuses to enter JNI before the
 * packaged library and Metal device are ready.
 */
public final class NativeIrisMslLibraryValidator
    implements IrisMslLibraryValidator {
  static final int NATIVE_FAILED = NativeBridge.IRIS_MSL_COMPILE_FAILED;
  static final int NATIVE_UNSUPPORTED =
      NativeBridge.IRIS_MSL_COMPILE_UNSUPPORTED;
  static final int NATIVE_COMPILED =
      NativeBridge.IRIS_MSL_COMPILE_COMPILED;
  static final int NATIVE_DEFERRED =
      NativeBridge.IRIS_MSL_COMPILE_DEFERRED;

  private final NativeApi api;

  public NativeIrisMslLibraryValidator() {
    this(new NativeApi() {
      @Override
      public boolean loaded() {
        return NativeBridge.isLibLoaded();
      }

      @Override
      public boolean available() {
        return NativeBridge.nIsAvailable();
      }

      @Override
      public boolean compilerReady() {
        return NativeBridge.nIsIrisMslCompilerReady();
      }

      @Override
      public int validate(byte[] mslUtf8, int stageOrdinal) {
        return NativeBridge.nValidateIrisMslLibrary(mslUtf8, stageOrdinal);
      }

      @Override
      public long liveLibraryCount() {
        return NativeBridge.nGetIrisMslLiveLibraryCount();
      }
    });
  }

  NativeIrisMslLibraryValidator(NativeApi api) {
    this.api = Objects.requireNonNull(api, "api");
  }

  @Override
  public Readiness readiness() {
    try {
      if (!api.loaded()) {
        return Readiness.DEFERRED;
      }
      return api.compilerReady() && api.available()
          ? Readiness.READY : Readiness.DEFERRED;
    } catch (RuntimeException | LinkageError unavailable) {
      return Readiness.DEFERRED;
    }
  }

  @Override
  public Result validate(byte[] mslUtf8, IrisShaderStage stage) {
    Objects.requireNonNull(mslUtf8, "mslUtf8");
    Objects.requireNonNull(stage, "stage");
    if (mslUtf8.length == 0
        || mslUtf8.length
            > IrisPipelineCache.MAX_LIBRARY_VALIDATION_MSL_BYTES) {
      return Result.FAILED;
    }
    if (readiness() != Readiness.READY) {
      return Result.DEFERRED;
    }
    try {
      return switch (api.validate(mslUtf8, stage.ordinal())) {
        case NATIVE_COMPILED -> Result.COMPILED;
        case NATIVE_UNSUPPORTED -> Result.UNSUPPORTED;
        case NATIVE_FAILED -> Result.FAILED;
        case NATIVE_DEFERRED -> Result.DEFERRED;
        default -> Result.FAILED;
      };
    } catch (RuntimeException failure) {
      return Result.FAILED;
    } catch (LinkageError readinessRace) {
      return Result.DEFERRED;
    }
  }

  @Override
  public long liveLibraryCount() {
    try {
      if (!api.loaded()) {
        return 0;
      }
      long count = api.liveLibraryCount();
      return count < 0 ? -1 : count;
    } catch (RuntimeException | LinkageError unavailable) {
      return -1;
    }
  }

  interface NativeApi {
    boolean loaded();

    boolean available();

    boolean compilerReady();

    int validate(byte[] mslUtf8, int stageOrdinal);

    long liveLibraryCount();
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class NativeIrisMslLibraryValidatorTest {
  private static final byte[] MSL =
      "#include <metal_stdlib>\nvertex float4 main0(){}\n"
          .getBytes(StandardCharsets.UTF_8);

  @Test
  void defersWithoutEnteringNativeValidationUntilRuntimeIsReady() {
    FakeNativeApi api = new FakeNativeApi();
    NativeIrisMslLibraryValidator validator =
        new NativeIrisMslLibraryValidator(api);

    assertEquals(IrisMslLibraryValidator.Readiness.DEFERRED,
        validator.readiness());
    assertEquals(IrisMslLibraryValidator.Result.DEFERRED,
        validator.validate(MSL, IrisShaderStage.VERTEX));
    assertEquals(0, api.availableCalls.get());
    assertEquals(0, api.validationCalls.get());

    api.loaded = true;
    assertEquals(IrisMslLibraryValidator.Result.DEFERRED,
        validator.validate(MSL, IrisShaderStage.VERTEX));
    assertEquals(0, api.validationCalls.get());

    api.available = true;
    assertEquals(IrisMslLibraryValidator.Result.DEFERRED,
        validator.validate(MSL, IrisShaderStage.VERTEX));
    assertEquals(0, api.validationCalls.get(),
        "available Metal device is not enough before renderer nInit");

    api.compilerReady = true;
    api.result = NativeIrisMslLibraryValidator.NATIVE_COMPILED;
    assertEquals(IrisMslLibraryValidator.Result.COMPILED,
        validator.validate(MSL, IrisShaderStage.VERTEX));
    assertEquals(1, api.validationCalls.get());
    assertEquals(IrisShaderStage.VERTEX.ordinal(), api.lastStageOrdinal);
  }

  @Test
  void mapsExplicitNativeTerminalAndDeferredCodesWithoutReadinessGuessing() {
    FakeNativeApi api = new FakeNativeApi();
    api.loaded = true;
    api.available = true;
    api.compilerReady = true;
    NativeIrisMslLibraryValidator validator =
        new NativeIrisMslLibraryValidator(api);

    api.result = NativeIrisMslLibraryValidator.NATIVE_UNSUPPORTED;
    assertEquals(IrisMslLibraryValidator.Result.UNSUPPORTED,
        validator.validate(MSL, IrisShaderStage.FRAGMENT));

    api.result = NativeIrisMslLibraryValidator.NATIVE_FAILED;
    assertEquals(IrisMslLibraryValidator.Result.FAILED,
        validator.validate(MSL, IrisShaderStage.FRAGMENT));

    api.result = NativeIrisMslLibraryValidator.NATIVE_DEFERRED;
    api.abaReinitializeDuringValidation = true;
    assertEquals(IrisMslLibraryValidator.Result.DEFERRED,
        validator.validate(MSL, IrisShaderStage.FRAGMENT));
    assertEquals(IrisMslLibraryValidator.Readiness.READY,
        validator.readiness(),
        "destroy then fast re-init must not rewrite native DEFERRED as terminal");
    api.abaReinitializeDuringValidation = false;
    api.throwLinkageError = true;
    assertEquals(IrisMslLibraryValidator.Result.DEFERRED,
        validator.validate(MSL, IrisShaderStage.FRAGMENT));
  }

  @Test
  void exposesLoadedNativeGaugeEvenWhenCompilerIsNotReady() {
    FakeNativeApi api = new FakeNativeApi();
    api.liveLibraries = 3;
    NativeIrisMslLibraryValidator validator =
        new NativeIrisMslLibraryValidator(api);

    assertEquals(0, validator.liveLibraryCount());
    assertEquals(0, api.liveGaugeCalls.get());
    api.loaded = true;
    assertEquals(3, validator.liveLibraryCount());
    assertEquals(1, api.liveGaugeCalls.get());
    assertEquals(0, api.availableCalls.get(),
        "gauge query must not depend on device/compiler readiness");

    api.throwLiveGauge = true;
    assertEquals(-1, validator.liveLibraryCount(),
        "loaded telemetry failure must not be masked as no leak");
  }

  private static final class FakeNativeApi
      implements NativeIrisMslLibraryValidator.NativeApi {
    private final AtomicInteger availableCalls = new AtomicInteger();
    private final AtomicInteger validationCalls = new AtomicInteger();
    private final AtomicInteger liveGaugeCalls = new AtomicInteger();
    private boolean loaded;
    private boolean available;
    private boolean compilerReady;
    private boolean throwLinkageError;
    private boolean abaReinitializeDuringValidation;
    private boolean throwLiveGauge;
    private int result;
    private int lastStageOrdinal = -1;
    private long liveLibraries;

    @Override
    public boolean loaded() {
      return loaded;
    }

    @Override
    public boolean available() {
      availableCalls.incrementAndGet();
      return available;
    }

    @Override
    public boolean compilerReady() {
      return compilerReady;
    }

    @Override
    public int validate(byte[] mslUtf8, int stageOrdinal) {
      validationCalls.incrementAndGet();
      lastStageOrdinal = stageOrdinal;
      if (throwLinkageError) {
        throw new UnsatisfiedLinkError("readiness race");
      }
      if (abaReinitializeDuringValidation) {
        compilerReady = false;
        compilerReady = true;
      }
      return result;
    }

    @Override
    public long liveLibraryCount() {
      liveGaugeCalls.incrementAndGet();
      if (throwLiveGauge) {
        throw new UnsatisfiedLinkError("gauge unavailable");
      }
      return liveLibraries;
    }
  }
}

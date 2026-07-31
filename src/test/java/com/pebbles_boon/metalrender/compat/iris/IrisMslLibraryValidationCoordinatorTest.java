package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

final class IrisMslLibraryValidationCoordinatorTest {
  private static final IrisTranslationProfile PROFILE =
      new IrisTranslationProfile("library-validation-test=1");

  @TempDir
  Path temporaryDirectory;

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void validationPropertyRequiresCaptureAndTranslationOptIns() {
    String capture = IrisShaderCapture.ENABLED_PROPERTY;
    String translation =
        IrisTranslationCoordinator.TRANSLATION_ENABLED_PROPERTY;
    String validation =
        IrisTranslationCoordinator.LIBRARY_VALIDATION_ENABLED_PROPERTY;
    String oldCapture = System.getProperty(capture);
    String oldTranslation = System.getProperty(translation);
    String oldValidation = System.getProperty(validation);
    try {
      System.clearProperty(capture);
      System.clearProperty(translation);
      System.setProperty(validation, "true");
      assertFalse(IrisTranslationCoordinator.isLibraryValidationOptedIn());

      System.setProperty(capture, "true");
      assertFalse(IrisTranslationCoordinator.isLibraryValidationOptedIn());

      System.setProperty(translation, "true");
      assertTrue(IrisTranslationCoordinator.isLibraryValidationOptedIn());

      System.clearProperty(capture);
      assertFalse(IrisTranslationCoordinator.isLibraryValidationOptedIn());
    } finally {
      restoreProperty(capture, oldCapture);
      restoreProperty(translation, oldTranslation);
      restoreProperty(validation, oldValidation);
    }
  }

  @Test
  void coldAndWarmRunsCompileVerifiedStagesWithStableDigest()
      throws Exception {
    Path root = temporaryDirectory.resolve("cold-warm");
    IrisPipelineCache cache = cache(root);
    IrisFinalShaderProgram program = program("cold-warm");

    FakeBackend coldBackend = new FakeBackend();
    FakeValidator coldValidator = FakeValidator.readyCompiled();
    IrisTranslationCoordinator.Status cold = run(
        root, cache, coldBackend, coldValidator, program, 8,
        status -> status.libraryValidationComplete());

    assertEquals(1, cold.translated());
    assertEquals(0, cold.cacheHits());
    assertEquals(1, cold.libraryProgramsAttempted());
    assertEquals(1, cold.libraryProgramsSucceeded());
    assertEquals(2, cold.libraryStagesAttempted());
    assertEquals(2, cold.libraryStagesSucceeded());
    assertEquals(2, cold.libraryStagesFromTranslation());
    assertEquals(0, cold.libraryStagesFromCache());
    assertEquals(0, cold.libraryLiveLibraries());
    assertTrue(cold.compiledArtifactSetComplete());
    assertEquals(expectedDigest(program), cold.compiledArtifactSetSha256());
    assertTrue(cold.libraryValidationComplete());
    assertEquals(2, coldValidator.validationCalls.get());

    FakeBackend warmBackend = new FakeBackend();
    FakeValidator warmValidator = FakeValidator.readyCompiled();
    IrisTranslationCoordinator.Status warm = run(
        root, cache, warmBackend, warmValidator, program, 8,
        status -> status.libraryValidationComplete());

    assertEquals(0, warm.translated());
    assertEquals(1, warm.cacheHits());
    assertEquals(0, warmBackend.calls.get());
    assertEquals(0, warm.libraryStagesFromTranslation());
    assertEquals(2, warm.libraryStagesFromCache());
    assertEquals(cold.compiledArtifactSetSha256(),
        warm.compiledArtifactSetSha256());
    assertTrue(Files.readString(cache.lookup(program, PROFILE)
        .orElseThrow().manifest()).contains("pipeline.status=pending"));
    assertFalse(Files.exists(cache.lookup(program, PROFILE)
        .orElseThrow().metalBinaryArchive()));
  }

  @Test
  void disabledValidationNeverSchedulesOrEntersValidator()
      throws Exception {
    Path root = temporaryDirectory.resolve("disabled");
    IrisShaderCaptureQueue queue = queue();
    FakeBackend backend = new FakeBackend();
    FakeValidator validator = FakeValidator.readyCompiled();
    IrisTranslationCoordinator coordinator = new IrisTranslationCoordinator(
        queue, backend, cache(root), root, false, validator, 8);
    queue.offer(program("disabled"));
    try {
      coordinator.start();
      await(() -> coordinator.snapshot().translated() == 1,
          Duration.ofSeconds(3));
      IrisTranslationCoordinator.Status status = coordinator.snapshot();
      assertFalse(status.libraryValidationEnabled());
      assertEquals(0, status.libraryProgramsAttempted());
      assertEquals(0, status.libraryStagesAttempted());
      assertEquals(0, validator.readinessCalls.get());
      assertEquals(0, validator.validationCalls.get());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void deferredReadinessKeepsJobsPendingWithoutFalseTerminalCounts()
      throws Exception {
    Path root = temporaryDirectory.resolve("deferred");
    IrisShaderCaptureQueue queue = queue();
    FakeValidator validator = new FakeValidator();
    IrisTranslationCoordinator coordinator = new IrisTranslationCoordinator(
        queue, new FakeBackend(), cache(root), root, validator, 8);
    queue.offer(program("deferred"));
    try {
      coordinator.start();
      await(() -> coordinator.snapshot().libraryStagesPending() == 2,
          Duration.ofSeconds(3));
      IrisTranslationCoordinator.Status pending = coordinator.snapshot();
      assertFalse(pending.libraryValidationReady());
      assertEquals(0, pending.libraryStagesSucceeded());
      assertEquals(0, pending.libraryStagesUnsupported());
      assertEquals(0, pending.libraryStagesFailed());
      assertEquals(0, validator.validationCalls.get());

      validator.readiness.set(IrisMslLibraryValidator.Readiness.READY);
      await(() -> coordinator.snapshot().libraryValidationComplete(),
          Duration.ofSeconds(3));
      assertEquals(2, validator.validationCalls.get());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void nativeFailureAndUnsupportedAreSeparateAndFailOpen()
      throws Exception {
    Path failureRoot = temporaryDirectory.resolve("failure");
    FakeValidator failureValidator = FakeValidator.readyCompiled();
    failureValidator.results.put(
        IrisShaderStage.FRAGMENT, IrisMslLibraryValidator.Result.FAILED);
    IrisTranslationCoordinator.Status failure = run(
        failureRoot, cache(failureRoot), new FakeBackend(), failureValidator,
        program("failure"), 8,
        status -> status.libraryProgramsFailed() == 1);
    assertTrue(failure.running());
    assertEquals(0, failure.failed(),
        "library compile failures must not become translation failures");
    assertEquals(1, failure.libraryStagesSucceeded());
    assertEquals(1, failure.libraryStagesFailed());
    assertEquals(0, failure.libraryStagesUnsupported());
    assertFalse(failure.libraryValidationComplete());
    assertTrue(failure.libraryValidationLastFailure()
        .contains("native-compile-failed"));

    Path unsupportedRoot = temporaryDirectory.resolve("unsupported");
    FakeValidator unsupportedValidator = FakeValidator.readyCompiled();
    unsupportedValidator.results.put(
        IrisShaderStage.FRAGMENT,
        IrisMslLibraryValidator.Result.UNSUPPORTED);
    IrisTranslationCoordinator.Status unsupported = run(
        unsupportedRoot, cache(unsupportedRoot), new FakeBackend(),
        unsupportedValidator, program("unsupported"), 8,
        status -> status.libraryProgramsUnsupported() == 1);
    assertTrue(unsupported.running());
    assertEquals(1, unsupported.libraryStagesUnsupported());
    assertEquals(0, unsupported.libraryStagesFailed());
    assertFalse(unsupported.libraryValidationComplete());
  }

  @Test
  void tamperAfterQueueingFailsVerifiedReadWithoutCallingNative()
      throws Exception {
    Path root = temporaryDirectory.resolve("tamper");
    IrisPipelineCache cache = cache(root);
    IrisShaderCaptureQueue queue = queue();
    FakeValidator validator = new FakeValidator();
    IrisFinalShaderProgram program = program("tamper");
    IrisTranslationCoordinator coordinator = new IrisTranslationCoordinator(
        queue, new FakeBackend(), cache, root, validator, 8);
    queue.offer(program);
    try {
      coordinator.start();
      await(() -> coordinator.snapshot().libraryStagesPending() == 2,
          Duration.ofSeconds(3));
      IrisPipelineCacheLayout.CachePaths paths =
          cache.lookup(program, PROFILE).orElseThrow();
      Files.writeString(paths.msl(IrisShaderStage.VERTEX),
          msl(IrisShaderStage.VERTEX).replace("main0", "main1"));
      validator.readiness.set(IrisMslLibraryValidator.Readiness.READY);

      await(() -> coordinator.snapshot().libraryProgramsFailed() == 1,
          Duration.ofSeconds(3));
      IrisTranslationCoordinator.Status status = coordinator.snapshot();
      assertEquals(1, status.libraryStagesFailed());
      assertEquals(1, status.libraryStagesSucceeded());
      assertEquals(1, validator.validationCalls.get());
      assertTrue(status.libraryValidationLastFailure()
          .contains("cache-verification-failed"));
    } finally {
      coordinator.close();
    }
  }

  @Test
  void boundedStageQueueMakesOverflowVisibleAndProgramFailsOpen()
      throws Exception {
    Path root = temporaryDirectory.resolve("bounded");
    IrisShaderCaptureQueue queue = queue();
    FakeValidator validator = new FakeValidator();
    IrisTranslationCoordinator coordinator = new IrisTranslationCoordinator(
        queue, new FakeBackend(), cache(root), root, validator, 1);
    queue.offer(program("bounded"));
    try {
      coordinator.start();
      await(() -> coordinator.snapshot().libraryStagesRejected() == 1,
          Duration.ofSeconds(3));
      IrisTranslationCoordinator.Status bounded = coordinator.snapshot();
      assertEquals(2, bounded.libraryStagesAttempted());
      assertEquals(1, bounded.libraryStagesPending());
      assertEquals(1, bounded.libraryStagesFailed());
      assertEquals(1, bounded.libraryStagesRejected());
      assertEquals(0, bounded.libraryProgramsFailed());

      validator.readiness.set(IrisMslLibraryValidator.Readiness.READY);
      await(() -> coordinator.snapshot().libraryProgramsFailed() == 1,
          Duration.ofSeconds(3));
      IrisTranslationCoordinator.Status completed = coordinator.snapshot();
      assertEquals(1, completed.libraryStagesSucceeded());
      assertEquals(0, completed.libraryStagesPending());
      assertFalse(completed.libraryValidationComplete());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void compiledArtifactIdentitySetHasHardVisibleCapacity()
      throws Exception {
    Path root = temporaryDirectory.resolve("identity-capacity");
    IrisShaderCaptureQueue queue = queue();
    FakeValidator validator = FakeValidator.readyCompiled();
    IrisTranslationCoordinator coordinator = new IrisTranslationCoordinator(
        queue, new FakeBackend(), cache(root), root, validator, 8, 1);
    queue.offer(program("identity-capacity"));
    try {
      coordinator.start();
      await(() -> coordinator.snapshot().libraryProgramsSucceeded() == 1,
          Duration.ofSeconds(3));
      IrisTranslationCoordinator.Status status = coordinator.snapshot();
      assertEquals(2, status.libraryStagesAttempted());
      assertEquals(2, status.libraryStagesSucceeded(),
          "successful native compiles stay truthful when digest storage caps");
      assertEquals(0, status.libraryStagesFailed());
      assertEquals(1, status.libraryProgramsSucceeded());
      assertFalse(status.compiledArtifactSetComplete());
      assertFalse(status.libraryValidationComplete());
      assertEquals("compiled-artifact-set-capacity-exceeded",
          status.libraryValidationLastFailure());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void unavailableLiveLibraryTelemetryBlocksCompletionWithoutHidingSuccess()
      throws Exception {
    Path root = temporaryDirectory.resolve("live-gauge-unavailable");
    FakeValidator validator = FakeValidator.readyCompiled();
    validator.throwLiveGauge = true;
    IrisTranslationCoordinator.Status status = run(
        root, cache(root), new FakeBackend(), validator,
        program("live-gauge-unavailable"), 8,
        snapshot -> snapshot.libraryProgramsSucceeded() == 1
            && snapshot.libraryStagesPending() == 0
            && snapshot.libraryStagesInFlight() == 0);

    assertEquals(2, status.libraryStagesSucceeded());
    assertEquals(0, status.libraryStagesFailed());
    assertEquals(-1, status.libraryLiveLibraries());
    assertFalse(status.libraryValidationComplete());
  }

  private IrisTranslationCoordinator.Status run(Path root,
      IrisPipelineCache cache, FakeBackend backend, FakeValidator validator,
      IrisFinalShaderProgram program, int capacity,
      java.util.function.Predicate<IrisTranslationCoordinator.Status> done)
      throws Exception {
    IrisShaderCaptureQueue queue = queue();
    IrisTranslationCoordinator coordinator = new IrisTranslationCoordinator(
        queue, backend, cache, root, validator, capacity);
    queue.offer(program);
    try {
      coordinator.start();
      await(() -> done.test(coordinator.snapshot()),
          Duration.ofSeconds(3));
      return coordinator.snapshot();
    } finally {
      coordinator.close();
    }
  }

  private static IrisShaderCaptureQueue queue() {
    return new IrisShaderCaptureQueue(8, 10_000, 80_000, PROFILE);
  }

  private static IrisFinalShaderProgram program(String name) {
    return IrisFinalShaderProgram.fromGraphicsLink(
        name, "private-final-vertex", null, null, null,
        "private-final-fragment");
  }

  private static IrisPipelineCache cache(Path root) {
    return new IrisPipelineCache(new IrisPipelineCacheLayout(root));
  }

  private static void await(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), "condition did not become true");
  }

  private static String msl(IrisShaderStage stage) {
    String functionType = stage == IrisShaderStage.VERTEX
        ? "vertex" : "fragment";
    String value = stage == IrisShaderStage.VERTEX ? "0.0" : "1.0";
    return "#include <metal_stdlib>\nusing namespace metal;\n"
        + functionType + " float4 main0() { return float4("
        + value + "); }\n";
  }

  private static String expectedDigest(IrisFinalShaderProgram program)
      throws Exception {
    String key = IrisShaderCacheKey.from(program, PROFILE).sha256();
    List<String> lines = new ArrayList<>();
    for (IrisShaderStage stage
        : List.of(IrisShaderStage.VERTEX, IrisShaderStage.FRAGMENT)) {
      String mslDigest = rawSha256(
          msl(stage).getBytes(StandardCharsets.UTF_8));
      lines.add(key + "/" + stage.cacheName() + "/" + mslDigest + "\n");
    }
    lines.sort(String::compareTo);
    return rawSha256(String.join("", lines)
        .getBytes(StandardCharsets.US_ASCII));
  }

  private static String rawSha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(content));
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  private static final class FakeValidator
      implements IrisMslLibraryValidator {
    private final AtomicReference<Readiness> readiness =
        new AtomicReference<>(Readiness.DEFERRED);
    private final AtomicInteger readinessCalls = new AtomicInteger();
    private final AtomicInteger validationCalls = new AtomicInteger();
    private final EnumMap<IrisShaderStage, Result> results =
        new EnumMap<>(IrisShaderStage.class);
    private boolean throwLiveGauge;

    private static FakeValidator readyCompiled() {
      FakeValidator validator = new FakeValidator();
      validator.readiness.set(Readiness.READY);
      return validator;
    }

    @Override
    public Readiness readiness() {
      readinessCalls.incrementAndGet();
      return readiness.get();
    }

    @Override
    public Result validate(byte[] mslUtf8, IrisShaderStage stage) {
      validationCalls.incrementAndGet();
      assertTrue(new String(mslUtf8, StandardCharsets.UTF_8)
          .contains("metal_stdlib"));
      return results.getOrDefault(stage, Result.COMPILED);
    }

    @Override
    public long liveLibraryCount() {
      if (throwLiveGauge) {
        throw new UnsatisfiedLinkError("gauge unavailable");
      }
      return 0;
    }
  }

  private static final class FakeBackend
      implements IrisShaderTranslatorBackend {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public String id() {
      return "library-validation-test";
    }

    @Override
    public IrisTranslationProfile profile() {
      return PROFILE;
    }

    @Override
    public Availability discover() {
      return new Availability(true, "test backend");
    }

    @Override
    public StageSupport support(IrisShaderStage stage) {
      return StageSupport.SUPPORTED;
    }

    @Override
    public IrisShaderTranslation translate(IrisFinalShaderProgram program) {
      calls.incrementAndGet();
      EnumMap<IrisShaderStage, IrisShaderTranslation.StageArtifacts> stages =
          new EnumMap<>(IrisShaderStage.class);
      for (IrisShaderStage stage : IrisShaderStage.values()) {
        if (program.hasStage(stage)) {
          stages.put(stage, new IrisShaderTranslation.StageArtifacts(
              minimalSpirv(), msl(stage)));
        }
      }
      return new IrisShaderTranslation(
          IrisShaderCacheKey.from(program, PROFILE), id(), PROFILE, stages);
    }

    private static byte[] minimalSpirv() {
      return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
          .putInt(0x07230203)
          .putInt(0x00010000)
          .putInt(0)
          .putInt(1)
          .putInt(0)
          .array();
    }
  }
}

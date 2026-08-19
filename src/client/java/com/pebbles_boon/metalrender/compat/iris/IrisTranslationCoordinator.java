package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicitly enabled background owner for capture hashing, translation, and
 * cache writes.
 *
 * <p>Nothing in this class can replace or cancel Iris' OpenGL link. Every
 * translation/cache failure is counted and swallowed on the daemon worker.</p>
 */
public final class IrisTranslationCoordinator implements AutoCloseable {
  public static final String TRANSLATION_ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalTranslation";
  public static final String LIBRARY_VALIDATION_ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalLibraryValidation";
  static final int DEFAULT_LIBRARY_VALIDATION_QUEUE_CAPACITY = 512;
  static final int DEFAULT_COMPILED_ARTIFACT_IDENTITY_CAPACITY =
      Math.multiplyExact(IrisPipelineCache.DEFAULT_MAX_ENTRIES,
          IrisShaderStage.values().length);
  static final int DEFAULT_PIPELINE_STATE_IDENTITY_CAPACITY = 65_536;
  static final int DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY = 32;
  private static final long CLIENT_QUIET_PERIOD_NANOS =
      TimeUnit.SECONDS.toNanos(8);

  private static final AtomicReference<IrisTranslationCoordinator> ACTIVE =
      new AtomicReference<>();
  private static final AtomicReference<String> CONFIGURED_CACHE_ROOT =
      new AtomicReference<>("");
  private static final AtomicReference<String> START_FAILURE =
      new AtomicReference<>("");

  private final IrisShaderCaptureQueue captureQueue;
  private final IrisShaderTranslatorBackend backend;
  private final IrisPipelineCache cache;
  private final IrisPipelineStateCapture pipelineStateCapture;
  private final IrisPipelineStateCache pipelineStateCache;
  private final IrisSpecializationStateReader specializationStateReader;
  private final IrisMslLibraryValidator libraryValidator;
  private final boolean libraryValidationEnabled;
  private final ArrayBlockingQueue<LibraryStageJob> libraryStageQueue;
  private final int compiledArtifactIdentityCapacity;
  private final String cacheRoot;
  private final long quietPeriodNanos;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicLong attempted = new AtomicLong();
  private final AtomicLong translated = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicReference<String> lastFailure =
      new AtomicReference<>("");
  private final AtomicLong warningCount = new AtomicLong();
  private final AtomicBoolean libraryValidationReady = new AtomicBoolean();
  private final AtomicInteger libraryStagesInFlight = new AtomicInteger();
  private final AtomicLong libraryProgramsAttempted = new AtomicLong();
  private final AtomicLong libraryProgramsSucceeded = new AtomicLong();
  private final AtomicLong libraryProgramsUnsupported = new AtomicLong();
  private final AtomicLong libraryProgramsFailed = new AtomicLong();
  private final AtomicLong libraryStagesAttempted = new AtomicLong();
  private final AtomicLong libraryStagesSucceeded = new AtomicLong();
  private final AtomicLong libraryStagesUnsupported = new AtomicLong();
  private final AtomicLong libraryStagesFailed = new AtomicLong();
  private final AtomicLong libraryStagesFromTranslation = new AtomicLong();
  private final AtomicLong libraryStagesFromCache = new AtomicLong();
  private final AtomicLong libraryStagesRejected = new AtomicLong();
  private final AtomicLong libraryLiveLibraries = new AtomicLong();
  private final AtomicReference<String> libraryValidationLastFailure =
      new AtomicReference<>("");
  private final AtomicLong libraryWarningCount = new AtomicLong();
  private final ConcurrentSkipListSet<String> compiledArtifactLines =
      new ConcurrentSkipListSet<>();
  private final AtomicBoolean compiledArtifactSetComplete =
      new AtomicBoolean(true);
  private final AtomicLong pipelineStatesAttempted = new AtomicLong();
  private final AtomicLong pipelineStatesSucceeded = new AtomicLong();
  private final AtomicLong pipelineStateCacheHits = new AtomicLong();
  private final AtomicLong pipelineStatesUnsupported = new AtomicLong();
  private final AtomicLong pipelineStatesFailed = new AtomicLong();
  private final AtomicLong pipelineStatesExecutionBlocked = new AtomicLong();
  private final AtomicReference<String> pipelineStateLastFailure =
      new AtomicReference<>("");
  private final BoundedReasonSet pipelineStateUnsupportedReasons =
      new BoundedReasonSet(
          DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final ConcurrentSkipListSet<String> pipelineStateIdentityLines =
      new ConcurrentSkipListSet<>();
  private final AtomicBoolean pipelineStateSetComplete =
      new AtomicBoolean(true);

  IrisTranslationCoordinator(IrisShaderCaptureQueue captureQueue,
      IrisShaderTranslatorBackend backend, IrisPipelineCache cache,
      Path cacheRoot) {
    this(captureQueue, backend, cache, cacheRoot, 0, false,
        IrisMslLibraryValidator.deferred(),
        DEFAULT_LIBRARY_VALIDATION_QUEUE_CAPACITY,
        DEFAULT_COMPILED_ARTIFACT_IDENTITY_CAPACITY);
  }

  IrisTranslationCoordinator(IrisShaderCaptureQueue captureQueue,
      IrisShaderTranslatorBackend backend, IrisPipelineCache cache,
      Path cacheRoot, IrisMslLibraryValidator libraryValidator,
      int libraryValidationQueueCapacity) {
    this(captureQueue, backend, cache, cacheRoot, 0, true, libraryValidator,
        libraryValidationQueueCapacity,
        DEFAULT_COMPILED_ARTIFACT_IDENTITY_CAPACITY);
  }

  IrisTranslationCoordinator(IrisShaderCaptureQueue captureQueue,
      IrisShaderTranslatorBackend backend, IrisPipelineCache cache,
      Path cacheRoot, IrisMslLibraryValidator libraryValidator,
      int libraryValidationQueueCapacity,
      int compiledArtifactIdentityCapacity) {
    this(captureQueue, backend, cache, cacheRoot, 0, true, libraryValidator,
        libraryValidationQueueCapacity, compiledArtifactIdentityCapacity);
  }

  IrisTranslationCoordinator(IrisShaderCaptureQueue captureQueue,
      IrisShaderTranslatorBackend backend, IrisPipelineCache cache,
      Path cacheRoot, boolean libraryValidationEnabled,
      IrisMslLibraryValidator libraryValidator,
      int libraryValidationQueueCapacity) {
    this(captureQueue, backend, cache, cacheRoot, 0,
        libraryValidationEnabled,
        libraryValidator, libraryValidationQueueCapacity,
        DEFAULT_COMPILED_ARTIFACT_IDENTITY_CAPACITY);
  }

  private IrisTranslationCoordinator(IrisShaderCaptureQueue captureQueue,
      IrisShaderTranslatorBackend backend, IrisPipelineCache cache,
      Path cacheRoot, long quietPeriodNanos,
      boolean libraryValidationEnabled,
      IrisMslLibraryValidator libraryValidator,
      int libraryValidationQueueCapacity,
      int compiledArtifactIdentityCapacity) {
    this.captureQueue = Objects.requireNonNull(captureQueue, "captureQueue");
    this.backend = Objects.requireNonNull(backend, "backend");
    this.cache = Objects.requireNonNull(cache, "cache");
    pipelineStateCapture = IrisPipelineStateCapture.global();
    pipelineStateCache = new IrisPipelineStateCache(cacheRoot);
    specializationStateReader = new IrisSpecializationStateReader(cache,
        backend.profile());
    this.libraryValidator = Objects.requireNonNull(
        libraryValidator, "libraryValidator");
    this.libraryValidationEnabled = libraryValidationEnabled;
    if (libraryValidationQueueCapacity <= 0) {
      throw new IllegalArgumentException(
          "library validation queue capacity must be positive");
    }
    libraryStageQueue = new ArrayBlockingQueue<>(
        libraryValidationQueueCapacity);
    if (compiledArtifactIdentityCapacity <= 0) {
      throw new IllegalArgumentException(
          "compiled artifact identity capacity must be positive");
    }
    this.compiledArtifactIdentityCapacity =
        compiledArtifactIdentityCapacity;
    this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot")
        .toAbsolutePath().normalize().toString();
    this.quietPeriodNanos = Math.max(0, quietPeriodNanos);
    ThreadFactory daemonFactory = task -> {
      Thread thread = new Thread(task, "MetalRender-Iris-Translator");
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
    };
    executor = Executors.newSingleThreadScheduledExecutor(daemonFactory);
  }

  /**
   * Starts the singleton worker only when both experimental properties were
   * set before client initialization.
   */
  public static synchronized boolean startIfEnabled(Path cacheRoot) {
    Path normalized = Objects.requireNonNull(cacheRoot, "cacheRoot")
        .toAbsolutePath().normalize();
    CONFIGURED_CACHE_ROOT.set(normalized.toString());
    if (!isOptedIn()) {
      return false;
    }
    IrisTranslationCoordinator existing = ACTIVE.get();
    if (existing != null && existing.running.get()) {
      return true;
    }

    try {
      IrisPipelineStateCapture.global().initializeOpenGlDefaults();
      LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
          LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
      IrisPipelineCache cache = new IrisPipelineCache(
          new IrisPipelineCacheLayout(normalized));
      IrisTranslationCoordinator coordinator =
          new IrisTranslationCoordinator(IrisShaderCapture.captureQueue(),
              backend, cache, normalized, CLIENT_QUIET_PERIOD_NANOS,
              isLibraryValidationOptedIn(),
              new NativeIrisMslLibraryValidator(),
              DEFAULT_LIBRARY_VALIDATION_QUEUE_CAPACITY,
              DEFAULT_COMPILED_ARTIFACT_IDENTITY_CAPACITY);
      ACTIVE.set(coordinator);
      coordinator.start();
      START_FAILURE.set("");
      return true;
    } catch (Throwable error) {
      START_FAILURE.set(redactedFailure(error));
      MetalLogger.warn(
          "Iris Metal translation coordinator did not start; Iris OpenGL remains active (%s)",
          redactedFailure(error));
      return false;
    }
  }

  public static synchronized void stop() {
    IrisTranslationCoordinator coordinator = ACTIVE.getAndSet(null);
    if (coordinator != null) {
      coordinator.close();
    }
  }

  /**
   * Stable read-only diagnostics for commands and exact-JAR QA.
   */
  public static Status status() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return new Status(isOptedIn(), false,
          IrisShaderCapture.queuedPrograms(),
          IrisShaderCapture.rejectedPrograms(),
          IrisShaderCapture.captureFailures(), 0, 0, 0, 0,
          CONFIGURED_CACHE_ROOT.get(), START_FAILURE.get(),
          isLibraryValidationOptedIn(), false,
          0, 0, 0, 0,
          0, 0, 0, 0, 0, 0,
          0, 0, 0, 0, true, emptyCompiledArtifactSetSha256(), "",
          IrisPipelineStateCapture.global().drawsObserved(),
          IrisPipelineStateCapture.global().dispatchesObserved(),
          IrisPipelineStateCapture.global().variantsAccepted(),
          IrisPipelineStateCapture.global().variantsRejected(),
          IrisPipelineStateCapture.global().incompleteVariants(),
          IrisPipelineStateCapture.global().queued(),
          0, 0, 0, 0,
          0, true, emptyPipelineStateSetSha256(), "",
          0, 0, 0, true,
          emptyPipelineStateSetSha256(), "");
    }
    return coordinator.snapshot();
  }

  private static boolean isOptedIn() {
    return IrisShaderCapture.isEnabled()
        && Boolean.getBoolean(TRANSLATION_ENABLED_PROPERTY);
  }

  static boolean isLibraryValidationOptedIn() {
    return isOptedIn()
        && Boolean.getBoolean(LIBRARY_VALIDATION_ENABLED_PROPERTY);
  }

  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    executor.scheduleWithFixedDelay(this::drainSafely,
        0, 100, TimeUnit.MILLISECONDS);
  }

  private void drainSafely() {
    try {
      if (captureQueue.nanosSinceLastOffer() < quietPeriodNanos) {
        return;
      }
      drainOneLibraryStage();
      for (int processed = 0; processed < 1 && running.get(); processed++) {
        Optional<IrisShaderCaptureQueue.CapturedProgram> captured =
            captureQueue.poll();
        if (captured.isEmpty()) {
          for (int stateBatch = 0; stateBatch < 16 && running.get();
               stateBatch++) {
            if (!drainOnePipelineState()) {
              break;
            }
          }
          return;
        }
        process(captured.orElseThrow());
      }
    } catch (OutOfMemoryError resourceLimit) {
      failed.incrementAndGet();
      lastFailure.set("OutOfMemoryError");
      running.set(false);
      executor.shutdown();
    } catch (Exception | LinkageError error) {
      recordFailure(error);
    }
  }

  private void process(IrisShaderCaptureQueue.CapturedProgram captured) {
    captured.registration().ifPresent(registration ->
        IrisProgramIdentityRegistry.global().resolve(registration,
            captured.key(), captured.program().sources().keySet()));
    if (captured.duplicate()) {
      return;
    }
    attempted.incrementAndGet();
    IrisFinalShaderProgram program = captured.program();
    try {
      for (IrisShaderStage stage : IrisShaderStage.values()) {
        if (program.hasStage(stage)
            && backend.support(stage)
            == IrisShaderTranslatorBackend.StageSupport
                .UNSUPPORTED_KEEP_IRIS_OPENGL) {
          failed.incrementAndGet();
          lastFailure.set(stage.cacheName()
              + " unsupported; kept Iris OpenGL fallback");
          return;
        }
      }

      if (cache.lookup(program, backend.profile()).isPresent()) {
        cacheHits.incrementAndGet();
        scheduleLibraryValidation(program,
            IrisShaderCacheKey.from(program, backend.profile()),
            backend.profile(), LibraryStageSource.CACHE);
        return;
      }
      IrisShaderTranslation result = backend.translate(program);
      if (!running.get()) {
        return;
      }
      IrisPipelineCache.StoreResult stored = cache.store(program, result);
      if (stored.cacheHit()) {
        cacheHits.incrementAndGet();
        scheduleLibraryValidation(program, result.key(), result.profile(),
            LibraryStageSource.CACHE);
      } else {
        translated.incrementAndGet();
        scheduleLibraryValidation(program, result.key(), result.profile(),
            LibraryStageSource.TRANSLATION);
      }
    } catch (Exception | LinkageError error) {
      recordFailure(error, program);
    }
  }

  private boolean drainOnePipelineState() {
    Optional<IrisPipelineStateCapture.PendingState> pending =
        pipelineStateCapture.poll();
    if (pending.isEmpty()) {
      return false;
    }
    pipelineStatesAttempted.incrementAndGet();
    IrisPipelineStateCapture.PendingState captured = pending.orElseThrow();
    try {
      Optional<IrisProgramIdentityRegistry.ResolvedProgram> resolved =
          captured.registration().resolved();
      if (resolved.isEmpty()) {
        recordPipelineStateUnsupported("program-identity-unresolved");
        return true;
      }
      IrisProgramIdentityRegistry.ResolvedProgram program =
          resolved.orElseThrow();
      IrisSpecializationStateReader.Result specialization =
          specializationStateReader.read(program);
      if (specialization instanceof IrisSpecializationStateReader.Unsupported
          unsupported) {
        recordPipelineStateUnsupported(
            "specialization-" + unsupported.reason());
        return true;
      }
      IrisSpecializationStateReader.Complete constants =
          (IrisSpecializationStateReader.Complete) specialization;
      IrisPipelineStateMapper.Result mapped = IrisPipelineStateMapper.map(
          captured.snapshot(), program, constants.constants());
      if (mapped instanceof IrisPipelineStateMapper.Unsupported unsupported) {
        recordPipelineStateUnsupported(unsupported.reason());
        return true;
      }
      IrisPipelineStateMapper.Complete complete =
          (IrisPipelineStateMapper.Complete) mapped;
      IrisPipelineStateCache.StoreResult stored = pipelineStateCache.store(
          program.shaderKey(), complete.state());
      pipelineStatesSucceeded.incrementAndGet();
      if (stored.cacheHit()) {
        pipelineStateCacheHits.incrementAndGet();
      }
      if (!complete.metalExecutionSupported()) {
        pipelineStatesExecutionBlocked.incrementAndGet();
      }
      retainPipelineStateIdentity(stored.verified());
    } catch (Exception | LinkageError error) {
      pipelineStatesFailed.incrementAndGet();
      pipelineStateLastFailure.set(redactedFailure(error));
    }
    return true;
  }

  private void recordPipelineStateUnsupported(String reason) {
    pipelineStatesUnsupported.incrementAndGet();
    pipelineStateLastFailure.set(reason);
    pipelineStateUnsupportedReasons.add(reason);
  }

  private void recordFailure(Throwable error) {
    failed.incrementAndGet();
    String redacted = redactedFailure(error);
    lastFailure.set(redacted);
    long warnings = warningCount.incrementAndGet();
    if (warnings <= 3 || warnings % 32 == 0) {
      MetalLogger.warn(
          "Iris Metal background translation failed open; Iris OpenGL remains active (%s, failure %d)",
          redacted, warnings);
    }
  }

  private void recordFailure(Throwable error,
      IrisFinalShaderProgram program) {
    failed.incrementAndGet();
    String name = program.programName().replaceAll(
        "[^A-Za-z0-9._:/-]", "_");
    if (name.length() > 80) {
      name = name.substring(0, 80);
    }
    String redacted = "program=" + name + ":" + redactedFailure(error);
    lastFailure.set(redacted);
    long warnings = warningCount.incrementAndGet();
    if (warnings <= 3 || warnings % 32 == 0) {
      MetalLogger.warn(
          "Iris Metal background translation failed open; Iris OpenGL remains active (%s, failure %d)",
          redacted, warnings);
    }
  }

  private void scheduleLibraryValidation(IrisFinalShaderProgram program,
      IrisShaderCacheKey key, IrisTranslationProfile profile,
      LibraryStageSource source) {
    if (!libraryValidationEnabled) {
      return;
    }
    int stageCount = 0;
    for (IrisShaderStage stage : IrisShaderStage.values()) {
      if (program.hasStage(stage)) {
        stageCount++;
      }
    }
    if (stageCount == 0) {
      return;
    }

    libraryProgramsAttempted.incrementAndGet();
    LibraryProgramState programState = new LibraryProgramState(stageCount);
    for (IrisShaderStage stage : IrisShaderStage.values()) {
      if (!program.hasStage(stage)) {
        continue;
      }
      libraryStagesAttempted.incrementAndGet();
      if (source == LibraryStageSource.TRANSLATION) {
        libraryStagesFromTranslation.incrementAndGet();
      } else {
        libraryStagesFromCache.incrementAndGet();
      }
      LibraryStageJob job = new LibraryStageJob(
          key, profile, stage, programState);
      if (!libraryStageQueue.offer(job)) {
        libraryStagesRejected.incrementAndGet();
        libraryStagesFailed.incrementAndGet();
        String failure = artifactLabel(key, stage) + ": queue-full";
        libraryValidationLastFailure.set(failure);
        programState.resolve(LibraryStageOutcome.FAILED);
      }
    }
  }

  private void drainOneLibraryStage() {
    if (!libraryValidationEnabled || libraryStageQueue.isEmpty()) {
      return;
    }
    IrisMslLibraryValidator.Readiness readiness =
        libraryValidator.readiness();
    boolean ready = readiness == IrisMslLibraryValidator.Readiness.READY;
    libraryValidationReady.set(ready);
    if (!ready) {
      return;
    }

    LibraryStageJob job = libraryStageQueue.poll();
    if (job == null) {
      return;
    }
    libraryStagesInFlight.incrementAndGet();
    boolean resolved = false;
    try {
      Optional<IrisPipelineCache.VerifiedMslStage> verified =
          cache.readVerifiedMslStage(
              job.key(), job.profile(), job.stage());
      if (verified.isEmpty()) {
        recordLibraryStageFailure(job, "cache-verification-failed");
        resolved = true;
        return;
      }

      IrisPipelineCache.VerifiedMslStage artifact =
          verified.orElseThrow();
      IrisMslLibraryValidator.Result result = libraryValidator.validate(
          artifact.mslUtf8(), artifact.stage());
      switch (result) {
        case COMPILED -> {
          libraryStagesSucceeded.incrementAndGet();
          retainCompiledArtifactIdentity(artifact);
          job.programState().resolve(LibraryStageOutcome.SUCCEEDED);
          resolved = true;
        }
        case UNSUPPORTED -> {
          libraryStagesUnsupported.incrementAndGet();
          libraryValidationLastFailure.set(
              artifactLabel(job.key(), job.stage()) + ": unsupported");
          job.programState().resolve(LibraryStageOutcome.UNSUPPORTED);
          resolved = true;
        }
        case FAILED -> {
          recordLibraryStageFailure(job, "native-compile-failed");
          resolved = true;
        }
        case DEFERRED -> {
          libraryValidationReady.set(false);
          if (!libraryStageQueue.offer(job)) {
            recordLibraryStageFailure(job, "deferred-requeue-failed");
            resolved = true;
          }
        }
      }
    } catch (Exception | LinkageError error) {
      recordLibraryStageFailure(job,
          "validation-" + redactedFailure(error));
      resolved = true;
    } finally {
      if (!resolved && !libraryStageQueue.contains(job)) {
        recordLibraryStageFailure(job, "validation-job-lost");
      }
      libraryStagesInFlight.decrementAndGet();
      refreshLiveLibraryCount();
    }
  }

  private void recordLibraryStageFailure(LibraryStageJob job,
      String reason) {
    libraryStagesFailed.incrementAndGet();
    String failure = artifactLabel(job.key(), job.stage()) + ": " + reason;
    libraryValidationLastFailure.set(failure);
    job.programState().resolve(LibraryStageOutcome.FAILED);
    long warnings = libraryWarningCount.incrementAndGet();
    if (warnings <= 3 || warnings % 32 == 0) {
      MetalLogger.warn(
          "Iris MSL library validation failed open; Iris OpenGL remains active (%s, failure %d)",
          failure, warnings);
    }
  }

  private void refreshLiveLibraryCount() {
    try {
      long liveLibraries = libraryValidator.liveLibraryCount();
      libraryLiveLibraries.set(liveLibraries < 0 ? -1 : liveLibraries);
    } catch (RuntimeException | LinkageError unavailable) {
      libraryLiveLibraries.set(-1);
    }
  }

  private static String artifactLabel(IrisShaderCacheKey key,
      IrisShaderStage stage) {
    return key.sha256().substring(0, 12) + "/" + stage.cacheName();
  }

  private void retainCompiledArtifactIdentity(
      IrisPipelineCache.VerifiedMslStage artifact) {
    String identity = artifact.programKeySha256()
        + "/" + artifact.stage().cacheName()
        + "/" + artifact.mslSha256() + "\n";
    synchronized (compiledArtifactLines) {
      if (compiledArtifactLines.contains(identity)) {
        return;
      }
      if (compiledArtifactLines.size()
          >= compiledArtifactIdentityCapacity) {
        if (compiledArtifactSetComplete.compareAndSet(true, false)) {
          String reason = "compiled-artifact-set-capacity-exceeded";
          libraryValidationLastFailure.set(reason);
          MetalLogger.warn(
              "Iris MSL compiled artifact identity set reached its hard capacity; validation remains fail-open (%s)",
              reason);
        }
        return;
      }
      compiledArtifactLines.add(identity);
    }
  }

  private String compiledArtifactSetSha256() {
    MessageDigest digest = newSha256();
    synchronized (compiledArtifactLines) {
      for (String line : compiledArtifactLines) {
        digest.update(line.getBytes(StandardCharsets.US_ASCII));
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private void retainPipelineStateIdentity(
      IrisPipelineStateCache.VerifiedState state) {
    String identity = state.identityLine();
    synchronized (pipelineStateIdentityLines) {
      if (pipelineStateIdentityLines.contains(identity)) {
        return;
      }
      if (pipelineStateIdentityLines.size()
          >= DEFAULT_PIPELINE_STATE_IDENTITY_CAPACITY) {
        pipelineStateSetComplete.set(false);
        pipelineStateLastFailure.compareAndSet("",
            "pipeline-state-identity-capacity-exceeded");
        return;
      }
      pipelineStateIdentityLines.add(identity);
    }
  }

  private String pipelineStateSetSha256() {
    MessageDigest digest = newSha256();
    synchronized (pipelineStateIdentityLines) {
      for (String line : pipelineStateIdentityLines) {
        digest.update(line.getBytes(StandardCharsets.US_ASCII));
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String emptyPipelineStateSetSha256() {
    return HexFormat.of().formatHex(newSha256().digest());
  }

  private static String emptyCompiledArtifactSetSha256() {
    return HexFormat.of().formatHex(newSha256().digest());
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "JVM does not provide SHA-256", impossible);
    }
  }

  Status snapshot() {
    return new Status(true, running.get(), captureQueue.size(),
        captureQueue.rejectedPrograms(), IrisShaderCapture.captureFailures(),
        attempted.get(), translated.get(), cacheHits.get(), failed.get(),
        cacheRoot, lastFailure.get(),
        libraryValidationEnabled, libraryValidationReady.get(),
        libraryProgramsAttempted.get(), libraryProgramsSucceeded.get(),
        libraryProgramsUnsupported.get(), libraryProgramsFailed.get(),
        libraryStagesAttempted.get(), libraryStagesSucceeded.get(),
        libraryStagesUnsupported.get(), libraryStagesFailed.get(),
        libraryStageQueue.size(), libraryStagesInFlight.get(),
        libraryStagesFromTranslation.get(), libraryStagesFromCache.get(),
        libraryStagesRejected.get(), libraryLiveLibraries.get(),
        compiledArtifactSetComplete.get(), compiledArtifactSetSha256(),
        libraryValidationLastFailure.get(),
        pipelineStateCapture.drawsObserved(),
        pipelineStateCapture.dispatchesObserved(),
        pipelineStateCapture.variantsAccepted(),
        pipelineStateCapture.variantsRejected(),
        pipelineStateCapture.incompleteVariants(),
        pipelineStateCapture.queued(),
        pipelineStatesAttempted.get(), pipelineStatesSucceeded.get(),
        pipelineStateCacheHits.get(), pipelineStatesUnsupported.get(),
        pipelineStateUnsupportedReasons.size(),
        pipelineStateUnsupportedReasons.complete(),
        pipelineStateUnsupportedReasons.sha256(),
        pipelineStateUnsupportedReasons.summary(),
        pipelineStatesFailed.get(), pipelineStatesExecutionBlocked.get(),
        pipelineStateIdentityLines.size(),
        pipelineStateSetComplete.get(), pipelineStateSetSha256(),
        pipelineStateLastFailure.get());
  }

  private static String redactedFailure(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName();
  }

  @Override
  public void close() {
    running.set(false);
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        lastFailure.compareAndSet("", "shutdown-timeout");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      lastFailure.compareAndSet("", "shutdown-interrupted");
    }
    libraryStageQueue.clear();
    libraryStagesInFlight.set(0);
    ACTIVE.compareAndSet(this, null);
  }

  private enum LibraryStageSource {
    TRANSLATION,
    CACHE
  }

  private enum LibraryStageOutcome {
    SUCCEEDED,
    UNSUPPORTED,
    FAILED
  }

  private final class LibraryProgramState {
    private final AtomicInteger remainingStages;
    private final AtomicBoolean unsupported = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean finalized = new AtomicBoolean();

    private LibraryProgramState(int stageCount) {
      remainingStages = new AtomicInteger(stageCount);
    }

    private void resolve(LibraryStageOutcome outcome) {
      if (outcome == LibraryStageOutcome.FAILED) {
        failed.set(true);
      } else if (outcome == LibraryStageOutcome.UNSUPPORTED) {
        unsupported.set(true);
      }
      int remaining = remainingStages.decrementAndGet();
      if (remaining < 0) {
        throw new IllegalStateException(
            "library validation stage resolved more than once");
      }
      if (remaining != 0 || !finalized.compareAndSet(false, true)) {
        return;
      }
      if (failed.get()) {
        libraryProgramsFailed.incrementAndGet();
      } else if (unsupported.get()) {
        libraryProgramsUnsupported.incrementAndGet();
      } else {
        libraryProgramsSucceeded.incrementAndGet();
      }
    }
  }

  private record LibraryStageJob(IrisShaderCacheKey key,
                                 IrisTranslationProfile profile,
                                 IrisShaderStage stage,
                                 LibraryProgramState programState) {
    private LibraryStageJob {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(profile, "profile");
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(programState, "programState");
    }
  }

  public record Status(boolean enabled, boolean running, int queued,
                       long rejected, long captureFailures,
                       long attempted, long translated, long cacheHits,
                       long failed, String cacheRoot, String lastFailure,
                       boolean libraryValidationEnabled,
                       boolean libraryValidationReady,
                       long libraryProgramsAttempted,
                       long libraryProgramsSucceeded,
                       long libraryProgramsUnsupported,
                       long libraryProgramsFailed,
                       long libraryStagesAttempted,
                       long libraryStagesSucceeded,
                       long libraryStagesUnsupported,
                       long libraryStagesFailed,
                       int libraryStagesPending,
                       int libraryStagesInFlight,
                       long libraryStagesFromTranslation,
                       long libraryStagesFromCache,
                       long libraryStagesRejected,
                       long libraryLiveLibraries,
                       boolean compiledArtifactSetComplete,
                       String compiledArtifactSetSha256,
                       String libraryValidationLastFailure,
                       long pipelineDrawsObserved,
                       long pipelineDispatchesObserved,
                       long pipelineVariantsAccepted,
                       long pipelineVariantsRejected,
                       long pipelineIncompleteVariants,
                       int pipelineStatesPending,
                       long pipelineStatesAttempted,
                       long pipelineStatesSucceeded,
                       long pipelineStateCacheHits,
                       long pipelineStatesUnsupported,
                       int pipelineStateUnsupportedReasonCount,
                       boolean pipelineStateUnsupportedReasonSetComplete,
                       String pipelineStateUnsupportedReasonSetSha256,
                       String pipelineStateUnsupportedReasonSummary,
                       long pipelineStatesFailed,
                       long pipelineStatesExecutionBlocked,
                       int pipelineStateIdentityCount,
                       boolean pipelineStateSetComplete,
                       String pipelineStateSetSha256,
                       String pipelineStateLastFailure) {
    public Status {
      Objects.requireNonNull(cacheRoot, "cacheRoot");
      Objects.requireNonNull(lastFailure, "lastFailure");
      Objects.requireNonNull(
          compiledArtifactSetSha256, "compiledArtifactSetSha256");
      Objects.requireNonNull(
          libraryValidationLastFailure, "libraryValidationLastFailure");
      Objects.requireNonNull(pipelineStateSetSha256,
          "pipelineStateSetSha256");
      Objects.requireNonNull(pipelineStateLastFailure,
          "pipelineStateLastFailure");
      Objects.requireNonNull(pipelineStateUnsupportedReasonSetSha256,
          "pipelineStateUnsupportedReasonSetSha256");
      Objects.requireNonNull(pipelineStateUnsupportedReasonSummary,
          "pipelineStateUnsupportedReasonSummary");
    }

    /**
     * Gate for advancing beyond validation-only work. It can become true only
     * when every discovered stage compiled, every program succeeded, and the
     * native leak guard is zero.
     */
    public boolean libraryValidationComplete() {
      return libraryValidationEnabled
          && libraryValidationReady
          && libraryProgramsAttempted > 0
          && libraryProgramsAttempted == libraryProgramsSucceeded
          && libraryProgramsUnsupported == 0
          && libraryProgramsFailed == 0
          && libraryStagesAttempted > 0
          && libraryStagesAttempted == libraryStagesSucceeded
          && libraryStagesUnsupported == 0
          && libraryStagesFailed == 0
          && libraryStagesPending == 0
          && libraryStagesInFlight == 0
          && libraryStagesRejected == 0
          && libraryLiveLibraries == 0
          && compiledArtifactSetComplete;
    }

    /** Gate proving that every observed Iris operation has a cacheable key. */
    public boolean pipelineStateCaptureComplete() {
      return pipelineVariantsAccepted > 0
          && pipelineVariantsRejected == 0
          && pipelineIncompleteVariants == 0
          && pipelineStatesPending == 0
          && pipelineStatesAttempted == pipelineVariantsAccepted
          && pipelineStatesAttempted == pipelineStatesSucceeded
          && pipelineStatesUnsupported == 0
          && pipelineStatesFailed == 0
          && pipelineStateSetComplete;
    }
  }

  /**
   * A deterministic bounded view of distinct fail-open reasons.
   *
   * <p>If the capacity is exceeded, the lexicographically smallest values are
   * retained. This makes the summary and digest independent of observation
   * order while the completeness bit prevents a truncated set from being
   * mistaken for the full diagnostic.</p>
   */
  static final class BoundedReasonSet {
    private static final int MAX_REASON_LENGTH = 96;

    private final int capacity;
    private final ConcurrentSkipListSet<String> reasons =
        new ConcurrentSkipListSet<>();
    private boolean complete = true;

    BoundedReasonSet(int capacity) {
      if (capacity <= 0) {
        throw new IllegalArgumentException("capacity must be positive");
      }
      this.capacity = capacity;
    }

    void add(String reason) {
      String normalized = normalizeReason(reason);
      synchronized (reasons) {
        if (!reasons.add(normalized)) {
          return;
        }
        if (reasons.size() > capacity) {
          reasons.pollLast();
          complete = false;
        }
      }
    }

    int size() {
      synchronized (reasons) {
        return reasons.size();
      }
    }

    boolean complete() {
      synchronized (reasons) {
        return complete;
      }
    }

    String summary() {
      synchronized (reasons) {
        return String.join(",", reasons);
      }
    }

    String sha256() {
      MessageDigest digest = newSha256();
      synchronized (reasons) {
        for (String reason : reasons) {
          digest.update(reason.getBytes(StandardCharsets.US_ASCII));
          digest.update((byte) '\n');
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    }

    private static String normalizeReason(String reason) {
      Objects.requireNonNull(reason, "reason");
      String normalized = reason.trim().replaceAll(
          "[^A-Za-z0-9._:/-]", "_");
      if (normalized.isEmpty()) {
        normalized = "unknown";
      }
      if (normalized.length() > MAX_REASON_LENGTH) {
        normalized = normalized.substring(0, MAX_REASON_LENGTH);
      }
      return normalized;
    }
  }
}

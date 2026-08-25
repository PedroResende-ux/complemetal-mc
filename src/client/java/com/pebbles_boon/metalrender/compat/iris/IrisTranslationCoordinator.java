package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
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
  public static final String CUTOVER_ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalFinalCutover";
  static final String EXACT_JAR_FAST_DRAIN_PROPERTY =
      "metalrender.exactJar.fastIrisDrain";
  private static final int EXACT_JAR_FAST_DRAIN_BATCH = 8;
  static final int DEFAULT_LIBRARY_VALIDATION_QUEUE_CAPACITY = 512;
  static final int DEFAULT_COMPILED_ARTIFACT_IDENTITY_CAPACITY =
      Math.multiplyExact(IrisPipelineCache.DEFAULT_MAX_ENTRIES,
          IrisShaderStage.values().length);
  static final int DEFAULT_PIPELINE_STATE_IDENTITY_CAPACITY = 65_536;
  static final int DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY = 32;
  static final int DEFAULT_RENDER_GRAPH_IDENTITY_CAPACITY = 4_096;
  static final int DEFAULT_METAL_PIPELINE_QUEUE_CAPACITY = 4_096;
  static final int DEFAULT_METAL_PIPELINE_IDENTITY_CAPACITY = 65_536;
  static final int DEFAULT_SHADOW_REPLAY_CANDIDATE_CAPACITY = 4_096;
  static final int DEFAULT_SHADOW_REPLAY_DIAGNOSTIC_CAPACITY = 64;
  static final int DEFAULT_CUTOVER_PIPELINE_LOOKUP_CAPACITY = 65_536;
  static final int DEFAULT_FULL_GRAPH_FRAME_QUEUE_CAPACITY = 2;
  static final int DEFAULT_FULL_GRAPH_PRESENTATION_QUEUE_CAPACITY = 3;
  static final int DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS = 12;
  static final int MINIMUM_FULL_GRAPH_SUCCESS_FRAMES = 3;
  static final long FULL_GRAPH_TELEMETRY_INTERVAL_FRAMES = 600;
  static final IrisVisualParityGate.Thresholds FINAL_VISUAL_PARITY_THRESHOLDS =
      new IrisVisualParityGate.Thresholds(2, 0.001, 0.75, 3, 3);
  private static final long CLIENT_QUIET_PERIOD_NANOS =
      TimeUnit.SECONDS.toNanos(8);

  private static final AtomicReference<IrisTranslationCoordinator> ACTIVE =
      new AtomicReference<>();
  private static final AtomicReference<String> CONFIGURED_CACHE_ROOT =
      new AtomicReference<>("");
  private static final AtomicReference<String> START_FAILURE =
      new AtomicReference<>("");
  private static final java.util.Set<String> EXACT_TEXTURE_ARTIFACTS =
      ConcurrentHashMap.newKeySet();

  private final IrisShaderCaptureQueue captureQueue;
  private final IrisShaderTranslatorBackend backend;
  private final IrisPipelineCache cache;
  private final IrisPipelineStateCapture pipelineStateCapture;
  private final IrisPipelineStateCache pipelineStateCache;
  private final IrisSpecializationStateReader specializationStateReader;
  private final IrisRenderGraphCapture renderGraphCapture;
  private final IrisRenderGraphBuilder renderGraphBuilder;
  private final NativeIrisMetalGraphResources metalGraphResources;
  private final IrisMetalPipelineCompiler metalPipelineCompiler;
  private final ArrayBlockingQueue<MetalPipelineCandidate>
      metalPipelineQueue;
  private final IrisProgramResourceLayoutReader resourceLayoutReader;
  private final IrisMslArgumentLayoutReader mslArgumentLayoutReader;
  private final IrisMslLibraryValidator libraryValidator;
  private final boolean libraryValidationEnabled;
  private final ArrayBlockingQueue<LibraryStageJob> libraryStageQueue;
  private final int compiledArtifactIdentityCapacity;
  private final String cacheRoot;
  private final long quietPeriodNanos;
  private final ScheduledExecutorService executor;
  private final Object acceptanceSnapshotLock = new Object();
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicLong attempted = new AtomicLong();
  private final AtomicLong translated = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicReference<String> lastFailure =
      new AtomicReference<>("");
  private final BoundedReasonSet translationFailureReasons =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
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
  private final java.util.Set<String> resourceProgramsProcessed =
      ConcurrentHashMap.newKeySet();
  private final AtomicLong resourceProgramsAttempted = new AtomicLong();
  private final AtomicLong resourceProgramsSucceeded = new AtomicLong();
  private final AtomicLong resourceProgramsUnsupported = new AtomicLong();
  private final AtomicLong resourceProgramsFailed = new AtomicLong();
  private final AtomicLong resourceStagesReflected = new AtomicLong();
  private final AtomicLong resourceBindingsReflected = new AtomicLong();
  private final ConcurrentSkipListSet<String> resourceLayoutIdentityLines =
      new ConcurrentSkipListSet<>();
  private final ConcurrentHashMap<String, IrisProgramResourceLayout>
      resourceLayouts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, IrisMslArgumentLayout>
      mslArgumentLayouts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> resourceLayoutFailures =
      new ConcurrentHashMap<>();
  private final AtomicBoolean resourceLayoutSetComplete =
      new AtomicBoolean(true);
  private final AtomicReference<String> resourceLayoutLastFailure =
      new AtomicReference<>("");
  private final AtomicLong resourceBindingVariantsAttempted =
      new AtomicLong();
  private final AtomicLong resourceBindingVariantsSucceeded =
      new AtomicLong();
  private final AtomicLong resourceBindingVariantsIncomplete =
      new AtomicLong();
  private final AtomicLong resourceBindingsMatched = new AtomicLong();
  private final BoundedReasonSet resourceBindingIncompleteReasons =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final AtomicLong renderGraphsAttempted = new AtomicLong();
  private final AtomicLong renderGraphsSucceeded = new AtomicLong();
  private final AtomicLong renderGraphsUnsupported = new AtomicLong();
  private final AtomicLong renderGraphsFailed = new AtomicLong();
  private final AtomicLong renderGraphResources = new AtomicLong();
  private final AtomicLong renderGraphNodes = new AtomicLong();
  private final AtomicLong renderGraphEdges = new AtomicLong();
  private final AtomicLong renderGraphBarriers = new AtomicLong();
  private final AtomicLong renderGraphClears = new AtomicLong();
  private final AtomicLong renderGraphTransfers = new AtomicLong();
  private final AtomicLong renderGraphPingPongResources = new AtomicLong();
  private final ConcurrentSkipListSet<String> renderGraphIdentityLines =
      new ConcurrentSkipListSet<>();
  private final ConcurrentSkipListSet<String> renderGraphPhases =
      new ConcurrentSkipListSet<>();
  private final AtomicBoolean renderGraphSetComplete =
      new AtomicBoolean(true);
  private final BoundedReasonSet renderGraphUnsupportedReasons =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final AtomicReference<String> renderGraphLastFailure =
      new AtomicReference<>("");
  private final AtomicLong metalGraphResourcePlansObserved = new AtomicLong();
  private final AtomicLong metalGraphResourcePlansComplete = new AtomicLong();
  private final AtomicLong metalGraphResourcePlansBlocked = new AtomicLong();
  private final AtomicLong metalGraphResourceAllocations = new AtomicLong();
  private final AtomicLong metalGraphResourceNativeAttempts = new AtomicLong();
  private final AtomicLong metalGraphResourceNativeSucceeded = new AtomicLong();
  private final AtomicLong metalGraphResourceNativeFailed = new AtomicLong();
  private final AtomicInteger metalGraphResourceNativeTextureCount =
      new AtomicInteger();
  private final AtomicLong metalGraphResourceNativeTextureBytes =
      new AtomicLong();
  private final BoundedReasonSet metalGraphResourceBlockers =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final AtomicLong shadowPlansObserved = new AtomicLong();
  private final AtomicLong shadowPlansStructurallyComplete = new AtomicLong();
  private final AtomicLong shadowPlansBlocked = new AtomicLong();
  private final AtomicLong shadowExecutionSteps = new AtomicLong();
  private final BoundedReasonSet shadowPlanBlockers =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final AtomicLong shadowBufferPlansObserved = new AtomicLong();
  private final AtomicLong shadowBufferPlansComplete = new AtomicLong();
  private final AtomicLong shadowBufferPlansBlocked = new AtomicLong();
  private final AtomicLong shadowBufferImages = new AtomicLong();
  private final AtomicLong shadowBufferBytes = new AtomicLong();
  private final BoundedReasonSet shadowBufferBlockers =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final AtomicLong shadowArgumentPlansObserved = new AtomicLong();
  private final AtomicLong shadowArgumentPlansComplete = new AtomicLong();
  private final AtomicLong shadowArgumentPlansBlocked = new AtomicLong();
  private final AtomicLong shadowArgumentsResolved = new AtomicLong();
  private final AtomicLong shadowInlineUniformBytes = new AtomicLong();
  private final BoundedReasonSet shadowArgumentBlockers =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final NativeIrisMetalShadowReplayer shadowReplayer =
      new NativeIrisMetalShadowReplayer();
  private final ConcurrentHashMap<String, CompiledMetalPipeline>
      compiledMetalPipelines = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<
      IrisPipelineStateCapture.PipelineLookupKey, String>
      cutoverPipelineIdentities = new ConcurrentHashMap<>();
  private final java.util.Set<String> shadowReplayCandidateKeys =
      ConcurrentHashMap.newKeySet();
  private final java.util.Set<String> shadowReplayDiagnosticKeys =
      ConcurrentHashMap.newKeySet();
  private final java.util.Set<IrisRenderGraph.Phase> shadowReplayPhases =
      ConcurrentHashMap.newKeySet();
  private final AtomicBoolean shadowReplayCandidateSetComplete =
      new AtomicBoolean(true);
  private final AtomicLong shadowReplayDrawsObserved = new AtomicLong();
  private final AtomicLong shadowReplayDrawsReady = new AtomicLong();
  private final AtomicLong shadowReplayDrawsAttempted = new AtomicLong();
  private final AtomicLong shadowReplayDrawsSucceeded = new AtomicLong();
  private final AtomicLong shadowReplayDrawsUnsupported = new AtomicLong();
  private final AtomicLong shadowReplayDrawsFailed = new AtomicLong();
  private final AtomicLong shadowReplayDrawsBlocked = new AtomicLong();
  private final AtomicLong shadowReplayLastColorHash = new AtomicLong();
  private final AtomicInteger shadowReplayLastWidth = new AtomicInteger();
  private final AtomicInteger shadowReplayLastHeight = new AtomicInteger();
  private final BoundedReasonSet shadowReplayBlockers =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final IrisVisualParityGate visualParityGate =
      new IrisVisualParityGate(FINAL_VISUAL_PARITY_THRESHOLDS);
  private final AtomicLong visualParityReplaySequence = new AtomicLong();
  private final AtomicLong visualParitySamplesHandled = new AtomicLong();
  private final AtomicLong visualParityMissingOpenGl = new AtomicLong();
  private final AtomicLong visualParityMissingMetal = new AtomicLong();
  private final AtomicLong visualParityDimensionMismatches = new AtomicLong();
  private final AtomicLong visualParityReplayFailures = new AtomicLong();
  private final BoundedReasonSet visualParityBlockers =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final IrisSelectiveCutoverGate cutoverGate =
      new IrisSelectiveCutoverGate(
          java.util.Set.of(IrisRenderGraph.Phase.FINAL));
  private final AtomicLong cutoverFrameSequence = new AtomicLong();
  private final AtomicLong cutoverDrawsObserved = new AtomicLong();
  private final AtomicLong cutoverDrawsEligible = new AtomicLong();
  private final AtomicLong cutoverMetalAttempts = new AtomicLong();
  private final AtomicLong cutoverMetalSucceeded = new AtomicLong();
  private final AtomicLong cutoverPresentations = new AtomicLong();
  private final AtomicLong cutoverGpuInputTextures = new AtomicLong();
  private final AtomicLong cutoverGpuInputBytes = new AtomicLong();
  private final AtomicLong cutoverCpuInputTextures = new AtomicLong();
  private final AtomicLong cutoverCpuInputBytes = new AtomicLong();
  private final AtomicLong cutoverGpuInputBuffers = new AtomicLong();
  private final AtomicLong cutoverGpuInputBufferBytes = new AtomicLong();
  private final AtomicLong cutoverCpuInputBuffers = new AtomicLong();
  private final AtomicLong cutoverCpuInputBufferBytes = new AtomicLong();
  private final AtomicLong cutoverFailures = new AtomicLong();
  private final AtomicReference<String> cutoverLastFailure =
      new AtomicReference<>("");
  private final BoundedReasonSet cutoverFailureReasons =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final NativeIrisMetalGraphExecutor metalGraphExecutor =
      new NativeIrisMetalGraphExecutor();
  private final ConcurrentLinkedQueue<PreparedMetalGraphFrame>
      preparedMetalGraphFrames = new ConcurrentLinkedQueue<>();
  private final AtomicInteger preparedMetalGraphFrameCount =
      new AtomicInteger();
  private final java.util.Set<Long> initializedMetalGraphTokens =
      ConcurrentHashMap.newKeySet();
  private final AtomicReference<Map<IrisGlStateSnapshot.ResourceHandle,
      MetalGraphTextureReference>> currentMetalGraphTextures =
          new AtomicReference<>(Map.of());
  private final AtomicInteger fullGraphCapturesOutstanding =
      new AtomicInteger();
  private final AtomicLong fullGraphCaptureRequests = new AtomicLong();
  private final AtomicLong fullGraphCaptureAborts = new AtomicLong();
  private final AtomicLong fullGraphFramesObserved = new AtomicLong();
  private final AtomicLong fullGraphFramesPlanned = new AtomicLong();
  private final AtomicLong fullGraphFramesAttempted = new AtomicLong();
  private final AtomicLong fullGraphFramesSucceeded = new AtomicLong();
  private final AtomicLong fullGraphFramesUnsupported = new AtomicLong();
  private final AtomicLong fullGraphFramesFailed = new AtomicLong();
  private final AtomicLong fullGraphOperations = new AtomicLong();
  private final AtomicLong fullGraphDraws = new AtomicLong();
  private final AtomicLong fullGraphClears = new AtomicLong();
  private final AtomicLong fullGraphTransfers = new AtomicLong();
  private final AtomicLong fullGraphBarriers = new AtomicLong();
  private final AtomicLong fullGraphCapturedBytes = new AtomicLong();
  private final AtomicLong fullGraphLastOutputHash = new AtomicLong();
  private final AtomicBoolean fullGraphParityArtifactsWritten =
      new AtomicBoolean();
  private final AtomicBoolean fullGraphValidationEstablished =
      new AtomicBoolean();
  private final AtomicReference<String> fullGraphLastFailure =
      new AtomicReference<>("");
  private final BoundedReasonSet fullGraphBlockers =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final AtomicLong fullGraphOwnershipFrameSequence = new AtomicLong();
  private final AtomicLong fullGraphOwnershipSubmissions = new AtomicLong();
  private final AtomicLong fullGraphOwnershipReady = new AtomicLong();
  private final AtomicLong fullGraphOwnershipFramesArmed = new AtomicLong();
  private final AtomicLong fullGraphOwnershipFramesPresented = new AtomicLong();
  private final AtomicLong fullGraphOwnershipFramesReused = new AtomicLong();
  private final AtomicLong fullGraphOwnershipFramesInvalidated =
      new AtomicLong();
  private final AtomicLong fullGraphOwnershipCommandsSuppressed =
      new AtomicLong();
  private final AtomicLong fullGraphOwnershipFailures = new AtomicLong();
  private final AtomicLong displayPresentationGeneration =
      new AtomicLong(1);
  private final AtomicLong displayTransitionCaptureAbortDeadlineNanos =
      new AtomicLong();
  private final AtomicBoolean fullGraphWorkerSubmissionLogged =
      new AtomicBoolean();
  private final AtomicReference<String> fullGraphOwnershipLastFailure =
      new AtomicReference<>("");
  private final TimingAccumulator fullGraphCaptureTiming =
      new TimingAccumulator();
  private final TimingAccumulator fullGraphPlanTiming =
      new TimingAccumulator();
  private final TimingAccumulator fullGraphSubmitTiming =
      new TimingAccumulator();
  private final TimingAccumulator fullGraphPresentTiming =
      new TimingAccumulator();
  private final ConcurrentLinkedDeque<PendingGraphPresentation>
      inFlightGraphPresentations = new ConcurrentLinkedDeque<>();
  private final ConcurrentLinkedDeque<ReadyGraphPresentation>
      readyGraphPresentations = new ConcurrentLinkedDeque<>();
  private final AtomicInteger graphPresentationBacklog =
      new AtomicInteger();
  private volatile OwnershipFrame ownershipFrame;
  private volatile IrisPipelineStateCapture.PendingState
      lastOwnershipPresentationState;
  private volatile int lastOwnershipPresentationWidth;
  private volatile int lastOwnershipPresentationHeight;
  private final IrisVisualParityGate fullGraphVisualParityGate =
      new IrisVisualParityGate(FINAL_VISUAL_PARITY_THRESHOLDS);
  private final java.util.Set<String> metalPipelineCandidateKeys =
      ConcurrentHashMap.newKeySet();
  private final java.util.Set<String> metalPipelineBlockedKeys =
      ConcurrentHashMap.newKeySet();
  private final AtomicReference<IrisMetalPipelineCompiler.Readiness>
      metalPipelineReadiness = new AtomicReference<>(
          IrisMetalPipelineCompiler.Readiness.DEFERRED);
  private final AtomicLong metalPipelinesAttempted = new AtomicLong();
  private final AtomicLong metalPipelinesCompiled = new AtomicLong();
  private final AtomicLong metalPipelineCacheHits = new AtomicLong();
  private final AtomicLong metalPipelineNewVariantsCompiled =
      new AtomicLong();
  private final AtomicLong metalPipelineKnownArchiveMisses =
      new AtomicLong();
  private final AtomicLong metalPipelinesUnsupported = new AtomicLong();
  private final AtomicLong metalPipelinesFailed = new AtomicLong();
  private final AtomicLong metalPipelineQueueRejected = new AtomicLong();
  private final AtomicBoolean metalPipelineCacheFlushed =
      new AtomicBoolean(false);
  private final AtomicLong metalPipelineFlushFailures = new AtomicLong();
  private final ConcurrentSkipListSet<String> metalPipelineIdentityLines =
      new ConcurrentSkipListSet<>();
  private final AtomicBoolean metalPipelineIdentitySetComplete =
      new AtomicBoolean(true);
  private final BoundedReasonSet metalPipelineFailureReasons =
      new BoundedReasonSet(DEFAULT_PIPELINE_STATE_UNSUPPORTED_REASON_CAPACITY);
  private final AtomicReference<String> metalPipelineLastFailure =
      new AtomicReference<>("");

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
    renderGraphCapture = IrisRenderGraphCapture.global();
    renderGraphBuilder = new IrisRenderGraphBuilder(specializationStateReader);
    metalGraphResources = new NativeIrisMetalGraphResources();
    metalPipelineCompiler = new NativeIrisMetalPipelineCompiler(cacheRoot,
        backend.profile());
    metalPipelineQueue = new ArrayBlockingQueue<>(
        DEFAULT_METAL_PIPELINE_QUEUE_CAPACITY);
    resourceLayoutReader = new IrisProgramResourceLayoutReader(cache,
        backend.profile());
    mslArgumentLayoutReader = new IrisMslArgumentLayoutReader(cache,
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
      Thread thread = new Thread(task, "Complemetal-Iris-Translator");
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
    };
    executor = Executors.newSingleThreadScheduledExecutor(daemonFactory);
  }

  /**
   * Starts the singleton worker only when the packaged stable default or
   * explicit diagnostic overrides enable capture and translation.
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
          0, true, emptyPipelineStateSetSha256(), "",
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
          emptyPipelineStateSetSha256(), "",
          0, 0, 0, 0, 0, 0, 0, true,
          emptyPipelineStateSetSha256(), "",
          0, 0, 0, 0, 0, true, emptyPipelineStateSetSha256(), "");
    }
    return coordinator.snapshot();
  }

  public static RenderGraphStatus renderGraphStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      IrisRenderGraphCapture capture = IrisRenderGraphCapture.global();
      return new RenderGraphStatus(capture.framesStarted(),
          capture.framesCompleted(), capture.framesRejected(),
          capture.queued(), capture.frozen(), 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, "", 0, true,
          emptyPipelineStateSetSha256(), 0, true,
          emptyPipelineStateSetSha256(), "");
    }
    return coordinator.snapshotRenderGraph();
  }

  public static MetalGraphResourceStatus metalGraphResourceStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return new MetalGraphResourceStatus(
          NativeIrisMetalGraphResources.isOptedIn(), false,
          0, 0, 0, 0, 0, 0, 0, 0, 0,
          0, true, emptyPipelineStateSetSha256(), "");
    }
    return coordinator.snapshotMetalGraphResources();
  }

  public static FullGraphStatus fullGraphStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return FullGraphStatus.empty(NativeIrisMetalGraphExecutor.isOptedIn());
    }
    return coordinator.snapshotFullGraph();
  }

  /**
   * Captures the cross-component acceptance counters at one worker boundary.
   * The production ownership path remains live; this only prevents the graph
   * worker from advancing one group of counters halfway through the snapshot.
   */
  public static AcceptanceStatus acceptanceStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return new AcceptanceStatus(status(), renderGraphStatus(),
          metalGraphResourceStatus(), fullGraphStatus(), shadowPlanStatus(),
          metalPipelineCacheStatus(), shadowReplayStatus(),
          visualParityStatus());
    }
    return coordinator.snapshotAcceptance();
  }

  public static MetalPipelineCacheStatus metalPipelineCacheStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      IrisMetalPipelineCompiler.NativeStatus nativeStatus =
          new IrisMetalPipelineCompiler.NativeStatus(0, 0, 0, 0, 0, 0, 0);
      return new MetalPipelineCacheStatus(
          NativeIrisMetalPipelineCompiler.isOptedIn(), "DEFERRED", "",
          0, 0, 0,
          0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
          false, 0L,
          0, true, emptyPipelineStateSetSha256(),
          0, true, emptyPipelineStateSetSha256(), "", nativeStatus);
    }
    return coordinator.snapshotMetalPipelineCache();
  }

  public static ShadowPlanStatus shadowPlanStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return new ShadowPlanStatus(0, 0, 0, 0, 0, true,
          emptyPipelineStateSetSha256(), "");
    }
    return coordinator.snapshotShadowPlan();
  }

  public static ShadowBufferStatus shadowBufferStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return new ShadowBufferStatus(IrisGlBufferMirror.isEnabled(), 0, 0,
          0, 0, 0, 0, true, emptyPipelineStateSetSha256(), "");
    }
    return coordinator.snapshotShadowBuffers();
  }

  public static ShadowArgumentStatus shadowArgumentStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return new ShadowArgumentStatus(IrisGlBufferMirror.isEnabled(), 0, 0,
          0, 0, 0, 0, true, emptyPipelineStateSetSha256(), "");
    }
    return coordinator.snapshotShadowArguments();
  }

  public static ShadowReplayStatus shadowReplayStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return new ShadowReplayStatus(
          NativeIrisMetalShadowReplayer.isOptedIn(), 0, 0, 0, 0, 0, 0, 0,
          0, true, 0, "", "0", 0, 0, 0, true,
          emptyPipelineStateSetSha256(), "");
    }
    return coordinator.snapshotShadowReplay();
  }

  public static VisualParityStatus visualParityStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      IrisVisualParityCapture.Status capture =
          IrisVisualParityCapture.global().status();
      return emptyVisualParityStatus(capture);
    }
    return coordinator.snapshotVisualParity();
  }

  /** Starts one fail-open cutover frame from Iris' real render-frame hook. */
  public static void beginCutoverFrame() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null || !isCutoverOptedIn()) {
      return;
    }
    coordinator.beginCutoverFrameInternal();
  }

  /** Arms one full-graph ownership frame only from a completed IOSurface. */
  public static void beginFullGraphOwnershipFrame() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator != null) {
      coordinator.beginFullGraphOwnershipFrameInternal();
    }
  }

  /** Presents the armed Metal-owned frame after Iris finishes its graph. */
  public static void endFullGraphOwnershipFrame() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator != null) {
      coordinator.endFullGraphOwnershipFrameInternal();
    }
  }

  /**
   * Drops only the display-facing bridge after a monitor, backing-scale or
   * wake transition. Translation, pipeline archives and validated graph state
   * remain reusable; the next completed IOSurface must be promoted before
   * OpenGL suppression can resume.
   */
  public static void invalidateDisplayPresentation(String reason) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator != null) {
      coordinator.invalidateDisplayPresentationInternal(reason);
    }
  }

  /** True means this captured draw belongs exclusively to the Metal graph. */
  public static boolean tryFullGraphCutover(
      IrisPipelineStateCapture.PendingState pending) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    return coordinator != null
        && coordinator.tryFullGraphCutoverInternal(pending);
  }

  /** Suppresses a captured clear/barrier/transfer in an armed ownership frame. */
  public static boolean suppressFullGraphOperation() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    return coordinator != null
        && coordinator.suppressFullGraphOperationInternal();
  }

  /** Cancels an unencodable draw after ownership was already committed. */
  public static boolean suppressUnsupportedFullGraphDraw(String reason) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    return coordinator != null
        && coordinator.suppressUnsupportedFullGraphDrawInternal(reason);
  }

  /** Avoids QA readback/association once the validated production mode starts. */
  static boolean fullGraphOwnershipCaptureActive() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    return coordinator != null && coordinator.productionOwnershipReady();
  }

  /**
   * Resolves a metadata-only input only when this exact generation-qualified
   * GL texture already has initialized persistent Metal storage. A missing or
   * incompatible mapping deliberately falls back to the ordinary GL capture.
   */
  static Optional<IrisGlTextureMirror.TextureSnapshot>
      initializedMetalGraphTextureReference(
      IrisGlStateSnapshot.ResourceHandle handle, long mirrorGeneration,
      int mipLevel, int layer,
      IrisGlTextureMirror.TextureMetadata metadata) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null || handle == null || metadata == null
        || handle.kind() != IrisGlStateSnapshot.ResourceKind.TEXTURE
        || mirrorGeneration <= 0
        || metadata.generation() != mirrorGeneration
        || mipLevel != 0 || layer != 0) {
      return Optional.empty();
    }
    MetalGraphTextureReference reference =
        coordinator.currentMetalGraphTextures.get().get(handle);
    if (reference == null
        || !coordinator.initializedMetalGraphTokens.contains(reference.token())
        || reference.sampleCount() != 1
        || reference.depthOrLayers() != 1
        || reference.mipLevels() <= 0
        || !reference.format().equals(metadata.format())
        || reference.width() != metadata.width()
        || reference.height() != metadata.height()) {
      return Optional.empty();
    }
    return Optional.of(IrisGlTextureMirror.TextureSnapshot
        .fromGraphReference(handle.name(), mirrorGeneration,
            metadata.format(), metadata.width(), metadata.height(),
            metadata.bytesPerPixel(), layer, mipLevel));
  }

  /** Records the render-thread Iris traversal for one full replay frame. */
  static void recordFullGraphCaptureNanos(long elapsedNanos) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator != null) {
      coordinator.fullGraphCaptureTiming.record(elapsedNanos);
    }
  }

  /**
   * Returns a strict reflected capture filter only after the linked program's
   * semantic layout has passed the SPIRV-Cross MSL argument-layout match.
   * Absence deliberately means the render thread must keep the conservative
   * full capture rather than guess a resource binding.
   */
  static Optional<IrisReplayCaptureRequirements> replayCaptureRequirements(
      IrisProgramIdentityRegistry.Registration registration,
      IrisGlResourceBindingSnapshot live) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    IrisProgramIdentityRegistry.ResolvedProgram resolved = registration == null
        ? null : registration.resolved().orElse(null);
    if (coordinator == null || resolved == null) {
      return Optional.empty();
    }
    IrisProgramResourceLayout layout = coordinator.resourceLayouts.get(
        resolved.shaderKey().sha256());
    IrisMslArgumentLayout msl = coordinator.mslArgumentLayouts.get(
        resolved.shaderKey().sha256());
    if (layout == null || msl == null || !msl.semanticallyMatches(layout)) {
      return Optional.empty();
    }
    return IrisReplayCaptureRequirements.resolve(layout, msl, live);
  }

  /** Render-thread query used to retain live FINAL resources only when armed. */
  static boolean cutoverCaptureRequested(IrisRenderGraph.Phase phase,
      IrisProgramIdentityRegistry.Registration registration,
      IrisGlStateSnapshot snapshot) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null || !isCutoverOptedIn()) {
      return false;
    }
    IrisSelectiveCutoverGate.Status status = coordinator.cutoverGate.status();
    return legacyCutoverCaptureEligible(
        NativeIrisMetalGraphExecutor.isOwnershipOptedIn(),
        coordinator.productionOwnershipReady(),
        coordinator.fullGraphCapturesOutstanding.get(), phase, status)
        && coordinator.cutoverPipelineIdentities.containsKey(
            IrisPipelineStateCapture.lookupKey(registration, snapshot));
  }

  static boolean legacyCutoverCaptureEligible(
      boolean fullGraphOwnershipOptedIn, boolean fullGraphOwnershipReady,
      int fullGraphCapturesOutstanding, IrisRenderGraph.Phase phase,
      IrisSelectiveCutoverGate.Status status) {
    return !fullGraphOwnershipOptedIn && !fullGraphOwnershipReady
        && fullGraphCapturesOutstanding == 0
        && phase == IrisRenderGraph.Phase.FINAL && status != null
        && status.currentFrame() >= 0 && !status.frameFallback()
        && (status.mode() == IrisSelectiveCutoverGate.Mode.ARMED
            || status.mode() == IrisSelectiveCutoverGate.Mode.ACTIVE);
  }

  /**
   * Attempts one same-frame FINAL replacement. True means the paired OpenGL
   * draw may be cancelled because Metal execution and framebuffer handoff both
   * completed successfully.
   */
  public static boolean tryFinalCutover(
      IrisPipelineStateCapture.PendingState pending) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null || !isCutoverOptedIn()) {
      return false;
    }
    return coordinator.tryFinalCutoverInternal(pending);
  }

  public static CutoverStatus cutoverStatus() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator == null) {
      return CutoverStatus.empty(isCutoverOptedIn());
    }
    return coordinator.snapshotCutover();
  }

  /**
   * Reserves one bounded frame only after every upstream compiler/pipeline
   * queue is quiet. Called from the Iris render-thread frame hook.
   */
  static boolean reserveFullGraphCapture() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    return coordinator != null
        && coordinator.reserveFullGraphCaptureInternal();
  }

  /** Releases a reservation rejected by the render-thread capture bounds. */
  static void fullGraphCaptureAborted(String reason) {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator != null) {
      boolean ownershipReady = coordinator.productionOwnershipReady();
      String normalized = BoundedReasonSet.normalizeReason(
          reason == null || reason.isBlank()
              ? "graph-frame-capture-aborted" : reason);
      boolean displayTransition =
          coordinator.consumeDisplayTransitionCaptureAbort();
      if (ownershipReady && (displayTransition
          || retryableFullGraphCaptureAbort(normalized))) {
        // A reserved frame aborted before graph planning, so introduce the
        // matching planned terminal outcome before recording its invalidation.
        coordinator.fullGraphFramesPlanned.incrementAndGet();
        coordinator.recordFullGraphOwnershipInvalidation(
            displayTransition ? "display-transition-capture-aborted"
                : normalized);
      } else if (ownershipReady) {
        coordinator.recordFullGraphOwnershipFailure(normalized);
      } else {
        coordinator.recordFullGraphCaptureAbort();
      }
      coordinator.releaseFullGraphCaptureReservation();
    }
  }

  /**
   * Executes at most one worker-prepared graph on the render thread. Native
   * IOSurface and resident-buffer handles are deliberately thread-affine.
   */
  public static void drainPreparedGraphExecution() {
    IrisTranslationCoordinator coordinator = ACTIVE.get();
    if (coordinator != null) {
      coordinator.drainPreparedGraphExecutionInternal();
    }
  }

  private static boolean isOptedIn() {
    return IrisShaderCapture.isEnabled()
        && IrisMetalFeatureFlags.enabled(TRANSLATION_ENABLED_PROPERTY);
  }

  static boolean isLibraryValidationOptedIn() {
    return isOptedIn()
        && IrisMetalFeatureFlags.enabled(
            LIBRARY_VALIDATION_ENABLED_PROPERTY);
  }

  static boolean isCutoverOptedIn() {
    return NativeIrisMetalShadowReplayer.isOptedIn()
        && IrisVisualParityCapture.isOptedIn()
        && IrisMetalFeatureFlags.enabled(CUTOVER_ENABLED_PROPERTY);
  }

  private boolean reserveFullGraphCaptureInternal() {
    boolean ownershipReady = productionOwnershipReady();
    if (!NativeIrisMetalGraphExecutor.isOptedIn() || !running.get()
        || !ownershipReady && fullGraphTerminal()
        || preparedMetalGraphFrameCount.get()
            >= DEFAULT_FULL_GRAPH_FRAME_QUEUE_CAPACITY
        || graphPresentationBacklog.get()
            >= DEFAULT_FULL_GRAPH_PRESENTATION_QUEUE_CAPACITY
        || !fullGraphCaptureReady()) {
      return false;
    }
    int outstanding = fullGraphCapturesOutstanding.get();
    while (outstanding < DEFAULT_FULL_GRAPH_FRAME_QUEUE_CAPACITY) {
      if (fullGraphCapturesOutstanding.compareAndSet(
          outstanding, outstanding + 1)) {
        fullGraphCaptureRequests.incrementAndGet();
        return true;
      }
      outstanding = fullGraphCapturesOutstanding.get();
    }
    return false;
  }

  private void releaseFullGraphCaptureReservation() {
    int previous = fullGraphCapturesOutstanding.getAndUpdate(
        value -> value > 0 ? value - 1 : 0);
    if (previous > 0) {
      return;
    }
    if (NativeIrisMetalGraphExecutor.isOwnershipOptedIn()) {
      recordFullGraphOwnershipFailure("graph-frame-reservation-underflow");
    } else {
      recordFullGraphFailed("graph-frame-reservation-underflow");
    }
  }

  private boolean fullGraphCaptureReady() {
    return captureQueue.size() == 0
        && captureQueue.nanosSinceLastOffer() >= quietPeriodNanos
        && libraryStageQueue.isEmpty()
        && libraryStagesInFlight.get() == 0
        && pipelineStateCapture.queued() == 0
        && metalPipelineQueue.isEmpty()
        && metalPipelineReadiness.get()
            == IrisMetalPipelineCompiler.Readiness.READY
        && !compiledMetalPipelines.isEmpty()
        && hasRequiredRenderGraphCoverage()
        && snapshotVisualParity().validated()
        && metalGraphResources.runtimeAvailable();
  }

  private boolean fullGraphTerminal() {
    return fullGraphValidationEstablished.get()
        || fullGraphFramesUnsupported.get() > 0
        || fullGraphFramesFailed.get() > 0
        || fullGraphFramesAttempted.get()
            >= DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS;
  }

  static boolean fullGraphParityConverged(
      IrisVisualParityGate.Status status) {
    return status != null
        && status.framesCompared() >= MINIMUM_FULL_GRAPH_SUCCESS_FRAMES
        && status.consecutivePasses()
            >= MINIMUM_FULL_GRAPH_SUCCESS_FRAMES;
  }

  private boolean productionOwnershipReady() {
    return NativeIrisMetalGraphExecutor.isOwnershipOptedIn()
        && fullGraphValidationEstablished.get()
        && fullGraphOwnershipFailures.get() == 0;
  }

  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    executor.scheduleWithFixedDelay(this::drainSafely, 0,
        NativeIrisMetalGraphExecutor.isOwnershipOptedIn() ? 2 : 100,
        TimeUnit.MILLISECONDS);
  }

  private void drainSafely() {
    synchronized (acceptanceSnapshotLock) {
      drainSafelyAtWorkerBoundary();
    }
  }

  private void drainSafelyAtWorkerBoundary() {
    try {
      if (captureQueue.nanosSinceLastOffer() < quietPeriodNanos) {
        return;
      }
      int workerBatch = Boolean.getBoolean(EXACT_JAR_FAST_DRAIN_PROPERTY)
          ? EXACT_JAR_FAST_DRAIN_BATCH : 1;
      for (int processed = 0; processed < workerBatch && running.get();
           processed++) {
        drainOneLibraryStage();
      }
      for (int processed = 0; processed < workerBatch && running.get();
           processed++) {
        if (!drainOneMetalPipeline()) {
          break;
        }
      }
      for (int processed = 0; processed < workerBatch && running.get();
           processed++) {
        Optional<IrisShaderCaptureQueue.CapturedProgram> captured =
            captureQueue.poll();
        if (captured.isEmpty()) {
          for (int stateBatch = 0; stateBatch < 16 && running.get();
               stateBatch++) {
            if (!drainOnePipelineState()) {
              break;
            }
          }
          for (int pipelineBatch = 0; pipelineBatch < 16 && running.get();
               pipelineBatch++) {
            if (!drainOneMetalPipeline()) {
              break;
            }
          }
          if (NativeIrisMetalShadowReplayer.isOptedIn()
              && NativeIrisMetalPipelineCompiler.isOptedIn()
              && (!libraryStageQueue.isEmpty()
                  || pipelineStateCapture.queued() != 0
                  || !metalPipelineQueue.isEmpty())) {
            return;
          }
          for (int graphBatch = 0; graphBatch < 4 && running.get();
               graphBatch++) {
            if (!drainOneRenderGraph()) {
              break;
            }
          }
          return;
        }
        process(captured.orElseThrow());
      }
    } catch (OutOfMemoryError resourceLimit) {
      lastFailure.set("OutOfMemoryError");
      translationFailureReasons.add("OutOfMemoryError");
      failed.incrementAndGet();
      running.set(false);
      executor.shutdown();
    } catch (Exception | LinkageError error) {
      recordFailure(error);
    }
  }

  private void process(IrisShaderCaptureQueue.CapturedProgram captured) {
    IrisProgramIdentityRegistry.ResolvedProgram resolved =
        captured.registration().map(registration ->
            IrisProgramIdentityRegistry.global().resolve(registration,
                captured.key(), captured.program().sources().keySet()))
            .orElse(null);
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
          lastFailure.set(stage.cacheName()
              + " unsupported; kept Iris OpenGL fallback");
          translationFailureReasons.add(stage.cacheName()
              + "-unsupported-keep-iris-opengl");
          failed.incrementAndGet();
          return;
        }
      }

      if (cache.lookup(program, backend.profile()).isPresent()) {
        cacheHits.incrementAndGet();
        scheduleLibraryValidation(program,
            IrisShaderCacheKey.from(program, backend.profile()),
            backend.profile(), LibraryStageSource.CACHE);
        reflectResourceLayout(resolved);
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
      reflectResourceLayout(resolved);
    } catch (Exception | LinkageError error) {
      recordFailure(error, program);
    }
  }

  private void reflectResourceLayout(
      IrisProgramIdentityRegistry.ResolvedProgram program) {
    if (program == null) {
      return;
    }
    String shaderKey = program.shaderKey().sha256();
    if (!resourceProgramsProcessed.add(shaderKey)) {
      return;
    }
    resourceProgramsAttempted.incrementAndGet();
    try {
      IrisProgramResourceLayoutReader.Result result =
          resourceLayoutReader.read(program);
      if (result instanceof IrisProgramResourceLayoutReader.Unsupported
          unsupported) {
        resourceProgramsUnsupported.incrementAndGet();
        resourceLayoutLastFailure.set(unsupported.reason());
        resourceLayoutFailures.put(shaderKey, unsupported.reason());
        return;
      }
      IrisProgramResourceLayout layout =
          ((IrisProgramResourceLayoutReader.Complete) result).layout();
      IrisMslArgumentLayoutReader.Result argumentResult =
          mslArgumentLayoutReader.read(program);
      if (argumentResult instanceof IrisMslArgumentLayoutReader.Unsupported
          unsupported) {
        resourceProgramsUnsupported.incrementAndGet();
        String reason = "msl-argument-layout-" + unsupported.reason();
        resourceLayoutLastFailure.set(reason);
        resourceLayoutFailures.put(shaderKey, reason);
        return;
      }
      IrisMslArgumentLayout argumentLayout =
          ((IrisMslArgumentLayoutReader.Complete) argumentResult).layout();
      if (!argumentLayout.semanticallyMatches(layout)) {
        resourceProgramsUnsupported.incrementAndGet();
        String reason = "msl-argument-layout-semantic-mismatch";
        resourceLayoutLastFailure.set(reason);
        resourceLayoutFailures.put(shaderKey, reason);
        return;
      }
      String identity = program.shaderKey().sha256() + '|'
          + layout.key().sha256() + '|'
          + argumentLayout.sha256() + '\n';
      if (resourceLayoutIdentityLines.size()
          >= DEFAULT_PIPELINE_STATE_IDENTITY_CAPACITY
          && !resourceLayoutIdentityLines.contains(identity)) {
        resourceLayoutSetComplete.set(false);
        resourceProgramsFailed.incrementAndGet();
        resourceLayoutLastFailure.set(
            "resource-layout-identity-capacity-exceeded");
        return;
      }
      resourceLayoutIdentityLines.add(identity);
      resourceStagesReflected.addAndGet(layout.stages().size());
      resourceBindingsReflected.addAndGet(layout.resourceCount());
      resourceLayouts.put(shaderKey, layout);
      mslArgumentLayouts.put(shaderKey, argumentLayout);
      resourceProgramsSucceeded.incrementAndGet();
    } catch (Exception | LinkageError error) {
      resourceProgramsFailed.incrementAndGet();
      String reason = "reflection-" + redactedFailure(error);
      resourceLayoutLastFailure.set(reason);
      resourceLayoutFailures.put(shaderKey, reason);
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
      retainCutoverPipelineLookup(captured,
          candidateIdentity(program, stored.key()));
      pipelineStatesSucceeded.incrementAndGet();
      if (stored.cacheHit()) {
        pipelineStateCacheHits.incrementAndGet();
      }
      if (!complete.metalExecutionSupported()) {
        pipelineStatesExecutionBlocked.incrementAndGet();
      }
      retainPipelineStateIdentity(stored.verified());
      resolveResourceBindings(program, captured.resourceBindings());
      scheduleMetalPipeline(program, complete.state(), stored.key(),
          stored.cacheHit(), complete.metalExecutionSupported());
    } catch (Exception | LinkageError error) {
      pipelineStatesFailed.incrementAndGet();
      pipelineStateLastFailure.set(redactedFailure(error));
    }
    return true;
  }

  private boolean drainOneRenderGraph() {
    Optional<IrisRenderGraphCapture.PendingFrame> pending =
        renderGraphCapture.poll();
    if (pending.isEmpty()) {
      return false;
    }
    IrisRenderGraphCapture.PendingFrame capturedFrame =
        pending.orElseThrow();
    long fullGraphPlanStarted = capturedFrame.fullReplayCaptured()
        ? System.nanoTime() : 0;
    boolean fullGraphCaptureTransferred = false;
    renderGraphsAttempted.incrementAndGet();
    try {
      IrisRenderGraphBuilder.Result result =
          renderGraphBuilder.build(capturedFrame);
      if (result instanceof IrisRenderGraphBuilder.Unsupported unsupported) {
        renderGraphsUnsupported.incrementAndGet();
        renderGraphUnsupportedReasons.add(unsupported.reason());
        renderGraphLastFailure.set(unsupported.reason());
        if (capturedFrame.fullReplayCaptured()) {
          if (productionOwnershipReady()) {
            recordFullGraphOwnershipFailure(
                "graph-frame-build-unsupported");
          } else {
            recordFullGraphBlocked("graph-frame-build-unsupported");
          }
          releaseFullGraphCaptureReservation();
        }
        return true;
      }
      IrisRenderGraphBuilder.Complete complete =
          (IrisRenderGraphBuilder.Complete) result;
      IrisRenderGraph graph = complete.graph();
      IrisRenderExecutionPlan executionPlan = complete.executionPlan();
      shadowPlansObserved.incrementAndGet();
      shadowExecutionSteps.addAndGet(executionPlan.steps().size());
      List<String> planBlockers = executionPlan.structuralBlockers();
      if (planBlockers.isEmpty()) {
        shadowPlansStructurallyComplete.incrementAndGet();
      } else {
        shadowPlansBlocked.incrementAndGet();
        planBlockers.forEach(shadowPlanBlockers::add);
      }
      if (executionPlan.bufferCaptureEnabled()) {
        shadowBufferPlansObserved.incrementAndGet();
        shadowBufferImages.addAndGet(executionPlan.capturedBufferImages());
        shadowBufferBytes.addAndGet(executionPlan.capturedBufferBytes());
        List<String> bufferBlockers =
            executionPlan.bufferCaptureBlockers();
        if (bufferBlockers.isEmpty()) {
          shadowBufferPlansComplete.incrementAndGet();
        } else {
          shadowBufferPlansBlocked.incrementAndGet();
          bufferBlockers.forEach(shadowBufferBlockers::add);
        }
        resolveShadowArguments(executionPlan,
            !capturedFrame.fullReplayCaptured());
      }
      MetalGraphResourceResolution resourceResolution =
          recordMetalGraphResources(executionPlan);
      if (capturedFrame.fullReplayCaptured()) {
        if (resourceResolution.resources() == null) {
          if (productionOwnershipReady()) {
            recordFullGraphOwnershipFailure("graph-frame-resource-"
                + resourceResolution.blocker());
          } else {
            recordFullGraphBlocked("graph-frame-resource-"
                + resourceResolution.blocker());
          }
          releaseFullGraphCaptureReservation();
        } else {
          if (prepareFullMetalGraphFrame(capturedFrame, executionPlan,
              resourceResolution.resources())) {
            fullGraphCaptureTransferred = true;
          } else {
            releaseFullGraphCaptureReservation();
          }
        }
      }
      String identity = graph.key().sha256() + '\n';
      if (renderGraphIdentityLines.size()
          >= DEFAULT_RENDER_GRAPH_IDENTITY_CAPACITY
          && !renderGraphIdentityLines.contains(identity)) {
        renderGraphSetComplete.set(false);
        renderGraphsFailed.incrementAndGet();
        renderGraphLastFailure.set("render-graph-identity-capacity-exceeded");
        return true;
      }
      renderGraphIdentityLines.add(identity);
      graph.phases().forEach(phase -> renderGraphPhases.add(phase.name()));
      renderGraphResources.addAndGet(graph.resources().size());
      renderGraphNodes.addAndGet(graph.nodes().size());
      renderGraphEdges.addAndGet(graph.edges().size());
      renderGraphBarriers.addAndGet(graph.barrierCount());
      renderGraphClears.addAndGet(graph.clearCount());
      renderGraphTransfers.addAndGet(graph.transferCount());
      renderGraphPingPongResources.addAndGet(graph.pingPongResourceCount());
      renderGraphsSucceeded.incrementAndGet();
      if (hasRequiredRenderGraphCoverage()
          && (!IrisVisualParityCapture.isOptedIn()
              || visualParityTerminal())
          && (!NativeIrisMetalGraphExecutor.isOptedIn()
              || !metalGraphResources.runtimeAvailable()
              || fullGraphTerminal())
          && !NativeIrisMetalGraphExecutor.isOwnershipOptedIn()) {
        renderGraphCapture.freeze();
      }
    } catch (Exception | LinkageError error) {
      renderGraphsFailed.incrementAndGet();
      renderGraphLastFailure.set(redactedFailure(error));
      if (capturedFrame.fullReplayCaptured()) {
        if (productionOwnershipReady()) {
          recordFullGraphOwnershipFailure(
              "graph-frame-preparation-exception");
        } else {
          recordFullGraphFailed("graph-frame-preparation-exception");
        }
        releaseFullGraphCaptureReservation();
      }
    } finally {
      if (capturedFrame.fullReplayCaptured()
          && !fullGraphCaptureTransferred) {
        IrisGlTextureGpuHandoff.abandonCapturedSurfaces(
            capturedFrame.fullReplayTextures());
      }
      if (fullGraphPlanStarted != 0) {
        fullGraphPlanTiming.record(System.nanoTime()
            - fullGraphPlanStarted);
      }
    }
    return true;
  }

  private MetalGraphResourceResolution recordMetalGraphResources(
      IrisRenderExecutionPlan plan) {
    if (!NativeIrisMetalGraphResources.isOptedIn()) {
      return MetalGraphResourceResolution.blocked("allocation-disabled");
    }
    metalGraphResourcePlansObserved.incrementAndGet();
    IrisMetalGraphResourcePlan.Result planned =
        IrisMetalGraphResourcePlan.build(plan);
    if (planned instanceof IrisMetalGraphResourcePlan.Unsupported blocked) {
      metalGraphResourcePlansBlocked.incrementAndGet();
      metalGraphResourceBlockers.add(blocked.reason());
      MetalLogger.warn("Iris Metal graph resource plan blocked: %s",
          blocked.reason());
      return MetalGraphResourceResolution.blocked(
          "plan-" + blocked.reason());
    }
    IrisMetalGraphResourcePlan resourcePlan =
        ((IrisMetalGraphResourcePlan.Complete) planned).plan();
    metalGraphResourcePlansComplete.incrementAndGet();
    metalGraphResourceAllocations.addAndGet(
        resourcePlan.allocations().size());
    if (!metalGraphResources.runtimeAvailable()) {
      return MetalGraphResourceResolution.blocked("runtime-unavailable");
    }
    metalGraphResourceNativeAttempts.incrementAndGet();
    NativeIrisMetalGraphResources.Result allocated =
        metalGraphResources.ensure(resourcePlan);
    if (allocated instanceof NativeIrisMetalGraphResources.Unsupported failed) {
      metalGraphResourceNativeFailed.incrementAndGet();
      metalGraphResourceBlockers.add(failed.reason());
      MetalLogger.warn("Iris Metal graph resource allocation blocked: %s",
          failed.reason());
      return MetalGraphResourceResolution.blocked(
          "native-" + failed.reason());
    }
    NativeIrisMetalGraphResources.Complete complete =
        (NativeIrisMetalGraphResources.Complete) allocated;
    metalGraphResourceNativeSucceeded.incrementAndGet();
    metalGraphResourceNativeTextureCount.set(complete.nativeTextureCount());
    metalGraphResourceNativeTextureBytes.set(complete.nativeTextureBytes());
    recordMetalGraphTextureReferences(plan, complete.bindings());
    return MetalGraphResourceResolution.complete(complete);
  }

  private void recordMetalGraphTextureReferences(
      IrisRenderExecutionPlan plan,
      List<NativeIrisMetalGraphResources.Binding> bindings) {
    java.util.HashMap<Integer, Long> tokensByResource =
        new java.util.HashMap<>();
    for (NativeIrisMetalGraphResources.Binding binding : bindings) {
      tokensByResource.put(binding.resourceId(), binding.token());
    }
    // A resize can replace a texture behind the same generation-qualified GL
    // handle while one older frame is still queued. Old initialization tokens
    // must not accumulate or make the replacement look initialized.
    initializedMetalGraphTokens.retainAll(tokensByResource.values());
    java.util.HashMap<IrisGlStateSnapshot.ResourceHandle,
        MetalGraphTextureReference> references = new java.util.HashMap<>();
    for (IrisRenderExecutionPlan.ResourceBinding binding
        : plan.resourceBindings()) {
      Long token = tokensByResource.get(binding.resourceId());
      if (token == null
          || binding.handle().kind()
              != IrisGlStateSnapshot.ResourceKind.TEXTURE
          || binding.resourceId() < 0
          || binding.resourceId() >= plan.graph().resources().size()) {
        continue;
      }
      IrisRenderGraph.Resource resource = plan.graph().resources().get(
          binding.resourceId());
      if (resource.kind() != IrisRenderGraph.ResourceKind.TEXTURE) {
        continue;
      }
      references.put(binding.handle(), new MetalGraphTextureReference(token,
          resource.format(), resource.sampleCount(), resource.width(),
          resource.height(), resource.depthOrLayers(),
          resource.mipLevels()));
    }
    currentMetalGraphTextures.set(Map.copyOf(references));
  }

  private boolean prepareFullMetalGraphFrame(
      IrisRenderGraphCapture.PendingFrame capturedFrame,
      IrisRenderExecutionPlan plan,
      NativeIrisMetalGraphResources.Complete nativeResources) {
    fullGraphFramesObserved.incrementAndGet();
    fullGraphCapturedBytes.addAndGet(capturedFrame.fullReplayBytes());
    if (!NativeIrisMetalGraphExecutor.isOptedIn()) {
      recordFullGraphBlocked("graph-frame-execution-disabled");
      return false;
    }
    IrisMetalGraphFramePlanner.Result result =
        IrisMetalGraphFramePlanner.build(plan, nativeResources.bindings(),
            java.util.Set.copyOf(initializedMetalGraphTokens),
            (pipeline, width, height) -> resolveFullGraphDraw(plan,
                pipeline, width, height));
    if (result instanceof IrisMetalGraphFramePlanner.Unsupported blocked) {
      if (productionOwnershipReady()) {
        if (recoverableOwnershipInvalidation(blocked.reason())) {
          // This frame reached graph planning but was invalidated before a
          // packet could be queued. Count it as a planned terminal outcome so
          // ownership accounting remains: planned = attempted + submitted +
          // invalidated + pending. Native/queued invalidations are already
          // counted when their packet is queued.
          fullGraphFramesPlanned.incrementAndGet();
          recordFullGraphOwnershipInvalidation(blocked.reason());
        } else {
          recordFullGraphOwnershipFailure(blocked.reason());
        }
      } else {
        recordFullGraphBlocked(blocked.reason());
      }
      return false;
    }
    IrisMetalGraphFramePlanner.Complete complete =
        (IrisMetalGraphFramePlanner.Complete) result;
    if (complete.readbackResourceId()
        == IrisMetalGraphFramePacketEncoder.NO_READBACK) {
      if (productionOwnershipReady()) {
        recordFullGraphOwnershipFailure(
            "graph-frame-presentation-unavailable");
      } else {
        recordFullGraphBlocked("graph-frame-readback-unavailable");
      }
      return false;
    }
    IrisRenderGraph.Resource output = plan.graph().resources().get(
        complete.readbackResourceId());
    if (productionOwnershipReady()) {
      if (!"rgba8-unorm".equals(output.format())
          || output.sampleCount() != 1) {
        recordFullGraphOwnershipFailure(
            "graph-frame-rgba8-presentation-required");
        return false;
      }
      IrisPipelineStateCapture.PendingState presentationState =
          fullGraphPresentationState(plan, complete.readbackResourceId());
      if (presentationState == null) {
        recordFullGraphOwnershipFailure(
            "graph-frame-presentation-state-unavailable");
        return false;
      }
      IrisMetalGraphFramePacketEncoder.Frame presentationFrame =
          complete.frame().asPresentation(complete.readbackResourceId());
      if (graphPresentationBacklog.get()
          >= DEFAULT_FULL_GRAPH_PRESENTATION_QUEUE_CAPACITY) {
        // The previously completed surface remains reusable while the GPU
        // queue drains. This is normal bounded back-pressure, not a graph
        // ownership failure.
        IrisGlTextureGpuHandoff.abandonCapturedSurfaces(
            capturedFrame.fullReplayTextures());
        releaseFullGraphCaptureReservation();
        return true;
      }
      IrisMetalGraphFramePacketEncoder.DirectPacket encodedPacket;
      try {
        encodedPacket = IrisMetalGraphFramePacketEncoder.encodeDirect(
            presentationFrame);
      } catch (RuntimeException failure) {
        recordFullGraphOwnershipFailure("graph-frame-packet-encode-failed");
        return false;
      }
      PreparedMetalGraphFrame prepared;
      try {
        prepared = new PreparedMetalGraphFrame(
            presentationFrame, nativeResources.bindings(),
            complete.writtenResourceIds(), null, presentationState,
            encodedPacket, displayPresentationGeneration.get(),
            capturedFrame.fullReplayTextures(),
            PreparedMetalGraphFrame.Mode.PRESENTATION);
      } catch (RuntimeException failure) {
        encodedPacket.close();
        recordFullGraphOwnershipFailure("graph-frame-prepare-failed");
        return false;
      }
      if (!complete.renderThreadSubmissionRequired()) {
        fullGraphFramesPlanned.incrementAndGet();
        submitFullGraphPresentationOnWorker(prepared);
        return true;
      }
      int queuedFrames = preparedMetalGraphFrameCount.incrementAndGet();
      if (queuedFrames > DEFAULT_FULL_GRAPH_FRAME_QUEUE_CAPACITY) {
        preparedMetalGraphFrameCount.decrementAndGet();
        encodedPacket.close();
        recordFullGraphOwnershipFailure("graph-frame-execution-queue-full");
        return false;
      }
      // A shared GL IOSurface input still needs the current CGL thread for its
      // fence transition. Keep only that uncommon frame on the render path.
      preparedMetalGraphFrames.offer(prepared);
      fullGraphFramesPlanned.incrementAndGet();
      return true;
    }
    boolean diagnosticReadback = Integer.getInteger(
        "metalrender.exactJar.diagnosticGraphReadbackNode", -1) >= 0;
    int diagnosticMipLevel = Math.max(0, Integer.getInteger(
        "metalrender.exactJar.diagnosticGraphReadbackMipLevel", -1));
    if (!fullGraphReadbackFormatSupported(output.format(),
            diagnosticReadback)
        || output.sampleCount() != 1) {
      recordFullGraphBlocked("graph-frame-rgba8-readback-required");
      return false;
    }
    IrisGlStateSnapshot.ResourceHandle outputHandle = plan.resourceBindings()
        .stream().filter(binding -> binding.resourceId()
            == complete.readbackResourceId())
        .map(IrisRenderExecutionPlan.ResourceBinding::handle)
        .findFirst().orElse(null);
    Optional<IrisVisualParityCapture.CapturedFrame> openGlFrame =
        outputHandle == null ? Optional.empty()
            : capturedFrame.finalOutputCaptures().stream()
                .filter(capture -> capture.texture().equals(outputHandle))
                .map(IrisRenderGraphCapture.FinalOutputCapture::frame)
                .findFirst();
    int expectedReadbackWidth = Math.max(1,
        output.width() >> diagnosticMipLevel);
    int expectedReadbackHeight = Math.max(1,
        output.height() >> diagnosticMipLevel);
    if (System.getProperty("metalrender.exactJar.expectedPath") != null) {
      String candidates = capturedFrame.finalOutputCaptures().stream()
          .map(capture -> "t" + capture.texture().name() + "/g"
              + capture.texture().generation() + '='
              + summarizeRgba8(capture.frame().rgba8()))
          .collect(java.util.stream.Collectors.joining(";"));
      MetalLogger.info("exact-JAR FULL graph output routing: resource=%d "
              + "texture=%s format=%s/%dx%d writer=%s candidates=%s",
          complete.readbackResourceId(), outputHandle == null ? "missing"
              : "t" + outputHandle.name() + "/g"
                  + outputHandle.generation(),
          output.format(), expectedReadbackWidth, expectedReadbackHeight,
          describeFullGraphWriter(plan, complete.readbackResourceId()),
          candidates.isEmpty() ? "none" : candidates);
    }
    if (openGlFrame.isEmpty()
        || openGlFrame.orElseThrow().width() != expectedReadbackWidth
        || openGlFrame.orElseThrow().height() != expectedReadbackHeight) {
      recordFullGraphBlocked("graph-frame-opengl-parity-unavailable");
      return false;
    }
    int queuedFrames = preparedMetalGraphFrameCount.incrementAndGet();
    if (queuedFrames > DEFAULT_FULL_GRAPH_FRAME_QUEUE_CAPACITY) {
      preparedMetalGraphFrameCount.decrementAndGet();
      recordFullGraphBlocked("graph-frame-execution-queue-full");
      return false;
    }
    preparedMetalGraphFrames.offer(new PreparedMetalGraphFrame(
        complete.frame(), nativeResources.bindings(),
        complete.writtenResourceIds(), openGlFrame.orElseThrow(), null, null,
        displayPresentationGeneration.get(),
        capturedFrame.fullReplayTextures(),
        PreparedMetalGraphFrame.Mode.VALIDATION));
    fullGraphFramesPlanned.incrementAndGet();
    return true;
  }

  private static IrisPipelineStateCapture.PendingState
      fullGraphPresentationState(IrisRenderExecutionPlan plan,
          int resourceId) {
    IrisPipelineStateCapture.PendingState result = null;
    for (IrisRenderExecutionPlan.Step step : plan.steps()) {
      if (!(step instanceof IrisRenderExecutionPlan.PipelineStep pipeline)) {
        continue;
      }
      boolean writes = plan.graph().nodes().get(step.nodeId()).resources()
          .stream().anyMatch(use -> use.resourceId() == resourceId
              && use.access().writes());
      if (writes) {
        result = pipeline.pending();
      }
    }
    return result;
  }

  static boolean fullGraphReadbackFormatSupported(String format,
      boolean diagnosticReadback) {
    return "rgba8-unorm".equals(format)
        || diagnosticReadback && "rg11b10-float".equals(format);
  }

  private static String describeFullGraphWriter(
      IrisRenderExecutionPlan plan, int resourceId) {
    String writer = "none";
    for (IrisRenderExecutionPlan.Step step : plan.steps()) {
      List<IrisRenderGraph.ResourceUse> resources;
      if (step instanceof IrisRenderExecutionPlan.PipelineStep pipeline) {
        resources = plan.graph().nodes().get(pipeline.nodeId()).resources();
      } else if (step instanceof IrisRenderExecutionPlan.ClearStep clear) {
        resources = clear.resources();
      } else if (step instanceof IrisRenderExecutionPlan.TransferStep
          transfer) {
        resources = transfer.resources();
      } else {
        continue;
      }
      if (resources.stream().noneMatch(use -> use.resourceId() == resourceId
          && use.access().writes())) {
        continue;
      }
      if (step instanceof IrisRenderExecutionPlan.PipelineStep pipeline) {
        String program = pipeline.pending().registration().resolved()
            .map(value -> value.descriptor().programName())
            .orElse("unresolved");
        writer = pipeline.phase() + ":" + pipeline.kind() + ":"
            + program + ":node" + pipeline.nodeId();
      } else if (step instanceof IrisRenderExecutionPlan.ClearStep clear) {
        writer = clear.phase() + ":CLEAR:"
            + clear.command().valueKind() + ":node" + clear.nodeId();
      } else {
        IrisRenderExecutionPlan.TransferStep transfer =
            (IrisRenderExecutionPlan.TransferStep) step;
        writer = transfer.phase() + ":" + transfer.kind() + ":"
            + transfer.command().getClass().getSimpleName() + ":node"
            + transfer.nodeId();
      }
    }
    return writer;
  }

  private static String exactJarArgumentDiagnostic(
      IrisProgramResourceLayout semantic, IrisMslArgumentLayout msl,
      IrisShadowReplayArgumentTable arguments,
      IrisShadowReplayBufferSnapshot buffers) {
    StringBuilder result = new StringBuilder();
    for (IrisProgramResourceLayout.StageLayout semanticStage
        : semantic.stages()) {
      IrisMslArgumentLayout.StageLayout mslStage = msl.stages().stream()
          .filter(stage -> stage.stage() == semanticStage.stage())
          .findFirst().orElse(null);
      IrisShadowReplayArgumentTable.StageTable boundStage =
          arguments.stages().stream()
              .filter(stage -> stage.stage() == semanticStage.stage())
              .findFirst().orElse(null);
      if (mslStage == null || boundStage == null) {
        continue;
      }
      for (IrisSpirvResourceLayout.ResourceBinding resource
          : semanticStage.layout().resources()) {
        String name = semanticStage.layout()
            .diagnosticName(resource.address()).orElse("unnamed");
        IrisMslArgumentLayout.ArgumentBinding binding = mslStage.bindings()
            .stream().filter(candidate -> candidate.address().equals(
                resource.address())).findFirst().orElse(null);
        if (binding == null) {
          continue;
        }
        IrisShadowReplayArgumentTable.BoundArgument bound =
            boundStage.arguments().stream()
                .filter(candidate -> candidate.argumentBufferIndex()
                    == binding.argumentBufferIndex()
                    && candidate.id() == binding.primaryId())
                .findFirst().orElse(null);
        if (!result.isEmpty()) {
          result.append(';');
        }
        result.append(semanticStage.stage().cacheName()).append(':')
            .append(name).append("@b")
            .append(binding.argumentBufferIndex()).append('i')
            .append(binding.primaryId()).append('=')
            .append(exactJarArgumentValue(bound, buffers));
      }
    }
    return result.isEmpty() ? "none" : result.toString();
  }

  private static String exactJarArgumentValue(
      IrisShadowReplayArgumentTable.BoundArgument bound,
      IrisShadowReplayBufferSnapshot buffers) {
    if (bound == null) {
      return "missing";
    }
    IrisShadowReplayArgumentTable.ArgumentValue value = bound.value();
    if (value instanceof IrisShadowReplayArgumentTable.InlineUniform inline) {
      return "inline" + exactJarWords(inline.bytes(), 16);
    }
    if (value instanceof IrisShadowReplayArgumentTable.BufferImage image) {
      if (image.imageId() < 0 || image.imageId() >= buffers.images().size()) {
        return "buffer-out-of-range";
      }
      IrisShadowReplayBufferSnapshot.BufferImage captured =
          buffers.images().get(image.imageId());
      return captured.shared() ? "buffer-shared/" + captured.byteLength()
          : "buffer" + exactJarWords(captured.bytes(), 48);
    }
    if (value instanceof IrisShadowReplayArgumentTable.TextureImage texture) {
      return "texture-t" + texture.glTexture();
    }
    if (value instanceof IrisShadowReplayArgumentTable.CapturedSampler) {
      return "sampler";
    }
    return value.getClass().getSimpleName();
  }

  private static String exactJarWords(byte[] bytes, int maximum) {
    ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int count = Math.min(maximum, bytes.length / Integer.BYTES);
    StringBuilder result = new StringBuilder();
    result.append('[').append(bytes.length).append(':');
    for (int index = 0; index < count; index++) {
      if (index > 0) {
        result.append(',');
      }
      int bits = input.getInt(index * Integer.BYTES);
      float value = Float.intBitsToFloat(bits);
      if (Float.isFinite(value)) {
        result.append(String.format(java.util.Locale.ROOT, "%.6g", value));
      } else {
        result.append("0x").append(Integer.toHexString(bits));
      }
    }
    if (count < bytes.length / Integer.BYTES) {
      result.append(",...");
    }
    return result.append(']').toString();
  }

  private IrisMetalGraphFramePlanner.DrawResult resolveFullGraphDraw(
      IrisRenderExecutionPlan plan,
      IrisRenderExecutionPlan.PipelineStep pipeline,
      int targetWidth, int targetHeight) {
    IrisPipelineStateCapture.PendingState pending = pipeline.pending();
    IrisProgramIdentityRegistry.ResolvedProgram program = pending
        .registration().resolved().orElse(null);
    if (program == null) {
      return new IrisMetalGraphFramePlanner.UnsupportedDraw(
          "graph-frame-program-identity-unresolved");
    }
    IrisRenderGraph.Node node = plan.graph().nodes().get(pipeline.nodeId());
    String identity = program.shaderKey().sha256() + ':'
        + node.pipelineKeySha256();
    CompiledMetalPipeline compiled = compiledMetalPipelines.get(identity);
    if (compiled == null) {
      return new IrisMetalGraphFramePlanner.UnsupportedDraw(
          metalPipelineBlockedKeys.contains(identity)
              ? "graph-frame-pipeline-execution-blocked"
              : "graph-frame-compiled-pipeline-unavailable");
    }
    String shaderKey = program.shaderKey().sha256();
    IrisProgramResourceLayout semantic = resourceLayouts.get(shaderKey);
    IrisMslArgumentLayout msl = mslArgumentLayouts.get(shaderKey);
    if (semantic == null || msl == null) {
      return new IrisMetalGraphFramePlanner.UnsupportedDraw(
          "graph-frame-argument-layout-unavailable");
    }
    IrisShadowReplayArgumentTable arguments =
        IrisShadowReplayArgumentTable.resolve(semantic, msl,
            pending.resourceBindings(), pending.replayBuffers(),
            pending.replayTextures(), pending.replaySamplers());
    ArrayList<String> blockers = new ArrayList<>();
    pending.replayBuffers().drawBlockers().forEach(
        reason -> addDistinct(blockers, reason));
    arguments.blockers().forEach(reason -> addDistinct(blockers, reason));
    pending.replayTextures().blockers().forEach(
        reason -> addDistinct(blockers, reason));
    pending.replaySamplers().blockers().forEach(
        reason -> addDistinct(blockers, reason));
    if (!pending.replayBuffers().drawComplete()) {
      addDistinct(blockers, "graph-frame-buffer-capture-incomplete");
    }
    if (!pending.replayTextures().complete()) {
      addDistinct(blockers, "graph-frame-texture-capture-incomplete");
    }
    if (!pending.replaySamplers().complete()) {
      addDistinct(blockers, "graph-frame-sampler-capture-incomplete");
    }
    if (!pending.dynamicState().completeFor(
        IrisGlStateSnapshot.Operation.DRAW)) {
      addDistinct(blockers, "graph-frame-dynamic-state-incomplete");
    }
    if (!supportedReplayCommand(pending.command())) {
      addDistinct(blockers, "graph-frame-command-not-replayable");
    }
    if (arguments.stages().stream()
        .flatMap(stage -> stage.arguments().stream())
        .map(IrisShadowReplayArgumentTable.BoundArgument::value)
        .anyMatch(value ->
            value instanceof IrisShadowReplayArgumentTable.CanonicalZeroTexture
                || value instanceof IrisShadowReplayArgumentTable
                    .CanonicalDefaultSampler)) {
      addDistinct(blockers, "graph-frame-canonical-resource-unavailable");
    }
    if (!blockers.isEmpty()) {
      MetalLogger.warn(
          "Iris full Metal graph draw prerequisites blocked: phase=%s program=%s node=%d blockers=%s argumentBlockers=%s textureBlockers=%s textureState=%s samplerState=%s",
          pipeline.phase(), program.descriptor().programName(),
          pipeline.nodeId(), String.join(",", blockers),
          String.join(",", arguments.blockers()),
          String.join(",", pending.replayTextures().blockers()),
          exactJarTextureDiagnostic(pending.resourceBindings(),
              pending.replayTextures()),
          exactJarSamplerDiagnostic(pending.replaySamplers()));
      return new IrisMetalGraphFramePlanner.UnsupportedDraw(
          blockers.getFirst());
    }
    try {
      java.util.Set<Integer> sampledTextureNames = arguments.stages().stream()
          .flatMap(stage -> stage.arguments().stream())
          .map(IrisShadowReplayArgumentTable.BoundArgument::value)
          .filter(IrisShadowReplayArgumentTable.TextureImage.class::isInstance)
          .map(IrisShadowReplayArgumentTable.TextureImage.class::cast)
          .map(IrisShadowReplayArgumentTable.TextureImage::glTexture)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      if (System.getProperty("metalrender.exactJar.expectedPath") != null
          && fullGraphCaptureRequests.get() == 1) {
        MetalLogger.info("exact-JAR FULL graph draw routing: phase=%s "
                + "program=%s node=%d command=%s target=%dx%d "
                + "sampled=%s resources=%s textureState=%s samplerState=%s",
            pipeline.phase(), exactJarProgramDiagnostic(pending),
            pipeline.nodeId(), pending.command().getClass().getSimpleName(),
            targetWidth, targetHeight,
            sampledTextureNames.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")),
            exactJarGraphNodeResources(plan, node),
            exactJarTextureDiagnostic(pending.resourceBindings(),
                pending.replayTextures()),
            exactJarSamplerDiagnostic(pending.replaySamplers()));
        if (program.descriptor().programName().startsWith("hand_")) {
          MetalLogger.info("exact-JAR hand argument data: %s",
              exactJarArgumentDiagnostic(semantic, msl, arguments,
                  pending.replayBuffers()));
        }
      }
      List<IrisShadowReplayBufferSnapshot.BufferImage> requiredBuffers =
          IrisMetalShadowReplayPacketEncoder.requiredBufferImages(
              pending.replayBuffers(), arguments);
      List<IrisGlTextureMirror.TextureSnapshot> requiredTextures =
          sampledTextureNames.stream().sorted().map(name -> {
            IrisGlTextureMirror.TextureSnapshot texture =
                pending.replayTextures().textures().get(name);
            if (texture == null) {
              throw new IllegalArgumentException(
                  "sampled texture snapshot missing");
            }
            return texture;
          }).toList();
      return new IrisMetalGraphFramePlanner.CompleteDraw(
          new IrisMetalGraphFramePlanner.ResolvedDraw(
              compiled.pipelineKey().sha256(), compiled.state(),
              sampledTextureNames, requiredBuffers, requiredTextures,
              (externalTextureNames, externalBufferIndices,
                  externalTextureIndices) ->
                  IrisMetalShadowReplayPacketEncoder.encode(
                      pending.command(), pending.dynamicState(),
                      pending.replayBuffers(), pending.replayTextures(),
                      compiled.vertexBindings(), arguments, targetWidth,
                      targetHeight, externalTextureNames,
                      externalBufferIndices, externalTextureIndices)));
    } catch (IllegalArgumentException | IllegalStateException failure) {
      return new IrisMetalGraphFramePlanner.UnsupportedDraw(
          "graph-frame-draw-packet-invalid");
    }
  }

  private static String exactJarGraphNodeResources(
      IrisRenderExecutionPlan plan, IrisRenderGraph.Node node) {
    return node.resources().stream().map(use -> {
      IrisRenderGraph.Resource resource = plan.graph().resources()
          .get(use.resourceId());
      IrisGlStateSnapshot.ResourceHandle handle = plan.resourceBindings()
          .stream().filter(binding -> binding.resourceId()
              == use.resourceId())
          .map(IrisRenderExecutionPlan.ResourceBinding::handle)
          .findFirst().orElse(null);
      String identity = handle == null ? "unbound"
          : (handle.kind() == IrisGlStateSnapshot.ResourceKind.TEXTURE
              ? "t" : "o") + handle.name() + "/g"
                  + handle.generation();
      return use.access() + ":r" + use.resourceId() + '=' + identity + '/'
          + resource.format();
    }).collect(java.util.stream.Collectors.joining(","));
  }

  private void drainPreparedGraphExecutionInternal() {
    refreshFullGraphPresentation();
    if (inFlightGraphPresentations.size() + readyGraphPresentations.size()
        >= DEFAULT_FULL_GRAPH_PRESENTATION_QUEUE_CAPACITY) {
      return;
    }
    PreparedMetalGraphFrame prepared = preparedMetalGraphFrames.poll();
    if (prepared == null) {
      return;
    }
    preparedMetalGraphFrameCount.decrementAndGet();
    try {
      if (!displayPresentationGenerationMatches(
          prepared.displayPresentationGeneration(),
          displayPresentationGeneration.get())) {
        recordFullGraphOwnershipInvalidation(
            "graph-frame-display-generation-stale");
        return;
      }
      if (prepared.frame().contextGeneration()
          != pipelineStateCapture.tracker().contextGeneration()) {
        if (prepared.mode() == PreparedMetalGraphFrame.Mode.PRESENTATION
            && productionOwnershipReady()) {
          recordFullGraphOwnershipInvalidation(
              "graph-frame-context-generation-stale");
        } else {
          recordFullGraphBlocked("graph-frame-context-generation-stale");
        }
        return;
      }
      if (prepared.mode() == PreparedMetalGraphFrame.Mode.PRESENTATION) {
        submitFullGraphPresentation(prepared);
        return;
      }
      boolean supersededValidation = fullGraphValidationEstablished.get();
      if (!supersededValidation) {
        fullGraphFramesAttempted.incrementAndGet();
      }
      NativeIrisMetalGraphExecutor.Result result =
          metalGraphExecutor.execute(prepared.frame());
      if (supersededValidation) {
        if (result.outcome()
            == NativeIrisMetalGraphExecutor.Outcome.SUCCEEDED) {
          // The second bounded priming capture can already be queued when the
          // preceding sample establishes three-frame parity. Execute it so
          // native IOSurface leases retire, but do not let its later temporal
          // state alter the terminal parity result.
          markInitializedMetalGraphTokens(prepared);
          recordFullGraphOwnershipInvalidation(
              "graph-frame-validation-superseded");
        } else {
          recordFullGraphOwnershipFailure(
              "graph-frame-superseded-execution-failed");
        }
        return;
      }
      if (result.outcome()
          == NativeIrisMetalGraphExecutor.Outcome.SUCCEEDED) {
        // Native success means every encoded write has completed and its
        // persistent Metal resource is now initialized. Preserve that state
        // even when parity has not passed yet; otherwise temporal resources
        // are cleared on every validation attempt and cannot converge to the
        // OpenGL history used as the reference.
        markInitializedMetalGraphTokens(prepared);
        int requiredBytes = Math.toIntExact(Math.multiplyExact(
            (long) prepared.openGlFrame().width()
                * prepared.openGlFrame().height(), 4L));
        byte[] metalPixels = result.rgba8();
        if (metalPixels.length != requiredBytes) {
          recordFullGraphFailed("graph-frame-metal-parity-unavailable");
          return;
        }
        IrisVisualParityGate.Comparison comparison =
            fullGraphVisualParityGate.compare(
                prepared.openGlFrame().rgba8(), metalPixels,
                prepared.openGlFrame().width(),
                prepared.openGlFrame().height());
        if (System.getProperty("metalrender.exactJar.expectedPath") != null) {
          IrisVisualParityGate diagnosticGate = new IrisVisualParityGate(
              new IrisVisualParityGate.Thresholds(2, 1.0, 255.0, 1, 1));
          IrisVisualParityGate.Comparison flipped = diagnosticGate.compare(
              prepared.openGlFrame().rgba8(), flipRgba8Rows(metalPixels,
                  prepared.openGlFrame().width(),
                  prepared.openGlFrame().height()),
              prepared.openGlFrame().width(),
              prepared.openGlFrame().height());
          PixelSummary openGlSummary = summarizeRgba8(
              prepared.openGlFrame().rgba8());
          PixelSummary metalSummary = summarizeRgba8(metalPixels);
          MetalLogger.info("exact-JAR FULL graph visual parity: frame=%d "
                  + "target=%dx%d directRatio=%.8f directRmse=%.8f "
                  + "flippedRatio=%.8f flippedRmse=%.8f maxDelta=%d "
                  + "passed=%s gl=%s metal=%s",
              fullGraphVisualParityGate.status().framesCompared(),
              comparison.width(), comparison.height(),
              comparison.differentPixelRatio(),
              comparison.rootMeanSquareError(),
              flipped.differentPixelRatio(),
              flipped.rootMeanSquareError(),
              comparison.maximumChannelDelta(), comparison.passed(),
              openGlSummary, metalSummary);
          dumpExactFullGraphParity(prepared.openGlFrame().rgba8(),
              metalPixels, comparison.width(), comparison.height());
        }
        if (!comparison.passed()) {
          IrisVisualParityGate.Status parity =
              fullGraphVisualParityGate.status();
          if (fullGraphFramesAttempted.get()
                  >= DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS
              && !fullGraphParityConverged(parity)) {
            recordFullGraphFailed("graph-frame-visual-parity-mismatch");
          } else {
            MetalLogger.info(
                "Iris full Metal parity sample rejected during temporal convergence: frame=%d consecutive=%d ratio=%.8f rmse=%.8f",
                parity.framesCompared(), parity.consecutivePasses(),
                comparison.differentPixelRatio(),
                comparison.rootMeanSquareError());
          }
          return;
        }
        fullGraphFramesSucceeded.incrementAndGet();
        fullGraphOperations.addAndGet(result.steps());
        fullGraphClears.addAndGet(result.clears());
        fullGraphTransfers.addAndGet(result.transfers());
        fullGraphBarriers.addAndGet(result.barriers());
        fullGraphDraws.addAndGet(prepared.drawCount());
        fullGraphLastOutputHash.set(result.outputHash());
        fullGraphLastFailure.set("");
        long succeeded = fullGraphFramesSucceeded.get();
        if (fullGraphParityConverged(fullGraphVisualParityGate.status())
            && fullGraphFramesUnsupported.get() == 0
            && fullGraphFramesFailed.get() == 0
            && fullGraphBlockers.size() == 0
            && fullGraphBlockers.complete()) {
          fullGraphValidationEstablished.set(true);
        }
        MetalLogger.info(
            "Iris full Metal graph frame: success=%d operations=%d draws=%d hash=%s OpenGL=VISIBLE_VALIDATION",
            succeeded, result.steps(), prepared.drawCount(),
            Long.toUnsignedString(result.outputHash()));
      } else if (result.outcome()
          == NativeIrisMetalGraphExecutor.Outcome.UNSUPPORTED) {
        recordFullGraphBlocked(result.reason());
      } else {
        recordFullGraphFailed(result.reason());
      }
    } catch (RuntimeException | LinkageError failure) {
      recordFullGraphFailed("graph-frame-render-thread-exception");
    } finally {
      if (prepared.encodedPacket() != null) {
        prepared.encodedPacket().close();
      }
      IrisGlTextureGpuHandoff.abandonCapturedSurfaces(
          prepared.fullReplayTextures());
      releaseFullGraphCaptureReservation();
    }
  }

  private void refreshFullGraphPresentation() {
    while (!inFlightGraphPresentations.isEmpty()
        && readyGraphPresentations.size()
            < DEFAULT_FULL_GRAPH_PRESENTATION_QUEUE_CAPACITY) {
      PendingGraphPresentation pending = inFlightGraphPresentations.peekFirst();
      if (!displayPresentationGenerationMatches(
          pending.displayPresentationGeneration(),
          displayPresentationGeneration.get())) {
        inFlightGraphPresentations.removeFirst();
        metalGraphExecutor.discardPresentation(pending.token());
        decrementGraphPresentationBacklog();
        recordFullGraphOwnershipInvalidation(
            "graph-frame-display-generation-stale");
        continue;
      }
      NativeIrisMetalGraphExecutor.PresentationStatus status =
          metalGraphExecutor.pollPresentation(pending.token());
      if (status.state()
          == NativeIrisMetalGraphExecutor.PresentationState.PENDING) {
        return;
      }
      inFlightGraphPresentations.removeFirst();
      if (status.state()
          == NativeIrisMetalGraphExecutor.PresentationState.READY) {
        readyGraphPresentations.addLast(new ReadyGraphPresentation(status,
            pending.presentationState(),
            pending.displayPresentationGeneration()));
        fullGraphOwnershipReady.incrementAndGet();
        continue;
      }
      metalGraphExecutor.discardPresentation(pending.token());
      decrementGraphPresentationBacklog();
      recordFullGraphOwnershipFailure(status.reason());
    }
  }

  private void submitFullGraphPresentation(PreparedMetalGraphFrame prepared) {
    long displayGeneration = prepared.displayPresentationGeneration();
    if (!displayPresentationGenerationMatches(displayGeneration,
        displayPresentationGeneration.get())) {
      recordFullGraphOwnershipInvalidation(
          "graph-frame-display-generation-stale");
      return;
    }
    long started = System.nanoTime();
    NativeIrisMetalGraphExecutor.PresentationSubmission submission =
        metalGraphExecutor.submitForPresentation(prepared.frame(),
            prepared.encodedPacket());
    fullGraphSubmitTiming.record(System.nanoTime() - started);
    if (submission.outcome()
        == NativeIrisMetalGraphExecutor.Outcome.SUCCEEDED) {
      if (!displayPresentationGenerationMatches(displayGeneration,
          displayPresentationGeneration.get())) {
        metalGraphExecutor.discardPresentation(submission.token());
        recordFullGraphOwnershipInvalidation(
            "graph-frame-display-generation-stale");
        return;
      }
      markInitializedMetalGraphTokens(prepared);
      PendingGraphPresentation pending = new PendingGraphPresentation(
          submission.token(), prepared.presentationState(),
          displayGeneration);
      graphPresentationBacklog.incrementAndGet();
      inFlightGraphPresentations.addLast(pending);
      if (!displayPresentationGenerationMatches(displayGeneration,
              displayPresentationGeneration.get())
          && inFlightGraphPresentations.remove(pending)) {
        metalGraphExecutor.discardPresentation(submission.token());
        decrementGraphPresentationBacklog();
        recordFullGraphOwnershipInvalidation(
            "graph-frame-display-generation-stale");
        return;
      }
      fullGraphOwnershipSubmissions.incrementAndGet();
      fullGraphOperations.addAndGet(submission.steps());
      fullGraphClears.addAndGet(submission.clears());
      fullGraphTransfers.addAndGet(submission.transfers());
      fullGraphBarriers.addAndGet(submission.barriers());
      fullGraphDraws.addAndGet(prepared.drawCount());
      fullGraphLastOutputHash.set(submission.token());
      fullGraphOwnershipLastFailure.set("");
      return;
    }
    if (submission.outcome()
            == NativeIrisMetalGraphExecutor.Outcome.UNSUPPORTED
        && recoverableOwnershipInvalidation(submission.reason())) {
      recordFullGraphOwnershipInvalidation(submission.reason());
      return;
    }
    recordFullGraphOwnershipFailure(submission.reason());
  }

  private void submitFullGraphPresentationOnWorker(
      PreparedMetalGraphFrame prepared) {
    try {
      if (prepared.frame().contextGeneration()
          != pipelineStateCapture.tracker().contextGeneration()) {
        recordFullGraphOwnershipInvalidation(
            "graph-frame-context-generation-stale");
        return;
      }
      if (fullGraphWorkerSubmissionLogged.compareAndSet(false, true)) {
        MetalLogger.info(
            "Iris full Metal graph submission: translation-worker async token path");
      }
      submitFullGraphPresentation(prepared);
    } catch (RuntimeException | LinkageError failure) {
      recordFullGraphOwnershipFailure(
          "graph-frame-worker-submit-exception");
    } finally {
      prepared.encodedPacket().close();
      IrisGlTextureGpuHandoff.abandonCapturedSurfaces(
          prepared.fullReplayTextures());
      releaseFullGraphCaptureReservation();
    }
  }

  private void decrementGraphPresentationBacklog() {
    int previous = graphPresentationBacklog.getAndUpdate(
        value -> value > 0 ? value - 1 : 0);
    if (previous == 0) {
      recordFullGraphOwnershipFailure(
          "graph-presentation-backlog-underflow");
    }
  }

  static boolean recoverableOwnershipInvalidation(String reason) {
    return "graph-native-resource-generation-stale".equals(reason)
        || "graph-frame-texture-generation-stale".equals(reason)
        || "graph-frame-display-generation-stale".equals(reason);
  }

  static boolean retryableFullGraphCaptureAbort(String reason) {
    return "graph-frame-capture-backpressure".equals(reason);
  }

  static boolean displayPresentationGenerationMatches(long captured,
      long current) {
    return captured > 0 && captured == current;
  }

  private void markInitializedMetalGraphTokens(
      PreparedMetalGraphFrame prepared) {
    initializedMetalGraphTokens.addAll(prepared.writtenTokens());
  }

  private void recordFullGraphBlocked(String reason) {
    long blocked = fullGraphFramesUnsupported.incrementAndGet();
    fullGraphBlockers.add(reason);
    fullGraphLastFailure.set(reason);
    if (blocked <= DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS) {
      MetalLogger.warn("Iris full Metal graph blocked (%d/%d): %s",
          blocked, DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS, reason);
    }
  }

  private void recordFullGraphCaptureAbort() {
    long aborted = fullGraphCaptureAborts.incrementAndGet();
    if (!fullGraphCaptureAbortRetryAllowed(aborted)) {
      recordFullGraphBlocked("graph-frame-capture-aborted");
      return;
    }
    MetalLogger.info(
        "Iris full Metal graph capture aborted (%d/%d); retrying before ownership",
        aborted, DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS);
  }

  static boolean fullGraphCaptureAbortRetryAllowed(long aborted) {
    return aborted > 0 && aborted < DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS;
  }

  private void dumpExactFullGraphParity(byte[] openGl, byte[] metal,
      int width, int height) {
    if (!fullGraphParityArtifactsWritten.compareAndSet(false, true)) {
      return;
    }
    try {
      Path directory = Path.of(System.getProperty("user.dir"));
      writeExactRgbaPng(directory.resolve(
          "metalrender-full-graph-opengl.png"), openGl, width, height,
          null);
      writeExactRgbaPng(directory.resolve(
          "metalrender-full-graph-metal.png"), metal, width, height, null);
      writeExactRgbaPng(directory.resolve(
          "metalrender-full-graph-diff.png"), openGl, width, height, metal);
    } catch (IOException | RuntimeException failure) {
      MetalLogger.warn("exact-JAR FULL graph parity artifact write failed: %s",
          failure.getClass().getSimpleName());
    }
  }

  private static void writeExactRgbaPng(Path path, byte[] first,
      int width, int height, byte[] second) throws IOException {
    BufferedImage image = new BufferedImage(width, height,
        BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      int sourceY = height - 1 - y;
      for (int x = 0; x < width; x++) {
        int offset = (sourceY * width + x) * 4;
        int red = Byte.toUnsignedInt(first[offset]);
        int green = Byte.toUnsignedInt(first[offset + 1]);
        int blue = Byte.toUnsignedInt(first[offset + 2]);
        int alpha = Byte.toUnsignedInt(first[offset + 3]);
        if (second != null) {
          red = Math.min(255, Math.abs(red
              - Byte.toUnsignedInt(second[offset])) * 4);
          green = Math.min(255, Math.abs(green
              - Byte.toUnsignedInt(second[offset + 1])) * 4);
          blue = Math.min(255, Math.abs(blue
              - Byte.toUnsignedInt(second[offset + 2])) * 4);
          alpha = 255;
        }
        image.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
      }
    }
    if (!ImageIO.write(image, "png", path.toFile())) {
      throw new IOException("PNG writer unavailable");
    }
  }

  static void dumpExactTextureArtifact(
      IrisGlTextureMirror.TextureSnapshot snapshot) {
    if (System.getProperty("metalrender.exactJar.expectedPath") == null
        || snapshot == null || snapshot.bytes().length == 0
        || !snapshot.format().equals("rgba8-unorm")
        || snapshot.width() > 64 || snapshot.height() > 64) {
      return;
    }
    String key = snapshot.texture() + "-g" + snapshot.generation();
    if (!EXACT_TEXTURE_ARTIFACTS.add(key)) {
      return;
    }
    try {
      writeExactRgbaPng(Path.of(System.getProperty("user.dir")).resolve(
              "metalrender-input-t" + key + ".png"), snapshot.bytes(),
          snapshot.width(), snapshot.height(), null);
    } catch (IOException | RuntimeException failure) {
      MetalLogger.warn("exact-JAR input texture artifact write failed: %s",
          failure.getClass().getSimpleName());
    }
  }

  private void recordFullGraphFailed(String reason) {
    long failed = fullGraphFramesFailed.incrementAndGet();
    fullGraphBlockers.add(reason);
    fullGraphLastFailure.set(reason);
    if (failed <= DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS) {
      MetalLogger.warn("Iris full Metal graph failed (%d/%d): %s",
          failed, DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS, reason);
    }
  }

  private void recordFullGraphOwnershipFailure(String reason) {
    String normalized = BoundedReasonSet.normalizeReason(
        reason == null || reason.isBlank()
            ? "graph-ownership-unknown" : reason);
    long failed = fullGraphOwnershipFailures.incrementAndGet();
    fullGraphOwnershipLastFailure.set(normalized);
    if (failed <= DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS) {
      MetalLogger.warn("Iris full Metal ownership failure (%d): %s",
          failed, normalized);
    }
  }

  private void recordFullGraphOwnershipInvalidation(String reason) {
    long invalidated = fullGraphOwnershipFramesInvalidated.incrementAndGet();
    if (invalidated <= DEFAULT_FULL_GRAPH_FRAME_ATTEMPTS
        || invalidated % 32 == 0) {
      MetalLogger.info(
          "Iris full Metal ownership frame invalidated (%d): %s; recapturing",
          invalidated, BoundedReasonSet.normalizeReason(reason));
    }
  }

  private void resolveShadowArguments(IrisRenderExecutionPlan plan,
      boolean replayIsolatedDraws) {
    shadowArgumentPlansObserved.incrementAndGet();
    ArrayList<String> blockers = new ArrayList<>();
    long arguments = 0;
    long inlineBytes = 0;
    for (IrisRenderExecutionPlan.Step step : plan.steps()) {
      if (!(step instanceof IrisRenderExecutionPlan.PipelineStep pipeline)) {
        continue;
      }
      if (!pipeline.pending().replayBuffers().captureEnabled()) {
        continue;
      }
      IrisProgramIdentityRegistry.ResolvedProgram program = pipeline.pending()
          .registration().resolved().orElse(null);
      if (program == null) {
        addDistinct(blockers, "program-identity-unresolved");
        recordShadowReplayBlocked(pipeline,
            "program-identity-unresolved");
        continue;
      }
      String shaderKey = program.shaderKey().sha256();
      IrisProgramResourceLayout semantic = resourceLayouts.get(shaderKey);
      IrisMslArgumentLayout msl = mslArgumentLayouts.get(shaderKey);
      if (semantic == null || msl == null) {
        addDistinct(blockers, "argument-layout-unavailable");
        recordShadowReplayBlocked(pipeline, "argument-layout-unavailable");
        continue;
      }
      IrisShadowReplayArgumentTable table =
          IrisShadowReplayArgumentTable.resolve(semantic, msl,
              pipeline.pending().resourceBindings(),
              pipeline.pending().replayBuffers(),
              pipeline.pending().replayTextures(),
              pipeline.pending().replaySamplers());
      table.blockers().forEach(reason -> addDistinct(blockers, reason));
      arguments += table.argumentCount();
      inlineBytes += table.inlineUniformBytes();
      if (replayIsolatedDraws) {
        replayShadowDraw(plan, pipeline, program, table);
      }
    }
    shadowArgumentsResolved.addAndGet(arguments);
    shadowInlineUniformBytes.addAndGet(inlineBytes);
    if (blockers.isEmpty()) {
      shadowArgumentPlansComplete.incrementAndGet();
    } else {
      shadowArgumentPlansBlocked.incrementAndGet();
      blockers.forEach(shadowArgumentBlockers::add);
    }
  }

  private void replayShadowDraw(IrisRenderExecutionPlan plan,
      IrisRenderExecutionPlan.PipelineStep pipeline,
      IrisProgramIdentityRegistry.ResolvedProgram program,
      IrisShadowReplayArgumentTable arguments) {
    if (!NativeIrisMetalShadowReplayer.isOptedIn()
        || pipeline.kind() != IrisRenderGraph.NodeKind.DRAW) {
      return;
    }
    shadowReplayDrawsObserved.incrementAndGet();
    IrisPipelineStateCapture.PendingState pending = pipeline.pending();
    boolean visualParitySample = pipeline.phase()
        == IrisRenderGraph.Phase.FINAL
        && IrisVisualParityCapture.isOptedIn()
        && !visualParityTerminal();
    if (shadowReplayPhases.contains(pipeline.phase())
        && !visualParitySample) {
      if (pipeline.phase() == IrisRenderGraph.Phase.FINAL) {
        IrisVisualParityCapture.global().take(pending);
      }
      return;
    }
    Optional<IrisVisualParityCapture.CapturedFrame> openGlFrame =
        visualParitySample
            ? IrisVisualParityCapture.global().take(pending)
            : Optional.empty();

    IrisRenderGraph.Node node = plan.graph().nodes().get(pipeline.nodeId());
    String identity = program.shaderKey().sha256() + ':'
        + node.pipelineKeySha256();
    CompiledMetalPipeline compiled = compiledMetalPipelines.get(identity);
    ArrayList<String> blockers = new ArrayList<>();
    if (compiled == null) {
      addDistinct(blockers, metalPipelineBlockedKeys.contains(identity)
          ? "metal-pipeline-execution-blocked"
          : "compiled-metal-pipeline-unavailable");
    }
    pending.replayBuffers().drawBlockers().forEach(
        reason -> addDistinct(blockers, reason));
    arguments.blockers().forEach(reason -> addDistinct(blockers, reason));
    if (!pending.replayBuffers().drawComplete()) {
      addDistinct(blockers, "shadow-buffer-capture-incomplete");
    }
    if (!pending.dynamicState().completeFor(
        IrisGlStateSnapshot.Operation.DRAW)) {
      addDistinct(blockers, "dynamic-draw-state-incomplete");
    }
    if (!supportedReplayCommand(pending.command())) {
      addDistinct(blockers, "draw-command-not-replayable");
    }
    TargetExtent target = null;
    if (compiled != null) {
      if (compiled.state().rasterSampleCount() != 1) {
        addDistinct(blockers, "multisample-shadow-replay-unimplemented");
      }
      if (compiled.state().colorAttachments().isEmpty()) {
        addDistinct(blockers, "color-target-required-for-replay-hash");
      }
      target = targetExtent(plan.graph(), node, compiled.state(), blockers);
    }
    if (arguments.stages().stream()
        .flatMap(stage -> stage.arguments().stream())
        .map(IrisShadowReplayArgumentTable.BoundArgument::value)
        .anyMatch(value ->
            value instanceof IrisShadowReplayArgumentTable.CanonicalZeroTexture
                || value instanceof IrisShadowReplayArgumentTable
                    .CanonicalDefaultSampler)) {
      addDistinct(blockers, "canonical-resource-type-unavailable");
    }
    if (!blockers.isEmpty()) {
      shadowReplayDrawsBlocked.incrementAndGet();
      if (visualParitySample) {
        recordVisualParityReplayFailure(openGlFrame,
            "visual-parity-metal-replay-blocked");
      }
      logExactJarReplayBlocker(pipeline, identity, compiled != null,
          arguments, blockers);
      return;
    }

    String replayIdentity = pipeline.phase().name() + ':' + identity;
    if (visualParitySample) {
      replayIdentity += ":visual-"
          + visualParityReplaySequence.getAndIncrement();
    }
    synchronized (shadowReplayCandidateKeys) {
      if (shadowReplayCandidateKeys.contains(replayIdentity)) {
        return;
      }
      if (shadowReplayCandidateKeys.size()
          >= DEFAULT_SHADOW_REPLAY_CANDIDATE_CAPACITY) {
        shadowReplayCandidateSetComplete.set(false);
        shadowReplayBlockers.add("shadow-replay-candidate-capacity-exceeded");
        return;
      }
      shadowReplayCandidateKeys.add(replayIdentity);
    }

    shadowReplayDrawsReady.incrementAndGet();
    shadowReplayDrawsAttempted.incrementAndGet();
    try {
      byte[] packet = IrisMetalShadowReplayPacketEncoder.encode(pending,
          compiled.vertexBindings(), arguments, target.width(),
          target.height());
      NativeIrisMetalShadowReplayer.Result result = shadowReplayer.replay(
          compiled.pipelineKey().sha256(), packet, target.width(),
          target.height());
      switch (result.outcome()) {
        case SUCCEEDED -> {
          shadowReplayDrawsSucceeded.incrementAndGet();
          shadowReplayPhases.add(pipeline.phase());
          shadowReplayLastColorHash.set(result.colorHash());
          shadowReplayLastWidth.set(result.width());
          shadowReplayLastHeight.set(result.height());
          if (visualParitySample) {
            compareVisualParity(openGlFrame, result);
          }
          logExactJarNativeReplayResult(pipeline, compiled, arguments,
              target, result);
        }
        case UNSUPPORTED -> {
          shadowReplayDrawsUnsupported.incrementAndGet();
          shadowReplayBlockers.add(result.reason());
          if (visualParitySample) {
            recordVisualParityReplayFailure(openGlFrame,
                "visual-parity-native-unsupported");
          }
          logExactJarNativeReplayResult(pipeline, compiled, arguments,
              target, result);
        }
        case FAILED -> {
          shadowReplayDrawsFailed.incrementAndGet();
          shadowReplayBlockers.add(result.reason());
          if (visualParitySample) {
            recordVisualParityReplayFailure(openGlFrame,
                "visual-parity-native-failed");
          }
          logExactJarNativeReplayResult(pipeline, compiled, arguments,
              target, result);
        }
      }
    } catch (IllegalArgumentException | IllegalStateException error) {
      shadowReplayDrawsFailed.incrementAndGet();
      shadowReplayBlockers.add("shadow-replay-packet-invalid");
      if (visualParitySample) {
        recordVisualParityReplayFailure(openGlFrame,
            "visual-parity-replay-packet-invalid");
      }
    }
  }

  private void compareVisualParity(
      Optional<IrisVisualParityCapture.CapturedFrame> openGlFrame,
      NativeIrisMetalShadowReplayer.Result result) {
    visualParitySamplesHandled.incrementAndGet();
    if (openGlFrame.isEmpty()) {
      visualParityMissingOpenGl.incrementAndGet();
      visualParityBlockers.add("visual-parity-opengl-readback-missing");
      return;
    }
    if (result.rgba8().length == 0) {
      visualParityMissingMetal.incrementAndGet();
      visualParityBlockers.add("visual-parity-metal-rgba8-missing");
      return;
    }
    IrisVisualParityCapture.CapturedFrame iris = openGlFrame.orElseThrow();
    if (iris.width() != result.width() || iris.height() != result.height()) {
      visualParityDimensionMismatches.incrementAndGet();
      visualParityBlockers.add("visual-parity-dimension-mismatch");
      return;
    }
    byte[] metal = result.rgba8();
    IrisVisualParityGate.Comparison comparison = visualParityGate.compare(
        iris.rgba8(), metal, iris.width(), iris.height());
    if (!comparison.passed()) {
      visualParityBlockers.add("visual-parity-pixel-mismatch");
    }
    if (System.getProperty("metalrender.exactJar.expectedPath") != null) {
      IrisVisualParityGate diagnosticGate = new IrisVisualParityGate(
          new IrisVisualParityGate.Thresholds(2, 1.0, 255.0, 1, 1));
      IrisVisualParityGate.Comparison flipped = diagnosticGate.compare(
          iris.rgba8(), flipRgba8Rows(result.rgba8(), result.width(),
              result.height()), iris.width(), iris.height());
      PixelSummary irisSummary = summarizeRgba8(iris.rgba8());
      PixelSummary metalSummary = summarizeRgba8(result.rgba8());
      MetalLogger.info("exact-JAR FINAL visual parity: frame=%d target=%dx%d "
              + "directRatio=%.8f directRmse=%.8f flippedRatio=%.8f "
              + "flippedRmse=%.8f maxDelta=%d passed=%s "
              + "orientation=native-row-order gl=%s metal=%s",
          visualParityGate.status().framesCompared(), comparison.width(),
          comparison.height(), comparison.differentPixelRatio(),
          comparison.rootMeanSquareError(), flipped.differentPixelRatio(),
          flipped.rootMeanSquareError(), comparison.maximumChannelDelta(),
          comparison.passed(), irisSummary, metalSummary);
    }
  }

  private void recordVisualParityReplayFailure(
      Optional<IrisVisualParityCapture.CapturedFrame> openGlFrame,
      String reason) {
    visualParitySamplesHandled.incrementAndGet();
    visualParityReplayFailures.incrementAndGet();
    if (openGlFrame.isEmpty()) {
      visualParityMissingOpenGl.incrementAndGet();
    }
    visualParityBlockers.add(reason);
  }

  private boolean visualParityTerminal() {
    if (!IrisVisualParityCapture.isOptedIn()) {
      return true;
    }
    if (visualParityGate.status().validated()) {
      return true;
    }
    return visualParitySamplesHandled.get()
        >= IrisRenderGraphCapture.MAX_REPLAY_SAMPLES_PER_PHASE;
  }

  private void beginFullGraphOwnershipFrameInternal() {
    ownershipFrame = null;
    if (!productionOwnershipReady()) {
      return;
    }
    boolean promoted = false;
    ReadyGraphPresentation ready;
    while ((ready = readyGraphPresentations.peekFirst()) != null
        && !displayPresentationGenerationMatches(
            ready.displayPresentationGeneration(),
            displayPresentationGeneration.get())) {
      readyGraphPresentations.removeFirst();
      metalGraphExecutor.discardPresentation(ready.status().token());
      decrementGraphPresentationBacklog();
      recordFullGraphOwnershipInvalidation(
          "graph-frame-display-generation-stale");
    }
    if (ready != null && metalGraphExecutor.promotePresentation(
        ready.status())) {
      readyGraphPresentations.removeFirst();
      decrementGraphPresentationBacklog();
      lastOwnershipPresentationState = ready.presentationState();
      lastOwnershipPresentationWidth = ready.status().width();
      lastOwnershipPresentationHeight = ready.status().height();
      promoted = true;
    }
    if ((!promoted
        && !IrisMetalCutoverPresenter.global().hasReusableGraphSurface())
        || lastOwnershipPresentationState == null
        || lastOwnershipPresentationWidth <= 0
        || lastOwnershipPresentationHeight <= 0) {
      return;
    }
    long frame = fullGraphOwnershipFrameSequence.incrementAndGet();
    long generation = pipelineStateCapture.tracker().contextGeneration();
    ownershipFrame = new OwnershipFrame(frame, generation,
        lastOwnershipPresentationState, lastOwnershipPresentationWidth,
        lastOwnershipPresentationHeight, promoted);
    fullGraphOwnershipFramesArmed.incrementAndGet();
  }

  private boolean tryFullGraphCutoverInternal(
      IrisPipelineStateCapture.PendingState pending) {
    Objects.requireNonNull(pending, "pending");
    OwnershipFrame frame = ownershipFrame;
    if (frame == null || frame.contextGeneration
        != pipelineStateCapture.tracker().contextGeneration()) {
      return false;
    }
    IrisRenderGraph.Phase phase = IrisRenderGraphCapture.global()
        .currentPhase();
    if (phase == IrisRenderGraph.Phase.FINAL) {
      frame.finalPresentationState = pending;
    }
    // This draw belongs to the next asynchronously prepared graph, while the
    // armed ownership frame presents only a previously completed surface.
    // IrisRenderGraphCapture is the single authority for rejecting an
    // incomplete capture and releasing its transient IOSurfaces. Marking the
    // already safe presentation degraded here would turn bounded capture
    // backpressure during a display transition into a false ownership
    // failure.
    frame.commandsSuppressed++;
    fullGraphOwnershipCommandsSuppressed.incrementAndGet();
    return true;
  }

  private void invalidateDisplayPresentationInternal(String reason) {
    // Bracket the native reset with two generations. Frames prepared before
    // the transition and frames that race the reset are both rejected; only
    // captures prepared after the reset may become visible.
    displayPresentationGeneration.incrementAndGet();
    long now = System.nanoTime();
    displayTransitionCaptureAbortDeadlineNanos.updateAndGet(deadline ->
        deadline > now ? deadline : now + 5_000_000_000L);
    ownershipFrame = null;
    lastOwnershipPresentationState = null;
    lastOwnershipPresentationWidth = 0;
    lastOwnershipPresentationHeight = 0;
    // Framebuffer-sized Iris inputs change generation across Retina/fullscreen
    // transitions. A presentation-only reset would retain obsolete resident
    // inputs until their bounded native caches filled, forcing incomplete
    // captures. Reset transient graph/input resources while preserving the
    // already compiled MSL libraries and pipeline archive.
    IrisMetalCutoverPresenter.global().reset();
    IrisGlTextureGpuHandoff.reset();
    IrisMetalBufferResidentCache.reset();
    PendingGraphPresentation inFlight;
    while ((inFlight = inFlightGraphPresentations.pollFirst()) != null) {
      metalGraphExecutor.discardPresentation(inFlight.token());
    }
    ReadyGraphPresentation ready;
    while ((ready = readyGraphPresentations.pollFirst()) != null) {
      metalGraphExecutor.discardPresentation(ready.status().token());
    }
    graphPresentationBacklog.set(0);
    PreparedMetalGraphFrame abandoned;
    while ((abandoned = preparedMetalGraphFrames.poll()) != null) {
      preparedMetalGraphFrameCount.decrementAndGet();
      if (abandoned.encodedPacket() != null) {
        abandoned.encodedPacket().close();
      }
      IrisGlTextureGpuHandoff.abandonCapturedSurfaces(
          abandoned.fullReplayTextures());
      releaseFullGraphCaptureReservation();
      if (productionOwnershipReady()) {
        recordFullGraphOwnershipInvalidation(
            "graph-frame-display-generation-stale");
      }
    }
    initializedMetalGraphTokens.clear();
    currentMetalGraphTextures.set(Map.of());
    metalGraphResourceNativeTextureCount.set(0);
    metalGraphResourceNativeTextureBytes.set(0);
    displayPresentationGeneration.incrementAndGet();
    if (productionOwnershipReady()) {
      MetalLogger.info(
          "Iris full Metal display graph reset: %s; awaiting fresh capture",
          BoundedReasonSet.normalizeReason(reason));
    }
  }

  private boolean consumeDisplayTransitionCaptureAbort() {
    long deadline = displayTransitionCaptureAbortDeadlineNanos.get();
    if (deadline != 0 && System.nanoTime() <= deadline) {
      return true;
    }
    displayTransitionCaptureAbortDeadlineNanos.compareAndSet(deadline, 0);
    return false;
  }

  private boolean suppressFullGraphOperationInternal() {
    OwnershipFrame frame = ownershipFrame;
    if (frame == null) {
      return false;
    }
    frame.commandsSuppressed++;
    fullGraphOwnershipCommandsSuppressed.incrementAndGet();
    return true;
  }

  private boolean suppressUnsupportedFullGraphDrawInternal(String reason) {
    OwnershipFrame frame = ownershipFrame;
    if (frame == null) {
      return false;
    }
    frame.degraded = true;
    frame.lastFailure = BoundedReasonSet.normalizeReason(reason);
    frame.commandsSuppressed++;
    fullGraphOwnershipCommandsSuppressed.incrementAndGet();
    return true;
  }

  private void endFullGraphOwnershipFrameInternal() {
    OwnershipFrame frame = ownershipFrame;
    ownershipFrame = null;
    if (frame == null || frame.commandsSuppressed == 0) {
      return;
    }
    IrisPipelineStateCapture.PendingState presentationState =
        frame.finalPresentationState != null
            ? frame.finalPresentationState : frame.presentationState;
    long presentationStarted = System.nanoTime();
    boolean presented = IrisMetalCutoverPresenter.global().presentGraph(
        presentationState, frame.width, frame.height,
        frame.promotedNewSurface);
    fullGraphPresentTiming.record(System.nanoTime() - presentationStarted);
    if (!presented) {
      IrisMetalCutoverPresenter presenter =
          IrisMetalCutoverPresenter.global();
      presenter.discardPendingGraphSurface();
      String reason = presenter.lastFailure();
      recordFullGraphOwnershipFailure(reason.isEmpty()
          ? "graph-ownership-framebuffer-handoff-failed" : reason);
      return;
    }
    long presentations = fullGraphOwnershipFramesPresented.incrementAndGet();
    if (!frame.promotedNewSurface) {
      fullGraphOwnershipFramesReused.incrementAndGet();
    }
    if (frame.degraded) {
      recordFullGraphOwnershipFailure(frame.lastFailure);
    } else {
      fullGraphOwnershipLastFailure.set("");
    }
    if (presentations == 1
        || presentations % FULL_GRAPH_TELEMETRY_INTERVAL_FRAMES == 0) {
      MetalLogger.info(
          "Iris full Metal ownership: presentation=%d target=%dx%d commands=%d surface=%s OpenGL=SUPPRESSED",
          presentations, frame.width, frame.height,
          frame.commandsSuppressed,
          frame.promotedNewSurface ? "NEW" : "REUSED");
      logFullGraphTimings();
    }
  }

  private void logFullGraphTimings() {
    try {
      long[] timing = NativeBridge.nGetIrisMetal4GraphTiming();
      if (timing == null || timing.length != 12 || timing[0] <= 0
          || timing[5] <= 0) {
        return;
      }
      double gpuLastMs = timing[1] / 1_000_000.0;
      double gpuAverageMs = timing[2] / (double) timing[0] / 1_000_000.0;
      double gpuMaximumMs = timing[3] / 1_000_000.0;
      double cpuLastMs = timing[6] / 1_000_000.0;
      double cpuAverageMs = timing[7] / (double) timing[5] / 1_000_000.0;
      double cpuMaximumMs = timing[8] / 1_000_000.0;
      double queueLastMs = timing[9] / 1_000_000.0;
      double queueAverageMs = timing[10] / (double) timing[0]
          / 1_000_000.0;
      double queueMaximumMs = timing[11] / 1_000_000.0;
      MetalLogger.info(
          "Iris full Metal timing: gpu=%.2f/%.2f/%.2fms queue=%.2f/%.2f/%.2fms nativeSubmit=%.2f/%.2f/%.2fms last/avg/max gpuSamples=%d cpuSamples=%d feedbackErrors=%d",
          gpuLastMs, gpuAverageMs, gpuMaximumMs,
          queueLastMs, queueAverageMs, queueMaximumMs,
          cpuLastMs, cpuAverageMs, cpuMaximumMs,
          timing[0], timing[5], timing[4]);
      long[] profile = NativeBridge.nGetIrisMetal4GraphCpuProfile();
      if (profile != null && profile.length == 18 && profile[0] > 0) {
        double divisor = profile[0] * 1_000_000.0;
        MetalLogger.info(
            "Iris full Metal native CPU profile: parse=%.3f inputs=%.3f draws=%.3f setup=%.3fms [present=%.3f residencyCreate=%.3f residencyPopulate=%.3f residencyCommit=%.3f command=%.3f] encode=%.3f commit=%.3f retire=%.3fms avg samples=%d residency=%d/%d raw/unique renderPasses=%d/%d passes/draws barriers=%d",
            profile[1] / divisor, profile[2] / divisor,
            profile[3] / divisor, profile[4] / divisor,
            profile[5] / divisor, profile[6] / divisor,
            profile[7] / divisor, profile[8] / divisor,
            profile[9] / divisor, profile[10] / divisor,
            profile[11] / divisor, profile[12] / divisor,
            profile[0], profile[13] / profile[0],
            profile[14] / profile[0], profile[16] / profile[0],
            profile[15] / profile[0], profile[17] / profile[0]);
      }
      NativeIrisMetalGraphExecutor.PacketStats packets =
          metalGraphExecutor.packetStats();
      if (packets.samples() > 0) {
        MetalLogger.info(
            "Iris full Metal packet: %.2f/%.2f/%.2fMiB last/avg/max samples=%d",
            packets.lastMiB(), packets.averageMiB(), packets.maximumMiB(),
            packets.samples());
      }
      TimingSnapshot capture = fullGraphCaptureTiming.snapshot();
      TimingSnapshot plan = fullGraphPlanTiming.snapshot();
      TimingSnapshot submit = fullGraphSubmitTiming.snapshot();
      TimingSnapshot present = fullGraphPresentTiming.snapshot();
      if (capture.samples() > 0 && plan.samples() > 0
          && submit.samples() > 0 && present.samples() > 0) {
        MetalLogger.info(
            "Iris full Metal Java timing: capture=%.2f/%.2f/%.2fms plan=%.2f/%.2f/%.2fms submit=%.2f/%.2f/%.2fms present=%.2f/%.2f/%.2fms last/avg/max samples=%d/%d/%d/%d",
            capture.lastMs(), capture.averageMs(), capture.maximumMs(),
            plan.lastMs(), plan.averageMs(), plan.maximumMs(),
            submit.lastMs(), submit.averageMs(), submit.maximumMs(),
            present.lastMs(), present.averageMs(), present.maximumMs(),
            capture.samples(), plan.samples(), submit.samples(),
            present.samples());
      }
    } catch (RuntimeException | LinkageError ignored) {
      // Timing is release evidence only and never controls frame ownership.
    }
  }

  private void beginCutoverFrameInternal() {
    long frame = cutoverFrameSequence.incrementAndGet();
    long generation = pipelineStateCapture.tracker().contextGeneration();
    try {
      cutoverGate.beginFrame(frame, generation);
      cutoverGate.arm(snapshotVisualParity().validated());
    } catch (IllegalArgumentException | IllegalStateException failure) {
      recordCutoverFailure("cutover-frame-identity-invalid");
      cutoverGate.asynchronousFailure();
    }
  }

  private boolean tryFinalCutoverInternal(
      IrisPipelineStateCapture.PendingState pending) {
    Objects.requireNonNull(pending, "pending");
    IrisSelectiveCutoverGate.Status gateStatus = cutoverGate.status();
    IrisRenderGraph.Phase capturePhase = IrisRenderGraphCapture.global()
        .currentPhase();
    boolean graphOwnershipOptedIn =
        NativeIrisMetalGraphExecutor.isOwnershipOptedIn();
    boolean unsubmittedLegacyCapture = pending.replayTextures()
        .captureEnabled() && legacyCutoverCaptureEligible(
            graphOwnershipOptedIn, false,
            fullGraphCapturesOutstanding.get(), capturePhase, gateStatus);
    if (graphOwnershipOptedIn) {
      // Full-graph mode may suppress OpenGL only from an already completed,
      // promoted graph surface. Falling back to the legacy FINAL-only bridge
      // after a graph capture failure combines stale inputs with a current
      // frame and was the direct cause of black output in 0.4.0.
      return false;
    }
    if (productionOwnershipReady()) {
      // Full-graph ownership supersedes the legacy same-frame FINAL bridge.
      // Keeping both capture paths active leases one IOSurface per visible
      // frame even though tryFullGraphCutover wins first, eventually starving
      // the next post-display-reset graph capture.
      abandonLegacyCutoverCapture(pending, unsubmittedLegacyCapture);
      return false;
    }
    // A full-graph validation frame must execute the complete OpenGL baseline.
    // Suppressing its FINAL draw would compare Metal against a stale/cleared
    // attachment and could falsely approve or reject the native graph.
    if (fullGraphCapturesOutstanding.get() != 0
        || IrisRenderGraphCapture.global().currentPhase()
        != IrisRenderGraph.Phase.FINAL
        || gateStatus.currentFrame() < 0 || gateStatus.frameFallback()
        || (gateStatus.mode() != IrisSelectiveCutoverGate.Mode.ARMED
            && gateStatus.mode() != IrisSelectiveCutoverGate.Mode.ACTIVE)
        || !pending.replayBuffers().captureEnabled()) {
      abandonLegacyCutoverCapture(pending, unsubmittedLegacyCapture);
      return false;
    }
    cutoverDrawsObserved.incrementAndGet();

    String identity = cutoverPipelineIdentities.get(
        IrisPipelineStateCapture.lookupKey(pending));
    if (identity == null) {
      cutoverLastFailure.set("cutover-pipeline-identity-unavailable");
      abandonLegacyCutoverCapture(pending, unsubmittedLegacyCapture);
      return false;
    }
    CompiledMetalPipeline compiled = compiledMetalPipelines.get(identity);
    if (compiled == null) {
      cutoverLastFailure.set("compiled-cutover-pipeline-unavailable");
      abandonLegacyCutoverCapture(pending, unsubmittedLegacyCapture);
      return false;
    }
    IrisProgramIdentityRegistry.ResolvedProgram program =
        pending.registration().resolved().orElse(null);
    if (program == null) {
      cutoverLastFailure.set("cutover-program-identity-unresolved");
      abandonLegacyCutoverCapture(pending, unsubmittedLegacyCapture);
      return false;
    }
    String shaderKey = program.shaderKey().sha256();
    IrisProgramResourceLayout semantic = resourceLayouts.get(shaderKey);
    IrisMslArgumentLayout msl = mslArgumentLayouts.get(shaderKey);
    if (semantic == null || msl == null) {
      cutoverLastFailure.set("cutover-argument-layout-unavailable");
      abandonLegacyCutoverCapture(pending, unsubmittedLegacyCapture);
      return false;
    }

    IrisShadowReplayBufferSnapshot replayBuffers =
        pending.replayBuffers().promoteGeometryToMetal();
    IrisShadowReplayArgumentTable arguments =
        IrisShadowReplayArgumentTable.resolve(semantic, msl,
            pending.resourceBindings(), replayBuffers,
            pending.replayTextures(), pending.replaySamplers());
    ArrayList<String> blockers = new ArrayList<>();
    replayBuffers.drawBlockers().forEach(
        reason -> addDistinct(blockers, reason));
    arguments.blockers().forEach(reason -> addDistinct(blockers, reason));
    if (!replayBuffers.drawComplete()) {
      addDistinct(blockers, "cutover-buffer-capture-incomplete");
    }
    if (!pending.replayTextures().complete()) {
      addDistinct(blockers, "cutover-texture-capture-incomplete");
    }
    if (!pending.replaySamplers().complete()) {
      addDistinct(blockers, "cutover-sampler-capture-incomplete");
    }
    if (!pending.dynamicState().completeFor(
        IrisGlStateSnapshot.Operation.DRAW)) {
      addDistinct(blockers, "cutover-dynamic-state-incomplete");
    }
    if (!supportedReplayCommand(pending.command())) {
      addDistinct(blockers, "cutover-command-not-replayable");
    }
    if (arguments.stages().stream()
        .flatMap(stage -> stage.arguments().stream())
        .map(IrisShadowReplayArgumentTable.BoundArgument::value)
        .anyMatch(value ->
            value instanceof IrisShadowReplayArgumentTable.CanonicalZeroTexture
                || value instanceof IrisShadowReplayArgumentTable
                    .CanonicalDefaultSampler)) {
      addDistinct(blockers, "cutover-canonical-resource-unavailable");
    }
    TargetExtent target = cutoverTargetExtent(pending, compiled.state(),
        blockers);
    IrisSelectiveCutoverGate.Ticket ticket = cutoverGate.beforeDraw(
        IrisRenderGraph.Phase.FINAL, compiled.pipelineKey().sha256(),
        true, blockers.isEmpty());
    if (!ticket.candidate()) {
      if (!blockers.isEmpty()) {
        recordCutoverFailure("cutover-prerequisite-blocked");
      }
      abandonLegacyCutoverCapture(pending, unsubmittedLegacyCapture);
      return false;
    }

    cutoverDrawsEligible.incrementAndGet();
    cutoverMetalAttempts.incrementAndGet();
    for (IrisGlTextureMirror.TextureSnapshot texture
        : pending.replayTextures().textures().values()) {
      long logicalBytes = Math.multiplyExact(
          Math.multiplyExact((long) texture.width(), texture.height()),
          texture.bytesPerPixel());
      if (texture.shared()) {
        cutoverGpuInputTextures.incrementAndGet();
        cutoverGpuInputBytes.addAndGet(logicalBytes);
      } else {
        cutoverCpuInputTextures.incrementAndGet();
        cutoverCpuInputBytes.addAndGet(logicalBytes);
      }
    }
    for (IrisShadowReplayBufferSnapshot.BufferImage image
        : IrisMetalShadowReplayPacketEncoder.requiredBufferImages(
            replayBuffers, arguments)) {
      if (image.shared()) {
        cutoverGpuInputBuffers.incrementAndGet();
        cutoverGpuInputBufferBytes.addAndGet(image.byteLength());
      } else {
        cutoverCpuInputBuffers.incrementAndGet();
        cutoverCpuInputBufferBytes.addAndGet(image.byteLength());
      }
    }
    try {
      byte[] packet = IrisMetalShadowReplayPacketEncoder.encode(
          pending.command(), pending.dynamicState(), replayBuffers,
          pending.replayTextures(), compiled.vertexBindings(), arguments,
          target.width(), target.height());
      NativeIrisMetalShadowReplayer.DirectResult result =
          shadowReplayer.replayForPresentation(
          compiled.pipelineKey().sha256(), packet, target.width(),
          target.height());
      boolean encoded = result.outcome()
          == NativeIrisMetalShadowReplayer.Outcome.SUCCEEDED;
      boolean presented = encoded
          && IrisMetalCutoverPresenter.global().presentDirect(pending,
              target.width(), target.height());
      boolean suppress = cutoverGate.metalEncodeCompleted(ticket, encoded,
          presented);
      if (!suppress) {
        recordCutoverFailure(encoded
            ? "cutover-framebuffer-handoff-failed"
            : "cutover-native-replay-" + result.outcome().name()
                .toLowerCase(java.util.Locale.ROOT));
        return false;
      }
      cutoverMetalSucceeded.incrementAndGet();
      cutoverPresentations.incrementAndGet();
      cutoverLastFailure.set("");
      long succeeded = cutoverPresentations.get();
      if (succeeded == 1
          || succeeded % FULL_GRAPH_TELEMETRY_INTERVAL_FRAMES == 0) {
        MetalLogger.info(
            "Iris FINAL selective cutover: presentation=%d target=%dx%d pipeline=%s bridge=%s OpenGL=SUPPRESSED",
            succeeded, target.width(), target.height(),
            compiled.pipelineKey().sha256().substring(0, 12),
            IrisMetalCutoverPresenter.BRIDGE_NAME);
      }
      return true;
    } catch (IllegalArgumentException | IllegalStateException
        | LinkageError failure) {
      abandonLegacyCutoverCapture(pending, unsubmittedLegacyCapture);
      cutoverGate.metalEncodeCompleted(ticket, false, false);
      recordCutoverFailure("cutover-replay-exception");
      return false;
    }
  }

  private static void abandonLegacyCutoverCapture(
      IrisPipelineStateCapture.PendingState pending, boolean unsubmitted) {
    if (!unsubmitted) {
      return;
    }
    IrisGlTextureGpuHandoff.abandonCapturedSurfaces(
        pending.replayTextures().textures().values());
  }

  private TargetExtent cutoverTargetExtent(
      IrisPipelineStateCapture.PendingState pending,
      IrisPipelineState state, List<String> blockers) {
    if (state.rasterSampleCount() != 1) {
      addDistinct(blockers, "cutover-multisample-unimplemented");
    }
    if (state.colorAttachments().size() != 1
        || state.colorAttachments().get(0).slot() != 0
        || !state.colorAttachments().get(0).format().cacheName()
            .equals("rgba8-unorm")) {
      addDistinct(blockers, "cutover-rgba8-single-target-required");
    }
    if (state.depthAttachmentFormat().isPresent()
        || state.stencilAttachmentFormat().isPresent()) {
      addDistinct(blockers, "cutover-depth-stencil-handoff-unimplemented");
    }
    IrisGlStateSnapshot.StateValue<IrisDynamicDrawState.Rect> viewport =
        pending.dynamicState().viewport();
    if (!viewport.isKnown()) {
      addDistinct(blockers, "cutover-viewport-unavailable");
      return new TargetExtent(1, 1);
    }
    IrisDynamicDrawState.Rect rect = viewport.value();
    if (rect.x() != 0 || rect.y() != 0 || rect.width() <= 0
        || rect.height() <= 0) {
      addDistinct(blockers, "cutover-full-target-viewport-required");
      return new TargetExtent(1, 1);
    }
    long activeTargets = pending.snapshot().colorTargets().stream()
        .filter(target -> target.drawBuffer().isKnown()
            && target.drawBuffer().value() != 0)
        .count();
    if (activeTargets != 1) {
      addDistinct(blockers, "cutover-single-gl-draw-buffer-required");
      return new TargetExtent(rect.width(), rect.height());
    }
    IrisGlStateSnapshot.TextureAttachment attachment =
        pending.snapshot().colorTargets().stream()
            .filter(target -> target.drawBuffer().isKnown()
                && target.drawBuffer().value() != 0)
            .map(IrisGlStateSnapshot.ColorTarget::attachment)
            .filter(IrisGlStateSnapshot.StateValue::isKnown)
            .map(IrisGlStateSnapshot.StateValue::value)
            .flatMap(Optional::stream)
            .findFirst().orElse(null);
    if (attachment == null) {
      addDistinct(blockers, "cutover-color-attachment-unavailable");
      return new TargetExtent(rect.width(), rect.height());
    }
    IrisGlStateTracker.TextureMetadata metadata =
        pipelineStateCapture.tracker().textureMetadata(attachment.texture())
            .orElse(null);
    if (metadata == null || metadata.width() != rect.width()
        || metadata.height() != rect.height()) {
      addDistinct(blockers, "cutover-target-extent-mismatch");
    }
    return new TargetExtent(rect.width(), rect.height());
  }

  private void recordCutoverFailure(String reason) {
    cutoverFailures.incrementAndGet();
    cutoverLastFailure.set(reason);
    cutoverFailureReasons.add(reason);
  }

  static byte[] flipRgba8Rows(byte[] rgba8, int width, int height) {
    Objects.requireNonNull(rgba8, "rgba8");
    int rowBytes = Math.toIntExact(Math.multiplyExact((long) width, 4L));
    int required = Math.toIntExact(Math.multiplyExact((long) rowBytes,
        height));
    if (width <= 0 || height <= 0 || rgba8.length != required) {
      throw new IllegalArgumentException("invalid RGBA8 row flip");
    }
    byte[] flipped = new byte[required];
    for (int row = 0; row < height; row++) {
      System.arraycopy(rgba8, row * rowBytes, flipped,
          (height - row - 1) * rowBytes, rowBytes);
    }
    return flipped;
  }

  private static PixelSummary summarizeRgba8(byte[] rgba8) {
    long[] sums = new long[4];
    int[] minimums = {255, 255, 255, 255};
    int[] maximums = new int[4];
    int pixels = rgba8.length / 4;
    for (int offset = 0; offset < rgba8.length; offset += 4) {
      for (int channel = 0; channel < 4; channel++) {
        int value = Byte.toUnsignedInt(rgba8[offset + channel]);
        sums[channel] += value;
        minimums[channel] = Math.min(minimums[channel], value);
        maximums[channel] = Math.max(maximums[channel], value);
      }
    }
    return new PixelSummary(
        String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f/%.2f",
            sums[0] / (double) pixels, sums[1] / (double) pixels,
            sums[2] / (double) pixels, sums[3] / (double) pixels),
        minimums[0] + "/" + minimums[1] + "/" + minimums[2] + "/"
            + minimums[3],
        maximums[0] + "/" + maximums[1] + "/" + maximums[2] + "/"
            + maximums[3]);
  }

  private record PixelSummary(String mean, String minimum, String maximum) {
    @Override
    public String toString() {
      return "mean=" + mean + ",min=" + minimum + ",max=" + maximum;
    }
  }

  private void logExactJarReplayBlocker(
      IrisRenderExecutionPlan.PipelineStep pipeline, String identity,
      boolean pipelineAvailable, IrisShadowReplayArgumentTable arguments,
      List<String> blockers) {
    if (System.getProperty("metalrender.exactJar.expectedPath") == null) {
      return;
    }
    String key = pipeline.phase().name() + ':' + identity + ':'
        + String.join(",", blockers);
    synchronized (shadowReplayDiagnosticKeys) {
      if (shadowReplayDiagnosticKeys.contains(key)
          || shadowReplayDiagnosticKeys.size()
          >= DEFAULT_SHADOW_REPLAY_DIAGNOSTIC_CAPACITY) {
        return;
      }
      shadowReplayDiagnosticKeys.add(key);
    }
    IrisPipelineStateCapture.PendingState pending = pipeline.pending();
    IrisShadowReplayBufferSnapshot buffers = pending.replayBuffers();
    IrisShadowReplayTextureSnapshot textures = pending.replayTextures();
    IrisShadowReplaySamplerSnapshot samplers = pending.replaySamplers();
    IrisGlResourceBindingSnapshot resources = pending.resourceBindings();
    MetalLogger.info("exact-JAR replay blocked: phase=%s program=%s "
            + "command=%s "
            + "pipeline=%s vertexRefs=%d indexRef=%s bufferImages=%d "
            + "indexedBuffers=%d textureBuffers=%d textures=%d samplers=%d "
            + "arguments=%d liveIndexed=%d liveTextures=%d inputs=%s "
            + "layout=%s textureState=%s blockers=%s",
        pipeline.phase().name(), exactJarProgramDiagnostic(pending),
        pending.command().getClass().getSimpleName(),
        pipelineAvailable, buffers.vertexBuffers().size(),
        buffers.indexBuffer().isPresent(), buffers.images().size(),
        buffers.indexedBuffers().size(), buffers.textureBuffers().size(),
        textures.textures().size(), samplers.textureUnits().size(),
        arguments.argumentCount(),
        resources == null ? 0 : resources.indexedBuffers().size(),
        resources == null ? 0 : resources.textureUnits().size(),
        exactJarInputDiagnostic(pending.vertexInputBindings()),
        exactJarVertexLayoutDiagnostic(
            pending.registration().descriptor()),
        exactJarTextureDiagnostic(resources, textures),
        String.join(",", blockers));
  }

  private void logExactJarNativeReplayResult(
      IrisRenderExecutionPlan.PipelineStep pipeline,
      CompiledMetalPipeline compiled,
      IrisShadowReplayArgumentTable arguments, TargetExtent target,
      NativeIrisMetalShadowReplayer.Result result) {
    if (System.getProperty("metalrender.exactJar.expectedPath") == null) {
      return;
    }
    IrisPipelineStateCapture.PendingState pending = pipeline.pending();
    String key = "native:" + pipeline.phase().name() + ':'
        + compiled.pipelineKey().sha256() + ':' + result.outcome().name()
        + ':' + result.reason();
    synchronized (shadowReplayDiagnosticKeys) {
      if (shadowReplayDiagnosticKeys.contains(key)
          || shadowReplayDiagnosticKeys.size()
          >= DEFAULT_SHADOW_REPLAY_DIAGNOSTIC_CAPACITY) {
        return;
      }
      shadowReplayDiagnosticKeys.add(key);
    }
    ArrayList<String> colorFormats = new ArrayList<>();
    compiled.state().colorAttachments().forEach(attachment ->
        colorFormats.add(attachment.slot() + "="
            + attachment.format().cacheName()));
    String depthFormat = compiled.state().depthAttachmentFormat()
        .map(IrisPipelineState.DataFormat::cacheName).orElse("none");
    String stencilFormat = compiled.state().stencilAttachmentFormat()
        .map(IrisPipelineState.DataFormat::cacheName).orElse("none");
    MetalLogger.info("exact-JAR native replay result: phase=%s program=%s "
            + "command=%s pipeline=%s target=%dx%d colors=%s depth=%s "
            + "stencil=%s arguments=%d textureState=%s outcome=%s "
            + "reason=%s inputs=%s layout=%s cull=%s frontFace=%s",
        pipeline.phase().name(), exactJarProgramDiagnostic(pending),
        pending.command().getClass().getSimpleName(),
        compiled.pipelineKey().sha256().substring(0, 12), target.width(),
        target.height(), String.join(";", colorFormats), depthFormat,
        stencilFormat, arguments.argumentCount(),
        exactJarTextureDiagnostic(pending.resourceBindings(),
            pending.replayTextures()),
        result.outcome().name(), result.reason(),
        exactJarInputDiagnostic(pending.vertexInputBindings()),
        exactJarVertexLayoutDiagnostic(pending.registration().descriptor()),
        compiled.state().raster().cullMode().cacheName(),
        compiled.state().raster().frontFace().cacheName());
  }

  private static String exactJarProgramDiagnostic(
      IrisPipelineStateCapture.PendingState pending) {
    String name = pending.registration().descriptor().programName()
        .replaceAll("\\s+", "_");
    if (name.length() > 96) {
      name = name.substring(0, 96);
    }
    IrisProgramIdentityRegistry.ResolvedProgram resolved =
        pending.registration().resolved().orElse(null);
    String digest = resolved == null ? "unresolved"
        : resolved.shaderKey().sha256().substring(0, 12);
    return name + ':' + digest;
  }

  private static String exactJarVertexLayoutDiagnostic(
      IrisProgramIdentityRegistry.ProgramDescriptor descriptor) {
    ArrayList<String> values = new ArrayList<>();
    descriptor.vertexBuffers().stream().limit(8).forEach(buffer ->
        values.add("b" + buffer.bufferIndex() + '='
            + buffer.stepFunction().cacheName() + '/'
            + buffer.strideBytes()));
    descriptor.vertexAttributes().stream().limit(12).forEach(attribute ->
        values.add("a" + attribute.location() + "->b"
            + attribute.bufferIndex() + '@' + attribute.offsetBytes() + '/'
            + attribute.format().cacheName()));
    return String.join(";", values);
  }

  private static String exactJarTextureDiagnostic(
      IrisGlResourceBindingSnapshot resources,
      IrisShadowReplayTextureSnapshot captured) {
    if (resources == null) {
      return "unavailable";
    }
    IrisGlTextureMirror mirror = IrisGlTextureMirror.global();
    ArrayList<String> values = new ArrayList<>();
    IrisShadowReplayTextureSnapshot.sampledUnits(resources).stream()
        .limit(16).forEach(unit -> {
          IrisGlResourceBindingSnapshot.TextureUnitBinding binding =
              resources.textureUnits().get(unit);
          if (binding == null) {
            values.add("u" + unit + "=unbound");
            return;
          }
          int texture = binding.texture();
          IrisGlTextureMirror.TextureSnapshot snapshot =
              captured.textures().get(texture);
          IrisGlTextureMirror.TextureMetadata metadata = texture <= 0
              ? null : mirror.metadata(texture, 0, 0).orElse(null);
          String metadataValue = metadata == null ? "none"
              : metadata.format() + '/' + metadata.width() + 'x'
                  + metadata.height() + 'x' + metadata.depthOrLayers();
          String capturedValue = snapshot == null ? "none"
              : snapshot.format() + '/' + snapshot.width() + 'x'
                  + snapshot.height() + '/' + snapshot.bytesPerPixel()
                  + "bpp/" + (snapshot.graphReference() ? "graph"
                      : snapshot.shared() ? "iosurface" : "cpu");
          values.add("u" + unit + "=t" + texture + "/target0x"
              + Integer.toHexString(binding.target()) + "/gen"
              + binding.mirrorGeneration() + "/captured"
              + capturedValue + "/meta"
              + metadataValue);
        });
    return values.isEmpty() ? "none" : String.join(";", values);
  }

  private static String exactJarSamplerDiagnostic(
      IrisShadowReplaySamplerSnapshot captured) {
    ArrayList<String> values = new ArrayList<>();
    captured.textureUnits().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey()).limit(16)
        .forEach(entry -> {
          IrisGlSamplerMirror.Snapshot snapshot = entry.getValue();
          String unsupported = snapshot.state().unsupportedParameters()
              .stream().map(value -> "0x" + Integer.toHexString(value))
              .collect(java.util.stream.Collectors.joining(","));
          IrisGlSamplerMirror.SamplerState state = snapshot.state();
          values.add("u" + entry.getKey() + "=o" + snapshot.object()
              + "/gen" + snapshot.generation()
              + "/owner=" + (snapshot.textureOwned()
                  ? "texture" : "sampler")
              + "/min=0x" + Integer.toHexString(state.minFilter())
              + "/mag=0x" + Integer.toHexString(state.magFilter())
              + "/wrap=0x" + Integer.toHexString(state.wrapS())
              + ",0x" + Integer.toHexString(state.wrapT())
              + ",0x" + Integer.toHexString(state.wrapR())
              + "/levels=" + state.baseLevel() + "-" + state.maxLevel()
              + "/lod=" + state.minLod() + "-" + state.maxLod()
              + "/bias=" + state.lodBias()
              + "/aniso=" + state.maxAnisotropy()
              + "/compare=0x" + Integer.toHexString(state.compareMode())
              + ":0x" + Integer.toHexString(state.compareFunc())
              + "/unsupported="
              + (unsupported.isEmpty() ? "none" : unsupported));
        });
    return values.isEmpty() ? "none" : String.join(";", values);
  }

  private static String exactJarInputDiagnostic(
      IrisVertexInputBindings bindings) {
    if (!bindings.complete()) {
      return bindings.incompleteReason();
    }
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    ArrayList<String> values = new ArrayList<>();
    for (IrisVertexInputBindings.BufferSlice slice
        : bindings.vertexBuffers()) {
      values.add("v" + slice.slot() + '=' + mirror.rangeDiagnostic(
          slice.glBuffer(), slice.mirrorGeneration(), slice.offsetBytes(),
          slice.lengthBytes()));
    }
    bindings.indexBuffer().ifPresent(slice -> values.add("i="
        + mirror.rangeDiagnostic(slice.glBuffer(), slice.mirrorGeneration(),
            slice.offsetBytes(), slice.lengthBytes())));
    return String.join(";", values);
  }

  private void recordShadowReplayBlocked(
      IrisRenderExecutionPlan.PipelineStep pipeline, String reason) {
    if (!NativeIrisMetalShadowReplayer.isOptedIn()
        || pipeline.kind() != IrisRenderGraph.NodeKind.DRAW) {
      return;
    }
    shadowReplayDrawsObserved.incrementAndGet();
    shadowReplayDrawsBlocked.incrementAndGet();
    if (pipeline.phase() == IrisRenderGraph.Phase.FINAL
        && IrisVisualParityCapture.isOptedIn()
        && !visualParityTerminal()) {
      Optional<IrisVisualParityCapture.CapturedFrame> openGlFrame =
          IrisVisualParityCapture.global().take(pipeline.pending());
      recordVisualParityReplayFailure(openGlFrame,
          "visual-parity-shadow-prerequisite-blocked");
      visualParityBlockers.add(reason);
    }
  }

  private static boolean supportedReplayCommand(IrisExecutionCommand command) {
    if (command instanceof IrisExecutionCommand.DrawArrays) {
      return true;
    }
    if (command instanceof IrisExecutionCommand.DrawIndexed indexed) {
      return indexed.indexElementBytes() != 1;
    }
    if (command instanceof IrisExecutionCommand.MultiDrawIndexed indexed) {
      return indexed.indexElementBytes() != 1;
    }
    return false;
  }

  private static TargetExtent targetExtent(IrisRenderGraph graph,
      IrisRenderGraph.Node node, IrisPipelineState state,
      List<String> blockers) {
    int width = 0;
    int height = 0;
    int samples = 0;
    for (IrisRenderGraph.ResourceUse use : node.resources()) {
      if (!use.access().writes()) {
        continue;
      }
      IrisRenderGraph.Resource resource =
          graph.resources().get(use.resourceId());
      if (resource.kind() != IrisRenderGraph.ResourceKind.TEXTURE
          || !resource.allocationComplete()) {
        continue;
      }
      if (width == 0) {
        width = resource.width();
        height = resource.height();
        samples = resource.sampleCount();
      } else if (width != resource.width() || height != resource.height()
          || samples != resource.sampleCount()) {
        addDistinct(blockers, "render-target-extent-mismatch");
      }
    }
    if (width <= 0 || height <= 0) {
      addDistinct(blockers, "render-target-extent-unavailable");
    } else if (width > IrisMetalShadowReplayPacketEncoder.MAX_TARGET_EXTENT
        || height > IrisMetalShadowReplayPacketEncoder.MAX_TARGET_EXTENT) {
      addDistinct(blockers, "render-target-extent-exceeds-replay-bound");
    }
    if (samples != 0 && samples != state.rasterSampleCount()) {
      addDistinct(blockers, "render-target-sample-count-mismatch");
    }
    return new TargetExtent(
        Math.min(IrisMetalShadowReplayPacketEncoder.MAX_TARGET_EXTENT,
            Math.max(1, width)),
        Math.min(IrisMetalShadowReplayPacketEncoder.MAX_TARGET_EXTENT,
            Math.max(1, height)));
  }


  private void scheduleMetalPipeline(
      IrisProgramIdentityRegistry.ResolvedProgram program,
      IrisPipelineState state, IrisPipelineStateKey stateKey,
      boolean stateCacheHit, boolean executionSupported) {
    if (!NativeIrisMetalPipelineCompiler.isOptedIn()) {
      return;
    }
    String candidateIdentity = candidateIdentity(program, stateKey);
    if (!executionSupported) {
      metalPipelineBlockedKeys.add(candidateIdentity);
      return;
    }
    if (!metalPipelineCandidateKeys.add(candidateIdentity)) {
      return;
    }
    if (!metalPipelineQueue.offer(new MetalPipelineCandidate(
        program, state, stateKey, stateCacheHit))) {
      metalPipelineQueueRejected.incrementAndGet();
      metalPipelinesFailed.incrementAndGet();
      metalPipelineLastFailure.set("metal-pipeline-queue-full");
      metalPipelineFailureReasons.add("metal-pipeline-queue-full");
    }
  }

  private boolean drainOneMetalPipeline() {
    if (!NativeIrisMetalPipelineCompiler.isOptedIn()) {
      return false;
    }
    if (captureQueue.size() != 0 || !libraryStageQueue.isEmpty()
        || pipelineStateCapture.queued() != 0) {
      return false;
    }
    IrisMetalPipelineCompiler.Readiness readiness =
        metalPipelineCompiler.readiness();
    metalPipelineReadiness.set(readiness);
    MetalPipelineCandidate candidate = metalPipelineQueue.peek();
    if (candidate == null) {
      flushMetalPipelineCacheIfNeeded(readiness);
      return false;
    }
    if (readiness == IrisMetalPipelineCompiler.Readiness.DEFERRED) {
      return false;
    }
    candidate = metalPipelineQueue.poll();
    if (candidate == null) {
      return false;
    }
    if (readiness == IrisMetalPipelineCompiler.Readiness.UNSUPPORTED) {
      metalPipelinesUnsupported.incrementAndGet();
      logMetalPipelineProgress();
      return true;
    }
    try {
      IrisProgramResourceLayout layout = resourceLayouts.get(
          candidate.program().shaderKey().sha256());
      IrisMslArgumentLayout argumentLayout = mslArgumentLayouts.get(
          candidate.program().shaderKey().sha256());
      if (layout == null || argumentLayout == null) {
        String reason = resourceLayoutFailures.getOrDefault(
            candidate.program().shaderKey().sha256(), "unavailable");
        recordMetalPipelineFailure("resource-layout-" + reason);
        return true;
      }
      IrisMetalVertexBindingLayout vertexBindings =
          IrisMetalVertexBindingLayout.resolve(candidate.state(),
              argumentLayout);
      IrisPipelineState metalState = vertexBindings.state();
      IrisPipelineStateKey metalStateKey = IrisPipelineStateKey.from(
          candidate.program().shaderKey(), metalState);
      EnumMap<IrisShaderStage, byte[]> stages =
          new EnumMap<>(IrisShaderStage.class);
      for (IrisShaderStage stage : candidate.program().stages()) {
        Optional<IrisPipelineCache.VerifiedMslStage> verified =
            cache.readVerifiedMslStage(candidate.program().shaderKey(),
                backend.profile(), stage);
        if (verified.isEmpty()) {
          recordMetalPipelineFailure(
              "verified-msl-unavailable-" + stage.cacheName());
          return true;
        }
        stages.put(stage, verified.orElseThrow().mslUtf8());
      }
      String deviceCompiler =
          metalPipelineCompiler.deviceCompilerSha256();
      if (!deviceCompiler.matches("[0-9a-f]{64}")) {
        recordMetalPipelineFailure("device-compiler-key-unavailable");
        return true;
      }
      IrisMetalPipelineKey pipelineKey = IrisMetalPipelineKey.from(
          deviceCompiler, candidate.program().shaderKey(),
          metalStateKey, layout.key(), argumentLayout, backend.profile());
      metalPipelinesAttempted.incrementAndGet();
      IrisMetalPipelineCompiler.Outcome outcome =
          metalPipelineCompiler.compile(pipelineKey,
              candidate.program().shaderKey(), metalState,
              Map.copyOf(stages));
      switch (outcome) {
        case COMPILED -> {
          metalPipelinesCompiled.incrementAndGet();
          if (candidate.stateCacheHit()) {
            metalPipelineKnownArchiveMisses.incrementAndGet();
          } else {
            metalPipelineNewVariantsCompiled.incrementAndGet();
          }
          metalPipelineCacheFlushed.set(false);
          retainMetalPipelineIdentity(pipelineKey, metalStateKey, layout,
              argumentLayout);
          retainCompiledMetalPipeline(candidate, pipelineKey, metalState,
              vertexBindings);
        }
        case CACHE_HIT -> {
          metalPipelineCacheHits.incrementAndGet();
          retainMetalPipelineIdentity(pipelineKey, metalStateKey, layout,
              argumentLayout);
          retainCompiledMetalPipeline(candidate, pipelineKey, metalState,
              vertexBindings);
        }
        case DEFERRED -> {
          metalPipelinesAttempted.decrementAndGet();
          if (!metalPipelineQueue.offer(candidate)) {
            metalPipelineQueueRejected.incrementAndGet();
            recordMetalPipelineFailure("metal-pipeline-requeue-full");
          }
        }
        case UNSUPPORTED -> metalPipelinesUnsupported.incrementAndGet();
        case FAILED -> recordMetalPipelineFailure("native-pipeline-build");
      }
      if (outcome != IrisMetalPipelineCompiler.Outcome.DEFERRED) {
        logMetalPipelineProgress();
      }
    } catch (Exception | LinkageError error) {
      recordMetalPipelineFailure(redactedFailure(error));
      logMetalPipelineProgress();
    }
    return true;
  }

  private void logMetalPipelineProgress() {
    long resolved = metalPipelinesCompiled.get()
        + metalPipelineCacheHits.get()
        + metalPipelinesUnsupported.get()
        + metalPipelinesFailed.get();
    int observed = metalPipelineCandidateKeys.size();
    if (resolved == observed || resolved % 16 == 0) {
      MetalLogger.info(
          "Iris MTL4 pipeline cache: resolved=%d/%d compiled=%d "
              + "new=%d knownMisses=%d hits=%d unsupported=%d failed=%d "
              + "pending=%d reasons=%s",
          resolved, observed, metalPipelinesCompiled.get(),
          metalPipelineNewVariantsCompiled.get(),
          metalPipelineKnownArchiveMisses.get(),
          metalPipelineCacheHits.get(), metalPipelinesUnsupported.get(),
          metalPipelinesFailed.get(), metalPipelineQueue.size(),
          metalPipelineFailureReasons.summary().isEmpty()
              ? "none" : metalPipelineFailureReasons.summary());
    }
  }

  private void flushMetalPipelineCacheIfNeeded(
      IrisMetalPipelineCompiler.Readiness readiness) {
    if (readiness != IrisMetalPipelineCompiler.Readiness.READY
        || metalPipelineCacheFlushed.get()
        || metalPipelineIdentityLines.isEmpty()
        || metalPipelineQueueRejected.get() != 0
        || metalPipelinesFailed.get() != 0) {
      return;
    }
    if (metalPipelineCompiler.flush()) {
      metalPipelineCacheFlushed.set(true);
    } else {
      metalPipelineFlushFailures.incrementAndGet();
      recordMetalPipelineFailure("pipeline-cache-flush");
    }
  }

  private void retainMetalPipelineIdentity(IrisMetalPipelineKey pipelineKey,
      IrisPipelineStateKey metalStateKey,
      IrisProgramResourceLayout layout,
      IrisMslArgumentLayout argumentLayout) {
    String identity = pipelineKey.sha256() + '|'
        + metalStateKey.sha256() + '|'
        + layout.key().sha256() + '|'
        + argumentLayout.sha256() + '\n';
    if (metalPipelineIdentityLines.size()
        >= DEFAULT_METAL_PIPELINE_IDENTITY_CAPACITY
        && !metalPipelineIdentityLines.contains(identity)) {
      metalPipelineIdentitySetComplete.set(false);
      recordMetalPipelineFailure("metal-pipeline-identity-capacity-exceeded");
      return;
    }
    metalPipelineIdentityLines.add(identity);
  }

  private void retainCompiledMetalPipeline(MetalPipelineCandidate candidate,
      IrisMetalPipelineKey pipelineKey, IrisPipelineState metalState,
      IrisMetalVertexBindingLayout vertexBindings) {
    String identity = candidateIdentity(candidate.program(),
        candidate.stateKey());
    if (compiledMetalPipelines.size()
        >= DEFAULT_METAL_PIPELINE_IDENTITY_CAPACITY
        && !compiledMetalPipelines.containsKey(identity)) {
      metalPipelineIdentitySetComplete.set(false);
      recordMetalPipelineFailure("compiled-pipeline-map-capacity-exceeded");
      return;
    }
    compiledMetalPipelines.put(identity, new CompiledMetalPipeline(
        pipelineKey, metalState, vertexBindings));
  }

  private void retainCutoverPipelineLookup(
      IrisPipelineStateCapture.PendingState pending,
      String candidateIdentity) {
    IrisPipelineStateCapture.PipelineLookupKey lookup =
        IrisPipelineStateCapture.lookupKey(pending);
    if (cutoverPipelineIdentities.size()
        >= DEFAULT_CUTOVER_PIPELINE_LOOKUP_CAPACITY
        && !cutoverPipelineIdentities.containsKey(lookup)) {
      cutoverFailureReasons.add("cutover-pipeline-lookup-capacity-exceeded");
      cutoverLastFailure.set("cutover-pipeline-lookup-capacity-exceeded");
      return;
    }
    cutoverPipelineIdentities.put(lookup, candidateIdentity);
  }

  private static String candidateIdentity(
      IrisProgramIdentityRegistry.ResolvedProgram program,
      IrisPipelineStateKey stateKey) {
    return program.shaderKey().sha256() + ':' + stateKey.sha256();
  }

  private void recordMetalPipelineFailure(String reason) {
    metalPipelinesFailed.incrementAndGet();
    metalPipelineLastFailure.set(reason);
    metalPipelineFailureReasons.add(reason);
  }

  private boolean hasRequiredRenderGraphCoverage() {
    return renderGraphPhases.contains(IrisRenderGraph.Phase.SHADOW.name())
        && renderGraphPhases.contains(IrisRenderGraph.Phase.GEOMETRY.name())
        && renderGraphPhases.contains(IrisRenderGraph.Phase.COMPOSITE.name())
        && renderGraphPhases.contains(IrisRenderGraph.Phase.FINAL.name())
        && renderGraphBarriers.get() > 0
        && renderGraphClears.get() > 0
        && renderGraphTransfers.get() > 0
        && renderGraphPingPongResources.get() > 0;
  }

  private void resolveResourceBindings(
      IrisProgramIdentityRegistry.ResolvedProgram program,
      IrisGlResourceBindingSnapshot snapshot) {
    resourceBindingVariantsAttempted.incrementAndGet();
    IrisProgramResourceLayout layout = resourceLayouts.get(
        program.shaderKey().sha256());
    if (layout == null) {
      recordIncompleteResourceBindings("program-layout-unavailable");
      return;
    }
    IrisResourceBindingResolver.Result result =
        IrisResourceBindingResolver.resolve(layout, snapshot);
    if (result instanceof IrisResourceBindingResolver.Incomplete incomplete) {
      recordIncompleteResourceBindings(incomplete.reason());
      return;
    }
    IrisResourceBindingResolver.Complete complete =
        (IrisResourceBindingResolver.Complete) result;
    resourceBindingsMatched.addAndGet(complete.matchedResources());
    resourceBindingVariantsSucceeded.incrementAndGet();
  }

  private void recordIncompleteResourceBindings(String reason) {
    resourceBindingVariantsIncomplete.incrementAndGet();
    resourceBindingIncompleteReasons.add(reason);
    resourceLayoutLastFailure.set(reason);
  }

  private static void addDistinct(List<String> values, String value) {
    if (!values.contains(value)) {
      values.add(value);
    }
  }

  private void recordPipelineStateUnsupported(String reason) {
    pipelineStatesUnsupported.incrementAndGet();
    pipelineStateLastFailure.set(reason);
    pipelineStateUnsupportedReasons.add(reason);
  }

  private void recordFailure(Throwable error) {
    String redacted = redactedFailure(error);
    String reason = diagnosticFailureReason(error);
    translationFailureReasons.add(reason);
    lastFailure.set(redacted);
    failed.incrementAndGet();
    long warnings = warningCount.incrementAndGet();
    if (warnings <= 3 || warnings % 32 == 0) {
      MetalLogger.warn(
          "Iris Metal background translation failed open; Iris OpenGL remains active (%s, reason=%s, failure %d)",
          redacted, reason, warnings);
    }
  }

  private void recordFailure(Throwable error,
      IrisFinalShaderProgram program) {
    String name = program.programName().replaceAll(
        "[^A-Za-z0-9._:/-]", "_");
    if (name.length() > 80) {
      name = name.substring(0, 80);
    }
    String reason = diagnosticFailureReason(error);
    translationFailureReasons.add(reason);
    String redacted = "program=" + name + ":" + redactedFailure(error);
    lastFailure.set(redacted);
    failed.incrementAndGet();
    long warnings = warningCount.incrementAndGet();
    if (warnings <= 3 || warnings % 32 == 0) {
      MetalLogger.warn(
          "Iris Metal background translation failed open; Iris OpenGL remains active (%s, reason=%s, failure %d)",
          redacted, reason, warnings);
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
        cacheRoot, lastFailure.get(), translationFailureReasons.size(),
        translationFailureReasons.complete(),
        translationFailureReasons.sha256(),
        translationFailureReasons.summary(),
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
        pipelineStateLastFailure.get(),
        resourceProgramsAttempted.get(), resourceProgramsSucceeded.get(),
        resourceProgramsUnsupported.get(), resourceProgramsFailed.get(),
        resourceStagesReflected.get(), resourceBindingsReflected.get(),
        resourceLayoutIdentityLines.size(),
        resourceLayoutSetComplete.get(), resourceLayoutSetSha256(),
        resourceLayoutLastFailure.get(),
        resourceBindingVariantsAttempted.get(),
        resourceBindingVariantsSucceeded.get(),
        resourceBindingVariantsIncomplete.get(), resourceBindingsMatched.get(),
        resourceBindingIncompleteReasons.size(),
        resourceBindingIncompleteReasons.complete(),
        resourceBindingIncompleteReasons.sha256(),
        resourceBindingIncompleteReasons.summary());
  }

  private RenderGraphStatus snapshotRenderGraph() {
    return new RenderGraphStatus(renderGraphCapture.framesStarted(),
        renderGraphCapture.framesCompleted(),
        renderGraphCapture.framesRejected(), renderGraphCapture.queued(),
        renderGraphCapture.frozen(), renderGraphsAttempted.get(),
        renderGraphsSucceeded.get(), renderGraphsUnsupported.get(),
        renderGraphsFailed.get(), renderGraphResources.get(),
        renderGraphNodes.get(), renderGraphEdges.get(),
        renderGraphBarriers.get(), renderGraphClears.get(),
        renderGraphTransfers.get(),
        renderGraphPingPongResources.get(),
        String.join(",", renderGraphPhases),
        renderGraphIdentityLines.size(), renderGraphSetComplete.get(),
        renderGraphSetSha256(), renderGraphUnsupportedReasons.size(),
        renderGraphUnsupportedReasons.complete(),
        renderGraphUnsupportedReasons.sha256(),
        renderGraphLastFailure.get());
  }

  private AcceptanceStatus snapshotAcceptance() {
    synchronized (acceptanceSnapshotLock) {
      return new AcceptanceStatus(snapshot(), snapshotRenderGraph(),
          snapshotMetalGraphResources(), snapshotFullGraph(),
          snapshotShadowPlan(), snapshotMetalPipelineCache(),
          snapshotShadowReplay(), snapshotVisualParity());
    }
  }

  private MetalGraphResourceStatus snapshotMetalGraphResources() {
    return new MetalGraphResourceStatus(
        NativeIrisMetalGraphResources.isOptedIn(),
        metalGraphResources.runtimeAvailable(),
        metalGraphResourcePlansObserved.get(),
        metalGraphResourcePlansComplete.get(),
        metalGraphResourcePlansBlocked.get(),
        metalGraphResourceAllocations.get(),
        metalGraphResourceNativeAttempts.get(),
        metalGraphResourceNativeSucceeded.get(),
        metalGraphResourceNativeFailed.get(),
        metalGraphResourceNativeTextureCount.get(),
        metalGraphResourceNativeTextureBytes.get(),
        metalGraphResourceBlockers.size(),
        metalGraphResourceBlockers.complete(),
        metalGraphResourceBlockers.sha256(),
        metalGraphResourceBlockers.summary());
  }

  private FullGraphStatus snapshotFullGraph() {
    boolean baselineValidated = NativeIrisMetalGraphExecutor.isOptedIn()
        && (fullGraphValidationEstablished.get()
            || fullGraphFramesSucceeded.get()
                >= MINIMUM_FULL_GRAPH_SUCCESS_FRAMES)
        && fullGraphFramesUnsupported.get() == 0
        && fullGraphFramesFailed.get() == 0
        && fullGraphLastOutputHash.get() != 0
        && fullGraphBlockers.size() == 0
        && fullGraphBlockers.complete();
    boolean ownershipOptedIn = NativeIrisMetalGraphExecutor
        .isOwnershipOptedIn();
    boolean performanceEligible = baselineValidated && ownershipOptedIn
        && fullGraphOwnershipFramesPresented.get() > 0
        && fullGraphOwnershipCommandsSuppressed.get() > 0
        && fullGraphOwnershipFailures.get() == 0;
    String ownershipMode = !ownershipOptedIn
        ? "OPENGL_VISIBLE_VALIDATION"
        : performanceEligible ? "METAL_FULL_GRAPH_OWNERSHIP"
            : "METAL_FULL_GRAPH_PRIMING";
    boolean validated = baselineValidated && (ownershipOptedIn
        || preparedMetalGraphFrameCount.get() == 0
            && fullGraphCapturesOutstanding.get() == 0);
    return new FullGraphStatus(NativeIrisMetalGraphExecutor.isOptedIn(),
        metalGraphResources.runtimeAvailable(),
        fullGraphCapturesOutstanding.get() != 0,
        fullGraphCaptureRequests.get(), fullGraphFramesObserved.get(),
        fullGraphFramesPlanned.get(), preparedMetalGraphFrameCount.get(),
        fullGraphFramesAttempted.get(), fullGraphFramesSucceeded.get(),
        fullGraphFramesUnsupported.get(), fullGraphFramesFailed.get(),
        fullGraphOperations.get(), fullGraphDraws.get(),
        fullGraphClears.get(), fullGraphTransfers.get(),
        fullGraphBarriers.get(), fullGraphCapturedBytes.get(),
        initializedMetalGraphTokens.size(),
        Long.toUnsignedString(fullGraphLastOutputHash.get()),
        MINIMUM_FULL_GRAPH_SUCCESS_FRAMES, validated,
        ownershipMode, performanceEligible, ownershipOptedIn,
        fullGraphOwnershipSubmissions.get(), fullGraphOwnershipReady.get(),
        fullGraphOwnershipFramesArmed.get(),
        fullGraphOwnershipFramesPresented.get(),
        fullGraphOwnershipFramesReused.get(),
        fullGraphOwnershipFramesInvalidated.get(),
        fullGraphOwnershipCommandsSuppressed.get(),
        fullGraphOwnershipFailures.get(),
        fullGraphOwnershipLastFailure.get(), fullGraphBlockers.size(),
        fullGraphBlockers.complete(), fullGraphBlockers.sha256(),
        fullGraphBlockers.summary(), fullGraphLastFailure.get());
  }

  private MetalPipelineCacheStatus snapshotMetalPipelineCache() {
    IrisMetalPipelineCompiler.NativeStatus nativeStatus =
        metalPipelineCompiler.nativeStatus();
    return new MetalPipelineCacheStatus(
        NativeIrisMetalPipelineCompiler.isOptedIn(),
        metalPipelineReadiness.get().name(),
        metalPipelineCompiler.deviceCompilerSha256(),
        metalPipelineCandidateKeys.size(), metalPipelineBlockedKeys.size(),
        metalPipelineQueue.size(), metalPipelinesAttempted.get(),
        metalPipelinesCompiled.get(), metalPipelineCacheHits.get(),
        metalPipelineNewVariantsCompiled.get(),
        metalPipelineKnownArchiveMisses.get(),
        metalPipelinesUnsupported.get(), metalPipelinesFailed.get(),
        metalPipelineQueueRejected.get(), metalPipelineCacheFlushed.get(),
        metalPipelineFlushFailures.get(), metalPipelineIdentityLines.size(),
        metalPipelineIdentitySetComplete.get(), metalPipelineSetSha256(),
        metalPipelineFailureReasons.size(),
        metalPipelineFailureReasons.complete(),
        metalPipelineFailureReasons.sha256(),
        metalPipelineLastFailure.get(), nativeStatus);
  }

  private ShadowPlanStatus snapshotShadowPlan() {
    return new ShadowPlanStatus(shadowPlansObserved.get(),
        shadowPlansStructurallyComplete.get(), shadowPlansBlocked.get(),
        shadowExecutionSteps.get(), shadowPlanBlockers.size(),
        shadowPlanBlockers.complete(), shadowPlanBlockers.sha256(),
        shadowPlanBlockers.summary());
  }

  private ShadowBufferStatus snapshotShadowBuffers() {
    return new ShadowBufferStatus(IrisGlBufferMirror.isEnabled(),
        shadowBufferPlansObserved.get(), shadowBufferPlansComplete.get(),
        shadowBufferPlansBlocked.get(), shadowBufferImages.get(),
        shadowBufferBytes.get(), shadowBufferBlockers.size(),
        shadowBufferBlockers.complete(), shadowBufferBlockers.sha256(),
        shadowBufferBlockers.summary());
  }

  private ShadowArgumentStatus snapshotShadowArguments() {
    return new ShadowArgumentStatus(IrisGlBufferMirror.isEnabled(),
        shadowArgumentPlansObserved.get(), shadowArgumentPlansComplete.get(),
        shadowArgumentPlansBlocked.get(), shadowArgumentsResolved.get(),
        shadowInlineUniformBytes.get(), shadowArgumentBlockers.size(),
        shadowArgumentBlockers.complete(), shadowArgumentBlockers.sha256(),
        shadowArgumentBlockers.summary());
  }

  private ShadowReplayStatus snapshotShadowReplay() {
    String phases = shadowReplayPhases.stream().sorted()
        .map(Enum::name).collect(java.util.stream.Collectors.joining(","));
    return new ShadowReplayStatus(
        NativeIrisMetalShadowReplayer.isOptedIn(),
        shadowReplayDrawsObserved.get(), shadowReplayDrawsReady.get(),
        shadowReplayDrawsAttempted.get(), shadowReplayDrawsSucceeded.get(),
        shadowReplayDrawsUnsupported.get(), shadowReplayDrawsFailed.get(),
        shadowReplayDrawsBlocked.get(), shadowReplayCandidateKeys.size(),
        shadowReplayCandidateSetComplete.get(), shadowReplayPhases.size(),
        phases, Long.toUnsignedString(shadowReplayLastColorHash.get()),
        shadowReplayLastWidth.get(), shadowReplayLastHeight.get(),
        shadowReplayBlockers.size(), shadowReplayBlockers.complete(),
        shadowReplayBlockers.sha256(), shadowReplayBlockers.summary());
  }

  private VisualParityStatus snapshotVisualParity() {
    IrisVisualParityCapture.Status capture =
        IrisVisualParityCapture.global().status();
    IrisVisualParityGate.Status gate = visualParityGate.status();
    boolean validated = IrisVisualParityCapture.isOptedIn()
        && gate.validated() && capture.failed() == 0
        && capture.dropped() == 0
        && visualParityMissingOpenGl.get() == 0
        && visualParityMissingMetal.get() == 0
        && visualParityDimensionMismatches.get() == 0
        && visualParityReplayFailures.get() == 0
        && visualParityBlockers.size() == 0
        && visualParityBlockers.complete();
    return new VisualParityStatus(IrisVisualParityCapture.isOptedIn(),
        FINAL_VISUAL_PARITY_THRESHOLDS.channelTolerance(),
        FINAL_VISUAL_PARITY_THRESHOLDS.maxDifferentPixelRatio(),
        FINAL_VISUAL_PARITY_THRESHOLDS.maxRootMeanSquareError(),
        FINAL_VISUAL_PARITY_THRESHOLDS.minimumFrames(),
        FINAL_VISUAL_PARITY_THRESHOLDS.requiredConsecutivePasses(),
        capture.scheduled(), capture.captured(), capture.failed(),
        capture.dropped(), capture.pending(), capture.lastFailure(),
        visualParitySamplesHandled.get(),
        visualParityMissingOpenGl.get(), visualParityMissingMetal.get(),
        visualParityDimensionMismatches.get(),
        visualParityReplayFailures.get(), gate.framesCompared(),
        gate.framesPassed(), gate.framesFailed(), gate.consecutivePasses(),
        gate.worstDifferentPixelRatio(), gate.worstRootMeanSquareError(),
        gate.worstChannelDelta(), validated, "native-row-order",
        visualParityBlockers.size(), visualParityBlockers.complete(),
        visualParityBlockers.sha256(), visualParityBlockers.summary());
  }

  private CutoverStatus snapshotCutover() {
    IrisSelectiveCutoverGate.Status gate = cutoverGate.status();
    return new CutoverStatus(isCutoverOptedIn(), gate.mode().name(),
        gate.currentFrame(), gate.contextGeneration(), gate.frameFallback(),
        cutoverDrawsObserved.get(), cutoverDrawsEligible.get(),
        cutoverMetalAttempts.get(), cutoverMetalSucceeded.get(),
        cutoverPresentations.get(), gate.openGlSuppressions(),
        gate.frameFallbacks(), gate.lifecycleResets(), cutoverFailures.get(),
        IrisMetalCutoverPresenter.BRIDGE_NAME,
        cutoverGpuInputTextures.get(), cutoverGpuInputBytes.get(),
        cutoverCpuInputTextures.get(), cutoverCpuInputBytes.get(),
        cutoverGpuInputBuffers.get(), cutoverGpuInputBufferBytes.get(),
        cutoverCpuInputBuffers.get(), cutoverCpuInputBufferBytes.get(),
        cutoverPipelineIdentities.size(), cutoverFailureReasons.size(),
        cutoverFailureReasons.complete(), cutoverFailureReasons.sha256(),
        cutoverFailureReasons.summary(), cutoverLastFailure.get());
  }

  private static VisualParityStatus emptyVisualParityStatus(
      IrisVisualParityCapture.Status capture) {
    return new VisualParityStatus(IrisVisualParityCapture.isOptedIn(),
        FINAL_VISUAL_PARITY_THRESHOLDS.channelTolerance(),
        FINAL_VISUAL_PARITY_THRESHOLDS.maxDifferentPixelRatio(),
        FINAL_VISUAL_PARITY_THRESHOLDS.maxRootMeanSquareError(),
        FINAL_VISUAL_PARITY_THRESHOLDS.minimumFrames(),
        FINAL_VISUAL_PARITY_THRESHOLDS.requiredConsecutivePasses(),
        capture.scheduled(), capture.captured(), capture.failed(),
        capture.dropped(), capture.pending(), capture.lastFailure(),
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, false,
        "native-row-order", 0, true, emptyPipelineStateSetSha256(), "");
  }

  private String renderGraphSetSha256() {
    MessageDigest digest = newSha256();
    for (String line : renderGraphIdentityLines) {
      digest.update(line.getBytes(StandardCharsets.US_ASCII));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private String metalPipelineSetSha256() {
    MessageDigest digest = newSha256();
    for (String line : metalPipelineIdentityLines) {
      digest.update(line.getBytes(StandardCharsets.US_ASCII));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private String resourceLayoutSetSha256() {
    MessageDigest digest = newSha256();
    for (String line : resourceLayoutIdentityLines) {
      digest.update(line.getBytes(StandardCharsets.US_ASCII));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String redactedFailure(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName();
  }

  /** Stable, bounded diagnostic text; never includes captured shader source. */
  static String diagnosticFailureReason(Throwable error) {
    Objects.requireNonNull(error, "error");
    Throwable current = error;
    String message = null;
    while (current != null) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        message = current.getMessage();
      }
      Throwable cause = current.getCause();
      if (cause == null || cause == current) {
        break;
      }
      current = cause;
    }
    if (message == null) {
      message = current.getClass().getSimpleName();
    }
    return BoundedReasonSet.normalizeReason(message);
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
    if (!metalPipelineQueue.isEmpty()) {
      metalPipelineLastFailure.compareAndSet("", "shutdown-with-pending");
    }
    metalPipelineQueue.clear();
    if (metalPipelineReadiness.get()
        == IrisMetalPipelineCompiler.Readiness.READY
        && !metalPipelineIdentityLines.isEmpty()
        && !metalPipelineCacheFlushed.get()) {
      metalPipelineCompiler.flush();
    }
    metalPipelineCompiler.close();
    for (PendingGraphPresentation inFlight : inFlightGraphPresentations) {
      metalGraphExecutor.discardPresentation(inFlight.token());
    }
    for (ReadyGraphPresentation ready : readyGraphPresentations) {
      metalGraphExecutor.discardPresentation(ready.status().token());
    }
    inFlightGraphPresentations.clear();
    readyGraphPresentations.clear();
    graphPresentationBacklog.set(0);
    ownershipFrame = null;
    lastOwnershipPresentationState = null;
    lastOwnershipPresentationWidth = 0;
    lastOwnershipPresentationHeight = 0;
    PreparedMetalGraphFrame abandoned;
    while ((abandoned = preparedMetalGraphFrames.poll()) != null) {
      if (abandoned.encodedPacket() != null) {
        abandoned.encodedPacket().close();
      }
      IrisGlTextureGpuHandoff.abandonCapturedSurfaces(
          abandoned.fullReplayTextures());
    }
    preparedMetalGraphFrameCount.set(0);
    initializedMetalGraphTokens.clear();
    currentMetalGraphTextures.set(Map.of());
    fullGraphCapturesOutstanding.set(0);
    fullGraphValidationEstablished.set(false);
    metalGraphResources.reset();
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

  private static final class TimingAccumulator {
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong lastNanos = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong maximumNanos = new AtomicLong();

    private void record(long elapsedNanos) {
      if (elapsedNanos <= 0) {
        return;
      }
      lastNanos.set(elapsedNanos);
      totalNanos.addAndGet(elapsedNanos);
      long maximum = maximumNanos.get();
      while (maximum < elapsedNanos
          && !maximumNanos.compareAndSet(maximum, elapsedNanos)) {
        maximum = maximumNanos.get();
      }
      samples.incrementAndGet();
    }

    private TimingSnapshot snapshot() {
      return new TimingSnapshot(samples.get(), lastNanos.get(),
          totalNanos.get(), maximumNanos.get());
    }
  }

  private record TimingSnapshot(long samples, long lastNanos,
                                long totalNanos, long maximumNanos) {
    private double lastMs() {
      return lastNanos / 1_000_000.0;
    }

    private double averageMs() {
      return samples == 0 ? 0.0
          : totalNanos / (double) samples / 1_000_000.0;
    }

    private double maximumMs() {
      return maximumNanos / 1_000_000.0;
    }
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

  private record MetalPipelineCandidate(
      IrisProgramIdentityRegistry.ResolvedProgram program,
      IrisPipelineState state, IrisPipelineStateKey stateKey,
      boolean stateCacheHit) {
    private MetalPipelineCandidate {
      Objects.requireNonNull(program, "program");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(stateKey, "stateKey");
    }
  }

  private record CompiledMetalPipeline(IrisMetalPipelineKey pipelineKey,
                                       IrisPipelineState state,
                                       IrisMetalVertexBindingLayout
                                           vertexBindings) {
    private CompiledMetalPipeline {
      Objects.requireNonNull(pipelineKey, "pipelineKey");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(vertexBindings, "vertexBindings");
    }
  }

  private record PendingGraphPresentation(
      long token,
      IrisPipelineStateCapture.PendingState presentationState,
      long displayPresentationGeneration) {
    private PendingGraphPresentation {
      if (token <= 0 || displayPresentationGeneration <= 0) {
        throw new IllegalArgumentException("invalid pending presentation");
      }
      Objects.requireNonNull(presentationState, "presentationState");
    }
  }

  private record ReadyGraphPresentation(
      NativeIrisMetalGraphExecutor.PresentationStatus status,
      IrisPipelineStateCapture.PendingState presentationState,
      long displayPresentationGeneration) {
    private ReadyGraphPresentation {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(presentationState, "presentationState");
      if (displayPresentationGeneration <= 0 || status.state()
          != NativeIrisMetalGraphExecutor.PresentationState.READY) {
        throw new IllegalArgumentException("presentation is not ready");
      }
    }
  }

  private static final class OwnershipFrame {
    private final long frame;
    private final long contextGeneration;
    private final IrisPipelineStateCapture.PendingState presentationState;
    private final int width;
    private final int height;
    private final boolean promotedNewSurface;
    private IrisPipelineStateCapture.PendingState finalPresentationState;
    private long commandsSuppressed;
    private boolean degraded;
    private String lastFailure = "";

    private OwnershipFrame(long frame, long contextGeneration,
        IrisPipelineStateCapture.PendingState presentationState,
        int width, int height, boolean promotedNewSurface) {
      if (frame <= 0 || contextGeneration <= 0 || width <= 0 || height <= 0) {
        throw new IllegalArgumentException("invalid ownership frame");
      }
      this.frame = frame;
      this.contextGeneration = contextGeneration;
      this.presentationState = Objects.requireNonNull(presentationState,
          "presentationState");
      this.width = width;
      this.height = height;
      this.promotedNewSurface = promotedNewSurface;
    }
  }

  private record PreparedMetalGraphFrame(
      IrisMetalGraphFramePacketEncoder.Frame frame,
      List<Long> writtenTokens,
      IrisVisualParityCapture.CapturedFrame openGlFrame,
      IrisPipelineStateCapture.PendingState presentationState,
      IrisMetalGraphFramePacketEncoder.DirectPacket encodedPacket,
      long displayPresentationGeneration,
      List<IrisGlTextureMirror.TextureSnapshot> fullReplayTextures,
      long drawCount,
      Mode mode) {
    private PreparedMetalGraphFrame(
        IrisMetalGraphFramePacketEncoder.Frame frame,
        List<NativeIrisMetalGraphResources.Binding> bindings,
        java.util.Set<Integer> writtenResourceIds,
        IrisVisualParityCapture.CapturedFrame openGlFrame,
        IrisPipelineStateCapture.PendingState presentationState,
        IrisMetalGraphFramePacketEncoder.DirectPacket encodedPacket,
        long displayPresentationGeneration,
        List<IrisGlTextureMirror.TextureSnapshot> fullReplayTextures,
        Mode mode) {
      this(frame, writtenTokens(bindings, writtenResourceIds), openGlFrame,
          presentationState, encodedPacket, displayPresentationGeneration,
          List.copyOf(fullReplayTextures), drawCount(frame), mode);
    }

    private PreparedMetalGraphFrame {
      Objects.requireNonNull(frame, "frame");
      writtenTokens = List.copyOf(writtenTokens);
      fullReplayTextures = List.copyOf(fullReplayTextures);
      Objects.requireNonNull(mode, "mode");
      if (writtenTokens.isEmpty() || displayPresentationGeneration <= 0
          || drawCount < 0) {
        throw new IllegalArgumentException("invalid prepared Metal graph");
      }
      if (mode == Mode.VALIDATION
              != (openGlFrame != null && presentationState == null
                  && encodedPacket == null)
          || mode == Mode.PRESENTATION
              != (openGlFrame == null && presentationState != null
                  && encodedPacket != null && encodedPacket.length() > 0)) {
        throw new IllegalArgumentException("invalid prepared graph mode");
      }
    }

    private static List<Long> writtenTokens(
        List<NativeIrisMetalGraphResources.Binding> bindings,
        java.util.Set<Integer> writtenResourceIds) {
      Objects.requireNonNull(bindings, "bindings");
      Objects.requireNonNull(writtenResourceIds, "writtenResourceIds");
      ArrayList<Long> tokens = new ArrayList<>();
      for (NativeIrisMetalGraphResources.Binding binding : bindings) {
        if (writtenResourceIds.contains(binding.resourceId())) {
          tokens.add(binding.token());
        }
      }
      return List.copyOf(tokens);
    }

    private static long drawCount(
        IrisMetalGraphFramePacketEncoder.Frame frame) {
      long draws = 0;
      for (IrisMetalGraphFramePacketEncoder.Operation operation
          : frame.operations()) {
        if (operation instanceof IrisMetalGraphFramePacketEncoder.Draw) {
          draws++;
        }
      }
      return draws;
    }

    private enum Mode {
      VALIDATION,
      PRESENTATION
    }
  }

  private record MetalGraphTextureReference(
      long token, String format, int sampleCount, int width, int height,
      int depthOrLayers, int mipLevels) {
    private MetalGraphTextureReference {
      Objects.requireNonNull(format, "format");
      if (token <= 0 || format.isBlank() || sampleCount <= 0
          || width <= 0 || height <= 0 || depthOrLayers <= 0
          || mipLevels <= 0) {
        throw new IllegalArgumentException(
            "invalid Metal graph texture reference");
      }
    }
  }

  private record MetalGraphResourceResolution(
      NativeIrisMetalGraphResources.Complete resources, String blocker) {
    private MetalGraphResourceResolution {
      Objects.requireNonNull(blocker, "blocker");
      if ((resources == null) == blocker.isEmpty()
          || blocker.length() > 160) {
        throw new IllegalArgumentException(
            "invalid Metal graph resource resolution");
      }
    }

    static MetalGraphResourceResolution complete(
        NativeIrisMetalGraphResources.Complete resources) {
      return new MetalGraphResourceResolution(
          Objects.requireNonNull(resources, "resources"), "");
    }

    static MetalGraphResourceResolution blocked(String blocker) {
      return new MetalGraphResourceResolution(null, blocker);
    }
  }

  private record TargetExtent(int width, int height) {
    private TargetExtent {
      if (width <= 0 || height <= 0
          || width > IrisMetalShadowReplayPacketEncoder.MAX_TARGET_EXTENT
          || height > IrisMetalShadowReplayPacketEncoder.MAX_TARGET_EXTENT) {
        throw new IllegalArgumentException("invalid replay target extent");
      }
    }
  }

  public record Status(boolean enabled, boolean running, int queued,
                       long rejected, long captureFailures,
                       long attempted, long translated, long cacheHits,
                       long failed, String cacheRoot, String lastFailure,
                       int translationFailureReasonCount,
                       boolean translationFailureReasonSetComplete,
                       String translationFailureReasonSetSha256,
                       String translationFailureReasonSummary,
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
                       String pipelineStateLastFailure,
                       long resourceProgramsAttempted,
                       long resourceProgramsSucceeded,
                       long resourceProgramsUnsupported,
                       long resourceProgramsFailed,
                       long resourceStagesReflected,
                       long resourceBindingsReflected,
                       int resourceLayoutIdentityCount,
                       boolean resourceLayoutSetComplete,
                       String resourceLayoutSetSha256,
                       String resourceLayoutLastFailure,
                       long resourceBindingVariantsAttempted,
                       long resourceBindingVariantsSucceeded,
                       long resourceBindingVariantsIncomplete,
                       long resourceBindingsMatched,
                       int resourceBindingIncompleteReasonCount,
                       boolean resourceBindingIncompleteReasonSetComplete,
                       String resourceBindingIncompleteReasonSetSha256,
                       String resourceBindingIncompleteReasonSummary) {
    public Status {
      Objects.requireNonNull(cacheRoot, "cacheRoot");
      Objects.requireNonNull(lastFailure, "lastFailure");
      Objects.requireNonNull(translationFailureReasonSetSha256,
          "translationFailureReasonSetSha256");
      Objects.requireNonNull(translationFailureReasonSummary,
          "translationFailureReasonSummary");
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
      Objects.requireNonNull(resourceLayoutSetSha256,
          "resourceLayoutSetSha256");
      Objects.requireNonNull(resourceLayoutLastFailure,
          "resourceLayoutLastFailure");
      Objects.requireNonNull(resourceBindingIncompleteReasonSetSha256,
          "resourceBindingIncompleteReasonSetSha256");
      Objects.requireNonNull(resourceBindingIncompleteReasonSummary,
          "resourceBindingIncompleteReasonSummary");
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

    public boolean resourceReflectionComplete() {
      return resourceProgramsAttempted > 0
          && resourceProgramsAttempted == resourceProgramsSucceeded
          && resourceProgramsUnsupported == 0
          && resourceProgramsFailed == 0
          && resourceStagesReflected > 0
          && resourceLayoutIdentityCount == resourceProgramsSucceeded
          && resourceLayoutSetComplete;
    }

    public boolean resourceBindingCaptureComplete() {
      return resourceReflectionComplete()
          && resourceBindingVariantsAttempted > 0
          && resourceBindingVariantsAttempted
              == resourceBindingVariantsSucceeded
          && resourceBindingVariantsIncomplete == 0
          && resourceBindingsMatched > 0
          && resourceBindingIncompleteReasonSetComplete;
    }
  }

  public record RenderGraphStatus(long framesStarted, long framesCompleted,
                                  long framesRejected, int framesPending,
                                  boolean captureFrozen,
                                  long graphsAttempted,
                                  long graphsSucceeded,
                                  long graphsUnsupported,
                                  long graphsFailed,
                                  long resourcesRepresented,
                                  long nodesRepresented,
                                  long edgesRepresented,
                                  long barriersRepresented,
                                  long clearsRepresented,
                                  long transfersRepresented,
                                  long pingPongResourcesRepresented,
                                  String phaseSummary,
                                  int graphIdentityCount,
                                  boolean graphSetComplete,
                                  String graphSetSha256,
                                  int unsupportedReasonCount,
                                  boolean unsupportedReasonSetComplete,
                                  String unsupportedReasonSetSha256,
                                  String lastFailure) {
    public RenderGraphStatus {
      Objects.requireNonNull(phaseSummary, "phaseSummary");
      Objects.requireNonNull(graphSetSha256, "graphSetSha256");
      Objects.requireNonNull(unsupportedReasonSetSha256,
          "unsupportedReasonSetSha256");
      Objects.requireNonNull(lastFailure, "lastFailure");
    }

    public boolean complete() {
      boolean productionOwnership =
          NativeIrisMetalGraphExecutor.isOwnershipOptedIn();
      boolean frameAccounting = productionOwnership
          ? !captureFrozen
              && framesPending <= IrisRenderGraphCapture.FRAME_QUEUE_CAPACITY
              && framesCompleted >= graphsAttempted
              && framesCompleted - graphsAttempted == framesPending
          : captureFrozen && framesPending == 0
              && graphsAttempted == framesCompleted;
      return framesStarted > 0
          && framesCompleted > 0
          && framesRejected == 0
          && framesStarted >= framesCompleted
          && frameAccounting
          && graphsAttempted == graphsSucceeded
          && graphsUnsupported == 0
          && graphsFailed == 0
          && resourcesRepresented > 0
          && nodesRepresented > 0
          && edgesRepresented > 0
          && barriersRepresented > 0
          && clearsRepresented > 0
          && transfersRepresented > 0
          && pingPongResourcesRepresented > 0
          && phaseSummary.contains(IrisRenderGraph.Phase.SHADOW.name())
          && phaseSummary.contains(IrisRenderGraph.Phase.GEOMETRY.name())
          && phaseSummary.contains(IrisRenderGraph.Phase.COMPOSITE.name())
          && phaseSummary.contains(IrisRenderGraph.Phase.FINAL.name())
          && graphIdentityCount > 0
          && graphSetComplete
          && unsupportedReasonSetComplete;
    }
  }

  public record AcceptanceStatus(Status translation,
                                 RenderGraphStatus renderGraph,
                                 MetalGraphResourceStatus graphResources,
                                 FullGraphStatus fullGraph,
                                 ShadowPlanStatus shadowPlan,
                                 MetalPipelineCacheStatus metalPipelines,
                                 ShadowReplayStatus shadowReplay,
                                 VisualParityStatus visualParity) {
    public AcceptanceStatus {
      Objects.requireNonNull(translation, "translation");
      Objects.requireNonNull(renderGraph, "renderGraph");
      Objects.requireNonNull(graphResources, "graphResources");
      Objects.requireNonNull(fullGraph, "fullGraph");
      Objects.requireNonNull(shadowPlan, "shadowPlan");
      Objects.requireNonNull(metalPipelines, "metalPipelines");
      Objects.requireNonNull(shadowReplay, "shadowReplay");
      Objects.requireNonNull(visualParity, "visualParity");
    }
  }

  public record MetalGraphResourceStatus(
      boolean enabled, boolean runtimeAvailable,
      long plansObserved, long plansComplete, long plansBlocked,
      long allocationsRequested, long nativeAttempts,
      long nativeSucceeded, long nativeFailed,
      int nativeTextureCount, long nativeTextureBytes,
      int blockerCount, boolean blockerSetComplete,
      String blockerSetSha256, String blockerSummary) {
    public MetalGraphResourceStatus {
      Objects.requireNonNull(blockerSetSha256, "blockerSetSha256");
      Objects.requireNonNull(blockerSummary, "blockerSummary");
    }

    public boolean complete() {
      return enabled && runtimeAvailable && plansObserved > 0
          && plansObserved == plansComplete && plansBlocked == 0
          && allocationsRequested > 0
          && nativeAttempts == plansComplete
          && nativeSucceeded == nativeAttempts && nativeFailed == 0
          && nativeTextureCount > 0 && nativeTextureBytes > 0
          && blockerCount == 0 && blockerSetComplete;
    }

    public boolean safeUnsupportedFallback() {
      return enabled && !runtimeAvailable && plansObserved > 0
          && plansObserved == plansComplete && plansBlocked == 0
          && allocationsRequested > 0 && nativeAttempts == 0
          && nativeSucceeded == 0 && nativeFailed == 0
          && nativeTextureCount == 0 && nativeTextureBytes == 0
          && blockerCount == 0 && blockerSetComplete;
    }
  }

  public record FullGraphStatus(
      boolean enabled, boolean runtimeAvailable, boolean captureActive,
      long captureRequests, long framesObserved, long framesPlanned,
      int framesPending, long framesAttempted, long framesSucceeded,
      long framesUnsupported, long framesFailed, long operations,
      long draws, long clears, long transfers, long barriers,
      long capturedBytes, int initializedResources,
      String lastOutputHashUnsigned, int minimumSuccessFrames,
      boolean validated, String ownershipMode, boolean performanceEligible,
      boolean ownershipOptedIn, long ownershipSubmissions,
      long ownershipReady, long ownershipFramesArmed,
      long ownershipFramesPresented, long ownershipFramesReused,
      long ownershipFramesInvalidated, long ownershipCommandsSuppressed,
      long ownershipFailures,
      String ownershipLastFailure,
      int blockerCount, boolean blockerSetComplete,
      String blockerSetSha256, String blockerSummary, String lastFailure) {
    public FullGraphStatus {
      Objects.requireNonNull(lastOutputHashUnsigned,
          "lastOutputHashUnsigned");
      Objects.requireNonNull(ownershipMode, "ownershipMode");
      Objects.requireNonNull(ownershipLastFailure, "ownershipLastFailure");
      Objects.requireNonNull(blockerSetSha256, "blockerSetSha256");
      Objects.requireNonNull(blockerSummary, "blockerSummary");
      Objects.requireNonNull(lastFailure, "lastFailure");
      if (captureRequests < 0 || framesObserved < 0 || framesPlanned < 0
          || framesPending < 0 || framesAttempted < 0
          || framesSucceeded < 0 || framesUnsupported < 0
          || framesFailed < 0 || operations < 0 || draws < 0
          || clears < 0 || transfers < 0 || barriers < 0
          || capturedBytes < 0 || initializedResources < 0
          || minimumSuccessFrames <= 0 || ownershipSubmissions < 0
          || ownershipReady < 0 || ownershipFramesArmed < 0
          || ownershipFramesPresented < 0 || ownershipFramesReused < 0
          || ownershipFramesInvalidated < 0
          || ownershipCommandsSuppressed < 0 || ownershipFailures < 0
          || blockerCount < 0) {
        throw new IllegalArgumentException("invalid full graph status");
      }
      if (validated && (!enabled || !runtimeAvailable
          || framesSucceeded < minimumSuccessFrames
          || framesUnsupported != 0 || framesFailed != 0
          || operations == 0 || draws == 0 || blockerCount != 0)) {
        throw new IllegalArgumentException("inconsistent full graph status");
      }
      if (performanceEligible && (!validated || !ownershipOptedIn
          || !ownershipMode.equals("METAL_FULL_GRAPH_OWNERSHIP")
          || ownershipFramesPresented == 0
          || ownershipCommandsSuppressed == 0 || ownershipFailures != 0)) {
        throw new IllegalArgumentException(
            "inconsistent full graph ownership status");
      }
    }

    static FullGraphStatus empty(boolean enabled) {
      return new FullGraphStatus(enabled, false, false, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "0",
          MINIMUM_FULL_GRAPH_SUCCESS_FRAMES, false,
          "OPENGL_VISIBLE_VALIDATION", false, false,
          0, 0, 0, 0, 0, 0, 0, 0, "", 0, true,
          emptyPipelineStateSetSha256(), "", "");
    }
  }

  public record MetalPipelineCacheStatus(
      boolean enabled, String readiness, String deviceCompilerSha256,
      int candidatesObserved, int executionBlockedCandidates,
      int pending, long attempted, long compiled, long cacheHits,
      long newVariantsCompiled, long knownArchiveMisses,
      long unsupported, long failed, long queueRejected,
      boolean archiveFlushed, long archiveFlushFailures,
      int pipelineIdentityCount, boolean pipelineIdentitySetComplete,
      String pipelineSetSha256, int failureReasonCount,
      boolean failureReasonSetComplete, String failureReasonSetSha256,
      String lastFailure, IrisMetalPipelineCompiler.NativeStatus nativeStatus) {
    public MetalPipelineCacheStatus {
      Objects.requireNonNull(readiness, "readiness");
      Objects.requireNonNull(deviceCompilerSha256,
          "deviceCompilerSha256");
      Objects.requireNonNull(pipelineSetSha256, "pipelineSetSha256");
      Objects.requireNonNull(failureReasonSetSha256,
          "failureReasonSetSha256");
      Objects.requireNonNull(lastFailure, "lastFailure");
      Objects.requireNonNull(nativeStatus, "nativeStatus");
      if (newVariantsCompiled < 0 || knownArchiveMisses < 0) {
        throw new IllegalArgumentException(
            "invalid Metal pipeline compilation classification");
      }
    }

    public boolean complete() {
      long succeeded = compiled + cacheHits;
      return enabled
          && readiness.equals(IrisMetalPipelineCompiler.Readiness.READY.name())
          && deviceCompilerSha256.matches("[0-9a-f]{64}")
          && candidatesObserved > 0
          && pending == 0
          && attempted == candidatesObserved
          && attempted == succeeded
          // These counters are updated separately by the compiler worker, so
          // an in-progress snapshot may briefly observe only one side.  The
          // invariant is required once the queue has settled, not while work
          // is still being published.
          && compiled == newVariantsCompiled + knownArchiveMisses
          && knownArchiveMisses == 0
          && unsupported == 0
          && failed == 0
          && queueRejected == 0
          && archiveFlushed
          && archiveFlushFailures == 0
          && pipelineIdentityCount == succeeded
          && pipelineIdentitySetComplete
          && failureReasonCount == 0
          && failureReasonSetComplete
          && nativeStatus.attempts() == attempted
          && nativeStatus.compiled() == compiled
          && nativeStatus.cacheHits() == cacheHits
          && nativeStatus.failures() == 0
          && nativeStatus.livePipelines() == pipelineIdentityCount;
    }

    public boolean safeUnsupportedFallback() {
      return enabled
          && readiness.equals(
              IrisMetalPipelineCompiler.Readiness.UNSUPPORTED.name())
          && candidatesObserved > 0
          && pending == 0
          && attempted == 0
          && compiled == 0
          && cacheHits == 0
          && failed == 0
          && queueRejected == 0
          && pipelineIdentityCount == 0
          && nativeStatus.attempts() == 0
          && nativeStatus.livePipelines() == 0
          && nativeStatus.drawAttempts() == 0;
    }
  }

  public record ShadowPlanStatus(long plansObserved,
                                 long structurallyComplete,
                                 long blocked, long executionSteps,
                                 int blockerCount,
                                 boolean blockerSetComplete,
                                 String blockerSetSha256,
                                 String blockerSummary) {
    public ShadowPlanStatus {
      Objects.requireNonNull(blockerSetSha256, "blockerSetSha256");
      Objects.requireNonNull(blockerSummary, "blockerSummary");
    }

    public boolean complete() {
      return plansObserved > 0 && plansObserved == structurallyComplete
          && blocked == 0 && executionSteps > 0 && blockerCount == 0
          && blockerSetComplete;
    }
  }

  public record ShadowBufferStatus(boolean enabled, long plansObserved,
                                   long completePlans, long blockedPlans,
                                   long bufferImages, long bufferBytes,
                                   int blockerCount,
                                   boolean blockerSetComplete,
                                   String blockerSetSha256,
                                   String blockerSummary) {
    public ShadowBufferStatus {
      if (plansObserved < 0 || completePlans < 0 || blockedPlans < 0
          || bufferImages < 0 || bufferBytes < 0 || blockerCount < 0) {
        throw new IllegalArgumentException("negative shadow buffer metric");
      }
      Objects.requireNonNull(blockerSetSha256, "blockerSetSha256");
      Objects.requireNonNull(blockerSummary, "blockerSummary");
    }

    public boolean complete() {
      return enabled && plansObserved > 0
          && plansObserved == completePlans && blockedPlans == 0
          && bufferImages > 0 && bufferBytes > 0 && blockerCount == 0
          && blockerSetComplete;
    }
  }

  public record ShadowArgumentStatus(boolean enabled, long plansObserved,
                                     long completePlans, long blockedPlans,
                                     long argumentsResolved,
                                     long inlineUniformBytes,
                                     int blockerCount,
                                     boolean blockerSetComplete,
                                     String blockerSetSha256,
                                     String blockerSummary) {
    public ShadowArgumentStatus {
      if (plansObserved < 0 || completePlans < 0 || blockedPlans < 0
          || argumentsResolved < 0 || inlineUniformBytes < 0
          || blockerCount < 0) {
        throw new IllegalArgumentException("negative shadow argument metric");
      }
      Objects.requireNonNull(blockerSetSha256, "blockerSetSha256");
      Objects.requireNonNull(blockerSummary, "blockerSummary");
    }

    public boolean complete() {
      return enabled && plansObserved > 0
          && plansObserved == completePlans && blockedPlans == 0
          && argumentsResolved > 0 && blockerCount == 0
          && blockerSetComplete;
    }
  }

  public record ShadowReplayStatus(boolean enabled, long drawsObserved,
                                   long drawsReady, long drawsAttempted,
                                   long drawsSucceeded,
                                   long drawsUnsupported, long drawsFailed,
                                   long drawsBlocked, int candidateCount,
                                   boolean candidateSetComplete,
                                   int successfulPhaseCount,
                                   String successfulPhaseSummary,
                                   String lastColorHashUnsigned,
                                   int lastWidth, int lastHeight,
                                   int blockerCount,
                                   boolean blockerSetComplete,
                                   String blockerSetSha256,
                                   String blockerSummary) {
    public ShadowReplayStatus {
      if (drawsObserved < 0 || drawsReady < 0 || drawsAttempted < 0
          || drawsSucceeded < 0 || drawsUnsupported < 0 || drawsFailed < 0
          || drawsBlocked < 0 || candidateCount < 0
          || successfulPhaseCount < 0 || lastWidth < 0 || lastHeight < 0
          || blockerCount < 0) {
        throw new IllegalArgumentException("negative shadow replay metric");
      }
      Objects.requireNonNull(successfulPhaseSummary,
          "successfulPhaseSummary");
      Objects.requireNonNull(lastColorHashUnsigned,
          "lastColorHashUnsigned");
      Objects.requireNonNull(blockerSetSha256, "blockerSetSha256");
      Objects.requireNonNull(blockerSummary, "blockerSummary");
    }

    /** Native execution coverage only; visual parity remains a separate gate. */
    public boolean executionComplete() {
      return enabled && drawsObserved > 0 && drawsReady > 0
          && drawsAttempted == drawsReady
          && drawsAttempted == drawsSucceeded + drawsUnsupported + drawsFailed
          && drawsSucceeded > 0 && drawsUnsupported == 0 && drawsFailed == 0
          && candidateCount == drawsAttempted && candidateSetComplete
          && successfulPhaseCount >= 4
          && successfulPhaseSummary.contains(
              IrisRenderGraph.Phase.SHADOW.name())
          && successfulPhaseSummary.contains(
              IrisRenderGraph.Phase.GEOMETRY.name())
          && successfulPhaseSummary.contains(
              IrisRenderGraph.Phase.COMPOSITE.name())
          && successfulPhaseSummary.contains(
              IrisRenderGraph.Phase.FINAL.name())
          && !lastColorHashUnsigned.equals("0")
          && lastWidth > 0 && lastHeight > 0 && blockerCount == 0
          && blockerSetComplete;
    }
  }

  public record VisualParityStatus(boolean enabled, int channelTolerance,
                                   double maxDifferentPixelRatio,
                                   double maxRootMeanSquareError,
                                   int minimumFrames,
                                   int requiredConsecutivePasses,
                                   long captureScheduled,
                                   long captureSucceeded,
                                   long captureFailed,
                                   long captureDropped,
                                   int capturePending,
                                   String captureLastFailure,
                                   long samplesHandled,
                                   long missingOpenGlFrames,
                                   long missingMetalFrames,
                                   long dimensionMismatches,
                                   long replayFailures,
                                   long framesCompared,
                                   long framesPassed,
                                   long framesFailed,
                                   int consecutivePasses,
                                   double worstDifferentPixelRatio,
                                   double worstRootMeanSquareError,
                                   int worstChannelDelta,
                                   boolean validated,
                                   String orientation,
                                   int blockerCount,
                                   boolean blockerSetComplete,
                                   String blockerSetSha256,
                                   String blockerSummary) {
    public VisualParityStatus {
      if (channelTolerance < 0 || minimumFrames <= 0
          || requiredConsecutivePasses <= 0 || captureScheduled < 0
          || captureSucceeded < 0 || captureFailed < 0
          || captureDropped < 0 || capturePending < 0 || samplesHandled < 0
          || missingOpenGlFrames < 0 || missingMetalFrames < 0
          || dimensionMismatches < 0 || replayFailures < 0
          || framesCompared < 0 || framesPassed < 0 || framesFailed < 0
          || consecutivePasses < 0 || worstChannelDelta < 0
          || blockerCount < 0) {
        throw new IllegalArgumentException("negative visual parity metric");
      }
      Objects.requireNonNull(captureLastFailure, "captureLastFailure");
      Objects.requireNonNull(orientation, "orientation");
      Objects.requireNonNull(blockerSetSha256, "blockerSetSha256");
      Objects.requireNonNull(blockerSummary, "blockerSummary");
    }
  }

  public record CutoverStatus(boolean enabled, String mode,
                              long currentFrame, long contextGeneration,
                              boolean frameFallback,
                              long drawsObserved, long drawsEligible,
                              long metalAttempts, long metalSucceeded,
                              long presentations,
                              long openGlDrawsSuppressed,
                              long frameFallbacks, long lifecycleResets,
                              long failures, String bridge,
                              long gpuInputTextures, long gpuInputBytes,
                              long cpuInputTextures, long cpuInputBytes,
                              long gpuInputBuffers,
                              long gpuInputBufferBytes,
                              long cpuInputBuffers,
                              long cpuInputBufferBytes,
                              int pipelineLookupCount,
                              int failureReasonCount,
                              boolean failureReasonSetComplete,
                              String failureReasonSetSha256,
                              String failureReasonSummary,
                              String lastFailure) {
    public CutoverStatus {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(bridge, "bridge");
      Objects.requireNonNull(failureReasonSetSha256,
          "failureReasonSetSha256");
      Objects.requireNonNull(failureReasonSummary, "failureReasonSummary");
      Objects.requireNonNull(lastFailure, "lastFailure");
      if (currentFrame < -1 || contextGeneration < -1 || drawsObserved < 0
          || drawsEligible < 0 || metalAttempts < 0 || metalSucceeded < 0
          || presentations < 0 || openGlDrawsSuppressed < 0
          || frameFallbacks < 0 || lifecycleResets < 0 || failures < 0
          || gpuInputTextures < 0 || gpuInputBytes < 0
          || cpuInputTextures < 0 || cpuInputBytes < 0
          || gpuInputBuffers < 0 || gpuInputBufferBytes < 0
          || cpuInputBuffers < 0 || cpuInputBufferBytes < 0
          || pipelineLookupCount < 0 || failureReasonCount < 0) {
        throw new IllegalArgumentException("negative cutover metric");
      }
    }

    static CutoverStatus empty(boolean enabled) {
      return new CutoverStatus(enabled,
          IrisSelectiveCutoverGate.Mode.SHADOW.name(), -1, -1, false,
          0, 0, 0, 0, 0, 0, 0, 0, 0,
          IrisMetalCutoverPresenter.BRIDGE_NAME, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, true,
          emptyPipelineStateSetSha256(), "", "");
    }

    public boolean activeAndHealthy() {
      return enabled && mode.equals(IrisSelectiveCutoverGate.Mode.ACTIVE.name())
          && drawsEligible > 0 && metalAttempts == metalSucceeded
          && metalSucceeded == presentations
          && presentations == openGlDrawsSuppressed
          && frameFallbacks == 0 && failures == 0
          && failureReasonCount == 0 && failureReasonSetComplete;
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

    static String normalizeReason(String reason) {
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

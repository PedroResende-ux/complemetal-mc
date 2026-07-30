package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

  IrisTranslationCoordinator(IrisShaderCaptureQueue captureQueue,
      IrisShaderTranslatorBackend backend, IrisPipelineCache cache,
      Path cacheRoot) {
    this(captureQueue, backend, cache, cacheRoot, 0);
  }

  private IrisTranslationCoordinator(IrisShaderCaptureQueue captureQueue,
      IrisShaderTranslatorBackend backend, IrisPipelineCache cache,
      Path cacheRoot, long quietPeriodNanos) {
    this.captureQueue = Objects.requireNonNull(captureQueue, "captureQueue");
    this.backend = Objects.requireNonNull(backend, "backend");
    this.cache = Objects.requireNonNull(cache, "cache");
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
      LwjglShadercSpvcBackend backend = new LwjglShadercSpvcBackend(
          LwjglShadercSpvcBackend.ExecutionPolicy.EXPLICITLY_ENABLED);
      IrisPipelineCache cache = new IrisPipelineCache(
          new IrisPipelineCacheLayout(normalized));
      IrisTranslationCoordinator coordinator =
          new IrisTranslationCoordinator(IrisShaderCapture.captureQueue(),
              backend, cache, normalized, CLIENT_QUIET_PERIOD_NANOS);
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
          CONFIGURED_CACHE_ROOT.get(), START_FAILURE.get());
    }
    return coordinator.snapshot();
  }

  private static boolean isOptedIn() {
    return IrisShaderCapture.isEnabled()
        && Boolean.getBoolean(TRANSLATION_ENABLED_PROPERTY);
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
      for (int processed = 0; processed < 1 && running.get(); processed++) {
        Optional<IrisShaderCaptureQueue.CapturedProgram> captured =
            captureQueue.poll();
        if (captured.isEmpty()) {
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
        return;
      }
      IrisShaderTranslation result = backend.translate(program);
      if (!running.get()) {
        return;
      }
      IrisPipelineCache.StoreResult stored = cache.store(program, result);
      if (stored.cacheHit()) {
        cacheHits.incrementAndGet();
      } else {
        translated.incrementAndGet();
      }
    } catch (Exception | LinkageError error) {
      recordFailure(error);
    }
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

  Status snapshot() {
    return new Status(true, running.get(), captureQueue.size(),
        captureQueue.rejectedPrograms(), IrisShaderCapture.captureFailures(),
        attempted.get(), translated.get(), cacheHits.get(), failed.get(),
        cacheRoot, lastFailure.get());
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
    ACTIVE.compareAndSet(this, null);
  }

  public record Status(boolean enabled, boolean running, int queued,
                       long rejected, long captureFailures,
                       long attempted, long translated, long cacheHits,
                       long failed, String cacheRoot, String lastFailure) {
    public Status {
      Objects.requireNonNull(cacheRoot, "cacheRoot");
      Objects.requireNonNull(lastFailure, "lastFailure");
    }
  }
}

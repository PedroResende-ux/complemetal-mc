package com.pebbles_boon.metalrender.compat.iris;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, deduplicating in-memory queue for final Iris shader programs.
 *
 * <p>Both item count and retained source characters are bounded. No worker is
 * started here: an explicitly enabled translation coordinator must poll the
 * queue.</p>
 */
public final class IrisShaderCaptureQueue {
  public static final int DEFAULT_CAPACITY = 512;
  public static final long DEFAULT_MAX_PROGRAM_CHARS = 4L * 1024L * 1024L;
  public static final long DEFAULT_MAX_QUEUED_CHARS = 32L * 1024L * 1024L;

  private final int capacity;
  private final long maxProgramChars;
  private final long maxQueuedChars;
  private final int recentKeyCapacity;
  private final IrisTranslationProfile profile;
  private final ConcurrentLinkedQueue<Submission> queue =
      new ConcurrentLinkedQueue<>();
  private final AtomicInteger queuedPrograms = new AtomicInteger();
  private final AtomicLong queuedChars = new AtomicLong();
  private final AtomicLong lastOfferNanos = new AtomicLong();
  private final AtomicLong rejectedPrograms = new AtomicLong();
  private final LinkedHashMap<IrisShaderCacheKey, Boolean> recentKeys =
      new LinkedHashMap<>();

  public IrisShaderCaptureQueue(int capacity, long maxProgramChars,
      long maxQueuedChars) {
    this(capacity, maxProgramChars, maxQueuedChars,
        IrisTranslationProfile.LWJGL_3_3_3_METAL_3_ARGUMENT_BUFFERS);
  }

  public IrisShaderCaptureQueue(int capacity, long maxProgramChars,
      long maxQueuedChars, IrisTranslationProfile profile) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    if (maxProgramChars <= 0 || maxQueuedChars < maxProgramChars) {
      throw new IllegalArgumentException(
          "character limits must be positive and queue >= program");
    }
    this.capacity = capacity;
    this.maxProgramChars = maxProgramChars;
    this.maxQueuedChars = maxQueuedChars;
    this.profile = Objects.requireNonNull(profile, "profile");
    recentKeyCapacity = Math.max(64, Math.multiplyExact(capacity, 4));
  }

  public static IrisShaderCaptureQueue createDefault() {
    return new IrisShaderCaptureQueue(DEFAULT_CAPACITY,
        DEFAULT_MAX_PROGRAM_CHARS, DEFAULT_MAX_QUEUED_CHARS);
  }

  /**
   * Lock-free producer path used on Iris' link thread. It does not hash,
   * deduplicate, execute tools, touch disk, or wait for a consumer.
   */
  public Offer offer(IrisFinalShaderProgram program) {
    return offer(program, null);
  }

  /**
   * Enqueues one generation-specific program association. Duplicate shader
   * contents are still returned to the worker so a reload can resolve its new
   * OpenGL program generation; the {@link CapturedProgram#duplicate()} flag
   * prevents redundant translation work.
   */
  public Offer offer(IrisFinalShaderProgram program,
      IrisProgramIdentityRegistry.Registration registration) {
    Objects.requireNonNull(program, "program");
    // Rejected attempts still mean Iris is actively linking a pack. Extending
    // the quiet period prevents the consumer from competing with the rest of
    // that link wave even when the bounded queue is under pressure.
    lastOfferNanos.set(System.nanoTime());
    if (program.retainedChars() > maxProgramChars) {
      rejectedPrograms.incrementAndGet();
      return new Offer(Disposition.TOO_LARGE);
    }
    int count = queuedPrograms.incrementAndGet();
    if (count > capacity) {
      queuedPrograms.decrementAndGet();
      rejectedPrograms.incrementAndGet();
      return new Offer(Disposition.FULL);
    }
    if (!reserveChars(program.retainedChars())) {
      queuedPrograms.decrementAndGet();
      rejectedPrograms.incrementAndGet();
      return new Offer(Disposition.FULL);
    }

    queue.offer(new Submission(program, registration));
    return new Offer(Disposition.ACCEPTED);
  }

  /**
   * Consumer path. Hashing and content deduplication happen here, away from
   * Iris' link thread.
   */
  public Optional<CapturedProgram> poll() {
    Submission submission = queue.poll();
    if (submission == null) {
      return Optional.empty();
    }
    IrisFinalShaderProgram program = submission.program();
    queuedPrograms.decrementAndGet();
    queuedChars.addAndGet(-program.retainedChars());

    IrisShaderCacheKey key = IrisShaderCacheKey.from(program, profile);
    boolean duplicate;
    synchronized (recentKeys) {
      duplicate = recentKeys.containsKey(key);
      if (!duplicate) {
        remember(key);
      }
    }
    return Optional.of(new CapturedProgram(key, program,
        Optional.ofNullable(submission.registration()), duplicate));
  }

  public int size() {
    return queuedPrograms.get();
  }

  public long queuedChars() {
    return queuedChars.get();
  }

  public long rejectedPrograms() {
    return rejectedPrograms.get();
  }

  public long nanosSinceLastOffer() {
    long lastOffer = lastOfferNanos.get();
    return lastOffer == 0
        ? Long.MAX_VALUE
        : Math.max(0, System.nanoTime() - lastOffer);
  }

  private boolean reserveChars(long characters) {
    long current;
    long updated;
    do {
      current = queuedChars.get();
      updated = current + characters;
      if (updated > maxQueuedChars) {
        return false;
      }
    } while (!queuedChars.compareAndSet(current, updated));
    return true;
  }

  private void remember(IrisShaderCacheKey key) {
    recentKeys.put(key, Boolean.TRUE);
    while (recentKeys.size() > recentKeyCapacity) {
      IrisShaderCacheKey eldest =
          recentKeys.entrySet().iterator().next().getKey();
      recentKeys.remove(eldest);
    }
  }

  public enum Disposition {
    ACCEPTED,
    FULL,
    TOO_LARGE
  }

  public record Offer(Disposition disposition) {
    public Offer {
      Objects.requireNonNull(disposition, "disposition");
    }
  }

  private record Submission(IrisFinalShaderProgram program,
                            IrisProgramIdentityRegistry.Registration
                                registration) {
  }

  public record CapturedProgram(IrisShaderCacheKey key,
                                IrisFinalShaderProgram program,
                                Optional<IrisProgramIdentityRegistry.Registration>
                                    registration,
                                boolean duplicate) {
    public CapturedProgram {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(program, "program");
      Objects.requireNonNull(registration, "registration");
    }
  }
}

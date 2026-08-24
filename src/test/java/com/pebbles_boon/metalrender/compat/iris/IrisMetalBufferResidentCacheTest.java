package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class IrisMetalBufferResidentCacheTest {
  @AfterEach
  void resetCache() {
    IrisMetalBufferResidentCache.reset();
  }

  @Test
  void generationQualifiedSourceSkipsSecondHashAndUpload() {
    IrisMetalBufferResidentCache.reset();
    AtomicInteger uploads = new AtomicInteger();
    IrisShadowReplayBufferSnapshot.BufferImage first =
        new IrisShadowReplayBufferSnapshot.BufferImage(
            0, 7, 11, 16, new byte[] {1, 2, 3, 4});

    IrisShadowReplayBufferSnapshot.BufferImage promoted =
        IrisMetalBufferResidentCache.promote(first, (digest, bytes) -> {
          uploads.incrementAndGet();
          return 42;
        }).orElseThrow();
    IrisShadowReplayBufferSnapshot.BufferImage reused =
        IrisMetalBufferResidentCache.lookup(3, 7, 11, 16, 4)
            .orElseThrow();

    assertTrue(promoted.shared());
    assertEquals(42, reused.sharedHandle());
    assertEquals(3, reused.id());
    assertEquals(1, uploads.get());
    assertEquals(1, IrisMetalBufferResidentCache.status().sourceHits());
  }

  @Test
  void changedGenerationDoesNotAliasSourceEntry() {
    IrisMetalBufferResidentCache.reset();
    IrisShadowReplayBufferSnapshot.BufferImage first =
        new IrisShadowReplayBufferSnapshot.BufferImage(
            0, 9, 21, 0, new byte[] {5, 6, 7, 8});
    IrisMetalBufferResidentCache.promote(first,
        (digest, bytes) -> 77).orElseThrow();

    assertTrue(IrisMetalBufferResidentCache.lookup(
        0, 9, 22, 0, 4).isEmpty());
  }
}

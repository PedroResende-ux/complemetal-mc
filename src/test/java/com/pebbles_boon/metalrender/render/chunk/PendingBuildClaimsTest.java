package com.pebbles_boon.metalrender.render.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PendingBuildClaimsTest {
  @Test
  void staleWorkerCannotReleaseReplacementClaim() {
    PendingBuildClaims claims = new PendingBuildClaims();
    long key = 42L;

    long first = claims.claim(key);
    assertNotEquals(PendingBuildClaims.NO_CLAIM, first);
    assertEquals(PendingBuildClaims.NO_CLAIM, claims.claim(key));

    claims.invalidate(key);
    long replacement = claims.claim(key);
    assertNotEquals(PendingBuildClaims.NO_CLAIM, replacement);
    assertNotEquals(first, replacement);

    assertFalse(claims.release(key, first));
    assertFalse(claims.isCurrent(key, first));
    assertTrue(claims.isCurrent(key, replacement));
    assertTrue(claims.contains(key));
    assertEquals(1, claims.size());

    assertTrue(claims.release(key, replacement));
    assertFalse(claims.contains(key));
    assertEquals(0, claims.size());
  }

  @Test
  void clearInvalidatesEveryOutstandingWorker() {
    PendingBuildClaims claims = new PendingBuildClaims();
    long first = claims.claim(1L);
    long second = claims.claim(2L);

    claims.clear();

    assertFalse(claims.isCurrent(1L, first));
    assertFalse(claims.isCurrent(2L, second));
    assertEquals(0, claims.size());
  }
}

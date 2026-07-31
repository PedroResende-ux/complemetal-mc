package com.pebbles_boon.metalrender.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Tracks the unique worker that currently owns each pending section build.
 *
 * <p>A key-only set cannot distinguish an invalidated worker from its
 * replacement: the old worker's {@code finally} block can otherwise remove
 * the replacement's marker. Claim tokens make release a compare-and-remove
 * operation and also give workers an explicit cancellation signal when a
 * section is dirtied without advancing its mesh generation.</p>
 */
final class PendingBuildClaims {
  static final long NO_CLAIM = 0L;

  private final Long2LongOpenHashMap claims = new Long2LongOpenHashMap();
  private long nextClaim = 1L;

  synchronized long claim(long key) {
    if (claims.containsKey(key)) {
      return NO_CLAIM;
    }
    long claim = nextClaim++;
    if (claim == NO_CLAIM) {
      claim = nextClaim++;
    }
    claims.put(key, claim);
    return claim;
  }

  synchronized boolean isCurrent(long key, long claim) {
    return claim != NO_CLAIM
        && claims.containsKey(key)
        && claims.get(key) == claim;
  }

  synchronized boolean release(long key, long claim) {
    if (!isCurrent(key, claim)) {
      return false;
    }
    claims.remove(key);
    return true;
  }

  synchronized void invalidate(long key) {
    claims.remove(key);
  }

  synchronized boolean contains(long key) {
    return claims.containsKey(key);
  }

  synchronized int size() {
    return claims.size();
  }

  synchronized void clear() {
    claims.clear();
  }
}

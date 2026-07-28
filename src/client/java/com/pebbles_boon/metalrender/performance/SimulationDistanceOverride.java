package com.pebbles_boon.metalrender.performance;

/**
 * Tracks a temporary live simulation-distance cap without replacing the
 * player's preferred value.
 */
public final class SimulationDistanceOverride {
  private final int maximumLiveDistance;
  private Integer preferredDistance;

  public SimulationDistanceOverride(int maximumLiveDistance) {
    if (maximumLiveDistance < 1) {
      throw new IllegalArgumentException("maximumLiveDistance must be positive");
    }
    this.maximumLiveDistance = maximumLiveDistance;
  }

  /**
   * Enables or refreshes the override. A value changed outside MetalRender is
   * treated as a new player preference instead of being overwritten.
   */
  public int enable(int currentDistance) {
    if (preferredDistance == null) {
      preferredDistance = currentDistance;
    } else {
      int expectedLiveDistance = clamp(preferredDistance);
      if (currentDistance != expectedLiveDistance) {
        preferredDistance = currentDistance;
      }
    }
    return clamp(preferredDistance);
  }

  /**
   * Applies a value chosen in MetalRender settings.
   */
  public int setPreferred(int requestedDistance, boolean overrideEnabled) {
    if (!overrideEnabled) {
      preferredDistance = null;
      return requestedDistance;
    }
    preferredDistance = requestedDistance;
    return clamp(requestedDistance);
  }

  /**
   * Disables the override and returns the value that must be restored.
   */
  public int disable(int currentDistance) {
    int restored = preferredDistance != null
        ? preferredDistance
        : currentDistance;
    preferredDistance = null;
    return restored;
  }

  public int preferredOr(int currentDistance) {
    return preferredDistance != null ? preferredDistance : currentDistance;
  }

  public int liveOr(int currentDistance) {
    return preferredDistance != null ? clamp(preferredDistance) : currentDistance;
  }

  public boolean isActive() {
    return preferredDistance != null;
  }

  private int clamp(int distance) {
    return Math.min(distance, maximumLiveDistance);
  }
}

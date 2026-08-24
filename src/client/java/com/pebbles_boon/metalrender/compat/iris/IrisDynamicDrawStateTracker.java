package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisDynamicDrawState.Rect;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.StateValue;

/** Render-thread shadow of OpenGL draw state that is dynamic in Metal. */
public final class IrisDynamicDrawStateTracker {
  private static final IrisDynamicDrawStateTracker GLOBAL =
      new IrisDynamicDrawStateTracker();

  private StateValue<Rect> viewport = StateValue.unknown(
      "viewport not observed since tracker creation");
  private StateValue<Boolean> scissorEnabled = StateValue.unknown(
      "scissor enable not observed since tracker creation");
  private StateValue<Rect> scissor = StateValue.unknown(
      "scissor box not observed since tracker creation");

  public static IrisDynamicDrawStateTracker global() {
    return GLOBAL;
  }

  public synchronized void initializeOpenGlDefaults() {
    viewport = StateValue.unknown(
        "viewport size is context-dependent until first observation");
    scissorEnabled = StateValue.known(false);
    scissor = StateValue.known(new Rect(0, 0, 0, 0));
  }

  public synchronized void resetContext() {
    viewport = StateValue.unknown(
        "viewport not observed since GL context reset");
    scissorEnabled = StateValue.unknown(
        "scissor enable not observed since GL context reset");
    scissor = StateValue.unknown(
        "scissor box not observed since GL context reset");
  }

  public synchronized void viewport(int x, int y, int width, int height) {
    viewport = rectangle(x, y, width, height, "invalid GL viewport");
  }

  public synchronized void scissorEnabled(boolean enabled) {
    scissorEnabled = StateValue.known(enabled);
  }

  public synchronized void scissor(int x, int y, int width, int height) {
    scissor = rectangle(x, y, width, height, "invalid GL scissor box");
  }

  public synchronized IrisDynamicDrawState snapshot() {
    return new IrisDynamicDrawState(viewport, scissorEnabled, scissor);
  }

  private static StateValue<Rect> rectangle(int x, int y, int width,
      int height, String reason) {
    if (width < 0 || height < 0) {
      return StateValue.unknown(reason);
    }
    return StateValue.known(new Rect(x, y, width, height));
  }
}

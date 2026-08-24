package com.pebbles_boon.metalrender.display;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

/**
 * Render-thread display topology and backing-scale observer.
 *
 * <p>GLFW does not recreate MetalRender's IOSurfaces when a window migrates
 * between displays. This tracker turns topology, monitor, backing-scale and
 * framebuffer changes into one explicit fail-open transition. It also treats
 * a long gap between client ticks as a possible macOS wake/resume boundary.
 * The caller remains responsible for invalidating renderer-owned surfaces.</p>
 */
public final class DisplayLifecycleTracker {
  static final long RESUME_GAP_MILLIS = 5_000L;
  private static final float SCALE_EPSILON = 0.01F;

  private static DisplayState lastState;
  private static long transitions;
  private static long presentationResets;
  private static long resumeGaps;
  private static String lastReason = "";

  private DisplayLifecycleTracker() {
  }

  /** Captures and compares the current GLFW display state. */
  public static synchronized DisplayTransition poll(Window window) {
    DisplayState current = capture(Objects.requireNonNull(window, "window"));
    DisplayTransition transition = analyze(lastState, current);
    lastState = current;
    if (!transition.changed()) {
      return transition;
    }
    transitions++;
    lastReason = transition.reason();
    if (transition.requiresPresentationReset()) {
      presentationResets++;
    }
    if (transition.changes().contains(Change.RESUME_GAP)) {
      resumeGaps++;
    }
    return transition;
  }

  /** Latest bounded status for exact-JAR hardware evidence and diagnostics. */
  public static synchronized Status status() {
    return new Status(lastState, transitions, presentationResets, resumeGaps,
        lastReason);
  }

  /** Current GLFW displays, including work area, scale and active mode. */
  public static List<DisplayTarget> displays() {
    PointerBuffer monitors = GLFW.glfwGetMonitors();
    if (monitors == null || monitors.remaining() == 0) {
      return List.of();
    }
    ArrayList<DisplayTarget> result = new ArrayList<>(monitors.remaining());
    long primary = GLFW.glfwGetPrimaryMonitor();
    try (MemoryStack stack = MemoryStack.stackPush()) {
      IntBuffer x = stack.mallocInt(1);
      IntBuffer y = stack.mallocInt(1);
      IntBuffer width = stack.mallocInt(1);
      IntBuffer height = stack.mallocInt(1);
      FloatBuffer scaleX = stack.mallocFloat(1);
      FloatBuffer scaleY = stack.mallocFloat(1);
      for (int index = monitors.position(); index < monitors.limit(); index++) {
        long monitor = monitors.get(index);
        GLFW.glfwGetMonitorWorkarea(monitor, x, y, width, height);
        GLFW.glfwGetMonitorContentScale(monitor, scaleX, scaleY);
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        if (mode == null || width.get(0) <= 0 || height.get(0) <= 0) {
          continue;
        }
        result.add(new DisplayTarget(monitor,
            boundedMonitorName(GLFW.glfwGetMonitorName(monitor)),
            x.get(0), y.get(0), width.get(0), height.get(0),
            mode.width(), mode.height(), mode.refreshRate(),
            positiveScale(scaleX.get(0)), positiveScale(scaleY.get(0)),
            monitor == primary));
      }
    }
    return List.copyOf(result);
  }

  /** Clears process-local observations without touching GLFW or native state. */
  public static synchronized void reset() {
    lastState = null;
    transitions = 0;
    presentationResets = 0;
    resumeGaps = 0;
    lastReason = "";
  }

  static DisplayTransition analyze(DisplayState previous,
      DisplayState current) {
    Objects.requireNonNull(current, "current");
    if (previous == null) {
      return new DisplayTransition(Set.of(Change.INITIALIZED), null, current);
    }
    EnumSet<Change> changes = EnumSet.noneOf(Change.class);
    if (current.observedMillis() > previous.observedMillis()
        && current.observedMillis() - previous.observedMillis()
            >= RESUME_GAP_MILLIS) {
      changes.add(Change.RESUME_GAP);
    }
    if (previous.windowHandle() != current.windowHandle()) {
      changes.add(Change.WINDOW_RECREATED);
    }
    if (previous.topologyFingerprint() != current.topologyFingerprint()) {
      changes.add(Change.MONITOR_TOPOLOGY);
    }
    if (previous.monitorHandle() != current.monitorHandle()) {
      changes.add(Change.MONITOR);
    }
    if (previous.windowWidth() != current.windowWidth()
        || previous.windowHeight() != current.windowHeight()) {
      changes.add(Change.WINDOW_SIZE);
    }
    if (previous.framebufferWidth() != current.framebufferWidth()
        || previous.framebufferHeight() != current.framebufferHeight()) {
      changes.add(Change.FRAMEBUFFER_SIZE);
    }
    if (Math.abs(previous.contentScaleX() - current.contentScaleX())
            > SCALE_EPSILON
        || Math.abs(previous.contentScaleY() - current.contentScaleY())
            > SCALE_EPSILON) {
      changes.add(Change.CONTENT_SCALE);
    }
    if (previous.refreshRate() != current.refreshRate()) {
      changes.add(Change.REFRESH_RATE);
    }
    if (previous.fullscreen() != current.fullscreen()) {
      changes.add(Change.FULLSCREEN);
    }
    if (previous.visible() != current.visible()) {
      changes.add(Change.VISIBILITY);
    }
    if (previous.iconified() != current.iconified()) {
      changes.add(Change.ICONIFIED);
    }
    return new DisplayTransition(changes, previous, current);
  }

  private static DisplayState capture(Window window) {
    long handle = window.handle();
    float scaleX = 1.0F;
    float scaleY = 1.0F;
    if (handle != 0) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
        FloatBuffer x = stack.mallocFloat(1);
        FloatBuffer y = stack.mallocFloat(1);
        GLFW.glfwGetWindowContentScale(handle, x, y);
        if (Float.isFinite(x.get(0)) && x.get(0) > 0.0F) {
          scaleX = x.get(0);
        }
        if (Float.isFinite(y.get(0)) && y.get(0) > 0.0F) {
          scaleY = y.get(0);
        }
      }
    }
    Monitor monitor = window.findBestMonitor();
    long monitorHandle = monitor == null ? 0 : monitor.monitor();
    String monitorName = boundedMonitorName(
        monitor == null ? "unavailable" : monitor.monitorName());
    int refreshRate = window.getRefreshRate();
    if (refreshRate <= 0 && monitor != null && monitor.currentMode() != null) {
      refreshRate = monitor.currentMode().getRefreshRate();
    }
    boolean visible = handle != 0
        && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE)
            == GLFW.GLFW_TRUE;
    // Wall time deliberately detects time spent in macOS sleep. A forward
    // clock correction may conservatively create the same fail-open reset.
    return new DisplayState(System.currentTimeMillis(), handle,
        topologyFingerprint(),
        monitorHandle, monitorName, window.getX(), window.getY(),
        window.getScreenWidth(), window.getScreenHeight(), window.getWidth(),
        window.getHeight(), scaleX, scaleY, Math.max(0, refreshRate),
        window.isFullscreen(), visible, window.isIconified(),
        window.isFocused());
  }

  /** Repairs a missed GLFW backing-size callback before lifecycle analysis. */
  public static boolean synchronizeFramebufferSize(Window window) {
    Objects.requireNonNull(window, "window");
    long handle = window.handle();
    if (handle == 0) {
      return false;
    }
    try (MemoryStack stack = MemoryStack.stackPush()) {
      IntBuffer width = stack.mallocInt(1);
      IntBuffer height = stack.mallocInt(1);
      GLFW.glfwGetFramebufferSize(handle, width, height);
      int directWidth = width.get(0);
      int directHeight = height.get(0);
      if (!requiresFramebufferSync(window.getWidth(), window.getHeight(),
          directWidth, directHeight)) {
        return false;
      }
      window.setWidth(directWidth);
      window.setHeight(directHeight);
      return true;
    }
  }

  static boolean requiresFramebufferSync(int cachedWidth, int cachedHeight,
      int directWidth, int directHeight) {
    return directWidth > 0 && directHeight > 0
        && (cachedWidth != directWidth || cachedHeight != directHeight);
  }

  private static long topologyFingerprint() {
    long hash = 0xcbf29ce484222325L;
    List<DisplayTarget> displays = displays();
    if (displays.isEmpty()) {
      return mix(hash, 0);
    }
    for (DisplayTarget display : displays) {
      hash = mix(hash, display.handle());
      hash = mix(hash, display.name().hashCode());
      hash = mix(hash, display.workX());
      hash = mix(hash, display.workY());
      hash = mix(hash, display.workWidth());
      hash = mix(hash, display.workHeight());
      hash = mix(hash, Float.floatToIntBits(display.contentScaleX()));
      hash = mix(hash, Float.floatToIntBits(display.contentScaleY()));
      hash = mix(hash, display.modeWidth());
      hash = mix(hash, display.modeHeight());
      hash = mix(hash, display.refreshRate());
    }
    return hash;
  }

  private static float positiveScale(float value) {
    return Float.isFinite(value) && value > 0.0F ? value : 1.0F;
  }

  private static long mix(long hash, long value) {
    long result = hash;
    for (int byteIndex = 0; byteIndex < Long.BYTES; byteIndex++) {
      result ^= value & 0xffL;
      result *= 0x100000001b3L;
      value >>>= Byte.SIZE;
    }
    return result;
  }

  private static String boundedMonitorName(String value) {
    String normalized = value == null ? "unavailable"
        : value.replaceAll("[\\r\\n\\t]", " ").trim();
    if (normalized.isEmpty()) {
      return "unavailable";
    }
    return normalized.length() <= 96
        ? normalized : normalized.substring(0, 96);
  }

  public enum Change {
    INITIALIZED(false, false),
    RESUME_GAP(true, true),
    WINDOW_RECREATED(true, true),
    MONITOR_TOPOLOGY(true, true),
    MONITOR(true, true),
    WINDOW_SIZE(false, false),
    FRAMEBUFFER_SIZE(true, false),
    CONTENT_SCALE(true, false),
    REFRESH_RATE(false, true),
    FULLSCREEN(false, false),
    VISIBILITY(true, false),
    ICONIFIED(true, false);

    private final boolean presentationReset;
    private final boolean runtimeRefresh;

    Change(boolean presentationReset, boolean runtimeRefresh) {
      this.presentationReset = presentationReset;
      this.runtimeRefresh = runtimeRefresh;
    }
  }

  public record DisplayState(long observedMillis, long windowHandle,
                             long topologyFingerprint, long monitorHandle,
                             String monitorName, int windowX, int windowY,
                             int windowWidth, int windowHeight,
                             int framebufferWidth, int framebufferHeight,
                             float contentScaleX, float contentScaleY,
                             int refreshRate, boolean fullscreen,
                             boolean visible, boolean iconified,
                             boolean focused) {
    public DisplayState {
      if (windowWidth < 0 || windowHeight < 0
          || framebufferWidth < 0 || framebufferHeight < 0
          || !Float.isFinite(contentScaleX) || contentScaleX <= 0.0F
          || !Float.isFinite(contentScaleY) || contentScaleY <= 0.0F
          || refreshRate < 0) {
        throw new IllegalArgumentException("invalid display state");
      }
      monitorName = boundedMonitorName(monitorName);
    }

    public double framebufferScaleX() {
      return windowWidth == 0 ? 0.0
          : framebufferWidth / (double) windowWidth;
    }

    public double framebufferScaleY() {
      return windowHeight == 0 ? 0.0
          : framebufferHeight / (double) windowHeight;
    }
  }

  public record DisplayTransition(Set<Change> changes,
                                  DisplayState previous,
                                  DisplayState current) {
    public DisplayTransition {
      changes = Collections.unmodifiableSet(
          changes.isEmpty() ? EnumSet.noneOf(Change.class)
              : EnumSet.copyOf(changes));
      Objects.requireNonNull(current, "current");
    }

    public boolean changed() {
      return !changes.isEmpty();
    }

    public boolean initialized() {
      return changes.size() == 1 && changes.contains(Change.INITIALIZED);
    }

    public boolean requiresPresentationReset() {
      return changes.stream().anyMatch(change -> change.presentationReset);
    }

    public boolean requiresRuntimeRefresh() {
      return changes.stream().anyMatch(change -> change.runtimeRefresh);
    }

    public String reason() {
      StringJoiner result = new StringJoiner("+");
      changes.stream().sorted().forEach(change -> result.add(
          change.name().toLowerCase(Locale.ROOT).replace('_', '-')));
      return result.toString();
    }
  }

  public record Status(DisplayState state, long transitions,
                       long presentationResets, long resumeGaps,
                       String lastReason) {
    public Status {
      if (transitions < 0 || presentationResets < 0 || resumeGaps < 0
          || presentationResets > transitions || resumeGaps > transitions) {
        throw new IllegalArgumentException("invalid display lifecycle status");
      }
      lastReason = lastReason == null ? "" : lastReason;
    }
  }

  public record DisplayTarget(long handle, String name, int workX, int workY,
                              int workWidth, int workHeight, int modeWidth,
                              int modeHeight, int refreshRate,
                              float contentScaleX, float contentScaleY,
                              boolean primary) {
    public DisplayTarget {
      if (handle == 0 || workWidth <= 0 || workHeight <= 0
          || modeWidth <= 0 || modeHeight <= 0 || refreshRate <= 0
          || !Float.isFinite(contentScaleX) || contentScaleX <= 0.0F
          || !Float.isFinite(contentScaleY) || contentScaleY <= 0.0F) {
        throw new IllegalArgumentException("invalid display target");
      }
      name = boundedMonitorName(name);
    }
  }
}

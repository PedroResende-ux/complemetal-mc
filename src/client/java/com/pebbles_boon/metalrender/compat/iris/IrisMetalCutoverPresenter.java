package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.util.Objects;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;

/**
 * Same-thread fail-open GPU handoff of a completed Metal IOSurface to the
 * framebuffer that the paired Iris FINAL draw would have written.
 *
 * <p>The IOSurface removes the Metal-to-CPU-to-OpenGL pixel copy. A GL
 * framebuffer blit performs the required vertical orientation fix entirely
 * on the GPU. A fenced three-slot ring avoids a blocking GL finish during
 * steady-state presentation. FINAL textures use GPU/resident handoff and
 * geometry uses resident Metal buffers, but upstream passes remain OpenGL;
 * the replay waits for completion, so this is not Stage 9 performance eligible.</p>
 */
final class IrisMetalCutoverPresenter {
  static final String BRIDGE_NAME = "iosurface-gpu-handoff";
  private static final int SURFACE_RING_SIZE = 3;
  private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
  private static final int GL_TEXTURE_BINDING_RECTANGLE = 0x84F6;
  private static final IrisMetalCutoverPresenter GLOBAL =
      new IrisMetalCutoverPresenter();
  private final int[] rectangleTextures = new int[SURFACE_RING_SIZE];
  private int readFramebuffer;
  private int nextSurfaceSlot;
  private int lastBoundTexture;
  private int lastBoundWidth;
  private int lastBoundHeight;
  private boolean pendingPromotedGraphSurface;
  private String lastFailure = "";

  static IrisMetalCutoverPresenter global() {
    return GLOBAL;
  }

  synchronized boolean presentDirect(
      IrisPipelineStateCapture.PendingState pending,
      int expectedWidth, int expectedHeight) {
    return present(pending, expectedWidth, expectedHeight, true, true);
  }

  /** Presents a completed full-graph frame without a CPU or GL finish wait. */
  synchronized boolean presentGraph(
      IrisPipelineStateCapture.PendingState pending,
      int expectedWidth, int expectedHeight, boolean newSurfacePromoted) {
    pendingPromotedGraphSurface |= newSurfacePromoted;
    return present(pending, expectedWidth, expectedHeight, false, false,
        pendingPromotedGraphSurface);
  }

  synchronized boolean hasReusableGraphSurface() {
    return lastBoundTexture != 0 && lastBoundWidth > 0
        && lastBoundHeight > 0;
  }

  synchronized String lastFailure() {
    return lastFailure;
  }

  synchronized void discardPendingGraphSurface() {
    try {
      NativeBridge.nDiscardIrisMetal4FinalCutoverSurface();
    } catch (RuntimeException | LinkageError ignored) {
      // Lifecycle cleanup remains the final owner if JNI is unavailable.
    } finally {
      pendingPromotedGraphSurface = false;
    }
  }

  private boolean present(IrisPipelineStateCapture.PendingState pending,
      int expectedWidth, int expectedHeight, boolean requireNewSurface,
      boolean finishBeforeFence) {
    return present(pending, expectedWidth, expectedHeight, requireNewSurface,
        finishBeforeFence, true);
  }

  private boolean present(IrisPipelineStateCapture.PendingState pending,
      int expectedWidth, int expectedHeight, boolean requireNewSurface,
      boolean finishBeforeFence, boolean attemptNewSurface) {
    Objects.requireNonNull(pending, "pending");
    lastFailure = "";
    if (expectedWidth <= 0 || expectedHeight <= 0) {
      lastFailure = "graph-presentation-size-invalid";
      return false;
    }
    IrisGlStateSnapshot snapshot = pending.snapshot();
    if (!snapshot.drawFramebuffer().isKnown()
        || !snapshot.drawBuffers().isKnown()) {
      lastFailure = "graph-presentation-framebuffer-unknown";
      return false;
    }
    int expectedFramebuffer = snapshot.drawFramebuffer().value().name();
    int previousDrawFramebuffer = GL11C.glGetInteger(
        GL30C.GL_DRAW_FRAMEBUFFER_BINDING);

    int previousReadFramebuffer = GL11C.glGetInteger(
        GL30C.GL_READ_FRAMEBUFFER_BINDING);
    int previousReadBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
    int previousActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
    GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    int previousRectangleTexture = GL11C.glGetInteger(
        GL_TEXTURE_BINDING_RECTANGLE);
    GL13C.glActiveTexture(previousActiveTexture);
    boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
    int boundTexture = 0;
    boolean completionFenced = false;
    try {
      ensureResources();
      GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
      boolean reusableSurface = !requireNewSurface
          && lastBoundTexture != 0 && lastBoundWidth == expectedWidth
          && lastBoundHeight == expectedHeight;
      if (attemptNewSurface) {
        for (int offset = 0; offset < SURFACE_RING_SIZE; offset++) {
          int slot = (nextSurfaceSlot + offset) % SURFACE_RING_SIZE;
          int candidate = rectangleTextures[slot];
          GL11C.glBindTexture(GL_TEXTURE_RECTANGLE, candidate);
          if (NativeBridge.nBindIrisMetal4FinalCutoverSurface(
              candidate, expectedWidth, expectedHeight, reusableSurface)) {
            boundTexture = candidate;
            nextSurfaceSlot = (slot + 1) % SURFACE_RING_SIZE;
            lastBoundTexture = candidate;
            lastBoundWidth = expectedWidth;
            lastBoundHeight = expectedHeight;
            pendingPromotedGraphSurface = false;
            break;
          }
        }
      }
      if (boundTexture == 0 && reusableSurface) {
        boundTexture = lastBoundTexture;
        GL11C.glBindTexture(GL_TEXTURE_RECTANGLE, boundTexture);
      }
      if (boundTexture == 0) {
        lastFailure = "graph-presentation-surface-bind-unavailable";
        return false;
      }

      GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
      GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,
          expectedFramebuffer);
      GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER,
          GL30C.GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE,
          boundTexture, 0);
      GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
      if (GL30C.glCheckFramebufferStatus(GL30C.GL_READ_FRAMEBUFFER)
              != GL30C.GL_FRAMEBUFFER_COMPLETE
          || GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER)
              != GL30C.GL_FRAMEBUFFER_COMPLETE) {
        lastFailure = "graph-presentation-framebuffer-incomplete";
        return false;
      }
      GL30C.glBlitFramebuffer(0, expectedHeight, expectedWidth, 0,
          0, 0, expectedWidth, expectedHeight,
          GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
      if (finishBeforeFence) {
        // The legacy FINAL-only validation bridge can immediately return to
        // upstream OpenGL writes, so it retains its conservative wait. The
        // full-graph ownership path never writes these attachments through GL.
        GL11C.glFinish();
      }
      completionFenced =
          NativeBridge.nFenceIrisMetal4FinalCutoverSurface(boundTexture);
      if (!completionFenced) {
        lastFailure = "graph-presentation-release-fence-failed";
      }
      return completionFenced;
    } catch (RuntimeException | LinkageError failure) {
      lastFailure = "graph-presentation-exception";
      return false;
    } finally {
      if (boundTexture != 0 && !completionFenced) {
        try {
          NativeBridge.nFenceIrisMetal4FinalCutoverSurface(boundTexture);
        } catch (RuntimeException | LinkageError ignored) {
          // This slot stays retired until lifecycle cleanup.
        }
      }
      restoreCapability(GL11C.GL_SCISSOR_TEST, scissor);
      GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
      GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER,
          GL30C.GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, 0, 0);
      GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,
          previousReadFramebuffer);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,
          previousDrawFramebuffer);
      GL11C.glReadBuffer(previousReadBuffer);
      GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
      GL11C.glBindTexture(GL_TEXTURE_RECTANGLE,
          previousRectangleTexture);
      GL13C.glActiveTexture(previousActiveTexture);
    }
  }

  synchronized void reset() {
    try {
      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nResetIrisMetal4FinalCutoverSurface();
      }
      releaseGlResources();
    } catch (RuntimeException | LinkageError ignored) {
      // A lost context or unloaded native payload already owns cleanup.
    }
    clearState();
  }

  synchronized void resetPresentationBindings() {
    try {
      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nResetIrisMetal4PresentationBindings();
      }
      releaseGlResources();
    } catch (RuntimeException | LinkageError ignored) {
      // A display transition can race a lost context; fail open and rebuild.
    }
    clearState();
  }

  private void releaseGlResources() {
    if (readFramebuffer != 0) {
      GL30C.glDeleteFramebuffers(readFramebuffer);
    }
    for (int texture : rectangleTextures) {
      if (texture != 0) {
        GL11C.glDeleteTextures(texture);
      }
    }
  }

  private void clearState() {
    readFramebuffer = 0;
    java.util.Arrays.fill(rectangleTextures, 0);
    nextSurfaceSlot = 0;
    lastBoundTexture = 0;
    lastBoundWidth = 0;
    lastBoundHeight = 0;
    pendingPromotedGraphSurface = false;
    lastFailure = "";
  }

  private void ensureResources() {
    for (int index = 0; index < rectangleTextures.length; index++) {
      if (rectangleTextures[index] != 0) {
        continue;
      }
      rectangleTextures[index] = GL11C.glGenTextures();
      GL11C.glBindTexture(GL_TEXTURE_RECTANGLE, rectangleTextures[index]);
      GL11C.glTexParameteri(GL_TEXTURE_RECTANGLE,
          GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
      GL11C.glTexParameteri(GL_TEXTURE_RECTANGLE,
          GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
      GL11C.glTexParameteri(GL_TEXTURE_RECTANGLE,
          GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
      GL11C.glTexParameteri(GL_TEXTURE_RECTANGLE,
          GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
    }
    if (readFramebuffer == 0) {
      readFramebuffer = GL30C.glGenFramebuffers();
    }
  }

  private static void restoreCapability(int capability, boolean enabled) {
    if (enabled) {
      GL11C.glEnable(capability);
    } else {
      GL11C.glDisable(capability);
    }
  }
}

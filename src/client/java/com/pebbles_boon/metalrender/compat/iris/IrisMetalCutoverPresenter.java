package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Same-thread fail-open GPU handoff of a completed Metal IOSurface to the
 * framebuffer that the paired Iris FINAL draw would have written.
 *
 * <p>The IOSurface removes the Metal-to-CPU-to-OpenGL pixel copy. A tiny GL
 * fullscreen pass performs a byte-preserving BGRA swizzle entirely on the GPU
 * while keeping IOSurface and framebuffer coordinates in the same orientation.
 * A fenced three-slot ring avoids a blocking GL finish during steady-state
 * full-graph presentation. The legacy FINAL-only validation call remains
 * conservative, while Stage 9 presents an asynchronously completed Metal-owned
 * graph and may reuse the last fenced surface without a render-thread wait.</p>
 */
final class IrisMetalCutoverPresenter {
  static final String BRIDGE_NAME = "iosurface-gpu-handoff";
  private static final int SURFACE_RING_SIZE = 3;
  private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
  private static final int GL_TEXTURE_BINDING_RECTANGLE = 0x84F6;
  private static final int GL_RASTERIZER_DISCARD = 0x8C89;
  private static final String PRESENTATION_VERTEX_SHADER = """
      #version 410 core
      void main() {
        vec2 position = vec2(
            (gl_VertexID << 1) & 2,
            gl_VertexID & 2);
        gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
      }
      """;
  private static final String PRESENTATION_FRAGMENT_SHADER = """
      #version 410 core
      uniform sampler2DRect uSurface;
      layout(location = 0) out vec4 outColor;
      void main() {
        ivec2 coordinate = ivec2(gl_FragCoord.xy);
        outColor = texelFetch(uSurface, coordinate).bgra;
      }
      """;
  private static final IrisMetalCutoverPresenter GLOBAL =
      new IrisMetalCutoverPresenter();
  private final int[] rectangleTextures = new int[SURFACE_RING_SIZE];
  private final int[] savedViewport = new int[4];
  private final int[] savedPolygonMode = new int[2];
  private final ByteBuffer savedColorMask = BufferUtils.createByteBuffer(4);
  private int presentationProgram;
  private int presentationVertexArray;
  private int presentationSurfaceUniform = -1;
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
    int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
    int previousVertexArray = GL11C.glGetInteger(
        GL30C.GL_VERTEX_ARRAY_BINDING);
    int previousActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
    GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    int previousRectangleTexture = GL11C.glGetInteger(
        GL_TEXTURE_BINDING_RECTANGLE);
    GL13C.glActiveTexture(previousActiveTexture);
    boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
    boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
    boolean depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
    boolean stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
    boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
    boolean rasterizerDiscard = GL11C.glIsEnabled(GL_RASTERIZER_DISCARD);
    boolean framebufferSrgb = GL11C.glIsEnabled(
        GL30C.GL_FRAMEBUFFER_SRGB);
    boolean colorLogicOperation = GL11C.glIsEnabled(GL11C.GL_COLOR_LOGIC_OP);
    boolean dither = GL11C.glIsEnabled(GL11C.GL_DITHER);
    GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, savedViewport);
    GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, savedPolygonMode);
    savedColorMask.clear();
    GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, savedColorMask);
    boolean colorMaskRed = savedColorMask.get(0) != 0;
    boolean colorMaskGreen = savedColorMask.get(1) != 0;
    boolean colorMaskBlue = savedColorMask.get(2) != 0;
    boolean colorMaskAlpha = savedColorMask.get(3) != 0;
    int boundTexture = 0;
    boolean completionFenced = false;
    try {
      if (!ensureResources()) {
        return false;
      }
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
      GL11C.glDisable(GL11C.GL_BLEND);
      GL11C.glDisable(GL11C.GL_DEPTH_TEST);
      GL11C.glDisable(GL11C.GL_STENCIL_TEST);
      GL11C.glDisable(GL11C.GL_CULL_FACE);
      GL11C.glDisable(GL_RASTERIZER_DISCARD);
      GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
      GL11C.glDisable(GL11C.GL_COLOR_LOGIC_OP);
      GL11C.glDisable(GL11C.GL_DITHER);
      GL11C.glColorMask(true, true, true, true);
      GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, GL11C.GL_FILL);
      GL11C.glViewport(0, 0, expectedWidth, expectedHeight);
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,
          expectedFramebuffer);
      if (GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER)
              != GL30C.GL_FRAMEBUFFER_COMPLETE) {
        lastFailure = "graph-presentation-framebuffer-incomplete";
        return false;
      }
      GL20C.glUseProgram(presentationProgram);
      GL20C.glUniform1i(presentationSurfaceUniform, 0);
      GL30C.glBindVertexArray(presentationVertexArray);
      GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
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
      GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,
          previousDrawFramebuffer);
      GL11C.glViewport(savedViewport[0], savedViewport[1],
          savedViewport[2], savedViewport[3]);
      GL11C.glPolygonMode(GL11C.GL_FRONT, savedPolygonMode[0]);
      GL11C.glPolygonMode(GL11C.GL_BACK, savedPolygonMode[1]);
      GL11C.glColorMask(colorMaskRed, colorMaskGreen, colorMaskBlue,
          colorMaskAlpha);
      restoreCapability(GL11C.GL_SCISSOR_TEST, scissor);
      restoreCapability(GL11C.GL_BLEND, blend);
      restoreCapability(GL11C.GL_DEPTH_TEST, depth);
      restoreCapability(GL11C.GL_STENCIL_TEST, stencil);
      restoreCapability(GL11C.GL_CULL_FACE, cull);
      restoreCapability(GL_RASTERIZER_DISCARD, rasterizerDiscard);
      restoreCapability(GL30C.GL_FRAMEBUFFER_SRGB, framebufferSrgb);
      restoreCapability(GL11C.GL_COLOR_LOGIC_OP, colorLogicOperation);
      restoreCapability(GL11C.GL_DITHER, dither);
      GL20C.glUseProgram(previousProgram);
      GL30C.glBindVertexArray(previousVertexArray);
      GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
      GL11C.glBindTexture(GL_TEXTURE_RECTANGLE,
          previousRectangleTexture);
      GL13C.glActiveTexture(previousActiveTexture);
    }
  }

  synchronized void reset() {
    try {
      // Delete the CGL texture objects before native code returns their
      // IOSurfaces to Metal. Reversing this order leaves a short window where
      // the submit worker can reuse a surface that CGL still references.
      releaseGlResources();
    } catch (RuntimeException | LinkageError ignored) {
      // A lost context or unloaded native payload already owns cleanup.
    }
    try {
      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nResetIrisMetal4FinalCutoverSurface();
      }
    } catch (RuntimeException | LinkageError ignored) {
      // The native lifecycle owner may already have been torn down.
    }
    clearState();
  }

  synchronized void resetPresentationBindings() {
    try {
      // See reset(): CGL must relinquish every texture binding before its
      // backing IOSurface becomes eligible for Metal reuse.
      releaseGlResources();
    } catch (RuntimeException | LinkageError ignored) {
      // A display transition can race a lost context; fail open and rebuild.
    }
    try {
      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nResetIrisMetal4PresentationBindings();
      }
    } catch (RuntimeException | LinkageError ignored) {
      // The native lifecycle owner may already have been torn down.
    }
    clearState();
  }

  private void releaseGlResources() {
    if (presentationProgram != 0) {
      GL20C.glDeleteProgram(presentationProgram);
    }
    if (presentationVertexArray != 0) {
      GL30C.glDeleteVertexArrays(presentationVertexArray);
    }
    for (int texture : rectangleTextures) {
      if (texture != 0) {
        GL11C.glDeleteTextures(texture);
      }
    }
  }

  private void clearState() {
    presentationProgram = 0;
    presentationVertexArray = 0;
    presentationSurfaceUniform = -1;
    java.util.Arrays.fill(rectangleTextures, 0);
    nextSurfaceSlot = 0;
    lastBoundTexture = 0;
    lastBoundWidth = 0;
    lastBoundHeight = 0;
    pendingPromotedGraphSurface = false;
    lastFailure = "";
  }

  private boolean ensureResources() {
    if (presentationProgram == 0 && !createPresentationProgram()) {
      return false;
    }
    if (presentationVertexArray == 0) {
      presentationVertexArray = GL30C.glGenVertexArrays();
      if (presentationVertexArray == 0) {
        lastFailure = "graph-presentation-vertex-array-create-failed";
        return false;
      }
    }
    for (int index = 0; index < rectangleTextures.length; index++) {
      if (rectangleTextures[index] != 0) {
        continue;
      }
      rectangleTextures[index] = GL11C.glGenTextures();
      if (rectangleTextures[index] == 0) {
        lastFailure = "graph-presentation-texture-create-failed";
        return false;
      }
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
    return true;
  }

  private boolean createPresentationProgram() {
    int vertexShader = compileShader(GL20C.GL_VERTEX_SHADER,
        PRESENTATION_VERTEX_SHADER,
        "graph-presentation-vertex-shader-compile-failed");
    if (vertexShader == 0) {
      return false;
    }
    int fragmentShader = compileShader(GL20C.GL_FRAGMENT_SHADER,
        PRESENTATION_FRAGMENT_SHADER,
        "graph-presentation-fragment-shader-compile-failed");
    if (fragmentShader == 0) {
      GL20C.glDeleteShader(vertexShader);
      return false;
    }
    int program = GL20C.glCreateProgram();
    GL20C.glAttachShader(program, vertexShader);
    GL20C.glAttachShader(program, fragmentShader);
    GL20C.glLinkProgram(program);
    GL20C.glDeleteShader(vertexShader);
    GL20C.glDeleteShader(fragmentShader);
    if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS)
        == GL11C.GL_FALSE) {
      GL20C.glDeleteProgram(program);
      lastFailure = "graph-presentation-shader-link-failed";
      return false;
    }
    int surfaceUniform = GL20C.glGetUniformLocation(program, "uSurface");
    if (surfaceUniform < 0) {
      GL20C.glDeleteProgram(program);
      lastFailure = "graph-presentation-shader-uniform-missing";
      return false;
    }
    presentationProgram = program;
    presentationSurfaceUniform = surfaceUniform;
    return true;
  }

  private int compileShader(int type, String source, String failureReason) {
    int shader = GL20C.glCreateShader(type);
    GL20C.glShaderSource(shader, source);
    GL20C.glCompileShader(shader);
    if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS)
        == GL11C.GL_FALSE) {
      GL20C.glDeleteShader(shader);
      lastFailure = failureReason;
      return 0;
    }
    return shader;
  }

  private static void restoreCapability(int capability, boolean enabled) {
    if (enabled) {
      GL11C.glEnable(capability);
    } else {
      GL11C.glDisable(capability);
    }
  }
}

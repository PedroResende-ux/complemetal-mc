package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.BlendState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ColorMask;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ColorTarget;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.DepthState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.MultisampleState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.Operation;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.PrimitiveState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.RasterState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceHandle;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.StateValue;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.StencilFace;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.StencilState;
import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.TextureAttachment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe, bounded shadow of the OpenGL state needed to describe Iris
 * graphics and compute pipeline variants.
 *
 * <p>This class never calls OpenGL. Mixin hooks feed it numeric GL constants,
 * and draw/dispatch hooks obtain immutable snapshots. State not observed since
 * the last context reset remains explicitly unknown.</p>
 */
public final class IrisGlStateTracker {
  public static final int MAX_COLOR_ATTACHMENTS =
      IrisGlStateSnapshot.MAX_COLOR_ATTACHMENTS;

  // Numeric OpenGL constants keep this pure Java and independently testable.
  public static final int GL_NONE = 0;
  public static final int GL_FRONT = 0x0404;
  public static final int GL_BACK = 0x0405;
  public static final int GL_FRONT_AND_BACK = 0x0408;
  public static final int GL_BLEND = 0x0BE2;
  public static final int GL_DEPTH_TEST = 0x0B71;
  public static final int GL_STENCIL_TEST = 0x0B90;
  public static final int GL_CULL_FACE = 0x0B44;
  public static final int GL_SAMPLE_ALPHA_TO_COVERAGE = 0x809E;
  public static final int GL_SAMPLE_ALPHA_TO_ONE = 0x809F;
  public static final int GL_SAMPLE_COVERAGE = 0x80A0;
  public static final int GL_SAMPLE_MASK = 0x8E51;
  public static final int GL_PRIMITIVE_RESTART = 0x8F9D;
  public static final int GL_PRIMITIVE_RESTART_FIXED_INDEX = 0x8D69;
  public static final int GL_DEPTH_CLAMP = 0x864F;
  public static final int GL_RASTERIZER_DISCARD = 0x8C89;
  public static final int GL_POLYGON_OFFSET_POINT = 0x2A01;
  public static final int GL_POLYGON_OFFSET_LINE = 0x2A02;
  public static final int GL_POLYGON_OFFSET_FILL = 0x8037;
  public static final int GL_FRAMEBUFFER = 0x8D40;
  public static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
  public static final int GL_READ_FRAMEBUFFER = 0x8CA8;
  public static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
  public static final int GL_DEPTH_ATTACHMENT = 0x8D00;
  public static final int GL_STENCIL_ATTACHMENT = 0x8D20;
  public static final int GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;
  public static final int GL_FUNC_ADD = 0x8006;
  public static final int GL_ONE = 1;
  public static final int GL_ZERO = 0;
  public static final int GL_LESS = 0x0201;
  public static final int GL_ALWAYS = 0x0207;
  public static final int GL_KEEP = 0x1E00;
  public static final int GL_CCW = 0x0901;
  public static final int GL_FILL = 0x1B02;

  private static final int DEFAULT_MAX_PROGRAMS = 4_096;
  private static final int DEFAULT_MAX_FRAMEBUFFERS = 2_048;
  private static final int DEFAULT_MAX_TEXTURES = 32_768;

  private final int maxPrograms;
  private final int maxFramebuffers;
  private final int maxTextures;
  private final LinkedHashMap<Integer, ResourceHandle> programs =
      new LinkedHashMap<>(16, 0.75F, true);
  private final LinkedHashMap<Integer, FramebufferEntry> framebuffers =
      new LinkedHashMap<>(16, 0.75F, true);
  private final LinkedHashMap<Integer, TextureEntry> textures =
      new LinkedHashMap<>(16, 0.75F, true);

  private long contextGeneration = 1;
  private long nextResourceGeneration = 1;
  private long snapshotSequence;
  private long resourceEvictions;
  private FramebufferEntry defaultFramebuffer;
  private StateValue<Optional<ResourceHandle>> currentProgram;
  private StateValue<ResourceHandle> currentDrawFramebuffer;

  private StateValue<Boolean> globalBlendEnabled;
  private StateValue<Integer> globalBlendRgbEquation;
  private StateValue<Integer> globalBlendAlphaEquation;
  private StateValue<Integer> globalBlendSourceRgb;
  private StateValue<Integer> globalBlendDestinationRgb;
  private StateValue<Integer> globalBlendSourceAlpha;
  private StateValue<Integer> globalBlendDestinationAlpha;
  private final BlendOverride[] indexedBlend =
      new BlendOverride[MAX_COLOR_ATTACHMENTS];
  private StateValue<ColorMask> globalColorMask;
  private final ColorMask[] indexedColorMask =
      new ColorMask[MAX_COLOR_ATTACHMENTS];

  private StateValue<Boolean> depthTestEnabled;
  private StateValue<Integer> depthFunction;
  private StateValue<Boolean> depthWriteEnabled;
  private StateValue<Boolean> stencilTestEnabled;
  private MutableStencilFace stencilFront;
  private MutableStencilFace stencilBack;
  private StateValue<Boolean> cullEnabled;
  private StateValue<Integer> cullMode;
  private StateValue<Integer> frontFace;
  private StateValue<Boolean> depthClampEnabled;
  private StateValue<Boolean> rasterizerDiscardEnabled;
  private StateValue<Integer> polygonModeFront;
  private StateValue<Integer> polygonModeBack;
  private StateValue<Boolean> polygonOffsetPointEnabled;
  private StateValue<Boolean> polygonOffsetLineEnabled;
  private StateValue<Boolean> polygonOffsetFillEnabled;
  private StateValue<Float> polygonOffsetFactor;
  private StateValue<Float> polygonOffsetUnits;
  private StateValue<Float> polygonOffsetClamp;
  private StateValue<Boolean> sampleCoverageEnabled;
  private StateValue<Float> sampleCoverageValue;
  private StateValue<Boolean> sampleCoverageInvert;
  private StateValue<Boolean> sampleMaskEnabled;
  private StateValue<Integer> sampleMaskWord0;
  private StateValue<Integer> sampleMaskWord1;
  private StateValue<Boolean> alphaToCoverageEnabled;
  private StateValue<Boolean> alphaToOneEnabled;
  private StateValue<Boolean> primitiveRestartEnabled;
  private StateValue<Boolean> fixedIndexRestartEnabled;

  public IrisGlStateTracker() {
    this(DEFAULT_MAX_PROGRAMS, DEFAULT_MAX_FRAMEBUFFERS,
        DEFAULT_MAX_TEXTURES);
  }

  public IrisGlStateTracker(int maxPrograms, int maxFramebuffers,
                            int maxTextures) {
    if (maxPrograms <= 0 || maxFramebuffers <= 0 || maxTextures <= 0) {
      throw new IllegalArgumentException("tracker bounds must be positive");
    }
    this.maxPrograms = maxPrograms;
    this.maxFramebuffers = maxFramebuffers;
    this.maxTextures = maxTextures;
    initializeUnknownState("not observed since tracker creation");
  }

  /** Invalidates every old handle and returns the new context generation. */
  public synchronized long resetContext() {
    if (contextGeneration == Long.MAX_VALUE) {
      throw new IllegalStateException("GL context generation exhausted");
    }
    contextGeneration++;
    nextResourceGeneration = 1;
    snapshotSequence = 0;
    resourceEvictions = 0;
    programs.clear();
    framebuffers.clear();
    textures.clear();
    initializeUnknownState("not observed since GL context reset");
    return contextGeneration;
  }

  /**
   * Seeds the state mandated for a newly initialized OpenGL context.
   *
   * <p>This is intentionally separate from {@link #resetContext()}, which is
   * fail-closed. Call it exactly once after GL context initialization. Default
   * framebuffer attachment formats remain unknown because OpenGL does not
   * expose them through these defaults.</p>
   */
  public synchronized void initializeOpenGlDefaults() {
    currentProgram = known(Optional.empty());
    currentDrawFramebuffer = known(defaultFramebuffer.handle);
    defaultFramebuffer.drawBuffers = known(List.of(GL_BACK));
    setGlobalBlendEnabled(false);
    blendEquation(GL_FUNC_ADD);
    blendFunc(GL_ONE, GL_ZERO);
    colorMask(true, true, true, true);
    depthTestEnabled = known(false);
    depthFunction = known(GL_LESS);
    depthWriteEnabled = known(true);
    stencilTestEnabled = known(false);
    stencilFunc(GL_ALWAYS, 0, -1);
    stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    stencilMask(-1);
    cullEnabled = known(false);
    cullMode = known(GL_BACK);
    frontFace = known(GL_CCW);
    depthClampEnabled = known(false);
    rasterizerDiscardEnabled = known(false);
    polygonMode(GL_FRONT_AND_BACK, GL_FILL);
    polygonOffsetPointEnabled = known(false);
    polygonOffsetLineEnabled = known(false);
    polygonOffsetFillEnabled = known(false);
    polygonOffset(0.0F, 0.0F);
    sampleCoverageEnabled = known(false);
    sampleCoverage(1.0F, false);
    sampleMaskEnabled = known(false);
    sampleMaskWord0 = known(-1);
    sampleMaskWord1 = known(-1);
    alphaToCoverageEnabled = known(false);
    alphaToOneEnabled = known(false);
    primitiveRestartEnabled = known(false);
    fixedIndexRestartEnabled = known(false);
  }

  public synchronized long contextGeneration() {
    return contextGeneration;
  }

  public synchronized long resourceEvictions() {
    return resourceEvictions;
  }

  public synchronized ResourceHandle registerProgram(int name) {
    requirePositiveName(name, "program");
    ResourceHandle handle = newHandle(ResourceKind.PROGRAM, name);
    putBounded(programs, maxPrograms, name, handle);
    return handle;
  }

  public synchronized boolean deleteProgram(ResourceHandle expected) {
    requireKind(expected, ResourceKind.PROGRAM);
    ResourceHandle current = programs.get(expected.name());
    if (!expected.equals(current)) {
      return false;
    }
    programs.remove(expected.name());
    // OpenGL keeps a current program alive until it is unbound. The qualified
    // binding therefore remains valid even after its name is deleted.
    return true;
  }

  public synchronized boolean deleteProgram(int name) {
    ResourceHandle current = programs.get(name);
    return current != null && deleteProgram(current);
  }

  public synchronized void useProgram(int name) {
    if (name == 0) {
      currentProgram = known(Optional.empty());
      return;
    }
    ResourceHandle handle = programs.get(name);
    currentProgram = handle == null
        ? unknown("program " + name + " has not been registered")
        : known(Optional.of(handle));
  }

  public synchronized boolean useProgram(ResourceHandle handle) {
    requireKind(handle, ResourceKind.PROGRAM);
    ResourceHandle current = programs.get(handle.name());
    if (!handle.equals(current)) {
      currentProgram = unknown("stale program handle " + handle.name());
      return false;
    }
    currentProgram = known(Optional.of(handle));
    return true;
  }

  public synchronized ResourceHandle registerFramebuffer(int name) {
    requirePositiveName(name, "framebuffer");
    ResourceHandle handle = newHandle(ResourceKind.FRAMEBUFFER, name);
    putBounded(framebuffers, maxFramebuffers, name,
        FramebufferEntry.custom(handle));
    return handle;
  }

  /**
   * Defines a framebuffer name observed through a DSA path. Repeated
   * observations preserve the resource generation; only a delete followed by
   * reuse of the same numeric name creates a new generation.
   */
  public synchronized ResourceHandle defineFramebuffer(int name) {
    requirePositiveName(name, "framebuffer");
    FramebufferEntry existing = framebuffers.get(name);
    return existing == null ? registerFramebuffer(name) : existing.handle;
  }

  public synchronized boolean deleteFramebuffer(ResourceHandle expected) {
    requireKind(expected, ResourceKind.FRAMEBUFFER);
    if (expected.name() == 0) {
      return false;
    }
    FramebufferEntry current = framebuffers.get(expected.name());
    if (current == null || !expected.equals(current.handle)) {
      return false;
    }
    framebuffers.remove(expected.name());
    if (currentDrawFramebuffer.isKnown()
        && expected.equals(currentDrawFramebuffer.value())) {
      currentDrawFramebuffer = known(defaultFramebuffer.handle);
    }
    return true;
  }

  public synchronized boolean deleteFramebuffer(int name) {
    FramebufferEntry current = framebuffers.get(name);
    return current != null && deleteFramebuffer(current.handle);
  }

  /** Clears tracked attachment/draw-buffer state only for the exact handle. */
  public synchronized boolean resetFramebuffer(ResourceHandle expected) {
    requireKind(expected, ResourceKind.FRAMEBUFFER);
    FramebufferEntry entry = framebuffer(expected);
    if (entry == null || entry.defaultFramebuffer) {
      return false;
    }
    entry.resetCustom();
    return true;
  }

  public synchronized void bindFramebuffer(int target, int name) {
    if (target == GL_READ_FRAMEBUFFER) {
      return;
    }
    requireDrawFramebufferTarget(target);
    if (name == 0) {
      currentDrawFramebuffer = known(defaultFramebuffer.handle);
      return;
    }
    FramebufferEntry entry = framebuffers.get(name);
    currentDrawFramebuffer = entry == null
        ? unknown("framebuffer " + name + " has not been registered")
        : known(entry.handle);
  }

  public synchronized boolean bindFramebuffer(int target,
                                               ResourceHandle handle) {
    if (target == GL_READ_FRAMEBUFFER) {
      return true;
    }
    requireDrawFramebufferTarget(target);
    requireKind(handle, ResourceKind.FRAMEBUFFER);
    FramebufferEntry entry = framebuffer(handle);
    if (entry == null) {
      currentDrawFramebuffer = unknown(
          "stale framebuffer handle " + handle.name());
      return false;
    }
    currentDrawFramebuffer = known(handle);
    return true;
  }

  public synchronized ResourceHandle registerTexture(int name, String format,
                                                       int sampleCount) {
    if (sampleCount <= 0 || sampleCount > 64) {
      throw new IllegalArgumentException("sample count must be in 1..64");
    }
    return registerTexture(name, format, known(sampleCount));
  }

  /**
   * Defines or redefines texture storage. Redefinition preserves the object's
   * generation, as required for resize calls on an existing texture name.
   */
  public synchronized ResourceHandle defineTexture(int name, String format,
                                                    int sampleCount) {
    if (sampleCount <= 0 || sampleCount > 64) {
      throw new IllegalArgumentException("sample count must be in 1..64");
    }
    requirePositiveName(name, "texture");
    requireFormat(format);
    TextureEntry existing = textures.get(name);
    if (existing == null) {
      return registerTexture(name, format, known(sampleCount));
    }
    existing.format = format;
    existing.sampleCount = known(sampleCount);
    refreshTextureAttachments(existing);
    return existing.handle;
  }

  /** Registers a texture while explicitly preserving an unknown sample count. */
  public synchronized ResourceHandle registerTextureUnknownSampleCount(
      int name, String format, String reason) {
    return registerTexture(name, format, unknown(reason));
  }

  private ResourceHandle registerTexture(int name, String format,
                                         StateValue<Integer> sampleCount) {
    requirePositiveName(name, "texture");
    requireFormat(format);
    ResourceHandle handle = newHandle(ResourceKind.TEXTURE, name);
    putBounded(textures, maxTextures, name,
        new TextureEntry(handle, format, sampleCount));
    return handle;
  }

  public synchronized boolean deleteTexture(ResourceHandle expected) {
    requireKind(expected, ResourceKind.TEXTURE);
    TextureEntry current = textures.get(expected.name());
    if (current == null || !expected.equals(current.handle)) {
      return false;
    }
    textures.remove(expected.name());
    // Attachments retain the exact old object generation and its metadata.
    return true;
  }

  public synchronized boolean deleteTexture(int name) {
    TextureEntry current = textures.get(name);
    return current != null && deleteTexture(current.handle);
  }

  public synchronized boolean framebufferTexture2D(
      int target, int attachment, int textureTarget, int textureName,
      int mipLevel) {
    if (target == GL_READ_FRAMEBUFFER) {
      return false;
    }
    requireDrawFramebufferTarget(target);
    if (mipLevel < 0) {
      throw new IllegalArgumentException("negative texture mip level");
    }
    FramebufferEntry framebuffer = boundFramebuffer();
    if (framebuffer == null || framebuffer.defaultFramebuffer) {
      return false;
    }
    StateValue<Optional<TextureAttachment>> value;
    if (textureName == 0) {
      value = known(Optional.empty());
    } else {
      TextureEntry texture = textures.get(textureName);
      value = texture == null
          ? unknown("texture " + textureName + " has not been registered")
          : known(Optional.of(texture.attachment(textureTarget, mipLevel)));
    }
    setAttachment(framebuffer, attachment, value);
    return true;
  }

  public synchronized boolean framebufferTexture2D(
      ResourceHandle framebufferHandle, int attachment, int textureTarget,
      ResourceHandle textureHandle, int mipLevel) {
    requireKind(framebufferHandle, ResourceKind.FRAMEBUFFER);
    requireKind(textureHandle, ResourceKind.TEXTURE);
    if (mipLevel < 0) {
      throw new IllegalArgumentException("negative texture mip level");
    }
    FramebufferEntry framebuffer = framebuffer(framebufferHandle);
    TextureEntry texture = texture(textureHandle);
    if (framebuffer == null || framebuffer.defaultFramebuffer
        || texture == null) {
      return false;
    }
    setAttachment(framebuffer, attachment,
        known(Optional.of(texture.attachment(textureTarget, mipLevel))));
    return true;
  }

  public synchronized boolean detachFramebufferAttachment(
      ResourceHandle framebufferHandle, int attachment) {
    requireKind(framebufferHandle, ResourceKind.FRAMEBUFFER);
    FramebufferEntry framebuffer = framebuffer(framebufferHandle);
    if (framebuffer == null || framebuffer.defaultFramebuffer) {
      return false;
    }
    setAttachment(framebuffer, attachment, known(Optional.empty()));
    return true;
  }

  public synchronized boolean drawBuffers(int... buffers) {
    Objects.requireNonNull(buffers, "buffers");
    if (buffers.length > MAX_COLOR_ATTACHMENTS) {
      throw new IllegalArgumentException(
          "at most " + MAX_COLOR_ATTACHMENTS + " draw buffers are supported");
    }
    FramebufferEntry framebuffer = boundFramebuffer();
    if (framebuffer == null) {
      return false;
    }
    ArrayList<Integer> copy = new ArrayList<>(buffers.length);
    for (int buffer : buffers) {
      copy.add(buffer);
    }
    framebuffer.drawBuffers = known(List.copyOf(copy));
    return true;
  }

  public synchronized boolean drawBuffers(ResourceHandle framebufferHandle,
                                           List<Integer> buffers) {
    requireKind(framebufferHandle, ResourceKind.FRAMEBUFFER);
    Objects.requireNonNull(buffers, "buffers");
    if (buffers.size() > MAX_COLOR_ATTACHMENTS) {
      throw new IllegalArgumentException(
          "at most " + MAX_COLOR_ATTACHMENTS + " draw buffers are supported");
    }
    FramebufferEntry framebuffer = framebuffer(framebufferHandle);
    if (framebuffer == null) {
      return false;
    }
    ArrayList<Integer> copy = new ArrayList<>(buffers.size());
    for (Integer buffer : buffers) {
      copy.add(Objects.requireNonNull(buffer, "draw buffer"));
    }
    framebuffer.drawBuffers = known(List.copyOf(copy));
    return true;
  }

  /** DSA/name-based draw-buffer update that never changes current binding. */
  public synchronized boolean drawBuffersForFramebuffer(int framebufferName,
                                                        int... buffers) {
    Objects.requireNonNull(buffers, "buffers");
    FramebufferEntry framebuffer = framebufferByName(framebufferName);
    if (framebuffer == null) {
      return false;
    }
    return drawBuffers(framebuffer.handle, intList(buffers));
  }

  /** DSA/name-based attachment update that never changes current binding. */
  public synchronized boolean framebufferTexture2DForFramebuffer(
      int framebufferName, int attachment, int textureTarget, int textureName,
      int mipLevel) {
    if (mipLevel < 0) {
      throw new IllegalArgumentException("negative texture mip level");
    }
    FramebufferEntry framebuffer = framebufferByName(framebufferName);
    if (framebuffer == null || framebuffer.defaultFramebuffer) {
      return false;
    }
    StateValue<Optional<TextureAttachment>> value;
    if (textureName == 0) {
      value = known(Optional.empty());
    } else {
      TextureEntry texture = textures.get(textureName);
      value = texture == null
          ? unknown("texture " + textureName + " has not been registered")
          : known(Optional.of(texture.attachment(textureTarget, mipLevel)));
    }
    setAttachment(framebuffer, attachment, value);
    return true;
  }

  /** Tracks glEnable/glDisable. Returns false for a capability outside scope. */
  public synchronized boolean capability(int capability, boolean enabled) {
    switch (capability) {
      case GL_BLEND -> setGlobalBlendEnabled(enabled);
      case GL_DEPTH_TEST -> depthTestEnabled = known(enabled);
      case GL_STENCIL_TEST -> stencilTestEnabled = known(enabled);
      case GL_CULL_FACE -> cullEnabled = known(enabled);
      case GL_DEPTH_CLAMP -> depthClampEnabled = known(enabled);
      case GL_RASTERIZER_DISCARD -> rasterizerDiscardEnabled = known(enabled);
      case GL_POLYGON_OFFSET_POINT ->
          polygonOffsetPointEnabled = known(enabled);
      case GL_POLYGON_OFFSET_LINE -> polygonOffsetLineEnabled = known(enabled);
      case GL_POLYGON_OFFSET_FILL -> polygonOffsetFillEnabled = known(enabled);
      case GL_SAMPLE_COVERAGE -> sampleCoverageEnabled = known(enabled);
      case GL_SAMPLE_MASK -> sampleMaskEnabled = known(enabled);
      case GL_SAMPLE_ALPHA_TO_COVERAGE ->
          alphaToCoverageEnabled = known(enabled);
      case GL_SAMPLE_ALPHA_TO_ONE -> alphaToOneEnabled = known(enabled);
      case GL_PRIMITIVE_RESTART -> primitiveRestartEnabled = known(enabled);
      case GL_PRIMITIVE_RESTART_FIXED_INDEX ->
          fixedIndexRestartEnabled = known(enabled);
      default -> {
        return false;
      }
    }
    return true;
  }

  /** Tracks glEnablei/glDisablei for indexed blending. */
  public synchronized boolean capabilityIndexed(int capability, int index,
                                                boolean enabled) {
    requireColorIndex(index);
    if (capability != GL_BLEND) {
      return false;
    }
    indexedBlend[index].enabled = enabled;
    return true;
  }

  public synchronized void blendEquationSeparate(int rgb, int alpha) {
    globalBlendRgbEquation = known(rgb);
    globalBlendAlphaEquation = known(alpha);
    for (BlendOverride override : indexedBlend) {
      override.rgbEquation = null;
      override.alphaEquation = null;
    }
  }

  public synchronized void blendEquation(int equation) {
    blendEquationSeparate(equation, equation);
  }

  public synchronized void blendEquationSeparateIndexed(int index, int rgb,
                                                         int alpha) {
    requireColorIndex(index);
    indexedBlend[index].rgbEquation = rgb;
    indexedBlend[index].alphaEquation = alpha;
  }

  public synchronized void blendEquationIndexed(int index, int equation) {
    blendEquationSeparateIndexed(index, equation, equation);
  }

  public synchronized void blendFuncSeparate(int sourceRgb,
                                              int destinationRgb,
                                              int sourceAlpha,
                                              int destinationAlpha) {
    globalBlendSourceRgb = known(sourceRgb);
    globalBlendDestinationRgb = known(destinationRgb);
    globalBlendSourceAlpha = known(sourceAlpha);
    globalBlendDestinationAlpha = known(destinationAlpha);
    for (BlendOverride override : indexedBlend) {
      override.sourceRgb = null;
      override.destinationRgb = null;
      override.sourceAlpha = null;
      override.destinationAlpha = null;
    }
  }

  public synchronized void blendFunc(int source, int destination) {
    blendFuncSeparate(source, destination, source, destination);
  }

  public synchronized void blendFuncSeparateIndexed(int index, int sourceRgb,
      int destinationRgb, int sourceAlpha, int destinationAlpha) {
    requireColorIndex(index);
    BlendOverride override = indexedBlend[index];
    override.sourceRgb = sourceRgb;
    override.destinationRgb = destinationRgb;
    override.sourceAlpha = sourceAlpha;
    override.destinationAlpha = destinationAlpha;
  }

  public synchronized void blendFuncIndexed(int index, int source,
                                            int destination) {
    blendFuncSeparateIndexed(index, source, destination, source, destination);
  }

  public synchronized void colorMask(boolean red, boolean green, boolean blue,
                                     boolean alpha) {
    globalColorMask = known(new ColorMask(red, green, blue, alpha));
    for (int index = 0; index < indexedColorMask.length; index++) {
      indexedColorMask[index] = null;
    }
  }

  public synchronized void colorMaskIndexed(int index, boolean red,
                                            boolean green, boolean blue,
                                            boolean alpha) {
    requireColorIndex(index);
    indexedColorMask[index] = new ColorMask(red, green, blue, alpha);
  }

  public synchronized void depthFunc(int function) {
    depthFunction = known(function);
  }

  public synchronized void depthMask(boolean enabled) {
    depthWriteEnabled = known(enabled);
  }

  public synchronized void stencilFunc(int function, int reference,
                                       int readMask) {
    stencilFuncSeparate(GL_FRONT_AND_BACK, function, reference, readMask);
  }

  public synchronized void stencilFuncSeparate(int face, int function,
                                               int reference, int readMask) {
    forStencilFaces(face, stencil -> {
      stencil.function = known(function);
      stencil.reference = known(reference);
      stencil.readMask = known(readMask);
    });
  }

  public synchronized void stencilOp(int stencilFail, int depthFail,
                                     int depthPass) {
    stencilOpSeparate(GL_FRONT_AND_BACK, stencilFail, depthFail, depthPass);
  }

  public synchronized void stencilOpSeparate(int face, int stencilFail,
                                             int depthFail, int depthPass) {
    forStencilFaces(face, stencil -> {
      stencil.stencilFail = known(stencilFail);
      stencil.depthFail = known(depthFail);
      stencil.depthPass = known(depthPass);
    });
  }

  public synchronized void stencilMask(int writeMask) {
    stencilMaskSeparate(GL_FRONT_AND_BACK, writeMask);
  }

  public synchronized void stencilMaskSeparate(int face, int writeMask) {
    forStencilFaces(face, stencil -> stencil.writeMask = known(writeMask));
  }

  public synchronized void cullFace(int mode) {
    cullMode = known(mode);
  }

  public synchronized void frontFace(int winding) {
    frontFace = known(winding);
  }

  public synchronized void polygonMode(int face, int mode) {
    switch (face) {
      case GL_FRONT -> polygonModeFront = known(mode);
      case GL_BACK -> polygonModeBack = known(mode);
      case GL_FRONT_AND_BACK -> {
        polygonModeFront = known(mode);
        polygonModeBack = known(mode);
      }
      default -> throw new IllegalArgumentException(
          "unsupported polygon face 0x" + Integer.toHexString(face));
    }
  }

  /** Tracks glPolygonOffset; clamp is explicitly zero for this API. */
  public synchronized void polygonOffset(float factor, float units) {
    polygonOffsetClamp(factor, units, 0.0F);
  }

  public synchronized void polygonOffsetClamp(float factor, float units,
                                              float clamp) {
    polygonOffsetFactor = known(finite(factor, "polygon offset factor"));
    polygonOffsetUnits = known(finite(units, "polygon offset units"));
    polygonOffsetClamp = known(finite(clamp, "polygon offset clamp"));
  }

  public synchronized void sampleCoverage(float value, boolean invert) {
    if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
      throw new IllegalArgumentException("sample coverage must be in 0..1");
    }
    sampleCoverageValue = known(normalizeZero(value));
    sampleCoverageInvert = known(invert);
  }

  /** Tracks the first two 32-bit GL sample-mask words (up to 64 samples). */
  public synchronized void sampleMask(int maskNumber, int mask) {
    switch (maskNumber) {
      case 0 -> sampleMaskWord0 = known(mask);
      case 1 -> sampleMaskWord1 = known(mask);
      default -> throw new IllegalArgumentException(
          "only sample mask words 0 and 1 are supported");
    }
  }

  public synchronized IrisGlStateSnapshot snapshotDraw(int primitiveMode) {
    return snapshot(Operation.DRAW, known(primitiveMode));
  }

  public synchronized IrisGlStateSnapshot snapshotDispatch() {
    return snapshot(Operation.DISPATCH,
        unknown("primitive mode does not apply to compute dispatch"));
  }

  private IrisGlStateSnapshot snapshot(Operation operation,
                                       StateValue<Integer> primitiveMode) {
    snapshotSequence++;
    FramebufferEntry framebuffer = snapshotFramebuffer();
    StateValue<List<Integer>> buffers = framebuffer == null
        ? unknown("draw framebuffer state is unavailable")
        : framebuffer.drawBuffers;

    ArrayList<ColorTarget> targets = new ArrayList<>(MAX_COLOR_ATTACHMENTS);
    for (int index = 0; index < MAX_COLOR_ATTACHMENTS; index++) {
      StateValue<Integer> drawBuffer = drawBuffer(buffers, index);
      targets.add(new ColorTarget(index, drawBuffer,
          colorAttachment(framebuffer, drawBuffer), blend(index),
          indexedColorMask[index] == null
              ? globalColorMask : known(indexedColorMask[index])));
    }

    StateValue<Optional<TextureAttachment>> depthAttachment =
        attachment(framebuffer, GL_DEPTH_ATTACHMENT);
    StateValue<Optional<TextureAttachment>> stencilAttachment =
        attachment(framebuffer, GL_STENCIL_ATTACHMENT);
    DepthState depth = new DepthState(depthTestEnabled, depthFunction,
        depthWriteEnabled);
    StencilState stencil = new StencilState(stencilTestEnabled,
        stencilFront.snapshot(), stencilBack.snapshot());
    RasterState raster = new RasterState(cullEnabled, cullMode, frontFace,
        depthClampEnabled, rasterizerDiscardEnabled, polygonModeFront,
        polygonModeBack, polygonOffsetPointEnabled,
        polygonOffsetLineEnabled, polygonOffsetFillEnabled,
        polygonOffsetFactor, polygonOffsetUnits, polygonOffsetClamp);
    MultisampleState multisample = new MultisampleState(
        sampleCount(targets, depthAttachment, stencilAttachment),
        sampleCoverageEnabled, sampleCoverageValue, sampleCoverageInvert,
        sampleMaskEnabled, sampleMaskWord0, sampleMaskWord1,
        alphaToCoverageEnabled, alphaToOneEnabled);
    PrimitiveState primitive = new PrimitiveState(primitiveMode,
        primitiveRestartEnabled, fixedIndexRestartEnabled);

    List<String> unknowns = requiredUnknowns(operation, targets,
        depthAttachment, stencilAttachment, depth, stencil, raster,
        multisample, primitive);
    return new IrisGlStateSnapshot(snapshotSequence, operation,
        currentProgram, currentDrawFramebuffer, buffers, targets,
        depthAttachment, stencilAttachment, depth, stencil, raster,
        multisample, primitive, unknowns, resourceEvictions);
  }

  private List<String> requiredUnknowns(Operation operation,
      List<ColorTarget> targets,
      StateValue<Optional<TextureAttachment>> depthAttachment,
      StateValue<Optional<TextureAttachment>> stencilAttachment,
      DepthState depth, StencilState stencil, RasterState raster,
      MultisampleState multisample, PrimitiveState primitive) {
    LinkedHashSet<String> unknowns = new LinkedHashSet<>();
    requireProgram(unknowns);
    if (operation == Operation.DISPATCH) {
      return List.copyOf(unknowns);
    }

    addUnknown(unknowns, "drawFramebuffer", currentDrawFramebuffer);
    for (ColorTarget target : targets) {
      addUnknown(unknowns, "colorTargets[" + target.index()
          + "].drawBuffer", target.drawBuffer());
      if (!target.drawBuffer().isKnown()
          || target.drawBuffer().value() == GL_NONE) {
        continue;
      }
      String prefix = "colorTargets[" + target.index() + "]";
      addUnknown(unknowns, prefix + ".attachment", target.attachment());
      addUnknown(unknowns, prefix + ".blend.enabled",
          target.blend().enabled());
      if (knownTrue(target.blend().enabled())) {
        addUnknown(unknowns, prefix + ".blend.rgbEquation",
            target.blend().rgbEquation());
        addUnknown(unknowns, prefix + ".blend.alphaEquation",
            target.blend().alphaEquation());
        addUnknown(unknowns, prefix + ".blend.sourceRgb",
            target.blend().sourceRgb());
        addUnknown(unknowns, prefix + ".blend.destinationRgb",
            target.blend().destinationRgb());
        addUnknown(unknowns, prefix + ".blend.sourceAlpha",
            target.blend().sourceAlpha());
        addUnknown(unknowns, prefix + ".blend.destinationAlpha",
            target.blend().destinationAlpha());
      }
      addUnknown(unknowns, prefix + ".colorMask", target.colorMask());
    }

    addUnknown(unknowns, "depthAttachment", depthAttachment);
    addUnknown(unknowns, "stencilAttachment", stencilAttachment);
    addUnknown(unknowns, "depth.testEnabled", depth.testEnabled());
    if (knownTrue(depth.testEnabled())) {
      addUnknown(unknowns, "depth.function", depth.function());
    }
    addUnknown(unknowns, "depth.writeEnabled", depth.writeEnabled());

    addUnknown(unknowns, "stencil.testEnabled", stencil.testEnabled());
    if (knownTrue(stencil.testEnabled())) {
      addStencilUnknowns(unknowns, "stencil.front", stencil.front());
      addStencilUnknowns(unknowns, "stencil.back", stencil.back());
    }

    addUnknown(unknowns, "raster.cullEnabled", raster.cullEnabled());
    if (knownTrue(raster.cullEnabled())) {
      addUnknown(unknowns, "raster.cullMode", raster.cullMode());
    }
    addUnknown(unknowns, "raster.frontFace", raster.frontFace());
    addUnknown(unknowns, "raster.depthClampEnabled",
        raster.depthClampEnabled());
    addUnknown(unknowns, "raster.rasterizerDiscardEnabled",
        raster.rasterizerDiscardEnabled());
    addUnknown(unknowns, "raster.polygonModeFront",
        raster.polygonModeFront());
    addUnknown(unknowns, "raster.polygonModeBack",
        raster.polygonModeBack());
    addUnknown(unknowns, "raster.polygonOffsetPointEnabled",
        raster.polygonOffsetPointEnabled());
    addUnknown(unknowns, "raster.polygonOffsetLineEnabled",
        raster.polygonOffsetLineEnabled());
    addUnknown(unknowns, "raster.polygonOffsetFillEnabled",
        raster.polygonOffsetFillEnabled());
    if (knownTrue(raster.polygonOffsetPointEnabled())
        || knownTrue(raster.polygonOffsetLineEnabled())
        || knownTrue(raster.polygonOffsetFillEnabled())) {
      addUnknown(unknowns, "raster.polygonOffsetFactor",
          raster.polygonOffsetFactor());
      addUnknown(unknowns, "raster.polygonOffsetUnits",
          raster.polygonOffsetUnits());
      addUnknown(unknowns, "raster.polygonOffsetClamp",
          raster.polygonOffsetClamp());
    }

    addUnknown(unknowns, "multisample.rasterSampleCount",
        multisample.rasterSampleCount());
    addUnknown(unknowns, "multisample.sampleCoverageEnabled",
        multisample.sampleCoverageEnabled());
    if (knownTrue(multisample.sampleCoverageEnabled())) {
      addUnknown(unknowns, "multisample.sampleCoverageValue",
          multisample.sampleCoverageValue());
      addUnknown(unknowns, "multisample.sampleCoverageInvert",
          multisample.sampleCoverageInvert());
    }
    addUnknown(unknowns, "multisample.sampleMaskEnabled",
        multisample.sampleMaskEnabled());
    if (knownTrue(multisample.sampleMaskEnabled())) {
      addUnknown(unknowns, "multisample.sampleMaskWord0",
          multisample.sampleMaskWord0());
      if (multisample.rasterSampleCount().isKnown()
          && multisample.rasterSampleCount().value() > 32) {
        addUnknown(unknowns, "multisample.sampleMaskWord1",
            multisample.sampleMaskWord1());
      }
    }
    addUnknown(unknowns, "multisample.alphaToCoverageEnabled",
        multisample.alphaToCoverageEnabled());
    addUnknown(unknowns, "multisample.alphaToOneEnabled",
        multisample.alphaToOneEnabled());
    addUnknown(unknowns, "primitive.mode", primitive.mode());
    addUnknown(unknowns, "primitive.restartEnabled",
        primitive.restartEnabled());
    addUnknown(unknowns, "primitive.fixedIndexRestartEnabled",
        primitive.fixedIndexRestartEnabled());
    return List.copyOf(unknowns);
  }

  private void requireProgram(LinkedHashSet<String> unknowns) {
    addUnknown(unknowns, "program", currentProgram);
    if (currentProgram.isKnown() && currentProgram.value().isEmpty()) {
      unknowns.add("program: no program is bound");
    }
  }

  private static void addStencilUnknowns(LinkedHashSet<String> unknowns,
                                         String prefix, StencilFace face) {
    addUnknown(unknowns, prefix + ".function", face.function());
    addUnknown(unknowns, prefix + ".reference", face.reference());
    addUnknown(unknowns, prefix + ".readMask", face.readMask());
    addUnknown(unknowns, prefix + ".stencilFail", face.stencilFail());
    addUnknown(unknowns, prefix + ".depthFail", face.depthFail());
    addUnknown(unknowns, prefix + ".depthPass", face.depthPass());
    addUnknown(unknowns, prefix + ".writeMask", face.writeMask());
  }

  private static void addUnknown(LinkedHashSet<String> unknowns, String path,
                                 StateValue<?> value) {
    if (!value.isKnown()) {
      unknowns.add(path + ": " + value.unknownReason());
    }
  }

  private static boolean knownTrue(StateValue<Boolean> value) {
    return value.isKnown() && value.value();
  }

  private StateValue<Integer> sampleCount(List<ColorTarget> targets,
      StateValue<Optional<TextureAttachment>> depth,
      StateValue<Optional<TextureAttachment>> stencil) {
    ArrayList<StateValue<Integer>> counts = new ArrayList<>();
    for (ColorTarget target : targets) {
      if (!target.drawBuffer().isKnown()) {
        return unknown("draw buffer " + target.index() + " is unknown: "
            + target.drawBuffer().unknownReason());
      }
      if (target.drawBuffer().value() != GL_NONE) {
        if (!target.attachment().isKnown()) {
          return unknown("color target " + target.index()
              + " attachment is unknown: "
              + target.attachment().unknownReason());
        }
        if (target.attachment().value().isPresent()) {
          counts.add(target.attachment().value().get().sampleCount());
        }
      }
    }
    if (!depth.isKnown()) {
      return unknown("depth attachment is unknown: " + depth.unknownReason());
    }
    if (!stencil.isKnown()) {
      return unknown(
          "stencil attachment is unknown: " + stencil.unknownReason());
    }
    addAttachmentSampleCount(counts, depth);
    addAttachmentSampleCount(counts, stencil);
    if (counts.isEmpty()) {
      return unknown("no attached texture supplies a sample count");
    }
    Integer expected = null;
    for (StateValue<Integer> count : counts) {
      if (!count.isKnown()) {
        return unknown(count.unknownReason());
      }
      if (expected == null) {
        expected = count.value();
      } else if (!expected.equals(count.value())) {
        return unknown("framebuffer attachments have inconsistent sample "
            + "counts " + expected + " and " + count.value());
      }
    }
    return known(expected);
  }

  private static void addAttachmentSampleCount(
      List<StateValue<Integer>> counts,
      StateValue<Optional<TextureAttachment>> attachment) {
    if (attachment.isKnown() && attachment.value().isPresent()) {
      counts.add(attachment.value().get().sampleCount());
    }
  }

  private BlendState blend(int index) {
    BlendOverride override = indexedBlend[index];
    return new BlendState(
        override.enabled == null ? globalBlendEnabled
            : known(override.enabled),
        override.rgbEquation == null ? globalBlendRgbEquation
            : known(override.rgbEquation),
        override.alphaEquation == null ? globalBlendAlphaEquation
            : known(override.alphaEquation),
        override.sourceRgb == null ? globalBlendSourceRgb
            : known(override.sourceRgb),
        override.destinationRgb == null ? globalBlendDestinationRgb
            : known(override.destinationRgb),
        override.sourceAlpha == null ? globalBlendSourceAlpha
            : known(override.sourceAlpha),
        override.destinationAlpha == null ? globalBlendDestinationAlpha
            : known(override.destinationAlpha));
  }

  private static StateValue<Integer> drawBuffer(
      StateValue<List<Integer>> buffers, int index) {
    if (!buffers.isKnown()) {
      return unknown(buffers.unknownReason());
    }
    return known(index < buffers.value().size()
        ? buffers.value().get(index) : GL_NONE);
  }

  private static StateValue<Optional<TextureAttachment>> colorAttachment(
      FramebufferEntry framebuffer, StateValue<Integer> drawBuffer) {
    if (!drawBuffer.isKnown()) {
      return unknown(drawBuffer.unknownReason());
    }
    int attachment = drawBuffer.value();
    if (attachment == GL_NONE) {
      return known(Optional.empty());
    }
    if (attachment < GL_COLOR_ATTACHMENT0
        || attachment >= GL_COLOR_ATTACHMENT0 + MAX_COLOR_ATTACHMENTS) {
      return unknown("draw buffer 0x" + Integer.toHexString(attachment)
          + " is not a tracked color texture attachment");
    }
    return attachment(framebuffer, attachment);
  }

  private static StateValue<Optional<TextureAttachment>> attachment(
      FramebufferEntry framebuffer, int attachment) {
    if (framebuffer == null) {
      return unknown("draw framebuffer state is unavailable");
    }
    StateValue<Optional<TextureAttachment>> value =
        framebuffer.attachments.get(attachment);
    if (value != null) {
      return value;
    }
    return framebuffer.defaultFramebuffer
        ? unknown("default framebuffer attachment metadata was not observed")
        : known(Optional.empty());
  }

  private FramebufferEntry snapshotFramebuffer() {
    if (!currentDrawFramebuffer.isKnown()) {
      return null;
    }
    return framebuffer(currentDrawFramebuffer.value());
  }

  private FramebufferEntry boundFramebuffer() {
    return snapshotFramebuffer();
  }

  private FramebufferEntry framebuffer(ResourceHandle handle) {
    if (handle.contextGeneration() != contextGeneration
        || handle.kind() != ResourceKind.FRAMEBUFFER) {
      return null;
    }
    if (handle.name() == 0) {
      return handle.equals(defaultFramebuffer.handle)
          ? defaultFramebuffer : null;
    }
    FramebufferEntry entry = framebuffers.get(handle.name());
    return entry != null && handle.equals(entry.handle) ? entry : null;
  }

  private TextureEntry texture(ResourceHandle handle) {
    if (handle.contextGeneration() != contextGeneration
        || handle.kind() != ResourceKind.TEXTURE) {
      return null;
    }
    TextureEntry entry = textures.get(handle.name());
    return entry != null && handle.equals(entry.handle) ? entry : null;
  }

  private FramebufferEntry framebufferByName(int name) {
    if (name == 0) {
      return defaultFramebuffer;
    }
    return framebuffers.get(name);
  }

  private void refreshTextureAttachments(TextureEntry texture) {
    refreshTextureAttachments(defaultFramebuffer, texture);
    for (FramebufferEntry framebuffer : framebuffers.values()) {
      refreshTextureAttachments(framebuffer, texture);
    }
  }

  private static void refreshTextureAttachments(FramebufferEntry framebuffer,
                                                TextureEntry texture) {
    for (Map.Entry<Integer, StateValue<Optional<TextureAttachment>>> entry
        : framebuffer.attachments.entrySet()) {
      StateValue<Optional<TextureAttachment>> state = entry.getValue();
      if (!state.isKnown() || state.value().isEmpty()) {
        continue;
      }
      TextureAttachment attachment = state.value().get();
      if (attachment.texture().equals(texture.handle)) {
        entry.setValue(known(Optional.of(texture.attachment(
            attachment.textureTarget(), attachment.mipLevel()))));
      }
    }
  }

  private static List<Integer> intList(int[] values) {
    ArrayList<Integer> result = new ArrayList<>(values.length);
    for (int value : values) {
      result.add(value);
    }
    return List.copyOf(result);
  }

  private void setGlobalBlendEnabled(boolean enabled) {
    globalBlendEnabled = known(enabled);
    for (BlendOverride override : indexedBlend) {
      override.enabled = null;
    }
  }

  private void forStencilFaces(int face,
      java.util.function.Consumer<MutableStencilFace> mutation) {
    Objects.requireNonNull(mutation, "mutation");
    switch (face) {
      case GL_FRONT -> mutation.accept(stencilFront);
      case GL_BACK -> mutation.accept(stencilBack);
      case GL_FRONT_AND_BACK -> {
        mutation.accept(stencilFront);
        mutation.accept(stencilBack);
      }
      default -> throw new IllegalArgumentException(
          "unsupported stencil face 0x" + Integer.toHexString(face));
    }
  }

  private static void setAttachment(FramebufferEntry framebuffer,
      int attachment, StateValue<Optional<TextureAttachment>> value) {
    Objects.requireNonNull(value, "attachment value");
    if (attachment == GL_DEPTH_STENCIL_ATTACHMENT) {
      framebuffer.attachments.put(GL_DEPTH_ATTACHMENT, value);
      framebuffer.attachments.put(GL_STENCIL_ATTACHMENT, value);
      return;
    }
    boolean color = attachment >= GL_COLOR_ATTACHMENT0
        && attachment < GL_COLOR_ATTACHMENT0 + MAX_COLOR_ATTACHMENTS;
    if (!color && attachment != GL_DEPTH_ATTACHMENT
        && attachment != GL_STENCIL_ATTACHMENT) {
      throw new IllegalArgumentException(
          "unsupported framebuffer attachment 0x"
              + Integer.toHexString(attachment));
    }
    framebuffer.attachments.put(attachment, value);
  }

  private void initializeUnknownState(String suffix) {
    defaultFramebuffer = FramebufferEntry.defaultFramebuffer(
        new ResourceHandle(ResourceKind.FRAMEBUFFER, 0, 0,
            contextGeneration));
    currentProgram = unknown("current program " + suffix);
    currentDrawFramebuffer = unknown("draw framebuffer binding " + suffix);
    globalBlendEnabled = unknown("global blend enable " + suffix);
    globalBlendRgbEquation = unknown("global RGB blend equation " + suffix);
    globalBlendAlphaEquation = unknown(
        "global alpha blend equation " + suffix);
    globalBlendSourceRgb = unknown("global source RGB blend factor " + suffix);
    globalBlendDestinationRgb = unknown(
        "global destination RGB blend factor " + suffix);
    globalBlendSourceAlpha = unknown(
        "global source alpha blend factor " + suffix);
    globalBlendDestinationAlpha = unknown(
        "global destination alpha blend factor " + suffix);
    globalColorMask = unknown("global color mask " + suffix);
    for (int index = 0; index < MAX_COLOR_ATTACHMENTS; index++) {
      indexedBlend[index] = new BlendOverride();
      indexedColorMask[index] = null;
    }
    depthTestEnabled = unknown("depth test enable " + suffix);
    depthFunction = unknown("depth function " + suffix);
    depthWriteEnabled = unknown("depth write mask " + suffix);
    stencilTestEnabled = unknown("stencil test enable " + suffix);
    stencilFront = MutableStencilFace.unknown("front stencil state " + suffix);
    stencilBack = MutableStencilFace.unknown("back stencil state " + suffix);
    cullEnabled = unknown("cull enable " + suffix);
    cullMode = unknown("cull mode " + suffix);
    frontFace = unknown("front-face winding " + suffix);
    depthClampEnabled = unknown("depth clamp enable " + suffix);
    rasterizerDiscardEnabled = unknown(
        "rasterizer discard enable " + suffix);
    polygonModeFront = unknown("front polygon mode " + suffix);
    polygonModeBack = unknown("back polygon mode " + suffix);
    polygonOffsetPointEnabled = unknown(
        "point polygon offset enable " + suffix);
    polygonOffsetLineEnabled = unknown(
        "line polygon offset enable " + suffix);
    polygonOffsetFillEnabled = unknown(
        "fill polygon offset enable " + suffix);
    polygonOffsetFactor = unknown("polygon offset factor " + suffix);
    polygonOffsetUnits = unknown("polygon offset units " + suffix);
    polygonOffsetClamp = unknown("polygon offset clamp " + suffix);
    sampleCoverageEnabled = unknown("sample coverage enable " + suffix);
    sampleCoverageValue = unknown("sample coverage value " + suffix);
    sampleCoverageInvert = unknown("sample coverage invert " + suffix);
    sampleMaskEnabled = unknown("sample mask enable " + suffix);
    sampleMaskWord0 = unknown("sample mask word 0 " + suffix);
    sampleMaskWord1 = unknown("sample mask word 1 " + suffix);
    alphaToCoverageEnabled = unknown(
        "sample alpha-to-coverage enable " + suffix);
    alphaToOneEnabled = unknown("sample alpha-to-one enable " + suffix);
    primitiveRestartEnabled = unknown("primitive restart enable " + suffix);
    fixedIndexRestartEnabled = unknown(
        "fixed-index primitive restart enable " + suffix);
  }

  private ResourceHandle newHandle(ResourceKind kind, int name) {
    if (nextResourceGeneration == Long.MAX_VALUE) {
      throw new IllegalStateException("GL resource generation exhausted");
    }
    return new ResourceHandle(kind, name, nextResourceGeneration++,
        contextGeneration);
  }

  private <T> void putBounded(LinkedHashMap<Integer, T> registry, int bound,
                              int name, T value) {
    if (!registry.containsKey(name) && registry.size() >= bound) {
      Iterator<Map.Entry<Integer, T>> iterator =
          registry.entrySet().iterator();
      iterator.next();
      iterator.remove();
      resourceEvictions++;
    }
    registry.put(name, value);
  }

  private static void requirePositiveName(int name, String label) {
    if (name <= 0) {
      throw new IllegalArgumentException(label + " name must be positive");
    }
  }

  private static void requireFormat(String format) {
    Objects.requireNonNull(format, "format");
    if (format.isBlank() || format.length() > 128) {
      throw new IllegalArgumentException("invalid texture format");
    }
  }

  private static void requireKind(ResourceHandle handle, ResourceKind kind) {
    Objects.requireNonNull(handle, "handle");
    if (handle.kind() != kind) {
      throw new IllegalArgumentException(
          "expected " + kind + " handle, got " + handle.kind());
    }
  }

  private static void requireColorIndex(int index) {
    if (index < 0 || index >= MAX_COLOR_ATTACHMENTS) {
      throw new IllegalArgumentException(
          "color attachment index must be in 0.."
              + (MAX_COLOR_ATTACHMENTS - 1));
    }
  }

  private static void requireDrawFramebufferTarget(int target) {
    if (target != GL_FRAMEBUFFER && target != GL_DRAW_FRAMEBUFFER) {
      throw new IllegalArgumentException(
          "unsupported framebuffer target 0x"
              + Integer.toHexString(target));
    }
  }

  private static float finite(float value, String label) {
    if (!Float.isFinite(value)) {
      throw new IllegalArgumentException(label + " must be finite");
    }
    return normalizeZero(value);
  }

  private static float normalizeZero(float value) {
    return value == 0.0F ? 0.0F : value;
  }

  private static <T> StateValue<T> known(T value) {
    return StateValue.known(value);
  }

  private static <T> StateValue<T> unknown(String reason) {
    return StateValue.unknown(reason);
  }

  private static final class BlendOverride {
    private Boolean enabled;
    private Integer rgbEquation;
    private Integer alphaEquation;
    private Integer sourceRgb;
    private Integer destinationRgb;
    private Integer sourceAlpha;
    private Integer destinationAlpha;
  }

  private static final class MutableStencilFace {
    private StateValue<Integer> function;
    private StateValue<Integer> reference;
    private StateValue<Integer> readMask;
    private StateValue<Integer> stencilFail;
    private StateValue<Integer> depthFail;
    private StateValue<Integer> depthPass;
    private StateValue<Integer> writeMask;

    private static MutableStencilFace unknown(String reason) {
      MutableStencilFace face = new MutableStencilFace();
      face.function = IrisGlStateTracker.unknown(reason);
      face.reference = IrisGlStateTracker.unknown(reason);
      face.readMask = IrisGlStateTracker.unknown(reason);
      face.stencilFail = IrisGlStateTracker.unknown(reason);
      face.depthFail = IrisGlStateTracker.unknown(reason);
      face.depthPass = IrisGlStateTracker.unknown(reason);
      face.writeMask = IrisGlStateTracker.unknown(reason);
      return face;
    }

    private StencilFace snapshot() {
      return new StencilFace(function, reference, readMask, stencilFail,
          depthFail, depthPass, writeMask);
    }
  }

  private static final class TextureEntry {
    private final ResourceHandle handle;
    private String format;
    private StateValue<Integer> sampleCount;

    private TextureEntry(ResourceHandle handle, String format,
                         StateValue<Integer> sampleCount) {
      this.handle = handle;
      this.format = format;
      this.sampleCount = sampleCount;
    }

    private TextureAttachment attachment(int textureTarget, int mipLevel) {
      return new TextureAttachment(handle, format, sampleCount, textureTarget,
          mipLevel);
    }
  }

  private static final class FramebufferEntry {
    private final ResourceHandle handle;
    private final boolean defaultFramebuffer;
    private StateValue<List<Integer>> drawBuffers;
    private final Map<Integer, StateValue<Optional<TextureAttachment>>>
        attachments = new LinkedHashMap<>();

    private FramebufferEntry(ResourceHandle handle,
                             boolean defaultFramebuffer) {
      this.handle = handle;
      this.defaultFramebuffer = defaultFramebuffer;
      // OpenGL initializes a newly-created non-default framebuffer's draw
      // buffer to COLOR_ATTACHMENT0. The window-system framebuffer is the
      // exception: its initial buffer is platform/context dependent and is
      // seeded separately by initializeOpenGlDefaults().
      this.drawBuffers = defaultFramebuffer
          ? unknown("default framebuffer draw buffers were not observed")
          : known(List.of(GL_COLOR_ATTACHMENT0));
    }

    private static FramebufferEntry custom(ResourceHandle handle) {
      return new FramebufferEntry(handle, false);
    }

    private static FramebufferEntry defaultFramebuffer(
        ResourceHandle handle) {
      return new FramebufferEntry(handle, true);
    }

    private void resetCustom() {
      attachments.clear();
      drawBuffers = unknown("framebuffer draw buffers were reset");
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Strict bounded ABI for one batched MTL4 graph command buffer. */
public final class IrisMetalGraphFramePacketEncoder {
  public static final int MAGIC = 0x4d474639; // MGF9
  public static final int SCHEMA = 5;
  public static final int MAX_PACKET_BYTES =
      IrisMetalShadowReplayPacketEncoder.MAX_PACKET_BYTES;
  public static final int NO_READBACK = -1;
  public static final int NO_PRESENTATION = -1;
  private static final int MINIMUM_DIRECT_CAPACITY = 16 * 1024;
  private static final int MAXIMUM_POOLED_DIRECT_PACKETS = 4;
  private static final long MAXIMUM_POOLED_DIRECT_BYTES = 32L * 1024L
      * 1024L;
  private static final Object DIRECT_POOL_LOCK = new Object();
  private static final ArrayList<ByteBuffer> DIRECT_POOL = new ArrayList<>();
  private static long pooledDirectBytes;

  private IrisMetalGraphFramePacketEncoder() {
  }

  public static byte[] encode(Frame frame) {
    Objects.requireNonNull(frame, "frame");
    int encodedBytes = encodedSize(frame);
    ByteBuffer output = ByteBuffer.allocate(encodedBytes)
        .order(ByteOrder.BIG_ENDIAN);
    putFrame(output, frame);
    requireCompletePacket(output, encodedBytes);
    return output.array();
  }

  /**
   * Encodes into a bounded reusable off-heap buffer for the production JNI
   * submission path. The returned packet must be closed after native submit.
   */
  public static DirectPacket encodeDirect(Frame frame) {
    Objects.requireNonNull(frame, "frame");
    int encodedBytes = encodedSize(frame);
    ByteBuffer output = acquireDirect(encodedBytes);
    try {
      output.clear();
      output.order(ByteOrder.BIG_ENDIAN);
      output.limit(encodedBytes);
      putFrame(output, frame);
      requireCompletePacket(output, encodedBytes);
      output.flip();
      return new DirectPacket(output, encodedBytes);
    } catch (RuntimeException failure) {
      recycleDirect(output);
      throw failure;
    }
  }

  private static void putFrame(ByteBuffer out, Frame frame) {
    out.putInt(MAGIC);
    out.putInt(SCHEMA);
    out.putLong(frame.contextGeneration());
    out.putInt(frame.readbackResourceId());
    out.putInt(frame.presentationResourceId());
    out.putInt(frame.resources().size());
    for (Resource resource : frame.resources()) {
      out.putInt(resource.resourceId());
      out.putLong(resource.token());
    }
    out.putInt(frame.inputBuffers().size());
    for (InputBuffer input : frame.inputBuffers()) {
      IrisShadowReplayBufferSnapshot.BufferImage image = input.image();
      out.putInt(image.shared() ? 2 : 1);
      out.putInt(image.byteLength());
      if (image.shared()) {
        out.putLong(image.sharedHandle());
      } else {
        out.put(image.ownedBytes());
      }
    }
    out.putInt(frame.inputTextures().size());
    for (InputTexture input : frame.inputTextures()) {
      IrisGlTextureMirror.TextureSnapshot image = input.image();
      out.putInt(image.texture());
      putString(out, image.format());
      out.putInt(image.width());
      out.putInt(image.height());
      out.putInt(image.layer());
      out.putInt(image.mipLevel());
      out.putInt(image.bytesPerPixel());
      out.putInt(1); // immutable inline frame texture
      out.putInt(image.byteLength());
      out.put(image.ownedBytes());
    }
    out.putInt(frame.operations().size());
    for (Operation operation : frame.operations()) {
      putOperation(out, operation);
    }
  }

  private static void putOperation(ByteBuffer out, Operation operation) {
    if (operation instanceof Clear clear) {
      out.putInt(1);
      out.putInt(clear.resourceId());
      out.putInt(clear.mipLevel());
      out.putInt(clear.aspect().ordinal());
      out.putInt(clear.valueKind().ordinal());
      out.putInt(clear.rawValues().size());
      for (Long value : clear.rawValues()) {
        out.putLong(value);
      }
      out.put((byte) (clear.region().isPresent() ? 1 : 0));
      if (clear.region().isPresent()) {
        IrisClearCommand.Rect region = clear.region().orElseThrow();
        out.putInt(region.x());
        out.putInt(region.y());
        out.putInt(region.width());
        out.putInt(region.height());
      }
      return;
    }
    if (operation instanceof Barrier barrier) {
      out.putInt(2);
      out.putInt(barrier.glBarrierBits());
      return;
    }
    if (operation instanceof CopyTexture copy) {
      out.putInt(3);
      out.putInt(copy.sourceResourceId());
      out.putInt(copy.destinationResourceId());
      out.putInt(copy.sourceLevel());
      out.putInt(copy.destinationLevel());
      out.putInt(copy.sourceX());
      out.putInt(copy.sourceY());
      out.putInt(copy.destinationX());
      out.putInt(copy.destinationY());
      out.putInt(copy.width());
      out.putInt(copy.height());
      return;
    }
    if (operation instanceof GenerateMipmaps mipmaps) {
      out.putInt(4);
      out.putInt(mipmaps.resourceId());
      return;
    }
    if (operation instanceof Compute compute) {
      out.putInt(6);
      putString(out, compute.pipelineKeySha256());
      out.putInt(compute.replayPacket().length);
      out.put(compute.replayPacket);
      out.putInt(compute.groupsX());
      out.putInt(compute.groupsY());
      out.putInt(compute.groupsZ());
      out.putInt(compute.resources().size());
      for (Integer resourceId : compute.resources()) {
        out.putInt(resourceId);
      }
      return;
    }
    if (operation instanceof Draw draw) {
      out.putInt(5);
      putString(out, draw.pipelineKeySha256());
      // This encoder owns the immutable frame. Direct field access avoids an
      // additional defensive clone of a potentially large nested packet.
      byte[] replayPacket = draw.replayPacket;
      out.putInt(replayPacket.length);
      out.put(replayPacket);
      out.putInt(draw.colorTargets().size());
      for (ColorTarget target : draw.colorTargets()) {
        out.putInt(target.slot());
        out.putInt(target.resourceId());
        out.putInt(target.mipLevel());
      }
      out.putInt(draw.depthResourceId());
      out.putInt(draw.depthMipLevel());
      out.putInt(draw.stencilResourceId());
      out.putInt(draw.stencilMipLevel());
      out.putInt(draw.textureOverrides().size());
      for (Map.Entry<Integer, Integer> entry
          : draw.textureOverrides().entrySet()) {
        out.putInt(entry.getKey());
        out.putInt(entry.getValue());
      }
      return;
    }
    throw new IllegalArgumentException("unknown Metal graph operation");
  }

  private static void putString(ByteBuffer out, String value) {
    int length = asciiLength(value);
    out.putInt(length);
    for (int index = 0; index < length; index++) {
      out.put((byte) value.charAt(index));
    }
  }

  private static int encodedSize(Frame frame) {
    long size = 28L + frame.resources().size() * 12L + 4L;
    for (InputBuffer input : frame.inputBuffers()) {
      IrisShadowReplayBufferSnapshot.BufferImage image = input.image();
      size = addSize(size, 8L + (image.shared() ? 8L
          : image.byteLength()));
    }
    size = addSize(size, 4L);
    for (InputTexture input : frame.inputTextures()) {
      IrisGlTextureMirror.TextureSnapshot image = input.image();
      size = addSize(size, 36L + asciiLength(image.format())
          + image.byteLength());
    }
    size = addSize(size, 4L);
    for (Operation operation : frame.operations()) {
      if (operation instanceof Clear clear) {
        size = addSize(size, 25L + clear.rawValues().size() * 8L
            + (clear.region().isPresent() ? 16L : 0L));
      } else if (operation instanceof Barrier
          || operation instanceof GenerateMipmaps) {
        size = addSize(size, 8L);
      } else if (operation instanceof CopyTexture) {
        size = addSize(size, 44L);
      } else if (operation instanceof Draw draw) {
        size = addSize(size, 28L + asciiLength(
            draw.pipelineKeySha256()) + draw.replayPacket.length
            + draw.colorTargets().size() * 12L
            + 8L + 8L
            + draw.textureOverrides().size() * 8L);
      } else if (operation instanceof Compute compute) {
        size = addSize(size, 28L + asciiLength(
            compute.pipelineKeySha256()) + compute.replayPacket.length
            + compute.resources().size() * 4L);
      } else {
        throw new IllegalArgumentException("unknown Metal graph operation");
      }
    }
    if (size <= 0 || size > MAX_PACKET_BYTES) {
      throw new IllegalArgumentException("Metal graph frame packet too large");
    }
    return Math.toIntExact(size);
  }

  private static long addSize(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Metal graph frame packet too large",
          overflow);
    }
  }

  private static int asciiLength(String value) {
    Objects.requireNonNull(value, "value");
    if (value.isEmpty() || value.length() > 128) {
      throw new IllegalArgumentException("invalid graph packet string");
    }
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) > 0x7f) {
        throw new IllegalArgumentException("invalid graph packet string");
      }
    }
    return value.length();
  }

  private static void requireCompletePacket(ByteBuffer output,
      int encodedBytes) {
    if (output.position() != encodedBytes) {
      throw new IllegalStateException("Metal graph frame packet size drift");
    }
  }

  private static ByteBuffer acquireDirect(int requiredBytes) {
    synchronized (DIRECT_POOL_LOCK) {
      int best = -1;
      int bestCapacity = Integer.MAX_VALUE;
      for (int index = 0; index < DIRECT_POOL.size(); index++) {
        int capacity = DIRECT_POOL.get(index).capacity();
        if (capacity >= requiredBytes && capacity < bestCapacity) {
          best = index;
          bestCapacity = capacity;
        }
      }
      if (best >= 0) {
        ByteBuffer buffer = DIRECT_POOL.remove(best);
        pooledDirectBytes -= buffer.capacity();
        return buffer;
      }
    }
    return ByteBuffer.allocateDirect(directCapacity(requiredBytes));
  }

  private static int directCapacity(int requiredBytes) {
    int capacity = MINIMUM_DIRECT_CAPACITY;
    while (capacity < requiredBytes && capacity <= MAX_PACKET_BYTES / 2) {
      capacity <<= 1;
    }
    return capacity >= requiredBytes ? capacity : requiredBytes;
  }

  private static void recycleDirect(ByteBuffer buffer) {
    if (buffer == null || !buffer.isDirect()
        || buffer.capacity() > MAXIMUM_POOLED_DIRECT_BYTES) {
      return;
    }
    synchronized (DIRECT_POOL_LOCK) {
      if (DIRECT_POOL.size() >= MAXIMUM_POOLED_DIRECT_PACKETS
          || pooledDirectBytes + buffer.capacity()
              > MAXIMUM_POOLED_DIRECT_BYTES) {
        return;
      }
      buffer.clear();
      DIRECT_POOL.add(buffer);
      pooledDirectBytes += buffer.capacity();
    }
  }

  /** Ownership wrapper preventing a pooled packet from being recycled early. */
  public static final class DirectPacket implements AutoCloseable {
    private ByteBuffer buffer;
    private final int length;

    private DirectPacket(ByteBuffer buffer, int length) {
      this.buffer = Objects.requireNonNull(buffer, "buffer");
      this.length = length;
    }

    ByteBuffer nativeBuffer() {
      if (buffer == null) {
        throw new IllegalStateException("Metal graph packet is closed");
      }
      return buffer;
    }

    public int length() {
      return length;
    }

    byte[] copyBytes() {
      ByteBuffer source = nativeBuffer().duplicate();
      source.position(0);
      source.limit(length);
      byte[] result = new byte[length];
      source.get(result);
      return result;
    }

    @Override
    public void close() {
      ByteBuffer released = buffer;
      buffer = null;
      recycleDirect(released);
    }
  }

  public record Frame(long contextGeneration, List<Resource> resources,
                      List<InputBuffer> inputBuffers,
                      List<InputTexture> inputTextures,
                      List<Operation> operations,
                      int readbackResourceId,
                      int presentationResourceId) {
    public Frame(long contextGeneration, List<Resource> resources,
                 List<InputBuffer> inputBuffers,
                 List<Operation> operations, int readbackResourceId,
                 int presentationResourceId) {
      this(contextGeneration, resources, inputBuffers, List.of(), operations,
          readbackResourceId, presentationResourceId);
    }

    public Frame(long contextGeneration, List<Resource> resources,
                 List<Operation> operations, int readbackResourceId,
                 int presentationResourceId) {
      this(contextGeneration, resources, List.of(), List.of(), operations,
          readbackResourceId, presentationResourceId);
    }

    /** Compatibility constructor for validation and graph-only callers. */
    public Frame(long contextGeneration, List<Resource> resources,
                 List<Operation> operations, int readbackResourceId) {
      this(contextGeneration, resources, List.of(), List.of(), operations,
          readbackResourceId, NO_PRESENTATION);
    }

    public Frame asPresentation(int resourceId) {
      return new Frame(contextGeneration, resources, inputBuffers,
          inputTextures,
          operations, NO_READBACK, resourceId);
    }

    public Frame {
      if (contextGeneration <= 0) {
        throw new IllegalArgumentException("invalid graph frame generation");
      }
      resources = sortedResources(resources);
      inputBuffers = sortedInputBuffers(inputBuffers);
      inputTextures = sortedInputTextures(inputTextures);
      operations = List.copyOf(operations);
      if (operations.isEmpty()
          || operations.size() > IrisRenderGraph.MAX_NODES * 8L) {
        throw new IllegalArgumentException("invalid graph operation count");
      }
      HashSet<Integer> ids = new HashSet<>();
      for (Resource resource : resources) {
        ids.add(resource.resourceId());
      }
      if (readbackResourceId != NO_READBACK
          && !ids.contains(readbackResourceId)) {
        throw new IllegalArgumentException("readback resource is absent");
      }
      if (presentationResourceId != NO_PRESENTATION
          && !ids.contains(presentationResourceId)) {
        throw new IllegalArgumentException("presentation resource is absent");
      }
      if (readbackResourceId != NO_READBACK
          && presentationResourceId != NO_PRESENTATION) {
        throw new IllegalArgumentException(
            "readback and presentation are mutually exclusive");
      }
      for (Operation operation : operations) {
        Objects.requireNonNull(operation, "operation");
        for (Integer resourceId : operation.resourceIds()) {
          if (!ids.contains(resourceId)) {
            throw new IllegalArgumentException(
                "operation resource is absent");
          }
        }
      }
    }
  }

  private static List<InputBuffer> sortedInputBuffers(
      List<InputBuffer> input) {
    Objects.requireNonNull(input, "inputBuffers");
    if (input.size() > IrisShadowReplayBufferSnapshot.MAX_IMAGES) {
      throw new IllegalArgumentException("graph input buffer capacity");
    }
    ArrayList<InputBuffer> buffers = new ArrayList<>(input);
    buffers.sort(Comparator.comparingInt(InputBuffer::bufferId));
    long totalBytes = 0;
    for (int index = 0; index < buffers.size(); index++) {
      InputBuffer buffer = Objects.requireNonNull(buffers.get(index),
          "inputBuffer");
      if (buffer.bufferId() != index) {
        throw new IllegalArgumentException(
            "graph input buffer ids must be dense");
      }
      totalBytes = Math.addExact(totalBytes, buffer.image().byteLength());
    }
    if (totalBytes > IrisMetalShadowReplayPacketEncoder.MAX_PACKET_BYTES) {
      throw new IllegalArgumentException("graph input buffer byte capacity");
    }
    return List.copyOf(buffers);
  }

  private static List<InputTexture> sortedInputTextures(
      List<InputTexture> input) {
    Objects.requireNonNull(input, "inputTextures");
    if (input.size() > IrisShadowReplayTextureSnapshot.MAX_TEXTURES) {
      throw new IllegalArgumentException("graph input texture capacity");
    }
    ArrayList<InputTexture> textures = new ArrayList<>(input);
    textures.sort(Comparator.comparingInt(InputTexture::textureId));
    long totalBytes = 0;
    HashSet<Integer> names = new HashSet<>();
    for (int index = 0; index < textures.size(); index++) {
      InputTexture texture = Objects.requireNonNull(textures.get(index),
          "inputTexture");
      if (texture.textureId() != index
          || !names.add(texture.image().texture())) {
        throw new IllegalArgumentException(
            "graph input texture ids must be dense and names unique");
      }
      totalBytes = Math.addExact(totalBytes,
          texture.image().byteLength());
    }
    if (totalBytes > IrisShadowReplayTextureSnapshot.MAX_TOTAL_BYTES) {
      throw new IllegalArgumentException("graph input texture byte capacity");
    }
    return List.copyOf(textures);
  }

  private static List<Resource> sortedResources(List<Resource> input) {
    Objects.requireNonNull(input, "resources");
    if (input.isEmpty() || input.size() > 512) {
      throw new IllegalArgumentException("invalid graph resource count");
    }
    ArrayList<Resource> resources = new ArrayList<>(input);
    resources.sort(Comparator.comparingInt(Resource::resourceId));
    int previous = -1;
    HashSet<Long> tokens = new HashSet<>();
    for (Resource resource : resources) {
      Objects.requireNonNull(resource, "resource");
      if (resource.resourceId() == previous || !tokens.add(resource.token())) {
        throw new IllegalArgumentException("duplicate graph resource");
      }
      previous = resource.resourceId();
    }
    return List.copyOf(resources);
  }

  public record Resource(int resourceId, long token) {
    public Resource {
      if (resourceId < 0 || resourceId >= IrisRenderGraph.MAX_RESOURCES
          || token <= 0) {
        throw new IllegalArgumentException("invalid graph frame resource");
      }
    }
  }

  public record InputBuffer(int bufferId,
                            IrisShadowReplayBufferSnapshot.BufferImage image) {
    public InputBuffer {
      if (bufferId < 0) {
        throw new IllegalArgumentException("negative graph input buffer");
      }
      Objects.requireNonNull(image, "image");
    }
  }

  public record InputTexture(int textureId,
                             IrisGlTextureMirror.TextureSnapshot image) {
    public InputTexture {
      if (textureId < 0) {
        throw new IllegalArgumentException("negative graph input texture");
      }
      Objects.requireNonNull(image, "image");
      if (image.shared() || image.byteLength() <= 0) {
        throw new IllegalArgumentException(
            "graph input texture must own inline pixels");
      }
    }
  }

  public sealed interface Operation permits Clear, Barrier, CopyTexture,
      GenerateMipmaps, Draw, Compute {
    List<Integer> resourceIds();
  }

  public record Clear(int resourceId, int mipLevel, Aspect aspect,
                      IrisClearCommand.ValueKind valueKind,
                      List<Long> rawValues,
                      Optional<IrisClearCommand.Rect> region)
      implements Operation {
    public Clear(int resourceId, Aspect aspect,
                 IrisClearCommand.ValueKind valueKind,
                 List<Long> rawValues,
                 Optional<IrisClearCommand.Rect> region) {
      this(resourceId, 0, aspect, valueKind, rawValues, region);
    }

    public Clear {
      if (resourceId < 0 || mipLevel < 0) {
        throw new IllegalArgumentException("invalid clear resource");
      }
      Objects.requireNonNull(aspect, "aspect");
      Objects.requireNonNull(valueKind, "valueKind");
      rawValues = List.copyOf(rawValues);
      region = Objects.requireNonNull(region, "region");
      int expected = aspect == Aspect.COLOR ? 4
          : aspect == Aspect.DEPTH_STENCIL ? 2 : 1;
      if (rawValues.size() != expected) {
        throw new IllegalArgumentException("invalid graph clear values");
      }
      rawValues.forEach(value -> Objects.requireNonNull(value,
          "clear value"));
    }

    @Override
    public List<Integer> resourceIds() {
      return List.of(resourceId);
    }
  }

  public record Barrier(int glBarrierBits) implements Operation {
    public Barrier {
      if (glBarrierBits < 0) {
        throw new IllegalArgumentException("invalid graph barrier bits");
      }
    }

    @Override
    public List<Integer> resourceIds() {
      return List.of();
    }
  }

  public record CopyTexture(int sourceResourceId,
                            int destinationResourceId,
                            int sourceLevel, int destinationLevel,
                            int sourceX, int sourceY,
                            int destinationX, int destinationY,
                            int width, int height) implements Operation {
    public CopyTexture {
      if (sourceResourceId < 0 || destinationResourceId < 0
          || sourceResourceId == destinationResourceId
          || sourceLevel < 0 || destinationLevel < 0
          || sourceX < 0 || sourceY < 0
          || destinationX < 0 || destinationY < 0
          || width <= 0 || height <= 0) {
        throw new IllegalArgumentException("invalid graph texture copy");
      }
    }

    @Override
    public List<Integer> resourceIds() {
      return List.of(sourceResourceId, destinationResourceId);
    }
  }

  public record GenerateMipmaps(int resourceId) implements Operation {
    public GenerateMipmaps {
      if (resourceId < 0) {
        throw new IllegalArgumentException("negative mipmap resource");
      }
    }

    @Override
    public List<Integer> resourceIds() {
      return List.of(resourceId);
    }
  }

  public record Draw(String pipelineKeySha256, byte[] replayPacket,
                     List<ColorTarget> colorTargets,
                     int depthResourceId, int depthMipLevel,
                     int stencilResourceId, int stencilMipLevel,
                     Map<Integer, Integer> textureOverrides)
      implements Operation {
    public Draw(String pipelineKeySha256, byte[] replayPacket,
                List<ColorTarget> colorTargets,
                int depthResourceId, int stencilResourceId,
                Map<Integer, Integer> textureOverrides) {
      this(pipelineKeySha256, replayPacket, colorTargets,
          depthResourceId, 0, stencilResourceId, 0, textureOverrides);
    }

    public Draw {
      IrisRenderGraph.requireSha(pipelineKeySha256, "pipelineKeySha256");
      replayPacket = Objects.requireNonNull(replayPacket, "replayPacket")
          .clone();
      if (replayPacket.length <= 0 || replayPacket.length
          > IrisMetalShadowReplayPacketEncoder.MAX_PACKET_BYTES) {
        throw new IllegalArgumentException("invalid nested replay packet");
      }
      ArrayList<ColorTarget> colors = new ArrayList<>(colorTargets);
      colors.sort(Comparator.comparingInt(ColorTarget::slot));
      int previous = -1;
      for (ColorTarget color : colors) {
        Objects.requireNonNull(color, "colorTarget");
        if (color.slot() == previous) {
          throw new IllegalArgumentException("duplicate draw color slot");
        }
        previous = color.slot();
      }
      colorTargets = List.copyOf(colors);
      if (colorTargets.size() > IrisPipelineState.MAX_COLOR_ATTACHMENTS
          || depthResourceId < -1 || stencilResourceId < -1
          || depthMipLevel < 0 || stencilMipLevel < 0
          || (depthResourceId < 0 && depthMipLevel != 0)
          || (stencilResourceId < 0 && stencilMipLevel != 0)) {
        throw new IllegalArgumentException("invalid draw graph targets");
      }
      java.util.TreeMap<Integer, Integer> overrides =
          new java.util.TreeMap<>();
      for (Map.Entry<Integer, Integer> entry
          : Objects.requireNonNull(textureOverrides,
              "textureOverrides").entrySet()) {
        if (entry.getKey() == null || entry.getKey() <= 0
            || entry.getValue() == null || entry.getValue() < 0
            || overrides.put(entry.getKey(), entry.getValue()) != null) {
          throw new IllegalArgumentException("invalid texture override");
        }
      }
      if (overrides.size() > IrisShadowReplayTextureSnapshot.MAX_TEXTURES) {
        throw new IllegalArgumentException("texture override capacity");
      }
      textureOverrides = Collections.unmodifiableMap(
          new LinkedHashMap<>(overrides));
      if (colorTargets.isEmpty() && depthResourceId < 0
          && stencilResourceId < 0) {
        throw new IllegalArgumentException("draw has no graph target");
      }
    }

    @Override
    public byte[] replayPacket() {
      return replayPacket.clone();
    }

    @Override
    public List<Integer> resourceIds() {
      ArrayList<Integer> ids = new ArrayList<>(colorTargets.size()
          + textureOverrides.size() + 2);
      colorTargets.forEach(target -> ids.add(target.resourceId()));
      if (depthResourceId >= 0) {
        ids.add(depthResourceId);
      }
      if (stencilResourceId >= 0) {
        ids.add(stencilResourceId);
      }
      ids.addAll(textureOverrides.values());
      return List.copyOf(ids);
    }
  }

  public record Compute(String pipelineKeySha256, byte[] replayPacket,
                        int groupsX, int groupsY, int groupsZ,
                        List<Integer> resources) implements Operation {
    public Compute {
      IrisRenderGraph.requireSha(pipelineKeySha256, "pipelineKeySha256");
      replayPacket = Objects.requireNonNull(replayPacket, "replayPacket").clone();
      if (replayPacket.length <= 0 ||
          replayPacket.length > IrisMetalShadowReplayPacketEncoder.MAX_PACKET_BYTES) {
        throw new IllegalArgumentException("invalid compute replay packet");
      }
      if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
        throw new IllegalArgumentException("invalid compute dispatch groups");
      }
      resources = resources == null ? List.of() : List.copyOf(resources);
      if (resources.size() > IrisRenderGraph.MAX_RESOURCES
          || resources.stream().anyMatch(id -> id == null || id < 0)
          || resources.stream().distinct().count() != resources.size()) {
        throw new IllegalArgumentException("invalid compute graph resources");
      }
    }

    @Override
    public byte[] replayPacket() {
      return replayPacket.clone();
    }

    @Override
    public List<Integer> resourceIds() {
      return resources;
    }
  }

  public record ColorTarget(int slot, int resourceId, int mipLevel) {
    public ColorTarget(int slot, int resourceId) {
      this(slot, resourceId, 0);
    }

    public ColorTarget {
      if (slot < 0 || slot >= IrisPipelineState.MAX_COLOR_ATTACHMENTS
          || resourceId < 0 || mipLevel < 0) {
        throw new IllegalArgumentException("invalid graph color target");
      }
    }
  }

  public enum Aspect {
    COLOR,
    DEPTH,
    STENCIL,
    DEPTH_STENCIL
  }
}

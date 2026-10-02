package com.pebbles_boon.metalrender.compat.iris;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Strict, bounded JNI packet for one offscreen Metal shadow draw. */
public final class IrisMetalShadowReplayPacketEncoder {
  public static final int MAGIC = 0x4d525837; // MRX8 (stable magic)
  public static final int SCHEMA = 8;
  public static final int MAX_PACKET_BYTES = 384 * 1024 * 1024;
  public static final int MAX_TARGET_EXTENT = 4_096;

  private IrisMetalShadowReplayPacketEncoder() {
  }

  public static byte[] encode(IrisPipelineStateCapture.PendingState pending,
      IrisMetalVertexBindingLayout vertexLayout,
      IrisShadowReplayArgumentTable arguments,
      int targetWidth, int targetHeight) {
    Objects.requireNonNull(pending, "pending");
    return encode(pending.command(), pending.dynamicState(),
        pending.replayBuffers(), pending.replayTextures(), vertexLayout,
        arguments, targetWidth, targetHeight);
  }

  static byte[] encode(IrisExecutionCommand command,
      IrisDynamicDrawState dynamic,
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayTextureSnapshot textures,
      IrisMetalVertexBindingLayout vertexLayout,
      IrisShadowReplayArgumentTable arguments,
      int targetWidth, int targetHeight) {
    return encode(command, dynamic, buffers, textures, vertexLayout,
        arguments, targetWidth, targetHeight, Set.of());
  }

  static byte[] encode(IrisExecutionCommand command,
      IrisDynamicDrawState dynamic,
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayTextureSnapshot textures,
      IrisMetalVertexBindingLayout vertexLayout,
      IrisShadowReplayArgumentTable arguments,
      int targetWidth, int targetHeight,
      Set<Integer> externalTextureNames) {
    return encode(command, dynamic, buffers, textures, vertexLayout,
        arguments, targetWidth, targetHeight, externalTextureNames,
        Map.of());
  }

  static byte[] encode(IrisExecutionCommand command,
      IrisDynamicDrawState dynamic,
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayTextureSnapshot textures,
      IrisMetalVertexBindingLayout vertexLayout,
      IrisShadowReplayArgumentTable arguments,
      int targetWidth, int targetHeight,
      Set<Integer> externalTextureNames,
      Map<Integer, Integer> externalBufferIndices) {
    return encode(command, dynamic, buffers, textures, vertexLayout,
        arguments, targetWidth, targetHeight, externalTextureNames,
        externalBufferIndices, Map.of());
  }

  static byte[] encode(IrisExecutionCommand command,
      IrisDynamicDrawState dynamic,
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayTextureSnapshot textures,
      IrisMetalVertexBindingLayout vertexLayout,
      IrisShadowReplayArgumentTable arguments,
      int targetWidth, int targetHeight,
      Set<Integer> externalTextureNames,
      Map<Integer, Integer> externalBufferIndices,
      Map<Integer, Integer> externalTextureIndices) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(dynamic, "dynamic");
    Objects.requireNonNull(buffers, "buffers");
    Objects.requireNonNull(textures, "textures");
    Objects.requireNonNull(vertexLayout, "vertexLayout");
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(externalTextureNames, "externalTextureNames");
    Objects.requireNonNull(externalBufferIndices, "externalBufferIndices");
    Objects.requireNonNull(externalTextureIndices,
        "externalTextureIndices");
    if (externalTextureNames.stream().anyMatch(name -> name == null
        || name <= 0 || !textures.textures().containsKey(name))) {
      throw new IllegalArgumentException("invalid external texture set");
    }
    if (externalTextureIndices.entrySet().stream().anyMatch(entry ->
        entry.getKey() == null || entry.getKey() <= 0
            || !textures.textures().containsKey(entry.getKey())
            || externalTextureNames.contains(entry.getKey())
            || entry.getValue() == null || entry.getValue() < 0)) {
      throw new IllegalArgumentException(
          "invalid frame external texture table");
    }
    if (targetWidth <= 0 || targetWidth > MAX_TARGET_EXTENT
        || targetHeight <= 0 || targetHeight > MAX_TARGET_EXTENT) {
      throw new IllegalArgumentException("invalid shadow replay target");
    }
    boolean compute = vertexLayout.state().pass().kind()
        == IrisPipelineState.PassKind.COMPUTE;
    IrisGlStateSnapshot.Operation operation = compute
        ? IrisGlStateSnapshot.Operation.DISPATCH
        : IrisGlStateSnapshot.Operation.DRAW;
    if (!buffers.drawComplete() || !textures.captureEnabled()
        || !arguments.complete() || !dynamic.completeFor(operation)
        || compute && !(command instanceof IrisExecutionCommand.Dispatch)) {
      throw new IllegalArgumentException("incomplete shadow replay packet");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(16_384);
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(MAGIC);
      out.writeInt(SCHEMA);
      out.writeInt(targetWidth);
      out.writeInt(targetHeight);
      putDynamic(out, dynamic, compute);
      putCommand(out, command, buffers);
      BufferPacketLayout bufferPacket = bufferPacketLayout(buffers,
          arguments);
      putBuffers(out, buffers, vertexLayout, bufferPacket,
          externalBufferIndices);
      putTextures(out, textures, arguments, externalTextureNames,
          externalTextureIndices);
      putArguments(out, arguments, bufferPacket.imageRemap());
      out.flush();
      byte[] result = bytes.toByteArray();
      if (result.length <= 0 || result.length > MAX_PACKET_BYTES) {
        throw new IllegalArgumentException("shadow replay packet too large");
      }
      return result;
    } catch (IOException impossible) {
      throw new IllegalStateException("shadow replay packet encode failed",
          impossible);
    }
  }

  private static void putDynamic(DataOutputStream out,
      IrisDynamicDrawState dynamic, boolean compute) throws IOException {
    if (compute) {
      putRect(out, new IrisDynamicDrawState.Rect(0, 0, 1, 1));
      out.writeBoolean(false);
      putRect(out, new IrisDynamicDrawState.Rect(0, 0, 0, 0));
      return;
    }
    putRect(out, dynamic.viewport().value());
    boolean scissor = dynamic.scissorEnabled().value();
    out.writeBoolean(scissor);
    putRect(out, scissor ? dynamic.scissor().value()
        : new IrisDynamicDrawState.Rect(0, 0, 0, 0));
  }

  private static void putRect(DataOutputStream out,
      IrisDynamicDrawState.Rect rect) throws IOException {
    out.writeInt(rect.x());
    out.writeInt(rect.y());
    out.writeInt(rect.width());
    out.writeInt(rect.height());
  }

  private static void putCommand(DataOutputStream out,
      IrisExecutionCommand command,
      IrisShadowReplayBufferSnapshot buffers) throws IOException {
    if (command instanceof IrisExecutionCommand.DrawArrays draw) {
      out.writeInt(1);
      out.writeInt(draw.primitiveMode());
      out.writeInt(draw.firstVertex());
      out.writeInt(draw.vertexCount());
      out.writeInt(draw.instanceCount());
      out.writeInt(draw.baseInstance());
      return;
    }
    if (command instanceof IrisExecutionCommand.DrawIndexed draw) {
      if (draw.indexElementBytes() == 1) {
        throw new IllegalArgumentException("uint8 indices need expansion");
      }
      out.writeInt(2);
      out.writeInt(draw.primitiveMode());
      out.writeLong(draw.indexOffsetBytes());
      out.writeInt(draw.indexCount());
      out.writeInt(draw.indexElementBytes());
      out.writeInt(draw.baseVertex());
      out.writeInt(draw.instanceCount());
      out.writeInt(draw.baseInstance());
      return;
    }
    if (command instanceof IrisExecutionCommand.Dispatch dispatch) {
      out.writeInt(4);
      out.writeInt(dispatch.groupsX());
      out.writeInt(dispatch.groupsY());
      out.writeInt(dispatch.groupsZ());
      out.writeInt(dispatch.localSizeX());
      out.writeInt(dispatch.localSizeY());
      out.writeInt(dispatch.localSizeZ());
      return;
    }
    if (command instanceof IrisExecutionCommand.MultiDrawIndexed draw) {
      if (draw.indexElementBytes() == 1) {
        throw new IllegalArgumentException("uint8 indices need expansion");
      }
      out.writeInt(3);
      out.writeInt(draw.primitiveMode());
      out.writeInt(draw.indexElementBytes());
      long[] offsets = draw.indexOffsetsBytes();
      int[] counts = draw.indexCounts();
      int[] bases = draw.baseVertices();
      out.writeInt(offsets.length);
      for (int index = 0; index < offsets.length; index++) {
        out.writeLong(offsets[index]);
        out.writeInt(counts[index]);
        out.writeInt(bases[index]);
      }
      return;
    }
    if (command instanceof IrisExecutionCommand.IndirectDraw indirect) {
      putIndirectCommand(out, indirect, buffers);
      return;
    }
    throw new IllegalArgumentException("unsupported shadow draw command");
  }

  /**
   * Lowers GL indirect draw arguments into the direct/multi-draw packet ABI.
   * The replay protocol intentionally has no indirect-buffer execution path,
   * so the conversion happens from the immutable draw-time CPU snapshot.
   *
   * <p>Indexed indirect commands map naturally to MultiDrawIndexed. Array
   * indirect is representable only for a single command because the packet ABI
   * has no MultiDrawArrays form.  The native replay ABI also has one common
   * instance/baseInstance pair for MultiDrawIndexed, so indexed indirect is
   * accepted only for the ordinary instanceCount=1, baseInstance=0 case.</p>
   */
  private static void putIndirectCommand(DataOutputStream out,
      IrisExecutionCommand.IndirectDraw draw,
      IrisShadowReplayBufferSnapshot buffers) throws IOException {
    IrisShadowReplayBufferSnapshot.BufferRef reference = buffers.indirectArguments()
        .orElseThrow(() -> new IllegalArgumentException(
            "indirect draw arguments snapshot unavailable"));
    int imageId = reference.imageId();
    if (imageId < 0 || imageId >= buffers.images().size()) {
      throw new IllegalArgumentException("indirect draw arguments image invalid");
    }
    IrisShadowReplayBufferSnapshot.BufferImage image = buffers.images().get(imageId);
    int elementBytes = draw.indexElementBytes();
    int commandBytes = elementBytes == 0 ? 16 : 20;
    long requiredBytes = Math.multiplyExact((long) draw.drawCount(),
        commandBytes);
    if (image.byteLength() < requiredBytes || image.ownedBytes().length <
        requiredBytes) {
      throw new IllegalArgumentException(
          "indirect draw arguments snapshot truncated");
    }
    ByteBuffer data = ByteBuffer.wrap(image.ownedBytes())
        .order(ByteOrder.LITTLE_ENDIAN);

    if (elementBytes == 0) {
      if (draw.drawCount() != 1) {
        throw new IllegalArgumentException(
            "multi-draw-array indirect lowering unavailable");
      }
      int vertexCount = readUnsignedIntAsInt(data, "indirect vertex count");
      int instanceCount = readUnsignedIntAsInt(data, "indirect instance count");
      int firstVertex = readUnsignedIntAsInt(data, "indirect first vertex");
      int baseInstance = readUnsignedIntAsInt(data, "indirect base instance");
      out.writeInt(1);
      out.writeInt(draw.primitiveMode());
      out.writeInt(firstVertex);
      out.writeInt(vertexCount);
      out.writeInt(instanceCount);
      out.writeInt(baseInstance);
      return;
    }

    if (elementBytes != 2 && elementBytes != 4) {
      throw new IllegalArgumentException(
          "uint8 indices need expansion for indirect draw");
    }

    long[] offsets = new long[draw.drawCount()];
    int[] counts = new int[draw.drawCount()];
    int[] bases = new int[draw.drawCount()];
    for (int index = 0; index < draw.drawCount(); index++) {
      int count = readUnsignedIntAsInt(data, "indirect index count");
      int instanceCount =
          readUnsignedIntAsInt(data, "indirect instance count");
      long firstIndex = Integer.toUnsignedLong(data.getInt());
      int baseVertex = data.getInt();
      int baseInstance =
          readUnsignedIntAsInt(data, "indirect base instance");
      if (instanceCount != 1 || baseInstance != 0) {
        throw new IllegalArgumentException(
            "indirect indexed draw needs instanceCount=1 and baseInstance=0");
      }
      offsets[index] = Math.multiplyExact(firstIndex, (long) elementBytes);
      counts[index] = count;
      bases[index] = baseVertex;
    }

    out.writeInt(3);
    out.writeInt(draw.primitiveMode());
    out.writeInt(elementBytes);
    out.writeInt(offsets.length);
    for (int index = 0; index < offsets.length; index++) {
      out.writeLong(offsets[index]);
      out.writeInt(counts[index]);
      out.writeInt(bases[index]);
    }
  }

  private static int readUnsignedIntAsInt(ByteBuffer data, String label) {
    long value = Integer.toUnsignedLong(data.getInt());
    if (value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(label + " exceeds signed packet range");
    }
    return (int) value;
  }

  private static void putBuffers(DataOutputStream out,
      IrisShadowReplayBufferSnapshot buffers,
      IrisMetalVertexBindingLayout vertexLayout,
      BufferPacketLayout packet,
      Map<Integer, Integer> externalBufferIndices) throws IOException {
    if (externalBufferIndices.size() > packet.images().size()
        || externalBufferIndices.entrySet().stream().anyMatch(entry ->
            entry.getKey() == null || entry.getKey() < 0
                || entry.getValue() == null || entry.getValue() < 0)) {
      throw new IllegalArgumentException("invalid external buffer table");
    }
    out.writeInt(packet.images().size());
    for (IrisShadowReplayBufferSnapshot.BufferImage image : packet.images()) {
      Integer external = externalBufferIndices.get(image.id());
      if (external != null) {
        out.writeInt(3);
        out.writeInt(image.byteLength());
        out.writeLong(Integer.toUnsignedLong(external) + 1L);
        continue;
      }
      out.writeInt(image.shared() ? 2 : 1);
      out.writeInt(image.byteLength());
      if (image.shared()) {
        out.writeLong(image.sharedHandle());
      } else {
        out.write(image.ownedBytes());
      }
    }
    out.writeInt(buffers.vertexBuffers().size());
    for (IrisShadowReplayBufferSnapshot.VertexBufferRef vertex
        : buffers.vertexBuffers()) {
      out.writeInt(vertexLayout.metalBufferIndex(vertex.slot()));
      out.writeInt(packet.remap(vertex.buffer().imageId()));
    }
    out.writeInt(buffers.indexBuffer().map(reference ->
        packet.remap(reference.imageId())).orElse(-1));
  }

  private static void putTextures(DataOutputStream out,
      IrisShadowReplayTextureSnapshot textures,
      IrisShadowReplayArgumentTable arguments,
      Set<Integer> externalTextureNames,
      Map<Integer, Integer> externalTextureIndices) throws IOException {
    TreeSet<Integer> requiredNames = new TreeSet<>();
    arguments.stages().stream()
        .flatMap(stage -> stage.arguments().stream())
        .map(IrisShadowReplayArgumentTable.BoundArgument::value)
        .flatMap(value -> {
          if (value instanceof IrisShadowReplayArgumentTable.TextureImage image) {
            return java.util.stream.Stream.of(image.glTexture());
          }
          if (value instanceof IrisShadowReplayArgumentTable.StorageTextureImage image) {
            return java.util.stream.Stream.of(image.glTexture());
          }
          return java.util.stream.Stream.empty();
        })
        .forEach(requiredNames::add);
    if (!requiredNames.containsAll(externalTextureNames)
        || !requiredNames.containsAll(externalTextureIndices.keySet())) {
      throw new IllegalArgumentException("unused external texture reference");
    }
    ArrayList<IrisGlTextureMirror.TextureSnapshot> sorted =
        new ArrayList<>(requiredNames.size());
    for (Integer name : requiredNames) {
      IrisGlTextureMirror.TextureSnapshot texture =
          textures.textures().get(name);
      if (texture == null) {
        throw new IllegalArgumentException(
            "required texture snapshot missing");
      }
      sorted.add(texture);
    }
    sorted.sort(Comparator.comparingInt(
        IrisGlTextureMirror.TextureSnapshot::texture));
    out.writeInt(sorted.size());
    for (IrisGlTextureMirror.TextureSnapshot texture : sorted) {
      out.writeInt(texture.texture());
      putString(out, texture.format());
      out.writeInt(texture.width());
      out.writeInt(texture.height());
      out.writeInt(texture.layer());
      out.writeInt(texture.mipLevel());
      out.writeInt(texture.bytesPerPixel());
      if (externalTextureNames.contains(texture.texture())) {
        // MGF9 resolves this logical GL texture to a persistent Metal graph
        // attachment. Preserve strict compatibility metadata without
        // duplicating the attachment pixels in every draw packet.
        out.writeInt(3);
      } else if (texture.graphReference()) {
        throw new IllegalArgumentException(
            "Metal graph texture reference requires graph override");
      } else if (externalTextureIndices.containsKey(texture.texture())) {
        out.writeInt(4);
        out.writeLong(Integer.toUnsignedLong(
            externalTextureIndices.get(texture.texture())) + 1L);
      } else if (texture.shared()) {
        out.writeInt(2);
        out.writeLong(texture.sharedHandle());
      } else {
        out.writeInt(1);
        byte[] payload = texture.bytes();
        out.writeInt(payload.length);
        out.write(payload);
      }
    }
  }

  private static void putArguments(DataOutputStream out,
      IrisShadowReplayArgumentTable arguments,
      Map<Integer, Integer> imageRemap) throws IOException {
    out.writeInt(arguments.stages().size());
    for (IrisShadowReplayArgumentTable.StageTable stage
        : arguments.stages()) {
      out.writeInt(stage.stage().ordinal());
      out.writeInt(stage.arguments().size());
      for (IrisShadowReplayArgumentTable.BoundArgument argument
          : stage.arguments()) {
        out.writeInt(argument.argumentBufferIndex());
        out.writeInt(argument.id());
        putArgument(out, argument.value(), imageRemap);
      }
    }
  }

  private static void putArgument(DataOutputStream out,
      IrisShadowReplayArgumentTable.ArgumentValue value,
      Map<Integer, Integer> imageRemap) throws IOException {
    if (value instanceof IrisShadowReplayArgumentTable.InlineUniform inline) {
      out.writeInt(1);
      byte[] payload = inline.bytes();
      out.writeInt(payload.length);
      out.write(payload);
    } else if (value instanceof IrisShadowReplayArgumentTable.BufferImage image) {
      out.writeInt(2);
      Integer remapped = imageRemap.get(image.imageId());
      if (remapped == null) {
        throw new IllegalArgumentException("missing packet buffer image");
      }
      out.writeInt(remapped);
    } else if (value instanceof IrisShadowReplayArgumentTable.TextureBufferImage image) {
      out.writeInt(7);
      Integer remapped = imageRemap.get(image.imageId());
      if (remapped == null) {
        throw new IllegalArgumentException(
            "missing packet texture-buffer image");
      }
      out.writeInt(remapped);
      out.writeInt(image.internalFormat());
    } else if (value instanceof IrisShadowReplayArgumentTable.TextureImage image) {
      out.writeInt(3);
      out.writeInt(image.glTexture());
    } else if (value instanceof IrisShadowReplayArgumentTable.StorageTextureImage image) {
      out.writeInt(8);
      out.writeInt(image.glTexture());
    } else if (value instanceof IrisShadowReplayArgumentTable.CanonicalZeroTexture) {
      out.writeInt(4);
    } else if (value instanceof IrisShadowReplayArgumentTable.CapturedSampler sampler) {
      out.writeInt(5);
      putSampler(out, sampler.state());
    } else if (value instanceof IrisShadowReplayArgumentTable.CanonicalDefaultSampler) {
      out.writeInt(6);
    } else {
      throw new IllegalArgumentException("unknown shadow argument value");
    }
  }

  private static void putSampler(DataOutputStream out,
      IrisGlSamplerMirror.SamplerState state) throws IOException {
    out.writeInt(state.minFilter());
    out.writeInt(state.magFilter());
    out.writeInt(state.wrapS());
    out.writeInt(state.wrapT());
    out.writeInt(state.wrapR());
    out.writeInt(state.compareMode());
    out.writeInt(state.compareFunc());
    out.writeInt(state.baseLevel());
    out.writeInt(state.maxLevel());
    out.writeInt(Float.floatToRawIntBits(state.minLod()));
    out.writeInt(Float.floatToRawIntBits(state.maxLod()));
    out.writeInt(Float.floatToRawIntBits(state.lodBias()));
    out.writeInt(Float.floatToRawIntBits(state.maxAnisotropy()));
    out.writeBoolean(state.integerBorderColor());
    for (Integer component : state.borderColor()) {
      out.writeInt(component);
    }
  }

  private static void putString(DataOutputStream out, String value)
      throws IOException {
    byte[] encoded = Objects.requireNonNull(value, "value")
        .getBytes(StandardCharsets.US_ASCII);
    if (encoded.length <= 0 || encoded.length > 128) {
      throw new IllegalArgumentException("invalid packet string");
    }
    out.writeInt(encoded.length);
    out.write(encoded);
  }

  static List<IrisShadowReplayBufferSnapshot.BufferImage>
      requiredBufferImages(IrisShadowReplayBufferSnapshot buffers,
          IrisShadowReplayArgumentTable arguments) {
    return bufferPacketLayout(buffers, arguments).images();
  }

  private static BufferPacketLayout bufferPacketLayout(
      IrisShadowReplayBufferSnapshot buffers,
      IrisShadowReplayArgumentTable arguments) {
    TreeSet<Integer> required = new TreeSet<>();
    buffers.vertexBuffers().forEach(reference ->
        required.add(reference.buffer().imageId()));
    buffers.indexBuffer().ifPresent(reference ->
        required.add(reference.imageId()));
    arguments.stages().stream()
        .flatMap(stage -> stage.arguments().stream())
        .map(IrisShadowReplayArgumentTable.BoundArgument::value)
        .filter(IrisShadowReplayArgumentTable.BufferImage.class::isInstance)
        .map(IrisShadowReplayArgumentTable.BufferImage.class::cast)
        .forEach(image -> required.add(image.imageId()));
    arguments.stages().stream()
        .flatMap(stage -> stage.arguments().stream())
        .map(IrisShadowReplayArgumentTable.BoundArgument::value)
        .filter(IrisShadowReplayArgumentTable.TextureBufferImage.class
            ::isInstance)
        .map(IrisShadowReplayArgumentTable.TextureBufferImage.class::cast)
        .forEach(image -> required.add(image.imageId()));

    ArrayList<IrisShadowReplayBufferSnapshot.BufferImage> images =
        new ArrayList<>(required.size());
    LinkedHashMap<Integer, Integer> remap = new LinkedHashMap<>();
    for (Integer original : required) {
      if (original < 0 || original >= buffers.images().size()) {
        throw new IllegalArgumentException("packet buffer image out of range");
      }
      remap.put(original, images.size());
      images.add(buffers.images().get(original));
    }
    return new BufferPacketLayout(List.copyOf(images), Map.copyOf(remap));
  }

  private record BufferPacketLayout(
      List<IrisShadowReplayBufferSnapshot.BufferImage> images,
      Map<Integer, Integer> imageRemap) {
    private BufferPacketLayout {
      images = List.copyOf(images);
      imageRemap = Map.copyOf(imageRemap);
    }

    private int remap(int original) {
      Integer mapped = imageRemap.get(original);
      if (mapped == null) {
        throw new IllegalArgumentException("missing packet buffer remap");
      }
      return mapped;
    }
  }
}

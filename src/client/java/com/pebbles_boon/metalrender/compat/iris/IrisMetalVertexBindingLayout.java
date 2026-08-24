package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Avoids Metal vertex-buffer collisions with SPIRV-Cross argument buffers. */
public record IrisMetalVertexBindingLayout(IrisPipelineState state,
                                           Map<Integer, Integer> remap) {
  public static final int MAX_METAL_BUFFER_INDEX = 30;

  public IrisMetalVertexBindingLayout {
    Objects.requireNonNull(state, "state");
    remap = Map.copyOf(remap);
    if (remap.size() != state.vertexBuffers().size()) {
      throw new IllegalArgumentException("incomplete Metal vertex remap");
    }
    HashSet<Integer> unique = new HashSet<>(remap.values());
    if (unique.size() != remap.size() || unique.stream().anyMatch(index ->
        index < 0 || index > MAX_METAL_BUFFER_INDEX)) {
      throw new IllegalArgumentException("invalid Metal vertex remap");
    }
  }

  public static IrisMetalVertexBindingLayout resolve(IrisPipelineState state,
      IrisMslArgumentLayout arguments) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(arguments, "arguments");
    Set<Integer> reserved = new HashSet<>();
    arguments.stages().stream()
        .filter(stage -> stage.stage() == IrisShaderStage.VERTEX)
        .flatMap(stage -> stage.bindings().stream())
        .map(IrisMslArgumentLayout.ArgumentBinding::argumentBufferIndex)
        .forEach(reserved::add);
    ArrayList<Integer> available = new ArrayList<>();
    for (int index = 0; index <= MAX_METAL_BUFFER_INDEX; index++) {
      if (!reserved.contains(index)) {
        available.add(index);
      }
    }
    if (state.vertexBuffers().size() > available.size()) {
      throw new IllegalArgumentException(
          "Metal buffer slots exhausted by argument buffers");
    }
    HashMap<Integer, Integer> remap = new HashMap<>();
    HashSet<Integer> used = new HashSet<>();
    for (VertexBufferLayout buffer : state.vertexBuffers()) {
      int original = buffer.bufferIndex();
      if (original <= MAX_METAL_BUFFER_INDEX
          && !reserved.contains(original) && used.add(original)) {
        remap.put(original, original);
      } else {
        int replacement = available.stream()
            .filter(candidate -> !used.contains(candidate))
            .findFirst().orElseThrow();
        used.add(replacement);
        remap.put(original, replacement);
      }
    }
    ArrayList<VertexBufferLayout> buffers = new ArrayList<>();
    for (VertexBufferLayout buffer : state.vertexBuffers()) {
      buffers.add(new VertexBufferLayout(remap.get(buffer.bufferIndex()),
          buffer.strideBytes(), buffer.stepFunction(), buffer.stepRate()));
    }
    ArrayList<VertexAttribute> attributes = new ArrayList<>();
    for (VertexAttribute attribute : state.vertexAttributes()) {
      Integer buffer = remap.get(attribute.bufferIndex());
      if (buffer == null) {
        throw new IllegalArgumentException(
            "vertex attribute remap is unavailable");
      }
      attributes.add(new VertexAttribute(attribute.location(), buffer,
          attribute.offsetBytes(), attribute.format()));
    }
    IrisPipelineState remapped = new IrisPipelineState(state.pass(), buffers,
        attributes, state.colorAttachments(), state.depthAttachmentFormat(),
        state.stencilAttachmentFormat(), state.rasterSampleCount(),
        state.sampleMask(), state.sampleCoverageEnabled(),
        state.sampleCoverageValue(), state.sampleCoverageInvert(),
        state.alphaToCoverage(), state.alphaToOne(), state.depth(),
        state.stencil(), state.raster(), state.primitive(),
        state.specializationScanned(), state.functionConstants());
    return new IrisMetalVertexBindingLayout(remapped, remap);
  }

  public int metalBufferIndex(int capturedSlot) {
    Integer result = remap.get(capturedSlot);
    if (result == null) {
      throw new IllegalArgumentException("unmapped vertex buffer slot");
    }
    return result;
  }
}

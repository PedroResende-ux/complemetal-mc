package com.pebbles_boon.metalrender.compat.iris;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Strict, versioned JNI descriptor for Stage 6 Metal pipeline creation. */
public final class IrisMetalPipelineDescriptorEncoder {
  static final int MAGIC = 0x4d525036; // MRP6
  static final int SCHEMA = 1;
  static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;

  private IrisMetalPipelineDescriptorEncoder() {
  }

  public static byte[] encode(IrisPipelineState state) {
    Objects.requireNonNull(state, "state");
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(MAGIC);
      out.writeInt(SCHEMA);
      out.writeInt(state.pass().kind().ordinal());

      out.writeInt(state.vertexBuffers().size());
      for (IrisPipelineState.VertexBufferLayout buffer
          : state.vertexBuffers()) {
        out.writeInt(buffer.bufferIndex());
        out.writeInt(buffer.strideBytes());
        out.writeInt(buffer.stepFunction().ordinal());
        out.writeInt(buffer.stepRate());
      }
      out.writeInt(state.vertexAttributes().size());
      for (IrisPipelineState.VertexAttribute attribute
          : state.vertexAttributes()) {
        out.writeInt(attribute.location());
        out.writeInt(attribute.bufferIndex());
        out.writeInt(attribute.offsetBytes());
        putString(out, attribute.format().cacheName());
      }
      out.writeInt(state.colorAttachments().size());
      for (IrisPipelineState.ColorAttachment attachment
          : state.colorAttachments()) {
        out.writeInt(attachment.slot());
        putString(out, attachment.format().cacheName());
        out.writeInt(attachment.writeMask());
        putBlend(out, attachment.blend());
      }
      putOptionalFormat(out, state.depthAttachmentFormat());
      putOptionalFormat(out, state.stencilAttachmentFormat());
      out.writeInt(state.rasterSampleCount());
      out.writeLong(state.sampleMask());
      out.writeBoolean(state.sampleCoverageEnabled());
      out.writeInt(Float.floatToRawIntBits(state.sampleCoverageValue()));
      out.writeBoolean(state.sampleCoverageInvert());
      out.writeBoolean(state.alphaToCoverage());
      out.writeBoolean(state.alphaToOne());

      out.writeBoolean(state.depth().testEnabled());
      out.writeInt(state.depth().compare().ordinal());
      out.writeBoolean(state.depth().writeEnabled());
      out.writeBoolean(state.stencil().enabled());
      putStencilFace(out, state.stencil().front());
      putStencilFace(out, state.stencil().back());

      out.writeBoolean(state.raster().rasterizationEnabled());
      out.writeInt(state.raster().cullMode().ordinal());
      out.writeInt(state.raster().frontFace().ordinal());
      out.writeInt(state.raster().frontFillMode().ordinal());
      out.writeInt(state.raster().backFillMode().ordinal());
      out.writeInt(state.raster().depthClipMode().ordinal());
      out.writeInt(state.raster().polygonOffsetModeMask());
      out.writeInt(Float.floatToRawIntBits(state.raster().depthBias()));
      out.writeInt(Float.floatToRawIntBits(state.raster().slopeScale()));
      out.writeInt(Float.floatToRawIntBits(state.raster().depthBiasClamp()));

      out.writeInt(state.primitive().topology().ordinal());
      out.writeInt(state.primitive().restartMode().ordinal());
      out.writeInt(state.primitive().patchControlPoints());
      out.writeInt(state.functionConstants().size());
      for (IrisPipelineState.FunctionConstant constant
          : state.functionConstants()) {
        out.writeInt(constant.stage().ordinal());
        out.writeInt(constant.constantId());
        out.writeInt(constant.type().ordinal());
        out.writeLong(constant.rawBits());
      }
      out.flush();
      byte[] result = bytes.toByteArray();
      if (result.length <= 0 || result.length > MAX_DESCRIPTOR_BYTES) {
        throw new IllegalArgumentException(
            "Metal pipeline descriptor exceeds JNI bound");
      }
      return result;
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "in-memory Metal pipeline encoding failed", impossible);
    }
  }

  private static void putBlend(DataOutputStream out,
      IrisPipelineState.BlendState blend) throws IOException {
    out.writeBoolean(blend.enabled());
    putBlendEquation(out, blend.rgb());
    putBlendEquation(out, blend.alpha());
  }

  private static void putBlendEquation(DataOutputStream out,
      IrisPipelineState.BlendEquation equation) throws IOException {
    out.writeInt(equation.operation().ordinal());
    out.writeInt(equation.source().ordinal());
    out.writeInt(equation.destination().ordinal());
  }

  private static void putStencilFace(DataOutputStream out,
      IrisPipelineState.StencilFace face) throws IOException {
    out.writeInt(face.compare().ordinal());
    out.writeInt(face.stencilFail().ordinal());
    out.writeInt(face.depthFail().ordinal());
    out.writeInt(face.pass().ordinal());
    out.writeInt(face.readMask());
    out.writeInt(face.writeMask());
    out.writeInt(face.reference());
  }

  private static void putOptionalFormat(DataOutputStream out,
      java.util.Optional<IrisPipelineState.DataFormat> format)
      throws IOException {
    out.writeBoolean(format.isPresent());
    if (format.isPresent()) {
      putString(out, format.orElseThrow().cacheName());
    }
  }

  private static void putString(DataOutputStream out, String value)
      throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
    if (encoded.length <= 0 || encoded.length > 64) {
      throw new IllegalArgumentException("invalid Metal format name");
    }
    out.writeInt(encoded.length);
    out.write(encoded);
  }
}

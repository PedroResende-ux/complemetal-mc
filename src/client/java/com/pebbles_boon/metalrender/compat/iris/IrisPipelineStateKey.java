package com.pebbles_boon.metalrender.compat.iris;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Content-addressed identity for a shader plus one complete pipeline state. */
public record IrisPipelineStateKey(String sha256) {
  private static final byte[] DOMAIN =
      "metalrender.iris.pipeline-state.v1"
          .getBytes(StandardCharsets.US_ASCII);

  public IrisPipelineStateKey {
    Objects.requireNonNull(sha256, "sha256");
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "sha256 must be 64 lowercase hexadecimal characters");
    }
  }

  public static IrisPipelineStateKey from(IrisShaderCacheKey shaderKey,
      IrisPipelineState state) {
    Objects.requireNonNull(shaderKey, "shaderKey");
    Objects.requireNonNull(state, "state");
    MessageDigest digest = newSha256();
    putBytes(digest, DOMAIN);
    putBytes(digest, canonicalBytes(shaderKey, state));
    return new IrisPipelineStateKey(
        HexFormat.of().formatHex(digest.digest()));
  }

  static byte[] canonicalBytes(IrisShaderCacheKey shaderKey,
      IrisPipelineState state) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
      DataOutputStream output = new DataOutputStream(bytes);
      putString(output, shaderKey.sha256());
      putNamed(output, state.pass().kind());
      putString(output, state.pass().passIdSha256());
      output.writeBoolean(state.pass().fallback());

      output.writeInt(state.vertexBuffers().size());
      for (IrisPipelineState.VertexBufferLayout buffer
          : state.vertexBuffers()) {
        output.writeInt(buffer.bufferIndex());
        output.writeInt(buffer.strideBytes());
        putNamed(output, buffer.stepFunction());
        output.writeInt(buffer.stepRate());
      }
      output.writeInt(state.vertexAttributes().size());
      for (IrisPipelineState.VertexAttribute attribute
          : state.vertexAttributes()) {
        output.writeInt(attribute.location());
        output.writeInt(attribute.bufferIndex());
        output.writeInt(attribute.offsetBytes());
        putString(output, attribute.format().cacheName());
      }
      output.writeInt(state.colorAttachments().size());
      for (IrisPipelineState.ColorAttachment attachment
          : state.colorAttachments()) {
        output.writeInt(attachment.slot());
        putString(output, attachment.format().cacheName());
        output.writeInt(attachment.writeMask());
        putBlend(output, attachment.blend());
      }
      putOptionalFormat(output, state.depthAttachmentFormat());
      putOptionalFormat(output, state.stencilAttachmentFormat());
      output.writeInt(state.rasterSampleCount());
      output.writeLong(state.sampleMask());
      output.writeBoolean(state.sampleCoverageEnabled());
      output.writeInt(
          Float.floatToRawIntBits(state.sampleCoverageValue()));
      output.writeBoolean(state.sampleCoverageInvert());
      output.writeBoolean(state.alphaToCoverage());
      output.writeBoolean(state.alphaToOne());

      output.writeBoolean(state.depth().testEnabled());
      putNamed(output, state.depth().compare());
      output.writeBoolean(state.depth().writeEnabled());

      output.writeBoolean(state.stencil().enabled());
      putStencilFace(output, state.stencil().front());
      putStencilFace(output, state.stencil().back());

      output.writeBoolean(state.raster().rasterizationEnabled());
      putNamed(output, state.raster().cullMode());
      putNamed(output, state.raster().frontFace());
      putNamed(output, state.raster().frontFillMode());
      putNamed(output, state.raster().backFillMode());
      putNamed(output, state.raster().depthClipMode());
      output.writeInt(state.raster().polygonOffsetModeMask());
      output.writeInt(Float.floatToRawIntBits(state.raster().depthBias()));
      output.writeInt(Float.floatToRawIntBits(state.raster().slopeScale()));
      output.writeInt(
          Float.floatToRawIntBits(state.raster().depthBiasClamp()));

      putNamed(output, state.primitive().topology());
      putNamed(output, state.primitive().restartMode());
      output.writeInt(state.primitive().patchControlPoints());
      output.writeBoolean(state.specializationScanned());
      output.writeInt(state.functionConstants().size());
      for (IrisPipelineState.FunctionConstant constant
          : state.functionConstants()) {
        putString(output, constant.stage().cacheName());
        output.writeInt(constant.constantId());
        putNamed(output, constant.type());
        output.writeLong(constant.rawBits());
      }
      output.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "in-memory pipeline-state encoding failed", impossible);
    }
  }

  private static void putBlend(DataOutputStream output,
      IrisPipelineState.BlendState blend) throws IOException {
    output.writeBoolean(blend.enabled());
    putBlendEquation(output, blend.rgb());
    putBlendEquation(output, blend.alpha());
  }

  private static void putBlendEquation(DataOutputStream output,
      IrisPipelineState.BlendEquation equation) throws IOException {
    putNamed(output, equation.operation());
    putNamed(output, equation.source());
    putNamed(output, equation.destination());
  }

  private static void putStencilFace(DataOutputStream output,
      IrisPipelineState.StencilFace face) throws IOException {
    putNamed(output, face.compare());
    putNamed(output, face.stencilFail());
    putNamed(output, face.depthFail());
    putNamed(output, face.pass());
    output.writeInt(face.readMask());
    output.writeInt(face.writeMask());
    output.writeInt(face.reference());
  }

  private static void putOptionalFormat(DataOutputStream output,
      java.util.Optional<IrisPipelineState.DataFormat> format)
      throws IOException {
    output.writeBoolean(format.isPresent());
    if (format.isPresent()) {
      putString(output, format.orElseThrow().cacheName());
    }
  }

  private static void putNamed(DataOutputStream output,
      IrisPipelineState.CacheNamed value) throws IOException {
    putString(output, value.cacheName());
  }

  private static void putString(DataOutputStream output, String value)
      throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeLong(encoded.length);
    output.write(encoded);
  }

  private static void putBytes(MessageDigest digest, byte[] bytes) {
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
    digest.update(bytes);
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "JVM does not provide SHA-256", impossible);
    }
  }
}

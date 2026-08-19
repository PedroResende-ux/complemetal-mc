package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ArrayDimension;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.BaseType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.CacheNamed;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.MatrixType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.SampledImageType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ScalarType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StructMember;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StructType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.TypeRef;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.VectorType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Content-addressed identity of an {@link IrisSpirvResourceLayout}. */
public record IrisSpirvResourceLayoutKey(String sha256) {
  private static final byte[] DOMAIN =
      "metalrender.iris.spirv-resource-layout.v1"
          .getBytes(StandardCharsets.US_ASCII);

  public IrisSpirvResourceLayoutKey {
    Objects.requireNonNull(sha256, "sha256");
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "sha256 must be 64 lowercase hexadecimal characters");
    }
  }

  public static IrisSpirvResourceLayoutKey from(
      IrisSpirvResourceLayout layout) {
    Objects.requireNonNull(layout, "layout");
    MessageDigest digest = newSha256();
    putBytes(digest, DOMAIN);
    putBytes(digest, canonicalBytes(layout));
    return new IrisSpirvResourceLayoutKey(
        HexFormat.of().formatHex(digest.digest()));
  }

  static byte[] canonicalBytes(IrisSpirvResourceLayout layout) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeInt(layout.resources().size());
      for (ResourceBinding resource : layout.resources()) {
        putNamed(output, resource.address().kind());
        output.writeInt(resource.address().descriptorSet());
        output.writeInt(resource.address().index());
        putNamed(output, resource.kind());
        putNamed(output, resource.storageClass());
        putNamed(output, resource.access());
        putTypeRef(output, resource.pointeeType());
      }
      output.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "in-memory resource-layout encoding failed", impossible);
    }
  }

  private static void putTypeRef(DataOutputStream output, TypeRef type)
      throws IOException {
    output.writeInt(type.arrays().size());
    for (ArrayDimension dimension : type.arrays()) {
      putNamed(output, dimension.kind());
      output.writeBoolean(dimension.literalLength().isPresent());
      if (dimension.literalLength().isPresent()) {
        putString(output,
            dimension.literalLength().orElseThrow().toString());
      }
      output.writeInt(dimension.strideBytes());
    }
    putBaseType(output, type.baseType());
  }

  private static void putBaseType(DataOutputStream output, BaseType type)
      throws IOException {
    putNamed(output, type.kind());
    switch (type) {
      case ScalarType scalar -> putScalar(output, scalar);
      case VectorType vector -> {
        putScalar(output, vector.componentType());
        output.writeInt(vector.componentCount());
      }
      case MatrixType matrix -> {
        putScalar(output, matrix.componentType());
        output.writeInt(matrix.rowCount());
        output.writeInt(matrix.columnCount());
      }
      case StructType structure -> {
        output.writeInt(structure.members().size());
        for (StructMember member : structure.members()) {
          putTypeRef(output, member.type());
          output.writeInt(member.offsetBytes());
          output.writeInt(member.matrixStrideBytes());
          putNamed(output, member.matrixMajor());
          output.writeBoolean(member.nonReadable());
          output.writeBoolean(member.nonWritable());
        }
      }
      case ImageType image -> putImage(output, image);
      case IrisSpirvResourceLayout.SamplerType ignored -> {
      }
      case SampledImageType sampled -> putImage(output,
          sampled.imageType());
    }
  }

  private static void putScalar(DataOutputStream output, ScalarType scalar)
      throws IOException {
    putNamed(output, scalar.scalarKind());
    output.writeInt(scalar.widthBits());
  }

  private static void putImage(DataOutputStream output, ImageType image)
      throws IOException {
    output.writeBoolean(image.sampledType().isPresent());
    if (image.sampledType().isPresent()) {
      putScalar(output, image.sampledType().orElseThrow());
    }
    putNamed(output, image.dimension());
    putNamed(output, image.depth());
    output.writeBoolean(image.arrayed());
    output.writeBoolean(image.multisampled());
    putNamed(output, image.sampling());
    putNamed(output, image.format());
    putNamed(output, image.declaredAccess());
  }

  private static void putNamed(DataOutputStream output, CacheNamed value)
      throws IOException {
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

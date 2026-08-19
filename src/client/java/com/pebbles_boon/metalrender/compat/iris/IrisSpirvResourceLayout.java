package com.pebbles_boon.metalrender.compat.iris;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable, API-independent resource layout reflected from one SPIR-V
 * module.
 *
 * <p>SPIR-V result ids and debug names are intentionally absent from
 * {@link ResourceBinding}. Debug names live in a separate map and are ignored
 * by {@link #equals(Object)}, {@link #hashCode()}, and the content-addressed
 * layout key. This makes the layout stable across harmless compiler id and
 * {@code OpName} changes.</p>
 */
public final class IrisSpirvResourceLayout {
  public static final int MAX_RESOURCES = 4_096;
  public static final int MAX_ARRAY_DIMENSIONS = 16;
  public static final int MAX_STRUCT_MEMBERS = 1_024;
  public static final int MAX_TYPE_DEPTH = 64;

  private static final Comparator<ResourceBinding> RESOURCE_ORDER =
      Comparator.comparing(ResourceBinding::address,
              IrisSpirvResourceLayout::compareAddresses)
          .thenComparing(binding -> binding.kind().cacheName())
          .thenComparing(binding -> binding.storageClass().cacheName());

  private final List<ResourceBinding> resources;
  private final Map<ResourceAddress, String> diagnosticNames;
  private final boolean diagnosticNamesComplete;

  public IrisSpirvResourceLayout(List<ResourceBinding> resources,
      Map<ResourceAddress, String> diagnosticNames,
      boolean diagnosticNamesComplete) {
    Objects.requireNonNull(resources, "resources");
    Objects.requireNonNull(diagnosticNames, "diagnosticNames");
    if (resources.size() > MAX_RESOURCES) {
      throw new IllegalArgumentException(
          "resource count exceeds " + MAX_RESOURCES);
    }

    ArrayList<ResourceBinding> sorted = new ArrayList<>(resources.size());
    for (ResourceBinding resource : resources) {
      ResourceBinding checked = Objects.requireNonNull(resource, "resource");
      validateType(checked.pointeeType(), new IdentityHashMap<>(), 0);
      sorted.add(checked);
    }
    sorted.sort(RESOURCE_ORDER);
    for (int index = 1; index < sorted.size(); index++) {
      if (sorted.get(index - 1).address().equals(
          sorted.get(index).address())) {
        throw new IllegalArgumentException(
            "resource addresses must be unique");
      }
    }
    this.resources = List.copyOf(sorted);

    TreeMap<ResourceAddress, String> copiedNames = new TreeMap<>(
        IrisSpirvResourceLayout::compareAddresses);
    for (Map.Entry<ResourceAddress, String> entry
        : diagnosticNames.entrySet()) {
      ResourceAddress address = Objects.requireNonNull(
          entry.getKey(), "diagnostic address");
      String name = Objects.requireNonNull(
          entry.getValue(), "diagnostic name");
      if (name.isBlank()) {
        throw new IllegalArgumentException(
            "diagnostic names must not be blank");
      }
      if (sorted.stream().noneMatch(
          resource -> resource.address().equals(address))) {
        throw new IllegalArgumentException(
            "diagnostic name references an absent resource");
      }
      copiedNames.put(address, name);
    }
    this.diagnosticNames = Collections.unmodifiableMap(copiedNames);
    this.diagnosticNamesComplete = diagnosticNamesComplete;
  }

  public List<ResourceBinding> resources() {
    return resources;
  }

  public Map<ResourceAddress, String> diagnosticNames() {
    return diagnosticNames;
  }

  public Optional<String> diagnosticName(ResourceAddress address) {
    return Optional.ofNullable(diagnosticNames.get(
        Objects.requireNonNull(address, "address")));
  }

  public boolean diagnosticNamesComplete() {
    return diagnosticNamesComplete;
  }

  public IrisSpirvResourceLayoutKey key() {
    return IrisSpirvResourceLayoutKey.from(this);
  }

  /** Diagnostic names and name truncation do not participate in identity. */
  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof IrisSpirvResourceLayout layout
        && resources.equals(layout.resources);
  }

  /** Diagnostic names and name truncation do not participate in identity. */
  @Override
  public int hashCode() {
    return resources.hashCode();
  }

  @Override
  public String toString() {
    return "IrisSpirvResourceLayout[resources=" + resources
        + ", diagnosticNames=" + diagnosticNames
        + ", diagnosticNamesComplete=" + diagnosticNamesComplete + ']';
  }

  private static int compareAddresses(ResourceAddress left,
      ResourceAddress right) {
    int kind = left.kind().cacheName().compareTo(right.kind().cacheName());
    if (kind != 0) {
      return kind;
    }
    int set = Integer.compare(left.descriptorSet(), right.descriptorSet());
    if (set != 0) {
      return set;
    }
    return Integer.compare(left.index(), right.index());
  }

  private static void validateType(TypeRef type,
      IdentityHashMap<Object, Boolean> path, int depth) {
    Objects.requireNonNull(type, "type");
    if (depth > MAX_TYPE_DEPTH) {
      throw new IllegalArgumentException(
          "resource type depth exceeds " + MAX_TYPE_DEPTH);
    }
    if (type.arrays().size() > MAX_ARRAY_DIMENSIONS) {
      throw new IllegalArgumentException(
          "array rank exceeds " + MAX_ARRAY_DIMENSIONS);
    }
    if (path.put(type, Boolean.TRUE) != null) {
      throw new IllegalArgumentException("recursive resource type");
    }
    try {
      if (type.baseType() instanceof StructType structure) {
        if (structure.members().size() > MAX_STRUCT_MEMBERS) {
          throw new IllegalArgumentException(
              "struct member count exceeds " + MAX_STRUCT_MEMBERS);
        }
        for (StructMember member : structure.members()) {
          validateType(member.type(), path, depth + 1);
        }
      }
    } finally {
      path.remove(type);
    }
  }

  public record ResourceBinding(ResourceAddress address, ResourceKind kind,
                                StorageClass storageClass, Access access,
                                TypeRef pointeeType) {
    public ResourceBinding {
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(storageClass, "storageClass");
      Objects.requireNonNull(access, "access");
      Objects.requireNonNull(pointeeType, "pointeeType");
      if (address instanceof UniformLocation
          && kind != ResourceKind.UNIFORM) {
        throw new IllegalArgumentException(
            "only plain uniforms may use a uniform location");
      }
    }

    public BaseType baseType() {
      return pointeeType.baseType();
    }

    public List<ArrayDimension> arrays() {
      return pointeeType.arrays();
    }
  }

  public sealed interface ResourceAddress
      permits DescriptorAddress, UniformLocation {
    AddressKind kind();

    int descriptorSet();

    /** Descriptor binding or OpenGL uniform location. */
    int index();
  }

  public record DescriptorAddress(int descriptorSet, int binding)
      implements ResourceAddress {
    public DescriptorAddress {
      requireAddressComponent(descriptorSet, "descriptor set");
      requireAddressComponent(binding, "binding");
    }

    @Override
    public AddressKind kind() {
      return AddressKind.DESCRIPTOR;
    }

    @Override
    public int index() {
      return binding;
    }
  }

  /**
   * Address of an OpenGL-style non-opaque uniform. The descriptor set is kept
   * because shaderc emits it even when the resource has no Binding decoration.
   */
  public record UniformLocation(int descriptorSet, int location)
      implements ResourceAddress {
    public UniformLocation {
      requireAddressComponent(descriptorSet, "descriptor set");
      requireAddressComponent(location, "uniform location");
    }

    @Override
    public AddressKind kind() {
      return AddressKind.UNIFORM_LOCATION;
    }

    @Override
    public int index() {
      return location;
    }
  }

  public record TypeRef(List<ArrayDimension> arrays, BaseType baseType) {
    public TypeRef {
      Objects.requireNonNull(arrays, "arrays");
      ArrayList<ArrayDimension> copy = new ArrayList<>(arrays.size());
      for (ArrayDimension dimension : arrays) {
        copy.add(Objects.requireNonNull(dimension, "array dimension"));
      }
      arrays = List.copyOf(copy);
      Objects.requireNonNull(baseType, "baseType");
    }

    public static TypeRef scalar(ScalarKind kind, int widthBits) {
      return new TypeRef(List.of(), new ScalarType(kind, widthBits));
    }
  }

  public record ArrayDimension(ArrayKind kind,
                               Optional<BigInteger> literalLength,
                               int strideBytes) {
    private static final BigInteger MAX_LITERAL_LENGTH =
        BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    public ArrayDimension {
      Objects.requireNonNull(kind, "kind");
      literalLength = Objects.requireNonNull(
          literalLength, "literalLength");
      if (strideBytes < -1) {
        throw new IllegalArgumentException("invalid array stride");
      }
      if (kind == ArrayKind.LITERAL) {
        BigInteger value = literalLength.orElseThrow(() ->
            new IllegalArgumentException("literal array length is absent"));
        if (value.signum() <= 0 || value.compareTo(MAX_LITERAL_LENGTH) > 0) {
          throw new IllegalArgumentException("invalid literal array length");
        }
      } else if (literalLength.isPresent()) {
        throw new IllegalArgumentException(
            "runtime arrays cannot have a literal length");
      }
    }

    public static ArrayDimension literal(BigInteger length,
        int strideBytes) {
      return new ArrayDimension(ArrayKind.LITERAL, Optional.of(length),
          strideBytes);
    }

    public static ArrayDimension runtime(int strideBytes) {
      return new ArrayDimension(ArrayKind.RUNTIME, Optional.empty(),
          strideBytes);
    }
  }

  public sealed interface BaseType
      permits ScalarType, VectorType, MatrixType, StructType, ImageType,
      SamplerType, SampledImageType {
    TypeKind kind();
  }

  public record ScalarType(ScalarKind scalarKind, int widthBits)
      implements BaseType {
    public ScalarType {
      Objects.requireNonNull(scalarKind, "scalarKind");
      if (scalarKind == ScalarKind.BOOL) {
        if (widthBits != 1) {
          throw new IllegalArgumentException("bool width must be one bit");
        }
      } else if (widthBits != 8 && widthBits != 16 && widthBits != 32
          && widthBits != 64) {
        throw new IllegalArgumentException("unsupported scalar width");
      }
    }

    @Override
    public TypeKind kind() {
      return TypeKind.SCALAR;
    }
  }

  public record VectorType(ScalarType componentType, int componentCount)
      implements BaseType {
    public VectorType {
      Objects.requireNonNull(componentType, "componentType");
      if (componentCount < 2 || componentCount > 4) {
        throw new IllegalArgumentException("vector width must be 2 through 4");
      }
    }

    @Override
    public TypeKind kind() {
      return TypeKind.VECTOR;
    }
  }

  public record MatrixType(ScalarType componentType, int rowCount,
                           int columnCount) implements BaseType {
    public MatrixType {
      Objects.requireNonNull(componentType, "componentType");
      if (componentType.scalarKind() != ScalarKind.FLOAT
          || rowCount < 2 || rowCount > 4
          || columnCount < 2 || columnCount > 4) {
        throw new IllegalArgumentException("unsupported matrix shape");
      }
    }

    @Override
    public TypeKind kind() {
      return TypeKind.MATRIX;
    }
  }

  public record StructType(List<StructMember> members) implements BaseType {
    public StructType {
      Objects.requireNonNull(members, "members");
      if (members.size() > MAX_STRUCT_MEMBERS) {
        throw new IllegalArgumentException(
            "struct member count exceeds " + MAX_STRUCT_MEMBERS);
      }
      ArrayList<StructMember> copy = new ArrayList<>(members.size());
      for (StructMember member : members) {
        copy.add(Objects.requireNonNull(member, "struct member"));
      }
      members = List.copyOf(copy);
    }

    @Override
    public TypeKind kind() {
      return TypeKind.STRUCT;
    }
  }

  public record StructMember(TypeRef type, int offsetBytes,
                             int matrixStrideBytes, MatrixMajor matrixMajor,
                             boolean nonReadable, boolean nonWritable) {
    public StructMember {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(matrixMajor, "matrixMajor");
      if (offsetBytes < -1 || matrixStrideBytes < -1) {
        throw new IllegalArgumentException("invalid struct member layout");
      }
    }
  }

  public record ImageType(Optional<ScalarType> sampledType,
                          ImageDimension dimension, ImageDepth depth,
                          boolean arrayed, boolean multisampled,
                          ImageSampling sampling, ImageFormat format,
                          DeclaredImageAccess declaredAccess)
      implements BaseType {
    public ImageType {
      sampledType = Objects.requireNonNull(sampledType, "sampledType");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(depth, "depth");
      Objects.requireNonNull(sampling, "sampling");
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(declaredAccess, "declaredAccess");
    }

    @Override
    public TypeKind kind() {
      return TypeKind.IMAGE;
    }
  }

  public record SamplerType() implements BaseType {
    @Override
    public TypeKind kind() {
      return TypeKind.SAMPLER;
    }
  }

  public record SampledImageType(ImageType imageType) implements BaseType {
    public SampledImageType {
      Objects.requireNonNull(imageType, "imageType");
    }

    @Override
    public TypeKind kind() {
      return TypeKind.SAMPLED_IMAGE;
    }
  }

  public interface CacheNamed {
    String cacheName();
  }

  public enum AddressKind implements CacheNamed {
    DESCRIPTOR("descriptor"),
    UNIFORM_LOCATION("uniform-location");

    private final String cacheName;

    AddressKind(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum ResourceKind implements CacheNamed {
    UNIFORM("uniform"),
    SAMPLED_IMAGE("sampled-image"),
    SAMPLER("sampler"),
    TEXTURE("texture"),
    STORAGE_IMAGE("storage-image"),
    UNIFORM_BUFFER("uniform-buffer"),
    STORAGE_BUFFER("storage-buffer");

    private final String cacheName;

    ResourceKind(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum StorageClass implements CacheNamed {
    UNIFORM_CONSTANT(0, "uniform-constant"),
    UNIFORM(2, "uniform"),
    IMAGE(11, "image"),
    STORAGE_BUFFER(12, "storage-buffer");

    private final int spirvValue;
    private final String cacheName;

    StorageClass(int spirvValue, String cacheName) {
      this.spirvValue = spirvValue;
      this.cacheName = cacheName;
    }

    public int spirvValue() {
      return spirvValue;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum Access implements CacheNamed {
    READ_ONLY("read-only"),
    WRITE_ONLY("write-only"),
    READ_WRITE("read-write");

    private final String cacheName;

    Access(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum ArrayKind implements CacheNamed {
    LITERAL("literal"),
    RUNTIME("runtime");

    private final String cacheName;

    ArrayKind(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum TypeKind implements CacheNamed {
    SCALAR("scalar"),
    VECTOR("vector"),
    MATRIX("matrix"),
    STRUCT("struct"),
    IMAGE("image"),
    SAMPLER("sampler"),
    SAMPLED_IMAGE("sampled-image");

    private final String cacheName;

    TypeKind(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum ScalarKind implements CacheNamed {
    BOOL("bool"),
    SIGNED_INT("sint"),
    UNSIGNED_INT("uint"),
    FLOAT("float");

    private final String cacheName;

    ScalarKind(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum MatrixMajor implements CacheNamed {
    NONE("none"),
    ROW_MAJOR("row-major"),
    COLUMN_MAJOR("column-major");

    private final String cacheName;

    MatrixMajor(String cacheName) {
      this.cacheName = cacheName;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum ImageDimension implements CacheNamed {
    D1(0, "1d"),
    D2(1, "2d"),
    D3(2, "3d"),
    CUBE(3, "cube"),
    RECT(4, "rect"),
    BUFFER(5, "buffer"),
    SUBPASS_DATA(6, "subpass-data");

    private final int spirvValue;
    private final String cacheName;

    ImageDimension(int spirvValue, String cacheName) {
      this.spirvValue = spirvValue;
      this.cacheName = cacheName;
    }

    public int spirvValue() {
      return spirvValue;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum ImageDepth implements CacheNamed {
    NON_DEPTH(0, "non-depth"),
    DEPTH(1, "depth"),
    UNKNOWN(2, "unknown");

    private final int spirvValue;
    private final String cacheName;

    ImageDepth(int spirvValue, String cacheName) {
      this.spirvValue = spirvValue;
      this.cacheName = cacheName;
    }

    public int spirvValue() {
      return spirvValue;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum ImageSampling implements CacheNamed {
    UNKNOWN(0, "unknown"),
    SAMPLED(1, "sampled"),
    STORAGE(2, "storage");

    private final int spirvValue;
    private final String cacheName;

    ImageSampling(int spirvValue, String cacheName) {
      this.spirvValue = spirvValue;
      this.cacheName = cacheName;
    }

    public int spirvValue() {
      return spirvValue;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  public enum DeclaredImageAccess implements CacheNamed {
    NONE(-1, "none"),
    READ_ONLY(0, "read-only"),
    WRITE_ONLY(1, "write-only"),
    READ_WRITE(2, "read-write");

    private final int spirvValue;
    private final String cacheName;

    DeclaredImageAccess(int spirvValue, String cacheName) {
      this.spirvValue = spirvValue;
      this.cacheName = cacheName;
    }

    public int spirvValue() {
      return spirvValue;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  /** Core SPIR-V image formats supported by the Metal translation path. */
  public enum ImageFormat implements CacheNamed {
    UNKNOWN(0, "unknown"),
    RGBA32F(1, "rgba32f"),
    RGBA16F(2, "rgba16f"),
    R32F(3, "r32f"),
    RGBA8(4, "rgba8"),
    RGBA8_SNORM(5, "rgba8-snorm"),
    RG32F(6, "rg32f"),
    RG16F(7, "rg16f"),
    R11F_G11F_B10F(8, "r11f-g11f-b10f"),
    R16F(9, "r16f"),
    RGBA16(10, "rgba16"),
    RGB10_A2(11, "rgb10-a2"),
    RG16(12, "rg16"),
    RG8(13, "rg8"),
    R16(14, "r16"),
    R8(15, "r8"),
    RGBA16_SNORM(16, "rgba16-snorm"),
    RG16_SNORM(17, "rg16-snorm"),
    RG8_SNORM(18, "rg8-snorm"),
    R16_SNORM(19, "r16-snorm"),
    R8_SNORM(20, "r8-snorm"),
    RGBA32I(21, "rgba32i"),
    RGBA16I(22, "rgba16i"),
    RGBA8I(23, "rgba8i"),
    R32I(24, "r32i"),
    RG32I(25, "rg32i"),
    RG16I(26, "rg16i"),
    RG8I(27, "rg8i"),
    R16I(28, "r16i"),
    R8I(29, "r8i"),
    RGBA32UI(30, "rgba32ui"),
    RGBA16UI(31, "rgba16ui"),
    RGBA8UI(32, "rgba8ui"),
    R32UI(33, "r32ui"),
    RGB10_A2UI(34, "rgb10-a2ui"),
    RG32UI(35, "rg32ui"),
    RG16UI(36, "rg16ui"),
    RG8UI(37, "rg8ui"),
    R16UI(38, "r16ui"),
    R8UI(39, "r8ui"),
    R64UI(40, "r64ui"),
    R64I(41, "r64i");

    private final int spirvValue;
    private final String cacheName;

    ImageFormat(int spirvValue, String cacheName) {
      this.spirvValue = spirvValue;
      this.cacheName = cacheName;
    }

    public int spirvValue() {
      return spirvValue;
    }

    @Override
    public String cacheName() {
      return cacheName;
    }
  }

  private static void requireAddressComponent(int value, String name) {
    if (value < 0 || value > 1_048_575) {
      throw new IllegalArgumentException("invalid " + name);
    }
  }
}

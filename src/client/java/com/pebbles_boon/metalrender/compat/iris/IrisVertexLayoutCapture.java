package com.pebbles_boon.metalrender.compat.iris;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

/** Converts Mojang's immutable vertex format into cache-safe plain data. */
public final class IrisVertexLayoutCapture {
  private static final Set<String> IRIS_PREFIXED_SEMANTICS = Set.of(
      "Position", "Color", "Normal", "UV0", "UV1", "UV2",
      "LineWidth");

  private IrisVertexLayoutCapture() {
  }

  /**
   * Resolves the physical Metal vertex format against the exact final GLSL
   * input type. Mojang's format name can retain a normalized tag even when
   * Iris links the attribute through the integer OpenGL path. Metal requires
   * an integer vertex format for an MSL integer stage input, but the raw bytes,
   * vector width, buffer stride, and attribute offset stay unchanged.
   */
  static Layout resolveShaderInputFormats(String vertexSource,
      Layout captured) {
    Objects.requireNonNull(vertexSource, "vertexSource");
    Objects.requireNonNull(captured, "captured");
    String sourceWithoutComments = stripComments(vertexSource);
    ArrayList<ShaderInput> inputs = new ArrayList<>(
        captured.shaderInputs().size());
    ArrayList<IrisPipelineState.VertexAttribute> attributes =
        new ArrayList<>(captured.attributes());
    for (ShaderInput input : captured.shaderInputs()) {
      IrisPipelineState.DataFormat resolved = resolveFormat(
          sourceWithoutComments, input);
      inputs.add(new ShaderInput(input.linkedName(), input.location(),
          resolved));
      if (!resolved.equals(input.format())) {
        boolean replaced = false;
        for (int index = 0; index < attributes.size(); index++) {
          IrisPipelineState.VertexAttribute attribute = attributes.get(index);
          if (attribute.location() == input.location()) {
            attributes.set(index, new IrisPipelineState.VertexAttribute(
                attribute.location(), attribute.bufferIndex(),
                attribute.offsetBytes(), resolved));
            replaced = true;
            break;
          }
        }
        if (!replaced) {
          throw new IllegalArgumentException(
              "vertex shader input location has no captured attribute");
        }
      }
    }
    return new Layout(captured.buffers(), attributes, inputs);
  }

  public static Layout capture(VertexFormat format) {
    return capture(format, false);
  }

  /**
   * Replaces guessed Iris attribute names with the active attributes from the
   * successfully linked OpenGL program. This is the authoritative post-link
   * name/location set and also removes inactive format elements from the
   * future Metal vertex descriptor.
   */
  public static Layout captureLinked(int glProgram, VertexFormat format,
      boolean fallback) {
    if (glProgram <= 0) {
      throw new IllegalArgumentException("OpenGL program must be positive");
    }
    Layout captured = capture(format, fallback);
    int count = GL20C.glGetProgrami(glProgram, GL20C.GL_ACTIVE_ATTRIBUTES);
    int maxNameLength = GL20C.glGetProgrami(glProgram,
        GL20C.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH);
    if (count < 0 || count > IrisPipelineState.MAX_VERTEX_ATTRIBUTES
        || count > 0 && maxNameLength <= 0) {
      throw new IllegalArgumentException(
          "invalid linked OpenGL attribute metadata");
    }
    ArrayList<LinkedAttribute> linked = new ArrayList<>(count);
    try (MemoryStack stack = MemoryStack.stackPush()) {
      IntBuffer size = stack.mallocInt(1);
      IntBuffer type = stack.mallocInt(1);
      for (int index = 0; index < count; index++) {
        size.clear();
        type.clear();
        String name = GL20C.glGetActiveAttrib(glProgram, index,
            maxNameLength, size, type);
        if (name == null || name.isBlank() || size.get(0) != 1) {
          throw new IllegalArgumentException(
              "unsupported linked OpenGL vertex attribute");
        }
        if (name.endsWith("[0]")) {
          name = name.substring(0, name.length() - 3);
        }
        int location = GL20C.glGetAttribLocation(glProgram, name);
        linked.add(new LinkedAttribute(name, location, type.get(0)));
      }
    }
    return withLinkedAttributes(captured, linked);
  }

  static Layout withLinkedAttributes(Layout captured,
      List<LinkedAttribute> linkedAttributes) {
    Objects.requireNonNull(captured, "captured");
    Objects.requireNonNull(linkedAttributes, "linkedAttributes");
    Map<Integer, IrisPipelineState.VertexAttribute> byLocation =
        new HashMap<>();
    Map<String, IrisPipelineState.VertexAttribute> byLinkedName =
        new HashMap<>();
    for (IrisPipelineState.VertexAttribute attribute
        : captured.attributes()) {
      byLocation.put(attribute.location(), attribute);
    }
    for (ShaderInput input : captured.shaderInputs()) {
      IrisPipelineState.VertexAttribute physical =
          byLocation.get(input.location());
      if (physical == null
          || byLinkedName.put(input.linkedName(), physical) != null) {
        throw new IllegalArgumentException(
            "captured attribute name mapping is invalid");
      }
    }
    ArrayList<LinkedAttribute> sorted = new ArrayList<>(linkedAttributes);
    sorted.sort(Comparator.comparingInt(LinkedAttribute::location)
        .thenComparing(LinkedAttribute::name));
    ArrayList<IrisPipelineState.VertexAttribute> attributes =
        new ArrayList<>(sorted.size());
    ArrayList<ShaderInput> inputs = new ArrayList<>(sorted.size());
    ArrayList<IrisPipelineState.VertexBufferLayout> buffers =
        new ArrayList<>(captured.buffers());
    int constantBufferIndex = buffers.stream()
        .mapToInt(IrisPipelineState.VertexBufferLayout::bufferIndex)
        .max().orElse(-1) + 1;
    int constantOffset = 0;
    HashSet<Integer> locations = new HashSet<>();
    HashSet<String> names = new HashSet<>();
    for (LinkedAttribute linked : sorted) {
      if (!locations.add(linked.location()) || !names.add(linked.name())) {
        throw new IllegalArgumentException(
            "duplicate linked OpenGL vertex attribute");
      }
      IrisPipelineState.VertexAttribute physical =
          byLinkedName.get(linked.name());
      if (physical == null) {
        physical = byLocation.get(linked.location());
      }
      if (physical == null) {
        // OpenGL permits an active shader attribute whose array is disabled
        // in the bound VAO. Such an input reads the context's generic current
        // value (zero, zero, zero, one by default), so it legitimately has no
        // physical VertexFormat element. Keep it in the shader ABI while
        // omitting it from the Metal vertex descriptor; the native compiler
        // can then either accept Metal's missing-attribute default or reject
        // the variant without corrupting a physical element mapping.
        IrisPipelineState.DataFormat format = linkedDataFormat(
            linked.glType());
        attributes.add(new IrisPipelineState.VertexAttribute(
            linked.location(), constantBufferIndex, constantOffset, format));
        inputs.add(new ShaderInput(linked.name(), linked.location(), format));
        constantOffset += 4 * Float.BYTES;
        continue;
      }
      attributes.add(new IrisPipelineState.VertexAttribute(
          linked.location(), physical.bufferIndex(), physical.offsetBytes(),
          physical.format()));
      inputs.add(new ShaderInput(linked.name(), linked.location(),
          physical.format()));
    }
    if (constantOffset > 0) {
      if (constantBufferIndex >= IrisPipelineState.MAX_VERTEX_BUFFERS) {
        throw new IllegalArgumentException(
            "generic OpenGL attributes exhaust Metal buffer slots");
      }
      buffers.add(new IrisPipelineState.VertexBufferLayout(
          constantBufferIndex, constantOffset,
          IrisPipelineState.StepFunction.CONSTANT, 0));
    }
    return new Layout(buffers, attributes, inputs);
  }

  static IrisPipelineState.DataFormat linkedDataFormat(int glType) {
    return new IrisPipelineState.DataFormat(switch (glType) {
      case 0x1406 -> "r32-float";       // GL_FLOAT
      case 0x8B50 -> "rg32-float";     // GL_FLOAT_VEC2
      case 0x8B51 -> "rgb32-float";    // GL_FLOAT_VEC3
      case 0x8B52 -> "rgba32-float";   // GL_FLOAT_VEC4
      case 0x1404 -> "r32-sint";        // GL_INT
      case 0x8B53 -> "rg32-sint";      // GL_INT_VEC2
      case 0x8B54 -> "rgb32-sint";     // GL_INT_VEC3
      case 0x8B55 -> "rgba32-sint";    // GL_INT_VEC4
      case 0x1405 -> "r32-uint";        // GL_UNSIGNED_INT
      case 0x8DC6 -> "rg32-uint";      // GL_UNSIGNED_INT_VEC2
      case 0x8DC7 -> "rgb32-uint";     // GL_UNSIGNED_INT_VEC3
      case 0x8DC8 -> "rgba32-uint";    // GL_UNSIGNED_INT_VEC4
      default -> throw new IllegalArgumentException(
          "unsupported linked OpenGL attribute type "
              + Integer.toHexString(glType));
    });
  }

  /**
   * Captures the same attribute locations and linked names used by Iris'
   * {@code VertexFormatExtension.bindAttributesIris} implementation.
   */
  public static Layout capture(VertexFormat format, boolean fallback) {
    Objects.requireNonNull(format, "format");
    int stepRate = format.getStepRate();
    IrisPipelineState.StepFunction stepFunction = stepRate == 0
        ? IrisPipelineState.StepFunction.PER_VERTEX
        : IrisPipelineState.StepFunction.PER_INSTANCE;
    List<IrisPipelineState.VertexBufferLayout> buffers = List.of(
        new IrisPipelineState.VertexBufferLayout(0, format.getVertexSize(),
            stepFunction, stepRate));
    ArrayList<IrisPipelineState.VertexAttribute> attributes =
        new ArrayList<>();
    ArrayList<ShaderInput> shaderInputs = new ArrayList<>();
    HashSet<String> linkedNames = new HashSet<>();
    List<VertexFormatElement> elements = format.getElements();
    for (int location = 0; location < elements.size(); location++) {
      VertexFormatElement element = elements.get(location);
      IrisPipelineState.DataFormat dataFormat =
          new IrisPipelineState.DataFormat(
              formatCacheName(element.format().name()));
      attributes.add(new IrisPipelineState.VertexAttribute(location, 0,
          element.offset(), dataFormat));
      String linkedName = linkedAttributeName(element.name(), fallback);
      if (!linkedNames.add(linkedName)) {
        throw new IllegalArgumentException(
            "duplicate linked vertex attribute name");
      }
      shaderInputs.add(new ShaderInput(linkedName, location, dataFormat));
    }
    return new Layout(buffers, attributes, shaderInputs);
  }

  static String formatCacheName(String enumName) {
    return Objects.requireNonNull(enumName, "enumName")
        .toLowerCase(Locale.ROOT).replace('_', '-');
  }

  static String linkedAttributeName(String semanticName, boolean fallback) {
    Objects.requireNonNull(semanticName, "semanticName");
    if (!fallback && IRIS_PREFIXED_SEMANTICS.contains(semanticName)) {
      return "iris_" + semanticName;
    }
    return semanticName;
  }

  private static IrisPipelineState.DataFormat resolveFormat(String source,
      ShaderInput input) {
    String type = declaredInputType(source, input.linkedName());
    boolean unsigned = "uint".equals(type) || type.startsWith("uvec");
    boolean signed = "int".equals(type) || type.startsWith("ivec");
    if (!unsigned && !signed) {
      return input.format();
    }
    String format = input.format().cacheName();
    if (format.endsWith("-unorm") || format.endsWith("-snorm")) {
      int suffix = format.lastIndexOf('-');
      return new IrisPipelineState.DataFormat(
          format.substring(0, suffix) + (unsigned ? "-uint" : "-sint"));
    }
    return input.format();
  }

  private static String declaredInputType(String source, String linkedName) {
    Pattern declaration = Pattern.compile(
        "(?:^|[;{}])\\s*"
            + "(?:layout\\s*\\([^;{}]*\\)\\s*)?"
            + "(?:(?:flat|smooth|noperspective|centroid|sample|patch|"
            + "invariant|precise)\\s+)*"
            + "(?:in|attribute)\\s+"
            + "(?:(?:highp|mediump|lowp)\\s+)?"
            + "([A-Za-z_][A-Za-z0-9_]*)\\s+"
            + Pattern.quote(linkedName) + "\\b",
        Pattern.MULTILINE);
    Matcher matcher = declaration.matcher(source);
    String type = "";
    while (matcher.find()) {
      if (!type.isEmpty() && !type.equals(matcher.group(1))) {
        throw new IllegalArgumentException(
            "vertex shader input has conflicting final GLSL types");
      }
      type = matcher.group(1);
    }
    return type;
  }

  private static String stripComments(String source) {
    StringBuilder clean = new StringBuilder(source.length());
    boolean lineComment = false;
    boolean blockComment = false;
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      char next = index + 1 < source.length()
          ? source.charAt(index + 1) : '\0';
      if (lineComment) {
        if (current == '\n' || current == '\r') {
          lineComment = false;
          clean.append(current);
        } else {
          clean.append(' ');
        }
      } else if (blockComment) {
        if (current == '*' && next == '/') {
          clean.append("  ");
          index++;
          blockComment = false;
        } else {
          clean.append(current == '\n' || current == '\r' ? current : ' ');
        }
      } else if (current == '/' && next == '/') {
        clean.append("  ");
        index++;
        lineComment = true;
      } else if (current == '/' && next == '*') {
        clean.append("  ");
        index++;
        blockComment = true;
      } else {
        clean.append(current);
      }
    }
    return clean.toString();
  }

  public record Layout(
      List<IrisPipelineState.VertexBufferLayout> buffers,
      List<IrisPipelineState.VertexAttribute> attributes,
      List<ShaderInput> shaderInputs) {
    public Layout {
      buffers = List.copyOf(buffers);
      attributes = List.copyOf(attributes);
      shaderInputs = List.copyOf(shaderInputs);
    }
  }

  /** Exact final-GLSL input name associated with one Iris-linked location. */
  public record ShaderInput(String linkedName, int location,
                            IrisPipelineState.DataFormat format) {
    public ShaderInput {
      Objects.requireNonNull(linkedName, "linkedName");
      Objects.requireNonNull(format, "format");
      if (linkedName.isBlank() || linkedName.length() > 256
          || location < 0
          || location >= IrisPipelineState.MAX_VERTEX_ATTRIBUTES) {
        throw new IllegalArgumentException("invalid vertex shader input");
      }
    }
  }

  record LinkedAttribute(String name, int location, int glType) {
    LinkedAttribute(String name, int location) {
      this(name, location, 0x8B52);
    }

    LinkedAttribute {
      Objects.requireNonNull(name, "name");
      if (name.isBlank() || name.length() > 256 || location < 0
          || location >= IrisPipelineState.MAX_VERTEX_ATTRIBUTES
          || glType <= 0) {
        throw new IllegalArgumentException("invalid linked attribute");
      }
    }
  }
}

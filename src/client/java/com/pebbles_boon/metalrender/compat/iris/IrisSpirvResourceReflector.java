package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.Access;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ArrayDimension;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.BaseType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DeclaredImageAccess;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DescriptorAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageDepth;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageDimension;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageSampling;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.MatrixMajor;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.MatrixType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.SampledImageType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.SamplerType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ScalarKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ScalarType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StorageClass;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StructMember;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StructType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.TypeRef;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.UniformLocation;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.VectorType;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic, fail-closed SPIR-V resource reflection with no native or
 * graphics API dependency.
 *
 * <p>The scanner accepts SPIR-V 1.0 through 1.6 in either serialized byte
 * order. It only returns {@link Status#SUCCESS} when every externally bound
 * resource has an unambiguous address, supported type graph, and explicit
 * access model. Valid constructs outside that contract return
 * {@link Status#UNSUPPORTED}; structurally invalid input returns
 * {@link Status#MALFORMED}.</p>
 */
public final class IrisSpirvResourceReflector {
  public static final int MAX_MODULE_BYTES = 16 * 1024 * 1024;
  public static final int MAX_ID_BOUND = 262_144;
  public static final int MAX_INSTRUCTIONS = 1_000_000;
  public static final int MAX_VARIABLES = 65_536;
  public static final int MAX_MEMBER_DECORATIONS = 65_536;
  public static final int MAX_DIAGNOSTIC_NAME_BYTES = 1 * 1024 * 1024;
  public static final int MAX_SINGLE_DIAGNOSTIC_NAME_BYTES = 4_096;

  private static final int SPIRV_MAGIC = 0x07230203;
  private static final int SPIRV_SWAPPED_MAGIC = 0x03022307;
  private static final int HEADER_WORDS = 5;

  private static final int OP_NAME = 5;
  private static final int OP_TYPE_VOID = 19;
  private static final int OP_TYPE_BOOL = 20;
  private static final int OP_TYPE_INT = 21;
  private static final int OP_TYPE_FLOAT = 22;
  private static final int OP_TYPE_VECTOR = 23;
  private static final int OP_TYPE_MATRIX = 24;
  private static final int OP_TYPE_IMAGE = 25;
  private static final int OP_TYPE_SAMPLER = 26;
  private static final int OP_TYPE_SAMPLED_IMAGE = 27;
  private static final int OP_TYPE_ARRAY = 28;
  private static final int OP_TYPE_RUNTIME_ARRAY = 29;
  private static final int OP_TYPE_STRUCT = 30;
  private static final int OP_TYPE_OPAQUE = 31;
  private static final int OP_TYPE_POINTER = 32;
  private static final int OP_TYPE_EVENT = 34;
  private static final int OP_TYPE_DEVICE_EVENT = 35;
  private static final int OP_TYPE_RESERVE_ID = 36;
  private static final int OP_TYPE_QUEUE = 37;
  private static final int OP_TYPE_PIPE = 38;
  private static final int OP_TYPE_FORWARD_POINTER = 39;
  private static final int OP_CONSTANT_TRUE = 41;
  private static final int OP_CONSTANT_FALSE = 42;
  private static final int OP_CONSTANT = 43;
  private static final int OP_SPEC_CONSTANT_TRUE = 48;
  private static final int OP_SPEC_CONSTANT_FALSE = 49;
  private static final int OP_SPEC_CONSTANT = 50;
  private static final int OP_SPEC_CONSTANT_COMPOSITE = 51;
  private static final int OP_SPEC_CONSTANT_OP = 52;
  private static final int OP_VARIABLE = 59;
  private static final int OP_DECORATE = 71;
  private static final int OP_MEMBER_DECORATE = 72;
  private static final int OP_DECORATION_GROUP = 73;
  private static final int OP_GROUP_DECORATE = 74;
  private static final int OP_GROUP_MEMBER_DECORATE = 75;
  private static final int OP_DECORATE_ID = 332;

  private static final int DECORATION_SPEC_ID = 1;
  private static final int DECORATION_BLOCK = 2;
  private static final int DECORATION_BUFFER_BLOCK = 3;
  private static final int DECORATION_ROW_MAJOR = 4;
  private static final int DECORATION_COLUMN_MAJOR = 5;
  private static final int DECORATION_ARRAY_STRIDE = 6;
  private static final int DECORATION_MATRIX_STRIDE = 7;
  private static final int DECORATION_NON_WRITABLE = 24;
  private static final int DECORATION_NON_READABLE = 25;
  private static final int DECORATION_LOCATION = 30;
  private static final int DECORATION_BINDING = 33;
  private static final int DECORATION_DESCRIPTOR_SET = 34;
  private static final int DECORATION_OFFSET = 35;

  private IrisSpirvResourceReflector() {
  }

  public static ReflectionResult reflect(byte[] spirv) {
    if (spirv == null) {
      return ReflectionResult.failure(Status.MALFORMED,
          FailureCode.NULL_INPUT, "SPIR-V byte array is null");
    }
    try {
      return new Scanner(spirv).scan();
    } catch (ScanFailure failure) {
      return ReflectionResult.failure(failure.status, failure.code,
          failure.getMessage());
    } catch (IllegalArgumentException invalidModel) {
      return ReflectionResult.failure(Status.MALFORMED,
          FailureCode.INVALID_RESOURCE,
          "invalid reflected resource model: "
              + safeMessage(invalidModel));
    } catch (ArithmeticException malformedCount) {
      return ReflectionResult.failure(Status.MALFORMED,
          FailureCode.MALFORMED_INSTRUCTION,
          "integer overflow while scanning SPIR-V");
    }
  }

  private static String safeMessage(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank()
        ? failure.getClass().getSimpleName() : message;
  }

  public enum Status {
    SUCCESS,
    MALFORMED,
    UNSUPPORTED
  }

  public enum FailureCode {
    NONE,
    NULL_INPUT,
    TRUNCATED_HEADER,
    UNALIGNED_LENGTH,
    MODULE_LIMIT,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    NON_ZERO_SCHEMA,
    INVALID_ID_BOUND,
    ID_BOUND_LIMIT,
    INSTRUCTION_LIMIT,
    MALFORMED_INSTRUCTION,
    INVALID_ID,
    DUPLICATE_ID,
    CONFLICTING_DECORATION,
    DECORATION_GROUP,
    VARIABLE_LIMIT,
    MEMBER_DECORATION_LIMIT,
    RESOURCE_LIMIT,
    MISSING_TYPE,
    UNSUPPORTED_TYPE,
    TYPE_DEPTH_LIMIT,
    NON_LITERAL_ARRAY_LENGTH,
    INVALID_ARRAY_LENGTH,
    UNSUPPORTED_STORAGE_CLASS,
    MISSING_RESOURCE_ADDRESS,
    DUPLICATE_RESOURCE_ADDRESS,
    UNSUPPORTED_IMAGE,
    CONFLICTING_ACCESS,
    INVALID_RESOURCE
  }

  public record ReflectionResult(Status status,
                                 Optional<IrisSpirvResourceLayout> layout,
                                 FailureCode failureCode, String detail) {
    public ReflectionResult {
      Objects.requireNonNull(status, "status");
      layout = Objects.requireNonNull(layout, "layout");
      Objects.requireNonNull(failureCode, "failureCode");
      detail = Objects.requireNonNull(detail, "detail");
      if (status == Status.SUCCESS
          && (layout.isEmpty() || failureCode != FailureCode.NONE)) {
        throw new IllegalArgumentException("invalid success result");
      }
      if (status != Status.SUCCESS
          && (layout.isPresent() || failureCode == FailureCode.NONE)) {
        throw new IllegalArgumentException("invalid failure result");
      }
    }

    private static ReflectionResult success(
        IrisSpirvResourceLayout layout) {
      return new ReflectionResult(Status.SUCCESS, Optional.of(layout),
          FailureCode.NONE, "ok");
    }

    private static ReflectionResult failure(Status status,
        FailureCode code, String detail) {
      return new ReflectionResult(status, Optional.empty(), code, detail);
    }

    public boolean successful() {
      return status == Status.SUCCESS;
    }
  }

  private static final class Scanner {
    private final byte[] bytes;
    private ByteOrder byteOrder;
    private int wordCount;
    private int idBound;
    private Definition[] definitions;
    private Decorations[] decorations;
    private String[] names;
    private TypeRef[] resolvedTypes;
    private byte[] resolutionState;
    private final List<VariableNode> variables = new ArrayList<>();
    private final Map<Long, MemberDecorations> memberDecorations =
        new HashMap<>();
    private final Map<Integer, Integer> highestDecoratedMember =
        new HashMap<>();
    private int retainedNameBytes;
    private boolean diagnosticNamesComplete = true;

    private Scanner(byte[] bytes) {
      this.bytes = bytes;
    }

    private ReflectionResult scan() throws ScanFailure {
      readHeader();
      readInstructions();
      return ReflectionResult.success(buildLayout());
    }

    private void readHeader() throws ScanFailure {
      if (bytes.length < HEADER_WORDS * Integer.BYTES) {
        throw malformed(FailureCode.TRUNCATED_HEADER,
            "SPIR-V header is truncated");
      }
      if (bytes.length % Integer.BYTES != 0) {
        throw malformed(FailureCode.UNALIGNED_LENGTH,
            "SPIR-V byte length is not word aligned");
      }
      if (bytes.length > MAX_MODULE_BYTES) {
        throw unsupported(FailureCode.MODULE_LIMIT,
            "SPIR-V module exceeds " + MAX_MODULE_BYTES + " bytes");
      }
      int littleMagic = readInt(0, ByteOrder.LITTLE_ENDIAN);
      if (littleMagic == SPIRV_MAGIC) {
        byteOrder = ByteOrder.LITTLE_ENDIAN;
      } else if (littleMagic == SPIRV_SWAPPED_MAGIC) {
        byteOrder = ByteOrder.BIG_ENDIAN;
      } else {
        throw malformed(FailureCode.BAD_MAGIC,
            "invalid SPIR-V magic 0x"
                + Integer.toUnsignedString(littleMagic, 16));
      }
      wordCount = bytes.length / Integer.BYTES;

      int version = word(1);
      int major = (version >>> 16) & 0xFF;
      int minor = (version >>> 8) & 0xFF;
      if (major != 1 || minor > 6 || (version & 0xFF0000FF) != 0) {
        throw unsupported(FailureCode.UNSUPPORTED_VERSION,
            "unsupported SPIR-V version word 0x"
                + Integer.toUnsignedString(version, 16));
      }
      long unsignedBound = Integer.toUnsignedLong(word(3));
      if (unsignedBound <= 1) {
        throw malformed(FailureCode.INVALID_ID_BOUND,
            "SPIR-V id bound must be greater than one");
      }
      if (unsignedBound > MAX_ID_BOUND) {
        throw unsupported(FailureCode.ID_BOUND_LIMIT,
            "SPIR-V id bound exceeds " + MAX_ID_BOUND);
      }
      if (word(4) != 0) {
        throw unsupported(FailureCode.NON_ZERO_SCHEMA,
            "non-zero SPIR-V instruction schema is unsupported");
      }

      idBound = (int) unsignedBound;
      definitions = new Definition[idBound];
      decorations = new Decorations[idBound];
      names = new String[idBound];
      resolvedTypes = new TypeRef[idBound];
      resolutionState = new byte[idBound];
    }

    private void readInstructions() throws ScanFailure {
      int offset = HEADER_WORDS;
      int instructionCount = 0;
      while (offset < wordCount) {
        if (++instructionCount > MAX_INSTRUCTIONS) {
          throw unsupported(FailureCode.INSTRUCTION_LIMIT,
              "SPIR-V instruction count exceeds " + MAX_INSTRUCTIONS);
        }
        int header = word(offset);
        int instructionWords = header >>> 16;
        int opcode = header & 0xFFFF;
        if (instructionWords == 0) {
          throw malformed(FailureCode.MALFORMED_INSTRUCTION,
              "zero-word instruction at word " + offset);
        }
        long end = (long) offset + instructionWords;
        if (end > wordCount) {
          throw malformed(FailureCode.MALFORMED_INSTRUCTION,
              "instruction overruns module at word " + offset);
        }
        readInstruction(opcode, offset, instructionWords);
        offset = (int) end;
      }
    }

    private void readInstruction(int opcode, int offset, int count)
        throws ScanFailure {
      switch (opcode) {
        case OP_NAME -> readName(offset, count);
        case OP_TYPE_VOID -> {
          requireCount(opcode, count, 2);
          define(word(offset + 1), new VoidNode());
        }
        case OP_TYPE_BOOL -> {
          requireCount(opcode, count, 2);
          define(word(offset + 1), new BoolNode());
        }
        case OP_TYPE_INT -> {
          requireCount(opcode, count, 4);
          define(word(offset + 1), new IntNode(word(offset + 2),
              word(offset + 3)));
        }
        case OP_TYPE_FLOAT -> {
          requireCount(opcode, count, 3);
          define(word(offset + 1), new FloatNode(word(offset + 2)));
        }
        case OP_TYPE_VECTOR -> {
          requireCount(opcode, count, 4);
          define(word(offset + 1), new VectorNode(
              checkedId(word(offset + 2)), word(offset + 3)));
        }
        case OP_TYPE_MATRIX -> {
          requireCount(opcode, count, 4);
          define(word(offset + 1), new MatrixNode(
              checkedId(word(offset + 2)), word(offset + 3)));
        }
        case OP_TYPE_IMAGE -> readImageType(offset, count);
        case OP_TYPE_SAMPLER -> {
          requireCount(opcode, count, 2);
          define(word(offset + 1), new SamplerNode());
        }
        case OP_TYPE_SAMPLED_IMAGE -> {
          requireCount(opcode, count, 3);
          define(word(offset + 1), new SampledImageNode(
              checkedId(word(offset + 2))));
        }
        case OP_TYPE_ARRAY -> {
          requireCount(opcode, count, 4);
          define(word(offset + 1), new ArrayNode(
              checkedId(word(offset + 2)),
              checkedId(word(offset + 3)), false));
        }
        case OP_TYPE_RUNTIME_ARRAY -> {
          requireCount(opcode, count, 3);
          define(word(offset + 1), new ArrayNode(
              checkedId(word(offset + 2)), 0, true));
        }
        case OP_TYPE_STRUCT -> readStructType(offset, count);
        case OP_TYPE_OPAQUE, OP_TYPE_EVENT, OP_TYPE_DEVICE_EVENT,
            OP_TYPE_RESERVE_ID, OP_TYPE_QUEUE, OP_TYPE_PIPE -> {
          int minimum = opcode == OP_TYPE_OPAQUE ? 3 : 2;
          if (count < minimum) {
            throw malformed(FailureCode.MALFORMED_INSTRUCTION,
                "truncated unsupported type instruction");
          }
          define(word(offset + 1), new UnsupportedTypeNode(opcode));
        }
        case OP_TYPE_POINTER -> {
          requireCount(opcode, count, 4);
          define(word(offset + 1), new PointerNode(word(offset + 2),
              checkedId(word(offset + 3))));
        }
        case OP_TYPE_FORWARD_POINTER -> {
          requireCount(opcode, count, 3);
          checkedId(word(offset + 1));
        }
        case OP_CONSTANT_TRUE, OP_CONSTANT_FALSE -> {
          requireCount(opcode, count, 3);
          define(word(offset + 2), new UnsupportedConstantNode(opcode));
          checkedId(word(offset + 1));
        }
        case OP_CONSTANT, OP_SPEC_CONSTANT -> {
          if (count < 4) {
            throw malformed(FailureCode.MALFORMED_INSTRUCTION,
                "truncated scalar constant");
          }
          int typeId = checkedId(word(offset + 1));
          define(word(offset + 2), new ConstantNode(typeId,
              opcode == OP_SPEC_CONSTANT, offset + 3, count - 3));
        }
        case OP_SPEC_CONSTANT_TRUE, OP_SPEC_CONSTANT_FALSE -> {
          requireCount(opcode, count, 3);
          checkedId(word(offset + 1));
          define(word(offset + 2), new UnsupportedConstantNode(opcode));
        }
        case OP_SPEC_CONSTANT_COMPOSITE, OP_SPEC_CONSTANT_OP -> {
          if (count < 4) {
            throw malformed(FailureCode.MALFORMED_INSTRUCTION,
                "truncated specialization constant");
          }
          checkedId(word(offset + 1));
          define(word(offset + 2), new UnsupportedConstantNode(opcode));
        }
        case OP_VARIABLE -> readVariable(offset, count);
        case OP_DECORATE -> readDecoration(offset, count);
        case OP_MEMBER_DECORATE -> readMemberDecoration(offset, count);
        case OP_DECORATION_GROUP, OP_GROUP_DECORATE,
            OP_GROUP_MEMBER_DECORATE, OP_DECORATE_ID ->
            throw unsupported(FailureCode.DECORATION_GROUP,
                "id/group decorations are outside the reflection contract");
        default -> {
          // Instructions unrelated to resource declarations are intentionally
          // skipped after their framing has been validated.
        }
      }
    }

    private void readImageType(int offset, int count) throws ScanFailure {
      if (count != 9 && count != 10) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "OpTypeImage must contain 9 or 10 words");
      }
      define(word(offset + 1), new ImageNode(
          checkedId(word(offset + 2)), word(offset + 3),
          word(offset + 4), word(offset + 5), word(offset + 6),
          word(offset + 7), word(offset + 8),
          count == 10 ? word(offset + 9) : -1));
    }

    private void readStructType(int offset, int count) throws ScanFailure {
      if (count < 2) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "truncated OpTypeStruct");
      }
      int memberCount = count - 2;
      if (memberCount > IrisSpirvResourceLayout.MAX_STRUCT_MEMBERS) {
        throw unsupported(FailureCode.RESOURCE_LIMIT,
            "struct member count exceeds "
                + IrisSpirvResourceLayout.MAX_STRUCT_MEMBERS);
      }
      int[] members = new int[memberCount];
      for (int index = 0; index < memberCount; index++) {
        members[index] = checkedId(word(offset + 2 + index));
      }
      define(word(offset + 1), new StructNode(members));
    }

    private void readVariable(int offset, int count) throws ScanFailure {
      if (count != 4 && count != 5) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "OpVariable must contain 4 or 5 words");
      }
      if (variables.size() >= MAX_VARIABLES) {
        throw unsupported(FailureCode.VARIABLE_LIMIT,
            "SPIR-V variable count exceeds " + MAX_VARIABLES);
      }
      int typeId = checkedId(word(offset + 1));
      int resultId = checkedId(word(offset + 2));
      VariableNode variable = new VariableNode(typeId, resultId,
          word(offset + 3));
      define(resultId, variable);
      variables.add(variable);
      if (count == 5) {
        checkedId(word(offset + 4));
      }
    }

    private void readName(int offset, int count) throws ScanFailure {
      if (count < 3) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "truncated OpName");
      }
      int target = checkedId(word(offset + 1));
      DecodedString decoded = decodeString(offset + 2, count - 2);
      if (decoded.value != null) {
        names[target] = decoded.value;
      }
    }

    private DecodedString decodeString(int startWord, int words)
        throws ScanFailure {
      int byteCount = 0;
      boolean terminated = false;
      boolean nonZeroPadding = false;
      for (int wordIndex = 0; wordIndex < words; wordIndex++) {
        int value = word(startWord + wordIndex);
        for (int shift = 0; shift < 32; shift += 8) {
          int next = (value >>> shift) & 0xFF;
          if (!terminated) {
            if (next == 0) {
              terminated = true;
            } else {
              byteCount++;
            }
          } else if (next != 0) {
            nonZeroPadding = true;
          }
        }
      }
      if (!terminated || nonZeroPadding) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "invalid SPIR-V literal string");
      }
      if (byteCount > MAX_SINGLE_DIAGNOSTIC_NAME_BYTES
          || retainedNameBytes + byteCount > MAX_DIAGNOSTIC_NAME_BYTES) {
        diagnosticNamesComplete = false;
        return new DecodedString(null);
      }
      byte[] encoded = new byte[byteCount];
      int write = 0;
      outer:
      for (int wordIndex = 0; wordIndex < words; wordIndex++) {
        int value = word(startWord + wordIndex);
        for (int shift = 0; shift < 32; shift += 8) {
          int next = (value >>> shift) & 0xFF;
          if (next == 0) {
            break outer;
          }
          encoded[write++] = (byte) next;
        }
      }
      try {
        String value = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded)).toString();
        retainedNameBytes += byteCount;
        return new DecodedString(value);
      } catch (CharacterCodingException invalidUtf8) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "invalid UTF-8 in SPIR-V literal string");
      }
    }

    private void readDecoration(int offset, int count) throws ScanFailure {
      if (count < 3) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "truncated OpDecorate");
      }
      int target = checkedId(word(offset + 1));
      int decoration = word(offset + 2);
      Decorations targetDecorations = decorations(target);
      switch (decoration) {
        case DECORATION_SPEC_ID -> targetDecorations.specId =
            decorationValue(opcodeName(OP_DECORATE), decoration, offset,
                count, targetDecorations.specId);
        case DECORATION_BLOCK -> {
          requireCount(OP_DECORATE, count, 3);
          targetDecorations.block = true;
        }
        case DECORATION_BUFFER_BLOCK -> {
          requireCount(OP_DECORATE, count, 3);
          targetDecorations.bufferBlock = true;
        }
        case DECORATION_ROW_MAJOR -> {
          requireCount(OP_DECORATE, count, 3);
          targetDecorations.rowMajor = true;
        }
        case DECORATION_COLUMN_MAJOR -> {
          requireCount(OP_DECORATE, count, 3);
          targetDecorations.columnMajor = true;
        }
        case DECORATION_ARRAY_STRIDE -> targetDecorations.arrayStride =
            decorationValue(opcodeName(OP_DECORATE), decoration, offset,
                count, targetDecorations.arrayStride);
        case DECORATION_NON_WRITABLE -> {
          requireCount(OP_DECORATE, count, 3);
          targetDecorations.nonWritable = true;
        }
        case DECORATION_NON_READABLE -> {
          requireCount(OP_DECORATE, count, 3);
          targetDecorations.nonReadable = true;
        }
        case DECORATION_LOCATION -> targetDecorations.location =
            decorationValue(opcodeName(OP_DECORATE), decoration, offset,
                count, targetDecorations.location);
        case DECORATION_BINDING -> targetDecorations.binding =
            decorationValue(opcodeName(OP_DECORATE), decoration, offset,
                count, targetDecorations.binding);
        case DECORATION_DESCRIPTOR_SET -> targetDecorations.descriptorSet =
            decorationValue(opcodeName(OP_DECORATE), decoration, offset,
                count, targetDecorations.descriptorSet);
        default -> {
          // Decorations such as BuiltIn, Flat, and Location on non-resource
          // values do not alter the reflected resource layout.
        }
      }
      if (targetDecorations.block && targetDecorations.bufferBlock) {
        throw malformed(FailureCode.CONFLICTING_DECORATION,
            "type has both Block and BufferBlock decorations");
      }
      if (targetDecorations.rowMajor && targetDecorations.columnMajor) {
        throw malformed(FailureCode.CONFLICTING_DECORATION,
            "target has both RowMajor and ColMajor decorations");
      }
    }

    private void readMemberDecoration(int offset, int count)
        throws ScanFailure {
      if (count < 4) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "truncated OpMemberDecorate");
      }
      int target = checkedId(word(offset + 1));
      long unsignedMember = Integer.toUnsignedLong(word(offset + 2));
      if (unsignedMember >= IrisSpirvResourceLayout.MAX_STRUCT_MEMBERS) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            "struct member index is outside the supported range");
      }
      int member = (int) unsignedMember;
      highestDecoratedMember.merge(target, member, Math::max);
      long key = memberKey(target, member);
      MemberDecorations targetDecorations = memberDecorations.get(key);
      if (targetDecorations == null) {
        if (memberDecorations.size() >= MAX_MEMBER_DECORATIONS) {
          throw unsupported(FailureCode.MEMBER_DECORATION_LIMIT,
              "member decoration count exceeds "
                  + MAX_MEMBER_DECORATIONS);
        }
        targetDecorations = new MemberDecorations();
        memberDecorations.put(key, targetDecorations);
      }
      int decoration = word(offset + 3);
      switch (decoration) {
        case DECORATION_ROW_MAJOR -> {
          requireCount(OP_MEMBER_DECORATE, count, 4);
          targetDecorations.rowMajor = true;
        }
        case DECORATION_COLUMN_MAJOR -> {
          requireCount(OP_MEMBER_DECORATE, count, 4);
          targetDecorations.columnMajor = true;
        }
        case DECORATION_MATRIX_STRIDE -> targetDecorations.matrixStride =
            memberDecorationValue(decoration, offset, count,
                targetDecorations.matrixStride);
        case DECORATION_NON_WRITABLE -> {
          requireCount(OP_MEMBER_DECORATE, count, 4);
          targetDecorations.nonWritable = true;
        }
        case DECORATION_NON_READABLE -> {
          requireCount(OP_MEMBER_DECORATE, count, 4);
          targetDecorations.nonReadable = true;
        }
        case DECORATION_OFFSET -> targetDecorations.offset =
            memberDecorationValue(decoration, offset, count,
                targetDecorations.offset);
        default -> {
          // BuiltIn and interpolation decorations do not affect resources.
        }
      }
      if (targetDecorations.rowMajor && targetDecorations.columnMajor) {
        throw malformed(FailureCode.CONFLICTING_DECORATION,
            "struct member has both RowMajor and ColMajor decorations");
      }
    }

    private Long decorationValue(String instruction, int decoration,
        int offset, int count, Long oldValue) throws ScanFailure {
      requireCount(OP_DECORATE, count, 4);
      long value = Integer.toUnsignedLong(word(offset + 3));
      if (oldValue != null && oldValue != value) {
        throw malformed(FailureCode.CONFLICTING_DECORATION,
            instruction + " contains conflicting decoration " + decoration);
      }
      return value;
    }

    private Long memberDecorationValue(int decoration, int offset, int count,
        Long oldValue) throws ScanFailure {
      requireCount(OP_MEMBER_DECORATE, count, 5);
      long value = Integer.toUnsignedLong(word(offset + 4));
      if (oldValue != null && oldValue != value) {
        throw malformed(FailureCode.CONFLICTING_DECORATION,
            "OpMemberDecorate contains conflicting decoration "
                + decoration);
      }
      return value;
    }

    private IrisSpirvResourceLayout buildLayout() throws ScanFailure {
      ArrayList<ResourceBinding> resources = new ArrayList<>();
      HashMap<ResourceAddress, String> diagnosticNames = new HashMap<>();
      Set<ResourceAddress> addresses = new HashSet<>();
      for (VariableNode variable : variables) {
        Decorations variableDecorations = decorationOrEmpty(
            variable.resultId);
        boolean hasDescriptorDecoration =
            variableDecorations.binding != null
                || variableDecorations.descriptorSet != null;
        StorageClass storageClass = storageClass(variable.storageClass);
        if (storageClass == null) {
          if (hasDescriptorDecoration) {
            throw unsupported(FailureCode.UNSUPPORTED_STORAGE_CLASS,
                "resource id " + variable.resultId
                    + " uses storage class "
                    + Integer.toUnsignedString(variable.storageClass));
          }
          continue;
        }
        if (resources.size() >= IrisSpirvResourceLayout.MAX_RESOURCES) {
          throw unsupported(FailureCode.RESOURCE_LIMIT,
              "resource count exceeds "
                  + IrisSpirvResourceLayout.MAX_RESOURCES);
        }

        Definition resultType = definition(variable.resultTypeId);
        if (!(resultType instanceof PointerNode pointer)) {
          throw malformed(FailureCode.INVALID_RESOURCE,
              "resource variable result type is not OpTypePointer");
        }
        if (pointer.storageClass != variable.storageClass) {
          throw malformed(FailureCode.INVALID_RESOURCE,
              "resource variable and pointer storage classes differ");
        }
        TypeRef pointee = resolveType(pointer.pointeeTypeId, 0);
        int baseTypeId = baseTypeId(pointer.pointeeTypeId);
        Decorations pointerDecorations = decorationOrEmpty(
            variable.resultTypeId);
        Decorations baseDecorations = decorationOrEmpty(baseTypeId);
        ResourceKind kind = classifyResource(storageClass, pointee,
            baseDecorations, variable.resultId);
        ResourceAddress address = resourceAddress(kind,
            variableDecorations, variable.resultId);
        Access access = resourceAccess(kind, pointee,
            variableDecorations.nonReadable
                || pointerDecorations.nonReadable
                || baseDecorations.nonReadable,
            variableDecorations.nonWritable
                || pointerDecorations.nonWritable
                || baseDecorations.nonWritable,
            variable.resultId);
        ResourceBinding resource = new ResourceBinding(address, kind,
            storageClass, access, pointee);
        if (!addresses.add(address)) {
          throw malformed(FailureCode.DUPLICATE_RESOURCE_ADDRESS,
              "multiple resources use " + address);
        }
        resources.add(resource);
        /*
         * OpenGL identifies interface blocks by the block/type name, not by
         * the optional GLSL instance name. shaderc emits both as OpName when
         * an instance exists, and emits only the struct name for anonymous
         * blocks. Prefer the struct name for UBO parity with
         * glGetUniformBlockIndex; all other resources retain the variable
         * name used by glGetUniformLocation.
         */
        String name = kind == ResourceKind.UNIFORM_BUFFER
            ? names[baseTypeId] : names[variable.resultId];
        if ((name == null || name.isBlank())
            && kind == ResourceKind.UNIFORM_BUFFER) {
          name = names[variable.resultId];
        }
        if (name != null && !name.isBlank()) {
          diagnosticNames.put(address, name);
        }
      }
      return new IrisSpirvResourceLayout(resources, diagnosticNames,
          diagnosticNamesComplete);
    }

    private StorageClass storageClass(int raw) {
      for (StorageClass value : StorageClass.values()) {
        if (value.spirvValue() == raw) {
          return value;
        }
      }
      return null;
    }

    private ResourceKind classifyResource(StorageClass storageClass,
        TypeRef pointee, Decorations baseDecorations, int resourceId)
        throws ScanFailure {
      BaseType base = pointee.baseType();
      return switch (storageClass) {
        case UNIFORM_CONSTANT -> switch (base) {
          case SamplerType ignored -> ResourceKind.SAMPLER;
          case SampledImageType ignored -> ResourceKind.SAMPLED_IMAGE;
          case ImageType image -> switch (image.sampling()) {
            case SAMPLED -> ResourceKind.TEXTURE;
            case STORAGE -> ResourceKind.STORAGE_IMAGE;
            case UNKNOWN -> throw unsupported(FailureCode.UNSUPPORTED_IMAGE,
                "resource id " + resourceId
                    + " has an ambiguous image sampling mode");
          };
          default -> ResourceKind.UNIFORM;
        };
        case UNIFORM -> {
          if (baseDecorations.block) {
            if (!(base instanceof StructType)) {
              throw malformed(FailureCode.INVALID_RESOURCE,
                  "Block resource base type is not a struct");
            }
            yield ResourceKind.UNIFORM_BUFFER;
          }
          if (baseDecorations.bufferBlock) {
            if (!(base instanceof StructType)) {
              throw malformed(FailureCode.INVALID_RESOURCE,
                  "BufferBlock resource base type is not a struct");
            }
            yield ResourceKind.STORAGE_BUFFER;
          }
          yield ResourceKind.UNIFORM;
        }
        case STORAGE_BUFFER -> {
          if (!(base instanceof StructType) || !baseDecorations.block) {
            throw unsupported(FailureCode.INVALID_RESOURCE,
                "StorageBuffer resource id " + resourceId
                    + " is not a Block-decorated struct");
          }
          yield ResourceKind.STORAGE_BUFFER;
        }
        case IMAGE -> {
          if (!(base instanceof ImageType image)
              || image.sampling() != ImageSampling.STORAGE) {
            throw unsupported(FailureCode.UNSUPPORTED_IMAGE,
                "Image storage-class resource id " + resourceId
                    + " is not a storage image");
          }
          yield ResourceKind.STORAGE_IMAGE;
        }
      };
    }

    private ResourceAddress resourceAddress(ResourceKind kind,
        Decorations decorations, int resourceId) throws ScanFailure {
      int descriptorSet = checkedAddressValue(decorations.descriptorSet,
          0, "descriptor set", resourceId);
      if (decorations.binding != null) {
        int binding = checkedAddressValue(decorations.binding, -1,
            "binding", resourceId);
        return new DescriptorAddress(descriptorSet, binding);
      }
      if (kind == ResourceKind.UNIFORM && decorations.location != null) {
        int location = checkedAddressValue(decorations.location, -1,
            "uniform location", resourceId);
        return new UniformLocation(descriptorSet, location);
      }
      throw unsupported(FailureCode.MISSING_RESOURCE_ADDRESS,
          "resource id " + resourceId
              + " has neither Binding nor a plain-uniform Location");
    }

    private int checkedAddressValue(Long value, int defaultValue,
        String label, int resourceId) throws ScanFailure {
      if (value == null) {
        return defaultValue;
      }
      if (value > 1_048_575L) {
        throw unsupported(FailureCode.INVALID_RESOURCE,
            label + " exceeds the hard bound for resource id "
                + resourceId);
      }
      return value.intValue();
    }

    private Access resourceAccess(ResourceKind kind, TypeRef pointee,
        boolean nonReadable, boolean nonWritable, int resourceId)
        throws ScanFailure {
      boolean readable = false;
      boolean writable = false;
      if (kind == ResourceKind.STORAGE_BUFFER) {
        readable = true;
        writable = true;
      } else if (kind == ResourceKind.STORAGE_IMAGE) {
        ImageType image = imageType(pointee.baseType());
        switch (image.declaredAccess()) {
          case NONE, READ_WRITE -> {
            readable = true;
            writable = true;
          }
          case READ_ONLY -> {
            readable = true;
            writable = false;
          }
          case WRITE_ONLY -> {
            readable = false;
            writable = true;
          }
        }
      } else {
        readable = true;
        writable = false;
      }
      if (nonReadable) {
        readable = false;
      }
      if (nonWritable) {
        writable = false;
      }
      if (!readable && !writable) {
        throw unsupported(FailureCode.CONFLICTING_ACCESS,
            "resource id " + resourceId
                + " has no permitted read or write access");
      }
      if (readable && writable) {
        return Access.READ_WRITE;
      }
      return readable ? Access.READ_ONLY : Access.WRITE_ONLY;
    }

    private ImageType imageType(BaseType base) throws ScanFailure {
      if (base instanceof ImageType image) {
        return image;
      }
      if (base instanceof SampledImageType sampled) {
        return sampled.imageType();
      }
      throw malformed(FailureCode.INVALID_RESOURCE,
          "image resource has a non-image base type");
    }

    private int baseTypeId(int typeId) throws ScanFailure {
      int current = checkedId(typeId);
      for (int depth = 0;
          depth <= IrisSpirvResourceLayout.MAX_ARRAY_DIMENSIONS; depth++) {
        Definition definition = definition(current);
        if (definition instanceof ArrayNode array) {
          current = array.elementTypeId;
        } else {
          return current;
        }
      }
      throw unsupported(FailureCode.TYPE_DEPTH_LIMIT,
          "resource array rank exceeds "
              + IrisSpirvResourceLayout.MAX_ARRAY_DIMENSIONS);
    }

    private TypeRef resolveType(int typeId, int depth) throws ScanFailure {
      checkedId(typeId);
      if (depth > IrisSpirvResourceLayout.MAX_TYPE_DEPTH) {
        throw unsupported(FailureCode.TYPE_DEPTH_LIMIT,
            "resource type graph exceeds depth "
                + IrisSpirvResourceLayout.MAX_TYPE_DEPTH);
      }
      TypeRef cached = resolvedTypes[typeId];
      if (cached != null) {
        return cached;
      }
      if (resolutionState[typeId] == 1) {
        throw unsupported(FailureCode.UNSUPPORTED_TYPE,
            "recursive SPIR-V resource type graph");
      }
      resolutionState[typeId] = 1;
      try {
        Definition definition = definition(typeId);
        TypeRef resolved = switch (definition) {
          case BoolNode ignored -> TypeRef.scalar(ScalarKind.BOOL, 1);
          case IntNode integer -> resolveInteger(integer);
          case FloatNode floating -> new TypeRef(List.of(),
              new ScalarType(ScalarKind.FLOAT,
                  checkedScalarWidth(floating.width)));
          case VectorNode vector -> resolveVector(vector, depth);
          case MatrixNode matrix -> resolveMatrix(matrix, depth);
          case ImageNode image -> resolveImage(image, depth);
          case SamplerNode ignored -> new TypeRef(List.of(),
              new SamplerType());
          case SampledImageNode sampled -> resolveSampledImage(sampled,
              depth);
          case ArrayNode array -> resolveArray(typeId, array, depth);
          case StructNode structure -> resolveStruct(typeId, structure,
              depth);
          case VoidNode ignored -> throw unsupported(
              FailureCode.UNSUPPORTED_TYPE,
              "void is not a standalone resource type");
          case PointerNode ignored -> throw unsupported(
              FailureCode.UNSUPPORTED_TYPE,
              "nested pointer resource types are unsupported");
          case UnsupportedTypeNode unsupported -> throw unsupported(
              FailureCode.UNSUPPORTED_TYPE,
              "resource uses unsupported type opcode "
                  + unsupported.opcode);
          default -> throw malformed(FailureCode.MISSING_TYPE,
              "id " + typeId + " is not a SPIR-V type");
        };
        resolvedTypes[typeId] = resolved;
        resolutionState[typeId] = 2;
        return resolved;
      } finally {
        if (resolutionState[typeId] == 1) {
          resolutionState[typeId] = 0;
        }
      }
    }

    private TypeRef resolveInteger(IntNode integer) throws ScanFailure {
      if (integer.signedness != 0 && integer.signedness != 1) {
        throw malformed(FailureCode.UNSUPPORTED_TYPE,
            "OpTypeInt signedness must be zero or one");
      }
      return TypeRef.scalar(integer.signedness == 1
              ? ScalarKind.SIGNED_INT : ScalarKind.UNSIGNED_INT,
          checkedScalarWidth(integer.width));
    }

    private int checkedScalarWidth(int rawWidth) throws ScanFailure {
      if (rawWidth == 8 || rawWidth == 16 || rawWidth == 32
          || rawWidth == 64) {
        return rawWidth;
      }
      throw unsupported(FailureCode.UNSUPPORTED_TYPE,
          "unsupported scalar width " + Integer.toUnsignedString(rawWidth));
    }

    private TypeRef resolveVector(VectorNode vector, int depth)
        throws ScanFailure {
      TypeRef component = resolveType(vector.componentTypeId, depth + 1);
      if (!component.arrays().isEmpty()
          || !(component.baseType() instanceof ScalarType scalar)) {
        throw malformed(FailureCode.INVALID_RESOURCE,
            "OpTypeVector component is not a scalar");
      }
      if (vector.componentCount < 2 || vector.componentCount > 4) {
        throw unsupported(FailureCode.UNSUPPORTED_TYPE,
            "unsupported vector component count "
                + Integer.toUnsignedString(vector.componentCount));
      }
      return new TypeRef(List.of(),
          new VectorType(scalar, vector.componentCount));
    }

    private TypeRef resolveMatrix(MatrixNode matrix, int depth)
        throws ScanFailure {
      TypeRef column = resolveType(matrix.columnTypeId, depth + 1);
      if (!column.arrays().isEmpty()
          || !(column.baseType() instanceof VectorType vector)) {
        throw malformed(FailureCode.INVALID_RESOURCE,
            "OpTypeMatrix column is not a vector");
      }
      if (matrix.columnCount < 2 || matrix.columnCount > 4) {
        throw unsupported(FailureCode.UNSUPPORTED_TYPE,
            "unsupported matrix column count "
                + Integer.toUnsignedString(matrix.columnCount));
      }
      return new TypeRef(List.of(), new MatrixType(vector.componentType(),
          vector.componentCount(), matrix.columnCount));
    }

    private TypeRef resolveImage(ImageNode image, int depth)
        throws ScanFailure {
      Optional<ScalarType> sampledType;
      Definition sampledDefinition = definition(image.sampledTypeId);
      if (sampledDefinition instanceof VoidNode) {
        sampledType = Optional.empty();
      } else {
        TypeRef sampled = resolveType(image.sampledTypeId, depth + 1);
        if (!sampled.arrays().isEmpty()
            || !(sampled.baseType() instanceof ScalarType scalar)) {
          throw malformed(FailureCode.INVALID_RESOURCE,
              "OpTypeImage sampled type is not scalar or void");
        }
        if (scalar.scalarKind() == ScalarKind.BOOL) {
          throw malformed(FailureCode.INVALID_RESOURCE,
              "OpTypeImage sampled type cannot be bool");
        }
        sampledType = Optional.of(scalar);
      }
      ImageDimension dimension = imageDimension(image.dimension);
      ImageDepth imageDepth = imageDepth(image.depth);
      boolean arrayed = spirvBoolean(image.arrayed, "image arrayed");
      boolean multisampled = spirvBoolean(image.multisampled,
          "image multisampled");
      ImageSampling sampling = imageSampling(image.sampling);
      ImageFormat format = imageFormat(image.format);
      DeclaredImageAccess declaredAccess = declaredImageAccess(
          image.accessQualifier);
      if (sampling != ImageSampling.STORAGE
          && declaredAccess != DeclaredImageAccess.NONE) {
        throw unsupported(FailureCode.UNSUPPORTED_IMAGE,
            "non-storage image has an AccessQualifier");
      }
      return new TypeRef(List.of(), new ImageType(sampledType, dimension,
          imageDepth, arrayed, multisampled, sampling, format,
          declaredAccess));
    }

    private TypeRef resolveSampledImage(SampledImageNode sampled, int depth)
        throws ScanFailure {
      TypeRef image = resolveType(sampled.imageTypeId, depth + 1);
      if (!image.arrays().isEmpty()
          || !(image.baseType() instanceof ImageType imageType)) {
        throw malformed(FailureCode.INVALID_RESOURCE,
            "OpTypeSampledImage operand is not OpTypeImage");
      }
      if (imageType.sampling() == ImageSampling.STORAGE) {
        throw unsupported(FailureCode.UNSUPPORTED_IMAGE,
            "sampled-image wrapper contains a storage image");
      }
      return new TypeRef(List.of(), new SampledImageType(imageType));
    }

    private TypeRef resolveArray(int typeId, ArrayNode array, int depth)
        throws ScanFailure {
      TypeRef element = resolveType(array.elementTypeId, depth + 1);
      if (element.arrays().size()
          >= IrisSpirvResourceLayout.MAX_ARRAY_DIMENSIONS) {
        throw unsupported(FailureCode.TYPE_DEPTH_LIMIT,
            "resource array rank exceeds "
                + IrisSpirvResourceLayout.MAX_ARRAY_DIMENSIONS);
      }
      int stride = checkedLayoutValue(
          decorationOrEmpty(typeId).arrayStride, "array stride");
      ArrayDimension dimension = array.runtime
          ? ArrayDimension.runtime(stride)
          : ArrayDimension.literal(arrayLength(array.lengthId), stride);
      ArrayList<ArrayDimension> dimensions = new ArrayList<>(
          element.arrays().size() + 1);
      dimensions.add(dimension);
      dimensions.addAll(element.arrays());
      return new TypeRef(dimensions, element.baseType());
    }

    private TypeRef resolveStruct(int typeId, StructNode structure,
        int depth) throws ScanFailure {
      Integer highestMember = highestDecoratedMember.get(typeId);
      if (highestMember != null
          && highestMember >= structure.memberTypeIds.length) {
        throw malformed(FailureCode.INVALID_RESOURCE,
            "member decoration is outside its struct type");
      }
      ArrayList<StructMember> members = new ArrayList<>(
          structure.memberTypeIds.length);
      for (int index = 0; index < structure.memberTypeIds.length; index++) {
        TypeRef memberType = resolveType(structure.memberTypeIds[index],
            depth + 1);
        MemberDecorations member = memberDecorations.getOrDefault(
            memberKey(typeId, index), MemberDecorations.EMPTY);
        MatrixMajor matrixMajor = member.rowMajor
            ? MatrixMajor.ROW_MAJOR
            : member.columnMajor ? MatrixMajor.COLUMN_MAJOR
            : MatrixMajor.NONE;
        members.add(new StructMember(memberType,
            checkedLayoutValue(member.offset, "member offset"),
            checkedLayoutValue(member.matrixStride, "matrix stride"),
            matrixMajor, member.nonReadable, member.nonWritable));
      }
      return new TypeRef(List.of(), new StructType(members));
    }

    private BigInteger arrayLength(int lengthId) throws ScanFailure {
      Definition definition = definition(lengthId);
      if (!(definition instanceof ConstantNode constant)
          || constant.specialization) {
        throw unsupported(FailureCode.NON_LITERAL_ARRAY_LENGTH,
            "array length id " + lengthId
                + " is not an OpConstant literal");
      }
      Definition type = definition(constant.typeId);
      if (!(type instanceof IntNode integer)) {
        throw malformed(FailureCode.INVALID_ARRAY_LENGTH,
            "array length constant is not an integer");
      }
      int width = checkedScalarWidth(integer.width);
      int requiredWords = (width + 31) / 32;
      if (constant.literalWordCount != requiredWords) {
        throw malformed(FailureCode.INVALID_ARRAY_LENGTH,
            "array length literal word count does not match its type");
      }
      BigInteger value = BigInteger.ZERO;
      for (int index = requiredWords - 1; index >= 0; index--) {
        value = value.shiftLeft(32).add(BigInteger.valueOf(
            Integer.toUnsignedLong(word(constant.literalOffset + index))));
      }
      BigInteger modulus = BigInteger.ONE.shiftLeft(width);
      value = value.and(modulus.subtract(BigInteger.ONE));
      if (integer.signedness == 1) {
        if (value.testBit(width - 1)) {
          value = value.subtract(modulus);
        }
      } else if (integer.signedness != 0) {
        throw malformed(FailureCode.INVALID_ARRAY_LENGTH,
            "array length signedness is invalid");
      }
      if (value.signum() <= 0) {
        throw malformed(FailureCode.INVALID_ARRAY_LENGTH,
            "array length must be positive");
      }
      return value;
    }

    private int checkedLayoutValue(Long value, String label)
        throws ScanFailure {
      if (value == null) {
        return -1;
      }
      if (value > Integer.MAX_VALUE) {
        throw unsupported(FailureCode.INVALID_RESOURCE,
            label + " exceeds the signed Java model range");
      }
      return value.intValue();
    }

    private ImageDimension imageDimension(int raw) throws ScanFailure {
      for (ImageDimension value : ImageDimension.values()) {
        if (value.spirvValue() == raw) {
          return value;
        }
      }
      throw unsupported(FailureCode.UNSUPPORTED_IMAGE,
          "unsupported image dimension " + Integer.toUnsignedString(raw));
    }

    private ImageDepth imageDepth(int raw) throws ScanFailure {
      for (ImageDepth value : ImageDepth.values()) {
        if (value.spirvValue() == raw) {
          return value;
        }
      }
      throw malformed(FailureCode.UNSUPPORTED_IMAGE,
          "invalid image depth operand " + Integer.toUnsignedString(raw));
    }

    private ImageSampling imageSampling(int raw) throws ScanFailure {
      for (ImageSampling value : ImageSampling.values()) {
        if (value.spirvValue() == raw) {
          return value;
        }
      }
      throw malformed(FailureCode.UNSUPPORTED_IMAGE,
          "invalid image sampling operand " + Integer.toUnsignedString(raw));
    }

    private ImageFormat imageFormat(int raw) throws ScanFailure {
      for (ImageFormat value : ImageFormat.values()) {
        if (value.spirvValue() == raw) {
          return value;
        }
      }
      throw unsupported(FailureCode.UNSUPPORTED_IMAGE,
          "unsupported image format " + Integer.toUnsignedString(raw));
    }

    private DeclaredImageAccess declaredImageAccess(int raw)
        throws ScanFailure {
      for (DeclaredImageAccess value : DeclaredImageAccess.values()) {
        if (value.spirvValue() == raw) {
          return value;
        }
      }
      throw malformed(FailureCode.UNSUPPORTED_IMAGE,
          "invalid image AccessQualifier " + Integer.toUnsignedString(raw));
    }

    private boolean spirvBoolean(int raw, String label) throws ScanFailure {
      if (raw == 0) {
        return false;
      }
      if (raw == 1) {
        return true;
      }
      throw malformed(FailureCode.UNSUPPORTED_IMAGE,
          label + " operand must be zero or one");
    }

    private Definition definition(int id) throws ScanFailure {
      checkedId(id);
      Definition definition = definitions[id];
      if (definition == null) {
        throw unsupported(FailureCode.MISSING_TYPE,
            "referenced id " + id
                + " has no supported declaration");
      }
      return definition;
    }

    private void define(int rawId, Definition definition)
        throws ScanFailure {
      int id = checkedId(rawId);
      if (definitions[id] != null) {
        throw malformed(FailureCode.DUPLICATE_ID,
            "SPIR-V id " + id + " is defined more than once");
      }
      definitions[id] = definition;
    }

    private int checkedId(int rawId) throws ScanFailure {
      long unsigned = Integer.toUnsignedLong(rawId);
      if (unsigned == 0 || unsigned >= idBound) {
        throw malformed(FailureCode.INVALID_ID,
            "SPIR-V id " + unsigned + " is outside the declared bound");
      }
      return rawId;
    }

    private Decorations decorations(int id) {
      Decorations value = decorations[id];
      if (value == null) {
        value = new Decorations();
        decorations[id] = value;
      }
      return value;
    }

    private Decorations decorationOrEmpty(int id) {
      Decorations value = decorations[id];
      return value == null ? Decorations.EMPTY : value;
    }

    private int word(int wordIndex) {
      return readInt(wordIndex * Integer.BYTES, byteOrder);
    }

    private int readInt(int byteOffset, ByteOrder order) {
      int first = bytes[byteOffset] & 0xFF;
      int second = bytes[byteOffset + 1] & 0xFF;
      int third = bytes[byteOffset + 2] & 0xFF;
      int fourth = bytes[byteOffset + 3] & 0xFF;
      if (order == ByteOrder.LITTLE_ENDIAN) {
        return first | second << 8 | third << 16 | fourth << 24;
      }
      return first << 24 | second << 16 | third << 8 | fourth;
    }

    private void requireCount(int opcode, int actual, int expected)
        throws ScanFailure {
      if (actual != expected) {
        throw malformed(FailureCode.MALFORMED_INSTRUCTION,
            opcodeName(opcode) + " has " + actual
                + " words; expected " + expected);
      }
    }

    private String opcodeName(int opcode) {
      return switch (opcode) {
        case OP_DECORATE -> "OpDecorate";
        case OP_MEMBER_DECORATE -> "OpMemberDecorate";
        default -> "opcode " + opcode;
      };
    }
  }

  private sealed interface Definition permits VoidNode, BoolNode, IntNode,
      FloatNode, VectorNode, MatrixNode, ImageNode, SamplerNode,
      SampledImageNode, ArrayNode, StructNode, PointerNode,
      UnsupportedTypeNode, ConstantNode, UnsupportedConstantNode,
      VariableNode {
  }

  private record VoidNode() implements Definition {
  }

  private record BoolNode() implements Definition {
  }

  private record IntNode(int width, int signedness) implements Definition {
  }

  private record FloatNode(int width) implements Definition {
  }

  private record VectorNode(int componentTypeId, int componentCount)
      implements Definition {
  }

  private record MatrixNode(int columnTypeId, int columnCount)
      implements Definition {
  }

  private record ImageNode(int sampledTypeId, int dimension, int depth,
                           int arrayed, int multisampled, int sampling,
                           int format, int accessQualifier)
      implements Definition {
  }

  private record SamplerNode() implements Definition {
  }

  private record SampledImageNode(int imageTypeId) implements Definition {
  }

  private record ArrayNode(int elementTypeId, int lengthId, boolean runtime)
      implements Definition {
  }

  private record StructNode(int[] memberTypeIds) implements Definition {
  }

  private record PointerNode(int storageClass, int pointeeTypeId)
      implements Definition {
  }

  private record UnsupportedTypeNode(int opcode) implements Definition {
  }

  private record ConstantNode(int typeId, boolean specialization,
                              int literalOffset, int literalWordCount)
      implements Definition {
  }

  private record UnsupportedConstantNode(int opcode) implements Definition {
  }

  private record VariableNode(int resultTypeId, int resultId,
                              int storageClass) implements Definition {
  }

  private static final class Decorations {
    private static final Decorations EMPTY = new Decorations();

    private Long specId;
    private Long arrayStride;
    private Long location;
    private Long binding;
    private Long descriptorSet;
    private boolean block;
    private boolean bufferBlock;
    private boolean rowMajor;
    private boolean columnMajor;
    private boolean nonWritable;
    private boolean nonReadable;
  }

  private static final class MemberDecorations {
    private static final MemberDecorations EMPTY = new MemberDecorations();

    private Long offset;
    private Long matrixStride;
    private boolean rowMajor;
    private boolean columnMajor;
    private boolean nonWritable;
    private boolean nonReadable;
  }

  private record DecodedString(String value) {
  }

  private static long memberKey(int structureId, int member) {
    return Integer.toUnsignedLong(structureId) << 32
        | Integer.toUnsignedLong(member);
  }

  private static ScanFailure malformed(FailureCode code, String detail) {
    return new ScanFailure(Status.MALFORMED, code, detail);
  }

  private static ScanFailure unsupported(FailureCode code, String detail) {
    return new ScanFailure(Status.UNSUPPORTED, code, detail);
  }

  private static final class ScanFailure extends Exception {
    private final Status status;
    private final FailureCode code;

    private ScanFailure(Status status, FailureCode code, String detail) {
      super(detail);
      this.status = status;
      this.code = code;
    }
  }
}

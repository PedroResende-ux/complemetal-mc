package com.pebbles_boon.metalrender.compat.iris;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Bounded, dependency-free scanner for SPIR-V specialization constants.
 *
 * <p>This is deliberately not a replacement for a full SPIR-V validator. It
 * validates the module framing and every instruction whose data it consumes,
 * and it never turns malformed or unresolved input into a successful empty
 * scan. Unknown instructions are skipped by their declared word count.</p>
 */
public final class SpirvSpecializationScanner {
  static final int MAX_MODULE_BYTES = 64 * 1024 * 1024;
  static final int MAX_TYPE_DECLARATIONS = 65_536;
  static final int MAX_SPECIALIZATION_CONSTANTS = 4_096;
  static final int MAX_SPEC_ID_DECORATIONS = 4_096;

  private static final int SPIRV_MAGIC = 0x07230203;
  private static final int SWAPPED_SPIRV_MAGIC = 0x03022307;
  private static final int HEADER_WORDS = 5;

  private static final int OP_TYPE_BOOL = 20;
  private static final int OP_TYPE_INT = 21;
  private static final int OP_TYPE_FLOAT = 22;
  private static final int OP_TYPE_VECTOR = 23;
  private static final int OP_TYPE_MATRIX = 24;
  private static final int OP_TYPE_ARRAY = 28;
  private static final int OP_TYPE_RUNTIME_ARRAY = 29;
  private static final int OP_TYPE_STRUCT = 30;
  private static final int OP_SPEC_CONSTANT_TRUE = 48;
  private static final int OP_SPEC_CONSTANT_FALSE = 49;
  private static final int OP_SPEC_CONSTANT = 50;
  private static final int OP_SPEC_CONSTANT_COMPOSITE = 51;
  private static final int OP_SPEC_CONSTANT_OP = 52;
  private static final int OP_DECORATE = 71;
  private static final long DECORATION_SPEC_ID = 1L;

  private SpirvSpecializationScanner() {
  }

  /**
   * Scans a complete SPIR-V module. A malformed result never contains partial
   * specialization metadata.
   */
  public static ScanResult scan(byte[] spirv) {
    if (spirv == null) {
      return malformed(FailureCode.NULL_INPUT, -1,
          "SPIR-V input is null");
    }
    if (spirv.length < HEADER_WORDS * Integer.BYTES
        || spirv.length % Integer.BYTES != 0) {
      return malformed(FailureCode.INVALID_SIZE, -1,
          "SPIR-V must contain an aligned five-word header");
    }
    if (spirv.length > MAX_MODULE_BYTES) {
      return malformed(FailureCode.MODULE_TOO_LARGE, -1,
          "SPIR-V exceeds the scanner byte limit");
    }

    ByteOrder byteOrder;
    int littleEndianMagic = ByteBuffer.wrap(spirv, 0, Integer.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).getInt();
    if (littleEndianMagic == SPIRV_MAGIC) {
      byteOrder = ByteOrder.LITTLE_ENDIAN;
    } else if (littleEndianMagic == SWAPPED_SPIRV_MAGIC) {
      byteOrder = ByteOrder.BIG_ENDIAN;
    } else {
      return malformed(FailureCode.INVALID_MAGIC, 0,
          "SPIR-V magic is invalid");
    }

    ByteBuffer words = ByteBuffer.wrap(spirv).order(byteOrder);
    int versionWord = words.getInt(Integer.BYTES);
    int versionMajor = (versionWord >>> 16) & 0xff;
    int versionMinor = (versionWord >>> 8) & 0xff;
    if ((versionWord & 0xff0000ff) != 0 || versionMajor == 0) {
      return malformed(FailureCode.INVALID_HEADER, 1,
          "SPIR-V version word is invalid");
    }
    long generator = unsigned(words.getInt(2 * Integer.BYTES));
    long idBound = unsigned(words.getInt(3 * Integer.BYTES));
    long schema = unsigned(words.getInt(4 * Integer.BYTES));
    if (idBound == 0 || schema != 0) {
      return malformed(FailureCode.INVALID_HEADER,
          idBound == 0 ? 3 : 4,
          idBound == 0 ? "SPIR-V id bound is zero"
              : "SPIR-V reserved schema word is non-zero");
    }

    ModuleMetadata module = new ModuleMetadata(
        byteOrder == ByteOrder.LITTLE_ENDIAN
            ? Endianness.LITTLE_ENDIAN : Endianness.BIG_ENDIAN,
        versionMajor, versionMinor, generator, idBound);
    ParseState state = new ParseState(words, spirv.length / Integer.BYTES,
        idBound);
    try {
      state.parse();
      return state.finish(module);
    } catch (ParseException failure) {
      return malformed(failure.code, failure.wordOffset,
          failure.getMessage());
    }
  }

  private static Malformed malformed(FailureCode code, int wordOffset,
      String detail) {
    return new Malformed(code, wordOffset, detail);
  }

  private static long unsigned(int word) {
    return Integer.toUnsignedLong(word);
  }

  /** Strongly separates a verified scan from malformed input. */
  public sealed interface ScanResult permits Scanned, Malformed {
    default boolean specializationScanned() {
      return this instanceof Scanned;
    }
  }

  /** A successfully and explicitly scanned module, including an empty one. */
  public record Scanned(ModuleMetadata module,
                        List<SpecializationConstant> constants,
                        boolean allDecoratedSpecIdsRepresentable)
      implements ScanResult {
    public Scanned {
      if (module == null || constants == null) {
        throw new NullPointerException("scanned result fields");
      }
      constants = List.copyOf(constants);
    }

    public boolean scannedEmpty() {
      return constants.isEmpty();
    }
  }

  /** Parse failure. Partial constants are intentionally not exposed. */
  public record Malformed(FailureCode failureCode, int wordOffset,
                          String detail) implements ScanResult {
    public Malformed {
      if (failureCode == null || detail == null || detail.isBlank()) {
        throw new IllegalArgumentException("malformed result fields");
      }
      if (wordOffset < -1) {
        throw new IllegalArgumentException("invalid failure word offset");
      }
    }
  }

  public record ModuleMetadata(Endianness endianness, int versionMajor,
                               int versionMinor, long generator,
                               long idBound) {
    public ModuleMetadata {
      if (endianness == null || versionMajor <= 0 || versionMinor < 0
          || generator < 0 || idBound <= 0) {
        throw new IllegalArgumentException("invalid SPIR-V module metadata");
      }
    }
  }

  /**
   * Metadata for one specialization declaration.
   *
   * <p>Words are exposed as unsigned values in the range 0..2^32-1. For
   * scalar literal constants, {@code rawDefaultBits} contains the complete
   * normalized scalar bit pattern. Composite and operation declarations keep
   * their dependency/operand words and are deliberately not scalar defaults.</p>
   */
  public record SpecializationConstant(
      long resultId,
      long resultTypeId,
      OptionalLong specId,
      ConstantKind kind,
      ScalarType scalarType,
      long bitWidth,
      List<Long> defaultLiteralWords,
      OptionalLong rawDefaultBits,
      List<Long> operandWords,
      OptionalLong operationOpcode,
      boolean decoratedSpecIdRepresentable) {
    public SpecializationConstant {
      if (resultId <= 0 || resultTypeId <= 0 || specId == null
          || kind == null || scalarType == null || bitWidth < 0
          || defaultLiteralWords == null || rawDefaultBits == null
          || operandWords == null || operationOpcode == null) {
        throw new IllegalArgumentException(
            "invalid specialization constant metadata");
      }
      defaultLiteralWords = List.copyOf(defaultLiteralWords);
      operandWords = List.copyOf(operandWords);
      if (decoratedSpecIdRepresentable && specId.isEmpty()) {
        throw new IllegalArgumentException(
            "an undecorated constant cannot be externally representable");
      }
    }

    public boolean hasSpecId() {
      return specId.isPresent();
    }
  }

  public enum ConstantKind {
    BOOLEAN_TRUE,
    BOOLEAN_FALSE,
    SCALAR_LITERAL,
    COMPOSITE,
    OPERATION
  }

  /** Stable scalar names shared with the pipeline-state cache vocabulary. */
  public enum ScalarType {
    BOOL("bool"),
    INT32("int32"),
    UINT32("uint32"),
    FLOAT32("float32"),
    INT64("int64"),
    UINT64("uint64"),
    FLOAT64("float64"),
    UNSUPPORTED_SCALAR("unsupported-scalar"),
    COMPOSITE("composite");

    private final String cacheName;

    ScalarType(String cacheName) {
      this.cacheName = cacheName;
    }

    public String cacheName() {
      return cacheName;
    }

    public boolean pipelineRepresentable() {
      return switch (this) {
        case BOOL, INT32, UINT32, FLOAT32, INT64, UINT64, FLOAT64 -> true;
        case UNSUPPORTED_SCALAR, COMPOSITE -> false;
      };
    }
  }

  public enum Endianness {
    LITTLE_ENDIAN,
    BIG_ENDIAN
  }

  public enum FailureCode {
    NULL_INPUT,
    INVALID_SIZE,
    MODULE_TOO_LARGE,
    INVALID_MAGIC,
    INVALID_HEADER,
    ZERO_WORD_COUNT,
    TRUNCATED_INSTRUCTION,
    INVALID_OPERAND_COUNT,
    INVALID_ID,
    DUPLICATE_RESULT_ID,
    TOO_MANY_TYPE_DECLARATIONS,
    TOO_MANY_SPECIALIZATION_CONSTANTS,
    TOO_MANY_SPEC_ID_DECORATIONS,
    DUPLICATE_SPEC_ID_DECORATION,
    ORPHAN_SPEC_ID_DECORATION,
    DUPLICATE_SPEC_ID,
    UNRESOLVED_RESULT_TYPE,
    INVALID_SCALAR_TYPE,
    INVALID_SCALAR_DEFAULT
  }

  private static final class ParseState {
    private static final Comparator<SpecializationConstant> CONSTANT_ORDER =
        Comparator.comparingInt((SpecializationConstant value) ->
                value.specId().isPresent() ? 0 : 1)
            .thenComparingLong(value -> value.specId().orElse(0L))
            .thenComparingLong(SpecializationConstant::resultId);

    private final ByteBuffer words;
    private final int totalWords;
    private final long idBound;
    private final Map<Long, TypeInfo> types = new HashMap<>();
    private final Map<Long, RawConstant> constants = new HashMap<>();
    private final Map<Long, Long> specIdsByTarget = new HashMap<>();
    private final Set<Long> parsedResultIds = new HashSet<>();

    private ParseState(ByteBuffer words, int totalWords, long idBound) {
      this.words = words;
      this.totalWords = totalWords;
      this.idBound = idBound;
    }

    private void parse() throws ParseException {
      int offset = HEADER_WORDS;
      while (offset < totalWords) {
        int instruction = word(offset);
        int wordCount = instruction >>> 16;
        int opcode = instruction & 0xffff;
        if (wordCount == 0) {
          throw failure(FailureCode.ZERO_WORD_COUNT, offset,
              "instruction has a zero word count");
        }
        long end = (long) offset + wordCount;
        if (end > totalWords) {
          throw failure(FailureCode.TRUNCATED_INSTRUCTION, offset,
              "instruction extends beyond the module");
        }
        parseInstruction(offset, wordCount, opcode);
        offset += wordCount;
      }
    }

    private void parseInstruction(int offset, int wordCount, int opcode)
        throws ParseException {
      switch (opcode) {
        case OP_TYPE_BOOL -> parseBoolType(offset, wordCount);
        case OP_TYPE_INT -> parseIntType(offset, wordCount);
        case OP_TYPE_FLOAT -> parseFloatType(offset, wordCount);
        case OP_TYPE_VECTOR, OP_TYPE_MATRIX, OP_TYPE_ARRAY ->
            parseCompositeType(offset, wordCount, 4);
        case OP_TYPE_RUNTIME_ARRAY ->
            parseCompositeType(offset, wordCount, 3);
        case OP_TYPE_STRUCT -> parseStructType(offset, wordCount);
        case OP_SPEC_CONSTANT_TRUE -> parseConstant(offset, wordCount,
            ConstantKind.BOOLEAN_TRUE, 3);
        case OP_SPEC_CONSTANT_FALSE -> parseConstant(offset, wordCount,
            ConstantKind.BOOLEAN_FALSE, 3);
        case OP_SPEC_CONSTANT -> parseLiteralConstant(offset, wordCount);
        case OP_SPEC_CONSTANT_COMPOSITE ->
            parseCompositeConstant(offset, wordCount);
        case OP_SPEC_CONSTANT_OP -> parseOperationConstant(offset, wordCount);
        case OP_DECORATE -> parseDecoration(offset, wordCount);
        default -> {
          // Unknown instructions remain structurally bounded by parse().
        }
      }
    }

    private void parseBoolType(int offset, int wordCount)
        throws ParseException {
      requireWordCount(offset, wordCount, 2);
      registerType(offset, id(offset + 1, offset), TypeInfo.bool());
    }

    private void parseIntType(int offset, int wordCount)
        throws ParseException {
      requireWordCount(offset, wordCount, 4);
      long resultId = id(offset + 1, offset);
      long width = unsigned(word(offset + 2));
      long signedness = unsigned(word(offset + 3));
      if (width == 0 || signedness > 1) {
        throw failure(FailureCode.INVALID_SCALAR_TYPE, offset,
            "integer type has invalid width or signedness");
      }
      registerType(offset, resultId,
          TypeInfo.integer(width, signedness == 1));
    }

    private void parseFloatType(int offset, int wordCount)
        throws ParseException {
      requireWordCount(offset, wordCount, 3);
      long resultId = id(offset + 1, offset);
      long width = unsigned(word(offset + 2));
      if (width == 0) {
        throw failure(FailureCode.INVALID_SCALAR_TYPE, offset,
            "floating-point type has a zero width");
      }
      registerType(offset, resultId, TypeInfo.floating(width));
    }

    private void parseCompositeType(int offset, int wordCount,
        int expectedWordCount) throws ParseException {
      requireWordCount(offset, wordCount, expectedWordCount);
      registerType(offset, id(offset + 1, offset), TypeInfo.composite());
    }

    private void parseStructType(int offset, int wordCount)
        throws ParseException {
      requireMinimumWordCount(offset, wordCount, 2);
      registerType(offset, id(offset + 1, offset), TypeInfo.composite());
    }

    private void registerType(int offset, long resultId, TypeInfo type)
        throws ParseException {
      if (types.size() >= MAX_TYPE_DECLARATIONS) {
        throw failure(FailureCode.TOO_MANY_TYPE_DECLARATIONS, offset,
            "type declaration limit exceeded");
      }
      registerResultId(offset, resultId);
      types.put(resultId, type);
    }

    private void parseConstant(int offset, int wordCount,
        ConstantKind kind, int expectedWordCount) throws ParseException {
      requireWordCount(offset, wordCount, expectedWordCount);
      registerConstant(offset, new RawConstant(
          id(offset + 1, offset), id(offset + 2, offset), kind,
          List.of(), List.of(), OptionalLong.empty()));
    }

    private void parseLiteralConstant(int offset, int wordCount)
        throws ParseException {
      requireMinimumWordCount(offset, wordCount, 4);
      registerConstant(offset, new RawConstant(
          id(offset + 1, offset), id(offset + 2, offset),
          ConstantKind.SCALAR_LITERAL, unsignedWords(offset + 3,
          offset + wordCount), List.of(), OptionalLong.empty()));
    }

    private void parseCompositeConstant(int offset, int wordCount)
        throws ParseException {
      requireMinimumWordCount(offset, wordCount, 3);
      List<Long> constituents = unsignedWords(offset + 3,
          offset + wordCount);
      for (long constituent : constituents) {
        requireId(constituent, offset);
      }
      registerConstant(offset, new RawConstant(
          id(offset + 1, offset), id(offset + 2, offset),
          ConstantKind.COMPOSITE, List.of(), constituents,
          OptionalLong.empty()));
    }

    private void parseOperationConstant(int offset, int wordCount)
        throws ParseException {
      requireMinimumWordCount(offset, wordCount, 4);
      long operationOpcode = unsigned(word(offset + 3));
      if (operationOpcode > 0xffffL) {
        throw failure(FailureCode.INVALID_SCALAR_DEFAULT, offset,
            "specialization operation opcode is out of range");
      }
      registerConstant(offset, new RawConstant(
          id(offset + 1, offset), id(offset + 2, offset),
          ConstantKind.OPERATION, List.of(),
          unsignedWords(offset + 4, offset + wordCount),
          OptionalLong.of(operationOpcode)));
    }

    private void registerConstant(int offset, RawConstant constant)
        throws ParseException {
      if (constants.size() >= MAX_SPECIALIZATION_CONSTANTS) {
        throw failure(FailureCode.TOO_MANY_SPECIALIZATION_CONSTANTS, offset,
            "specialization constant limit exceeded");
      }
      registerResultId(offset, constant.resultId);
      constants.put(constant.resultId, constant);
    }

    private void parseDecoration(int offset, int wordCount)
        throws ParseException {
      requireMinimumWordCount(offset, wordCount, 3);
      long decoration = unsigned(word(offset + 2));
      if (decoration != DECORATION_SPEC_ID) {
        return;
      }
      requireWordCount(offset, wordCount, 4);
      if (specIdsByTarget.size() >= MAX_SPEC_ID_DECORATIONS) {
        throw failure(FailureCode.TOO_MANY_SPEC_ID_DECORATIONS, offset,
            "SpecId decoration limit exceeded");
      }
      long targetId = id(offset + 1, offset);
      long specId = unsigned(word(offset + 3));
      if (specIdsByTarget.putIfAbsent(targetId, specId) != null) {
        throw failure(FailureCode.DUPLICATE_SPEC_ID_DECORATION, offset,
            "result id has more than one SpecId decoration");
      }
    }

    private Scanned finish(ModuleMetadata module) throws ParseException {
      for (long targetId : specIdsByTarget.keySet()) {
        if (!constants.containsKey(targetId)) {
          throw failure(FailureCode.ORPHAN_SPEC_ID_DECORATION, -1,
              "SpecId decorates a non-specialization result id");
        }
      }
      Set<Long> uniqueSpecIds = new HashSet<>();
      ArrayList<SpecializationConstant> resolved =
          new ArrayList<>(constants.size());
      boolean allRepresentable = true;
      for (RawConstant raw : constants.values()) {
        TypeInfo type = types.get(raw.resultTypeId);
        if (type == null) {
          throw failure(FailureCode.UNRESOLVED_RESULT_TYPE, -1,
              "specialization constant result type was not declared");
        }
        OptionalLong specId = specIdsByTarget.containsKey(raw.resultId)
            ? OptionalLong.of(specIdsByTarget.get(raw.resultId))
            : OptionalLong.empty();
        if (specId.isPresent() && !uniqueSpecIds.add(specId.getAsLong())) {
          throw failure(FailureCode.DUPLICATE_SPEC_ID, -1,
              "multiple specialization constants use the same SpecId");
        }
        SpecializationConstant constant = resolve(raw, type, specId);
        resolved.add(constant);
        if (specId.isPresent()
            && !constant.decoratedSpecIdRepresentable()) {
          allRepresentable = false;
        }
      }
      resolved.sort(CONSTANT_ORDER);
      return new Scanned(module, resolved, allRepresentable);
    }

    private SpecializationConstant resolve(RawConstant raw, TypeInfo type,
        OptionalLong specId) throws ParseException {
      List<Long> literalWords = raw.literalWords;
      OptionalLong rawBits = OptionalLong.empty();
      ScalarType scalarType = type.scalarType();
      switch (raw.kind) {
        case BOOLEAN_TRUE, BOOLEAN_FALSE -> {
          if (type.family != TypeFamily.BOOL) {
            throw failure(FailureCode.INVALID_SCALAR_TYPE, -1,
                "boolean specialization constant has a non-boolean type");
          }
          literalWords = List.of(
              raw.kind == ConstantKind.BOOLEAN_TRUE ? 1L : 0L);
          rawBits = OptionalLong.of(literalWords.getFirst());
        }
        case SCALAR_LITERAL -> {
          if (type.family != TypeFamily.INTEGER
              && type.family != TypeFamily.FLOAT) {
            throw failure(FailureCode.INVALID_SCALAR_TYPE, -1,
                "literal specialization constant has a non-scalar type");
          }
          long expectedWords = (type.bitWidth + 31L) / 32L;
          if (expectedWords != literalWords.size()) {
            throw failure(FailureCode.INVALID_SCALAR_DEFAULT, -1,
                "scalar default word count does not match its type width");
          }
          if (literalWords.size() <= 2) {
            long bits = literalWords.getFirst();
            if (literalWords.size() == 2) {
              bits |= literalWords.get(1) << 32;
            }
            rawBits = OptionalLong.of(bits);
          }
        }
        case COMPOSITE -> {
          if (type.family != TypeFamily.COMPOSITE) {
            throw failure(FailureCode.INVALID_SCALAR_TYPE, -1,
                "composite specialization constant has a scalar type");
          }
        }
        case OPERATION -> {
          // An operation can produce either a scalar or a composite. Its
          // default requires evaluation, so rawDefaultBits remains absent.
        }
      }

      boolean representable = specId.isPresent()
          && specId.getAsLong() <= Integer.MAX_VALUE
          && (raw.kind == ConstantKind.BOOLEAN_TRUE
          || raw.kind == ConstantKind.BOOLEAN_FALSE
          || raw.kind == ConstantKind.SCALAR_LITERAL)
          && scalarType.pipelineRepresentable()
          && rawBits.isPresent();
      return new SpecializationConstant(raw.resultId, raw.resultTypeId,
          specId, raw.kind, scalarType, type.bitWidth, literalWords, rawBits,
          raw.operandWords, raw.operationOpcode, representable);
    }

    private void registerResultId(int offset, long resultId)
        throws ParseException {
      requireId(resultId, offset);
      if (!parsedResultIds.add(resultId)) {
        throw failure(FailureCode.DUPLICATE_RESULT_ID, offset,
            "scanner-observed result id is declared more than once");
      }
    }

    private long id(int wordIndex, int instructionOffset)
        throws ParseException {
      long value = unsigned(word(wordIndex));
      requireId(value, instructionOffset);
      return value;
    }

    private void requireId(long value, int offset) throws ParseException {
      if (value == 0 || value >= idBound) {
        throw failure(FailureCode.INVALID_ID, offset,
            "id is zero or outside the module bound");
      }
    }

    private void requireWordCount(int offset, int actual, int expected)
        throws ParseException {
      if (actual != expected) {
        throw failure(FailureCode.INVALID_OPERAND_COUNT, offset,
            "instruction has an invalid word count");
      }
    }

    private void requireMinimumWordCount(int offset, int actual, int minimum)
        throws ParseException {
      if (actual < minimum) {
        throw failure(FailureCode.INVALID_OPERAND_COUNT, offset,
            "instruction has too few operands");
      }
    }

    private List<Long> unsignedWords(int start, int end) {
      ArrayList<Long> result = new ArrayList<>(end - start);
      for (int index = start; index < end; index++) {
        result.add(unsigned(word(index)));
      }
      return List.copyOf(result);
    }

    private int word(int wordIndex) {
      return words.getInt(wordIndex * Integer.BYTES);
    }

    private static ParseException failure(FailureCode code, int wordOffset,
        String message) {
      return new ParseException(code, wordOffset, message);
    }
  }

  private enum TypeFamily {
    BOOL,
    INTEGER,
    FLOAT,
    COMPOSITE
  }

  private record TypeInfo(TypeFamily family, long bitWidth,
                          boolean signed) {
    private static TypeInfo bool() {
      return new TypeInfo(TypeFamily.BOOL, 1, false);
    }

    private static TypeInfo integer(long width, boolean signed) {
      return new TypeInfo(TypeFamily.INTEGER, width, signed);
    }

    private static TypeInfo floating(long width) {
      return new TypeInfo(TypeFamily.FLOAT, width, false);
    }

    private static TypeInfo composite() {
      return new TypeInfo(TypeFamily.COMPOSITE, 0, false);
    }

    private ScalarType scalarType() {
      return switch (family) {
        case BOOL -> ScalarType.BOOL;
        case INTEGER -> {
          if (bitWidth == 32) {
            yield signed ? ScalarType.INT32 : ScalarType.UINT32;
          }
          if (bitWidth == 64) {
            yield signed ? ScalarType.INT64 : ScalarType.UINT64;
          }
          yield ScalarType.UNSUPPORTED_SCALAR;
        }
        case FLOAT -> {
          if (bitWidth == 32) {
            yield ScalarType.FLOAT32;
          }
          if (bitWidth == 64) {
            yield ScalarType.FLOAT64;
          }
          yield ScalarType.UNSUPPORTED_SCALAR;
        }
        case COMPOSITE -> ScalarType.COMPOSITE;
      };
    }
  }

  private record RawConstant(long resultTypeId, long resultId,
                             ConstantKind kind,
                             List<Long> literalWords,
                             List<Long> operandWords,
                             OptionalLong operationOpcode) {
  }

  private static final class ParseException extends Exception {
    private final FailureCode code;
    private final int wordOffset;

    private ParseException(FailureCode code, int wordOffset, String message) {
      super(message);
      this.code = code;
      this.wordOffset = wordOffset;
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.SpirvSpecializationScanner.ConstantKind;
import com.pebbles_boon.metalrender.compat.iris.SpirvSpecializationScanner.Endianness;
import com.pebbles_boon.metalrender.compat.iris.SpirvSpecializationScanner.FailureCode;
import com.pebbles_boon.metalrender.compat.iris.SpirvSpecializationScanner.Malformed;
import com.pebbles_boon.metalrender.compat.iris.SpirvSpecializationScanner.ScalarType;
import com.pebbles_boon.metalrender.compat.iris.SpirvSpecializationScanner.Scanned;
import com.pebbles_boon.metalrender.compat.iris.SpirvSpecializationScanner.SpecializationConstant;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SpirvSpecializationScannerTest {
  private static final int OP_TYPE_BOOL = 20;
  private static final int OP_TYPE_INT = 21;
  private static final int OP_TYPE_FLOAT = 22;
  private static final int OP_TYPE_VECTOR = 23;
  private static final int OP_SPEC_CONSTANT_TRUE = 48;
  private static final int OP_SPEC_CONSTANT_FALSE = 49;
  private static final int OP_SPEC_CONSTANT = 50;
  private static final int OP_SPEC_CONSTANT_COMPOSITE = 51;
  private static final int OP_SPEC_CONSTANT_OP = 52;
  private static final int OP_DECORATE = 71;
  private static final int DECORATION_SPEC_ID = 1;

  @Test
  void explicitlyDistinguishesScannedEmptyFromParseFailure() {
    Scanned little = scanned(module(ByteOrder.LITTLE_ENDIAN, 1));
    Scanned big = scanned(module(ByteOrder.BIG_ENDIAN, 1));

    assertTrue(little.specializationScanned());
    assertTrue(little.scannedEmpty());
    assertTrue(little.allDecoratedSpecIdsRepresentable());
    assertEquals(Endianness.LITTLE_ENDIAN,
        little.module().endianness());
    assertTrue(big.scannedEmpty());
    assertEquals(Endianness.BIG_ENDIAN, big.module().endianness());

    Malformed malformed = malformed(new byte[20]);
    assertFalse(malformed.specializationScanned());
    assertEquals(FailureCode.INVALID_MAGIC, malformed.failureCode());
  }

  @Test
  void resolvesScalarTypesRawBitsAndSortsByUnsignedSpecId() {
    byte[] spirv = module(ByteOrder.LITTLE_ENDIAN, 30,
        instruction(OP_DECORATE, 12, DECORATION_SPEC_ID, 42),
        instruction(OP_DECORATE, 11, DECORATION_SPEC_ID, 2),
        instruction(OP_DECORATE, 10, DECORATION_SPEC_ID, 7),
        instruction(OP_TYPE_BOOL, 1),
        instruction(OP_TYPE_INT, 2, 32, 1),
        instruction(OP_TYPE_FLOAT, 3, 64),
        instruction(OP_SPEC_CONSTANT, 3, 12,
            0x89abcdef, 0x01234567),
        instruction(OP_SPEC_CONSTANT_TRUE, 1, 10),
        instruction(OP_SPEC_CONSTANT, 2, 11, 0x80000001));

    Scanned result = scanned(spirv);
    assertTrue(result.allDecoratedSpecIdsRepresentable());
    assertEquals(List.of(2L, 7L, 42L), result.constants().stream()
        .map(value -> value.specId().orElseThrow()).toList());

    SpecializationConstant signed = result.constants().get(0);
    assertEquals(ScalarType.INT32, signed.scalarType());
    assertEquals(32, signed.bitWidth());
    assertEquals(List.of(0x80000001L), signed.defaultLiteralWords());
    assertEquals(0x80000001L, signed.rawDefaultBits().orElseThrow());
    assertTrue(signed.decoratedSpecIdRepresentable());

    SpecializationConstant bool = result.constants().get(1);
    assertEquals(ConstantKind.BOOLEAN_TRUE, bool.kind());
    assertEquals(ScalarType.BOOL, bool.scalarType());
    assertEquals(List.of(1L), bool.defaultLiteralWords());
    assertEquals(1L, bool.rawDefaultBits().orElseThrow());

    SpecializationConstant doubleValue = result.constants().get(2);
    assertEquals(ScalarType.FLOAT64, doubleValue.scalarType());
    assertEquals(List.of(0x89abcdefL, 0x01234567L),
        doubleValue.defaultLiteralWords());
    assertEquals(0x0123456789abcdefL,
        doubleValue.rawDefaultBits().orElseThrow());
  }

  @Test
  void resolvesEveryPipelineScalarVocabularyValue() {
    byte[] spirv = module(ByteOrder.BIG_ENDIAN, 40,
        instruction(OP_DECORATE, 10, DECORATION_SPEC_ID, 0),
        instruction(OP_DECORATE, 11, DECORATION_SPEC_ID, 1),
        instruction(OP_DECORATE, 12, DECORATION_SPEC_ID, 2),
        instruction(OP_DECORATE, 13, DECORATION_SPEC_ID, 3),
        instruction(OP_DECORATE, 14, DECORATION_SPEC_ID, 4),
        instruction(OP_DECORATE, 15, DECORATION_SPEC_ID, 5),
        instruction(OP_DECORATE, 16, DECORATION_SPEC_ID, 6),
        instruction(OP_TYPE_BOOL, 1),
        instruction(OP_TYPE_INT, 2, 32, 1),
        instruction(OP_TYPE_INT, 3, 32, 0),
        instruction(OP_TYPE_FLOAT, 4, 32),
        instruction(OP_TYPE_INT, 5, 64, 1),
        instruction(OP_TYPE_INT, 6, 64, 0),
        instruction(OP_TYPE_FLOAT, 7, 64),
        instruction(OP_SPEC_CONSTANT_FALSE, 1, 10),
        instruction(OP_SPEC_CONSTANT, 2, 11, 1),
        instruction(OP_SPEC_CONSTANT, 3, 12, 2),
        instruction(OP_SPEC_CONSTANT, 4, 13, 3),
        instruction(OP_SPEC_CONSTANT, 5, 14, 4, 5),
        instruction(OP_SPEC_CONSTANT, 6, 15, 6, 7),
        instruction(OP_SPEC_CONSTANT, 7, 16, 8, 9));

    Scanned result = scanned(spirv);
    assertEquals(List.of(ScalarType.BOOL, ScalarType.INT32,
            ScalarType.UINT32, ScalarType.FLOAT32, ScalarType.INT64,
            ScalarType.UINT64, ScalarType.FLOAT64),
        result.constants().stream()
            .map(SpecializationConstant::scalarType).toList());
    assertTrue(result.allDecoratedSpecIdsRepresentable());
  }

  @Test
  void detectsCompositeAndOperationWithoutInventingScalarDefaults() {
    byte[] spirv = module(ByteOrder.LITTLE_ENDIAN, 30,
        instruction(OP_DECORATE, 7, DECORATION_SPEC_ID, 9),
        instruction(OP_DECORATE, 8, DECORATION_SPEC_ID, 10),
        instruction(OP_TYPE_INT, 1, 32, 1),
        instruction(OP_TYPE_VECTOR, 2, 1, 2),
        instruction(OP_SPEC_CONSTANT, 1, 5, 20),
        instruction(OP_SPEC_CONSTANT, 1, 6, 22),
        instruction(OP_SPEC_CONSTANT_COMPOSITE, 2, 7, 5, 6),
        instruction(OP_SPEC_CONSTANT_OP, 1, 8, 128, 5, 6));

    Scanned result = scanned(spirv);
    assertFalse(result.allDecoratedSpecIdsRepresentable());
    SpecializationConstant composite = result.constants().get(0);
    assertEquals(ConstantKind.COMPOSITE, composite.kind());
    assertEquals(ScalarType.COMPOSITE, composite.scalarType());
    assertEquals(List.of(5L, 6L), composite.operandWords());
    assertTrue(composite.defaultLiteralWords().isEmpty());
    assertTrue(composite.rawDefaultBits().isEmpty());
    assertFalse(composite.decoratedSpecIdRepresentable());

    SpecializationConstant operation = result.constants().get(1);
    assertEquals(ConstantKind.OPERATION, operation.kind());
    assertEquals(List.of(5L, 6L), operation.operandWords());
    assertEquals(128L, operation.operationOpcode().orElseThrow());
    assertTrue(operation.rawDefaultBits().isEmpty());
    assertFalse(operation.decoratedSpecIdRepresentable());
  }

  @Test
  void unsupportedScalarWidthIsVisibleAndFailClosedForPipelineMapping() {
    Scanned result = scanned(module(ByteOrder.LITTLE_ENDIAN, 10,
        instruction(OP_DECORATE, 2, DECORATION_SPEC_ID, 1),
        instruction(OP_TYPE_INT, 1, 16, 0),
        instruction(OP_SPEC_CONSTANT, 1, 2, 0xabcd)));

    SpecializationConstant constant = result.constants().getFirst();
    assertEquals(ScalarType.UNSUPPORTED_SCALAR, constant.scalarType());
    assertEquals(0xabcdL, constant.rawDefaultBits().orElseThrow());
    assertFalse(constant.decoratedSpecIdRepresentable());
    assertFalse(result.allDecoratedSpecIdsRepresentable());
  }

  @Test
  void resultIsIndependentOfDeclarationAndDecorationOrder() {
    int[] type = instruction(OP_TYPE_INT, 1, 32, 0);
    int[] firstConstant = instruction(OP_SPEC_CONSTANT, 1, 2, 10);
    int[] secondConstant = instruction(OP_SPEC_CONSTANT, 1, 3, 11);
    int[] firstDecoration = instruction(
        OP_DECORATE, 2, DECORATION_SPEC_ID, 20);
    int[] secondDecoration = instruction(
        OP_DECORATE, 3, DECORATION_SPEC_ID, 10);

    Scanned first = scanned(module(ByteOrder.LITTLE_ENDIAN, 10,
        firstDecoration, secondDecoration, type,
        firstConstant, secondConstant));
    Scanned second = scanned(module(ByteOrder.LITTLE_ENDIAN, 10,
        secondDecoration, firstDecoration, type,
        secondConstant, firstConstant));

    assertEquals(first.constants(), second.constants());
    assertEquals(List.of(10L, 20L), first.constants().stream()
        .map(value -> value.specId().orElseThrow()).toList());
  }

  @Test
  void rejectsBrokenFramingAndConsumedInstructionShapes() {
    assertFailure(FailureCode.INVALID_SIZE, new byte[19]);
    assertFailure(FailureCode.ZERO_WORD_COUNT,
        moduleWords(ByteOrder.LITTLE_ENDIAN, 2, 0));
    assertFailure(FailureCode.TRUNCATED_INSTRUCTION,
        moduleWords(ByteOrder.LITTLE_ENDIAN, 3,
            instructionHeader(OP_SPEC_CONSTANT, 4), 1));
    assertFailure(FailureCode.INVALID_OPERAND_COUNT,
        module(ByteOrder.LITTLE_ENDIAN, 3,
            instruction(OP_TYPE_BOOL, 1, 2)));
    assertFailure(FailureCode.INVALID_ID,
        module(ByteOrder.LITTLE_ENDIAN, 2,
            instruction(OP_TYPE_BOOL, 2)));
  }

  @Test
  void rejectsAmbiguousOrUnresolvedSpecializationMetadata() {
    assertFailure(FailureCode.ORPHAN_SPEC_ID_DECORATION,
        module(ByteOrder.LITTLE_ENDIAN, 5,
            instruction(OP_DECORATE, 2, DECORATION_SPEC_ID, 1),
            instruction(OP_TYPE_INT, 1, 32, 0)));
    assertFailure(FailureCode.DUPLICATE_SPEC_ID_DECORATION,
        module(ByteOrder.LITTLE_ENDIAN, 5,
            instruction(OP_DECORATE, 2, DECORATION_SPEC_ID, 1),
            instruction(OP_DECORATE, 2, DECORATION_SPEC_ID, 2),
            instruction(OP_TYPE_INT, 1, 32, 0),
            instruction(OP_SPEC_CONSTANT, 1, 2, 0)));
    assertFailure(FailureCode.DUPLICATE_SPEC_ID,
        module(ByteOrder.LITTLE_ENDIAN, 6,
            instruction(OP_DECORATE, 2, DECORATION_SPEC_ID, 1),
            instruction(OP_DECORATE, 3, DECORATION_SPEC_ID, 1),
            instruction(OP_TYPE_INT, 1, 32, 0),
            instruction(OP_SPEC_CONSTANT, 1, 2, 0),
            instruction(OP_SPEC_CONSTANT, 1, 3, 0)));
    assertFailure(FailureCode.UNRESOLVED_RESULT_TYPE,
        module(ByteOrder.LITTLE_ENDIAN, 5,
            instruction(OP_SPEC_CONSTANT, 1, 2, 0)));
    assertFailure(FailureCode.INVALID_SCALAR_DEFAULT,
        module(ByteOrder.LITTLE_ENDIAN, 5,
            instruction(OP_TYPE_INT, 1, 64, 0),
            instruction(OP_SPEC_CONSTANT, 1, 2, 0)));
  }

  private static Scanned scanned(byte[] spirv) {
    return assertInstanceOf(Scanned.class,
        SpirvSpecializationScanner.scan(spirv));
  }

  private static Malformed malformed(byte[] spirv) {
    return assertInstanceOf(Malformed.class,
        SpirvSpecializationScanner.scan(spirv));
  }

  private static void assertFailure(FailureCode expected, byte[] spirv) {
    assertEquals(expected, malformed(spirv).failureCode());
  }

  private static int[] instruction(int opcode, int... operands) {
    int[] words = new int[operands.length + 1];
    words[0] = instructionHeader(opcode, words.length);
    System.arraycopy(operands, 0, words, 1, operands.length);
    return words;
  }

  private static int instructionHeader(int opcode, int wordCount) {
    return (wordCount << 16) | opcode;
  }

  private static byte[] module(ByteOrder order, int idBound,
      int[]... instructions) {
    int wordCount = 5;
    for (int[] instruction : instructions) {
      wordCount += instruction.length;
    }
    ByteBuffer buffer = moduleHeader(order, idBound, wordCount);
    for (int[] instruction : instructions) {
      for (int word : instruction) {
        buffer.putInt(word);
      }
    }
    return buffer.array();
  }

  private static byte[] moduleWords(ByteOrder order, int idBound,
      int... bodyWords) {
    ByteBuffer buffer = moduleHeader(order, idBound, 5 + bodyWords.length);
    for (int word : bodyWords) {
      buffer.putInt(word);
    }
    return buffer.array();
  }

  private static ByteBuffer moduleHeader(ByteOrder order, int idBound,
      int wordCount) {
    return ByteBuffer.allocate(wordCount * Integer.BYTES).order(order)
        .putInt(0x07230203)
        .putInt(0x00010000)
        .putInt(0)
        .putInt(idBound)
        .putInt(0);
  }
}

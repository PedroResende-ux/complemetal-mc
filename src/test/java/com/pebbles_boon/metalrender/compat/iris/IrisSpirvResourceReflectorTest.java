package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.Access;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ArrayKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DescriptorAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ImageType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ScalarKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ScalarType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StorageClass;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StructType;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.UniformLocation;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceReflector.FailureCode;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceReflector.ReflectionResult;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceReflector.Status;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisSpirvResourceReflectorTest {
  private static final int OP_NAME = 5;
  private static final int OP_TYPE_FLOAT = 22;
  private static final int OP_TYPE_VECTOR = 23;
  private static final int OP_TYPE_IMAGE = 25;
  private static final int OP_TYPE_SAMPLER = 26;
  private static final int OP_TYPE_SAMPLED_IMAGE = 27;
  private static final int OP_TYPE_ARRAY = 28;
  private static final int OP_TYPE_RUNTIME_ARRAY = 29;
  private static final int OP_TYPE_STRUCT = 30;
  private static final int OP_TYPE_POINTER = 32;
  private static final int OP_CONSTANT = 43;
  private static final int OP_SPEC_CONSTANT = 50;
  private static final int OP_VARIABLE = 59;
  private static final int OP_DECORATE = 71;
  private static final int OP_MEMBER_DECORATE = 72;

  private static final int BLOCK = 2;
  private static final int ARRAY_STRIDE = 6;
  private static final int NON_WRITABLE = 24;
  private static final int NON_READABLE = 25;
  private static final int LOCATION = 30;
  private static final int BINDING = 33;
  private static final int DESCRIPTOR_SET = 34;
  private static final int OFFSET = 35;

  @Test
  void reflectsEveryRequiredResourceKindAndStructuralTypeDetail() {
    ReflectionResult result = IrisSpirvResourceReflector.reflect(
        completeModule("albedo", 4, 3, false, ByteOrder.LITTLE_ENDIAN)
            .build(ByteOrder.LITTLE_ENDIAN));

    assertEquals(Status.SUCCESS, result.status(), result.detail());
    IrisSpirvResourceLayout layout = result.layout().orElseThrow();
    assertEquals(8, layout.resources().size());
    assertTrue(layout.diagnosticNamesComplete());

    ResourceBinding uniform = resource(layout, ResourceKind.UNIFORM);
    assertEquals(new UniformLocation(0, 7), uniform.address());
    assertEquals(StorageClass.UNIFORM_CONSTANT, uniform.storageClass());
    assertEquals(new ScalarType(ScalarKind.FLOAT, 32), uniform.baseType());

    ResourceBinding sampled = resource(layout,
        new DescriptorAddress(2, 3));
    assertEquals(ResourceKind.SAMPLED_IMAGE, sampled.kind());
    assertEquals(Access.READ_ONLY, sampled.access());
    assertEquals("albedo",
        layout.diagnosticName(sampled.address()).orElseThrow());

    ResourceBinding sampler = resource(layout, ResourceKind.SAMPLER);
    assertEquals(new DescriptorAddress(1, 4), sampler.address());
    assertEquals(Access.READ_ONLY, sampler.access());

    ResourceBinding texture = resource(layout, ResourceKind.TEXTURE);
    assertEquals(new DescriptorAddress(1, 5), texture.address());
    ImageType textureType = assertInstanceOf(ImageType.class,
        texture.baseType());
    assertEquals(ImageFormat.UNKNOWN, textureType.format());

    ResourceBinding storageImage = resource(layout,
        ResourceKind.STORAGE_IMAGE);
    assertEquals(new DescriptorAddress(1, 6), storageImage.address());
    assertEquals(Access.READ_ONLY, storageImage.access());
    assertEquals(ImageFormat.RGBA32F,
        assertInstanceOf(ImageType.class,
            storageImage.baseType()).format());

    ResourceBinding uniformBuffer = resource(layout,
        ResourceKind.UNIFORM_BUFFER);
    StructType ubo = assertInstanceOf(StructType.class,
        uniformBuffer.baseType());
    assertEquals(0, ubo.members().getFirst().offsetBytes());
    assertEquals(Access.READ_ONLY, uniformBuffer.access());

    ResourceBinding storageBuffer = resource(layout,
        ResourceKind.STORAGE_BUFFER);
    StructType ssbo = assertInstanceOf(StructType.class,
        storageBuffer.baseType());
    assertEquals(Access.WRITE_ONLY, storageBuffer.access());
    assertEquals(ArrayKind.RUNTIME,
        ssbo.members().getFirst().type().arrays().getFirst().kind());
    assertEquals(16,
        ssbo.members().getFirst().type().arrays().getFirst().strideBytes());

    ResourceBinding descriptorArray = resource(layout,
        new DescriptorAddress(3, 2));
    assertEquals(ResourceKind.SAMPLED_IMAGE, descriptorArray.kind());
    assertEquals(BigInteger.valueOf(4), descriptorArray.arrays().getFirst()
        .literalLength().orElseThrow());
  }

  @Test
  void keyIsSortedAndIgnoresNamesIdsAndDeclarationOrder() {
    IrisSpirvResourceLayout first = successful(completeModule(
        "albedo", 4, 3, false, ByteOrder.LITTLE_ENDIAN));
    IrisSpirvResourceLayout renamedAndReordered = successful(completeModule(
        "renamed_only_for_debug", 4, 3, true,
        ByteOrder.LITTLE_ENDIAN));

    assertEquals(first, renamedAndReordered);
    assertEquals(first.hashCode(), renamedAndReordered.hashCode());
    assertEquals(first.key(), renamedAndReordered.key());
    assertNotEquals(first.diagnosticNames(),
        renamedAndReordered.diagnosticNames());

    IrisSpirvResourceLayout changedBinding = successful(completeModule(
        "albedo", 4, 9, false, ByteOrder.LITTLE_ENDIAN));
    IrisSpirvResourceLayout changedArrayLength = successful(completeModule(
        "albedo", 8, 3, false, ByteOrder.LITTLE_ENDIAN));
    assertNotEquals(first.key(), changedBinding.key());
    assertNotEquals(first.key(), changedArrayLength.key());
    assertTrue(first.key().sha256().matches("[0-9a-f]{64}"));
  }

  @Test
  void uniformBlockUsesInterfaceTypeNameInsteadOfInstanceName() {
    ModuleBuilder module = completeModule(
        "albedo", 4, 3, false, ByteOrder.LITTLE_ENDIAN);
    module.name(10, "CameraData");
    module.name(36, "cameraData");
    IrisSpirvResourceLayout layout = successful(module);
    ResourceBinding uniformBuffer = resource(layout,
        ResourceKind.UNIFORM_BUFFER);

    assertEquals("CameraData",
        layout.diagnosticName(uniformBuffer.address()).orElseThrow());
  }

  @Test
  void acceptsByteSwappedSpirvDeterministically() {
    ModuleBuilder module = completeModule(
        "albedo", 4, 3, false, ByteOrder.LITTLE_ENDIAN);
    IrisSpirvResourceLayout little = successful(
        module.build(ByteOrder.LITTLE_ENDIAN));
    IrisSpirvResourceLayout big = successful(
        module.build(ByteOrder.BIG_ENDIAN));

    assertEquals(little, big);
    assertEquals(little.key(), big.key());
  }

  @Test
  void boundsDiagnosticNamesWithoutChangingIdentity() {
    IrisSpirvResourceLayout shortName = successful(completeModule(
        "albedo", 4, 3, false, ByteOrder.LITTLE_ENDIAN));
    IrisSpirvResourceLayout longName = successful(completeModule(
        "x".repeat(
            IrisSpirvResourceReflector.MAX_SINGLE_DIAGNOSTIC_NAME_BYTES + 1),
        4, 3, false, ByteOrder.LITTLE_ENDIAN));

    assertFalse(longName.diagnosticNamesComplete());
    assertTrue(longName.diagnosticNames().isEmpty());
    assertEquals(shortName, longName);
    assertEquals(shortName.key(), longName.key());
  }

  @Test
  void malformedModulesReturnExplicitFailureInsteadOfThrowing() {
    assertFailure(null, Status.MALFORMED, FailureCode.NULL_INPUT);
    assertFailure(new byte[19], Status.MALFORMED,
        FailureCode.TRUNCATED_HEADER);

    ModuleBuilder badMagic = new ModuleBuilder(8);
    byte[] badMagicBytes = badMagic.build(ByteOrder.LITTLE_ENDIAN);
    badMagicBytes[0] = 0;
    assertFailure(badMagicBytes, Status.MALFORMED, FailureCode.BAD_MAGIC);

    ModuleBuilder zeroWord = new ModuleBuilder(8);
    zeroWord.rawWord(0);
    assertFailure(zeroWord.build(ByteOrder.LITTLE_ENDIAN), Status.MALFORMED,
        FailureCode.MALFORMED_INSTRUCTION);

    ModuleBuilder conflictingBinding = minimalSampledImage(1);
    conflictingBinding.decorate(20, BINDING, 1);
    conflictingBinding.decorate(20, BINDING, 2);
    assertFailure(conflictingBinding.build(ByteOrder.LITTLE_ENDIAN),
        Status.MALFORMED, FailureCode.CONFLICTING_DECORATION);
  }

  @Test
  void unsupportedConstructsFailClosedWithSpecificCodes() {
    ModuleBuilder excessiveBound = new ModuleBuilder(
        IrisSpirvResourceReflector.MAX_ID_BOUND + 1);
    assertFailure(excessiveBound.build(ByteOrder.LITTLE_ENDIAN),
        Status.UNSUPPORTED, FailureCode.ID_BOUND_LIMIT);

    ModuleBuilder missingAddress = minimalSampledImage(1);
    assertFailure(missingAddress.build(ByteOrder.LITTLE_ENDIAN),
        Status.UNSUPPORTED, FailureCode.MISSING_RESOURCE_ADDRESS);

    ModuleBuilder ambiguousImage = new ModuleBuilder(32);
    ambiguousImage.instruction(OP_TYPE_FLOAT, 1, 32);
    ambiguousImage.instruction(OP_TYPE_IMAGE, 2, 1, 1, 0, 0, 0, 0, 0);
    ambiguousImage.instruction(OP_TYPE_POINTER, 3, 0, 2);
    ambiguousImage.instruction(OP_VARIABLE, 3, 20, 0);
    ambiguousImage.decorate(20, BINDING, 0);
    assertFailure(ambiguousImage.build(ByteOrder.LITTLE_ENDIAN),
        Status.UNSUPPORTED, FailureCode.UNSUPPORTED_IMAGE);

    ModuleBuilder specArray = new ModuleBuilder(32);
    specArray.instruction(OP_TYPE_FLOAT, 1, 32);
    specArray.instruction(21, 2, 32, 0);
    specArray.instruction(OP_SPEC_CONSTANT, 2, 3, 4);
    specArray.instruction(OP_TYPE_ARRAY, 4, 1, 3);
    specArray.instruction(OP_TYPE_POINTER, 5, 0, 4);
    specArray.instruction(OP_VARIABLE, 5, 20, 0);
    specArray.decorate(20, LOCATION, 0);
    assertFailure(specArray.build(ByteOrder.LITTLE_ENDIAN),
        Status.UNSUPPORTED, FailureCode.NON_LITERAL_ARRAY_LENGTH);

    ModuleBuilder decorationGroup = new ModuleBuilder(8);
    decorationGroup.instruction(73, 1);
    assertFailure(decorationGroup.build(ByteOrder.LITTLE_ENDIAN),
        Status.UNSUPPORTED, FailureCode.DECORATION_GROUP);
  }

  private static ModuleBuilder completeModule(String sampledImageName,
      int descriptorArrayLength, int sampledImageBinding,
      boolean reverseVariables, ByteOrder ignored) {
    ModuleBuilder module = new ModuleBuilder(64);
    module.instruction(OP_TYPE_FLOAT, 1, 32);
    module.instruction(OP_TYPE_VECTOR, 2, 1, 4);
    // Sampled 2D texture.
    module.instruction(OP_TYPE_IMAGE, 3, 1, 1, 0, 0, 0, 1, 0);
    module.instruction(OP_TYPE_SAMPLED_IMAGE, 4, 3);
    module.instruction(OP_TYPE_SAMPLER, 5);
    // Read-only rgba32f storage image.
    module.instruction(OP_TYPE_IMAGE, 6, 1, 1, 0, 0, 0, 2, 1, 0);
    // Signed literal lengths are valid too and must not be mistaken for
    // specialization constants or negative values.
    module.instruction(21, 7, 32, 1);
    module.instruction(OP_CONSTANT, 7, 8, descriptorArrayLength);
    module.instruction(OP_TYPE_ARRAY, 9, 4, 8);
    module.instruction(OP_TYPE_STRUCT, 10, 2);
    module.instruction(OP_TYPE_RUNTIME_ARRAY, 11, 2);
    module.instruction(OP_TYPE_STRUCT, 12, 11);

    module.instruction(OP_TYPE_POINTER, 20, 0, 1);
    module.instruction(OP_TYPE_POINTER, 21, 0, 4);
    module.instruction(OP_TYPE_POINTER, 22, 0, 5);
    module.instruction(OP_TYPE_POINTER, 23, 0, 3);
    module.instruction(OP_TYPE_POINTER, 24, 0, 6);
    module.instruction(OP_TYPE_POINTER, 25, 2, 10);
    module.instruction(OP_TYPE_POINTER, 26, 12, 12);
    module.instruction(OP_TYPE_POINTER, 27, 0, 9);

    module.decorate(10, BLOCK);
    module.memberDecorate(10, 0, OFFSET, 0);
    module.decorate(11, ARRAY_STRIDE, 16);
    module.decorate(12, BLOCK);
    module.memberDecorate(12, 0, OFFSET, 0);

    List<int[]> variables = new ArrayList<>(List.of(
        new int[]{20, 31, 0}, new int[]{21, 32, 0},
        new int[]{22, 33, 0}, new int[]{23, 34, 0},
        new int[]{24, 35, 0}, new int[]{25, 36, 2},
        new int[]{26, 37, 12}, new int[]{27, 38, 0}));
    if (reverseVariables) {
      Collections.reverse(variables);
    }
    for (int[] variable : variables) {
      module.instruction(OP_VARIABLE, variable);
    }

    module.decorate(31, DESCRIPTOR_SET, 0);
    module.decorate(31, LOCATION, 7);
    module.decorate(32, DESCRIPTOR_SET, 2);
    module.decorate(32, BINDING, sampledImageBinding);
    module.decorate(33, DESCRIPTOR_SET, 1);
    module.decorate(33, BINDING, 4);
    module.decorate(34, DESCRIPTOR_SET, 1);
    module.decorate(34, BINDING, 5);
    module.decorate(35, DESCRIPTOR_SET, 1);
    module.decorate(35, BINDING, 6);
    module.decorate(35, NON_WRITABLE);
    module.decorate(36, DESCRIPTOR_SET, 0);
    module.decorate(36, BINDING, 0);
    module.decorate(37, DESCRIPTOR_SET, 0);
    module.decorate(37, BINDING, 1);
    module.decorate(37, NON_READABLE);
    module.decorate(38, DESCRIPTOR_SET, 3);
    module.decorate(38, BINDING, 2);
    module.name(32, sampledImageName);
    return module;
  }

  private static ModuleBuilder minimalSampledImage(int sampledOperand) {
    ModuleBuilder module = new ModuleBuilder(32);
    module.instruction(OP_TYPE_FLOAT, 1, 32);
    module.instruction(OP_TYPE_IMAGE, 2, 1, 1, 0, 0, 0,
        sampledOperand, 0);
    module.instruction(OP_TYPE_SAMPLED_IMAGE, 3, 2);
    module.instruction(OP_TYPE_POINTER, 4, 0, 3);
    module.instruction(OP_VARIABLE, 4, 20, 0);
    return module;
  }

  private static IrisSpirvResourceLayout successful(ModuleBuilder module) {
    return successful(module.build(ByteOrder.LITTLE_ENDIAN));
  }

  private static IrisSpirvResourceLayout successful(byte[] module) {
    ReflectionResult result = IrisSpirvResourceReflector.reflect(module);
    assertEquals(Status.SUCCESS, result.status(), result.detail());
    return result.layout().orElseThrow();
  }

  private static ResourceBinding resource(IrisSpirvResourceLayout layout,
      ResourceKind kind) {
    return layout.resources().stream()
        .filter(resource -> resource.kind() == kind)
        .findFirst().orElseThrow();
  }

  private static ResourceBinding resource(IrisSpirvResourceLayout layout,
      IrisSpirvResourceLayout.ResourceAddress address) {
    return layout.resources().stream()
        .filter(resource -> resource.address().equals(address))
        .findFirst().orElseThrow();
  }

  private static void assertFailure(byte[] module, Status status,
      FailureCode code) {
    ReflectionResult result = IrisSpirvResourceReflector.reflect(module);
    assertEquals(status, result.status(), result.detail());
    assertEquals(code, result.failureCode(), result.detail());
    assertTrue(result.layout().isEmpty());
    assertFalse(result.detail().isBlank());
  }

  private static final class ModuleBuilder {
    private static final int MAGIC = 0x07230203;
    private static final int VERSION_1_0 = 0x00010000;

    private final List<Integer> words = new ArrayList<>();

    private ModuleBuilder(int bound) {
      words.add(MAGIC);
      words.add(VERSION_1_0);
      words.add(0);
      words.add(bound);
      words.add(0);
    }

    private void instruction(int opcode, int... operands) {
      words.add((operands.length + 1) << 16 | opcode);
      for (int operand : operands) {
        words.add(operand);
      }
    }

    private void rawWord(int value) {
      words.add(value);
    }

    private void decorate(int target, int decoration, int... operands) {
      int[] all = new int[2 + operands.length];
      all[0] = target;
      all[1] = decoration;
      System.arraycopy(operands, 0, all, 2, operands.length);
      instruction(OP_DECORATE, all);
    }

    private void memberDecorate(int target, int member, int decoration,
        int... operands) {
      int[] all = new int[3 + operands.length];
      all[0] = target;
      all[1] = member;
      all[2] = decoration;
      System.arraycopy(operands, 0, all, 3, operands.length);
      instruction(OP_MEMBER_DECORATE, all);
    }

    private void name(int target, String value) {
      byte[] text = (value + '\0').getBytes(StandardCharsets.UTF_8);
      int stringWords = (text.length + 3) / 4;
      int[] operands = new int[1 + stringWords];
      operands[0] = target;
      for (int index = 0; index < text.length; index++) {
        operands[1 + index / 4] |= (text[index] & 0xFF) << (index % 4 * 8);
      }
      instruction(OP_NAME, operands);
    }

    private byte[] build(ByteOrder order) {
      ByteBuffer output = ByteBuffer.allocate(words.size() * Integer.BYTES)
          .order(order);
      for (int word : words) {
        output.putInt(word);
      }
      return output.array();
    }
  }
}

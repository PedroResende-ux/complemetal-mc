package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisMetalPipelineKeyTest {
  private static final String A = "a".repeat(64);
  private static final String B = "b".repeat(64);
  private static final String C = "c".repeat(64);
  private static final String D = "d".repeat(64);
  private static final IrisTranslationProfile PROFILE =
      IrisTranslationProfile.LWJGL_3_3_3_METAL_3_ARGUMENT_BUFFERS;

  @Test
  void keyIsDeterministicAndIncludesEveryCompatibilityBoundary() {
    IrisMetalPipelineKey first = key(A, B, C, D);
    assertEquals(first, key(A, B, C, D));
    assertNotEquals(first, key("e".repeat(64), B, C, D));
    assertNotEquals(first, key(A, "e".repeat(64), C, D));
    assertNotEquals(first, key(A, B, "e".repeat(64), D));
    assertNotEquals(first, key(A, B, C, "e".repeat(64)));
  }

  @Test
  void rejectsAnUnkeyedDeviceCompilerIdentity() {
    assertThrows(IllegalArgumentException.class,
        () -> key("Apple M4", B, C, D));
  }

  @Test
  void argumentBufferAbiParticipatesInTheProductionPipelineKey() {
    IrisMslArgumentLayout first = argumentLayout(0);
    IrisMslArgumentLayout second = argumentLayout(1);
    IrisMetalPipelineKey firstKey = IrisMetalPipelineKey.from(A,
        new IrisShaderCacheKey(B), new IrisPipelineStateKey(C),
        new IrisProgramResourceLayoutKey(D), first, PROFILE);
    IrisMetalPipelineKey secondKey = IrisMetalPipelineKey.from(A,
        new IrisShaderCacheKey(B), new IrisPipelineStateKey(C),
        new IrisProgramResourceLayoutKey(D), second, PROFILE);
    assertNotEquals(firstKey, secondKey);
  }

  @Test
  void descriptorHasStrictHeaderAndIsDeterministic() {
    byte[] first = IrisMetalPipelineDescriptorEncoder.encode(state());
    byte[] second = IrisMetalPipelineDescriptorEncoder.encode(state());
    assertTrue(java.util.Arrays.equals(first, second));
    ByteBuffer header = ByteBuffer.wrap(first);
    assertEquals(IrisMetalPipelineDescriptorEncoder.MAGIC,
        header.getInt());
    assertEquals(IrisMetalPipelineDescriptorEncoder.SCHEMA,
        header.getInt());
    assertEquals(IrisPipelineState.PassKind.LINKED_GRAPHICS.ordinal(),
        header.getInt());
  }

  private static IrisMetalPipelineKey key(String device, String shader,
      String state, String layout) {
    return IrisMetalPipelineKey.from(device,
        new IrisShaderCacheKey(shader), new IrisPipelineStateKey(state),
        new IrisProgramResourceLayoutKey(layout), PROFILE);
  }

  private static IrisMslArgumentLayout argumentLayout(int id) {
    return new IrisMslArgumentLayout(List.of(
        new IrisMslArgumentLayout.StageLayout(IrisShaderStage.FRAGMENT,
            List.of(new IrisMslArgumentLayout.ArgumentBinding(
                new IrisSpirvResourceLayout.DescriptorAddress(0, 0),
                IrisSpirvResourceLayout.ResourceKind.UNIFORM_BUFFER,
                0, id, -1)))));
  }

  static IrisPipelineState state() {
    IrisPipelineState.BlendEquation blend =
        new IrisPipelineState.BlendEquation(
            IrisPipelineState.BlendOperation.ADD,
            IrisPipelineState.BlendFactor.SRC_ALPHA,
            IrisPipelineState.BlendFactor.ONE_MINUS_SRC_ALPHA);
    return new IrisPipelineState(
        new IrisPipelineState.PassIdentity(
            IrisPipelineState.PassKind.LINKED_GRAPHICS, A, false),
        List.of(new IrisPipelineState.VertexBufferLayout(0, 16,
            IrisPipelineState.StepFunction.PER_VERTEX, 0)),
        List.of(new IrisPipelineState.VertexAttribute(0, 0, 0,
            new IrisPipelineState.DataFormat("rgb32-float"))),
        List.of(new IrisPipelineState.ColorAttachment(0,
            new IrisPipelineState.DataFormat("rgba8-unorm"), 0xf,
            new IrisPipelineState.BlendState(true, blend, blend))),
        Optional.of(new IrisPipelineState.DataFormat("d32-float")),
        Optional.empty(), 1, -1L, false, 1.0F, false, false, false,
        new IrisPipelineState.DepthState(true,
            IrisPipelineState.CompareOperation.LESS_EQUAL, true),
        IrisPipelineState.StencilState.disabled(),
        new IrisPipelineState.RasterState(true,
            IrisPipelineState.CullMode.BACK,
            IrisPipelineState.FrontFace.COUNTER_CLOCKWISE,
            IrisPipelineState.FillMode.FILL,
            IrisPipelineState.FillMode.FILL,
            IrisPipelineState.DepthClipMode.CLIP, 0, 0, 0, 0),
        new IrisPipelineState.PrimitiveState(
            IrisPipelineState.PrimitiveTopology.TRIANGLE,
            IrisPipelineState.PrimitiveRestartMode.NONE, 0),
        true, List.of());
  }
}

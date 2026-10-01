package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IrisMetalGraphFramePacketEncoderTest {
  @Test
  void encodesDeterministicBoundedFrameBatch() {
    IrisMetalGraphFramePacketEncoder.Frame frame =
        new IrisMetalGraphFramePacketEncoder.Frame(7, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(4, 92),
            new IrisMetalGraphFramePacketEncoder.Resource(1, 91)), List.of(
                new IrisMetalGraphFramePacketEncoder.Clear(1,
                    IrisMetalGraphFramePacketEncoder.Aspect.COLOR,
                    IrisClearCommand.ValueKind.FLOAT32,
                    List.of(raw(0.25F), raw(0.5F), raw(1.0F), raw(1.0F)),
                    Optional.empty()),
                new IrisMetalGraphFramePacketEncoder.Barrier(0x28),
                new IrisMetalGraphFramePacketEncoder.CopyTexture(
                    1, 4, 0, 0, 0, 0, 0, 0, 32, 16),
                new IrisMetalGraphFramePacketEncoder.GenerateMipmaps(4)), 4);

    byte[] first = IrisMetalGraphFramePacketEncoder.encode(frame);
    byte[] second = IrisMetalGraphFramePacketEncoder.encode(frame);

    assertTrue(java.util.Arrays.equals(first, second));
    ByteBuffer header = ByteBuffer.wrap(first);
    assertEquals(IrisMetalGraphFramePacketEncoder.MAGIC, header.getInt());
    assertEquals(IrisMetalGraphFramePacketEncoder.SCHEMA, header.getInt());
    assertEquals(7, header.getLong());
    assertEquals(4, header.getInt());
    assertEquals(IrisMetalGraphFramePacketEncoder.NO_PRESENTATION,
        header.getInt());
    assertEquals(2, header.getInt());
    assertEquals(1, header.getInt());
    assertEquals(91, header.getLong());
  }

  @Test
  void encodesClearMipLevel() {
    IrisMetalGraphFramePacketEncoder.Frame frame =
        new IrisMetalGraphFramePacketEncoder.Frame(13, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(3, 103)), List.of(
                new IrisMetalGraphFramePacketEncoder.Clear(3, 2,
                    IrisMetalGraphFramePacketEncoder.Aspect.COLOR,
                    IrisClearCommand.ValueKind.FLOAT32,
                    List.of(0L, 0L, 0L, 0L), Optional.empty())), -1);

    ByteBuffer buffer = ByteBuffer.wrap(
        IrisMetalGraphFramePacketEncoder.encode(frame));
    buffer.position(28 + 12 + 4 + 4 + 4);
    assertEquals(1, buffer.getInt());
    assertEquals(3, buffer.getInt());
    assertEquals(2, buffer.getInt());
  }

  @Test
  void encodesDrawAttachmentMipLevels() {
    IrisMetalGraphFramePacketEncoder.Draw draw =
        new IrisMetalGraphFramePacketEncoder.Draw(
            "a".repeat(64), new byte[] {7},
            List.of(new IrisMetalGraphFramePacketEncoder.ColorTarget(0, 3, 2)),
            4, 1, 5, 1, Map.of(17, 6));
    IrisMetalGraphFramePacketEncoder.Frame frame =
        new IrisMetalGraphFramePacketEncoder.Frame(13, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(3, 103),
            new IrisMetalGraphFramePacketEncoder.Resource(4, 104),
            new IrisMetalGraphFramePacketEncoder.Resource(5, 105),
            new IrisMetalGraphFramePacketEncoder.Resource(6, 106)), List.of(draw),
            -1);

    ByteBuffer buffer = ByteBuffer.wrap(
        IrisMetalGraphFramePacketEncoder.encode(frame));
    buffer.position(88);
    assertEquals(5, buffer.getInt());
    assertEquals(64, buffer.getInt());
    buffer.position(buffer.position() + 64);
    assertEquals(1, buffer.getInt());
    assertEquals(7, buffer.get());
    assertEquals(1, buffer.getInt());
    assertEquals(0, buffer.getInt());
    assertEquals(3, buffer.getInt());
    assertEquals(2, buffer.getInt());
    assertEquals(4, buffer.getInt());
    assertEquals(1, buffer.getInt());
    assertEquals(5, buffer.getInt());
    assertEquals(1, buffer.getInt());
    assertEquals(17, buffer.getInt());
    assertEquals(6, buffer.getInt());
  }

  @Test
  void encodesComputeOperationWithDispatchResources() {
    IrisMetalGraphFramePacketEncoder.Compute compute =
        new IrisMetalGraphFramePacketEncoder.Compute(
            "a".repeat(64), new byte[] {1, 2, 3}, 8, 4, 2,
            List.of(1, 2));
    IrisMetalGraphFramePacketEncoder.Frame frame =
        new IrisMetalGraphFramePacketEncoder.Frame(9, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(1, 101),
            new IrisMetalGraphFramePacketEncoder.Resource(2, 102)), List.of(
                compute), -1);

    byte[] encoded = IrisMetalGraphFramePacketEncoder.encode(frame);
    ByteBuffer buffer = ByteBuffer.wrap(encoded);
    assertEquals(IrisMetalGraphFramePacketEncoder.MAGIC, buffer.getInt());
    assertEquals(IrisMetalGraphFramePacketEncoder.SCHEMA, buffer.getInt());
    assertEquals(9, buffer.getLong());
    assertEquals(-1, buffer.getInt());
    assertEquals(-1, buffer.getInt());
    assertEquals(2, buffer.getInt());
    assertEquals(0, buffer.getInt());
    assertEquals(0, buffer.getInt());
    assertEquals(1, buffer.getInt());
    assertEquals(6, buffer.getInt());
  }

  @Test
  void rejectsInvalidComputeDispatchDimensions() {
    assertThrows(IllegalArgumentException.class, () ->
        new IrisMetalGraphFramePacketEncoder.Compute(
            "b".repeat(64), new byte[] {1}, 0, 1, 1, List.of()));
  }

  @Test
  void rejectsMissingAndAliasedResources() {
    assertThrows(IllegalArgumentException.class, () ->
        new IrisMetalGraphFramePacketEncoder.Frame(1, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(0, 7),
            new IrisMetalGraphFramePacketEncoder.Resource(1, 7)), List.of(
                new IrisMetalGraphFramePacketEncoder.Barrier(0)), -1));
    assertThrows(IllegalArgumentException.class, () ->
        new IrisMetalGraphFramePacketEncoder.Frame(1, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(0, 7)), List.of(
                new IrisMetalGraphFramePacketEncoder.GenerateMipmaps(2)),
            -1));
    assertThrows(IllegalArgumentException.class, () ->
        new IrisMetalGraphFramePacketEncoder.Frame(1, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(0, 7)), List.of(
                new IrisMetalGraphFramePacketEncoder.Barrier(0)), -1, 1));
    assertThrows(IllegalArgumentException.class, () ->
        new IrisMetalGraphFramePacketEncoder.Frame(1, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(0, 7)), List.of(
                new IrisMetalGraphFramePacketEncoder.Barrier(0)), 0, 0));
  }

  @Test
  void encodesPresentationResourceSeparatelyFromReadback() {
    IrisMetalGraphFramePacketEncoder.Frame frame =
        new IrisMetalGraphFramePacketEncoder.Frame(11, List.of(
            new IrisMetalGraphFramePacketEncoder.Resource(3, 99)), List.of(
                new IrisMetalGraphFramePacketEncoder.Barrier(0)),
            IrisMetalGraphFramePacketEncoder.NO_READBACK, 3);

    ByteBuffer header = ByteBuffer.wrap(
        IrisMetalGraphFramePacketEncoder.encode(frame));
    assertEquals(IrisMetalGraphFramePacketEncoder.MAGIC, header.getInt());
    assertEquals(IrisMetalGraphFramePacketEncoder.SCHEMA, header.getInt());
    assertEquals(11, header.getLong());
    assertEquals(IrisMetalGraphFramePacketEncoder.NO_READBACK,
        header.getInt());
    assertEquals(3, header.getInt());
  }

  @Test
  void preservesFrameInputsWhenPromotingToPresentation() {
    IrisMetalGraphFramePacketEncoder.InputBuffer input =
        new IrisMetalGraphFramePacketEncoder.InputBuffer(0,
            new IrisShadowReplayBufferSnapshot.BufferImage(
                4, 17, 9, 0, new byte[32]));
    IrisMetalGraphFramePacketEncoder.Frame validation =
        new IrisMetalGraphFramePacketEncoder.Frame(11,
            List.of(new IrisMetalGraphFramePacketEncoder.Resource(3, 99)),
            List.of(input), List.of(new IrisMetalGraphFramePacketEncoder
                .InputTexture(0,
                    IrisGlTextureMirror.TextureSnapshot.fromReadback(
                        29, 4, new IrisGlTextureMirror.TextureMetadata(
                            "rgba8-unorm", 2, 2, 1, 4, 4), 0, 0,
                        new byte[16]))), List.of(
                new IrisMetalGraphFramePacketEncoder.Barrier(0)), 3,
            IrisMetalGraphFramePacketEncoder.NO_PRESENTATION);
    IrisMetalGraphFramePacketEncoder.Frame presentation =
        validation.asPresentation(3);

    assertEquals(List.of(input), presentation.inputBuffers());
    assertEquals(validation.inputTextures(), presentation.inputTextures());
    assertEquals(IrisMetalGraphFramePacketEncoder.NO_READBACK,
        presentation.readbackResourceId());
    assertEquals(3, presentation.presentationResourceId());
  }

  @Test
  void rejectsSharedFrameInputTexture() {
    assertThrows(IllegalArgumentException.class, () ->
        new IrisMetalGraphFramePacketEncoder.InputTexture(0,
            IrisGlTextureMirror.TextureSnapshot.fromGpuHandoff(
                29, 4, "rgba8-unorm", 2, 2, 4, 77)));
  }

  @Test
  void directPacketIsByteExactAndRejectsUseAfterRecycle() {
    IrisMetalGraphFramePacketEncoder.Frame frame =
        new IrisMetalGraphFramePacketEncoder.Frame(11,
            List.of(new IrisMetalGraphFramePacketEncoder.Resource(3, 99)),
            List.of(new IrisMetalGraphFramePacketEncoder.InputBuffer(0,
                new IrisShadowReplayBufferSnapshot.BufferImage(
                    4, 17, 9, 0, new byte[32]))),
            List.of(new IrisMetalGraphFramePacketEncoder.InputTexture(0,
                IrisGlTextureMirror.TextureSnapshot.fromReadback(
                    29, 4, new IrisGlTextureMirror.TextureMetadata(
                        "rgba8-unorm", 2, 2, 1, 4, 4), 0, 0,
                    new byte[16]))),
            List.of(new IrisMetalGraphFramePacketEncoder.Barrier(0)),
            IrisMetalGraphFramePacketEncoder.NO_READBACK, 3);
    byte[] expected = IrisMetalGraphFramePacketEncoder.encode(frame);
    IrisMetalGraphFramePacketEncoder.DirectPacket direct =
        IrisMetalGraphFramePacketEncoder.encodeDirect(frame);

    assertTrue(direct.nativeBuffer().isDirect());
    assertEquals(expected.length, direct.length());
    assertArrayEquals(expected, direct.copyBytes());
    direct.close();
    assertThrows(IllegalStateException.class, direct::copyBytes);
    direct.close();
  }

  @Test
  void rejectsGraphReferenceAsInlineFrameInputTexture() {
    assertThrows(IllegalArgumentException.class, () ->
        new IrisMetalGraphFramePacketEncoder.InputTexture(0,
            IrisGlTextureMirror.TextureSnapshot.fromGraphReference(
                29, 4, "rgba8-unorm", 2, 2, 4, 0, 0)));
  }

  private static long raw(float value) {
    return Integer.toUnsignedLong(Float.floatToRawIntBits(value));
  }
}

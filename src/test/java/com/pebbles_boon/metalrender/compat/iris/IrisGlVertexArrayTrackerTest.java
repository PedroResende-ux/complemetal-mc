package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisGlVertexArrayTrackerTest {
  @Test
  void resolvesDenseProgramBuffersFromLegacyVaoAttributes() {
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    mirror.clear();
    assertTrue(mirror.allocate(51, 48));
    assertTrue(mirror.write(51, 48, 0, 48, ByteBuffer.allocate(48)));
    assertTrue(mirror.allocate(52, 24));
    assertTrue(mirror.write(52, 24, 0, 24, ByteBuffer.allocate(24)));
    assertTrue(mirror.allocate(53, 12));
    assertTrue(mirror.write(53, 12, 0, 12, ByteBuffer.allocate(12)));

    IrisGlVertexArrayTracker tracker = new IrisGlVertexArrayTracker();
    tracker.reset();
    tracker.bindVertexArray(7);
    tracker.bindBuffer(IrisGlVertexArrayTracker.GL_ARRAY_BUFFER, 51);
    tracker.vertexAttribute(0, 3, 0x1406, false, 16, 0, false);
    tracker.enableAttribute(0);
    tracker.vertexAttribute(1, 4, 0x1401, true, 16, 12, false);
    tracker.enableAttribute(1);
    tracker.bindBuffer(IrisGlVertexArrayTracker.GL_ARRAY_BUFFER, 52);
    tracker.vertexAttribute(2, 3, 0x1406, false, 12, 0, false);
    tracker.enableAttribute(2);
    tracker.bindBuffer(IrisGlVertexArrayTracker.GL_ELEMENT_ARRAY_BUFFER, 53);

    IrisVertexInputBindings snapshot = tracker.snapshot(descriptor());
    assertTrue(snapshot.complete(), snapshot.incompleteReason());
    assertEquals(2, snapshot.vertexBuffers().size());
    assertEquals(51, snapshot.vertexBuffers().get(0).glBuffer());
    assertEquals(52, snapshot.vertexBuffers().get(1).glBuffer());
    assertEquals(53, snapshot.indexBuffer().orElseThrow().glBuffer());
  }

  @Test
  void rejectsAStaleOrMismatchedVaoWithoutGuessing() {
    IrisGlVertexArrayTracker tracker = new IrisGlVertexArrayTracker();
    tracker.reset();
    tracker.bindVertexArray(9);
    tracker.bindBuffer(IrisGlVertexArrayTracker.GL_ARRAY_BUFFER, 61);
    tracker.vertexAttribute(0, 3, 0x1406, false, 12, 0, false);
    tracker.enableAttribute(0);

    IrisVertexInputBindings snapshot = tracker.snapshot(descriptor());
    assertFalse(snapshot.complete());
    assertEquals("vao-attribute-layout-mismatch",
        snapshot.incompleteReason());
  }

  @Test
  void resolvesModernMojangVertexBindingsAndLegacyIndexBuffer() {
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    mirror.clear();
    assertTrue(mirror.allocate(71, 64));
    assertTrue(mirror.write(71, 64, 0, 64, ByteBuffer.allocate(64)));
    assertTrue(mirror.allocate(72, 12));
    assertTrue(mirror.write(72, 12, 0, 12, ByteBuffer.allocate(12)));

    IrisGlVertexArrayTracker tracker = new IrisGlVertexArrayTracker();
    tracker.reset();
    tracker.bindVertexArray(11);
    tracker.bindMojangVertexBuffers(List.of(
        new IrisGlVertexArrayTracker.MojangVertexBuffer(0, 71, 0, 64,
            mirror.generation(71), 16),
        new IrisGlVertexArrayTracker.MojangVertexBuffer(1, 71, 0, 64,
            mirror.generation(71), 12)));
    tracker.bindBuffer(IrisGlVertexArrayTracker.GL_ELEMENT_ARRAY_BUFFER, 72);

    IrisVertexInputBindings snapshot = tracker.snapshot(descriptor());
    assertTrue(snapshot.complete(), snapshot.incompleteReason());
    assertEquals(2, snapshot.vertexBuffers().size());
    assertEquals(71, snapshot.vertexBuffers().get(0).glBuffer());
    assertEquals(72, snapshot.indexBuffer().orElseThrow().glBuffer());
  }

  @Test
  void skipsSyntheticConstantLayoutsWhenReadingLegacyVao() {
    IrisGlBufferMirror mirror = IrisGlBufferMirror.global();
    mirror.clear();
    assertTrue(mirror.allocate(81, 32));
    assertTrue(mirror.write(81, 32, 0, 32, ByteBuffer.allocate(32)));

    IrisGlVertexArrayTracker tracker = new IrisGlVertexArrayTracker();
    tracker.reset();
    tracker.bindVertexArray(12);
    tracker.bindBuffer(IrisGlVertexArrayTracker.GL_ARRAY_BUFFER, 81);
    tracker.vertexAttribute(0, 3, 0x1406, false, 16, 0, false);
    tracker.enableAttribute(0);
    IrisProgramIdentityRegistry.ProgramDescriptor descriptor =
        new IrisProgramIdentityRegistry.ProgramDescriptor(
            PassKind.LINKED_GRAPHICS, "vao-constant", false,
            List.of(
                new VertexBufferLayout(0, 16, StepFunction.PER_VERTEX, 0),
                new VertexBufferLayout(1, 16, StepFunction.CONSTANT, 0)),
            List.of(
                new VertexAttribute(0, 0, 0,
                    new DataFormat("rgb32-float")),
                new VertexAttribute(4, 1, 0,
                    new DataFormat("rgba32-float"))));

    IrisVertexInputBindings snapshot = tracker.snapshot(descriptor);
    assertTrue(snapshot.complete(), snapshot.incompleteReason());
    assertEquals(1, snapshot.vertexBuffers().size());
  }

  private static IrisProgramIdentityRegistry.ProgramDescriptor descriptor() {
    return new IrisProgramIdentityRegistry.ProgramDescriptor(
        PassKind.LINKED_GRAPHICS, "vao-test", false,
        List.of(
            new VertexBufferLayout(0, 16, StepFunction.PER_VERTEX, 0),
            new VertexBufferLayout(1, 12, StepFunction.PER_VERTEX, 0)),
        List.of(
            new VertexAttribute(0, 0, 0,
                new DataFormat("rgb32-float")),
            new VertexAttribute(1, 0, 12,
                new DataFormat("rgba8-unorm")),
            new VertexAttribute(2, 1, 0,
                new DataFormat("rgb32-float"))));
  }
}

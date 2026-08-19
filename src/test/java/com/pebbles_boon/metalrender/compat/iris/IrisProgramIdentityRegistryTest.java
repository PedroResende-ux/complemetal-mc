package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.DataFormat;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.PassKind;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.StepFunction;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexAttribute;
import com.pebbles_boon.metalrender.compat.iris.IrisPipelineState.VertexBufferLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisProgramIdentityRegistryTest {
  @Test
  void programIdReuseIsGenerationSafe() {
    IrisProgramIdentityRegistry registry =
        new IrisProgramIdentityRegistry(4);
    IrisProgramIdentityRegistry.Registration first =
        registry.register(7, graphics("first"));
    registry.resolve(first, new IrisShaderCacheKey("1".repeat(64)),
        List.of(IrisShaderStage.VERTEX, IrisShaderStage.FRAGMENT));
    registry.delete(7);
    IrisProgramIdentityRegistry.Registration second =
        registry.register(7, graphics("second"));

    assertTrue(first.deleted());
    assertNotEquals(first.generation(), second.generation());
    assertEquals(second, registry.lookup(7).orElseThrow());
    assertTrue(first.resolved().isPresent());
    assertFalse(second.resolved().isPresent());
  }

  @Test
  void passIdentityIsStableAndIncludesLayoutAndOrigin() {
    IrisProgramIdentityRegistry.ProgramDescriptor base = graphics("terrain");
    IrisProgramIdentityRegistry.ProgramDescriptor same = graphics("terrain");
    IrisProgramIdentityRegistry.ProgramDescriptor fullscreen =
        new IrisProgramIdentityRegistry.ProgramDescriptor(
            PassKind.FULLSCREEN_GRAPHICS, "terrain", false,
            base.vertexBuffers(), base.vertexAttributes());

    assertEquals(IrisProgramIdentityRegistry.passIdentitySha256(base),
        IrisProgramIdentityRegistry.passIdentitySha256(same));
    assertNotEquals(IrisProgramIdentityRegistry.passIdentitySha256(base),
        IrisProgramIdentityRegistry.passIdentitySha256(fullscreen));
  }

  @Test
  void registryEvictsOldestGenerationWithinBound() {
    IrisProgramIdentityRegistry registry =
        new IrisProgramIdentityRegistry(2);
    IrisProgramIdentityRegistry.Registration first =
        registry.register(1, graphics("first"));
    registry.register(2, graphics("second"));
    registry.register(3, graphics("third"));

    assertTrue(first.deleted());
    assertFalse(registry.lookup(1).isPresent());
    assertEquals(2, registry.size());
    assertEquals(1, registry.evictions());
  }

  private static IrisProgramIdentityRegistry.ProgramDescriptor graphics(
      String name) {
    return new IrisProgramIdentityRegistry.ProgramDescriptor(
        PassKind.LINKED_GRAPHICS, name, false,
        List.of(new VertexBufferLayout(0, 16, StepFunction.PER_VERTEX, 0)),
        List.of(new VertexAttribute(0, 0, 0,
            new DataFormat("rgb32-float"))));
  }
}

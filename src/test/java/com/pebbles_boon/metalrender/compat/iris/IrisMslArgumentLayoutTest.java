package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.ArgumentBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisMslArgumentLayout.StageLayout;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DescriptorAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.UniformLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisMslArgumentLayoutTest {
  @Test
  void identityIsIndependentOfInputOrdering() {
    ArgumentBinding uniform = new ArgumentBinding(new UniformLocation(0, 7),
        ResourceKind.UNIFORM, 0, 1, -1);
    ArgumentBinding sampled = new ArgumentBinding(
        new DescriptorAddress(0, 3), ResourceKind.SAMPLED_IMAGE,
        0, 2, 3);
    IrisMslArgumentLayout first = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.FRAGMENT,
            List.of(sampled, uniform))));
    IrisMslArgumentLayout second = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.FRAGMENT,
            List.of(uniform, sampled))));

    assertEquals(first, second);
    assertEquals(first.sha256(), second.sha256());
    assertTrue(first.sha256().matches("[0-9a-f]{64}"));
  }

  @Test
  void sampledImageMayBeTextureOnlyForTexelFetch() {
    ArgumentBinding textureOnly = new ArgumentBinding(
        new DescriptorAddress(0, 9), ResourceKind.SAMPLED_IMAGE,
        0, 4, -1);

    IrisMslArgumentLayout layout = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.VERTEX, List.of(textureOnly))));

    assertEquals(-1,
        layout.stages().getFirst().bindings().getFirst().secondaryId());
  }

  @Test
  void rejectsArgumentIdAndSemanticAddressCollisions() {
    ArgumentBinding first = new ArgumentBinding(
        new DescriptorAddress(0, 1), ResourceKind.UNIFORM_BUFFER,
        0, 2, -1);
    ArgumentBinding sameId = new ArgumentBinding(
        new DescriptorAddress(0, 2), ResourceKind.STORAGE_BUFFER,
        0, 2, -1);
    ArgumentBinding sameAddress = new ArgumentBinding(
        new DescriptorAddress(0, 1), ResourceKind.STORAGE_BUFFER,
        0, 3, -1);

    assertThrows(IllegalArgumentException.class,
        () -> new StageLayout(IrisShaderStage.VERTEX,
            List.of(first, sameId)));
    assertThrows(IllegalArgumentException.class,
        () -> new StageLayout(IrisShaderStage.VERTEX,
            List.of(first, sameAddress)));
  }

  @Test
  void semanticMatchRequiresEveryAddressAndKind() {
    DescriptorAddress address = new DescriptorAddress(0, 1);
    IrisSpirvResourceLayout.TypeRef type =
        new IrisSpirvResourceLayout.TypeRef(
            List.of(), new IrisSpirvResourceLayout.StructType(List.of()));
    IrisSpirvResourceLayout semanticStage = new IrisSpirvResourceLayout(
        List.of(new IrisSpirvResourceLayout.ResourceBinding(address,
            ResourceKind.UNIFORM_BUFFER,
            IrisSpirvResourceLayout.StorageClass.UNIFORM,
            IrisSpirvResourceLayout.Access.READ_ONLY, type)),
        java.util.Map.of(), true);
    IrisProgramResourceLayout semantic = new IrisProgramResourceLayout(
        List.of(new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.VERTEX, semanticStage)));
    IrisMslArgumentLayout matching = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.VERTEX, List.of(
            new ArgumentBinding(address, ResourceKind.UNIFORM_BUFFER,
                0, 0, -1)))));
    IrisMslArgumentLayout wrongKind = new IrisMslArgumentLayout(List.of(
        new StageLayout(IrisShaderStage.VERTEX, List.of(
            new ArgumentBinding(address, ResourceKind.STORAGE_BUFFER,
                0, 0, -1)))));

    assertTrue(matching.semanticallyMatches(semantic));
    assertEquals(false, wrongKind.semanticallyMatches(semantic));
  }
}

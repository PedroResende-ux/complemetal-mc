package com.pebbles_boon.metalrender.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.Access;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.DescriptorAddress;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceBinding;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ResourceKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.ScalarKind;
import com.pebbles_boon.metalrender.compat.iris.IrisSpirvResourceLayout.StorageClass;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class IrisProgramResourceLayoutKeyTest {
  @Test
  void sortsStagesAndExcludesDiagnosticNamesFromIdentity() {
    IrisSpirvResourceLayout first = layout("first");
    IrisSpirvResourceLayout renamed = layout("renamed");
    IrisProgramResourceLayout a = new IrisProgramResourceLayout(List.of(
        new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.FRAGMENT, first),
        new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.VERTEX, first)));
    IrisProgramResourceLayout b = new IrisProgramResourceLayout(List.of(
        new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.VERTEX, renamed),
        new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.FRAGMENT, renamed)));

    assertEquals(a.key(), b.key());
    assertEquals(IrisShaderStage.VERTEX, a.stages().getFirst().stage());
    assertEquals(2, a.resourceCount());
  }

  @Test
  void stageVisibilityParticipatesInIdentity() {
    IrisSpirvResourceLayout layout = layout("buffer");
    IrisProgramResourceLayout vertex = new IrisProgramResourceLayout(
        List.of(new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.VERTEX, layout)));
    IrisProgramResourceLayout fragment = new IrisProgramResourceLayout(
        List.of(new IrisProgramResourceLayout.StageLayout(
            IrisShaderStage.FRAGMENT, layout)));
    assertNotEquals(vertex.key(), fragment.key());
  }

  private static IrisSpirvResourceLayout layout(String name) {
    DescriptorAddress address = new DescriptorAddress(0, 3);
    ResourceBinding binding = new ResourceBinding(address,
        ResourceKind.UNIFORM_BUFFER, StorageClass.UNIFORM,
        Access.READ_ONLY,
        IrisSpirvResourceLayout.TypeRef.scalar(ScalarKind.FLOAT, 32));
    return new IrisSpirvResourceLayout(List.of(binding),
        Map.of(address, name), true);
  }
}

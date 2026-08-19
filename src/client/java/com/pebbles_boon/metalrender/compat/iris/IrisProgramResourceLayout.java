package com.pebbles_boon.metalrender.compat.iris;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Immutable per-stage resource layout for one linked Iris program. */
public record IrisProgramResourceLayout(List<StageLayout> stages) {
  public IrisProgramResourceLayout {
    Objects.requireNonNull(stages, "stages");
    if (stages.isEmpty()) {
      throw new IllegalArgumentException("program layout requires a stage");
    }
    ArrayList<StageLayout> sorted = new ArrayList<>(stages.size());
    EnumSet<IrisShaderStage> seen = EnumSet.noneOf(IrisShaderStage.class);
    for (StageLayout stage : stages) {
      StageLayout checked = Objects.requireNonNull(stage, "stage layout");
      if (!seen.add(checked.stage())) {
        throw new IllegalArgumentException("duplicate resource stage");
      }
      sorted.add(checked);
    }
    sorted.sort(Comparator.comparing(StageLayout::stage));
    stages = List.copyOf(sorted);
  }

  public int resourceCount() {
    return stages.stream().mapToInt(
        stage -> stage.layout().resources().size()).sum();
  }

  public IrisProgramResourceLayoutKey key() {
    return IrisProgramResourceLayoutKey.from(this);
  }

  public record StageLayout(IrisShaderStage stage,
                            IrisSpirvResourceLayout layout) {
    public StageLayout {
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(layout, "layout");
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.compat.iris.IrisGlStateSnapshot.StateValue;
import java.util.Objects;

/** Draw-time viewport and scissor state, kept outside the pipeline key. */
public record IrisDynamicDrawState(StateValue<Rect> viewport,
                                   StateValue<Boolean> scissorEnabled,
                                   StateValue<Rect> scissor) {
  public IrisDynamicDrawState {
    Objects.requireNonNull(viewport, "viewport");
    Objects.requireNonNull(scissorEnabled, "scissorEnabled");
    Objects.requireNonNull(scissor, "scissor");
  }

  public boolean completeFor(IrisGlStateSnapshot.Operation operation) {
    if (operation == IrisGlStateSnapshot.Operation.DISPATCH) {
      return true;
    }
    return viewport.isKnown() && scissorEnabled.isKnown()
        && (!scissorEnabled.value() || scissor.isKnown());
  }

  public record Rect(int x, int y, int width, int height) {
    public Rect {
      if (width < 0 || height < 0) {
        throw new IllegalArgumentException("negative GL rectangle extent");
      }
    }
  }
}

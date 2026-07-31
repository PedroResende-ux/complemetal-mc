package com.pebbles_boon.metalrender.render.chunk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class CustomChunkMesherFluidSideWindingTest {
  @Test
  void everyHorizontalFluidSideIsOrientedTowardItsDeclaredDirection() {
    assertWinding(Direction.NORTH, 0, 0, 1, 0, false);
    assertWinding(Direction.SOUTH, 0, 1, 1, 1, true);
    assertWinding(Direction.WEST, 0, 0, 0, 1, true);
    assertWinding(Direction.EAST, 1, 0, 1, 1, false);
  }

  private static void assertWinding(Direction direction,
      int x0, int z0, int x1, int z1,
      boolean initiallyOutward) {
    boolean outward =
        CustomChunkMesher.isFluidSideEndpointOrderOutward(
            direction, x0, z0, x1, z1);
    if (initiallyOutward) {
      assertTrue(outward, direction + " should start outward");
    } else {
      assertFalse(outward, direction + " should require an endpoint swap");
      int swapX = x0;
      x0 = x1;
      x1 = swapX;
      int swapZ = z0;
      z0 = z1;
      z1 = swapZ;
    }

    assertTrue(CustomChunkMesher.isFluidSideEndpointOrderOutward(
        direction, x0, z0, x1, z1),
        direction + " final triangle winding points inward");
  }
}

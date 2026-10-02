package com.pebbles_boon.metalrender.render.chunk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class CustomChunkMesherFluidFamilyTest {
  @BeforeAll
  static void bootstrapMinecraftRegistries() throws Exception {
    Field field = Bootstrap.class.getDeclaredField("isBootstrapped");
    boolean bootstrapped = false;
    if (field.trySetAccessible()) {
      bootstrapped = field.getBoolean(null);
    }
    if (!bootstrapped) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
    }
  }

  @Test
  void matchesStillAndFlowingStatesOnlyWithinTheirFluidFamily() {
    assertTrue(CustomChunkMesher.isSameFluidFamily(
        Fluids.WATER, Fluids.WATER.defaultFluidState()));
    assertTrue(CustomChunkMesher.isSameFluidFamily(
        Fluids.WATER, Fluids.FLOWING_WATER.defaultFluidState()));
    assertTrue(CustomChunkMesher.isSameFluidFamily(
        Fluids.FLOWING_WATER, Fluids.WATER.defaultFluidState()));

    assertTrue(CustomChunkMesher.isSameFluidFamily(
        Fluids.LAVA, Fluids.LAVA.defaultFluidState()));
    assertTrue(CustomChunkMesher.isSameFluidFamily(
        Fluids.LAVA, Fluids.FLOWING_LAVA.defaultFluidState()));
    assertTrue(CustomChunkMesher.isSameFluidFamily(
        Fluids.FLOWING_LAVA, Fluids.LAVA.defaultFluidState()));

    assertFalse(CustomChunkMesher.isSameFluidFamily(
        Fluids.WATER, Fluids.LAVA.defaultFluidState()));
    assertFalse(CustomChunkMesher.isSameFluidFamily(
        Fluids.LAVA, Fluids.WATER.defaultFluidState()));
    assertFalse(CustomChunkMesher.isSameFluidFamily(
        Fluids.WATER, Fluids.EMPTY.defaultFluidState()));
  }
}

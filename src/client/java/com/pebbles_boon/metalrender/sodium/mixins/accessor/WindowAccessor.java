package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Repairs Minecraft's cached logical GLFW window dimensions. */
@Mixin(Window.class)
public interface WindowAccessor {
  @Accessor("width")
  void metalrender$setWindowWidth(int width);

  @Accessor("height")
  void metalrender$setWindowHeight(int height);
}

package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightTexture.class)
public interface AccessorLightTexture {
  @Accessor("lightTexture")
  DynamicTexture complemetal$getLightTexture();
}

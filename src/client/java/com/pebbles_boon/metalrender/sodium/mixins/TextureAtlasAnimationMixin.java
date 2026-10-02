package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.render.MetalTextureManager;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1 atlas animation/reload bridge. The pinned Minecraft 1.21.1 path
 * still uses TextureAtlasSprite.Ticker internally, so avoid shadowing those
 * private implementation details and hook stable public lifecycle methods.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasAnimationMixin {
  @Inject(method = "cycleAnimationFrames", at = @At("RETURN"), require = 0)
  private void metalrender$markAtlasDirtyAfterAnimation(CallbackInfo ci) {
    MetalTextureManager.markAtlasDirty();
  }

  @Inject(method = "upload", at = @At("TAIL"), require = 0)
  private void metalrender$markAtlasDirtyAfterReload(
      SpriteLoader.Preparations preparations, CallbackInfo ci) {
    MetalTextureManager.markAtlasDirty();
  }
}

package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.render.MetalTextureManager;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the stable atlas-level animation upload instead of the private
 * SpriteContents animation implementation.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasAnimationMixin {
  @Shadow
  @Final
  private List<SpriteContents.AnimationState> animatedTexturesStates;

  @Inject(method = "cycleAnimationFrames",
      at = @At(value = "INVOKE",
          target = "Lnet/minecraft/client/renderer/texture/TextureAtlas;" +
              "uploadAnimationFrames()V"),
      require = 1)
  private void metalrender$markAtlasDirtyForChangedAnimation(CallbackInfo ci) {
    for (SpriteContents.AnimationState animation : animatedTexturesStates) {
      if (animation.needsToDraw()) {
        MetalTextureManager.markAtlasDirty();
        return;
      }
    }
  }

  @Inject(method = "upload", at = @At("TAIL"), require = 1)
  private void metalrender$markAtlasDirtyAfterReload(
      SpriteLoader.Preparations preparations, CallbackInfo ci) {
    MetalTextureManager.markAtlasDirty();
  }
}

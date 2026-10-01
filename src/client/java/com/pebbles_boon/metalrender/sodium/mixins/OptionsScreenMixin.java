package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.gui.MetalRenderSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
  protected OptionsScreenMixin(Component title) {
    super(title);
  }

  @Inject(method = "init", at = @At("TAIL"), require = 1)
  private void metalrender$addSettingsButton(CallbackInfo ci) {
    addRenderableWidget(Button.builder(Component.literal("Complemetal"),
            button -> minecraft.setScreen(
                new MetalRenderSettingsScreen(this)))
        .bounds(width - 108, 8, 100, 20)
        .build());
  }
}

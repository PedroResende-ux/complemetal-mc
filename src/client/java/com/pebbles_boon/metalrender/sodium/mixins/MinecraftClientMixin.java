package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.StartupBlocker;
import com.pebbles_boon.metalrender.gui.StartupBlockerOverlay;
import com.pebbles_boon.metalrender.performance.PerformanceController;
import com.pebbles_boon.metalrender.render.MetalRenderHookState;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
  @Unique
  private ClientLevel metalrender$loadedLevel;
  @Unique
  private int metalrender$debugCounter = 0;

  @Inject(method = "<init>", at = @At("TAIL"), require = 1)
  private void metalrender$showStartupBlocker(GameConfig gameConfig,
      CallbackInfo ci) {
    if (StartupBlocker.shouldBlockStartup()) {
      Minecraft minecraft = (Minecraft) (Object) this;
      if (minecraft.gui != null) {
        minecraft.gui.setOverlay(new StartupBlockerOverlay());
      }
    }
  }

  @Inject(method = "runTick", at = @At("HEAD"), require = 1)
  private void metalrender$startFrame(boolean tick, CallbackInfo ci) {
    if (StartupBlocker.shouldBlockStartup()) {
      return;
    }
    ClientLevel level = ((Minecraft) (Object) this).level;
    if (MetalRenderClient.isEnabled()) {
      PerformanceController.startFrame();
      metalrender$debugCounter++;
      if (metalrender$debugCounter % 600 == 1) {
        MetalLogger.deepInfo(
            "[mcmix] lvl=" +
                (level != null ? "ok" : "null") +
                " attached=" +
                (metalrender$loadedLevel == null ? "none"
                    : metalrender$loadedLevel == level ? "same" : "other") +
                " wr=" +
                (MetalRenderClient.getWorldRenderer() != null ? "ok"
                    : "null"));
      }
      MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
      if (wr != null && level != metalrender$loadedLevel) {
        MetalRenderHookState.resetSession();
        if (metalrender$loadedLevel != null) {
          try {
            wr.onWorldUnload();
          } catch (Throwable error) {
            MetalLogger.warn("[mcmix] old world unload failed: %s",
                error.getMessage());
          }
        }
        metalrender$loadedLevel = level;
        if (level != null) {
          MetalLogger.info("[mcmix] new level found");
          try {
            wr.onWorldLoad();
            MetalLogger.info("[mcmix] onworldload ok");
          } catch (Throwable error) {
            MetalLogger.error("[mcmix] onworldload fail", error);
          }
        }
      }
      if (level != null) {
        if (wr != null && wr.metalActive()) {
          wr.prepareMeshes();
        }
      }
    } else {
      // This field tracks the level attached to the current Metal renderer,
      // not merely Minecraft's current level. Re-enabling Metal must attach
      // the active level even when its object identity did not change.
      metalrender$loadedLevel = null;
    }
  }

  @Inject(method = "runTick", at = @At("TAIL"), require = 1)
  private void metalrender$endFrame(boolean tick, CallbackInfo ci) {
    if (StartupBlocker.shouldBlockStartup()) {
      return;
    }
    if (MetalRenderClient.isEnabled()) {
      PerformanceController.endFrame();
    }
  }
}

package com.pebbles_boon.metalrender.compat.iris.mixin;

import com.pebbles_boon.metalrender.compat.iris.IrisShaderCapture;
import com.pebbles_boon.metalrender.compat.iris.IrisMetalFeatureFlags;
import com.pebbles_boon.metalrender.compat.iris.IrisTranslationCoordinator;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Loads Iris hooks only when Iris is installed and the stable production
 * default or an explicit startup override enables the Metal path.
 */
public final class IrisMixinPlugin implements IMixinConfigPlugin {
  private static final String SUPPORTED_IRIS_VERSION =
      "1.8.12+1.21.1-neoforge";

  @Override
  public void onLoad(String mixinPackage) {
  }

  @Override
  public String getRefMapperConfig() {
    return null;
  }

  @Override
  public boolean shouldApplyMixin(String targetClassName,
      String mixinClassName) {
    try {
      if (!ModList.get().isLoaded("iris")) {
        return false;
      }

      String installedVersion = ModList.get().getModContainerById("iris")
          .map(container -> container.getModInfo().getVersion().toString())
          .orElse("");
      // Every compatibility mixin in this config targets the verified
      // Minecraft 1.21.1 / Iris 1.8.12 ABI. Partial loading against a newer
      // Iris build is more dangerous than disabling the Metal integration.
      if (!SUPPORTED_IRIS_VERSION.equals(installedVersion)) {
        return false;
      }

      // The field accessor is observational and may be applied without
      // enabling the experimental Metal translation path.
      if (mixinClassName.endsWith("Iris1211RenderingPipelineMixin")) {
        return true;
      }

      return IrisShaderCapture.isEnabled()
          && IrisMetalFeatureFlags.enabled(
              IrisTranslationCoordinator.TRANSLATION_ENABLED_PROPERTY);
    } catch (Throwable ignored) {
      return false;
    }
  }

  @Override
  public void acceptTargets(Set<String> myTargets,
      Set<String> otherTargets) {
  }

  @Override
  public List<String> getMixins() {
    return null;
  }

  @Override
  public void preApply(String targetClassName, ClassNode targetClass,
      String mixinClassName, IMixinInfo mixinInfo) {
  }

  @Override
  public void postApply(String targetClassName, ClassNode targetClass,
      String mixinClassName, IMixinInfo mixinInfo) {
  }
}

package com.pebbles_boon.metalrender.entity;

import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

/**
 * 1.21.1 compatibility shell. The upstream entity path depends on the newer
 * Minecraft render-state API; terrain remains the first Metal cutover target.
 */
public final class MetalEntityRenderer {
  private boolean active;

  public MetalEntityRenderer() {
  }

  public void setup(long device, long pipeline) {
    active = device != 0;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public boolean isActive() {
    return active;
  }

  public boolean hasVisibleSubmergedEntities() {
    return false;
  }

  public boolean captureEntity(Entity entity, float delta, Matrix4f model) {
    return false;
  }

  public void buildMeshes(long ctx) {
  }

  public void renderCapturedEntities(long ctx, boolean inWater) {
  }

  public void invalidateTextureCache() {
  }

  public int getLastEntityCount() {
    return 0;
  }

  public int getLastVertexCount() {
    return 0;
  }

  public void clearCapturedEntities() {
  }

  public void shutdown() {
    active = false;
  }
}

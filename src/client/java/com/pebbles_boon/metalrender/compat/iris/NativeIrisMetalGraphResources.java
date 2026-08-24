package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Fail-open owner for persistent private Metal graph attachments. */
final class NativeIrisMetalGraphResources {
  static final String ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalGraphResources";
  private final NativeAccess nativeAccess;

  NativeIrisMetalGraphResources() {
    this(new NativeAccess() {
      @Override
      public long ensure(long contextGeneration, int glTexture,
          long resourceGeneration, String format, int sampleCount,
          int width, int height, int depthOrLayers, int mipLevels,
          int usage) {
        return NativeBridge.nEnsureIrisMetal4GraphTexture(contextGeneration,
            glTexture, resourceGeneration, format, sampleCount, width,
            height, depthOrLayers, mipLevels, usage);
      }

      @Override
      public boolean runtimeAvailable() {
        return NativeBridge.isLibLoaded() && NativeBridge.nIsMetal4Active();
      }

      @Override
      public int count() {
        return NativeBridge.nGetIrisMetal4GraphTextureCount();
      }

      @Override
      public long bytes() {
        return NativeBridge.nGetIrisMetal4GraphTextureBytes();
      }

      @Override
      public void reset() {
        NativeBridge.nResetIrisMetal4GraphResources();
      }
    });
  }

  NativeIrisMetalGraphResources(NativeAccess nativeAccess) {
    this.nativeAccess = Objects.requireNonNull(nativeAccess, "nativeAccess");
  }

  static boolean isOptedIn() {
    return IrisMetalFeatureFlags.enabled(ENABLED_PROPERTY);
  }

  boolean runtimeAvailable() {
    try {
      return nativeAccess.runtimeAvailable();
    } catch (RuntimeException | LinkageError unavailable) {
      return false;
    }
  }

  Result ensure(IrisMetalGraphResourcePlan plan) {
    Objects.requireNonNull(plan, "plan");
    ArrayList<Binding> bindings = new ArrayList<>(plan.allocations().size());
    HashSet<Long> tokens = new HashSet<>();
    try {
      for (IrisMetalGraphResourcePlan.Allocation allocation
          : plan.allocations()) {
        IrisGlStateSnapshot.ResourceHandle handle = allocation.handle();
        long token = nativeAccess.ensure(handle.contextGeneration(),
            handle.name(), handle.generation(), allocation.format(),
            allocation.sampleCount(), allocation.width(), allocation.height(),
            allocation.depthOrLayers(), allocation.mipLevels(),
            allocation.usage());
        if (token <= 0) {
          return new Unsupported(
              "native-graph-texture-allocation-failed-r"
                  + allocation.resourceId() + '-'
                  + allocation.width() + 'x' + allocation.height()
                  + "-s" + allocation.sampleCount()
                  + "-u" + allocation.usage());
        }
        if (!tokens.add(token)) {
          return new Unsupported("native-graph-texture-token-collision");
        }
        bindings.add(new Binding(allocation.resourceId(), token));
      }
      int nativeCount = nativeAccess.count();
      long nativeBytes = nativeAccess.bytes();
      if (nativeCount < bindings.size() || nativeBytes <= 0) {
        return new Unsupported("native-graph-texture-telemetry-invalid");
      }
      return new Complete(bindings, nativeCount, nativeBytes);
    } catch (RuntimeException | LinkageError failure) {
      return new Unsupported("native-graph-texture-exception");
    }
  }

  void reset() {
    try {
      nativeAccess.reset();
    } catch (RuntimeException | LinkageError ignored) {
      // A lost native runtime already owns its allocations.
    }
  }

  record Binding(int resourceId, long token) {
    Binding {
      if (resourceId < 0 || token <= 0) {
        throw new IllegalArgumentException("invalid Metal graph binding");
      }
    }
  }

  sealed interface Result permits Complete, Unsupported {
  }

  record Complete(List<Binding> bindings, int nativeTextureCount,
                  long nativeTextureBytes) implements Result {
    Complete {
      bindings = List.copyOf(bindings);
      if (bindings.isEmpty() || nativeTextureCount < bindings.size()
          || nativeTextureBytes <= 0) {
        throw new IllegalArgumentException("invalid native graph resources");
      }
    }
  }

  record Unsupported(String reason) implements Result {
    Unsupported {
      Objects.requireNonNull(reason, "reason");
      if (reason.isBlank() || reason.length() > 128) {
        throw new IllegalArgumentException("invalid graph resource blocker");
      }
    }
  }

  interface NativeAccess {
    boolean runtimeAvailable();

    long ensure(long contextGeneration, int glTexture,
        long resourceGeneration, String format, int sampleCount,
        int width, int height, int depthOrLayers, int mipLevels, int usage);

    int count();

    long bytes();

    void reset();
  }
}

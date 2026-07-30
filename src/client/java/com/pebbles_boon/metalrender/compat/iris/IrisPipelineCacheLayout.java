package com.pebbles_boon.metalrender.compat.iris;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Deterministic on-disk layout for translated Iris programs and the future
 * Metal binary archive.
 */
public final class IrisPipelineCacheLayout {
  public static final String CACHE_NAMESPACE = "iris-metal-v1";

  private final Path root;

  public IrisPipelineCacheLayout(Path root) {
    this.root = Objects.requireNonNull(root, "root")
        .toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  public CachePaths paths(IrisShaderCacheKey key) {
    Objects.requireNonNull(key, "key");
    String digest = key.sha256();
    Path directory = root.resolve(CACHE_NAMESPACE)
        .resolve(digest.substring(0, 2))
        .resolve(digest.substring(2, 4))
        .resolve(digest);
    return new CachePaths(directory,
        directory.resolve("manifest.properties"),
        directory.resolve("spirv"),
        directory.resolve("msl"),
        directory.resolve("pipeline").resolve("metal.binarchive"),
        directory.resolve("translation.complete"));
  }

  public record CachePaths(Path directory, Path manifest,
                           Path spirvDirectory, Path mslDirectory,
                           Path metalBinaryArchive,
                           Path translationComplete) {
    public CachePaths {
      Objects.requireNonNull(directory, "directory");
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(spirvDirectory, "spirvDirectory");
      Objects.requireNonNull(mslDirectory, "mslDirectory");
      Objects.requireNonNull(metalBinaryArchive, "metalBinaryArchive");
      Objects.requireNonNull(translationComplete, "translationComplete");
    }

    public Path spirv(IrisShaderStage stage) {
      return spirvDirectory.resolve(
          Objects.requireNonNull(stage, "stage").cacheName() + ".spv");
    }

    public Path msl(IrisShaderStage stage) {
      return mslDirectory.resolve(
          Objects.requireNonNull(stage, "stage").cacheName() + ".metal");
    }
  }
}

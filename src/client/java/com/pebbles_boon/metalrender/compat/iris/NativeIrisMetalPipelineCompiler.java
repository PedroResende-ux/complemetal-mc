package com.pebbles_boon.metalrender.compat.iris;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Native MTL4 compiler/archive owner for translated Iris pipelines. */
final class NativeIrisMetalPipelineCompiler
    implements IrisMetalPipelineCompiler {
  static final String ENABLED_PROPERTY =
      "metalrender.experimental.irisMetalPipelineCompilation";
  static final String METAL4_REQUESTED_PROPERTY =
      "metalrender.feature.metal4";
  static final String DIRECTORY = "metal4-pipelines-v1";
  private static final String ARCHIVE = "pipelines.mtl4archive";
  private static final String COMPILER_SCHEMA =
      "metalrender-iris-mtl4-compiler=2";

  private static final int NATIVE_FAILED = -1;
  private static final int NATIVE_UNSUPPORTED = 0;
  private static final int NATIVE_COMPILED = 1;
  private static final int NATIVE_CACHE_HIT = 2;
  private static final int NATIVE_DEFERRED = 3;

  private final Path cacheRoot;
  private final IrisTranslationProfile profile;
  private boolean configured;
  private boolean closed;
  private Readiness terminalReadiness;
  private String identitySha256 = "";

  NativeIrisMetalPipelineCompiler(Path cacheRoot,
      IrisTranslationProfile profile) {
    this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot")
        .toAbsolutePath().normalize();
    this.profile = Objects.requireNonNull(profile, "profile");
  }

  static boolean isOptedIn() {
    return IrisMetalFeatureFlags.enabled(ENABLED_PROPERTY);
  }

  @Override
  public synchronized Readiness readiness() {
    if (closed || !isOptedIn()) {
      return Readiness.UNSUPPORTED;
    }
    if (configured) {
      return Readiness.READY;
    }
    if (terminalReadiness != null) {
      return terminalReadiness;
    }
    if (!NativeBridge.isLibLoaded()) {
      return Readiness.DEFERRED;
    }
    String backend;
    try {
      backend = NativeBridge.nGetBackendMode();
    } catch (LinkageError error) {
      terminalReadiness = Readiness.UNSUPPORTED;
      return terminalReadiness;
    }
    if (backend == null || shouldDeferInactiveMetal4(backend,
        IrisMetalFeatureFlags.enabled(METAL4_REQUESTED_PROPERTY))) {
      return Readiness.DEFERRED;
    }
    if (!NativeBridge.nIsMetal4Active()) {
      terminalReadiness = Readiness.UNSUPPORTED;
      return terminalReadiness;
    }
    try {
      String nativeIdentity =
          NativeBridge.nGetIrisMetal4PipelineCacheIdentity();
      if (nativeIdentity == null || nativeIdentity.isBlank()
          || nativeIdentity.length() > 2048
          || nativeIdentity.indexOf('\n') >= 0
          || nativeIdentity.indexOf('\r') >= 0) {
        terminalReadiness = Readiness.UNSUPPORTED;
        return terminalReadiness;
      }
      identitySha256 = IrisMetalPipelineKey.sha256(
          COMPILER_SCHEMA + ';' + profile.canonicalValue() + ';'
              + nativeIdentity);
      Path namespace = cacheRoot.resolve(IrisPipelineCacheLayout.CACHE_NAMESPACE);
      Path directory = namespace.resolve(DIRECTORY).resolve(identitySha256);
      createSafeDirectories(cacheRoot, namespace, directory);
      Path archive = directory.resolve(ARCHIVE);
      if (Files.isSymbolicLink(archive)
          || (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)
          && !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS))) {
        terminalReadiness = Readiness.UNSUPPORTED;
        return terminalReadiness;
      }
      int result = NativeBridge.nConfigureIrisMetal4PipelineCache(
          archive.toString());
      if (result <= NATIVE_UNSUPPORTED) {
        terminalReadiness = Readiness.UNSUPPORTED;
        return terminalReadiness;
      }
      configured = true;
      return Readiness.READY;
    } catch (IOException | RuntimeException | LinkageError error) {
      terminalReadiness = Readiness.UNSUPPORTED;
      return terminalReadiness;
    }
  }

  /**
   * The coordinator can start before native runtime configuration. In that
   * window the native backend reports its initial METAL3 value even when
   * Metal 4 was explicitly requested. Treat that value as transient; the
   * explicit fallback modes remain terminal and fail open to Iris/OpenGL.
   */
  static boolean shouldDeferInactiveMetal4(String backend,
      boolean metal4Requested) {
    if (backend == null || backend.contains("PROBE_PENDING")) {
      return true;
    }
    return metal4Requested && "METAL3".equals(backend);
  }

  @Override
  public synchronized String deviceCompilerSha256() {
    return readiness() == Readiness.READY ? identitySha256 : "";
  }

  @Override
  public synchronized Outcome compile(IrisMetalPipelineKey key,
      IrisShaderCacheKey shaderKey, IrisPipelineState state,
      Map<IrisShaderStage, byte[]> mslStages) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(shaderKey, "shaderKey");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mslStages, "mslStages");
    Readiness readiness = readiness();
    if (readiness == Readiness.DEFERRED) {
      return Outcome.DEFERRED;
    }
    if (readiness != Readiness.READY) {
      return Outcome.UNSUPPORTED;
    }
    byte[] vertex = mslStages.get(IrisShaderStage.VERTEX);
    byte[] fragment = mslStages.get(IrisShaderStage.FRAGMENT);
    byte[] compute = mslStages.get(IrisShaderStage.COMPUTE);
    int result;
    try {
      result = NativeBridge.nCompileIrisMetal4Pipeline(key.sha256(),
          shaderKey.sha256(),
          IrisMetalPipelineDescriptorEncoder.encode(state),
          vertex, fragment, compute);
    } catch (RuntimeException | LinkageError error) {
      return Outcome.FAILED;
    }
    return switch (result) {
      case NATIVE_COMPILED -> Outcome.COMPILED;
      case NATIVE_CACHE_HIT -> Outcome.CACHE_HIT;
      case NATIVE_DEFERRED -> Outcome.DEFERRED;
      case NATIVE_UNSUPPORTED -> Outcome.UNSUPPORTED;
      case NATIVE_FAILED -> Outcome.FAILED;
      default -> Outcome.FAILED;
    };
  }

  @Override
  public synchronized boolean flush() {
    if (readiness() != Readiness.READY) {
      return false;
    }
    try {
      return NativeBridge.nFlushIrisMetal4PipelineCache();
    } catch (RuntimeException | LinkageError error) {
      return false;
    }
  }

  @Override
  public synchronized NativeStatus nativeStatus() {
    if (!NativeBridge.isLibLoaded()) {
      return new NativeStatus(0, 0, 0, 0, 0, 0, 0);
    }
    try {
      return new NativeStatus(
          NativeBridge.nGetIrisMetal4PipelineAttemptCount(),
          NativeBridge.nGetIrisMetal4PipelineCompileCount(),
          NativeBridge.nGetIrisMetal4PipelineCacheHitCount(),
          NativeBridge.nGetIrisMetal4PipelineFailureCount(),
          NativeBridge.nGetIrisMetal4PipelineStaleRecoveryCount(),
          NativeBridge.nGetIrisMetal4LivePipelineCount(),
          NativeBridge.nGetIrisMetal4PipelineDrawAttemptCount());
    } catch (RuntimeException | LinkageError error) {
      return new NativeStatus(0, 0, 0, 0, 0, 0, 0);
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (!NativeBridge.isLibLoaded()) {
      return;
    }
    try {
      NativeBridge.nResetIrisMetal4Pipelines();
    } catch (RuntimeException | LinkageError ignored) {
      // The JVM/native teardown path remains fail-open.
    }
  }

  private static void createSafeDirectories(Path root, Path namespace,
      Path directory) throws IOException {
    Files.createDirectories(root);
    requireDirectory(root);
    Files.createDirectories(namespace);
    requireDirectory(namespace);
    Files.createDirectories(namespace.resolve(DIRECTORY));
    requireDirectory(namespace.resolve(DIRECTORY));
    Files.createDirectories(directory);
    requireDirectory(directory);
  }

  private static void requireDirectory(Path path) throws IOException {
    if (Files.isSymbolicLink(path)
        || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("unsafe Metal pipeline cache directory");
    }
  }
}

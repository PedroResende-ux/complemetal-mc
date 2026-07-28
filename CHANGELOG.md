# Changelog

## 0.2.0-beta.1+mc26.2

### Platform

- Target Minecraft Java Edition 26.2, Fabric Loader 0.19.3, Fabric API
  0.156.0+26.2 and Java 25.
- Upgrade to Gradle 9.5.1 and Fabric Loom 1.17.17.
- Package a signed arm64 native library with a macOS 14.0 deployment target.

### Renderer

- Port client hooks to the Minecraft 26.2 render extraction lifecycle.
- Add a synchronized capture/present handshake for world, GUI, entities and
  particles.
- Move the Metal composite before vanilla feature rendering and retain a
  vanilla opaque underlay by default; incomplete meshes remain visible while
  entities and particles stay on the vanilla path.
- Mark animated and newly uploaded texture atlases dirty before native upload.
- Replace unsafe synchronous `glGetTexImage` atlas/lightmap transfers with
  Minecraft's fenced texture-to-buffer readback and throttle atlas handoff to
  4 Hz.
- Cancel publication and retain per-request ownership if a fenced readback
  reports a late driver error.
- Release-lock native entity/particle replacement until its remaining legacy
  raw OpenGL texture readback is migrated to the fenced path.
- Correct reversed-Z comparison and depth clearing.
- Replace unsafe memoryless OIT textures with private textures.
- Add bounded native memory allocation based on the configured and detected
  working-set budget.
- Fence IOSurface slots across reuse, resize and shutdown.
- Add optional MetalFX scaling and native QoS configuration.
- Derive the default native frame-time budget from the active display refresh
  and Minecraft FPS limit.
- Keep mesh-shader and Hi-Z fast paths disabled until conformance validation.

### Reliability

- Load the packaged native library lazily from a checksum-versioned cache.
- Fail open to Minecraft's renderer on unsupported platforms or backends.
- Add deterministic renderer shutdown, restart, config reload and config reset.
- Publish immutable asynchronous culling snapshots.
- Avoid repeated native initialization attempts after a terminal failure.

### Verification

- Add config and frustum-culling unit tests.
- Add generated-header source JNI parity checks and full packaged-dylib export
  parity verification.
- Make the release gate rebuild the native library and all offline shaders
  before native architecture and release-JAR payload verification.
- Add a macOS client game-test harness for the Metal hybrid path and a
  native-disabled vanilla baseline.
- Add a CI workflow for compilation, tests and release artifact validation.

### Known boundary

The Metal 4 implementation in this release is a hybrid runtime scaffold. It
probes and creates Metal 4 queue/allocator objects where available, but the
validated render stream still encodes draws through Metal 3. A full MTL4
pipeline and draw encoder is future work. This beta also requires real-hardware
visual conformance before it can be called stable; a successful payload smoke
test or non-empty screenshot is not pixel-parity proof.

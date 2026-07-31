# Changelog

## 0.2.1+mc26.2

### Runtime clarity

- Replace the random F3 death-message easter egg with deterministic renderer
  states for active Metal terrain, Iris/OpenGL compatibility pause, disabled
  configuration, initialization, no-world readiness and vanilla fallback.
- Include a bounded initialization failure reason in the F3 fallback line so
  a safe fallback is distinguishable from a crash.
- Keep Metal frame encoding paused until Iris' applied compatibility latch is
  released, closing a one-frame gap while shader disable/rebuild is settling.
- Add an explicit square mod icon so Mod Menu no longer reports a broken icon.

## 0.2.0+mc26.2

### Stable hybrid profile

- Promote the conservative Apple Silicon hybrid configuration for Minecraft
  Java Edition 26.2: selected terrain rendering uses the Metal 3 compatibility
  stream and unsupported content retains Minecraft's normal renderer.
- Keep the Metal 4 queue, allocator and completion probe enabled when the
  runtime exposes it, while keeping actual MTL4 draw encoding disabled.
- Retain the safe vanilla overlay, bounded native memory policy, fenced
  IOSurface ownership and fail-open initialization/restart behavior.
- Correct chunk face-bucket interpretation and validate the active Metal path
  through Overworld, Nether and End transitions.
- Correct still/flowing fluid-family height sampling, keep opaque lava in the
  opaque terrain stream, and fix NORTH/EAST fluid-side winding under back-face
  culling.
- Keep the pending-section set and priority list synchronized when sorting is
  deferred, preventing block-update rebuild queues from stalling.
- Bound each bulk block-update recovery episode to one clean rebuild instead
  of repeatedly clearing meshes while late section updates arrive.
- Give every asynchronous section build a unique ownership token so a stale
  worker cannot clear or publish over its replacement.
- Validate resize and fullscreen transitions in both the Metal 4 hybrid and
  forced Metal 3 profiles.

### Iris boundary

- Keep Iris shader-pack rendering on Iris' OpenGL path. MetalRender detaches
  its presentation path while an Iris shader pack is active.
- Keep final-GLSL capture and GLSL-to-SPIR-V-to-MSL translation as an opt-in
  developer experiment. Generated MSL is cached but is not compiled into or
  executed by a Metal render graph, so this release makes no Iris FPS claim.
- Scope the translation evidence to the exact tested 76-program Complementary
  Reimagined workload rather than claiming arbitrary shader-pack capacity.

### Explicit exclusions

- True 2x Retina framebuffer backing, macOS sleep/wake and external-display
  hot-plug/reconnect are not validated by this release.
- The native high-rate stress loop validates offscreen buffer recycling and
  throughput only. It is not evidence of real presented 200 Hz frame pacing.

## 0.2.0-beta.2+mc26.2

### Iris shader foundation

- Pause and fully detach the hybrid Metal presentation path while an Iris
  shader pack is active, retaining Iris' OpenGL renderer as the safe fallback.
- Add an opt-in, bounded capture path for Iris' final linked GLSL stages.
- Translate captured stages in process through shaderc to SPIR-V and
  SPIRV-Cross to MSL without persisting source GLSL or program names.
- Add content-addressed, integrity-checked SPIR-V/MSL artifacts and an
  immutable, size-bounded disk cache.
- Keep a bounded capture queue validated against the exact tested 76-program
  Complementary Reimagined workload, then delay and throttle background
  translation so it does not compete with Iris' initial shader-pack and
  world-load work. Queue overload remains fail-open and may skip captures.

### Metal pipeline preparation

- Replace the availability-only Metal 4 check with a real command-buffer,
  compute-encoder and completion-feedback probe.
- Populate Metal binary archives before pipeline creation, retry safely
  without a bad archive and serialize cache updates atomically.
- Add an inactive Metal 4 compiler/dataset serializer scaffold for future Iris
  MSL pipelines. It is not connected to the draw path in this beta.

### Verification

- Add an isolated exact-release-JAR harness using Fabric, Iris, Sodium and
  Complementary Reimagined with shader on/off/on screenshots.
- Verify that Metal presentation and IOSurface ownership stop while Iris is
  active and resume after shaders are disabled.
- Validate the translation cache structure and support cold/warm cache runs.

### Known boundary

The translated MSL and future pipeline-cache plumbing are preparation only.
Iris still compiles and executes its shader passes through OpenGL, and the
translated artifacts are not submitted to a Metal render graph. Consequently
this beta does not claim an average-FPS improvement from Iris translation; the
opt-in translator can add startup work while the future execution path is
unfinished.

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

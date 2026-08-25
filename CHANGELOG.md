# Changelog

## 0.3.1+mc26.2 - 2026-08-25

### Display lifecycle and high-refresh QA

- Observe GLFW monitor topology, window migration/recreation, framebuffer and
  content-scale changes, refresh rate, visibility/iconification and likely
  wake gaps; invalidate only the display-facing cutover bridge while retaining
  translated shaders, Metal pipelines, persistent graph resources and
  in-flight graph tokens.
- Measure completed Minecraft `GlSurface.present()` calls in a bounded tracker
  and add strict exact-JAR gates for Retina backing, two-display migration and
  minimum-refresh cadence.
- During development, pass strict cold and warm two-display runs with a real
  2x Retina backing,
  migration back to a 200 Hz VX24G10, and 600 present-call samples at
  181.52/198.64 Hz. The p50 intervals were 5.081/5.003 ms, with zero >=100 ms
  stalls and zero ownership failures. Sustained/native VSync-synchronised
  200 Hz scanout is not claimed, and those physical gates were not repeated
  for the final release SHA after later presentation/reset fixes.
- Repair a missed Cocoa/GLFW Retina backing-size callback by synchronizing the
  direct framebuffer dimensions into Minecraft and invoking its normal resize
  handler before display-lifecycle analysis.
- Fix display-only reset destroying active graph presentation tokens during a
  resize/lifecycle transition, and fix cadence accounting after a GLFW window
  replacement.
- Fence every asynchronous translation and replay result to its lifecycle
  generation, discard stale completions, and bound the capture-abort grace so
  a display transition cannot promote data from the previous surface epoch.
- Release every retained texture/surface lease exactly once when capture is
  rejected, queued, completed or cancelled. Count unique retained bytes for
  backpressure and reject incomplete graph resources before cutover.
- Replace the ambiguous framebuffer blit with an exact GPU-only rectangle
  texture presentation shader. It uses framebuffer coordinates, corrects the
  IOSurface BGRA channel order, preserves caller GL state and reached exact
  3/3 FINAL parity with zero RMSE and zero maximum channel delta.
- Destroy GL rectangle-texture bindings before native IOSurfaces are recycled.
  This closes an intermittent pool-reuse race that produced black/magenta
  640x384 tiles after a lifecycle reset while keeping the output path free of
  CPU pixel copies.
- Discard and retry a cadence sample only when lifecycle counters prove that
  resize/fullscreen/display migration contaminated the sample; genuine stalls
  on a stable display still fail the gate.
- Add unit-tested exact-JAR lifecycle/hardware validators to the normal Gradle
  `check` task. The exact-JAR manifest/result schema is now version 5 and the
  in-game driver schema is version 14.

## 0.3.0+mc26.2

### Stable Stage 9 Iris-to-Metal 4 renderer

- Execute the validated Iris SHADOW, GEOMETRY, DEFERRED, COMPOSITE and FINAL
  pass graph through persistent Metal-owned resources and frame-batched MTL4
  command buffers after strict three-frame visual parity.
- Translate Iris' final linked GLSL through shaderc SPIR-V and SPIRV-Cross
  MSL, compile exact render-state pipelines, and persist a device/OS/compiler
  qualified Metal archive with stale-archive recovery.
- Retain compatible textures and geometry in bounded Metal caches, encode
  explicit RAW/WAR/WAW barriers, and present through a fenced IOSurface ring
  without a CPU output copy or steady-state `glFinish`.
- Submit graph frames off the render thread. Make status, promotion and bind
  probes nonblocking when a previously fenced surface can be reused, while
  keeping the first usable presentation blocking and ownership-safe.
- Enable the full path automatically only for the packaged stable JAR on
  Apple Silicon macOS 26+. Add
  `-Dmetalrender.irisMetal.enabled=false` as a global safe-disable override;
  dev/prerelease classpaths stay opt-in.
- Validate the exact packaged candidate cold and warm in Metal 4 and forced
  Metal 3 profiles through Iris on/off/on, Overworld, Nether, End, return to
  Overworld, resize, fullscreen and surface suspend/restore, with zero native
  faults or ownership failures.
- Add a fail-closed 600-frame matched performance gate. Two opposite-order
  stable-JAR A/B runs passed: Metal improved CPU p50 by 42.6-44.8%, CPU p95 by
  27.1-37.4%, GPU p95 by 10.5-19.5% and GPU p99 by 11.5-32.5%. CPU p99
  improved 38.3% in the first run and regressed only 1.5% in the reverse run,
  within the 5% limit; every side recorded zero >=100 ms stutters.
- Keep true 2x Retina backing, physical sleep/wake, external-display reconnect
  and real presented 200 Hz cadence outside the validated release scope.

### Stage 9 FINAL resource-residency milestone

- Move compatible dynamic FINAL texture inputs through synchronized IOSurface
  handoffs and retain static input content as native Metal textures.
- Retain referenced vertex/index content in bounded content-addressed Metal
  buffers and encode shared handles instead of inline geometry bytes in the
  MRX7 replay packet. Prune captured GL bindings that the reflected argument
  table and draw command do not consume.
- Validate the v41 exact JAR
  (`SHA-256 d1aa3eaf759fe8de8e8c96b29026b282e786f359c5d38884636d4cea666e050a`)
  in Metal 4 and forced Metal 3 cold/warm runs. Metal 4 produced 197/198
  successful visible FINAL presentations with 591/594 GPU texture references,
  394/396 GPU buffer references, zero CPU texture or buffer payloads and zero
  cutover failures. Forced Metal 3 made zero attempts and reported zero input
  counters.
- Keep `performanceEligible=false`: FINAL I/O residency is only a Stage 9
  substage. SHADOW, GEOMETRY and COMPOSITE remain visibly owned by OpenGL, and
  isolated Metal replay still waits synchronously for completion.

### Iris FINAL selective-cutover milestone

- Compare paired Iris/OpenGL and Metal FINAL output for three consecutive
  frames; pass with zero pixels outside tolerance, worst RMSE below `0.005`
  and maximum channel delta `1` using native row order.
- Present the validated Metal FINAL result before cancelling its paired
  OpenGL draw. The exact v32 JAR passed cold and warm runs with 195/197
  successful presentations and suppressions and zero fallback/failure events.
- Keep forced Metal 3 fail-open: the same candidate performed zero Metal
  attempts, suppressed zero OpenGL draws and retained Iris/OpenGL ownership.
- Replace the temporary Metal-to-CPU-to-OpenGL output upload with a direct
  IOSurface GPU handoff. A three-slot GL fence ring avoids a blocking
  steady-state `glFinish`; busy or unfenceable slots fail open before the
  paired OpenGL draw is cancelled.
- Validate the v36 exact JAR (`SHA-256 bbff76ffa74853d313c458140578b9d98038a2bbbb7a30311296aad82c7bddee`)
  in Metal 4 and forced Metal 3 cold/warm runs. Metal 4 performed 194/195
  visible FINAL presentations and suppressions with zero failure or fallback;
  forced Metal 3 performed zero Metal attempts and suppressions.
- Fix a startup race where the pre-configuration native `METAL3` state could
  be cached permanently as unsupported before the Metal 4 probe completed.
- Keep the historical direct-output bridge `performanceEligible=false`: at
  this v36 boundary, live OpenGL inputs still used CPU readback/upload and only
  the paired FINAL draw was owned by Metal.

### Iris Metal offscreen execution milestone

- Build and archive device-qualified MTL4 pipelines for the exact final Iris
  shader ABI, with cold compilation, warm cache hits and stale-archive recovery.
- Mirror bounded draw-time vertex/index buffers, sampled textures, samplers and
  reflected argument tables into the MRX7 native replay packet.
- Execute real Complementary Reimagined SHADOW, GEOMETRY, COMPOSITE and FINAL
  passes offscreen through Metal 4 with 4/4 successes, zero unsupported/failed
  attempts and zero native fault deltas in cold and warm exact-JAR runs.
- Repack OpenGL RGB8/RGB8_SNORM texture snapshots to Metal RGBA8 while
  preserving RGB and OpenGL's implicit alpha=1 semantics.
- Keep non-FINAL and unsupported Iris draws on OpenGL; the remaining
  `sky_basic` topology/index-expansion variant is fail-open.

### Iris render-graph milestone

- Capture bounded, content-addressed Iris frame graphs spanning shadow,
  geometry, deferred, composite and final phases without using transient
  OpenGL names as graph identity.
- Represent framebuffer and texture dependencies, memory barriers, blits,
  copies, mip generation and history ping-pong while tying draw nodes to the
  complete shader, pipeline-state and resource-layout keys.
- Pass packaged-JAR Metal 4 and forced Metal 3 cold/warm validation with 16
  successful graphs per run, all mandatory coverage, 51 barriers, 92
  transfers and zero unsupported or failed graph builds.
- Keep generated-MSL execution explicitly false: Iris/OpenGL still owns every
  visible shader-pack draw and no FPS claim is made.

### Iris resource-binding milestone

- Retain optimized SPIR-V resource names as non-semantic ABI metadata and
  reflect complete uniform, sampler, image, UBO and SSBO layouts without
  allowing names or compiler IDs to affect content identity.
- Capture direct Iris, Sodium and Mojang OpenGL resource paths, including UBO
  block indices, exact buffer ranges and texture-buffer backing storage.
- Resolve each observed pipeline variant fail-closed against the live runtime
  bindings while Iris/OpenGL remains the visible renderer.
- Pass packaged-JAR Metal 4 and forced Metal 3 cold/warm validation with 231
  programs, 462 stages, 6,248 reflected resources, 231 layout identities and
  zero incomplete binding variants.

### Iris pipeline-state milestone

- Capture generation-safe Iris program, framebuffer and texture identities,
  final vertex layouts, attachment formats, MRT blend/color-mask state,
  depth/stencil/raster/multisample state, primitive topology and SPIR-V
  specialization constants without replacing Iris' OpenGL draws.
- Persist integrity-checked, content-addressed pipeline-state manifests while
  keeping generated Metal execution explicitly pending and fail-open.
- Model OpenGL line loops and triangle fans as complete states with explicit
  index-expansion blockers for the later selective-cutover stage.
- Extend exact-JAR QA through Overworld, Nether, End and back to Overworld,
  plus Iris off/on reloads. Complementary Reimagined r5.8.1 passed Metal 4
  and forced Metal 3 cold/warm runs with 231 programs, 462 stages, 185 mapped
  variants, 90 per-run state identities, and zero incomplete, unsupported or
  failed mappings.

### Boundary

- These entries record the staged development history that preceded the stable
  full-graph path above. The final `0.3.0` boundary supersedes their temporary
  FINAL-only and no-performance-claim limitations for the declared supported
  matrix; unsupported variants still remain fail-open to Iris/OpenGL.

## 0.3.0-alpha.1+mc26.2

### Iris Metal compiler milestone

- Emit Metal argument-buffer resource declarations during SPIR-V-to-MSL
  translation, preventing real shader-pack stages from exceeding Metal's
  direct buffer-index limit. Runtime resource binding remains a later
  roadmap stage.
- Add an explicit opt-in, fail-open validation path that compiles generated MSL
  into an ephemeral `MTLLibrary`, resolves the expected `main0` function and
  releases the library without creating a pipeline or encoding a draw.
- Bound compile-validation input to 16 MiB per stage, verify cached MSL again
  before JNI, cap retained artifact identities, and fail the completion gate
  closed when digest or live-library telemetry is incomplete.
- Distinguish lifecycle `DEFERRED` from terminal `UNSUPPORTED`, including
  startup, renderer restart and teardown races.
- Keep translation, Apple Metal compiler validation, pipeline creation and
  shader execution as separate status/evidence boundaries.
- Extend exact-JAR QA to require all 76 captured Complementary Reimagined
  programs and all 152 generated stages to pass the Apple Metal compiler in
  cold and warm cache runs under both the Metal 4 hybrid and forced Metal 3
  profiles.

### Boundary

- Iris still owns the visible OpenGL render graph. This milestone does not
  execute generated MSL, replace an Iris pass or make an FPS claim.

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

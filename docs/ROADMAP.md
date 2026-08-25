# Iris-to-Metal roadmap

This roadmap records the implemented Stage 9 path and the `0.4.1+mc26.2`
stable safety release. Every stage remains a separate fail-closed acceptance
boundary; a later success never hides an earlier translation, state, resource,
parity, lifecycle, or performance failure.

| Stage | Goal | Stable state | Exit evidence |
| --- | --- | --- | --- |
| 0 | Minecraft 26.2 renderer foundation | Complete | Exact-JAR Fabric environment, native lifecycle and Metal 3 fallback |
| 1 | Final Iris GLSL -> SPIR-V -> MSL cache | Complete | 231 Complementary programs and 462 stages, integrity-checked cold/warm cache |
| 2 | Apple Metal compiler validation | Complete | 462/462 stages compile, resolve `main0` with the correct function type and leave no live libraries |
| 3 | Complete Iris pipeline state | Complete | Vertex ABI, targets, blend/depth/stencil/raster/topology and specialization state are content-keyed |
| 4 | Resource reflection and binding | Complete | 6,248 declarations across uniforms, samplers, textures, images, UBOs and SSBOs; missing data rejects the candidate |
| 5 | Iris render graph | Complete | SHADOW, GEOMETRY, DEFERRED, COMPOSITE and FINAL, history ping-pong, transfers and barriers represented |
| 6 | MTL4 pipeline/archive cache | Complete for the validated workload | Device/OS/compiler-qualified cold compile, warm archive hits and stale-archive recovery |
| 7 | Offscreen execution and parity | Complete | Four-phase MTL4 replay plus exact 3/3 FINAL parity, zero differing pixels in final acceptance |
| 8 | Selective cutover | Complete | Fenced IOSurface presentation, paired FINAL OpenGL cancellation and fail-open recovery |
| 9 | Full graph ownership and stable performance | Architecture complete; production requalification pending | Historical persistent-resource, MTL4, parity, lifecycle and matched A/B evidence; `0.4.1` automatic ownership disabled after field failures |

## `0.4.1` stable safety release

Real `0.4.0` play exposed three release-blocking failures that the earlier
synthetic graph matrix did not catch: 489–1,271 ms render-thread capture
attempts, a black world after an incomplete graph fell through to the legacy
FINAL bridge, and a live-reload crash caused by leaving Minecraft's `ViewArea`
null. `0.4.1` therefore changes the production gate:

- packaged stable versions no longer imply Iris Metal activation;
- Iris/OpenGL owns the visible shader frame by default;
- full-graph opt-in and the legacy FINAL bridge are mutually exclusive;
- duplicate Metal world resources are deferred for the full Iris session;
- live reload uses Minecraft's paired geometry invalidation/rebuild path;
- release authority now includes a real production-client, full-modpack,
  fullscreen enabled/disabled field run.

The exact `0.4.1` JAR passed Complementary Ultra with ImmediatelyFast, Entity
Culling, Lithium, FerriteCore, YACL, Zoomify, and the rest of the declared set
across flight, mining, reloads, dimensions, biomes, weather, and two worlds at
1920x1080@200 Hz. Automatic Iris Metal ownership remains pending until an
equally realistic profile proves it can be restored without weakening these
gates.

## `0.3.1` hardening release

The nine-stage architecture remains unchanged. `0.3.1` closes reliability
gaps found while qualifying display changes and repeated lifecycle resets:

- asynchronous translation/replay results are bound to a lifecycle generation
  and stale work cannot be promoted;
- captured texture/surface leases are released exactly once and unique retained
  bytes drive backpressure;
- incomplete graph resources fail open before OpenGL suppression;
- GLFW topology, framebuffer scale, window replacement and wake-like gaps
  rebuild only the display-facing bridge;
- the final IOSurface uses an exact GPU-only BGRA presentation shader; and
- GL bindings are destroyed before IOSurface recycling, preventing Metal pool
  reuse while CGL still references the surface.

The final `0.3.1` JAR passed Metal 4 and forced Metal 3 exact-JAR cold/warm
matrices, including Overworld -> Nether -> End -> Overworld, Iris off/on,
resize, fullscreen and surface suspend/restore. Physical cable and power-state
actions remain separate hardware qualification, not unfinished Stage 9 code.

## Stage 9 release boundary

Stage 9 replaces the supported live Iris shader-pass graph, not every piece of
Minecraft. Metal owns the validated shader-pack SHADOW, GEOMETRY, DEFERRED,
COMPOSITE and FINAL work. Minecraft UI and deliberately unsupported content
continue through their normal renderer. The final Metal IOSurface is attached
to a GL rectangle texture for an exact GPU-only presentation pass, so macOS
does not perform a CPU output readback.

Production ownership has these prerequisites:

1. an explicit experimental development opt-in; stable versions alone are not
   sufficient;
2. Apple Silicon macOS 26 or newer;
3. a successful native Metal 4 command-buffer/feedback probe;
4. final Iris shader translation and Apple compilation complete;
5. exact pipeline state and resource layouts complete;
6. device-qualified MTL4 pipelines available;
7. three consecutive visual-parity passes;
8. complete graph resources, draw packets and hazard barriers;
9. a completed presentation surface or a previously fenced reusable surface.

If any prerequisite fails, the candidate remains on Iris/OpenGL. Failures
before suppression affect no visible draw. A fatal failure after a draw was
already suppressed can make the current in-flight frame incomplete; ownership
is invalidated and the following frame returns to the safe path.

## Delivered execution architecture

- Final Iris GLSL is hashed and translated off the render thread.
- SPIR-V/MSL artifacts are immutable, bounded and content-addressed; original
  shader-pack GLSL is not persisted.
- Pipeline archives include the translation profile, complete render state,
  GPU identity, OS build and compiler identity.
- Compatible textures use synchronized IOSurface or resident Metal handles.
- Vertex/index data uses bounded content-addressed resident Metal buffers.
- Persistent graph attachments retain history across frames.
- RAW, WAR and WAW dependencies generate explicit MTL4 barriers.
- Clear, transfer and draw commands are batched into a frame command buffer.
- Worker submission uses direct native packets; render-thread status,
  promotion and bind probes do not block when a previous surface can be reused.
- MTL4 commit feedback provides raw GPU timings and completion/error state.

The validated steady-state graph observed 13 render passes and 19 draws per
frame with 18 explicit hazard barriers. Those counts describe the exact
Complementary workload, not a fixed renderer limit.

## Release acceptance

The historical Stage 9 publication required all of the following. A future
attempt to restore production ownership must repeat them and also pass the
`0.4.1` real-client full-modpack field scenarios without capture stalls,
black output, crashes, or retained-memory regression:

- Metal 4 cold and warm runs with full graph ownership required;
- Metal 3 cold and warm runs with Iris/OpenGL ownership retained;
- Iris initially on, disabled, reapplied and reenabled;
- Overworld -> Nether -> End -> Overworld;
- resize, fullscreen and surface suspend/restore;
- translation, reflection, pipeline, graph and visual-parity checks;
- zero ownership failures and zero native command/feedback faults;
- 600-frame matched CPU/GPU samples for OpenGL and Metal;
- at least 5% CPU p50 and p95 improvement, no more than 5% p99/GPU-tail
  regression, and no stutter regression;
- a second matched A/B in reverse launch order.

The final `0.3.1` exact JAR uses SHA-256
`5c5bfd4511a1304ad0b32e2f1ab66f1da7e5f718c2d1dc72dec1c97e1ffd30b7`.
Its Metal 4 and Metal 3 cold/warm matrices passed. The two opposite-order
matched A/B gates remain the published `0.3.0` performance qualification;
`0.3.1` does not attach a new FPS percentage to its correctness changes. The
independent final-artifact evidence is recorded in
[`RELEASE_CHECKLIST_0.3.1.md`](RELEASE_CHECKLIST_0.3.1.md).

## Explicitly deferred hardware validation

The source-level Stage 9 pipeline is complete, but stable automatic ownership
is not currently qualified. `0.4.1` does validate physical 1920x1080
fullscreen selection on a 200 Hz display; it does not claim VSync-synchronised
native 200 Hz scanout, true 2x Retina backing, physical sleep/wake, or external
display reconnect. Those remain separate hardware gates.

The `0.3.1` display-lifecycle work delivered the reusable foundation for those
runs: topology/backing/wake observation, fail-open
display-only IOSurface reset, real window-present timing, and opt-in exact-JAR
requirements for Retina, two-display migration and minimum refresh. With the
built-in Retina panel and the 200 Hz VX24G10 active, earlier strict cold/warm
development runs passed real 2x backing and migration across both displays.
The final 600-sample
200 Hz phases measured 181.52/198.64 calls per second with p50 intervals of
5.081/5.003 ms in the explicit VSync-off software-paced mode, zero >=100 ms
stalls and zero ownership failures. Those measurements came from a development
candidate rather than the final `0.3.1` SHA. Sustained native
VSync-synchronised 200 Hz scanout, final-artifact Retina/migration
qualification, physical display disconnect/reconnect and physical sleep/wake
remain open hardware gates. See
[`DISPLAY_LIFECYCLE_QA.md`](DISPLAY_LIFECYCLE_QA.md).

## Performance interpretation

The two historical experimental A/B runs showed a 42.6-44.8% CPU p50 improvement,
27.1-37.4% CPU p95 improvement, a first-run 38.3% CPU p99 improvement and a
reverse-run 1.5% CPU p99 regression within the 5% limit, plus 10.5-19.5% GPU
p95 and 11.5-32.5% GPU p99 improvements in the exact M4 Pro 1280x720
Complementary scene. Both sides had zero >=100 ms stutters.

This is evidence that the Stage 9 path can raise FPS and reduce frame time in
that CPU/driver-sensitive scene. It is not a universal percentage promise:
fragment math, high shadow resolution, volumetrics, bandwidth, resolution,
pack settings and world complexity can move the bottleneck elsewhere.

The stable `0.4.1` field pair measured 58.01 versus 51.21 average FPS and
36.74 versus 36.14 FPS 1% low. Since Iris/OpenGL rendered both visible shader
paths, the +13.29% average is retained as a non-regression measurement, not as
a Metal shader-acceleration claim.

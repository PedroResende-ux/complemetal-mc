# Metal 4 implementation status

This document records the Metal 4 implementation and the current
`0.4.1+mc26.2` release boundary. Runtime status must distinguish Metal 4
availability, experimental Iris graph ownership, and the stable Iris/OpenGL
fallback; creating an MTL4 object alone is not an active draw claim.

| Area | `0.4.1` status |
| --- | --- |
| Metal 4 API/runtime detection | Implemented with a real command-buffer and completion-feedback probe |
| MTL4 command queue and allocator | Implemented; Iris graph use is explicit experimental opt-in |
| Iris GLSL -> SPIR-V -> MSL | Implemented after final Iris transformations; bounded cache |
| Apple Metal library validation | Implemented for every accepted stage |
| Device-qualified pipeline archive | Implemented with warm reuse and stale-archive recovery |
| Full Iris pipeline/resource ABI | Captured, reflected and fail-closed |
| Persistent Metal graph resources | Implemented with history preservation and bounded native allocation |
| MTL4 frame execution | Implemented for validated phases; not the stable Iris visible path |
| Hazards | Explicit RAW/WAR/WAW tracking and MTL4 barriers |
| Texture input | IOSurface handoff or bounded resident Metal texture |
| Vertex/index input | Bounded resident Metal buffer cache |
| Presentation | Asynchronous fenced IOSurface handoff; exact GPU-only BGRA presentation; reusable last completed surface |
| Visual parity | Three consecutive exact final-frame comparisons required before ownership |
| Metal 3 fallback | Iris/OpenGL remains visible; no MTL4 draw ownership |
| Resize/fullscreen/surface restore | Exact-JAR validated |
| Overworld/Nether/End transition | Exact-JAR validated |
| Iris shader toggle/reload | Exact-JAR validated |
| Historical Stage 9 frame-time gate | Passed twice in opposite launch orders |
| Stable full-modpack fullscreen gate | Passed enabled/disabled at 1920x1080@200 Hz |
| True 2x Retina backing | Passed on an earlier development candidate; outside final-artifact qualification |
| Physical sleep/wake | Not validated; outside stable scope |
| External display reconnect | Not validated; outside stable scope |
| Real presented 200 Hz | Software-paced development probe passed; native synchronized scanout is not claimed |

## `0.3.1` display and replay hardening

The stable client now has an explicit GLFW display-state observer and a
display-only native reset. Monitor topology, active monitor, backing scale,
framebuffer size, visibility/iconification, window recreation and a likely
sleep/wake gap invalidate the presentation bridge fail-open. Translation,
pipeline archives, in-flight graph presentation tokens, persistent graph
attachments and resident Metal inputs remain intact.

Earlier development-candidate cold and warm exact-JAR runs passed with both
the built-in Retina panel and the 200 Hz VX24G10 active. The strict runs moved
the live window through both
displays, verified a real 2x framebuffer on Retina, returned to the external
200 Hz mode and retained Metal graph ownership with three display resets per
run. Around the actual Minecraft `GlSurface.present()` call, 600 samples
measured 181.52 Hz and 198.64 Hz respectively; p50 stayed at 5.081/5.003 ms,
with zero >=100 ms stalls and zero Metal ownership failures. The passing
profile is explicitly `software-paced-vsync-off`: it verifies a high-refresh
path rather than a 60 Hz clamp, but not guaranteed or VSync-synchronised 200 Hz
scanout.

The hardware-candidate run also exposed and fixed a missed Cocoa/GLFW
backing-size callback:
GLFW reported the correct 1920x1080 backing for a 960x540 Retina window while
Minecraft still cached 960x540. The client now synchronizes the direct GLFW
framebuffer size and invokes Minecraft's normal framebuffer-resize handling
before lifecycle analysis.

The final `0.3.1` release additionally fences asynchronous work to its display
lifecycle generation, releases captured surface leases exactly once, and
rejects incomplete resources before graph ownership. Its final presentation
uses an exact rectangle-texture shader with framebuffer coordinates and BGRA
channel order instead of an ambiguous framebuffer blit. GL bindings are
deleted before native IOSurfaces return to the Metal pool, closing the
post-reset black/magenta-tile race found during warm QA.

The exact final JAR passed automated Metal 4 and forced Metal 3 cold/warm runs
without physical monitor actions. Physical display disconnect/reconnect and
physical sleep/wake remain unperformed hardware actions. Exact evidence and
reproduction commands are in
[`DISPLAY_LIFECYCLE_QA.md`](DISPLAY_LIFECYCLE_QA.md).

## Production activation

Stable `0.4.1` never turns Iris draw interception on from static eligibility.
The global default is off for every JAR version, OS, and architecture. An
explicit development `-Dmetalrender.irisMetal.enabled=true` can select the
experimental path; native availability, translation, pipeline, graph, parity,
and presentation gates must still pass before suppression. `false` remains a
hard safe-disable override.

## Backend names

- `METAL4`: the native runtime probe passed and the validated Iris graph is
  actively encoded through MTL4.
- `METAL4_RUNTIME_VERIFIED_METAL3_RENDER`: MTL4 runtime probing passed, but
  the active compatibility draw stream is still Metal 3.
- `METAL3`: Metal 4 was not requested.
- `METAL3_FALLBACK_NO_METAL4`: Metal 4 was requested but unavailable.
- `METAL3_FALLBACK_METAL4_PROBE_PENDING`: configuration/probe is incomplete.
- `METAL3_FALLBACK_METAL4_PROBE_FAILED`: the real MTL4 probe failed.
- `UNAVAILABLE`: native initialization did not complete; Minecraft retains its
  normal renderer.

`nIsMetal4Active()` means the runtime probe completed. It is not enough to
prove visible Metal execution. `nIsMetal4DrawPathActive()` plus exact full
graph ownership/presentation telemetry establish the active MTL4 draw path.

## Correctness and ownership rules

Pipeline candidates are rejected when required vertex formats, attachments,
resource bindings, subresources, buffer ranges, texture formats, topology or
specialization state are unknown. Geometry shaders are unsupported. The
renderer does not synthesize missing resources.

Before production ownership, Metal final output must match Iris/OpenGL for
three consecutive frames. The final acceptance run uses exact native row order
and recorded zero different pixels, zero RMSE and zero maximum channel delta.

Each submitted frame owns its IOSurface and feedback state until it is
completed, promoted, bound and fenced or explicitly discarded. Status,
promotion and bind calls avoid render-thread mutex waits only when the caller
can safely show the previous fenced surface; the first usable surface remains
blocking so startup cannot suppress a frame without an image.

## Performance status

The experimental Stage 9 renderer passed two 600-frame matched warm-cache A/B gates in
opposite launch orders. Metal improved every required CPU and GPU percentile,
with no >=100 ms stutters and no MTL4 commit-feedback errors. Detailed numbers
and the exact scenario are in [README.md](../README.md). Those measurements
were made for `0.3.0`; `0.3.1` is a correctness/lifecycle maintenance release
and does not claim a newly measured FPS percentage. Its final exact-artifact
evidence is in
[`RELEASE_CHECKLIST_0.3.1.md`](RELEASE_CHECKLIST_0.3.1.md).

The measured uplift applies to the historical M4 Pro, 1280x720 Complementary
Reimagined r5.8.1 scenario. A GPU-bound pack or resolution can show a smaller
gain because shader math and memory traffic are not removed by translation.

The stable `0.4.1` production field pair passed at 58.01 versus 51.21 average
FPS and 36.74 versus 36.14 FPS 1% low in physical 1920x1080@200 Hz fullscreen.
Iris/OpenGL remained visible on both sides, so this proves non-regression for
the qualified modpack rather than active MTL4 shader ownership.

## Stable support boundary

Stable `0.4.1` means Iris/OpenGL ownership while the full graph is
requalified. Unsupported content retains Iris/OpenGL or Minecraft rendering. Native
entity/particle replacement, experimental mesh shaders, Hi-Z culling,
MetalFX conformance, physical power/display lifecycle and native synchronized
200 Hz presentation are not implied by the Stage 9 Iris graph release.

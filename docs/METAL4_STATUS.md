# Metal 4 implementation status

This document distinguishes implemented behavior from planned work. It is part
of the release contract: the status exposed by the game must match this table.

| Area | `0.2.1+mc26.2` status |
| --- | --- |
| Runtime API detection | Implemented |
| Metal 4 command queue / allocator / completion probe | Implemented when exposed by the OS |
| Runtime configuration and status reporting | Implemented |
| Static native arena startup cap | Implemented with `recommendedMaxWorkingSetSize` |
| IOSurface slot ownership and completion fences | Implemented |
| Reversed-Z depth state | Implemented |
| MetalFX spatial scaling path | Experimental opt-in; outside stable support while hardware conformance is pending |
| Atlas/lightmap handoff | Fenced asynchronous GPU readback |
| Metal 3 render compatibility stream | Active stable terrain draw path |
| Safe alpha composite with vanilla feature overlay | Default |
| Fast vanilla-terrain suppression | Disabled pending depth/coverage validation |
| Native entity/particle replacement | Release-locked off pending fenced texture readback |
| Iris compatibility transition gate | Live Iris state and the applied renderer pause latch both block Metal frame encoding |
| MTL4 render pipeline / draw encoder | Not active |
| Validated mesh-shader terrain path | Disabled |
| Validated Hi-Z occlusion path | Disabled |
| Overworld, Nether and End active-path transition | Validated |
| Window resize and fullscreen transition | Validated |
| True 2x Retina framebuffer backing | Not validated; outside stable support |
| Sleep/wake and display hot-plug/reconnect | Not validated; outside stable support |
| Real presented 200 Hz pacing | Not validated; outside stable support |

The bundled shader library is intentionally compiled with the Metal 3 language
standard because the active draw stream is still the compatibility path.
Creating Metal 4 queue/allocator objects does not convert those pipelines or
encoders into MTL4.

## `0.3.0-alpha.1` Iris compiler milestone

The development alpha adds a third, separately opt-in boundary after the Iris
SPIR-V/MSL cache. On a ready Apple Silicon renderer it asks the Apple Metal
runtime compiler to create an ephemeral executable `MTLLibrary` for each
verified MSL stage, resolves `main0`, verifies its function type and releases
both objects immediately. The boundary requires argument-buffer tier 2 and is
limited to 16 MiB of MSL per stage.

This validation runs in both the Metal 4 hybrid and forced Metal 3 profiles;
it is not MTL4 draw encoding. It creates no render pipeline, persistent
archive, command encoder or Iris draw. Exact-JAR cold/warm tests passed 152 of
152 stages in both profiles, while visible shader-pack ownership remained with
Iris/OpenGL. The next roadmap target is complete Iris pipeline-state capture,
not a renderer cutover.

## Stage 3 Iris pipeline-state milestone

The development branch now captures and content-keys the complete pipeline
state observed for registered Iris programs. The exact-JAR acceptance route
passed Metal 4 and forced Metal 3 cold/warm runs with 231 programs, 462
Apple-compiled MSL stages, 185 mapped variants and zero incomplete,
unsupported or failed state mappings. The final four runs produced the same
90-entry per-run state digest.

This remains metadata capture, not MTL4 draw encoding. Twelve observed
line-loop or triangle-fan variants carry explicit index-expansion blockers and
continue through Iris/OpenGL. Resource binding is the next ordered gate.

Late native encode or presentation failures fall back on the following frame;
Minecraft cannot replay vanilla submissions already skipped in the in-flight
frame. One incomplete in-flight frame therefore remains a documented recovery
limitation rather than a promise of replay.

## Backend names

- `METAL4_RUNTIME_VERIFIED_METAL3_RENDER`: an MTL4 command buffer was encoded,
  committed and completed without feedback errors, but draw commands still use
  the Metal 3 compatibility stream.
- `METAL3`: Metal 4 was not requested.
- `METAL3_FALLBACK_NO_METAL4`: Metal 4 was requested but is not exposed by the
  current device/operating-system runtime.
- `METAL3_FALLBACK_METAL4_PROBE_PENDING`: MTL4 objects exist, but the completion
  probe has not established a usable runtime.
- `METAL3_FALLBACK_METAL4_PROBE_FAILED`: Metal 4 is exposed, but the real
  command-buffer completion probe failed.
- `UNAVAILABLE`: native initialization did not complete and Minecraft retains
  its normal renderer.

`nIsMetal4Active()` reports the completed runtime probe. It must not be
interpreted as proof that the renderer's draw stream uses MTL4.
`nIsMetal4DrawPathActive()` is the separate draw-path signal and is false in
this release.

The packaged native-payload smoke test verifies extraction, initialization,
ABI/status signals and selected resource lifetimes. The regular client game
test covers the active hybrid and native-disabled lifecycle. The separate
exact-release-JAR Iris harness adds coarse shader-toggle image checks. Those
checks reject uniform output and verify that the re-enabled Iris image is
closer to the initial Iris image than to the shaders-off image, but they are
not full reference-image parity proof.

## Stable support boundary

Stable in `0.2.1+mc26.2` means the conservative hybrid profile:

1. Selected terrain is mirrored through the Metal 3 compatibility stream.
2. Minecraft retains unsupported or deliberately excluded content, including
   native entity and particle replacement.
3. Metal 4 queue/allocator APIs may be initialized and completion-probed, but
   no game draw is encoded through MTL4.
4. World lifecycle validation includes Overworld, Nether and End transitions.
5. Iris shader packs retain Iris' OpenGL renderer. The opt-in
   GLSL-to-SPIR-V-to-MSL path only prepares cache artifacts and makes no
   performance claim.
6. Initialization, capture and compatibility failures remain fail-open to
   Minecraft's renderer.

The following are explicitly outside this release's validated stable scope:

- a true 2x Retina backing framebuffer;
- macOS sleep/wake;
- external-display hot-plug or reconnect;
- real display presentation at 200 Hz.

The native 200 Hz stress loop exercises offscreen buffer throughput, ownership
and slot recycling. It is not a display cadence or presentation test.

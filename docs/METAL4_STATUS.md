# Metal 4 implementation status

This document distinguishes implemented behavior from planned work. It is part
of the release contract: the status exposed by the game must match this table.

| Area | `0.2.0-beta.2+mc26.2` status |
| --- | --- |
| Runtime API detection | Implemented |
| Metal 4 command queue / allocator / completion probe | Implemented when exposed by the OS |
| Runtime configuration and status reporting | Implemented |
| Static native arena startup cap | Implemented with `recommendedMaxWorkingSetSize` |
| IOSurface slot ownership and completion fences | Implemented |
| Reversed-Z depth state | Implemented |
| MetalFX spatial scaling path | Implemented, opt-in; hardware conformance pending |
| Atlas/lightmap handoff | Fenced asynchronous GPU readback |
| Metal 3 render compatibility stream | Active beta draw path |
| Safe alpha composite with vanilla feature overlay | Default |
| Fast vanilla-terrain suppression | Disabled pending depth/coverage validation |
| Native entity/particle replacement | Release-locked off pending fenced texture readback |
| MTL4 render pipeline / draw encoder | Not active |
| Validated mesh-shader terrain path | Disabled |
| Validated Hi-Z occlusion path | Disabled |
| Full in-game visual conformance | Required before stable release |

The bundled shader library is intentionally compiled with the Metal 3 language
standard because the active draw stream is still the compatibility path.
Creating Metal 4 queue/allocator objects does not convert those pipelines or
encoders into MTL4.

Late native encode or presentation failures fall back on the following frame;
Minecraft cannot replay vanilla submissions already skipped in the in-flight
frame. Stable-release conformance must include deliberate late-failure tests.

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
ABI/status signals and selected resource lifetimes. The client game test adds
world lifecycle plus coarse shader-toggle image checks. Those checks reject
uniform output and verify that the re-enabled Iris image is closer to the
initial Iris image than to the shaders-off image, but they are not full
reference-image parity proof.

## Stable-release exit gate

Before changing the version from beta to stable:

1. Build offline shaders with full Xcode.
2. Run the active Metal client test and native-disabled vanilla baseline with
   Java 25; retain sanitizer output and screenshots.
3. Start a Fabric 26.2 client with the exact release JAR.
4. Test world load/unload, dimension switch and resource-pack reload.
5. Compare terrain, fluids, entities, block entities, particles, weather, GUI
   and transparency against the normal renderer.
6. Exercise window resize, fullscreen, Retina scaling, sleep/wake and display
   hot-plug.
7. Run sustained high native-buffer allocation at the static device cap and a
   200 Hz frame-pacing test.
8. Confirm safe fallback with Metal 4 disabled and with the native library
   intentionally unavailable.
9. Confirm entity/particle native replacement remains release-locked.
10. Archive logs, screenshots and hardware/OS details with the release.

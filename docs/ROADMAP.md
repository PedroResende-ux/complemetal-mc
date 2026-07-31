# Iris-to-Metal roadmap

This roadmap is ordered. A later stage cannot enable Metal draws until every
earlier acceptance gate is green. Iris/OpenGL remains the visible renderer
through the validation stages, and every experimental failure stays fail-open.

| Stage | Goal | State | Exit gate |
| --- | --- | --- | --- |
| 0 | Stable Minecraft 26.2 hybrid renderer | Complete in `0.2.1+mc26.2` | Exact-JAR Metal 4/Metal 3, Iris on/off/on, dimensions and native lifecycle pass |
| 1 | Final Iris GLSL -> SPIR-V -> MSL cache | Complete | 76 Complementary Reimagined programs, 152 SPIR-V and 152 MSL stages, clean cold/warm cache |
| 2 | Apple Metal compiler validation | Complete in `0.3.0-alpha.1+mc26.2` | Every generated stage creates an ephemeral `MTLLibrary`, resolves `main0` with the expected function type, then releases it; 152/152 and zero failures |
| 3 | Capture complete Iris pipeline state | Pending | Vertex layout, attachment formats, blend/depth/stencil/cull/topology and specialization state are captured and content-keyed |
| 4 | Reflect and bind resources | Pending | Uniforms, samplers, textures, images, UBOs and SSBOs have deterministic argument-buffer layouts and parity tests |
| 5 | Reproduce the Iris render graph | Pending | Shadow, geometry, composite and final pass routing, history ping-pong and barriers are represented without Metal output replacing Iris |
| 6 | Build the MTL4 pipeline/compiler cache | Pending | Device/OS/compiler-keyed pipelines load cold and warm, recover from stale archives and create no draw on failure |
| 7 | Shadow execution and visual parity | Pending | Metal renders offscreen beside Iris; automated image comparisons pass before any OpenGL draw is suppressed |
| 8 | Selective Metal cutover | Pending | One validated pass at a time replaces Iris, with immediate per-frame OpenGL fallback and lifecycle recovery |
| 9 | Performance and stable release | Pending | Reproducible A/B frame-time, stutter and GPU/CPU measurements show a benefit without visual or lifecycle regressions |

## Delivered Stage 2 boundary

Stage 2 compiles and validates generated MSL only. It does not create a render
pipeline, retain a library handle, encode a command or execute an Iris shader
through Metal. The translation profile emits Metal argument-buffer resource
declarations so real shader packs do not exceed Metal's direct buffer-index
limit; runtime binding remains Stage 4.

The exact alpha JAR passed cold and warm Complementary Reimagined runs in both
the Metal 4 hybrid and forced Metal 3 profiles. Each run validated 76 programs
and 152 ephemeral Metal libraries with zero unsupported stages, failures,
pending jobs or live libraries. Cold runs translated all 76 programs; warm
runs reused all 76 SPIR-V/MSL cache entries but intentionally repeated Apple
Metal compilation because no persistent library or pipeline artifact exists at
this stage.

Stage 3 is now the active development target: capture the complete Iris
pipeline state without changing visible rendering ownership.

The persistent pipeline cache belongs to stage 6 because its key must include
the complete state captured in stages 3-5 plus GPU identity, OS build and Metal
compiler version. Creating it earlier would cache guessed, unusable pipelines.

## Release rule

No FPS claim is made until stage 9. A translated or Metal-compiled shader is
not an accelerated renderer by itself. Each stage must retain evidence from
the exact packaged JAR. Before Stage 7, runtime ownership evidence must remain
Iris/OpenGL and the generated-MSL boundary must remain explicitly static and
validation-only rather than being reported as draw telemetry.

# Iris to Metal pipeline

The repository implements a complete experimental Iris-to-Metal 4 path. The
input is the final GLSL after Iris has applied shader-pack directives,
compatibility transforms, generated uniforms and Sodium vertex conventions;
capturing the original pack source would not reproduce the program Iris runs.

Stable `0.4.1+mc26.2` does **not** enable this draw-interception path
automatically. Iris/OpenGL remains the visible owner and duplicate Metal world
resources stay deferred. This document describes the experimental pipeline
and the gates it must pass before a future production requalification.

```text
Iris final linked GLSL
  -> shaderc with OpenGL-semantics SPIR-V
  -> SPIRV-Cross MSL and reflected ABI
  -> Apple MTLLibrary validation
  -> render-state-specific MTL4 pipeline
  -> device/OS/compiler-specific pipeline archive
  -> persistent Metal graph textures and resident inputs
  -> frame-level MTL4 commands and barriers
  -> completion feedback
  -> fenced IOSurface presentation
```

## Compilation and cache

Captured sources are bounded, hashed off the render thread and deduplicated.
Original shader-pack GLSL and diagnostic program names are not persisted.
SPIR-V/MSL artifacts are content-addressed and integrity-checked. The MSL
profile emits argument-buffer layouts so the shader does not exceed Metal's
direct resource-index limits.

Every accepted MSL stage is compiled through Apple's runtime compiler,
`main0` is resolved with the expected vertex/fragment function type, and its
identity joins the complete pipeline-state key. The pipeline archive is
qualified by shader/profile digest, state, GPU, OS build and compiler identity.
A corrupt/stale archive is bypassed and rebuilt rather than trusted.

The validated Complementary Reimagined r5.8.1 workload contains 231 linked
programs, 462 stages and 6,248 reflected resource declarations. This is exact
workload evidence, not a promise that arbitrary packs use only supported
features.

## Captured state and ABI

Pipeline identity includes:

- final vertex attribute locations, packed formats and step rates;
- color/depth/stencil attachment formats and sample count;
- blend equations/factors, color masks and logic relevant to output;
- depth/stencil tests, reference values and write masks;
- cull mode, front face, fill mode, depth bias and topology;
- SPIR-V specialization/function constants;
- uniforms, samplers, sampled/storage images, texture buffers, UBO ranges and
  SSBO bindings;
- framebuffer routing and history ping-pong identity.

Missing or contradictory information rejects only that Metal candidate. Iris'
OpenGL link is never replaced, so the safe renderer remains available.

## Graph execution

The frame graph models BEGIN, SHADOW, GEOMETRY, DEFERRED, COMPOSITE and FINAL
work, including clears, copies, blits, mip generation, read/write attachments
and history. Persistent graph textures preserve temporal state across frames.
Resource generations prevent a stale GL identity from aliasing a newly
created texture or buffer.

The production MGF9 packet carries complete clear/transfer/draw commands and
explicit hazards. Native execution tracks RAW, WAR and WAW dependencies and
encodes the corresponding MTL4 barriers. Compatible texture inputs are
IOSurface-backed or resident; geometry references bounded content-addressed
Metal buffers. Late OpenGL readback is promoted once into the resident cache
instead of being serialized into every frame packet.

The validated frame uses 13 render passes, 19 draws and 18 explicit barriers.
These counts are observations of the exact pack/scene, not hard-coded limits.

## Asynchronous ownership and presentation

Frame planning and native submission run off the render thread. Each native
submission returns a token and MTL4 commit-feedback state. When it completes,
its IOSurface is promoted into a fenced three-slot GL rectangle ring and blit
to the framebuffer expected by Iris. There is no CPU output copy and no
steady-state `glFinish`.

If the worker owns the native graph mutex, status/promotion/bind probes return
immediately. The renderer reuses the last completed, GL-fenced surface and
retries the pending surface on a following frame. The very first surface is
not allowed to defer because no prior valid image exists.

OpenGL shader draws are suppressed only after full graph validation and a
usable presentation surface exist. Resize, fullscreen, context generation,
shader reload and dimension changes invalidate stale ownership and recapture
the graph.

## Visual parity

Before ownership, Metal's FINAL output is compared with the paired Iris/OpenGL
output for three consecutive frames. The release gate checks dimensions,
orientation, different-pixel ratio, RMSE and maximum channel delta. Final
candidate acceptance recorded 3/3 passes with zero different pixels, zero RMSE
and zero maximum delta in native row order.

Parity validates the exact observed frame/state set. A new unsupported shader
or resource configuration fails open rather than inheriting an unrelated
parity result.

## Activation and fallback

Stable `0.4.1` always defaults this path off, including packaged Apple Silicon
macOS 26 builds. A developer can opt in explicitly with:

```text
-Dmetalrender.irisMetal.enabled=true
```

That property selects an unsupported experimental profile; it is not an
installation requirement or a stable-performance recommendation. Setting it
to `false` remains a hard safe-disable override.

Forced Metal 3 exact-JAR runs keep MTL4 pipeline readiness unsupported, encode
zero native Iris draws, suppress zero OpenGL draws, preserve shader-toggle and
dimension behavior, and exit normally. Native faults, missing APIs, cache
errors and unsupported shader state follow the same safe boundary.

## Expected FPS effect

Full graph ownership can reduce OpenGL driver/state overhead and make
submission more predictable. In two historical matched M4 Pro 1280x720
Complementary r5.8.1 experimental Stage 9 runs, Metal improved CPU p50 by
42.6-44.8%, CPU p95 by
27.1-37.4%, GPU p95 by 10.5-19.5% and GPU p99 by 11.5-32.5%. Every side
recorded zero >=100 ms stutters.

The reverse-order stable repeat kept CPU p50 42.6% and p95 27.1% faster while
CPU p99 was 1.5% slower, within the release gate's 5% tail limit; GPU p95 and
p99 remained 10.5% and 11.5% faster. The gain is workload-dependent.
Translation does not remove fragment shader
math, high-resolution shadows, volumetrics or memory bandwidth, so a GPU-bound
configuration can improve less. Visual parity and the matched performance
gate are both required; neither a translated shader nor a compiled pipeline is
accepted as an FPS claim by itself.

The stable `0.4.1` field pair instead measured 58.01 FPS enabled versus 51.21
disabled in 1920x1080@200 Hz fullscreen. Because Iris/OpenGL owned both visible
shader paths, the +13.29% average is a non-regression observation rather than
an Iris-to-Metal acceleration claim.

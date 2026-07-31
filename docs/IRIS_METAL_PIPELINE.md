# Iris to Metal pipeline

The implementation order and acceptance gates are tracked in
[`ROADMAP.md`](ROADMAP.md). The stages below must not be collapsed into a
single unvalidated renderer switch.

## Target architecture

This is a future target architecture, not part of the stable renderer contract.
The stable `0.2.1+mc26.2` draw path remains the Metal 3 compatibility stream.

The target path starts after Iris has completed all shader-pack preprocessing
and compatibility transforms:

```text
Iris final GLSL
  -> shaderc (OpenGL-semantics SPIR-V)
  -> SPIRV-Cross (MSL)
  -> Metal library
  -> render-state-specific Metal pipeline
  -> device-specific pipeline archive
  -> Metal draw/dispatch
```

Capturing shader-pack source before Iris transforms is not sufficient. The
input must be the final strings passed to Iris' OpenGL linker, otherwise Sodium
vertex formats, Iris compatibility patches, generated uniforms and shader-pack
directives can be missing.

## Current implementation boundary

The experimental foundation is excluded from the stable support profile and is
intentionally opt-in and fail-open:

| Layer | Status |
| --- | --- |
| Capture final strings at Iris `ShaderCreator.link` | Implemented by an optional mixin |
| Bounded off-thread hashing and deduplication | Implemented |
| In-process GLSL to SPIR-V with LWJGL shaderc | Experimental |
| In-process SPIR-V to MSL with LWJGL SPIRV-Cross | Experimental |
| Content-addressed SPIR-V/MSL cache | Experimental |
| Persist original shader-pack GLSL | Prohibited |
| Compile generated MSL into an ephemeral Metal library | Implemented as opt-in validation in `0.3.0-alpha.1+mc26.2` |
| Capture Iris framebuffer, blend, depth and vertex state | Pending |
| Reflect and bind Iris uniforms, samplers, images and buffers | Pending |
| Reproduce the Iris shadow/composite render graph on Metal | Pending |
| Create and load device-specific MTL4 pipeline archives | Pending |
| Execute Iris shader-pack draws through Metal | Pending |

The experiment never replaces Iris' normal OpenGL link. Unsupported stages,
translation failures, cache failures and missing LWJGL native modules are
contained in the background path; Iris continues with its normal renderer.
Geometry shaders are not advertised as supported because Metal has no direct
geometry-shader stage.

The Stage 2 validator accepts at most 16 MiB of verified MSL per stage. It
requires argument-buffer tier 2, compiles an executable MSL 3.0 library with
safe math and invariance enabled, resolves `main0` with the expected function
type, and releases the function and library synchronously. It has no pipeline,
archive, encoder or draw contract. A distinct deferred result protects client
startup, renderer restart and teardown races; terminal unsupported and failed
results remain separate and fail open to Iris.

The stable `0.2.1+mc26.2` exact-JAR cold/warm validation captured all 76
Complementary Reimagined r5.8.1 programs in both its Metal 4 hybrid and forced
Metal 3 test profiles. Each cold run produced 152 SPIR-V and 152 MSL stage
artifacts with zero failures; each warm run reused all 76 cache entries with
zero retranslations. A second independent Metal 4 cold run repeated the clean
result. This proves the translation/cache foundation for those exact workloads
only, not arbitrary shader-pack capacity and not Metal execution of the shader
pack.

The `0.3.0-alpha.1+mc26.2` exact-JAR gate repeated that matrix and additionally
compiled all 152 generated stages through the Apple Metal runtime compiler in
every cold and warm run. All four runs resolved the expected functions, left
zero live libraries, reported zero native compiler failures and produced the
same independently reconstructed compiled-artifact digest. This proves the
library compile/resolve/release boundary for that exact workload only. It does
not prove pipeline creation, resource binding, draw execution or arbitrary
shader-pack compatibility.

The capture/translation experiment is enabled only at JVM startup:

```text
-Dmetalrender.experimental.irisMetalPipeline=true
-Dmetalrender.experimental.irisMetalTranslation=true
-Dmetalrender.experimental.irisMetalLibraryValidation=true
```

The first two switches enable capture and translation. The third independently
enables Stage 2 library validation; all three are required for the complete
alpha milestone. An isolated cache root can be selected with
`-Dmetalrender.experimental.irisMetalCacheRoot=/absolute/path`. These switches
prepare persistent SPIR-V/MSL artifacts and validate them with ephemeral
in-memory libraries; they do not mean that the shader pack is rendered by
Metal. No compiled library is persisted.

## Why a translated shader is not yet a renderer

A vertex/fragment program is only one part of an Iris pass. A usable Metal
pipeline key also needs:

- vertex layout and step rate;
- color/depth attachment formats;
- blend, depth, stencil, cull and topology state;
- specialization/function constants;
- sampler, texture, image, UBO and SSBO bindings;
- framebuffer routing and ping-pong history;
- barriers and ordering between shadow, geometry, composite and final passes.

Those values must be captured from the same Iris program and render-graph
state, translated to Metal descriptors, and validated before suppressing the
OpenGL draw. Caching a generic pipeline with guessed state would not be
correct and would not accelerate the real pass.

The final archive key must include at least the final shader digest,
translation profile, complete pipeline state, GPU identity, OS build and Metal
compiler version. SPIR-V/MSL artifacts can be shared more broadly; compiled
Metal pipeline archives are device and system specific.

## Expected FPS effect

The current opt-in foundation does not replace Iris' normal OpenGL compilation
or rendering, so it does not reduce Iris' current shader-pack load time and
does not raise steady-state FPS. A cold cache adds shaderc, SPIRV-Cross and
Apple Metal compiler work. A warm cache skips shaderc/SPIRV-Cross but still
recompiles every ephemeral `MTLLibrary`; Stage 2 therefore adds validation work
in both cases.

Once the translated programs are actually used by the future Metal render
graph, the MSL and device pipeline caches can reduce Metal shader/pipeline
compilation stalls. That future benefit is not active in this stable release.

Steady-state FPS can improve only after the Iris passes actually execute
through Metal. The largest likely benefit is in CPU/driver-bound scenes with
many state changes and draw calls, where a native Metal render graph can reduce
OpenGL driver overhead and schedule work more predictably. A shader pack that
is already limited by fragment-shader math, shadow resolution, volumetrics or
memory bandwidth may show little improvement because translation does not make
that GPU work disappear. A regression is also possible until resource binding
and pass scheduling are tuned.

No percentage is a release claim. The acceptance test is an A/B capture of the
same world, seed, camera, shader-pack settings, resolution and frame limit,
recording frame-time percentiles, CPU time, GPU time and shader/pipeline
stutters for:

1. Iris OpenGL baseline;
2. translated Metal path with a cold cache;
3. translated Metal path with a warm cache.

Visual output must pass image comparison before performance results are
accepted.

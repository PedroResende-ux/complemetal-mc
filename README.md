# MetalRender

MetalRender is a Fabric client renderer for Apple Silicon Macs. Version
`0.3.0+mc26.2` adds a stable Iris-to-Metal 4 path for Minecraft Java Edition
26.2: Iris' final transformed GLSL is compiled through SPIR-V and MSL, cached
as device-qualified Metal pipelines, and executed as one persistent
Metal-owned frame graph. The final image is handed to Minecraft through a
fenced IOSurface without a CPU pixel copy.

The release is deliberately fail-open. If Metal 4, a translated shader,
pipeline state, resource binding, lifecycle state, or presentation surface is
not proven safe, MetalRender retains Iris/OpenGL or Minecraft's normal
renderer. It never guesses a missing binding or silently substitutes a shader.

> MetalRender is not affiliated with or endorsed by Apple or Mojang.

## Supported release matrix

| Component | Validated configuration |
| --- | --- |
| MetalRender | `0.3.0+mc26.2` |
| Minecraft | Java Edition 26.2 |
| Loader | Fabric Loader 0.19.3 or newer compatible build |
| Fabric API | 0.156.0+26.2 or newer compatible 26.2 build |
| Java | 25 |
| Platform | Apple Silicon, macOS 26, Metal 4 runtime |
| Iris | 1.11.2 for Minecraft 26.2 |
| Sodium | 0.9.1 for Minecraft 26.2 |
| Shader-pack acceptance workload | Complementary Reimagined r5.8.1 |
| Iris draw path | SHADOW, GEOMETRY, DEFERRED, COMPOSITE and FINAL graph owned by Metal 4 after parity validation |
| Presentation | Asynchronous fenced IOSurface handoff; no CPU output copy and no steady-state `glFinish` |
| Metal 3 / unavailable Metal 4 | Safe Iris/OpenGL or Minecraft fallback |

The packaged native library is arm64-only with a macOS 14 deployment target,
but automatic Iris graph ownership is enabled only by the packaged stable JAR
on Apple Silicon macOS 26 or newer. Other shader packs are not advertised as
prevalidated: supported states may use Metal after the same strict gates;
unknown or unsupported states remain on Iris/OpenGL.

The following are outside this release's validated scope:

- true 2x Retina framebuffer backing;
- physical macOS sleep/wake;
- external-display hot-plug or reconnect;
- real display presentation at 200 Hz;
- Intel Macs and non-macOS systems;
- geometry-shader packs, because Metal has no direct geometry-shader stage.

## What Stage 9 delivers

The stable path performs the complete validated chain:

```text
Iris final GLSL
  -> shaderc SPIR-V with OpenGL semantics
  -> SPIRV-Cross MSL
  -> Apple Metal library
  -> device/OS/compiler-qualified MTL4 pipeline archive
  -> persistent Metal graph resources
  -> frame-batched MTL4 passes, draws, transfers and barriers
  -> fenced IOSurface presentation
```

The implementation captures and keys the exact Iris vertex ABI, attachment
formats, blend/depth/stencil/raster state, specialization constants, uniforms,
samplers, textures, images, UBOs and SSBOs. It retains compatible buffers and
textures in bounded resident caches, tracks RAW/WAR/WAW graph hazards, and
uses asynchronous frame submissions so the render thread can reuse the last
completed surface rather than waiting behind native encoding.

Exact-JAR acceptance covers cold and warm caches, Iris on/off/on, Overworld,
Nether, End and return to Overworld, resize, fullscreen, surface suspend and
restore, exact three-frame visual parity, persistent pipeline archives, and a
forced Metal 3 fallback. See [the roadmap](docs/ROADMAP.md),
[Metal 4 status](docs/METAL4_STATUS.md), [pipeline details](docs/IRIS_METAL_PIPELINE.md),
[release checklist](docs/RELEASE_CHECKLIST_0.3.0.md), and
[changelog](CHANGELOG.md).

## Performance evidence

On the validated M4 Pro test scene at 1280x720 with Complementary Reimagined
r5.8.1, render distance 8, simulation distance 5, fixed camera, noon and clear
weather, two 600-frame warm-cache A/B runs passed in both launch orders.

First matched run:

| Metric | Iris/OpenGL | Metal 4 | Metal improvement |
| --- | ---: | ---: | ---: |
| CPU p50 | 5.898 ms | 3.253 ms | 44.8% |
| CPU p95 | 8.719 ms | 5.462 ms | 37.4% |
| CPU p99 | 11.118 ms | 6.858 ms | 38.3% |
| GPU p95 | 7.773 ms | 6.257 ms | 19.5% |
| GPU p99 | 9.596 ms | 6.477 ms | 32.5% |

Reverse-order repeat:

| Metric | Iris/OpenGL | Metal 4 | Metal improvement |
| --- | ---: | ---: | ---: |
| CPU p50 | 5.776 ms | 3.318 ms | 42.6% |
| CPU p95 | 6.808 ms | 4.965 ms | 27.1% |
| CPU p99 | 7.442 ms | 7.550 ms | -1.5% (within 5% gate) |
| GPU p95 | 7.073 ms | 6.328 ms | 10.5% |
| GPU p99 | 7.411 ms | 6.559 ms | 11.5% |

Both sides recorded zero >=100 ms CPU/GPU stutters, and Metal recorded zero
commit-feedback errors. These numbers prove an uplift for this exact matched
scene; they are not a universal FPS guarantee. Fragment-math, shadow,
volumetric, memory-bandwidth, resolution, pack and world bottlenecks can change
the result.

## Install

1. Install Minecraft 26.2, Fabric Loader, Fabric API, Sodium and Iris.
2. Use Java 25 and place the MetalRender JAR in the instance's `mods` folder.
3. Install/select the shader pack, start the game, and run
   `/metalrender status`.

No JVM enable flags are required for stable `0.3.0`. To disable the complete
Iris-to-Metal path for troubleshooting, add:

```text
-Dmetalrender.irisMetal.enabled=false
```

After disabling, Iris continues through OpenGL. Include the flag state when
reporting a bug.

## Build and verify

Development build:

```bash
./gradlew test check build
```

Publishable release build with full Xcode selected:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew clean test check build releaseCheck
```

`releaseCheck` rebuilds the native arm64 library and offline Metal shaders,
checks all Java/JNI declarations, runs the test suite, and verifies the exact
JAR payload. Xcode 26 may require its separately installed Metal Toolchain:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild -downloadComponent MetalToolchain
```

Useful focused checks:

```bash
./gradlew test checkJniParity
./gradlew verifyReleaseJar
./scripts/run_asan_smoke.sh
python3 scripts/exact_jar_qa.py --help
python3 scripts/stage9_performance_qa.py --help
```

## Reporting bugs

Include the MetalRender/Minecraft/macOS versions, Mac model, shader pack,
`/metalrender status`, `latest.log`, whether the issue remains after
`/metalrender restart`, and a screenshot or short capture. Also state whether
`-Dmetalrender.irisMetal.enabled=false` removes the issue.

## License

Apache License 2.0. See [LICENSE](LICENSE).

# MetalRender

MetalRender is an experimental Fabric client renderer for Apple Silicon Macs.
It mirrors selected Minecraft render data into a native Metal backend and
falls back to Minecraft's normal renderer whenever the native path is
unsupported or cannot be initialized.

The `0.2.x` line targets Minecraft Java Edition 26.2 and introduces an
experimental Metal 4 runtime scaffold. The current draw path is deliberately
hybrid: MetalRender probes and instantiates Metal 4 command-queue and allocator
objects when the operating system exposes them, while native draw encoding
remains on the Metal 3 compatibility stream.

> MetalRender is not affiliated with or endorsed by Apple or Mojang.

## Support matrix

| Component | Supported configuration |
| --- | --- |
| MetalRender | `0.2.0-beta.2+mc26.2` |
| Minecraft | 26.2 |
| Loader | Fabric Loader 0.19.3 or newer |
| Fabric API | 0.156.0+26.2 or newer compatible 26.2 build |
| Java | 25 |
| Platform | Apple Silicon macOS |
| Native deployment target | macOS 14.0 |
| Metal 4 | Runtime-detected, optional hybrid path |
| Sodium | Optional; 0.9.1 for Minecraft 26.2 is the compatibility target |
| Iris | Optional; 1.11.2 for Minecraft 26.2 is the compatibility target |
| Other operating systems / Intel Macs | Safe vanilla fallback, no MetalRender acceleration |

The packaged native library is arm64-only. Metal 4 is never assumed from a
marketing device name: MetalRender probes the runtime APIs and reports the
result in the settings screen and `/metalrender status`.

## What changed for 26.2

- Updated the Fabric toolchain, mappings and Java target for Minecraft 26.2.
- Rebased renderer hooks on the 26.2 extraction/render lifecycle.
- Added lazy, checksum-versioned loading of the bundled native library.
- Added fail-open initialization and explicit restart/shutdown handling.
- Added a conservative Metal terrain composite between vanilla opaque terrain
  and vanilla feature rendering, keeping entities, particles and incomplete
  chunk areas on the vanilla path by default.
- Replaced synchronous raw OpenGL atlas/lightmap readback with Minecraft's
  fenced texture-to-buffer handoff.
- Reworked IOSurface reuse around per-slot state and completion fences.
- Corrected reversed-Z depth state and private OIT texture allocation.
- Replaced the fixed 3 GiB native arena with a startup budget that is capped
  against Metal's `recommendedMaxWorkingSetSize`. This is a static safety cap,
  not a runtime memory-pressure handler.
- Added optional MetalFX scaling and runtime QoS controls.
- Match the native frame-time budget to the active display refresh and the
  user's Minecraft FPS cap by default.
- Disabled unvalidated mesh-shader and Hi-Z paths by default.
- Added unit, JNI parity and packaged-JAR verification.
- Added automatic Iris shader-pack compatibility pause plus an opt-in,
  fail-open capture and GLSL-to-SPIR-V-to-MSL translation foundation.

See [Metal 4 status](docs/METAL4_STATUS.md) for the exact implementation
boundary, [Iris to Metal pipeline](docs/IRIS_METAL_PIPELINE.md) for the shader
translation contract, and [the changelog](CHANGELOG.md) for release details.

## Install

1. Install Minecraft 26.2, Fabric Loader and Fabric API.
2. Use a Java 25 runtime.
3. Copy the MetalRender JAR into the instance's `mods` directory.
4. Start the game and run `/metalrender status`.

MetalRender does not abort startup on an unsupported machine. If the platform,
Minecraft backend or native library is unsuitable, it stays disabled and
Minecraft continues with its normal renderer.

Capture and initialization failures retain the normal renderer immediately.
If native encoding or presentation fails after a vanilla submission has
already been suppressed, the in-flight frame can be incomplete; the handshake
is reset so the next frame returns to vanilla.

## Build

```bash
./gradlew clean test check build
```

This is the development build and test path. It does not pretend that an
offline Metal shader library exists when the Metal compiler is unavailable.

To rebuild the native library without recompiling offline Metal shaders:

```bash
BUILD_SHADERS=0 ./compile_native.sh
```

A full shader rebuild requires the complete Xcode toolchain selected with
`DEVELOPER_DIR`; Command Line Tools alone do not provide `metal` and
`metallib`. Xcode 26 may also require its separately installed Metal Toolchain
component:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild -downloadComponent MetalToolchain
```

For a publishable artifact, run the fail-closed release gate with full Xcode
selected:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew clean test check build releaseCheck
```

`releaseCheck` rebuilds the native library and every offline shader, verifies
source and packaged-binary JNI parity, and rejects a JAR without a compiled
`shaders.metallib`. Ordinary development builds can still use the native
inline-shader fallback.

Useful focused checks:

```bash
./gradlew test checkJniParity
./gradlew verifyReleaseJar
```

## Runtime controls

Open the MetalRender panel from Video Settings or use `/metalrender config
open`. The Metal 4 hybrid runtime is enabled by default when the operating
system exposes it; MetalFX remains opt-in. Both retain conservative fallbacks.
Safe alpha-overlay mode keeps vanilla terrain underneath and renders vanilla
entities, particles and translucent terrain after the Metal opaque composite.
Fast terrain replacement remains a command-line development experiment until
native depth interop and full mesh coverage pass conformance.
Experimental mesh shaders and Hi-Z culling are off in the supported release
configuration; explicit JVM properties can opt into those development paths.
Native entity/particle replacement is release-locked off because that legacy
path still uses unsafe raw OpenGL texture readback on the current macOS driver.

See [COMMANDS.md](COMMANDS.md) for the complete command list.

This beta is not a complete MTL4 renderer and carries no claim of stable visual
parity or a guaranteed performance uplift. Its opt-in Iris translator prepares
SPIR-V/MSL cache artifacts in the background but does not replace Iris'
OpenGL compilation or draws, so it does not currently increase Iris FPS. The
native payload and lifecycle checks complement, but do not replace, visual
comparison on real hardware.

## Reporting bugs

Include:

- the MetalRender version and Minecraft version;
- macOS version and Mac model;
- output from `/metalrender status`;
- `latest.log`;
- whether the issue disappears after `/metalrender restart`;
- a screenshot or short capture for visual corruption.

Do not report a successful synthetic/native smoke test as proof of correct
in-game rendering. A release still needs world, entity, particle, UI,
transparency, resize, fullscreen and sleep/wake visual checks on real hardware.

## License

Apache License 2.0. See [LICENSE](LICENSE).

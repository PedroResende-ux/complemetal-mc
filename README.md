<p align="center">
  <img src="src/main/resources/assets/complemetal/icon.png" width="160" alt="Complemetal icon">
</p>

# Complemetal

[GitHub release](https://github.com/daniiarkg/complemetal-mc/releases/tag/v0.4.0%2Bmc26.2)
· [Modrinth](https://modrinth.com/mod/complemetal)
· [Architecture graphs](docs/ARCHITECTURE_GRAPH.md)

Complemetal is a Fabric client renderer for Apple Silicon Macs. It captures
the final shader programs and render graph produced by Iris, translates the
validated workload through SPIR-V and MSL, executes it with Metal 4, then
presents the completed image to Minecraft through a fenced IOSurface bridge.

The renderer is deliberately **fail-open**: if translation, render state,
resource bindings, visual parity, display lifecycle or presentation cannot be
proven correct, Iris/OpenGL or Minecraft keeps ownership. Complemetal does not
invent missing bindings and does not suppress a visible OpenGL draw before a
usable Metal result exists.

## Origin

Complemetal is a continuation and fork of
[MetalRender by pebbles_boon / webblepebbles](https://github.com/webblepebbles/MetalRender).
The upstream project supplied the original Apple Silicon Metal renderer
foundation. The Minecraft 26.2 port, Iris translation/compiler path, complete
pipeline and resource capture, Metal render graph, parity/cutover system,
display lifecycle hardening and release QA were developed in this fork.

See [project lineage and attribution](docs/LINEAGE.md) and the root
[NOTICE](NOTICE). The project remains under Apache License 2.0.

> Complemetal is not affiliated with or endorsed by Apple, Mojang, Microsoft,
> Iris, Sodium or the Complementary shader-pack authors.

## Supported release matrix

| Component | Validated configuration |
| --- | --- |
| Complemetal | `0.4.0+mc26.2` |
| Minecraft | Java Edition 26.2 |
| Loader | Fabric Loader 0.19.3 or newer compatible build |
| Fabric API | 0.156.0+26.2 or newer compatible 26.2 build |
| Java | 25 |
| Platform | Apple Silicon, macOS 26+, Metal 4 runtime |
| Iris | 1.11.2 for Minecraft 26.2 |
| Sodium | 0.9.1 for Minecraft 26.2 |
| Shader-pack acceptance workload | Complementary Reimagined r5.8.1 |
| Metal-owned Iris phases | SHADOW, GEOMETRY, DEFERRED, COMPOSITE and FINAL after strict validation |
| Presentation | Fenced IOSurface plus exact GPU-only BGRA presentation; no CPU output copy |
| Unsupported state / Metal 3 | Safe Iris/OpenGL or Minecraft fallback |

The packaged native library is arm64-only with a macOS 14 deployment target,
but automatic Iris graph ownership activates only for a packaged stable JAR on
Apple Silicon macOS 26 or newer. Other shader packs may use Metal when their
observed states pass the same gates; they are not advertised as prevalidated.
Geometry-shader packs cannot be translated directly because Metal has no
geometry-shader stage.

## Render path

```text
Iris final transformed GLSL
  -> shaderc SPIR-V with OpenGL semantics
  -> SPIRV-Cross reflection + MSL
  -> Apple MTLLibrary validation
  -> complete render-state-specific Metal pipeline
  -> GPU/OS/compiler-qualified persistent archive
  -> persistent Metal textures and resident buffers
  -> frame-level MTL4 passes, draws, transfers and barriers
  -> exact three-frame comparison with Iris/OpenGL
  -> asynchronous fenced IOSurface presentation
```

The validated Complementary workload contains 231 linked programs, 462 shader
stages and 6,248 reflected resource declarations. A representative steady
frame contains 13 render passes, 19 draws and 18 explicit RAW/WAR/WAW hazard
barriers. These are observations from the qualified workload, not hard-coded
limits.

The final image still crosses a GPU-only IOSurface-to-OpenGL/CGL presentation
boundary because Minecraft owns the window. It is not direct `CAMetalLayer`
scanout and it is not a generic OpenGL-to-Metal compatibility layer.

## Performance evidence

Two matched 600-frame warm-cache A/B runs on an M4 Pro at 1280x720 with
Complementary Reimagined r5.8.1 passed in opposite launch orders:

| Metric | Observed Metal improvement range |
| --- | ---: |
| CPU p50 frame time | 42.6–44.8% |
| CPU p95 frame time | 27.1–37.4% |
| GPU p95 frame time | 10.5–19.5% |
| GPU p99 frame time | 11.5–32.5% |

Every side recorded zero frames at or above 100 ms. In the reverse-order run,
CPU p99 was 1.5% slower, within the release gate's 5% tail limit. These results
prove an uplift for that exact scene; they are not a universal FPS guarantee.
High-resolution shadows, volumetrics, fragment math, memory bandwidth,
resolution, shader settings and world complexity can move the bottleneck.

The public `0.4.0` change is a rebrand and compatibility migration. It does not
claim a new performance percentage beyond the Stage 9 measurements.

## Install

1. Install Minecraft 26.2, Fabric Loader, Fabric API, Sodium and Iris.
2. Run the instance with Java 25.
3. Place `complemetal-0.4.0+mc26.2.jar` in the instance's `mods` folder.
4. Select the shader pack and run `/complemetal status` in a world.

Download the exact release JAR from
[GitHub Releases](https://github.com/daniiarkg/complemetal-mc/releases/tag/v0.4.0%2Bmc26.2)
or [Modrinth](https://modrinth.com/mod/complemetal). New Modrinth projects can
remain unavailable publicly while their first release is under moderation.

No JVM enable flags are required for the packaged stable release. The emergency
fallback override intentionally keeps its legacy name:

```text
-Dmetalrender.irisMetal.enabled=false
```

With that override, shader rendering stays on Iris/OpenGL.

## Migration from MetalRender

- Fabric mod id: `complemetal`; the metadata also provides the legacy
  `metalrender` alias.
- Primary commands: `/complemetal` and `/cm`.
- Legacy commands: `/metalrender` and `/mr` remain available.
- New config: `config/complemetal.json`.
- Existing `config/metalrender.json` is read and migrated automatically when
  the new config is absent; the legacy file is left intact as a backup.
- Java packages, JNI exports, `libmetalrender.dylib`, cache formats and
  `metalrender.*` JVM properties remain stable internal compatibility
  boundaries.

## Build and verify

Development checks:

```bash
./gradlew --no-daemon test check build
```

Publishable native release build:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew --no-daemon clean test check build releaseCheck
```

`releaseCheck` rebuilds the native arm64 library and offline Metal shaders,
checks all Java/JNI declarations, runs the unit validators and verifies the
packaged JAR. Xcode 26 may require the separately downloadable Metal
Toolchain.

The exact packaged JAR is the release authority. The isolated harness supports
Metal 4 and forced Metal 3 cold/warm runs:

```bash
python3 scripts/exact_jar_qa.py --help
```

## Documentation

- [Detailed architecture graphs](docs/ARCHITECTURE_GRAPH.md)
- [Complete technical architecture](docs/TECHNICAL_ARCHITECTURE.md)
- [Russian documentation](docs/TECHNICAL_ARCHITECTURE_RU.md)
- [Project lineage and attribution](docs/LINEAGE.md)
- [Iris-to-Metal pipeline](docs/IRIS_METAL_PIPELINE.md)
- [Metal 4 implementation status](docs/METAL4_STATUS.md)
- [Nine-stage roadmap](docs/ROADMAP.md)
- [Display lifecycle QA](docs/DISPLAY_LIFECYCLE_QA.md)
- [Commands and diagnostics](COMMANDS.md)
- [Changelog](CHANGELOG.md)

## Reporting bugs

Include the Complemetal, Minecraft and macOS versions, Mac model, Iris/Sodium
versions, shader pack and settings, `/complemetal status`, `latest.log`, and a
screenshot or short capture. Also state whether
`-Dmetalrender.irisMetal.enabled=false` removes the issue.

## License

Apache License 2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE) and
[project lineage](docs/LINEAGE.md).

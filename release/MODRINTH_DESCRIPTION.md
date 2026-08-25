# Apple Silicon rendering, built for real Minecraft worlds

Complemetal is an Apple Silicon rendering project for Minecraft Java 26.2. It
combines a native Metal 4 runtime, a hybrid terrain renderer, and an advanced
Iris shader-translation foundation while keeping unsupported work on the safe
Minecraft/Iris renderer.

The current stable release focuses on what matters in an actual modpack:
smooth play, predictable frame pacing, shader compatibility, safe reloads, and
no second renderer consuming resources behind Iris.

## Stable Iris and Complementary profile

With an Iris shader pack active, Complemetal `0.4.1` deliberately leaves the
visible frame on Iris/OpenGL. Its duplicate Metal world meshes, texture
mirrors, and entity/particle buffers stay unallocated. The experimental
GLSL -> SPIR-V -> MSL -> Metal graph remains in the project for further
qualification, but it is not automatically enabled merely because a Mac
supports Metal 4.

This boundary fixes the black-world failure, crash, and extreme slowdown found
in `0.4.0` without removing the project's Metal 4 foundation.

## Tested like a game, not just a benchmark

The exact release JAR passed an isolated production Minecraft 26.2/Fabric run
with Complementary Reimagined r5.8.1 Ultra and the complete compatibility set:

- Sodium and Iris;
- ImmediatelyFast;
- Entity Culling;
- Lithium and FerriteCore;
- Mod Menu, YACL, Zoomify, and Placeholder API;
- Fabric API and Fabric Language Kotlin.

The test covered entities, water, biome changes, flight and chunk streaming,
survival mining, Zoomify, live renderer reload, resource reload, Nether, End,
return to the Overworld, and a second world with night and rain. It also
captured and validated 14 screenshots against black output.

## Fullscreen result

In one matched M4 Pro run at physical 1920x1080 fullscreen and 200 Hz with
Complementary Ultra, the same JAR measured:

- **58.01 FPS enabled** versus **51.21 FPS disabled**;
- **36.74 versus 36.14 FPS 1% low**;
- no one-second blackout-class stall;
- zero GPU command-buffer errors, timeouts, or unsafe surface-slot skips;
- 445.5 MB versus 456.7 MB retained Java heap after two full GCs.

That +13.29% average is a controlled result for this machine and scene, not a
universal FPS guarantee. Stable Iris rendering remained on OpenGL, so it is
reported as a compatibility and non-regression result rather than a claim that
the shader pack was natively rendered through Metal.

## Requirements

- Minecraft Java Edition 26.2
- Apple Silicon Mac
- macOS 26+
- Java 25
- Fabric Loader 0.19.3+
- Fabric API 0.156.0+26.2
- Sodium 0.9.1
- Iris 1.11.2

Complementary Reimagined r5.8.1 Ultra is the qualified shader workload.

## Install

1. Install Fabric API, Sodium, and Iris for Minecraft 26.2.
2. Remove any older Complemetal or MetalRender JAR from `mods/`.
3. Put the current Complemetal JAR in `mods/`.
4. Select the shader pack and play.

Use `/complemetal status` to inspect the active runtime. Existing MetalRender
settings migrate automatically, and `/metalrender` plus `/mr` remain command
aliases.

## Source and documentation

- [GitHub repository](https://github.com/daniiarkg/complemetal-mc)
- [Technical architecture](https://github.com/daniiarkg/complemetal-mc/blob/main/docs/TECHNICAL_ARCHITECTURE.md)
- [Architecture graphs](https://github.com/daniiarkg/complemetal-mc/blob/main/docs/ARCHITECTURE_GRAPH.md)
- [0.4.1 release verification](https://github.com/daniiarkg/complemetal-mc/blob/main/docs/RELEASE_CHECKLIST_0.4.1.md)

## Origin and license

Complemetal is a continuation and fork of
[MetalRender by pebbles_boon / webblepebbles](https://github.com/webblepebbles/MetalRender).
The original renderer foundation and Complemetal modifications are distributed
under Apache License 2.0.

Complemetal is not affiliated with or endorsed by Apple, Mojang, Microsoft,
Iris, Sodium, or the Complementary shader-pack authors.

# Complemetal 0.4.0 for Minecraft Java 26.2

This is the first stable release under the **Complemetal** name. The rendering
algorithm remains the qualified Stage 9 Iris-to-Metal 4 path from MetalRender
0.3.1; this release establishes the new public identity and a compatible
migration path.

## Highlights

- New project name, Fabric id `complemetal`, JAR name, UI, resource namespace,
  commands and minimalist icon.
- New `/complemetal` and `/cm` commands.
- Legacy `metalrender` Fabric alias, `/metalrender` and `/mr` commands, Java/JNI
  ABI, native library name, cache formats and `metalrender.*` JVM properties
  remain compatible.
- Existing `config/metalrender.json` is automatically imported into
  `config/complemetal.json` without deleting the old file.
- Complete technical architecture, Mermaid render graphs, GitHub/Modrinth
  publication kit and explicit upstream attribution.
- Stage 9 keeps the full final-Iris-GLSL -> SPIR-V -> MSL -> Metal pipeline
  archive -> persistent Metal graph -> fenced IOSurface path.
- Strict fail-open behavior remains: unsupported state stays on Iris/OpenGL.

## Origin

Complemetal is a continuation and fork of
[MetalRender by pebbles_boon / webblepebbles](https://github.com/webblepebbles/MetalRender),
licensed under Apache-2.0. See `NOTICE` and `docs/LINEAGE.md` in the source.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3+
- Fabric API 0.156.0+26.2
- Java 25
- Apple Silicon, macOS 26+
- Sodium 0.9.1 and Iris 1.11.2

Qualified shader workload: Complementary Reimagined r5.8.1.

## Verification

The release JAR was rebuilt from source, checked for all 155 Java/JNI exports,
validated for its arm64 native payload and Metal library, and exercised by the
isolated exact-JAR Metal 4 and Metal 3 cold/warm matrix. Exact artifact hashes
are recorded in `docs/RELEASE_CHECKLIST_0.4.0.md` and the attached
`SHA256SUMS`.

## Performance boundary

The published Stage 9 A/B evidence remains 42.6–44.8% CPU p50, 27.1–37.4% CPU
p95, 10.5–19.5% GPU p95 and 11.5–32.5% GPU p99 frame-time improvement for the
exact M4 Pro 1280x720 Complementary scene. `0.4.0` is a rebrand/migration
release and does not claim a new universal FPS percentage.

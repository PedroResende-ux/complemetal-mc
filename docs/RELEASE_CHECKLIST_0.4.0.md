# Complemetal 0.4.0 release checklist

- Release version: `0.4.0+mc26.2`
- Git tag: `v0.4.0+mc26.2`
- Qualified shader pack: `ComplementaryReimagined_r5.8.1.zip`
- Decision: **PASS — eligible for GitHub and Modrinth publication**

## Release environment

| Component | Qualified value |
| --- | --- |
| Hardware | Apple M4 Pro, arm64 |
| macOS | 26.6, build `25G5065a` |
| Java | Temurin 25.0.3+9 LTS |
| Xcode | 26.6, build `17F113` |
| Metal compiler | Apple metal 32023.883 |
| Minecraft | Java Edition 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.156.0+26.2 |
| Sodium | 0.9.1 for Minecraft 26.2 |
| Iris | 1.11.2 for Minecraft 26.2 |

## Artifact identity

| Artifact | SHA-256 |
| --- | --- |
| `complemetal-0.4.0+mc26.2.jar` | `f058bb223d0ec4f9a96e26d14a9a1220df2fcb1dedf5d489bb2f6d4aed58e496` |
| `complemetal-0.4.0+mc26.2-sources.jar` | `730a4c1bac14608cc4f58598f40457eb192904f0fa0d8c137c3adb4b30833538` |
| packaged `libmetalrender.dylib` | `b22ebedffbb2ff97379d88236471b9f1bea8d988d02738badf09df05c93a23df` |
| packaged `shaders.metallib` | `96acbbeeaad63a4ef6d569367fb2875a01e6bf7a7977904d1582a31d21d183c8` |
| `assets/complemetal/icon.png` | `395f90af2e94f5b0db7632299ab5051ac04f2b7b425bbc90b9168620604c8ba8` |

The icon is a 256 x 256 RGBA PNG with alpha, 55,844 bytes.

## Clean build gate

Command:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew --no-daemon clean test check build releaseCheck
```

Result: **PASS**.

- 307 JUnit tests executed; 11 skipped; zero failures and zero errors.
- Six exact-JAR validator unit tests passed.
- Java/JNI parity passed for all 155 declared methods and native exports.
- The release dylib is a thin arm64 Mach-O, declares macOS 14.0 minimum
  compatibility, is ad-hoc signed, and has no sanitizer runtime dependency.
- `shaders.metallib` is a compiled Metal library with `MTLB` magic.
- Fabric metadata resolves to id `complemetal`, name `Complemetal`, version
  `0.4.0+mc26.2`, icon `assets/complemetal/icon.png`, and legacy provided id
  `metalrender`.
- The JAR contains `LICENSE`, `NOTICE`, upstream attribution, both mixin
  configurations, native payloads, and no obsolete debug dylib.

Build warnings are limited to the existing Java `Unsafe`/deprecated-API use,
one unused offline Metal helper, and Gradle deprecations. They did not produce
compile, link, test, package, or runtime failures.

## Exact packaged-JAR matrix

Every run loaded the exact JAR SHA above from an isolated production Fabric
profile. Each prepare manifest reported 91 classpath JARs and zero source-set
classpath entries.

| Backend | Cache | Result | Evidence SHA-256 |
| --- | --- | --- | --- |
| Metal 4 full graph | cold | PASS | `29079abb8f5880a950bd52d0e157c84781ee00a3268af63e02404d900c7c8f36` |
| Metal 4 full graph | warm | PASS | `c2d46073813fd0a8fc714b92da301907427989e68965af928a250bc9ebf0545f` |
| Forced Metal 3 fallback | cold | PASS | `827c69fc2f4ce0b17a491249cafd6c2d2933e26e849bd30658c40599bc705360` |
| Forced Metal 3 fallback | warm | PASS | `7a5797fc8bfa99a83591ec10a9662a8d1961497cfff0d5978910057253527b30` |

All four result files reported every harness check true, normal shutdown, no
crash report, no forbidden runtime diagnostics, and an unchanged release SHA
after execution.

## Metal 4 acceptance evidence

Cold cache:

- 231/231 programs translated; zero translation failures or rejections;
- 462/462 stages compiled and validated through the Apple Metal compiler;
- 90 pipeline variants compiled, archived, and made visible;
- three successful full-graph validation frames;
- ownership mode `METAL_FULL_GRAPH_OWNERSHIP`;
- 293 ownership frames presented and 14,650 paired OpenGL commands
  suppressed;
- zero ownership failures, GPU command-buffer errors, in-flight timeouts, or
  unavailable-IOSurface-slot skips.

Warm cache:

- 231/231 translation-cache hits and 462/462 cached stages;
- the compiled MSL artifact-set SHA remained
  `f08d5b70e39c1f1a65d1840c7f98f2b162149899aa69ba6b25f6425783b1dcfd`;
- 90/90 pipeline cache hits and zero new pipeline compilations;
- the pipeline-set SHA remained
  `f84caf7015bea5f472bc87c73bfc6a2a4d91c2572ea2d6fdf7221e7ac221ddf3`;
- 588 ownership frames presented and 29,400 paired OpenGL commands
  suppressed;
- zero ownership failures and zero native fault-counter deltas.

Both Metal 4 runs passed shader-pack on/off/on, Overworld -> Nether -> End ->
Overworld, exact visual parity, resize, fullscreen, windowed restore, and
surface suspend/restore. Lifecycle recovery produced 80/82 additional
ownership presentations and 4,000/4,100 additional suppressions, with zero
ownership failures.

## Metal 3 fallback evidence

Both cold and warm forced-Metal-3 runs reported:

- backend mode `METAL3`;
- ownership mode `OPENGL_VISIBLE_VALIDATION`;
- zero Metal graph submissions and presentations;
- zero paired OpenGL commands suppressed;
- zero ownership failures and zero native fault-counter deltas;
- all shader-toggle and dimension-route checks passed;
- 231 cold translations followed by 231 warm cache hits.

This verifies that unavailable or explicitly disabled Metal 4 does not steal
visible ownership from Iris/OpenGL.

## Documentation and publication gates

- English is the default documentation language.
- Russian architecture and graph translations are separate `_RU.md` files.
- All Mermaid blocks in both languages passed Mermaid CLI 11.16.0 parsing.
- `README.md`, `NOTICE`, and `docs/LINEAGE.md` identify Complemetal as a
  continuation and fork of MetalRender by pebbles_boon / webblepebbles.
- Public mod identity is Complemetal; internal Java/JNI/native/cache names
  remain deliberately compatible.
- GitHub release notes and Modrinth project/version metadata are prepared in
  `release/`.
- Modrinth dependencies resolve to Fabric API `P7dR8mSH`, Sodium `AANobbMI`,
  and Iris `YL57xq9U`.

## Explicitly unqualified claims

This release does not claim:

- support for geometry-shader packs, Intel Macs, Windows, Linux, or Minecraft
  versions other than 26.2;
- prevalidation of every Iris shader pack;
- direct `CAMetalLayer` scanout or VSync-synchronised native 200 Hz scanout;
- physical sleep/wake or external-display disconnect/reconnect qualification
  against this final SHA;
- a universal FPS improvement percentage.

Unsupported state remains on the fail-open Iris/OpenGL or Minecraft path.

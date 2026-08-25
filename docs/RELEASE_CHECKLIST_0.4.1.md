# Complemetal 0.4.1 stable release checklist

- Release version: `0.4.1+mc26.2`
- Git tag: `v0.4.1+mc26.2`
- Qualified shader pack: `ComplementaryReimagined_r5.8.1.zip`, Ultra
- Decision: **PASS — eligible for GitHub and Modrinth publication**

## Release boundary

The supported `0.4.1` Iris profile is deliberately fail-open:

- Iris/OpenGL owns every visible shader-pack frame;
- automatic Iris translation, draw interception, and full-graph ownership are
  disabled;
- the experimental Stage 9 pipeline remains available only through explicit
  development properties;
- Metal terrain meshes, texture mirrors, entity/particle buffers, and
  presentation resources are not initialized while Iris owns the frame;
- version, OS, architecture, or an available MTL4 object cannot by themselves
  authorize OpenGL suppression.

This is a corrective release boundary, not a rollback of the source-level
Metal 4 pipeline. It prevents the incomplete-graph/legacy-FINAL overlap that
produced black output and removes the render-thread capture storm seen in
`0.4.0`.

## Release environment

| Component | Qualified value |
| --- | --- |
| Hardware | Apple M4 Pro, 20-core GPU, arm64 |
| Display | VX24G10, 1920x1080, 200 Hz |
| macOS | 26.6, build `25G5065a` |
| Java | Temurin 25.0.3+9 LTS |
| Xcode | 26.6, build `17F113` |
| Metal compiler | Apple metal 32023.883 |
| Minecraft | Java Edition 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.156.0+26.2 |
| Sodium | 0.9.1+mc26.2 |
| Iris | 1.11.2+mc26.2 |

## Artifact identity

| Artifact | SHA-256 |
| --- | --- |
| `complemetal-0.4.1+mc26.2.jar` | `e291b3b80c702ee90fc5f42b1ef5062b3c17e2e0cf383e55e34c55929e62f554` |
| `complemetal-0.4.1+mc26.2-sources.jar` | `ac19ae5f253f321c78069e8fb3d26e0b96d7a5f5ca89ee12c90ba8064010edc5` |
| packaged `libmetalrender.dylib` | `b22ebedffbb2ff97379d88236471b9f1bea8d988d02738badf09df05c93a23df` |
| packaged `shaders.metallib` | `96acbbeeaad63a4ef6d569367fb2875a01e6bf7a7977904d1582a31d21d183c8` |
| `assets/complemetal/icon.png` | `395f90af2e94f5b0db7632299ab5051ac04f2b7b425bbc90b9168620604c8ba8` |

The native payload is a thin arm64 Mach-O, ad-hoc signed, declares macOS 14.0
minimum compatibility, and has no sanitizer dependency. The Metal library is
a compiled `MTLB` artifact. The icon is a 256 x 256, 8-bit RGBA PNG with alpha
and is 55,844 bytes.

## Build and static verification

Commands:

```bash
./gradlew --no-daemon test compileGametestJava checkJniParity \
  testExactJarQaValidators
./gradlew --no-daemon runClientGameTest
./gradlew --no-daemon buildReleaseNativePayload processResources \
  build verifyReleaseJar
```

Results:

- **PASS**, 308 JUnit tests, 11 skipped, zero failures and zero errors;
- **PASS**, all six exact-JAR validator unit tests;
- **PASS**, all 155 Java/JNI declarations and native exports match;
- **PASS**, client GameTest in 39 seconds, including live reload, world
  lifecycle, resources, entities, fluids, weather, fullscreen
  1920x1080@200 Hz, Nether, End, Overworld return, and reopening a world;
- **PASS**, exact release-JAR metadata, native payload, and compiled Metal
  shader library.

Warnings were limited to existing Java deprecated/Unsafe notices, an unused
offline Metal helper, and Gradle diagnostics. They caused no build, link,
package, or runtime failure.

## Production full-modpack field QA

Command:

```bash
python3 scripts/field_qa.py \
  --jar build/libs/complemetal-0.4.1+mc26.2.jar \
  --root build/field-qa/release-stability-final \
  --side both --minimum-refresh-hz 200 --timeout 720
```

The harness launched the official Minecraft 26.2/Fabric production runtime,
not a Gradle development source set. Each side used a fresh isolated game
directory and temporary worlds while loading the same exact JAR SHA above.

### Compatibility set

| Mod id | Version |
| --- | --- |
| `complemetal` | `0.4.1+mc26.2` |
| `entityculling` | `1.10.5` |
| `fabric-api` | `0.156.0+26.2` |
| `fabric-language-kotlin` | `1.13.13+kotlin.2.4.10` |
| `ferritecore` | `9.0.0` |
| `immediatelyfast` | `1.16.2+26.2` |
| `iris` | `1.11.2+mc26.2` |
| `lithium` | `0.25.2+mc26.2` |
| `modmenu` | `20.0.1` |
| `placeholder-api` | `3.1.0-beta.1+26.2` |
| `sodium` | `0.9.1+mc26.2` |
| `yet_another_config_lib_v3` | `3.9.5+26.2-fabric` |
| `zoomify` | `2.16.1+26.2` |

Shader pack SHA-256:
`3f1cd389e717b2e62f58edff222059b9c60de71b14bb49b517eb58318ce35b15`.

### Scenario coverage

The enabled side completed:

1. Overworld entities, a block entity, fluids, and mixed geometry;
2. a sustained 12-second black-frame regression window;
3. desert and snowy-plains biome transitions;
4. physical 1920x1080 fullscreen on the 200 Hz display;
5. a 30-second stationary frame sample;
6. the registered Zoomify key path;
7. spectator flight with repeated long-distance chunk streaming;
8. survival-mode mining through the real client interaction path;
9. live Complemetal renderer reload plus a 12-second post-reload window;
10. full resource-pack reload;
11. Overworld -> Nether -> End -> Overworld;
12. a second independent world at midnight in rain.

The enabled run lasted 106.91 seconds and the baseline lasted 96.17 seconds.
Both shut down normally, generated no crash report, contained none of the
forbidden renderer diagnostics, and passed every harness check.

### Visual and native-fault gates

- 14/14 enabled screenshots passed luminance, variance, and black-pixel gates;
- minimum mean luminance: `0.097367`;
- minimum luminance standard deviation: `0.103335`;
- maximum black-pixel fraction: `0.009869`;
- GPU command-buffer errors: `0`;
- in-flight frame timeouts: `0`;
- unsafe IOSurface-slot skips: `0`;
- blackout-class frame stalls of one second or more: `0`.

The enabled log contains the expected
`duplicate Metal world resources deferred` marker for every client world and
contains no mesher/entity/particle GPU initialization while Iris is active.

## Fullscreen A/B results

Both sides used Complementary Reimagined r5.8.1 Ultra, a 15-chunk render
distance, a 13-chunk simulation distance, VSync off, a 260 FPS cap, and the
same 1920x1080@200 Hz fullscreen display mode.

| Stationary metric | Enabled | Disabled | Delta |
| --- | ---: | ---: | ---: |
| Samples | 1,741 | 1,539 | — |
| Average FPS | 58.0147 | 51.2093 | +13.2893% |
| 1% low FPS | 36.7444 | 36.1387 | +1.6762% |
| p50 frame time | 17.861 ms | 19.450 ms | -8.17% |
| p95 frame time | 25.430 ms | 26.742 ms | -4.91% |
| p99 frame time | 27.215 ms | 27.671 ms | -1.65% |
| Maximum frame | 51.169 ms | 85.572 ms | — |
| Frames >=100 ms | 0 | 0 | — |

Flight/chunk-streaming measured 76.54 FPS enabled and 61.10 FPS disabled, with
1% lows of 38.88 and 37.40 FPS. It is supporting scenario evidence, not a
separate release claim.

After two explicit full-GC settle cycles at the end of each run, retained heap
was 445,508,136 bytes enabled and 456,724,544 bytes disabled, a delta of
-11,216,408 bytes. This closes the apparent approximately 1 GB difference in
the earlier unsynchronised memory snapshots.

The average-FPS result is one controlled pair. Because the stable Iris frame
remained on OpenGL, it must not be advertised as proof of Iris Metal
acceleration or as a universal uplift. It establishes that `0.4.1` removed the
catastrophic enabled-side regression for the qualified setup.

## Evidence identity

| Evidence | SHA-256 |
| --- | --- |
| Enabled prepare manifest | `24c7d9f349a76f510a38dbac6e49b6912a2b1bad9e015f2dcc04acdcd915588e` |
| Baseline prepare manifest | `19cc1ccd4d5e8623d1b3c819025685b76842e4d1a74cf38130888b27beb23e26` |
| Enabled result | `900642abffffa292a0694592f181f571e961968f3f9132f95bbab64fd3c678fd` |
| Baseline result | `023cd3da703ec0edff40d6a743c48c8efaa4ee14c233d59fbbdd0948d0f94cdc` |
| A/B comparison | `df1646335c587a01b0cba01a9902d960aa8935bf13d488e5d125afa1d2f4f83e` |
| Enabled stdout | `88cc293984497a0a6c822c4f7e13942d760833faf3765658846b50fed7adb364` |
| Baseline stdout | `4e7f745ebec8778275e5b48959cf45b05b3d4475a6a5c9ebf6d9ac271e0e4e5d` |

## Explicitly unqualified claims

This release does not claim:

- production Iris-to-Metal visible graph ownership;
- a universal FPS increase or a guaranteed +13.29%;
- support for geometry-shader packs or every Iris pack;
- Intel Mac, Windows, Linux, or Minecraft versions other than 26.2;
- direct `CAMetalLayer` scanout;
- VSync-synchronised native 200 Hz scanout;
- physical sleep/wake or cable disconnect/reconnect qualification;
- production entity/particle replacement, mesh shaders, Hi-Z, MetalFX, or
  programmable blending.

Unsupported work remains on Iris/OpenGL or Minecraft rendering. It is never
acceptable for a fallback decision to produce a black frame or crash.

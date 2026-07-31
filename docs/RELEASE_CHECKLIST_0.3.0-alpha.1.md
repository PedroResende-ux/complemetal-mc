# `0.3.0-alpha.1+mc26.2` Stage 2 checklist

This is a development milestone for Minecraft Java Edition 26.2, not the
stable install. Stable `0.2.1+mc26.2` remains unchanged. The alpha closes only
Stage 2 of the ordered Iris-to-Metal roadmap: Apple Metal compiler validation
of generated MSL.

## Implemented boundary

- [x] Capture Iris' final linked GLSL without persisting original source or
      program names.
- [x] Translate in process through shaderc to SPIR-V and SPIRV-Cross to MSL.
- [x] Emit Metal argument-buffer declarations at tier 2 so the tested real
      shader stages stay within Metal's direct buffer-index limit.
- [x] Revalidate cache marker, manifest, profile, relative path, size, SHA-256,
      strict UTF-8 and file type before JNI.
- [x] Limit library-validation input to 16 MiB per stage before Java/native
      copies; retain the wider 64 MiB disk-artifact limit separately.
- [x] Compile an ephemeral executable MSL 3.0 `MTLLibrary`, resolve `main0`,
      verify the expected function type and release function/library
      synchronously.
- [x] Keep `DEFERRED`, `UNSUPPORTED` and `FAILED` distinct across startup,
      restart and teardown; transient deferred calls do not change terminal
      compiler counters.
- [x] Bound the compiled-artifact identity set to 3072 entries and make an
      incomplete digest or unavailable live-library gauge fail the completion
      gate closed.
- [x] Keep `pipeline.status=pending`. No Iris Metal pipeline, archive, command
      encoder or draw is part of this milestone.

## Build and native checks

- [x] `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew
      --no-daemon clean test check build releaseCheck`
- [x] Unit suite: 63 tests, zero failures/errors; three explicitly opt-in
      cases were skipped in the normal suite and the translation smoke was
      run separately with its opt-in enabled.
- [x] JNI source/export parity: 113 methods.
- [x] Packaged arm64 dylib and compiled `shaders.metallib` verified.
- [x] Opt-in in-process translation smoke passed, including 40 plain uniforms
      emitted through an argument buffer instead of direct buffer slots above
      Metal's limit.
- [x] Packaged M4 Pro smoke passed pre-init deferred telemetry, post-init valid
      vertex/fragment compile, invalid-source failure, geometry unsupported,
      release to live-library count zero and post-destroy readiness reset.
- [x] Native smoke terminal delta: 4 attempts, 2 successes, 1 unsupported and
      1 expected failure; GPU/IOSurface fault delta remained zero.

## Exact-JAR Iris matrix

All four runs used the same exact alpha JAR, Fabric Loader 0.19.3, Iris
1.11.2, Sodium 0.9.1 and Complementary Reimagined r5.8.1 in isolated game,
home, native and translation-cache directories.

| Profile | Cache | Translation | Apple compiler validation | Result |
| --- | --- | --- | --- | --- |
| Metal 4 hybrid | Cold | 76 translated, 0 hits | 76 programs / 152 stages | PASS, 16/16 checks |
| Metal 4 hybrid | Warm | 0 translated, 76 hits | 76 programs / 152 stages | PASS, 16/16 checks |
| Forced Metal 3 | Cold | 76 translated, 0 hits | 76 programs / 152 stages | PASS, 16/16 checks |
| Forced Metal 3 | Warm | 0 translated, 76 hits | 76 programs / 152 stages | PASS, 16/16 checks |

Every run reported zero unsupported/failed/rejected/pending/in-flight stages,
zero native compiler failures, zero retained libraries and zero native fault
deltas. The independently reconstructed cache identity matched the coordinator
digest in every run:

`ac5c0c80ffc5a511ca6aeba61d95b16c1d6839b53784af943d61bab717443097`

Warm cache skips shaderc and SPIRV-Cross only. It deliberately recompiles all
152 ephemeral Metal libraries because Stage 2 persists no compiled library or
pipeline artifact.

## Evidence hashes

Metal 4 hybrid:

- Prepare manifest:
  `5d3eec388189d4caacc5ca01be56cab7e607574d13a104e33631c37fd2c29bdb`
- Cold result:
  `c6c9169e6b492480aa5293cb0f65c5e9f4d93675998e9e44c48cfc8604056f2a`
- Warm result:
  `fd00f3f7a36e6a38e299e1554e5f25426ad7c6c5de3b2d7b43838bba44e93c02`

Forced Metal 3:

- Prepare manifest:
  `f31c8d7886184005c8cf1877a982181a7a32046721c08f638617982c6db471b4`
- Cold result:
  `cc3ea09db1aae87f27f4e022cf2ed0e8f3cbcae89805091ab30404449a107f02`
- Warm result:
  `d0557ecec13aa21d62eda8291ff0dd8ce1ea0bbc88e883dbc92f5c4ea6b658c8`

The evidence JSON separates indirect runtime Iris/OpenGL ownership checks from
the explicitly static validation-only execution boundary. It does not present
literal pipeline/draw values as runtime telemetry.

## Verified artifacts

- JAR: `build/libs/metalrender-0.3.0-alpha.1+mc26.2.jar`
- JAR SHA-256:
  `5ae507edc0fb80c1a4b51643ca4bcb17d2b04e3b6e5dee94244eeca29ae6c240`
- Native dylib SHA-256:
  `16cdc5eafc8db72642fdc75346b8e9c81820173e22329e6dbc69670861b18eac`
- Offline Metal shader library SHA-256:
  `96acbbeeaad63a4ef6d569367fb2875a01e6bf7a7977904d1582a31d21d183c8`

## Exit and next gate

Stage 2 is complete for the exact tested workload. This does not mean Iris is
rendering through Metal and it makes no FPS claim. Stage 3 must capture and
content-key the complete Iris pipeline state while Iris/OpenGL remains the
visible renderer.

# `0.2.1+mc26.2` release checklist

`0.2.1+mc26.2` is the current stable maintenance release for Minecraft Java
Edition 26.2. Its supported renderer remains the conservative hybrid profile:
selected terrain draws use the Metal 3 compatibility stream, the Metal 4
runtime probe is optional, and actual MTL4 draw encoding remains disabled.

The complete `0.2.0+mc26.2` hardware and native validation record remains in
[`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md). This checklist records the
patch delta and the checks repeated against the exact `0.2.1` artifact.

## Patch scope

- [x] Replace the random F3 death-message easter egg with deterministic status
      for active Metal terrain, Iris/OpenGL pause, configuration disable,
      initialization, no-world readiness and vanilla fallback.
- [x] Bound the initialization failure detail shown in F3 so a safe fallback
      is distinguishable from a crash without flooding the overlay.
- [x] Package a valid square mod icon and reference it from `fabric.mod.json`.
- [x] Require both live Iris compatibility state and the renderer's applied
      Iris-pause latch to be clear before a Metal world frame can be encoded.
- [x] Keep `metalActive()` independent of the Iris-pause latch so the normal
      client tick can release that latch and request the terrain rebuild when
      shaders are disabled.
- [x] Leave the native renderer, offline Metal library and stable support
      boundary unchanged.

## Corrective validation history

- [x] The first pre-fix cold exact-JAR run was treated as a release failure,
      not waived as harmless variance. Its transition log showed that Iris'
      live API could report shaders disabled before the renderer's applied
      pause latch had been released, allowing a Metal frame to begin before
      the rebuild request.
- [x] A small truth-table gate was added for
      `rendererActive && !liveIrisPause && !appliedIrisPause`.
- [x] The helper was initially placed under the declared mixin package. The
      active client GameTest caught the invalid runtime placement; the helper
      and its test were moved to the regular `render` package before the final
      artifact was built.
- [x] Unit coverage now rejects inactive rendering, live Iris compatibility,
      an applied Iris latch, and both Iris signals together, while permitting
      normal startup with an active renderer and neither pause signal.
- [x] After those corrections, the final exact-JAR cold and warm runs both
      passed without forbidden runtime diagnostics, crashes or native fault
      counter increments.

## Final build and active-path checks

- [x] `./gradlew --no-daemon test check build releaseCheck`
- [x] Unit suite: 46 tests, zero failures and zero errors; two opt-in native
      translation smoke cases were skipped by their explicit assumptions.
- [x] JNI source/export parity and packaged release-JAR validation passed.
- [x] Packaged dylib is arm64, ad-hoc signed and declares macOS 14.0 as its
      minimum deployment target.
- [x] The compiled `shaders.metallib` is packaged in the final JAR.
- [x] Active `runClientGameTest` passed after the helper-package correction,
      including resize, fullscreen, resource reload, bulk-update recovery and
      Overworld, Nether and End transitions.
- [x] `nIsMetal4DrawPathActive()` remained false; the active draw stream is
      still the Metal 3 compatibility path.

## Final exact-JAR Iris checks

All runs used the final JAR identified below, isolated runtimes and isolated
Iris translation caches with Complementary Reimagined r5.8.1.

- [x] Cold cache: shader on/off/on completed, all 76 captured programs produced
      152 SPIR-V and 152 MSL stage artifacts, with zero translation failures.
- [x] Warm cache: all 76 entries were cache hits with zero retranslations and
      zero failures.
- [x] The primary Metal 4 cold/warm pair reported
      `METAL4_RUNTIME_VERIFIED_METAL3_RENDER`, kept Iris rendering on OpenGL
      and reported `generatedMslExecuted=false`.
- [x] That pair completed 396 Metal frames while shaders were disabled and 391
      successful presentations per run; GPU command-buffer errors, in-flight
      timeouts and IOSurface-slot skips all remained zero.
- [x] A second independent Metal 4 cold run passed with 76 translations, 152
      SPIR-V stages, 152 MSL stages, 399 Metal frames, 394 successful
      presentations and zero forbidden diagnostics or native fault deltas.
- [x] A forced Metal 3 cold/warm pair also passed: cold translated all 76
      programs, warm reused all 76 entries, and both runs retained zero
      forbidden diagnostics and zero native fault deltas.
- [x] Shader-toggle screenshots passed the harness' non-uniformity and coarse
      on/off/on similarity checks. These are workload-specific checks, not
      proof of arbitrary shader-pack parity.

Final evidence:

- `build/exact-jar-qa-0.2.1-final-metal4/prepare-manifest.json`
  SHA-256: `05ca41d6af8b0e6036d0ab559c6c128e7313d581525061242efd148837fe0ceb`
- Cold result SHA-256:
  `d8529f50c423c68b619c5d9ab5fa07eb15ca2b38c46286cb5743a1dc96302a5f`
- Warm result SHA-256:
  `9df0e424c395dfb0d91aa2910f7b7dd7bf9893a3de2f0ba704f642dacbe71ef0`
- Independent Metal 4 cold result SHA-256:
  `681ebbe1e817bd3c384020e2bfd561c5ba0d57716c4e677121c5625905a71789`
- Forced Metal 3 cold result SHA-256:
  `c8772314dad83225b98d2afe6cfda314523ff1524ad7a0b386a0d8c56deb5ec1`
- Forced Metal 3 warm result SHA-256:
  `7b17e2247bc2e0bd7aa2cb137169a38f5c6103333a1c739beda777e1121fe883`

## Verified final artifacts

- JAR: `build/libs/metalrender-0.2.1+mc26.2.jar`
- JAR SHA-256:
  `4def2a1d1498b5ce1d5d4f1672d4b4107cfa5cd34a6a7b85cafa4fe553fb3a97`
- Native dylib SHA-256:
  `3b9e2e0febe2309dc4f308f599b9253378039492ad5e4b7f4629216845b61428`
- Metal shader library SHA-256:
  `96acbbeeaad63a4ef6d569367fb2875a01e6bf7a7977904d1582a31d21d183c8`

The dylib and Metal library hashes exactly match `0.2.0+mc26.2`; this patch did
not change either payload. The JAR hash changed because the Java status/gating
fixes, version metadata and icon are new.

## Stable boundary and exclusions

- Iris shader-pack draws still execute through Iris/OpenGL. Generated MSL is
  not compiled into or executed by a Metal render graph, so this patch makes
  no Iris FPS claim.
- MTL4 render-pipeline and draw encoding remain inactive.
- True 2x Retina backing, sleep/wake, external-display hot-plug/reconnect,
  MetalFX hardware conformance and real presented 200 Hz pacing remain outside
  validated stable scope.
- Forced Metal 3 was repeated against the final `0.2.1` JAR. Native-disabled
  and ASan evidence remains historical in the `0.2.0` checklist and was not
  relabelled as a fresh patch run; the unchanged native payload hashes are
  recorded above.

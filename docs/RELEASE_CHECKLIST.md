# `0.2.0+mc26.2` release checklist

Stable refers to the conservative hybrid profile: selected terrain draws use
the Metal 3 compatibility stream, the Metal 4 runtime probe is optional, and
actual MTL4 draw encoding remains disabled.

## Final artifact gates

- [x] `./gradlew clean test check build`
- [x] `./gradlew checkJniParity`
- [x] `./gradlew compileGametestJava`
- [x] Packaged dylib is arm64, ad-hoc signed and has deployment target 14.0
- [x] `scripts/smoke_release_payload.sh build/libs/metalrender-0.2.0+mc26.2.jar`
- [x] `git diff --check`

## Full-Xcode build

- [x] If required, install the compiler with
      `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -downloadComponent MetalToolchain`
- [x] `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun --sdk macosx metal --version` runs
- [x] `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun --sdk macosx --find metallib` succeeds
- [x] `./gradlew releaseCheck` rebuilds the native library and offline shaders
- [x] Every shader compiles
- [x] `shaders.metallib` is packaged
- [x] Packaged dylib JNI exports match all 106 `NativeBridge` declarations

## Stable-profile hardware validation

- [x] Fresh Fabric 26.2 profile starts the exact stable JAR with Java 25
- [x] Stable exact-JAR cold cache translates the exact tested 76 captured
      Complementary programs into 152 SPIR-V and 152 MSL stage artifacts with
      zero failures
- [x] Stable exact-JAR warm cache reuses all 76 entries with zero retranslations
- [x] Active `runClientGameTest` completes with the release-candidate native
      library
- [x] Active Metal validation covers Overworld, Nether and End transitions
      after the chunk face-bucket correctness fix
- [x] ASan-instrumented native payload smoke completes with no sanitizer report
- [x] Native-disabled client baseline completes with
      `-Dmetalrender.enabled=false -Dmetalrender.gametest.baseline=true`
- [x] Screenshots are visually inspected; non-empty files alone are not parity
- [x] Vanilla backend fallback is clean
- [x] Metal 3 compatibility mode starts and shuts down cleanly
- [x] Metal 4 hybrid mode reports the correct backend name
- [x] `nIsMetal4DrawPathActive()` remains false
- [x] Native entity/particle replacement remains release-locked
- [x] Terrain and chunk rebuild, including End terrain
- [x] Entities and block entities
- [x] Particles and weather
- [x] Fluids and translucent geometry
- [x] HUD, screens and screenshot capture
- [x] Window resize
- [x] Fullscreen transition in both Metal 4 hybrid and forced Metal 3 profiles
- [x] Resource-pack reload
- [x] Overworld, Nether and End world/dimension transitions
- [x] Native arena startup budget is capped against
      `recommendedMaxWorkingSetSize`
- [x] High-rate native buffer ownership and slot recycling smoke test
- [x] Bulk block-update recovery performs at most one clean rebuild per
      recovery episode
- [x] Stale section workers cannot release or publish over a replacement
      worker's pending-build claim

## Explicitly outside the stable support scope

These are not passed release gates and must not be presented as validated:

- True 2x Retina framebuffer backing. The final validated GameTest reported a
  1x game framebuffer and content scale, so a 2x backing was not exercised.
- macOS sleep/wake.
- External-display hot-plug or reconnect.
- Real presented 200 Hz frame pacing. The active external display reported
  200 Hz, but the final GameTest native target was capped at 120 FPS and did
  not measure presentation cadence. The native high-rate loop is an offscreen
  throughput and slot-recycling test, not display presentation evidence.
- MetalFX hardware conformance.
- MTL4 render-pipeline or draw encoding.
- Execution of generated Iris MSL through Metal.
- Process-wide ASan Minecraft execution. The closed Apple OpenGL shader linker
  faults nondeterministically under sanitizer injection; the native payload is
  tested separately with ASan.

## Publication

- [x] Version is no longer marked beta or dev
- [x] Release notes call this a stable safe hybrid, not a complete MTL4 renderer
- [x] Iris documentation states that rendering remains OpenGL and that the
      opt-in translation cache makes no FPS claim
- [x] Unsupported Retina, sleep/wake, display reconnect and real 200 Hz
      presentation are explicitly documented
- [x] Changelog matches the final artifact
- [x] Final SHA-256 values are recorded

## Verified final artifacts

All values below come from the final `0.2.0+mc26.2` build. The exact-JAR
profiles used separate runtime and cache directories.

- JAR SHA-256:
  `96f87dc22e9628c38c9a97a12ecbb351ec5469aaacfef87f67e5072e295af0de`
- Native dylib SHA-256:
  `3b9e2e0febe2309dc4f308f599b9253378039492ad5e4b7f4629216845b61428`
- Metal shader library SHA-256:
  `96acbbeeaad63a4ef6d569367fb2875a01e6bf7a7977904d1582a31d21d183c8`
- Metal 4 hybrid exact-JAR prepare manifest SHA-256:
  `0803808cac189cb00dd798c7ba4b0f54b4d35dfb81bb7186397d6d564fcc7e62`
- Metal 4 hybrid exact-JAR cold evidence SHA-256:
  `1f4d4eac99448ae7f4bdb9296bc6d82d045ef9ce323c26bd7684f1bac445cdce`
- Metal 4 hybrid exact-JAR warm evidence SHA-256:
  `bb676da546f77d263646a47e7609e01c83317b14edc0453d5512d5f1a9c731b9`
- Metal 3 exact-JAR prepare manifest SHA-256:
  `215199e7bdfadaf86501620778da3b3dce2bc798b5312faf0fd3f13652b847b9`
- Metal 3 exact-JAR cold evidence SHA-256:
  `d87d9fc314c4c83972c93fcb5222e8f83518aa0aadee3cf350c90b0d9a9b1a48`
- Metal 3 exact-JAR warm evidence SHA-256:
  `25a2ba1ee219630a023921d39b815d9c711814747bfcbad0fde5c7f7cc4d7512`
- Hardware: MacBook Pro `Mac16,8`, Apple M4 Pro (14-core CPU, 20-core GPU),
  24 GB RAM, with an external VX24G10 at 1920 x 1080 / 200 Hz during the final
  validation; Metal 4 support was reported by the GPU runtime.
- Operating system: macOS 26.6, build `25G5065a`.
- Gradle/GameTest Java: Eclipse Temurin 25.0.3+9, arm64.
- Exact-JAR Java: official launcher Microsoft OpenJDK 25.0.1+8 LTS, arm64.
- Active GameTest results: two consecutive Metal 4 hybrid passes in 36 s and
  37 s, forced Metal 3 in 38 s, and native-disabled vanilla baseline in 30 s.
  The final lava fixture occupied 10.831% of the fixed ROI versus the 9%
  minimum.
- Final archives: `build/stable-qa/final-metal4`,
  `build/stable-qa/final-metal4-variance`,
  `build/stable-qa/final-metal3`, `build/stable-qa/final-vanilla`,
  `build/stable-qa/native-payload`,
  `build/stable-qa/asan-native-payload`,
  `build/exact-jar-qa-stable-metal4`, and
  `build/exact-jar-qa-stable-metal3`.

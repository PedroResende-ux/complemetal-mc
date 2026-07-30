# `0.2.0-beta.2+mc26.2` release checklist

## Automated

- [x] `./gradlew clean test check build`
- [x] `./gradlew checkJniParity`
- [x] `./gradlew compileGametestJava`
- [x] Packaged dylib is arm64, ad-hoc signed and has deployment target 14.0
- [x] `scripts/smoke_release_payload.sh build/libs/metalrender-0.2.0-beta.2+mc26.2.jar`
- [x] `git diff --check`

## Full-Xcode build

- [x] If required, install the compiler with
      `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -downloadComponent MetalToolchain`
- [x] `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun --sdk macosx metal --version` runs
- [x] `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun --sdk macosx --find metallib` succeeds
- [x] `./gradlew releaseCheck` rebuilds the native library and offline shaders
- [x] Every shader compiles
- [x] `shaders.metallib` is packaged
- [x] Packaged dylib JNI exports match all 103 `NativeBridge` declarations

## In-game hardware validation

- [x] Fresh Fabric 26.2 profile starts the exact release JAR with Java 25
- [x] Exact-JAR cold cache translates all 76 captured Complementary programs
      into 152 SPIR-V and 152 MSL stage artifacts with zero failures
- [x] Exact-JAR warm cache reuses all 76 entries with zero retranslations
- [x] Active `runClientGameTest` completes with the release native library
- [x] ASan-instrumented active client test completes with no sanitizer report
- [x] Native-disabled client baseline completes with
      `-Dmetalrender.enabled=false -Dmetalrender.gametest.baseline=true`
- [x] Screenshots are visually inspected; non-empty files alone are not parity
- [x] Vanilla backend fallback is clean
- [ ] Metal 3 compatibility mode starts and shuts down cleanly
- [x] Metal 4 hybrid mode reports the correct backend name
- [x] `nIsMetal4DrawPathActive()` remains false
- [x] Native entity/particle replacement remains release-locked
- [x] Terrain and chunk rebuild
- [ ] Entities and block entities
- [ ] Particles and weather
- [ ] Fluids and translucent geometry
- [x] HUD, screens and screenshot capture
- [ ] Resize, fullscreen and Retina scale
- [ ] Resource-pack reload
- [ ] World/dimension transitions
- [ ] Sleep/wake and display reconnect
- [ ] Sustained 200 Hz pacing
- [x] Native arena startup budget is capped against
      `recommendedMaxWorkingSetSize`

## Publication

- [x] Version is no longer marked `dev`
- [x] Changelog matches the artifact
- [x] SHA-256 recorded
- [x] Release notes call this a beta hybrid, not a complete MTL4 renderer
- [x] Known limitations include the inactive MTL4 draw path, release-locked
      entity/particle replacement and remaining visual-conformance work

## Verified artifacts

- JAR SHA-256:
  `6c16501263c5feacca048db170036ac66081a42af4c9900762e54fc81b266ebf`
- Native dylib SHA-256:
  `ff563ba1f36587b43b2b87676f2879ddca20fd907efacc961a2f7b5e135b5edb`
- Metal shader library SHA-256:
  `96acbbeeaad63a4ef6d569367fb2875a01e6bf7a7977904d1582a31d21d183c8`
- Exact-JAR prepare manifest SHA-256:
  `bc3858da253637c3cbace8de6420d9edfe811e21fa063bd88fc728c20eee06fd`
- Exact-JAR cold evidence SHA-256:
  `99851d1914ed656cc2eb28e2e68a50be4b563f0b8bccd992daad9e4e034e2dc8`
- Exact-JAR warm evidence SHA-256:
  `b80c2745ec2de6b7d15eb57bca726cf42187e4edefa680e638228464a58d8fa1`
- Cold and warm Iris on/off/on screenshots were visually inspected at
  1280x720 on Apple M4 Pro with Java 25.

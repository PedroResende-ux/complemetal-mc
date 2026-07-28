# `0.2.0-beta.1+mc26.2` release checklist

## Automated

- [x] `./gradlew clean test check build`
- [x] `./gradlew checkJniParity`
- [x] `./gradlew compileGametestJava`
- [x] Packaged dylib is arm64, ad-hoc signed and has deployment target 14.0
- [x] `scripts/smoke_release_payload.sh build/libs/metalrender-0.2.0-beta.1+mc26.2.jar`
- [x] `git diff --check`

## Full-Xcode build

- [x] If required, install the compiler with
      `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -downloadComponent MetalToolchain`
- [x] `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun --sdk macosx metal --version` runs
- [x] `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun --sdk macosx --find metallib` succeeds
- [x] `./gradlew releaseCheck` rebuilds the native library and offline shaders
- [x] Every shader compiles
- [x] `shaders.metallib` is packaged
- [x] Packaged dylib JNI exports match all 102 `NativeBridge` declarations

## In-game hardware validation

- [ ] Fresh Fabric 26.2 profile starts the exact release JAR with Java 25
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
- [ ] Memory-pressure fallback

## Publication

- [x] Version is no longer marked `dev`
- [x] Changelog matches the artifact
- [x] SHA-256 recorded
- [x] Release notes call this a beta hybrid, not a complete MTL4 renderer
- [x] Known limitations include the inactive MTL4 draw path, release-locked
      entity/particle replacement and remaining visual-conformance work

## Verified artifacts

- JAR SHA-256:
  `85d8e609d16877661347927aedf0bb4a82672730e8f5e2fe75d89cbd82b864ae`
- Native dylib SHA-256:
  `e14df09a80d8b4130eb05f1b37d2f0e4ad6ec208ef6efff66de52d8c1b318f41`
- Metal shader library SHA-256:
  `96acbbeeaad63a4ef6d569367fb2875a01e6bf7a7977904d1582a31d21d183c8`
- Active hybrid and native-disabled baseline screenshots were visually
  inspected on Apple M4 Pro with the Microsoft Java 25 runtime bundled by the
  Minecraft Launcher.

# Display lifecycle and 200 Hz QA

Status: **development candidate PASS** on 2026-08-25 (Asia/Bishkek).

This document records post-`0.3.0` work against the remaining display-facing
limitations. It does not rewrite the published `0.3.0+mc26.2` support
contract.

## Exact candidate

- JAR: `build/libs/metalrender-0.3.0+mc26.2.jar`
- JAR SHA-256:
  `5c62d7a62c931accafafd62a0438b57d6ee9b10ab7208183645fe20ff05e5b6e`
- Branch: `develop/0.3.1-display-lifecycle`
- Platform: MacBook Pro `Mac16,8`, Apple M4 Pro, 24 GB RAM, arm64
- OS: macOS 26.6, build `25G5065a`
- Active GLFW displays: built-in `Liquid Retina XDR` at 2x backing and
  external `VX24G10` at 200 Hz
- Shader workload: Complementary Reimagined r5.8.1 through Iris 1.11.2
- Full build: `clean test check build releaseCheck` — PASS
- Java/JNI parity: 152 declarations/exports — PASS

## Delivered lifecycle behavior

The client now polls a bounded display state at client-tick frequency. A
topology, active-monitor, window recreation, backing/framebuffer,
visibility/iconification or likely wake transition creates an explicit safe
boundary. Refresh/topology changes also refresh the runtime frame target.

The reset is deliberately presentation-only. It waits for outstanding GL
reads at this rare boundary, releases GL fences/textures and recycles their
IOSurfaces, but preserves:

- Iris GLSL/SPIR-V/MSL artifacts and device-qualified pipeline archives;
- in-flight Metal graph presentation tokens;
- persistent graph attachments and history;
- resident texture and vertex/index inputs.

OpenGL suppression remains off until a new completed IOSurface has been
promoted and bound. This fixes the earlier lifecycle bug where a resize could
destroy an in-flight token and produce `graph-presentation-native-failed-1`.

The strict Retina run exposed a second lifecycle gap. After a display move,
GLFW directly reported a 1920x1080 backing for the 960x540 game window, but
Minecraft's cached framebuffer remained 960x540 because its backing-size
callback had been missed. Before evaluating the display transition, the client
now compares the direct GLFW framebuffer dimensions to the cached Minecraft
dimensions. A mismatch is copied into the window and passed through
Minecraft's normal framebuffer-resize handler, which also reconfigures the
Metal presentation surface. The exact-JAR probe independently records both
direct and cached dimensions after the migration.

The wake detector uses wall time and treats a client-tick gap of at least five
seconds as a resume boundary, so time spent in macOS sleep is visible even if
a platform monotonic clock pauses. Forward clock corrections conservatively
create the same safe reset; backward corrections do not. This behavior is unit
tested, while physical sleep was not forced during this run.

## Cold/warm exact-JAR result

The cadence tracker wraps the actual Minecraft `GlSurface.present()` call. It
does not infer presentation from renderer FPS or an offscreen Metal loop.

| Gate | Cold | Warm |
| --- | ---: | ---: |
| Overall exact-JAR result | PASS | PASS |
| Connected GLFW displays | 2 | 2 |
| Two-display migration | PASS | PASS |
| Retina framebuffer backing | 2.0x / 2.0x | 2.0x / 2.0x |
| Reported display refresh | 200 Hz | 200 Hz |
| Completed intervals | 600 | 600 |
| Measured present-call cadence | 181.524564 Hz | 198.644953 Hz |
| Interval p50 | 5.080541 ms | 5.003375 ms |
| Interval p95 | 8.492667 ms | 6.137917 ms |
| Interval p99 | 11.732083 ms | 6.807667 ms |
| Present-call duration p50 | 0.864666 ms | 0.498084 ms |
| >=100 ms stalls | 0 | 0 |
| Metal-owned presentations during capture | 609 | 605 |
| Display transitions / resets | 3 / 3 | 3 / 3 |
| Ownership failures | 0 | 0 |

The normal Stage 9 lifecycle subtest also passed resize, fullscreen, windowed
restore and surface hide/show. It recorded 98/89 additional Metal
presentations, 4,930/4,480 suppressed OpenGL commands, three expected
graph-frame invalidations per run and zero failures.

Evidence SHA-256:

| Evidence | SHA-256 |
| --- | --- |
| Cold run result | `b884a6fde916a0c60f68adf982c0d4dd483eeae99b1d4eec323ec04a71933587` |
| Warm run result | `5a873b9fc208412e000d707a256c89061ae3a9643a6d58cd94ea488a4c1f31c5` |
| Cold hardware sidecar | `2551a45d960180bd0cda518595b0f811d445adcac19f1c542806715f4c863ec9` |
| Warm hardware sidecar | `6acc65cf5dc22089577a29bd6f0315245cb744481e94e87155d8aa58f4be1090` |
| Cold lifecycle sidecar | `e343dc6c2e6fc2656d581ba7f96084315efc4f3770316e90e5108cdf6c71cbc7` |
| Warm lifecycle sidecar | `4e40d6eedd6e29bd8fc784b927c416c5540af63006c265bb00f8343e3ebfa0c2` |

## What the 200 Hz result means

The passing profile disables VSync and applies an exact 200 FPS software cap.
It proves that Minecraft executes the real window-present call above the
strict 160 Hz acceptance floor while the Metal graph owns the measured frames.
The cold run reached 181.52 Hz after the first complete two-display/Retina
migration, while the warm repeat reached 198.64 Hz. This remains a finite
controlled sample rather than a guaranteed 200 FPS floor. It also does **not**
prove that Core Animation or the monitor scanned out 600 distinct frames in a
VSync-synchronised native Metal presentation path. The final image still
crosses the fenced IOSurface-to-OpenGL bridge, so native synchronized 200 Hz
remains a separate future cutover boundary.

## Remaining hardware gates

| Gate | Current state | How it closes |
| --- | --- | --- |
| True 2x Retina framebuffer | **PASS** cold and warm | Strict exact-JAR probe recorded matching cached/direct 2.0x framebuffer scales |
| Two-display migration | **PASS** cold and warm | Strict run visited both displays and recorded three matched transitions/resets per run |
| Physical display disconnect/reconnect | Not run | Disconnect and reconnect the external display during a controlled exact-JAR session, then require ownership recovery with zero faults |
| Physical sleep/wake | Resume-gap recovery implemented and unit tested | Sleep and wake the Mac during a controlled exact-JAR session, then require ownership recovery with zero faults |
| Native VSync-synchronised 200 Hz | Not claimed | Replace/augment the final CGL/GLFW swap boundary with a native display-linked Metal presentation path and measure compositor/display feedback |

The lid-open configuration closed the Retina-backing and live migration gates.
It did not exercise a physical cable hot-plug or a real system sleep/wake, so
those remain separate hardware actions rather than inferred passes.

## Reproduce the strict Retina/migration/200 Hz gate

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew --no-daemon clean test check build releaseCheck

python3 scripts/exact_jar_qa.py run \
  --runtime-dir build/exact-jar-qa-retina-migration \
  --jar build/libs/metalrender-0.3.0+mc26.2.jar \
  --backend metal4 --require-graph-ownership \
  --hardware-display --require-retina --require-display-migration \
  --minimum-refresh-hz 200 \
  --presentation-samples 600 --timeout 1200

python3 scripts/exact_jar_qa.py warm \
  --runtime-dir build/exact-jar-qa-retina-migration \
  --jar build/libs/metalrender-0.3.0+mc26.2.jar \
  --backend metal4 --require-graph-ownership \
  --hardware-display --require-retina --require-display-migration \
  --minimum-refresh-hz 200 \
  --presentation-samples 600 --timeout 1200
```

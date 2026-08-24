# Display lifecycle and 200 Hz QA

Status: **development candidate PASS** on 2026-08-25 (Asia/Bishkek).

This document records post-`0.3.0` work against the remaining display-facing
limitations. It does not rewrite the published `0.3.0+mc26.2` support
contract.

## Exact candidate

- JAR: `build/libs/metalrender-0.3.0+mc26.2.jar`
- JAR SHA-256:
  `5a8e54d703648dd3e73cd3d24418f5b1c0e5fd52fcf8feff7debfd0ce226540a`
- Branch: `develop/0.3.1-display-lifecycle`
- Platform: MacBook Pro `Mac16,8`, Apple M4 Pro, 24 GB RAM, arm64
- OS: macOS 26.6, build `25G5065a`
- Active GLFW display: one `VX24G10`, 1x backing, 200 Hz
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
| Reported display refresh | 200 Hz | 200 Hz |
| Completed intervals | 600 | 600 |
| Measured present-call cadence | 199.534406 Hz | 198.713753 Hz |
| Interval p50 | 5.000208 ms | 5.015625 ms |
| Interval p95 | 6.041667 ms | 6.384000 ms |
| Interval p99 | 6.485584 ms | 7.473584 ms |
| Present-call duration p50 | 0.480833 ms | 0.508791 ms |
| >=100 ms stalls | 0 | 0 |
| Metal-owned presentations during capture | 608 | 605 |
| Display transitions / resets | 2 / 2 | 2 / 2 |
| Ownership failures | 0 | 0 |

The normal Stage 9 lifecycle subtest also passed resize, fullscreen, windowed
restore and surface hide/show. It recorded 106/90 additional Metal
presentations, 5,320/4,520 suppressed OpenGL commands, two expected graph-frame
invalidations per run and zero failures.

Evidence SHA-256:

| Evidence | SHA-256 |
| --- | --- |
| Cold run result | `bc041ef117efbb65a009b32ba7e635217942655a91d8d0dc61b63baa2dcafd72` |
| Warm run result | `6ba306eec574fe05cbfb8ad234f3c67a115ad2f90a2b232eeb21f42ebfd795a6` |
| Cold hardware sidecar | `21f1387ec0d7b3a6f46d9f04692a7d906c7a0607ba41002eaa91c41595d80d0f` |
| Warm hardware sidecar | `bc99aeba83901a990d8899442b8ab4928952065ef0df027cdf7fd55913c03e72` |
| Cold lifecycle sidecar | `6c2873cd0eedaa11d2d6d7e206fd2bdc470ef54bc41a225a426ee7f36f757ae6` |
| Warm lifecycle sidecar | `7257eac22615ff2b5d7ced2a496f3c32e025be0ceb45e6fea8dd4dd2769be850` |

## What the 200 Hz result means

The passing profile disables VSync and applies an exact 200 FPS software cap.
It proves that Minecraft executes the real window-present call at the intended
cadence while the Metal graph owns the measured frames. Both final runs stayed
within about 1.3 calls per second of 200, but this remains a finite controlled
sample rather than a guaranteed 200 FPS floor. It also does **not**
prove that Core Animation or the monitor scanned out 600 distinct frames in a
VSync-synchronised native Metal presentation path. The final image still
crosses the fenced IOSurface-to-OpenGL bridge, so native synchronized 200 Hz
remains a separate future cutover boundary.

## Remaining hardware gates

| Gate | Current state | How it closes |
| --- | --- | --- |
| True 2x Retina framebuffer | Harness and reset logic ready; not run | Make the built-in Retina panel active and run with `--require-retina` |
| Two-display migration/reconnect | Topology/migration logic ready; not run | Expose two GLFW displays and run with `--require-display-migration`; physically reconnect separately |
| Physical sleep/wake | Resume-gap recovery implemented and unit tested | Sleep and wake the Mac during a controlled exact-JAR session, then require ownership recovery with zero faults |
| Native VSync-synchronised 200 Hz | Not claimed | Replace/augment the final CGL/GLFW swap boundary with a native display-linked Metal presentation path and measure compositor/display feedback |

Only one display was visible to GLFW in the passing run. Consequently, the
Retina and two-monitor gates cannot be honestly marked PASS from this machine
state.

## Reproduce the available 200 Hz gate

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew --no-daemon clean test check build releaseCheck

python3 scripts/exact_jar_qa.py run \
  --runtime-dir build/exact-jar-qa-hardware-200hz \
  --jar build/libs/metalrender-0.3.0+mc26.2.jar \
  --backend metal4 --require-graph-ownership \
  --hardware-display --minimum-refresh-hz 200 \
  --presentation-samples 600 --timeout 1200

python3 scripts/exact_jar_qa.py warm \
  --runtime-dir build/exact-jar-qa-hardware-200hz \
  --jar build/libs/metalrender-0.3.0+mc26.2.jar \
  --backend metal4 --require-graph-ownership \
  --hardware-display --minimum-refresh-hz 200 \
  --presentation-samples 600 --timeout 1200
```

When the built-in Retina panel and a second display are both available, add
`--require-retina --require-display-migration` to both commands.

# Display lifecycle and presentation QA

Status: **stable software/replay PASS** for `0.3.1+mc26.2` on 2026-08-25
(Asia/Bishkek). Physical monitor and sleep/wake qualification remains separate.

## Exact final artifact

- JAR: `build/libs/metalrender-0.3.1+mc26.2.jar`
- JAR SHA-256:
  `5c5bfd4511a1304ad0b32e2f1ab66f1da7e5f718c2d1dc72dec1c97e1ffd30b7`
- Branch: `develop/0.3.1-display-lifecycle`
- Platform: MacBook Pro `Mac16,8`, Apple M4 Pro, 24 GB RAM, arm64
- OS: macOS 26.6, build `25G5065a`
- Shader workload: Complementary Reimagined r5.8.1 through Iris 1.11.2
- Full build: `clean test check build releaseCheck` — PASS
- Java/JNI parity: 155 declarations/exports — PASS
- Exact-JAR manifest/driver schemas: 5 / 14
- Final run profile: automated 1280x720 cold/warm; no
  `--hardware-display` or physical actions requested

## Delivered lifecycle behavior

The client observes a bounded GLFW display state at client-tick frequency.
Topology, active monitor, window recreation, backing/framebuffer size,
visibility/iconification or a likely wake gap creates an explicit safe
boundary. Refresh/topology changes also refresh the runtime frame target.

The reset is presentation-only. It preserves translated shaders, pipeline
archives, in-flight graph tokens, persistent graph attachments/history and
resident Metal inputs. OpenGL suppression remains off until a new completed
IOSurface is promoted and bound. Direct GLFW framebuffer dimensions are copied
through Minecraft's normal resize handler before lifecycle analysis, closing a
missed Cocoa/GLFW Retina callback observed during development.

`0.3.1` also adds three release-critical ownership rules:

- every asynchronous capture, translation and replay completion is fenced to
  the lifecycle generation that created it;
- every retained texture/surface lease is released exactly once, and unique
  retained bytes drive capture backpressure; and
- incomplete graph resources fail open before any OpenGL draw is suppressed.

The final IOSurface is sampled with a GPU-only rectangle-texture shader using
exact framebuffer coordinates and BGRA channel order. The presenter preserves
the caller's GL state. On reset it destroys the GL texture binding before the
native IOSurface can return to the Metal pool; this closes the intermittent
black/magenta 640x384 tile corruption found in a repeated warm lifecycle run.
There is still no CPU output copy.

Cadence capture may retry only when lifecycle counters prove that a real
resize/fullscreen/display transition contaminated the sample. A >=100 ms stall
on a stable display remains a failure. Wake detection treats a client-tick gap
of at least five wall-clock seconds as a resume boundary; this is unit tested,
but physical sleep was not forced for the final artifact.

## Final automated Metal 4 cold/warm result

| Gate | Cold | Warm |
| --- | ---: | ---: |
| Overall exact-JAR result | PASS | PASS |
| Activation | packaged stable default | packaged stable default |
| Backend / visible ownership | `METAL4` / full graph | `METAL4` / full graph |
| Shader translation | 231 translated, 0 failed | 231 cache hits, 0 failed |
| MSL stages validated | 462 | 462 from cache |
| Metal pipelines | 86 compiled | 89 archive hits, 1 new variant |
| FINAL visual parity | 3/3, RMSE 0, max delta 0 | 3/3, RMSE 0, max delta 0 |
| Full-graph presentations | 295 | 578 |
| OpenGL graph commands suppressed | 14,750 | 28,900 |
| Full-graph ownership failures | 0 | 0 |
| Lifecycle presentations | 83 | 81 |
| Lifecycle OpenGL suppressions | 4,150 | 4,050 |
| Lifecycle invalidations / failures | 3 / 0 | 3 / 0 |
| GPU errors / timeouts / IOSurface skips | 0 / 0 / 0 | 0 / 0 / 0 |
| FINAL screenshot reference MAD | 0.00022013 | 0.00019006 |

Both lifecycle subtests passed resize 1280x720 -> 960x540, fullscreen,
windowed restore and surface suspend/restore. The exact FINAL screenshots were
also inspected at original resolution: orientation/channel order are correct
and neither contains black/magenta macrotiles.

Evidence SHA-256:

| Evidence | SHA-256 |
| --- | --- |
| Metal 4 cold result | `6533c57112318baa632611e26b5f90bda212947c621731fcd413ed6f4fb2ae20` |
| Metal 4 warm result | `56089b5680a89764349f061336d491b1a2de83d1ffe441c2530c4718cc4cf3eb` |
| Cold lifecycle sidecar | `5c99a68dc19a111049f91e05971dadd88549ddc62e3a6bbc4f7fd2daf94ccb5c` |
| Warm lifecycle sidecar | `3ae76b6335d9e0ed1131ea1298580972b84c8f994d79294fc7a1370f6f49dff1` |

## Hardware qualification boundary

An earlier development candidate (JAR SHA
`5c62d7a62c931accafafd62a0438b57d6ee9b10ab7208183645fe20ff05e5b6e`)
ran with the built-in Liquid Retina XDR and a 200 Hz VX24G10. Its strict cold
and warm sessions recorded real 2x backing, migration through two displays and
600 completed `GlSurface.present()` intervals. They measured 181.52/198.64
calls per second, p50 5.081/5.003 ms and zero >=100 ms stalls or ownership
failures in an explicit VSync-off software-paced profile.

Those measurements prove the tested development path was not pinned to 60 Hz.
They are retained as engineering evidence, not silently promoted to exact
final-JAR qualification after later presentation/reset changes.

| Gate | `0.3.1` release state |
| --- | --- |
| True 2x Retina framebuffer | Earlier development-candidate PASS; not repeated for final SHA |
| Two-display migration | Earlier development-candidate PASS; not repeated for final SHA |
| Physical display disconnect/reconnect | Not run |
| Physical sleep/wake | Recovery implemented and unit tested; physical cycle not run |
| Native VSync-synchronised 200 Hz | Not claimed; final image still crosses the fenced IOSurface-to-GL/CGL boundary |

The software release is therefore stable for its declared automated matrix,
while cable, lid/power and direct-scanout claims remain explicit hardware
follow-up gates.

## Reproduce final software acceptance

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew --no-daemon clean test check build releaseCheck

python3 scripts/exact_jar_qa.py run \
  --runtime-dir build/exact-jar-qa-0.3.1-stable \
  --jar build/libs/metalrender-0.3.1+mc26.2.jar \
  --backend metal4 --require-graph-ownership \
  --shader-pack ComplementaryReimagined_r5.8.1.zip --timeout 1200

python3 scripts/exact_jar_qa.py warm \
  --runtime-dir build/exact-jar-qa-0.3.1-stable \
  --jar build/libs/metalrender-0.3.1+mc26.2.jar \
  --backend metal4 --require-graph-ownership \
  --shader-pack ComplementaryReimagined_r5.8.1.zip --timeout 1200
```

The optional physical qualification adds `--hardware-display` and the exact
`--require-*` gates only when the required display/power setup is available.

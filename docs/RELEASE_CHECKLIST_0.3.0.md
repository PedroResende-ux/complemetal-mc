# `0.3.0+mc26.2` Stage 9 release checklist

Status: **PASS** on 2026-08-24 (Asia/Bishkek) for the declared Apple Silicon
macOS 26 / Minecraft Java 26.2 / Iris 1.11.2 / Sodium 0.9.1 / Complementary
Reimagined r5.8.1 matrix.

## Exact artifact

- JAR: `build/libs/metalrender-0.3.0+mc26.2.jar`
- SHA-256:
  `4e874abbde4fdbc5e6edd72bc6cd419e321a15ced9f255df67d86091953cb57e`
- `releaseCheck`: PASS
- Java/JNI parity: PASS, 151 declarations/exports
- Native payload: signed arm64 dylib, macOS 14 deployment target
- Offline `shaders.metallib`: present and verified

Both exact-JAR manifests report `irisMetalActivation` as
`packaged-stable-default`. Their JVM launch commands contain zero legacy
`-Dmetalrender.experimental.irisMetal*=true` flags and no global enable flag.
The Metal 4 run nevertheless asserted measured
`METAL4_FULL_GRAPH_OWNERSHIP`, proving normal stable-install activation.

## Metal 4 full graph

Runtime: `build/exact-jar-qa-stage9-stable-metal`

| Gate | Result |
| --- | --- |
| Cold exact-JAR QA | PASS |
| Warm exact-JAR QA | PASS |
| Iris initially on / disabled / reenabled | PASS / PASS / PASS |
| Dimension route | Overworld -> Nether -> End -> Overworld |
| Translation | 231 programs / 462 stages, zero failures |
| Visual parity | 3/3, ratio 0, RMSE 0, maximum delta 0 |
| Cold pipelines | 90 compiled, 0 failed |
| Warm repeat pipelines | 86 archive hits, 0 compiled, 0 failed |
| Cold ownership | 1,074 presented, 53,720 OpenGL draws suppressed, 0 failures |
| Warm reverse repeat ownership | 3,101 presented, 155,070 OpenGL draws suppressed, 0 failures |
| Native faults | 0 GPU command errors, 0 in-flight timeouts, 0 IOSurface skips |

Warm lifecycle evidence is PASS for resize 1280x720 -> 960x540, fullscreen,
windowed restore and surface suspend/restore. It recorded 116 additional Metal
presentations, 5,820 OpenGL suppressions, two expected recapture invalidations
and zero ownership failures.

Evidence:

- cold result SHA-256:
  `f43aa0b69a25a1c29a15a7e2f30dbe15c9970557085247ccf5258ed36c5b8ca5`
- reverse-repeat warm result SHA-256:
  `384cef3479cf4c23ba07ee47b606ca3f104a23af32f106ad43cef6dd06dcaab3`
- warm lifecycle SHA-256:
  `b8030ca9da841cf06b5434268cad6d8691371184f0dc8b835b5835d5e2b8a5ff`

## Forced Metal 3 / Iris OpenGL fallback

Runtime: `build/exact-jar-qa-stage9-stable-opengl`

| Gate | Result |
| --- | --- |
| Cold exact-JAR QA | PASS |
| Warm exact-JAR QA | PASS |
| Backend | `METAL3` |
| Iris initially on / disabled / reenabled | PASS / PASS / PASS |
| Dimension route | Overworld -> Nether -> End -> Overworld |
| MTL4 pipeline readiness | Unsupported-safe fallback |
| Native Iris draws | 0 |
| Metal graph submissions / presentations | 0 / 0 |
| OpenGL draws suppressed | 0 |
| Ownership failures / native faults | 0 / 0 |

Evidence:

- cold result SHA-256:
  `907a9114215eb0f533338df2f10318f3cfcbb5a7363b7a7358ffc4ad8681b3f5`
- reverse-repeat warm result SHA-256:
  `f3b383cb3dfd10f2267badd9107a86f0d3d24570b85caf999c33956863b3b0bc`

## Matched performance gates

Scenario SHA-256:
`7fa233817cdfb31352fb269e0181a418ef51a7c8c0ca2982bccb94f8e8f4505e`.
Both sides use the same exact JAR, 600 CPU samples, 600 GPU samples, 1280x720,
render distance 8, simulation distance 5, fixed camera, noon, clear weather,
and Complementary Reimagined r5.8.1.

### First order: Metal then OpenGL

Gate: `build/stage9-performance-gate-stable-first-preserved.json` — **PASS**

| Metric | OpenGL | Metal | Comparison |
| --- | ---: | ---: | ---: |
| CPU p50 | 5.898 ms | 3.253 ms | 44.8% faster |
| CPU p95 | 8.719 ms | 5.462 ms | 37.4% faster |
| CPU p99 | 11.118 ms | 6.858 ms | 38.3% faster |
| GPU p95 | 7.773 ms | 6.257 ms | 19.5% faster |
| GPU p99 | 9.596 ms | 6.477 ms | 32.5% faster |

Gate JSON SHA-256:
`0e443082fdfaeab3428121daa303a4582b8cf20ce5a2bd3a63c09f7f5959b4d5`.

### Reverse order: OpenGL then Metal

Gate: `build/stage9-performance-gate-stable-reverse-repeat.json` — **PASS**

| Metric | OpenGL | Metal | Comparison |
| --- | ---: | ---: | ---: |
| CPU p50 | 5.776 ms | 3.318 ms | 42.6% faster |
| CPU p95 | 6.808 ms | 4.965 ms | 27.1% faster |
| CPU p99 | 7.442 ms | 7.550 ms | 1.5% slower; within 5% limit |
| GPU p95 | 7.073 ms | 6.328 ms | 10.5% faster |
| GPU p99 | 7.411 ms | 6.559 ms | 11.5% faster |

Gate JSON SHA-256:
`27bc74ba35c72583c9760d021c2b6a915d02a17b84a6f47bc1a780ebd1bcd8a7`.

All four performance sides recorded zero >=100 ms CPU/GPU stutters. Metal
recorded zero dropped GPU samples, instrumentation errors and commit-feedback
errors.

## Release boundary

The software Stage 9 gate is complete for the matrix above. True 2x Retina
backing, physical sleep/wake, external-display reconnect and real presented
200 Hz cadence remain explicitly unvalidated and are not release claims.

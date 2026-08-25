# `0.3.1+mc26.2` stable release checklist

Status: **PASS** on 2026-08-25 (Asia/Bishkek) for the declared Apple Silicon
macOS 26 / Minecraft Java 26.2 / Iris 1.11.2 / Sodium 0.9.1 / Complementary
Reimagined r5.8.1 software matrix.

Physical display reconnect, real sleep/wake, final-SHA Retina migration and
native synchronized 200 Hz scanout were deliberately not claimed in this
no-hardware release run.

## Exact artifact

- JAR: `build/libs/metalrender-0.3.1+mc26.2.jar`
- SHA-256:
  `5c5bfd4511a1304ad0b32e2f1ab66f1da7e5f718c2d1dc72dec1c97e1ffd30b7`
- `clean test check build releaseCheck`: PASS
- Java/JNI parity: PASS, 155 declarations/exports
- Native payload: ad-hoc signed thin arm64 dylib, macOS 14 deployment target
- Offline `shaders.metallib`: present and verified
- Exact-JAR manifest/driver schemas: 5 / 14
- Runtime classpath: 91 resolved release artifacts, zero source-set entries

Both exact-JAR manifests report `irisMetalActivation` as
`packaged-stable-default`. The Metal 4 launch has no global
`metalrender.irisMetal.enabled` override and no legacy
`metalrender.experimental.irisMetal*=true` enable flag. It nevertheless asserts
measured `METAL4_FULL_GRAPH_OWNERSHIP`. The Metal 3 profile differs only by the
explicit safe-backend selector `-Dmetalrender.feature.metal4=false`.

## Metal 4 full graph

Runtime: `build/exact-jar-qa-0.3.1-stable`

| Gate | Cold | Warm |
| --- | ---: | ---: |
| Exact-JAR QA | PASS | PASS |
| Backend / ownership | `METAL4` / full graph | `METAL4` / full graph |
| Iris initially on / disabled / reenabled | PASS / PASS / PASS | PASS / PASS / PASS |
| Dimension route | Overworld -> Nether -> End -> Overworld | Overworld -> Nether -> End -> Overworld |
| Translation | 231 translated, 0 failed | 231 cache hits, 0 failed |
| Generated MSL validation | 462/462 stages | 462/462 cached stages |
| Pipeline archive | 86 compiled, 0 failed | 89 hits + 1 new variant, 0 failed |
| FINAL visual parity | 3/3, ratio 0, RMSE 0, max delta 0 | 3/3, ratio 0, RMSE 0, max delta 0 |
| Metal presentations | 295 | 578 |
| OpenGL commands suppressed | 14,750 | 28,900 |
| Ownership failures | 0 | 0 |
| GPU errors / timeouts / IOSurface skips | 0 / 0 / 0 | 0 / 0 / 0 |
| FINAL screenshot reference MAD | 0.00022013 | 0.00019006 |

Stage 9 lifecycle passed resize 1280x720 -> 960x540, fullscreen, windowed
restore and surface suspend/restore in both runs. Cold/warm recorded 83/81
additional Metal presentations, 4,150/4,050 OpenGL suppressions, three expected
generation invalidations and zero failures. Both final screenshots were
inspected at original resolution and contain no inverted/channel-swapped image
or black/magenta macrotiles.

Evidence:

- cold result SHA-256:
  `6533c57112318baa632611e26b5f90bda212947c621731fcd413ed6f4fb2ae20`
- warm result SHA-256:
  `56089b5680a89764349f061336d491b1a2de83d1ffe441c2530c4718cc4cf3eb`
- cold lifecycle SHA-256:
  `5c99a68dc19a111049f91e05971dadd88549ddc62e3a6bbc4f7fd2daf94ccb5c`
- warm lifecycle SHA-256:
  `3ae76b6335d9e0ed1131ea1298580972b84c8f994d79294fc7a1370f6f49dff1`

## Forced Metal 3 / Iris OpenGL fallback

Runtime: `build/exact-jar-qa-0.3.1-metal3`

| Gate | Cold | Warm |
| --- | ---: | ---: |
| Exact-JAR QA | PASS | PASS |
| Backend / visible ownership | `METAL3` / OpenGL | `METAL3` / OpenGL |
| Iris initially on / disabled / reenabled | PASS / PASS / PASS | PASS / PASS / PASS |
| Dimension route | Overworld -> Nether -> End -> Overworld | Overworld -> Nether -> End -> Overworld |
| Translation | 231 translated, 0 failed | 231 cache hits, 0 failed |
| MTL4 pipeline attempts / draws | 0 / 0 | 0 / 0 |
| Metal graph submissions / presentations | 0 / 0 | 0 / 0 |
| OpenGL commands suppressed | 0 | 0 |
| Ownership failures | 0 | 0 |
| GPU errors / timeouts / IOSurface skips | 0 / 0 / 0 | 0 / 0 / 0 |

Evidence:

- cold result SHA-256:
  `041652d2ce2d7412244fb6f35e5e6abbce81ecb8bc317645295082eac7c2675f`
- warm result SHA-256:
  `f3d195e092d565c9486d673c04e8322f8a35fe16ae163a824a350c6e383bae85`

## Performance evidence

The renderer's matched 600-frame A/B qualification remains the two
opposite-order `0.3.0` gates: 42.6-44.8% CPU p50, 27.1-37.4% CPU p95,
10.5-19.5% GPU p95 and 11.5-32.5% GPU p99 improvement in the exact M4 Pro
1280x720 Complementary scene, with no >=100 ms stutters. `0.3.1` changes
correctness, lifecycle and GPU presentation ordering; a new FPS percentage was
not measured or claimed for this artifact.

## Release boundary

The exact software matrix is complete and fail-open. The final image remains a
fenced IOSurface sampled at the CGL/GLFW presentation boundary, not a direct
`CAMetalLayer` drawable. Earlier development-candidate Retina/two-display/200
Hz evidence is documented in
[`DISPLAY_LIFECYCLE_QA.md`](DISPLAY_LIFECYCLE_QA.md), but the final SHA was not
requalified with physical monitor actions. Cable reconnect, physical
sleep/wake and native synchronized 200 Hz scanout remain explicit follow-up
gates rather than hidden release claims.

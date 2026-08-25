# Complemetal 0.4.1 for Minecraft Java 26.2

`0.4.1` is the critical stability release for Iris and Complementary users.
It replaces the unsafe automatic Iris-to-Metal cutover from `0.4.0` with a
qualified fail-open profile: Iris/OpenGL owns the visible shader frame, while
Complemetal keeps its Metal 4 runtime and experimental translation foundation
without duplicating world rendering work.

## Fixed

- Fixed the severe FPS collapse caused by repeated full-graph capture on the
  Minecraft render thread.
- Fixed the world turning black after several seconds. Full-graph mode can no
  longer fall through to the legacy FINAL-only bridge and suppress a valid
  Iris frame with stale or incomplete inputs.
- Fixed the approximately one-minute crash and the deterministic
  `/complemetal reload` crash. Live reload now rebuilds Minecraft's
  `LevelRenderer` without leaving its `ViewArea` null.
- Deferred duplicate Metal terrain meshes, atlas mirrors, entity/particle GPU
  buffers, and presentation resources for the entire time Iris owns the frame.
- Kept experimental Iris Metal translation and graph ownership explicit
  opt-in only. Stable users do not need JVM flags.

## Compatibility verified

The exact release JAR was launched in the official Minecraft 26.2/Fabric
production runtime with:

- Complementary Reimagined r5.8.1, Ultra preset;
- Sodium 0.9.1 and Iris 1.11.2;
- ImmediatelyFast 1.16.2;
- Entity Culling 1.10.5;
- Lithium 0.25.2;
- FerriteCore 9.0.0;
- Mod Menu, YACL, Zoomify, Placeholder API, Fabric API, and Fabric Language
  Kotlin.

The enabled run passed entities, fluids, biome changes, a 12-second black-frame
window, flight and chunk streaming, survival mining, Zoomify input, live mod
reload, resource reload, Nether/End/Overworld transitions, and a second rainy
night world. It ran for 106.9 seconds, produced 14 verified non-black
screenshots, and recorded no crash report or forbidden renderer diagnostic.

## Fullscreen A/B result

Both sides used the same JAR, modpack, shader pack, worlds, and physical
1920x1080 fullscreen mode at 200 Hz:

| Metric | Enabled | Disabled | Difference |
| --- | ---: | ---: | ---: |
| Average FPS | 58.01 | 51.21 | +13.29% |
| 1% low FPS | 36.74 | 36.14 | +1.68% |
| Frame-time p50 | 17.86 ms | 19.45 ms | -8.17% |
| Frame-time p95 | 25.43 ms | 26.74 ms | -4.91% |
| Frame-time p99 | 27.22 ms | 27.67 ms | -1.65% |
| Retained heap after two full GCs | 445.5 MB | 456.7 MB | -11.2 MB |

Native fault counters were all zero. The +13.29% average is one controlled
paired result, not a universal promise and not proof of Iris-to-Metal
acceleration: the stable Iris frame remained on OpenGL. The release guarantee
is that the catastrophic `0.4.0` regression is removed for the qualified
workload.

## Upgrade

Remove `complemetal-0.4.0+mc26.2.jar` and install only
`complemetal-0.4.1+mc26.2.jar`. Existing `complemetal.json` and migrated
`metalrender.json` settings remain compatible.

Artifact SHA-256:

```text
e291b3b80c702ee90fc5f42b1ef5062b3c17e2e0cf383e55e34c55929e62f554  complemetal-0.4.1+mc26.2.jar
```

Complete evidence and reproduction commands are in
[`docs/RELEASE_CHECKLIST_0.4.1.md`](https://github.com/daniiarkg/complemetal-mc/blob/v0.4.1%2Bmc26.2/docs/RELEASE_CHECKLIST_0.4.1.md).

Complemetal is a continuation and fork of
[MetalRender by pebbles_boon / webblepebbles](https://github.com/webblepebbles/MetalRender),
distributed under Apache License 2.0.

# Minecraft shaders, accelerated for Apple Silicon

Complemetal is a performance mod built specifically for M-series Macs. It
accelerates compatible Iris shader workloads with Metal 4, targeting smoother
frame pacing, lower CPU and GPU frame times, and more headroom for demanding
shaders at high refresh rates.

Keep the Minecraft setup you already know: Fabric, Sodium, Iris, and your
shader pack. Complemetal works underneath that stack to make better use of the
Apple Silicon GPU.

## Measured performance

In two matched 600-frame M4 Pro tests at 1280x720 with Complementary
Reimagined r5.8.1, Complemetal measured:

- up to **44.8% lower CPU median frame time**;
- up to **37.4% lower CPU p95 frame time**;
- up to **19.5% lower GPU p95 frame time**;
- up to **32.5% lower GPU p99 frame time**;
- zero frames at or above 100 ms across every test side.

That translates into faster shader rendering, smoother frame delivery, and
more performance headroom for higher visual settings. Results vary with the
Mac, resolution, world, shader preset, and graphics settings.

## Built for M-series Macs

The current release is tuned and qualified for:

- Minecraft Java Edition 26.2;
- Apple Silicon on macOS 26+;
- Java 25 and Fabric Loader;
- Fabric API, Sodium 0.9.1, and Iris 1.11.2;
- Complementary Reimagined r5.8.1 as the primary acceptance workload.

Other Iris shader packs can use the accelerated path when their required
features are supported. If a particular effect cannot use Metal safely,
Minecraft continues through its normal Iris/OpenGL renderer instead of
breaking the frame.

## Install

1. Install Fabric API, Sodium, and Iris for Minecraft 26.2.
2. Use Java 25 on an Apple Silicon Mac running macOS 26 or newer.
3. Put the Complemetal JAR in `mods/`.
4. Select your Iris shader pack and play.

To inspect the active renderer in a world, run:

```text
/complemetal status
```

Existing MetalRender users keep their settings automatically. The legacy
`/metalrender` and `/mr` commands also remain available.

## Source and documentation

- [GitHub repository](https://github.com/daniiarkg/complemetal-mc)
- [Technical architecture](https://github.com/daniiarkg/complemetal-mc/blob/main/docs/TECHNICAL_ARCHITECTURE.md)
- [Architecture graphs](https://github.com/daniiarkg/complemetal-mc/blob/main/docs/ARCHITECTURE_GRAPH.md)
- [Release verification](https://github.com/daniiarkg/complemetal-mc/blob/main/docs/RELEASE_CHECKLIST_0.4.0.md)

## Origin and license

Complemetal is a continuation and fork of
[MetalRender by pebbles_boon / webblepebbles](https://github.com/webblepebbles/MetalRender).
The original renderer foundation and the modifications in Complemetal are
distributed under Apache License 2.0.

Complemetal is not affiliated with or endorsed by Apple, Mojang, Microsoft,
Iris, Sodium, or the Complementary shader-pack authors.

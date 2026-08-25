# Complemetal

Complemetal accelerates validated Iris shader graphs through Metal 4 on Apple
Silicon Macs. It captures the final GLSL produced by Iris, translates it to
OpenGL-semantics SPIR-V and MSL, compiles exact Metal pipelines, reconstructs
the shader-pack render graph, and presents the completed image through a
fenced IOSurface without a CPU output copy.

## Honest compatibility model

Complemetal is fail-open. A Metal frame becomes visible only after shader,
pipeline, resource, graph, lifecycle, three-frame visual-parity and
presentation gates all pass. Unknown or unsupported state remains on
Iris/OpenGL or Minecraft's normal renderer. Geometry-shader packs are not
supported because Metal has no direct geometry-shader stage.

The current qualified profile is:

- Minecraft Java Edition 26.2;
- Fabric Loader 0.19.3+ and Fabric API 0.156.0+26.2;
- Java 25;
- Apple Silicon on macOS 26+;
- Sodium 0.9.1 and Iris 1.11.2;
- Complementary Reimagined r5.8.1 as the exact shader-pack acceptance
  workload.

Other shader packs are not prevalidated. They may use Metal only when their
observed programs and render state pass the same strict gates.

## Performance evidence

On an M4 Pro at 1280x720 with Complementary Reimagined r5.8.1, two matched
600-frame warm-cache A/B runs in opposite launch orders measured 42.6–44.8%
lower CPU p50 frame time, 27.1–37.4% lower CPU p95, 10.5–19.5% lower GPU p95
and 11.5–32.5% lower GPU p99. Every side recorded zero frames at or above
100 ms.

Those numbers apply to that exact scene, not every Mac or shader setting.
Fragment math, shadow resolution, volumetrics, bandwidth and resolution can
remain the bottleneck.

## Install

Install Fabric API, Sodium and Iris, use Java 25, then put the Complemetal JAR
in `mods/`. In a world, run:

```text
/complemetal status
```

The old `/metalrender` and `/mr` commands remain available. An existing
`config/metalrender.json` is migrated to `config/complemetal.json` on first
load. The emergency Iris/OpenGL baseline switch is:

```text
-Dmetalrender.irisMetal.enabled=false
```

## Origin and license

Complemetal is a continuation and fork of
[MetalRender by pebbles_boon / webblepebbles](https://github.com/webblepebbles/MetalRender).
The original renderer foundation and the modifications in Complemetal are
distributed under Apache License 2.0. The source repository contains a full
lineage document and NOTICE.

Complemetal is not affiliated with or endorsed by Apple, Mojang, Microsoft,
Iris, Sodium or the Complementary shader-pack authors.

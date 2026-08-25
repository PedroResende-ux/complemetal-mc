# Project lineage and attribution

Complemetal is a continuation and fork of
[MetalRender](https://github.com/webblepebbles/MetalRender), originally created
by **pebbles_boon / webblepebbles**. The upstream project introduced the Apple
Silicon Metal renderer foundation, Fabric integration, native bridge,
configuration UI and early custom-renderer experiments on which this project
was based.

This repository was imported as a standalone Git history in commit
`5f9997b84b30c9abaf6a22f05a58829739b1072c`. The import did not record the
exact upstream commit hash, so this project does not claim an unverifiable
one-to-one base revision. The original project is configured locally as the
`upstream` Git remote; the Complemetal publication repository is `origin`.

## What changed after the fork

The fork was ported to Minecraft Java Edition 26.2 and Java 25, then developed
into a strict Iris shader replay pipeline:

- capture Iris' final transformed and linked GLSL rather than shader-pack
  source;
- compile GLSL to OpenGL-semantics SPIR-V, reflect it, and emit MSL;
- validate every accepted stage with Apple's Metal compiler;
- capture complete render state, vertex ABI and resource bindings;
- build device, OS and compiler qualified Metal pipeline archives;
- reconstruct Iris SHADOW, GEOMETRY, DEFERRED, COMPOSITE and FINAL work as a
  persistent Metal render graph;
- require exact three-frame visual parity before OpenGL suppression;
- present completed frames through a fenced IOSurface bridge without a CPU
  output copy;
- fail open to Iris/OpenGL or Minecraft whenever state is unsupported or a
  correctness gate fails;
- add exact packaged-JAR, cold/warm cache, Metal 3 fallback, dimension,
  lifecycle and matched performance qualification.

The public name changed from MetalRender to Complemetal in `0.4.0+mc26.2`.
Java packages, JNI symbols, the native library filename and the
`metalrender.*` JVM property namespace intentionally remain stable. Renaming
those internals would add binary and diagnostic risk without changing the
visible product. The Fabric metadata declares `metalrender` as a provided
legacy alias; `/metalrender` and `/mr` remain command aliases; and an existing
`config/metalrender.json` is migrated to `config/complemetal.json` on first
load.

## Licensing

The project is licensed under Apache License 2.0. The root `LICENSE` applies
to the upstream-derived work and subsequent modifications. The root `NOTICE`
retains the upstream attribution. Complemetal is not affiliated with or
endorsed by Apple, Mojang, Microsoft, Iris, Sodium or the Complementary shader
pack authors.

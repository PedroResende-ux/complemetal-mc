# MetalRender commands

All commands are client-side. Use `/metalrender` or the shorter `/mr` alias.

| Command | Result |
| --- | --- |
| `/metalrender` | Shows help |
| `/metalrender help` | Shows the command list |
| `/metalrender status` | Reports initialization, hardware, backend mode, Metal 4 runtime and draw-path state |
| `/metalrender config open` | Opens MetalRender settings |
| `/metalrender config save` | Saves the active configuration |
| `/metalrender config reload` | Reloads the configuration from disk and reapplies it |
| `/metalrender config reset` | Restores validated defaults and invalidates generated meshes |
| `/metalrender cache clear` | Clears MetalRender terrain meshes |
| `/metalrender reload` | Rebuilds Minecraft level render data and MetalRender meshes |
| `/metalrender restart` | Restarts the renderer session; process-global Metal device and pipeline objects are released when the client exits |
| `/metalrender performance reset` | Restores resolution scaling to `1.0x` |
| `/metalrender profile` | Toggles the MetalRender profiler overlay |

Commands that reset, reload or restart state require an attended client
session. This prevents automation or a remote command source from silently
destroying transient renderer state.

## First diagnostic

Run:

```text
/metalrender status
```

The important fields are:

- `Init state`: whether MetalRender reached the running state;
- `Fallback reason`: why the normal Minecraft renderer was retained;
- `Native backend`: `METAL4_RUNTIME_VERIFIED_METAL3_RENDER` means an MTL4
  command buffer completed successfully while renderer draw encoding still
  uses the Metal 3 compatibility path;
- `Metal 4 runtime`: result of the real MTL4 command-buffer completion probe;
- `Metal 4 draw path`: `Compatibility` is expected in the current release.

`Ready` for the Metal 4 runtime is not proof of MTL4 draw encoding. On a
Metal 4-capable macOS 26 runtime, the expected combination is runtime `Ready`,
draw path `Compatibility`, and backend
`METAL4_RUNTIME_VERIFIED_METAL3_RENDER`. On macOS 14 or another runtime that
does not expose MTL4, `Unavailable`, draw path `Compatibility`, and
`METAL3_FALLBACK_NO_METAL4` are the normal stable fallback.

The stable label applies to this Metal 3 compatibility draw profile. When an
Iris shader pack is active, Iris continues to render through OpenGL. The
opt-in Iris GLSL-to-SPIR-V-to-MSL cache experiment does not execute Metal
pipelines and does not claim an FPS improvement.

## Recovery order

For a transient visual problem, use the least disruptive operation first:

```text
/metalrender reload
/metalrender restart
/metalrender config reset
```

If the renderer still falls back, save `latest.log` before restarting the game.

For a clean vanilla baseline, disable MetalRender before startup with the JVM
property `-Dmetalrender.enabled=false`. This is a diagnostic switch, not an
in-game command, and requires a client restart.

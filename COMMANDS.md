# Complemetal commands

All commands are client-side. Use `/complemetal` or the short `/cm` alias.
The former MetalRender roots `/metalrender` and `/mr` remain compatibility
aliases and execute the same command tree.

| Command | Result |
| --- | --- |
| `/complemetal` | Shows help |
| `/complemetal help` | Shows the command list |
| `/complemetal status` | Reports initialization, hardware, translation, pipeline, graph, ownership and presentation status |
| `/complemetal config open` | Opens Complemetal settings |
| `/complemetal config save` | Saves the active configuration |
| `/complemetal config reload` | Reloads the configuration from disk and reapplies it |
| `/complemetal config reset` | Restores validated defaults and invalidates generated meshes |
| `/complemetal cache clear` | Clears generated terrain meshes |
| `/complemetal reload` | Rebuilds Minecraft level render data and Complemetal meshes |
| `/complemetal restart` | Restarts the renderer session; process-global Metal objects are released at client exit |
| `/complemetal performance reset` | Restores resolution scaling to `1.0x` |
| `/complemetal profile` | Toggles the frame-time profiler overlay |

Commands that reset, reload or restart state require an attended client
session. This prevents automation or a remote command source from silently
discarding transient renderer state.

## First diagnostic

Run:

```text
/complemetal status
```

The most useful fields are:

- `Init state` and `Fallback reason`: whether native initialization succeeded
  and why Minecraft retained the normal renderer when it did not;
- `Native backend`: the exact Metal runtime mode;
- `Iris GLSL translation`, `MSL library validation` and pipeline-state fields:
  progress through shader and pipeline preparation;
- `Iris render graph`, graph attachments and full graph: whether every pass,
  dependency and resource is complete;
- `ownership`, successful presentations and OpenGL suppression: whether Metal
  actually owns the validated Iris graph rather than merely compiling it;
- native fault counters and blocker summaries: the first release-relevant
  reason for a fail-open decision.

`nIsMetal4Active` or an available MTL4 object is not by itself proof that the
visible shader graph runs through Metal. In stable `0.4.1`, an active Iris
shader pack intentionally retains visible OpenGL ownership. Experimental
Metal ownership additionally requires the full translation, pipeline,
resource, graph, parity, and presentation gates.

## Recovery order

For a transient visual problem, use the least disruptive operation first:

```text
/complemetal reload
/complemetal restart
/complemetal config reset
```

If the problem remains, save `latest.log` before restarting the game. Stable
`0.4.1` already uses the safe Iris/OpenGL profile by default. The following
legacy property remains useful only to override an explicitly enabled
development profile:

```text
-Dmetalrender.irisMetal.enabled=false
```

The legacy `metalrender.*` property namespace is intentional: it is a stable
diagnostic and automation compatibility boundary retained after the public
Complemetal rename.

---
name: unverified-paper-image-contract
description: What :core assumes about the inside of a Paper container — now verified end to end against a real image, including the save path, plus the startup timing that shapes the probe
metadata:
  type: project
---

Everything `:core` assumes about the *inside* of a Paper container is written
against `itzg/minecraft-server` and confined to two objects,
`PaperImageContract` and `PaperCommands`, so a correction stays a one-file
change.

**As of 2026-07-27 it is verified**, against containerd 2.3.3 and a real image,
by `app/src/integrationTest` running 6/6 green — including the drain path end to
end (`worldSaved=true worldData=true`, container stopped, world left behind,
definition purged).

Facts worth keeping, because none is derivable from reading the code:

- `mc-monitor status` prints one summary line —
  `127.0.0.1:25565 : version=Paper 1.21.4 online=0 max=20 motd='...'` — with no
  `players.sample` block. Note `version=Paper 1.21.4` contains a space: never
  parse it positionally.
- `save-all flush` blocks until the write completes; **plain `save-all` replies
  with the byte-identical `Saved the game` about six seconds early**. The
  `flush` argument is the entire safety margin, and a test pins it.
- `rcon-cli save-all flush` and the `Saved the game` reply are what the drain
  actually matches on, confirmed by a real drain reaching a stop.
- A duplicate create returns `FAILED_PRECONDITION`, not `ALREADY_EXISTS`.
- Sandboxes need a systemd-slice `cgroup_parent` or nothing starts at all.

## The startup window, which shapes the probe

**A Paper server takes 60–95 seconds to become joinable, and the 10s Server List
Ping probe times out several times inside that window** — reliably, on every one
of the six servers the suite brings up. The container is `RUNNING` after about
two seconds, which is the whole reason readiness is a ping rather than a
container state.

Those probe timeouts surface from containerd as `DEADLINE_EXCEEDED`, the same
code `:cri` produces when its own transport deadline elapses, and reading them
as an unreachable runtime is what put a healthy server at
`phase=UNKNOWN failure=RUNTIME_UNREACHABLE` for two minutes of every bring-up.
See [[integration-freeze]] for how much that cost to diagnose.

**How to apply:** budget generously for readiness in any integration work — five
minutes, not one — and expect `EXEC_SYNC ... DEADLINE_EXCEEDED durationMs≈10000`
WARN lines during a normal, healthy bring-up. They are not a fault. A run where
they *stop* appearing but the server never becomes joinable is the interesting
case.

---
name: unverified-paper-image-contract
description: The Paper server's env-var names and in-container commands were written from documentation and have never been run — what integration-tester has to confirm
metadata:
  type: project
---

Everything `:core` assumes about the *inside* of a Paper container is written
against `itzg/minecraft-server` (the image the schema's own examples use) and has
never been executed. It is deliberately confined to two objects,
`PaperImageContract` and `PaperCommands`, so a correction is a one-file change.

**Much of this is now verified** against containerd 2.3.3 and
`itzg/minecraft-server`, by `integration-tester` and by the suite in
`app/src/integrationTest`:

- `mc-monitor status` prints one summary line —
  `127.0.0.1:25565 : version=Paper 1.21.4 online=0 max=20 motd='...'` — and
  `PaperCommands.parseOccupancy` reads it correctly. No `players.sample` block.
  Note `version=Paper 1.21.4` contains a space: never parse it positionally.
- `save-all flush` blocks until the write completes; **plain `save-all` replies
  with the byte-identical `Saved the game` about six seconds early**. The
  `flush` argument is the entire safety margin, and is now pinned by a test.
- `ExecSync` outrunning its own timeout does surface as `DEADLINE_EXCEEDED`.
- A duplicate create returns `FAILED_PRECONDITION`, not `ALREADY_EXISTS`.
- Sandboxes need a systemd-slice `cgroup_parent` or nothing starts at all.

Still unverified, in rough order of how badly a wrong guess hurts:

- `rcon-cli save-all flush` and the reply text that counts as a *completed* save
  (`Saved the game` / `Saved the world`). If the real reply differs, every drain
  aborts and no server can ever be stopped — loud, not silent.
- Whether `save-all flush` replies after the flush or on acceptance. If it
  replies early, the loop would believe a save that has not finished. This is
  the one that fails *quietly*, so it is the one to check first.
- `mc-monitor status` output shape (`online=N max=M`), which is both the
  readiness verdict and the zero-player evidence a drain depends on.
- Env names: `INIT_MEMORY`/`MAX_MEMORY`, `PAPER_BUILD`, `MAX_PLAYERS`,
  `SERVER_PORT`, `ENABLE_RCON`.

**Why:** unit tests use a fake node, so all of this is asserted against a
simulator that agrees with the code by construction.

**How to apply:** route to `integration-tester` before anyone treats a green
`./gradlew build` as evidence a server can be drained. `LocalNode` itself is in
the same position — no unit test constructs one.

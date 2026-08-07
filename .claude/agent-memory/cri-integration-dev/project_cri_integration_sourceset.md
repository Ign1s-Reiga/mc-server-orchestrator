---
name: cri-integration-sourceset
description: :cri now has its own integrationTest source set for claims only a real containerd can settle, and the concurrency hazard of sharing the dev runtime with other agents
metadata:
  type: project
---

Added 2026-08-06. `cri/src/integrationTest` + a `:cri:integrationTest` task,
wired exactly like `:app`'s and **not** in `check`.

**Why it is here and not in `:app`:** `:app` deliberately has no `:cri` on its
classpath (see the note at the top of `app/build.gradle.kts`), so it physically
cannot test the CRI boundary. What belongs here is the small set of claims about
*containerd's own behaviour* that the wrapper is built on — a fake CRI server
cannot check those, because a fake just agrees with whatever the wrapper
believes.

**How to apply:**
- The protobuf plugin creates a generate task per source set, so the build now
  also carries `generateIntegrationTestProto`. There is no
  `src/integrationTest/proto`, so it is a no-op. Stubs come from `main` only;
  the source set gets them via `sourceSets["main"].output`.
- `internal` from `:cri` main is **not** visible here (only `test` is a friend),
  so the raw-stub half builds its own netty channel. The generated `runtime.v1`
  types are public and are available.

**The dev containerd is shared with other agents' worktrees.** During this work
another worktree's `:app:integrationTest` had containers running on the same
socket. Namespace everything (`mcorch-cri-it` here, `mcorch-it` for `:app`) and
make any orphan sweep filter on the namespace — a sweep that removed "everything
running" would kill a concurrent agent's run. Before concluding "my test leaked
a container", check `/proc/<pid>/cmdline` of the running gradle for which
worktree it belongs to.

Related trap: several concurrent Gradle builds on the same project produced a
one-off `:cri:generateProto FAILED` that does not reproduce serially. Kill
stray daemons before reading a build failure as a defect.

See [[cri-stop-timeout-overflow]], [[cri-build-env-findings]].

---
name: cri-wrapper-design-decisions
description: Non-obvious choices baked into the :cri wrapper — UNKNOWN is retryable, StopGracePeriod has no default, slf4j-api was introduced project-wide here, and JUnit silently drops non-Unit test methods
metadata:
  type: project
---

Decisions made while writing `mcorch.cri.CriClient` (2026-07-26) that are not
recoverable by reading the code, plus one trap that cost a debugging cycle.

**`UNKNOWN`/`INTERNAL`/`DATA_LOSS` are classified retryable**
(`CriException.RuntimeFailure`). containerd reports genuinely transient
conditions this way — snapshotter contention, registry hiccups mid-pull — so a
bounded backoff recovers. It is the one judgement call in the mapping table,
which is why it has a distinct type rather than being folded into another.
*How to apply:* if `:core` ever spins forever on a bad image tag, this is the
row to revisit, not the whole classification scheme.

**`StopGracePeriod` deliberately has no default and no `Duration` overload.**
The zero case is only reachable as `StopGracePeriod.IMMEDIATE_KILL`, so a
zero-grace stop is greppable in a drain audit. `of()` rounds *up* to whole
seconds so a grace period is never silently shortened.
*How to apply:* do not add a convenience `stopContainer(id)` overload, however
often it is asked for.

**slf4j-api 2.0.17 was introduced into the version catalog by this work.** It
was the first logging dependency in the repo; nothing else had one. API only, no
binding, so `:app` still has to choose an implementation.
*How to apply:* if another module wants logging, use slf4j rather than adding a
second framework.

**JUnit Jupiter silently drops `@Test` methods with a non-`Unit` return type.**
`fun t() = runBlocking { ... }` infers the block's last expression, so a whole
suite went green having run a third of its tests. `cri/src/test/.../Fixtures.kt`
has `runCriTest` to make this structurally impossible.
*How to apply:* when adding suspending tests anywhere in this repo, either use a
block body or a helper that pins the return type — and check the test *count*,
not just the green tick.

See [[cri-pipeline-phasing]].

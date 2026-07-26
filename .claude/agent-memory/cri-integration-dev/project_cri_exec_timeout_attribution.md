---
name: cri-exec-timeout-attribution
description: containerd reports an ExecSync command timeout as DEADLINE_EXCEEDED, the same code our own transport deadline gives — CriException.Timeout.commandTimeout tells them apart by elapsed time
metadata:
  type: project
---

Verified against containerd 2.3.3 on 2026-07-27. `ExecSync` with a command
timeout that expires comes back as gRPC `DEADLINE_EXCEEDED` with
`failed to exec in container: timeout 10s exceeded: context deadline exceeded`
— **the same status code this client produces when its own transport deadline
elapses.**

**Why it matters:** undistinguished, a healthy runtime running a slow command is
indistinguishable from a runtime that has stopped answering. A Paper server
still generating its world took longer than the 10s readiness probe, and
observed status recorded `phase=UNKNOWN`, `reason=RUNTIME_UNREACHABLE` against a
containerd answering in milliseconds. It recovered on the next pass, but the
status it wrote in between sent a whole debugging session after the wrong
component.

**How to apply:** `CriException.Timeout.commandTimeout` distinguishes them. The
discriminator is *elapsed time versus the transport deadline*, never message
text — descriptions are free-form and change between releases, which is the
same rule `translateStatus` follows. grpc raises a client-side
`DEADLINE_EXCEEDED` at or after the deadline and never before, so anything
shorter must be the runtime answering. The inequality is one-sided on purpose:
when `deadlineSlack` is configured too small to separate the two, it reports the
ordinary transport timeout rather than guessing.

The `:core` half is **not done** — `PaperServerAgent.probe` still turns any
`NodeException` into `ProbeOutcome.Unavailable`, and `Reconciler` still maps
that to `RUNTIME_UNREACHABLE` + `phase=UNKNOWN`. Carrying `commandTimeout`
through `NodeException.Timeout` so a slow probe stays `STARTING`/"not joinable
yet" is a `:core` change, deliberately left to whoever owns that module.

See [[runblocking-loop-trap]] and [[cri-wrapper-design-decisions]].

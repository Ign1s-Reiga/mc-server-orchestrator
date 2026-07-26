---
name: cri-log-redaction-policy
description: Why :cri withholds the runtime's error description for PULL_IMAGE / CREATE_CONTAINER / RUN_SANDBOX and caps it everywhere else — do not "restore the useful detail"
metadata:
  type: project
---

Raised by the fifth drain audit (2026-07-27) and fixed in `:cri`. The rule is
easy to undo by accident, because the thing being withheld is genuinely the most
useful line in the log.

**The rule.** `GrpcCriClient.instrumented` caps a failure description at
`MAX_LOGGED_DESCRIPTION_CHARS` and withholds it entirely for the operations
`CriOperation.requestMayCarrySecrets` names: `PULL_IMAGE`, `CREATE_CONTAINER`,
`RUN_SANDBOX`.

**Why:** a runtime's error text is a third party's free-form string. Go's
`fmt.Errorf("...: %+v", config)` habit means a rejected request can come back
with the request inside it, and `CreateContainer`'s request is
`ContainerConfig.envs` — where the RCON password sits, and the only route by
which the Velocity forwarding secret reaches a container (CLAUDE.md invariant
4). `PullImage` carries `AuthConfig`, whose password and tokens the CRI proto
*itself* marks `debug_redact = true`. A cap alone is not enough for those: a
truncated prefix of a rejected `CreateContainer` is still a prefix of the
environment. The earlier comment claimed the text was safe because containerd
has never seen a player — true of *player* data, and silent about the request.

**How to apply:**

- Do not restore the full text for those operations because a failure was hard
  to diagnose. The whole string is still on the server's observed status; that
  is the copy to read.
- `EXEC_SYNC` and `EXEC` are deliberately *not* on the list. No call site puts a
  credential in an argv, and their descriptions are what separate a slow command
  from a sick node — see [[cri-exec-timeout-attribution]]. Add them only if a
  call site starts passing a secret as an argument.
- The list is one exhaustive `when`, not a set, so adding an RPC to
  `CriOperation` will not compile until someone decides which side it falls on.
  Keep it that way.
- `:cri`'s tests now have a capturing SLF4J provider (mirroring `:store`'s) that
  reads back what was really logged, at every level. Reverting the redaction
  fails four of them. A test that only asserts on the redaction *function* would
  agree with itself.

**Still open, and not mine:** `FailureStatus.message` carries the same
undecorated text into the SQLite store and out through the API. If a
`CreateContainer` error quotes the request, the secret is persisted and served.
The `:cri` half of the fix exists — `requestMayCarrySecrets` is public precisely
so `LocalNode.describe()` can consult one list rather than growing a second that
drifts.

---
name: cri-log-redaction-policy
description: Why :cri withholds the runtime's error description for PULL_IMAGE / CREATE_CONTAINER / RUN_SANDBOX, and why safeMessage/safeDescription exist rather than a boolean callers combine themselves
metadata:
  type: project
---

Raised by the fifth drain audit (2026-07-27) and fixed in `:cri` over two
rounds. The rule is easy to undo by accident, because the thing being withheld
is genuinely the most useful line in the log.

**The rule.** `CriException.safeDescription` is the *only* place the withholding
decision is made. It withholds when the operation's request carries secret
material (`CriOperation.requestMayCarrySecrets`: `PULL_IMAGE`,
`CREATE_CONTAINER`, `RUN_SANDBOX`) **and** the text is the runtime's rather than
ours. `safeMessage` is the decorated form. `message`/`description` stay
unredacted for use inside the module.

**Why:** a runtime's error text is a third party's free-form string. Go's
`fmt.Errorf("...: %+v", config)` habit means a rejected request can come back
with the request inside it, and `CreateContainer`'s request is
`ContainerConfig.envs` — where the RCON password sits, and the only route by
which the Velocity forwarding secret reaches a container (CLAUDE.md invariant
4). `PullImage` carries `AuthConfig`, whose password and tokens the CRI proto
*itself* marks `debug_redact = true`.

**Why an accessor and not a boolean.** Round one exported
`requestMayCarrySecrets` and let `:core` combine it with `message` itself. That
left a one-token security decision in `LocalNode.describe()` — a module that
cannot name a `mcorch.cri` type in its own tests, so inverting or dropping the
token broke nothing visible. Round two moved the *application* here too, so
`describe()` is plain delegation. **If a future consumer needs a redacted
string, give it a pre-redacted accessor; do not hand out the raw text plus a
flag.**

**How to apply:**

- Do not restore the full text for those operations because a failure was hard
  to diagnose. It is not on observed status either — `:core` withholds the same
  ones. The place it exists is the **container runtime's own log on the node**,
  which is what `WITHHELD_DESCRIPTION` tells the operator. An earlier version of
  this comment pointed at observed status and was wrong.
- Log side and persist side are **not** the same policy. The log additionally
  caps at `MAX_LOGGED_DESCRIPTION_CHARS`; the persisted copy deliberately does
  not, because Go renders a rejected request from the front and a prefix of a
  failed `CreateContainer` is a prefix of the environment. A cap is a defence
  against log volume, never against disclosure.
- `EXEC_SYNC`/`EXEC` are deliberately *not* on the list, and `:core` pins their
  message with `shouldBe` on the whole rendered string — it must pass through
  byte for byte. See [[cri-exec-timeout-attribution]].
- Text this client wrote itself (`emptyIdentifier`) is exempt via
  `describedByRuntime = false`. Every operation it can fire for is a
  secret-bearing one, so without the exemption a precise report of a broken
  runtime is replaced, in every case, by a warning about secrets the sentence
  does not contain.
- The list is one exhaustive `when`, not a set, so adding an RPC to
  `CriOperation` will not compile until someone decides which side it falls on.

**Testing.** `:cri` has a capturing SLF4J provider (mirroring `:store`'s) that
reads back what was really emitted, at every level. Mutation-checked both
rounds: dropping the withholding fails four tests. A test asserting only on the
redaction *function* would agree with itself, and a test that needed a fake
`CriClient` in `:core` would have cost the grep-verified property that
`LocalNode.kt` is the only file naming a `mcorch.cri` type.

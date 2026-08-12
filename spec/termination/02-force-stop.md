# 02 — Forced termination

> This path **can lose the last several minutes of play**, and in the worst case
> can corrupt a region file. It exists because the alternative — a server that
> cannot be retired at all — is worse. Nothing below hides that.

## 1. The shape

```
DELETE /api/v1/servers/{name}?force=true
```

`Superuser` only. Surfaced on the dashboard as **Force Stop**, visible only to
that tier.

Not a new route. The same endpoint that ends a server, with less patience — one
path for "end this server", which keeps the tombstone, the terminating row and
the name-freeing guard all working exactly as they do now.

## 2. Semantics: it skips the patience, not the save

This is the whole design, and it follows from *when the button gets used*.

An operator reaches for Force Stop when an ordinary delete did not finish — which
per [README.md](README.md) means, overwhelmingly, that **RCON is not answering**.
And RCON not answering usually means the main thread is busy. It may be busy
*saving*. The drain-protocol skill:

> A kill mid-save can corrupt region files.

So the case the button exists for is the case where an immediate kill is most
likely to do permanent damage. An immediate kill is therefore not what it does.

**Force Stop:**

| Step | Ordinary delete | Force |
|---|---|---|
| Seal the proxy, stop new joins | Yes | Yes — free, and it costs nothing to keep |
| Transfer players out | Wait until zero | **Attempt, then proceed** |
| Request a world save | Yes | Yes — **always** |
| Wait for save confirmation | Until confirmed, or fail the drain | Wait the full save timeout, then **proceed unconfirmed** |
| Retry a failed drain | Yes, with backoff | **No** |
| Stop the container | Only after confirmation | After the save timeout, regardless |
| Grace period on stop | `spec.lifecycle.stopGracePeriod` | **Unchanged** — still the full period |

Two of those rows are the ones that matter.

**The save is always requested and always waited for.** Skipping it would save an
operator the save timeout — tens of seconds — in exchange for the data the whole
system exists to protect. That is not a trade worth offering. In the common case
(RCON wedged but the server still writing) the wait is what lets an in-flight save
finish.

**The grace period is unchanged.** `spec.lifecycle.stopGracePeriod` is the
last-resort net that lets a server flush on `SIGTERM`. Shortening it under
"force" would remove the one protection that still works when RCON does not.

## 3. What it costs, stated plainly

- **Players are disconnected**, not transferred, if the transfer did not complete.
- **Unsaved state is lost** if the save did not confirm within the timeout.
- **A region file can still be corrupted** if the container is killed at the end
  of the grace period while a save is genuinely mid-write. Rarer than an immediate
  kill would make it — not impossible.

The confirmation the dashboard shows must say those three things. Not "this may
cause data loss": which data, and to whom.

## 4. `crictl stop --force` stays out of band

The orchestrator does not offer an immediate kill and should not. `crictl` remains
available to anyone with host access, and remains what the drain audits treat it
as: the symptom of a system that failed to converge, not a supported workflow.

Worth knowing about its interaction with the loop — a container killed by `crictl`
is observed gone on the next pass, and the loop converges on that. It is not
corruption of orchestrator state; it is a fact the loop accepts. The cost is that
nothing recorded *why*, which is exactly what the audit record of a Force Stop is
for.

## 5. `RouteTableTest` has to change, deliberately

It currently asserts that no route pattern contains `stop`, `kill`, `force` or
`purge`, with the reason inline: *"one that could stop a container directly could
stop one with players on it."*

A query parameter does not change a *pattern*, so the assertion would keep passing
untouched — **and that is the problem.** The test's intent is about capability,
not spelling, and leaving it green while the capability arrives makes it a test
that no longer tests what it claims.

CLAUDE.md: *"Never delete a test to make the build pass. If the spec changed,
rewrite the test and say why in the commit message."* The spec changed. The
rewrite:

- keep the pattern assertions for `purge` and for a bare `stop` route — nothing
  should reintroduce those;
- replace the blanket claim with the narrower true one: **exactly one route can
  stop a container, it is `DELETE`, and the forced form requires `Superuser`**;
- assert the tier, so a later refactor that drops the check fails here.

## 6. The audit record

Force Stop is the single most consequential thing an operator can do through this
API, so it is recorded whether or not the console's audit sink exists yet:

| Field | |
|---|---|
| identity | Who pressed it |
| server | The declared name |
| at | Timestamp |
| playersOnline | As last observed, **a count** — the logging rule is unchanged |
| saveConfirmed | Whether the save was confirmed before the stop. **This is the field that says whether data was lost** |
| drainState | What the drain had reached when force was applied |

`saveConfirmed: false` is the record that matters. It is the difference between
"an operator retired a stuck server" and "an operator lost a world", and six
months later it is the only thing that can tell them apart.

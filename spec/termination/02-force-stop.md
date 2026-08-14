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

> **As built, this is not a change to the drain — and that is a correction to what
> this section originally specified.**
>
> The exemptions below were written as behaviour inside `DrainController`. Two
> things in that file refuse them. `DrainPass.cause` carries an explicit rule —
> *"what a drain does is the same whatever asked for it, and a cause consulted at a
> gate is how a delete comes to take a path a replacement was written for"* — and
> force-as-exemptions is exactly a per-drain variation read at gates.
> `DrainStatus.mayStop` is the single precondition for every stop in the file,
> carrying a comment saying it exists to catch *"a future edit that routes into
> the stop without a current save"*.
>
> So `NodeForcedTermination` does what `docs/operating.md` note 1 already tells an
> operator to do by hand — save, stop, let the teardown observe it — and the drain
> is untouched. The table below still describes the *effect*; it no longer
> describes where the code lives.

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

**The save is requested wherever a request can be sent, and always waited for.**
Skipping it would save an operator the save timeout — tens of seconds — in
exchange for the data the whole system exists to protect. That is not a trade
worth offering. In the common case (RCON wedged but the server still writing) the
wait is what lets an in-flight save finish.

> An earlier draft of this file said *always requested*, and the implementation
> repeated the claim. It was false on three branches, one of which is the very
> population this feature is named for: a container with no save channel returns
> before an exec is built. The response therefore carries `saveAttempted` beside
> `saveConfirmed` — *"never sent"* and *"sent and not confirmed"* are different
> events and must not be reported as one.

**The grace period is never shortened, and on one branch it is lengthened.**
`spec.lifecycle.stopGracePeriod` is the last-resort net that lets a server flush
on `SIGTERM`. Shortening it under "force" would remove the one protection that
still works when RCON does not.

When **no save request could be sent**, that net is not a net — it is the entire
save, with nothing watching it. On that branch the grace period is raised to at
least `PaperServerDefaults.SAVE_TIMEOUT`, which is already this orchestrator's
model of how long a world save takes. Accepting less there than the drain accepts
for a save it can watch would put the lower bar on the more dangerous path. The
schema's own minimum permits as little as 31 seconds.

It is a **raise, not a refusal**, and that distinction is load-bearing. The stop
runs after the definition has been tombstoned, and a tombstoned definition cannot
be edited — so a refusal reading *"raise it and force again"* is advice nobody can
take, and it strands the server: undrainable, unforceable, `crictl` only, which is
the exact state this feature exists to remove. Every refusal on this path is
either decided **before** the tombstone or answerable by re-sending the same
request.

**The login path is sealed first, and the acknowledgement is a count.**
`requireEmpty`'s zero is durable in the drain because the **seal** holds it: from
`SEALED` onward the proxy will not route a join, so nobody arrives between the
observation and the stop. Two drafts of this path dropped step 2 and tried to make
a bare probe carry the same weight; neither could. One probe let players join
across the save wait — an hour, at the `SpecBounds` ceiling — and the branch that
exposed was the one that looks safest, since a server observed empty is asked for
no acknowledgement at all. Two probes only narrowed that, and made the counted
acknowledgement livelock on a busy server: nothing owned the number between the
409 that named it and the re-send that quoted it.

So this path performs drain step 2 before it reads anything. Under the seal the
count can only fall, which is the direction that is safe to be wrong about. A
door that cannot be shut is decided against the count rather than on its own —
the same trade the drain makes — so an empty server is still stopped and a
populated one is refused until the proxy is repaired or the server empties.

A **standalone** server has no door. The count is re-read immediately before the
stop there instead, which narrows the window without closing it; see the residual
note in [README.md](README.md).

`?acknowledgeOccupancy=` therefore takes **the number the operator was shown**, or
the literal `unreadable`. A boolean would say *"proceed regardless"*: it cannot
notice the population changing between the decision and the request, does not
require anyone to have looked, and — because a wedged server never answers a ping
— would be mandatory on essentially every legitimate use of this endpoint. A
confirmation that fires on every correct invocation is noise within a week, and
carries nothing at the one moment it matters. `unreadable` is a distinct value
rather than a wildcard, so the acknowledgement a stuck server needs cannot quietly
authorise stopping a healthy populated one.

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

> Authorised. The rewrite lands **in the change that adds the force parameter**,
> not before — a test asserting a `Superuser` tier and a `force` parameter that do
> not exist yet would not compile, and one reworded ahead of them would be a test
> describing a system that is not there.

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
| playersOnline | **A count, read immediately before the stop** — never the one the operator acknowledged, and null when the server did not answer. The logging rule is unchanged |
| acknowledged | The count the operator stated they had been shown |
| saveConfirmed | Whether the save was confirmed before the stop. **This is the field that says whether data was lost** |
| drainState | What the drain had reached when force was applied |

`saveConfirmed: false` is the record that matters. It is the difference between
"an operator retired a stuck server" and "an operator lost a world", and six
months later it is the only thing that can tell them apart.

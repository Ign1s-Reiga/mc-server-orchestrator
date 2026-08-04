---
name: level-triggered-seal
description: Why the proxy seal is asserted rather than issued, the two rules that look alike and are not (sealsBackend vs drainInitiated), and the single-point argument that replaced "one guard for six states"
metadata:
  type: project
---

Effects on a **third party that outlives an abort** cannot be issued as events.
That is the whole design of the proxy side of the drain, ruled before
implementation and confirmed by the tests.

## The seal is asserted every pass, and nobody ever un-seals

There is no unseal operation, on the wire or in `:core`. `PUT
/v1/backends/{name}` states registration *and* admission, and it is called on
every pass of every state that depends on it. An abort restores joins by simply
not asserting a seal any more; a proxy restart is repaired by the next
assertion. Neither needs an edge somebody has to remember to write.

**The proxy's own reconcile pass is the load-bearing half**, not the backend's.
A *permanent* drain abort freezes the backend's status and
`isBlockedByPermanentFailure` stops its passes entirely — so only the proxy's
sweep can restore joins to it. Without the sweep the failure mode is a server
that is running, invisible and unreachable, for ever.

**Deregistration is the one step that cannot be level-triggered** — it is the
last thing before the stop, so "assert it every pass" would mean asserting it
from states that must not reach it. It gets an explicit re-registration edge on
the abort path out of `DEREGISTERED`, in `abort`/`blocked`. A `NodeException`
escaping the controller skips that edge, which is why the stop is now caught
inside `DrainController` and turned into an abort.

## Two rules that look alike and answer different questions

- `DrainState.sealsBackend()` — *is this drain holding the backend out of
  routing.* False for `DRAIN_FAILED`: a drain that has stopped advancing is not
  going to move those players.
- `PaperServerStatus.drainInitiated` — *may this server receive somebody else's
  players.* True for `DRAIN_FAILED`, because a server parked on a retryable
  abort will try to stop again.

Using `drainInitiated` for the seal was a real defect the tests caught: a parked
backend stayed sealed for ever. They are opposite answers about the same state
and the plausible-looking substitution is wrong in both directions.

## The single-point argument that replaced "one guard for six states"

The old argument for wrapping every state in `requireEmpty` was that there was
one thing to audit. What replaces it is narrower, not looser:

> **No path reaches `Node.stopWorkload` except through `requireEmpty` followed by
> `mayStop`.** Steps 2–4 have no stop call and no edge to `STOPPING` that does
> not pass through `SAVING`, so they cannot lose data however wrong they are;
> `stop()` re-asserts `mayStop` itself as a backstop.

## The gate is the workload's own SLP, and a proxy count is never a decision

`backends[].players` cannot see a client connected straight to the backend port,
and whether backends are firewalled is a deployment property this code cannot
assert. It is corroboration and a log line. The trap is subtler than reading it
at the gate: **reporting `Progressed` from a sweep's `remaining`** resets the
backoff on every pass of a drain getting nowhere, and prints "every player has
been moved" about a populated server. A sweep in flight is always `Waiting`.

## A limit on a drain step is a wedge unless the success exit precedes it

Two states the thirteenth audit found, both ending in a manual `crictl stop`:

- **The limit was consulted before the player count.** `startTransfer` went
  straight to the retry check, so once the budget was spent the resume ladder
  re-entered it on every pass *without ever reading whether anybody was still
  there*. The delete could not be completed by waiting for the last player to
  log off, by editing the definition (a drain record survives a generation
  bump), or by restarting the loop. **The general rule: the exit that means
  "this step succeeded" must sit above the exit that means "this step has tried
  enough".**
- **The bound counted passes.** With start-or-join, a repeat asks nobody to move
  again, so a six-sweep budget went in twelve seconds at a two-second poll and
  the documented `playerTransferTimeout` was unreachable. Counting *completed*
  sweeps fails identically when the proxy settles one instantly. The bound is
  now the clock and nothing else — a second bound that cannot bite is worse than
  no second bound — anchored on `sealRequestedAt`, because `enteredStateAt`
  restamps on every park-and-resume and hands the allowance back in full.

`transferAttempts` survives as a report. **A counter nothing gates on cannot
wedge anything**, which is the only reason it is safe to keep.

### A duration needs an anchor, and an anchor is a field that can be absent

The price of choosing a clock over a counter, and it cost three rounds. A counter
starts at zero by construction; an anchor can be missing, stale or restamped, and
each of those is a different wedge:

- `enteredStateAt` **restamps** on park-and-resume — allowance handed back every
  cycle, for ever.
- `sealRequestedAt` **can be absent**: it is written at one site with `holdSeal`
  above it, and nothing re-enters `DRAIN_REQUESTED`, so one blink of the control
  endpoint meant the drain never had an anchor at all. It was also **stamped too
  early** — everything between step 2 and step 4 spent step 4's budget, including
  an orchestrator restart, since it is persisted.

`DrainStatus.transferStartedAt` is the answer: stamped on entry to the step it
bounds, never cleared, and passed to `exhausted` as a **non-null argument** so
the function cannot look it up and cannot fall back. Three rules that generalise
to any anchored bound:

1. Stamp at the entry to the step being bounded, not at an earlier step that
   happens to have a convenient field.
2. No `?:` in the consumer. A missing anchor must **stamp**, never substitute —
   a fallback is what made two of these silent.
3. Treat it like `saveRequestedAt`: one load-bearing field, covered by a test
   that drives the path where the ordinary stamp does not happen.

### "Did the pass make progress" is not "did the pass avoid failing"

`resumeInto` cleared the recorded failure whenever the resumed state did not
itself re-abort. Once step 3 had a body, asking the `Scheduler` for a destination
satisfied that — so a drain whose transfers kept being refused wiped its own
failure every other pass: `attempts` pinned at 1, `occurredAt` restamped,
`escalates()` never true, and `queue.succeeded` on the `Progressed` held the
backoff at the poll interval.

**Re-deriving state is not progress.** Nothing left the process and the drain
knows exactly what it knew before, so how long it has been failing is still true
and must survive. `DrainProgress.derivedOnly` marks the one step that qualifies,
and a resume that only re-derived neither clears the failure nor reports
progress. The level-triggered seal is deliberately *not* covered by that flag —
it runs every pass by design and would make it always false.

## The address had two derivations, and they disagreed for the window that matters

The proxy sweep read `status.endpoint.address` (written by `awaitJoinable`,
cleared by `teardown`) and fell back to the *server name*; the drain read the
node. A proxy pass landing while a backend was `Absent`/`CREATING`/`STARTING`
registered it at a hostname that does not resolve, and because `ADDRESS_CONFLICT`
is deliberately not an upsert, drain step 2 then aborted every pass **for ever**
— the only thing that clears a wrong registration is the `DELETE` that step 2
never reaches.

This was the fourth one-fact-two-derivations bug in a single session
(`saveRequestedAt`/`worldSaved`, `passFailure` across two modules, `drainBlocked`
condition-versus-field, and this). **When a value is asserted to a third party
from more than one call site, make it one function before writing the second
site.** And prefer *not asserting* over asserting a guessed value: "not
registered yet" is a state a protocol handles; "registered wrongly" often is not.

## Rulings I made that a human may overrule

1. **`DRAIN_FAILED` unseals.** A blocked-with-a-proxy drain therefore takes new
   players while parked. The alternative is an invisible running server, which
   the audit named as the harm.
2. **More than one proxy claiming a backend is a retryable failure on the
   *backend's* status**, and the container is not created while it holds. The
   refusal is **exempt for a terminating definition** — it returns before
   placement, so without the exemption such a backend was permanently
   undeletable, which is the state that produces a manual stop. A conflicted
   delete drains with no binding at all, so it blocks on players rather than
   transferring them.
3. **The proxy sweep deregisters registrations whose selector no longer
   matches.** That is step 6 performed by a sweep, and it is safe only because
   the plugin refuses `DELETE` with `BACKEND_OCCUPIED` and has no force flag.
   `wanted` must therefore be matched names **plus `ServerListing.unreadable`
   names**: dropping the unreadable half keeps `listAll`'s tolerance and throws
   away the part that made it safe, turning "this build cannot describe that
   server" into a destructive call against a live backend. A NULL-named row
   cannot be matched to a registration and is still swept — named, not fixed.
4. **At the transfer limit the abort is `RETRYABLE` and escalates by the ordinary
   threshold**, with no exemption added to `escalates()`. Adding one is the
   mechanism that produced two earlier audit findings.

See [[standalone-drain-decision]] for what this supersedes and
[[blocked-is-not-failed]] for the failure/block split it rests on.

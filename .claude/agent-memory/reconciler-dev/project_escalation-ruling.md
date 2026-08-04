---
name: escalation-ruling
description: The drain-auditor's ruling on where failure-modes item 7 stops — you may escalate what a stuck drain *reports*, never what happens to its container
metadata:
  type: project
---

`drain-auditor` ruled on this after I asked rather than guessed, and endorsed
asking: **item 7 forbids changing what happens to the container at a limit
("you stop trying — you do not stop the container"). It says nothing about
changing what you report.**

Permitted at a counter or a time limit: the failure reason, a status condition,
a log level, a metric. Not permitted: changing the failure *class* in a way that
stops the drain retrying (that makes a container undeletable on a transient
fault), or touching the grace period, the stop, or the player count.

**Why:** the system had no way to say "this has been stuck for twenty minutes
and will not fix itself" — a wrong RCON password and a busy evening looked the
same on the dashboard for ever.

**How to apply:** the implementation is now `ConditionType.NEEDS_ATTENTION` in
`:schema`, derived in `StatusDrafting` from the drain's recorded failure via the
single rule `escalates()` in `DrainController.kt`. The old `[needs attention]`
message marker is gone.

## The flag answers "must a person act", not "is something wrong"

I got this backwards once and it shipped: `escalates()` required
`FailureClass.RETRYABLE`, so a **permanent** drain abort never raised the flag.
My reasoning was that a permanent failure is already surfaced as permanent, so
the flag added nothing — wrong question. The failure says something is wrong; the
flag says *the loop has stopped and only a person can move this*, which is the
definition of a permanent abort. It left exactly the unconfirmable-save and
never-re-sent-`DRAIN_SAVE_TIMEOUT` states unflagged, and it was caught only by
pointing a real dashboard at a real orchestrator.

One consequence worth keeping:

- **A permanent failure escalates immediately, and must.**
  `isBlockedByPermanentFailure` returns before a non-terminating server is
  observed and writes no status, so a permanent abort can be the last status ever
  written. A threshold not crossed by then is never re-evaluated — a timer would
  not delay the signal, it would delete it.

## The exempt reason is gone, and so is the whole mechanism

**Superseded 2026-08-04.** `escalates()` used to take a `FailureReason` and
return false for the players-online one. Two rounds of patching that exemption —
ordering it before the class check, then making a permanent
`DRAIN_NO_DESTINATION` unconstructable so the premise held — both survive only as
history. The exemption is deleted; `escalates` takes no reason at all.

What replaced it: a drain waiting on players records `DrainStatus.blocked` and
**no failure**, and `escalated()` was already false when `failure == null`. The
special case did not need a better guard, it needed the state it guarded to stop
existing. That is the move worth generalising — **if a rule needs an exception
for a case that is not really an instance of it, the case is mis-modelled.**
Recording "everything is fine" as a failure was the actual defect; the exemption
was a symptom being maintained.

`FailureStatus.init` still refuses `DRAIN_NO_DESTINATION` + `PERMANENT`, but it
now stands on the capacity case alone: *stop trying* buys nothing when what you
are blocked on is not a property of this server. The general lesson from that
round still holds where it applies — forbid an impossible combination at the type
rather than defending the order you check things in, and it costs nothing in
`:store` because a hand-edited row becomes `StoreException.Corrupt`. Do not add a
second `require` to a decoded type, though: every one is paid by the widest fleet
read, which is why `DrainStatus` does *not* enforce blocked/failure disjointness.

Related: the prose has to branch too. Telling an operator "the loop keeps
retrying" about a drain that has permanently stopped is how they wait instead of
acting — and the permanent text must not assert *joinability* either, since one
of the aborts it covers is reached precisely because a probe could not answer.

The same trap caught the *blocked* prose one level up in `:api`. `detail()`
matched `TERMINATING && drain != null` before anything else, so a delete on a
server people were playing on rendered as "delete requested; draining (drain
failed)" — the exact question the new flag answers, answered wrongly. A new
branch has to be inserted ahead of every existing branch it is a special case of,
not merely added; only the `:app` end-to-end test saw it.

## Known-and-accepted, so they are not re-found

Ruled cosmetic by the eighth audit, no fix required:

- A stale permanent drain failure rides into the teardown status — the
  container-is-down branch carries `drain.failure` untouched, so a terminating
  server whose container exits on its own shows one or two passes of `STOPPED`
  plus the flag. Self-limiting: the next pass observes `Absent` and purges.

Out of scope but the cases to design against **if the flag is ever generalised
from the drain record to `status.failure`** — all three are permanent, gate the
loop, and today show a badge with no flag beside it: `forbiddenTransition`
(phase `RUNNING`, `drain = null`), `refusePlacement` with `PINNED_NODE_UNKNOWN`,
and a rejected definition. None is an *inverted* badge, which is why they stayed
out; a generalisation has to handle all of them at once or it will look
arbitrary.

Two traps found while implementing it, both worth re-checking after any change
to the loop's requeue policy:

- **Measure the counter before trusting it.** The escalation would have been
  timing a two-second hot loop: a drain's resume step returned `Progressed`,
  which makes `ReconcileLoop` call `queue.succeeded` and reset the attempt
  counter, so the backoff never applied. Only outcomes that reflect real work
  may reset it.
- A resume that has not done anything is not progress. It now runs the state it
  resumes into in the same pass.

See [[drain-against-the-container]] and [[standalone-drain-decision]].

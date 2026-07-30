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

Two consequences worth keeping:

- **A permanent failure escalates immediately, and must.**
  `isBlockedByPermanentFailure` returns before a non-terminating server is
  observed and writes no status, so a permanent abort can be the last status ever
  written. A threshold not crossed by then is never re-evaluated — a timer would
  not delay the signal, it would delete it.
- **Order the exclusion before the class check.** `DRAIN_NO_DESTINATION` must
  never escalate; with the class tested first, a future permanent classification
  at either call site would route players-being-online straight back in.

Related: the prose has to branch too. Telling an operator "the loop keeps
retrying" about a drain that has permanently stopped is how they wait instead of
acting.

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

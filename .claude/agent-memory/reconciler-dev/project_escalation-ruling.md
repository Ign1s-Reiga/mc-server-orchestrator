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

**How to apply:** `ReconcilerConfig.drainAttentionAfter` and the `ATTENTION`
marker in `DrainController.kt` are the current implementation, and they are a
stand-in: the right home is a `NEEDS_ATTENTION` `ConditionType` or
`FailureReason` in `:schema`, which was out of scope when this landed. If you
are in `:schema` for another reason, add it and move the marker onto it.

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

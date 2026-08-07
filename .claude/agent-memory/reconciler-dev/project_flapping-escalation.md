---
name: flapping-escalation
description: The flapping item under-reports rather than over-reports; the ledger that closed it, why the decrement must be symmetric, why the crossover sits at exactly half, and the three neutral-pass traps the red-proof found
metadata:
  type: project
---

The long-standing open item on `DrainController.blocked`. **Closed 2026-08-08**
by `DrainStatus.faultLedger` (option 2 below), on the user's ruling. What follows
is the diagnosis, then the design decisions that are not obvious from the code.

## The direction is under-reporting, and a brief said the opposite

I was handed it as *"the anchor set by one transient abort survives hours of
healthy waiting, so a recovered endpoint reports `NEEDS_ATTENTION` at
`attentionAfter` as though it had been failing the whole time"*. That is the
**counter-scenario of the declined `troubleSince` design** (see
[[gate-and-ceiling]]), not the behaviour of the code. It reads as a defect report
because a declined remedy's cost and a live defect are written in the same voice.

What the code does, measured rather than argued: a scratch test drove four hours
of a control endpoint failing every other pass — `failAlways(EXEC)` → pass →
`clearFailures(EXEC)` with three players on → pass, at ten-minute steps, twelve
times. `NEEDS_ATTENTION` was **FALSE at every one of the twenty-five
observations**, and the failure read `DRAIN_STALLED/RETRYABLE@<now>x1` on every
fault pass: a fresh anchor and `attempts` pinned at 1, for ever.

The mechanism is not `settleRecords` at all. It is `blocked()`:
`standing = drain.failure?.takeIf { it.failureClass == PERMANENT }` and then
`copy(failure = standing)`, so **a block deletes a retryable failure outright**.
`settleRecords`' early return in `DRAIN_FAILED` is what stops it *re-adding* one,
which is correct. `abort` also does `copy(blocked = null)`, so the block's own
`observations` counter resets on every fault and cannot serve as a ledger without
a change.

The control that proves the machinery is sound where it applies: the same scratch
run with **no players** (so no block — `DRAIN_FAILED` ↔ `DEREGISTERED` on a
container that will not exit) carried one anchor from 10:09 through
`attempts = 1..7` and fired `NEEDS_ATTENTION` at 12:20. A *continuously* failing
drain escalates correctly today. Only the intermittent one is invisible.

## The question the fix has to answer

*What evidence retires a retryable failure that a block would otherwise carry?*
Deleting it on the first clean pass is what produces the hole; keeping it makes a
healthy wait report a fault that has genuinely gone, and then escalate on it at
fifteen minutes. **A single instant cannot tell the two apart** — that is the
`troubleSince` finding and it still stands.

Three options, in the order I would argue them:

1. **Stop deleting it in `blocked` and let `settleRecords` clear it.** One line.
   It is the wrong-way narrowing the KDoc names: `DRAIN_BLOCKED` is derived from
   `blocked != null && failure == null`, so every hiccup costs the *"waiting,
   needs nobody"* reading for the rest of the block, and `escalated()` then fires
   on a fault that is over. Reject.
2. **A ledger paid down by clean passes.** `DrainStatus` gains an `Int` that
   `abort`/`noteFailure` increments and every non-failing pass — block included —
   **decrements rather than zeroes**. A fault present under half the time trends
   to zero and never fires; one present over half grows without bound and always
   does. The fifty-percent crossover is the whole policy and it is legible.
   Costs: a `:schema` field, a `:store` codec entry and a migration, one
   threshold constant.
3. **Escalate on the block.** Dead end, and worth one line so it is not
   re-proposed: a block is quiet *by design* (a busy evening is not an alert), and
   the whole reason the reason-keyed exemption was deleted was that a blocked
   drain records no failure to escalate from.

## The latency question, answered

Asked whether a fix can avoid making a *genuinely* failing endpoint escalate
later. **It can, and only if the new rule is a disjunct**: keep
`escalates(occurredAt, class, now, after)` exactly as it is and raise the flag on
`escalates(…) || ledger >= N`. Nothing that fires today fires later, provably,
because the existing arm is untouched. Replacing the time arm with a count arm
does not have that property — a count threshold and a grown backoff are in
different units, and at a five-minute backoff a count of N is N×5 minutes.

The cost moves to false positives, and the decrement is what bounds it: without
decay, a server that hiccups N times across a week eventually crosses the
threshold with nothing wrong.

## What the build settled that the design could not

**The asymmetric decrement I nearly proposed is wrong, and the reason is
`blocked`'s own park.** `settleRecords`' hysteresis — *one good pass does not
clear a failure; the pass after it does* — reads like the obvious precedent for
"a *resuming* pass may not pay the ledger down", and it would make the perfectly
alternating case escalate. It cannot be used: **a block parks in `DRAIN_FAILED`,
so every pass of a long healthy block is a resume.** The ledger would then never
decay at all, one fault would stick for ever, and six unrelated hiccups across a
week would page somebody. Traced and dropped before writing it.

That is what leaves the crossover at exactly one half, and the exact alternation
unreported. It is pinned as a test rather than hidden, because it is the case a
later reader will "fix" by shrinking the decrement — which moves the threshold
somewhere no sentence describes. The saving grace is real and worth repeating:
against a floor at zero a driftless walk has unbounded excursions, so a *genuine*
one-in-two fault arrives eventually; only a metronome never does.

**Deriving the threshold rather than picking it.** Both arms of a flapping drain
requeue as `Retry`, so `WorkQueue`'s counter never resets and the backoff settles
at its 5-minute cap. At that cadence `drainAttentionAfter` is **three consecutive
faulting passes** — so six keeps the age arm binding on every continuous fault
and leaves this arm to the cases the age arm cannot see at all. Anchoring one
threshold on another in the unit they actually share (passes) is what made the
number defensible instead of round.

## Three neutral-pass traps, all found by the red-proof and none by review

1. **An `UNKNOWN` observation never reaches the funnel.** `advance` answers it
   before any step runs, so a test built on it asserts a true property through a
   path that bypasses the code implementing it. The mutation reddened nothing.
   The reachable, repeatable neutral pass is **`STOPPING` inside the grace
   period**.
2. **"A failure is standing" is not "this pass recorded one".** `awaitStopped`
   carries a failure forward untouched, so an increment keyed on presence climbs
   once per poll on a drain that is behaving. Reaching it needs a *refused then
   repaired* stop — two faults, then the standing failure outliving them.
3. **A ledger built before real work is paid down by that work.** A scenario that
   needs a non-zero ledger at `STOPPING` has to over-build it first: the save,
   the deregistration and the stop each honestly subtract one, and a ledger that
   hits the floor on the way hides the property behind `coerceAtLeast(0)`.

## The options as they were put, for the record

1. **Stop deleting the failure in `blocked`.** One line, and the wrong-way
   narrowing the KDoc names: every hiccup costs the *"waiting, needs nobody"*
   reading and then escalates on a fault that is over. Rejected.
2. **The ledger.** Built.
3. **Escalate on the block.** Dead end: a block is quiet by design, and the
   reason the reason-keyed exemption could be deleted is that a blocked drain
   records no failure to escalate from.

**The latency question, and the answer that made it cheap:** a fix can avoid
making a genuinely-failing endpoint escalate later **only if the new rule is a
disjunct**. `escalates(...) || ledger >= N` leaves the existing arm untouched, so
the guarantee is structural rather than argued. Replacing the time arm with a
count arm does not have that property — a count and a grown backoff are in
different units. That sentence is in the code at the disjunct, because "simplify
this into one predicate" is exactly what a later reader will try.

See [[gate-and-ceiling]] for why `troubleSince` was declined and
[[blocked-is-not-failed]] for the three states a consumer must tell apart.

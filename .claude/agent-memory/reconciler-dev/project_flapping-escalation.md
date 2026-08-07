---
name: flapping-escalation
description: The flapping-control-endpoint item under-reports (a fault present half the time never escalates) — it does not over-report; the measurement that settles it, and the three fixes with the one that costs no added latency
metadata:
  type: project
---

The long-standing open item on `DrainController.blocked`. **Still open as of
2026-08-07** — brought back with options rather than a fix, because the fix is a
`:schema` + `:store` change and a policy nobody has calibrated.

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

See [[gate-and-ceiling]] for why `troubleSince` was declined and
[[blocked-is-not-failed]] for the three states a consumer must tell apart.

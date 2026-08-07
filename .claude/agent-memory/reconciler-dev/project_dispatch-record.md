---
name: dispatch-record
description: Round 32's must-fix — why "a stop request left this process" became a field rather than either proxy, why the stamp inverts the save record's rule, the Rejected ruling I took past the brief, and the two bound-populations SpecBounds emptied
metadata:
  type: project
---

`DrainStatus.stopDispatchedAt` exists because a compensating edge needed a fact
the status did not carry. `restoreRegistration` re-registered a backend
**admitting** on every park out of `DEREGISTERED`, including parks that follow a
container stop — a player then lands on a process whose shutdown save has already
run and loses that session.

## Neither proxy is right, and the disjunction is still short

- `state == STOPPING` misses `stop`'s own `Timeout` catch: the request went out and
  the drain is still `DEREGISTERED`.
- `failure is Timeout` misses `awaitStopped`'s re-issue catch: a plain `Rejected`
  there still follows the *first* stop that returned, which is the only thing that
  puts a drain in `STOPPING`.
- Their **disjunction** is right at both catches and still not the fact. The lap
  through `goingRoundInCircles` returns to `SAVING` carrying a dispatched stop, so
  the block a populated server hits next has neither clause true. That third case
  is what decided the field over the one-line fix.

**The shape to recognise:** two proposed discriminators, each wrong at a different
call site, means both are proxies for a fact nobody records. Record it.

## The stamp is written *before* the call, opposite to `saveRequestedAt`

Because the two records have opposite purposes. A save record exists to stop a
second send, so it must not be written for a request that never left. This one
exists to tell a later pass there is something **not** to reverse, so losing it
errs toward re-admitting — the direction to design against. Set once, never
cleared, and carried under `sideEffectIssued` so a cancelled pass cannot lose it.

The ordering is the whole content of the field and is invisible to a reviewer
reading two lines, so it is pinned structurally (`every container stop records the
dispatch before it issues one`) as well as behaviourally.

**"Never cleared" was half the sentence and the missing half was the next
critical.** Round 33: the record was cleared the way every other field here is —
by the whole object going when the drain stops being *wanted* — and that deleted
it while the `SIGTERM` was still inside the container. It has a lifetime of its
own now (`stopIsInFlight` / `clearedDrainRecord`); see [[record-lifetime]] before
reading anything below as current.

## The ruling I took past the brief, and it is open to overruling

**A `Rejected` from the stop no longer restores the registration.** The auditor's
disjunction would have restored there; the pre-RPC stamp does not. The reason is
that `NodeException.Rejected` cannot carry "nothing was signalled": `LocalNode`
uses it both for a grace period it refused before calling anything *and* as the
catch-all for a failure its translator did not classify.

The cost is availability, and it is the reason to revisit: a stop refused on a bad
`stopGracePeriod` strands the backend out of routing until the operator fixes the
row — with a permanent failure on the status telling them to. What would reclaim
it is a fact only an implementation knows, so it belongs on `NodeException` (a
"this node refused to issue it, nothing was sent" property, defaulting false).
Costed and not taken: the population is one `SpecBounds` already clamps.

`a drain that aborts after deregistering re-registers the backend` was rewritten
rather than deleted — its failure is an unanswered probe now, a genuine "before any
stop" abort — with `stops` and `stopDispatchedAt` asserted beside the registration
so the premise cannot move unnoticed.

## Two bounds whose stated population was emptied in another module

`SpecBounds.bound` runs at `DefinitionCodec.decode`, and `Reconciler` acts on
nothing else — every `Pass` takes its definition from the store. So for everything
the loop stops, `stopGracePeriod <= 2h` and `saveTimeout <= 1h`, which means
**neither `StopGraceCeiling` nor `:cri`'s `stopDeadlineCap` can fire**. Two KDocs
described those populations as reachable; both are corrected in place, kept as
second lines of defence, with what would re-open them written down (a `Node` caller
that is not `Reconciler`, a store that decodes without the clamp, a raised reader
cap).

The lesson generalises past these fields: **a reachability argument that rests on
another module's decode expires when that module changes**, and it changed in the
same round the argument was written. See [[deadline-ceilings]] for the rounds this
sits on top of and [[invariants-need-an-enforcement-point]] for the rule the field
is an instance of.

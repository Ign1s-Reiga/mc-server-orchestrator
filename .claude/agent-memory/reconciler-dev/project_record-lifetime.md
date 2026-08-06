---
name: record-lifetime
description: Round 33's critical — a compensation record whose value was right and whose lifetime was inherited; the rule that replaced it, the second defect the fix itself created, which sites the mutation set proved unreachable, and the workload-owned record still open
metadata:
  type: project
---

`DrainStatus.stopDispatchedAt` ([[dispatch-record]]) was correct and was deleted
by the ordinary converging pass. Reverting an edit is the documented way to
withdraw a `REPLACEMENT`; taken between step 7 and the container's exit it made
`drainCause` answer null, the pass converged, and `awaitJoinable` wrote
`drain = null` on **every** branch — a container inside its grace period answers a
ping right up to the moment it stops. The proxy's sweep then read a backend with
no record, put it back **admitting**, and players landed on a process whose
`savePlayers` had already run. Up to `stopGracePeriod` of sessions, gone.

**The shape to carry:** every other field on `DrainStatus` describes the drain and
dies with it; this one describes an act *outside* the drain, in a container the
store cannot recall, and it inherited a lifetime built for facts that expire when
the drain stops being wanted. The audit's rule for future compensation records is
**"who deletes the object it lives in"**, not "who reads it" — and the answer has
now been `Reconciler.converge` twice in two rounds, once as the fix and once as
the defect.

## The rule, and the two-question split

`stopIsInFlight(drain, observation)` — a stamp **and** an observation of the
container it was aimed at. Three consumers: routing (`outstandingStopCause` keeps
both kinds draining when the cause is withdrawn but the stop is not),
`clearedDrainRecord` at every site that retires a record, and nothing else.

> **"No longer wanted" and "the `SIGTERM` is already out" are different questions,
> and only the first is the operator's to answer.**

The withdrawal is honoured one step later rather than ignored: the drain finishes
what it signalled, `teardown` removes it, the create after that applies the
reverted definition. That is now the true version of [[replacement-exit]]'s
*"edit it back and the login path reopens on the next converge pass"*.

## The fix's own defect, caught by an existing test

The prescribed remedy — make the clears conditional — **retained a record past the
container it named**. On the proxy path the drain record was inherited across the
create (`convergeProxy` passed no `drain` at all, unlike `converge`), so the next
pass saw a stamp beside a brand-new container, routed to the drain, and stopped
the replacement it had just built. For ever. `a proxy at zero players whose
endpoint is dead can still be replaced` went red on a *create count* of three.

Two things fixed it and both are load-bearing: `CREATED` and `SANDBOX_ONLY` are
excluded from `stopIsInFlight` (a stop is only ever dispatched against a container
this loop started and probed), and the proxy's creates clear the record the way
the Paper path always did. **A conditional retention needs an expiry that is a
fact about the world, not about the condition.**

## What the mutation set proved, and what it proved is unreachable

D52–D58D, 58 → 67 entries. Two results worth more than the greens:

- **D56/D57 redden the shape and no scenario.** Behind the routing guard,
  `converge`/`awaitJoinable`/`awaitProxyReady` are not reachable with a stop in
  flight, so those clears are defence in depth and `DrainWiringTest` is the only
  instrument that can see them. That is the answer to "why a structural test as
  well": eight lines in one file wrote the token, on paths with nothing in common.
- **D52 has no behavioural cover left**, because the create-side clear now fires
  one step earlier. The clause is real and only the unit test can see it.

## The site the audit did not name

`forbiddenTransition` — the `storage.mode` persistent→ephemeral refusal — also
wrote `drain = null`, and a refusal is *not* a withdrawal, so no routing guard
stands in front of it. An edit landing inside the grace period of a stop some
earlier edit asked for deleted the record and re-admitted. Verified live: the
scenario reddens on the wire assertion against pre-fix source. The refusal itself
is untouched.

**Residual, escalated rather than fixed:** with the record retained, that refusal
no longer freezes the server (the drain record is not `parkedOnTheFailure`), so
the signalled container exits, the drain finishes, and the create applies the
ephemeral definition. No world is lost — an ephemeral workload mounts nothing, so
the volume's files stay on the host — but an edit the loop spent several passes
refusing does end up applied. That end state is a ruling for `drain-auditor`.

## Open: whether the record should belong to the workload

The audit's structural read is that an irreversible external act needs a record
whose owner is the *workload*, retired when the container is confirmed gone and
touched by nothing else. What this round did instead is give the field a rule with
one enforcement point while leaving it on `DrainStatus`. The cost of going further
is a `:schema` + `:store` migration and a second object every consumer has to
learn; the benefit is that the lifetime stops being a property of eleven call
sites. Do not start it without a human — and note that `stopIsInFlight` would be
the same predicate either way, so the work is a move rather than a redesign.

See [[dispatch-record]] for why the field exists at all,
[[level-triggered-seal]] for the sweep this protects the loop from, and
[[audit-remedies-are-hypotheses]] for the round's own instance of that rule.

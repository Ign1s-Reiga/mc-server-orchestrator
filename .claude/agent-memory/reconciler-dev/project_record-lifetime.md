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

**The residual I escalated came back as round 34's second critical** — ruled not
acceptable, and fixed by making the refusal outlive the container rather than by
touching the retention. See [[guard-lifetime]].

## The workload-owned record: **ruled, do not start it**

The structural read was that an irreversible external act needs a record owned by
the *workload*. Round 34 declined it, and the reasons are worth keeping because
they are about when a move is the wrong fix:

1. The live defect was **one missing argument** to an existing function, not a
   lifetime in the wrong place.
2. The only *exact* version — keyed on the container id — **could not be
   exercised**: `FakeNode` named every container after the server, so ids were
   identical across recreations and an identity test would be true by
   construction, reporting the record correctly retired in exactly the scenario
   where it outlived its container. (Fixed since: ids are per-create.)
3. Moving a field while its classification is wrong moves the defect.

The sequence to follow if it is revisited: fix the classification, give the fake
per-create ids, add a scenario where the node reports `SANDBOX_ONLY` with a live
container mid-stop — **all three are now done** — then ask again whether the move
buys anything. The audit's expectation, and mine, is that it does not.

## Round 34: the rule was one argument short

`stopIsInFlight` classified `SANDBOX_ONLY` as "not the signalled container",
unconditionally — and that observation is two worlds: a container never created,
and a live one `ListContainers` has stopped returning. `containerIsDown` reads the
**identical** observation, splits them on `hadContainer`, and says why in its own
comment. This rule was the only one in the module that trusted the state, and it
was the one written that round. The fix is `SANDBOX_ONLY -> hadContainer`, from a
`Pass.hadContainer` property both rules now read. See [[guard-lifetime]] for the
sibling critical and for what the instruments could and could not have caught.

**The shape:** when a new rule answers a question an existing rule already
answers, the check is a hand comparison of the two, not another test. Ours
disagreed for a round.

See [[dispatch-record]] for why the field exists at all,
[[level-triggered-seal]] for the sweep this protects the loop from, and
[[audit-remedies-are-hypotheses]] for the round's own instance of that rule.

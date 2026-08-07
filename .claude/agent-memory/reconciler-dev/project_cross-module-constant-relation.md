---
name: cross-module-constant-relation
description: The thirty-eighth audit's item 1 — a loop whose termination rested on four constants in three modules at equality, why the check is a test rather than the `require` the precedent suggests, why the "outer" constant's mutation is the only informative one, and the finding that it decides both stop sites
metadata:
  type: project
---

`DrainController` re-issues a stop with the **same** grace period (`failure-modes.md`
item 7). A re-issue does not re-deliver the signal — containerd CASes a per-container
flag — so all it supplies is a fresh grace period on a fresh deadline, and it reaches
`SIGKILL` only when **its own grace period expires before its own deadline**.
`GrpcCriClient` sets that deadline to `min(gracePeriod, stopDeadlineCap) + slack`.
Above the cap the two are ordered the wrong way *by construction*: every attempt ends
exactly as the first did, for ever, and no retry count reaches it.

Nothing was checking the ordering. It held on four constants in three modules meeting
**at equality against a strict `>`** — `StopGraceCeiling.MAX`,
`PaperServerDefaults.MAX_STOP_GRACE_PERIOD` it borrows from,
`SpecBounds.MAX_SAVE_TIMEOUT` (in via the ceiling's *floor*), and
`CriTimeouts.stopDeadlineCap`, whose own KDoc says `:cri` cannot see `:schema`'s cap
and deliberately does not depend on it.

**Why:** this is [[invariants-need-an-enforcement-point]]'s borrowed constant one
level out — the two ends are in modules that by design do not know about each other,
so neither can hold the relation. `:core` depends on both and is the only place they
are visible together.

**How to apply:**

- **A `require` was the precedent and the seam refused it.** `SpecBounds.init` binds
  its own two borrowed constants exactly this way, but the far side here is a `:cri`
  type and `StopGraceCeiling` lives in `Node.kt`. `:cri` is an `implementation`
  dependency precisely so `LocalNode` is the only class in `:core` naming a CRI type;
  a `require` there would make the *seam's* policy ceiling a statement about one
  runtime's transport config. The rule that came out of it: **when the enforcement
  point the precedent suggests would put the wrong module's type in the seam, the
  test is the enforcement point, and the reason goes where the `require` would have
  gone.** See [[deadline-ceilings]] for the round that established which bound
  belongs to which layer.
- **The informative mutation is the one on the constant nothing else names.**
  Lowering `stopDeadlineCap` reddened the new test *and nothing else in 954*.
  Raising the schema's grace ceiling reddened it plus two tests that assert the
  constant's **value** — which reads like coverage and is not, because somebody
  deliberately raising the ceiling edits those two as part of the change. Score a
  relation's pin by whether the tests it shares a mutation with could be satisfied by
  editing them.
- **A derived assertion needs a mutation the primary survives, or it is a restatement.**
  The floor-excursion assertion could only be isolated by *decoupling `SpecBounds`'
  two borrows* (grace ceiling to 3h, save ceiling to 2h) — while both come from
  `PaperServerDefaults`, `SpecBounds.init` already forbids the pair that would break
  it. Under that, assertion one is green and assertion two red. If no such mutation
  exists, the extra assertion is prose.
- **Pin the premise that makes reading a default honest.** The relation is asserted
  against `CriTimeouts()`, which stands for what ships only while nothing in `:core`
  configures the type. A scan for the token over `:core`'s main sources, comments and
  string literals stripped, plus a vacuity control that `CriClientConfig(` *is* found.
  Its red-proof used the **fully qualified** spelling with no import — an
  import-keyed scan would have been green on it; see
  [[invariants-need-an-enforcement-point]]'s "a source scan's holes".
- **The finding the trace turned up, which the audit had not.** `DrainController.stop`
  reaches the same inequality **first**: its call times out, its catch aborts as
  retryable, the next pass comes back into the same call. `awaitStopped`'s re-issue
  is reached only where the first stop returned cleanly. Both spin, and each is
  correct in isolation, which is why nothing reported it. **When told a loop is at
  site B, check site A calls the same thing with the same value.**
- **Population empty and the residual is a ruling.** `SpecBounds` bounds both halves
  at the decode and `Reconciler` acts on nothing else, so nothing the loop presents
  is over the cap. `StopGrace.of(31.days, 30.days)` is still constructible and is
  exercised by a green test. If it ever fires no world is lost — the save is
  confirmed before step 7, `stopDispatchedAt` keeps the backend out of routing, the
  abort is retryable, `NEEDS_ATTENTION` raises — the cost is a container nobody can
  retire without `crictl`. **Do not shorten the re-issue's grace period** (item 7),
  and do not cap this ceiling at `:cri`'s number either: that clamps one half of a
  validated pair by a rule that cannot see the other half, which is round 30's
  critical.

Item 2 of the same round was the retired premise *"It is finished by the drain
re-issuing the stop"* — see [[prove-the-test-can-fail]]'s rule about sweeping the
claim rather than the identifier. It had been borrowed by **three** `:core` sentences,
not one, in the round that corrected it in `:cri`.

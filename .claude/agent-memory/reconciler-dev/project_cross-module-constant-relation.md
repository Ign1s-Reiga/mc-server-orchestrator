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

- **"The seam refuses a `require`" was right; "so it is a test" was wrong.**
  `StopGraceCeiling` lives in `Node.kt`, and a `:cri` type named there would make the
  seam's policy ceiling a statement about one runtime's transport config — the audit
  accepted all of that. What it did not accept was the conclusion, because
  `LocalNode.open` is the class `:core` already permits to name CRI types *and* the
  one holding the `CriClientConfig` it just built. The `require` went there, against
  `criConfig.timeouts.stopDeadlineCap` — the value the process actually runs on,
  rather than `CriTimeouts()`. **When a check cannot go where the invariant is
  declared, the next question is which layer holds the real value, not whether to
  demote it to a test.** Both survive: the test pins the constants, the `require`
  pins the deployment. See [[deadline-ceilings]] for which bound belongs to which
  layer, and [[invariants-need-an-enforcement-point]] on where a `require` may throw
  — wiring time is safe, a definition-driven `require` is not.
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
- **Pin the premise that makes reading a default honest — and remember `copy`.** The
  relation is asserted against `CriTimeouts()`, honest only while nothing in `:core`
  configures the type. My scan banned the token `CriTimeouts`; both it and
  `CriClientConfig` are **data classes**, so
  `cfg.copy(timeouts = cfg.timeouts.copy(stopDeadlineCap = …))` configures the cap
  carrying neither token. *For any scan over a Kotlin type's configuration, ask what
  `copy` spells* — the field name belongs in the token set, not just the type name.
  Widening that far makes the `require` itself a hit, so the scan became a
  classification (a file naming either token either builds a `CriClientConfig` or is
  a finding) rather than a ban. Coarser than the call-site unit this repo prefers,
  and acceptable **only because the `require` is the enforcement point behind it** —
  which is the argument to write down instead of tightening the scan. Its red-proof
  used the fully qualified spelling with no import; see
  [[invariants-need-an-enforcement-point]]'s "a source scan's holes".
- **A comment-strip keyed on the line prefix reads a wrapped block-comment line as
  code**, so the scan can go spuriously red on prose — and a scan that cries wolf is
  a scan somebody deletes. Track `/* … */` depth instead, and blank string literals
  first so neither a `//` nor a `/*` inside one can open a comment.
- **The finding the trace turned up, which the audit had not.** `DrainController.stop`
  reaches the same inequality **first**: its call times out, its catch aborts as
  retryable, the next pass comes back into the same call. Both spin, and each is
  correct in isolation, which is why nothing reported it. **When told a loop is at
  site B, check site A calls the same thing with the same value.**
- **I then wrote "the re-issue is reached *only* where the first stop returned
  cleanly" into three places, and it is false.** `STOPPING` has two producers and the
  already-down branch reaches it from the *observation*, dispatching nothing — the
  code says so in bold twelve lines above itself. This is the identical premise
  [[producer-scan-scope]] exists to record, in the same function, found by the next
  audit. Nothing was lost (the re-issue gates and stamps for itself), but **a survey
  I had a memory file about still went in as a justification.** The rule that
  generalises: *before writing "X is the only producer of state S", run the producer
  scan rather than reading the one call path in front of you.* A fourth instance of
  the same claim was sitting in `restoreRegistration`'s KDoc from round 32 and is
  corrected in the same commit — the claim is older than my use of it.
- **The dead band starts at `stopDeadlineCap + deadlineSlack`, not at the cap.**
  `deadline = min(grace, cap) + slack`, so between the cap and the cap plus the slack
  the runtime's own wait still expires first and the kill still fires. "Above the cap
  … for ever" is wrong across thirty seconds. The guard is nevertheless written at
  the **bare cap**, deliberately: `deadlineSlack` is `:cri`'s to change and is a
  margin rather than a bound, so spending it would be depending on somebody else's
  headroom. *State the true threshold and then say which side of it the guard sits
  on, rather than letting the guard's value stand in for the mechanism.*
- **`:cri` states the band correctly and has since `46d0170`; there is nothing to
  fix there.** `CriClientConfig.kt` on `main` carries the carve-out in as many
  words — *"the condition is that the deadline expired first, and not merely that
  the grace period was over the cap: between the two lies a `deadlineSlack`-wide
  band where the deadline still outlasts the grace period and the kill is still
  reached, which is why `GrpcCriClient` tests the elapsed time rather than the
  cap"* — and `docs/operating.md` says the same for an operator. **`:core` was the
  only place that drifted.** My branch's prose was written against the *previous*
  KDoc, so after a rebase some of its pointers into `:cri` may want rewording even
  though the files do not overlap.
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

---
name: invariants-need-an-enforcement-point
description: An invariant held by every call site doing the right thing is not enforced — collapse it into one function whose return type carries it, and distrust any comment that maintains a count of call sites
metadata:
  type: feedback
---

When a safety rule is stated as "every place that does X also does Y", it has no
enforcement point and will be broken by the next place that does X. Make the
rule a **return type**: one function that performs X and hands back the result of
Y already applied, so a caller cannot obtain the first without the second.

**Why:** round 17's critical. "A positive player count voids the save
confirmation" lived in four branches of `DrainController`, each calling
`forgetSaveEvidence` in the same expression it read `probe.online`. It held. The
argument that it held was a KDoc sentence carrying a *maintained count of the
call sites*, and a fifth reader — a re-probe after a confirmed save — recorded
three players and voided nothing. Neither the sentence nor any test noticed,
because nothing anywhere asserted the rule as a rule. The fix was
`DrainStatus.readPlayers(probe, at): PlayerReading`, whose `Occupied` case
carries a drain already voided, plus a unit test of *the function* rather than of
a scenario — the defect was a caller nobody had thought of, and a scenario tests
one caller.

**How to apply:**

- **A comment that counts call sites is a defect waiting.** This project has now
  written that comment three times: once wrong about the set it claimed, once
  left correct-looking by a change that added a reader, and once ("it cannot
  fire") falsified by a branch that was there all along. If you are about to
  write "there are exactly N such places", that is the signal to remove the
  places instead.
- **Enforce the clause that is uniform; leave the ones that genuinely differ.**
  `readPlayers` decides the positive-count case and deliberately returns no drain
  for an unanswered probe, because the three callers disagree about silence for
  good reasons (one aborts, one tolerates it because a container inside its stop
  grace period is *expected* to go quiet, one keeps a confirmation it just
  earned). Folding those into one rule would be the same mistake pointing the
  other way. Say in the KDoc which half the type enforces.
- **A partial collapse is worth it.** One caller — the pass-entry occupancy in
  `advance` — takes the reading and declines the voided drain, because adopting
  it there would change which state the resume ladder lands in. That exception is
  visible in one expression with its reason beside it, which is the whole
  difference from four invisible ones.
- Reuse of a *constant* is the same hazard one level down: `evidenceGap` was
  reused as the bound on a whole re-save lap, and a lap contains a save the schema
  sizes at minutes. Before reusing a duration, say what quantity it measures and
  what the new one measures.

## A read-point rule cannot bind a step that reads nothing

Round 18, and it is the same defect one level out. `readPlayers` enforces "a
branch that **reads** a positive count is handed the voided drain". `holdSeal`
reads no count — and at `DEREGISTERED` it runs *before* the zero-player gate — so
the type never got the chance, and a proxy control endpoint that stopped
answering parked a drain still claiming a save a player had outlived.

**Find the point where the facts are written down together, not where one of them
is read.** The rule that actually held was "*recording* a drain in a pass whose
probe reported players implies voiding", and the pair lives on `DrainProgress`,
which the reconciler writes onto one status side by side. Two questions that
locate it: which type holds *both* facts, and which function is the single exit
every producer goes through. Here that was `DrainController.advance` — worth
splitting a function into `advance`/`advanceOnce` to create the exit if there
isn't one; it is cheaper than renaming a constructor parameter at twenty sites,
which was the other design I costed.

**A net and a fix are not the same thing even when they produce the same record.**
Both halves shipped, and either alone kept the regression test green — a sabotage
that changes nothing is a finding, so I traced it. The pass-entry adoption is what
makes every *decision* in the pass see a drain with no confirmation; the
record-point rule repairs what is *written*. Today nothing decides to stop before
the gate, so they agree — but no repair of a record can un-stop a container, and
the net's value as a defect signal (it logs at error) depends on the primary
keeping it unreachable. Say which is which in the test docstring, or the next
reader deletes the one that "does nothing".

## A borrowed constant carries the guarantees of the type it came from

`goingRoundInCircles` bounded a re-save lap with `stopGracePeriod` as a stand-in
for `saveTimeout`, on a schema guarantee that one exceeds the other. Two ways
that goes wrong, and both generalise:

- **The guarantee had a subject.** It is `PaperServer`'s (`SpecInvariants`);
  `ProxyLifecycleSpec` deliberately has none. The proxy case was safe only via an
  unwritten reachability argument, which is how round 17's exemption came to be
  widened.
- **The borrowed value was operator-tunable, in a range that changed the
  meaning.** Capped at two hours, so a long grace period set for an unrelated
  reason bought a two-hour escalation latency on a defect whose honest lap is a
  minute.

Before substituting quantity A for quantity B: name the subject that guarantees
the relation and check every implementation shares it, and ask what the bound
means at A's extremes. The fix was to put the real quantity on the interface —
`DrainSubject.saveTimeout`, `Duration.ZERO` for a workload with no world, which
is an answer about the subject rather than a placeholder needing a reachability
proof.

See [[localnode-test-gap]] for the sibling rule about decisions, and
[[prove-the-test-can-fail]] for why the unit test of the function was the one
that mattered.

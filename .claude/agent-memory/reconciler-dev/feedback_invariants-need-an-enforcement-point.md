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

See [[localnode-test-gap]] for the sibling rule about decisions, and
[[prove-the-test-can-fail]] for why the unit test of the function was the one
that mattered.

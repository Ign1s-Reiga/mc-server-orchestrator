---
name: producer-scan-scope
description: The thirty-seventh audit's instrument — how :core discovers which drain states a :schema decode rule keys on without listing them, why the obligation is discharged at the producer, what the scan reads and what it deliberately does not, and the half store-dev owns
metadata:
  type: project
---

A rule in `:schema` (`StatusReconstruction`) keys on `DrainState.STOPPING` and is
justified by *"a drain reaches `STOPPING` only after a stop request returned
cleanly"*. `:core` has **two** producers of that state and the author surveyed
one — `DrainController`'s already-down branch reaches it from the observation,
dispatching nothing — so the build routinely writes the document the rule says
cannot exist.

**Why:** no instrument was looking. There is no expression to flip in a premise
that is a survey, so 921 tests, both harnesses and 11/11 integration were
honestly green. It is [[invariants-need-an-enforcement-point]]'s "a comment that
counts call sites" with the comment in a *different module* from the sites.

**How to apply:**

- **The two questions the audit asked, and the answers that were accepted into
  the code.** *How does the scan know which states a rule keys on?* By **asking
  the rule**: one probe status per `DrainState.entries` carrying no side-effect
  record, through `StatusReconstruction.reconstruct`; the states that come back
  reconstructed are the keyed ones and the record's name is read off the report.
  Nothing is listed. *What does "named by the justification" mean?* Not a KDoc in
  `:schema` naming a `:core` line or function — that rots, and it points the wrong
  way across the dependency. The obligation is discharged **at the producer**, and
  the two ends meet at the **name of the record**, which neither side hardcodes.
- **It stays in `:core` and the reason generalises.** The rule is discoverable from
  anywhere; the producers are not. When module A's rule keys on a value module B
  produces, the enumeration belongs with the producers and the discovery has to be
  behavioural, or it is a list in a second place.
- **What it does not see, stated because the next round will ask.** A decode rule
  keyed on some field *other* than a `DrainState` is invisible to the probe
  (widening means an axis per field). `:store` reproducing a stored state through
  `valueOf` is not a producer and is not scanned — it can only reproduce a document
  some producer wrote. And the write/read split over-reports on set expressions
  (`DrainState.entries.toSet() - DrainState.DRAIN_FAILED` reads as a write), which
  costs a note if that state is ever keyed and is the chosen direction.
- **`store-dev` owns the other half** (correcting the premise in `:schema`, 2026-08-07,
  in parallel). If their fix retires the rule or re-keys it off `DrainState`, the
  scan's vacuity guard **fires** rather than passing — deliberately, with a message
  saying to widen the discovery or delete the test. A scan whose subject vanished
  reading green is the failure this suite exists to retire, so the red is correct
  and is a coordination cost, not a defect.
- The `:302` note is documentation only. **No behaviour of the already-down branch
  was touched** — that path is a drain decision and belongs to `drain-auditor`.

See [[classification-scan-scope]] for the sibling scan over `WorkloadState` (the
two share the wrapped-pattern fold, deliberately, so they cannot disagree about
what a `when` pattern is), and [[dispatch-record]] for the round that created the
stamp this rule reconstructs.

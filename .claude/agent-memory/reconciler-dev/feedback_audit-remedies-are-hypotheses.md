---
name: audit-remedies-are-hypotheses
description: An audit's finding and its prescribed remedy carry different weight — apply the fix, then let the existing suite arbitrate, because the named helper may itself be wrong
metadata:
  type: feedback
---

When a `drain-auditor` round names both a defect **and** the function to fix it
with, treat the defect as established and the remedy as a hypothesis. Apply it,
then run the whole suite before believing it.

**Why:** round 5 correctly found that both failed-probe branches of
`requireEmpty` called `forgetSaveEvidence()`, which lifts the wedge keeping a
second `save-all flush` off a live server, and prescribed
`forgetSaveConfirmation()` — "written for exactly this case", and already used
by a sibling call site. Making exactly that change broke an existing test: the
helper was itself wrong. `DrainStatus.saveRequestedAt` means two different
things depending on `worldSaved` — the instant a *completed* save was confirmed,
or the record of a request that was *delivered and never confirmed* — and the
helper preserved it unconditionally, so a confirmed-then-expired save came back
as "a request went out and never returned" and wedged a healthy drain
permanently. The correct fix branched on `worldSaved`, which neither the audit
nor the helper's own doc comment anticipated.

The general shape, worth watching for anywhere in this codebase: **a field whose
meaning depends on a flag must have the flag consulted at every site that clears
it.** `dropUnusableSaveEvidence` already documented this exact trap; the two
helpers next to it did not.

**That specific instance is gone** — `worldSavedAt` was added to `DrainStatus`
(2026-07-27, store schema V3), so the two facts are two fields, `worldSaved` is
derived, and both voiders are unconditional. Do not go looking for the bug; look
for the *shape*. The better ending is the one worth copying: the fix was not to
get the branch right at three call sites, it was to delete the thing they were
branching on. A flag beside its own timestamp is the same smell as a raw value
beside a predicate about it — see [[localnode-test-gap]] for the other instance.

**How to apply:** never commit a drain change on the strength of the audit alone
— the existing suite is what arbitrates, and a pre-existing test failing right
after a prescribed one-line change is evidence the prescription is incomplete,
not that the test is stale. Write the regression test first and confirm it fails
against the old code, so a green suite afterwards means something. See
[[assert-on-side-effects]] for why the assertions have to be about what was
*done*.

---
name: audit-remedies-are-hypotheses
description: An audit's finding and its prescribed remedy carry different weight — the named helper may be wrong, and a remedy for "the same root" may reach only one of the defects it was filed against
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

## A remedy can be right and still not reach every defect it was filed against

Round 15 named two criticals with "the same one-line root" and prescribed one
fix: invert `derivedOnly` into a positive claim. Inverting it closed the first
and **not** the second, and tracing why is what found the real rule. The second
defect's resume does a genuine `save-all flush` and the server confirms it, so it
claims the flag honestly — the failing step is the *stop*, and a save says
nothing about a stop.

What closed it was a second rule the audit did not ask for: **the pass that
resumes may not clear the failure, however much work it did; the ordinary pass
after it may.** Hysteresis, the way an alarm clears on sustained recovery rather
than on the first good sample.

Two things worth keeping from it:

- **Trace the prescribed fix against each defect separately before writing it.**
  "Same root" in an audit means the same *code shape*, which is not the same as
  the same *mechanism*. Ten minutes of tracing beat a green suite that would have
  covered only half the brief.
- **Two questions that look like one.** "Is this server making progress right
  now" (which governs the backoff) and "has this drain recovered" (which governs
  the failure record) have different answers for the same pass, and tying them
  together breaks one of them: the strict rule on the backoff leaves a drain that
  emptied after a play session waiting out a five-minute backoff; the loose rule
  on the failure record is the defect.

## Arguing to leave something open

When escalating a known hole rather than fixing it, **argue from what is at
stake, not from how narrow the window is.** Round 7 accepted a decision to leave
the teardown's partial-removal record unshielded and explicitly rejected the
reasoning I gave for it — "a vanishing fraction of the window" is unfalsifiable
at review and reviewers discount it on principle. What carried it was that the
container was already gone, so nothing playable was stranded. The reusable test
is *what is left behind and is anything playable in it*: an undeletable sandbox
with no process in it is acceptable, an undeletable server with a running
container is not, because the operator has no reason to suspect they caused it.

Rulings to leave something open can also carry an **expiry condition** — round
7's held only while every side effect the drain issues is idempotent game-side.
Record the condition with the ruling ([[cancellation-exposure]]), or a later
change quietly invalidates it.

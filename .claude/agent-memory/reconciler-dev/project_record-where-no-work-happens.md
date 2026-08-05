---
name: record-where-no-work-happens
description: Three rounds of criticals came from "did work ⇒ recovered" — the fix that finally held was moving the record to the pass that does no work, not narrowing the rule
metadata:
  type: project
---

`DrainController.settleRecords` clears a recorded failure on a pass that did
work and did not begin parked. Rounds 15, 16 and 17 each produced a critical
from that assumption, and round 17's defect was precisely *did work **and** did
not recover*: a drain saving, losing the confirmation, saving again, reporting
`Progressed` every pass and never reaching the stop.

**Why:** the auditor offered two directions — escalate off a field
`settleRecords` does not touch, or narrow the clearing rule to "reached a state
it has not occupied since the failure". Both were rejected on their merits, and
the third option is the one worth remembering.

- **Escalating off a flag** puts a genuinely-failing fact in a non-failure
  carrier to dodge a rule, and `NEEDS_ATTENTION` would then fire with nothing for
  `attentionMessage`/`detail()` to say. Whatever carries the flag has to carry
  the words, and the failure record already does.
- **Narrowing the rule** cannot decide from one pass: a wait that has ended and a
  wait about to resume look identical, so it needs new per-failure state (the
  state the failure was recorded in, or a high-water mark) and every drain test
  is a constraint on it.
- **What held:** record the failure at the pass that does **no work** — the edge
  back to `SAVING`, which is a re-derivation. The abort parks, so `settleRecords`
  returns early; the next pass is the excluded resume; the pass after that aborts
  again. The existing rule never gets a chance to be wrong, and it is not
  weakened for anything else.

**How to apply:** when a record keeps being erased, ask *which pass in the cycle
is claiming to have recovered* before touching the rule that believes it. If the
cycle contains a pass that honestly did nothing, that is where the record
belongs. And place a detector on the branch that is the defect's own signature:
putting this one in `save` diagnosed a refused *stop* as a save problem, because
every drain reaches `save`, and only a drain that lost a confirmation reaches the
edge back to it.

Two things this round also settled, both open to overruling:

- **`awaitStopped`'s re-issue claims no work.** It runs because the container is
  still running, so the previous stop did not take. Past the stop grace period it
  records a retryable failure and keeps re-issuing the *same* grace period —
  report only, item 7 — and `enteredStateAt` is a sound anchor there and nowhere
  else, because that branch never leaves `STOPPING`. The lap that does leave
  (back to `SAVING` for a fresh save) restamps it, and is measured by the re-save
  anchor instead.
- **`settleRecords` still clears `blocked` unconditionally.** Narrowing it to
  `workDone` cannot fire — both `blocked` call sites call `forgetSaveEvidence`,
  so the ladder resumes into `SEALED`/`TARGET_RESOLVED` and every branch out of
  those claims the flag — and keeping the record while the drain progresses
  renders "waiting, not stuck" on a drain that is transferring. Preserving
  `DrainBlock.since` across an interruption needs a carrier that is not the
  current block record, whose reset faces the same undecidable question.

See [[level-triggered-seal]] for the hysteresis this sits beside,
[[audit-remedies-are-hypotheses]] for tracing a prescription per defect, and
[[blocked-is-not-failed]] for the three states a consumer must tell apart.

---
name: guard-lifetime
description: Round 34's second critical — a refusal conditioned on a state the drain itself ends, the ruling on the churn that replaced the freeze, and the two extensions I made past the prescription
metadata:
  type: project
---

**A guard must not be conditioned on a state the thing it guards against takes
away.** `forbiddenTransition` refused `storage.mode` persistent→ephemeral only
while the container was `RUNNING`. The drain ends `RUNNING`. So: edit A dispatches
a stop, edit B lands in the grace period and is refused, the signalled container
exits on its own, the refusal stops firing, the drain resumes, and the create
applies the definition the loop had spent several passes refusing.

**Why:** the window was opened by the previous round's fix. With the drain record
retained across a refusal the record is `STOPPING`, so `parkedOnTheFailure()` is
false, so the permanent-failure gate does not arm and the passes keep coming —
where before the record was deleted here, the gate armed, and the server froze
with the edit unapplied. The freeze was doing the enforcing; once it went, the
refusal had to hold on its own.

**How to apply:** the discriminator is the test. `Labels.WORLD_DATA` is read off
the *container* and survives its exit, so the refusal can too — `RUNNING`,
`EXITED` and `UNKNOWN` refuse; `CREATED` and `SANDBOX_ONLY` pass through, where
"a workload that says nothing about itself" reasoning applies. Ask of any guard:
which fact does it turn on, and does that fact outlive the process?

## What is lost is downstream, and invariant 2 still holds literally

The volume directory is untouched — that is the whole point of a mount that
outlives its container — so nothing on disk is destroyed. The loss is that the
server returns on a **freshly generated empty world** and everything built from
then on lives in a writable layer that dies with the next replacement. Do not let
"the invariant holds" close a case: trace what the *operator* ends up with.

## Two extensions past the prescription, both flagged

- **`UNKNOWN` refuses too.** The audit named `RUNNING` and `EXITED` and named the
  pass-through as `CREATED`/`SANDBOX_ONLY`; the two clauses only agree if
  `UNKNOWN` refuses. It is the right side anyway — the labels are the
  container's own there, and a refusal is an inaction, which is the loop's
  posture on a state it cannot read.
- **The refusal keeps the storage record the container was created under.**
  `Pass.storageStatus` derives from the *definition*, so refusing an edit while
  drafting from it reported `persistent = false, volumeName = null` — the loop
  erasing its own record of which volume holds the world, which is the name
  recovery depends on and the one thing nothing else remembers. The prescribed
  fix did not reach this; see [[audit-remedies-are-hypotheses]] and
  [[derive-from-the-consumer]]. The general form — `StorageStatus` is documented
  as *observed* and is derived from desired state everywhere in the loop — is
  still open and is a `:schema`/`:api` conversation.

## The churn, decided rather than discovered

With the record retained and the refusal permanent, every pass re-records the
same failure and increments `attempts`: one store write per resync, nothing
issued at the runtime. Accepted over arming the gate, because **this loop is the
only thing that can notice the operator reverting the edit**, and freezing a
workload whose container it has already signalled leaves the stop half-finished.
The lever is asserted at the end of the scenario test, not just described.

See [[record-lifetime]] for the round that opened the window, [[gate-and-ceiling]]
for why a park is not automatically a gate, and [[prove-the-test-can-fail]] for
what the mutation board could and could not have said about either critical.

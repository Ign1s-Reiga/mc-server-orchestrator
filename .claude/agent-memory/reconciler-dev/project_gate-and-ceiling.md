---
name: gate-and-ceiling
description: Round 29 — why a blocked drain must not arm the permanent-failure gate, why the release promise was a lie, why the node caps rather than refuses a stop grace period, and the traced reason troubleSince was declined
metadata:
  type: project
---

The twenty-ninth audit's three findings and one declined remedy. All landed on
`fix/save-evidence-stamping` at four commits from `3506098`.

## A gate has to be narrowed at the gate, not at what feeds it

Round 28 made [[blocked-is-not-failed]]'s block **keep** a standing `PERMANENT`
failure. `Reconciler.drain` copies `drain.failure` onto the status, so the pass
that recorded the block armed `isBlockedByPermanentFailure` at the current
generation and froze the loop. The audit offered two remedies — narrow the
retention to `!permanentFailureStopsPasses`, or carry the wedge only in the
block's message. **Both were traced and both are worse than fixing the gate:**

- Narrowing the retention clears the failure in the non-terminating case, which
  destroys `FailureStatus.occurredAt` and turns `NEEDS_ATTENTION` off — the exact
  harm round 28 filed.
- Message-only does the same in *every* case, and leaves `DRAIN_BLOCKED`
  ("do not act") beside a message saying waiting does not finish this.

`DrainStatus?.parkedOnTheFailure()` is the fix: `state == DRAIN_FAILED && blocked
== null`, one expression asked by both kinds' gates, next to
`permanentFailureStopsPasses` and for the same reason. **A drain waiting for
players is parked on players, and that is the one park a later pass gets past
with nobody doing anything** — so it is not what the gate is for. Both clauses
only ever *narrow* the gate, which is what makes the change safe to argue: it can
un-freeze a workload, never freeze one.

The mechanism to recognise: the only thing that lifts that gate on a server
nobody has deleted is a generation bump, and it lifts it for **one pass**. Any
state that pass can land in which re-writes the failure spends the operator's
edit. For a `ProxyDrainSubject` the frozen end state is a fleet-wide login
blackout, because round 27's `resume` shuts the door before the gate and
`releaseSeal` is reachable only from `abort`.

## "The next pass fixes it" — name the branch, again

`SEAL_STUCK_SHUT` told an operator the loop "releases the seal on the first pass
that reaches the endpoint". `releaseSeal` has **one caller**, so the retry rides
on a *park*: the pass after runs `resume` → `holdSeal` → `requireEmpty`, and with
anybody on it lands in `blocked`, which releases nothing. It still converges — a
shut door is what makes the population fall — but the sentence read as *wait, it
is about to fix itself*. Third time this project has been caught by "the next
pass repairs it" being a claim about a branch nobody checked ([[level-triggered-seal]]).

The reporting half of the same finding: all three `blocked` call sites stated
joinability for themselves, and `requireEmpty`'s said *"the server keeps running
and stays joinable"* — false for a self-sealing workload since round 27, and it
is the only sentence `:api` shows for a `DRAIN_BLOCKED` drain. `blocked` now
composes `loginPathAfterAPark`, the function `abortSeal` already used. **A park is
a park**: if a fact is stated by one kind of park it belongs to both.

## An operational ceiling caps; it does not refuse

`GrpcCriClient.stopContainer` deadlines the call at `gracePeriod + slack`, so the
grace period is also how long a worker is parked at a container that will not
exit. `StopGracePeriod` bounds it 292 years out, and no *type* enforces the
readers' two-hour cap (`LifecycleSpec.init` checks only the save relation,
`ProxyLifecycleSpec` has no `init`), so a store row or a migration reaches the
node with anything.

`StopGraceCeiling` in `Node.kt` is the answer, applied in `LocalNode.stopWorkload`
beside `StopGracePeriod.of`. **The decision worth remembering is cap versus
refuse.** Round 24's rule — operator-supplied values are refused at the node —
was written about a *create*, which strands nothing. The operation here is the
**stop**, and a stop nobody can issue is a populated world-holding server nobody
can retire: a certain harm traded for a conditional one. Capping is safe only
because of where the stop sits — nothing reaches it except through the
zero-player gate and `mayStop`, so the save is already confirmed and the grace
period is the last-resort net — and because the constant is *borrowed* from
`PaperServerDefaults.MAX_STOP_GRACE_PERIOD`, whose meaning is "no reader accepts
more". `INFINITE`, zero and negatives are **not** capped: they are not durations
anybody meant, and capping `INFINITE` to two hours would turn an uninterpretable
argument into a plausible-looking stop.

## `troubleSince` was declined, and the counter-scenario is concrete

The audit prescribed a set-once, never-restamped `DrainStatus.troubleSince`,
stamped by `noteFailure`, cleared only at `workDone && !resuming`, with
`escalates` anchored on it instead of `FailureStatus.occurredAt`. It reintroduces
the alarm fatigue that anchor was *chosen* to avoid, and the sequence is ordinary:

1. one transient abort (a missed ping, a control blip) stamps `troubleSince`;
2. the fault clears, the resume finds players, `blocked` — which never clears
   `troubleSince`, because `settleRecords` returns early in `DRAIN_FAILED`;
3. four hours of healthy blocking on a busy evening;
4. one more transient hiccup → `escalates` sees a four-hour anchor → the flag
   fires immediately on a fault one second old.

`AttentionTest`'s *"a drain failure after a long healthy block is not flagged by
the drain's age"* is the same shape and stays green only because its scenario has
no failure *before* the block. **A single instant cannot distinguish one fault
four hours ago from a fault present every other pass for four hours**, and the
flapping case needs exactly that distinction. A carrier for it has to be cleared
by evidence proportional to what set it — some count of clean passes, not one —
which is two fields, a `:schema` change and a `:store` codec change; not written
on speculation. The reasoning is in `blocked`'s KDoc so the next reader does not
re-propose it.

## What is still open

- `:api`'s `detail()` leads a blocked drain with *"waiting, not stuck — "* and
  the blackout sentence now arrives in the tail. Honest, but the lead is the part
  a fleet table truncates to.
- The integration suites did **not** run this round: `scripts/dev/containerd-up.sh`
  needs an interactive `sudo` and there was no TTY. Nothing here changes a CRI
  call's shape, but `LocalNode.stopWorkload`'s capped value has only unit
  coverage.

See [[level-triggered-seal]] for the seal rules this rests on and
[[blocked-is-not-failed]] for the three states a consumer must tell apart.

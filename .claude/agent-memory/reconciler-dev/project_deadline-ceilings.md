---
name: deadline-ceilings
description: Round 30 — why a ceiling on one half of a validated pair inverts it, why the bound moved onto the argument's type at the Node seam, the sibling ceiling on the exec that was left unwritten for a round, and the block message whose reassurance came first
metadata:
  type: project
---

The thirtieth audit found no critical. Three findings and one test gap, all on
`fix/save-evidence-stamping` at three commits from `1db654b`. Everything here
revises [[gate-and-ceiling]]'s ceiling section rather than replacing it: capping
a stop rather than refusing it was and remains right.

## Do not clamp one field of a validated pair at a consumer that cannot see the other

`stopGracePeriod` and `drain.saveTimeout` are validated **together** —
`LifecycleSpec.init` refuses a `PaperServer` whose grace period does not exceed
its save timeout by `MIN_STOP_GRACE_MARGIN`, because *"a grace period shorter
than the save timeout kills the container part-way through the save"*. Round 29
put `StopGraceCeiling` inside `LocalNode.stopWorkload`, which is handed one of the
two. A row carrying `saveTimeout = 3h` and `stopGracePeriod = 3h1m` clears
`init`, decodes, confirms its save, and was stopped with two hours.

What SIGKILL lands in there is not the drain's own flush — that is confirmed by
then — it is **Paper's shutdown save**, so the loss is a torn region file.

**The population argument is the part worth carrying.** I nearly filed this as
too narrow: the cap only fires on a definition that bypassed `PaperServerReader`,
since nothing else produces a grace period above two hours. But that is *exactly*
the population that can also carry a save timeout above `MAX_TIMEOUT`. The two
conditions are correlated, not independent, and "each condition is rare" is not an
argument about their conjunction when one implies the other's opportunity.

The fix is a floor: `bound(requested, saveTimeout)` caps to
`max(MAX, saveTimeout + MIN_STOP_GRACE_MARGIN)`. For any pair a reader would
accept the floor is below `MAX` and nothing changes. Note what it is *not* — the
row above is still shortened, to `3h0m30s`, the smallest value the schema would
have accepted for that pair. Giving up on the row entirely would hand the worker
back the unbounded park the ceiling exists to stop.

**The residual is a ruling, not an oversight.** A save timeout so large the
derived floor passes what containerd accepts (292 years) leaves the stop
*refused*. That inverts round 29's cap-versus-refuse trade, deliberately: that
population needs **both** halves absurd rather than merely unvalidated, and
between a loud refusal and a silent mid-save SIGKILL the refusal is the one to
keep. Pinned by a test so the next reader finds a decision rather than a surprise.

## The seam gets the bound, and the type is the enforcement point

The auditor's ruling, and it is the one to generalise. Applying the ceiling inside
`LocalNode` made the property "a `Node` never holds a stop open past the
operational ceiling" something **one implementation does**, pinned by a test *a
second implementation is not required to pass*. `Node` is the distribution seam;
an invariant of a seam that each implementation has to remember is an invariant
the second implementation breaks.

`Node.stopWorkload(handle, StopGrace)` — a value class whose only factory applies
the bound. Two conditions the auditor set, both worth keeping:

- **The type carries the *policy* ceiling only.** Each implementation keeps its own
  runtime bound (`StopGracePeriod.of` for containerd), because where a runtime's
  arithmetic wraps is a fact about that runtime. Zero, negative and `INFINITE`
  still reach the node intact and are refused there, by the rule whose message an
  operator reads.
- **The factory takes the floor's input**, so the pair cannot be split. It is also
  the only layer where the floor *can* be applied: `DrainController.stopGrace` has
  both fields off `DrainSubject` side by side.

Ripple: `LocalNode`, `FakeNode`, `:app`'s `StubNode`, the integration harness, and
two harness mutations. `:app` still has no `:cri` on its classpath.

## A bound derived from "this becomes a deadline" belongs at every number that is one

The ceiling's whole argument was *"this becomes a gRPC deadline, so an absurd
value parks a worker with no effective timeout"*. `GrpcCriClient.execSync` does
the identical thing with `spec.lifecycle.drain.saveTimeout` — same row, same
absent bound, on the **longer** of the two calls. The fix was written for one
number and not for its sibling, and nobody noticed for a round.

`ExecRequest.timeout` is an `ExecTimeout` now, bounded by `ExecTimeoutCeiling`
(borrowed from `MAX_TIMEOUT`, the widest any reader accepts). I went past the
brief's "ceiling at the agent" to a type for the same reason item 1 got one — only
one of the three `ExecRequest` sites carries an unvalidated value today, and the
next one will not remember.

**It caps `INFINITE` where the stop's ceiling refuses it, and the asymmetry is
statable:** what the number authorises differs. A grace period authorises a
*kill*, and short is the dangerous direction, so an uninterpretable value must not
be made plausible. An exec timeout authorises only *waiting* — cutting it short
can do no more than withhold a confirmation, and an unconfirmed save is a
container this orchestrator will not stop. Write the asymmetry down or somebody
"makes them consistent".

## A reassurance that leads is the whole message on a truncated table

`:api` renders a blocked drain as `"waiting, not stuck — <block message>"`, and
the block message opened with the wait. On a proxy that has sealed its own login
path the blackout sentence arrived ~250 characters in, so a fleet table that
truncates showed only the half agreeing with `DRAIN_BLOCKED`'s *needs nobody*.

Fixed in `:core`, not `:api` — `:api` cannot see `DrainSubject.router` and keying
on `sealRequestedAt` alone would over-state a blackout on every backend mid-drain.
`loginPathAfterAPark` returns `LoginPath.Restored | ShutByThisDrain | Open`, each
carrying its sentence, and `blocked` puts `ShutByThisDrain` first. One derivation
still; only the order moves. Two follow-ons:

- **A sentence that can lead and can follow needs rewriting for both.** The old
  text opened *"It keeps running with…"*, which has no antecedent at the front.
- **Sentence-case it on the way.** Every block message is written to follow
  `:api`'s lower-case lead-in, so second in a sentence it reads as a typo — and a
  status line that looks broken is one an operator trusts less.

## What is still open

- Integration suites did **not** run again: `containerd-up.sh` needs an
  interactive `sudo`. Three rounds now. `LocalNode.stopWorkload`'s capped value
  and the exec's bounded deadline have unit coverage only.
- `EndpointRequest.timeout` is the third duration that becomes a transport
  deadline. Every construction site is a compile-time constant today, so nothing
  unvalidated reaches it — but that is a survey of call sites, which is the
  argument this project keeps being caught by. If a definition field ever feeds
  it, it needs the same treatment.

See [[invariants-need-an-enforcement-point]] for the rule this is an instance of,
and [[gate-and-ceiling]] for the cap-versus-refuse ruling it revises.

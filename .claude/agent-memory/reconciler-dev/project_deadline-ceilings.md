---
name: deadline-ceilings
description: Rounds 30–32 on the three durations that become transport deadlines — why a ceiling on one half of a validated pair inverts it, why the bound moved onto the argument's type at the Node seam, why the floor makes that ceiling bound the relation rather than the wait, who owns the wait now, and why one unbuildable request is classified retryable at one call site and permanent at another
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

## Round 31: a ceiling with a floor bounds the relation, not the wait

The floor above is correct and stays. What was wrong was the sentence describing
it, and the gap is not small: `ceilingFor` is `max(MAX, saveTimeout + margin)`, so
**the moment the save timeout passes `MAX - margin` the effective ceiling is the
save timeout**, and it rises with it up to the runtime's own refusal.
`saveTimeout = 30d` beside `stopGracePeriod = 31d` clears `LifecycleSpec.init`,
decodes, and is *capped* — to a month. The residual the KDoc had named was the
refusal at 292 years, which needs both halves absurd; the reachable one, over
essentially the whole range the cap fires in, was unnamed.

**The general form, worth carrying past this field:** a bound written
`max(K, f(other))` promises `K` only where `f(other) <= K`, and past that it
promises whatever bounds `other`. So it bounds the *relation* between the two
values and not the magnitude of either, and the sentence has to be written over
the range where the second term wins. Here that means the thing which actually
bounds the stop's deadline is whatever bounds `drain.saveTimeout` — and nothing in
`:core` does; `ExecTimeoutCeiling` bounds a *copy* of that field on its way to an
exec. The row-level half belongs at the decode and `store-dev` closed it in the
same round.

The trade is still the right way round and was not touched: a parked worker loses
no world, an inverted pair loses one.

## A licence to shorten is derived per construction site

`ExecTimeout`'s KDoc justified capping with *"cutting an exec short can do no more
than withhold a confirmation"*. True of the save exec. **False of the probe**:
`DrainController.save` re-probes after the flush and stamps `worldSavedAt` on an
`Unanswered` reading exactly as on an `Empty` one, so a probe that merely timed out
*mints* the confirmation the stop is gated on, and `awaitStopped` lets an
unanswered probe fall through without holding the re-issue. Unreachable today —
both probe timeouts are private 10s constants against a one-hour ceiling — and
reachable the day one comes off a definition the way `saveTimeout` does.

**Before writing "shortening this is safe", ask what reads the *absence* of an
answer.** A wait whose silence is read as consent is not a wait that can be cut
short, whatever the value authorises on its face.

## Round 32: the third type, and the floor's other half

Both open items below are closed. `EndpointTimeout` is the type of
`EndpointRequest.timeout`, bounded by `VelocityProxyDefaults.MAX_TIMEOUT` — the
same constant `SpecBounds.MAX_HANDSHAKE_TIMEOUT` borrows for the same field, which
is what makes the two layers agree by construction rather than by coincidence.

**The licence to cap was re-derived at the call, and this time the derivation is
per-consumer rather than per-number.** For the control channel every unanswered
call becomes `ControlOutcome.Unavailable`, and every consumer either parks the
drain or discards the answer (`observedPlayers` is *"corroboration only, and never
a gate"*). So shortening it can only park a drain, never advance one — and the
sentence to write down is the *test*: **what reads the absence of an answer**, not
what the number authorises.

**The classification is a caller's decision, and the two callers land opposite.**
Same defect, same shape, different class, because what a `PERMANENT` failure arms
is `isBlockedByPermanentFailure`, and the only thing that lifts it is a generation
bump **on the server it froze**:

- `ControlChannel` → `Unavailable(retryable = true)`. The row is the *proxy's* and
  the drain reading it is usually a *backend's*, so permanence freezes every
  backend behind that proxy with no lever on any of them. Precedent already in the
  repo: `ProxyLink.transfer`'s `UNAUTHENTICATED`. And on the proxy's own drain it
  would not even be a permanence — `abort`'s compensation runs back through the
  same unbuildable request, so it down-classifies to retryable anyway and appends
  `SEAL_STUCK_SHUT`, a sentence about a blackout that never happened.
- `requestSave` → `NotDelivered(retryable = false)`. Own definition, so the repair
  bumps this server's generation. `NotDelivered` and not `Unconfirmed`: nothing was
  dispatched, so the never-re-send wedge must stay unarmed.

**Ask of any permanent verdict: does the repair bump the generation of the server
this freezes?** If the field belongs to another object, the answer is no and the
verdict is a fleet-wide wedge.

The catch wraps the **whole construction**, which is what makes it worth more than
a nullable factory: `EndpointRequest.init` also validates `port`, and that is
`spec.control.port` — a second definition field from the same row.

## The wait the ceiling does not bound now has an owner, and it is `:cri`

`GrpcCriClient` deadlines a stop at
`min(gracePeriod, CriTimeouts.stopDeadlineCap) + deadlineSlack`, two hours by
default, and **sends the whole grace period on the wire**. So round 31's "nothing
bounds the wait" is answered without moving the grace period an inch, and
`awaitStopped`'s overdue check still measures against the value the runtime was
given. Three `:core` sentences described the old `gracePeriod + slack` derivation
and one *test name* carried it as its justification — renamed, and the mutation
harness's anchor with it.

**The measured fact worth keeping, because it decides the classification:**
containerd does **not** escalate to `SIGKILL` once the request context has expired
(`container_stop.go`: `if ctx.Err() != nil { return ctx.Err() }` between the wait
and the kill; observed on 2.3.3 — a 12s grace deadlined at 4s left the container
RUNNING 17s later). A capped deadline can therefore only leave a container running
**longer**, never kill it sooner, and the re-issue is the only thing that finishes
it. I was told the opposite in relay first — *"containerd keeps stopping
server-side after the client deadline"* — and it is false; do not write it down.

## What is still open

- Integration suites did **not** run again: `containerd-up.sh` needs an
  interactive `sudo`. Five rounds now.
- **`Reconciler.readControl` discards the detail**, so on a proxy's *own* status a
  bad row reads as "the control endpoint did not answer". The backends carry the
  full sentence; a fleet with no backend draining does not. Closing it needs a
  place on `ControlEndpointStatus` for *"answering is not the problem"* — the same
  field `assertBackends`'s `UNAUTHENTICATED` branch is already waiting for.
- ~~**`restoreRegistration` re-registers a container that has been sent
  SIGTERM.**~~ Closed in round 32 by `DrainStatus.stopDispatchedAt` — see
  [[dispatch-record]], which also records that the cap this file describes cannot
  fire on any definition the loop acts on, `SpecBounds` having emptied that
  population at the decode.

See [[invariants-need-an-enforcement-point]] for the rule this is an instance of,
and [[gate-and-ceiling]] for the cap-versus-refuse ruling it revises.

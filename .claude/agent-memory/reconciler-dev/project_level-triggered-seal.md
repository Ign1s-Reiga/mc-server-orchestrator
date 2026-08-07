---
name: level-triggered-seal
description: Why the proxy seal is asserted rather than issued, the two rules that look alike and are not (sealsBackend vs drainInitiated), the anchor rules a bounded drain step needs, why a resume never clears a failure, the rounds that narrowed the seal release to the gate's answer and made the gated resume assert step 2, and why the seal's record is written at every asserting site while the release's outcome decides the failure class
metadata:
  type: project
---

Effects on a **third party that outlives an abort** cannot be issued as events.
That is the whole design of the proxy side of the drain, ruled before
implementation and confirmed by the tests.

## The seal is asserted every pass, and nobody ever un-seals

There is no unseal operation, on the wire or in `:core`. `PUT
/v1/backends/{name}` states registration *and* admission, and it is called on
every pass of every state that depends on it. An abort restores joins by simply
not asserting a seal any more; a proxy restart is repaired by the next
assertion. Neither needs an edge somebody has to remember to write.

**The proxy's own reconcile pass is the load-bearing half**, not the backend's.
A *permanent* drain abort freezes the backend's status and
`isBlockedByPermanentFailure` stops its passes entirely — so only the proxy's
sweep can restore joins to it. Without the sweep the failure mode is a server
that is running, invisible and unreachable, for ever.

**Deregistration is the one step that cannot be level-triggered** — it is the
last thing before the stop, so "assert it every pass" would mean asserting it
from states that must not reach it. It gets an explicit re-registration edge on
the abort path out of `DEREGISTERED`, in `abort`/`blocked`. A `NodeException`
escaping the controller skips that edge, which is why the stop is now caught
inside `DrainController` and turned into an abort.

## Two rules that look alike and answer different questions

- `DrainState.sealsBackend()` — *is this drain holding the backend out of
  routing.* False for `DRAIN_FAILED`: a drain that has stopped advancing is not
  going to move those players.
- `PaperServerStatus.drainInitiated` — *may this server receive somebody else's
  players.* True for `DRAIN_FAILED`, because a server parked on a retryable
  abort will try to stop again.

Using `drainInitiated` for the seal was a real defect the tests caught: a parked
backend stayed sealed for ever. They are opposite answers about the same state
and the plausible-looking substitution is wrong in both directions.

## The single-point argument that replaced "one guard for six states"

The old argument for wrapping every state in `requireEmpty` was that there was
one thing to audit. What replaces it is narrower, not looser:

> **No path reaches `Node.stopWorkload` except through `requireEmpty` followed by
> `mayStop`.** Steps 2–4 have no stop call and no edge to `STOPPING` that does
> not pass through `SAVING`, so they cannot lose data however wrong they are;
> `stop()` re-asserts `mayStop` itself as a backstop.

## The gate is the workload's own SLP, and a proxy count is never a decision

`backends[].players` cannot see a client connected straight to the backend port,
and whether backends are firewalled is a deployment property this code cannot
assert. It is corroboration and a log line. The trap is subtler than reading it
at the gate: **reporting `Progressed` from a sweep's `remaining`** resets the
backoff on every pass of a drain getting nowhere, and prints "every player has
been moved" about a populated server. A sweep in flight is always `Waiting`.

## A limit on a drain step is a wedge unless the success exit precedes it

Two states the thirteenth audit found, both ending in a manual `crictl stop`:

- **The limit was consulted before the player count.** `startTransfer` went
  straight to the retry check, so once the budget was spent the resume ladder
  re-entered it on every pass *without ever reading whether anybody was still
  there*. The delete could not be completed by waiting for the last player to
  log off, by editing the definition (a drain record survives a generation
  bump), or by restarting the loop. **The general rule: the exit that means
  "this step succeeded" must sit above the exit that means "this step has tried
  enough".**
- **The bound counted passes.** With start-or-join, a repeat asks nobody to move
  again, so a six-sweep budget went in twelve seconds at a two-second poll and
  the documented `playerTransferTimeout` was unreachable. Counting *completed*
  sweeps fails identically when the proxy settles one instantly. The bound is
  now the clock and nothing else — a second bound that cannot bite is worse than
  no second bound — anchored on `sealRequestedAt`, because `enteredStateAt`
  restamps on every park-and-resume and hands the allowance back in full.

`transferAttempts` survives as a report. **A counter nothing gates on cannot
wedge anything**, which is the only reason it is safe to keep.

### A duration needs an anchor, and an anchor is a field that can be absent

The price of choosing a clock over a counter, and it cost three rounds. A counter
starts at zero by construction; an anchor can be missing, stale or restamped, and
each of those is a different wedge:

- `enteredStateAt` **restamps** on park-and-resume — allowance handed back every
  cycle, for ever.
- `sealRequestedAt` **can be absent**: it is written at one site with `holdSeal`
  above it, and nothing re-enters `DRAIN_REQUESTED`, so one blink of the control
  endpoint meant the drain never had an anchor at all. It was also **stamped too
  early** — everything between step 2 and step 4 spent step 4's budget, including
  an orchestrator restart, since it is persisted.

`DrainStatus.transferStartedAt` is the answer: stamped on entry to the step it
bounds, never cleared, and passed to `exhausted` as a **non-null argument** so
the function cannot look it up and cannot fall back. Three rules that generalise
to any anchored bound:

1. Stamp at the entry to the step being bounded, not at an earlier step that
   happens to have a convenient field.
2. No `?:` in the consumer. A missing anchor must **stamp**, never substitute —
   a fallback is what made two of these silent. "A parameter is the only version
   of no fallback a future edit cannot undo" is right about the *callee* and
   wrong about the caller that fills it: `issueTransfer` kept `?: now` three
   lines below a caller that stamped, and it was dead only for that reason. The
   answer is to **produce the value where it is used and return it in the
   record**, so a second caller from an unstamped state gets a stamp rather than
   a substitution. The same argument, one size smaller, retired
   `pass.occupancy?.online ?: 0` in `exhausted`.
3. Treat it like `saveRequestedAt`: one load-bearing field, covered by a test
   that drives the path where the ordinary stamp does not happen.
4. **An anchor on a cycle detector must not be cleared by the success path.**
   `resaveForcedAt` is cleared only by `forgetSaveEvidence` — an observed player
   — so it spans laps. Round 19 asked whether a lap that reached `DEREGISTERED`
   with a fresh confirmation should clear it; the answer is no, and the reason
   generalises: **that is exactly what every lap of the cycle being detected
   does**, so the clear would hand the allowance back once per lap and disable
   the detector for the defect it was built for. The general test — would this
   clear fire on a healthy lap *of the pathological cycle*? A drain that
   genuinely finishes takes its whole record with it, so an anchor only ever
   outlives laps, never a drain. The price is reporting: the figure may include
   time the drain spent making honest progress, so state it as what it measures
   ("a confirmation was first voided Ns ago and it has happened again") and never
   restate the same number as "nothing has worked for Ns" — that second wording
   is what sends an operator to `crictl stop`.

### A flag that names the exception is wrong the moment a second exception exists

`derivedOnly` was correct about the one step it was attached to and silently
wrong about every early return added afterwards — two of them in `save` alone.
**Invert any flag whose default is the dangerous answer**, so a step has to claim
the privilege rather than disclaim it, and **do not enumerate the members** in the
KDoc: the sentence "there is one such step" is what stops the next reader
looking. State the *test* instead (a request left the process, or the ping
established something), so a new site can be judged against it.

The flag is now `DrainProgress.workDone`, and the surviving unmarked returns are
exactly the three of that shape: choosing a destination, skipping a save for a
container with no world, skipping a save whose confirmation is still current.

### One good pass is not proof that a drain recovered

The flag alone does not close every version of this, and the version it misses is
worth remembering: a drain parked on a refused container stop re-enters through
the ladder, finds its save evidence aged out, and **saves for real**. That is work
by any honest definition, so the resume claims the flag — and clearing the failure
on it reset `attempts` and restamped `occurredAt` every cycle. A stop refused for
six hours reported three attempts and never reached the fifteen-minute threshold.

The rule that closes it is hysteresis: `settleRecords` clears the failure only on
a pass that did work **and did not begin in `DRAIN_FAILED`**. The drain proves it
has recovered by completing one ordinary step after the resume. Deliberately
*not* applied to the backoff — see [[audit-remedies-are-hypotheses]] for the two
questions that look like one.

A recorded **block** is settled by the opposite rule, unconditionally: it is
written by one function that always parks in `DRAIN_FAILED`, so a block on any
other state is stale by construction. Left riding, it reaches `stopWorkload` as
"waiting, not stuck" seconds before the container stops, and `recordBlock` carries
its `since` into the next genuine wait.

### "Did the pass make progress" is not "did the pass avoid failing"

`resumeInto` cleared the recorded failure whenever the resumed state did not
itself re-abort. Once step 3 had a body, asking the `Scheduler` for a destination
satisfied that — so a drain whose transfers kept being refused wiped its own
failure every other pass: `attempts` pinned at 1, `occurredAt` restamped,
`escalates()` never true, and `queue.succeeded` on the `Progressed` held the
backoff at the poll interval.

**Re-deriving state is not progress.** Nothing left the process and the drain
knows exactly what it knew before, so how long it has been failing is still true
and must survive. `DrainProgress.derivedOnly` marks the one step that qualifies,
and a resume that only re-derived neither clears the failure nor reports
progress. The level-triggered seal is deliberately *not* covered by that flag —
it runs every pass by design and would make it always false.

## The address had two derivations, and they disagreed for the window that matters

The proxy sweep read `status.endpoint.address` (written by `awaitJoinable`,
cleared by `teardown`) and fell back to the *server name*; the drain read the
node. A proxy pass landing while a backend was `Absent`/`CREATING`/`STARTING`
registered it at a hostname that does not resolve, and because `ADDRESS_CONFLICT`
is deliberately not an upsert, drain step 2 then aborted every pass **for ever**
— the only thing that clears a wrong registration is the `DELETE` that step 2
never reaches.

This was the fourth one-fact-two-derivations bug in a single session
(`saveRequestedAt`/`worldSaved`, `passFailure` across two modules, `drainBlocked`
condition-versus-field, and this). **When a value is asserted to a third party
from more than one call site, make it one function before writing the second
site.** And prefer *not asserting* over asserting a guessed value: "not
registered yet" is a state a protocol handles; "registered wrongly" often is not.

## A seal is a precondition only for a subject that has a transfer

Round 24's critical, and the rule generalises past the bug. `holdSeal` aborts
because *"a drain that carried on would be transferring into a queue that refills
behind it"* — a sentence about **step 4**. A subject with no `DrainRouter` has no
step 4, so the justification does not apply to it, and for those subjects the
seal is an **optimisation** while `requireEmpty` + `mayStop` is the gate.

The proxy was the case nobody had traced: a `VelocityProxy` always has a seal
object and never a router, so the `seal == null` short-circuit that carries a
standalone `PaperServer` could not save it. With the plugin absent or unloaded it
aborted at step 2 on **every pass of every state, at zero players, for ever** —
and the only repair, recreating it, is itself a replacement drain through the
endpoint that does not answer. `sealIsPrecondition(router, reading)` is the fix:
waive only with `router == null` **and** a fresh `PlayerReading.Empty`. Silence
still parks; players still park, because with anybody on, the seal is what lets
the wait for zero end.

Three things worth carrying:

- **The proxy is the shape that has one counterparty and not the other.** When a
  branch keys on "has a proxy", check whether it means *seal* or *router* — this
  is the second defect from conflating them, after `sealsBackend` versus
  `drainInitiated`.
- **A waived step must not claim it happened.** `holdSeal` returns a `SealHold`
  (`NothingToSeal` / `Asserted` / `Waived` / `Aborted`) rather than a nullable
  progress, because `DRAIN_REQUESTED` stamps `sealRequestedAt` and claims
  `workDone` on the strength of a `PUT` that landed. A boolean "did we abort"
  would have written "sealed at" about a seal that is not in place.
- **The residual risk was already accepted.** Somebody may connect between the
  reading and the stop — which is exactly the standalone server's exposure, and
  `requireEmpty` re-reading on the stopping pass is the guarantee. Saying so is
  what makes the waiver defensible rather than a loosening.

## "It lapses on its own" is a sentence about a third party

Round 25, and it is the limit of the whole level-triggered design. The reason an
abort needs no unseal edge is that **somebody else** re-asserts: a backend's
admission is stated by the *proxy's* pass, every pass, from `sealsBackend()`,
including for a backend whose permanent abort has stopped its own passes.

A subject that seals **itself** has no such party. The proxy's own re-assertion
lives in `assertBackends`, which only a non-draining pass reaches, and a drain
whose cause persists takes the draining branch for ever; a permanent abort stops
the passes altogether. So `DrainController.abort` releases the seal of a subject
with a seal and **no router** — the same shape `sealIsPrecondition` keys on, and
stated as *"is there anything else that asserts this workload's admission"* so a
future self-sealing subject gets it without being named.

### …and the compensation is for a *permanent* park only

Round 26's critical, and it retired ruling 5 below. The edge was implemented on
every abort. The accepted cost was "a parked proxy drain flaps its own seal,
because the abort releases and the next pass's resume re-asserts" — and the
second half is **false**. For a subject with no router the `DRAIN_FAILED` resume
is wrapped in `requireEmpty`; with anybody online it lands in `blocked`, which
does not seal, and the six forward states that do are unreachable while a player
is on. So a retryable abort gave the login path back and *nothing could ever take
it again*: population refills, the wait for zero never ends, delete parked for
ever.

Three things to carry:

- **"The next pass repairs it" is a claim about a specific branch, not about the
  loop.** Name the branch and check what it calls before accepting a flap as the
  cost of a compensation.
- **The justification names the class.** *"This drain has stopped advancing"* is
  `isBlockedByPermanentFailure`, i.e. `PERMANENT`. A retryable abort is a drain
  still being attempted, and there the seal is the mechanism of the wait — the
  same sentence `blocked` gets.
- The audit's alternative — release always, and re-assert with `holdSeal` before
  `requireEmpty` on the gated resume — was rejected on its merits: it hands the
  door back for a whole backoff per cycle (enough to refill a busy fleet), and it
  turns a healthy block into an abort exactly when the control endpoint is what is
  down. **Half of it was taken in round 27** — see below — once the release was
  narrow enough that there was nothing left to pair it with.

### …and "permanent" was still one of the gate's *inputs*

Round 27's critical, and it is the same defect one clause along. The fix above
read `failureClass == PERMANENT`, and the sentence it rests on — *no pass will
look at this workload again* — is `isBlockedByPermanentFailure`, which is
`PERMANENT` **and not terminating**. A delete is exempt from that gate on purpose
(a failure must never make a workload undeletable), so a permanent abort during a
delete — a refused `stopWorkload`, or a proxy whose missing `WORLD_DATA` label
makes a save unconfirmable — reopened the door of a fleet the loop kept
reconciling. Then the population refilled, the gated resume blocked for ever, and
the first `blocked` wrote `failure = null`, so the status settled on *"waiting for
the server to empty"* about a blackout the orchestrator had itself undone.

- **Key an edge on the gate's answer, never on one of its inputs.** The remedy is
  `Reconciler.permanentFailureStopsPasses()`: one expression, asked by both kinds'
  gates, handed to `advance` per pass and carried to every `abort` on `DrainPass`.
  A second derivation is what produced this, and `cause == DELETION` would have
  been a third — placement decides a cause first, so a terminating definition can
  drain as a `RELOCATION`.
- **A level a later pass restores cannot be asserted from a flag.** My first
  version of the delete test read `plugin.proxyAdmits` after the passes and went
  **green against the defect**: the resume's own `holdSeal` had re-sealed the door
  by the time it looked. The assertion has to be the wire record — *no `PUT
  /v1/proxy` asserted `true` after the seal landed* — which is the form the round-26
  test had already used and I did not copy. Ask, of any door/level assertion: what
  re-asserts this between the event and the read?

### The mirror: a first failed seal was final

The same round's second finding, and the re-take of the rejected alternative. The
six states that seal all sit behind `requireEmpty` on the gated path, so a proxy
whose **first** `holdSeal` failed with players on parked with the door open and
could never reach step 2 again — the fleet never emptied, and it did not converge
after the endpoint recovered either. `resume` now asserts `holdSeal` before
`requireEmpty` for the gated path.

- Under the old *unconditional* release the pairing handed the door back once a
  backoff; under the permanent-and-not-terminating release **there is no release to
  pair with**, so all that is left is a reporting change: a pass that cannot reach
  the endpoint with somebody on records a `RETRYABLE` failure where it recorded a
  healthy block. That is the honest report — a wait whose seal cannot be maintained
  is not a wait that can end — and it is the change to argue about, not the seal.
- **Re-take a rejected alternative when the rule it was argued under changes.** The
  rejection was right at the time and wrong six weeks later, and nothing in the
  code says so.
- It repairs the neighbouring case for free: a proxy restarted under a long healthy
  block loses its admission state, and `assertBackends` — the only other re-assertion
  — is reached by non-draining passes only.
- The price is one extra control call per parked pass (two, on a waived path: the
  resume's and the resumed state's). Bounded by `sealTimeout`; visible in the
  integration log for `it-mute-proxy`.

**`blocked` deliberately does not**, and that asymmetry is the interesting part:
a block is the protocol working, the drain is waiting for the last player to log
off, and the seal is *the mechanism of that wait*. Releasing it there refills the
population the drain is waiting to drain and a delete on a busy fleet could never
complete. The general test — does this effect exist to *cause* the condition the
step is waiting for? Then a "consistency" compensation on the waiting path
removes the reason the wait can end.

### …and the seal's *record* was written at one of the seven sites that shut the door

Round 28's first critical, and it is the reporting mirror of the round-27 fix. The
stamp lived in the `DRAIN_REQUESTED` arm while `holdSeal` runs on six other states
**and on the gated resume** — which, since round 27, is where a self-sealing
workload's door is first shut whenever the opening attempt failed with players on.
`loginPathAfterAPark` keys on that record, so the next pass to lose the endpoint
said *"the server keeps running and keeps taking players"* about a fleet this
controller had blacked out one pass earlier: **danger pattern 119 reintroduced
through a call site rather than a stale comment**, with both neighbouring tests
green because each ends with the field null for a *different* reason.

- `SealHold.recordedOn(drain, now)` is the fix — the hold carries the stamp, every
  site records it, `?: now` so a re-assertion does not restamp. `Waived` stamps
  nothing.
- **A record has to be written where the work happens, at every site that does the
  work.** The scenario suite can only cover the site it drives, so the other six
  are pinned structurally: every `holdSeal(` result is bound, consulted for its
  abort, and recorded (`DrainWiringTest`, harness D34/D35).
- Reading the field *with* the wire flag is what makes the assertion mean anything:
  the record alone passes against a build that stamps without sealing.

### The compensation decides the class it is recorded under

Round 28's second. `releaseSeal` was best-effort inside the one gate that
guarantees nobody retries it — one timed-out control call left the fleet's front
door shut, the `PERMANENT` class froze `reconcileProxy`, and a definition edit did
not repair it because the generation bump resumes straight into `holdSeal`. The
contrast that shows the shape is wrong rather than an accepted residual:
`restoreRegistration` is best-effort *and safe*, because `assertBackends`
re-registers a parked backend every pass. **The seal has no third party — that is
the whole argument for the edge existing.**

`releaseSeal` now returns whether the login path was left shut, and `abort` records
`RETRYABLE` when it was: *a permanence whose own compensation is unrecoverable is
not a permanence anyone can act on*. It settles as `PERMANENT` on the pass where
the release lands, which is the end state the edge was always for. Both ends need a
test — a build that only ever retried is the other way to get this wrong.

### An operator-facing remedy has to exist under every cause

Round 28's fourth. *"Until whatever asked for this drain is withdrawn"* is true of a
`REPLACEMENT` and false of a `DELETION` (`deletedAt` is one-way, `:api` has no
un-delete), and it was the only sentence about a fleet-wide blackout. The fix is
**wording that names both exits**, not a branch: the discriminator would have to be
the terminating flag, `cause` is the plausible substitute and is wrong (placement
decides a cause first, so a terminating definition can drain as a `RELOCATION`), and
`permanentFailureStopsPasses` is `!terminating` today but is the answer to a
different question. A sentence true under every cause needs no plumbing at all.

## Rulings I made that a human may overrule

1. **`DRAIN_FAILED` unseals.** A blocked-with-a-proxy drain therefore takes new
   players while parked. The alternative is an invisible running server, which
   the audit named as the harm.
5. ~~**A parked proxy drain flaps its own seal**, because the abort releases and
   the next pass's resume re-asserts.~~ **Overruled by round 26**, and the ruling
   is kept struck through rather than deleted because the *reasoning* is the
   instructive part: the resume it relied on is gated on zero players, so with
   anybody on there was no flap and no re-assertion at all. The release is
   permanent-**and-not-terminating** only, since round 27. What the old ruling
   feared — a retryable park staying sealed while the fault lasts — is real and is
   the accepted cost: the loop keeps retrying, the escalation fires after
   `drainAttentionAfter`, and reverting the definition (or the build pin) makes the
   cause vanish so a *converging* pass re-asserts admission through
   `assertBackends`. Round 27 made the resume assert the seal, so the flap the
   ruling described is now exactly what an over-wide release would produce — which
   is why the two changes have to be read together and why the delete test asserts
   at the wire.
6. **A parked routerless drain whose endpoint is down reports a failure, not a
   block.** The reporting half of the resume's `holdSeal`; it makes
   `NEEDS_ATTENTION` fire after `drainAttentionAfter` on a proxy drain that a
   dashboard used to render as *waiting, do not act*. Defensible because the wait
   genuinely cannot end while the seal cannot be asserted — but it is the one part
   of round 27 a human may want the other way, and `blocked-is-not-failed` is the
   ruling it sits closest to.
2. **More than one proxy claiming a backend is a retryable failure on the
   *backend's* status**, and the container is not created while it holds. The
   refusal is **exempt for a terminating definition** — it returns before
   placement, so without the exemption such a backend was permanently
   undeletable, which is the state that produces a manual stop. A conflicted
   delete drains with no binding at all, so it blocks on players rather than
   transferring them.
3. **The proxy sweep deregisters registrations whose selector no longer
   matches.** That is step 6 performed by a sweep, and it is safe only because
   the plugin refuses `DELETE` with `BACKEND_OCCUPIED` and has no force flag.
   `wanted` must therefore be matched names **plus `ServerListing.unreadable`
   names**: dropping the unreadable half keeps `listAll`'s tolerance and throws
   away the part that made it safe, turning "this build cannot describe that
   server" into a destructive call against a live backend. A NULL-named row
   cannot be matched to a registration and is still swept — named, not fixed.
4. **At the transfer limit the abort is `RETRYABLE` and escalates by the ordinary
   threshold**, with no exemption added to `escalates()`. Adding one is the
   mechanism that produced two earlier audit findings.

See [[standalone-drain-decision]] for what this supersedes and
[[blocked-is-not-failed]] for the failure/block split it rests on.

---
name: audit-remedies-are-hypotheses
description: An audit's finding and its prescribed remedy carry different weight — the named helper may be wrong, a remedy for "the same root" may reach only one of the defects it was filed against, and even a demonstrated hole can be given a mechanism that does not compile here
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

## A re-scoping reaches the files you edited, not the claim you were fixing

Round 18 narrowed "the schema guarantees the grace period exceeds the save
timeout" to `PaperServer`, because `ProxyLifecycleSpec` has no such rule. It was
applied to `DrainSubject` and `Node` — the two files the change touched — and
left standing on `stop()`, the one function that issues the call, and in
`LocalNode`'s non-positive-grace refusal, which is the wording an operator reads.
Round 19 found both.

**Grep the claim's words across the repo, not the files in the diff.** A claim
that has to be re-scoped is a sentence, and sentences get copied to exactly the
places that matter most: the call site and the error message. The same grep is
what tells you which copies are already correctly scoped by their subject
(`PaperWorkloadPlanner`'s is, because its parameter is a `PaperServerDefinition`)
so you do not churn them.

## A remedy that moves a defect gives it a new address

Round 20's sharpest mutation was a *narrowed* predicate — `is Occupied &&
!playersEvacuated` — written inline at a call site where no test could see it.
The fix was to extract the predicate into a function so a unit test could call
it. That is the right move, and it means the same defect can now be written in
two places: at the call site (wrapped in a condition) and inside the function
(narrowed). Both had to be red-proved, and they are caught by different tests —
a structural one and a behavioural one.

**When a remedy relocates a defect, mutate it at its new address too.** The
question to ask of any extraction is "where can this be written now that it
could not be written before", and the answer belongs in the red-proof rather than
in the commit message.

## A fact about upstream is not a fact about what we run

A hand-off arrived with a defect stated as verified: "Velocity 4.0.0's generated
`velocity.toml` binds players on 25565, not 25577 — observed against the real
STABLE release, sha256 verified. Check `VelocityProxyDefaults.PLAYER_PORT`." The
observation was correct. The conclusion was not, and acting on it broke every
proxy: the image installs a stock `velocity.toml` binding 25577 *before* Velocity
ever generates one, so the release's own default never happens here. A real proxy
then came up healthy, loaded its plugin, answered its control endpoint — and
never became ready, because the readiness ping went to a port nothing was
listening on.

**Before changing a constant on evidence about an upstream project, find the
layer that actually decides it in our deployment.** Usually that is the image's
entrypoint, and it is usually one readable file. The same question applies to
every "the docs say" fact about a container: the docs describe the software, and
we run an image that configures the software.

Keep the evidence *both* ways in the code. The constant's KDoc now says what
Velocity's own default is, why this differs, and what it would take to make the
value a request rather than a claim ([[proxy-image-contract]]) — otherwise the
next reader repeats the correction with the same good evidence.

## A remedy hedged as "at minimum" can be weaker than the defect it is filed for

Round 24 flagged that rotating the control token leaves every backend
undrainable while `reachable` and `compatible` both read true, and prescribed:
*"at minimum the version handshake should report whether authentication is
required"*. Tracing it took two minutes and the remedy does not close the case.
In a rotation **both ends agree** that authentication is required — the
container simply holds the old value — so an `authRequired` flag reads `true`
from a plugin that is refusing every call. It detects only the configuration
mismatch (one end has a token, the other does not), which is the neighbouring
defect.

What detects the flagged one is an **authenticated** call, and the pass was
already making one and discarding its variant: `assertBackends` calls
`channel.state()` and matched only `Answered`. Branching on `UNAUTHENTICATED`
there cost three lines, no wire change, and no new field on a status type that
would have rippled into `:store` and `:api`.

Two things to carry:

- **"At minimum" is the auditor flagging their own uncertainty, not setting a
  floor.** Treat the *finding* as established and re-derive the remedy from the
  mechanism, exactly as for a confidently-prescribed one.
- **Before adding a field or a wire change, look for the call the pass already
  makes and throws away.** A discarded `when` branch is the cheapest possible
  enforcement point and it is invisible to a grep for the thing you want to
  detect.

## A remedy names a property, not a layer

Round 25 offered two fixes and asked for one with a reason. The chosen one was
*"make it an operator-settable field with a default"* — and "field" reads as the
server-definition YAML, which would have been a lockstep `:schema` + `:store` +
`:core` change for a value that has nothing to do with one server.

The properties the remedy actually asked for were: a default, settable, still
hash-bearing, revertable. A `ReconcilerConfig` entry plus an environment variable
in `:app` satisfies all four in one module. **Pick the layer whose lifetime
matches the value**: this one tracks `:velocity-plugin`'s compile target, so it
belongs to the orchestrator build rather than to a proxy document, and a
per-proxy field would have invited a pin the bundled plugin cannot load. Say
which layer you chose and what it costs — here, reverting needs a restart and
bumps no generation, so it does not lift a permanent failure.

The other half of picking one of two: **say what the one you rejected leaves
open.** (a) would have exempted containers created before the entry existed and
left the mechanism live for every future bump, and it adds a second derivation of
the canonical spec — the shape that has produced repeated defects here.

## The prescribed remedy can *introduce* the next defect, and the old suite says so

Round 33 prescribed "make the `drain = null` writes conditional on
`stopDispatchedAt == null`". Written exactly that way it kept a record alive past
the container it described: on the proxy path the drain record is inherited across
the create, so the pass after a replacement was built saw a stamp beside a
brand-new container and drained it — for ever. The suite caught it in one run, on
an *existing* test that counts creates ([[record-lifetime]]).

Two things to carry:

- **A remedy that makes a value survive longer needs an expiry that is a fact about
  the world**, not the negation of the condition that used to delete it. Here that
  is the observation: `CREATED` and `SANDBOX_ONLY` cannot be the container that was
  signalled, and `Absent` is the retirement.
- **Run the whole module before believing a one-line conditional**, and read a
  pre-existing red as the prescription being incomplete rather than the test being
  stale — which is this file's oldest rule, arriving from the other direction: not
  "the fix breaks a test that was right", but "the fix creates a defect the old
  test was already watching for".

## A demonstrated hole can come with a mechanism that is not real

Round 36 demonstrated three holes in a source scan and was right about all three.
One of them was stated as *"Kotlin 2.4.10 resolves enum entries context-sensitively
in `when`, so `SANDBOX_ONLY -> true` compiles"* — and against this build's compiler
it does not: every entry reads `Unresolved reference`. The hole was real by a
different route (`import mcorch.core.WorkloadState.SANDBOX_ONLY`, legal in every
Kotlin version and one IDE quick-fix away), so the fix stood and the mutation that
scores it had to be rewritten.

**Compile the shape before writing the entry that scores it.** A finding of the form
"X is expressible and your check cannot see it" has two halves, and an auditor can
verify the second by reading while taking the first from a release note. Under this
harness a non-compiling mutation leaves no XML and reads as UNKNOWN, which is a
failure and not a catch — so believing the stated mechanism would have cost a round.
And when the mechanism turns out to be wrong, say so **in the instrument**, with what
would make it true later: a Kotlin release enabling that resolution is exactly when
somebody needs the second entry. See [[prove-the-test-can-fail]] for the sibling rule
about signature changes turning mutations into UNKNOWNs.

## A defect *description* can be the declined remedy's counter-scenario, inverted

The strongest instance yet, and it cost nothing only because I measured before
building. A brief described the flapping-escalation item as *over*-reporting: an
anchor surviving healthy waiting so a recovered endpoint escalates as though it
had been failing throughout. The code does the **opposite** — `blocked()` deletes
a retryable failure, so an endpoint failing every other pass never escalates at
all. Four hours of simulated alternation, twenty-five observations, `attention`
FALSE at every one ([[flapping-escalation]]).

Where the inverted description came from is worth knowing, because it will happen
again: it is almost word for word the counter-scenario I wrote when I *declined*
a proposed fix a few rounds earlier. **A declined remedy's cost and a live defect
read identically once they are one hop from the source** — both are a paragraph
saying "and then it escalates on a fault one second old".

**How to apply:** when a brief hands you a defect in prose, reproduce it before
designing against it — a scratch test in the existing harness that prints the
status every pass, thrown away afterwards, is twenty minutes. Two things make it
worth it beyond catching an inversion: the run tells you the *reachable* scenario
(mine needed players online, which the prose never mentioned), and the control —
the same scenario with the one condition removed — is what proves the machinery
is sound where it applies rather than broken everywhere. Report the measurement,
not the correction: "here is what twenty-five passes did" ends the disagreement
in one round, and it is not an accusation.

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

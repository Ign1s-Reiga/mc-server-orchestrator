---
name: invariants-need-an-enforcement-point
description: An invariant held by every call site doing the right thing is not enforced — collapse it into one function whose return type carries it, distrust any comment counting call sites, and pin wiring that no input can exercise by asserting on the source (shapes only: unconditional application, exits, gates, classification rather than lists, and the call site rather than the file as the unit; prefer a constructive unreachability argument to a survey of inputs, pin every premise of it including which object reaches the callee, and cover two gates in series with an assertion about the side effect rather than the refusal)
metadata:
  type: feedback
---

When a safety rule is stated as "every place that does X also does Y", it has no
enforcement point and will be broken by the next place that does X. Make the
rule a **return type**: one function that performs X and hands back the result of
Y already applied, so a caller cannot obtain the first without the second.

**Why:** round 17's critical. "A positive player count voids the save
confirmation" lived in four branches of `DrainController`, each calling
`forgetSaveEvidence` in the same expression it read `probe.online`. It held. The
argument that it held was a KDoc sentence carrying a *maintained count of the
call sites*, and a fifth reader — a re-probe after a confirmed save — recorded
three players and voided nothing. Neither the sentence nor any test noticed,
because nothing anywhere asserted the rule as a rule. The fix was
`DrainStatus.readPlayers(probe, at): PlayerReading`, whose `Occupied` case
carries a drain already voided, plus a unit test of *the function* rather than of
a scenario — the defect was a caller nobody had thought of, and a scenario tests
one caller.

**How to apply:**

- **A comment that counts call sites is a defect waiting.** This project has now
  written that comment three times: once wrong about the set it claimed, once
  left correct-looking by a change that added a reader, and once ("it cannot
  fire") falsified by a branch that was there all along. If you are about to
  write "there are exactly N such places", that is the signal to remove the
  places instead.
- **Enforce the clause that is uniform; leave the ones that genuinely differ.**
  `readPlayers` decides the positive-count case and deliberately returns no drain
  for an unanswered probe, because the three callers disagree about silence for
  good reasons (one aborts, one tolerates it because a container inside its stop
  grace period is *expected* to go quiet, one keeps a confirmation it just
  earned). Folding those into one rule would be the same mistake pointing the
  other way. Say in the KDoc which half the type enforces.
- **A partial collapse is worth it.** One caller — the pass-entry occupancy in
  `advance` — takes the reading and declines the voided drain, because adopting
  it there would change which state the resume ladder lands in. That exception is
  visible in one expression with its reason beside it, which is the whole
  difference from four invisible ones.
- Reuse of a *constant* is the same hazard one level down: `evidenceGap` was
  reused as the bound on a whole re-save lap, and a lap contains a save the schema
  sizes at minutes. Before reusing a duration, say what quantity it measures and
  what the new one measures.

## A read-point rule cannot bind a step that reads nothing

Round 18, and it is the same defect one level out. `readPlayers` enforces "a
branch that **reads** a positive count is handed the voided drain". `holdSeal`
reads no count — and at `DEREGISTERED` it runs *before* the zero-player gate — so
the type never got the chance, and a proxy control endpoint that stopped
answering parked a drain still claiming a save a player had outlived.

**Find the point where the facts are written down together, not where one of them
is read.** The rule that actually held was "*recording* a drain in a pass whose
probe reported players implies voiding", and the pair lives on `DrainProgress`,
which the reconciler writes onto one status side by side. Two questions that
locate it: which type holds *both* facts, and which function is the single exit
every producer goes through. Here that was `DrainController.advance` — worth
splitting a function into `advance`/`advanceOnce` to create the exit if there
isn't one; it is cheaper than renaming a constructor parameter at twenty sites,
which was the other design I costed.

**A net and a fix are not the same thing even when they produce the same record.**
Both halves shipped, and either alone kept the regression test green — a sabotage
that changes nothing is a finding, so I traced it. The pass-entry adoption is what
makes every *decision* in the pass see a drain with no confirmation; the
record-point rule repairs what is *written*. Today nothing decides to stop before
the gate, so they agree — but no repair of a record can un-stop a container, and
the net's value as a defect signal (it logs at error) depends on the primary
keeping it unreachable. Say which is which in the test docstring, or the next
reader deletes the one that "does nothing".

## When the enforcement point cannot be behavioural, pin the wiring by shape

The net-and-fix pair above has a property that took another round to name: **the
primary makes the secondary unreachable, so no input can make the secondary
fire, so no scenario test can pin it.** Delete either line and all 216 `:core`
tests stay green. That is not a gap in those tests; it is what "unreachable by
construction" means. The only thing left standing between the surviving half and
a container stopped on an outlived save is a docstring — the same protection
that failed in rounds 17 and 18.

The answer is a test that reads the module's own sources.
`velocity-plugin`'s `TransferNeverKicksTest` was already doing this for a
different reason; `core/src/test/kotlin/mcorch/core/DrainWiringTest.kt` does it
for wiring. Four assertions worth copying the shape of:

- `advance` **binds** the rule's result and its only `return` returns that bound
  name — follow a name the regex captured, never a literal line, so a rename or a
  rewrap stays green and a deletion does not.
- `advanceOnce` is `private` and has exactly **one** call site, which lies inside
  `advance`'s line range. That is what makes "single exit" a fact rather than a
  sentence.
- The pass is stepped with the *adopted* reading, not the drain it was built from.
- `stopWorkload` has exactly **two** call sites, one in each gate's function.

That last one is the direct answer to this file's own rule: if you are about to
write "there are exactly N such places" and you cannot collapse them, make the
count a test. Two is the honest number and the class KDoc had been claiming one
since `awaitStopped` learned to re-issue a stop.

**A structural test needs a different red-proof.** Behavioural sabotage cannot
work on it, so sabotage the *wiring*: remove the call at the record point,
replace the adoption with the unvoided drain, add a third `stopWorkload` site as
a dead private function. Each reddened exactly one test and nothing else — which
is simultaneously the red-proof and the evidence for the finding that motivated
the test. Report both halves; "only this test failed" is the load-bearing half.

## What a structural pin may carry, and what it may not

Round 20 mutated the real source four ways and **every one stayed green** against
the pins above while restoring round 18's critical. The division of labour that
came out of it is the durable part:

- **A rule's content is behavioural; only "it is applied unconditionally" is a
  shape.** Following a bound name survives renames and rewraps — which is what it
  was sold on — and is no defence at all against a predicate wrapped round the
  call site or narrowed inside it. Assert the binding's *right-hand side is the
  call and nothing else*, which is a short expression to pin, and put the rule
  itself in a function a unit test can call with every input. If the rule has
  nowhere a test can reach it, that is the finding: extract it.
- **An exit is a `return` token, not a line that starts with one.** `?: return x`
  and `if (c) return x` are exits and a prefix match sees neither. Strip string
  literals and trailing comments before any keyword scan, or the keyword gets
  found in prose.
- **Assert the gate, not the location.** "Two calls, one in each of these two
  functions" says nothing about what stands above them. "Every call site's
  enclosing function also calls `mayStop`" survives a third legitimate site being
  added correctly, and refuses one added carelessly.
- **Classify, never enumerate.** A file-set scan written as a literal list of
  paths is a maintained list wearing a test's clothes, and the next `Node`
  implementation has to be edited past it — which is exactly the seam this
  project protects. The honest form: a file that names `stopWorkload(` either
  *declares or overrides* it, or it is a caller, and there is one caller.

Scope matters as much as shape: a claim written "in this codebase" pinned by a
scan of one file is false in the direction that matters, because a stop added to
a teardown or a rescheduling path is outside the scanned file *by construction*
and is precisely what a drain audit goes looking for.

## Presence catches a deletion; a narrowing needs a scenario

Round 21. "Every stop's enclosing function also calls `mayStop`" is a **presence**
pin: it catches the token being deleted and is green against
`!mayStop(…) && !playersEvacuated`, which keeps the token, the count and the
enclosing set. `playersEvacuated` is true of every drain that reaches `STOPPING`,
so the narrowed gate is an unconditional re-issue — at a container an operator may
have restarted underneath the drain, which is invariant 2 with a live session in
it. Round 20's rule was "assert the gate, not the location"; the rule now is that a
gate's *content* is behaviour like any other rule, and its branch needs a scenario.
Two cheap ways to find the branches that have none:

- **Grep the branch's own message.** A detail string that appears nowhere in the
  test sources means nothing has ever reached that branch.
- **Ask what the narrowing reads.** A field that is constant by the time the branch
  runs makes the narrowing an unconditional bypass rather than a special case.

Some branches cannot be reached at all: `DrainController.stop`'s own `mayStop` is a
backstop that `DEREGISTERED` decides first, so a narrowing *there* is invisible to
every test. Same category as round 18's net, same answer — say so in the docstring
rather than invent a scenario that cannot exist.

## A scan has an alphabet, and the motivation names what is outside it

The stop scan was keyed on `stopWorkload(` while its own KDoc gave **rescheduling**
as the reason it existed — and rescheduling reaches `Node.removeWorkload`, outside
the alphabet by construction. Before trusting a source scan, list the verbs that do
the dangerous thing and check each is covered; they may need *different* claims,
because they carry different arguments. A stop ends a running container and needs a
gate; a removal refuses one at the interface, so all it needs is to be decided in
one file.

## Classify the call, not the file

A file-level partition — "a file naming `stopWorkload(` either declares it or calls
it" — is bought off by `private suspend fun stopWorkload(node, grace) =
node.stopWorkload(…)`, which is exactly what somebody writes when they need a stop
in two places, and which moves the whole file onto the *performing* side. The unit
is the call, and performing means a call inside an **`override`** of the verb: a
wrapper cannot write `override`, a distributed `Node` implementation can. The same
correction applies to any "which files may do X" test.

## The unit of a scan has to be smaller than where the danger would be written

Round 22, and it is round 20's "classify the call, not the file" arriving one
level lower. The removal pin listed the deciding **files** and read
`["…/Reconciler.kt"]`. Rescheduling — the case the whole widening was performed
for — is reconcile-loop work, so it lands in `Reconciler.kt`, which was already
on the list carrying `teardown` and `teardownProxy`. A third, fourth or tenth
removal decided there left the list unchanged, and the vacuity control beside it
(`naming(v).size > deciding(v).size`) stayed true as well. Right verb, right
alphabet, wrong unit.

**Ask where the thing you are protecting against would actually be written, and
make the assertion's unit smaller than that.** `path to enclosingFunctionName`
per call site, not a set of paths. It stays a review trigger and it still fails
open on a new `Node` implementation, because what is enumerated is *decisions*
and an `override` is not one.

## A constructive argument, not a survey of inputs

The same round: I defended leaving one gate's condition unpinned with "invisible
to every possible input". The auditor accepted the conclusion and flagged the
argument, because the stronger form was available — `stop` has one caller, whose
one branch hands it the *same* object the caller's own gate just tested with the
same arguments in the same pass, and whose other branch returns without stopping.
Nothing reaches it, rather than nothing anybody thought of. "Invisible to every
input" is the round-18 and round-19 sentence, and both were enumerations that a
later reader falsified.

Two follow-ons:

- **The facts a constructive argument rests on are call-site counts, so they go in
  a test.** Otherwise the argument is the KDoc counting call sites that this file
  opens by banning. Assert the count *and* the visibility that makes the count
  complete: "one caller in this file" means nothing if the callee could be called
  from another one.
- **A mutation that will not compile can be an enforcement point you did not
  know you had.** Widening `stop` to `internal` is rejected outright — it would
  expose a `private`-in-class parameter type — so the visibility half has no
  mutation. Write *why* where the mutation would have gone, and keep the
  assertion for the day somebody widens both.

## A borrowed constant carries the guarantees of the type it came from

`goingRoundInCircles` bounded a re-save lap with `stopGracePeriod` as a stand-in
for `saveTimeout`, on a schema guarantee that one exceeds the other. Two ways
that goes wrong, and both generalise:

- **The guarantee had a subject.** It is `PaperServer`'s (`SpecInvariants`);
  `ProxyLifecycleSpec` deliberately has none. The proxy case was safe only via an
  unwritten reachability argument, which is how round 17's exemption came to be
  widened.
- **The borrowed value was operator-tunable, in a range that changed the
  meaning.** Capped at two hours, so a long grace period set for an unrelated
  reason bought a two-hour escalation latency on a defect whose honest lap is a
  minute.

Before substituting quantity A for quantity B: name the subject that guarantees
the relation and check every implementation shares it, and ask what the bound
means at A's extremes. The fix was to put the real quantity on the interface —
`DrainSubject.saveTimeout`, `Duration.ZERO` for a workload with no world, which
is an answer about the subject rather than a placeholder needing a reachability
proof.

## Pin every premise, and name the one that decides dead from live

Round 23. The constructive argument above ("nothing reaches it") had **three**
premises and the test pinned two — one caller, and that caller reached from a
branch that has already asked the same question. The unpinned one was that the
*same object* reaches the callee: `stop(pass, drain)` and
`stop(pass, drain.copy(…))` are both well-typed, and under the second the
backstop answers a question nothing upstream asked. That is the premise deciding
whether the thing is dead code (fine unpinned) or a live gate whose narrowing no
test can see, and it was the one left in prose.

- **Count the premises of a constructive argument and pin each.** Two out of
  three reads as "asserted rather than left in prose" and is not.
- **The arguments are premises too.** The gate read `contract`,
  `containerStartedAt` and `now` off the *pass*, so forwarding the pass unchanged
  carried as much as forwarding the drain. Assert the whole argument list.
- **Follow, never restate.** Read the callee's parameter names off its own
  declaration and require the call to forward them — a rename stays green, a
  substitution reddens. Restating the literal `drain` catches only the second.

## Two gates in series need an assertion about the side effect

The same round, and it is the answer to "this presence check is coarse". When a
dangerous side effect needs gate A **and** gate B to agree, neither weakened
alone does harm; only the composite does. So the instrument for the pair is an
assertion that reads **what reached the runtime** — `node.stops.shouldBeEmpty()`
— not one that reads which refusal was recorded, because a refusal's wording
names which gate spoke and can be satisfied by the gate that is not under test.

Two consequences worth keeping: a coarse structural pin on one gate is tolerable
when a composite assertion exists, and *that* is the argument to write down
rather than tightening the scan; and those side-effect assertions must be marked
as load-bearing, or somebody "strengthens" them into a check on the recorded
failure and quietly loses the composite.

## Where an invariant is enforced decides which failures are survivable

Round 24, and it is the counterweight to everything above. A `require` in a type
is the strongest enforcement point available — and in this codebase it is
sometimes the *wrong* one, because of where it throws.

`Reconciler` builds its `Pass` (and so the `WorkloadSpec`) **before** the
terminating-definition exemption, and `rejectDefinition` records `PERMANENT`
with no exemption of its own. So an `IllegalArgumentException` out of a
`WorkloadSpec` or `StorageRequest` `init` makes that server permanently
unreconcilable — **drain and delete included**. For a rule about a value an
operator supplies that is a world-holding server nobody can retire, which is the
state that ends in a manual `crictl stop`. And a `require` cannot be told the
definition it came from is on its way out.

The split that came out of it, now written in `WorkloadSpec`'s `init` and in
`StorageRequest.Persistent.mountPath`:

- **A type's `init` may only enforce what a *planner* can get wrong.** Blank
  hash, blank hostname, two assets at one path — closed sets in this repo,
  reachable only by a code bug, and freezing is correct because the repair is a
  code change.
- **Rules about operator- or store-supplied values belong at the node**, as
  `NodeException.Rejected` from `HostPaths`. Same permanence, same message, and
  it fails the *create* — the operation that is actually wrong — while the drain,
  the stop and the teardown carry on, because none of them asks the type
  anything.

The tell is a second arrival route: `spec.storage.mountPath` comes from YAML
*and* from a stored row through `DefinitionCodec`, which does not re-run the
reader's validation. **Ask of any `require`: can this value reach here from
outside the compiler?** If yes, it belongs where refusing costs a create.

The same round produced the timing version of it. `HostPaths` correctly refuses
a proxy whose control plugin is missing, and correctly calls it permanent — but
the create is the *last* step of a replacement, so the question was first asked
after the drain, the stop and the removal had all succeeded. `Node.checkWorkload`
asks it before the teardown commits by running the create's own derivation and
discarding the result: one enforcement point, asked earlier. **A correct refusal
at the wrong moment is still a defect**, and the fix is not a second check, it is
an earlier call to the same one. Note that the *classification* may legitimately
differ between the two callers — the pre-flight records `RETRYABLE` because
freezing a running proxy stops the routing sweep — and that is a caller's
decision, not a second enforcement point.

## A record is an invariant too, and it needs the same enforcement point

Round 28. `sealRequestedAt` was written by one of the **seven** sites that shut a
workload's login path, and the six that did not were invisible: nothing *decides*
on the field, so no scenario can see a missing stamp except by reading a status in
exactly the right state. The pattern to recognise: *"X happens at seven places and
the note that X happened is written at one."* Same answer as the rules above —
put the record on the value the work returns (`SealHold.recordedOn`), so a caller
cannot carry on without it — plus a structural pin, because the behavioural half
can only ever cover the site a scenario drives.

**Assert the record together with the thing it is a record of.** `sealRequestedAt`
alone is green against a build that stamps without sealing; the wire flag alone
says nothing about what the operator is told. The pair is the assertion.

## Re-derive a mutation harness's anchors, and read what its misses mean

The same round changed five of `drain-wiring-mutations.sh`'s literals out from
under it, and the script said so rather than passing: *"the source contains 0
occurrences of … re-derive this mutation before trusting a green run"*. Two
follow-ons worth keeping:

- **Shape the source so the anchor can be one contiguous block.** A comment landing
  in the middle of the block a mutation replaces makes its literal carry prose;
  moving the comment above the block is free and keeps the harness honest.
- **A MISCAUGHT that names your new test is a finding, not noise.** Three existing
  mutations reddened one extra case each — the new scenarios are genuinely
  sensitive to those defects — so the claim is widened *and the reason written
  beside it*. An entry whose claimed set is stale is the harness lying about its
  own subject.

## A rule about a pair cannot be enforced where only one half is visible

Round 30, and it is the sharpest form of this file's rule so far. `LifecycleSpec`
validates `stopGracePeriod` **against** `saveTimeout`; a ceiling on the first was
applied inside `LocalNode.stopWorkload`, which is handed the first and not the
second — so it could reduce one half below the other and break a relation the
schema had already checked. Nothing was wrong with the ceiling; it was in a place
that could not be right.

- **Before clamping, normalising or defaulting a field, ask what it was validated
  *against*, and whether this layer can see that.** If it cannot, the layer is
  wrong however sound the rule is. The tell is the same one as round 24's: a value
  with a second arrival route, plus a cross-field `init` that route does not re-run.
- **The fix that holds is a parameter type whose factory takes both.**
  `Node.stopWorkload(handle, StopGrace)` where `StopGrace.of(requested,
  saveTimeout)` is the only constructor — the pair cannot be split by a caller,
  and the derivation lands where both fields already sit side by side.
- **At a seam, prefer the type over the implementation, and say which bound is
  whose.** The policy ceiling belongs to the interface and travels in the type; the
  runtime bound stays with the implementation, because where containerd's
  arithmetic wraps is not a fact about `Node`. A test that pins the first inside
  one implementation is a test *a second implementation is not required to pass*,
  which is the seam quietly assuming there is one node.
- **Then pin who may read the raw field.** The type bounds the value; nothing
  bounds who reads the field it came from. A source scan asserting the declared
  field is read only inside the derivation is what stops a fourth reader quoting a
  number the runtime was never given — and it replaces the "there are three
  callers" sentence this file bans.

**A fix derived from a general property belongs at every value with that
property.** The ceiling's argument was "this becomes a transport deadline"; a
sibling field became one on a longer call and went unbounded for a round. When
writing the justification for a bound, read it back as a predicate and grep for
everything it is true of. Round 31 found the *third* one the same way and by the
same failure: the note claiming it was safe was a survey of call sites.

## A type proves the rule ran; it does not prove what it ran on

Round 31, and it is the limit of the previous section rather than a
counter-example. `StopGrace.of(requested, saveTimeout)` makes the ceiling
unskippable — but the **floor is an argument**, so `StopGrace.of(x,
Duration.ZERO)` is a legal call from anywhere that disables it while handing back a
value whose type says the bound was applied. Two call sites pass zero today and
both are right (world-free workloads, in test code).

- **When a factory takes the other half of the rule as a parameter, the type's
  guarantee is conditional on the caller, so the call sites need their own pin.**
  The field half ("nothing else reads the raw value") does not imply the factory
  half ("nothing else applies the bound"), and closing one reads exactly like
  closing both.
- The pin is the same shape as every other one here: one entry per call site as
  `path to enclosingFunctionName`, over main sources, so a new implementation
  contributes none and a second derivation in the file that already holds the
  first is still visible.
- And say which subjects make the argument honest. Here the floor is real because
  `PaperDrainSubject` reads both halves off one `LifecycleSpec`; `ProxyDrainSubject`
  supplies a constant zero, correct only while a proxy holds no world — the single
  place a future change removes the floor without touching the ceiling's file or
  any test of it.

See [[localnode-test-gap]] for the sibling rule about decisions,
[[deadline-ceilings]] for the round this came from, and
[[prove-the-test-can-fail]] for why the unit test of the function was the one
that mattered.

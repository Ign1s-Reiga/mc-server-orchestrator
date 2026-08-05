---
name: prove-the-test-can-fail
description: Before trusting a green run, prove the check could have gone red — Gradle skips up-to-date tasks, virtual time hides races, a mutation set beats one sabotage, a precondition the compiler refuses is written as a reason where its mutation would go, a rename must sweep the retired claim and not just the identifier, and some checks belong in the type system instead
metadata:
  type: feedback
---

A green result is worth nothing until you have seen the same check go red.
Three ways it has silently meant nothing on this project:

**`:app:integrationTest` reports success without running.** Gradle marks it
`UP-TO-DATE`, finishes in ~2s, and the XML left in
`app/build/test-results/integrationTest/` is the *previous* run's. Use
`./gradlew :app:integrationTest --rerun`, and sanity-check the wall time: a real
run is 9–13 minutes. "6/6 in 2 seconds" is a stale read, and a coordinator
signed off on one.

**`runTest`'s virtual time hides races.** Its dispatcher is single-threaded and
deterministic, so an interleaving that needs two real threads never happens. A
loop-shutdown test written under `runTest` passed against code that was provably
broken; the same shutdown on `Dispatchers.Default` failed 4 times in 200. If the
bug is a race, a `runTest` test is a guard, not a regression test — say which it
is in the docstring rather than letting a future reader assume it caught
something.

**A control assertion can itself be unfindable.** When asserting "X does not
appear in these bytes", assert in the same test that something which *should*
appear does. That has already caught a version of a leak test where the needle
was never findable at all, so the security assertion passed for the wrong
reason.

**A sabotage that does not compile reads as a passing suite.** `if (false && …)`
to disable a branch is rejected by this build's compiler settings, and the run
printed no "tests completed" line and left the previous XML in place — so
grepping for failures showed none, and the test looked green against the
sabotage. Two rules from it: sabotage by *changing a value* (`since = now`), never
by adding dead code, and treat "no failures **and** no test-count line" as a
build failure to investigate rather than a pass. Check the exit status or the
`BUILD` line, not just the failure list.

**A negative assertion satisfied by a *downstream* guard proves nothing about the
one you changed.** A test for "the proxy's player count does not move the gate"
asserted no stop, no save, no deregistration — all three of which the `SAVING`
gate delivers whatever the earlier step concluded. It passed against a build that
believed the proxy. The sabotage is what found it: the fix is to assert on where
the drain *ends up* (which failure it records, whether it records a block at all),
because that is the first observable that differs. Generalise it — when the
property you changed sits upstream of another guard, the negative assertions are
about the downstream guard and you need a positive one about yours.

**An instrument that reads zero by construction measures nothing.** A hot-loop
test asserted `sweepsStarted <= 6`, in a scenario whose setup removed the
destination from the fake proxy — so the call was refused *before* recording
anything and the counter was structurally zero. The assertion could not fail.
Before trusting a bound, assert that the quantity it bounds actually moved in
that scenario; and prefer measuring the property directly (here: no pass reports
`Progressed` once the step is running) over a side counter that a refusal path
can bypass.

**A whole-file revert red-proofs the wrong thing when a scenario has several
guards.** Reverting `DrainController.kt` made a stale-block test go red on its
*first* assertion (the block's wording, fixed in the same commit) and never
reached the block assertion at all — which passed under a targeted sabotage that
carried the block forward, because an abort later in the scenario had already
cleared it. The block assertion was vacuous and the whole-file red said nothing.
Do both: **revert the whole file to prove the commit matters, then sabotage the
single line to prove each assertion does.** A scenario that reaches an `abort`
resets most of the drain record, so any assertion downstream of one is suspect.

**How the loop spaces its own passes is part of the scenario, not a detail.** A
drain test that advances the clock by a fixed amount models a loop that does not
exist. At 2s throughout, save evidence never expires and the re-save under test
never happens; at 45s throughout it expires *before the stop is ever attempted*,
so the drain shuttles `DEREGISTERED` → `SAVING` without calling the runtime and
the test asserts against a state the reconcile loop cannot produce. Advance by the
poll interval after a `Progressed` and by a grown backoff after a `Retry` — the
alternation is the mechanism in every backoff-related drain defect so far.

The exception, and it needs saying in the docstring: **a requeue delay is a
floor, not a schedule.** When the defect *is* the loop arriving late — a
saturated orchestrator taking 40 seconds to get round to one server, which is
longer than a save confirmation survives — a fixed spacing is the mechanism, and
modelling the alternation would model the case that is not the problem.

**A sabotage that changes nothing is a finding about the code, not the test.**
Removing a stamp in `advance` reddened no test, and the reason was not a weak
test: the consumer already produced the anchor and wrote it back, so the earlier
line could not be observed *by construction*. The right response is to delete the
line rather than to invent a test for it — and deleting it turned out to fix a
real misdiagnosis, because stamping earlier made the anchor older than the cycle
it measured. Two lines writing one field is the same smell as two derivations of
one fact.

**Pick the scenario's numbers so that only the guard under test can pass it.**
A drain test for "a player joined during the save" let them build for *sixty*
seconds before logging off — and sixty seconds is longer than
`saveEvidenceMaxGap`, so `dropUnusableSaveEvidence` voided the confirmation and
the world was protected whatever the branch under test did. The sabotage passed.
Ten seconds — inside the gap — made the same assertion fail on its own. When a
scenario has two guards in series, every duration in it is a choice about which
one you are testing; write the reason for the number in the test.

**One fix can contain two independent claims, and a single sabotage proves only
one.** Sabotaging the voiding in `readPlayers` left the data-loss scenario green,
because in that branch the protection is *which branch is taken*, not what it
voids — the confirmation was never written, so there was nothing to void.
Sabotaging the re-probe reddened it. Both are real and both needed pinning, and
the code comment was quietly wrong about which one carried the safety until the
sabotage said so. Treat a sabotage that leaves a test green as a claim about the
code to go and check, not as a weak test.

**"Unobservable in this harness" is a claim about the fixture, not about the
harness.** I ruled that the gap between `confirmedAt` and `observedAt` in the
save-confirming pass could not be pinned, and an auditor endorsed it; both of us
had read what `FakeNode.defaultExec` does today rather than what `onExec` can
express. It routes **every** command, `mc-monitor` included, so a ping that costs
five seconds makes the two instants five seconds apart on the recorded status and
a fused single read fails the assertion. Before writing "this cannot be tested",
name the seam the fixture already has for the neighbouring case — here, the same
hook another test uses to make a save take sixty seconds.

**Do not key a test on a constant the fix will change.** A discriminator written
against `MAX_TRANSFER_ATTEMPTS` turns red when the limit is corrected, for a
reason unrelated to what the test is about. Key on the facts that actually differ
between the two behaviours — the state the drain lands in, whether it recorded a
block — not on which of two failures a limit happened to produce.

## A mutation set, and a verdict read from the report

One sabotage proves one assertion. When a test carries several independent claims
— a structural test usually does — the honest red-proof is a **set of mutations,
each expected to redden exactly one named test**, kept as a script that applies
them to a working copy and restores it (`scripts/dev/drain-wiring-mutations.sh`
is the one for the drain wiring). Two things make it worth the trouble:

- **Deletions are the easy half.** A deleted line reddens almost anything; what
  slips through is the *plausible edit* — a narrowed predicate, an early return
  inserted above a rule. Write the mutations a careful person would actually make
  after reading the comments, and keep the deletions only as controls that the
  harness reaches the assertions at all.
- **"Exactly one test went red" is the load-bearing result.** It says the
  assertions are independent and none is carrying another. Print the failing test
  names, not a pass/fail.

**Read the verdict from the JUnit XML, never from the build's exit status.** A
mutation that fails to compile also exits non-zero, and counting that as "caught"
is the non-compiling-sabotage trap above pointing the other way. No report is an
unknown; a report with no `<failure>` is a green.

**And read it per test case, not per class.** `grep -q "<failure"` on the class
report scores *any* red as "caught", so one broken shared helper — a renamed
function that an exactly-one-declaration `check` throws on, a class-init failure, a
count assertion a legitimate third call site reddens — makes every mutation *and
both controls* pass at once, and the run prints "all caught" having proved nothing.
The controls cannot detect it, because the controls fail the same way. An auditor
and I both signed off on a 10/10 run that was equally consistent with the real
thing and with total vacuity. Carry the expected test-case names in the tuple and
require the red set to equal them exactly: a name missing means the assertion did
not bite, a name extra means something else broke and the run says nothing about
either.

**The harness needs its own red-proof, and that proof needs its own vacuity
check.** `S1` in `drain-wiring-mutations.sh` breaks the whole class the way a
rename does *and* applies a real mutation on top, then requires the verdict to
refuse the result — but it must also assert the class went red at all, because a
self-test whose own mutation fails to compile refuses for the wrong reason and
reads exactly like a working one.

**A mutation set proves what it ran, and the claim usually reaches further.** Twice
now the set has mutated the shape that is caught *by construction* and left the
real one unwritten: round 21's D6 put a stop in a file that was off the list
anyway (the wrapper shape needed D13), and round 22's D14 put a removal in a file
off the *removal* list, while the path the test exists for — rescheduling — lands
in the one file already on it. Both times the run was honest about what it ran and
was read as evidence for the general claim. **For every "X anywhere else is caught"
assertion, ask which mutation lands in the location that is already listed**, and
write it *before* the fix, so a not-caught entry states the finding in the
instrument's voice rather than the auditor's.

**The name in the report is not the name in the source.** A test taking an
injected parameter is reported as `the plugin is mounted …(Path)` — every
`@TempDir` test is — so a stripper that only knows the empty `()` pair produces a
name no claim can ever match, and *every* entry reads MISCAUGHT. That is
indistinguishable at a glance from twelve real findings, and it fails in the
opposite direction from the vacuity trap: total noise rather than total green.
Strip a trailing parenthesised list, and read one MISCAUGHT's two lists side by
side before believing a run that reddened everything.

**A delimiter that can occur in the payload is a harness lying about its subject.**
The mutation table was `name|file|class|literal|replacement`, and the first literal
containing `||` split into the wrong fields, applied a replacement nobody wrote,
and reported the resulting compile failure as UNKNOWN. It was one step from
scoring a defect that did not exist. `@@` now, plus a field count per entry.

**A precondition the compiler refuses gets no mutation, and the reason goes where
the mutation would have gone.** That is a convention now, not an exception made
once. Be right about *why*: under this harness a non-compiling entry leaves no
XML (`execute` deletes the report first), `judge` reads no report as UNKNOWN, and
the run counts it a **failure** — it does not "score as caught". Either way the
entry proves nothing, and the written reason is what tells the next reader the
gap is a ruling rather than an oversight, plus what to write the day the compiler
stops refusing it.

**When a rename retires a claim, grep the claim — not the identifier.** A rename
performed on the rule that the audit trail reads these strings updated the
harness's `$DECIDED` variable and left the prose one level up still asserting the
retired version, in the header, which is the string read first. The identifiers
are what the tooling sees; the sentences are what a human uses to decide whether
a green run means anything.

**A test that first demonstrates the defect inherits every rule the defect rests
on.** Round 25's pin-exit test asserts the login path is *shut* while the
replacement drain waits, before asserting the operator's lever reopens it — so a
mutation that released the seal on a *block* reddened it as well as the case
written for that rule, and the run reported MISCAUGHT. The extra red was a true
dependency, not noise: with the seal released on a block there was never a
blackout to have an exit from. Declare the full red set in the mutation entry and
say why, and keep a second entry that isolates the rule on its own — weakening
the demonstration half to make one mutation tidy would delete the evidence that
the defect is real.

## When the check cannot exist, move it to the compiler

Structured-logging argument order is untyped and untested anywhere in this repo —
at `LOG.error(pattern, vararg Any?)` everything is `Any?`, so a swap between a
`Boolean` and an `Int` compiles and prints garbage. One such line shipped and
review slid past it because the correct branch sat directly beside it.

The fix that generalises is **not a log-capture harness**: it is wrapping the call
in a function whose parameters are named and typed so that neighbouring arguments
differ in type. The same mistake then does not compile. Prefer this wherever the
failure mode is positional — a test only guards the sites somebody remembered to
write one for, and the guard has to be re-remembered at every new call site.
Prove it the same way: make the swap and confirm the compiler rejects it.

**Why:** each of the above produces a confident green that means nothing, and the
cost lands on whoever trusts it next. Two rounds of diagnosis here went after the wrong
component partly on the strength of results that had not actually run.

**How to apply:** for any bug fix, stash the fix
(`git stash push -q -- <main sources>`), run the new test, confirm it fails,
then `git stash pop`. If it does not fail, the test is not testing the fix —
find out why before continuing. Where a race cannot be made deterministic,
measure it instead: run a few hundred iterations on a real dispatcher and report
the rate before and after. See [[audit-remedies-are-hypotheses]] for the related
case where an *existing* test is what arbitrates.

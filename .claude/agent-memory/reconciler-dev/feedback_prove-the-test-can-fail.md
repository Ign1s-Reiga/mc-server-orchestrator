---
name: prove-the-test-can-fail
description: Before trusting a green run, prove the check could have gone red — Gradle skips up-to-date tasks, virtual time hides races, and some checks belong in the type system instead
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

**Do not key a test on a constant the fix will change.** A discriminator written
against `MAX_TRANSFER_ATTEMPTS` turns red when the limit is corrected, for a
reason unrelated to what the test is about. Key on the facts that actually differ
between the two behaviours — the state the drain lands in, whether it recorded a
block — not on which of two failures a limit happened to produce.

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

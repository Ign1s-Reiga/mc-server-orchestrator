---
name: prove-the-test-can-fail
description: Before trusting a green run, prove the check could have gone red — Gradle skips up-to-date tasks, and virtual time hides races that only a real dispatcher shows
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

**Why:** each produces a confident green that means nothing, and the cost lands
on whoever trusts it next. Two rounds of diagnosis here went after the wrong
component partly on the strength of results that had not actually run.

**How to apply:** for any bug fix, stash the fix
(`git stash push -q -- <main sources>`), run the new test, confirm it fails,
then `git stash pop`. If it does not fail, the test is not testing the fix —
find out why before continuing. Where a race cannot be made deterministic,
measure it instead: run a few hundred iterations on a real dispatcher and report
the rate before and after. See [[audit-remedies-are-hypotheses]] for the related
case where an *existing* test is what arbitrates.

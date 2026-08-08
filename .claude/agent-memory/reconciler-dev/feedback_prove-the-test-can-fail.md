---
name: prove-the-test-can-fail
description: Before trusting a green run, prove the check could have gone red — Gradle skips up-to-date tasks, virtual time hides races, a level something else re-asserts must be pinned at the wire, a mutation set beats one sabotage, a mutation carrying a value a constructor rejects measures the constructor, a precondition the compiler refuses is written as a reason where its mutation would go, a rename must sweep the retired claim and not just the identifier, a grep only proves what is in your own tree so check the base before disputing a quotation, and some checks belong in the type system instead
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

**A level that something else re-asserts cannot be pinned by reading the level.**
Round 27's delete test asserted `plugin.proxyAdmits` was `false` after a run of
passes, and passed against the very defect it was written for: the release opened
the door and the resume's own `holdSeal` shut it again before the assertion looked.
The instrument is the **record of the calls** — no `PUT /v1/proxy` asserted `true`
after the seal landed — which the neighbouring round-26 test already used. Ask of
any assertion on a door, a flag or a registration: *what re-asserts this between
the event and the read?* If anything does, assert on the wire, and take the
baseline at the moment the property starts holding rather than at the end. See
[[level-triggered-seal]].

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

**And the stripper has to be fixed in *every* harness, not the one that found it.**
`control-plugin-mutations.sh` learned `sub(/\([^()]*\)$/, "", name)` when the
`(Path)` trap was first found; `drain-wiring-mutations.sh` was left stripping `()`
alone, and it went unnoticed for rounds because no entry there named a `@TempDir`
test until one did. The moment it did, the entry read MISCAUGHT however the
mutation had gone. **When a harness bug is fixed, grep the sibling harnesses for
the same expression before closing it** — a fix applied to the instrument that
happened to expose the bug is half a fix.

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

**And a retired premise usually has no identifier to grep at all.** Round 38's
was *"the re-issue is what finishes it"*, corrected in `:cri`'s KDoc and operator
message in the same round it was measured — and left standing in **three**
`:core` sentences that share no token with it: *"it is the re-issue that ends
it"*, *"a shorter grace period cannot make a stuck container stop any faster than
the runtime's own kill already will"*, and *"the stop is re-issued, the container
goes"*. The audit named one of the three. What found the other two was grepping
the **verb of the mechanism** (`re-issu`) across every module and reading each
hit for what it *asserts* rather than what it mentions. Do that before closing a
correction, and expect the count to be more than one: this project has now had
"the correction landed in one file of N" twice in consecutive rounds.

**Adding a test can grow an existing mutation's red set, and that is a result to
read rather than a nuisance to suppress.** Round 26's new retryable-abort test
spends six passes in `blocked` proving the seal is never handed back, so D23 —
the mutation that releases the seal on a block — went from two names to three and
the harness reported MISCAUGHT until the entry was updated. The extra red was a
true dependency both times this has happened. **After adding a test to a class a
mutation names, re-run the whole harness before trusting it**; a MISCAUGHT there
is the instrument working.

**A type change silently turns mutations into UNKNOWNs, and an UNKNOWN is not a
catch.** Round 30 changed `Node.stopWorkload`'s parameter to a value type, and the
two mutations that add a *stop somewhere it does not belong* both declared
`grace: kotlin.time.Duration`. They stopped compiling, the harness left no report,
and the run reported them UNKNOWN — which is the honest answer and reads, at a
glance, like the type having caught them. It has not: the type is a **bound**, not
a gate, and a stop decided in the wrong file is exactly as expressible as before.
Re-derive the mutation's parameter types after any signature change and confirm it
goes red again. The general form: after changing a signature, every mutation that
*writes new code* against it needs re-deriving, not only the ones whose literal you
edited.

**A self-closing tag breaks an open..close regex, and the harness then names the
wrong test.** JUnit writes a *passing* case as `<testcase … />` and a failing one
as `<testcase …>…</testcase>`, so a non-greedy `>(.*?)</testcase>` runs from one
open tag past every self-closed case to the next close tag and attributes the
failure it finds there to a test that passed. A throwaway harness of mine reported
seven MISCAUGHT verdicts naming tests that were green, and the code was fine.
Split on the *opening* tag instead. (`drain-wiring-mutations.sh`'s awk is already
correct — it tracks the last name seen.) The tell: a mutation in file A reddening
a test that cannot possibly read file A.

**Put the precondition in the tool, not in your head.** After the loss below I
gave the red-proof runner a first line that refuses to start on a dirty tree
(`git status --porcelain --untracked-files=no`). That is the difference between a
rule I have to remember at 2am and one I cannot break — and it is the same move
this project keeps making in the code it audits, one level up.

**"Commit first" binds any script that restores with `git checkout --`, not just
the named harnesses.** I wrote a throwaway red-proof runner that mutates one file,
runs the suite and restores it — and pointed it at `Node.kt` while holding
uncommitted edits to `Node.kt`. The restore reverted my work, silently, and the
tell was almost invisible: the file simply **stopped appearing in `git status`**,
which reads like nothing happened rather than like something was lost. I only
caught it because the next thing I did happened to grep for text I had written.
Two habits: commit before pointing any restoring tool at a file, and after a
restore, check that the files you edited are *still listed as modified* rather
than checking that the tree is clean — "clean" is the failure here, not the
success.

**Signing fails in a worktree agent session, and the failure is a timeout rather
than a refusal.** `gpg-agent.conf` here sets `pinentry-program pinentry-curses`,
which launches against a `/dev/pts` that is not this session's, waits, and dies:
`gpg: signing failed: Timeout`, and `git commit` aborts having written nothing.
`keyinfo --list` shows the keygrips uncached and the key is passphrase-protected,
so `--pinentry-mode loopback` has nothing to feed it either. There is no way to
produce a signed commit from here. **Commit with `--no-gpg-sign`, put the reason
and the `git commit --amend -S --no-edit` recovery in the commit message body,
and lead the hand-off report with it** — an unsigned commit in a repo that is
420/420 signed is a thing the dispatcher checks, and silence about it reads as
the flag having been forgotten rather than as an environment limit. Losing the
work to a failed commit is the worse outcome of the two.

**Two more from the same script.** `git add -A` after a red-proof swept the
harness and its 660-line log into the commit; stage explicitly or clean up before
committing, and read `git show --stat` before moving on. And a habitual
`--no-gpg-sign` went onto a commit in a repo that signs everything — check
`git log --format=%G?` rather than assuming the flag you always pass is still the
right one.

**Never run a mutation harness in the background while you are still editing.** It
backs the sources up at start and restores them at exit, so every edit made during
the run is either silently reverted at the end or mutated underneath the run — and
a `pkill` that catches a child rather than the script leaves a mutation *committed
to your working tree*. It cost me a reverted edit and five leftover mutations in
two files, one of which I could not `git checkout --` because it also held real
work. Commit first, then run the harness, then edit again — the same rule as
sabotaging by hand, for the same reason.

**A killed harness did not die, and that was a bug in the harness.** Both scripts
carried `trap cleanup EXIT INT TERM`, and a signal handler that only cleans up
**returns**: the run restored the sources, deleted its backup directory and carried
on, so every later mutation was applied with nothing left to restore it. A TERM I
sent mid-run left two mutated files in the working tree, reported the next entry
UNKNOWN, and kept printing verdicts. That is also where the previous round's
"orphaned harness racing its own retry" came from — it was never orphaned, it was
never stopped. `trap 'cleanup; exit 130' INT` and the TERM equivalent are the fix,
in *both* harnesses. Two rules from it: **a trap handler that does not exit is not a
kill**, and when a run has to be stopped, check `git status` afterwards rather than
trusting the trap — the tree being committed first is what makes that a one-line
recovery.

**And the full drain harness outruns a ten-minute tool timeout**, which kills it
mid-mutation and leaves that mutation in the working tree — the same wreckage as a
`pkill`, from a completely ordinary invocation. Run it detached rather than in the
foreground. Having committed first is what made recovery a one-line
`git checkout --` of a file I knew held no work of mine; run a subset first
(`./scripts/dev/drain-wiring-mutations.sh D62 D63`) to iterate, and the whole set
once, detached, when nothing is left to change. It is slow in a way worth planning
around: round 38's full drain run took about 80 minutes for 89 mutations.

**A test can assert a true property through a path that bypasses the code
implementing it, and only a mutation says so.** A test for "a pass that
establishes nothing does not spend the ledger" was built on an `UNKNOWN`
observation — which `advance` answers *before* any step runs, so those passes
never reach the funnel the rule lives in. The property held, the test was green,
and making the rule's neutral branch do the opposite reddened nothing. The tell
was a `got=[]` on a mutation I was confident about; the fix was to find the
branch's genuinely reachable instance (`STOPPING` inside the grace period) and
rebuild the scenario on it. **When a mutation of the exact line under test
catches nothing, suspect the scenario reaches a different line** — an early
return above it is the usual culprit, and `advance`-style dispatchers are full of
them. Same family as the structurally-zero counter below, arriving through
control flow rather than through a fake.

**A `@TestFactory`'s case name is the dynamic test's name, not the method's.** A
mutation entry naming `a row written without each field reads as declared` scored
MISCAUGHT while the report held `DrainStatus.faultLedger` — the parent method name
appears nowhere in the XML. Read one report before declaring an expected set
against a parametrised class; the same trap as the trailing `(Path)` on an injected
parameter, from the other end.

**The harness sets the cadence, so a timing defect is invisible to every test
that picks its own intervals.** `FlappingEscalationTest` advanced two to eight
minutes per pass; the real loop spaces retries from `Backoff` (1s, 2s, 4s, 8s,
16s…). A rule that fired after six passes therefore fired in *thirty seconds* in
production and in *half an hour* in every test, and nine green tests could not
see it. When a rule counts passes and the loop schedules them, at least one
scenario has to walk the scheduler's real delays — and say in its docstring that
the cadence is the subject, or somebody will "tidy" it to match its neighbours.

**A red-proof's extra red is usually a true dependency; declare it rather than
weaken the test.** Removing the ledger's floor reddened two tests, not the one
claimed — because the drain's own healthy passes drive the count negative before
the scenario starts, so a second test's premise (`ledger >= 1`) fails too. That is
the instrument working. The entry gets both names.

**A mutation must use a value the constructors accept, or it measures the
constructor.** A red-proof of mine configured a CRI timeout to `Duration.ZERO`;
`CriTimeouts.init` rejects that, so the `:core` object holding the constant failed
class-init and **76** tests went red — the one under test among them. The run was
indistinguishable from a real catch and attributed nothing, which is the
per-test-case vacuity trap above arriving through the *value* rather than through
the report parser. Re-run with a legal value (`1.hours`) and it reddened exactly
one. **Before believing a red set, ask whether the mutation could have broken
something every test shares**; a red count far above the claim is the tell, not a
bonus.

**A grep proves what is in *your* tree, and a worktree agent's tree is not the one
the dispatcher is quoting.** I was quoted a sentence from `CriClientConfig.kt`,
could not find it, checked `git log -- <path>` and saw the file had not moved since
a commit my base contained, and reported the citation as unfindable — twice, the
second time with the greps laid out as evidence. Every one of those checks was
honest and the conclusion was wrong: `main` was **five commits ahead** of my base,
and the sentence arrived in one of them. `git log -- <path>` answers "when did this
move *in my history*", which is silent about commits I do not have, so it reads as
confirmation when it is nothing of the kind.

Before disputing a quotation, establish which tree it came from:

- `git rev-list --count <base>..main` — is the branch behind at all? This is the
  one question I did not ask, and it is the cheapest.
- `git merge-base --is-ancestor <commit> HEAD` — do I actually have the commit the
  claim rests on?
- `git show main:<path> | grep …` — read the file *on the branch being quoted from*,
  not the one checked out.

`git merge-base HEAD main` returning the base is **not** evidence the branch is
current; it returns the same value whether `main` has moved or not, and I read it
as reassurance. The failure is symmetrical and worth naming for that reason: every
symptom of reading a stale base is identical to the dispatcher having invented a
quote. Being confidently wrong in *that* direction is expensive — it impugns a
correct correction — so the base check comes before the accusation, not after.

What did survive: recording the disagreement in a form that stayed **cheap to
settle** — exact lines, exact command — is what let one `git show` end it instead
of another round of assertions. And [[audit-remedies-are-hypotheses]]' rule held in
both directions, including against my own rebuttal.

**The family this belongs to: an instrument that looks like a result.** Three in
two rounds — the vacuous mutation above, a waiter polling for a sentinel the
harness never prints (reaped, and it reads exactly like the harness dying
mid-run), and a `pgrep` for a pattern that matched its own command line so the
"stray process" check could never come back clean. Each produced output that
*looked* like evidence. The common defence is to ask, before trusting any check,
**what this would print if the thing it measures were absent** — and if the answer
is "the same", the check is decoration.

**Wait for the harness *process* to exit, not for its verdict line.** Both
harnesses print `all N mutations caught` and then keep working — the EXIT trap
still has sources to restore. Polling the verdict and immediately running
`git status` showed me `LocalNode.kt` **truncated to zero bytes**, which reads
exactly like the leftover-mutation wreckage this file warns about, and I nearly
reported it as one. It restored itself a minute later and the tree was clean.
Wait on the process; if a run really does leave a mutation, that is the moment
the check means something.

**And bracket the pattern, or `pgrep -f` matches the shell running it.** Checking
whether the harness was still alive with `pgrep -f "control-plugin-mutations"`
inside a `bash -c` whose command line contains that string returns its own PID,
for ever — so the loop never ended and the earlier "RUNNING" reading was also
self-matched. Write `pgrep -af "control-plugin[-]mutations"`. This is already in
this file as one of the *"instrument that looks like a result"* family, and I
still wrote it again in the very command meant to check for it: an anecdote does
not stop a habit. The rule, not the story: **any `pgrep -f` in a command that
contains the pattern needs a bracket, and if a liveness loop never ends, suspect
the check before the subject.**

**Poll for the harness's own verdict line, and read it out of the script before
polling.** This file used to say to poll for an `exit=` line. Neither harness
prints one — both end with `all $ran mutations caught, each by the test case it
names` on stdout, or `$failures mutations were not caught as claimed` on *stderr*.
Polling for the wrong sentinel does not fail loudly; the waiter simply never
returns and is eventually reaped, which arrives looking like the harness died
mid-run when it had in fact finished cleanly. Three of mine were killed that way
in one round. Two rules: `grep` the script for its final `echo` before writing a
waiter, and make the waiter's condition match **either** outcome — a condition
keyed only on the success line hangs for the whole run precisely when the run
found something.

**A green `./gradlew build` did not mean the integration sources compiled.**
Neither `:app`'s nor `:cri`'s `check` depended on `compileIntegrationTestKotlin`,
so `EndpointTimeout` becoming `EndpointRequest.timeout`'s type left
`VelocityProxyControlIT` passing a raw `Duration` for a whole round with every
build green — and it would have surfaced only to whoever next had a containerd.
Both are wired into `check` now (compiled, never run: a compile needs no runtime).
The general form: **when told "the build covers X", run the task and watch it
appear**, and prefer wiring the coverage over trusting the claim. `--rerun-tasks`
is the cheap check — a task that never prints did not run.

**`git stash pop` in a shared worktree can pop somebody else's stash.** A
`git stash -q -u` on a clean tree creates nothing and silently succeeds, so the
`pop` that follows takes `stash@{0}` — which here was an unrelated agent's WIP from
another branch, and it landed as a conflict inside
`.claude/agent-memory/code-reviewer/`. Nothing was lost (a conflicted pop keeps the
entry), but a `git checkout .` would have. Never pair a blind `stash`/`pop` around
a `checkout`; use `git stash create`/`git stash store` with an explicit ref, or
copy the files, and check `git stash list` **before** and after.

**Do not pipe a mutation harness through `tail`.** I ran one as
`./harness.sh 2>&1 | tail -70`, saw "2 mutations were not caught", and had thrown
away every `MISCAUGHT`/`GREEN`/`UNKNOWN` line — those go to stderr *interleaved*,
so the tail kept the successes and dropped the findings. It also masks the exit
status behind `tail`'s. Redirect the whole run to a file and grep it.

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

**A board scores flips, and the defect can be a *missing argument*.** Round 34's
critical was `stopIsInFlight` classifying an observation without the fact that
decides it. No entry in a 67-mutation suite could go red for a discriminator
nobody had written — there is no expression to flip — and the suite was, honestly,
green. What found it was a **hand comparison of two rules that read the same
observation**: `containerIsDown` splits the case on `hadContainer` and says in its
own comment why getting it wrong kills a live server. Two rules for one question
is the smell; when you see it, diff the rules rather than adding a mutant. And
after the fix, mutate the defect at its *new* address: a fact that is now a
parameter can be answered with a constant at one call site, which is invisible to
every other assertion and is exactly how the original defect would come back.

**A fixture can be shaped so the missing discriminator is invisible.** The unit
test for that rule built every observation with a container id — `SANDBOX_ONLY`
included, which a real node never produces (`WorkloadView.observe` takes that
branch precisely when there is no container). So the case the rule got wrong could
not be expressed, and an exhaustiveness control read as complete while one of five
states was classified from data that does not occur. The same trap one level up:
`FakeNode` named every container after the server, so ids were identical across
recreations and "is this the container the drain signalled" was **true by
construction** — an identity test would have reported a record correctly retired
in exactly the scenario where it outlived its container. Ask of any fixture: *does
the value I am handing this exist in the field, and does anything about it differ
between the two cases I am claiming to tell apart?*

**Two more of mine, both green under the sabotage written for them.** An
idempotency assertion — *"a second pass records the same storage"* — could not see
a record re-stamped with `now` on every pass, because `Harness` runs on a clock
that only advances when a test advances it: the same `now` twice compares equal.
Advancing past `statusHeartbeat` between the passes fixed it and does a second
job, because an unchanged status **inside** the heartbeat is not written at all,
so the comparison had been one row read twice rather than two rows written. And a
fixture injected the volume name `survival-01-world` to prove a refusal *carries*
the record — which is exactly what the definition derives (`metadata.name` plus
`-world`), so the assertion held whether the pass carried it or re-derived it,
under the very defect it was written for. **A fixture value must differ from the
value the defect would produce, and a comparability test must move the clock**;
"equal" is the cheapest possible green.

**A KDoc that spells out a block-comment terminator ends the KDoc there.** Writing
the delimiters out while documenting a comment stripper closed the doc four lines
early and the rest of the prose compiled as Kotlin — twenty unresolved-reference
errors pointing at symbols nowhere near the edit, which is what the failure looks
like from the outside. If a paragraph must discuss block comments, name them in
words. The generalisation: **when compile errors name symbols you did not touch,
suspect that something you wrote changed where the parser thinks code begins**
rather than reading the errors at face value.

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

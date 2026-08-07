#!/usr/bin/env bash
# Red-proof for the drain's wiring assertions.
#
# `core/src/test/kotlin/mcorch/core/DrainWiringTest.kt` asserts the *shape* of
# DrainController's safety claims — that each rule is applied unconditionally, that
# the value it produces is the value that leaves, and that every call which ends a
# container is decided at a call site named there. The unit used to be the *file*,
# and D15 is the mutation that retired that claim: the one path the assertion exists
# for lands in a file the list already carried. `SaveEvidenceTest` asserts what the
# rules themselves do, and `DrainTest` asserts the stop gates a scenario can reach. A
# structural test cannot be sabotaged behaviourally, so its red-proof has to
# sabotage the wiring, and one sabotage is not enough: these assertions fail
# independently of each other, and four of the mutations below restored round 18's
# critical while the whole suite, DrainWiringTest included, stayed green when the
# twentieth audit found them.
#
# Each mutation is applied to a working copy of one source file, the test class
# that must catch it is run, and the file is restored — mutated source is never
# committed and never survives this script, including on failure or interrupt. The
# JUnit reports those runs leave behind are removed on the way out for the same
# reason: they describe mutated source, and nothing in an XML report says so.
#
#   D1..D4   the wiring defects the twentieth audit demonstrated. DrainWiringTest.
#   D5       the same narrowing written where the fix moved the predicate to. It is
#            behaviour now, so SaveEvidenceTest is what has to catch it — a remedy
#            that only relocates a defect has to be traced to its new home.
#   D6       a stop added outside DrainController, which is where a drain audit
#            looks and where a file-scoped scan cannot see.
#   D7       a stop that keeps its place and loses its gate.
#   D8       the adoption applied to the wrong drain — the second address the same
#            extraction created, and only expressible since it became a function.
#   D9       the record-level rule narrowed inside itself.
#   D10      the stop gate *narrowed* rather than deleted: it keeps the token, the
#            count and the enclosing function, so no structural assertion can see
#            it. The twenty-first audit's finding, and the reason DrainTest now
#            carries that branch.
#   D11..D12 the single-exit precondition: `advanceOnce` private, with one caller.
#   D13      a stop reached through a same-named wrapper, which used to move the
#            whole file onto the "performs it" side of the classifier.
#   D14      a *removal* decided outside Reconciler. `removeWorkload` ends a
#            container too, and the scan was keyed on `stopWorkload` alone.
#   D15      a *second* removal decided inside Reconciler, which is where a
#            rescheduling path lands — the file D14 avoids by construction. Pinning
#            the deciding *files* left this one green while D14 was caught, so the
#            18/18 result was honest about what it ran and not evidence for the
#            claim. The unit is the call site.
#   D16      a second route into step 7. `DrainController.stop` re-asserts `mayStop`
#            as a backstop that no scenario can reach, and the argument for leaving
#            its condition unpinned is that its one caller has just asked the same
#            question. That argument is about this source, so it is pinned and this
#            is the edit that expires it.
#   D17      the same argument's third premise: that the *same* drain, and the same
#            pass, reach the backstop. A caller that hands `stop` anything else has
#            made it a live gate whose narrowing no scenario can see. The mutation
#            is small on purpose — it costs almost nothing at runtime and costs the
#            whole unreachability argument.
#            The argument's remaining precondition — step 7 being private, so
#            callers in this file are all the callers — has no mutation, and the
#            reason is written beside where one would go.
#   D18..D19 the replacement pre-flight on the kind that holds worlds, in its two
#            halves: the call removed (structural), and the call kept while its
#            answer is dropped (behavioural). Only the second is a plausible edit,
#            and no structural assertion can see it — which is the division of
#            labour D10 established for gates, arriving one layer out.
#   D20      the pre-flight narrowed back to the mounts alone: the subset that let
#            a missing secret through the question and into a create that had
#            already been drained for.
#   D21      the premise the step-2 waiver's narrowness rests on — a Paper subject
#            given a seal and no router, which is well-typed and would waive the
#            seal for a *backend* whose proxy stopped answering.
#   D22..D23 the seal's compensating edge, both directions: removed from `abort`,
#            and added to `blocked` — the plausible edit, since both park in
#            `DRAIN_FAILED`, and the one that would refill the population a delete
#            is waiting to drain.
#   D24..D25 the Velocity pin: dropped between the configuration and the planner,
#            and split so that the container's environment and the spec hash come
#            from different expressions. The second is the quiet one — a proxy
#            created running one build and recorded as running another is drained
#            and recreated on every pass, for ever.
#   D26      an unbuildable replacement's failure discarded on the branch where the
#            container has also exited, which is the branch where an operator most
#            needs to be told which of the two to fix.
#   D27      the seal's compensating edge stripped of its condition, which is how it
#            was written when the twenty-sixth audit found it: a *retryable* park
#            gives the login path back, and no later pass can take it again while
#            anybody is on, because the gated resume lands in `blocked`. D22 removes
#            the edge, this one over-applies it, and the two tests are different.
#   D28..D29 the last asking before steps 6 and 7 — the window the pass-level
#            pre-flight exempts — removed, and then widened past `REPLACEMENT` so
#            that a create can wedge a delete.
#   D30      the twenty-seventh audit's critical: the seal release keyed on the
#            failure class, which is one input to the gate rather than its answer.
#            A delete is exempt from that gate, so a permanent abort during one
#            reopened a fleet's login path with the passes still running.
#   D31..D32 the same defect written where no scenario can see it — an abort site
#            deriving its own answer, and one kind's gate copying the clause
#            instead of calling it. Both agree with the reconciler on every path a
#            test drives, and both are how the second derivation gets written.
#   D33      step 2 taken off the gated resume, which is the mirror: a drain whose
#            *first* seal failed with players on could then never reach `holdSeal`
#            again, so the door stayed open and the wait for zero could not end.
#   D34..D35 the same site with step 2 kept and its *record* dropped — the
#            twenty-eighth audit's first critical, scored once as a scenario and
#            once as a shape. The stamp lived at the `DRAIN_REQUESTED` arm alone
#            while seven sites shut the door, so a fleet blacked out by its own
#            resume was reported as one that "keeps taking players".
#   D36      the seal release's outcome discarded. Best effort inside the one gate
#            that stops anybody retrying it: the door stays shut, the permanent
#            class freezes the passes, and a definition edit resumes straight back
#            into the seal.
#   D37      a block clearing a *permanent* failure. Nothing is stopped by it, and
#            the report is what decides whether somebody reaches for `crictl stop`.
#   D38      D37's consequence, and the twenty-ninth audit's first finding: the
#            permanent-failure gate armed by a drain that is merely *waiting*. A
#            block parks in `DRAIN_FAILED` and keeps the standing failure, so the
#            pass that recorded it froze the loop — and a generation bump, the only
#            remedy on a server nobody has deleted, was then spent on one blocked
#            pass. The mutation removes the clause that reads the block, which is
#            exactly the narrowing a reader who only knows the sixteenth audit's
#            version of this gate would undo.
#   D39      the operational ceiling on the stop grace period taken off the value
#            the node stops with. The call is deadlined off that number, so what it
#            costs is a reconcile worker parked at a container that will not exit,
#            with no effective timeout — and the runtime's own bound is 292 years
#            away, so nothing else refuses it. Re-derived for the thirtieth audit:
#            the ceiling now lives in the argument's *type*, so the mutation is in
#            Node.kt, and D39D scores the same edit in the drain scenario.
#   D40      the *floor* under that ceiling, and the thirtieth audit's first
#            finding. `stopGracePeriod` and `saveTimeout` are validated as a pair;
#            a ceiling applied to one half by a consumer that cannot see the other
#            inverts the relation, and a grace period below the save timeout is
#            SIGKILL part-way through Paper's shutdown save. D40D is the same edit
#            scored end to end through a drain.
#   D41      the sibling ceiling, on the exec that carries `save-all flush`. Same
#            argument as D39 — the number becomes a gRPC deadline — on the longer
#            of the two calls, which is where it went unwritten for a round.
#   D42      a block's blackout sentence appended rather than leading. Nothing is
#            stopped and no state moves, so only an assertion about the *message*
#            can see it: `:api` renders "waiting, not stuck — " plus this string,
#            and a fleet table truncates whatever comes second.
#   D43      the overdue check reading the *declared* grace period rather than the
#            derived one, which is a container reported overdue against a number the
#            runtime was never given. The type bounds the value; only a source scan
#            can bound who reads the field it came from.
#   D44      the same scan's other half: a second *application* of the ceiling,
#            supplying `Duration.ZERO` as the save timeout. The floor is an argument,
#            so a caller can disable it and still hold a value whose type says the
#            ceiling was applied. Two call sites already pass zero legitimately and
#            both are world-free test code, which is why the claim is about main
#            sources.
#            D39 and D40 each grew a third name this round, and it is a result rather
#            than a nuisance: the new residual case reads the ceiling's arithmetic
#            *and* what the node was sent, so both mutations genuinely change what it
#            sees.
#   D45..D51 the *bottom* of the range those ceilings bound at the top: a duration no
#            request can be built from, which is an `IllegalArgumentException` thrown
#            inside a drain and outside every typed catch on the way up — a requeue
#            with no status write, so nothing is recorded, nothing escalates and the
#            server cannot be deleted. D45 and D46 restore that at each site whose
#            timeout comes off a definition. D47 and D48 swap the two classifications
#            for each other: they are opposite on purpose, because the proxy's row
#            cannot be repaired by an edit to the backend a permanent failure would
#            freeze, and the server's own row can. D49 removes a guard at the site no
#            input reaches, which is what the shape scan is for. D50 feeds a channel
#            the *other* `sealTimeout` — three reds, because a healthy default there
#            means the bad row never reaches a request. D51 routes the refusal into
#            the bucket that is never retried, which would leave a repaired
#            definition unable to save at all.
#   D52..D53 the thirty-third audit's critical, at the rule: a dispatched stop's
#            record deleted the moment the drain stops being *wanted*. D52 is the
#            half that makes the remedy itself a defect — a record that survives a
#            create describes a container that no longer exists, and the pass after a
#            replacement is built drains the replacement. D53 turns the retention off
#            and is scored twice, once as the rule and once as the paths.
#   D54..D55 the routing half, one per kind: a withdrawn cause converging over the
#            top of a stop it cannot recall. Reverting an edit is the documented way
#            to call off a replacement and it is the only lever an operator has, so
#            this is the pass an operator *causes*.
#   D56..D57 one clear back to `drain = null`, and the same clear asking the rule
#            about nothing. Neither reddens a scenario, and that is the finding:
#            behind D54's guard these sites are unreachable, so the shape is the only
#            instrument that can see them.
#   D58      the site the audit's prescription did not name. Refusing an edit is not
#            withdrawing a drain, so `forbiddenTransition` has no routing guard in
#            front of it and its clear is live — a `storage.mode` edit landing inside
#            the grace period of a stop some earlier edit asked for.
#   D59..D61 the thirty-fourth audit's two criticals, both consequences of the round
#            above. **Read the finding that came with the first before reading these
#            as coverage:** a board scores *flips of an expression*, and that critical
#            was a **missing argument** — `stopIsInFlight` classified `SANDBOX_ONLY`
#            without the fact that separates a sandbox this loop emptied from one
#            whose container the runtime has stopped enumerating. No entry here could
#            have gone red for a discriminator nobody had written, and the check that
#            found it was a hand comparison with `containerIsDown`, which reads the
#            identical observation and answers it the other way. What D59 and D60
#            score is the defect at the address the fix *created* for it: an arm that
#            can now be flipped, and a call site that can now answer the fact with a
#            constant. D61 is the second critical, where a refusal was conditioned on
#            `RUNNING` — a state the drain itself takes away — so the container exits
#            and the create applies the very definition the loop spent passes
#            refusing.
#   D61E     the fix for that second critical, and its own defect: the fallback it
#            kept for a status row carrying no storage record derives one from the
#            *edited* definition, so a row written before the field existed — the
#            population whose volume name is recorded nowhere else — is told
#            "ephemeral storage: there is no world to save" by the very pass refusing
#            to make it ephemeral. It reaches only those rows, which is why D61S's
#            scenario stays green under it.
#   D62..D64 the thirty-fifth audit's instrument, and the first entries here that can
#            go red for something **nobody wrote**. Rounds 33, 34 and 35 were one
#            defect three times — a fact modelled exactly in one place, approximated
#            at a new consumer asking a narrower question — and all three were
#            omissions, which a board of flips cannot score. D62 adds a
#            classification of a workload state that decides `SANDBOX_ONLY` with
#            neither the fact nor a word about doing without it; D62A gives the same
#            arm a comment that explains the branch without naming the fact, so that
#            "carries the argument" cannot be bought off by any comment at all; D63
#            folds a state into an `else`, which is how one leaves the scan's
#            alphabet entirely; and D64 moves a converge out of the `when` that
#            weighs it against the drain, which is the premise both `converge` arms
#            argue from.
#   D65..D67 the thirty-sixth audit on that instrument, and all three were **green**
#            against it — the two counts it balances fell together in each case, so
#            the alphabet control said nothing. D65 wraps a live arm's pattern the way
#            the formatter does, which takes the state off the `->` line and out of a
#            scan that read that line alone; D66 imports the entries and writes them
#            without their type, which the scan did not see at all — the audit gave
#            context-sensitive resolution as the way in and this compiler refuses it,
#            so the import is what makes the shape real; D67 classifies
#            with an `==` instead of an arm, which is what `:core/main` already did in
#            three places while the docstring claimed "every classification".
#   D68      the premise the drain's `SANDBOX_ONLY` abort argues from: it does not
#            read the fact at its own line, and is right only because
#            `containerIsDown(hadContainer)` has already returned for the other world.
#            The mutation answers that call with a literal, which leaves every token
#            the note names in place.
#   C1..C3   controls: the rule deleted outright, once per assertion arm. If these
#            do not redden, the harness is not reaching the assertions at all.
#   S1       the self-test. See below.
#
# The verdict is read from the JUnit XML, never from Gradle's exit status: a
# mutation that fails to compile also exits non-zero, and calling that "caught"
# would be the same false green in a different costume. No XML is an unknown.
#
# **And it is read per test case, not per class.** "Something in DrainWiringTest
# went red" is not the claim — the claim is that the assertion written for this
# mutation is what bit. Rename `advance` and `rangeOf`'s exactly-one-declaration
# check throws in two tests, so the class reddens for every mutation and both
# controls at once, and a run that proves nothing at all reports "all caught". The
# twenty-first audit reproduced that, having previously signed off on a 10/10 run
# from this script as independent verification. So each mutation carries the names
# it must redden, and the red set has to match exactly: a name missing means the
# assertion did not bite, a name extra means something else broke and the run says
# nothing about either. `S1` is the proof that this can fail — it breaks the whole
# class the way a rename would and requires the verdict to refuse the result.
#
# A mutation that no longer applies is a failure too, and a deliberate one: it
# means the source it was written against has moved, and whether the defect it
# describes is still expressible has to be decided by a human rather than assumed.
#
# **A precondition the compiler already refuses gets no mutation, and the reason is
# written where the mutation would have gone.** That is the convention, not an
# exception made once: an entry that cannot compile leaves no report, `judge` reads
# no report as UNKNOWN, and the run would count a failure that proves nothing about
# the assertion. The written reason is what tells the next reader that the gap is a
# ruling rather than an oversight, and what to write the day the compiler stops
# refusing it.
#
#   ./scripts/dev/drain-wiring-mutations.sh          run all of them, and S1
#   ./scripts/dev/drain-wiring-mutations.sh D3 C1    run some
#   ./scripts/dev/drain-wiring-mutations.sh S1       the self-test alone

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
RESULTS="$REPO_ROOT/core/build/test-results/test"
BACKUP_DIR="$(mktemp -d -t drain-wiring-XXXXXX)"

CONTROLLER=core/src/main/kotlin/mcorch/core/DrainController.kt
RECONCILER=core/src/main/kotlin/mcorch/core/Reconciler.kt
LOCAL_NODE=core/src/main/kotlin/mcorch/core/node/LocalNode.kt
# The seam itself. Since the thirtieth audit the stop's operational ceiling and
# the exec's live here rather than in an implementation, so the mutations for them
# moved with them.
NODE=core/src/main/kotlin/mcorch/core/Node.kt
PLANNER=core/src/main/kotlin/mcorch/core/proxy/VelocityWorkloadPlanner.kt
# The three files that build a node request out of a definition, plus the second
# place a control channel is constructed. A request that cannot be built is an
# exception thrown inside a drain, and where it is classified is the subject of
# D45..D51.
CHANNEL=core/src/main/kotlin/mcorch/core/proxy/ControlChannel.kt
PAPER_AGENT=core/src/main/kotlin/mcorch/core/paper/PaperServerAgent.kt
PROXY_AGENT=core/src/main/kotlin/mcorch/core/proxy/VelocityProxyAgent.kt
FLEET=core/src/main/kotlin/mcorch/core/ProxyFleet.kt

WIRING=mcorch.core.DrainWiringTest
LIFETIME=mcorch.core.DrainRecordLifetimeTest
RULES=mcorch.core.SaveEvidenceTest
DRAIN=mcorch.core.DrainTest
PROXY_DRAIN=mcorch.core.ProxyDrainTest
PROXY_RECONCILE=mcorch.core.ProxyReconcileTest
REPLACEMENT=mcorch.core.ReplacementTest
PLANNING=mcorch.core.proxy.VelocityWorkloadPlannerTest
STOP_GRACE=mcorch.core.node.StopGraceGuardTest
UNBUILDABLE=mcorch.core.UnbuildableRequestTest

# The test cases, by name. A mutation names the ones it must redden and no others.
EXIT='nothing leaves advance that has not been through the record-level rule'
CALLER='advanceOnce is private and advance is its only caller'
STEPPED='a pass is stepped with the drain the pass-entry reading voided'
DECIDED='the calls that end a container are decided at the sites named here'
BACKSTOP='stop has one caller, reached from a branch that has already asked mayStop'
ADOPTS='a pass entry adopts the confirmation clause of its reading and no more'
RECORDS='a recorded pass cannot carry a confirmed save beside a player count'
RESTARTED='a stop is not re-issued at a container that restarted underneath the drain'
ASKS_FIRST='every pass that decides to drain asks first whether the replacement can be built'
PREFLIGHT="the replacement pre-flight runs the create's own container derivation"
SAME_LINK='a Paper subject is given the same object as its seal and its router'
KEPT_RUNNING='a server whose replacement the node cannot build is not drained, and keeps running'
KEPT_PLAYING='players are not drained for a replacement the node cannot build'
RELEASES='a permanent abort that stops the passes releases the proxy login seal'
KEEPS_SEAL='a proxy drain waiting for players to leave keeps its login seal on'
RETRYABLE_SEAL='a proxy drain that parks on a retryable abort keeps its login seal on'
# The twenty-seventh audit's critical: the release keyed on the failure class alone
# was true of a permanent abort under an outstanding *delete*, whose passes carry on.
DELETE_SEALED='a permanent abort under a delete keeps the proxy login seal on'
# Its mirror: step 2 asserted on the gated resume, so a drain whose first seal failed
# with players on can still reach one.
FIRST_SEAL='a proxy whose first seal failed still converges once the endpoint comes back'
PARKED_ENDPOINT='a proxy with players online still parks when its control endpoint is dead'
# The twenty-eighth audit's first critical, in its two halves: the state the pair
# above cannot tell apart — sealed by the resume, then parked — and the shape that
# covers the six sites no scenario drives.
BLACKOUT_REPORTED='a proxy sealed by its resume reports the blackout when its endpoint drops again'
RECORDS_THE_SEAL='every step-2 assertion is recorded on the drain the pass carries on with'
# Its second: a permanence whose compensation did not land is retried rather than
# frozen, and its third, where a block finds a permanent diagnosis and keeps it.
RELEASE_RETRIED='a permanent park whose seal release fails keeps being retried until it lands'
WEDGE_SURVIVES='a permanent save wedge survives a player logging back on'
# The twenty-ninth audit's first finding, in its two halves: the kind that only
# loses its recoverability, and the kind whose frozen state is a fleet-wide
# blackout.
EDIT_NOT_SPENT='an operator edit is not spent on a player who logged on while the drain was frozen'
FLEET_LOCKED_OUT='an edit made while a proxy is populated does not leave the fleet locked out'
# Its third: the interface's own bound on the grace period it stops with. Both of
# these moved subject with the thirtieth audit's fix — they now drive a value the
# argument's *type* bounded, so the mutation for them is in Node.kt.
GRACE_CAPPED='a grace period containerd would invert is capped, not sent'
# Renamed when `:cri` took the deadline: the case is the same, its *reason* is not.
# The call is deadlined at `min(grace, stopDeadlineCap) + slack` now, so "because the
# call is deadlined off it" was a claim this ceiling no longer makes.
GRACE_STILL_CAPPED='the largest grace period the runtime honours is still capped to the widest a reader accepts'
# The thirtieth audit's first finding: the floor under that ceiling, and the
# residual the floor leaves behind at the runtime's own bound.
GRACE_FLOORED='a grace period is never capped below the save timeout it was validated against'
RUNTIME_REFUSES="a save timeout past the runtime's own bound leaves the stop refused, not silently inverted"
# The thirty-first audit's first finding: the *reachable* residual, where the floor
# raises the ceiling above MAX and the stop that goes out is a month long. It reads
# both the ceiling and what the node sent, so D39 and D40 each redden it as well —
# a true dependency, declared in their claims rather than worked around.
FLOOR_UNCAPPED='above a two-hour save timeout the ceiling is the save timeout, and a month-long stop goes out'
# ...and the factory half of the derivation: the ceiling is applied at one site,
# with the pair in front of it. The field half is ONE_DERIVATION above.
ONE_FACTORY='the stop grace ceiling is applied at one site, with the pair in front of it'
# ...and the scenario that drives both ceilings through a real drain, which is where
# the two consumers of one field are asserted against each other.
BOTH_CEILINGS='a store row past both ceilings keeps its grace above its save timeout, and its save exec bounded'
# The derivation's own enforcement point: the raw field is read in one place.
ONE_DERIVATION='the declared stop grace period is read only where it is bounded'
HANDED="the drain is handed the loop's permanence gate rather than deriving one"
GATED_RELEASE='the seal release is gated on the answer the abort was handed'
MID_DRAIN='a replacement that becomes unbuildable mid-drain parks before the stop'
DELETE_COMPLETES='a delete still completes while the node refuses every create'
PIN_EXIT='an operator can pin a proxy fleet back onto the build its containers were created with'
ONE_VALUE='a pinned Velocity build reaches the container and the hash as one value'
ARTEFACT='a proxy that exited and cannot be rebuilt reports the artefact, not the exit'
# The bottom of the same range the two ceilings bound at the top: a duration a
# request cannot be built from at all. Two behavioural cases on the proxy path, one
# on the save path, and the two shapes that cover the sites no scenario reaches.
PARKS_BACKEND='a proxy row with a zero seal timeout parks the backend drain and records why'
ISSUES_NOTHING='a second pass against the same bad row issues nothing and lands in the same place'
SAVE_REFUSED='a zero save timeout is recorded as a permanent drain failure with no exec sent'
CLASSIFIED='every request built from a definition is built where its refusal is classified'
CHANNEL_TIMEOUT='every control channel is given the seal timeout its message names'
# The thirty-third audit's critical: not what the dispatched-stop record says, but
# how long it lives. Two unit cases for the rule, one shape for the sites that ask
# it, and three scenarios for the paths that reach them.
RECORD_SURVIVES='a dispatched stop survives while the runtime still reports the container'
RECORD_RETIRED='a dispatched stop does not outlive the container it was aimed at'
RETIRED_BY_RULE='every drain record this loop retires is retired through the one rule'
BACKEND_REVERT='reverting the edit does not undo a stop that has already been dispatched'
PROXY_REVERT="reverting a proxy's edit does not undo a stop that has already been dispatched"
REFUSED_MID_STOP='an edit refused mid-stop does not delete the record of the stop'
# The thirty-fourth audit's criticals, both consequences of the round above. The
# first is the argument that rule was missing — and the finding that came with it is
# that **no entry in this table could have scored it**: a board scores flips of an
# expression, and a discriminator that was never written has nothing to flip. What
# these three entries score is the defect at its *new* address, which the fix
# created by making the fact expressible: a call site answering it with a literal.
SANDBOX_DECIDED='a sandbox the runtime reports no container in is decided by what this loop recorded'
UNREPORTED_MID_STOP='a runtime that stops reporting a container mid-stop does not re-admit the backend'
ONE_DERIVATION_OF_HISTORY='the fact that tells an emptied sandbox from an unreported container has one derivation'
TRANSITION_NOT_APPLIED='a refused storage transition is not applied by the container exiting underneath it'
# The thirty-fifth audit. The first is the fix above'"'"'s own fallback, reaching the
# rows with no storage record to carry forward — the population the volume name is
# recorded nowhere else for.
PREDATING_ROW='a status row that predates the storage field is not given a false one by the refusal'
# ...and the instrument the round was really about. Rounds 33, 34 and 35 were one
# defect three times, all three of them *omissions*, and this board scores
# inversions — so the three entries below are the first here that can go red for an
# argument nobody wrote rather than for an expression somebody flipped.
WORKLOAD_STATE_CLASSIFIED='every workload-state classification either takes the fact or argues at the SANDBOX_ONLY arm'
NO_ELSE_ARM='no workload-state classification hides a state in an else arm'
CONVERGE_ROUTED='every converge is an arm of the routing that asks the rule'
# The thirty-sixth audit, on the same instrument: two shapes that left its alphabet
# without moving either of its counts — a pattern the formatter wrapped, and an entry
# written without its type — and one classification syntax the docstring claimed and
# the scan never read.
BARE_ENTRY='no workload-state classification writes a bare enum entry'
SANDBOX_ABORT_ROUTED='the drain'"'"'s sandbox abort is reached only past the rule that separates the two sandboxes'

# Single-quoted throughout: these are literals, and one of them contains the
# quoting characters of two languages.

# The record-level rule, in `advance`.
RULE='val recorded = progress.dropSaveContradictedByPlayers()'
# The pass-entry adoption, in `advanceOnce`.
ADOPTION='val observed = drain.adoptSaveClause(reading)'
# The clause the adoption applies, in `adoptSaveClause`.
CLAUSE='is PlayerReading.Occupied -> unconfirmWorldSave()'
# The record-level rule's own predicate.
RECORD_RULE='    if (online == 0 || drain.worldSavedAt == null) return this'
# The re-issue's gate, in `awaitStopped`. Its first line is identical to the one
# in `stop`, so the comment under it is what makes the literal unique.
REISSUE_GATE='            if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap)) {
                // The same rule as in'
UNGATED_REISSUE='            if (!drain.playersEvacuated) {
                // The same rule as in'
NARROWED_REISSUE='            if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap) && !drain.playersEvacuated) {
                // The same rule as in'
# `advance`, with the annotation above it so the literal cannot match `advanceOnce`.
ADVANCE_DECLARATION='    @Suppress("LongParameterList")
    suspend fun advance('
# The tail of Reconciler.kt, for appending to.
RECONCILER_TAIL='        require(drainAttentionAfter.isPositive()) { "drainAttentionAfter must be positive" }
    }
}'
# A second call site, in the shape the class KDoc used to be blind to: a teardown
# helper that reaches the runtime directly. Never called, so nothing but the scan
# can see it — which is the point.
#
# Re-derived for the thirtieth audit, and the reason is worth keeping: the stop's
# grace period became a value type, so `grace: kotlin.time.Duration` stopped
# compiling and both this and STOP_WRAPPER below reported UNKNOWN. A mutation that
# no longer compiles proves nothing either way — it is not "the type caught it" —
# so the parameter follows the interface. The type is a bound, not a gate: a stop
# decided in the wrong file is exactly as expressible as it was.
STOP_ELSEWHERE="$RECONCILER_TAIL"'

private suspend fun teardownWithoutDraining(
    node: Node,
    handle: WorkloadHandle,
    grace: StopGrace,
) {
    node.stopWorkload(handle, grace)
}'
# The same thing wearing the name of the method it calls, which is what somebody
# writes when they need a stop in two places. Classifying *files* by whether they
# name `fun stopWorkload(` put this one on the "performs it, does not decide it"
# side and passed.
STOP_WRAPPER="$RECONCILER_TAIL"'

private suspend fun stopWorkload(
    node: Node,
    handle: WorkloadHandle,
    grace: StopGrace,
) {
    node.stopWorkload(handle, grace)
}'
# The tail of DrainController.kt, for appending to.
CONTROLLER_TAIL='                WorkloadState.RUNNING, WorkloadState.UNKNOWN -> null
            }
        }
    }'
# The path this test's own motivation names — a workload taken away so it can be
# brought up elsewhere — reaching the verb the scan used not to know about.
REMOVE_ELSEWHERE="$CONTROLLER_TAIL"'

private suspend fun makeRoomOnAnotherNode(
    node: Node,
    handle: WorkloadHandle,
) {
    node.removeWorkload(handle)
}'
# The same path, in the file it would really be written in. Rescheduling is
# reconcile-loop work, so it lands in `Reconciler.kt` — which is already on the
# deciding-files list, so a scan whose unit is the file cannot see this at all. D14
# is caught by construction and says nothing about it.
REMOVE_IN_RECONCILER="$RECONCILER_TAIL"'

private suspend fun relocateWorkload(
    node: Node,
    handle: WorkloadHandle,
) {
    node.removeWorkload(handle)
}'
# Step 7's declaration, as an anchor to put a second caller in front of. It has to
# be inside the class — `CONTROLLER_TAIL` is the end of the *file*, past the class,
# where a top-level function cannot see a private member at all.
STOP_DECLARATION='    private suspend fun stop(
        pass: DrainPass,'
# A second way into step 7, which is what the backstop'"'"'s unreachability argument
# says does not exist. `stop` re-asserts `mayStop` itself and no scenario can reach
# that re-assertion, so the argument for leaving its *content* unpinned is that
# nothing routes here except a branch that has already asked. This is that routing.
SECOND_WAY_IN='    private suspend fun stopWithoutTheLadder(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress = stop(pass, drain)

'"$STOP_DECLARATION"
# The one call into step 7. The backstop asks `mayStop` about whatever arrives here,
# and the argument that nothing can reach its refusal is that the `DEREGISTERED` arm
# has just asked the same question — of the same drain, off the same pass.
STOP_CALL='        if (router == null || drain.deregisteredAt != null) return stop(pass, drain)'
# The backstop handed a drain nobody upstream tested. The edit itself is the sort a
# careful person makes while tidying the record — the `Asserted` branch below stamps
# `deregisteredAt`, so why not this one — and its runtime cost is small: on this path
# there is no router, so it records a deregistration from a proxy that was never
# there. What it costs is the whole unreachability argument, because the question
# `mayStop` answers inside `stop` is no longer the question `step` asked. A backstop
# answering a different question is a live gate, and a live gate nothing can reach is
# one somebody can narrow with every test in the suite green.
DRAIN_SUBSTITUTED='        if (router == null || drain.deregisteredAt != null) return stop(pass, drain.copy(deregisteredAt = now))'

# The Paper pass's pre-flight, with the comment above it: the proxy path's call is
# written identically one screen down, so the literal has to carry its context.
PAPER_PREFLIGHT='                    // [replacementBlocker].
                    val blocker = replacementBlocker(pass, placement.node, cause)'
# The branch that uses the answer. Keeping the call and dropping its result is the
# edit a structural assertion cannot see, and the only one of the two that somebody
# would really write.
PAPER_BRANCH='                    when {
                        blocker != null -> converge(pass, placement.node, observation, blocker)
                        cause == null -> converge(pass, placement.node, observation)
                        else -> drain(pass, placement.node, observation, cause, binding)
                    }'
PAPER_BRANCH_DROPPED='                    if (cause == null) {
                        converge(pass, placement.node, observation)
                    } else {
                        drain(pass, placement.node, observation, cause, binding)
                    }'
# The pre-flight'"'"'s own derivation, and the subset it used to be.
PREFLIGHT_DERIVATION='            containerSpecFor(spec, SecretAccess.PRESENCE_ONLY)'
PREFLIGHT_SUBSET='            mountsFor(spec)'
# The waiver'"'"'s premise, at the one call site that establishes it.
SUBJECT_ROUTER='                router = link,'
# The seal'"'"'s compensating edge, in `abort`, with the condition that decides which
# aborts it belongs to. Since the twenty-eighth audit the call'"'"'s *answer* is bound
# too — whether the login path was left shut — so removing the edge means removing
# the release from the binding rather than removing a statement.
SEAL_RELEASE='        val heldShut = failureClass == FailureClass.PERMANENT && permanentFailureStopsPasses && releaseSeal(subject)'
SEAL_RELEASE_NONE='        val heldShut = false'
# …and the edge with its condition dropped, which is how it was written when the
# twenty-sixth audit found it. A retryable park then gives the login path back, and
# nothing takes it again until the next pass's resume — a whole backoff of open
# door, once per cycle, on the fleet the drain is waiting to empty.
SEAL_RELEASE_UNGATED='        val heldShut = releaseSeal(subject)'
# …and with half the condition: the class alone, which is how the twenty-sixth
# audit's fix was written and what the twenty-seventh found. It is true of a
# permanent abort under a delete, and a delete is exempt from the gate the sentence
# rests on, so the door is reopened on a fleet the loop keeps reconciling.
SEAL_RELEASE_CLASS_ONLY='        val heldShut = failureClass == FailureClass.PERMANENT && releaseSeal(subject)'
# What the park records once the release has spoken. The twenty-eighth audit'"'"'s
# second finding is the version that discards it: the declared class is written down
# however the compensation went, so a fleet whose front door this abort could not
# reopen is frozen with nobody left to try again.
RELEASE_DECIDES_CLASS='                failureClass = recorded,
                message = if (heldShut) "$message. $SEAL_STUCK_SHUT" else message,'
RELEASE_DISCARDED='                failureClass = failureClass,
                message = message,'
# The stop'"'"'s abort, handed a gate it worked out for itself. `RELOCATION` never
# occurs in any scenario in this suite, so the value is the pass'"'"'s on every path a
# test can drive and only the wiring assertion can see the difference — which is
# what makes it the edit worth pinning rather than the one worth a scenario.
# Re-derived for the thirty-second audit: the argument is `dispatching` now, the
# drain carrying `stopDispatchedAt`. The indent is still what makes this literal
# `stop`'"'"'s catch and not `awaitStopped`'"'"'s, which is four spaces deeper.
STOP_ABORT='                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = dispatching,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = if (failure.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,'
STOP_ABORT_DERIVED='                permanentFailureStopsPasses =
                    pass.permanentFailureStopsPasses && pass.cause != DrainCause.RELOCATION,
                drain = dispatching,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = if (failure.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,'
# The proxy kind'"'"'s permanence gate, carrying the line above it so the literal is
# not the Paper kind'"'"'s as well.
# Re-derived for the twenty-ninth audit: the middle clause is now one expression
# asked by both kinds, so the anchor is the two lines that remain around it.
PROXY_GATE='                    previous.drain.parkedOnTheFailure()
            return failed && stored.permanentFailureStopsPasses()'
PROXY_GATE_COPIED='                    previous.drain.parkedOnTheFailure()
            return failed && !stored.definition.terminating'
# The clause that keeps a drain waiting on players out of the gate, and the version
# without it — which is the gate exactly as the sixteenth audit left it.
PARKED_ON_FAILURE='    this == null || (state == DrainState.DRAIN_FAILED && blocked == null)'
PARKED_IGNORING_BLOCK='    this == null || state == DrainState.DRAIN_FAILED'
# The stop grace period's operational ceiling, and the value going out unbounded.
# Re-derived for the thirtieth audit: this used to sit in `LocalNode.stopWorkload`
# and is now the only factory for the argument's type, so the mutation follows it
# into Node.kt. The node has no mutation of its own any more -- it cannot express
# forgetting a bound it never applies -- and that is written up in
# StopGraceGuardTest's class note rather than left as a silent gap.
GRACE_CEILING='        ): StopGrace = StopGrace(StopGraceCeiling.bound(requested, saveTimeout))'
GRACE_UNBOUNDED='        ): StopGrace = StopGrace(requested)'
# ...and the floor under that ceiling. Dropping it is the plausible edit -- the
# ceiling reads perfectly well without a save timeout in it -- and it restores the
# thirtieth audit's first finding: stopGracePeriod and saveTimeout are a pair the
# schema validated together, and clamping one half at a consumer that cannot see the
# other inverts the relation. Two hours of grace under a three-hour save.
GRACE_FLOOR='        if (saveTimeout.isFinite() && saveTimeout.isPositive()) {
            maxOf(MAX, saveTimeout + PaperServerDefaults.MIN_STOP_GRACE_MARGIN)
        } else {
            MAX
        }'
GRACE_NO_FLOOR='        MAX'
# The sibling ceiling, on the longer of the two calls: saveTimeout becomes
# execSync's gRPC deadline directly, so an unbounded one parks a reconcile worker in
# `save-all flush` with no effective timeout at all.
EXEC_CEILING='        public fun of(requested: Duration): ExecTimeout = ExecTimeout(ExecTimeoutCeiling.bound(requested))'
EXEC_UNBOUNDED='        public fun of(requested: Duration): ExecTimeout = ExecTimeout(requested)'
# Which half of a block's message comes first. Appending the blackout like the other
# two answers is the tidy-looking edit, and it is the thirtieth audit's fourth
# finding: `:api` renders "waiting, not stuck -- " plus this string, so a truncated
# fleet table then shows only the half that agrees with "needs nobody".
# A fourth reader taking the declared grace period instead of the derived one. This
# is the overdue check, and it is the plausible edit because it reads no node and
# looks like it only wants a number: it would then report a container overdue against
# a period the runtime was never given. Behaviourally invisible unless the ceiling
# bites, which is what the structural pin is for.
DERIVED_GRACE_READ='            val grace = stopGrace(pass).period'
DECLARED_GRACE_READ='            val grace = pass.subject.stopGracePeriod'
# A second *application* of the ceiling, which is the half the field scan leaves
# open. `StopGrace.of(x, Duration.ZERO)` is legal from anywhere and the second
# argument is the floor, so this disables it for whatever it is given -- and the
# type still says the ceiling was applied. Written in the shape somebody reaches for
# when there is no drain subject to hand: a block body rather than an expression
# one, so the scan resolves an enclosing function rather than running off the end of
# the file, which would redden the same test for the wrong reason.
GRACE_ELSEWHERE="$RECONCILER_TAIL"'

private fun cleanupGrace(declared: kotlin.time.Duration): StopGrace {
    return StopGrace.of(declared, kotlin.time.Duration.ZERO)
}'
BLACKOUT_LEADS='                LoginPath.ShutByThisDrain -> "${path.sentence}. ${message.replaceFirstChar { it.uppercase() }}"'
BLACKOUT_BURIED='                LoginPath.ShutByThisDrain -> "$message. ${path.sentence}"'
# Step 2 on the gated resume, and the version without it: the state the twenty-
# seventh audit'"'"'s second finding is about, where `holdSeal` sits behind
# `requireEmpty` and is unreachable while anybody is connected.
RESUME_SEAL='        if (!gated) return resumeInto(pass, drain)
        val hold = holdSeal(pass, drain)
        hold.abortOrNull?.let { return it }
        val sealed = hold.recordedOn(drain, pass.now)
        return requireEmpty(pass, sealed) { resumeInto(pass, sealed) }'
RESUME_WITHOUT_SEAL='        if (!gated) return resumeInto(pass, drain)
        return requireEmpty(pass, drain) { resumeInto(pass, drain) }'
# …and step 2 kept while its *record* is dropped, which is the twenty-eighth audit'"'"'s
# first critical. The door is shut and nothing says since when, so the park that
# follows describes a fleet that "keeps taking players".
RESUME_WITHOUT_RECORD='        if (!gated) return resumeInto(pass, drain)
        val hold = holdSeal(pass, drain)
        hold.abortOrNull?.let { return it }
        return requireEmpty(pass, drain) { resumeInto(pass, drain) }'
# The last asking before the irreversible half of the protocol, at the entry to
# steps 6 and 7. Carries the line below it so the literal cannot match anything else.
MID_DRAIN_PREFLIGHT='        replacementIsBuildable(pass, drain)?.let { return it }
        val router = pass.subject.router'
# Its scope. Widening it is the plausible edit — "why only a replacement" — and it
# makes an unbuildable create able to block a *delete*, which is the failure mode
# the whole pre-flight exists to avoid, arriving from the other direction.
PREFLIGHT_SCOPE='        if (pass.cause != DrainCause.REPLACEMENT) return null'
# `blocked`'"'"'s first two lines, to put the same edge in front of. Both park in
# `DRAIN_FAILED`, so "make them consistent" is the obvious edit — and it releases the
# seal that is the mechanism of the wait a block is waiting out.
BLOCK_ENTRY='        val restored = restoreRegistration(subject, drain)
        val standing = drain.failure?.takeIf { it.failureClass == FailureClass.PERMANENT }'
# What a block does to a failure it finds standing. Clearing it unconditionally is
# the twenty-eighth audit'"'"'s third finding: a permanent diagnosis is not resolved by
# somebody logging in, and the status settled on "waiting, not stuck" about a server
# whose delete cannot complete and whose world may not be on disk.
BLOCK_KEEPS_PERMANENT='                    .copy(blocked = block, failure = standing),'
BLOCK_CLEARS_EVERYTHING='                    .copy(blocked = block, failure = null),'
# The deployment'"'"'s Velocity pin, between the configuration and the planner.
PIN_FORWARDED='VelocityWorkloadPlanner.plan(definition, config.velocityBuild)'
# The resolved pin reaching the container'"'"'s environment. The hash entry is taken
# from the same value two lines up; this is what splitting them looks like.
PIN_IN_ENVIRONMENT='                    put(VELOCITY_VERSION, velocity)'
# The blocker preferred over the exit failure, on the branch where both are true.
EXIT_PREFERENCE='                                // an operator can do something about.
                                failure = blocker ?: failure,'
# The classification of a request that cannot be built, at each of the two sites
# whose timeout comes off a definition, and the guard that carries it at a site no
# input reaches.
CHANNEL_CLASSIFIES='return unbuildable(verb, path, rejected)'
SAVE_CLASSIFIES='                return unbuildableSave(rejected)'
# Anchored on the *indentation* rather than on the sentence above it. Coupling a
# mutation to prose is what left D47 reporting a stale anchor the moment the
# operator-facing message was reworded, and the classification is the subject here.
CHANNEL_RETRYABLE='            retryable = true,'
CHANNEL_PERMANENT='            retryable = false,'
SAVE_PERMANENT_CLASS='            retryable = false,
        )

    /**'
SAVE_RETRYABLE_CLASS='            retryable = true,
        )

    /**'
# The whole guard at the proxy'"'"'s probe. It has to come out rather than be hollowed
# out: the scan is a *presence* check on the enclosing function, so catching and
# rethrowing keeps the token and passes — which the test says in its own words.
PROBE_GUARDED='            try {
                ExecRequest(
                    command = PaperCommands.serverListPing(spec.network.port),
                    timeout = ExecTimeout.of(PROBE_TIMEOUT),
                )
            } catch (rejected: IllegalArgumentException) {
                return ProbeOutcome.Unavailable(detail = unbuildableProbe(rejected), retryable = false)
            }'
PROBE_UNGUARDED='            ExecRequest(
                command = PaperCommands.serverListPing(spec.network.port),
                timeout = ExecTimeout.of(PROBE_TIMEOUT),
            )'
# The field a control channel is built from, and the sibling with the same name on
# another spec — which is what "make these consistent" reaches for.
CHANNEL_FIELD='timeout = spec.backends.drain.sealTimeout,'
CHANNEL_OTHER_FIELD='timeout = spec.lifecycle.drain.sealTimeout,'
# The three arms of the dispatched-stop record'"'"'s lifetime rule: D52, D53 and the
# one the thirty-fourth audit'"'"'s critical was in, D59.
FRESH_CONTAINER='        WorkloadState.CREATED -> false'
SIGNALLED_CONTAINER='        WorkloadState.RUNNING, WorkloadState.EXITED, WorkloadState.UNKNOWN -> true'
UNREPORTED_CONTAINER='        WorkloadState.SANDBOX_ONLY -> hadContainer'
# Three sites that retire a drain record, each identified by the comment above it —
# the call itself is the same expression at eleven places, which is the point of it
# having one spelling.
JOINABLE_CLEAR='            // the moment it stops, so the record of that survives this. See
            // [clearedDrainRecord].
            drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),'
REFUSAL_CLEAR='                // the refusal either way.
                drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),'
JOINABLE_DELETES='            // the moment it stops, so the record of that survives this. See
            // [clearedDrainRecord].
            drain = null,'
JOINABLE_ASKS_NOTHING='            // the moment it stops, so the record of that survives this. See
            // [clearedDrainRecord].
            drain = clearedDrainRecord(null, observation, pass.hadContainer),'
# The address the thirty-third audit'"'"'s remedy created for the thirty-fourth
# audit'"'"'s critical: the rule asked, with the fact it turns on answered by a
# constant. Every other assertion about this site stays green.
JOINABLE_ASSUMES_NOTHING='            // the moment it stops, so the record of that survives this. See
            // [clearedDrainRecord].
            drain = clearedDrainRecord(pass.previous?.drain, observation, hadContainer = false),'
REFUSAL_DELETES='                // the refusal either way.
                drain = null,'
# The two routing lines that keep a withdrawn cause draining while its stop is out,
# one per kind. Each is anchored on the line under it, since the call is identical.
WITHDRAWN_BACKEND='                            ?: outstandingStopCause(pass.previous?.drain, observation, pass.hadContainer)
                    // Before the drain, never after'
WITHDRAWN_PROXY='                            ?: outstandingStopCause(pass.previous?.drain, observation, pass.hadContainer)
                    val blocker = replacementBlocker(pass, placement.node, cause)'
CONVERGES_INSTEAD='                    // Before the drain, never after'
CONVERGES_INSTEAD_PROXY='                    val blocker = replacementBlocker(pass, placement.node, cause)'
# The same routing line with the fact answered by a constant rather than removed —
# the plausible edit, and the exact defect that reopened the round above.
ASSUMES_NO_CONTAINER='                            ?: outstandingStopCause(pass.previous?.drain, observation, hadContainer = false)
                    // Before the drain, never after'
# The storage guard'"'"'s state condition, and it back on the one state the drain
# itself takes away.
#
# Re-derived for the thirty-fifth audit: the `SANDBOX_ONLY` arm now carries the note
# the wiring scan requires of it, so the two arms are no longer contiguous and a
# literal spanning both would carry prose. The anchor is the *widened* arm alone,
# which is the whole of what this defect narrows.
TRANSITION_STATES='                WorkloadState.RUNNING, WorkloadState.EXITED, WorkloadState.UNKNOWN -> true'
TRANSITION_RUNNING_ONLY='                WorkloadState.RUNNING -> true
                WorkloadState.EXITED, WorkloadState.UNKNOWN -> false'
# ...and the storage record it writes: the definition it is refusing, rather than
# the container it is about.
TRANSITION_STORAGE='                storage = pass.previous?.storage?.copy(bound = true),'
TRANSITION_STORAGE_DERIVED='                storage = pass.storageStatus(observation),'
# The same erasure through the fallback the thirty-fourth audit'"'"'s fix left behind,
# which reaches only the rows that have no storage record to carry forward — every
# row written before the field existed. The scenario with a *recorded* volume stays
# green under it, which is exactly why it needed a case of its own.
TRANSITION_STORAGE_FALLBACK='                storage = pass.previous?.storage?.copy(bound = true) ?: pass.storageStatus(observation),'
# A sixth classification of a workload state, deciding `SANDBOX_ONLY` with neither
# the fact that separates its two worlds nor a word about doing without it. This is
# the *omission* shape, which is the one a board of flips cannot score: there is no
# expression here to invert, and the defect is the argument nobody wrote. Dead code,
# so nothing but the scan can see it — the same shape as D6 and D14.
NEW_CLASSIFICATION="$RECONCILER_TAIL"'

private fun couldStillBeServing(state: WorkloadState): Boolean =
    when (state) {
        WorkloadState.RUNNING, WorkloadState.EXITED, WorkloadState.UNKNOWN -> true
        WorkloadState.SANDBOX_ONLY -> false
        WorkloadState.CREATED -> false
    }'
# The same arm with a note that reads like an argument and is not one: it explains
# the branch without naming the fact the branch turns on, which is exactly what the
# rule this scores must refuse. Otherwise "carries the argument" is satisfied by any
# comment at all and the second half of the check is decoration.
NEW_CLASSIFICATION_EXCUSED="$RECONCILER_TAIL"'

private fun couldStillBeServing(state: WorkloadState): Boolean =
    when (state) {
        WorkloadState.RUNNING, WorkloadState.EXITED, WorkloadState.UNKNOWN -> true

        // Nothing has been created yet, so there is nothing that could be serving.
        WorkloadState.SANDBOX_ONLY -> false

        WorkloadState.CREATED -> false
    }'
# A workload state folded back into an `else`, where an instrument built to find
# omissions cannot see it. The badge is unchanged — `CREATED` and `SANDBOX_ONLY` are
# refused upstream — so nothing behavioural moves, and the arm scan above stops
# finding the state at all rather than finding it unargued: the two counts fall
# together, so the classification check stays green and only this one bites.
PHASE_ARMS='                        WorkloadState.CREATED, WorkloadState.SANDBOX_ONLY -> ServerPhase.UNKNOWN'
PHASE_ELSE='                        else -> ServerPhase.UNKNOWN'
# Three arms merged into one wrapped pattern, with the note rewritten to explain the
# merge. Behaviourally identical — all three already answered `ServerPhase.UNKNOWN` —
# and it is what a tidy-minded person writes after reading the arms. The whole point
# is where the two states end up: on *continuation* lines, so an arm scan that reads
# the `->` line alone loses `SANDBOX_ONLY` and `CREATED` together, the alphabet
# control balances, and the classification check stays green over a state it can no
# longer see. That is the thirty-sixth audit's first hole, and it was one edit away
# from the live arm in `couldBeTheContainerTheEditIsAbout`.
PHASE_STATES='                        WorkloadState.UNKNOWN -> ServerPhase.UNKNOWN

                        // Refused by `couldBeTheContainerTheEditIsAbout` above, so
                        // unreachable here — enumerated rather than folded into an
                        // `else` because an `else` is how a classification of this
                        // state stops being visible to anything that goes looking
                        // for one. `hadContainer` is not asked for the reason that
                        // arm gives; this is a badge either way, not a decision
                        // about a container.
                        WorkloadState.CREATED, WorkloadState.SANDBOX_ONLY -> ServerPhase.UNKNOWN'
PHASE_STATES_WRAPPED='                        // Three states, one badge. Nothing here decides anything
                        // about a container, so they are merged rather than
                        // repeated three times over.
                        WorkloadState.CREATED,
                        WorkloadState.SANDBOX_ONLY,
                        WorkloadState.UNKNOWN -> ServerPhase.UNKNOWN'
# The same sixth classification as D62, written with the type left off the entries —
# and a scan keyed on the qualified name sees neither the state nor the `CREATED` that
# controls it. Both vanish together, which is why the check that scores this refuses
# the *shape* rather than counting arms that would have balanced.
#
# **The import is the mutation**, and it took a compile to find that out. The
# thirty-sixth audit gave the bare form as `SANDBOX_ONLY -> true` compiling under
# Kotlin 2.4.10's context-sensitive resolution; against this build's compiler it does
# not — every entry reads "Unresolved reference". Importing the entries is the shape
# that *does* compile, in every Kotlin version, so it is the one written here. The
# finding was right and the mechanism given for it was not; if a later Kotlin turns
# the other form on by default, this entry is where the second one goes.
#
# Anchored on the last import rather than the file's tail because both halves have to
# land in one replacement, and a top-level function may follow the imports directly.
LAST_IMPORT='import java.time.Duration as JavaDuration'
NEW_CLASSIFICATION_UNQUALIFIED="$LAST_IMPORT"'
import mcorch.core.WorkloadState.CREATED
import mcorch.core.WorkloadState.EXITED
import mcorch.core.WorkloadState.RUNNING
import mcorch.core.WorkloadState.SANDBOX_ONLY
import mcorch.core.WorkloadState.UNKNOWN

private fun couldStillBeServing(state: WorkloadState): Boolean =
    when (state) {
        RUNNING, EXITED, UNKNOWN -> true
        SANDBOX_ONLY -> false
        CREATED -> false
    }'
# A classification that is not a `when` arm at all. `:core/main` already held three
# of these when the scan claimed "every classification" and read arms alone — one of
# them the drain's own abort — so this is the shape the docstring was writing cheques
# for. Dead code, so nothing but the scan can see it.
NEW_COMPARISON="$RECONCILER_TAIL"'

private fun isAnEmptySandbox(observation: WorkloadObservation.Present): Boolean =
    observation.state == WorkloadState.SANDBOX_ONLY'
# The one fact that separates a sandbox this loop emptied from one whose container the
# runtime has stopped reporting, answered with a constant. Every token the abort's note
# names survives — the call, the binding, the guard, the return — and the note is
# false. This is the thirty-fourth audit's critical written at the address its own fix
# created, which is the general rule for a fact that has become a parameter.
ASKS_SANDBOX_RULE='        val down = observation.containerIsDown(hadContainer)'
ASKS_WITH_A_LITERAL='        val down = observation.containerIsDown(false)'
# The converge decided above the `when` rather than in it: behaviourally identical,
# and it takes the choice out of the one place both answers are weighed together.
ROUTING_WHEN='                    when {
                        blocker != null -> converge(pass, placement.node, observation, blocker)'
ROUTING_EARLY_RETURN='                    if (blocker != null) return converge(pass, placement.node, observation, blocker)
                    when {
                        blocker != null -> converge(pass, placement.node, observation, blocker)'

# name @@ file @@ class @@ testcases that must redden (";"-separated) @@ literal @@ replacement
#
# `@@` because a field holds Kotlin: `|` was the separator until a literal
# containing `||` split into the wrong fields, applied a replacement nobody wrote,
# and reported the resulting compile failure as an UNKNOWN. A delimiter that can
# occur in the payload is a mutation harness lying about its own subject.
MUTATIONS=(
    # An exit above the rule that a prefix match cannot see. The pass that took it
    # records whatever it was handed.
    "D1@@$CONTROLLER@@$WIRING@@$EXIT@@$RULE@@if (progress.occupancy == null) return progress
        $RULE"
    # The same exit spelled as an elvis, which is how it would really be written.
    "D2@@$CONTROLLER@@$WIRING@@$EXIT@@$RULE@@val skip = progress.occupancy ?: return progress
        $RULE"
    # The rule applied to some passes and not others, with the bound name intact.
    "D3@@$CONTROLLER@@$WIRING@@$EXIT@@$RULE@@val recorded = if (progress.drain.playersEvacuated) progress.dropSaveContradictedByPlayers() else progress"
    # The sharpest: a narrowing of the adoption that reads like a careful edit by
    # somebody who has just read the *Declined* paragraph at the call site.
    "D4@@$CONTROLLER@@$WIRING@@$STEPPED@@$ADOPTION@@val observed = if (reading is PlayerReading.Occupied && !drain.playersEvacuated) drain.unconfirmWorldSave() else drain"
    # D4 again, written inside the function the predicate moved into. Same defect,
    # different address, and it is behavioural there rather than structural.
    "D5@@$CONTROLLER@@$RULES@@$ADOPTS@@$CLAUSE@@is PlayerReading.Occupied -> if (playersEvacuated) this else unconfirmWorldSave()"
    "D6@@$RECONCILER@@$WIRING@@$DECIDED@@$RECONCILER_TAIL@@$STOP_ELSEWHERE"
    "D7@@$CONTROLLER@@$WIRING@@every container stop sits behind mayStop, and there are two@@$REISSUE_GATE@@$UNGATED_REISSUE"
    # The other thing the extraction made expressible: the clause applied to the
    # drain from *before* the pass dropped its unusable evidence, which hands the
    # confirmation back.
    "D8@@$CONTROLLER@@$WIRING@@$STEPPED@@$ADOPTION@@val observed = recorded.adoptSaveClause(reading)"
    # The record-level rule excused by a field that is not about this pass. Caught
    # today only because the fixture's drain claims `playersEvacuated`, which is
    # coverage by default until a mutation says otherwise.
    "D9@@$CONTROLLER@@$RULES@@$RECORDS@@$RECORD_RULE@@    if (online == 0 || drain.worldSavedAt == null || drain.playersEvacuated) return this"
    # The gate narrowed instead of deleted. Every structural assertion stays green:
    # the token is there, the count is two, the enclosing set is unchanged. And
    # `playersEvacuated` is true of every drain that reaches `STOPPING`, so this is
    # an unconditional re-issue against a container the drain may never have saved.
    "D10@@$CONTROLLER@@$DRAIN@@$RESTARTED@@$REISSUE_GATE@@$NARROWED_REISSUE"
    # The single exit's precondition, both halves. Widening the visibility opens the
    # set of possible callers to the module.
    "D11@@$CONTROLLER@@$WIRING@@$CALLER@@    private suspend fun advanceOnce(@@    internal suspend fun advanceOnce("
    # And a second call: \"one more pass\" for a drain that did nothing, which
    # discards the first pass's record and keeps its side effects.
    "D12@@$CONTROLLER@@$WIRING@@$CALLER;$EXIT@@$RULE@@val again =
            if (progress.workDone) {
                progress
            } else {
                advanceOnce(
                    subject = subject,
                    node = node,
                    observation = observation,
                    current = current,
                    cause = cause,
                    lastProbedAt = lastProbedAt,
                    hadContainer = hadContainer,
                    permanentFailureStopsPasses = permanentFailureStopsPasses,
                )
            }
        val recorded = again.dropSaveContradictedByPlayers()"
    "D13@@$RECONCILER@@$WIRING@@$DECIDED@@$RECONCILER_TAIL@@$STOP_WRAPPER"
    "D14@@$CONTROLLER@@$WIRING@@$DECIDED@@$CONTROLLER_TAIL@@$REMOVE_ELSEWHERE"
    # D14's shape one file over, in the file rescheduling actually reaches. A
    # deciding-*files* assertion is green against this: the list is already
    # `[Reconciler.kt]` and stays it however many removals are decided there.
    "D15@@$RECONCILER@@$WIRING@@$DECIDED@@$RECONCILER_TAIL@@$REMOVE_IN_RECONCILER"
    # The claim that `stop`'s own `mayStop` needs no scenario is an argument about
    # this source, not about inputs. This is what expires it.
    "D16@@$CONTROLLER@@$WIRING@@$BACKSTOP@@$STOP_DECLARATION@@$SECOND_WAY_IN"
    # The same argument's third premise, and the one that decides whether the
    # backstop is dead code or a gate: what reaches it is what the branch above it
    # asked about. Pinned by following `letGoAndStop`'s own parameter names rather
    # than by restating them, so a rename stays green and this does not.
    "D17@@$CONTROLLER@@$WIRING@@$BACKSTOP@@$STOP_CALL@@$DRAIN_SUBSTITUTED"
    # There is deliberately no mutation for that argument's other precondition —
    # that step 7 is private, so callers in this file are all the callers. The
    # obvious edit does not compile: `internal suspend fun stop` "exposes its
    # 'private-in-class' parameter type 'DrainPass'", so widening it means widening
    # `DrainPass` in the same change, which is not a quiet edit. The assertion stays
    # because that day may come; the compiler is what refuses it today.
    # The pre-flight the world-holding kind went without. The call, first: a
    # `REPLACEMENT` decided with no question asked of the node.
    "D18@@$RECONCILER@@$WIRING@@$ASKS_FIRST@@$PAPER_PREFLIGHT@@                    val blocker: FailureStatus? = null"
    # And the half no shape can carry: the question asked, the answer thrown away.
    "D19@@$RECONCILER@@$REPLACEMENT@@$KEPT_RUNNING;$KEPT_PLAYING@@$PAPER_BRANCH@@$PAPER_BRANCH_DROPPED"
    # The pre-flight narrowed to one of the create's two refusals, which is what it
    # was when a repointed secret drained a proxy to zero before anybody asked.
    "D20@@$LOCAL_NODE@@$WIRING@@$PREFLIGHT@@$PREFLIGHT_DERIVATION@@$PREFLIGHT_SUBSET"
    # The waiver's premise. Well-typed, quiet, and it hands the seal waiver to a
    # backend whose proxy has stopped answering.
    "D21@@$RECONCILER@@$WIRING@@$SAME_LINK@@$SUBJECT_ROUTER@@                router = null,"
    # The compensating edge removed: a proxy left running, ready and joinable to
    # nobody, with the loop no longer passing over it.
    #
    # It claims the retry test as well, and that is a fact about the defect: with no
    # release attempted there is no outcome to record, so the park that was supposed
    # to be retried until the door reopens settles as permanent on the first pass.
    #
    # And the lock-out test, added in the twenty-ninth round: its last assertion is
    # that the fleet's door comes back once the last player logs off, and what
    # opens it is the release on the abort this mutation removes. A true dependency
    # — the whole scenario is about a door that must be able to reopen — and the
    # entry that isolates the edge on its own is still this one.
    "D22@@$CONTROLLER@@$PROXY_DRAIN@@$RELEASES;$RELEASE_RETRIED;$FLEET_LOCKED_OUT@@$SEAL_RELEASE@@$SEAL_RELEASE_NONE"
    # And added where it must not be. A block is the protocol working, and the seal
    # is what lets the wait end.
    #
    # It claims five names, and the four beyond the obvious one are facts about the
    # defect rather than noise. The pin's exit test demonstrates the blackout by
    # asserting the login path is shut while the replacement drain waits, so a seal
    # released on a block means there was never a blackout to have an exit from; the
    # retryable-abort test spends six passes in `blocked` proving the seal is never
    # handed back, which this hands back on the first of them; the delete test's wait
    # for players blocks, so a release lands inside the window in which no `PUT
    # /v1/proxy` may assert `true`; and the first-seal test's recovery pass seals and
    # then blocks, so the door it just shut is handed straight back and the fleet
    # never empties. The sixth is the twenty-eighth audit's blackout report, whose
    # park follows a block that would have handed the door back. The seventh is the
    # twenty-ninth's lock-out test, which asserts the door is shut across the whole
    # wait it drives; all seven redden for the one reason, and D22 is the entry that
    # isolates the edge on its own.
    "D23@@$CONTROLLER@@$PROXY_DRAIN@@$KEEPS_SEAL;$PIN_EXIT;$RETRYABLE_SEAL;$DELETE_SEALED;$FIRST_SEAL;$BLACKOUT_REPORTED;$FLEET_LOCKED_OUT@@$BLOCK_ENTRY@@$BLOCK_ENTRY
        releaseSeal(subject)"
    # The operator's lever dropped between the configuration and the planner: the
    # knob still exists, and nothing reads it.
    "D24@@$RECONCILER@@$PROXY_DRAIN@@$PIN_EXIT@@$PIN_FORWARDED@@VelocityWorkloadPlanner.plan(definition)"
    # The environment and the hash taken from different expressions. Nothing fails
    # at create time; the container simply never matches the hash it was recorded
    # under, so the loop drains and recreates it on every pass.
    "D25@@$PLANNER@@$PLANNING@@$ONE_VALUE@@$PIN_IN_ENVIRONMENT@@                    put(VELOCITY_VERSION, VELOCITY_BUILD)"
    # The artefact's message discarded on the branch that reports a container which
    # has also exited — where the operator is told the unactionable half.
    "D26@@$RECONCILER@@$PROXY_RECONCILE@@$ARTEFACT@@$EXIT_PREFERENCE@@                                failure = failure,"
    # The same edge with its condition dropped — the twenty-sixth audit's critical.
    # It is the *retryable* park that must keep the seal: the loop is still coming
    # back, and the door it hands over is handed over for a whole backoff on a fleet
    # the drain is waiting to empty. D22 and this are the two directions of one line.
    #
    # It claims the delete test too, and that is the honest dependency rather than
    # noise: an unconditional release is a superset of D30's half-condition, so it
    # releases on the permanent abort under a delete as well. D30 is the entry that
    # isolates the twenty-seventh audit's version on its own.
    "D27@@$CONTROLLER@@$PROXY_DRAIN@@$RETRYABLE_SEAL;$DELETE_SEALED@@$SEAL_RELEASE@@$SEAL_RELEASE_UNGATED"
    # The last asking before the irreversible half, removed. The pass-level
    # pre-flight exempts a drain in flight, so with this gone an artefact that
    # disappears mid-drain is discovered by the create — after the stop and the
    # removal.
    "D28@@$CONTROLLER@@$REPLACEMENT@@$MID_DRAIN@@$MID_DRAIN_PREFLIGHT@@        val router = pass.subject.router"
    # …and the same guard widened past a replacement, which makes a create nobody
    # needs able to wedge a delete.
    "D29@@$CONTROLLER@@$REPLACEMENT@@$DELETE_COMPLETES@@$PREFLIGHT_SCOPE@@        if (pass.cause == DrainCause.RELOCATION) return null"
    # The twenty-seventh audit's critical, restored: the gate keyed on the failure
    # class, which is one *input* to `isBlockedByPermanentFailure`. Its other input
    # exempts a terminating definition, so this reopens the login path of a fleet
    # whose delete is still being reconciled — and the gated resume cannot shut it
    # again while the population it refilled is on.
    "D30@@$CONTROLLER@@$PROXY_DRAIN@@$DELETE_SEALED@@$SEAL_RELEASE@@$SEAL_RELEASE_CLASS_ONLY"
    # …and the same defect as a *quiet* edit: one abort site working the gate out
    # for itself instead of forwarding the one it was handed. It agrees with the
    # pass on every path a scenario can drive, so only the wiring assertion moves.
    "D31@@$CONTROLLER@@$WIRING@@$GATED_RELEASE@@$STOP_ABORT@@$STOP_ABORT_DERIVED"
    # The predicate copied rather than called, in one kind's gate. Behaviour is
    # identical today; what it costs is that the loop's answer and the drain's can
    # drift, which is the whole mechanism of the critical above.
    "D32@@$RECONCILER@@$WIRING@@$HANDED@@$PROXY_GATE@@$PROXY_GATE_COPIED"
    # Step 2 taken off the gated resume — the mirror finding. A drain whose first
    # seal failed with players on can then never reach `holdSeal` again: the door
    # stays open, the population never falls, and recovery of the endpoint does not
    # help. It reddens the report half too, and that is a true dependency: with the
    # seal unreachable the park settles into a healthy-looking block. The blackout
    # report is red for the plainest reason of all: this is the site that shuts the
    # door it describes — and so is the lock-out test, whose blocked-with-the-door-
    # shut assertion is a direct reading of this line.
    "D33@@$CONTROLLER@@$PROXY_DRAIN@@$FIRST_SEAL;$PARKED_ENDPOINT;$BLACKOUT_REPORTED;$FLEET_LOCKED_OUT@@$RESUME_SEAL@@$RESUME_WITHOUT_SEAL"
    # …and the same site with step 2 kept and its *record* dropped, which is the
    # twenty-eighth audit's first critical. One mutation, scored twice, because a
    # class is the unit here and the pairing is the point: the scenario covers the
    # one site it drives, and the shape covers the six it cannot.
    "D34@@$CONTROLLER@@$PROXY_DRAIN@@$BLACKOUT_REPORTED@@$RESUME_SEAL@@$RESUME_WITHOUT_RECORD"
    "D35@@$CONTROLLER@@$WIRING@@$RECORDS_THE_SEAL@@$RESUME_SEAL@@$RESUME_WITHOUT_RECORD"
    # The seal release's outcome discarded — best effort inside the one gate that
    # guarantees nobody retries it. The class is what carries the difference: a
    # permanence whose own compensation could not be delivered freezes the loop on a
    # fleet with no front door.
    "D36@@$CONTROLLER@@$PROXY_DRAIN@@$RELEASE_RETRIED@@$RELEASE_DECIDES_CLASS@@$RELEASE_DISCARDED"
    # A block clearing every failure it finds, including a permanent one. Nothing is
    # stopped by it — the wedge survives — so only the report moves, and the report
    # is what decides whether somebody reaches for `crictl stop`.
    "D37@@$CONTROLLER@@$DRAIN@@$WEDGE_SURVIVES@@$BLOCK_KEEPS_PERMANENT@@$BLOCK_CLEARS_EVERYTHING"
    # …and the gate that retention arms. Removing the block clause restores the
    # twenty-ninth audit's first finding: the pass that records a block writes the
    # standing permanent failure at the current generation and freezes the loop, so
    # the definition edit an operator makes to lift it is spent on that one blocked
    # pass. Only the Paper half is claimed here; the proxy half is D38P below,
    # because the two land in different classes and the harness runs one class per
    # mutation.
    "D38@@$CONTROLLER@@$REPLACEMENT@@$EDIT_NOT_SPENT@@$PARKED_ON_FAILURE@@$PARKED_IGNORING_BLOCK"
    "D38P@@$CONTROLLER@@$PROXY_DRAIN@@$FLEET_LOCKED_OUT@@$PARKED_ON_FAILURE@@$PARKED_IGNORING_BLOCK"
    # The stop grace period leaving the factory unbounded. Both node tests redden,
    # and that is the pair rather than a duplicate: one is the value containerd's
    # own arithmetic inverts, the other the largest value it honours -- which this
    # node still refuses to hold a worker for, because the call's deadline is
    # derived from it. The drain-level case is the same mutation scored in a second
    # class, because the harness runs one class per entry.
    # The third name in each claim is the thirty-first audit's case, and its extra
    # red is a true dependency: it reads both the ceiling's arithmetic and the value
    # the node sent, so an unbounded factory and a floorless ceiling both change what
    # it sees. Declared rather than tidied away -- weakening it to make these two
    # entries shorter would delete the evidence that the residual is reachable.
    "D39@@$NODE@@$STOP_GRACE@@$GRACE_CAPPED;$GRACE_STILL_CAPPED;$FLOOR_UNCAPPED@@$GRACE_CEILING@@$GRACE_UNBOUNDED"
    "D39D@@$NODE@@$DRAIN@@$BOTH_CEILINGS@@$GRACE_CEILING@@$GRACE_UNBOUNDED"
    # The floor under it: the thirtieth audit's first finding. Two cases in the node
    # class -- the floor itself, and the residual it leaves at containerd's own bound
    # -- plus the drain that carries the pair end to end.
    "D40@@$NODE@@$STOP_GRACE@@$GRACE_FLOORED;$RUNTIME_REFUSES;$FLOOR_UNCAPPED@@$GRACE_FLOOR@@$GRACE_NO_FLOOR"
    "D40D@@$NODE@@$DRAIN@@$BOTH_CEILINGS@@$GRACE_FLOOR@@$GRACE_NO_FLOOR"
    # The exec deadline, unbounded. Only the drain scenario sees it: it is the one
    # test that reads what a save exec was actually allowed to take.
    "D41@@$NODE@@$DRAIN@@$BOTH_CEILINGS@@$EXEC_CEILING@@$EXEC_UNBOUNDED"
    # The blackout buried behind the wait. Nothing is stopped by it and no decision
    # moves -- only the order of one message -- which is exactly why it needs a
    # mutation: it is invisible to every assertion that reads a state.
    "D42@@$CONTROLLER@@$PROXY_DRAIN@@$KEEPS_SEAL@@$BLACKOUT_LEADS@@$BLACKOUT_BURIED"
    # A second reader of the raw grace period. The type bounds the value; nothing
    # bounds who reads the field it came from, so that half is a source scan.
    "D43@@$CONTROLLER@@$WIRING@@$ONE_DERIVATION@@$DERIVED_GRACE_READ@@$DECLARED_GRACE_READ"
    # ...and a second *application* of it, in another file. D43 covers who may read
    # the raw field; nothing covered who may call the factory, and the factory takes
    # the floor as an argument -- so a caller supplying zero for a workload that holds
    # a world has the type's blessing on a ceiling with nothing under it.
    "D44@@$RECONCILER@@$WIRING@@$ONE_FACTORY@@$RECONCILER_TAIL@@$GRACE_ELSEWHERE"
    # A request that cannot be built, rethrown instead of classified — which is what
    # the code did before, and what "this cannot happen" reaches for. Both proxy
    # scenarios redden because both drive a drain through a channel whose row is bad.
    "D45@@$CHANNEL@@$UNBUILDABLE@@$PARKS_BACKEND;$ISSUES_NOTHING@@$CHANNEL_CLASSIFIES@@throw rejected"
    "D46@@$PAPER_AGENT@@$UNBUILDABLE@@$SAVE_REFUSED@@$SAVE_CLASSIFIES@@                throw rejected"
    # The two classifications, each swapped for the other's. They are opposite on
    # purpose — the proxy's row cannot be repaired by an edit to the backend it
    # freezes, and the server's own row can — so "make these consistent" is the edit
    # this pair exists to catch.
    "D47@@$CHANNEL@@$UNBUILDABLE@@$PARKS_BACKEND@@$CHANNEL_RETRYABLE@@$CHANNEL_PERMANENT"
    "D48@@$PAPER_AGENT@@$UNBUILDABLE@@$SAVE_REFUSED@@$SAVE_PERMANENT_CLASS@@$SAVE_RETRYABLE_CLASS"
    # A construction site with no guard at all, at the site no input reaches — the
    # one the shape scan exists for, since no scenario can drive it.
    "D49@@$PROXY_AGENT@@$UNBUILDABLE@@$CLASSIFIED@@$PROBE_GUARDED@@$PROBE_UNGUARDED"
    # A channel built from the *other* `sealTimeout`. Three reds, and the extra two
    # are a true dependency rather than noise: `ProxyDrainSpec` carries a healthy
    # default, so a channel fed from it never sees the bad row and both scenarios
    # converge normally. That is the property the entry demonstrates — which field
    # reaches the wire — and weakening the scenarios to make this entry tidy would
    # delete the evidence.
    "D50@@$FLEET@@$UNBUILDABLE@@$CHANNEL_TIMEOUT;$PARKS_BACKEND;$ISSUES_NOTHING@@$CHANNEL_FIELD@@$CHANNEL_OTHER_FIELD"
    # The refusal routed into the bucket that is never retried. No exec was
    # dispatched, so arming the wedge would leave a repaired definition unable to
    # save at all — the failure `SaveOutcome`'s three cases exist to keep apart.
    "D51@@$PAPER_AGENT@@$UNBUILDABLE@@$SAVE_REFUSED@@$SAVE_CLASSIFIES@@                return SaveOutcome.Unconfirmed(unbuildableSave(rejected).detail)"
    # A record that survives a create, which is how the rule's own remedy becomes a
    # defect: the pass after a replacement is built then drains the replacement, for
    # ever. Nothing behavioural covers this clause — `convergeProxy`'s create clears
    # the record on an `Absent` observation one step earlier — and the scenario that
    # first exposed it counted *creates* rather than reading a record, which is luck.
    "D52@@$CONTROLLER@@$LIFETIME@@$RECORD_RETIRED@@$FRESH_CONTAINER@@        WorkloadState.CREATED -> true"
    # The retention off altogether: every site that concludes no drain is wanted
    # deletes the record of a `SIGTERM` that is still inside the container. Scored
    # twice, as the rule and as the three paths that reach it.
    "D53@@$CONTROLLER@@$LIFETIME@@$RECORD_SURVIVES@@$SIGNALLED_CONTAINER@@        WorkloadState.RUNNING, WorkloadState.EXITED, WorkloadState.UNKNOWN -> false"
    "D53D@@$CONTROLLER@@$PROXY_DRAIN@@$BACKEND_REVERT;$PROXY_REVERT;$REFUSED_MID_STOP@@$SIGNALLED_CONTAINER@@        WorkloadState.RUNNING, WorkloadState.EXITED, WorkloadState.UNKNOWN -> false"
    # The withdrawn cause routed back to converging, one entry per kind. This is the
    # critical itself: reverting the edit is the documented way to call off a
    # replacement, and taken mid-stop it used to put the workload back on a pass that
    # re-admits players to a container inside its grace period.
    # Two reds since the thirty-fourth audit, and it is a true dependency rather than
    # noise: the new scenario reverts an edit mid-stop as well, so a routing line that
    # is not there at all converges in both of them. D60 is the entry that isolates
    # the *fact* — the line present and answering with a constant — and only the new
    # scenario can see that one.
    "D54@@$RECONCILER@@$PROXY_DRAIN@@$BACKEND_REVERT;$UNREPORTED_MID_STOP@@$WITHDRAWN_BACKEND@@$CONVERGES_INSTEAD"
    "D55@@$RECONCILER@@$PROXY_DRAIN@@$PROXY_REVERT@@$WITHDRAWN_PROXY@@$CONVERGES_INSTEAD_PROXY"
    # A single site back to deleting the record outright, and the same site asking
    # the rule about nothing — `clearedDrainRecord(null, …)` is `drain = null` with
    # more letters, and it satisfies a scan for the call alone. Both redden the shape
    # and neither reddens a scenario: `awaitJoinable` is not reachable with a stop in
    # flight while D54's guard stands, which is what makes these clears defence in
    # depth and the shape the only thing that can see them.
    "D56@@$RECONCILER@@$WIRING@@$RETIRED_BY_RULE@@$JOINABLE_CLEAR@@$JOINABLE_DELETES"
    "D57@@$RECONCILER@@$WIRING@@$RETIRED_BY_RULE@@$JOINABLE_CLEAR@@$JOINABLE_ASKS_NOTHING"
    # The site that *is* reachable, and the one the audit's prescription did not
    # name: refusing an edit is not withdrawing a drain, so no routing guard stands
    # in front of `forbiddenTransition`. Scored as a shape and as a scenario.
    "D58@@$RECONCILER@@$WIRING@@$RETIRED_BY_RULE@@$REFUSAL_CLEAR@@$REFUSAL_DELETES"
    "D58D@@$RECONCILER@@$PROXY_DRAIN@@$REFUSED_MID_STOP@@$REFUSAL_CLEAR@@$REFUSAL_DELETES"
    # The thirty-fourth audit's first critical, at the rule: a sandbox with no
    # container reported classified as "not the container that was signalled",
    # whatever this loop recorded. Scored twice, once as the rule and once as the
    # path — the scenario is the one where the runtime stops enumerating a container
    # inside its grace period, which is indistinguishable from an empty sandbox.
    "D59@@$CONTROLLER@@$LIFETIME@@$SANDBOX_DECIDED@@$UNREPORTED_CONTAINER@@        WorkloadState.SANDBOX_ONLY -> false"
    "D59D@@$CONTROLLER@@$PROXY_DRAIN@@$UNREPORTED_MID_STOP@@$UNREPORTED_CONTAINER@@        WorkloadState.SANDBOX_ONLY -> false"
    # The same defect at the address the previous round's remedy created for it: the
    # fact is a parameter now, so a site can answer it with a constant. D60 does that
    # where the routing asks — behavioural and structural, since the shape is what
    # says the argument is the pass's own property — and D60R where a clear asks,
    # which no scenario reaches for the reason D56 and D57 give.
    "D60@@$RECONCILER@@$PROXY_DRAIN@@$UNREPORTED_MID_STOP@@$WITHDRAWN_BACKEND@@$ASSUMES_NO_CONTAINER"
    # D60S's red set grew with the thirty-fifth audit's routing pin, and the extra red
    # is a true dependency: both `converge` arms argue that the fact was asked *above*
    # them, so a routing line that answers it with a constant makes their notes false
    # as surely as it makes the derivation's claim false. Two assertions, one premise.
    "D60S@@$RECONCILER@@$WIRING@@$ONE_DERIVATION_OF_HISTORY;$CONVERGE_ROUTED@@$WITHDRAWN_BACKEND@@$ASSUMES_NO_CONTAINER"
    "D60R@@$RECONCILER@@$WIRING@@$RETIRED_BY_RULE@@$JOINABLE_CLEAR@@$JOINABLE_ASSUMES_NOTHING"
    # The thirty-fourth audit's second critical: the storage refusal conditioned on a
    # state the drain itself takes away, so the container exits, the refusal stops
    # firing and the create applies the definition several passes refused. D61S is
    # its second half — the refusal writing the storage record of the definition it
    # is refusing, which erases the volume name recovery depends on.
    "D61@@$RECONCILER@@$DRAIN@@$TRANSITION_NOT_APPLIED@@$TRANSITION_STATES@@$TRANSITION_RUNNING_ONLY"
    # Re-derived for the thirty-fifth audit, and its red set grew with the source:
    # writing the *definition's* storage is now visible to two cases, the one that has
    # a record to erase and the one that has none and would be handed a false one.
    "D61S@@$RECONCILER@@$DRAIN@@$TRANSITION_NOT_APPLIED;$PREDATING_ROW@@$TRANSITION_STORAGE@@$TRANSITION_STORAGE_DERIVED"
    # The thirty-fifth audit's first item: the fallback the previous round's fix left
    # behind. It reaches only rows decoded with no storage block, so the scenario with
    # a recorded volume — the one D61S reddens — stays green under it, which is the
    # whole reason that case could not carry this claim.
    "D61E@@$RECONCILER@@$DRAIN@@$PREDATING_ROW@@$TRANSITION_STORAGE@@$TRANSITION_STORAGE_FALLBACK"
    # The round's instrument, proved on the shape it exists for: a classification of a
    # workload state that decides `SANDBOX_ONLY` with neither the fact nor a word about
    # doing without it. D62A is the same arm with a comment that explains the branch
    # without naming the fact, which is what stops "carries the argument" being
    # satisfied by any comment at all.
    "D62@@$RECONCILER@@$WIRING@@$WORKLOAD_STATE_CLASSIFIED@@$RECONCILER_TAIL@@$NEW_CLASSIFICATION"
    "D62A@@$RECONCILER@@$WIRING@@$WORKLOAD_STATE_CLASSIFIED@@$RECONCILER_TAIL@@$NEW_CLASSIFICATION_EXCUSED"
    # The escape from that scan's alphabet: a state decided by an `else`, which names
    # nothing and so is invisible to an instrument keyed on arms that name a state.
    "D63@@$RECONCILER@@$WIRING@@$NO_ELSE_ARM@@$PHASE_ARMS@@$PHASE_ELSE"
    # The premise the two `converge` arms argue from, taken away without changing what
    # the loop does: the decision moved above the `when` that weighs it against the
    # drain. The arms' notes would then be quietly false, which is the state rounds 18
    # and 19 both ended in.
    "D64@@$RECONCILER@@$WIRING@@$CONVERGE_ROUTED@@$ROUTING_WHEN@@$ROUTING_EARLY_RETURN"
    # The thirty-sixth audit's two ways out of that scan's alphabet, and the syntax it
    # never read. D65 wraps an existing arm rather than adding one, because adding one
    # is the case D62 already covers and the hole was in how an *edit* to a live arm
    # gets formatted. D66 writes the entries without their type. Both were green before
    # the scan learned to fold continuation lines and to refuse a bare entry, and both
    # were green *including the alphabet control*, which is the part worth keeping: a
    # control that falls with the thing it controls is not one.
    "D65@@$RECONCILER@@$WIRING@@$WORKLOAD_STATE_CLASSIFIED@@$PHASE_STATES@@$PHASE_STATES_WRAPPED"
    "D66@@$RECONCILER@@$WIRING@@$BARE_ENTRY@@$LAST_IMPORT@@$NEW_CLASSIFICATION_UNQUALIFIED"
    # A classification written as a comparison instead of an arm. The docstring said
    # "every classification"; the scan read arms, and three comparisons were already in
    # `:core/main` — `DrainController`'s `SANDBOX_ONLY` abort among them.
    "D67@@$RECONCILER@@$WIRING@@$WORKLOAD_STATE_CLASSIFIED@@$RECONCILER_TAIL@@$NEW_COMPARISON"
    # The premises of that abort's argument, which is a constructive one and so goes in
    # a test rather than in the note beside it: the rule is asked above the
    # classification, once, with this pass's own fact, and its answer is bound and
    # returned on. D68 answers it with a literal — the thirty-fourth audit's critical at
    # the one address the fix left for it, and the shape a `false` at a call site always
    # has: every token the note names is still there and the fact is gone.
    "D68@@$CONTROLLER@@$WIRING@@$SANDBOX_ABORT_ROUTED@@$ASKS_SANDBOX_RULE@@$ASKS_WITH_A_LITERAL"
    "C1@@$CONTROLLER@@$WIRING@@$EXIT@@$RULE@@val recorded = progress"
    "C2@@$CONTROLLER@@$WIRING@@$STEPPED@@$ADOPTION@@val observed = drain"
    "C3@@$CONTROLLER@@$RULES@@$ADOPTS@@$CLAUSE@@is PlayerReading.Occupied -> this"
)

restore() {
    for backup in "$BACKUP_DIR"/*.kt; do
        [[ -e "$backup" ]] || continue
        case "$(basename -- "$backup")" in
            DrainController.kt) cp -- "$backup" "$REPO_ROOT/$CONTROLLER" ;;
            Reconciler.kt) cp -- "$backup" "$REPO_ROOT/$RECONCILER" ;;
            LocalNode.kt) cp -- "$backup" "$REPO_ROOT/$LOCAL_NODE" ;;
            Node.kt) cp -- "$backup" "$REPO_ROOT/$NODE" ;;
            VelocityWorkloadPlanner.kt) cp -- "$backup" "$REPO_ROOT/$PLANNER" ;;
            ControlChannel.kt) cp -- "$backup" "$REPO_ROOT/$CHANNEL" ;;
            PaperServerAgent.kt) cp -- "$backup" "$REPO_ROOT/$PAPER_AGENT" ;;
            VelocityProxyAgent.kt) cp -- "$backup" "$REPO_ROOT/$PROXY_AGENT" ;;
            ProxyFleet.kt) cp -- "$backup" "$REPO_ROOT/$FLEET" ;;
        esac
    done
}

# The reports this script leaves behind describe *mutated* source, and a JUnit XML
# carries nothing that says so. One was read as a regression on clean source before
# a re-run showed it was this script's last mutation. They go on the way out, with
# the same trap that restores the source: this script's own printed verdict is the
# record of the run, and it names the tests it reddened.
discard_reports() {
    for class in "$WIRING" "$LIFETIME" "$RULES" "$DRAIN" "$PROXY_DRAIN" "$PROXY_RECONCILE" "$REPLACEMENT" \
        "$PLANNING" "$STOP_GRACE" "$UNBUILDABLE"; do
        rm -f -- "$RESULTS/TEST-$class.xml"
    done
}

cleanup() {
    restore
    discard_reports
    rm -rf -- "$BACKUP_DIR"
}
# A signal handler has to **stop the run**, and `trap cleanup INT TERM` does not: the
# handler returns and the loop carries on — with the backups it just deleted, so every
# later mutation restores nothing and the killed run outlives the kill. That is where
# the thirty-fifth round's "orphaned harness racing its own retry" came from, and the
# mutations left in the working tree with it. Killing this script now ends it.
#
# `cleanup` is idempotent, so the EXIT trap firing again after these is free: `restore`
# over a deleted backup directory matches no files, and both removals are `-f`.
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

cp -- "$REPO_ROOT/$CONTROLLER" "$BACKUP_DIR/DrainController.kt"
cp -- "$REPO_ROOT/$RECONCILER" "$BACKUP_DIR/Reconciler.kt"
cp -- "$REPO_ROOT/$LOCAL_NODE" "$BACKUP_DIR/LocalNode.kt"
cp -- "$REPO_ROOT/$NODE" "$BACKUP_DIR/Node.kt"
cp -- "$REPO_ROOT/$PLANNER" "$BACKUP_DIR/VelocityWorkloadPlanner.kt"
cp -- "$REPO_ROOT/$CHANNEL" "$BACKUP_DIR/ControlChannel.kt"
cp -- "$REPO_ROOT/$PAPER_AGENT" "$BACKUP_DIR/PaperServerAgent.kt"
cp -- "$REPO_ROOT/$PROXY_AGENT" "$BACKUP_DIR/VelocityProxyAgent.kt"
cp -- "$REPO_ROOT/$FLEET" "$BACKUP_DIR/ProxyFleet.kt"

apply() {
    LITERAL="$2" REPLACEMENT="$3" python3 - "$1" <<'PY'
import os, sys
path = sys.argv[1]
literal, replacement = os.environ["LITERAL"], os.environ["REPLACEMENT"]
with open(path, encoding="utf-8") as handle:
    text = handle.read()
found = text.count(literal)
if found != 1:
    sys.exit(
        f"  the source contains {found} occurrences of:\n    {literal}\n"
        "  re-derive this mutation against the current source before trusting a green run"
    )
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text.replace(literal, replacement))
PY
}

# The test cases that went red in a report, one per line, without the parameter
# list the runner appends. Scanned rather than parsed: the file is this build's own
# output, and a dev script should need nothing but a shell.
#
# `(...)`, not `()`. A test taking an injected parameter is reported as
# `... (Path)` — every `@TempDir` test is — so stripping the empty pair alone
# produces a name no claim can ever match, and *every* entry naming such a test
# reads MISCAUGHT. That is indistinguishable at a glance from a real finding, and
# it fails in the opposite direction from a false green. The sibling harness
# already strips the list; this one did not, and the first entry to name a
# `StopGraceGuardTest` case found out.
reddened() {
    awk 'match($0, /<testcase name="[^"]*"/) {
             name = substr($0, RSTART + 16, RLENGTH - 17)
         }
         (/<failure/ || /<error/) && name != "" {
             sub(/\([^()]*\)$/, "", name)
             print name
             name = ""
         }' "$1" | sort -u
}

# 0 if the named class reddened exactly the test cases the mutation claims.
judge() {
    local name="$1" class="$2" expected="$3"
    local report="$RESULTS/TEST-$class.xml"

    if [[ ! -f "$report" ]]; then
        echo "  UNKNOWN — no report for $class; the mutation probably did not compile" >&2
        return 1
    fi

    local red want
    red="$(reddened "$report")"
    want="$(printf '%s' "$expected" | tr ';' '\n' | sort -u)"

    if [[ -z "$red" ]]; then
        echo "  GREEN — $class did not catch $name" >&2
        return 1
    fi
    if [[ "$red" != "$want" ]]; then
        # The failure this exists for: a red set that is not the claimed one says
        # nothing about the assertion under test, however red it is.
        echo "  MISCAUGHT — $name claims:" >&2
        echo "$want" | sed 's/^/    /' >&2
        echo "  and reddened:" >&2
        echo "$red" | sed 's/^/    /' >&2
        return 1
    fi
    echo "$red" | sed 's/^/  red: /'
    return 0
}

# Runs the already-applied mutation's test class, leaving the report behind.
execute() {
    local class="$1"

    rm -f -- "$RESULTS/TEST-$class.xml"
    # --rerun because the only input that changed is a source file the test reads
    # at runtime, which Gradle has no way to know about.
    (cd "$REPO_ROOT" && ./gradlew --quiet --console=plain :core:test --tests "$class" --rerun) \
        >/dev/null 2>&1 || true
}

# The verdict's own red-proof.
#
# Breaks the whole test class the way renaming `advance` does — two declarations of
# it, so `rangeOf`'s exactly-one check throws before any assertion runs — *and*
# applies C1 on top, so the run is a real mutation scored under a class that is
# failing for an unrelated reason. Reading the verdict per class would call it
# caught. Reading it per test case must refuse it, because the assertion C1 is
# about never ran.
self_test() {
    echo "== S1 (the verdict, proved able to fail)"
    restore
    apply "$REPO_ROOT/$CONTROLLER" "$ADVANCE_DECLARATION" '    @Suppress("unused")
    private fun advance(neverCalled: Int): Int = neverCalled

'"$ADVANCE_DECLARATION" || return 1
    apply "$REPO_ROOT/$CONTROLLER" "$RULE" 'val recorded = progress' || return 1

    execute "$WIRING"

    # The instrument has to be shown to move before its refusal means anything: a
    # self-test whose own mutation did not compile refuses for the wrong reason and
    # reads exactly like a working one.
    local report="$RESULTS/TEST-$WIRING.xml" red
    if [[ ! -f "$report" ]]; then
        echo "  UNKNOWN — the self-test's own mutation left no report; it probably did not compile" >&2
        return 1
    fi
    red="$(reddened "$report")"
    if [[ -z "$red" ]]; then
        echo "  the class did not go red at all, so this proves nothing about the verdict" >&2
        return 1
    fi
    if judge "S1" "$WIRING" "$EXIT" >/dev/null 2>&1; then
        echo "  the verdict scored a class-wide failure as 'caught' — it is per class again, and" >&2
        echo "  every result this script has ever printed is consistent with total vacuity" >&2
        return 1
    fi
    echo "  ${red//$'\n'/, } went red; C1's claim was refused rather than counted"
    return 0
}

failures=0
ran=0
selected=("$@")

wanted() {
    [[ ${#selected[@]} -eq 0 ]] || [[ " ${selected[*]} " == *" $1 "* ]]
}

for mutation in "${MUTATIONS[@]}"; do
    name=${mutation%%@@*}
    rest=${mutation#*@@}
    file=${rest%%@@*}
    rest=${rest#*@@}
    class=${rest%%@@*}
    rest=${rest#*@@}
    expected=${rest%%@@*}
    rest=${rest#*@@}
    literal=${rest%%@@*}
    replacement=${rest#*@@}

    wanted "$name" || continue

    # Six fields, or the entry has been mis-split and every field after the break
    # is something nobody wrote. That is how the `||` collision reached a Gradle
    # run at all.
    if [[ $(grep -c '@@' <<<"${mutation//@@/$'\n@@\n'}") -ne 5 ]]; then
        echo "== $name" >&2
        echo "  malformed entry: expected 6 fields separated by @@" >&2
        failures=$((failures + 1))
        continue
    fi

    echo "== $name"
    restore
    if ! apply "$REPO_ROOT/$file" "$literal" "$replacement"; then
        failures=$((failures + 1))
        continue
    fi
    ran=$((ran + 1))
    execute "$class"
    judge "$name" "$class" "$expected" || failures=$((failures + 1))
done

if wanted S1; then
    ran=$((ran + 1))
    self_test || failures=$((failures + 1))
fi

restore

if [[ $ran -eq 0 && $failures -eq 0 ]]; then
    echo "no mutation ran" >&2
    exit 1
fi
if [[ $failures -ne 0 ]]; then
    echo "$failures mutations were not caught as claimed" >&2
    exit 1
fi
echo "all $ran mutations caught, each by the test case it names"

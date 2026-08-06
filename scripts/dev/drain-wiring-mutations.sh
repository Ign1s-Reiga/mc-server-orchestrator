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
PLANNER=core/src/main/kotlin/mcorch/core/proxy/VelocityWorkloadPlanner.kt

WIRING=mcorch.core.DrainWiringTest
RULES=mcorch.core.SaveEvidenceTest
DRAIN=mcorch.core.DrainTest
PROXY_DRAIN=mcorch.core.ProxyDrainTest
PROXY_RECONCILE=mcorch.core.ProxyReconcileTest
REPLACEMENT=mcorch.core.ReplacementTest
PLANNING=mcorch.core.proxy.VelocityWorkloadPlannerTest

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
HANDED="the drain is handed the loop's permanence gate rather than deriving one"
GATED_RELEASE='the seal release is gated on the answer the abort was handed'
MID_DRAIN='a replacement that becomes unbuildable mid-drain parks before the stop'
DELETE_COMPLETES='a delete still completes while the node refuses every create'
PIN_EXIT='an operator can pin a proxy fleet back onto the build its containers were created with'
ONE_VALUE='a pinned Velocity build reaches the container and the hash as one value'
ARTEFACT='a proxy that exited and cannot be rebuilt reports the artefact, not the exit'

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
STOP_ELSEWHERE="$RECONCILER_TAIL"'

private suspend fun teardownWithoutDraining(
    node: Node,
    handle: WorkloadHandle,
    grace: kotlin.time.Duration,
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
    grace: kotlin.time.Duration,
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
# aborts it belongs to.
SEAL_RELEASE='        if (failureClass == FailureClass.PERMANENT && permanentFailureStopsPasses) releaseSeal(subject)'
# …and the edge with its condition dropped, which is how it was written when the
# twenty-sixth audit found it. A retryable park then gives the login path back, and
# nothing takes it again until the next pass's resume — a whole backoff of open
# door, once per cycle, on the fleet the drain is waiting to empty.
SEAL_RELEASE_UNGATED='        releaseSeal(subject)'
# …and with half the condition: the class alone, which is how the twenty-sixth
# audit's fix was written and what the twenty-seventh found. It is true of a
# permanent abort under a delete, and a delete is exempt from the gate the sentence
# rests on, so the door is reopened on a fleet the loop keeps reconciling.
SEAL_RELEASE_CLASS_ONLY='        if (failureClass == FailureClass.PERMANENT) releaseSeal(subject)'
# The stop'"'"'s abort, handed a gate it worked out for itself. `RELOCATION` never
# occurs in any scenario in this suite, so the value is the pass'"'"'s on every path a
# test can drive and only the wiring assertion can see the difference — which is
# what makes it the edit worth pinning rather than the one worth a scenario.
STOP_ABORT='                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = drain,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = if (failure.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,'
STOP_ABORT_DERIVED='                permanentFailureStopsPasses =
                    pass.permanentFailureStopsPasses && pass.cause != DrainCause.RELOCATION,
                drain = drain,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = if (failure.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,'
# The proxy kind'"'"'s permanence gate, carrying the line above it so the literal is
# not the Paper kind'"'"'s as well.
PROXY_GATE='                    previous.drain.let { it == null || it.state == DrainState.DRAIN_FAILED }
            return failed && stored.permanentFailureStopsPasses()'
PROXY_GATE_COPIED='                    previous.drain.let { it == null || it.state == DrainState.DRAIN_FAILED }
            return failed && !stored.definition.terminating'
# Step 2 on the gated resume, and the version without it: the state the twenty-
# seventh audit'"'"'s second finding is about, where `holdSeal` sits behind
# `requireEmpty` and is unreachable while anybody is connected.
RESUME_SEAL='        if (!gated) return resumeInto(pass, drain)
        holdSeal(pass, drain).abortOrNull?.let { return it }
        return requireEmpty(pass, drain) { resumeInto(pass, drain) }'
RESUME_WITHOUT_SEAL='        if (!gated) return resumeInto(pass, drain)
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
        val block = recordBlock(reason, message, now, drain.blocked)'
# The deployment'"'"'s Velocity pin, between the configuration and the planner.
PIN_FORWARDED='VelocityWorkloadPlanner.plan(definition, config.velocityBuild)'
# The resolved pin reaching the container'"'"'s environment. The hash entry is taken
# from the same value two lines up; this is what splitting them looks like.
PIN_IN_ENVIRONMENT='                    put(VELOCITY_VERSION, velocity)'
# The blocker preferred over the exit failure, on the branch where both are true.
EXIT_PREFERENCE='                                // an operator can do something about.
                                failure = blocker ?: failure,'

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
    "D22@@$CONTROLLER@@$PROXY_DRAIN@@$RELEASES@@$SEAL_RELEASE@@        Unit"
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
    # never empties. All five redden for the one reason, and D22 is the entry that
    # isolates the edge on its own.
    "D23@@$CONTROLLER@@$PROXY_DRAIN@@$KEEPS_SEAL;$PIN_EXIT;$RETRYABLE_SEAL;$DELETE_SEALED;$FIRST_SEAL@@$BLOCK_ENTRY@@$BLOCK_ENTRY
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
    # seal unreachable the park settles into a healthy-looking block.
    "D33@@$CONTROLLER@@$PROXY_DRAIN@@$FIRST_SEAL;$PARKED_ENDPOINT@@$RESUME_SEAL@@$RESUME_WITHOUT_SEAL"
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
            VelocityWorkloadPlanner.kt) cp -- "$backup" "$REPO_ROOT/$PLANNER" ;;
        esac
    done
}

# The reports this script leaves behind describe *mutated* source, and a JUnit XML
# carries nothing that says so. One was read as a regression on clean source before
# a re-run showed it was this script's last mutation. They go on the way out, with
# the same trap that restores the source: this script's own printed verdict is the
# record of the run, and it names the tests it reddened.
discard_reports() {
    for class in "$WIRING" "$RULES" "$DRAIN" "$PROXY_DRAIN" "$PROXY_RECONCILE" "$REPLACEMENT" "$PLANNING"; do
        rm -f -- "$RESULTS/TEST-$class.xml"
    done
}

cleanup() {
    restore
    discard_reports
    rm -rf -- "$BACKUP_DIR"
}
trap cleanup EXIT INT TERM

cp -- "$REPO_ROOT/$CONTROLLER" "$BACKUP_DIR/DrainController.kt"
cp -- "$REPO_ROOT/$RECONCILER" "$BACKUP_DIR/Reconciler.kt"
cp -- "$REPO_ROOT/$LOCAL_NODE" "$BACKUP_DIR/LocalNode.kt"
cp -- "$REPO_ROOT/$PLANNER" "$BACKUP_DIR/VelocityWorkloadPlanner.kt"

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

# The test cases that went red in a report, one per line, without the `()` the
# runner appends. Scanned rather than parsed: the file is this build's own output,
# and a dev script should need nothing but a shell.
reddened() {
    awk 'match($0, /<testcase name="[^"]*"/) {
             name = substr($0, RSTART + 16, RLENGTH - 17)
         }
         (/<failure/ || /<error/) && name != "" {
             sub(/\(\)$/, "", name)
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

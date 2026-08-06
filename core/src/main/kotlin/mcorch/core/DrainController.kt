package mcorch.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.SaveOutcome
import mcorch.core.paper.WorkloadContract
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.FailureStatus
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PlayerOccupancy
import mcorch.schema.ResourceName
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * The drain protocol, one step per reconcile pass.
 *
 * Read `.claude/skills/drain-protocol/` before changing anything here. The
 * short version:
 *
 * - **There is no unconditional stop in this class.** Every call to
 *   [Node.stopWorkload] is guarded by a freshly observed zero-player count and,
 *   for a server with world data, a *confirmed* save. How many there are is not
 *   stated here — see the two gates below, and `DrainWiringTest`, which asserts
 *   that each one sits behind `mayStop` and that no other file in this module's
 *   main sources decides to call the stop at all. That scan covers
 *   [Node.removeWorkload] too: it is the other way a container ends, it is what a
 *   rescheduling path reaches, and the drain does not call it — `Reconciler`'s
 *   teardowns do, once this controller has reported the container down. The
 *   removal's own refusal to touch a running container is enforced against the
 *   containers the runtime *enumerates*; one it omits is round 4's residual and not
 *   a guarantee, which is why the scan is a review trigger rather than decoration.
 * - **A drain that cannot finish leaves the server running.** Every failure path
 *   lands on [DrainState.DRAIN_FAILED], and there is no edge from there to
 *   [DrainState.STOPPING]. Reaching a retry limit means the loop stops trying,
 *   not that the container stops.
 * - **Zero players is observed, never assumed.** A probe that could not be run
 *   is not a zero-player report, and it aborts the drain.
 * - **A save confirmation belongs to one evacuation, not to the drain.** The
 *   moment a probe reports anybody online, every save this drain had confirmed
 *   is void: whatever they do next is not on disk. Seeing a player therefore
 *   *erases* the evidence rather than merely pausing on it, and the drain has to
 *   save again before it can reach a stop. A drain can sit blocked for a whole
 *   play session — there is no attempt limit, by design — so a latched
 *   confirmation would authorise a stop on evidence a session old, which is
 *   `failure-modes.md` item 6 wearing a different hat.
 * - **What the container was built with beats what the definition says.** An
 *   edit that flips `storage.mode` or enables RCON is a recreate, so the drain
 *   that applies it runs against the *old* container. It reads that container's
 *   own labels ([mcorch.core.paper.WorkloadContract]), never the new spec.
 *
 * ## What a counterparty changes, and what it does not
 *
 * The protocol has seven steps, three of which are conversations with a proxy:
 * stop new joins (2), transfer the players (4), deregister the backend (6).
 * Whether this workload has anybody to have those conversations with is a
 * property of [DrainSubject] — [DrainSubject.seal] and [DrainSubject.router] —
 * and not a branch invented here. A standalone Paper server has neither; a
 * `VelocityProxy` has a seal and no router, because a fleet has one front door and
 * there is nowhere to send its own players.
 *
 * With no router, step 3 is where the drain parks: there is nowhere for players to
 * go, so a workload with players online cannot be emptied and the drain
 * **blocks**. Kicking them to make progress is not an option (`failure-modes.md`
 * item 4).
 *
 * Blocked is not failed, and this class records the difference: a block writes
 * [mcorch.schema.DrainStatus.blocked] and leaves
 * [mcorch.schema.DrainStatus.failure] null. It is retried on the same backoff an
 * abort gets — that is what lets it resolve when the last player logs off — but
 * nothing reports it as a fault, and the escalation stays quiet without needing to
 * be told to. See [blocked] against [abort].
 *
 * ## The zero-player gate covers `SAVING` onward, and only that
 *
 * [requireEmpty] used to wrap every state from `SEALED` down, and the argument for
 * it was that there was then one thing to audit rather than six. That argument was
 * only ever available because steps 2, 3 and 4 had no bodies: fill them in and the
 * guard aborts a drain **precisely when the states it guards are supposed to
 * act**. A destination search that refuses to run while players are online is a
 * destination search that never runs, and a transfer that refuses to move anybody
 * unless nobody is there is not a transfer.
 *
 * What replaces the old argument is narrower, not looser. There are **two** gates
 * on [Node.stopWorkload], not one, and the second exists because the first cannot
 * be applied where it stands:
 *
 * 1. **Step 7 itself** ([stop], reached from `DEREGISTERED`) is behind
 *    [requireEmpty] followed by `mayStop`. [requireEmpty] guards `SAVING`,
 *    `DEREGISTERED` and the `DRAIN_FAILED` resume — the states that flush the
 *    world, let go of the backend and take the container away — and it aborts on a
 *    probe that could not answer at all.
 * 2. **The re-issue of that same stop** ([awaitStopped], in `STOPPING`) is behind an
 *    inline [readPlayers] followed by `mayStop`. `STOPPING` is deliberately *not*
 *    wrapped in [requireEmpty], and the difference is what an unanswered probe
 *    means: a container inside its stop grace period is expected to stop answering,
 *    so aborting on silence would leave a drain unable to finish exactly when it is
 *    working correctly. A *positive* count blocks the re-issue in both.
 *
 * Both gates end in `mayStop`, so neither can issue a stop against a world that is
 * not on disk, and [stop] re-asserts `mayStop` itself as a backstop for a future
 * edit that routes around the state machine. Steps 2, 3 and 4 have no
 * [Node.stopWorkload] call and no edge to `STOPPING` that does not pass through
 * `SAVING`, so they cannot stop anything however wrong they are.
 *
 * The sentence above used to read "no path reaches [Node.stopWorkload] except
 * through [requireEmpty] followed by `mayStop`", and it had been false since the
 * re-issue was written. That the count is now two is held by `DrainWiringTest`
 * rather than by this paragraph, because a KDoc carrying a maintained count of call
 * sites is how the last three of these came to be wrong.
 *
 * Steps 3 and 4 get their own preconditions instead, which are the preconditions
 * of the thing they do: step 3 needs *a destination with capacity*, step 4 needs
 * *a sweep that is making progress*.
 *
 * ## The seal is asserted, never issued
 *
 * See [DrainSeal]. Every state that depends on the seal re-asserts it on every
 * pass, so an abort restores joins by simply not asserting it any more and a proxy
 * restart is repaired by the next pass. The one step that cannot work that way is
 * the deregistration — it is the last thing before the stop — so it carries an
 * explicit re-registration edge on the abort path out of `DEREGISTERED`.
 */
internal class DrainController(
    private val clock: Clock,
    /**
     * The longest gap between two *successful probes* that still counts as
     * having watched the server.
     *
     * A confirmed save is only worth anything while the loop has been looking
     * the whole time since. Nothing else notices a gap: a probe reports what is
     * online *now*, so half an hour of play between two passes leaves no trace
     * anywhere, and the zero-player reading that follows it is true and
     * worthless.
     *
     * The witness has to be a probe rather than "a status was written". A status
     * is also written by a pass that never reached the node, which observes
     * nothing about who is online, and an unchanged status is skipped for up to
     * `statusHeartbeat` — so writes are both too generous and too sparse. The
     * last occupancy reading is the honest one: it exists only when a probe
     * answered, and it stops advancing the moment they stop answering.
     *
     * Related constants, and the order they have to keep:
     * `stepInterval` (1s) < this (30s) << the backoff cap (5min). Much below ten
     * seconds a healthy drain would re-save between its own passes; far above
     * this, a play session fits inside the window.
     */
    private val evidenceGap: Duration = DEFAULT_EVIDENCE_GAP,
    private val attentionAfter: Duration = DEFAULT_ATTENTION_AFTER,
) {
    /**
     * Advances the drain by at most one step, performing at most one side
     * effect.
     *
     * ## The one exit, and the one rule asserted on it
     *
     * Every [DrainProgress] this controller produces leaves through here, which
     * makes this the point where the pass is *recorded*: [DrainProgress.drain] and
     * [DrainProgress.occupancy] are the two facts it establishes, and they leave
     * together. [dropSaveContradictedByPlayers] states the rule they have to satisfy
     * together, and it is asserted here rather than at the steps that build a
     * progress because the defect it exists for is a step that does not think to
     * ask — round 17's was a reader, round 18's was a step that read no count at all
     * and aborted with what it was handed.
     *
     * That this is the *only* exit is not a claim maintained here: `DrainWiringTest`
     * asserts that `advanceOnce` is private with one caller, that this function
     * returns nothing but the rule's result, and that the pass is stepped with the
     * adopted reading. Both lines were jointly pinned and individually unpinned by
     * the behavioural suite, for a reason that is not going to change — see that
     * test's own note.
     *
     * It should never fire. `advanceOnce` establishes the pass's drain through
     * [readPlayers] and hands the same value to every state, so a positive count
     * and a live confirmation cannot coexist by the time a step runs. That is an
     * argument about today's code; this is the thing that holds if it stops being
     * true, and it fails safe — the confirmation goes, the drain saves again, and
     * nothing is stopped on it.
     *
     * @param current the drain recorded last pass, or null to start one.
     * @param lastProbedAt when a probe last answered for this server, or null if
     *   none ever has. The evidence chain is measured against it.
     * @param hadContainer whether a container has ever been observed for this
     *   server. A sandbox that reports no containers means something different
     *   depending on the answer.
     * @param permanentFailureStopsPasses whether a `PERMANENT` failure recorded by
     *   this pass will stop the loop passing over this server again — the
     *   reconciler's own gate, answered rather than re-derived here. [abort] is
     *   what reads it: the compensating edges that belong to *"nothing will ever
     *   look at this workload again"* must not be taken while something will.
     */
    @Suppress("LongParameterList")
    suspend fun advance(
        subject: DrainSubject,
        node: Node,
        observation: WorkloadObservation,
        current: DrainStatus?,
        cause: DrainCause,
        lastProbedAt: Instant?,
        hadContainer: Boolean,
        permanentFailureStopsPasses: Boolean,
    ): DrainProgress {
        val progress =
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
        val recorded = progress.dropSaveContradictedByPlayers()
        if (recorded !== progress) {
            LOG.error(
                "server={} was about to record a drain claiming a world save confirmed at {} in the same pass " +
                    "that observed {} of {} player slots in use. The confirmation has been discarded, so the " +
                    "drain saves again and nothing is stopped on it — but a step reached the record without " +
                    "voiding it, which is a defect in the drain controller",
                subject.server,
                progress.drain.worldSavedAt,
                progress.occupancy?.online,
                progress.occupancy?.max,
            )
        }
        return recorded
    }

    /** One pass, before the rule [advance] asserts on the way out. */
    @Suppress("LongParameterList")
    private suspend fun advanceOnce(
        subject: DrainSubject,
        node: Node,
        observation: WorkloadObservation,
        current: DrainStatus?,
        cause: DrainCause,
        lastProbedAt: Instant?,
        hadContainer: Boolean,
        permanentFailureStopsPasses: Boolean,
    ): DrainProgress {
        val now = clock.instant()
        val recorded = current ?: started(now)
        if (current == null) {
            LOG.info("drain started for {} cause={}", WorkloadRef(subject.server, node.name), cause)
            return DrainProgress(
                drain = recorded,
                outcome = ReconcileOutcome.Progressed("drain requested (${cause.detail})"),
            )
        }

        // Nothing is running. There are provably no players and nothing left to
        // flush, so the drain is already where it was trying to get to. This is
        // the runtime reporting a reaped process or an absent workload — it is
        // never inferred from a failed probe or an unreachable node.
        val down = observation.containerIsDown(hadContainer)
        if (down != null) {
            // The container is gone and the proxy does not know. Skipping the
            // teardown's proxy step would leave a live registration pointing at an
            // address nothing is listening on, and the proxy would route new
            // players straight into it — the drain finished correctly and the
            // fleet is broken anyway.
            //
            // Safe here and nowhere else: the runtime has said there is no
            // container, so there is provably nobody connected, which is the one
            // precondition `DELETE` enforces. It is best-effort on purpose — a
            // proxy that cannot be reached must not wedge a delete whose container
            // is already gone — and the proxy's own reconcile sweep is the
            // backstop that removes a registration this call could not.
            val letGo = releaseRegistration(subject, recorded, node)
            return DrainProgress(
                // Any block goes with it. A block is a live claim that somebody
                // is connected to this container; the runtime has just said there
                // is no container, so the claim cannot survive into the teardown
                // status and be read there as "still waiting for players".
                //
                // A recorded **failure** deliberately does not, and this return
                // skipping [settleRecords] is not an oversight. This pass never ran
                // a step: it observed that the container is gone, which says
                // nothing about why the drain was failing. Erasing a permanent
                // `DRAIN_SAVE_TIMEOUT` here on the strength of a container that
                // died on its own would delete the one record telling an operator
                // the world may not have been flushed — at the exact moment they
                // would come looking. It rides into one or two `STOPPED` statuses
                // and the next pass purges the whole record; the eighth audit ruled
                // that cosmetic, and the sixteenth's harm through it (re-arming the
                // permanent gate on the replacement path) is closed at the gate.
                drain =
                    letGo
                        .moveTo(DrainState.STOPPING, now)
                        .copy(playersEvacuated = true, blocked = null),
                containerDown = true,
                outcome = ReconcileOutcome.Progressed("the container is already $down"),
            )
        }
        if (observation !is WorkloadObservation.Present) {
            return DrainProgress(drain = recorded, outcome = ReconcileOutcome.Waiting(UNKNOWN_STATE, POLL))
        }
        if (observation.state == WorkloadState.UNKNOWN) {
            return DrainProgress(drain = recorded, outcome = ReconcileOutcome.Waiting(UNKNOWN_STATE, POLL))
        }

        // Two ways a confirmation stops describing the world, neither of which
        // any probe can report, both dropped here so that every state below
        // sees a drain whose evidence is about the container in front of it.
        //
        // Deliberately **not** where the re-save anchor is stamped, although this is
        // the one place that can see a confirmation being voided. Losing one is not
        // the signal — the drain having to go *back* for another is, and a drain
        // that loses its evidence while parked has a failure recorded already. A
        // stamp here also made the anchor older than the cycle it measures, so a
        // drain that had been parked for an hour aborted on its first honest lap
        // with the wrong diagnosis. [goingRoundInCircles] stamps it at the edge it
        // bounds, and no test could tell the two apart, which is the other half of
        // the reason this line is not here.
        val drain = recorded.dropUnusableSaveEvidence(observation.startedAt, lastProbedAt, now, evidenceGap)
        if (drain !== recorded) {
            LOG.warn(
                "server={} has a world save confirmed at {} that is no longer evidence: the container now " +
                    "running started at {} and a probe last answered at {}. The drain will save again",
                subject.server,
                recorded.worldSavedAt,
                observation.startedAt,
                lastProbedAt,
            )
        }

        // The runtime lists no container for a workload that has had one. That
        // is not "the container was never created" — it is the runtime failing
        // to tell us about a process that may well be serving players, and the
        // one thing that must not follow is a teardown. There is nothing to
        // probe either, since the handle has no container to exec into, so this
        // stops here and comes back.
        //
        // The registration is deliberately left alone, and this is the one early
        // return where that is the right answer: the process may still be serving
        // players, so removing it from routing is `failure-modes.md` item 3 with
        // extra steps. The seal is not re-asserted either — nothing was observed —
        // and the abort below parks the drain, which is what lifts it.
        if (observation.state == WorkloadState.SANDBOX_ONLY) {
            return abort(
                subject = subject,
                permanentFailureStopsPasses = permanentFailureStopsPasses,
                // The *confirmation* goes, because nothing was observed this
                // pass and the world may have moved on. The record of a
                // delivered-but-unconfirmed save request stays: it is the only
                // thing keeping a second `save-all flush` off a live server, and
                // a runtime that has stopped reporting a container is not a
                // reason to lift a wedge that exists to make a human confirm the
                // world state. A request that *was* confirmed leaves nothing
                // behind, because there is no wedge to keep — see
                // [forgetSaveConfirmation] for why the two cannot share an
                // answer.
                drain = drain.forgetSaveConfirmation(),
                occupancy = null,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = FailureClass.RETRYABLE,
                message =
                    "the runtime reports no container in sandbox ${observation.handle.sandboxId} for a server " +
                        "that has had one. Nothing is stopped or removed on the strength of that: an " +
                        "unreported container is not an absent one, and the process may still be serving players",
            )
        }

        // Occupancy is re-read on every pass of a running server, not once at the
        // start. A workload with no seal keeps taking players throughout its own
        // drain, and even a sealed one is reachable on its own port by anybody who
        // knows it, so a count taken three states ago is not evidence of anything.
        val probe = subject.probe(node, observation.handle)

        // Built through [readPlayers], which is the only constructor of a
        // `PlayerOccupancy` in this file, and every abort below depends on that:
        // a non-null `occupancy` means an SLP answered, so a message or a
        // decision may say what is online, and a null one means nothing was
        // established and nothing may be claimed.
        //
        // Stated here rather than as a list of the call sites that hold it. That
        // list was written once and was already wrong — `awaitStopped`'s abort
        // reaches `step()` directly rather than through `requireEmpty` — while a
        // single constructor cannot drift.
        //
        // [save] calls it a second time, because a save is the one step long
        // enough for a pass-entry instant to have gone stale by the time it
        // returns. The guarantee that survives is the one the aborts actually
        // rest on — an occupancy instant is when an SLP answered, not when the
        // pass began.
        //
        // **This caller adopts one clause of the reading and declines the rest**,
        // and which clause is the whole of round 18's critical. The clause is
        // [DrainStatus.adoptSaveClause] rather than a conditional written here, so
        // that the predicate has somewhere a unit test can reach it — no scenario
        // can, for the reason given under *Declined* below. The argument for
        // choosing this clause stays here, with the caller it is about.
        //
        // *Adopted:* the confirmation. A positive count means anything this drain
        // had saved is behind whatever that player is doing, and from here on every
        // state in this pass sees a drain that does not claim a save. It used to
        // take neither clause, on the argument that each state which flushes a
        // world, lets go of a backend or takes a container away reads the count
        // itself. They do — but only *after* the steps that run before them. At
        // `DEREGISTERED`, [holdSeal] runs before [requireEmpty], reads no count, and
        // aborts with whatever drain it was handed when the proxy's control endpoint
        // does not answer. That abort parked a drain still claiming a save taken
        // before the player arrived, the loop kept probing and so kept the evidence
        // window fresh, and the pass after they logged off stopped the container on
        // it. Blocks placed after the flush were lost. The rule that holds is about
        // *recording*, not reading, so it is applied where the pass's drain is
        // established rather than at the readers.
        //
        // *Declined:* `playersEvacuated`, `saveRequestedAt` and the re-save anchor —
        // everything else [forgetSaveEvidence] takes. Clearing `playersEvacuated`
        // here would drop a proxied parked drain from rung 2 of the resume ladder to
        // rung 3, so it would resume into a *transfer* where it now resumes into
        // `SAVING` and blocks in [requireEmpty]. Rung 1 is `saveIsCurrent`, which the
        // adopted clause correctly stops satisfying; rung 2 sends it somewhere that
        // blocks on the same player. So the ladder still refuses to jump to the stop
        // and still refuses to start a sweep, which is what the two rungs are for.
        //
        // What this retires is a proof that was never written down: the exemption
        // used to be harmless in `SEALED`, `TARGET_RESOLVED`, `TRANSFERRING` and
        // `SAVING` only because [dropUnusableSaveEvidence] and the resume ladder
        // between them make `worldSavedAt` provably null in all four, and it was
        // *not* harmless in `DEREGISTERED`. Nobody had to check which four, because
        // there are no longer any states where the confirmation survives a positive
        // count — which is the argument this line makes on its own.
        //
        // The general form is enforced where this pass is *recorded* rather than
        // trusted to this line — see [dropSaveContradictedByPlayers], called on the
        // way out of [advance].
        val reading = drain.readPlayers(probe, now)
        val occupancy = reading.occupancy
        val observed = drain.adoptSaveClause(reading)

        val pass =
            DrainPass(
                subject = subject,
                node = node,
                observation = observation,
                probe = probe,
                occupancy = occupancy,
                // Read once per pass, off the running container rather than off
                // the definition, and threaded through every decision below.
                contract = subject.contractOf(observation),
                cause = cause,
                now = now,
                permanentFailureStopsPasses = permanentFailureStopsPasses,
            )
        // Whether this pass began parked is decided here, before anything moves the
        // recorded state, and it is one of the two inputs [settleRecords] needs. A
        // step cannot be asked about it afterwards: the resume has already moved the
        // drain out of `DRAIN_FAILED` by the time its progress comes back.
        return step(pass, observed).settleRecords(resuming = observed.state == DrainState.DRAIN_FAILED)
    }

    /**
     * Lets the proxy go of a workload whose container the runtime says is gone.
     *
     * Best-effort by design. Two things are true at once: leaving a live
     * registration behind makes the proxy route new players to a dead address, and
     * a proxy that cannot be reached must not be able to wedge the delete of a
     * container that has already stopped. So this tries, records the outcome when
     * it worked, and reports the failure at warn when it did not — the proxy's own
     * reconcile sweep removes a registration whose definition no longer matches its
     * selector, and the plugin refuses `DELETE` outright while anybody is
     * connected, so nothing here can disconnect a player whatever it gets wrong.
     */
    private suspend fun releaseRegistration(
        subject: DrainSubject,
        drain: DrainStatus,
        node: Node,
    ): DrainStatus {
        val router = subject.router ?: return drain
        if (drain.deregisteredAt != null) return drain
        return when (val outcome = router.deregister()) {
            is SealOutcome.Asserted -> {
                LOG.info(
                    "deregistered {} from proxy={} after its container was observed gone",
                    WorkloadRef(subject.server, node.name),
                    router.proxy,
                )
                drain.copy(deregisteredAt = clock.instant())
            }

            is SealOutcome.Refused -> {
                LOG.warn(
                    "proxy={} refused to deregister {} whose container is gone: {}. The teardown continues; " +
                        "the proxy's own pass removes the registration",
                    router.proxy,
                    WorkloadRef(subject.server, node.name),
                    outcome.detail,
                )
                drain
            }

            is SealOutcome.Unavailable -> {
                LOG.warn(
                    "proxy={} could not be reached to deregister {} whose container is gone: {}. The teardown " +
                        "continues; the proxy's own pass removes the registration",
                    router.proxy,
                    WorkloadRef(subject.server, node.name),
                    outcome.detail,
                )
                drain
            }
        }
    }

    /**
     * One state's worth of work.
     *
     * Separate from [advance] so that a drain re-entered after a failure can run
     * the state it resumes into *in the same pass*. Re-entering used to cost a
     * pass of its own, reported as [ReconcileOutcome.Progressed] — which told
     * the loop the server had made progress and reset its backoff, so a drain
     * whose save kept failing alternated resume/fail about once a second for
     * ever instead of backing off. Nothing here performs more than one side
     * effect: the resume itself does none.
     */
    @Suppress("ReturnCount")
    private suspend fun step(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        val observation = pass.observation
        val occupancy = pass.occupancy
        val contract = pass.contract
        val now = pass.now

        return when (drain.state) {
            DrainState.DRAIN_REQUESTED -> {
                // Step 2: stop new joins.
                //
                // Asserted rather than issued, and asserted again by every state
                // below that depends on it — see [holdSeal]. The record of when the
                // door was shut is [SealHold.recordedOn], the same call every one of
                // those states makes, rather than a stamp written here.
                val hold = holdSeal(pass, drain)
                hold.abortOrNull?.let { return it }
                val sealed = hold.recordedOn(drain, now)
                // Three answers, not two. A waived seal asked the proxy and was not
                // answered, so it claims no work: treating it as `Asserted` would
                // report a step that happened when it did not.
                val asserted = hold == SealHold.Asserted
                DrainProgress(
                    drain = sealed.moveTo(DrainState.SEALED, now),
                    occupancy = occupancy,
                    // A `PUT` went out and the proxy confirmed it, or there was
                    // nothing to seal and this step asked nobody anything. The
                    // ladder never resumes into `DRAIN_REQUESTED`, so nothing reads
                    // this today; it is the honest answer rather than a convenient
                    // one, because the next reader will take it for the rule.
                    workDone = asserted,
                    outcome =
                        ReconcileOutcome.Progressed(
                            when (hold) {
                                SealHold.Asserted -> SEALED_AT_PROXY
                                SealHold.Waived -> WAIVED_PROXY_SEAL
                                else -> NO_PROXY_SEAL
                            },
                        ),
                )
            }

            // Step 3: secure a destination.
            DrainState.SEALED -> {
                val hold = holdSeal(pass, drain)
                hold.abortOrNull?.let { return it }
                secureDestination(pass, hold.recordedOn(drain, now))
            }

            // Step 4: move the players.
            DrainState.TARGET_RESOLVED -> {
                val hold = holdSeal(pass, drain)
                hold.abortOrNull?.let { return it }
                startTransfer(pass, hold.recordedOn(drain, now))
            }

            DrainState.TRANSFERRING -> {
                val hold = holdSeal(pass, drain)
                hold.abortOrNull?.let { return it }
                awaitEvacuated(pass, hold.recordedOn(drain, now))
            }

            // Step 5: save the world and wait for completion.
            DrainState.SAVING -> {
                val hold = holdSeal(pass, drain)
                hold.abortOrNull?.let { return it }
                val sealed = hold.recordedOn(drain, now)
                requireEmpty(pass, sealed) {
                    save(pass, sealed)
                }
            }

            // Step 6: deregister the backend, then step 7.
            DrainState.DEREGISTERED -> {
                val hold = holdSeal(pass, drain)
                hold.abortOrNull?.let { return it }
                val sealed = hold.recordedOn(drain, now)
                requireEmpty(pass, sealed) {
                    if (sealed.mayStop(contract, observation.startedAt, now, evidenceGap)) {
                        letGoAndStop(pass, sealed)
                    } else {
                        // The evidence that got this drain here is gone — a
                        // player was seen since, or the container restarted —
                        // so it goes back and gets it again. Going back is the
                        // whole point: the alternative is a stop on a
                        // confirmation that has been outlived, and a drain
                        // that gives up here would leave a server nobody can
                        // retire.
                        //
                        // Going back *for ever* is a different thing, and this is
                        // where it is caught. See [goingRoundInCircles].
                        goingRoundInCircles(
                            pass = pass,
                            drain = sealed,
                            detail = "the world has to be saved again before this server can stop",
                        )
                    }
                }
            }

            // Step 7 was issued on the way into this state. The container has
            // not been observed down yet, so watch for it.
            DrainState.STOPPING -> {
                awaitStopped(pass, drain)
            }

            // Re-entered after a backoff.
            //
            // The re-check happens *in place*: while the drain is still
            // blocked it stays in `DRAIN_FAILED` with a rising attempt count,
            // rather than cycling back through the earlier states and making a
            // dashboard read as though it were progressing.
            //
            // A resume keeps the side effects already issued —
            // `saveRequestedAt` in particular — so it does not re-send a save
            // request. A drain that aborted permanently is re-entered only
            // while a delete is outstanding, and then only so the loop can
            // notice the operator has finished the job by hand; every state it
            // can reach from here aborts again without touching the server.
            //
            // Where it resumes depends on whether this drain still holds a save
            // confirmation that is worth anything. It does not if anybody has
            // been seen since ([forgetSaveEvidence] cleared it) or if the
            // container has restarted since ([dropStaleSaveEvidence] did), and
            // then it goes back and saves again rather than jumping to the stop
            // on a confirmation from the last session.
            DrainState.DRAIN_FAILED -> {
                // No seal is asserted here **for a backend**, and that is what
                // restores its joins. A drain that has stopped advancing is a drain
                // that is not going to move those players, so holding it out of the
                // proxy's routing buys nothing and costs a running server no player
                // can reach — for ever, if the abort was permanent, because then
                // this pass never happens again either. The proxy's own reconcile
                // sweep is what makes the restoration land in that case; this
                // branch simply declines to re-assert.
                //
                // A subject that seals *itself* is the opposite case and [resume]
                // is where it is handled: there is no third party to re-assert for
                // it, and the seal is what lets its wait for zero end. See that
                // function for why step 2 runs before the gate rather than after
                // it.
                //
                // The zero-player wrapper is only for a workload with no router.
                // With one, players are the thing the resumed states exist to move,
                // so blocking the resume on their absence would park a proxied drain
                // for as long as anybody was playing — the same defect [requireEmpty]
                // shrank to avoid, one level up. Safety is unchanged: every state
                // this can resume into that touches the world or the container calls
                // [requireEmpty] itself.
                resume(pass, drain, gated = pass.subject.router == null)
            }
        }
    }

    /**
     * Re-enters a parked drain, and runs the state it resumes into in this same
     * pass.
     *
     * [gated] is false exactly when there is a router. Players are then the thing
     * the resumed states exist to move, so refusing to resume while anybody is
     * connected would park a proxied drain for as long as people were playing.
     *
     * ## Step 2 runs first on the gated path, and it is the only place it can
     *
     * The twenty-seventh audit's second finding, and it is the mirror of the one
     * that made [releaseSeal] permanent-only. A subject with no router seals
     * *itself*, and the six forward states that assert that seal all sit behind
     * [requireEmpty] on this path — so a drain whose **first** [holdSeal] failed
     * with players on parks with the door open, and every pass after it stops at
     * [requireEmpty] with anybody connected. [holdSeal] is then unreachable for
     * ever: the population never falls to zero, the drain never converges, and it
     * does not converge after the endpoint recovers either, because nothing brings
     * it back to a state that would seal. A delete or a replacement in that state
     * is another `crictl stop`.
     *
     * Asserting it here is level-triggering the seal on the one state that had none
     * of it, and it repairs the neighbouring case for free: a proxy restarted
     * underneath a long, healthy block loses its admission state, and nothing else
     * on this path would ever put it back — `assertBackends` is reached only by a
     * pass that is *not* draining.
     *
     * ## What it costs, which is a report rather than a door
     *
     * It hands nothing back: there is no unseal here, only an assertion, and
     * [abort] no longer releases on a retryable park. What changes is that a pass
     * which cannot reach the control endpoint while somebody is on now records a
     * `RETRYABLE` failure where it used to record a healthy [blocked]. That is the
     * honest report — a wait whose seal cannot be maintained is not the protocol
     * working, it is a wait that cannot end — and it is what an operator needs to
     * be told, since [blocked] is rendered as *do not act*.
     *
     * The alternative was rejected under the old rule for a reason that has since
     * expired: paired with an *unconditional* release it handed the door back for a
     * whole backoff on every cycle. With the release gated there is nothing to pair
     * it with, so only the reporting change is left, and it is weighed against a
     * delete that can never complete.
     */
    private suspend fun resume(
        pass: DrainPass,
        drain: DrainStatus,
        gated: Boolean,
    ): DrainProgress {
        // The record below is the twenty-eighth audit's first critical: on this path
        // the resume is where the door *first* gets shut, because the six states
        // that assert step 2 all sit behind the gate and a drain whose opening
        // attempt failed with players on never reaches one of them. A seal that
        // lands with nothing written down is a blackout no operator-facing sentence
        // can mention.
        if (!gated) return resumeInto(pass, drain)
        val hold = holdSeal(pass, drain)
        hold.abortOrNull?.let { return it }
        val sealed = hold.recordedOn(drain, pass.now)
        return requireEmpty(pass, sealed) { resumeInto(pass, sealed) }
    }

    private suspend fun resumeInto(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        val observation = pass.observation
        val now = pass.now
        // The furthest state the evidence still justifies. A drain
        // that has emptied the server and lost only its save goes
        // back to the save rather than round the whole machine,
        // which would make a dashboard read as though it were
        // making progress every fourth pass for as long as the save
        // keeps failing.
        //
        // `destination` is on the ladder for a reason that is not
        // cosmetic. Without it a drain whose *transfer* keeps failing
        // resumes at `SEALED`, re-resolves a destination it already
        // had, and reports `Progressed` — which makes `ReconcileLoop`
        // call `queue.succeeded` and reset the backoff. The next pass
        // transfers, fails, parks; the pass after that resolves again.
        // A two-second loop, for ever, issuing destination lookups and
        // **transfer requests at live players** with `attempts` pinned
        // at 1 and the backoff never growing. Resuming straight to
        // `TARGET_RESOLVED` makes the whole cycle one pass that ends in
        // an abort, so the backoff applies and the counter rises.
        val resume =
            when {
                drain.saveIsCurrent(observation.startedAt, now, evidenceGap) -> DrainState.DEREGISTERED
                drain.playersEvacuated -> DrainState.SAVING
                drain.destination != null -> DrainState.TARGET_RESOLVED
                else -> DrainState.SEALED
            }
        // Straight into that state, in this pass, against the probe
        // just taken. Returning here instead would report progress
        // for a pass that did nothing, and the loop reads that as a
        // reason to forget how long this has been failing.
        //
        // The recorded failure travels *with* it. If the resumed
        // state fails again, `recordFailure` needs the previous one
        // to carry the attempt count and the first-occurrence time
        // forward — clearing it here made every pass of a failing
        // drain report "attempt 1, first seen now", which is the
        // number the escalation is built on. Nothing in this function
        // clears it any more: see [settleRecords], which is asked of
        // the pass after this one as well.
        //
        // A recorded *block* travels the same way and for the same
        // reason: `recordBlock` needs it to keep "blocked since" from
        // resetting to now on every pass, which is the one number an
        // operator reads off a drain that is waiting.
        val resumed = step(pass, drain.moveTo(resume, now))
        if (resumed.drain.state == DrainState.DRAIN_FAILED) return resumed
        // **A resume that only re-derived state is not progress.**
        //
        // Reporting `Progressed` here is what tells `ReconcileLoop` to call
        // `queue.succeeded` and forget how many times this server has failed, so a
        // drain whose transfers keep being refused held the backoff at the poll
        // interval for ever: resume, re-derive, report progress, transfer, fail,
        // park — two seconds a cycle, with admission flapping once per cycle as
        // `sealsBackend` alternates.
        //
        // Nothing about the recorded failure is decided here. Clearing it used to
        // be, and it was the wrong place: this branch sees only the resume, and the
        // failure has to survive the resume *and* be judged against the pass after
        // it. [settleRecords] does that, for every pass rather than for this one.
        if (!resumed.workDone) {
            return resumed.copy(outcome = ReconcileOutcome.Retry(resumed.outcome.detail))
        }
        return resumed
    }

    /**
     * What the pass leaves behind on the drain record once its step has run: the
     * stale block always goes, the recorded failure goes only when it has been
     * earned.
     *
     * ## A block is only ever true of a drain that is parked
     *
     * [mcorch.schema.DrainStatus.blocked] is written by [blocked] and nowhere else,
     * and [blocked] always parks in `DRAIN_FAILED`. So a block riding on any other
     * state is stale by construction, and it used to ride: only an abort, a fresh
     * block, the container-is-down branch and the old resume cleared it, and an
     * ordinary forward step cleared nothing. A drain that had waited for players,
     * then emptied and carried on, arrived at the container stop still claiming to
     * be waiting for players — `:api` renders that as "waiting, not stuck" about a
     * drain seconds from stopping the container, and `recordBlock` carried the stale
     * `since` and `observations` into the next genuine block, so "waiting since"
     * pointed at a wait that had already ended. It is cleared unconditionally,
     * because a re-derivation is not a block either.
     *
     * ### The cost of that, examined and left as it is
     *
     * `since` is the number an operator reads off a waiting drain, and clearing the
     * record resets it whenever the drain leaves `DRAIN_FAILED` and comes back — a
     * proxied drain that blocks, transfers and blocks again reports "waiting since"
     * a few seconds ago every time. Both obvious narrowings are worse:
     *
     * - **Clear only on [DrainProgress.workDone].** It fires for exactly one
     *   pass, and that pass is the one it would be worst to misreport. Every
     *   block voids the save evidence, which clears `playersEvacuated`, so the
     *   resume ladder lands on `SEALED` or `TARGET_RESOLVED`; almost every branch
     *   of [secureDestination] and [transferStep] that leaves `DRAIN_FAILED`
     *   claims `workDone`, the transfer included. The exception is
     *   [DestinationChoice.Chosen], which is re-derived rather than done and says
     *   so — and it is precisely where a proxied drain resumes from a block with
     *   no destination recorded. The narrowed rule would therefore carry the
     *   stale block through the pass that just secured one: "waiting, not stuck"
     *   on a drain that has made real headway. The same misreading this
     *   unconditional clear exists to stop, one pass long instead of many.
     *
     *   The earlier version of this paragraph said the rule "cannot fire" and
     *   named no exception. It is corrected rather than deleted because the
     *   conclusion did not change and the argument for it did, and a wrong reason
     *   left standing beside a right decision is what the next reader takes for a
     *   general licence.
     * - **Keep the record while the drain progresses.** `DRAIN_BLOCKED` is derived
     *   from `blocked != null && failure == null`, and `:api` documents that pair as
     *   *waiting*. Keeping it means a live "waiting, not stuck; needs nobody" on a
     *   drain that is transferring players — the reading this unconditional clear
     *   exists to stop, for longer.
     *
     * Preserving `since` needs a carrier that is not the *current* block record, and
     * whatever resets that carrier faces the same undecidable question the failure
     * rule below does: from one pass, a wait that has ended and a wait that is about
     * to resume look identical. Not added on speculation.
     *
     * ## One good pass does not clear a failure; the pass after it does
     *
     * [resuming] is true when this pass began in `DRAIN_FAILED`, and a pass that
     * began there may not delete the failure however much work it did. That is not
     * the same rule as [DrainProgress.workDone] and it is not redundant with it:
     *
     * A drain parked on a refused container stop re-enters through the ladder,
     * which — once the save evidence has aged past `evidenceGap`, which it does on
     * any backoff longer than 30 seconds — lands on `SAVING`. The save is real: a
     * `save-all flush` goes out and the server confirms it, so the pass has done
     * work by any honest definition. The *stop* is what is failing, and the next
     * pass fails it again. Clearing on the strength of the save reset `attempts` and
     * restamped `occurredAt` every cycle, so a stop refused for six hours reported
     * three attempts and never reached the fifteen-minute threshold — the anchor
     * destroyed nine times before it could fire.
     *
     * So the drain proves it has recovered by completing one *ordinary* step after
     * the resume. If the next pass fails again, the failure it carries is the same
     * one, with its count and its first occurrence intact. If the next pass gets
     * somewhere, the failure was genuinely behind it and goes.
     *
     * The backoff is deliberately **not** governed by this rule — `resumeInto` uses
     * [DrainProgress.workDone] alone. The two answer different questions: *is this
     * server making progress right now*, which a save that went out honestly is, and
     * *has this drain recovered*, which one pass cannot establish. Tying the backoff
     * to the stricter rule would leave a drain that emptied after a play session
     * waiting out a five-minute backoff before it could move.
     */
    private fun DrainProgress.settleRecords(resuming: Boolean): DrainProgress {
        // An abort or a block has just written exactly what it means to record.
        if (drain.state == DrainState.DRAIN_FAILED) return this
        return copy(
            drain =
                drain.copy(
                    failure =
                        when {
                            workDone && !resuming -> null

                            // Retained for its escalation anchor — `attempts` and
                            // `occurredAt` — but not for its class.
                            //
                            // A resume that did real work has *disproved* the
                            // permanence: the step that was refused just
                            // succeeded. Carrying `PERMANENT` forward from there
                            // states something the pass has evidence against, and
                            // `Reconciler.isBlockedByPermanentFailure` reads that
                            // class to decide whether to stop reconciling the
                            // server at all. Belt and braces with the parked
                            // clause on the gate itself: either alone closes the
                            // sixteenth audit's second critical, and the two
                            // together mean neither a future reader of the class
                            // nor a future caller of the gate can reopen it.
                            //
                            // The anchor survives, so a drain that fails again
                            // keeps counting from when the trouble started rather
                            // than from the resume — which is the whole reason the
                            // hysteresis exists.
                            workDone && resuming -> drain.failure?.recoverable()

                            else -> drain.failure
                        },
                    blocked = null,
                ),
        )
    }

    /**
     * What one pass established about drain step 2.
     *
     * A value rather than a nullable [DrainProgress] because `DRAIN_REQUESTED` has
     * to tell [Asserted] from [Waived] — it claims [DrainProgress.workDone] on the
     * strength of a `PUT` that landed, which is false of a pass that gave up on the
     * seal and carried on. Every other caller wants [abortOrNull] and [recordedOn].
     */
    private sealed interface SealHold {
        /** There is nothing that could stop new joins, or step 6 has already run. */
        data object NothingToSeal : SealHold

        /** A `PUT` went out and the proxy confirmed the workload no longer admits. */
        data object Asserted : SealHold

        /** See [sealIsPrecondition]: the seal could not be asserted and did not have to be. */
        data object Waived : SealHold

        data class Aborted(
            val progress: DrainProgress,
        ) : SealHold

        /** The abort to return from the step, or null when the drain may carry on. */
        val abortOrNull: DrainProgress? get() = (this as? Aborted)?.progress

        /**
         * The drain to carry on with: `sealRequestedAt` stamped when this pass is
         * what got the seal in place, unchanged otherwise.
         *
         * **Every caller of [holdSeal] records its answer**, not only step 2, and
         * that is the twenty-eighth audit's first critical. The stamp used to be
         * written at the `DRAIN_REQUESTED` arm alone, while [holdSeal] itself runs
         * on six other states and — since the twenty-seventh audit — on the gated
         * `DRAIN_FAILED` [resume]. So a proxy whose *first* step 2 failed with
         * players on, and whose resume then shut the door once the endpoint came
         * back, carried on with the field null; the next pass to lose the endpoint
         * read that null in [loginPathAfterAPark] and told an operator *"the server
         * keeps running and keeps taking players"* about a fleet this controller had
         * blacked out one pass earlier. A record written where the work happens
         * cannot go stale at the six sites that do the same work elsewhere.
         *
         * `?: now` rather than an unconditional stamp: the field is *since when* the
         * door has been shut by this drain, so a pass that re-asserts a seal already
         * in place must not restamp it. Nothing gates on it — [exhausted] was moved
         * off it onto `transferStartedAt` for exactly that reason — and nothing may,
         * because a gate would be the event-shaped seal wearing a timestamp.
         *
         * A [Waived] hold deliberately stamps nothing: it asked the proxy and was not
         * answered, so recording "sealed at" would put an instant on a dashboard for a
         * seal that is not in place, which is the one thing a reader consults this
         * field to rule out.
         */
        fun recordedOn(
            drain: DrainStatus,
            now: Instant,
        ): DrainStatus = if (this is Asserted) drain.copy(sealRequestedAt = drain.sealRequestedAt ?: now) else drain
    }

    /** Everything one pass established before it looked at the drain's state. */
    private class DrainPass(
        val subject: DrainSubject,
        val node: Node,
        val observation: WorkloadObservation.Present,
        val probe: ProbeOutcome,
        val occupancy: PlayerOccupancy?,
        val contract: WorkloadContract,
        /**
         * Why this drain is running.
         *
         * Read by exactly one step — [replacementIsBuildable], which is a question
         * only a `REPLACEMENT` has to answer. Nothing else may branch on it: what a
         * drain *does* is the same whatever asked for it, and a cause consulted at a
         * gate is how a delete comes to take a path a replacement was written for.
         */
        val cause: DrainCause,
        val now: Instant,
        /**
         * `Reconciler.permanentFailureStopsPasses`, carried so that every [abort]
         * reached from a step is handed the same answer this pass was given.
         *
         * A step may not derive it, and [cause] is the derivation that looks as
         * though it would do. It is not: placement decides a cause before
         * `drainCause` is consulted, so a terminating definition whose container is
         * on a node the scheduler no longer chooses drains as a `RELOCATION`. A
         * gate keyed on the cause would then take the delete's branch for a
         * replacement's, or the reverse, which is the shape this field exists to
         * stop being written a third time.
         */
        val permanentFailureStopsPasses: Boolean,
    ) {
        val server: ResourceName get() = subject.server
    }

    /**
     * Step 2, on every pass of every state that depends on it.
     *
     * Failing to hold the seal is a real abort rather than a warning **for a
     * workload that has somewhere to send its players**: an unsealed backend keeps
     * taking players, so a drain that carried on would be transferring into a queue
     * that refills behind it, which is the state the protocol's own
     * `SOURCE_NOT_SEALED` exists to make unreachable. That sentence is the whole
     * justification for the abort, and it is a sentence about a *transfer* — see
     * [sealIsPrecondition] for the subject the sentence is false about, and for the
     * critical it produced.
     *
     * Skipped once the backend has been deregistered. `PUT /v1/backends/{name}`
     * asserts registration *and* admission, so asserting a seal after step 6 would
     * put the backend back in the routing table moments before the container stops.
     */
    private suspend fun holdSeal(
        pass: DrainPass,
        drain: DrainStatus,
    ): SealHold {
        val seal = pass.subject.seal ?: return SealHold.NothingToSeal
        if (drain.deregisteredAt != null) return SealHold.NothingToSeal
        return when (val outcome = seal.assertAdmission(admits = false)) {
            is SealOutcome.Asserted -> {
                if (!outcome.admits) {
                    SealHold.Asserted
                } else {
                    // The proxy accepted the call and reports the workload still
                    // admitting. Nothing in the protocol produces that, so it means
                    // the read-back describes something else — a different
                    // incarnation, or a plugin that is not doing what it says.
                    abortSeal(pass, drain, "the proxy accepted the seal and still reports new players admitted")
                }
            }

            is SealOutcome.Refused -> {
                abortSeal(pass, drain, outcome.detail, outcome.retryable)
            }

            is SealOutcome.Unavailable -> {
                abortSeal(pass, drain, outcome.detail, outcome.retryable)
            }
        }
    }

    /**
     * The one place a failed step 2 decides between parking the drain and letting it
     * through, so that the waiver has a single enforcement point rather than one per
     * abort branch.
     *
     * The message's second half is [loginPathAfterAPark] rather than a sentence,
     * because what a failed assertion leaves behind is not one state. It used to say
     * *"the server keeps running and keeps taking players"* unconditionally, which
     * is true of a backend and of a proxy that never got sealed — and false of the
     * state this controller now deliberately produces, a proxy sealed on an earlier
     * pass whose endpoint has since gone quiet. Over-stating availability errs safe
     * and hides the only symptom there is: nobody can log in.
     */
    private suspend fun abortSeal(
        pass: DrainPass,
        drain: DrainStatus,
        detail: String,
        retryable: Boolean = true,
    ): SealHold {
        val reading = drain.readPlayers(pass.probe, pass.now)
        if (!sealIsPrecondition(pass.subject.router, reading)) {
            LOG.warn(
                "server={} could not assert its login seal and is empty, so the drain carries on without it: {}. " +
                    "Nothing is stopped on that alone — the zero-player gate before the stop is what decides",
                pass.server,
                detail,
            )
            return SealHold.Waived
        }
        return SealHold.Aborted(
            abort(
                subject = pass.subject,
                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = drain,
                occupancy = pass.occupancy,
                now = pass.now,
                reason = FailureReason.PROXY_CONTROL_UNREACHABLE,
                failureClass = if (retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                message =
                    "new joins could not be stopped at the proxy, so the drain is not going further: $detail. " +
                        loginPathAfterAPark(pass.subject, drain).sentence,
            ),
        )
    }

    /**
     * What a park leaves the workload's login path in — the half an operator acts on.
     *
     * **Asked by both kinds of park**, and that is the twenty-ninth audit's second
     * finding. It was written for [abortSeal] and the three block messages each
     * stated joinability for themselves; [requireEmpty]'s said *"the server keeps
     * running and stays joinable"*, which since the twenty-seventh audit is exactly
     * false for a workload that seals itself — the gated [resume] asserts
     * [holdSeal] **before** the gate, so the pass that records the block is often
     * the pass that shut the front door, and a blocked drain renders as
     * `DRAIN_BLOCKED`: *waiting, needs nobody*, about a fleet nobody can log in to.
     * The sentence is composed in [blocked] so all three block sites get it,
     * for the reason `SealHold.recordedOn` exists: a fact stated at one of the
     * places that produce it goes stale at the others.
     *
     * ## It is a value rather than a string, and the thirtieth audit's fourth finding
     *
     * `:api` renders a blocked drain as `"waiting, not stuck — <block message>"`, and
     * a block message leads with the wait. That put the reassurance first and the
     * blackout about 250 characters in — so a fleet table that truncates shows only
     * the half agreeing with `DRAIN_BLOCKED`'s *needs nobody*, and an evening of
     * refused logins reads as a healthy wait. The fix belongs here rather than in
     * `:api`, which cannot see [DrainSubject.router] and would have to key on
     * `sealRequestedAt` alone — over-stating a blackout on every backend mid-drain.
     *
     * So this answers with **which of the three states** it found, and [blocked] puts
     * [LoginPath.ShutByThisDrain] ahead of the wait sentence. One derivation still;
     * only the order moves, and only for the one case where something is shut.
     *
     * Three states, and the record distinguishes them:
     *
     * - **A backend.** Its admission is stated by the *proxy's* pass, every pass,
     *   from `DrainState.sealsBackend()` — false in `DRAIN_FAILED` — so a park hands
     *   its joins back whatever this drain could or could not assert. Checked first,
     *   which is why `sealRequestedAt` being set for a backend mid-drain says nothing
     *   here.
     * - **A workload that seals itself and has a seal in place.** `sealRequestedAt`
     *   is stamped only by a `PUT` the proxy confirmed, by [SealHold.recordedOn] at
     *   every state that asserts one, and nothing clears it — so a park with it set
     *   is a front door that is still shut. Since the twenty-sixth audit that is
     *   deliberate — the seal is what lets the wait for zero end — and it means the
     *   symptom is a blackout, which no other surface reports.
     *
     *   The one thing that reopens that door is [releaseSeal], and it cannot make
     *   this sentence stale: it runs *after* the message is composed, and only on a
     *   park that either stops the passes (so no later pass composes another) or
     *   fails to land, which is recorded [FailureClass.RETRYABLE] and leaves the
     *   door shut. A pass that does follow re-asserts the seal before it could get
     *   here.
     * - **A workload that seals itself and never got one.** The one case the old
     *   unconditional sentence described.
     *
     * ## Both exits are named, and the cause is deliberately not consulted
     *
     * The twenty-eighth audit's fourth finding: *"until whatever asked for this drain
     * is withdrawn"* is true of a `REPLACEMENT` — revert the edit, `proxyDrainCause`
     * returns null, a converging pass re-admits — and false of a `DELETION`, where
     * `deletedAt` is one-way and `:api` has neither an un-delete nor a force flag. It
     * was the only operator-facing sentence about a fleet-wide blackout and it named
     * an impossible action in the case where the blackout lasts longest.
     *
     * The fix is the wording rather than a branch, and that is a choice. The
     * discriminator would have to be *"can what asked for this be withdrawn"*, which
     * is the terminating flag; [DrainPass.cause] is the plausible substitute and is
     * wrong, because placement decides a cause first, so a terminating definition
     * whose container is on a node the scheduler no longer chooses drains as a
     * `RELOCATION` and would be offered the revert after all. The other candidate is
     * `permanentFailureStopsPasses`, which is `!terminating` today and is *the answer
     * to a different question* — the mistake the twenty-seventh audit's critical was.
     * A sentence that states both exits is true under every cause and needs neither.
     *
     * ## The residual, and the premise that keeps it unreachable
     *
     * On the [abortSeal] path this is composed before [abort] runs, and [abort] may
     * release the seal — so on a *permanent* park whose release lands, this sentence
     * over-states the blackout. ([blocked] releases nothing, so its caller has no
     * such window: what this says is what that pass leaves behind.)
     * It cannot happen through the seal this controller has: `ProxyLink` makes every
     * `SealOutcome.Refused` retryable, so a permanent step-2 abort means a
     * `SealOutcome.Unavailable` the channel raised (an unreadable body, an unknown
     * error code), and the release goes over that same channel and fails the same
     * way. If a refusal ever becomes non-retryable, this needs the release's outcome
     * rather than a premise. Stated as the direction of the error, because that is
     * what an operator pays: over-stating a blackout sends somebody to look at a door
     * that is fine, where under-stating it hides the only symptom there is.
     */
    private fun loginPathAfterAPark(
        subject: DrainSubject,
        drain: DrainStatus,
    ): LoginPath =
        when {
            subject.router != null -> LoginPath.Restored
            drain.sealRequestedAt != null -> LoginPath.ShutByThisDrain
            else -> LoginPath.Open
        }

    /**
     * Step 3: somewhere for the players to go.
     *
     * **The zero-player gate is deliberately not here.** A destination search that
     * refuses to run while players are online is a destination search that never
     * runs — the precondition would be the negation of the state's own purpose. The
     * precondition that belongs here is the one the step is about: *a destination
     * with capacity*.
     *
     * With no router there is nowhere to send anybody, so an empty workload goes
     * straight through and one with players **blocks**: the loop backs off and
     * looks again, and if the last player logs off the drain continues on its own.
     * That is the standalone shape, and it is also the proxy's own drain, because a
     * fleet has one front door.
     */
    private suspend fun secureDestination(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        val occupancy = pass.occupancy
        val now = pass.now
        val router =
            pass.subject.router ?: return requireEmpty(pass, drain) {
                DrainProgress(
                    drain = drain.moveTo(DrainState.TARGET_RESOLVED, now).copy(playersEvacuated = true),
                    occupancy = occupancy,
                    // Zero players, established by this pass's ping. Nothing left
                    // the process, but the drain did not know it and could not have
                    // computed it — see [DrainProgress.workDone].
                    workDone = true,
                    outcome = ReconcileOutcome.Progressed("no destination needed: the server is empty"),
                )
            }

        // Nobody to move: no search, and no destination recorded either. A
        // `destination` set here would send the resume ladder to `TARGET_RESOLVED`
        // on a drain that never needed one.
        if (drain.readPlayers(pass.probe, now) is PlayerReading.Empty) {
            return DrainProgress(
                drain =
                    drain
                        .moveTo(DrainState.TARGET_RESOLVED, now)
                        .copy(playersEvacuated = true, transferStartedAt = drain.transferStartedAt ?: now),
                occupancy = occupancy,
                workDone = true,
                outcome = ReconcileOutcome.Progressed("no destination needed: the server is empty"),
            )
        }

        return when (val choice = router.resolveDestination()) {
            is DestinationChoice.Chosen -> {
                LOG.info(
                    "drain for server={} will move its players to server={} through proxy={}",
                    pass.server,
                    choice.destination,
                    router.proxy,
                )
                DrainProgress(
                    drain =
                        drain
                            .moveTo(DrainState.TARGET_RESOLVED, now)
                            .copy(
                                destination = choice.destination,
                                playersEvacuated = false,
                                // Step 4's allowance starts here, on the first entry
                                // and never again. See `DrainStatus.transferStartedAt`.
                                transferStartedAt = drain.transferStartedAt ?: now,
                            ),
                    occupancy = occupancy,
                    // **Re-derived, not done**, so `workDone` is left false.
                    // Choosing a destination asks the scheduler and nothing else —
                    // no request leaves this process — so a resume whose only work
                    // was this has learned nothing that would justify forgetting how
                    // long the drain has been failing, and must not tell the loop
                    // the server made progress.
                    outcome = ReconcileOutcome.Progressed("destination secured: ${choice.destination}"),
                )
            }

            is DestinationChoice.NoCapacity -> {
                // The search ran and the fleet had nothing. That is not the
                // protocol working — it is a fleet too small — so it is a failure
                // rather than a block, and it escalates once it has been true long
                // enough. `FailureStatus` refuses to let it be PERMANENT: what it
                // is blocked on is not a property of this server.
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = drain,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_NO_DESTINATION,
                    failureClass = FailureClass.RETRYABLE,
                    message =
                        "no server behind proxy=${router.proxy} has capacity for this server's players: " +
                            "${choice.detail}. Nobody is disconnected and the server keeps running; add " +
                            "capacity and the drain continues on its own",
                )
            }

            is DestinationChoice.Unavailable -> {
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = drain,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.PROXY_CONTROL_UNREACHABLE,
                    failureClass = if (choice.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                    message = "no destination could be secured: ${choice.detail}",
                )
            }
        }
    }

    /**
     * Step 4, first pass: ask the proxy to move everybody.
     *
     * The precondition is *a sweep that can start*, not zero players. With no
     * router, or with nobody to move, this is the old empty-server path and
     * [requireEmpty] still guards it — there is genuinely nothing else it could
     * mean.
     */
    private suspend fun startTransfer(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress =
        transferStep(
            pass = pass,
            drain = drain,
            emptyState = DrainState.TRANSFERRING,
            emptyDetail = "no players to transfer",
        )

    /**
     * Step 4, waiting: is the sweep getting anywhere, and is the server empty yet.
     *
     * ## The gate is the workload's own Server List Ping, and a proxy count never moves it
     *
     * A proxy makes a cheaper count available — one RPC where SLP is an
     * `ExecSync` — and it is strictly wrong for this decision. A client connected
     * straight to the backend's own port is invisible to the proxy and visible to
     * SLP, and whether backends are firewalled is a deployment property this code
     * cannot assert. So the proxy's number is read, logged when it disagrees, and
     * never consulted: a disagreement is a log line, never a decision.
     */
    private suspend fun awaitEvacuated(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress =
        transferStep(
            pass = pass,
            drain = drain,
            emptyState = DrainState.SAVING,
            emptyDetail = "zero players confirmed after the transfer",
        )

    /**
     * Both halves of step 4, because they differ only in where an empty server
     * goes next.
     *
     * ## The zero-player exit is taken **before** the retry limit is consulted
     *
     * That ordering is the whole of this function's safety, and getting it wrong
     * produced a drain that could never finish. `startTransfer` used to go straight
     * to [issueTransfer], which asks [exhausted] first — so once the counter was
     * spent, the resume ladder (`DRAIN_FAILED` → `TARGET_RESOLVED` → here) re-entered
     * the limit check *without ever reading the player count*. An operator who
     * deleted a populated backend got a drain that parked permanently, and it stayed
     * parked after every last player had logged off: nothing clears
     * `transferAttempts` short of the teardown the wedge is upstream of, a generation
     * bump does not clear the drain record, and `DRAIN_FAILED` does not seal, so the
     * proxy sweep re-admitted players to a server nobody could retire. The path from
     * there is a manual `crictl stop`, which is a container stopped with no save.
     *
     * So: a server that is empty finishes, whatever the counter says. A limit bounds
     * how long the loop keeps *asking*, and there is nothing left to ask for.
     */
    private suspend fun transferStep(
        pass: DrainPass,
        drain: DrainStatus,
        emptyState: DrainState,
        emptyDetail: String,
    ): DrainProgress {
        val occupancy = pass.occupancy
        val now = pass.now
        val router = pass.subject.router
        val destination = drain.destination

        // No counterparty, or nobody was ever assigned one: the standalone shape,
        // where zero players is the only way forward and `requireEmpty` says so.
        if (router == null || destination == null) {
            return requireEmpty(pass, drain) {
                DrainProgress(
                    drain = drain.moveTo(emptyState, now).copy(playersEvacuated = true),
                    occupancy = occupancy,
                    workDone = true,
                    outcome = ReconcileOutcome.Progressed(emptyDetail),
                )
            }
        }

        val reading = drain.readPlayers(pass.probe, now)
        // Every answered probe is corroborated, whatever it counted, so the
        // disagreement between the ping and the proxy is logged in both
        // directions. A probe that did not answer establishes nothing to compare.
        reading.occupancy?.let { corroborate(pass, router, it.online) }
        return when (reading) {
            // A probe that did not answer is not a zero-player report, and nothing
            // about a sweep being in flight changes that.
            is PlayerReading.Unanswered -> {
                unansweredProbe(pass, drain, reading.probe)
            }

            is PlayerReading.Empty -> {
                DrainProgress(
                    drain = reading.drain.moveTo(emptyState, now).copy(playersEvacuated = true),
                    occupancy = reading.occupancy,
                    workDone = true,
                    outcome = ReconcileOutcome.Progressed(emptyDetail),
                )
            }

            is PlayerReading.Occupied -> {
                issueTransfer(
                    pass = pass,
                    // Already voided: see [readPlayers].
                    drain = reading.drain,
                    router = router,
                    destination = destination,
                    online = reading.online,
                    into = DrainState.TRANSFERRING,
                )
            }
        }
    }

    /**
     * Asks the proxy to sweep, once, and counts the ask.
     *
     * ## `transferAttempts` counts; it never gates
     *
     * It is read to decide *when to stop asking* and to report how many times this
     * drain has asked. It is never read as "a transfer already went out, so skip
     * this one" — the moment a pass declines to re-issue because a record says one
     * was issued, a lost status write wedges a drain on a server with players and a
     * seal applied, and there is nothing left that can move them. The protocol is
     * what makes re-issuing safe: a repeat naming the same destination while a
     * sweep is still running joins that sweep and asks nobody to move again.
     *
     * ## The step-4 anchor is produced here, so no caller can fail to supply one
     *
     * [mcorch.schema.DrainStatus.transferStartedAt] bounds this step, and a missing
     * anchor is **stamped and written back into the returned drain**, never
     * substituted for the length of one call. The caller used to stamp it and this
     * function kept a `?: now` for the case that could not happen — which is defect
     * 1 of the three this bound has had, spelled out in [exhausted]: an anchor that
     * restamps every pass is an allowance handed back every pass, and a drain that
     * can never reach its own limit. The fallback was dead only because the single
     * caller stamped three lines above it; a second caller reaching this from a
     * state that does not stamp is all it would have taken, and nothing in the old
     * arrangement would have said so.
     *
     * Producing the value where it is used removes the question. Every return below
     * carries [anchored] rather than the argument, so the stamp survives an abort as
     * well as a sweep.
     *
     * [secureDestination] still stamps on the edge into `TARGET_RESOLVED`, and that
     * is not a duplicate to be cleaned up: it is the *ordinary* stamp, at the entry
     * to the step being bounded, and it is what makes the allowance start when step 4
     * starts rather than when its first sweep happens to go out. This is the backstop
     * for the paths that never run a bodied step-3 pass at all — a `holdSeal` that
     * aborted on the drain's one `DRAIN_REQUESTED` pass, a router that appeared only
     * after the proxy's runtime was observed, a conflicted-proxy delete that drained
     * with no router. Both stamp only when the field is null, so they cannot disagree.
     */
    @Suppress("LongParameterList")
    private suspend fun issueTransfer(
        pass: DrainPass,
        drain: DrainStatus,
        router: DrainRouter,
        destination: ResourceName,
        online: Int,
        into: DrainState,
    ): DrainProgress {
        val occupancy = pass.occupancy
        val now = pass.now
        val anchor = drain.transferStartedAt ?: now
        val anchored = drain.copy(transferStartedAt = anchor)
        exhausted(pass, anchor, online)?.let { limit ->
            // `failure-modes.md` item 7, and the line somebody writes after "2 of 6
            // transfers were refused" is a disconnect. At the limit the loop stops
            // *trying*; it does not kick and it does not stop the container.
            //
            // RETRYABLE rather than PERMANENT, and the choice is load-bearing. A
            // permanent abort freezes the status and stops the loop passing over
            // this server at all, which would leave it sealed, invisible and
            // running with nobody left to lift the seal. It is only safe because
            // the proxy's own sweep restores joins for a parked drain — see the
            // `DRAIN_FAILED` branch.
            return abort(
                subject = pass.subject,
                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = anchored,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_TRANSFER_FAILED,
                failureClass = FailureClass.RETRYABLE,
                message =
                    "the loop has stopped asking proxy=${router.proxy} to move this server's players to " +
                        "`$destination`: $limit. Nobody is disconnected, the container is not stopped, and new " +
                        "joins are restored while this drain is parked. It finishes on its own if the last " +
                        "player logs off",
            )
        }

        return when (val report = router.transfer(destination)) {
            is TransferReport.Sweeping -> {
                DrainProgress(
                    drain =
                        anchored
                            .moveTo(into, now)
                            // A report of how many times this drain has asked, and
                            // **nothing gates on it** — see [exhausted] for why the
                            // attempt bound was removed rather than corrected. A
                            // counter nothing branches on cannot wedge anything, so
                            // it can afford to be the simple honest number.
                            .copy(
                                transferAttempts = anchored.transferAttempts + 1,
                                playersEvacuated = false,
                            ),
                    occupancy = occupancy,
                    // A `POST .../transfer` left this process and the proxy took it.
                    // That is work whatever the sweep then reports, and it is what
                    // keeps a resume that actually asked the proxy to move somebody
                    // from being downgraded alongside one that only re-derived.
                    workDone = true,
                    // **Never `Progressed`, whatever the sweep says.** A sweep in
                    // flight is something that changes by itself, so `Waiting` is the
                    // honest outcome — but the reason it must not be `Progressed`
                    // even on a sweep the proxy calls finished is sharper than that:
                    // `remaining` is the *proxy's* count, and it is zero for a
                    // backend whose players connected straight to its own port.
                    // Reporting progress on it would make `ReconcileLoop` reset the
                    // backoff on every pass of a drain that is getting nowhere, which
                    // is the hot loop wearing the proxy's number — and it would say
                    // "every player has been moved" about a server two people are
                    // playing on. Only the ping advances this state, in
                    // [awaitEvacuated].
                    outcome =
                        ReconcileOutcome.Waiting(
                            "asked proxy=${router.proxy} to move this server's players to `$destination`; the " +
                                "proxy reports ${report.remaining} still on it (${report.unmoved} move(s) to " +
                                "re-try). The Server List Ping is what decides when this server is empty",
                            POLL,
                        ),
                )
            }

            is TransferReport.DestinationLost -> {
                // The destination stopped being one — it went away, or it started
                // draining itself — so the drain lets go of it and a later pass
                // picks another, rather than moving players onto a server they would
                // have to be moved off again.
                //
                // **It aborts rather than stepping quietly back to `SEALED`.** The
                // fleet saying a sibling is a fine destination while the proxy keeps
                // refusing it is stable — an `ADDRESS_CONFLICT` on that sibling will
                // do it — and without a recorded failure the drain simply cycled
                // `SEALED` → `TARGET_RESOLVED` → refused → `SEALED` at the poll
                // interval, with `secureDestination` reporting `Progressed` every
                // other pass so `ReconcileLoop` reset the backoff and nothing ever
                // escalated. Recording it is what makes the attempt count rise, the
                // backoff grow and a human eventually get called.
                LOG.info(
                    "destination `{}` is no longer eligible for server={}: {}. Choosing another",
                    destination,
                    pass.server,
                    report.detail,
                )
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = anchored.copy(destination = null, transferAttempts = anchored.transferAttempts + 1),
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_TRANSFER_FAILED,
                    failureClass = FailureClass.RETRYABLE,
                    message =
                        "proxy=${router.proxy} will not accept `$destination` as a destination for this " +
                            "server's players: ${report.detail}. Another is chosen on the next pass; nobody is " +
                            "disconnected and the container is not stopped",
                )
            }

            is TransferReport.Refused -> {
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = anchored.copy(transferAttempts = anchored.transferAttempts + 1),
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_TRANSFER_FAILED,
                    failureClass = if (report.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                    message = "proxy=${router.proxy} refused to move this server's players: ${report.detail}",
                )
            }

            is TransferReport.Unavailable -> {
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = anchored,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.PROXY_CONTROL_UNREACHABLE,
                    failureClass = if (report.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                    message = "the players could not be moved: ${report.detail}",
                )
            }
        }
    }

    /**
     * Whether step 4 has been trying long enough, and why.
     *
     * ## One bound, and it is the clock
     *
     * There were two, and the attempt bound was worse than redundant. The plugin's
     * start-or-join means a repeat while a sweep is in flight asks nobody to move
     * again, so counting *passes* counted nothing that happened — and at a
     * two-second poll it spent a six-sweep budget in twelve seconds, which made the
     * documented `playerTransferTimeout` allowance unreachable. Counting completed
     * sweeps instead is no better in the one case that matters: a sweep the proxy
     * settles instantly starts fresh on every pass, and the count runs away again at
     * exactly the same rate.
     *
     * The clock has neither problem. It measures the thing an operator would
     * measure — how long these players have been failing to move — it cannot be
     * outrun by the poll interval, and it is what
     * `drain-protocol/references/state-machine.md` prescribes. The allowance is
     * extended per player, because a fixed value always fails on a full server.
     *
     * [mcorch.schema.DrainStatus.transferAttempts] survives as a *report* — how many
     * times this drain has asked — and nothing branches on it. That is the whole of
     * its remit: a counter nothing gates on cannot wedge anything.
     *
     * ## Both inputs are arguments, not lookups
     *
     * [transferStartedAt] is passed in and is not nullable, so this function cannot
     * be reached without an anchor and cannot invent one. That is deliberate and it
     * is the third anchor this bound has had. `enteredStateAt` restamps on every
     * park-and-resume, so the allowance was handed back in full every cycle;
     * `sealRequestedAt` does not restamp but is stamped at step *2*, so it was
     * missing on three reachable paths and — when present — spent step 4's budget on
     * a destination search, a flapping control endpoint or an orchestrator restart.
     * A `?:` fallback is what made both of those silent, so there is none anywhere on
     * the path: [issueTransfer] stamps a missing anchor into the drain it returns,
     * which is the only version of "no fallback" a later edit cannot quietly undo.
     *
     * [online] is passed for the same reason and it is the same mistake one size
     * smaller. It was read here as `pass.occupancy?.online ?: 0`, and the argument
     * for the fallback was that this is only reachable with a `Joinable` probe — the
     * argument that was also true of the anchor, right up until it was not. A zero
     * there silently shrinks the allowance to its floor, which is the safe direction
     * and still the wrong number. The caller holds the probe that established it.
     *
     * ## Only reachable with players online
     *
     * [transferStep] takes the zero-player exit before calling anything that
     * consults this, so a server that has emptied finishes its drain whatever this
     * says. That ordering is what stops a spent budget becoming a drain that can
     * never be completed — see [transferStep].
     */
    private fun exhausted(
        pass: DrainPass,
        transferStartedAt: Instant,
        online: Int,
    ): String? {
        val allowance = pass.subject.playerTransferTimeout + PER_PLAYER_TRANSFER_ALLOWANCE * online
        val waited = JavaDuration.between(transferStartedAt, pass.now).toKotlinDuration()
        return if (waited > allowance) {
            "players have been moving for ${waited.inWholeSeconds}s, past the " +
                "${allowance.inWholeSeconds}s allowed for $online player(s)"
        } else {
            null
        }
    }

    /**
     * Reads the proxy's own count and says so when it disagrees with the ping.
     *
     * It decides nothing. The value of reading it at all is that the disagreement
     * itself is diagnostic: the proxy seeing fewer means somebody is connected
     * straight to the backend port, which is a deployment problem an operator wants
     * to know about, and the proxy seeing more means the ping is answering from a
     * cached status that is behind.
     */
    private suspend fun corroborate(
        pass: DrainPass,
        router: DrainRouter,
        pinged: Int,
    ) {
        val reported = router.observedPlayers() ?: return
        if (reported == pinged) return
        LOG.info(
            "server={} occupancy disagrees: the Server List Ping reports {} and proxy={} reports {}. The ping " +
                "decides; a player connected straight to the server's own port is invisible to the proxy",
            pass.server,
            pinged,
            router.proxy,
            reported,
        )
    }

    /**
     * Step 6 and then step 7, in that order and on separate passes.
     *
     * Deregistration is the one step that cannot be level-triggered: it is the last
     * thing before the stop, so "assert it every pass" would mean asserting it from
     * states that must not reach it. It therefore happens exactly here, on the edge
     * into it, and the abort path out carries the compensating re-registration —
     * see [abort].
     *
     * The plugin refuses `DELETE` outright while anybody is connected, and there is
     * no force flag. Reaching that refusal means this caller's own ordering was
     * wrong, so it aborts rather than pressing on: [requireEmpty] has just
     * confirmed zero players by ping, and the proxy contradicting that is exactly
     * the case where the safe answer is to stop.
     *
     * ## The replacement question, asked here rather than in [stop]
     *
     * [replacementIsBuildable] guards the whole of steps 6 and 7, not just the stop,
     * because a deregistration is irreversible in the sense that matters: it takes
     * the backend out of routing, and a refusal one pass later would deregister and
     * re-register on every cycle for as long as the artefact is missing. Asked at
     * the entry, a refusal costs nothing at all. [stop] has this one caller — the
     * premise its own `mayStop` backstop rests on, pinned by
     * `scripts/dev/drain-wiring-mutations.sh` — so "at the entry" and "before the
     * stop" are the same place until that changes.
     */
    private suspend fun letGoAndStop(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        replacementIsBuildable(pass, drain)?.let { return it }
        val router = pass.subject.router
        val now = pass.now
        if (router == null || drain.deregisteredAt != null) return stop(pass, drain)
        return when (val outcome = router.deregister()) {
            is SealOutcome.Asserted -> {
                LOG.info("deregistered server={} from proxy={}", pass.server, router.proxy)
                DrainProgress(
                    drain = drain.copy(deregisteredAt = now),
                    occupancy = pass.occupancy,
                    workDone = true,
                    outcome = ReconcileOutcome.Progressed("the backend has left the proxy's routing table"),
                )
            }

            is SealOutcome.Refused -> {
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = drain,
                    occupancy = pass.occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_TRANSFER_FAILED,
                    failureClass = if (outcome.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                    message =
                        "proxy=${router.proxy} refused to deregister this backend: ${outcome.detail}. The " +
                            "container is not stopped: the proxy still has somebody on it, whatever the ping said",
                )
            }

            is SealOutcome.Unavailable -> {
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = drain,
                    occupancy = pass.occupancy,
                    now = now,
                    reason = FailureReason.PROXY_CONTROL_UNREACHABLE,
                    failureClass = if (outcome.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                    message =
                        "the backend could not be deregistered, so the container is not stopped: " +
                            "${outcome.detail}. Stopping it while the proxy still routes to it would send new " +
                            "players to a dead address",
                )
            }
        }
    }

    /**
     * The replacement question, asked one last time immediately before the drain
     * does anything that cannot be undone.
     *
     * `Reconciler.replacementBlocker` asks it before a drain starts, and exempts a
     * drain already in flight because *"the container it would have saved is gone or
     * going"*. That sentence is true from the stop onwards and false for every pass
     * before it — sealing, waiting, transferring, saving — which on a populated
     * server is hours. Inside that window an orchestrator upgrade can replace the
     * asset directory the control plugin is staged in, or a referenced secret can be
     * rotated away, and the drain then completes its teardown into a create that
     * refuses permanently. For a proxy that is the fleet's front door, gone, with a
     * failure the remedy cannot lift.
     *
     * So it is asked again here, where a refusal costs a park rather than an outage.
     * *Nothing has been taken away yet* is true of the first pass through
     * `DEREGISTERED` and not of a re-entry: the pass after a confirmed
     * deregistration comes back through here, and so does a resume that lands on
     * `DEREGISTERED` with a stop already issued. Both park safely — [abort]'s
     * [restoreRegistration] is the compensation for the first, and a stop inside its
     * grace period is a container the drain will observe down — but the *message*
     * has to say which, or it tells an operator nothing has moved about a backend
     * that has left the routing table.
     *
     * ## Scope and class, both deliberate
     *
     * `REPLACEMENT` only. A `DELETION` needs no create, and a delete that a create
     * can block is how a workload becomes undeletable — the failure mode this whole
     * pre-flight exists to avoid, arriving from the other direction.
     *
     * `RETRYABLE`, although the create's own answer for a missing artefact is
     * permanent, and for the reason `replacementBlocker` gives at greater length:
     * `Reconciler.isBlockedByPermanentFailure` lifts only on a generation bump or a
     * delete, and staging a JAR or a secret is neither. A permanent class here would
     * freeze a server whose operator has just done exactly what the message asked.
     *
     * ## What it cannot see
     *
     * [Node.checkWorkload] is the create's *spec* derivation and not the whole
     * create: `LocalNode` also prepares host directories, which cannot be checked
     * without creating something. A volume or log root that has become unwritable is
     * therefore still a create that refuses after the teardown — narrower than the
     * window this closes, and named rather than implied. See
     * `LocalNode.checkWorkload`.
     */
    private suspend fun replacementIsBuildable(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress? {
        if (pass.cause != DrainCause.REPLACEMENT) return null
        try {
            pass.node.checkWorkload(pass.subject.replacementSpec)
        } catch (refused: NodeException) {
            // Every [NodeException], not just `Rejected`. "I could not ask" is never
            // "yes" — [Node.checkWorkload] says so — and an exception escaping this
            // controller would skip the compensating edges [abort] carries.
            return abort(
                subject = pass.subject,
                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = drain,
                occupancy = pass.occupancy,
                now = pass.now,
                reason = FailureReason.CONTAINER_CREATE_FAILED,
                failureClass = FailureClass.RETRYABLE,
                message =
                    "node `${pass.node.name}` cannot build the replacement this drain is for: " +
                        "${refused.message}. Nothing has been stopped or removed on that, and the workload " +
                        "that is running stays where the drain left it — which on a re-entry after step 6 may " +
                        "be out of the proxy's routing, or inside the grace period of a stop already issued. " +
                        "The drain finishes on its own once the node can build what would take its place",
            )
        }
        return null
    }

    /**
     * The edge back to `SAVING` from a state that needed a save confirmation and
     * has not got one any more — and the one place a drain that keeps taking that
     * edge is written down.
     *
     * ## Taking it once is the protocol; taking it for ever is a defect nobody could see
     *
     * A confirmation is voided by a container that restarted or by a gap in which
     * the loop was not watching, and the honest response to either is to save
     * again. But nothing failed while that happens: the pass reports `Progressed`,
     * the save is real work, the recorded state advances, and the drain arrives
     * back where it started. A container crash-looping under a delete produces
     * exactly that, for ever — a full `save-all flush` at a live server every other
     * pass, `DRAINING` on the badge, and no failure, no attempt count and no
     * escalation anywhere. It is the sixteenth audit's first critical seen from the
     * other end: the primary cause (evidence stamped before the work that earned
     * it) is fixed, and this is what makes any *remaining* cause visible instead of
     * silent.
     *
     * ## Why the record survives, when three rounds of records did not
     *
     * The clearing rule in [settleRecords] is "a pass that did work and did not
     * begin parked has recovered", and this defect is precisely *did work and did
     * not recover*. It is not worked around here, and it is not weakened: the
     * failure is recorded by **the pass that does no work**. The abort parks, so
     * [settleRecords] returns early; the pass after it is the resume, which is
     * excluded by the hysteresis; and the pass after *that* is this abort again for
     * as long as the cycle continues. The one pass that would clear the failure —
     * work, not resuming — is the pass that finally reaches the stop, which is
     * recovery and should clear it.
     *
     * Recording it in [save] instead was tried and is wrong. That step is reached
     * by every drain that saves for any reason, and a drain parked on a *refused
     * stop* re-saves on every resume by design, so it was diagnosed as a save
     * problem and the accurate failure was overwritten by the wrong one. This
     * branch is reached only because a confirmation went stale, which is the
     * defect's own signature.
     *
     * ## `RETRYABLE`, and the container is not touched
     *
     * `failure-modes.md` item 7: at a limit the loop stops *trying*, it does not
     * stop the container — and here it does not even stop trying. The class stays
     * retryable so that a container whose runtime hiccuped cannot become
     * undeletable, the drain re-enters through the ladder and saves again on the
     * next pass, and what changes is only that the attempt count now rises and the
     * anchor stops moving, so `escalates` reaches the threshold and a human is
     * called.
     *
     * ## What the abort widens, written down rather than left to be rediscovered
     *
     * [abort] calls [restoreRegistration], and [awaitStopped] can reach here from
     * `STOPPING` — so this can put a backend back into the proxy's routing table
     * while a container stop with a **live grace period** is counting down to a
     * SIGKILL, and stop asserting the seal in the same act. The two pre-existing
     * paths that re-register from `STOPPING` do it after a stop the runtime
     * *refused*, so no kill was pending; this one has one genuinely in flight.
     *
     * It is the accepted posture widened rather than a new one. A parked drain
     * takes players again because a running server nobody can reach is the worse
     * failure, and the exposure is bounded: once the grace period elapses the
     * container is down, and the container-is-down branch releases the
     * registration properly.
     */
    private suspend fun goingRoundInCircles(
        pass: DrainPass,
        drain: DrainStatus,
        detail: String,
    ): DrainProgress {
        val now = pass.now
        val problem = drain.saveEvidenceProblem(pass.observation.startedAt)
        // Produced where it is used and written back into the returned drain,
        // never substituted for the length of one call. A state that reaches this
        // without an anchor — a row written before the field existed — gets one
        // stamped and is judged from the next cycle, which costs one cycle and
        // cannot silently disable the bound. See `DrainStatus.transferStartedAt`
        // for the same rule and the three wedges that taught it.
        //
        // **It spans laps on purpose.** Nothing on the success path clears it —
        // only [forgetSaveEvidence], which needs an observed player — so `circling`
        // is the age of the *first* forced re-save and not of this one, and it keeps
        // rising across laps that each ended with a real save and a re-issued stop.
        //
        // Clearing it on a lap that reached `DEREGISTERED` with a fresh confirmation
        // was considered and is wrong, because that is what every lap of the
        // defining cycle does: `STOPPING` → `SAVING` → a genuine save →
        // `DEREGISTERED` → `STOPPING`, for as long as a container refuses to exit.
        // The clear would hand the allowance back once per lap and disable the
        // detector for exactly the defect it exists for — the sixteenth audit's
        // first critical, arriving by a different route. A drain that has finished
        // takes its whole record with it, so an anchor only survives while the drain
        // has not.
        //
        // What the span costs is reporting, and it is why the message below states
        // the measured fact once — how long ago a confirmation was first voided, and
        // that it has happened again — rather than restating the same number as a
        // claim about how long nothing has been working. Some of that interval was
        // the drain making honest progress between laps, and the message says so in
        // as many words: an anchor hours old and a cause that started a minute ago
        // produce the same number, and the first pass of the second one aborts on it.
        val anchor = drain.resaveForcedAt ?: now
        val anchored = drain.copy(resaveForcedAt = anchor)
        val circling = JavaDuration.between(anchor, now).toKotlinDuration()
        // The bound is **one lap**, and a lap is not one evidence gap.
        //
        // It was `evidenceGap` alone, on the argument that a confirmation is
        // worth nothing once it is older than one. That is true of the
        // confirmation and false of the cycle: a lap is void, save, try to stop,
        // and the save in the middle is the one step in this protocol that runs
        // for minutes. The schema sizes it at `saveTimeout`, 180 seconds by
        // default, while `evidenceGap` is 30 and lives in a different module — so
        // any server whose world takes longer than half a minute to flush could
        // be told it "does not clear on its own" by a bound it had never been
        // given time to satisfy, about a cycle that then cleared on its own two
        // passes later.
        //
        // So the allowance is the quantity the lap is actually made of:
        // [DrainSubject.saveTimeout], the schema's own ceiling on the flush in the
        // middle of it, plus one [evidenceGap] for the passes at either end.
        //
        // It was `stopGracePeriod`, as a stand-in for the save timeout, and the
        // substitution was sound in direction and wrong in two ways worth writing
        // down rather than rediscovering. The schema guarantee it rested on
        // (`SpecInvariants.stopGraceProblem`) is `PaperServer`'s; `ProxyLifecycleSpec`
        // has no such rule, so for a proxy the arithmetic was founded on nothing and
        // was harmless only by an unwritten reachability argument — which is exactly
        // how the round-17 exemption came to be widened, and which was wrong anyway:
        // a proxy whose container has lost its `world-data` label is drained as
        // though it held a world, and one that loses it after `SAVING` reaches here
        // with `mayStop` false at `DEREGISTERED`. What makes any allowance safe
        // for a proxy is that its save cannot take time at all — see
        // [ProxyDrainSubject.saveTimeout], which now carries that argument instead of
        // leaving it to be reconstructed. And `stopGracePeriod` is an
        // operator's number, capped at two hours: someone who set a long one for an
        // unrelated reason bought a two-hour escalation latency on a defect whose
        // honest lap is about a minute, flushing a multi-gigabyte world once a minute
        // meanwhile with nothing recorded. Reading the save timeout makes both
        // subjects answer for themselves — a proxy's is zero, because its lap
        // contains no save.
        //
        // Note what is *not* bought: two thirty-minute loop stalls in a row still
        // trip this, and should, because a drain that has been chasing a usable
        // confirmation for an hour is the thing being detected.
        if (circling > evidenceGap + pass.subject.saveTimeout) {
            return abort(
                subject = pass.subject,
                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = anchored,
                occupancy = pass.occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = FailureClass.RETRYABLE,
                // The measured fact, never the prognosis. This said "it does not
                // clear on its own", which is the opposite of what the class means:
                // the abort is `RETRYABLE` by deliberate choice and `resumeInto`
                // re-enters on the next pass, so the cycle *does* clear once its
                // cause stops — a container that settles, a loop that catches up.
                // What an operator does when told a drain will not clear is
                // intervene on the container, and the only intervention available
                // there is `crictl stop`, which is a container stopped with no save.
                // A false diagnostic here is a data-loss vector one human step
                // removed.
                //
                // So it says what was measured, what the number is *not*, and what
                // the next pass does. The disclaimer is not padding: the anchor
                // spans laps, so a drain that circled this morning and hit an
                // unrelated cause a minute ago aborts on that cause's first lap
                // reporting hours — every word of it true, and read as hours of
                // downtime by anyone who is not holding this paragraph.
                message =
                    "this drain keeps saving the world and never reaches the stop: a confirmed save was first " +
                        "voided ${circling.inWholeSeconds}s ago and it has happened again since — $problem. " +
                        "That is the age of the first voiding and not a duration of downtime; laps in between " +
                        "may each have saved. Nothing has been stopped or removed and the server is still " +
                        "running, and the next pass saves again and re-tries the stop, so this clears on its " +
                        "own once the cause does: check whether the container is restarting underneath the " +
                        "drain, and whether the loop is reaching this server on every pass",
            )
        }
        return DrainProgress(
            drain = anchored.moveTo(DrainState.SAVING, now),
            occupancy = pass.occupancy,
            outcome = ReconcileOutcome.Progressed("$detail: $problem"),
        )
    }

    /**
     * Runs [next] only if a fresh ping reports zero players; blocks the drain if
     * anybody is on, and aborts if the ping could not answer at all.
     *
     * ## What it guards, and why that set shrank
     *
     * `SAVING`, `DEREGISTERED`, `STOPPING` and the `DRAIN_FAILED` resume — the
     * states that flush the world, let go of the backend and take the container
     * away. It used to guard `SEALED`, `TARGET_RESOLVED` and `TRANSFERRING` too,
     * which was defensible only while those three had no bodies: a destination
     * search that aborts when players are online never runs, and a transfer that
     * refuses to move anybody unless nobody is there is not a transfer. See the
     * class note for the single-point argument that replaced "one guard for six
     * states".
     *
     * It is no longer where the voiding *happens*, and the sentence that used to
     * be here is the reason. It said this was the only place a positive count
     * voided a confirmation, named the one other reader that did it at its own
     * call site, and carried a maintained count of them — and a later change
     * added a reader that voided nothing without falsifying a single test. The
     * rule is a return type now: see [readPlayers], which every branch below
     * goes through.
     *
     * ## The message says why *this* drain is waiting, not why drains wait
     *
     * It used to say "there is no proxy to transfer them through" unconditionally,
     * which is false for every proxied backend that reaches it — and they do reach
     * it, whenever somebody connects straight to the backend port during `SAVING`,
     * `DEREGISTERED` or the gated resume, which is precisely the case the ping can
     * see and the proxy cannot. The reason a proxied backend waits here is that its
     * transfer is already behind it: step 4 moved everybody the proxy knew about,
     * and whoever is left is not reachable through it. Both waits are correct and
     * they are correct for different reasons, so the operator is told which.
     */
    private suspend inline fun requireEmpty(
        pass: DrainPass,
        drain: DrainStatus,
        next: () -> DrainProgress,
    ): DrainProgress =
        when (val reading = drain.readPlayers(pass.probe, pass.now)) {
            is PlayerReading.Empty -> {
                next()
            }

            is PlayerReading.Occupied -> {
                val resaves = drain.worldSaved
                val why =
                    if (pass.subject.router == null) {
                        "there is no proxy to transfer them through"
                    } else {
                        "the transfer through the proxy is already behind this drain, so whoever is left " +
                            "is connected straight to this server's own port and the proxy cannot move them"
                    }
                blocked(
                    subject = pass.subject,
                    // Somebody is on the server, so anything this drain had
                    // saved is now behind whatever they are doing. The voiding
                    // is not done here — [readPlayers] has already done it, and
                    // this is the drain it handed back.
                    drain = reading.drain,
                    occupancy = reading.occupancy,
                    now = pass.now,
                    reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                    // What the login path is left in is deliberately **not** stated
                    // here: [blocked] composes it from [loginPathAfterAPark], the
                    // one function that knows the three answers. This branch used
                    // to claim "the server keeps running and stays joinable", which
                    // is false of a workload that seals itself and has a seal in
                    // place — the state the gated [resume] produces.
                    message =
                        "waiting for the server to empty. ${reading.online} of ${reading.max} player slots " +
                            "are in use and $why, so the protocol waits rather than disconnecting anybody. The " +
                            "drain resumes on its own once it is empty" +
                            if (resaves) ", and saves the world again before it stops" else "",
                )
            }

            // The server did not answer, and it makes no difference here whether
            // that is because the probe ran and got silence or because the probe
            // could not be run at all. Neither is a zero-player report, and
            // treating either as one is how a drain stops a server with people
            // on it. One branch, on purpose: see [ProbeOutcome.Unanswered] for
            // why the distinction is deliberately not available at this call
            // site.
            //
            // The *confirmation* goes. A drain can sit here for as long as the
            // exec path is unhealthy, and nothing seals joins meanwhile: players
            // can arrive, play and leave entirely inside the blind window, and
            // the zero-player probe that eventually succeeds is silent about all
            // of it. So a confirmed save survives only while the chain of
            // zero-player observations behind it is unbroken. The cost of
            // breaking it is one more `save-all flush` on an empty server.
            //
            // The record of a *delivered* save request stays, which is why this
            // is `forgetSaveConfirmation` and not `forgetSaveEvidence`. This
            // pass observed nothing, so it has no grounds to lift the wedge that
            // keeps a second `save-all flush` off a live server: only seeing a
            // player does that, because only that makes the earlier request
            // worthless. With the stronger call here, a delivered-but-
            // unconfirmed save followed by one failed probe would drop
            // `saveRequestedAt`, demote the abort from permanent to retryable,
            // and let the next healthy pass re-send the save — silently
            // replacing "a human confirms the world state" with "the exec
            // channel flickered".
            is PlayerReading.Unanswered -> {
                unansweredProbe(pass, drain, reading.probe)
            }
        }

    /**
     * The abort for a ping that did not answer, shared by [requireEmpty] and by
     * [awaitEvacuated].
     *
     * Two call sites and one body, deliberately: the second one exists because step
     * 4 cannot use [requireEmpty] any more, and a second copy of this reasoning is
     * a second place for it to drift. See [requireEmpty] for the full argument.
     */
    private suspend fun unansweredProbe(
        pass: DrainPass,
        drain: DrainStatus,
        probe: ProbeOutcome.Unanswered,
    ): DrainProgress =
        abort(
            subject = pass.subject,
            permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
            drain = drain.forgetSaveConfirmation(),
            occupancy = pass.occupancy,
            now = pass.now,
            reason = FailureReason.DRAIN_STALLED,
            failureClass = if (probe.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
            message = "cannot confirm zero players: ${probe.detail}",
        )

    /** Step 5. Requests a save at most once, and only proceeds on a confirmed completion. */
    private suspend fun save(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        val observation = pass.observation
        val contract = pass.contract
        val occupancy = pass.occupancy
        val now = pass.now
        val server = pass.server
        if (!contract.holdsWorldData) {
            // Ephemeral storage: the operator asked for a disposable instance
            // by name, and there is no world to flush. `worldSavedAt` stays
            // null because nothing was saved — stamping it would make a status
            // read claim evidence that does not exist, and `mayStop` lets this
            // container through on `holdsWorldData` alone rather than on a
            // confirmation it never got.
            //
            // Read off the container, not off the definition: an edit from
            // `persistent` to `ephemeral` must not turn the drain of a container
            // that holds a world into a stop with no save.
            //
            // **`workDone` stays false**, and that is the whole of critical 1 from
            // the fifteenth audit. This reads a label the pass already had in hand
            // and issues nothing, so a drain parked on a refused deregistration
            // re-entered here every other pass, advanced its state, reported
            // progress and deleted the failure carrying its escalation anchor.
            // `attempts` sat at 1 and the fifteen-minute threshold was unreachable
            // while a `PUT`, a `DELETE` and two pings went out every second, for
            // ever.
            return DrainProgress(
                drain = drain.moveTo(DrainState.DEREGISTERED, now),
                occupancy = occupancy,
                outcome = ReconcileOutcome.Progressed("ephemeral storage: no world to save"),
            )
        }
        if (drain.saveIsCurrent(observation.startedAt, now, evidenceGap)) {
            // Two stored timestamps compared. Same shape, same answer: nothing was
            // asked of the server, so nothing was earned.
            return DrainProgress(
                drain = drain.moveTo(DrainState.DEREGISTERED, now),
                occupancy = occupancy,
                outcome = ReconcileOutcome.Progressed("the world save is already confirmed"),
            )
        }
        if (drain.saveRequestedAt != null) {
            // A save went out on an earlier pass and was never confirmed —
            // the loop restarted, or the exec did not come back. Re-sending it
            // is not the answer: this drain has no evidence the world is on
            // disk, and only a human can supply that.
            return abort(
                subject = pass.subject,
                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = drain,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_SAVE_TIMEOUT,
                failureClass = FailureClass.PERMANENT,
                message =
                    "a world save was requested at ${drain.saveRequestedAt} and its completion was never " +
                        "confirmed. The save request is not re-sent and the server keeps running: confirm the " +
                        "world state before this server is stopped",
            )
        }

        return when (val outcome = pass.subject.requestSave(pass.node, observation, contract)) {
            SaveOutcome.Confirmed -> {
                // Both instants below are read *after* the save returned, never
                // from `pass.now`, and this is the whole of the sixteenth audit's
                // first critical.
                //
                // `pass.now` is taken once at the top of [advance], before the
                // probe and long before `save-all flush` comes back. A save is
                // the only step in this protocol that can run for minutes — the
                // schema's own default `saveTimeout` is 180 seconds — so
                // stamping evidence with it dates the evidence to before the
                // work that produced it. The next pass then measures its gap
                // from that stale instant, finds it wider than
                // `saveEvidenceMaxGap`, voids a confirmation that was seconds
                // old, and returns to `SAVING`. Nothing fails, so nothing
                // escalates: `Progressed` every pass, `DRAINING` on the badge,
                // and a full flush at a live server for ever. The threshold was
                // a save of about 28 seconds.
                //
                // **Which of the two instants carries that is worth knowing, and
                // it is not the one the name suggests.** [saveIsCurrent] returns
                // on `!confirmed.isBefore(containerStartedAt)` whenever the
                // runtime reports a start time, which is every running
                // container — it never consults the age. So the freshness half
                // of `dropUnusableSaveEvidence` rests entirely on `watched`,
                // which reads `lastProbedAt`, which is this `occupancy`'s
                // instant. Sabotaging `worldSavedAt` alone leaves the livelock
                // test green; sabotaging the occupancy reddens it.
                //
                // That claim is about **two functions agreeing**, and it goes with
                // them if either moves: [dropUnusableSaveEvidence] and the
                // `saveIsCurrent` early return above are computed from the same
                // `observation.startedAt`, the same `now` and the same
                // `evidenceGap`, which is why "the confirmation half never fires
                // for a running container" is true of both. Give one of them a
                // different argument and the sentence above stops holding without
                // anything else changing.
                //
                // Both instants are
                // stamped honestly here — `worldSavedAt` still decides the
                // no-start-time branch, and an instant that lies about when it
                // was established is a defect waiting for the next reader — but
                // the load-bearing one is the occupancy.
                //
                // The re-probe is what keeps the rule honest rather than merely
                // wider. Invariant 3 is that a stop follows a *confirmed* save
                // with nobody on the server, and that interval still has a real
                // zero-player reading at each end — this one closes it. A
                // re-probe that does not answer leaves occupancy null and the
                // evidence unwatched, so a later pass saves again rather than
                // stopping on a reading nobody took.
                //
                // A re-probe that finds players goes through [readPlayers] like
                // every other reader of a count, and **the protection is the
                // branch, not the voiding**. The confirmation is never written:
                // only the sibling case below stamps `worldSavedAt`, so there is
                // nothing here for [forgetSaveEvidence] to take away except
                // `playersEvacuated` and the re-save anchor, which is why a
                // sabotage of the voiding leaves this scenario green and a
                // sabotage of the re-probe reddens it. Both claims are pinned
                // separately.
                //
                // It used to stamp the confirmation and record the players it
                // found, on the argument that the next pass's [requireEmpty]
                // would block the stop — which holds only while the player is
                // still connected, and the losing case is exactly when they are
                // not. Somebody logging in during a sixty-second save, building
                // for ten seconds and disconnecting left a confirmation stamped
                // after their arrival and an occupancy that *refreshed* the
                // evidence window rather than breaking it, because `lastProbedAt`
                // advances on any probe that answered whatever it counted. The
                // next pass then read zero players, `mayStop` passed on that
                // confirmation, and the container stopped. Paper writes player
                // data on quit, so their inventory survived and their build did
                // not. The window is bounded above by `saveEvidenceMaxGap` —
                // longer than that and the observation-gap rule voids the
                // confirmation anyway — and it is 1 to 30 seconds of world edits
                // on a path where the loop held direct evidence they were there.
                //
                // The probe runs [NonCancellable], and that is not tidiness — but
                // the reason is not the permanent wedge an earlier version of this
                // comment described. This branch is reached only with
                // `saveRequestedAt` null (the guard above aborts `PERMANENT`
                // otherwise), so a cancellation here cannot leave an outstanding
                // request behind. What it loses is the *record that a
                // `save-all flush` was delivered at all*, and the next pass then
                // sends a second one: CLAUDE.md invariant 5, and exactly what the
                // three tests in `SaveRecordDurabilityTest` and `ReconcileLoopTest`
                // pin when they assert `saves shouldHaveSize 1`. They caught this
                // when the probe was added as an ordinary suspension point.
                //
                // Two residuals worth stating rather than implying. The cost is
                // bounded by the probe's own timeout, but that bound is a private
                // constant inside `PaperServerAgent` and nothing here asserts it —
                // a probe given a minute would make a shutdown wait a minute. And
                // the region depends on the CRI channel outliving the loop in the
                // same way the store write does; `Main` pins the store half and
                // nothing pins the channel half.
                val confirmedAt = clock.instant()
                val settled = withContext(NonCancellable) { pass.subject.probe(pass.node, observation.handle) }
                // Read *after* the probe, and separately from `confirmedAt`. The
                // two answer different questions — when the save finished, and
                // when somebody last looked at who was online — and [readPlayers]
                // requires the second. They were one instant, read before the
                // probe, which is the shape of the defect this branch exists to
                // remove: off by up to the probe timeout, in the safe direction,
                // and wrong for the same reason.
                val observedAt = clock.instant()
                when (val reading = drain.readPlayers(settled, observedAt)) {
                    is PlayerReading.Occupied -> {
                        LOG.warn(
                            "server={} had {} player slots in use when its world save completed; the " +
                                "confirmation is discarded and the drain waits",
                            server,
                            reading.online,
                        )
                        // `sideEffectIssued` is deliberately not threaded through
                        // [blocked], and the flush really did go out. Nothing is
                        // lost by that: the drain being recorded here has no
                        // confirmation and no outstanding request — [readPlayers]
                        // voided both — so there is no side-effect record for the
                        // shield to protect, and a pass cancelled before the store
                        // write is re-derived identically by the next one, which
                        // probes, sees the same player and blocks the same way.
                        //
                        // The repeat that *is* accepted is the second
                        // `save-all flush` once they log off. It is not a
                        // duplicate: a player has been on the server since, which
                        // is the one thing that makes an earlier flush worthless,
                        // and it is the same trade [forgetSaveEvidence] documents
                        // and this project has taken twice already.
                        blocked(
                            subject = pass.subject,
                            drain = reading.drain,
                            occupancy = reading.occupancy,
                            now = observedAt,
                            reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                            message =
                                "the world save completed, and ${reading.online} of ${reading.max} player " +
                                    "slots were in use by the time it did. The confirmation is not used: " +
                                    "somebody joined while the save was running, so it says nothing about " +
                                    "what they have done since. Nothing is stopped, the server keeps running, " +
                                    "and the drain saves again once it is empty",
                            // The save *did* complete, and observed status says so
                            // separately from the drain's evidence. `worldSaved` is
                            // false, so `:api` renders this as "no save is confirmed
                            // for the world as it is now; the last confirmed save was
                            // at ...", which is the true sentence.
                        ).copy(saveConfirmedAt = confirmedAt)
                    }

                    is PlayerReading.Empty, is PlayerReading.Unanswered -> {
                        DrainProgress(
                            drain =
                                drain
                                    .moveTo(DrainState.DEREGISTERED, confirmedAt)
                                    // The request is no longer outstanding — it
                                    // came back, and the server said the save
                                    // finished — so the wedge is released and the
                                    // confirmation takes its place. The two are
                                    // disjoint on purpose: a confirmation left
                                    // sitting beside its own request timestamp is
                                    // what used to make the next `SAVING` read a
                                    // completed save as one that never returned.
                                    .copy(saveRequestedAt = null, worldSavedAt = confirmedAt),
                            // No `?: occupancy` fallback, deliberately. Falling
                            // back to the pass-entry reading would record an
                            // instant from before the save as though it were taken
                            // after — which is the defect this branch exists to
                            // fix, restored by the line meant to be tidy about a
                            // null.
                            occupancy = reading.occupancy,
                            saveConfirmedAt = confirmedAt,
                            sideEffectIssued = true,
                            // A `save-all flush` went out and the server said it
                            // finished. Work by any honest measure — which is why
                            // it alone does not clear a failure recorded by the
                            // step *after* it; see [settleRecords].
                            workDone = true,
                            outcome = ReconcileOutcome.Progressed("world save confirmed"),
                        )
                    }
                }
            }

            is SaveOutcome.Unconfirmed -> {
                // The request reached the server. Record that it went out so no
                // later pass sends a second one, and stop here: a timeout tells
                // you the save has not finished, never that it is now fine to
                // stop the container (`failure-modes.md` item 1).
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = drain.copy(saveRequestedAt = now),
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_SAVE_TIMEOUT,
                    failureClass = FailureClass.PERMANENT,
                    message = "the world save was not confirmed: ${outcome.detail}",
                    sideEffectIssued = true,
                )
            }

            is SaveOutcome.NotDelivered -> {
                // The request never went out, so trying again later is safe and
                // `saveRequestedAt` stays null.
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = drain,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_STALLED,
                    failureClass =
                        if (outcome.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                    message = "the world save could not be requested: ${outcome.detail}",
                )
            }

            is SaveOutcome.Unconfirmable -> {
                abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = drain,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_STALLED,
                    failureClass = FailureClass.PERMANENT,
                    message = outcome.detail,
                )
            }
        }
    }

    /**
     * Step 7. The stop that ends a drain, and one of the two calls to
     * [Node.stopWorkload] in `:core`'s main sources — the other is [awaitStopped]'s
     * re-issue of *this* stop, behind its own gate. See the class note for both.
     * `DrainWiringTest` holds both halves of that claim: the count and the gate
     * here, and that no other file in the module reaches the stop.
     *
     * Everything it depends on has been established by the states above: zero
     * players confirmed by a probe taken this pass, and — for a workload with
     * world data — a save the server itself reported as completed.
     *
     * The grace period is `spec.lifecycle.stopGracePeriod`, read through
     * [DrainSubject.stopGracePeriod] and used as nothing but what the container stop
     * is given. For a `PaperServer` the schema guarantees it exceeds that server's
     * save timeout (`SpecInvariants.stopGraceProblem`); `ProxyLifecycleSpec` has no
     * such rule and needs none, because a proxy holds no world to flush. Nothing
     * here may read the value *as* a save timeout on the strength of the first half
     * — [DrainSubject.saveTimeout] is the quantity for that, and the one place that
     * made the substitution is written up in [goingRoundInCircles].
     */
    private suspend fun stop(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        val observation = pass.observation
        val contract = pass.contract
        val occupancy = pass.occupancy
        val now = pass.now
        val server = pass.server
        if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap)) {
            // Unreachable through the state machine: `DEREGISTERED` checks the
            // same thing and goes back to `SAVING` instead of calling this. Kept
            // as the last line of defence — if a future edit ever routes into
            // the stop without a current save, it aborts instead of losing a
            // world.
            return abort(
                subject = pass.subject,
                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = drain,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = FailureClass.PERMANENT,
                message = "refusing to stop: ${drain.saveEvidenceProblem(observation.startedAt)}",
            )
        }

        val grace = pass.subject.stopGracePeriod
        // Through a typed record, because this is the line an investigator reads
        // first after a world is lost and the two booleans used to be adjacent
        // `Any?` arguments — a swap would have reported a save that never
        // happened as confirmed. See [ContainerStopRecord].
        LOG.stoppingContainer(
            ContainerStopRecord(
                workload = WorkloadRef(server = server, node = pass.node.name),
                gracePeriod = grace,
                save = WorldSaveEvidence(drain.worldSaved),
                worldData = WorldDataHolding(contract.holdsWorldData),
            ),
        )
        try {
            pass.node.stopWorkload(observation.handle, grace)
        } catch (failure: NodeException) {
            // Caught here rather than allowed out to `Reconciler.nodeFailure`, and
            // the reason is the compensation rather than the classification. By this
            // point the backend has left the proxy's routing table, and an exception
            // escaping the controller skips [abort] — which is the only thing that
            // puts it back. The server would then be running, unreachable through
            // the proxy, with the record still saying it was deregistered.
            //
            // It is also the more precise answer: a stop that the runtime refused is
            // a drain that could not finish, not a pass that could not be completed.
            return abort(
                subject = pass.subject,
                permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                drain = drain,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = if (failure.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                message = "the container stop was refused: ${failure.message}",
            )
        }
        return DrainProgress(
            drain = drain.moveTo(DrainState.STOPPING, now),
            occupancy = occupancy,
            workDone = true,
            outcome = ReconcileOutcome.Progressed("container stop issued"),
        )
    }

    /**
     * Watches for the stopped container. There is no wait-for-exit call to
     * wait on, so this polls by requeueing.
     *
     * A container still running here means the stop did not take. Re-issuing it
     * is safe — the save is already confirmed — and it is re-issued with the
     * *same* grace period. Escalating to a zero-grace kill is deliberately not
     * done: the save being on disk makes a force stop survivable, not
     * necessary, and a shorter grace period cannot make a stuck container stop
     * any faster than the runtime's own kill already will.
     *
     * The probe is read here too, and it is the one state that treats it
     * asymmetrically. A *positive* player count blocks the re-issue and voids
     * the save, like everywhere else. A probe that merely fails does not: a
     * container inside its stop grace period is expected to stop answering, and
     * requiring an answer would leave the drain unable to finish exactly when it
     * is working correctly.
     */
    private suspend fun awaitStopped(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        val observation = pass.observation
        val probe = pass.probe
        val contract = pass.contract
        val occupancy = pass.occupancy
        val now = pass.now
        val server = pass.server
        if (observation.state == WorkloadState.RUNNING) {
            // Only the occupied case is acted on. An unanswered probe falls
            // through with the drain untouched, which is the asymmetry described
            // above and the reason [readPlayers] does not decide that half.
            val reading = drain.readPlayers(probe, now)
            if (reading is PlayerReading.Occupied) {
                LOG.warn(
                    "server={} still has players after a stop was issued; not re-issuing it",
                    server,
                )
                return blocked(
                    subject = pass.subject,
                    // Already voided: see [readPlayers].
                    drain = reading.drain,
                    occupancy = reading.occupancy,
                    now = now,
                    reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                    message =
                        "the container is still running after a stop was issued and ${reading.online} of " +
                            "${reading.max} player slots are in use. The stop is not re-issued and the world is " +
                            "saved again before it is",
                )
            }
            if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap)) {
                // The same rule as in `stop`, and the same answer as in
                // `DEREGISTERED`: re-issuing a stop is only safe *because* a
                // save that is still current is on disk, so if it is not, the
                // drain goes back and saves rather than stopping or giving up.
                //
                // And the same limit, through the same function. A container that
                // will not exit *and* a backoff that has grown past the evidence
                // gap is one cycle wearing two states — `STOPPING` → `SAVING` →
                // `DEREGISTERED` → `STOPPING` — and it restamps `enteredStateAt`
                // on every lap, so the elapsed-in-`STOPPING` limit below cannot
                // see it. This anchor does not restamp, which is the whole reason
                // it exists.
                return goingRoundInCircles(
                    pass = pass,
                    drain = drain,
                    detail = "the stop is not re-issued until the world is saved again",
                )
            }
            LOG.warn(
                "server={} is still running after a stop was issued; re-issuing with the same grace period",
                server,
            )
            // The same catch as in [stop], and it is needed *more* here. This runs in
            // `STOPPING`, which is a sealing state, so the proxy's level trigger will
            // not restore joins either — and by this point the backend has already
            // left the routing table. A node blip escaping to `Reconciler.nodeFailure`
            // would leave the record untouched: deregistered, running, and with
            // nothing that would ever re-register it.
            try {
                pass.node.stopWorkload(observation.handle, pass.subject.stopGracePeriod)
            } catch (failure: NodeException) {
                return abort(
                    subject = pass.subject,
                    permanentFailureStopsPasses = pass.permanentFailureStopsPasses,
                    drain = drain,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_STALLED,
                    failureClass = if (failure.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                    message = "the container stop could not be re-issued: ${failure.message}",
                )
            }
            // **`workDone` is false, and the branch is the argument.** This code is
            // reached *because* `observation.state == RUNNING`, which is to say the
            // previous stop did not take. A request that left the process and did
            // not come back with what it needed is not work by the flag's own
            // definition, and claiming it deleted the failure the drain was
            // carrying — in the one state where the container is meant to be going
            // away, which is where losing the record matters most.
            //
            // Past the grace period the runtime should have killed it in, the
            // *report* changes and nothing else: a failure is recorded, the
            // container is not touched again beyond the re-issue above, and the
            // class stays retryable so the loop keeps trying. `failure-modes.md`
            // item 7. Before that, a container that is still running is exactly
            // what a stop in progress looks like, and there is nothing to report.
            //
            // `enteredStateAt` is the right anchor *here* and only here: this
            // branch does not leave `STOPPING`, so nothing restamps it while the
            // wait goes on. The lap that does leave — back to `SAVING` for a fresh
            // save — is measured by [goingRoundInCircles] instead, for exactly that
            // reason.
            val grace = pass.subject.stopGracePeriod
            val stuckFor = JavaDuration.between(drain.enteredStateAt, now).toKotlinDuration()
            val overdue =
                if (stuckFor > grace) {
                    noteFailure(
                        server = server,
                        previous = drain.failure,
                        occupancy = occupancy,
                        now = now,
                        reason = FailureReason.DRAIN_STALLED,
                        failureClass = FailureClass.RETRYABLE,
                        message =
                            "the container is still running ${stuckFor.inWholeSeconds}s after a stop was " +
                                "issued, past the ${grace.inWholeSeconds}s grace period the runtime should " +
                                "have killed it in. The stop is re-issued on each pass and nothing else is " +
                                "done to it: the world save that authorised the stop is on disk, and a " +
                                "container that will not exit is for the runtime to explain",
                    )
                } else {
                    drain.failure
                }
            return DrainProgress(
                drain = drain.copy(failure = overdue),
                occupancy = occupancy,
                outcome = ReconcileOutcome.Retry("the container is still running after a stop was issued"),
            )
        }
        // Unreachable as things stand: `advance` sends every non-`RUNNING`
        // observation somewhere else before `step` runs — `EXITED`, `CREATED`
        // and a `SANDBOX_ONLY` sandbox with no history are already down,
        // `UNKNOWN` waits, and a `SANDBOX_ONLY` sandbox that had a container
        // aborts. Kept as the honest answer for a container observed stopped
        // here, and worth leaving alone: reporting `containerDown` for anything
        // this branch has not established is how a teardown starts on an
        // `UNKNOWN` container.
        return DrainProgress(
            drain = drain,
            occupancy = occupancy,
            containerDown = true,
            workDone = true,
            outcome = ReconcileOutcome.Progressed("the container has stopped"),
        )
    }

    /**
     * Records that the drain cannot advance, that nothing has gone wrong, and
     * leaves everything alone.
     *
     * The counterpart to [abort], and the difference is the whole point of the
     * type: **no [mcorch.schema.FailureStatus] is recorded.** A drain waiting for
     * players to log off is the protocol working exactly as designed, and writing
     * it down as a failure made every consumer that asks "is anything wrong with
     * this server" answer yes about a server with people happily playing on it.
     * The escalation then needed a named exemption to stay quiet; with no failure
     * to escalate from, `escalated()` is already false and the exemption is gone.
     *
     * What it does **not** change is the requeue. The outcome is
     * [ReconcileOutcome.Retry] exactly as before, so the loop keeps backing off and
     * looking again — that is what makes a block resolve on its own when the last
     * player logs off. A block is parked in [DrainState.DRAIN_FAILED] like an
     * abort, and for the same reason: the state means *not advancing*, the drain
     * re-enters it in place with a rising count rather than cycling through the
     * earlier states, and a dashboard that showed it walking the machine every
     * fourth pass would read as progress that is not happening.
     *
     * ## A **retryable** failure is cleared, and a permanent one is not
     *
     * A drain that failed transiently and is now merely waiting has had its problem
     * resolved — the pass that got here re-established whatever the fault was about,
     * up to and including [holdSeal] on the gated resume — and leaving that failure
     * beside the block would report a fault and its absence.
     *
     * A **permanent** diagnosis is not resolved by somebody logging in, and clearing
     * it was the twenty-eighth audit's third finding. The reachable case is a
     * standalone server under a delete: a save request delivered and never confirmed
     * aborts `DRAIN_SAVE_TIMEOUT`, `permanentFailureStopsPasses` is false under a
     * delete so the passes carry on, the resume finds somebody back on the server,
     * and the block wrote `failure = null`. The status then said *"waiting, not stuck
     * … the drain resumes on its own once it is empty"* about a server whose world
     * may not be on disk and whose delete cannot complete — when it empties, [save]
     * takes the `saveRequestedAt` branch and aborts permanently again. Nothing is
     * stopped either way, so it is a reporting defect; it is the report that decides
     * whether somebody reaches for `crictl stop`. The clear also destroyed
     * `FailureStatus.occurredAt`, so a population that comes and goes reset the
     * escalation anchor and the attention threshold was never reached.
     *
     * The block is still recorded beside it — both facts are true, the drain is
     * waiting *and* something is wrong — and every consumer already ranks them:
     * `StatusDrafting` derives `DRAIN_BLOCKED` from `blocked != null && failure ==
     * null`, so the *"needs nobody"* sentence disappears exactly here, and `:api`
     * renders the failure.
     *
     * ### …and the consumer that reads the class to **stop reconciling**
     *
     * The twenty-ninth audit's first finding, and it is this retention meeting
     * `Reconciler.isBlockedByPermanentFailure`. The status this returns carries the
     * standing failure — `Reconciler.drain` copies `drain.failure` onto it — so the
     * pass that wrote the block used to arm that gate at the current generation,
     * and the *next* pass never ran. On a delete that gate is lifted anyway, which
     * is the case the paragraph above is about and is why it was not noticed; on a
     * **replacement** it fires, and then a definition edit — the documented remedy
     * — is spent on one blocked pass while the step it was meant to repair never
     * runs, for as long as anybody is connected.
     *
     * It is closed at the gate rather than here, by
     * [parkedOnTheFailure]: a drain waiting for the last player to log off is
     * parked on *players*, and that is the one park a later pass can get past with
     * nobody doing anything. Keeping the record here is what the paragraph above
     * argued for and it still holds — the anchor, the class and the wedge below all
     * survive — and the sentence this function writes into the block, *"the drain
     * hits it again as soon as the server empties"*, is only true because the gate
     * lets that pass happen.
     *
     * What this does **not** close: a flapping control endpoint alternates a
     * retryable abort with a block, and each block clears the anchor, so a fault that
     * is present half the time never escalates. Closing it needs a carrier that
     * survives the recovery — the same undecidable question `since` faces above —
     * and a wrong-way narrowing here would leave a healthy wait reporting a fault
     * that has genuinely gone. A set-once instant is **not** that carrier: it cannot
     * tell one fault four hours ago followed by a healthy evening from a fault
     * present every other pass for four hours, and reading the first as the second
     * is the alarm fatigue `escalated()`'s anchor was moved onto
     * `FailureStatus.occurredAt` to avoid.
     */
    private suspend fun blocked(
        subject: DrainSubject,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
        reason: DrainBlockReason,
        message: String,
    ): DrainProgress {
        val server = subject.server
        val restored = restoreRegistration(subject, drain)
        val standing = drain.failure?.takeIf { it.failureClass == FailureClass.PERMANENT }
        // Carried into the block's own message the way `worldSaved` is, because the
        // block message is what a dashboard renders for a waiting drain and "this
        // resumes on its own once it is empty" is false while this stands.
        val wedge =
            standing?.let {
                ". A permanent failure (${it.reason}) recorded at ${it.occurredAt} is still standing, and the " +
                    "drain hits it again as soon as the server empties, so waiting alone does not finish this: " +
                    "${it.message}"
            }
        // The same sentence an abort composes, from the same function, because a
        // block is a park too — see the note above. The three call sites used to
        // state joinability for themselves, and the one in [requireEmpty] said *"the
        // server keeps running and stays joinable"*, which is exactly false of a
        // workload that seals itself: since the twenty-seventh audit the gated
        // [resume] asserts [holdSeal] before the gate, so the pass that records this
        // block is often the pass that shut the front door.
        //
        // **A blackout leads.** `:api` renders this as "waiting, not stuck — " plus
        // this string, and [message] opens with the wait and the reason for it —
        // roughly 250 characters before the login path was reached. A truncated
        // fleet table therefore showed only the half that agrees with
        // `DRAIN_BLOCKED`'s *needs nobody*, about a fleet nobody can log in to. The
        // other two answers stay where they were: neither describes anything an
        // operator has to act on, and leading with "the server keeps taking players"
        // would bury the wait instead.
        val path = loginPathAfterAPark(subject, drain)
        val body =
            when (path) {
                // Sentence-cased on the way, because every [message] here is
                // written to follow `:api`'s "waiting, not stuck — " and so opens
                // lower case. Second in a sentence it reads as a typo, and a status
                // line that looks broken is one an operator trusts less.
                LoginPath.ShutByThisDrain -> "${path.sentence}. ${message.replaceFirstChar { it.uppercase() }}"

                LoginPath.Restored, LoginPath.Open -> "$message. ${path.sentence}"
            }
        val block = recordBlock(reason, "$body${wedge.orEmpty()}", now, drain.blocked)
        if (standing == null) {
            // Info, not warn. Nothing is wrong, and a warning every backoff interval
            // for a whole play session is the log-level version of the alert this
            // change exists to stop firing.
            LOG.info(
                "drain for server={} is blocked and healthy: reason={} playersOnline={} since={} observations={}",
                server,
                reason,
                occupancy?.online,
                block.since,
                block.observations,
            )
        } else {
            LOG.warn(
                "drain for server={} is blocked with a permanent failure standing: reason={} failure={} " +
                    "playersOnline={} since={} observations={}",
                server,
                reason,
                standing.reason,
                occupancy?.online,
                block.since,
                block.observations,
            )
        }
        return DrainProgress(
            drain =
                restored
                    .moveTo(DrainState.DRAIN_FAILED, now)
                    .copy(blocked = block, failure = standing),
            occupancy = occupancy,
            outcome = ReconcileOutcome.Retry(message),
        )
    }

    /**
     * The compensating edge out of `DEREGISTERED`, taken whenever a drain parks
     * after having let go of the backend.
     *
     * Deregistration is the one proxy step that cannot be level-triggered — it is
     * the last thing before the stop, so re-asserting it every pass would mean
     * asserting it from states that must not reach it — so an abort or a block out
     * of `DEREGISTERED` has to put the registration back explicitly. Without this,
     * a drain that deregistered and then failed its stop leaves the backend running
     * and unreachable through the proxy, with nothing left that would re-add it.
     *
     * The seal is *not* re-asserted on the way back. `PUT /v1/backends/{name}`
     * registers and admits in one call, which is exactly what a parked drain
     * should leave behind: reachable again, because the drain is not going to move
     * those players.
     *
     * Best-effort, and it clears [DrainStatus.deregisteredAt] only when the proxy
     * confirmed. A failure here leaves the field set, so the next pass through this
     * function tries again.
     */
    private suspend fun restoreRegistration(
        subject: DrainSubject,
        drain: DrainStatus,
    ): DrainStatus {
        val router = subject.router ?: return drain
        if (drain.deregisteredAt == null) return drain
        return when (val outcome = router.reregister()) {
            is SealOutcome.Asserted -> {
                LOG.info(
                    "re-registered server={} with proxy={}: its drain has parked, so it takes players again",
                    subject.server,
                    router.proxy,
                )
                drain.copy(deregisteredAt = null)
            }

            is SealOutcome.Refused -> {
                LOG.warn(
                    "proxy={} refused to re-register server={} after its drain parked: {}. The server is " +
                        "running and unreachable through the proxy until this succeeds",
                    router.proxy,
                    subject.server,
                    outcome.detail,
                )
                drain
            }

            is SealOutcome.Unavailable -> {
                LOG.warn(
                    "proxy={} could not be reached to re-register server={} after its drain parked: {}. The " +
                        "server is running and unreachable through the proxy until this succeeds",
                    router.proxy,
                    subject.server,
                    outcome.detail,
                )
                drain
            }
        }
    }

    /**
     * The compensating edge for **step 2**, taken when a drain parks *permanently*
     * with a seal in place that nothing else will ever lift.
     *
     * ## Why the level trigger is not enough for this subject
     *
     * The seal is asserted rather than issued precisely so that an abort needs no
     * edge: a backend stops being sealed because the *proxy's* own pass re-asserts
     * its admission from `DrainState.sealsBackend()`, which is false in
     * `DRAIN_FAILED`, on every pass — including for a backend whose permanent abort
     * has stopped its own passes. That argument has a counterparty in it, and it
     * names a *third party*. For a subject that is asserting its own admission
     * there is nobody else: the proxy's re-assertion lives in `assertBackends`,
     * which a pass only reaches when it is *not* draining, and a drain whose cause
     * persists takes the draining branch for ever. A permanent abort then freezes
     * the passes altogether, and the front door is left running, ready, joinable to
     * nobody, with the loop no longer looking at it.
     *
     * So the test is **"is there anything else that asserts this workload's
     * admission"**, and the answer is no exactly when the subject has a seal and no
     * [DrainRouter] — the same shape [sealIsPrecondition] keys on, for the
     * neighbouring reason. Do not read it as "the proxy": a future subject that
     * seals itself gets this for free, which is the point of asking the question in
     * the type rather than about the kind.
     *
     * ## Only where no pass will look again, which is what the argument above says
     *
     * The twenty-sixth and twenty-seventh audits' criticals, and they are one
     * sentence read twice. The sentence this edge rests on is *nothing will ever
     * re-assert this workload's admission, because no pass will look at it again* —
     * and what decides that is `Reconciler.isBlockedByPermanentFailure`, in full.
     *
     * - A **retryable** abort is a drain that is still being attempted: the loop
     *   comes back after a backoff, so the seal is the mechanism of the wait rather
     *   than a leftover of a dead one.
     * - A **permanent** abort under an outstanding delete is the same thing wearing
     *   the other class. `isBlockedByPermanentFailure` exempts a terminating
     *   definition — a delete a failure can freeze is a workload nobody can retire
     *   — so those passes carry on too.
     *
     * Either release is unrecoverable for the same reason: for a subject with no
     * router the `DRAIN_FAILED` resume runs [holdSeal] and then [requireEmpty],
     * and while the door is open the population refills, so [requireEmpty] blocks
     * for ever and the wait is for a zero that can no longer arrive. A delete or a
     * replacement parked like that never completes, which is the state that ends in
     * a manual `crictl stop`. So the gate is [abort]'s
     * `permanentFailureStopsPasses` argument — the reconciler's own answer, handed
     * down — rather than the failure class, which is one of its inputs.
     *
     * ## Why [blocked] deliberately does not do this either
     *
     * A block is the protocol working: the drain has sealed the login path and is
     * waiting for the last player to log off, and **the seal is the mechanism of
     * that wait**. Releasing it there would refill the population the drain is
     * waiting to drain — the same sentence as above, arrived at from the other
     * side. A block also keeps requeueing, so nothing is abandoned.
     *
     * ## No `deregisteredAt` guard, unlike [holdSeal], and why that is right
     *
     * [holdSeal] skips itself after step 6 because `PUT /v1/backends/{name}`
     * registers *and* admits, so asserting a seal there would put a backend back in
     * the routing table moments before its container stops. This edge cannot reach
     * that state at all: `deregisteredAt` is *stamped* at exactly two sites
     * ([letGoAndStop] and [releaseRegistration]) — [restoreRegistration] is the
     * only other writer and it clears — and both stamps are downstream of a
     * [DrainRouter] call, while a subject with a router returns above. So no subject that
     * can reach [DrainSeal.assertAdmission] from here carries a stamp — an
     * unreachability argument rather than a judgement about whether re-registering
     * would be safe. Its premise is `Reconciler.drain` passing one link object as
     * both counterparties, which `DrainWiringTest` pins.
     *
     * ## Not best-effort, because nothing else retries it
     *
     * The twenty-eighth audit's second finding. This used to log its failure and
     * discard it, on the same "best effort" licence [restoreRegistration] takes — and
     * that licence is not transferable: a parked backend is re-registered by
     * `assertBackends` on *every* proxy pass, so a refused re-registration repairs
     * itself. **The seal has no such third party**, which is the whole argument for
     * this edge existing. So a single timed-out control call left a fleet's front
     * door shut with the loop no longer looking at the proxy, and a definition edit
     * did not repair it either: the generation bump resumes the passes straight into
     * [holdSeal], which shuts the door again. A frozen proxy also stops running
     * `assertBackends`, so a backend whose own drain has parked stays out of routing
     * with nothing left to re-register it.
     *
     * The answer is to make the *class* depend on whether the compensation landed:
     * [abort] records `RETRYABLE` when this returns true, because a permanence whose
     * own compensation is unrecoverable is not a permanence anyone can act on. The
     * loop then keeps coming back, and the next pass that reaches [abort] with the
     * endpoint answering releases the seal — the abort then settles as `PERMANENT`,
     * freezing the server with its door open, which is the intended end state.
     *
     * **The pass after a stuck release is not usually that pass**, and the sentence
     * an operator reads had to be corrected for it (see [SEAL_STUCK_SHUT]). This has
     * one caller, so the retry rides on a park; the pass in between runs [resume] and
     * parks in [blocked] whenever anybody is still connected, and [blocked] releases
     * nothing — deliberately, for the reason two sections down. It converges anyway,
     * because a shut door is what makes the population fall, but "the next pass
     * reopens it" would be a promise the machine does not keep.
     *
     * @return whether the login path is **left shut** by this drain: true only when a
     *   release was needed and did not land. A subject with nothing to release, or
     *   one whose seal somebody else re-asserts, returns false.
     *
     * One residual, accepted: a player may connect between this and the pass that
     * stops, and [requireEmpty] re-reading on that pass is the guarantee — the same
     * exposure a standalone server has had since [sealIsPrecondition].
     *
     * The paragraph that used to stand here claimed a second residual — that a
     * permanent abort out of `STOPPING` releases the seal of a container that has
     * already been told to go away, so *"no pass will look at it again"*. That was
     * false on the clause this section is now about: under a delete the passes
     * carry on, and the workload whose door had just been reopened was one the loop
     * would keep reconciling for ever. It is recorded rather than deleted because
     * the residual was the *justification* for the over-wide gate, and a wrong
     * reason left beside a corrected decision is what the next reader takes for a
     * general licence.
     */
    private suspend fun releaseSeal(subject: DrainSubject): Boolean {
        val seal = subject.seal ?: return false
        if (subject.router != null) return false
        return when (val outcome = seal.assertAdmission(admits = true)) {
            is SealOutcome.Asserted -> {
                if (outcome.admits) {
                    LOG.info(
                        "released the login seal on server={}: its drain has parked, so it admits players again",
                        subject.server,
                    )
                    false
                } else {
                    LOG.warn(
                        "server={} accepted the release of its login seal and still reports new players refused. " +
                            "It is running and nobody can join it",
                        subject.server,
                    )
                    true
                }
            }

            is SealOutcome.Refused -> {
                LOG.warn(
                    "server={} refused the release of its login seal after its drain parked: {}. It is running " +
                        "and nobody can join it until this succeeds",
                    subject.server,
                    outcome.detail,
                )
                true
            }

            is SealOutcome.Unavailable -> {
                LOG.warn(
                    "server={} could not be reached to release its login seal after its drain parked: {}. It is " +
                        "running and nobody can join it until this succeeds",
                    subject.server,
                    outcome.detail,
                )
                true
            }
        }
    }

    /**
     * Records a failure, leaves the container alone, and says how long this has
     * been going on.
     *
     * Once a drain has been failing for [attentionAfter], the *report* changes:
     * the message leads with a marker an operator can grep for and the log line
     * goes to error. Nothing else does. The failure class is untouched, so the
     * loop keeps retrying and a transient fault cannot make a server
     * undeletable; the grace period, the stop and the player count are not
     * consulted here at all. `failure-modes.md` item 7 forbids changing what
     * happens to the *container* at a limit — a drain that has been stuck for
     * twenty minutes still must not be stopped — and this changes only what a
     * human is told, which is the thing the system was missing.
     *
     * @param permanentFailureStopsPasses `Reconciler.permanentFailureStopsPasses`
     *   for this pass: whether the record this abort is about to write will stop
     *   the loop passing over the server. Not a class, not a [DrainCause], and not
     *   the terminating flag — the *answer* the loop's own gate gives, so that the
     *   compensating edges belonging to "nothing will look at this again" cannot be
     *   taken while something will. It replaced a `node` parameter that had been
     *   unread since the compensating edges moved in here.
     * @param failureClass what the step concluded. It is what gets recorded **unless
     *   the compensation this park owes could not be delivered**: a `PERMANENT` abort
     *   that was supposed to give the login path back and could not is recorded
     *   `RETRYABLE`, because freezing the loop there leaves a fleet with no front
     *   door and nothing that could ever reopen it. See [releaseSeal].
     */
    @Suppress("LongParameterList")
    private suspend fun abort(
        subject: DrainSubject,
        permanentFailureStopsPasses: Boolean,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
        reason: FailureReason,
        failureClass: FailureClass,
        message: String,
        sideEffectIssued: Boolean = false,
    ): DrainProgress {
        val server = subject.server
        // The explicit edge out of `DEREGISTERED`. Everything else the proxy holds
        // is level-triggered and lapses on its own once this drain stops asserting
        // it; a deregistration does not, so it is undone here.
        val restored = restoreRegistration(subject, drain)
        // …and "lapses on its own" is a sentence about a *third party* asserting it.
        // For a subject that seals itself there is none, so the seal needs the same
        // treatment as a deregistration — but only where the sentence that justifies
        // it is true, and that sentence is *"no pass will look at this server
        // again"*. It is one predicate, `Reconciler.permanentFailureStopsPasses`,
        // and this is its answer rather than one of its inputs: the class alone was
        // true of a permanent abort under an outstanding **delete**, whose passes
        // carry on, and the release then reopened a fleet's login path that the
        // gated resume could never shut again. A retryable park keeps the seal for
        // the neighbouring reason — the loop is still coming back. See [releaseSeal],
        // which also says why the block path must not do this.
        val heldShut = failureClass == FailureClass.PERMANENT && permanentFailureStopsPasses && releaseSeal(subject)
        // …and a permanence whose own compensation did not land is not a permanence
        // anybody can act on. Recording it as declared would freeze the loop on this
        // server with its login path shut and nothing left that could ever reopen it
        // — not another pass, which the class itself has just stopped, and not a
        // definition edit, whose generation bump resumes straight into [holdSeal] and
        // shuts the door again. Retryable is the honest class: the fault is real, the
        // count and the anchor carry on rising, and the compensation is attempted
        // again on the next pass that parks *here* — not simply on the pass after
        // this one, which runs [resume] and, with anybody connected, [blocked], and
        // [blocked] releases nothing. It settles as `PERMANENT` on the pass where the
        // release finally lands, which is the state this edge is for. See
        // [SEAL_STUCK_SHUT], which is the sentence an operator reads.
        val recorded = if (heldShut) FailureClass.RETRYABLE else failureClass
        val failure =
            noteFailure(
                server = server,
                previous = drain.failure,
                occupancy = occupancy,
                now = now,
                reason = reason,
                failureClass = recorded,
                message = if (heldShut) "$message. $SEAL_STUCK_SHUT" else message,
            )
        val aborted =
            restored
                .moveTo(DrainState.DRAIN_FAILED, now)
                // Any block goes: whatever the drain was waiting for, it has now
                // hit something that went wrong, and a record saying both would
                // report a fault and its absence at the same time. The failure is
                // the louder of the two and is the one that survives.
                .copy(failure = failure, blocked = null)
        // Read off the *recorded* class, not the declared one. The two differ only
        // when the seal is stuck shut, and there the requeue is the point: a
        // `Failed` outcome beside a retryable record would tell the loop to stop
        // looking at the workload whose door this abort could not reopen.
        val outcome =
            if (recorded == FailureClass.RETRYABLE) {
                ReconcileOutcome.Retry(failure.message)
            } else {
                ReconcileOutcome.Failed(failure.message)
            }
        return DrainProgress(
            drain = aborted,
            occupancy = occupancy,
            sideEffectIssued = sideEffectIssued,
            outcome = outcome,
        )
    }

    /**
     * Builds the [mcorch.schema.FailureStatus] a drain records, decides whether it
     * is time to call a human, and says so in the log.
     *
     * Split out of [abort] because a failure is not always a park. A container that
     * will not exit is reported from `STOPPING` while the drain stays there
     * re-issuing the stop — see [awaitStopped] — and [abort] is the *park*: it
     * moves the drain to `DRAIN_FAILED` and runs the compensating edges that go
     * with giving up on this pass, putting the backend back in the routing table
     * and — where no pass will look again — releasing the seal, whose outcome then
     * decides the class the failure is recorded under. None of that belongs to a
     * drain that has issued a stop, intends to issue it again next pass, and is
     * only telling somebody it is late.
     *
     * The earlier version of this paragraph said routing it through [abort] "would
     * unseal a server whose container has already been told to go away", and there
     * is a live counter-example three lines above it: when the *re-issue* itself
     * throws, [awaitStopped] does abort, with the first stop's grace period still
     * running. That is right there — a stop the node refused is a drain that could
     * not finish, and leaving it in `STOPPING` with no re-registration is the state
     * `restoreRegistration` exists for — and since the twenty-sixth audit the seal
     * half of it is permanent-only, which is the residual [releaseSeal] names. The
     * distinction that actually holds is park versus report, not what a park does.
     *
     * What the two share is the part that must not be written twice: the escalation
     * threshold, the two prose arms that go with it, and the attempt count carried
     * forward from [previous].
     */
    @Suppress("LongParameterList")
    private fun noteFailure(
        server: ResourceName,
        previous: FailureStatus?,
        occupancy: PlayerOccupancy?,
        now: Instant,
        reason: FailureReason,
        failureClass: FailureClass,
        message: String,
    ): FailureStatus {
        // The first pass that recorded *this* failure, not the first pass of the
        // drain. Asked before `recordFailure` builds the failure, because the
        // escalation decides the wording of the message that call is given — see
        // [firstOccurrenceOf] for why the rule lives in one place rather than
        // being restated here.
        val failingSince = previous.firstOccurrenceOf(reason, now)
        val failingFor = JavaDuration.between(failingSince, now).toKotlinDuration()
        val needsAttention =
            escalates(
                failingSince = failingSince,
                failureClass = failureClass,
                now = now,
                after = attentionAfter,
            )
        // The two escalations are not the same news, and one prose for both
        // would be wrong for whichever it was not written for. A retryable drain
        // is still being attempted; a permanent one is not, and telling an
        // operator "the loop keeps trying" about a drain that has stopped is how
        // they come to believe no action is needed.
        val permanent = failureClass == FailureClass.PERMANENT
        // Only a probe that answered this pass establishes that the server is
        // reachable by a player, and [occupancy] is non-null exactly then. The
        // distinction is not pedantry: the abort for an *unanswered* probe is
        // reached precisely because nothing could be confirmed about who is on
        // the server, and claiming joinability there would be the failure's own
        // subject matter contradicted in its own message — indefinitely, since a
        // permanent failure freezes the status.
        //
        // It stays "still running", because it is: the container is up and this
        // says nothing about stopping it. Weakening it further would start
        // reading as permission to stop the thing by hand, which is the pressure
        // this whole posture exists to avoid.
        val answering =
            if (occupancy != null) {
                "The server is still running and still joinable"
            } else {
                "The container is still running; the loop could not confirm whether it is answering players"
            }
        val reported =
            when {
                needsAttention && permanent -> {
                    "this drain has stopped and cannot finish on its own. $answering, and nothing further will " +
                        "be attempted until a human resolves this. $message"
                }

                needsAttention -> {
                    // "failing for", not "unable to finish for". The number is the
                    // age of this *failure*, and a drain that spent four hours
                    // legitimately waiting for players to log off has not been
                    // unable to finish for four hours — it was doing the right
                    // thing, and only the last few minutes are news.
                    "this drain has been failing for ${failingFor.inWholeMinutes} minutes and is not going to " +
                        "fix itself. The server keeps running and the loop keeps trying. $message"
                }

                else -> {
                    message
                }
            }
        val failure = recordFailure(reason, failureClass, reported, now, previous)
        if (needsAttention && permanent) {
            logPermanentEscalation(
                server = server,
                attempts = failure.attempts,
                answeringPlayers = occupancy != null,
                detail = message,
            )
        } else if (needsAttention) {
            logRetryableEscalation(
                server = server,
                failingFor = failingFor,
                attempts = failure.attempts,
                detail = message,
            )
        }
        return failure
    }

    /**
     * The escalation's only channel outside the dashboard, and the arguments are
     * **named and typed** rather than positional.
     *
     * This line printed nonsense until it was fixed — the placeholders read
     * (server, attempts, answeringPlayers, message) and the arguments were passed
     * (server, answeringPlayers, attempts, message), so an operator grepping for
     * the permanent case read *"stopped permanently after true attempt(s) … (
     * answeringPlayers=1)"*. Nothing branches on it, which is exactly why it
     * survived review: the retryable branch beside it was correct and the two
     * looked alike.
     *
     * Both parameter lists are deliberately shaped so that the same mistake does
     * not compile — every parameter differs in type from its neighbours. At the
     * `LOG.error` varargs boundary everything is `Any?`, so the compiler has
     * nothing to say about order; here it does. That is worth more than a test,
     * because a test only covers the sites somebody remembered to write one for.
     */
    private fun logPermanentEscalation(
        server: ResourceName,
        attempts: Int,
        answeringPlayers: Boolean,
        detail: String,
    ) {
        LOG.error(
            "server={} has a drain that stopped permanently after {} attempt(s); the container is still " +
                "running (answeringPlayers={}) and the loop will not try again — this needs a human: {}",
            server,
            attempts,
            answeringPlayers,
            detail,
        )
    }

    /** The other half. See [logPermanentEscalation] for why the parameters are typed. */
    private fun logRetryableEscalation(
        server: ResourceName,
        failingFor: Duration,
        attempts: Int,
        detail: String,
    ) {
        LOG.error(
            "server={} has had a drain failing for {} minutes ({} attempts); it keeps running and the loop " +
                "keeps trying, but this needs a human: {}",
            server,
            failingFor.inWholeMinutes,
            attempts,
            detail,
        )
    }

    private fun started(now: Instant): DrainStatus =
        DrainStatus(
            state = DrainState.DRAIN_REQUESTED,
            startedAt = now,
            enteredStateAt = now,
        )

    private companion object {
        private val LOG = LoggerFactory.getLogger(DrainController::class.java)
        private val POLL = 2.seconds
        private const val UNKNOWN_STATE =
            "the runtime did not report a usable container state; not acting on it"
        private const val NO_PROXY_SEAL =
            "no proxy to seal: a standalone server accepts joins until it stops"
        private const val SEALED_AT_PROXY =
            "new joins stopped at the proxy; the players already connected stay connected"
        private const val WAIVED_PROXY_SEAL =
            "new joins could not be stopped and the workload is empty, so the drain continues without a seal; " +
                "the zero-player gate before the stop is what decides"

        /**
         * What an operator is told when a park could not give the login path back.
         *
         * It names the class change, because that is the surprising half: a failure
         * this drain declared permanent is recorded as retryable, and somebody
         * reading "the loop keeps trying" needs to know it is trying the release as
         * well as the step that failed. See [releaseSeal].
         *
         * **It used to say the loop "releases the seal on the first pass that reaches
         * the endpoint", and that is not the machine** — the twenty-ninth audit's
         * second finding. [releaseSeal] has one caller, [abort], so the release is
         * attempted on the next pass that *parks again*; the pass after this one runs
         * [resume], which asserts [holdSeal] and then [requireEmpty], and with anybody
         * connected it lands in [blocked], which releases nothing. The state does
         * converge — nobody new can join, so the population falls and a pass reaches
         * the step that aborts again — but a sentence promising the next pass will
         * reopen the door is read as *wait, it is about to fix itself*, which is how
         * a blackout lasts an evening. What it says now is what happens, and the
         * waiting passes report themselves through [loginPathAfterAPark].
         */
        private const val SEAL_STUCK_SHUT =
            "The login seal this drain put on could not be released either, so the server is running and " +
                "nobody can join it. That is recorded as retryable rather than permanent on purpose: the " +
                "loop keeps coming back, and it tries the release again on the next pass that parks here. " +
                "A pass that finds players still connected waits for them instead and says so; nobody new " +
                "can join while the seal is on, so that wait is what ends"

        /**
         * Added to `spec.lifecycle.drain.playerTransferTimeout` per player.
         *
         * A fixed transfer allowance always fails on a full server
         * (`drain-protocol/references/state-machine.md`), so the declared timeout is
         * the floor and this is the slope. Together they are the **only** bound on
         * step 4 — the retry limit the orchestrator was said to own is this, in the
         * unit an operator can reason about.
         */
        private val PER_PLAYER_TRANSFER_ALLOWANCE = 2.seconds

        /**
         * How long a drain may keep failing before it is reported as needing a
         * human. Long enough that an ordinary blocked drain — players online,
         * a node restarting — never trips it.
         */
        private val DEFAULT_ATTENTION_AFTER = 15.minutes

        /**
         * The default evidence gap. Comfortably longer than the interval
         * between passes of a drain that is getting anywhere, and far shorter
         * than a session anybody would notice losing.
         */
        private val DEFAULT_EVIDENCE_GAP = 30.seconds
    }
}

/**
 * Whether a drain failure has gone on long enough to need a human, and is the
 * kind of failure a human could do anything about.
 *
 * **The one implementation of the escalation rule.** It is asked from two
 * places — [DrainController.abort], which has the failure it is about to record
 * but has not built it yet, and [deriveConditions], which has a drain carrying
 * one — and a rule maintained in two branches is a rule that eventually
 * disagrees with itself. The overload below unpacks a recorded failure into
 * this; nothing else decides.
 *
 * ## There is no exempt reason, and that is a deletion rather than an omission
 *
 * This used to take a [mcorch.schema.FailureReason] and return false for one of
 * them — the drain waiting for players to log off, which is the protocol working
 * and must not raise an alert on a busy evening. The exemption is gone because
 * the thing it exempted is gone: that drain records
 * [mcorch.schema.DrainStatus.blocked] and **no failure at all**, so the overload
 * below never reaches this, and the correct behaviour falls out of "a drain with
 * no recorded failure is not escalated" rather than out of a list.
 *
 * Worth more than the parameter it saves. The exemption produced two separate
 * audit findings on its own: it had to be checked *before* the class so that a
 * future permanent classification of that reason could not route a healthy drain
 * back in, and its premise — that the reason is always retryable — was a
 * convention nothing enforced until [mcorch.schema.FailureStatus] was made to
 * refuse the pair. A rule with no exceptions has neither problem.
 *
 * What did *not* move is `DRAIN_NO_DESTINATION`, which now means only that the
 * search for a destination ran and the fleet had no capacity. That escalates like
 * any other retryable drain failure, and it should: it sits blocked until an
 * operator adds capacity, which is precisely what the flag is for.
 *
 * ## Why a permanent failure escalates, and escalates at once
 *
 * This used to require [FailureClass.RETRYABLE], on the reasoning that a
 * permanent failure is already surfaced as permanent so the flag said nothing
 * new. That was exactly backwards, and it left the states that most need a
 * person as the only ones never flagged: an unconfirmable save, a
 * `DRAIN_SAVE_TIMEOUT` that by design is never re-sent, a workload whose
 * contract says it cannot be drained at all. Every one of those has "a human
 * resolves this" as its documented remedy. The flag does not mean "something is
 * wrong" — the failure already says that — it means **the loop has stopped and
 * only a person can move this**, which is the definition of a permanent abort.
 *
 * It fires on the pass that records the failure rather than after [after],
 * because for a permanent failure the timer cannot work at all. The delay exists
 * to let a retrying drain resolve itself first; a permanent one provably will
 * not. Worse, `Reconciler.Pass.isBlockedByPermanentFailure` returns before a
 * non-terminating server is observed, writing no status — so a replacement drain
 * that aborted permanently is frozen at the observation that recorded it, the
 * threshold is never re-evaluated, and a time-based flag would never appear.
 * Waiting would not delay the signal; it would delete it.
 *
 * What that costs while it is unflagged is not cosmetic: a permanently failed
 * drain leaves the container **running**, and `:api` ranks `TERMINATING` above
 * everything for `display.state`, so a fleet table shows it as on its way out
 * with nothing to do. That is the one wrong answer that matters, and this flag
 * is what stops `display.state` having to lie.
 *
 * Deliberately "running" and not "running and joinable". Whether it still
 * answers players is only known on the paths that took a `Joinable` probe this
 * pass; the permanent abort on an unanswered probe establishes the opposite.
 * That over-claim was removed from the operator-facing message and from
 * `StatusDrafting`, and it is not restated here — this is the sentence somebody
 * would otherwise copy back into one.
 *
 * ## [failingSince] is the failure's own first occurrence, on both arms
 *
 * It used to be `DrainStatus.startedAt` on the drain arm and
 * `FailureStatus.occurredAt` on the pass arm, and the two answered different
 * questions. A drain that is *blocked* — players online, which is the protocol
 * working — records no failure and sits in `DRAIN_FAILED` for as long as people
 * are playing, while `startedAt` never resets. So one retryable hiccup after a
 * four-hour block was already past the threshold and escalated on the pass that
 * recorded it, telling an operator the drain had been "unable to finish for 240
 * minutes" about something that had gone wrong a second earlier. That is the
 * alarm-fatigue outcome, reached by the back door.
 *
 * The anchor is therefore the instant this *failure* was first recorded, carried
 * forward by `recordFailure` while the same reason keeps recurring and reset when
 * a different one takes over. One arm, one anchor, one question: **how long has
 * this problem been true.**
 */
internal fun escalates(
    failingSince: Instant,
    failureClass: FailureClass,
    now: Instant,
    after: Duration,
): Boolean =
    when (failureClass) {
        FailureClass.PERMANENT -> true
        FailureClass.RETRYABLE -> JavaDuration.between(failingSince, now).toKotlinDuration() >= after
    }

/**
 * The same rule, asked of a drain that has already recorded its failure.
 *
 * A drain with no recorded failure is not escalated, whatever its age. That is
 * what makes the condition self-clearing — an *ordinary* pass that gets somewhere
 * clears `DrainStatus.failure` (see `DrainController.settleRecords`, and note that
 * the resume itself does not), so the escalation goes with it rather than being a
 * second thing to remember to reset — and it is also
 * what makes a *blocked* drain quiet, since a block records no failure of its own.
 * Both behaviours are this one line, which is why there is no list of exempt
 * reasons above it.
 *
 * A block does keep a **permanent** failure it finds standing, and then this stays
 * true and escalates on the anchor that failure already carried. That is the
 * intended reading rather than an exemption leaking back in: somebody logging in
 * does not resolve a world save that was never confirmed, and the drain hits it
 * again the moment the server empties.
 *
 * ## What clears a *permanent* one, since a retry cannot
 *
 * The same thing, and only that thing: a drain pass that reaches a state it
 * could not reach before. A permanent abort is not retried, so nothing clears it
 * on its own — which is correct, because nothing about the server has changed
 * either. Two things can produce that pass, and they are the two ways a human
 * intervenes:
 *
 * - **The server is deleted.** `isBlockedByPermanentFailure` deliberately lifts
 *   for as long as a delete is outstanding, so the loop keeps observing and can
 *   notice that whatever was wrong has been fixed — an RCON listener that came
 *   back, a world saved and a container stopped by hand.
 * - **The definition is edited.** The generation moves, the gate lifts, and the
 *   drain re-enters `DRAIN_FAILED` and either gets somewhere (cleared) or aborts
 *   again (still flagged, correctly).
 *
 * A flag that expired on its own would be worse than one that persists: it would
 * mean the dashboard stops asking for help while the server is still stuck.
 */
internal fun DrainStatus.escalated(
    now: Instant,
    after: Duration,
): Boolean =
    failure?.let {
        escalates(
            // The **failure's** first occurrence, never [startedAt]. A drain can
            // sit blocked for a whole play session with nothing wrong with it —
            // that is the designed behaviour — and `startedAt` never resets, so
            // anchoring here would mean the first retryable hiccup after a
            // four-hour block escalated on the pass that recorded it, reporting
            // "unable to finish for 240 minutes" about something that had been
            // wrong for one second. That is the alarm-fatigue outcome the
            // threshold exists to prevent, reached by the back door; the pass arm
            // in [deriveConditions] was built to avoid exactly it and the drain
            // arm now matches. `occurredAt` is carried forward by `recordFailure`
            // while the same reason keeps recurring, so it still measures *how
            // long this problem has been true* rather than how long ago the loop
            // last looked.
            failingSince = it.occurredAt,
            failureClass = it.failureClass,
            now = now,
            after = after,
        )
    } ?: false

/**
 * Whether a drain record leaves a `PERMANENT` failure standing with **nothing a
 * later pass could see change** — the middle clause of both
 * `Reconciler.isBlockedByPermanentFailure` implementations, in one expression.
 *
 * Written here rather than twice at the two gates for the reason
 * `Reconciler.permanentFailureStopsPasses` is: two derivations of one rule is how
 * the twenty-seventh audit's critical happened, and a gate that freezes the loop
 * is the worst place to keep a copy.
 *
 * Two things make it false, and they are different findings:
 *
 * - **The drain is not parked.** A permanent failure sitting on a drain in any
 *   other state is one the drain has already moved past, retained on purpose by
 *   [DrainController.settleRecords]' hysteresis so the escalation anchor survives a
 *   resume. Without this clause that retention armed the gate, and the sixteenth
 *   audit's second critical was the result: an operator edits the definition to
 *   repair a permanently failed drain, the generation moves, the gate opens for
 *   exactly one pass, that pass stops the container and moves to `STOPPING` — and
 *   then the retained failure is written at the new generation, the gate closes
 *   again, and `awaitStopped` and `teardown` never run. Container stopped, workload
 *   never removed, replacement never created, status frozen quoting a node fault
 *   the operator had already fixed.
 * - **The drain is parked on *players*, not on the failure.** Since the
 *   twenty-eighth audit a [DrainController.blocked] keeps a standing permanent
 *   failure, and it parks in `DRAIN_FAILED` like an abort — so the pass that wrote
 *   the block armed this gate, and the twenty-ninth audit's first finding was the
 *   result. A non-terminating server reaches a block with a permanent failure
 *   standing exactly once per generation bump: the operator edits the definition to
 *   repair the drain, the gate opens for one pass, somebody is online, the resume
 *   parks in [DrainController.blocked] — and the edit is spent on the block while
 *   the step it was meant to repair never runs. Every further edit goes the same
 *   way for as long as anybody is connected, no status is written meanwhile so
 *   nobody can see the server empty, and for a workload that seals *itself* the
 *   frozen state is a fleet-wide login blackout with the loop no longer looking.
 *
 *   A block is the one park the loop can get past without a human: nobody has to do
 *   anything, the last player logs off, and the drain hits the repaired step. So it
 *   is not what this gate is for, and re-entering costs one probe per backoff and
 *   issues nothing — the same price a delete already pays, for the same reason. It
 *   is also what makes [DrainController.blocked]'s own sentence *"the drain hits it
 *   again as soon as the server empties"* true rather than a promise the gate
 *   cancels.
 *
 * Both clauses narrow the gate, so neither can freeze a workload that would
 * otherwise be reconciled; they can only un-freeze one.
 */
internal fun DrainStatus?.parkedOnTheFailure(): Boolean =
    this == null || (state == DrainState.DRAIN_FAILED && blocked == null)

/**
 * What a park leaves a workload's login path in, as a value the composing site can
 * order rather than a sentence it can only append.
 *
 * See `DrainController.loginPathAfterAPark`, which is the single derivation, for
 * which record distinguishes the three and why. The reason this is a type at all is
 * the thirtieth audit's fourth finding: `:api` renders a block as *"waiting, not
 * stuck — <message>"*, so whichever half of the message comes first is the half a
 * truncated fleet table shows, and a blackout that arrives in the tail reads as a
 * healthy wait. `:api` cannot make that decision — it cannot see whether the
 * workload has a router — so the ordering is made here, where the three cases are
 * already told apart.
 *
 * Each [sentence] has to read correctly **both** as a lead and as a continuation,
 * because a park under a failed step 2 appends it and a block leads with it.
 */
internal sealed interface LoginPath {
    /** Operator-facing, self-contained, and never an address or a player name. */
    val sentence: String

    /** A backend: the proxy's own pass hands its joins back while this drain is parked. */
    data object Restored : LoginPath {
        override val sentence: String =
            "The server keeps running, and the proxy admits players to it again while the drain is parked"
    }

    /**
     * A workload that seals itself, with a seal in place. The blackout, and the only
     * surface that reports it.
     */
    data object ShutByThisDrain : LoginPath {
        override val sentence: String =
            "Nobody can log in: the login seal this drain put on is still in place, and it stays until the " +
                "drain finishes. Reverting a definition edit that asked for this drain lifts it, by putting " +
                "the workload back on a converging pass that re-admits players; a delete cannot be withdrawn " +
                "and clears only by finishing"
    }

    /** A workload that seals itself and never got one, or has nothing that could. */
    data object Open : LoginPath {
        override val sentence: String = "The server keeps running and keeps taking players"
    }
}

/** Why a drain is happening. It changes nothing about the procedure, only the message. */
internal enum class DrainCause(
    val detail: String,
) {
    DELETION("the definition was deleted"),
    REPLACEMENT("the definition changed in a way that needs the container recreated"),
    RELOCATION("the server has been scheduled onto a different node"),
}

/** One step of a drain: the state to record, what was observed, and what to do next. */
internal data class DrainProgress(
    val drain: DrainStatus,
    val occupancy: PlayerOccupancy? = null,
    /** Set on the pass that confirmed a save completed, never on the pass that requested one. */
    val saveConfirmedAt: Instant? = null,
    /**
     * True when this step did something to the server that the runtime cannot
     * be asked about later — in practice, sent a save request.
     *
     * A stop does not count: a stopped container is observable, so a lost record
     * of one costs a pass rather than a repeat, and re-issuing a stop is safe on
     * a drain that has already confirmed a save. A save request is not
     * observable, so the record of one is the only thing standing between a lost
     * write and a second save on a live server. Which field holds that record
     * depends on how the save ended — [mcorch.schema.DrainStatus.saveRequestedAt]
     * when it was delivered but never confirmed, and
     * [mcorch.schema.DrainStatus.worldSavedAt] once the server reported it
     * completed. Both are records of something that has already happened, so
     * neither may be lost, and the milder of the two is still a repeat.
     *
     * The caller therefore has to make the record durable against **both** ways
     * a pass can fail to write it: an observation the store rejects, and a pass
     * cancelled before it reaches the store. Cancellation is the likelier of the
     * two — it is what an ordinary shutdown does — and it is invisible from
     * here, so this flag is what a caller with a store in hand acts on.
     */
    val sideEffectIssued: Boolean = false,
    /**
     * True once the container is provably gone. Teardown — removing the
     * workload, purging the definition — waits for this, so nothing is ever
     * deleted out from under a running container.
     */
    val containerDown: Boolean = false,
    /**
     * This step **did something**, rather than restating what the drain already
     * knew.
     *
     * A positive claim with a default of false, and the direction is the whole
     * point. It was written the other way round — `derivedOnly`, set by the one
     * step that was known to re-derive — and the flag was then correct about that
     * step and silently wrong about every other early return of the same shape.
     * `save` had two: one reading a container label, one comparing two stored
     * timestamps. Both advanced the recorded state, issued nothing, reported
     * `Progressed`, and so cleared the failure that carries the escalation anchor
     * on every other pass of a drain that was getting nowhere — a refused
     * deregistration or a refused stop, alternating for ever with `attempts` pinned
     * and `NEEDS_ATTENTION` unreachable.
     *
     * **Do not enumerate the steps that re-derive.** That list was written once, was
     * complete when written, and was wrong two steps later. A step claims this only
     * when it can point at one of two things:
     *
     * - a request that left this process — a seal, a transfer, a save, a
     *   deregistration, a container stop — and came back with what it needed; or
     * - a fact only this pass's probe could establish, in practice *zero players*,
     *   which is an observation of the server rather than a computation over state
     *   already in hand.
     *
     * Asking the [Scheduler] for a destination is neither: nothing leaves the
     * process and the answer is derived from a fleet view this pass already held.
     * Nor is skipping a save because the container carries no world, or because a
     * confirmation already in the record is still current.
     *
     * The level-triggered seal assertion is deliberately *not* what this tracks in
     * the states that merely re-assert it. It runs on every pass by design, so a
     * flag keyed on it would be true for every pass and mean nothing.
     *
     * Read in two places, which ask different questions of it:
     *
     * - `resumeInto` downgrades a resume that did nothing from `Progressed` to
     *   `Retry`, because `Progressed` is what resets the loop's backoff.
     * - [DrainController.settleRecords] decides whether a pass earned the deletion
     *   of the recorded failure — and additionally requires that the pass was not
     *   itself the resume. See that function for why one good pass is not proof.
     */
    val workDone: Boolean = false,
    val outcome: ReconcileOutcome,
)

/**
 * The rule a recorded pass has to satisfy: **a pass that records players online
 * may not also record a confirmed world save.**
 *
 * ## Why this exists next to [readPlayers] rather than instead of it
 *
 * [readPlayers] enforces the rule where a count is *read*, which is where round 17
 * lost it. Round 18 lost it one step earlier: `holdSeal` reads no count at all, so
 * the type never gets the chance to hand it a voided drain, and at `DEREGISTERED`
 * it runs *before* the zero-player gate. A proxy control endpoint that stopped
 * answering therefore parked a drain still claiming a save taken before somebody
 * connected straight to the backend's own port — and because the loop kept probing
 * that player every pass, the observation that should have destroyed the evidence
 * kept refreshing the window that keeps it alive instead.
 *
 * A read-point rule cannot catch a non-reader. This is the same move applied to
 * the *record*: [DrainProgress] is the only thing that leaves this file and it
 * holds both facts, so every producer of a progress is bound by it — including the
 * ones that never look at a player count.
 *
 * ## The pair is what *this pass* established, not what lands on the status
 *
 * The placement argument used to be "`Reconciler` writes them onto one observed
 * status side by side", and that is not what it does: `players` is written as
 * `progress.occupancy ?: previous.players`. On a pass whose probe did not answer,
 * the count on the status is carried forward from an earlier pass and sits beside
 * *this* pass's drain — a pair this function returns early on, because a null
 * occupancy means this pass observed nobody and has no grounds to take anything
 * away. Which is right: a carried-forward count is not an observation of the world
 * this drain is about to stop, and it is the same distinction [readPlayers] refuses
 * to collapse for an unanswered probe.
 *
 * So the rule is over the two facts a pass **established**, which is exactly the
 * pair [DrainProgress] carries, and that is why it belongs to the progress rather
 * than to the drafted status. The wrong version was harmless today — a confirmation
 * can only be minted under a fresh zero reading, so the bad pair is unreachable —
 * and it is stated correctly here because the argument is what a future reader
 * inherits when that stops being true.
 *
 * ## What it does not do
 *
 * Only the confirmation goes — the narrowest rung of the ladder, for the reason
 * `advance` gives at its pass-entry reading: taking `playersEvacuated` with it
 * would move a parked proxied drain down the resume ladder and send it into a
 * transfer where it currently blocks. Voiding is always safe on its own; changing
 * where a drain re-enters is not.
 *
 * Pure, and the log line lives at the call site, so the rule can be asserted
 * directly in a unit test. A scenario tests the producer somebody thought of.
 */
internal fun DrainProgress.dropSaveContradictedByPlayers(): DrainProgress {
    val online = occupancy?.online ?: return this
    if (online == 0 || drain.worldSavedAt == null) return this
    return copy(drain = drain.unconfirmWorldSave())
}

/**
 * Whether this drain's save confirmation still describes what is on disk.
 *
 * Two ways it stops describing it, and both have to be checked because they fail
 * differently:
 *
 * - **Somebody played since.** Handled by erasing the evidence the moment a
 *   player is observed ([forgetSaveEvidence]), so a confirmation that survives
 *   to here has had no player seen after it.
 * - **The container restarted since.** A drain record outlives a container — a
 *   redeploy, a resumed drain read back from the store after a restart — so a
 *   confirmation from the previous container is still sitting there, and no
 *   probe in between would have contradicted it. A confirmation that predates
 *   the running process is not evidence about the running process.
 *
 * Equality passes: a confirmation stamped in the same instant the container
 * started cannot be evidence about an earlier one, and a frozen clock must not
 * make a correct drain refuse to finish.
 */
internal fun DrainStatus.saveIsCurrent(
    containerStartedAt: Instant?,
    now: Instant,
    maxAge: Duration,
): Boolean {
    val confirmed = worldSavedAt ?: return false
    if (containerStartedAt != null) return !confirmed.isBefore(containerStartedAt)
    // No start time to compare against, so the container-restart half of the
    // rule has nothing to say and only a *fresh* confirmation counts. Reading
    // the unknown as "no restart" would trust a confirmation of any age from a
    // runtime that has told us nothing; reading it as "restarted" would reject
    // the confirmation this pass just took, and the drain would save, fail to
    // stop, save again, for ever. Every runtime this talks to reports a start
    // time for a running container, so this is the answer for one that does
    // not — and the observation-gap rule is what covers the interval either
    // way.
    return JavaDuration.between(confirmed, now).toKotlinDuration().let { !it.isNegative() && it <= maxAge }
}

/**
 * The precondition for every container stop in this file: the world is on disk
 * *for the container that is running now*, or that container never held a world.
 *
 * [contract] is read off the workload rather than the definition on purpose —
 * see [mcorch.core.paper.PaperServerAgent.contractOf].
 */
private fun DrainStatus.mayStop(
    contract: WorkloadContract,
    containerStartedAt: Instant?,
    now: Instant,
    maxAge: Duration,
): Boolean = !contract.holdsWorldData || saveIsCurrent(containerStartedAt, now, maxAge)

/**
 * Drops a save confirmation that nothing observed can vouch for any more.
 *
 * Applied once per pass, before any state acts on the drain, and it is the half
 * of the evidence rule that no probe can supply. A probe reports who is online
 * *now*; the two things below are true of the *interval* since the
 * confirmation, and nothing in a later reading of zero players contradicts
 * either of them:
 *
 * - **The container restarted.** A drain record outlives containers — read back
 *   from the store after the loop restarts, or simply left behind when somebody
 *   restarts the container underneath it — so a confirmation can describe a
 *   process that no longer exists.
 * - **The loop stopped watching.** If the last recorded observation is older
 *   than [maxGap], nobody was looking, and a whole play session fits in the
 *   window. This is the only witness there is that the chain of zero-player
 *   readings behind the confirmation is unbroken: it costs one extra
 *   `save-all flush` whenever the loop is interrupted mid-drain, and it is what
 *   stops a restarted loop stopping a server on a confirmation from before it
 *   went down.
 *
 * Only the confirmation goes. `saveRequestedAt` is not touched and does not need
 * to be: the two are disjoint, so a drain holding a confirmation has no
 * outstanding request to leave behind. That used to be one field, and clearing
 * it here was load-bearing for exactly the reason it no longer is.
 */
internal fun DrainStatus.dropUnusableSaveEvidence(
    containerStartedAt: Instant?,
    lastProbedAt: Instant?,
    now: Instant,
    maxGap: Duration,
): DrainStatus {
    if (worldSavedAt == null) return this
    val watched =
        lastProbedAt != null &&
            !JavaDuration.between(lastProbedAt, now).toKotlinDuration().let { it > maxGap || it.isNegative() }
    return if (saveIsCurrent(containerStartedAt, now, maxGap) && watched) {
        this
    } else {
        unconfirmWorldSave()
    }
}

/**
 * What a probe established about who is on the server, and the drain record as
 * that reading leaves it.
 *
 * ## Why the rule is a return type
 *
 * "A positive player count voids the save confirmation" was a distributed
 * invariant with no enforcement point. Four branches read `probe.online`, and
 * each was separately responsible for calling [forgetSaveEvidence] in the same
 * expression; the argument that they all did was a sentence in [requireEmpty]'s
 * KDoc carrying a maintained count of the call sites. A change then added a
 * reader that voided nothing — the re-probe after a confirmed save — and neither
 * the sentence nor a single test noticed. The drain stopped a container on a save
 * taken before somebody logged in, built for a minute and logged off again: Paper
 * writes player data on quit, so their inventory came back and their blocks did
 * not.
 *
 * A fifth correct call site would have been a fifth thing to get right. This is
 * one function instead, and reading a count means calling it: the [Occupied] case
 * hands back a drain that has **already** had its evidence voided. A caller can
 * still reach past that and use its own — nothing in the language stops it — but
 * it has to do so in one visible expression rather than by not thinking of it.
 *
 * ## It enforces one clause, and the other is deliberately left to the caller
 *
 * [Unanswered] carries no drain, because the sites genuinely disagree about
 * silence and a single answer would be wrong for two of them:
 *
 * - `requireEmpty` and `awaitEvacuated` abort, through
 *   [forgetSaveConfirmation] — the confirmation goes and the record of a
 *   *delivered* request stays.
 * - `awaitStopped` tolerates it and touches nothing: a container inside its stop
 *   grace period is expected to stop answering, and demanding an answer would
 *   wedge the drain exactly when it is working.
 * - `save`'s re-probe keeps the confirmation it has just earned and records no
 *   occupancy, which leaves the evidence unwatched so a later pass saves again
 *   rather than stopping on a reading nobody took.
 *
 * Folding those into one rule here would be the same mistake pointing the other
 * way.
 */
internal sealed interface PlayerReading {
    /** What this pass may record about occupancy, or null when nothing was established. */
    val occupancy: PlayerOccupancy?

    /** The probe answered and nobody is on. [drain] is returned untouched. */
    data class Empty(
        val drain: DrainStatus,
        override val occupancy: PlayerOccupancy,
    ) : PlayerReading

    /**
     * The probe answered and somebody is on.
     *
     * [drain] has already been through [forgetSaveEvidence]. That is the whole
     * point of the type: it is not possible to learn that [online] is positive
     * without also being handed the drain that fact implies.
     */
    data class Occupied(
        val drain: DrainStatus,
        override val occupancy: PlayerOccupancy,
    ) : PlayerReading {
        val online: Int get() = occupancy.online

        val max: Int get() = occupancy.max
    }

    /**
     * The probe could not answer, and it makes no difference whether it ran and
     * got silence or could not be run at all. Neither is a zero-player report,
     * and treating either as one is how a drain stops a server with people on it.
     */
    data class Unanswered(
        val probe: ProbeOutcome.Unanswered,
    ) : PlayerReading {
        override val occupancy: PlayerOccupancy? get() = null
    }
}

/**
 * The only place a player count is read for a decision, and the only constructor
 * of a [PlayerOccupancy] in this file.
 *
 * [at] is when the probe answered, and callers must pass an instant read no
 * earlier than the call that produced [probe]. Handing it a pass-entry instant
 * for a probe taken after a three-minute save is what made the sixteenth audit's
 * livelock: the recorded instant is what the next pass measures its evidence gap
 * from, so a stale one voids evidence that was in fact fresh.
 *
 * Copying a count onto observed status is not reading it — `advance` does that
 * with the [PlayerReading.occupancy] alone, and says at its call site why it
 * declines the drain.
 */
internal fun DrainStatus.readPlayers(
    probe: ProbeOutcome,
    at: Instant,
): PlayerReading =
    when (probe) {
        is ProbeOutcome.Joinable -> {
            val occupancy = PlayerOccupancy(online = probe.online, max = probe.max, observedAt = at)
            if (probe.online == 0) {
                PlayerReading.Empty(this, occupancy)
            } else {
                // The one statement carrying the invariant. Somebody is on the
                // server, so anything this drain had saved is now behind whatever
                // they are doing, and the record says so before any caller sees
                // the count.
                PlayerReading.Occupied(forgetSaveEvidence(), occupancy)
            }
        }

        is ProbeOutcome.Unanswered -> {
            PlayerReading.Unanswered(probe)
        }
    }

/**
 * Whether a drain step 2 that could not be asserted must park the drain.
 *
 * ## The abort's own justification names a step this subject does not have
 *
 * `holdSeal` aborts because "a drain that carried on would be transferring into a
 * queue that refills behind it". That is a sentence about a **transfer**, and a
 * subject with no [DrainRouter] has none: its drain is seal-then-wait-for-zero,
 * and the thing that decides whether the container may stop is `requireEmpty`
 * followed by `mayStop`, exactly as for a standalone `PaperServer` — which has no
 * seal at all and is stopped safely every day on that basis. For those subjects
 * the seal is an **optimisation** (it stops the population climbing back while the
 * drain waits), not a precondition, and this pass has just read zero players off
 * the workload's own Server List Ping.
 *
 * ## The critical it closes
 *
 * A `VelocityProxy` always has a seal object and never a router, so the null-seal
 * short-circuit that saves the standalone server could not save it. A proxy whose
 * plugin is absent or failed to load therefore aborted at step 2 on **every** pass
 * of every state, at zero players, for ever: `DRAIN_REQUESTED` → abort → resume →
 * `requireEmpty` passes on zero → `SEALED` → abort. Delete, replacement and
 * relocation all take that path, and the only repair — recreating the proxy — is
 * itself a replacement drain through the endpoint that does not answer. The exit
 * left to an operator was a manual `crictl stop` of a running, joinable front
 * door, which is the chain this codebase exists to make unnecessary.
 *
 * ## Why the reading is part of the question
 *
 * With anybody online the seal is doing real work — it is what lets the wait end —
 * so a proxy that cannot seal *and* has players still parks, with
 * `PROXY_CONTROL_UNREACHABLE` on its status telling an operator to go and look.
 * The waiver applies only on a fresh [PlayerReading.Empty]; silence is not a
 * zero-player report here any more than it is anywhere else in this file, so
 * [PlayerReading.Unanswered] parks too.
 *
 * The residual risk is exactly the standalone server's: somebody may connect
 * between this reading and the stop. Nothing in this codebase has ever claimed to
 * close that window — `requireEmpty` re-reads on the pass that stops, and that is
 * the guarantee — so the waiver takes on no risk that the accepted shape does not
 * already carry.
 *
 * ## How narrow the waiver is, by construction rather than by survey
 *
 * The paragraphs above argue that waiving is *safe* for a subject with no router.
 * They do not say which subjects those are, and "a `PaperServer` behind a proxy
 * always has both" is the kind of sentence a later edit falsifies quietly. The
 * constructive version, which the twenty-fifth audit verified and asked to have
 * written down:
 *
 * `Reconciler.drain` builds one `ProxyFleet.linkFor` result and passes that **same
 * object** as `seal` and as `router`, so for every `PaperDrainSubject`
 * `seal != null` if and only if `router != null`. A Paper subject therefore either
 * has both counterparties, in which case the waiver's guard is false, or neither,
 * in which case `holdSeal` returned `NothingToSeal` and this was never asked.
 * `ProxyDrainSubject` overrides `router` with a `null` that is a `get()` — not a
 * constructor parameter anything could fill — so it is the only subject this
 * waiver can reach, and it is the subject the waiver was written for.
 *
 * The premise is one expression at one call site, so it is pinned there:
 * `DrainWiringTest` asserts that the two arguments are the same name.
 */
internal fun sealIsPrecondition(
    router: DrainRouter?,
    reading: PlayerReading,
): Boolean = router != null || reading !is PlayerReading.Empty

/**
 * The drain a pass is stepped and recorded against, given what its probe read.
 *
 * The one clause of a [PlayerReading] that a pass entry adopts, and deliberately
 * not the whole reading: [PlayerReading.Occupied] carries a drain that has been
 * through [forgetSaveEvidence], rung 3 of the ladder in [unconfirmWorldSave]'s
 * note, and this takes rung 1. Taking more would move a parked proxied drain down
 * the resume ladder and send it into a transfer where it currently blocks. The
 * argument for the choice is written out at the only call site,
 * `DrainController.advanceOnce`.
 *
 * ## Why the predicate is a function and not a conditional at that call site
 *
 * It is the one clause of round 18's fix that nothing can exercise. The adoption
 * makes the confirmation unreachable to every step in the pass, so narrowing it —
 * `is Occupied && !playersEvacuated`, say, which is a plausible reading of the
 * *Declined* paragraph at the call site — changes no scenario's outcome: the
 * record-level rule in `advance` still repairs what is written down. The
 * twentieth audit demonstrated exactly that by mutation. A rule no input can
 * exercise has to be asserted on the rule itself, which needs the rule to be
 * something a test can call. `SaveEvidenceTest` calls this one.
 *
 * ## What the extraction cost, and where that is paid
 *
 * Inline, the clause could only be applied to the drain its reading came from.
 * Here the receiver and the argument are supplied separately, so
 * `recorded.adoptSaveClause(reading)` is well-typed and would hand the pass back a
 * confirmation [dropUnusableSaveEvidence] has just taken away for want of a
 * witness. Nothing in the type system refuses it; giving every [PlayerReading] a
 * drain of its own would, and that is the collapse [readPlayers] deliberately does
 * not make — silence means different things to its three callers. So the guard is
 * the call site's shape instead: `DrainWiringTest` asserts this is applied to the
 * same name [readPlayers] was called on, and the red-proof mutates it there.
 */
internal fun DrainStatus.adoptSaveClause(reading: PlayerReading): DrainStatus =
    when (reading) {
        is PlayerReading.Occupied -> unconfirmWorldSave()
        is PlayerReading.Empty, is PlayerReading.Unanswered -> this
    }

/** Why the save evidence is not good enough to stop on, for an operator-facing message. */
private fun DrainStatus.saveEvidenceProblem(containerStartedAt: Instant?): String {
    val confirmed = worldSavedAt
    return if (confirmed == null) {
        "no completed world save has been confirmed for the container that has been running since " +
            "$containerStartedAt"
    } else {
        "the world save confirmed at $confirmed predates the container running since $containerStartedAt, so " +
            "it says nothing about what is in memory now"
    }
}

/**
 * Voids everything this drain had established about getting the server empty and
 * on disk, **including the record of a save request that was delivered**.
 *
 * Called only from a pass that has just *observed* somebody on the server, and
 * there is now exactly **one** call site — [readPlayers], which is the only place
 * a count is read for a decision and voids in the same expression it learns the
 * count. This used to be a list of callers with a maintained count, twice: once
 * wrong about the set it claimed, and once left correct-looking by a change that
 * added a reader voiding nothing. A list written down is a list that goes stale;
 * a single caller cannot. If you find yourself adding a second, add it to
 * [readPlayers] instead. That observation is what justifies the last part. Clearing `saveRequestedAt`
 * lifts the wedge that stops a delivered-but-unconfirmed save from ever being
 * re-sent — deliberately, because a player has been on the server since, which
 * makes the old request worth nothing, and because the fresh save that follows
 * is the only thing that can let the drain finish. A pass *can* reach here
 * holding such a record, since an outstanding delete lifts the permanent-failure
 * gate.
 *
 * **A pass that observed nothing must not use this.** It has no player to point
 * at, so it has no grounds to lift the wedge, and doing so silently sends a
 * second `save-all flush` to a live server. Use [forgetSaveConfirmation].
 *
 * It is also the one thing that resets
 * [mcorch.schema.DrainStatus.resaveForcedAt], and for the same reason it clears
 * everything else: a player has been on the server, so the save that follows is
 * this drain doing its job rather than this drain going round in a circle. The
 * sibling below deliberately leaves the anchor alone — a probe that did not
 * answer establishes nobody, and a drain that lost its evidence to a blind
 * window is exactly the case being counted.
 */
internal fun DrainStatus.forgetSaveEvidence(): DrainStatus =
    forgetSaveConfirmation().copy(saveRequestedAt = null, resaveForcedAt = null)

/**
 * The narrowest of the three voiders: this drain no longer claims a **confirmed
 * world save**, and nothing else about it changes.
 *
 * The three are a ladder, and each rung is a strictly wider claim about what the
 * pass established:
 *
 * 1. this — *the confirmation does not describe the world any more*. Used where a
 *    pass has to stop a drain jumping to the stop without changing where a parked
 *    one re-enters: `advance`'s pass-entry reading, and
 *    [dropUnusableSaveEvidence].
 * 2. [forgetSaveConfirmation] — that, **and** this drain can no longer claim it saw
 *    the server empty. For a pass that could not vouch for anything it had.
 * 3. [forgetSaveEvidence] — that, **and** the record of a delivered save request and
 *    the re-save anchor. Only for a pass that *observed a player*, because only
 *    that makes an outstanding request worthless.
 *
 * `worldSavedAt = null` is written here and nowhere else, on purpose. It was
 * written in four places, each of which was separately right about which of the
 * other fields to take with it, and the round-18 defect is what a fifth reader
 * needing a *fourth* combination looks like when the combinations are open-coded:
 * the one it needed did not exist, so the site took none of them.
 */
internal fun DrainStatus.unconfirmWorldSave(): DrainStatus = copy(worldSavedAt = null)

/**
 * Voids what this drain had established, and keeps the record of a request whose
 * completion was never confirmed.
 *
 * For a pass that cannot vouch for the world any more but has observed nothing
 * that would make a delivered save request worthless — a probe that did not
 * answer, or a runtime that has stopped reporting a container.
 *
 * The difference from [forgetSaveEvidence] is one field: `saveRequestedAt`
 * survives here. That is the wedge keeping a second `save-all flush` off a live
 * server (CLAUDE.md invariant 5), and only *observing a player* may lift it,
 * because only that makes the old request worthless. A pass that saw nothing has
 * no grounds.
 *
 * Both are unconditional now. They used to have to ask `worldSaved` which fact
 * `saveRequestedAt` was carrying before they could decide what to clear, and
 * each of them got that question wrong once: this one wedged a healthy drain by
 * keeping a confirmation's timestamp, and the other lifted the wedge on a
 * delivered save. Neither can ask any more, because there is nothing to ask.
 */
internal fun DrainStatus.forgetSaveConfirmation(): DrainStatus = unconfirmWorldSave().copy(playersEvacuated = false)

/** Moves to a new state, stamping the transition. Re-entering the same state does not restamp. */
private fun DrainStatus.moveTo(
    state: DrainState,
    now: Instant,
): DrainStatus = if (this.state == state) this else copy(state = state, enteredStateAt = now)

/**
 * The runtime's own word that nothing is running: an absent workload, a reaped
 * process, or a container that was never started. Returns null when the answer
 * is "still running" or "not known" — an unreachable node is not a stopped
 * container.
 */
private fun WorkloadObservation.containerIsDown(hadContainer: Boolean): String? =
    when (this) {
        WorkloadObservation.Absent -> {
            "gone"
        }

        is WorkloadObservation.Present -> {
            when (state) {
                WorkloadState.EXITED -> "exited"

                // Only for a workload that has never had a container. The same
                // observation on one that *has* had a container is the runtime
                // failing to report it, and the two are indistinguishable from
                // the sandbox alone — so the drain's own record of having seen
                // one is what separates them. Getting this wrong tears down a
                // sandbox with a live server inside it, killed with no grace
                // period and no save.
                WorkloadState.SANDBOX_ONLY -> if (hadContainer) null else "never created"

                WorkloadState.CREATED -> "never started"

                WorkloadState.RUNNING, WorkloadState.UNKNOWN -> null
            }
        }
    }

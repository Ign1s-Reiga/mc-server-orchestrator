package mcorch.core

import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.SaveOutcome
import mcorch.core.paper.WorkloadContract
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
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
 * - **There is no unconditional stop in this class.** The only call to
 *   [Node.stopWorkload] is guarded by a freshly observed zero-player count and,
 *   for a server with world data, a *confirmed* save.
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
 * What replaces the old argument is narrower, not looser, and it is the claim that
 * actually matters:
 *
 * **No path reaches [Node.stopWorkload] except through [requireEmpty] followed by
 * `mayStop`.** The gate guards `SAVING`, `DEREGISTERED`, `STOPPING` and the
 * `DRAIN_FAILED` resume — the states that flush the world, let go of the backend
 * and take the container away — and those are the only states whose mistake loses
 * data. Steps 2, 3 and 4 have no [Node.stopWorkload] call and no edge to
 * `STOPPING` that does not pass through `SAVING`, so they cannot stop anything
 * however wrong they are; and [stop] re-asserts `mayStop` itself as a backstop for
 * a future edit that routes around the state machine.
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
     * @param current the drain recorded last pass, or null to start one.
     * @param lastProbedAt when a probe last answered for this server, or null if
     *   none ever has. The evidence chain is measured against it.
     * @param hadContainer whether a container has ever been observed for this
     *   server. A sandbox that reports no containers means something different
     *   depending on the answer.
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
                node = node,
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

        // The only place occupancy is ever built, and every abort below depends
        // on that being true: a non-null `occupancy` means an SLP answered *this
        // pass*, so a message or a decision may say what is online, and a null
        // one means nothing was established and nothing may be claimed. It is
        // threaded read-only through `DrainPass` and never reconstructed.
        //
        // Stated here rather than as a list of the call sites that hold it. That
        // list was written once and was already wrong — `awaitStopped`'s abort
        // reaches `step()` directly rather than through `requireEmpty` — while
        // this single construction site cannot drift.
        val occupancy = (probe as? ProbeOutcome.Joinable)?.let { PlayerOccupancy(it.online, it.max, now) }

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
                now = now,
            )
        return step(pass, drain)
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
                // below that depends on it — see [holdSeal]. `sealRequestedAt`
                // records when this drain *first* got the seal in place, for a
                // dashboard; nothing gates on it, and nothing may, because a
                // gate would be the event-shaped seal wearing a timestamp.
                holdSeal(pass, drain)?.let { return it }
                val sealed = pass.subject.seal != null
                DrainProgress(
                    drain =
                        drain
                            .moveTo(DrainState.SEALED, now)
                            .copy(sealRequestedAt = if (sealed) drain.sealRequestedAt ?: now else null),
                    occupancy = occupancy,
                    outcome = ReconcileOutcome.Progressed(if (sealed) SEALED_AT_PROXY else NO_PROXY_SEAL),
                )
            }

            // Step 3: secure a destination.
            DrainState.SEALED -> {
                holdSeal(pass, drain)?.let { return it }
                secureDestination(pass, drain)
            }

            // Step 4: move the players.
            DrainState.TARGET_RESOLVED -> {
                holdSeal(pass, drain)?.let { return it }
                startTransfer(pass, drain)
            }

            DrainState.TRANSFERRING -> {
                holdSeal(pass, drain)?.let { return it }
                awaitEvacuated(pass, drain)
            }

            // Step 5: save the world and wait for completion.
            DrainState.SAVING -> {
                holdSeal(pass, drain)?.let { return it }
                requireEmpty(pass, drain) {
                    save(pass, drain)
                }
            }

            // Step 6: deregister the backend, then step 7.
            DrainState.DEREGISTERED -> {
                holdSeal(pass, drain)?.let { return it }
                requireEmpty(pass, drain) {
                    if (drain.mayStop(contract, observation.startedAt, now, evidenceGap)) {
                        letGoAndStop(pass, drain)
                    } else {
                        // The evidence that got this drain here is gone — a
                        // player was seen since, or the container restarted —
                        // so it goes back and gets it again. Going back is the
                        // whole point: the alternative is a stop on a
                        // confirmation that has been outlived, and a drain
                        // that gives up here would leave a server nobody can
                        // retire.
                        DrainProgress(
                            drain = drain.moveTo(DrainState.SAVING, now),
                            occupancy = occupancy,
                            outcome =
                                ReconcileOutcome.Progressed(
                                    "the world has to be saved again before this server can stop: " +
                                        drain.saveEvidenceProblem(observation.startedAt),
                                ),
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
                // No seal is asserted here, and that is what restores joins. A
                // drain that has stopped advancing is a drain that is not going to
                // move those players, so holding the backend out of routing buys
                // nothing and costs a running server no player can reach — for
                // ever, if the abort was permanent, because then this pass never
                // happens again either. The proxy's own reconcile sweep is what
                // makes the restoration land in that case; this branch simply
                // declines to re-assert.
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
     */
    private suspend fun resume(
        pass: DrainPass,
        drain: DrainStatus,
        gated: Boolean,
    ): DrainProgress = if (gated) requireEmpty(pass, drain) { resumeInto(pass, drain) } else resumeInto(pass, drain)

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
        // number the escalation is built on. It is cleared below
        // instead, on the passes that actually got somewhere.
        //
        // A recorded *block* travels the same way and for the same
        // reason: `recordBlock` needs it to keep "blocked since" from
        // resetting to now on every pass, which is the one number an
        // operator reads off a drain that is waiting.
        val resumed = step(pass, drain.moveTo(resume, now))
        return if (resumed.drain.state == DrainState.DRAIN_FAILED) {
            resumed
        } else {
            resumed.copy(drain = resumed.drain.copy(failure = null, blocked = null))
        }
    }

    /** Everything one pass established before it looked at the drain's state. */
    private class DrainPass(
        val subject: DrainSubject,
        val node: Node,
        val observation: WorkloadObservation.Present,
        val probe: ProbeOutcome,
        val occupancy: PlayerOccupancy?,
        val contract: WorkloadContract,
        val now: Instant,
    ) {
        val server: ResourceName get() = subject.server
    }

    /**
     * Step 2, on every pass of every state that depends on it.
     *
     * Returns null when the seal is in place — or when there is nothing that could
     * seal, which is the standalone shape — and a [DrainProgress] abort when the
     * proxy would not or could not confirm it. Failing to hold the seal is a real
     * abort rather than a warning: an unsealed backend keeps taking players, so a
     * drain that carried on would be transferring into a queue that refills behind
     * it, which is the state the protocol's own `SOURCE_NOT_SEALED` exists to make
     * unreachable.
     *
     * Skipped once the backend has been deregistered. `PUT /v1/backends/{name}`
     * asserts registration *and* admission, so asserting a seal after step 6 would
     * put the backend back in the routing table moments before the container stops.
     */
    private suspend fun holdSeal(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress? {
        val seal = pass.subject.seal ?: return null
        if (drain.deregisteredAt != null) return null
        return when (val outcome = seal.assertAdmission(admits = false)) {
            is SealOutcome.Asserted -> {
                if (!outcome.admits) {
                    null
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

    private suspend fun abortSeal(
        pass: DrainPass,
        drain: DrainStatus,
        detail: String,
        retryable: Boolean = true,
    ): DrainProgress =
        abort(
            subject = pass.subject,
            node = pass.node,
            drain = drain,
            occupancy = pass.occupancy,
            now = pass.now,
            reason = FailureReason.PROXY_CONTROL_UNREACHABLE,
            failureClass = if (retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
            message =
                "new joins could not be stopped at the proxy, so the drain is not going further: $detail. " +
                    "The server keeps running and keeps taking players",
        )

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
                    outcome = ReconcileOutcome.Progressed("no destination needed: the server is empty"),
                )
            }

        // Nobody to move: no search, and no destination recorded either. A
        // `destination` set here would send the resume ladder to `TARGET_RESOLVED`
        // on a drain that never needed one.
        val probe = pass.probe
        if (probe is ProbeOutcome.Joinable && probe.online == 0) {
            return DrainProgress(
                drain = drain.moveTo(DrainState.TARGET_RESOLVED, now).copy(playersEvacuated = true),
                occupancy = occupancy,
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
                            .copy(destination = choice.destination, playersEvacuated = false),
                    occupancy = occupancy,
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
                    node = pass.node,
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
                    node = pass.node,
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
    ): DrainProgress {
        val occupancy = pass.occupancy
        val now = pass.now
        val router = pass.subject.router
        val destination = drain.destination
        if (router == null || destination == null) {
            return requireEmpty(pass, drain) {
                DrainProgress(
                    drain = drain.moveTo(DrainState.TRANSFERRING, now).copy(playersEvacuated = true),
                    occupancy = occupancy,
                    outcome = ReconcileOutcome.Progressed("no players to transfer"),
                )
            }
        }
        return issueTransfer(pass, drain, router, destination, DrainState.TRANSFERRING)
    }

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
    ): DrainProgress {
        val occupancy = pass.occupancy
        val now = pass.now
        val router = pass.subject.router
        val destination = drain.destination
        if (router == null || destination == null) {
            return requireEmpty(pass, drain) {
                DrainProgress(
                    drain = drain.moveTo(DrainState.SAVING, now).copy(playersEvacuated = true),
                    occupancy = occupancy,
                    outcome = ReconcileOutcome.Progressed("zero players confirmed"),
                )
            }
        }

        return when (val probe = pass.probe) {
            // Same answer as `requireEmpty`, and it has to be: a probe that did not
            // answer is not a zero-player report, and nothing about a sweep being
            // in flight changes that.
            is ProbeOutcome.Unanswered -> {
                unansweredProbe(pass, drain, probe)
            }

            is ProbeOutcome.Joinable -> {
                corroborate(pass, router, probe.online)
                if (probe.online == 0) {
                    DrainProgress(
                        drain = drain.moveTo(DrainState.SAVING, now).copy(playersEvacuated = true),
                        occupancy = occupancy,
                        outcome = ReconcileOutcome.Progressed("zero players confirmed after the transfer"),
                    )
                } else {
                    // Somebody is still on, so anything this drain had saved is
                    // behind whatever they are doing.
                    issueTransfer(
                        pass = pass,
                        drain = drain.forgetSaveEvidence(),
                        router = router,
                        destination = destination,
                        into = DrainState.TRANSFERRING,
                    )
                }
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
     */
    private suspend fun issueTransfer(
        pass: DrainPass,
        drain: DrainStatus,
        router: DrainRouter,
        destination: ResourceName,
        into: DrainState,
    ): DrainProgress {
        val occupancy = pass.occupancy
        val now = pass.now
        exhausted(pass, drain)?.let { limit ->
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
                node = pass.node,
                drain = drain,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_TRANSFER_FAILED,
                failureClass = FailureClass.RETRYABLE,
                message =
                    "the loop has stopped asking proxy=${router.proxy} to move this server's players to " +
                        "`$destination`: $limit. Nobody is disconnected, the container is not stopped, and new " +
                        "joins are restored while this drain is parked",
            )
        }

        return when (val report = router.transfer(destination)) {
            is TransferReport.Sweeping -> {
                DrainProgress(
                    drain =
                        drain
                            .moveTo(into, now)
                            .copy(transferAttempts = drain.transferAttempts + 1, playersEvacuated = false),
                    occupancy = occupancy,
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
                // draining itself — so the drain goes back and picks another rather
                // than moving players onto a server they would have to be moved off
                // again.
                //
                // **[ReconcileOutcome.Retry], never `Progressed`, and the attempt is
                // counted.** This is the second shape of the hot loop and it is
                // easier to reach than the first: the fleet says a server is a fine
                // destination and the *proxy* says it is not — a registration the
                // sweep has not caught up with — so step 3 keeps choosing it and
                // step 4 keeps being refused, about once a second, for ever.
                // Reporting progress on either half is what would make
                // `ReconcileLoop` reset the backoff; counting the attempt is what
                // eventually stops it asking at all.
                LOG.info(
                    "destination `{}` is no longer eligible for server={}: {}. Choosing another",
                    destination,
                    pass.server,
                    report.detail,
                )
                DrainProgress(
                    drain =
                        drain
                            .moveTo(DrainState.SEALED, now)
                            .copy(destination = null, transferAttempts = drain.transferAttempts + 1),
                    occupancy = occupancy,
                    outcome = ReconcileOutcome.Retry("the destination is no longer eligible: ${report.detail}"),
                )
            }

            is TransferReport.Refused -> {
                abort(
                    subject = pass.subject,
                    node = pass.node,
                    drain = drain.copy(transferAttempts = drain.transferAttempts + 1),
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
                    node = pass.node,
                    drain = drain,
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
     * Whether step 4 has asked enough times, and why.
     *
     * Two bounds, because they fail differently. The attempt count catches a sweep
     * that keeps being refused; the clock catches one that keeps being accepted and
     * never converges. The time allowance is extended per player, because a fixed
     * value always fails on a full server
     * (`drain-protocol/references/state-machine.md`).
     */
    private fun exhausted(
        pass: DrainPass,
        drain: DrainStatus,
    ): String? {
        if (drain.transferAttempts >= MAX_TRANSFER_ATTEMPTS) {
            return "$MAX_TRANSFER_ATTEMPTS transfer sweeps have been asked for and players are still connected"
        }
        val online = pass.occupancy?.online ?: 0
        val allowance = pass.subject.playerTransferTimeout + PER_PLAYER_TRANSFER_ALLOWANCE * online
        val waited = JavaDuration.between(drain.enteredStateAt, pass.now).toKotlinDuration()
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
     */
    private suspend fun letGoAndStop(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        val router = pass.subject.router
        val now = pass.now
        if (router == null || drain.deregisteredAt != null) return stop(pass, drain)
        return when (val outcome = router.deregister()) {
            is SealOutcome.Asserted -> {
                LOG.info("deregistered server={} from proxy={}", pass.server, router.proxy)
                DrainProgress(
                    drain = drain.copy(deregisteredAt = now),
                    occupancy = pass.occupancy,
                    outcome = ReconcileOutcome.Progressed("the backend has left the proxy's routing table"),
                )
            }

            is SealOutcome.Refused -> {
                abort(
                    subject = pass.subject,
                    node = pass.node,
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
                    node = pass.node,
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
     * It is still the only place a positive player count voids a save
     * confirmation, and `awaitEvacuated` — which reads a positive count without
     * going through here — voids it the same way, at its own call site.
     */
    private suspend inline fun requireEmpty(
        pass: DrainPass,
        drain: DrainStatus,
        next: () -> DrainProgress,
    ): DrainProgress =
        when (val probe = pass.probe) {
            is ProbeOutcome.Joinable -> {
                if (probe.online == 0) {
                    next()
                } else {
                    val resaves = drain.worldSaved
                    blocked(
                        subject = pass.subject,
                        // Somebody is on the server. Anything it had saved is
                        // now behind whatever they are doing, so the evidence
                        // goes and a later pass has to save again before it can
                        // reach a stop.
                        drain = drain.forgetSaveEvidence(),
                        occupancy = pass.occupancy,
                        now = pass.now,
                        reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                        message =
                            "waiting for the server to empty. ${probe.online} of ${probe.max} player slots are " +
                                "in use and there is no proxy to transfer them through, so the protocol waits " +
                                "rather than disconnecting anybody. The server keeps running and stays " +
                                "joinable; the drain resumes on its own once it is empty" +
                                if (resaves) ", and saves the world again before it stops" else "",
                    )
                }
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
            is ProbeOutcome.Unanswered -> {
                unansweredProbe(pass, drain, probe)
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
            node = pass.node,
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
            return DrainProgress(
                drain = drain.moveTo(DrainState.DEREGISTERED, now),
                occupancy = occupancy,
                outcome = ReconcileOutcome.Progressed("ephemeral storage: no world to save"),
            )
        }
        if (drain.saveIsCurrent(observation.startedAt, now, evidenceGap)) {
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
                node = pass.node,
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
                DrainProgress(
                    drain =
                        drain
                            .moveTo(DrainState.DEREGISTERED, now)
                            // The request is no longer outstanding — it came
                            // back, and the server said the save finished — so
                            // the wedge is released and the confirmation takes
                            // its place. The two are disjoint on purpose: a
                            // confirmation left sitting beside its own request
                            // timestamp is what used to make the next `SAVING`
                            // read a completed save as one that never returned.
                            .copy(saveRequestedAt = null, worldSavedAt = now),
                    occupancy = occupancy,
                    saveConfirmedAt = now,
                    sideEffectIssued = true,
                    outcome = ReconcileOutcome.Progressed("world save confirmed"),
                )
            }

            is SaveOutcome.Unconfirmed -> {
                // The request reached the server. Record that it went out so no
                // later pass sends a second one, and stop here: a timeout tells
                // you the save has not finished, never that it is now fine to
                // stop the container (`failure-modes.md` item 1).
                abort(
                    subject = pass.subject,
                    node = pass.node,
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
                    node = pass.node,
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
                    node = pass.node,
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
     * Step 7. The only container stop in this codebase.
     *
     * Everything it depends on has been established by the states above: zero
     * players confirmed by a probe taken this pass, and — for a server with
     * world data — a save the server itself reported as completed. The grace
     * period comes from the definition, where the schema has already guaranteed
     * it exceeds the save timeout.
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
                node = pass.node,
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
                node = pass.node,
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
            if (probe is ProbeOutcome.Joinable && probe.online > 0) {
                LOG.warn(
                    "server={} still has players after a stop was issued; not re-issuing it",
                    server,
                )
                return blocked(
                    subject = pass.subject,
                    drain = drain.forgetSaveEvidence(),
                    occupancy = occupancy,
                    now = now,
                    reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                    message =
                        "the container is still running after a stop was issued and ${probe.online} of " +
                            "${probe.max} player slots are in use. The stop is not re-issued and the world is " +
                            "saved again before it is",
                )
            }
            if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap)) {
                // The same rule as in `stop`, and the same answer as in
                // `DEREGISTERED`: re-issuing a stop is only safe *because* a
                // save that is still current is on disk, so if it is not, the
                // drain goes back and saves rather than stopping or giving up.
                return DrainProgress(
                    drain = drain.moveTo(DrainState.SAVING, now),
                    occupancy = occupancy,
                    outcome =
                        ReconcileOutcome.Progressed(
                            "the stop is not re-issued until the world is saved again: " +
                                drain.saveEvidenceProblem(observation.startedAt),
                        ),
                )
            }
            LOG.warn(
                "server={} is still running after a stop was issued; re-issuing with the same grace period",
                server,
            )
            pass.node.stopWorkload(observation.handle, pass.subject.stopGracePeriod)
            return DrainProgress(
                drain = drain,
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
     * Any recorded failure is cleared. A drain that failed and is now merely
     * waiting has had its problem resolved, and leaving the old failure beside the
     * block would report both a fault and its absence.
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
        val block = recordBlock(reason, message, now, drain.blocked)
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
        return DrainProgress(
            drain =
                restored
                    .moveTo(DrainState.DRAIN_FAILED, now)
                    .copy(blocked = block, failure = null),
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
     */
    @Suppress("LongParameterList")
    private suspend fun abort(
        subject: DrainSubject,
        node: Node,
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
        // The first pass that recorded *this* failure, not the first pass of the
        // drain. Asked before `recordFailure` builds the failure, because the
        // escalation decides the wording of the message that call is given — see
        // [firstOccurrenceOf] for why the rule lives in one place rather than
        // being restated here.
        val failingSince = drain.failure.firstOccurrenceOf(reason, now)
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
        val failure = recordFailure(reason, failureClass, reported, now, drain.failure)
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
        val aborted =
            restored
                .moveTo(DrainState.DRAIN_FAILED, now)
                // Any block goes: whatever the drain was waiting for, it has now
                // hit something that went wrong, and a record saying both would
                // report a fault and its absence at the same time. The failure is
                // the louder of the two and is the one that survives.
                .copy(failure = failure, blocked = null)
        val outcome =
            if (failureClass == FailureClass.RETRYABLE) {
                ReconcileOutcome.Retry(reported)
            } else {
                ReconcileOutcome.Failed(reported)
            }
        return DrainProgress(
            drain = aborted,
            occupancy = occupancy,
            sideEffectIssued = sideEffectIssued,
            outcome = outcome,
        )
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

        /**
         * How many transfer sweeps step 4 asks for before it stops asking.
         *
         * The limit is the orchestrator's to own, because a fresh sweep zeroes the
         * plugin's tallies — the plugin cannot count across them. At the limit the
         * loop stops *asking*; nobody is disconnected and nothing is stopped.
         */
        private const val MAX_TRANSFER_ATTEMPTS = 6

        /**
         * Added to `spec.lifecycle.drain.playerTransferTimeout` per player.
         *
         * A fixed transfer allowance always fails on a full server
         * (`drain-protocol/references/state-machine.md`), so the declared timeout is
         * the floor and this is the slope.
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
 * what makes the condition self-clearing — a pass that gets somewhere clears
 * `DrainStatus.failure` (see the `DRAIN_FAILED` resume), so the escalation goes
 * with it rather than being a second thing to remember to reset — and it is also
 * what makes a *blocked* drain quiet, since a block records no failure. Both
 * behaviours are this one line, which is why there is no list of exempt reasons
 * above it.
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
    val outcome: ReconcileOutcome,
)

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
        copy(worldSavedAt = null)
    }
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
 * Called only from a pass that has just *observed* somebody on the server. There
 * are exactly two such call sites — the players-online branch of `requireEmpty`
 * and the players-online branch of `awaitStopped` — and the count is stated
 * exactly on purpose: an earlier version of this comment claimed a set of
 * callers it did not have, and a drain audit had to find that out the hard way.
 * If you add a caller, it must be one that saw a player, and this sentence must
 * change with it. That observation is what justifies the last part. Clearing `saveRequestedAt`
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
 */
internal fun DrainStatus.forgetSaveEvidence(): DrainStatus =
    copy(worldSavedAt = null, saveRequestedAt = null, playersEvacuated = false)

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
internal fun DrainStatus.forgetSaveConfirmation(): DrainStatus = copy(worldSavedAt = null, playersEvacuated = false)

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

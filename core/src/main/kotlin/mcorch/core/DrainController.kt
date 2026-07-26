package mcorch.core

import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.SaveOutcome
import mcorch.core.paper.WorkloadContract
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PlayerOccupancy
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

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
 * ## What a standalone Paper server changes
 *
 * The protocol has seven steps, three of which are conversations with a proxy:
 * stop new joins (2), transfer the players (4), deregister the backend (6). A
 * standalone Paper server has no proxy, so those steps have no counterparty.
 * They are still traversed as recorded states — the state machine stays whole,
 * the dashboard stays legible, and adding a proxy later fills in the bodies
 * rather than reshaping the flow.
 *
 * The consequence is step 3. With no proxy there is nowhere for players to go,
 * so a server with players online has **no drain destination** and the drain
 * aborts. Kicking them to make progress is not an option
 * (`failure-modes.md` item 4).
 */
internal class DrainController(
    private val clock: Clock,
) {
    /**
     * Advances the drain by at most one step, performing at most one side
     * effect.
     *
     * @param current the drain recorded last pass, or null to start one.
     */
    suspend fun advance(
        definition: PaperServerDefinition,
        agent: PaperServerAgent,
        node: Node,
        observation: WorkloadObservation,
        current: DrainStatus?,
        cause: DrainCause,
    ): DrainProgress {
        val now = clock.instant()
        val recorded = current ?: started(now)
        if (current == null) {
            LOG.info(
                "drain started for server={} cause={} node={}",
                definition.metadata.name,
                cause,
                node.name,
            )
            return DrainProgress(
                drain = recorded,
                outcome = ReconcileOutcome.Progressed("drain requested (${cause.detail})"),
            )
        }

        // Nothing is running. There are provably no players and nothing left to
        // flush, so the drain is already where it was trying to get to. This is
        // the runtime reporting a reaped process or an absent workload — it is
        // never inferred from a failed probe or an unreachable node.
        val down = observation.containerIsDown()
        if (down != null) {
            return DrainProgress(
                drain = recorded.moveTo(DrainState.STOPPING, now).copy(playersEvacuated = true),
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

        // A confirmation the loop recorded before the process now running
        // started is not evidence about that process. Dropped here, once, so
        // that every state below sees a drain whose evidence is about the
        // container in front of it — and so that the drain can go and get a
        // fresh confirmation rather than reporting a save it cannot vouch for.
        val drain = recorded.dropStaleSaveEvidence(observation.startedAt)
        if (drain !== recorded) {
            LOG.warn(
                "server={} has a world save confirmed at {}, before the container now running started at {}; " +
                    "it is not evidence about this process and the drain will save again",
                definition.metadata.name,
                recorded.saveRequestedAt,
                observation.startedAt,
            )
        }

        // Occupancy is re-read on every pass of a running server, not once at
        // the start. Nothing is stopping new players from joining a standalone
        // server mid-drain — there is no proxy to seal — so a count taken three
        // states ago is not evidence of anything.
        val probe = agent.probe(node, observation.handle)
        val occupancy = (probe as? ProbeOutcome.Joinable)?.let { PlayerOccupancy(it.online, it.max, now) }

        // Read once per pass, off the running container rather than off the
        // definition, and threaded through every decision below.
        val contract = agent.contractOf(observation)

        return when (drain.state) {
            DrainState.DRAIN_REQUESTED -> {
                // Step 2: stop new joins. No proxy, so nothing to instruct.
                // `sealRequestedAt` stays null because no request was sent —
                // those timestamps record side effects, and recording one that
                // never happened would make a resumed drain skip real work.
                DrainProgress(
                    drain = drain.moveTo(DrainState.SEALED, now),
                    occupancy = occupancy,
                    outcome = ReconcileOutcome.Progressed(NO_PROXY_SEAL),
                )
            }

            // Step 3: secure a destination.
            DrainState.SEALED -> {
                resolveDestination(definition, drain, probe, occupancy, now)
            }

            // Step 4: transfer. Zero players was just confirmed by the guard
            // below, so there is nobody to move.
            DrainState.TARGET_RESOLVED -> {
                requireEmpty(definition, drain, probe, occupancy, now) {
                    DrainProgress(
                        drain = drain.moveTo(DrainState.TRANSFERRING, now).copy(playersEvacuated = true),
                        occupancy = occupancy,
                        outcome = ReconcileOutcome.Progressed("no players to transfer"),
                    )
                }
            }

            DrainState.TRANSFERRING -> {
                requireEmpty(definition, drain, probe, occupancy, now) {
                    DrainProgress(
                        drain = drain.moveTo(DrainState.SAVING, now).copy(playersEvacuated = true),
                        occupancy = occupancy,
                        outcome = ReconcileOutcome.Progressed("zero players confirmed"),
                    )
                }
            }

            // Step 5: save the world and wait for completion.
            DrainState.SAVING -> {
                requireEmpty(definition, drain, probe, occupancy, now) {
                    save(agent, node, observation, contract, drain, occupancy, now)
                }
            }

            // Step 6: deregister the backend. No proxy, so nothing is
            // registered; `deregisteredAt` stays null for the same reason
            // `sealRequestedAt` does.
            DrainState.DEREGISTERED -> {
                requireEmpty(definition, drain, probe, occupancy, now) {
                    if (drain.mayStop(contract, observation.startedAt)) {
                        stop(definition, node, observation, contract, drain, occupancy, now)
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
                awaitStopped(definition, node, observation, probe, contract, drain, occupancy, now)
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
                requireEmpty(definition, drain, probe, occupancy, now) {
                    // The furthest state the evidence still justifies. A drain
                    // that has emptied the server and lost only its save goes
                    // back to the save rather than round the whole machine,
                    // which would make a dashboard read as though it were
                    // making progress every fourth pass for as long as the save
                    // keeps failing.
                    val resume =
                        when {
                            drain.saveIsCurrent(observation.startedAt) -> DrainState.DEREGISTERED
                            drain.playersEvacuated -> DrainState.SAVING
                            else -> DrainState.SEALED
                        }
                    DrainProgress(
                        drain = drain.moveTo(resume, now).copy(failure = null),
                        occupancy = occupancy,
                        outcome = ReconcileOutcome.Progressed("the drain resumes at $resume"),
                    )
                }
            }
        }
    }

    /**
     * Step 3, for a server with no proxy.
     *
     * With players online there is no destination and no way to make one, so
     * this is where the drain stops. It stops *without* stopping the container:
     * the failure is retryable, the loop backs off and looks again, and if the
     * last player logs off the drain continues on its own.
     */
    private fun resolveDestination(
        definition: PaperServerDefinition,
        drain: DrainStatus,
        probe: ProbeOutcome,
        occupancy: PlayerOccupancy?,
        now: Instant,
    ): DrainProgress =
        requireEmpty(definition, drain, probe, occupancy, now) {
            DrainProgress(
                drain = drain.moveTo(DrainState.TARGET_RESOLVED, now).copy(playersEvacuated = true),
                occupancy = occupancy,
                outcome = ReconcileOutcome.Progressed("no destination needed: the server is empty"),
            )
        }

    /**
     * Runs [next] only if a fresh probe reports zero players, and aborts
     * otherwise.
     *
     * Every state from [DrainState.SEALED] onward goes through this. It is the
     * single place that answers "is it safe to keep going", so there is one
     * thing to audit rather than six — and, because it is the only place a
     * positive player count is ever observed, it is also the single place that
     * can void a save confirmation.
     */
    private inline fun requireEmpty(
        definition: PaperServerDefinition,
        drain: DrainStatus,
        probe: ProbeOutcome,
        occupancy: PlayerOccupancy?,
        now: Instant,
        next: () -> DrainProgress,
    ): DrainProgress =
        when (probe) {
            is ProbeOutcome.Joinable -> {
                if (probe.online == 0) {
                    next()
                } else {
                    LOG.info(
                        "drain blocked for server={}: {} players online and no destination",
                        definition.metadata.name,
                        probe.online,
                    )
                    val resaves = drain.worldSaved
                    abort(
                        // Somebody is on the server. Anything it had saved is
                        // now behind whatever they are doing, so the evidence
                        // goes and a later pass has to save again before it can
                        // reach a stop.
                        drain = drain.forgetSaveEvidence(),
                        occupancy = occupancy,
                        now = now,
                        reason = FailureReason.DRAIN_NO_DESTINATION,
                        failureClass = FailureClass.RETRYABLE,
                        message =
                            "blocked: no drain destination. ${probe.online} of ${probe.max} player slots are in " +
                                "use and a standalone Paper server has no proxy to transfer them through. The " +
                                "server keeps running; the drain resumes on its own once it is empty" +
                                if (resaves) ", and saves the world again before it stops" else "",
                    )
                }
            }

            // The server did not answer. That is not a zero-player report, and
            // treating it as one is how a drain stops a server with people on
            // it.
            is ProbeOutcome.NotJoinable -> {
                abort(
                    drain = drain,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_STALLED,
                    failureClass = FailureClass.RETRYABLE,
                    message = "cannot confirm zero players: ${probe.detail}",
                )
            }

            is ProbeOutcome.Unavailable -> {
                abort(
                    drain = drain,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_STALLED,
                    failureClass =
                        if (probe.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                    message = "cannot confirm zero players: ${probe.detail}",
                )
            }
        }

    /** Step 5. Requests a save at most once, and only proceeds on a confirmed completion. */
    @Suppress("LongParameterList")
    private suspend fun save(
        agent: PaperServerAgent,
        node: Node,
        observation: WorkloadObservation.Present,
        contract: WorkloadContract,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
    ): DrainProgress {
        if (!contract.holdsWorldData) {
            // Ephemeral storage: the operator asked for a disposable instance
            // by name, and there is no world to flush. `worldSaved` stays false
            // because nothing was saved — saying otherwise would make a status
            // read claim evidence that does not exist.
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
        if (drain.saveIsCurrent(observation.startedAt)) {
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

        return when (val outcome = agent.requestSave(node, observation, contract)) {
            SaveOutcome.Confirmed -> {
                DrainProgress(
                    drain =
                        drain
                            .moveTo(DrainState.DEREGISTERED, now)
                            // `saveRequestedAt` doubles as *when* the completion
                            // was confirmed — see [saveConfirmedAt]. Both are
                            // stamped with this pass's instant, together.
                            .copy(saveRequestedAt = now, worldSaved = true),
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
    @Suppress("LongParameterList")
    private suspend fun stop(
        definition: PaperServerDefinition,
        node: Node,
        observation: WorkloadObservation.Present,
        contract: WorkloadContract,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
    ): DrainProgress {
        if (!drain.mayStop(contract, observation.startedAt)) {
            // Unreachable through the state machine: `DEREGISTERED` checks the
            // same thing and goes back to `SAVING` instead of calling this. Kept
            // as the last line of defence — if a future edit ever routes into
            // the stop without a current save, it aborts instead of losing a
            // world.
            return abort(
                drain = drain,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = FailureClass.PERMANENT,
                message = "refusing to stop: ${drain.saveEvidenceProblem(observation.startedAt)}",
            )
        }

        val grace = definition.spec.lifecycle.stopGracePeriod
        LOG.info(
            "stopping server={} node={} gracePeriod={}s worldSaved={} worldData={}",
            definition.metadata.name,
            node.name,
            grace.inWholeSeconds,
            drain.worldSaved,
            contract.holdsWorldData,
        )
        node.stopWorkload(observation.handle, grace)
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
    @Suppress("LongParameterList")
    private suspend fun awaitStopped(
        definition: PaperServerDefinition,
        node: Node,
        observation: WorkloadObservation.Present,
        probe: ProbeOutcome,
        contract: WorkloadContract,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
    ): DrainProgress {
        if (observation.state == WorkloadState.RUNNING) {
            if (probe is ProbeOutcome.Joinable && probe.online > 0) {
                LOG.warn(
                    "server={} still has players after a stop was issued; not re-issuing it",
                    definition.metadata.name,
                )
                return abort(
                    drain = drain.forgetSaveEvidence(),
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_NO_DESTINATION,
                    failureClass = FailureClass.RETRYABLE,
                    message =
                        "the container is still running after a stop was issued and ${probe.online} of " +
                            "${probe.max} player slots are in use. The stop is not re-issued and the world is " +
                            "saved again before it is",
                )
            }
            if (!drain.mayStop(contract, observation.startedAt)) {
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
                definition.metadata.name,
            )
            node.stopWorkload(observation.handle, definition.spec.lifecycle.stopGracePeriod)
            return DrainProgress(
                drain = drain,
                occupancy = occupancy,
                outcome = ReconcileOutcome.Retry("the container is still running after a stop was issued"),
            )
        }
        return DrainProgress(
            drain = drain,
            occupancy = occupancy,
            containerDown = true,
            outcome = ReconcileOutcome.Progressed("the container has stopped"),
        )
    }

    @Suppress("LongParameterList")
    private fun abort(
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
        reason: FailureReason,
        failureClass: FailureClass,
        message: String,
        sideEffectIssued: Boolean = false,
    ): DrainProgress {
        val failure = recordFailure(reason, failureClass, message, now, drain.failure)
        val aborted =
            drain
                .moveTo(DrainState.DRAIN_FAILED, now)
                .copy(failure = failure)
        val outcome =
            if (failureClass == FailureClass.RETRYABLE) {
                ReconcileOutcome.Retry(message)
            } else {
                ReconcileOutcome.Failed(message)
            }
        return DrainProgress(
            drain = aborted,
            occupancy = occupancy,
            sideEffectIssued = sideEffectIssued,
            outcome = outcome,
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
     * of one costs a pass rather than a repeat. A save request is not
     * observable, so [mcorch.schema.DrainStatus.saveRequestedAt] is the only
     * thing standing between a lost write and a second save on a live server.
     * The caller has to make that record durable even when the observation it
     * belongs to is rejected.
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
 * When this drain's completed save was confirmed, or null if it has no
 * confirmation.
 *
 * `worldSaved` is set only when the server itself reported a *completed* save,
 * never when one was merely requested — and it is stamped together with
 * `saveRequestedAt`, so that field carries the confirmation instant whenever
 * `worldSaved` is true. Reading a time out of a field named for a request is not
 * lovely; the alternative is a `worldSavedAt` on [DrainStatus], which lives in
 * `:schema`. Worth adding there, and the only thing that would change here is
 * this one accessor.
 */
private fun DrainStatus.saveConfirmedAt(): Instant? = if (worldSaved) saveRequestedAt else null

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
private fun DrainStatus.saveIsCurrent(containerStartedAt: Instant?): Boolean {
    val confirmed = saveConfirmedAt() ?: return false
    return containerStartedAt == null || !confirmed.isBefore(containerStartedAt)
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
): Boolean = !contract.holdsWorldData || saveIsCurrent(containerStartedAt)

/**
 * Drops a save confirmation that predates the process now running.
 *
 * Applied once per pass, before any state acts on the drain. A drain record
 * outlives containers — it is read back from the store after a restart of the
 * loop, and it survives a container being restarted underneath it — so this is
 * the point at which "the world was saved" stops being true without anybody
 * having observed anything contradicting it.
 *
 * `saveRequestedAt` goes with it, because it doubles as the confirmation
 * instant: leaving it behind would make the re-entered save read as "a request
 * went out and was never confirmed" and abort permanently on a save that
 * actually completed.
 */
private fun DrainStatus.dropStaleSaveEvidence(containerStartedAt: Instant?): DrainStatus =
    if (worldSaved && !saveIsCurrent(containerStartedAt)) {
        copy(worldSaved = false, saveRequestedAt = null)
    } else {
        this
    }

/** Why the save evidence is not good enough to stop on, for an operator-facing message. */
private fun DrainStatus.saveEvidenceProblem(containerStartedAt: Instant?): String {
    val confirmed = saveConfirmedAt()
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
 * on disk.
 *
 * Called when a player is observed on a server being drained, and only there.
 * The confirmation described the world as it was; from the next tick it does
 * not, and the drain has to ask for a fresh save — which means `saveRequestedAt`
 * has to go too, or the re-entered save would refuse to send one.
 * `playersEvacuated` goes with them: it is the claim that this server was
 * confirmed empty, and somebody just joined it.
 *
 * Note what this does *not* undo: an unconfirmed save that was genuinely
 * delivered. That state is a permanent abort, and the reconciler stops before a
 * pass can reach this function with it.
 */
private fun DrainStatus.forgetSaveEvidence(): DrainStatus =
    if (!worldSaved && saveRequestedAt == null && !playersEvacuated) {
        this
    } else {
        copy(worldSaved = false, saveRequestedAt = null, playersEvacuated = false)
    }

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
private fun WorkloadObservation.containerIsDown(): String? =
    when (this) {
        WorkloadObservation.Absent -> {
            "gone"
        }

        is WorkloadObservation.Present -> {
            when (state) {
                WorkloadState.EXITED -> "exited"
                WorkloadState.SANDBOX_ONLY -> "never created"
                WorkloadState.CREATED -> "never started"
                WorkloadState.RUNNING, WorkloadState.UNKNOWN -> null
            }
        }
    }

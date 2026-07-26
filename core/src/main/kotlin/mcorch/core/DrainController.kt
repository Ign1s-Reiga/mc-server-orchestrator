package mcorch.core

import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.SaveOutcome
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PlayerOccupancy
import mcorch.schema.StorageMode
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
        val drain = current ?: started(now)
        if (current == null) {
            LOG.info(
                "drain started for server={} cause={} node={}",
                definition.metadata.name,
                cause,
                node.name,
            )
            return DrainProgress(
                drain = drain,
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
                drain = drain.moveTo(DrainState.STOPPING, now).copy(playersEvacuated = true),
                containerDown = true,
                outcome = ReconcileOutcome.Progressed("the container is already $down"),
            )
        }
        if (observation !is WorkloadObservation.Present) {
            return DrainProgress(drain = drain, outcome = ReconcileOutcome.Waiting(UNKNOWN_STATE, POLL))
        }
        if (observation.state == WorkloadState.UNKNOWN) {
            return DrainProgress(drain = drain, outcome = ReconcileOutcome.Waiting(UNKNOWN_STATE, POLL))
        }

        // Occupancy is re-read on every pass of a running server, not once at
        // the start. Nothing is stopping new players from joining a standalone
        // server mid-drain — there is no proxy to seal — so a count taken three
        // states ago is not evidence of anything.
        val probe = agent.probe(node, observation.handle)
        val occupancy = (probe as? ProbeOutcome.Joinable)?.let { PlayerOccupancy(it.online, it.max, now) }

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
                    save(agent, node, observation.handle, drain, occupancy, now)
                }
            }

            // Step 6: deregister the backend. No proxy, so nothing is
            // registered; `deregisteredAt` stays null for the same reason
            // `sealRequestedAt` does.
            DrainState.DEREGISTERED -> {
                requireEmpty(definition, drain, probe, occupancy, now) {
                    stop(definition, node, observation.handle, drain, occupancy, now)
                }
            }

            // Step 7 was issued on the way into this state. The container has
            // not been observed down yet, so watch for it.
            DrainState.STOPPING -> {
                awaitStopped(definition, node, observation, drain, occupancy, now)
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
            // request. A permanent abort is never re-entered at all: the
            // reconciler stops before it gets here.
            DrainState.DRAIN_FAILED -> {
                requireEmpty(definition, drain, probe, occupancy, now) {
                    val resume =
                        if (drain.worldSaved) DrainState.DEREGISTERED else DrainState.SEALED
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
     * thing to audit rather than six.
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
                    abort(
                        drain = drain,
                        occupancy = occupancy,
                        now = now,
                        reason = FailureReason.DRAIN_NO_DESTINATION,
                        failureClass = FailureClass.RETRYABLE,
                        message =
                            "blocked: no drain destination. ${probe.online} of ${probe.max} player slots are in " +
                                "use and a standalone Paper server has no proxy to transfer them through. The " +
                                "server keeps running; the drain resumes on its own once it is empty",
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
    private suspend fun save(
        agent: PaperServerAgent,
        node: Node,
        handle: WorkloadHandle,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
    ): DrainProgress {
        if (!agent.savePersistsWorld) {
            // Ephemeral storage: the operator asked for a disposable instance
            // by name, and there is no world to flush. `worldSaved` stays false
            // because nothing was saved — saying otherwise would make a status
            // read claim evidence that does not exist.
            return DrainProgress(
                drain = drain.moveTo(DrainState.DEREGISTERED, now),
                occupancy = occupancy,
                outcome = ReconcileOutcome.Progressed("ephemeral storage: no world to save"),
            )
        }
        if (drain.worldSaved) {
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

        return when (val outcome = agent.requestSave(node, handle)) {
            SaveOutcome.Confirmed -> {
                DrainProgress(
                    drain =
                        drain
                            .moveTo(DrainState.DEREGISTERED, now)
                            .copy(saveRequestedAt = now, worldSaved = true),
                    occupancy = occupancy,
                    saveConfirmedAt = now,
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
    private suspend fun stop(
        definition: PaperServerDefinition,
        node: Node,
        handle: WorkloadHandle,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
    ): DrainProgress {
        if (!drain.savedOrNothingToSave(definition)) {
            // Unreachable through the state machine. Kept as a last line of
            // defence: if a future edit ever routes into the stop without a
            // confirmed save, it aborts instead of losing a world.
            return abort(
                drain = drain,
                occupancy = occupancy,
                now = now,
                reason = FailureReason.DRAIN_STALLED,
                failureClass = FailureClass.PERMANENT,
                message = "refusing to stop: the world save has not been confirmed",
            )
        }

        val grace = definition.spec.lifecycle.stopGracePeriod
        LOG.info(
            "stopping server={} node={} gracePeriod={}s worldSaved={}",
            definition.metadata.name,
            node.name,
            grace.inWholeSeconds,
            drain.worldSaved,
        )
        node.stopWorkload(handle, grace)
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
     */
    private suspend fun awaitStopped(
        definition: PaperServerDefinition,
        node: Node,
        observation: WorkloadObservation.Present,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
    ): DrainProgress {
        if (observation.state == WorkloadState.RUNNING) {
            if (!drain.savedOrNothingToSave(definition)) {
                // The same last line of defence as in `stop`. Re-issuing a stop
                // is only safe *because* the save is already on disk.
                return abort(
                    drain = drain,
                    occupancy = occupancy,
                    now = now,
                    reason = FailureReason.DRAIN_STALLED,
                    failureClass = FailureClass.PERMANENT,
                    message = "refusing to re-issue a stop: the world save has not been confirmed",
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

    private fun abort(
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
        reason: FailureReason,
        failureClass: FailureClass,
        message: String,
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
        return DrainProgress(drain = aborted, occupancy = occupancy, outcome = outcome)
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
     * True once the container is provably gone. Teardown — removing the
     * workload, purging the definition — waits for this, so nothing is ever
     * deleted out from under a running container.
     */
    val containerDown: Boolean = false,
    val outcome: ReconcileOutcome,
)

/**
 * The precondition for every container stop in this file: the world is on disk,
 * or there was never a world to put there.
 *
 * `worldSaved` is set only when the server itself reported a *completed* save,
 * never when one was merely requested.
 */
private fun DrainStatus.savedOrNothingToSave(definition: PaperServerDefinition): Boolean =
    worldSaved || definition.spec.storage.mode == StorageMode.EPHEMERAL

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

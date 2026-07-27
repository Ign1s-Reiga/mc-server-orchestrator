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
        definition: PaperServerDefinition,
        agent: PaperServerAgent,
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
        val down = observation.containerIsDown(hadContainer)
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

        // Two ways a confirmation stops describing the world, neither of which
        // any probe can report, both dropped here so that every state below
        // sees a drain whose evidence is about the container in front of it.
        val drain = recorded.dropUnusableSaveEvidence(observation.startedAt, lastProbedAt, now, evidenceGap)
        if (drain !== recorded) {
            LOG.warn(
                "server={} has a world save confirmed at {} that is no longer evidence: the container now " +
                    "running started at {} and a probe last answered at {}. The drain will save again",
                definition.metadata.name,
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
        if (observation.state == WorkloadState.SANDBOX_ONLY) {
            return abort(
                server = definition.metadata.name,
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

        // Occupancy is re-read on every pass of a running server, not once at
        // the start. Nothing is stopping new players from joining a standalone
        // server mid-drain — there is no proxy to seal — so a count taken three
        // states ago is not evidence of anything.
        val probe = agent.probe(node, observation.handle)
        val occupancy = (probe as? ProbeOutcome.Joinable)?.let { PlayerOccupancy(it.online, it.max, now) }

        val pass =
            DrainPass(
                definition = definition,
                agent = agent,
                node = node,
                observation = observation,
                probe = probe,
                occupancy = occupancy,
                // Read once per pass, off the running container rather than off
                // the definition, and threaded through every decision below.
                contract = agent.contractOf(observation),
                now = now,
            )
        return step(pass, drain)
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
    private suspend fun step(
        pass: DrainPass,
        drain: DrainStatus,
    ): DrainProgress {
        val definition = pass.definition
        val agent = pass.agent
        val node = pass.node
        val observation = pass.observation
        val probe = pass.probe
        val occupancy = pass.occupancy
        val contract = pass.contract
        val now = pass.now

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
                    save(pass, drain)
                }
            }

            // Step 6: deregister the backend. No proxy, so nothing is
            // registered; `deregisteredAt` stays null for the same reason
            // `sealRequestedAt` does.
            DrainState.DEREGISTERED -> {
                requireEmpty(definition, drain, probe, occupancy, now) {
                    if (drain.mayStop(contract, observation.startedAt, now, evidenceGap)) {
                        stop(pass, drain)
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
                requireEmpty(definition, drain, probe, occupancy, now) {
                    // The furthest state the evidence still justifies. A drain
                    // that has emptied the server and lost only its save goes
                    // back to the save rather than round the whole machine,
                    // which would make a dashboard read as though it were
                    // making progress every fourth pass for as long as the save
                    // keeps failing.
                    val resume =
                        when {
                            drain.saveIsCurrent(observation.startedAt, now, evidenceGap) -> DrainState.DEREGISTERED
                            drain.playersEvacuated -> DrainState.SAVING
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
                    val resumed = step(pass, drain.moveTo(resume, now))
                    if (resumed.drain.state == DrainState.DRAIN_FAILED) {
                        resumed
                    } else {
                        resumed.copy(drain = resumed.drain.copy(failure = null))
                    }
                }
            }
        }
    }

    /** Everything one pass established before it looked at the drain's state. */
    private class DrainPass(
        val definition: PaperServerDefinition,
        val agent: PaperServerAgent,
        val node: Node,
        val observation: WorkloadObservation.Present,
        val probe: ProbeOutcome,
        val occupancy: PlayerOccupancy?,
        val contract: WorkloadContract,
        val now: Instant,
    ) {
        val server: ResourceName get() = definition.metadata.name
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
                        server = definition.metadata.name,
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
                abort(
                    server = definition.metadata.name,
                    drain = drain.forgetSaveConfirmation(),
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
                server = server,
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

        return when (val outcome = pass.agent.requestSave(pass.node, observation, contract)) {
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
                    server = server,
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
                    server = server,
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
                    server = server,
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
        val definition = pass.definition
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
                server = server,
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
            pass.node.name,
            grace.inWholeSeconds,
            drain.worldSaved,
            contract.holdsWorldData,
        )
        pass.node.stopWorkload(observation.handle, grace)
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
        val definition = pass.definition
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
                    definition.metadata.name,
                )
                return abort(
                    server = server,
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
                definition.metadata.name,
            )
            pass.node.stopWorkload(observation.handle, definition.spec.lifecycle.stopGracePeriod)
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
    private fun abort(
        server: ResourceName,
        drain: DrainStatus,
        occupancy: PlayerOccupancy?,
        now: Instant,
        reason: FailureReason,
        failureClass: FailureClass,
        message: String,
        sideEffectIssued: Boolean = false,
    ): DrainProgress {
        val stuckFor = JavaDuration.between(drain.startedAt, now).toKotlinDuration()
        // `DRAIN_NO_DESTINATION` is excluded on purpose: it means somebody is
        // playing on a server that was asked to go away, which is the protocol
        // working exactly as designed and resolves itself when they log off. An
        // escalation that fires on a busy evening every backoff interval teaches
        // operators that the marker means nothing, and it is the only escalation
        // signal there is.
        val needsAttention =
            failureClass == FailureClass.RETRYABLE &&
                reason != FailureReason.DRAIN_NO_DESTINATION &&
                stuckFor >= attentionAfter
        val reported =
            if (needsAttention) {
                "$ATTENTION this drain has been unable to finish for ${stuckFor.inWholeMinutes} minutes and is " +
                    "not going to fix itself. The server keeps running and the loop keeps trying. $message"
            } else {
                message
            }
        val failure = recordFailure(reason, failureClass, reported, now, drain.failure)
        if (needsAttention) {
            LOG.error(
                "server={} has been unable to finish a drain for {} minutes ({} attempts); it keeps running and " +
                    "the loop keeps trying, but this needs a human: {}",
                server,
                stuckFor.inWholeMinutes,
                failure.attempts,
                message,
            )
        }
        val aborted =
            drain
                .moveTo(DrainState.DRAIN_FAILED, now)
                .copy(failure = failure)
        val outcome =
            if (failureClass == FailureClass.RETRYABLE) {
                ReconcileOutcome.Retry(reported)
            } else {
                ReconcileOutcome.Failed(reported)
            }
        return DrainProgress(
            drain = aborted,
            occupancy = occupancy,
            needsAttention = needsAttention,
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
 * The marker a long-failing drain's message leads with.
 *
 * A greppable constant rather than prose, because it is the only
 * machine-readable escalation signal available without a new
 * [mcorch.schema.FailureReason] or [mcorch.schema.ConditionType] — and both of
 * those live in `:schema`. A `NEEDS_ATTENTION` condition type is where this
 * belongs; this is the stand-in until there is one.
 */
internal const val ATTENTION: String = "[needs attention]"

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
     * True once this drain has been failing long enough that it will not fix
     * itself. Carried out to the status so the report can say so; it changes
     * nothing about what is done to the container.
     */
    val needsAttention: Boolean = false,
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

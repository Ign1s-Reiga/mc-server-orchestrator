package mcorch.core

import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.PaperWorkloadPlanner
import mcorch.core.paper.ProbeOutcome
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.FailureStatus
import mcorch.schema.ImageStatus
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.PlayerOccupancy
import mcorch.schema.ResourceName
import mcorch.schema.RuntimeIdentity
import mcorch.schema.ServerEndpoint
import mcorch.schema.ServerPhase
import mcorch.schema.StorageSpec
import mcorch.schema.StorageStatus
import mcorch.store.ConflictReason
import mcorch.store.Precondition
import mcorch.store.Store
import mcorch.store.StoreException
import mcorch.store.StoredServer
import mcorch.store.WriteOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * One reconcile pass for one server.
 *
 * ## The shape of a pass
 *
 * Read the desired state, ask the [Scheduler] where the server belongs, ask
 * that [Node] what is actually there, compare, apply **one** convergent step,
 * record what was observed, and say when to come back. Nothing here waits for a
 * container to change state: there is no wait-for-exit call to wait on, so
 * every wait is a requeue.
 *
 * ## Idempotency (CLAUDE.md invariant 5)
 *
 * Three mechanisms, none of which relies on the loop remembering anything
 * across a restart:
 *
 * 1. Workloads are adopted by label ([Node.ensureWorkload]), so a second pass
 *    finds the container the first one created instead of creating another.
 * 2. Images are checked before they are pulled ([Node.ensureImage]), so a
 *    second pass does not re-download.
 * 3. The one side effect that cannot be checked for — a save request — is
 *    guarded by a timestamp on observed status
 *    ([mcorch.schema.DrainStatus.saveRequestedAt]).
 *
 * On top of that, the status a pass would write is compared against the stored
 * one, and an observation that has not changed is not rewritten. A settled
 * server therefore produces no store traffic either, apart from a heartbeat
 * every [ReconcilerConfig.statusHeartbeat] so that a loop which has died can
 * still be told from one that is idle.
 *
 * ## Failure
 *
 * [NodeException] and [StoreException] both classify themselves. Retryable
 * failures requeue and appear on observed status while they last; permanent
 * ones are recorded with [FailureClass.PERMANENT] and the loop stops acting on
 * that server until its definition changes. Nothing is swallowed.
 *
 * Instances are stateless and safe to share between concurrent workers.
 */
public class Reconciler(
    private val store: Store,
    private val registry: NodeRegistry,
    private val scheduler: Scheduler,
    private val config: ReconcilerConfig = ReconcilerConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val drainController = DrainController(clock)

    /**
     * Converges [name] one step toward its desired state.
     *
     * Never throws for an expected failure: everything a pass can encounter
     * comes back as a [ReconcileOutcome], with the detail on observed status.
     * Cancellation propagates unchanged.
     */
    public suspend fun reconcile(name: ResourceName): ReconcileOutcome {
        val stored =
            try {
                store.getServer(name)
            } catch (failure: StoreException) {
                return storeOutcome(name, failure)
            } ?: return ReconcileOutcome.Settled("no definition is stored under `$name`")

        // Exhaustive on purpose: a new server kind must not compile until the
        // loop has been taught what to do with it.
        return when (val definition = stored.definition.definition) {
            is PaperServerDefinition -> reconcilePaper(Pass(stored, definition, clock.instant()))
        }
    }

    private suspend fun reconcilePaper(pass: Pass): ReconcileOutcome {
        // A permanent failure is a request to stop trying, not a request to
        // keep trying more slowly. The gate lifts when the operator changes the
        // definition (the generation moves) or asks for a delete that has not
        // been started yet.
        if (pass.isBlockedByPermanentFailure()) {
            return ReconcileOutcome.Failed(
                pass.previous?.failure?.message ?: "a permanent failure is recorded on observed status",
            )
        }

        return try {
            when (val placement = place(pass)) {
                is Placement.Refused -> {
                    refusePlacement(pass, placement)
                }

                is Placement.On -> {
                    val observation = placement.node.observe(pass.name)
                    val cause = placement.cause ?: drainCause(pass, observation)
                    if (cause == null) {
                        converge(pass, placement.node, observation)
                    } else {
                        drain(pass, placement.node, observation, cause)
                    }
                }
            }
        } catch (failure: NodeException) {
            nodeFailure(pass, failure)
        } catch (failure: StoreException) {
            storeOutcome(pass.name, failure)
        }
    }

    // ── placement ────────────────────────────────────────────────────────────

    /**
     * Asks the scheduler where this server belongs. Every pass, without
     * exception — a loop that only schedules on creation has hard-coded the
     * placement it happened to get the first time.
     *
     * When the node it is currently on is not the node it belongs on, the
     * answer carries [DrainCause.RELOCATION] and the *source* node: the old
     * container is drained before anything starts elsewhere. Starting the
     * replacement first is `failure-modes.md` item 5, and it is no less wrong
     * across two nodes than on one.
     */
    private suspend fun place(pass: Pass): Placement {
        val currentNode = pass.previous?.runtime?.node
        val spec = pass.definition.spec
        val decision =
            scheduler.schedule(
                PlacementRequest(
                    server = pass.name,
                    pin = spec.placement.node,
                    currentNode = currentNode,
                    demand =
                        PlacementDemand(
                            maxPlayers = spec.maxPlayers,
                            memoryBytes = spec.resources.memory.bytes,
                            cpuMillicores = spec.resources.cpu?.millicores,
                            persistentVolume = (spec.storage as? StorageSpec.Persistent)?.volume?.name,
                        ),
                ),
            )

        return when (decision) {
            is PlacementDecision.Unschedulable -> {
                Placement.Refused(decision.problem, decision.message)
            }

            is PlacementDecision.Scheduled -> {
                if (currentNode != null && currentNode != decision.node) {
                    val source = registry.node(currentNode)
                    if (source != null) return Placement.On(source, DrainCause.RELOCATION)
                    LOG.warn(
                        "server={} was last seen on node={}, which is no longer registered",
                        pass.name,
                        currentNode,
                    )
                }
                val node =
                    registry.node(decision.node)
                        ?: return Placement.Refused(
                            null,
                            "the scheduler chose node `${decision.node}`, which is not registered",
                        )
                Placement.On(node, null)
            }
        }
    }

    private suspend fun refusePlacement(
        pass: Pass,
        refusal: Placement.Refused,
    ): ReconcileOutcome {
        val permanent = refusal.problem == PlacementProblem.PINNED_NODE_UNKNOWN
        val failure =
            recordFailure(
                reason = FailureReason.NODE_UNAVAILABLE,
                failureClass = if (permanent) FailureClass.PERMANENT else FailureClass.RETRYABLE,
                message = refusal.message,
                now = pass.now,
                previous = pass.previous?.failure,
            )
        val status =
            pass.draft(
                phase = if (pass.previous?.runtime == null) ServerPhase.PENDING else ServerPhase.UNKNOWN,
                failure = failure,
            )
        return write(pass, status) {
            if (permanent) ReconcileOutcome.Failed(refusal.message) else ReconcileOutcome.Retry(refusal.message)
        }
    }

    private sealed interface Placement {
        data class On(
            val node: Node,
            val cause: DrainCause?,
        ) : Placement

        data class Refused(
            val problem: PlacementProblem?,
            val message: String,
        ) : Placement
    }

    // ── bring-up ─────────────────────────────────────────────────────────────

    /**
     * Moves an undrained server one step toward running and joinable.
     *
     * The steps are ordered the way the runtime requires — image, then
     * workload, then start, then readiness — and exactly one of them happens
     * per pass.
     */
    private suspend fun converge(
        pass: Pass,
        node: Node,
        observation: WorkloadObservation,
    ): ReconcileOutcome {
        val storage = pass.storageStatus(observation)
        val image = ensureImage(pass, node, observation)

        return when (observation) {
            WorkloadObservation.Absent -> {
                val created = node.ensureWorkload(pass.desired)
                LOG.info(
                    "created workload for server={} node={} sandbox={} container={}",
                    pass.name,
                    node.name,
                    created.handle.sandboxId,
                    created.handle.containerId,
                )
                val status =
                    pass.draft(
                        phase = ServerPhase.CREATING,
                        image = image,
                        runtime = pass.runtimeIdentity(created),
                        storage = storage,
                        drain = null,
                    )
                write(pass, status) { ReconcileOutcome.Progressed("workload created") }
            }

            is WorkloadObservation.Present -> {
                fun status(
                    phase: ServerPhase,
                    failure: FailureStatus? = null,
                ) = pass.draft(
                    phase = phase,
                    image = image,
                    runtime = pass.runtimeIdentity(observation),
                    storage = storage,
                    drain = null,
                    failure = failure,
                )

                when (observation.state) {
                    // The sandbox is there and the container is not. Adopting
                    // the sandbox and creating the container into it is exactly
                    // what `ensureWorkload` does — it is never a second create.
                    WorkloadState.SANDBOX_ONLY -> {
                        node.ensureWorkload(pass.desired)
                        write(pass, status(ServerPhase.CREATING)) {
                            ReconcileOutcome.Progressed("container created in the existing sandbox")
                        }
                    }

                    WorkloadState.CREATED -> {
                        node.startWorkload(observation.handle)
                        write(pass, status(ServerPhase.STARTING)) {
                            ReconcileOutcome.Progressed("container started")
                        }
                    }

                    WorkloadState.RUNNING -> {
                        awaitJoinable(pass, node, observation, image, storage)
                    }

                    // Nothing restarts this. There is no restart policy in the
                    // schema, and inventing one here would have the loop
                    // recreating a crash-looping server forever without an
                    // operator being told. Recorded as permanent so it surfaces
                    // and stays surfaced.
                    WorkloadState.EXITED -> {
                        val detail = observation.reason.ifBlank { observation.message }
                        val message =
                            "the container exited with code ${observation.exitCode ?: "unknown"}" +
                                if (detail.isBlank()) "" else " ($detail)"
                        val failure =
                            recordFailure(
                                reason = FailureReason.CONTAINER_EXITED,
                                failureClass = FailureClass.PERMANENT,
                                message = message,
                                now = pass.now,
                                previous = pass.previous?.failure,
                            )
                        write(pass, status(ServerPhase.STOPPED, failure)) { ReconcileOutcome.Failed(message) }
                    }

                    WorkloadState.UNKNOWN -> {
                        write(pass, status(ServerPhase.UNKNOWN)) {
                            ReconcileOutcome.Waiting(
                                "the runtime did not report a usable container state",
                                config.containerPollInterval,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * A running container is not a ready server.
     *
     * Readiness is a Server List Ping — the same handshake a player's client
     * makes — so `ready` means joinable rather than "the process is up". A
     * Paper server is `RUNNING` throughout world generation and stays `RUNNING`
     * while deadlocked.
     */
    private suspend fun awaitJoinable(
        pass: Pass,
        node: Node,
        observation: WorkloadObservation.Present,
        image: ImageStatus?,
        storage: StorageStatus,
    ): ReconcileOutcome {
        val probe = pass.agent.probe(node, observation.handle)
        val endpoint =
            ServerEndpoint(
                node = node.name,
                address = node.name.value,
                port = pass.definition.spec.network.hostPort ?: pass.definition.spec.network.port,
            )

        fun status(
            phase: ServerPhase,
            ready: Boolean,
            players: PlayerOccupancy?,
            failure: FailureStatus? = null,
        ) = pass.draft(
            phase = phase,
            ready = ready,
            image = image,
            runtime = pass.runtimeIdentity(observation),
            endpoint = endpoint,
            players = players,
            storage = storage,
            // A drain that had aborted and then became unnecessary — the last
            // player left, the node came back — leaves a stale record behind.
            // Reaching a joinable server means it is over.
            drain = null,
            failure = failure,
        )

        return when (probe) {
            is ProbeOutcome.Joinable -> {
                val players = PlayerOccupancy(online = probe.online, max = probe.max, observedAt = pass.now)
                write(pass, status(ServerPhase.RUNNING, ready = true, players = players)) {
                    ReconcileOutcome.Settled("running and joinable")
                }
            }

            is ProbeOutcome.NotJoinable -> {
                val startedAt = observation.startedAt ?: pass.previous?.lastTransitionAt ?: pass.now
                val waited = JavaDuration.between(startedAt, pass.now).toKotlinDuration()
                if (waited <= pass.definition.spec.lifecycle.startupTimeout) {
                    write(pass, status(ServerPhase.STARTING, ready = false, players = null)) {
                        ReconcileOutcome.Waiting("not joinable yet: ${probe.detail}", config.readinessPollInterval)
                    }
                } else {
                    // Deliberately retryable, and deliberately not a restart. A
                    // restart is a stop path, a stop path goes through a drain,
                    // and a drain cannot confirm zero players on a server that
                    // is not answering. So this surfaces and keeps watching.
                    val message =
                        "not joinable ${waited.inWholeSeconds}s after start, past the " +
                            "${pass.definition.spec.lifecycle.startupTimeout.inWholeSeconds}s startup timeout: " +
                            probe.detail
                    val failure =
                        recordFailure(
                            reason = FailureReason.READINESS_TIMEOUT,
                            failureClass = FailureClass.RETRYABLE,
                            message = message,
                            now = pass.now,
                            previous = pass.previous?.failure,
                        )
                    write(pass, status(ServerPhase.STARTING, ready = false, players = null, failure = failure)) {
                        ReconcileOutcome.Retry(message)
                    }
                }
            }

            is ProbeOutcome.Unavailable -> {
                val failure =
                    recordFailure(
                        reason = FailureReason.RUNTIME_UNREACHABLE,
                        failureClass = if (probe.retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT,
                        message = probe.detail,
                        now = pass.now,
                        previous = pass.previous?.failure,
                    )
                write(pass, status(ServerPhase.UNKNOWN, ready = false, players = null, failure = failure)) {
                    if (probe.retryable) {
                        ReconcileOutcome.Retry(probe.detail)
                    } else {
                        ReconcileOutcome.Failed(probe.detail)
                    }
                }
            }
        }
    }

    /**
     * Makes the image available, and skips even asking once a container exists
     * that is running from it.
     *
     * Skipping the *pull* is the node's contract. Skipping the round trip is
     * this loop's: an image cannot become unavailable to a container that is
     * already running from it, so a settled server does not talk to the image
     * service at all.
     */
    private suspend fun ensureImage(
        pass: Pass,
        node: Node,
        observation: WorkloadObservation,
    ): ImageStatus {
        val recorded = pass.previous?.image
        val settled =
            observation is WorkloadObservation.Present &&
                observation.state != WorkloadState.SANDBOX_ONLY
        if (settled && recorded != null && recorded.available && recorded.requested == pass.definition.spec.image) {
            return recorded
        }
        val availability = node.ensureImage(pass.definition.spec.image)
        if (availability.pulled) {
            LOG.info(
                "pulled image for server={} node={} image={}",
                pass.name,
                node.name,
                pass.definition.spec.image.canonical,
            )
        }
        return ImageStatus(
            requested = pass.definition.spec.image,
            resolvedDigest = availability.id,
            pulledAt = if (availability.pulled) pass.now else recorded?.pulledAt ?: pass.now,
        )
    }

    // ── drain and teardown ───────────────────────────────────────────────────

    /**
     * Whether the running workload no longer matches what is declared.
     *
     * A difference here is a *recreate*, and a recreate drains first. Nothing
     * in this function stops anything; it only names the cause.
     */
    private fun drainCause(
        pass: Pass,
        observation: WorkloadObservation,
    ): DrainCause? {
        if (pass.stored.definition.terminating) return DrainCause.DELETION
        val present = observation as? WorkloadObservation.Present ?: return null
        val actual = present.specHash ?: return null
        return if (actual == pass.desired.specHash) null else DrainCause.REPLACEMENT
    }

    /**
     * Every path that stops, replaces, relocates or deletes a server comes
     * through here, and this is the only place that can reach a
     * [DrainController].
     */
    private suspend fun drain(
        pass: Pass,
        node: Node,
        observation: WorkloadObservation,
        cause: DrainCause,
    ): ReconcileOutcome {
        val progress =
            drainController.advance(
                definition = pass.definition,
                agent = pass.agent,
                node = node,
                observation = observation,
                current = pass.previous?.drain,
                cause = cause,
            )
        val storage =
            pass.storageStatus(observation).let { base ->
                progress.saveConfirmedAt?.let { base.copy(lastSaveConfirmedAt = it) } ?: base
            }
        val phase =
            when {
                progress.containerDown -> ServerPhase.STOPPED

                // A failed drain leaves the server running, and says so. It is
                // not a phase on the way to STOPPED.
                progress.drain.state == DrainState.DRAIN_FAILED -> ServerPhase.RUNNING

                progress.drain.state == DrainState.STOPPING -> ServerPhase.STOPPING

                else -> ServerPhase.DRAINING
            }
        val status =
            pass.draft(
                phase = phase,
                // A draining standalone server stays joinable until it stops —
                // there is no proxy to seal it — so readiness follows the probe
                // rather than the drain.
                ready = progress.occupancy != null && phase == ServerPhase.RUNNING,
                runtime =
                    (observation as? WorkloadObservation.Present)
                        ?.let { pass.runtimeIdentity(it) }
                        ?: pass.previous?.runtime,
                players = progress.occupancy ?: pass.previous?.players,
                storage = storage,
                drain = progress.drain,
                failure = progress.drain.failure,
            )

        if (!progress.containerDown) {
            return write(pass, status) { progress.outcome }
        }
        return teardown(pass, node, observation, status, cause)
    }

    /**
     * Removes what is left of a stopped server, and completes a delete.
     *
     * Two guards, both here because the store cannot see a container and the
     * node cannot see a definition:
     *
     * - A workload is removed only once the drain has observed the container
     *   down. [Node.removeWorkload] refuses a running container anyway; the
     *   loop does not lean on that.
     * - A definition is purged only on a pass where the node reported
     *   [WorkloadObservation.Absent]. The store deliberately does not guard
     *   this, because it never sees a container — so the check lives where the
     *   observation does. Purging early would throw away the record of which
     *   side effects have been issued and leave a container running with
     *   nothing describing it.
     *
     * Persistent volumes are untouched throughout. That is the entire point of
     * a volume that outlives its container (CLAUDE.md invariant 2).
     */
    private suspend fun teardown(
        pass: Pass,
        node: Node,
        observation: WorkloadObservation,
        status: PaperServerStatus,
        cause: DrainCause,
    ): ReconcileOutcome {
        if (observation is WorkloadObservation.Present) {
            node.removeWorkload(observation.handle)
            LOG.info(
                "removed workload for server={} node={}; persistent storage is untouched",
                pass.name,
                node.name,
            )
            return write(pass, status) { ReconcileOutcome.Progressed("workload removed") }
        }

        if (pass.stored.definition.terminating) {
            // Record the final observation before the row goes away, so a
            // failure between here and the purge leaves an accurate picture.
            val verdict = writeStatus(pass, status)
            if (verdict is WriteVerdict.Conflicted) return verdict.outcome
            val outcome = store.purge(pass.name, Precondition.AtVersion(pass.stored.definition.resourceVersion))
            return when (outcome) {
                is WriteOutcome.Applied -> {
                    LOG.info("purged server={}: its workload is gone", pass.name)
                    ReconcileOutcome.Progressed("definition purged")
                }

                is WriteOutcome.Conflict -> {
                    ReconcileOutcome.Retry("the purge conflicted (${outcome.reason}); re-reading")
                }
            }
        }

        // A replacement or a relocation. The old workload is gone, so the drain
        // record goes with it and the next pass creates the new one from
        // scratch — never alongside the old one. Redrafted rather than copied,
        // so the derived conditions do not keep describing a drain that is over.
        val cleared =
            pass.draft(
                phase = status.phase,
                image = status.image,
                runtime = null,
                endpoint = null,
                players = null,
                storage = status.storage,
                drain = null,
            )
        return write(pass, cleared) {
            ReconcileOutcome.Progressed("the old workload is gone; ${cause.detail} is applied next pass")
        }
    }

    // ── status writing ───────────────────────────────────────────────────────

    private suspend inline fun write(
        pass: Pass,
        status: PaperServerStatus,
        outcome: () -> ReconcileOutcome,
    ): ReconcileOutcome =
        when (val verdict = writeStatus(pass, status)) {
            is WriteVerdict.Conflicted -> verdict.outcome
            WriteVerdict.Written, WriteVerdict.Unchanged -> outcome()
        }

    /**
     * Records an observation, unless it says exactly what the stored one
     * already says.
     *
     * `observedAt` moves every pass by definition, so comparing whole statuses
     * would never match and a settled server would generate a store write per
     * pass forever. It is compared modulo that field instead — and refreshed
     * anyway once every [ReconcilerConfig.statusHeartbeat], because a status
     * that has stopped advancing must mean the loop has died rather than that
     * nothing is happening.
     */
    private suspend fun writeStatus(
        pass: Pass,
        status: PaperServerStatus,
    ): WriteVerdict {
        val recorded = pass.stored.status
        val previous = recorded?.status as? PaperServerStatus
        if (previous != null && status.copy(observedAt = previous.observedAt) == previous) {
            val since = JavaDuration.between(recorded.recordedAt, pass.now).toKotlinDuration()
            if (since < config.statusHeartbeat) return WriteVerdict.Unchanged
        }

        // `observedDefinition` is the anti-lost-update guard. If the API server
        // replaced the definition while this pass ran, this observation
        // describes a spec nobody wants any more, and recording it would make
        // the server look settled at a generation nobody asked for.
        val outcome =
            store.putStatus(
                status = status,
                observedDefinition = pass.stored.definition.resourceVersion,
            )
        return when (outcome) {
            is WriteOutcome.Applied -> {
                WriteVerdict.Written
            }

            is WriteOutcome.Conflict -> {
                when (outcome.reason) {
                    ConflictReason.NOT_FOUND -> {
                        WriteVerdict.Conflicted(
                            ReconcileOutcome.Settled("the definition was purged while this pass ran"),
                        )
                    }

                    else -> {
                        WriteVerdict.Conflicted(
                            ReconcileOutcome.Retry("the observation was rejected (${outcome.reason}); re-reading"),
                        )
                    }
                }
            }
        }
    }

    private sealed interface WriteVerdict {
        data object Written : WriteVerdict

        data object Unchanged : WriteVerdict

        data class Conflicted(
            val outcome: ReconcileOutcome,
        ) : WriteVerdict
    }

    // ── failure paths ────────────────────────────────────────────────────────

    private suspend fun nodeFailure(
        pass: Pass,
        failure: NodeException,
    ): ReconcileOutcome {
        val recorded =
            recordFailure(
                reason = failure.asFailureReason(),
                failureClass = failure.asFailureClass(),
                message = failure.message,
                now = pass.now,
                previous = pass.previous?.failure,
            )
        LOG.warn("node operation failed for server={}: {}", pass.name, failure.message)
        val status =
            pass.draft(
                phase = if (pass.previous?.runtime == null) ServerPhase.PENDING else ServerPhase.UNKNOWN,
                failure = recorded,
            )
        return write(pass, status) {
            if (failure.retryable) {
                ReconcileOutcome.Retry(failure.message)
            } else {
                ReconcileOutcome.Failed(failure.message)
            }
        }
    }

    /**
     * A store failure cannot be recorded on observed status — the store is the
     * thing that failed — so it is logged and classified rather than swallowed.
     */
    private fun storeOutcome(
        name: ResourceName,
        failure: StoreException,
    ): ReconcileOutcome {
        LOG.warn("store operation failed for server={}: {}", name, failure.message)
        return if (failure.retryable) {
            ReconcileOutcome.Retry("the store is unavailable: ${failure.message}")
        } else {
            ReconcileOutcome.Failed("the store rejected this pass: ${failure.message}")
        }
    }

    /**
     * Everything one pass needs, read once.
     *
     * A pass has a single [now]: two timestamps taken a few milliseconds apart
     * within one observation would make an idempotent pass look like a changed
     * one.
     */
    private inner class Pass(
        val stored: StoredServer,
        val definition: PaperServerDefinition,
        val now: Instant,
    ) {
        val name: ResourceName = definition.metadata.name
        val previous: PaperServerStatus? = stored.status?.status as? PaperServerStatus
        val agent: PaperServerAgent = PaperServerAgent(definition)
        val desired: WorkloadSpec = PaperWorkloadPlanner.plan(definition)

        fun isBlockedByPermanentFailure(): Boolean {
            val failed =
                previous != null &&
                    previous.observedGeneration == stored.definition.generation &&
                    previous.failure?.failureClass == FailureClass.PERMANENT
            // A delete request is a human intervening, so it lifts the gate —
            // but only until the drain it asks for has itself failed
            // permanently, which is a state that needs a human again.
            val deleteNotStarted = stored.definition.terminating && previous?.drain == null
            return failed && !deleteNotStarted
        }

        @Suppress("LongParameterList")
        fun draft(
            phase: ServerPhase,
            ready: Boolean = false,
            image: ImageStatus? = previous?.image,
            runtime: RuntimeIdentity? = previous?.runtime,
            endpoint: ServerEndpoint? = previous?.endpoint,
            players: PlayerOccupancy? = previous?.players,
            storage: StorageStatus? = previous?.storage,
            drain: mcorch.schema.DrainStatus? = previous?.drain,
            failure: FailureStatus? = null,
        ): PaperServerStatus =
            draftStatus(
                previous = previous,
                name = name,
                generation = stored.definition.generation,
                now = now,
                phase = phase,
                ready = ready,
                image = image,
                runtime = runtime,
                endpoint = endpoint,
                players = players,
                storage = storage,
                drain = drain,
                failure = failure,
            )

        fun storageStatus(observation: WorkloadObservation): StorageStatus {
            val storage = definition.spec.storage
            return StorageStatus(
                persistent = storage is StorageSpec.Persistent,
                volumeName = (storage as? StorageSpec.Persistent)?.volume?.name,
                bound = observation is WorkloadObservation.Present,
                lastSaveConfirmedAt = previous?.storage?.lastSaveConfirmedAt,
            )
        }

        fun runtimeIdentity(observation: WorkloadObservation.Present): RuntimeIdentity =
            RuntimeIdentity(
                node = observation.handle.node,
                sandboxId = observation.handle.sandboxId,
                containerId = observation.handle.containerId,
                createdAt = observation.createdAt,
                startedAt = observation.startedAt,
                finishedAt = observation.finishedAt,
                exitCode = observation.exitCode,
                restartCount = previous?.runtime?.restartCount ?: 0,
            )
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(Reconciler::class.java)
    }
}

/**
 * Timings the loop uses when it has nothing better to go on.
 *
 * None of these is a backoff: a backoff is for something that failed, and these
 * are for something that is simply still happening.
 */
public data class ReconcilerConfig(
    /** How often a settled server's observation is refreshed even though nothing changed. */
    val statusHeartbeat: Duration = 60.seconds,
    /** How often to re-ping a server that is starting up. */
    val readinessPollInterval: Duration = 5.seconds,
    /** How often to re-read a container state the runtime could not report. */
    val containerPollInterval: Duration = 2.seconds,
) {
    init {
        require(statusHeartbeat.isPositive()) { "statusHeartbeat must be positive" }
        require(readinessPollInterval.isPositive()) { "readinessPollInterval must be positive" }
        require(containerPollInterval.isPositive()) { "containerPollInterval must be positive" }
    }
}

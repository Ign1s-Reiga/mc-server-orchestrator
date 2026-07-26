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
import kotlin.time.Duration.Companion.minutes
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
    private val drainController =
        DrainController(
            clock = clock,
            evidenceGap = config.saveEvidenceMaxGap,
            attentionAfter = config.drainAttentionAfter,
        )

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
            is PaperServerDefinition -> reconcilePaper(stored, definition)
        }
    }

    private suspend fun reconcilePaper(
        stored: StoredServer,
        definition: PaperServerDefinition,
    ): ReconcileOutcome {
        val now = clock.instant()

        // Deriving the workload can reject the definition — the workload types
        // enforce their own invariants — and that has to become an observation
        // rather than an exception thrown out of a pass. Built here, inside a
        // guard, rather than at the call site, because an escape from this
        // function cancels the worker that called it and every other worker with
        // it.
        val pass =
            try {
                Pass(stored, definition, now)
            } catch (rejected: IllegalArgumentException) {
                return rejectDefinition(stored, now, rejected)
            } catch (rejected: IllegalStateException) {
                return rejectDefinition(stored, now, rejected)
            }

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

    /**
     * Records a definition that cannot be turned into a workload at all.
     *
     * Permanent by construction: nothing the loop does will make the same
     * definition derivable next pass, so it surfaces and waits for an edit. It
     * writes the observation without a [Pass], because building one is what
     * failed.
     */
    private suspend fun rejectDefinition(
        stored: StoredServer,
        now: Instant,
        failure: RuntimeException,
    ): ReconcileOutcome {
        val message = "the definition cannot be turned into a workload: ${failure.message}"
        LOG.warn("server={} was rejected: {}", stored.name, message)
        val previous = stored.status?.status as? PaperServerStatus
        val status =
            draftStatus(
                previous = previous,
                name = stored.name,
                generation = stored.definition.generation,
                now = now,
                phase = ServerPhase.FAILED,
                failure =
                    recordFailure(
                        reason = FailureReason.CONTAINER_CREATE_FAILED,
                        failureClass = FailureClass.PERMANENT,
                        message = message,
                        now = now,
                        previous = previous?.failure,
                    ),
            )
        return try {
            store.putStatus(status, observedDefinition = stored.definition.resourceVersion)
            ReconcileOutcome.Failed(message)
        } catch (storeFailure: StoreException) {
            storeOutcome(stored.name, storeFailure)
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
                    // The workload is on a node this orchestrator can no longer
                    // talk to. It is not gone — an unreachable node is not a
                    // stopped container — so scheduling elsewhere would leave
                    // two live workloads for one server, which is
                    // `failure-modes.md` item 5 stretched across two machines.
                    // Nobody can drain the old one from here, so this is a
                    // human's problem and it says so.
                    LOG.warn(
                        "server={} was last seen on node={}, which is no longer registered",
                        pass.name,
                        currentNode,
                    )
                    return Placement.Refused(
                        null,
                        "this server's workload was last observed on node `$currentNode`, which is no longer " +
                            "registered. It is not being scheduled onto `${decision.node}`: that would run a " +
                            "second copy while the first may still have players on it. Bring `$currentNode` " +
                            "back so it can be drained, or confirm its workload is gone and clear this " +
                            "server's recorded placement",
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
                        // The returned observation, not the one this pass
                        // started from: it carries the container that was just
                        // created, and recording the sandbox-only one instead
                        // would leave `containerId` null for a container that
                        // exists and cost a pass rediscovering it.
                        val created = node.ensureWorkload(pass.desired)
                        val drafted =
                            pass.draft(
                                phase = ServerPhase.CREATING,
                                image = image,
                                runtime = pass.runtimeIdentity(created),
                                storage = storage,
                                drain = null,
                            )
                        write(pass, drafted) {
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
        forbiddenTransition(pass, observation, cause)?.let { return it }
        val progress =
            drainController.advance(
                definition = pass.definition,
                agent = pass.agent,
                node = node,
                observation = observation,
                current = pass.previous?.drain,
                cause = cause,
                // When a probe last answered. Occupancy is only ever recorded
                // from a probe that did, so this stops advancing the moment the
                // server stops answering — which is exactly when the chain of
                // zero-player observations behind a save confirmation breaks.
                // `observedAt` would not do: a pass that never reached the node
                // also writes one.
                lastProbedAt = pass.previous?.players?.observedAt,
                // Whether a container has ever been seen for this server, which
                // is what separates "never created" from "the runtime did not
                // tell us about it".
                hadContainer = pass.previous?.runtime?.containerId != null,
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
            return write(pass, status, mustRecord = progress.sideEffectIssued) { progress.outcome }
        }
        return teardown(pass, node, observation, status, cause)
    }

    /**
     * Definition edits that must not be applied by draining the running
     * container, because the drain would be conducted under rules the container
     * never ran under.
     *
     * There is one today: `storage.mode` from `persistent` to `ephemeral`. It is
     * in the spec hash, so the edit asks for a recreate, and the recreate drains
     * the container that is holding the world right now. Everything downstream
     * of that drain would read the *new* spec and conclude there is nothing to
     * flush.
     *
     * The drain controller does not conclude that any more — it reads the
     * container's own labels — so this is the second of two guards rather than
     * the only one. It is here because it is the one place stored and desired
     * state are both in hand, and because refusing the edit outright is a better
     * answer than performing it correctly: an operator who wanted the world gone
     * would delete the server, and an operator who wanted a lobby would create
     * one.
     *
     * A delete is never refused. Whatever else is true, an operator who asked
     * for a server to go away must be able to have it drained.
     */
    private suspend fun forbiddenTransition(
        pass: Pass,
        observation: WorkloadObservation,
        cause: DrainCause,
    ): ReconcileOutcome? {
        if (cause != DrainCause.REPLACEMENT) return null
        val present = observation as? WorkloadObservation.Present ?: return null
        if (present.state != WorkloadState.RUNNING) return null
        // Absent means the workload predates the label, which is not the same as
        // "it holds no world data" — and guessing either way from an edited
        // definition is exactly the mistake being guarded against.
        // Only a workload that positively says it holds a world. Unknown is
        // deliberately *not* refused here, and the two guards differ on purpose
        // because they answer different questions. This one asks "is this edit a
        // transition away from persistent storage" — and on a workload carrying
        // no label there is no way to tell a transition from a lobby that has
        // always been ephemeral, so refusing would make every replacement of
        // such a lobby a permanent, unclearable failure advising an edit that
        // does not apply to it. The drain asks the other question, "might this
        // container hold a world", and answers unknown with yes: it demands a
        // confirmed save before any stop. The edit still gets applied; nothing
        // gets discarded to apply it.
        val heldWorldData = Labels.booleanValue(present.labels, Labels.WORLD_DATA) ?: return null
        if (!heldWorldData) return null
        if (pass.definition.spec.storage !is StorageSpec.Ephemeral) return null

        val message =
            "refusing to change storage.mode to `ephemeral` on a running server: the container now running was " +
                "created with persistent world data, and applying this edit means draining and replacing it. " +
                "Whatever is in memory would be discarded rather than flushed. Revert spec.storage.mode; to " +
                "retire this server instead, delete it — that drains and saves it first"
        LOG.warn("server={} refused a storage mode change: {}", pass.name, message)
        val failure =
            recordFailure(
                // The drain that would apply this edit is refused before it
                // starts. There is no reason code for "this transition is not
                // allowed" in the closed set; this is the nearest true one.
                reason = FailureReason.DRAIN_STALLED,
                failureClass = FailureClass.PERMANENT,
                message = message,
                now = pass.now,
                previous = pass.previous?.failure,
            )
        val status =
            pass.draft(
                phase = ServerPhase.RUNNING,
                runtime = pass.runtimeIdentity(present),
                storage = pass.storageStatus(observation),
                drain = null,
                failure = failure,
            )
        return write(pass, status) { ReconcileOutcome.Failed(message) }
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
            val removal = node.removeWorkload(observation.handle)
            if (!removal.complete) {
                // The container is gone and the sandbox is not. Recording the
                // first half is not bookkeeping — it is what lets the next pass
                // tell "this loop removed the container" from "the runtime has
                // stopped reporting a container that may still be serving
                // players". Without it the drain refuses to touch the sandbox
                // again and the delete never completes.
                LOG.warn(
                    "workload for server={} on node={} is partly removed: {}",
                    pass.name,
                    node.name,
                    removal.detail,
                )
                val partial =
                    status.copy(
                        runtime = status.runtime?.copy(containerId = null),
                    )
                return write(pass, partial) { ReconcileOutcome.Retry(removal.detail) }
            }
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

    /**
     * Records [status] and turns the verdict into an outcome.
     *
     * [mustRecord] is for a pass that has already done something to the server
     * which the runtime cannot be asked about afterwards. The guarded write can
     * legitimately be rejected — the operator replaced the definition while the
     * pass ran — and dropping the observation is the right answer for
     * *observations*. It is the wrong answer for the record of a save request:
     * that record is the only thing stopping the next pass sending a second one
     * to a live server, and it must outlive a rejected write.
     */
    private suspend inline fun write(
        pass: Pass,
        status: PaperServerStatus,
        mustRecord: Boolean = false,
        outcome: () -> ReconcileOutcome,
    ): ReconcileOutcome =
        when (val verdict = writeStatus(pass, status)) {
            is WriteVerdict.Conflicted -> {
                if (mustRecord) forceRecord(pass, status)
                verdict.outcome
            }

            WriteVerdict.Written, WriteVerdict.Unchanged -> {
                outcome()
            }
        }

    /**
     * Writes an observation without the anti-lost-update guard, after that guard
     * has already rejected it.
     *
     * The guard exists so a server cannot *look settled* at a generation nobody
     * asked for. What is being written here does not look settled — it is a
     * drain part-way through, carrying the fact that a save request went out —
     * and the next pass re-reads the new definition and moves the observation on
     * anyway. Losing the record instead would cost a second save on a live
     * server, so this is the lesser of the two.
     */
    private suspend fun forceRecord(
        pass: Pass,
        status: PaperServerStatus,
    ) {
        LOG.warn(
            "server={} had its observation rejected after a side effect was issued; recording it unguarded so " +
                "the side effect is not repeated",
            pass.name,
        )
        when (val outcome = store.putStatus(status)) {
            is WriteOutcome.Applied -> {
                Unit
            }

            is WriteOutcome.Conflict -> {
                LOG.warn(
                    "server={} could not record an issued side effect ({}); the next pass may repeat it",
                    pass.name,
                    outcome.reason,
                )
            }
        }
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
            // A delete request is a human intervening, and it lifts the gate for
            // as long as the delete is outstanding — not just until the drain
            // starts.
            //
            // A permanently stalled drain is the case that matters. The server
            // keeps running, the operator is told to save and stop it by hand,
            // and the loop has to be able to *notice* that they did. A gate that
            // returns before the pass observes anything can never notice, so the
            // container would sit stopped with its definition tombstoned for
            // ever and the advice on its status would be a lie. Re-entering
            // costs one observation per resync and issues nothing: a drain that
            // has already recorded a delivered save does not re-send it, and one
            // with no save channel aborts again without touching the server.
            return failed && !stored.definition.terminating
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
                // A container this loop has seen is not un-seen by a pass that
                // does not see it. The runtime can stop reporting a container
                // that is still running — the field a sandbox status carries
                // them in is optional — and the drain uses "has this server ever
                // had a container" to tell that apart from one that was never
                // created. Letting a single unreported pass clear the record
                // would hand the drain the wrong answer on the next one.
                // Teardown clears it explicitly instead.
                containerId = observation.handle.containerId ?: previous?.runtime?.containerId,
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
    /**
     * How long a gap between two recorded observations voids a drain's world
     * save.
     *
     * The one timing here that is a safety property rather than a cadence. A
     * confirmed save says the world was on disk at that instant; it stays
     * evidence only while the loop keeps watching, because a player can join,
     * play and leave between two passes without either of them noticing. Must
     * comfortably exceed the interval between passes of a drain that is making
     * progress; shorter than a session anybody would mind losing. Erring short
     * costs an extra `save-all flush` on an empty server.
     */
    val saveEvidenceMaxGap: Duration = 30.seconds,
    /**
     * How long a drain may keep failing before it is *reported* as needing a
     * human.
     *
     * Changes nothing about what happens to the container: it keeps running and
     * the loop keeps retrying, which is what `failure-modes.md` item 7 requires.
     */
    val drainAttentionAfter: Duration = 15.minutes,
) {
    init {
        require(statusHeartbeat.isPositive()) { "statusHeartbeat must be positive" }
        require(readinessPollInterval.isPositive()) { "readinessPollInterval must be positive" }
        require(containerPollInterval.isPositive()) { "containerPollInterval must be positive" }
        require(saveEvidenceMaxGap.isPositive()) { "saveEvidenceMaxGap must be positive" }
        require(drainAttentionAfter.isPositive()) { "drainAttentionAfter must be positive" }
    }
}

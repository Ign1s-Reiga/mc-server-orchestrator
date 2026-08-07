package mcorch.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.PaperWorkloadPlanner
import mcorch.core.paper.ProbeOutcome
import mcorch.core.proxy.ControlChannel
import mcorch.core.proxy.ControlOutcome
import mcorch.core.proxy.ProxySelfLink
import mcorch.core.proxy.VelocityProxyAgent
import mcorch.core.proxy.VelocityWorkloadPlanner
import mcorch.schema.BackendRegistration
import mcorch.schema.BackendRoutingStatus
import mcorch.schema.BackendStatus
import mcorch.schema.ControlEndpointStatus
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
import mcorch.schema.SecretRef
import mcorch.schema.ServerEndpoint
import mcorch.schema.ServerPhase
import mcorch.schema.StorageSpec
import mcorch.schema.StorageStatus
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxyStatus
import mcorch.store.ConflictReason
import mcorch.store.Precondition
import mcorch.store.ServerListing
import mcorch.store.Store
import mcorch.store.StoreException
import mcorch.store.StoredServer
import mcorch.store.WriteOutcome
import mcorch.velocity.control.ControlErrorCode
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
            is PaperServerDefinition -> {
                reconcilePaper(stored, definition)
            }

            is VelocityProxyDefinition -> {
                reconcileProxy(stored, definition)
            }
        }
    }

    private suspend fun reconcilePaper(
        stored: StoredServer,
        definition: PaperServerDefinition,
    ): ReconcileOutcome {
        val now = clock.instant()

        // Which proxy, if any, fronts this server. A fleet-level read, because the
        // reference points proxy → backend: nothing in *this* definition says it is
        // behind a proxy, and that is what keeps a backend portable and keeps the
        // forwarding secret out of it.
        val fleet =
            try {
                ProxyFleet.resolve(store, stored)
            } catch (failure: StoreException) {
                return storeOutcome(stored.name, failure)
            }
        // A conflict refuses the *create*, never the *delete*. An operator who asked
        // for a server to go away must be able to have it drained, and a drain
        // issues no create — so the forwarding-secret ambiguity that makes the
        // conflict unresolvable simply does not apply to it. Without this exemption a
        // backend that two selectors start matching becomes permanently undeletable,
        // with both proxies routing to it, until a human narrows a selector; and an
        // undeletable populated server is what produces a manual `crictl stop`.
        //
        // It drains with `binding = null`, so it blocks on players rather than
        // transferring them. That is the correct degradation: choosing one of the two
        // proxies to seal through would leave the other routing new players in.
        if (fleet is ProxyFleet.Resolution.Conflicted && !stored.definition.terminating) {
            return refuseConflictedProxies(stored, now, fleet)
        }
        val binding = (fleet as? ProxyFleet.Resolution.Behind)?.binding

        // Deriving the workload can reject the definition — the workload types
        // enforce their own invariants — and that has to become an observation
        // rather than an exception thrown out of a pass. Built here, inside a
        // guard, rather than at the call site, because an escape from this
        // function cancels the worker that called it and every other worker with
        // it.
        val pass =
            try {
                Pass(stored, definition, now, binding?.forwardingSecret)
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
                    val cause =
                        placement.cause
                            ?: drainCause(pass, observation)
                            // Nothing wants a drain any more, and one has already
                            // signalled this container. It finishes rather than
                            // converging back over the top of its own stop — see
                            // [outstandingStopCause] for what that costs a player.
                            ?: outstandingStopCause(pass.previous?.drain, observation, pass.hadContainer)
                    // Before the drain, never after: a replacement this node cannot
                    // build must not take a populated server away first. See
                    // [replacementBlocker].
                    val blocker = replacementBlocker(pass, placement.node, cause)
                    when {
                        blocker != null -> converge(pass, placement.node, observation, blocker)
                        cause == null -> converge(pass, placement.node, observation)
                        else -> drain(pass, placement.node, observation, cause, binding)
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
     * Two proxies claim this backend and disagree about its forwarding secret.
     *
     * Not a parse error — neither document is wrong on its own, and neither parse
     * can see the other — so it surfaces here, on the server the conflict is
     * *about*. The container is not created or recreated while it holds: bringing
     * one up would mean choosing one of the two secrets, and choosing wrong means a
     * backend that authenticates nobody.
     *
     * **Retryable, and it escalates.** An operator fixing a selector on either
     * proxy resolves it without touching this server, which is the definition of a
     * failure that must not be classified permanent. It does need a person, and the
     * ordinary threshold is what calls them.
     */
    private suspend fun refuseConflictedProxies(
        stored: StoredServer,
        now: Instant,
        conflict: ProxyFleet.Resolution.Conflicted,
    ): ReconcileOutcome {
        LOG.error("server={} is claimed by conflicting proxies: {}", stored.name, conflict.message)
        val previous = stored.status?.status as? PaperServerStatus
        val status =
            draftStatus(
                previous = previous,
                name = stored.name,
                generation = stored.definition.generation,
                now = now,
                phase = if (previous?.runtime == null) ServerPhase.PENDING else ServerPhase.RUNNING,
                attentionAfter = config.drainAttentionAfter,
                attentionLedger = config.drainAttentionLedger,
                failure =
                    recordFailure(
                        reason = FailureReason.FORWARDING_SECRET_UNAVAILABLE,
                        failureClass = FailureClass.RETRYABLE,
                        message = conflict.message,
                        now = now,
                        previous = previous?.failure,
                    ),
            )
        return try {
            store.putStatus(status, observedDefinition = stored.definition.resourceVersion)
            ReconcileOutcome.Retry(conflict.message)
        } catch (storeFailure: StoreException) {
            storeOutcome(stored.name, storeFailure)
        }
    }

    // ── the proxy kind ───────────────────────────────────────────────────────

    /**
     * One pass over a `VelocityProxy`.
     *
     * The same shape as [reconcilePaper] — place, observe, diff, one step — with
     * two things a server does not have:
     *
     * - **A control endpoint**, which is observed rather than declared. A proxy
     *   that is joinable but whose plugin does not answer is a proxy behind which
     *   *no backend can complete a drain*, so the two are separate observations and
     *   neither implies the other.
     * - **A backend routing table**, asserted from the fleet on every pass. That
     *   assertion is the level trigger the whole seal design rests on: it is what
     *   restores joins to a backend whose drain has parked, including one whose
     *   permanent failure means the loop never passes over *it* again.
     *
     * This is a live path. `:store` persists the kind, so a proxy declared through
     * the API reaches this function on the next pass — the sentence that used to
     * stand here said the opposite, and it is the sentence a reader uses to decide
     * how carefully to read the rest.
     */
    private suspend fun reconcileProxy(
        stored: StoredServer,
        definition: VelocityProxyDefinition,
    ): ReconcileOutcome {
        val now = clock.instant()
        val pass =
            try {
                ProxyPass(stored, definition, now)
            } catch (rejected: IllegalArgumentException) {
                return rejectProxyDefinition(stored, now, rejected)
            } catch (rejected: IllegalStateException) {
                return rejectProxyDefinition(stored, now, rejected)
            }

        if (pass.isBlockedByPermanentFailure()) {
            return ReconcileOutcome.Failed(
                pass.previous?.failure?.message ?: "a permanent failure is recorded on observed status",
            )
        }

        return try {
            when (val placement = placeProxy(pass)) {
                is Placement.Refused -> {
                    refuseProxyPlacement(pass, placement)
                }

                is Placement.On -> {
                    val observation = placement.node.observe(pass.name)
                    val cause =
                        placement.cause
                            ?: proxyDrainCause(pass, observation)
                            // The same rule, on the kind whose stop takes the
                            // fleet's front door with it. A proxy holds no world, so
                            // what converging over a dispatched stop costs here is a
                            // mass disconnect and an availability report nobody can
                            // act on rather than a lost session — the same missing
                            // guard all the same.
                            ?: outstandingStopCause(pass.previous?.drain, observation, pass.hadContainer)
                    val blocker = replacementBlocker(pass, placement.node, cause)
                    when {
                        blocker != null -> convergeProxy(pass, placement.node, observation, blocker)
                        cause == null -> convergeProxy(pass, placement.node, observation)
                        else -> drainProxy(pass, placement.node, observation, cause)
                    }
                }
            }
        } catch (failure: NodeException) {
            proxyNodeFailure(pass, failure)
        } catch (failure: StoreException) {
            storeOutcome(pass.name, failure)
        }
    }

    /**
     * Asks whether the replacement could be built **before** the drain that
     * destroys the thing being replaced.
     *
     * ## What went wrong without it
     *
     * A proxy running perfectly, a hash-bearing edit, and then: drain to zero,
     * stop, remove — all correct — and only at `ensureWorkload` does the node
     * discover it has no control plugin to mount. The refusal is right and its
     * permanence is right; what was wrong is *when it was first asked*. The front
     * door was already gone, and the loop had just established permanently that it
     * could not build another. Nothing stages that artefact for `:app:run` or for
     * any distribution — only the integration suite does — so that is the default
     * state of a real install rather than an unlucky one.
     *
     * ## The kind that holds worlds needs it more, not less
     *
     * The twenty-fifth audit's third warning: this existed on the proxy path only,
     * and `storage.mountPath`, `rcon.secret` and the *proxy's* forwarding secret are
     * all in a `PaperServer`'s spec hash. A stored row whose `mountPath` a later
     * reader would refuse — `DefinitionCodec` does not re-run the YAML reader's
     * validation — therefore drained a running, populated server correctly, saved
     * it, stopped it, removed it, and only then met a create that refuses for ever.
     * No world is lost (the volume outlives the container and the drain is correct),
     * and the server cannot come back. One bad reference on one *proxy* definition
     * reaches every backend behind it, because the forwarding secret's coordinates
     * are in each of their hashes.
     *
     * ## Why the classification here is not the create's
     *
     * `HostPaths` is still the one enforcement point and still calls a missing
     * artefact `PERMANENT`; this asks it the same question through
     * [Node.checkWorkload], which *is* the create's own derivation. But permanence
     * means "stop trying", and that is a mechanism rather than a preference:
     * `isBlockedByPermanentFailure` lifts **only** on `observedGeneration !=
     * generation` or a delete, and neither documented remedy — staging a file,
     * staging a secret, re-aligning a token — produces either. A permanent
     * classification here would therefore be a freeze that the remedy cannot lift,
     * so an operator who did exactly what the message asked would still need a
     * second, meaningless definition edit. On the proxy it costs more still: a
     * frozen proxy stops running `assertBackends`, which is the level trigger that
     * restores joins to a backend whose own drain has parked.
     *
     * **What retryable costs, stated rather than glossed.** A permanent failure
     * escalates at once; a retryable one waits out
     * [ReconcilerConfig.drainAttentionAfter] first, and round 8 made permanent
     * failures immediate precisely because their remedy is a human. The quiet is
     * earned here — nothing has been stopped and the workload is up and serving —
     * but it is a trade, not a free choice.
     *
     * ## Scope, stated rather than implied
     *
     * `REPLACEMENT` only. A `DELETION` needs no create and must never be blocked by
     * one — that is how a workload becomes undeletable. A `RELOCATION` would create
     * on a *different* node, which this call site does not hold a handle to; it is
     * refused earlier for a different reason, and pre-flighting the destination is
     * left to whoever makes a second node real.
     */
    @Suppress("LongParameterList")
    private suspend fun replacementBlocker(
        name: ResourceName,
        desired: WorkloadSpec,
        node: Node,
        cause: DrainCause?,
        drainInFlight: Boolean,
        previous: FailureStatus?,
        now: Instant,
        subject: String,
        consequence: String,
    ): FailureStatus? {
        if (cause != DrainCause.REPLACEMENT) return null
        // A drain already in flight is past the point *this* asking protects: it is
        // asked before anything is drained, and refusing here once the protocol has
        // begun would strand a drain mid-flight without undoing any of it.
        //
        // The exemption is not a claim that the question stops mattering. It stops
        // mattering only from the stop onwards, and the passes before that — sealing,
        // waiting, transferring, saving — are hours on a populated server, in which
        // an asset directory can be restaged or a secret rotated. So the question is
        // asked again by `DrainController.letGoAndStop`, at the entry to steps 6 and
        // 7, where a refusal still costs nothing. Two askings, one for each end of
        // the window; this one is the cheap one and it is not the last line of
        // defence it used to read as.
        if (drainInFlight) return null
        return try {
            node.checkWorkload(desired)
            null
        } catch (rejected: NodeException.Rejected) {
            val message =
                "this $subject's definition has changed in a way that needs the container replaced, and node " +
                    "`${node.name}` cannot build the replacement: ${rejected.message}. Nothing has been drained " +
                    "or stopped — $consequence — and the replacement happens on its own once the node can " +
                    "build it"
            LOG.error(
                "{} cannot be replaced, so it has not been drained: {}",
                WorkloadRef(name, node.name),
                message,
            )
            recordFailure(
                reason = FailureReason.CONTAINER_CREATE_FAILED,
                // Retryable although the create's own answer is permanent, and
                // deliberately: see the note above.
                failureClass = FailureClass.RETRYABLE,
                message = message,
                now = now,
                previous = previous,
            )
        }
    }

    /**
     * [replacementBlocker] for a proxy.
     *
     * A wrapper rather than a copy: the question, its scope and its classification
     * are one function, and what differs between the kinds is two clauses of the
     * sentence an operator reads. Copying it is how the proxy path came to have the
     * guard and the world-holding path came not to.
     */
    private suspend fun replacementBlocker(
        pass: ProxyPass,
        node: Node,
        cause: DrainCause?,
    ): FailureStatus? =
        replacementBlocker(
            name = pass.name,
            desired = pass.desired,
            node = node,
            cause = cause,
            drainInFlight = pass.previous?.drain != null,
            previous = pass.previous?.failure,
            now = pass.now,
            subject = "proxy",
            consequence = "the proxy that is running keeps running and keeps routing",
        )

    /** [replacementBlocker] for a `PaperServer`. */
    private suspend fun replacementBlocker(
        pass: Pass,
        node: Node,
        cause: DrainCause?,
    ): FailureStatus? =
        replacementBlocker(
            name = pass.name,
            desired = pass.desired,
            node = node,
            cause = cause,
            drainInFlight = pass.previous?.drain != null,
            previous = pass.previous?.failure,
            now = pass.now,
            subject = "server",
            consequence =
                "the server that is running keeps running, with its players on it and its world where " +
                    "it is",
        )

    private suspend fun placeProxy(pass: ProxyPass): Placement {
        val spec = pass.definition.spec
        val currentNode = pass.previous?.runtime?.node
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
                            // A proxy holds no world, so there is nothing that has
                            // to be reachable from the node it lands on.
                            persistentVolume = null,
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
                    return Placement.Refused(
                        null,
                        "this proxy's workload was last observed on node `$currentNode`, which is no longer " +
                            "registered. It is not being scheduled onto `${decision.node}`: that would run a " +
                            "second front door while the first may still have players on it",
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

    private fun proxyDrainCause(
        pass: ProxyPass,
        observation: WorkloadObservation,
    ): DrainCause? {
        if (pass.stored.definition.terminating) return DrainCause.DELETION
        val present = observation as? WorkloadObservation.Present ?: return null
        val actual = present.specHash ?: return null
        return if (actual == pass.desired.specHash) null else DrainCause.REPLACEMENT
    }

    /**
     * Moves an undrained proxy one step toward running, joinable and routing.
     *
     * [blocker] is set only when a replacement was wanted and [replacementBlocker]
     * refused it. The proxy then converges as it always would — it is running and
     * correct, just not the *current* definition — and carries the reason on
     * observed status, because a log line is not a surface an operator diagnoses
     * from.
     *
     * **Every branch that records a failure prefers it**, which the twenty-fifth
     * audit's fourth warning found was true of `RUNNING` alone. On `EXITED` the
     * pass recorded `CONTAINER_EXITED` and discarded the sentence naming the
     * artefact that is missing — so "the proxy is down and cannot be rebuilt"
     * reported only that it exited, and the one fact an operator could act on was
     * in a log line.
     */
    private suspend fun convergeProxy(
        pass: ProxyPass,
        node: Node,
        observation: WorkloadObservation,
        blocker: FailureStatus? = null,
    ): ReconcileOutcome {
        val image = ensureProxyImage(pass, node, observation)
        return when (observation) {
            // Unreachable with a [blocker], for the reason [converge] gives: a
            // `REPLACEMENT` is named by comparing a hash read off a container.
            WorkloadObservation.Absent -> {
                val created = node.ensureWorkload(pass.desired)
                LOG.info(
                    "created proxy workload for {} sandbox={} container={}",
                    WorkloadRef(pass.name, node.name),
                    created.handle.sandboxId,
                    created.handle.containerId,
                )
                write(
                    pass,
                    pass.draft(
                        ServerPhase.CREATING,
                        image = image,
                        runtime = pass.identity(created),
                        // The record of the drain that took the *old* container away
                        // does not follow the new one. This branch used to leave it
                        // in place, which the pass that turned the replacement
                        // joinable then cleared; that made the clear load-bearing at
                        // a site whose job is readiness, and a record retained there
                        // for a stop in flight was a record describing a container
                        // that no longer existed. It dies where the replacement is
                        // built — see [clearedDrainRecord].
                        drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
                    ),
                ) {
                    ReconcileOutcome.Progressed("proxy workload created")
                }
            }

            is WorkloadObservation.Present -> {
                when (observation.state) {
                    // Classified without `hadContainer`, on the same argument
                    // [converge]'s arm carries and by the same routing: `cause ==
                    // null` is [outstandingStopCause]'s answer, which on this state
                    // is the `hadContainer` answer, and a `blocker` exists only where
                    // there is no drain record to have dispatched a stop. A proxy
                    // holds no world, so what a mistake here costs is a second front
                    // door rather than a world — but the rule is the same one and
                    // stating it differently on the two paths is how the pair drifts.
                    WorkloadState.SANDBOX_ONLY -> {
                        val created = node.ensureWorkload(pass.desired)
                        write(
                            pass,
                            pass.draft(
                                ServerPhase.CREATING,
                                image = image,
                                runtime = pass.identity(created),
                                // The other create, and the same rule.
                                drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
                                failure = blocker,
                            ),
                        ) { ReconcileOutcome.Progressed("proxy container created in the existing sandbox") }
                    }

                    WorkloadState.CREATED -> {
                        node.startWorkload(observation.handle)
                        write(
                            pass,
                            pass.draft(
                                ServerPhase.STARTING,
                                image = image,
                                runtime = pass.identity(observation),
                                // A container that has never been started cannot be
                                // one anything was signalled into, so this can only
                                // inherit a record the create above already cleared.
                                // It asks anyway: a record that reached here would
                                // otherwise be inherited by every pass that follows,
                                // and nothing downstream would ever clear it.
                                drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
                                failure = blocker,
                            ),
                        ) { ReconcileOutcome.Progressed("proxy container started") }
                    }

                    WorkloadState.RUNNING -> {
                        awaitProxyReady(pass, node, observation, image, blocker)
                    }

                    WorkloadState.EXITED -> {
                        val detail = observation.reason.ifBlank { observation.message }
                        val message =
                            "the proxy container exited with code ${observation.exitCode ?: "unknown"}" +
                                if (detail.isBlank()) "" else " ($detail)"
                        LOG.error(
                            "{} is down and nothing will restart it: {}",
                            WorkloadRef(pass.name, node.name),
                            message,
                        )
                        val failure =
                            recordFailure(
                                reason = FailureReason.CONTAINER_EXITED,
                                failureClass = FailureClass.PERMANENT,
                                message = message,
                                now = pass.now,
                                previous = pass.previous?.failure,
                            )
                        write(
                            pass,
                            pass.draft(
                                ServerPhase.STOPPED,
                                image = image,
                                runtime = pass.identity(observation),
                                // "It exited" is true and unactionable; "and the
                                // artefact it needs is not on this node" is the one
                                // an operator can do something about.
                                failure = blocker ?: failure,
                            ),
                            // The outcome follows the failure that was recorded: a
                            // `Failed` beside a retryable status is two answers to
                            // "is the loop still trying".
                        ) { blocker?.let { ReconcileOutcome.Retry(it.message) } ?: ReconcileOutcome.Failed(message) }
                    }

                    WorkloadState.UNKNOWN -> {
                        write(pass, pass.draft(ServerPhase.UNKNOWN, image = image, failure = blocker)) {
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

    private suspend fun ensureProxyImage(
        pass: ProxyPass,
        node: Node,
        observation: WorkloadObservation,
    ): ImageStatus {
        val recorded = pass.previous?.image
        // `hadContainer` is not asked, and this is a classification of
        // `SANDBOX_ONLY` where its two worlds genuinely do not differ: what is
        // being decided is whether to skip an *image* round trip, and an image
        // belongs to the node rather than to a container. An emptied sandbox and
        // one the runtime is under-reporting both answer "ask again", which costs
        // one call and is what a create would need anyway. Nothing here can stop,
        // remove or converge over anything.
        val settled =
            observation is WorkloadObservation.Present && observation.state != WorkloadState.SANDBOX_ONLY
        if (settled && recorded != null && recorded.available && recorded.requested == pass.definition.spec.image) {
            return recorded
        }
        val availability = node.ensureImage(pass.definition.spec.image)
        if (availability.pulled) {
            LOG.info(
                "pulled image for {} image={}",
                WorkloadRef(pass.name, node.name),
                pass.definition.spec.image.canonical,
            )
        }
        return ImageStatus(
            requested = pass.definition.spec.image,
            resolvedDigest = availability.id,
            pulledAt = if (availability.pulled) pass.now else recorded?.pulledAt ?: pass.now,
        )
    }

    /**
     * A running proxy container is not a working front door.
     *
     * Three separate observations, in the order a failure of each blocks the next:
     * the player port answers a Server List Ping, the plugin answers its handshake
     * and speaks a protocol this build knows, and the routing table matches what
     * the selector resolves to.
     *
     * The middle one is the reason this kind exists. `CONTROL_ENDPOINT_READY` being
     * false does not stop players joining — the proxy is perfectly functional — but
     * it means **no backend behind it can complete a drain**, which is a fleet-wide
     * property that nothing else would report.
     */
    private suspend fun awaitProxyReady(
        pass: ProxyPass,
        node: Node,
        observation: WorkloadObservation.Present,
        image: ImageStatus?,
        blocker: FailureStatus? = null,
    ): ReconcileOutcome {
        val channel = pass.channel(node, observation.handle)
        val control = readControl(pass, channel)
        val probe = pass.agent.probe(node, observation.handle)
        val endpoint =
            ServerEndpoint(
                node = node.name,
                address = node.name.value,
                port = pass.definition.spec.network.hostPort ?: pass.definition.spec.network.port,
            )

        if (probe !is ProbeOutcome.Joinable) {
            val startedAt = observation.startedAt ?: observation.createdAt ?: pass.now
            val waited = JavaDuration.between(startedAt, pass.now).toKotlinDuration()
            val detail = (probe as ProbeOutcome.Unanswered).detail
            val within = waited <= pass.definition.spec.lifecycle.startupTimeout
            val failure =
                if (within) {
                    null
                } else {
                    recordFailure(
                        reason = FailureReason.READINESS_TIMEOUT,
                        failureClass = FailureClass.RETRYABLE,
                        message = "the proxy is not joinable ${waited.inWholeSeconds}s after start: $detail",
                        now = pass.now,
                        previous = pass.previous?.failure,
                    )
                }
            val status =
                pass.draft(
                    ServerPhase.STARTING,
                    image = image,
                    runtime = pass.identity(observation),
                    endpoint = endpoint,
                    control = control,
                    // Same rule as every other branch: see [convergeProxy].
                    failure = blocker ?: failure,
                )
            return write(pass, status) {
                when {
                    blocker != null -> {
                        ReconcileOutcome.Retry(blocker.message)
                    }

                    within -> {
                        ReconcileOutcome.Waiting(
                            "the proxy is not joinable yet: $detail",
                            config.readinessPollInterval,
                        )
                    }

                    else -> {
                        ReconcileOutcome.Retry("the proxy is not joinable: $detail")
                    }
                }
            }
        }

        val players = PlayerOccupancy(probe.online, probe.max, pass.now)
        val routing = assertBackends(pass, channel, control)
        val status =
            pass.draft(
                ServerPhase.RUNNING,
                ready = true,
                image = image,
                runtime = pass.identity(observation),
                endpoint = endpoint,
                players = players,
                backends = routing.status,
                control = control,
                // A drain that had aborted and then became unnecessary leaves a
                // stale record behind, and a joinable proxy means it is over —
                // unless this proxy has a stop of its own outstanding, which is a
                // fact about the container rather than about the drain.
                drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
                // The blocker wins where both are set. A proxy that cannot be
                // replaced *and* cannot assert its routing table has two problems
                // and one field; the one an operator has to act on is the artefact,
                // because the other may well be a consequence of running the wrong
                // container.
                failure = blocker ?: routing.failure,
            )
        return write(pass, status) {
            when {
                blocker != null -> ReconcileOutcome.Retry(blocker.message)
                routing.failure != null -> ReconcileOutcome.Retry(routing.failure.message)
                else -> ReconcileOutcome.Settled("running, joinable and routing")
            }
        }
    }

    /** The handshake, turned into the observation a dashboard reads. */
    private suspend fun readControl(
        pass: ProxyPass,
        channel: ControlChannel,
    ): ControlEndpointStatus =
        when (val outcome = channel.version()) {
            is ControlOutcome.Answered -> {
                ControlEndpointStatus(
                    reachable = true,
                    pluginApiVersion = outcome.value.pluginApiVersion,
                    // Set membership, read from `ControlProtocol` rather than
                    // re-derived here — the point of the one blessed dependency
                    // arrow is that this build cannot hold a stale copy.
                    compatible = outcome.value.compatible,
                    lastContactAt = pass.now,
                )
            }

            is ControlOutcome.Refused -> {
                ControlEndpointStatus(reachable = true, compatible = false, lastContactAt = pass.now)
            }

            is ControlOutcome.Unavailable -> {
                ControlEndpointStatus(
                    reachable = false,
                    compatible = false,
                    lastContactAt = pass.previous?.control?.lastContactAt,
                )
            }
        }

    /**
     * Asserts the routing table, every pass, from the fleet.
     *
     * ## This is the level trigger
     *
     * Every seal in this system lapses when nothing re-asserts it, and *this* is
     * what re-asserts. `PUT /v1/backends/{name}` states registration and admission
     * together, so one call per backend per pass repairs: a proxy that restarted
     * and lost every seal, a backend whose drain aborted and should take players
     * again, a backend whose drain aborted **permanently** — where the backend's
     * own passes have stopped, so nothing else could — and an orchestrator that
     * died mid-drain.
     *
     * `admitsNewPlayers` is the negation of `DrainState.sealsBackend()` — *is this
     * drain holding the backend out of routing* — and **not** of `drainInitiated`,
     * which is the destination-eligibility rule. The two are opposite answers about
     * a drain parked in `DRAIN_FAILED`: it takes players again, and it must never be
     * handed somebody else's. Substituting one for the other here kept a parked
     * backend sealed for ever.
     *
     * ## It also lets go of what the selector no longer matches
     *
     * A backend whose definition was purged, or relabelled, leaves a registration
     * pointing at an address nothing is listening on. Removing it is drain step 6
     * performed by a sweep — which is safe *here and only here* because the plugin
     * refuses `DELETE` outright while anybody is connected, with no force flag. The
     * sweep therefore cannot disconnect a player however wrong it is, and it is the
     * only thing that can repair a registration whose backend is gone.
     */
    private suspend fun assertBackends(
        pass: ProxyPass,
        channel: ControlChannel,
        control: ControlEndpointStatus,
    ): ProxyRouting {
        if (!control.reachable || !control.compatible) {
            val message =
                if (!control.reachable) {
                    "the proxy's control endpoint did not answer, so its routing table cannot be asserted and " +
                        "no backend behind it can complete a drain"
                } else {
                    "the proxy's plugin speaks control protocol `${control.pluginApiVersion}`, which this " +
                        "build does not. No backend behind it can complete a drain"
                }
            return ProxyRouting(
                status = pass.previous?.backends,
                failure =
                    recordFailure(
                        reason =
                            if (control.reachable) {
                                FailureReason.PROXY_PLUGIN_INCOMPATIBLE
                            } else {
                                FailureReason.PROXY_CONTROL_UNREACHABLE
                            },
                        // Retryable even for the version mismatch. "Stop trying" on
                        // a proxy freezes its status and stops the sweep above,
                        // which is the one thing that restores joins to a parked
                        // backend — so the narrow bucket stays narrow here too.
                        failureClass = FailureClass.RETRYABLE,
                        message = message,
                        now = pass.now,
                        previous = pass.previous?.failure,
                    ),
            )
        }

        // The whole listing, not `.servers`. `listAll` was chosen over `listServers`
        // precisely so one undecodable row could not break this sweep — and the part
        // that made that safe is the `unreadable` half, which names the rows whose
        // *definitions* could not be read. Discarding it keeps the tolerance and
        // throws away the safety: an unreadable row is not "a server that went away",
        // it is a server this build cannot describe, and the garbage collector below
        // would turn that absence into an outbound `DELETE` against a live backend.
        val listing = store.listAll()
        val matched = pass.backends(listing)
        val registered =
            when (val state = channel.state()) {
                is ControlOutcome.Answered -> {
                    state.value
                }

                // **"Answering" is not "answering to us".**
                //
                // `GET /v1/version` needs no token by design — it is what lets a
                // misconfigured credential be told apart from a wrong port — so the
                // handshake above reports `reachable = true, compatible = true`
                // while every call that matters 401s. The spec hash carries the
                // token's *coordinates* and not its value, deliberately, so
                // rotating the secret behind the reference does not recreate the
                // container: it keeps the token it was created with, `:core` starts
                // sending the new one, and every seal, transfer and deregistration
                // is refused. Silently, until somebody tried to drain something.
                //
                // This is the first authenticated call of the pass and it is one
                // that was already being made — the variant was discarded. Refusing
                // here rather than letting the loop below refuse N times says what
                // is wrong once, in the operator's terms, and skips assertions that
                // could not have landed.
                //
                // It reaches `failure` and not `control`: `ControlEndpointStatus`
                // has no field for "answering, but not to us", so a dashboard shows
                // `reachable = true, compatible = true` beside a failure saying
                // nothing can be sealed. **When that field lands, fill it from this
                // branch.** The temptation is a second, authenticated probe inside
                // `readControl`, which would be one more round trip per pass to
                // learn what this `when` already knows.
                is ControlOutcome.Refused if state.code == ControlErrorCode.UNAUTHENTICATED -> {
                    val message =
                        "the proxy's control endpoint is answering but rejecting this orchestrator's " +
                            "credential (${state.code}). Its container holds the control token it was created " +
                            "with, and rotating the secret behind `spec.control.tokenSecret` does not recreate " +
                            "it — so no backend behind this proxy can be sealed, transferred or deregistered " +
                            "until the token they share is the same one again"
                    LOG.error("proxy={} is refusing this orchestrator's control token: {}", pass.name, message)
                    return ProxyRouting(
                        status = pass.previous?.backends,
                        failure =
                            recordFailure(
                                reason = FailureReason.PROXY_CONTROL_UNREACHABLE,
                                // The same reasoning the unreachable and
                                // incompatible branches above give: "stop trying"
                                // on a proxy freezes its status and stops this
                                // sweep, which is the one thing that restores joins
                                // to a backend whose drain has parked. And the
                                // remedy — re-align the token, or recreate the
                                // proxy — is one an operator performs without
                                // touching this definition.
                                failureClass = FailureClass.RETRYABLE,
                                message = message,
                                now = pass.now,
                                previous = pass.previous?.failure,
                            ),
                    )
                }

                else -> {
                    null
                }
            }
        val statuses = mutableListOf<BackendStatus>()
        var problem: String? = null
        for (backend in matched) {
            val address = backend.address
            if (address == null || backend.letGo) {
                statuses +=
                    BackendStatus(
                        server = backend.server,
                        registration = BackendRegistration.PENDING,
                        drainInitiated = backend.drainInitiated,
                        lastTransitionAt = pass.now,
                    )
                continue
            }
            // **`sealsBackend`, never `drainInitiated`.** They answer different
            // questions and the plausible-looking one is wrong: `drainInitiated`
            // means "has any drain record at all", which is the *destination
            // eligibility* rule, and using it here would keep a backend whose drain
            // has parked out of routing for ever — the running, invisible,
            // unreachable server this level trigger exists to repair.
            val admits = !backend.sealed
            when (val outcome = channel.assertBackend(backend.server, address, admits)) {
                is ControlOutcome.Answered -> {
                    statuses +=
                        BackendStatus(
                            server = backend.server,
                            registration =
                                when {
                                    // Filled from this loop's own probe, never from
                                    // the proxy: a proxy-side ping would make the
                                    // read path blocking, which the plugin author
                                    // ruled out.
                                    !backend.ready -> BackendRegistration.UNREACHABLE

                                    outcome.value.admitsNewPlayers -> BackendRegistration.REGISTERED

                                    else -> BackendRegistration.SEALED
                                },
                            players =
                                backend.online?.let {
                                    PlayerOccupancy(it, backend.maxPlayers, pass.now)
                                },
                            drainInitiated = backend.drainInitiated,
                            lastTransitionAt = pass.now,
                        )
                }

                is ControlOutcome.Refused -> {
                    problem = problem ?: "`${backend.server}` could not be registered (${outcome.code})"
                    statuses +=
                        BackendStatus(
                            server = backend.server,
                            registration = BackendRegistration.PENDING,
                            drainInitiated = backend.drainInitiated,
                            lastTransitionAt = pass.now,
                        )
                }

                is ControlOutcome.Unavailable -> {
                    problem = problem ?: outcome.detail
                    statuses +=
                        BackendStatus(
                            server = backend.server,
                            registration = BackendRegistration.PENDING,
                            drainInitiated = backend.drainInitiated,
                            lastTransitionAt = pass.now,
                        )
                }
            }
        }

        // What the proxy holds that the selector no longer matches — plus every name
        // this build could not read, which is exempt rather than swept.
        //
        // Per-pass garbage collection keeps working for every readable row; the
        // exemption is exactly as wide as the ignorance and lapses the moment the row
        // is repaired. `DefinitionCodec` deliberately widens the population landing
        // in `unreadable`, and this sweep is the one consumer that would turn that
        // absence into a destructive call.
        //
        // Residual, named rather than fixed: a row whose stored name is NULL cannot
        // be matched to a registration at all, so a backend registered under a name
        // that build cannot recover is still swept. `BACKEND_OCCUPIED` is what stops
        // that harming anybody — the plugin refuses while a player is connected — and
        // inventing a placeholder name to close it would contradict what
        // `UnreadableServer.name` promises.
        val wanted =
            matched.map { it.server.value.lowercase() }.toSet() +
                listing.unreadable.mapNotNull { it.name?.lowercase() }.toSet()
        registered?.backends?.filter { it.name.lowercase() !in wanted }?.forEach { stale ->
            ResourceName.of(stale.name).getOrNull()?.let { name ->
                when (val outcome = channel.deregister(name)) {
                    is ControlOutcome.Answered -> {
                        LOG.info(
                            "deregistered `{}` from proxy={}: no definition matches its backend selector any more",
                            name,
                            pass.name,
                        )
                    }

                    // BACKEND_OCCUPIED, in practice. The plugin refusing is the
                    // guard working: somebody is connected, so nothing is removed.
                    is ControlOutcome.Refused -> {
                        LOG.info(
                            "left `{}` registered with proxy={}: {}",
                            name,
                            pass.name,
                            outcome.problem,
                        )
                    }

                    is ControlOutcome.Unavailable -> {
                        Unit
                    }
                }
            }
        }

        // The proxy's own login seal, from the same rule the drain uses. With every
        // backend sealed the login path has nowhere to deflect a joining player to
        // and admits them anyway, so a fleet-wide drain could never reach zero.
        val proxyDraining =
            pass.previous
                ?.drain
                ?.state
                ?.sealsBackend() == true
        val anyAdmitting = matched.any { !it.sealed }
        channel.assertProxyAdmission(admits = !proxyDraining && anyAdmitting)

        val routing = BackendRoutingStatus(observedAt = pass.now, backends = statuses)
        return ProxyRouting(
            status = routing,
            failure =
                problem?.let {
                    recordFailure(
                        reason = FailureReason.PROXY_CONTROL_UNREACHABLE,
                        failureClass = FailureClass.RETRYABLE,
                        message = "the proxy's routing table could not be fully asserted: $it",
                        now = pass.now,
                        previous = pass.previous?.failure,
                    )
                },
        )
    }

    private class ProxyRouting(
        val status: BackendRoutingStatus?,
        val failure: FailureStatus?,
    )

    /**
     * The proxy's own drain.
     *
     * It keeps the standalone shape — seal the login path, then wait for the last
     * player to log off — because a proxy has nowhere to send its own players by
     * construction: a fleet has one front door. [ProxyDrainSubject] therefore has a
     * [DrainSeal] and no [DrainRouter], and there is no cross-server sequencing
     * anywhere in it.
     */
    private suspend fun drainProxy(
        pass: ProxyPass,
        node: Node,
        observation: WorkloadObservation,
        cause: DrainCause,
    ): ReconcileOutcome {
        val seal =
            (observation as? WorkloadObservation.Present)?.let {
                ProxySelfLink(pass.channel(node, it.handle))
            }
        val progress =
            drainController.advance(
                subject =
                    ProxyDrainSubject(
                        definition = pass.definition,
                        agent = pass.agent,
                        replacementSpec = pass.desired,
                        seal = seal,
                    ),
                node = node,
                observation = observation,
                current = pass.previous?.drain,
                cause = cause,
                lastProbedAt = pass.previous?.players?.observedAt,
                hadContainer = pass.hadContainer,
                // The same answer, from the same expression, on the kind that
                // seals *itself* — which is the kind the release it gates is for.
                permanentFailureStopsPasses = pass.stored.permanentFailureStopsPasses(),
            )
        val phase =
            when {
                progress.containerDown -> ServerPhase.STOPPED
                progress.drain.state == DrainState.DRAIN_FAILED -> ServerPhase.RUNNING
                progress.drain.state == DrainState.STOPPING -> ServerPhase.STOPPING
                else -> ServerPhase.DRAINING
            }
        val status =
            pass.draft(
                phase = phase,
                ready = progress.occupancy != null && phase == ServerPhase.RUNNING,
                runtime =
                    (observation as? WorkloadObservation.Present)
                        ?.let { pass.identity(it) }
                        ?: pass.previous?.runtime,
                players = progress.occupancy ?: pass.previous?.players,
                drain = progress.drain,
                // A copy, and it must stay one — see the note on `Reconciler.drain`.
                failure = progress.drain.failure,
            )
        if (!progress.containerDown) {
            return write(pass, status, mustRecord = progress.sideEffectIssued) { progress.outcome }
        }
        return teardownProxy(pass, node, observation, status, cause)
    }

    private suspend fun teardownProxy(
        pass: ProxyPass,
        node: Node,
        observation: WorkloadObservation,
        status: VelocityProxyStatus,
        cause: DrainCause,
    ): ReconcileOutcome {
        if (observation is WorkloadObservation.Present) {
            val removal = node.removeWorkload(observation.handle)
            if (!removal.complete) {
                val partial = status.copy(runtime = status.runtime?.copy(containerId = null))
                return write(pass, partial) { ReconcileOutcome.Retry(removal.detail) }
            }
            LOG.info("removed proxy workload for {}", WorkloadRef(pass.name, node.name))
            return write(pass, status) { ReconcileOutcome.Progressed("proxy workload removed") }
        }
        if (pass.stored.definition.terminating) {
            val verdict = writeProxyStatus(pass, status)
            if (verdict is WriteVerdict.Conflicted) return verdict.outcome
            return when (
                val outcome =
                    store.purge(
                        pass.name,
                        Precondition.AtVersion(pass.stored.definition.resourceVersion),
                    )
            ) {
                is WriteOutcome.Applied -> {
                    LOG.info("purged proxy={}: its workload is gone", pass.name)
                    ReconcileOutcome.Progressed("definition purged")
                }

                is WriteOutcome.Conflict -> {
                    ReconcileOutcome.Retry("the purge conflicted (${outcome.reason}); re-reading")
                }
            }
        }
        val cleared =
            pass.draft(
                phase = status.phase,
                image = status.image,
                runtime = null,
                endpoint = null,
                players = null,
                backends = null,
                control = null,
                // The retirement point, and the one clear that is not conditional in
                // substance: this is only reached with the workload observed
                // [WorkloadObservation.Absent], which is the evidence that the stop
                // this record is about has finished. It asks anyway, because a site
                // that clears by construction and a site that clears by argument look
                // identical to the next reader.
                drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
            )
        return write(pass, cleared) {
            ReconcileOutcome.Progressed("the old proxy workload is gone; ${cause.detail} is applied next pass")
        }
    }

    private suspend fun refuseProxyPlacement(
        pass: ProxyPass,
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
        if (permanent) LOG.error("proxy={} cannot be placed: {}", pass.name, refusal.message)
        val status =
            pass.draft(
                phase = if (pass.previous?.runtime == null) ServerPhase.PENDING else ServerPhase.UNKNOWN,
                failure = failure,
            )
        return write(pass, status) {
            if (permanent) ReconcileOutcome.Failed(refusal.message) else ReconcileOutcome.Retry(refusal.message)
        }
    }

    private suspend fun proxyNodeFailure(
        pass: ProxyPass,
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
        if (failure.retryable) {
            LOG.warn("node operation failed for proxy={}: {}", pass.name, failure.message)
        } else {
            LOG.error("node operation failed permanently for proxy={}: {}", pass.name, failure.message)
        }
        val status =
            pass.draft(
                phase = if (pass.previous?.runtime == null) ServerPhase.PENDING else ServerPhase.UNKNOWN,
                failure = recorded,
            )
        return write(pass, status) {
            if (failure.retryable) ReconcileOutcome.Retry(failure.message) else ReconcileOutcome.Failed(failure.message)
        }
    }

    private suspend fun rejectProxyDefinition(
        stored: StoredServer,
        now: Instant,
        failure: RuntimeException,
    ): ReconcileOutcome {
        val message = "the proxy definition cannot be turned into a workload: ${failure.message}"
        LOG.error("proxy={} was rejected: {}", stored.name, message)
        val previous = stored.status?.status as? VelocityProxyStatus
        val status =
            draftProxyStatus(
                previous = previous,
                name = stored.name,
                generation = stored.definition.generation,
                now = now,
                phase = ServerPhase.FAILED,
                attentionAfter = config.drainAttentionAfter,
                attentionLedger = config.drainAttentionLedger,
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

    private suspend inline fun write(
        pass: ProxyPass,
        status: VelocityProxyStatus,
        mustRecord: Boolean = false,
        outcome: () -> ReconcileOutcome,
    ): ReconcileOutcome {
        val verdict =
            if (mustRecord) {
                withContext(NonCancellable) { writeProxyStatus(pass, status) }
            } else {
                writeProxyStatus(pass, status)
            }
        return when (verdict) {
            is WriteVerdict.Conflicted -> verdict.outcome
            WriteVerdict.Written, WriteVerdict.Unchanged -> outcome()
        }
    }

    private suspend fun writeProxyStatus(
        pass: ProxyPass,
        status: VelocityProxyStatus,
    ): WriteVerdict {
        val recorded = pass.stored.status
        val previous = recorded?.status as? VelocityProxyStatus
        if (previous != null && status.copy(observedAt = previous.observedAt) == previous) {
            val since = JavaDuration.between(recorded.recordedAt, pass.now).toKotlinDuration()
            if (since < config.statusHeartbeat) return WriteVerdict.Unchanged
        }
        val outcome = store.putStatus(status = status, observedDefinition = pass.stored.definition.resourceVersion)
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

    /** Everything one proxy pass needs, read once. */
    private inner class ProxyPass(
        val stored: StoredServer,
        val definition: VelocityProxyDefinition,
        val now: Instant,
    ) {
        val name: ResourceName = definition.metadata.name
        val previous: VelocityProxyStatus? = stored.status?.status as? VelocityProxyStatus
        val agent: VelocityProxyAgent = VelocityProxyAgent(definition)

        /** The same fact, from the same expression, as `Pass.hadContainer`. */
        val hadContainer: Boolean get() = previous?.runtime?.containerId != null

        /**
         * The workload this proxy should be, including which Velocity it runs.
         *
         * [ReconcilerConfig.velocityBuild] is forwarded rather than read inside the
         * planner: the planner is a pure derivation and this is the only place that
         * holds the deployment's configuration. Forwarding it also means the
         * environment variable the container gets and the spec-hash entry it is
         * recorded under are one resolved value — see
         * `VelocityWorkloadPlanner.pinnedBuild` for why they may never differ.
         */
        val desired: WorkloadSpec = VelocityWorkloadPlanner.plan(definition, config.velocityBuild)

        fun isBlockedByPermanentFailure(): Boolean {
            val failed =
                previous != null &&
                    previous.observedGeneration == stored.definition.generation &&
                    previous.failure?.failureClass == FailureClass.PERMANENT &&
                    // The same expression, for the same reasons, on the kind that
                    // seals *itself* — where freezing a drain that is merely
                    // waiting for players leaves the fleet's login path shut with
                    // nothing left looking at it. See [parkedOnTheFailure].
                    previous.drain.parkedOnTheFailure()
            return failed && stored.permanentFailureStopsPasses()
        }

        fun channel(
            node: Node,
            handle: WorkloadHandle,
        ): ControlChannel =
            ControlChannel(
                node = node,
                handle = handle,
                port = definition.spec.control.port,
                token = definition.spec.control.tokenSecret,
                timeout = definition.spec.backends.drain.sealTimeout,
            )

        /**
         * Every server this proxy's selector matches, with what the fleet knows
         * about each.
         *
         * `listAll`, never `listServers`, for the reason `ProxyFleet.readFleet`
         * gives: the strict read throws for an undecodable row, and one of those on
         * this path would stop the proxy asserting *any* backend — which is the
         * level trigger that restores joins to a parked drain.
         */
        suspend fun backends(listing: ServerListing): List<MatchedBackend> {
            val selector = definition.spec.backends.selector
            return listing.servers.mapNotNull { row ->
                val backend = row.definition.definition as? PaperServerDefinition ?: return@mapNotNull null
                if (!selector.matches(backend.metadata.labels)) return@mapNotNull null
                val status = row.status?.status as? PaperServerStatus
                MatchedBackend(
                    server = backend.metadata.name,
                    sealed = status?.drain?.state?.sealsBackend() == true,
                    // Null until the loop knows which node the workload is on.
                    // **Not asserted under a guessed hostname**: a registration at a
                    // name that does not resolve is one the protocol will refuse to
                    // correct — `ADDRESS_CONFLICT` is deliberately not an upsert — so
                    // it wedges drain step 2 permanently, and players routed to it
                    // get a connection failure while the fleet reports healthy. "Not
                    // registered yet" is a state the protocol handles; that is not.
                    address =
                        status?.runtime?.node?.let {
                            backendAddress(it, backend.spec.network.hostPort ?: backend.spec.network.port)
                        },
                    // Its own drain has let go of it and the container is about to
                    // stop. Re-asserting would put the entry back between steps 6 and
                    // 7 — sealed, so nothing routes there, but Velocity's own
                    // fallback reconnect can land a player on a registered backend.
                    // `holdSeal` skips for the same reason; this is the other half.
                    letGo = status?.drain?.deregisteredAt != null,
                    maxPlayers = backend.spec.maxPlayers,
                    online = status?.players?.online,
                    ready = status?.ready == true,
                    drainInitiated = status?.drainInitiated == true || row.definition.terminating,
                )
            }
        }

        @Suppress("LongParameterList")
        fun draft(
            phase: ServerPhase,
            ready: Boolean = false,
            image: ImageStatus? = previous?.image,
            runtime: RuntimeIdentity? = previous?.runtime,
            endpoint: ServerEndpoint? = previous?.endpoint,
            players: PlayerOccupancy? = previous?.players,
            backends: BackendRoutingStatus? = previous?.backends,
            control: ControlEndpointStatus? = previous?.control,
            drain: mcorch.schema.DrainStatus? = previous?.drain,
            failure: FailureStatus? = null,
        ): VelocityProxyStatus =
            draftProxyStatus(
                previous = previous,
                name = name,
                generation = stored.definition.generation,
                now = now,
                phase = phase,
                attentionAfter = config.drainAttentionAfter,
                attentionLedger = config.drainAttentionLedger,
                ready = ready,
                image = image,
                runtime = runtime,
                endpoint = endpoint,
                players = players,
                backends = backends,
                control = control,
                drain = drain,
                failure = failure,
            )

        fun identity(observation: WorkloadObservation.Present): RuntimeIdentity =
            RuntimeIdentity(
                node = observation.handle.node,
                sandboxId = observation.handle.sandboxId,
                containerId = observation.handle.containerId ?: previous?.runtime?.containerId,
                createdAt = observation.createdAt,
                startedAt = observation.startedAt,
                finishedAt = observation.finishedAt,
                exitCode = observation.exitCode,
                restartCount = previous?.runtime?.restartCount ?: 0,
            )
    }

    /** One server the proxy's selector matched, as the routing assertion needs it. */
    private class MatchedBackend(
        val server: ResourceName,
        /**
         * Whether this backend's own drain is holding it out of routing.
         *
         * From `DrainState.sealsBackend`, which is the one definition of that rule
         * — the drain controller asserts the same thing for the workload it is
         * draining, and the two must not be able to disagree.
         */
        val sealed: Boolean,
        /**
         * `host:port` as the proxy must dial it, or null when the loop does not yet
         * know which node the workload is on. Never a player's address.
         */
        val address: String?,
        /** Its own drain has deregistered it and the stop is next. Not re-asserted. */
        val letGo: Boolean,
        val maxPlayers: Int,
        val online: Int?,
        val ready: Boolean,
        val drainInitiated: Boolean,
    )

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
        // Error, not warn: this is permanent by construction, so the loop is
        // about to stop acting on this server until somebody edits it. Every site
        // that records a PERMANENT `status.failure` logs at error for that one
        // reason — it is the level an operator's alerting greps for, and it is the
        // same set of sites the escalation now raises `NEEDS_ATTENTION` from.
        LOG.error("server={} was rejected: {}", stored.name, message)
        val previous = stored.status?.status as? PaperServerStatus
        val status =
            draftStatus(
                previous = previous,
                name = stored.name,
                generation = stored.definition.generation,
                now = now,
                phase = ServerPhase.FAILED,
                attentionAfter = config.drainAttentionAfter,
                attentionLedger = config.drainAttentionLedger,
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
        // Conditioned on the class, because the retryable refusal is an ordinary
        // "not yet" — a node coming back, capacity arriving — and logging every
        // one of those at error is how the level stops meaning anything.
        if (permanent) LOG.error("server={} cannot be placed: {}", pass.name, refusal.message)
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
     *
     * [blocker] is set only when a replacement was wanted and [replacementBlocker]
     * refused it. The server then converges as it always would — it is running and
     * correct, just not the *current* definition — and carries the reason on
     * observed status, because a log line is not a surface an operator diagnoses
     * from. It is preferred over every failure a branch here would record for
     * itself: "the container exited" is true and says nothing about the artefact
     * that is missing, and the second sentence is the one that tells an operator
     * what to do.
     */
    private suspend fun converge(
        pass: Pass,
        node: Node,
        observation: WorkloadObservation,
        blocker: FailureStatus? = null,
    ): ReconcileOutcome {
        val storage = pass.storageStatus(observation)
        val image = ensureImage(pass, node, observation)

        return when (observation) {
            // Unreachable with a [blocker] and deliberately left alone: a
            // `REPLACEMENT` is named by comparing a spec hash *read off a
            // container*, so a pass carrying one has observed a container. If that
            // ever stops holding, `ensureWorkload` adopts rather than duplicates.
            WorkloadObservation.Absent -> {
                val created = node.ensureWorkload(pass.desired)
                LOG.info(
                    "created workload for {} sandbox={} container={}",
                    WorkloadRef(pass.name, node.name),
                    created.handle.sandboxId,
                    created.handle.containerId,
                )
                val status =
                    pass.draft(
                        phase = ServerPhase.CREATING,
                        image = image,
                        runtime = pass.runtimeIdentity(created),
                        storage = storage,
                        drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
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
                    // Not `null`: a converging pass concludes that no drain is
                    // *wanted*, which says nothing about a stop that has already
                    // been issued against the container it is looking at. See
                    // [clearedDrainRecord].
                    drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
                    // The blocker wins where both are set. A server that cannot be
                    // replaced *and* has something else wrong with it has two
                    // problems and one field; the one an operator has to act on is
                    // the one that stops the definition being applied at all.
                    failure = blocker ?: failure,
                )

                when (observation.state) {
                    // The sandbox is there and the container is not. Adopting
                    // the sandbox and creating the container into it is exactly
                    // what `ensureWorkload` does — it is never a second create.
                    //
                    // **Classified without `hadContainer`, and the argument is at
                    // the routing site rather than here.** This is the observation
                    // where "the container was never created" and "the runtime has
                    // stopped enumerating a container that may still be serving
                    // players" are indistinguishable, and [stopIsInFlight] needs the
                    // fact to tell them apart. This arm does not, because both routes
                    // into `converge` have already asked it: `cause == null` means
                    // [outstandingStopCause] answered null, which on this state *is*
                    // the `hadContainer` answer, and `blocker != null` requires
                    // [replacementBlocker], which returns null whenever a drain record
                    // exists — so no stop this loop dispatched can still be inside
                    // whatever the sandbox is hiding. What is left is a container
                    // nobody signalled that the runtime is under-reporting, and the
                    // only place that can be re-asked is the node: `ensureWorkload`
                    // adopts rather than duplicating. Widening the guard here instead
                    // would refuse the legitimate recreate after a partial teardown.
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
                                drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
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
                        awaitJoinable(pass, node, observation, image, storage, blocker)
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
                        // The container this loop brought up is down and nothing
                        // is going to bring it back without a human. Permanent by
                        // construction, so unconditional.
                        LOG.error(
                            "{} is down and nothing will restart it: {}",
                            WorkloadRef(pass.name, node.name),
                            message,
                        )
                        // The outcome follows whichever failure was recorded, and it
                        // has to: a `Failed` beside a retryable status is two answers
                        // to "is the loop still trying".
                        write(pass, status(ServerPhase.STOPPED, failure)) {
                            blocker?.let { ReconcileOutcome.Retry(it.message) } ?: ReconcileOutcome.Failed(message)
                        }
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
        blocker: FailureStatus? = null,
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
            // Reaching a joinable server means it is over: a *drain* is over, and
            // a container that has been sent `SIGTERM` answers a ping right up to
            // the moment it stops, so the record of that survives this. See
            // [clearedDrainRecord].
            drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
            // See the note in [converge]: an unbuildable replacement outranks
            // whatever else this pass found.
            failure = blocker ?: failure,
        )

        return when (probe) {
            is ProbeOutcome.Joinable -> {
                val players = PlayerOccupancy(online = probe.online, max = probe.max, observedAt = pass.now)
                write(pass, status(ServerPhase.RUNNING, ready = true, players = players)) {
                    blocker?.let { ReconcileOutcome.Retry(it.message) }
                        ?: ReconcileOutcome.Settled("running and joinable")
                }
            }

            is ProbeOutcome.NotJoinable -> {
                // The anchor has to be a fact about the *container*, and the
                // last two fallbacks are ordered by how stable they are rather
                // than by how precise.
                //
                // `lastTransitionAt` is restamped whenever the phase changes, so
                // it is not an anchor at all for a server whose phase is moving:
                // a flapping exec channel alternating a cut-short probe
                // (STARTING) with a node that did not answer (UNKNOWN) resets it
                // every other pass, and the startup timeout below can then never
                // elapse — a wedged server that never surfaces as failed, plus a
                // store write per pass because the phase change defeats the
                // unchanged-status skip. `createdAt` does not move.
                //
                // Reaching past `startedAt` at all needs a runtime that does not
                // report one for a running container, which containerd does; the
                // normal path is unchanged, byte for byte. Anchoring earlier
                // than the start only ever makes `waited` larger, so the timeout
                // surfaces sooner — the safe direction for a failure that is
                // retryable and stops nothing.
                val startedAt =
                    observation.startedAt
                        ?: observation.createdAt
                        ?: pass.previous?.lastTransitionAt
                        ?: pass.now
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
                // Conditioned: a probe channel that is merely not answering yet is
                // the ordinary case on a server that is still generating a world.
                if (!probe.retryable) {
                    LOG.error("server={} cannot be probed for readiness: {}", pass.name, probe.detail)
                }
                write(pass, status(ServerPhase.UNKNOWN, ready = false, players = null, failure = failure)) {
                    when {
                        // The status carries the blocker, which is retryable, so
                        // the outcome must be too.
                        blocker != null -> ReconcileOutcome.Retry(blocker.message)

                        probe.retryable -> ReconcileOutcome.Retry(probe.detail)

                        else -> ReconcileOutcome.Failed(probe.detail)
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
        // The backend half of the proxy's [ensureProxyImage], and `hadContainer`
        // is not asked for the same reason: this decides whether to skip an image
        // round trip, not what to do with a container. Both of `SANDBOX_ONLY`'s
        // worlds answer "ask the image service again", which is what the create
        // that may follow needs in hand either way.
        val settled =
            observation is WorkloadObservation.Present &&
                observation.state != WorkloadState.SANDBOX_ONLY
        if (settled && recorded != null && recorded.available && recorded.requested == pass.definition.spec.image) {
            return recorded
        }
        val availability = node.ensureImage(pass.definition.spec.image)
        if (availability.pulled) {
            LOG.info(
                "pulled image for {} image={}",
                WorkloadRef(pass.name, node.name),
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
        binding: ProxyFleet.Binding?,
    ): ReconcileOutcome {
        forbiddenTransition(pass, observation, cause)?.let { return it }
        // The proxy conversation for steps 2, 4 and 6, or nothing. Built per pass
        // and never cached: which servers are eligible destinations, and whether
        // this is the last admitting backend, are facts about the fleet *now*.
        val link = binding?.let { ProxyFleet.linkFor(it, pass.name, registry, scheduler, node.name, config) }
        val subject =
            PaperDrainSubject(
                definition = pass.definition,
                agent = pass.agent,
                // What the drain would be replacing this container with, so steps 6
                // and 7 can ask the node about it once more before they commit. Given
                // for every cause; read only for a `REPLACEMENT`.
                replacementSpec = pass.desired,
                seal = link,
                router = link,
            )
        val progress =
            drainController.advance(
                subject = subject,
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
                // tell us about it". The routing above answers the same question
                // from the same property, and the two may not diverge: this one
                // decides whether the drain tears the sandbox down, that one
                // whether the loop converges over the top of a dispatched stop.
                hadContainer = pass.hadContainer,
                // The loop's own gate, answered for this pass. A drain that parks
                // permanently behaves differently depending on whether anything
                // will look at this server again, and that is not the drain's
                // question to answer — see [permanentFailureStopsPasses].
                permanentFailureStopsPasses = pass.stored.permanentFailureStopsPasses(),
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
                // **A copy, and it must stay one.** Three separate consumers
                // discriminate "this failure is the drain's own" by comparing
                // these two values, and every one of them is silently wrong the
                // moment this stops being the identical value:
                //
                // - `StatusDrafting.deriveConditions` computes `passFailure` as
                //   `failure != drain.failure`, so a derived value here would put
                //   every aborted drain through the *pass* arm of the escalation
                //   and word it "the loop cannot complete a pass".
                // - `:api`'s `ServerJson.detail` ranks a pass failure above the
                //   drain's, so it would drop the "the drain aborted" framing for
                //   every genuinely failed drain.
                // - `:store` migration V5 drops the retired top-level failure on
                //   the strength of the same fact being written twice.
                //
                // If this ever needs context added, add it to the failure the
                // drain records — inside `DrainController.abort`, where the drain
                // and the status stay one value — rather than by decorating it on
                // the way past. `AttentionTest` pins the consequence rather than
                // the line, so the guard survives a rewrite of this function.
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
     *
     * ## Why it is not conditioned on the container still running
     *
     * It was, and that made the refusal expire on a state the *drain itself*
     * produces. An edit landing inside the stop grace period of a drain some
     * earlier edit asked for is refused while the container is `RUNNING`; the
     * signalled container then exits on its own, `RUNNING` stops being true, the
     * refusal stops firing, the drain resumes, and the create applies the very
     * definition several passes had refused. No world is discarded — the volume's
     * files are untouched and the drain flushed the container before it stopped —
     * but the server comes back serving a **freshly generated empty world**, and
     * everything built from then on lives in a writable layer that dies with the
     * next replacement.
     *
     * `Labels.WORLD_DATA` is read off the container and is still there when it has
     * `EXITED`, so the discriminator outlives the process and the refusal now does
     * too. `UNKNOWN` refuses for the same reason: the labels are the container's
     * own, so the premise the message states still holds, and the loop's posture on
     * a state it cannot read is to act on nothing — which here *is* the refusal.
     * `CREATED` and `SANDBOX_ONLY` are the pass-through, where the reasoning below
     * about not guessing applies: a container that was never started holds nothing
     * to discard, and a sandbox reporting no container carries only the sandbox's
     * labels, so refusing there would freeze a workload on a sentence about a
     * container that is not running.
     *
     * ## What the refusal leaves behind, in both of its two outcomes
     *
     * Which one a server lands in is decided by whether a stop had already been
     * dispatched when the edit arrived, and they are not variants of one story.
     *
     * - **A stop was dispatched.** [clearedDrainRecord] retains the record, so it
     *   is still `STOPPING`, so `parkedOnTheFailure()` is false, so the
     *   permanent-failure gate does not arm and the passes keep coming. Each one
     *   re-records the same refusal and increments `FailureStatus.attempts` — one
     *   store write per resync, no side effect on the server, nothing issued at the
     *   runtime. Churn is the price of the lever staying live: this loop is the only
     *   thing that can notice the operator reverting `spec.storage.mode`, and
     *   freezing a workload whose container it has already signalled leaves the stop
     *   half-finished until a resync a gate would suppress.
     * - **No stop was dispatched** — the ordinary case, an ephemeral edit landing on
     *   a healthy persistent server. [clearedDrainRecord] answers null, so the status
     *   carries no drain, so `parkedOnTheFailure()` is true, the gate arms on the
     *   next pass, and the server **freezes**: `isBlockedByPermanentFailure` returns
     *   before anything is observed, so the status stops being refreshed altogether.
     *   The lever is still the operator's — an edit moves the generation, which is
     *   what lifts that gate — but nothing about this server is re-read until they
     *   pull it, and `observedAt` stops advancing rather than the failure's
     *   `attempts` climbing.
     *
     * The first is the rarer one, and the paragraph that used to be here described
     * only its second-order effect. Both are correct; a reader reasoning about a
     * refused server has to know which of the two they are looking at before
     * "the passes keep coming" means anything.
     *
     * ## What a refused edit costs a drain already in `STOPPING`, stated as the trade
     *
     * While this fires it is a **gate in front of `advance`**: the refusal returns
     * from [drain] before the controller is entered, so a drain that had reached
     * `STOPPING` never reaches `awaitStopped` and never reaches [teardown]. The
     * container has been signalled and the backend deregistered; the sandbox is not
     * removed and the workload stays dark until the operator reverts the edit. That
     * is the intended trade rather than an oversight — the alternative is letting the
     * drain run to a create that applies the definition this function exists to
     * refuse, which is the empty-world outcome above — and it is the reason the
     * first outcome keeps its passes: a frozen half-stopped workload has nothing
     * watching for the revert that releases it.
     */
    private suspend fun forbiddenTransition(
        pass: Pass,
        observation: WorkloadObservation,
        cause: DrainCause,
    ): ReconcileOutcome? {
        if (cause != DrainCause.REPLACEMENT) return null
        val present = observation as? WorkloadObservation.Present ?: return null
        val couldBeTheContainerTheEditIsAbout =
            when (present.state) {
                WorkloadState.RUNNING, WorkloadState.EXITED, WorkloadState.UNKNOWN -> true

                // Classified without `hadContainer`, and unlike the two `converge`
                // arms the reason is at this arm rather than above it: **this rule
                // reads the labels, not the container**, and the fact that decides
                // it is already in hand. `SANDBOX_ONLY` carries the *sandbox's*
                // labels, so `Labels.WORLD_DATA` below would answer about the wrong
                // object; `hadContainer` cannot repair that, because knowing a
                // container once existed says nothing about what it was built with.
                // `CREATED` is a container that was never started and so holds
                // nothing to discard.
                //
                // The pass-through is not free and is not claimed to be: an
                // ephemeral edit landing in the gap between a replacement drain's
                // teardown and the next create is refused by nothing, and converges
                // onto an empty world beside an orphaned volume. It cannot be closed
                // from here — `StorageStatus` is overwritten from the *desired*
                // definition by [converge] and by [drain] on every pass, so a pass
                // later there is no record left to ask — and making it observed in
                // fact as well as in KDoc is a `:schema`/`:api` change routed
                // separately. Widening this arm instead would freeze every
                // replacement of an always-ephemeral lobby on advice that does not
                // apply to it.
                WorkloadState.CREATED, WorkloadState.SANDBOX_ONLY -> false
            }
        if (!couldBeTheContainerTheEditIsAbout) return null
        // Absent means the workload predates the label, which is not the same as
        // "it holds no world data" — and guessing either way from an edited
        // definition is exactly the mistake being guarded against.
        // Only a workload that positively says it holds a world. A **missing
        // label** is deliberately *not* refused here — which is a different
        // question from `WorkloadState.UNKNOWN` above, and the two are answered
        // opposite ways on purpose: an unreadable container state leaves the label
        // in hand, an unlabelled container leaves nothing in hand at all. The two
        // guards differ for the same reason. This one asks "is this edit a
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

        // True of a container that has exited as well as one that is running,
        // because the refusal now outlives the process. A sentence that says "the
        // container now running" is the first thing an operator reads, and the
        // guard would be quietly wrong for the whole window it was widened to
        // cover.
        val message =
            "refusing to change storage.mode to `ephemeral`: the container this server holds was created with " +
                "persistent world data, and applying this edit means draining and replacing it with one that " +
                "mounts no volume. Anything still in memory would be discarded rather than flushed, and the " +
                "replacement would come up on a new, empty world while the old one stays on the volume. Revert " +
                "spec.storage.mode; to retire this server instead, delete it — that drains and saves it first"
        LOG.error("server={} refused a storage mode change: {}", pass.name, message)
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
                phase =
                    when (present.state) {
                        WorkloadState.RUNNING -> ServerPhase.RUNNING

                        // The refusal outlives the process, and the badge has to
                        // follow the container rather than the guard: a `RUNNING`
                        // phase on a container that has exited is a fleet table
                        // saying a dead server is fine.
                        WorkloadState.EXITED -> ServerPhase.STOPPED

                        WorkloadState.UNKNOWN -> ServerPhase.UNKNOWN

                        // Refused by `couldBeTheContainerTheEditIsAbout` above, so
                        // unreachable here — enumerated rather than folded into an
                        // `else` because an `else` is how a classification of this
                        // state stops being visible to anything that goes looking
                        // for one. `hadContainer` is not asked for the reason that
                        // arm gives; this is a badge either way, not a decision
                        // about a container.
                        WorkloadState.CREATED, WorkloadState.SANDBOX_ONLY -> ServerPhase.UNKNOWN
                    },
                runtime = pass.runtimeIdentity(present),
                // The container's storage, not the edited definition's.
                // [Pass.storageStatus] derives from the definition, so drafting it
                // here records `persistent = false, volumeName = null` for a
                // workload this pass has just refused to make ephemeral — erasing
                // the loop's own record of which volume holds the world, which is
                // the name an operator needs to recover with and the one thing
                // nothing else in the system remembers. The previous record is the
                // one the container was created under; `bound` is the only part of
                // it this observation can still speak to.
                //
                // **No fallback, and that is the whole of it.** A row decoded with
                // no storage block at all — `StatusCodec.readStorage` answers null
                // whenever `storage.persistent` is absent, which is every row
                // written before the field existed — has *no* record of the volume,
                // and deriving one from the edited definition here would write the
                // very claim this refusal exists to prevent: `persistent = false`,
                // which `StatusDrafting.worldSavedMessage` renders as "ephemeral
                // storage: there is no world to save" for a server the loop is
                // refusing to make ephemeral, on exactly the population where the
                // volume name is recorded nowhere else. Absence stays absence:
                // this expression is null exactly on the rows that carry no
                // storage block, and null is the honest record for a row that has
                // none.
                //
                // **Not** because the argument falls back to [draft]'s default. An
                // explicitly passed null takes no default in Kotlin; it writes
                // null. That the default here is the same `previous?.storage` is a
                // coincidence of this one call — the two agree on every row — and
                // reasoning from it would be wrong the moment either side changed,
                // which is what the next person to edit this line would act on.
                storage = pass.previous?.storage?.copy(bound = true),
                // Refusing the *edit* is not withdrawing a drain that is already
                // stopping this container, and this is the third site that would
                // have deleted the dispatch record to say so: the edit can land
                // while a drain started by some earlier edit is inside its stop
                // grace period. What the refusal is about — memory that would be
                // discarded rather than flushed — has been flushed by then, and
                // the record that keeps the backend out of routing must outlive
                // the refusal either way.
                drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
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
                    "workload for {} is partly removed: {}",
                    WorkloadRef(pass.name, node.name),
                    removal.detail,
                )
                val partial =
                    status.copy(
                        runtime = status.runtime?.copy(containerId = null),
                    )
                return write(pass, partial) { ReconcileOutcome.Retry(removal.detail) }
            }
            LOG.info(
                "removed workload for {}; persistent storage is untouched",
                WorkloadRef(pass.name, node.name),
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
        //
        // **This is where the dispatch record is retired**, and it is the only
        // place that may: the container it names has been observed absent, which
        // is the one thing that makes "a stop may still be inside it" false. It
        // still asks, so that no site in this file clears a drain record on its
        // own authority. See [clearedDrainRecord].
        val cleared =
            pass.draft(
                phase = status.phase,
                image = status.image,
                runtime = null,
                endpoint = null,
                players = null,
                storage = status.storage,
                drain = clearedDrainRecord(pass.previous?.drain, observation, pass.hadContainer),
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
     * which the runtime cannot be asked about afterwards. Such a record has two
     * ways of being lost, and both have to be closed or the survivor decides
     * nothing:
     *
     * - **The write is rejected.** The guarded write can legitimately be refused
     *   — the operator replaced the definition while the pass ran — and dropping
     *   the observation is the right answer for *observations*. It is the wrong
     *   answer for the record of a save request: that record is the only thing
     *   stopping the next pass sending a second one to a live server.
     * - **The pass is cancelled.** The orchestrator shutting down cancels a pass
     *   at its next suspension point, and the first one after a save request
     *   comes back is the store write itself. A cancelled coroutine does not
     *   reach the store at all — every call dispatches, and a dispatch from a
     *   cancelled coroutine never runs — so the record is dropped just as
     *   thoroughly as by a rejection, and by a far more ordinary event than an
     *   operator editing mid-pass.
     *
     * [recordIssuedSideEffect] closes the second; [forceRecord] closes the
     * first.
     */
    private suspend inline fun write(
        pass: Pass,
        status: PaperServerStatus,
        mustRecord: Boolean = false,
        outcome: () -> ReconcileOutcome,
    ): ReconcileOutcome {
        val verdict =
            if (mustRecord) {
                recordIssuedSideEffect(pass, status)
            } else {
                writeStatus(pass, status)
            }
        return when (verdict) {
            is WriteVerdict.Conflicted -> {
                verdict.outcome
            }

            WriteVerdict.Written, WriteVerdict.Unchanged -> {
                outcome()
            }
        }
    }

    /**
     * Writes the record of a side effect this pass has already performed, so
     * that cancelling the pass cannot lose it.
     *
     * ## Why the shield is here and not around the pass
     *
     * The region is deliberately as small as it can be while still being
     * useful: the store write, plus the unguarded retry that follows a
     * rejection. Widening it to the pass would put a container operation inside
     * it — an exec bounded by `spec.lifecycle.drain.saveTimeout`, minutes long —
     * and shutting the orchestrator down would then wait out a save timeout,
     * which is exactly what `Main` says it does not do. Narrowing it to a single
     * `putStatus` would leave the [forceRecord] fallback cancellable, so a
     * pass unlucky enough to be both rejected *and* cancelled would still lose
     * the record.
     *
     * ## Why there is no timeout on it
     *
     * A [NonCancellable] store write can hold a shutdown open for as long as the
     * store takes, so the question is whether the store can take unboundedly
     * long. The embedded one cannot in the way that matters: it is one
     * transaction on a local file, serialised through a mutex behind an IO
     * dispatcher, and lock contention — the one wait that is not bounded by the
     * work itself — is bounded by `PRAGMA busy_timeout`, after which the write
     * fails rather than waiting.
     *
     * A `withTimeout` here would not add a bound anyway. JDBC calls block a
     * thread rather than suspending, so a genuinely wedged filesystem is not
     * interruptible by cancellation at all: the timeout would fire, the
     * coroutine would still be waiting for the blocking call underneath it, and
     * the only thing it would reliably achieve is abandoning the write this
     * function exists to guarantee. A store that can wedge indefinitely is a
     * problem for `:store` to bound at the JDBC level, where the bound can
     * actually be enforced.
     *
     * `ReconcileLoop` already takes the same exposure for `queue.done`, and for
     * the same reason: a lock taken from a cancelled coroutine throws instead of
     * waiting, so the work simply does not happen.
     *
     * ## What the shield still depends on
     *
     * That the store is *open*. Being uncancellable is worth nothing against a
     * store that has already been closed, and closing it is the last thing an
     * orchestrator does — so this is only durable while the store outlives the
     * loop. `mcorch.app.Main` is what arranges that, by joining the loop before
     * `Orchestrator.close`, and `ReconcileLoop`'s own shutdown is what makes the
     * join mean something (`ReconcileLoopTest` pins it).
     *
     * If that ordering is ever broken the loss is silent — a closed store throws
     * a [StoreException], which every other write treats as an ordinary
     * hiccup — so this one is reported at error before it is rethrown. Reporting
     * is all there is: the store is the only durable place there is, and a
     * record that cannot be written there is gone.
     */
    private suspend fun recordIssuedSideEffect(
        pass: Pass,
        status: PaperServerStatus,
    ): WriteVerdict =
        withContext(NonCancellable) {
            val verdict =
                try {
                    writeStatus(pass, status)
                } catch (failure: StoreException) {
                    LOG.error(
                        "server={} could not record a side effect it had already performed, because the store " +
                            "refused the write ({}). The record is lost and the next pass may repeat it — for a " +
                            "drain that means a second world save against a running server. If the orchestrator " +
                            "was shutting down, the store was closed before the loop finished unwinding",
                        pass.name,
                        failure.message,
                    )
                    throw failure
                }
            if (verdict is WriteVerdict.Conflicted) forceRecord(pass, status)
            verdict
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
        // Conditioned on the class. A retryable node failure is a hiccup the loop
        // is built to absorb and it happens on every node restart; a permanent one
        // means the loop stops acting on this server until somebody intervenes,
        // which is the same threshold `NEEDS_ATTENTION` fires on.
        if (failure.retryable) {
            LOG.warn("node operation failed for server={}: {}", pass.name, failure.message)
        } else {
            LOG.error("node operation failed permanently for server={}: {}", pass.name, failure.message)
        }
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
        /**
         * The proxy's `spec.forwarding.secret`, when a proxy claims this server.
         *
         * **Coordinates only.** It travels into [WorkloadSpec.secretEnv] and the
         * node resolves it at the moment it hands it to the runtime, so no
         * material exists in this process, in a stored row, in an API response or
         * in a log line (CLAUDE.md invariant 4). The coordinate is in the spec
         * hash, so enrolling a server behind a proxy — or moving it to one with a
         * different secret — is a recreate, and a recreate goes through the drain
         * like every other one.
         */
        val forwardingSecret: SecretRef? = null,
    ) {
        val name: ResourceName = definition.metadata.name
        val previous: PaperServerStatus? = stored.status?.status as? PaperServerStatus
        val agent: PaperServerAgent = PaperServerAgent(definition)
        val desired: WorkloadSpec = PaperWorkloadPlanner.plan(definition, forwardingSecret)

        /**
         * Whether this loop has ever recorded a container for this workload.
         *
         * One derivation, because two rules read it and they must not disagree:
         * `DrainController.advance` uses it to tell "never created" from "the
         * runtime is not reporting a container that may still be serving players",
         * and [stopIsInFlight] uses it for the identical discrimination on the
         * identical observation. It is a fact this loop wrote down — the create
         * records it, and the teardown's partial-removal branch is the one place
         * that clears it — rather than anything a node reported this pass.
         */
        val hadContainer: Boolean get() = previous?.runtime?.containerId != null

        fun isBlockedByPermanentFailure(): Boolean {
            val failed =
                previous != null &&
                    previous.observedGeneration == stored.definition.generation &&
                    previous.failure?.failureClass == FailureClass.PERMANENT &&
                    // ...and the drain is actually parked on it, with nothing a
                    // later pass could see change. Two audits' worth of reasoning,
                    // in one expression asked by both kinds — see
                    // [parkedOnTheFailure], which also says why a drain waiting on
                    // players is not this.
                    previous.drain.parkedOnTheFailure()
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
            //
            // The exemption is [permanentFailureStopsPasses] rather than a clause
            // written here, because the drain has to be able to ask the same
            // question: what it does on a permanent abort depends on whether these
            // passes stop, and it used to assume they did.
            return failed && stored.permanentFailureStopsPasses()
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
                attentionAfter = config.drainAttentionAfter,
                attentionLedger = config.drainAttentionLedger,
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
 * Whether a `PERMANENT` failure recorded on this server stops the loop passing
 * over it again.
 *
 * The trailing clause of both `isBlockedByPermanentFailure` implementations, and
 * the **one** expression that answers this question for the whole module. A
 * terminating definition is exempt because a delete that a failure can freeze is a
 * workload nobody can retire; the passes therefore carry on, and everything that
 * reasons from *"a permanent abort stops the passes"* has to ask rather than
 * assume.
 *
 * `DrainController.abort` is the caller that made this worth extracting. Its
 * compensating seal release rests on that sentence, and it was keyed on the
 * failure class alone — one of this predicate's inputs rather than its answer — so
 * a permanent abort during a **delete** reopened the login path of a fleet whose
 * passes were still running, and the gated resume could never shut it again. The
 * twenty-seventh audit's critical. Handing the answer down costs a parameter and
 * makes the two sides one expression; copying the clause into the controller would
 * have been a third derivation of a fact that has already produced this defect
 * once.
 */
private fun StoredServer.permanentFailureStopsPasses(): Boolean = !definition.terminating

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
    /**
     * How far a drain's faults may exceed its recoveries before it is *reported*
     * as needing a human — the second, independent arm of the same flag.
     *
     * [drainAttentionAfter] measures **how long one fault has stood**, and it can
     * only fire on a fault that is still standing when the threshold passes. A
     * control endpoint that fails on one pass and behaves on the next never
     * presents one: the recovery deletes the record, the anchor restarts, and four
     * hours of a fault present half the time raised nothing at all. That is what
     * [mcorch.schema.DrainStatus.faultLedger] counts and this is where it is
     * judged.
     *
     * ## Where six comes from
     *
     * **It is not a time equivalence, and an earlier version of this paragraph
     * tried to make it one.** That draft argued six was safe because
     * [drainAttentionAfter] is three consecutive faulting passes at the backoff
     * cap, so six consecutive faults "cannot happen before three". The two halves
     * are in different units: a count is only comparable to a wall clock at one
     * cadence, and a fault streak does not reach the cap for about eight and a half
     * minutes. Six consecutive aborts requeue at one, two, four, eight and sixteen
     * seconds, so the sixth lands **around half a minute in** — and one containerd
     * blip raised the operator's single alert flag.
     *
     * The ordering is not this number's job at all. It belongs to the age gate on
     * [mcorch.schema.DrainStatus.faultLedgerSince]: the arm requires the ledger to
     * have been non-zero for [drainAttentionAfter] as well as to have reached this
     * count, so it cannot fire until the age arm has had its whole window and
     * declined. That is one rule in one unit, and it holds at every cadence rather
     * than at one.
     *
     * What is left for this number to decide is **how much evidence of intermittency
     * is a pattern**. Six is a net excess: six faults with nothing between them, or
     * twelve passes at three faults in four, or eighteen at two in three. Fewer than
     * about four and an ordinary flap — a proxy restart, a node blip and its retry —
     * would qualify. It also keeps the arithmetic the operator is told in
     * `docs/operating.md` legible: the count is the thing that has to be *earned*,
     * and the fifteen minutes is the thing that has to have *elapsed*.
     *
     * Raising it delays an intermittent report and can never make anything quieter
     * that is loud today, because the age gate already bounds this arm from below.
     * Lowering it trades evidence for latency and nothing else — the blip case is
     * held by the gate, not by the number.
     */
    val drainAttentionLedger: Int = 6,
    /**
     * The Velocity build every proxy this orchestrator creates is pinned to, or
     * null for the one it ships against
     * ([mcorch.core.proxy.VelocityWorkloadPlanner.VELOCITY_BUILD]).
     *
     * The only entry here that is not a cadence, and it is here because of what it
     * costs to get wrong. The build is a spec-hash input, so changing it drains and
     * recreates every proxy — and a proxy's own drain seals its login path and
     * waits for the last player to log off, because a fleet has one front door and
     * there is nowhere to send anybody. Until this was settable, that wait had no
     * exit an operator could reach: the value lived in a constant, so an
     * orchestrator upgrade that bumped it closed the fleet's login path with no
     * definition edit, no delete and no restart that reopened it.
     *
     * Leave it unset. Set it to the build a running fleet's containers were created
     * with to hold them still across an upgrade, or to a newer one to lead a bump.
     * Both are revertable and neither touches a server definition — which also
     * means neither lifts a permanent failure, because that gate lifts on a
     * generation bump or a delete and nothing else.
     */
    val velocityBuild: String? = null,
) {
    init {
        require(velocityBuild == null || velocityBuild.isNotBlank()) {
            "velocityBuild is the Velocity build proxies are pinned to; leave it null rather than blank"
        }
        require(statusHeartbeat.isPositive()) { "statusHeartbeat must be positive" }
        require(readinessPollInterval.isPositive()) { "readinessPollInterval must be positive" }
        require(containerPollInterval.isPositive()) { "containerPollInterval must be positive" }
        require(saveEvidenceMaxGap.isPositive()) { "saveEvidenceMaxGap must be positive" }
        require(drainAttentionAfter.isPositive()) { "drainAttentionAfter must be positive" }
        // Zero would raise the flag on every drain that has ever existed, including
        // one that has never failed — the ledger starts at zero and `>=` is the
        // test. Negative is the same thing further out.
        require(drainAttentionLedger > 0) {
            "drainAttentionLedger is a net fault count and must be positive; zero flags every drain at rest"
        }
    }
}

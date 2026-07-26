package mcorch.core.node

import mcorch.core.ExecOutcome
import mcorch.core.ExecRequest
import mcorch.core.ImageAvailability
import mcorch.core.Labels
import mcorch.core.Node
import mcorch.core.NodeCapacity
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.NodeStatus
import mcorch.core.StorageRequest
import mcorch.core.WorkloadHandle
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadRemoval
import mcorch.core.WorkloadSpec
import mcorch.core.WorkloadState
import mcorch.cri.ContainerFilter
import mcorch.cri.ContainerId
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerState
import mcorch.cri.ContainerStatus
import mcorch.cri.ContainerSummary
import mcorch.cri.CriClient
import mcorch.cri.CriClientConfig
import mcorch.cri.CriEndpoint
import mcorch.cri.CriException
import mcorch.cri.ImageName
import mcorch.cri.LinuxContainerSpec
import mcorch.cri.LinuxResources
import mcorch.cri.LinuxSandboxSpec
import mcorch.cri.PortMapping
import mcorch.cri.SandboxFilter
import mcorch.cri.SandboxId
import mcorch.cri.SandboxSpec
import mcorch.cri.SandboxStatus
import mcorch.cri.StopGracePeriod
import mcorch.cri.VolumeMount
import mcorch.schema.ImageRef
import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.store.SecretStore
import mcorch.store.StoreException
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * The [Node] that runs containers through a containerd on one host.
 *
 * **This is the only place in the orchestrator that may name a `mcorch.cri`
 * type** (CLAUDE.md invariant 7). `:core` depends on `:cri` as an
 * `implementation` dependency precisely so that nothing downstream can reach
 * around this class, and every CRI failure is translated into a
 * [NodeException] here so that no caller can pattern-match on a transport
 * detail either.
 *
 * ## What it holds together
 *
 * CRI's model is a sandbox with containers inside it, with two sharp edges:
 *
 * - `RunPodSandbox` and `CreateContainer` are **not** idempotent — a repeat
 *   raises `AlreadyExists` — so every create here lists by label and adopts
 *   first.
 * - `StopPodSandbox` and `RemovePodSandbox` **forcibly kill every container
 *   still inside, with no grace period and no save**. So the container is
 *   stopped and removed before the sandbox is touched, always, and
 *   [removeWorkload] refuses outright if it finds one still running.
 *
 * ## Persistent storage
 *
 * A [StorageRequest.Persistent] volume becomes a host directory under
 * [volumeRoot], created if it is not there and **never removed by this class**.
 * That is what makes the world survive the container (CLAUDE.md invariant 2).
 */
public class LocalNode internal constructor(
    override val name: NodeName,
    private val client: CriClient,
    private val secrets: SecretStore,
    private val volumeRoot: Path,
    private val logRoot: Path,
    private val sandboxNamespace: String,
    private val cgroupParent: String?,
) : Node,
    AutoCloseable {
    // ── health ───────────────────────────────────────────────────────────────

    override suspend fun status(): NodeStatus =
        translating(NodeOperation.STATUS) {
            val status = client.status()
            NodeStatus(
                ready = status.runtimeReady && status.networkReady,
                detail =
                    if (status.runtimeReady && status.networkReady) {
                        "containerd is ready"
                    } else {
                        status.conditions
                            .filterNot { it.status }
                            .joinToString("; ") { "${it.type}: ${it.reason}" }
                            .ifEmpty { "the runtime did not report itself ready" }
                    },
                // Nothing on a single host accounts for allocatable capacity,
                // and reporting a guess as a fact would make a real scheduler
                // place on fiction.
                capacity = NodeCapacity(),
            )
        }

    // ── observation ──────────────────────────────────────────────────────────

    override suspend fun observe(server: ResourceName): WorkloadObservation =
        translating(NodeOperation.OBSERVE) {
            val sandbox = findSandbox(server) ?: return@translating WorkloadObservation.Absent
            val status =
                try {
                    client.sandboxStatus(sandbox)
                } catch (gone: CriException.NotFound) {
                    LOG.debug("sandbox {} disappeared between the list and the status read", sandbox, gone)
                    return@translating WorkloadObservation.Absent
                }
            observationOf(server, status)
        }

    private suspend fun observationOf(
        server: ResourceName,
        status: SandboxStatus,
    ): WorkloadObservation.Present =
        WorkloadView.observe(
            node = name,
            server = server,
            sandboxId = status.id.value,
            sandboxLabels = status.labels,
            sandboxCreatedAt = status.createdAt,
            containers = containersIn(status.id, server, status.containerStatuses.map { it.toDetail() }),
        )

    /**
     * Every container in the sandbox, enumerated by `ListContainers`.
     *
     * **Not** from `PodSandboxStatusResponse.containers_statuses.** That field
     * is optional and runtime-version-dependent — it was added for evented PLEG
     * and no runtime is obliged to fill it — so an empty one cannot be told
     * apart from an empty sandbox. Believing it costs a running Paper server:
     * the workload reads as [WorkloadState.SANDBOX_ONLY], which a drain treats
     * as "nothing is running, nothing to save", and the teardown guard reads
     * the same empty list and sees nobody to protect. `ListContainers` is a
     * mandatory CRI call, so it is the one asked.
     *
     * The optional field is still used, for what it is good for: the per-status
     * detail a summary does not carry — start and finish times, exit code — for
     * the containers belonging to this server. When it is absent, that detail
     * is fetched for those containers only, which is at most one round trip and
     * only on a runtime that does not populate it. `startedAt` in particular is
     * load-bearing: the drain's save evidence is measured against it.
     */
    private suspend fun containersIn(
        sandbox: SandboxId,
        server: ResourceName?,
        reported: List<ContainerDetail>,
    ): List<ContainerView> {
        // The enumeration decides who exists. The overlay only decorates, and
        // the two are different types so they cannot be passed the wrong way
        // round.
        val listed = client.listContainers(ContainerFilter.inSandbox(sandbox)).map { it.toView() }
        val merged = WorkloadView.merge(listed, reported)
        if (server == null) return merged
        // Anything of this server's that the overlay did not describe: fetch the
        // detail the enumeration cannot carry. At most one round trip, and only
        // on a runtime that leaves the optional field empty.
        return merged.map { view ->
            if (view.labels[Labels.SERVER] == server.value && view.startedAt == null) {
                statusOrNull(ContainerId(view.id))?.toView() ?: view
            } else {
                view
            }
        }
    }

    private suspend fun statusOrNull(id: ContainerId): ContainerStatus? =
        try {
            client.containerStatus(id)
        } catch (gone: CriException.NotFound) {
            LOG.debug("container {} disappeared between the list and the status read", id, gone)
            null
        }

    /**
     * Finds this server's sandbox by label.
     *
     * By label rather than by a remembered ID: the loop's memory does not
     * survive a restart and the label does. More than one is a situation that
     * should not arise; the newest is adopted and the rest are reported loudly
     * rather than silently cleaned up, because "clean up the container I do not
     * recognise" is not a decision to make automatically.
     */
    private suspend fun findSandbox(server: ResourceName): SandboxId? {
        val found = client.listSandboxes(SandboxFilter.byLabels(Labels.selectorFor(server)))
        if (found.size > 1) {
            LOG.warn(
                "server={} has {} sandboxes on node={}; adopting the newest and leaving the rest alone",
                server,
                found.size,
                name,
            )
        }
        return found.maxByOrNull { it.createdAt }?.id
    }

    // ── images ───────────────────────────────────────────────────────────────

    override suspend fun ensureImage(image: ImageRef): ImageAvailability =
        translating(NodeOperation.IMAGE) {
            val reference = ImageName(image.canonical)
            // The anti-re-pull check. CRI reports an absent image as an empty
            // response rather than an error, so this is a question, not a
            // gamble.
            val present = client.imageStatus(reference)
            if (present != null) {
                return@translating ImageAvailability(image = image, id = present.id.value, pulled = false)
            }
            val pulled = client.pullImage(reference)
            ImageAvailability(image = image, id = pulled.value, pulled = true)
        }

    // ── workload lifecycle ───────────────────────────────────────────────────

    override suspend fun ensureWorkload(spec: WorkloadSpec): WorkloadObservation.Present =
        translating(NodeOperation.CREATE) {
            val sandboxSpec = sandboxSpecFor(spec)
            val existing = findSandbox(spec.server)

            val sandboxId =
                if (existing != null) {
                    val status = client.sandboxStatus(existing)
                    val adopted = observationOf(spec.server, status)
                    if (adopted.state != WorkloadState.SANDBOX_ONLY) {
                        // Already built. Adopting it is the whole reason a
                        // second pass is not a second container.
                        return@translating adopted
                    }
                    if (adopted.specHash != null && adopted.specHash != spec.specHash) {
                        // The sandbox was created from a different definition.
                        // CRI requires the *same* sandbox config to create a
                        // container into it, and the caller has to drain and
                        // replace rather than graft a new container onto an old
                        // sandbox.
                        return@translating adopted
                    }
                    existing
                } else {
                    prepareHostPaths(spec)
                    try {
                        client.runSandbox(sandboxSpec)
                    } catch (collision: CriException) {
                        // Something created it between the list and the create.
                        // Adopt it here rather than reporting "busy, try again":
                        // if the second look cannot find it either, the object
                        // exists under a name this build cannot see, and every
                        // future pass would repeat the same find-create-collide
                        // cycle for ever without anyone being asked to look.
                        if (!collision.isNameCollision()) throw collision
                        adoptAfterCollision(spec.server, collision)
                    }
                }

            val containerSpec = containerSpecFor(spec)
            // The same `sandboxSpec` value that created the sandbox — CRI
            // requires it back here, and a reconstructed near-copy misbehaves.
            // It is derived from the workload spec alone, so an adopted sandbox
            // rebuilds an identical one.
            val containerId =
                try {
                    client.createContainer(sandboxId, sandboxSpec, containerSpec)
                } catch (collision: CriException) {
                    if (!collision.isNameCollision()) throw collision
                    val adopted = observationOf(spec.server, client.sandboxStatus(sandboxId))
                    if (adopted.state == WorkloadState.SANDBOX_ONLY) {
                        throw unadoptable(NodeOperation.CREATE, "container for `${spec.server}`", collision)
                    }
                    return@translating adopted
                }
            WorkloadObservation.Present(
                handle = WorkloadHandle(name, sandboxId.value, containerId.value),
                state = WorkloadState.CREATED,
                specHash = spec.specHash,
                labels = spec.labels + (Labels.SPEC_HASH to spec.specHash),
                createdAt = null,
            )
        }

    /**
     * Re-reads after a create lost a race, and refuses rather than looping when
     * the object still cannot be found.
     */
    private suspend fun adoptAfterCollision(
        server: ResourceName,
        cause: CriException,
    ): SandboxId = findSandbox(server) ?: throw unadoptable(NodeOperation.CREATE, "sandbox for `$server`", cause)

    /**
     * Whether a failed create means "that name is already taken".
     *
     * Two status codes, because containerd does not use the obvious one.
     * containerd 2.3.3's CRI plugin rejects a duplicate at *name reservation*,
     * and a reservation conflict is `FAILED_PRECONDITION` — verified against a
     * real runtime, where `RunPodSandbox` and `CreateContainer` both answer
     * `failed to reserve ... name "..." is reserved for "<id>"`. Matching only
     * `ALREADY_EXISTS` made the adoption below dead code, so a create that lost
     * a race surfaced as a permanent rejection instead of adopting what is
     * already there.
     *
     * The narrowing is done here rather than in `:cri`'s code mapping, which is
     * right as it stands: `FAILED_PRECONDITION` legitimately covers other
     * things, and only a caller that has just attempted a create knows a
     * precondition failure is a name collision. The *message* carries the
     * colliding ID, and this deliberately does not parse it out — listing by
     * label finds the object whether or not containerd words it that way
     * tomorrow.
     */
    private fun CriException.isNameCollision(): Boolean =
        this is CriException.AlreadyExists || this is CriException.FailedPrecondition

    private fun unadoptable(
        operation: NodeOperation,
        what: String,
        cause: CriException,
    ): NodeException =
        NodeException.Rejected(
            name,
            operation,
            "the runtime says the $what already exists, and listing by label does not find it. Something on " +
                "this node holds the name without carrying this orchestrator's labels; retrying cannot resolve " +
                "that. Find it with `ctr`/`crictl` and either label it or remove it: ${cause.message}",
            cause,
        )

    override suspend fun startWorkload(handle: WorkloadHandle) {
        val containerId = handle.requireContainer(NodeOperation.START)
        translating(NodeOperation.START) {
            val status = client.containerStatus(containerId)
            if (status.state == ContainerState.RUNNING) {
                LOG.debug("container {} is already running", containerId)
                return@translating
            }
            client.startContainer(containerId)
        }
    }

    override suspend fun exec(
        handle: WorkloadHandle,
        request: ExecRequest,
    ): ExecOutcome {
        val containerId = handle.requireContainer(NodeOperation.EXEC)
        return translating(NodeOperation.EXEC) {
            val result = client.execSync(containerId, request.command, request.timeout)
            ExecOutcome(exitCode = result.exitCode, stdout = result.stdout, stderr = result.stderr)
        }
    }

    /**
     * Stops the container, and nothing else.
     *
     * The sandbox is deliberately left alone: `StopPodSandbox` kills whatever
     * is inside with no grace and no save, which is the opposite of what a stop
     * with a grace period is for.
     */
    override suspend fun stopWorkload(
        handle: WorkloadHandle,
        gracePeriod: Duration,
    ) {
        if (!gracePeriod.isPositive()) {
            // Refused rather than asserted: [Node] promises callers see nothing
            // but a [NodeException], and an `IllegalArgumentException` from here
            // would be the one thing a caller cannot classify. The refusal is
            // just as absolute — no stop is issued either way.
            throw NodeException.Rejected(
                name,
                NodeOperation.STOP,
                "the stop grace period must be positive; it comes from spec.lifecycle.stopGracePeriod, which " +
                    "the schema already guarantees exceeds the save timeout",
            )
        }
        val containerId =
            handle.containerId?.let(::ContainerId) ?: run {
                LOG.debug("nothing to stop for sandbox {}: no container was ever created", handle.sandboxId)
                return
            }
        translating(NodeOperation.STOP) {
            // `StopGracePeriod.of` rounds up to whole seconds, so a grace
            // period is never silently shortened.
            client.stopContainer(containerId, StopGracePeriod.of(gracePeriod))
        }
    }

    override suspend fun removeWorkload(handle: WorkloadHandle): WorkloadRemoval {
        val sandboxId = SandboxId(handle.sandboxId)
        val plan =
            translating(NodeOperation.REMOVE) {
                // Everything still inside, not just the container this workload
                // knows about. `StopPodSandbox` kills whatever is in there with
                // no grace and no save, so "my container is gone" is not the
                // question — "is the sandbox empty" is. A container this
                // orchestrator did not create, or one left by a create that
                // raced, is somebody's running process either way.
                //
                // No server and no overlay: the guard needs an id and a state,
                // and both come from the enumeration. Handing it the optional
                // status field would be handing it the empty list that made this
                // guard necessary.
                val containers = containersIn(sandboxId, server = null, reported = emptyList())
                WorkloadView.teardown(
                    own = containers.firstOrNull { it.id == handle.containerId },
                    containers = containers,
                    ownId = handle.containerId,
                )
            }

        var containerRemoved = handle.containerId == null
        for (step in plan) {
            when (step) {
                is TeardownStep.Refuse -> {
                    throw NodeException.Rejected(name, NodeOperation.REMOVE, step.reason)
                }

                is TeardownStep.RemoveContainer -> {
                    translating(NodeOperation.REMOVE) { client.removeContainer(ContainerId(step.id)) }
                    containerRemoved = true
                }

                TeardownStep.RemoveSandbox -> {
                    // Safe now, and only now: there is nothing left inside to
                    // kill. A failure here is reported rather than thrown, so
                    // the caller can record that the container is gone — if it
                    // does not, the next pass sees a sandbox with no containers,
                    // cannot tell that from a runtime hiding a live one, and
                    // never retries this.
                    val failure =
                        try {
                            client.stopSandbox(sandboxId)
                            client.removeSandbox(sandboxId)
                            null
                        } catch (failure: CriException) {
                            failure.asNodeException(NodeOperation.REMOVE)
                        }
                    if (failure != null) {
                        if (!containerRemoved) throw failure
                        LOG.warn(
                            "server workload on node={} had its container removed but its sandbox {} did not go " +
                                "away: {}",
                            name,
                            sandboxId,
                            failure.message,
                        )
                        return WorkloadRemoval(
                            containerRemoved = true,
                            sandboxRemoved = false,
                            detail = failure.message,
                        )
                    }
                    // The persistent volume directory is deliberately untouched.
                }
            }
        }
        return WorkloadRemoval.COMPLETE
    }

    private suspend fun containerStateOf(id: ContainerId): ContainerState? =
        try {
            client.containerStatus(id).state
        } catch (gone: CriException.NotFound) {
            LOG.debug("container {} is already gone", id, gone)
            null
        }

    // ── spec derivation ──────────────────────────────────────────────────────

    /**
     * Derives the sandbox configuration from the workload alone.
     *
     * Deterministic on purpose: CRI wants the identical sandbox config handed
     * back at `CreateContainer`, and the loop does not keep one between passes.
     * Same [WorkloadSpec] in, same value out — and a workload whose spec has
     * changed is drained and recreated rather than grafted onto the old
     * sandbox.
     */
    private fun sandboxSpecFor(spec: WorkloadSpec): SandboxSpec =
        SandboxSpec(
            name = "${SANDBOX_PREFIX}${spec.server.value}",
            uid = "$sandboxNamespace/${spec.server.value}",
            namespace = sandboxNamespace,
            hostname = spec.hostname,
            logDirectory = logDirectoryFor(spec.server).toString(),
            portMappings =
                spec.ports.mapNotNull { port ->
                    port.hostPort?.let { PortMapping(containerPort = port.containerPort, hostPort = it) }
                },
            labels = spec.labels + (Labels.SPEC_HASH to spec.specHash),
            // Without this, a host running the systemd cgroup driver rejects
            // every sandbox: containerd falls back to a cgroupfs-shaped path and
            // runc refuses it, wanting `slice:prefix:name`. Nothing this
            // orchestrator creates can start. See [LocalNodeConfig.cgroupParent]
            // for why it is not a constant.
            linux = LinuxSandboxSpec(cgroupParent = cgroupParent),
        )

    private suspend fun containerSpecFor(spec: WorkloadSpec): ContainerSpec =
        ContainerSpec(
            name = spec.server.value,
            image = ImageName(spec.image.canonical),
            env = spec.env + resolveSecrets(spec.secretEnv),
            command = spec.command,
            args = spec.args,
            mounts = mountsFor(spec),
            labels = spec.labels + (Labels.SPEC_HASH to spec.specHash),
            logPath = "${spec.server.value}.log",
            linux =
                LinuxContainerSpec(
                    resources =
                        LinuxResources(
                            memoryLimitBytes = spec.resources.memoryBytes,
                            cpuQuotaMicros =
                                spec.resources.cpuMillicores?.let {
                                    it.toLong() * CPU_PERIOD_MICROS / MILLICORES_PER_CORE
                                },
                            cpuPeriodMicros = spec.resources.cpuMillicores?.let { CPU_PERIOD_MICROS },
                        ),
                ),
        )

    private fun mountsFor(spec: WorkloadSpec): List<VolumeMount> =
        when (val storage = spec.storage) {
            is StorageRequest.Persistent -> {
                listOf(
                    VolumeMount(
                        containerPath = storage.mountPath,
                        hostPath = volumePathFor(storage.volume).toString(),
                    ),
                )
            }

            // The one case with no mount, and the only one that may skip it.
            is StorageRequest.Ephemeral -> {
                emptyList()
            }
        }

    /**
     * Resolves secret references at the moment they are handed to the runtime.
     *
     * The material exists as a `String` for exactly as long as it takes to put
     * it in a [ContainerSpec], whose `toString` redacts environment values.
     * Nothing here logs, wraps or returns it, and the reference — not the value
     * — is what travels through the rest of the system.
     *
     * The `String` is unavoidable: the CRI environment is a proto field of
     * strings, so somewhere between the store and the wire the material has to
     * become one. What is avoidable is the [SecretValue] outliving the call that
     * needed it — `use` hands out a copy and wipes the copy, but the original
     * lives on in whatever the store handed back — so it is destroyed here,
     * whatever happens next.
     */
    private suspend fun resolveSecrets(refs: Map<String, SecretRef>): Map<String, String> {
        if (refs.isEmpty()) return emptyMap()
        return refs.mapValues { (variable, ref) ->
            val secret =
                secrets.resolve(ref) ?: throw NodeException.Rejected(
                    name,
                    NodeOperation.CREATE,
                    "the secret `${ref.name}/${ref.key}` needed for `$variable` is not in the secret store",
                )
            try {
                secret.use { material -> String(material) }
            } finally {
                secret.destroy()
            }
        }
    }

    /**
     * Creates the host directories the runtime needs.
     *
     * The volume directory is created if it is absent and is otherwise left
     * exactly as it is — an existing world is the point of a persistent volume,
     * and this is the code path a restart goes through.
     */
    private fun prepareHostPaths(spec: WorkloadSpec) {
        // A full disk, a read-only mount, the wrong owner. None of it is a CRI
        // failure, so nothing above would translate it — see [HostPaths], which
        // owns that translation and is testable without a containerd.
        HostPaths.prepare(name, volumeRoot, logRoot, spec)
    }

    private fun volumePathFor(volume: ResourceName): Path = HostPaths.volumePath(volumeRoot, volume)

    private fun logDirectoryFor(server: ResourceName): Path = HostPaths.logDirectory(logRoot, server)

    // ── failure translation ──────────────────────────────────────────────────

    /**
     * Runs a call and converts anything it throws into a [NodeException].
     *
     * The classification of a CRI failure is not re-derived:
     * [CriException.retryable] has already decided, and this maps the subclass
     * onto the node's own vocabulary. `CancellationException` is rethrown
     * untouched, as structured concurrency requires.
     *
     * The last two clauses are what make this a boundary rather than a mapper.
     * [Node] promises that nothing but a [NodeException] comes out, and callers
     * rely on it hard: the reconcile loop's worker catches [NodeException] and
     * [mcorch.store.StoreException], and anything else escaping cancels the
     * scope that owns *every* worker. A node that reaches a full disk, a secret
     * store that is down, or a bug in this class must therefore surface as a
     * failure of one server rather than as the end of the loop. Nothing is
     * swallowed — the original is the cause of what is thrown.
     */
    private suspend fun <T> translating(
        operation: NodeOperation,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CriException) {
            throw failure.asNodeException(operation)
        } catch (failure: NodeException) {
            throw failure
        } catch (failure: StoreException) {
            // Secret resolution is the only store call a node makes. A remote
            // node would resolve it on the far side, so its failure is a node
            // failure from here.
            throw if (failure.retryable) {
                NodeException.Busy(name, operation, "the secret store is unavailable: ${failure.message}", failure)
            } else {
                NodeException.Rejected(name, operation, "the secret store rejected a read: ${failure.message}", failure)
            }
        } catch (failure: RuntimeException) {
            throw NodeException.Rejected(
                name,
                operation,
                "the node failed in a way it does not classify: ${failure::class.simpleName}: ${failure.message}",
                failure,
            )
        }

    private fun CriException.asNodeException(operation: NodeOperation): NodeException =
        when (this) {
            is CriException.Unavailable -> NodeException.Unreachable(name, operation, describe(), this)

            // The trailing argument is `commandTimeout`, carried across rather
            // than re-derived: :cri decides it by elapsed time against its own
            // deadline, and that deadline is not visible from anywhere above
            // this line. Nothing above may look at a gRPC code either, so this
            // is the only place the two kinds of timeout can be told apart.
            is CriException.Timeout -> NodeException.Timeout(name, operation, describe(), this, commandTimeout)

            is CriException.ResourceExhausted -> NodeException.Busy(name, operation, describe(), this)

            is CriException.Aborted -> NodeException.Busy(name, operation, describe(), this)

            is CriException.RuntimeFailure -> NodeException.Busy(name, operation, describe(), this)

            // A name collision from either create path has already been through
            // `adoptAfterCollision` and survived a second lookup that found
            // nothing — see [isNameCollision], and note that containerd reports
            // one as `FAILED_PRECONDITION` rather than this. Whichever code it
            // arrives as, retrying repeats the same find-create-collide cycle
            // for ever and shows `RETRYABLE` while never asking anyone to look,
            // so it is reported instead.
            is CriException.AlreadyExists -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.NotFound -> NodeException.NotFound(name, operation, describe(), this)

            is CriException.InvalidArgument -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.FailedPrecondition -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.PermissionDenied -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.Unimplemented -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.Cancelled -> NodeException.Rejected(name, operation, describe(), this)
        }

    /**
     * The runtime's account of a failure, as much of it as may be recorded.
     *
     * This is the one place a `mcorch.cri` string crosses into a
     * [NodeException], and from there into `FailureStatus.message`, SQLite and
     * the API. See [runtimeDetail] for what is withheld and why — and note that
     * the *list* is `:cri`'s, consulted here rather than copied.
     */
    private fun CriException.describe(): String =
        runtimeDetail(
            operation = operation.name,
            code = code.name,
            rendered = message,
            requestMayCarrySecrets = operation.requestMayCarrySecrets,
        )

    private fun WorkloadHandle.requireContainer(operation: NodeOperation): ContainerId =
        containerId?.let(::ContainerId)
            ?: throw NodeException.Rejected(
                name,
                operation,
                "sandbox $sandboxId has no container yet",
            )

    override fun close() {
        client.close()
    }

    public companion object {
        private val LOG = LoggerFactory.getLogger(LocalNode::class.java)
        private const val SANDBOX_PREFIX = "mcorch-"
        private const val CPU_PERIOD_MICROS = 100_000L
        private const val MILLICORES_PER_CORE = 1000L

        /**
         * Opens a node against a containerd on this host.
         *
         * Takes a [LocalNodeConfig] rather than a CRI configuration so the
         * composition root can build one without depending on `:cri` at all —
         * if wiring ever cannot construct a [Node] without reaching for a CRI
         * type, the seam is wrong.
         *
         * Does not connect eagerly: the first call is what discovers containerd
         * is down, and it fails with a retryable
         * [NodeException.Unreachable].
         */
        public fun open(
            config: LocalNodeConfig,
            secrets: SecretStore,
        ): LocalNode =
            LocalNode(
                name = config.name,
                client = CriClient.connect(CriClientConfig(endpoint = CriEndpoint.parse(config.runtimeEndpoint))),
                secrets = secrets,
                volumeRoot = config.volumeRoot,
                logRoot = config.logRoot,
                sandboxNamespace = config.sandboxNamespace,
                cgroupParent = config.cgroupParent,
            )
    }
}

/**
 * Everything the single-host node needs, in types the composition root can
 * name.
 *
 * [runtimeEndpoint] is a string in the form containerd tooling uses
 * (`unix:///run/containerd/containerd.sock`). It has no default: defaulting it
 * would let a misconfigured deployment quietly talk to the wrong containerd.
 */
public data class LocalNodeConfig(
    val name: NodeName,
    val runtimeEndpoint: String,
    /** Root of the persistent volume directories. Worlds live under here and outlive containers. */
    val volumeRoot: Path,
    /** Root of the container log directories. */
    val logRoot: Path,
    /** Groups this orchestrator's sandboxes. Not a Kubernetes namespace; nothing resolves it anywhere. */
    val sandboxNamespace: String = "mcorch",
    /**
     * The cgroup every sandbox this node creates is placed under.
     *
     * **Its shape depends on the runtime's cgroup driver, so it cannot be a
     * constant.** With the systemd driver — what containerd selects on a systemd
     * host with cgroup v2, and what `scripts/dev/containerd-up.sh` configures —
     * this is a slice name, which containerd expands into the
     * `slice:prefix:name` path runc demands. With the cgroupfs driver it is a
     * path fragment instead, so an operator on such a host sets `/mcorch`; a
     * slice name there would create a directory literally called
     * `mcorch.slice`.
     *
     * Null leaves the field unset, which is containerd's own default — and on a
     * systemd host that default is exactly what makes every `RunPodSandbox`
     * fail, so it is a deliberate opt-out rather than a sensible blank.
     *
     * The slice need not exist as a unit file: systemd creates a transient
     * slice on demand.
     */
    val cgroupParent: String? = DEFAULT_CGROUP_PARENT,
) {
    init {
        require(runtimeEndpoint.isNotBlank()) { "runtimeEndpoint must not be blank" }
        require(sandboxNamespace.isNotBlank()) { "sandboxNamespace must not be blank" }
        require(cgroupParent == null || cgroupParent.isNotBlank()) {
            "cgroupParent must not be blank; use null to leave the choice to the runtime"
        }
    }

    public companion object {
        /**
         * A systemd slice, because that is the driver containerd selects on the
         * hosts this targets. Everything the orchestrator runs lands under it,
         * so a host's Minecraft servers can be accounted for in one place.
         */
        public const val DEFAULT_CGROUP_PARENT: String = "mcorch.slice"
    }
}

/**
 * The detail view. `ListContainers` does not carry timings or exit information,
 * so a summary-derived view leaves them null rather than guessing.
 */
private fun ContainerStatus.toDetail(): ContainerDetail = ContainerDetail(toView())

private fun ContainerStatus.toView(): ContainerView =
    ContainerView(
        id = id.value,
        labels = labels,
        state = state.toWorkloadState(),
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
        exitCode = exitCode,
        reason = reason,
        message = message,
        imageId = imageId.value,
    )

private fun ContainerSummary.toView(): ContainerView =
    ContainerView(
        id = id.value,
        labels = labels,
        state = state.toWorkloadState(),
        createdAt = createdAt,
        imageId = imageId.value,
    )

private fun ContainerState.toWorkloadState(): WorkloadState =
    when (this) {
        ContainerState.CREATED -> WorkloadState.CREATED
        ContainerState.RUNNING -> WorkloadState.RUNNING
        ContainerState.EXITED -> WorkloadState.EXITED
        ContainerState.UNKNOWN -> WorkloadState.UNKNOWN
    }

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
import mcorch.core.WorkloadSpec
import mcorch.core.WorkloadState
import mcorch.cri.ContainerId
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerState
import mcorch.cri.CriClient
import mcorch.cri.CriClientConfig
import mcorch.cri.CriEndpoint
import mcorch.cri.CriException
import mcorch.cri.ImageName
import mcorch.cri.LinuxContainerSpec
import mcorch.cri.LinuxResources
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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
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

    private fun observationOf(
        server: ResourceName,
        status: SandboxStatus,
    ): WorkloadObservation.Present {
        val container =
            status.containerStatuses
                .filter { it.labels[Labels.SERVER] == server.value }
                .maxByOrNull { it.createdAt }
        val handle =
            WorkloadHandle(
                node = name,
                sandboxId = status.id.value,
                containerId = container?.id?.value,
            )
        val specHash = container?.labels?.get(Labels.SPEC_HASH) ?: status.labels[Labels.SPEC_HASH]
        if (container == null) {
            return WorkloadObservation.Present(
                handle = handle,
                state = WorkloadState.SANDBOX_ONLY,
                specHash = specHash,
                createdAt = status.createdAt,
            )
        }
        return WorkloadObservation.Present(
            handle = handle,
            state = container.state.toWorkloadState(),
            specHash = specHash,
            imageId = container.imageId.value,
            createdAt = container.createdAt,
            startedAt = container.startedAt,
            finishedAt = container.finishedAt,
            exitCode = container.exitCode,
            reason = container.reason,
            message = container.message,
        )
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
                    client.runSandbox(sandboxSpec)
                }

            val containerSpec = containerSpecFor(spec)
            // The same `sandboxSpec` value that created the sandbox — CRI
            // requires it back here, and a reconstructed near-copy misbehaves.
            // It is derived from the workload spec alone, so an adopted sandbox
            // rebuilds an identical one.
            val containerId = client.createContainer(sandboxId, sandboxSpec, containerSpec)
            WorkloadObservation.Present(
                handle = WorkloadHandle(name, sandboxId.value, containerId.value),
                state = WorkloadState.CREATED,
                specHash = spec.specHash,
                createdAt = null,
            )
        }

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
        require(gracePeriod.isPositive()) {
            "the stop grace period must be positive; it comes from spec.lifecycle.stopGracePeriod, which the " +
                "schema already guarantees exceeds the save timeout"
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

    override suspend fun removeWorkload(handle: WorkloadHandle) {
        translating(NodeOperation.REMOVE) {
            val containerId = handle.containerId?.let(::ContainerId)
            if (containerId != null) {
                val state = containerStateOf(containerId)
                if (state == ContainerState.RUNNING) {
                    // Removing a running container forcibly kills it, with no
                    // grace and no save. Whoever asked for this has skipped the
                    // drain.
                    throw NodeException.Rejected(
                        name,
                        NodeOperation.REMOVE,
                        "refusing to remove container ${containerId.value} while it is running: it must be " +
                            "drained and stopped first",
                    )
                }
                client.removeContainer(containerId)
            }
            val sandboxId = SandboxId(handle.sandboxId)
            // Safe now, and only now: there is nothing left inside to kill.
            client.stopSandbox(sandboxId)
            client.removeSandbox(sandboxId)
            // The persistent volume directory is deliberately untouched.
        }
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
     */
    private suspend fun resolveSecrets(refs: Map<String, SecretRef>): Map<String, String> {
        if (refs.isEmpty()) return emptyMap()
        return refs.mapValues { (variable, ref) ->
            val value =
                secrets.resolve(ref) ?: throw NodeException.Rejected(
                    name,
                    NodeOperation.CREATE,
                    "the secret `${ref.name}/${ref.key}` needed for `$variable` is not in the secret store",
                )
            value.use { material -> String(material) }
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
        Files.createDirectories(logDirectoryFor(spec.server))
        val storage = spec.storage
        if (storage is StorageRequest.Persistent) {
            val path = volumePathFor(storage.volume)
            if (Files.notExists(path)) {
                Files.createDirectories(path)
                LOG.info("created persistent volume directory for volume={} on node={}", storage.volume, name)
            }
        }
    }

    private fun volumePathFor(volume: ResourceName): Path = volumeRoot.resolve(volume.value)

    private fun logDirectoryFor(server: ResourceName): Path = logRoot.resolve(server.value)

    // ── failure translation ──────────────────────────────────────────────────

    /**
     * Runs a CRI call and converts anything it throws into a [NodeException].
     *
     * The classification is not re-derived: [CriException.retryable] has
     * already decided, and this maps the subclass onto the node's own
     * vocabulary. `CancellationException` is not a [CriException] and passes
     * through untouched, as structured concurrency requires.
     */
    private suspend fun <T> translating(
        operation: NodeOperation,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (failure: CriException) {
            throw failure.asNodeException(operation)
        }

    private fun CriException.asNodeException(operation: NodeOperation): NodeException =
        when (this) {
            is CriException.Unavailable -> NodeException.Unreachable(name, operation, describe(), this)

            is CriException.Timeout -> NodeException.Timeout(name, operation, describe(), this)

            is CriException.ResourceExhausted -> NodeException.Busy(name, operation, describe(), this)

            is CriException.Aborted -> NodeException.Busy(name, operation, describe(), this)

            is CriException.RuntimeFailure -> NodeException.Busy(name, operation, describe(), this)

            // A create that races another create resolves itself: the next pass
            // lists, finds it, and adopts it. So this is retryable rather than
            // a failure to report.
            is CriException.AlreadyExists -> NodeException.Busy(name, operation, describe(), this)

            is CriException.NotFound -> NodeException.NotFound(name, operation, describe(), this)

            is CriException.InvalidArgument -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.FailedPrecondition -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.PermissionDenied -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.Unimplemented -> NodeException.Rejected(name, operation, describe(), this)

            is CriException.Cancelled -> NodeException.Rejected(name, operation, describe(), this)
        }

    private fun CriException.describe(): String = message

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
) {
    init {
        require(runtimeEndpoint.isNotBlank()) { "runtimeEndpoint must not be blank" }
        require(sandboxNamespace.isNotBlank()) { "sandboxNamespace must not be blank" }
    }
}

private fun ContainerState.toWorkloadState(): WorkloadState =
    when (this) {
        ContainerState.CREATED -> WorkloadState.CREATED
        ContainerState.RUNNING -> WorkloadState.RUNNING
        ContainerState.EXITED -> WorkloadState.EXITED
        ContainerState.UNKNOWN -> WorkloadState.UNKNOWN
    }

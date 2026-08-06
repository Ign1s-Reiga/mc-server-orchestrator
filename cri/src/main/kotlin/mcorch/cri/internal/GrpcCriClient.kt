package mcorch.cri.internal

import io.grpc.ManagedChannel
import io.netty.channel.EventLoopGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mcorch.cri.ContainerFilter
import mcorch.cri.ContainerId
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerStatus
import mcorch.cri.ContainerSummary
import mcorch.cri.CriClient
import mcorch.cri.CriClientConfig
import mcorch.cri.CriException
import mcorch.cri.CriOperation
import mcorch.cri.CriStatusCode
import mcorch.cri.CriTimeouts
import mcorch.cri.ExecResult
import mcorch.cri.ExecStreams
import mcorch.cri.ImageId
import mcorch.cri.ImageInfo
import mcorch.cri.ImageName
import mcorch.cri.RegistryAuth
import mcorch.cri.RuntimeStatus
import mcorch.cri.RuntimeVersion
import mcorch.cri.SandboxFilter
import mcorch.cri.SandboxId
import mcorch.cri.SandboxSpec
import mcorch.cri.SandboxStatus
import mcorch.cri.SandboxSummary
import mcorch.cri.StopGracePeriod
import org.slf4j.LoggerFactory
import runtime.v1.ImageServiceGrpcKt
import runtime.v1.RuntimeServiceGrpcKt
import runtime.v1.containerFilter
import runtime.v1.containerStateValue
import runtime.v1.containerStatusRequest
import runtime.v1.createContainerRequest
import runtime.v1.execRequest
import runtime.v1.execSyncRequest
import runtime.v1.imageFilter
import runtime.v1.imageStatusRequest
import runtime.v1.listContainersRequest
import runtime.v1.listImagesRequest
import runtime.v1.listPodSandboxRequest
import runtime.v1.podSandboxFilter
import runtime.v1.podSandboxStateValue
import runtime.v1.podSandboxStatusRequest
import runtime.v1.pullImageRequest
import runtime.v1.removeContainerRequest
import runtime.v1.removeImageRequest
import runtime.v1.removePodSandboxRequest
import runtime.v1.runPodSandboxRequest
import runtime.v1.startContainerRequest
import runtime.v1.statusRequest
import runtime.v1.stopContainerRequest
import runtime.v1.stopPodSandboxRequest
import runtime.v1.versionRequest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gRPC-backed [CriClient].
 *
 * Stateless apart from the connection: no desired state, no caching, no
 * retrying. Every call gets a deadline and every gRPC failure is translated
 * before it leaves this class.
 */
internal class GrpcCriClient private constructor(
    private val channel: ManagedChannel,
    private val ownedEventLoopGroup: EventLoopGroup?,
    private val timeouts: CriTimeouts,
    private val endpointDescription: String,
) : CriClient {
    private val runtimeStub = RuntimeServiceGrpcKt.RuntimeServiceCoroutineStub(channel)
    private val imageStub = ImageServiceGrpcKt.ImageServiceCoroutineStub(channel)

    // ── runtime health ───────────────────────────────────────────────────────

    override suspend fun version(): RuntimeVersion =
        runtimeCall(CriOperation.VERSION, timeouts.query, target = null) { stub ->
            stub.version(versionRequest { }).toWrapper()
        }

    override suspend fun status(): RuntimeStatus =
        runtimeCall(CriOperation.RUNTIME_STATUS, timeouts.query, target = null) { stub ->
            stub.status(statusRequest { verbose = false }).toWrapper()
        }

    // ── images ───────────────────────────────────────────────────────────────

    override suspend fun imageStatus(image: ImageName): ImageInfo? =
        imageCall(CriOperation.IMAGE_STATUS, timeouts.query, target = image.value) { stub ->
            val response =
                stub.imageStatus(
                    imageStatusRequest {
                        this.image = image.toProto()
                        verbose = false
                    },
                )
            // CRI reports "not present" as an empty response, not as an error.
            if (response.hasImage()) response.image.toWrapper() else null
        }

    override suspend fun listImages(image: ImageName?): List<ImageInfo> =
        imageCall(CriOperation.LIST_IMAGES, timeouts.query, target = image?.value) { stub ->
            stub
                .listImages(
                    listImagesRequest {
                        if (image != null) filter = imageFilter { this.image = image.toProto() }
                    },
                ).imagesList
                .map { it.toWrapper() }
        }

    override suspend fun pullImage(
        image: ImageName,
        auth: RegistryAuth?,
        sandbox: SandboxSpec?,
    ): ImageId =
        imageCall(CriOperation.PULL_IMAGE, timeouts.imagePull, target = image.value) { stub ->
            val response =
                stub.pullImage(
                    pullImageRequest {
                        this.image = image.toProto()
                        if (auth != null) this.auth = auth.toProto()
                        if (sandbox != null) sandboxConfig = sandbox.toProto()
                    },
                )
            ImageId(
                response.imageRef.ifBlank {
                    throw emptyIdentifier(CriOperation.PULL_IMAGE, "image_ref")
                },
            )
        }

    override suspend fun removeImage(image: ImageName) {
        imageCall(CriOperation.REMOVE_IMAGE, timeouts.imageLifecycle, target = image.value) { stub ->
            stub.removeImage(removeImageRequest { this.image = image.toProto() })
        }
    }

    // ── sandboxes ────────────────────────────────────────────────────────────

    override suspend fun runSandbox(spec: SandboxSpec): SandboxId =
        runtimeCall(CriOperation.RUN_SANDBOX, timeouts.sandboxLifecycle, target = spec.name) { stub ->
            val response =
                stub.runPodSandbox(
                    runPodSandboxRequest {
                        config = spec.toProto()
                        runtimeHandler = spec.runtimeHandler.orEmpty()
                    },
                )
            SandboxId(
                response.podSandboxId.ifBlank {
                    throw emptyIdentifier(CriOperation.RUN_SANDBOX, "pod_sandbox_id")
                },
            )
        }

    override suspend fun stopSandbox(id: SandboxId) {
        runtimeCall(CriOperation.STOP_SANDBOX, timeouts.sandboxLifecycle, target = id.value) { stub ->
            stub.stopPodSandbox(stopPodSandboxRequest { podSandboxId = id.value })
        }
    }

    override suspend fun removeSandbox(id: SandboxId) {
        runtimeCall(CriOperation.REMOVE_SANDBOX, timeouts.sandboxLifecycle, target = id.value) { stub ->
            stub.removePodSandbox(removePodSandboxRequest { podSandboxId = id.value })
        }
    }

    override suspend fun sandboxStatus(id: SandboxId): SandboxStatus =
        runtimeCall(CriOperation.SANDBOX_STATUS, timeouts.query, target = id.value) { stub ->
            val response =
                stub.podSandboxStatus(
                    podSandboxStatusRequest {
                        podSandboxId = id.value
                        verbose = false
                    },
                )
            response.status.toWrapper(response.containersStatusesList.map { it.toWrapper() })
        }

    override suspend fun listSandboxes(filter: SandboxFilter): List<SandboxSummary> =
        runtimeCall(CriOperation.LIST_SANDBOXES, timeouts.query, target = filter.id?.value) { stub ->
            stub
                .listPodSandbox(
                    listPodSandboxRequest {
                        this.filter =
                            podSandboxFilter {
                                filter.id?.let { id = it.value }
                                filter.state?.let { state = podSandboxStateValue { this.state = it.toProto() } }
                                labelSelector.putAll(filter.labelSelector)
                            }
                    },
                ).itemsList
                .map { it.toWrapper() }
        }

    // ── containers ───────────────────────────────────────────────────────────

    override suspend fun createContainer(
        sandboxId: SandboxId,
        sandboxSpec: SandboxSpec,
        spec: ContainerSpec,
    ): ContainerId =
        runtimeCall(CriOperation.CREATE_CONTAINER, timeouts.containerLifecycle, target = sandboxId.value) { stub ->
            val response =
                stub.createContainer(
                    createContainerRequest {
                        podSandboxId = sandboxId.value
                        config = spec.toProto()
                        // CRI requires the sandbox config the sandbox was
                        // created with, handed back verbatim.
                        sandboxConfig = sandboxSpec.toProto()
                    },
                )
            ContainerId(
                response.containerId.ifBlank {
                    throw emptyIdentifier(CriOperation.CREATE_CONTAINER, "container_id")
                },
            )
        }

    override suspend fun startContainer(id: ContainerId) {
        runtimeCall(CriOperation.START_CONTAINER, timeouts.containerLifecycle, target = id.value) { stub ->
            stub.startContainer(startContainerRequest { containerId = id.value })
        }
    }

    override suspend fun stopContainer(
        id: ContainerId,
        gracePeriod: StopGracePeriod,
    ) {
        // The transport deadline outlasts the grace period, so containerd's kill
        // fires before the RPC gives up and the caller learns the container
        // actually stopped — up to [CriTimeouts.stopDeadlineCap], past which the
        // two part company on purpose. The deadline bounds how long this call
        // may park its caller; the grace period is what containerd is asked to
        // wait, and one is not the other. See [CriTimeouts.stopDeadlineCap].
        val capped = gracePeriod.duration > timeouts.stopDeadlineCap
        val waited = if (capped) timeouts.stopDeadlineCap else gracePeriod.duration
        val deadline = waited + timeouts.deadlineSlack
        val startedAt = System.nanoTime()
        try {
            runtimeCall(CriOperation.STOP_CONTAINER, deadline, target = id.value) { stub ->
                stub.stopContainer(
                    stopContainerRequest {
                        containerId = id.value
                        // The whole grace period, never the capped deadline.
                        // Shortening what is *sent* would shorten the
                        // last-resort net a save depends on, and would make the
                        // caller's own overdue accounting — which measures a
                        // container against the period the runtime was given —
                        // call a container late while it was still inside it.
                        timeout = gracePeriod.seconds
                    },
                )
            }
        } catch (timedOut: CriException.Timeout) {
            if (!capped) throw timedOut
            throw attributeCappedStop(timedOut, gracePeriod, deadline, System.nanoTime() - startedAt)
        }
    }

    /**
     * Says what a `StopContainer` timeout means when the deadline was capped
     * below the grace period, because by default it reads as the opposite.
     *
     * An uncapped stop that times out is alarming: the deadline outlasted the
     * grace period, so containerd should have killed the container and did not
     * answer. A *capped* one is not. It is the expected end of a stop whose grace
     * period is longer than one call is allowed to wait, the runtime is not
     * implicated, and the difference is invisible in the status code — both are
     * `DEADLINE_EXCEEDED`. Left undistinguished this is the `ExecSync` mistake
     * again ([attributeExecTimeout]), on the one operation where a healthy node
     * being reported as a sick one lands in the middle of a drain.
     *
     * The measurement is the same one and the same one-sided inequality, read the
     * other way round: grpc raises a client-side `DEADLINE_EXCEEDED` at or after
     * the deadline and never before it, so an [elapsedNanos] that reached
     * [deadline] is this client giving up and the sentence below is true of it.
     * Anything shorter came back for some other reason and is reported unchanged.
     *
     * [CriException.Timeout.commandTimeout] stays false, which is not an
     * oversight: it means "the runtime answered, promptly, to report a timeout
     * the caller asked for", and none of that happened here. This client stopped
     * waiting.
     */
    private fun attributeCappedStop(
        failure: CriException.Timeout,
        gracePeriod: StopGracePeriod,
        deadline: Duration,
        elapsedNanos: Long,
    ): CriException.Timeout {
        if (elapsedNanos.nanoseconds < deadline) return failure
        return CriException.Timeout(
            operation = CriOperation.STOP_CONTAINER,
            description =
                "gave up after $deadline while the ${gracePeriod.seconds}s grace period this stop asked for was " +
                    "still running: a stop may wait at most ${timeouts.stopDeadlineCap} for the runtime, and this " +
                    "grace period is longer than that. The runtime was asked for the whole ${gracePeriod.seconds}s " +
                    "and nothing shortened it — it has the stop signal, and it will not escalate to a kill for a " +
                    "call that has already given up. Nothing here says the runtime is unhealthy. Re-issue the stop, " +
                    "which is idempotent and delivers the signal again, or read the container's state to see where " +
                    "it got to. It said: " + failure.description,
            cause = failure.cause,
        )
    }

    override suspend fun removeContainer(id: ContainerId) {
        runtimeCall(CriOperation.REMOVE_CONTAINER, timeouts.containerLifecycle, target = id.value) { stub ->
            stub.removeContainer(removeContainerRequest { containerId = id.value })
        }
    }

    override suspend fun containerStatus(id: ContainerId): ContainerStatus =
        runtimeCall(CriOperation.CONTAINER_STATUS, timeouts.query, target = id.value) { stub ->
            stub
                .containerStatus(
                    containerStatusRequest {
                        containerId = id.value
                        verbose = false
                    },
                ).status
                .toWrapper()
        }

    override suspend fun listContainers(filter: ContainerFilter): List<ContainerSummary> =
        runtimeCall(CriOperation.LIST_CONTAINERS, timeouts.query, target = filter.sandboxId?.value) { stub ->
            stub
                .listContainers(
                    listContainersRequest {
                        this.filter =
                            containerFilter {
                                filter.id?.let { id = it.value }
                                filter.sandboxId?.let { podSandboxId = it.value }
                                filter.state?.let { state = containerStateValue { this.state = it.toProto() } }
                                labelSelector.putAll(filter.labelSelector)
                            }
                    },
                ).containersList
                .map { it.toWrapper() }
        }

    // ── exec ─────────────────────────────────────────────────────────────────

    override suspend fun execSync(
        id: ContainerId,
        command: List<String>,
        timeout: Duration,
    ): ExecResult {
        require(command.isNotEmpty()) { "exec command must not be empty" }
        require(timeout.isPositive()) {
            "execSync timeout must be positive; CRI treats 0 as 'run forever', which would let a stuck " +
                "command pin the reconcile loop on a call that never returns"
        }
        // CRI carries the command timeout as whole seconds. Give the transport
        // strictly more room, so a command that outruns its own timeout is
        // reported by containerd rather than cut off as a transport deadline.
        val commandSeconds = timeout.roundUpToWholeSeconds()
        val deadline = commandSeconds.seconds + timeouts.deadlineSlack
        val startedAt = System.nanoTime()
        return try {
            runtimeCall(CriOperation.EXEC_SYNC, deadline, target = id.value) { stub ->
                stub
                    .execSync(
                        execSyncRequest {
                            containerId = id.value
                            cmd += command
                            this.timeout = commandSeconds
                        },
                    ).toWrapper()
            }
        } catch (timedOut: CriException.Timeout) {
            throw attributeExecTimeout(timedOut, commandSeconds, deadline, System.nanoTime() - startedAt)
        }
    }

    /**
     * Says whose clock ran out when an `ExecSync` came back `DEADLINE_EXCEEDED`.
     *
     * containerd enforces the command timeout itself and reports the expiry as
     * `DEADLINE_EXCEEDED` — the same code this client's own transport deadline
     * produces. Left undistinguished, a Server List Ping that takes longer than
     * its ten seconds against a Paper server still generating a world is
     * indistinguishable from a containerd that has stopped answering, and gets
     * reported as an unreachable node. It was.
     *
     * The discriminator is time, not message text — descriptions are free-form
     * and change between releases (see [translateStatus]). grpc raises a
     * client-side `DEADLINE_EXCEEDED` at or after the deadline and never before
     * it, and [elapsedNanos] is measured after the call returned, so anything
     * shorter than the deadline cannot be this client giving up. containerd
     * answered, and the command timeout is the only deadline it was given.
     *
     * The inequality is deliberately one-sided: when the two are too close to
     * separate — a [CriTimeouts.deadlineSlack] configured down to nothing — this
     * reports the ordinary transport timeout, which is the cautious answer.
     */
    private fun attributeExecTimeout(
        failure: CriException.Timeout,
        commandSeconds: Long,
        deadline: Duration,
        elapsedNanos: Long,
    ): CriException.Timeout {
        if (elapsedNanos.nanoseconds >= deadline) return failure
        return CriException.Timeout(
            operation = CriOperation.EXEC_SYNC,
            description =
                "the command did not finish within the ${commandSeconds}s timeout it was given, and the runtime " +
                    "stopped it. The runtime answered this promptly, so it is reachable and healthy as far as " +
                    "this call can tell — the command was slow, which is not the same thing. It said: " +
                    failure.description,
            cause = failure.cause,
            commandTimeout = true,
        )
    }

    override suspend fun execStreamUrl(
        id: ContainerId,
        command: List<String>,
        streams: ExecStreams,
    ): String {
        require(command.isNotEmpty()) { "exec command must not be empty" }
        return runtimeCall(CriOperation.EXEC, timeouts.query, target = id.value) { stub ->
            val response =
                stub.exec(
                    execRequest {
                        containerId = id.value
                        cmd += command
                        tty = streams.tty
                        stdin = streams.stdin
                        stdout = streams.stdout
                        stderr = streams.stderr
                    },
                )
            response.url.ifBlank { throw emptyIdentifier(CriOperation.EXEC, "url") }
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    override suspend fun shutdown(gracePeriod: Duration) {
        logger.debug("cri shutdown endpoint={} graceMs={}", endpointDescription, gracePeriod.inWholeMilliseconds)
        withContext(Dispatchers.IO) {
            channel.shutdown()
            channel.awaitTermination(gracePeriod.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (!channel.isTerminated) {
                logger.warn("cri channel did not terminate within the grace period; cancelling in-flight calls")
                channel.shutdownNow()
                channel.awaitTermination(FORCED_SHUTDOWN_WAIT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
            releaseEventLoopGroup(gracePeriod)
        }
    }

    override fun close() {
        channel.shutdownNow()
        channel.awaitTermination(FORCED_SHUTDOWN_WAIT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        releaseEventLoopGroup(FORCED_SHUTDOWN_WAIT)
    }

    private fun releaseEventLoopGroup(timeout: Duration) {
        val group = ownedEventLoopGroup ?: return
        if (group.isShuttingDown) return
        group
            .shutdownGracefully(0, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private suspend fun <T> runtimeCall(
        operation: CriOperation,
        timeout: Duration,
        target: String?,
        block: suspend (RuntimeServiceGrpcKt.RuntimeServiceCoroutineStub) -> T,
    ): T =
        instrumented(operation, timeout, target) {
            block(runtimeStub.withDeadlineAfter(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
        }

    private suspend fun <T> imageCall(
        operation: CriOperation,
        timeout: Duration,
        target: String?,
        block: suspend (ImageServiceGrpcKt.ImageServiceCoroutineStub) -> T,
    ): T =
        instrumented(operation, timeout, target) {
            block(imageStub.withDeadlineAfter(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
        }

    /**
     * Deadline, logging and error translation, applied uniformly.
     *
     * A failure logs the runtime's own description alongside the code, because
     * without it the actionable half of a CRI failure exists nowhere but on the
     * observed status of whichever server happened to make the call — and a call
     * with no server to hang itself on, or one whose status write also failed,
     * then leaves nothing behind at all.
     *
     * **The description is a third party's free-form string and is treated as
     * one.** It is bounded to [MAX_LOGGED_DESCRIPTION_CHARS] here, and it is
     * withheld for some operations by [CriException.safeDescription] — that is
     * where the decision lives and where to change it, not here.
     *
     * If you are here to restore the full text because a failure was hard to
     * diagnose: it is not on the server's observed status either, which withholds
     * the same operations for the same reason. The place it does exist is the
     * container runtime's own log on the node, which is what
     * [CriException.WITHHELD_DESCRIPTION] tells the operator to read.
     *
     * Nothing else from the request reaches the log. Specs, environment and
     * registry credentials never appear.
     */
    private suspend fun <T> instrumented(
        operation: CriOperation,
        timeout: Duration,
        target: String?,
        block: suspend () -> T,
    ): T {
        val startedAt = System.nanoTime()
        logger.debug("cri call op={} target={} timeoutMs={}", operation, target ?: "-", timeout.inWholeMilliseconds)
        try {
            val result = translatingErrors(operation) { block() }
            logger.debug("cri ok op={} target={} durationMs={}", operation, target ?: "-", elapsedMillis(startedAt))
            return result
        } catch (e: CriException) {
            logger.warn(
                "cri failed op={} target={} code={} retryable={} durationMs={} detail={}",
                operation,
                target ?: "-",
                e.code,
                e.retryable,
                elapsedMillis(startedAt),
                loggableDetail(e),
            )
            throw e
        }
    }

    /**
     * The most of a failure description that may be written to a log.
     *
     * The withholding is [CriException.safeDescription]'s and is not repeated
     * here. What *is* only the log's business is the cap: a bound on a third
     * party's string so a runtime that renders a whole request into an error
     * cannot flood the log with it. The persisted copy is deliberately not
     * capped — see [CriException.safeDescription] for why a prefix is not a safe
     * unit to keep.
     */
    private fun loggableDetail(failure: CriException): String {
        val safe = failure.safeDescription
        if (safe.length <= MAX_LOGGED_DESCRIPTION_CHARS) return safe
        val dropped = safe.length - MAX_LOGGED_DESCRIPTION_CHARS
        return safe.take(MAX_LOGGED_DESCRIPTION_CHARS) + "… [$dropped more characters not logged]"
    }

    private fun elapsedMillis(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    /**
     * A response that succeeded and then named nothing.
     *
     * Every operation this can be raised for is one whose *request* carries
     * secret material, so the description is marked as ours rather than the
     * runtime's. Otherwise a precise report of a broken runtime would be
     * replaced by a warning about secrets that this sentence does not contain.
     */
    private fun emptyIdentifier(
        operation: CriOperation,
        field: String,
    ): CriException =
        CriException.RuntimeFailure(
            operation = operation,
            code = CriStatusCode.UNKNOWN,
            description = "containerd returned a successful response with an empty $field",
            cause = null,
            describedByRuntime = false,
        )

    internal companion object {
        private val logger = LoggerFactory.getLogger("mcorch.cri.CriClient")

        private val FORCED_SHUTDOWN_WAIT: Duration = 2.seconds

        /**
         * How much of a runtime failure description reaches the log.
         *
         * Enough for containerd's own one-line errors, which is what almost
         * every failure actually is, and short enough that a runtime which
         * renders a whole rejected request into its error cannot fill the log
         * with it. The bound exists because the string is a third party's and
         * has no length contract, not because 300 is special.
         */
        internal const val MAX_LOGGED_DESCRIPTION_CHARS: Int = 300

        fun connect(config: CriClientConfig): CriClient {
            val handle = buildChannel(config)
            logger.debug("cri channel created endpoint={}", config.endpoint.description)
            return GrpcCriClient(
                channel = handle.channel,
                ownedEventLoopGroup = handle.eventLoopGroup,
                timeouts = config.timeouts,
                endpointDescription = config.endpoint.description,
            )
        }

        /**
         * Test seam: wraps a channel this class does not own, so the wrapper can
         * be driven over grpc's in-process transport with no socket and no
         * containerd.
         */
        fun forChannel(
            channel: ManagedChannel,
            timeouts: CriTimeouts = CriTimeouts(),
        ): CriClient =
            GrpcCriClient(
                channel = channel,
                ownedEventLoopGroup = null,
                timeouts = timeouts,
                endpointDescription = "in-process",
            )
    }
}

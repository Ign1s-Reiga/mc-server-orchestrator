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
        // The transport deadline must outlast the grace period, or the RPC gives
        // up before containerd's kill fires and the caller cannot tell whether
        // the container stopped.
        val deadline = gracePeriod.duration + timeouts.deadlineSlack
        runtimeCall(CriOperation.STOP_CONTAINER, deadline, target = id.value) { stub ->
            stub.stopContainer(
                stopContainerRequest {
                    containerId = id.value
                    timeout = gracePeriod.seconds
                },
            )
        }
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
        return runtimeCall(CriOperation.EXEC_SYNC, deadline, target = id.value) { stub ->
            stub
                .execSync(
                    execSyncRequest {
                        containerId = id.value
                        cmd += command
                        this.timeout = commandSeconds
                    },
                ).toWrapper()
        }
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
     * Only operation names, container/sandbox IDs, image references and status
     * codes reach the log. Specs, environment, registry credentials and sandbox
     * IPs never do.
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
                "cri failed op={} target={} code={} retryable={} durationMs={}",
                operation,
                target ?: "-",
                e.code,
                e.retryable,
                elapsedMillis(startedAt),
            )
            throw e
        }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    private fun emptyIdentifier(
        operation: CriOperation,
        field: String,
    ): CriException =
        CriException.RuntimeFailure(
            operation,
            CriStatusCode.UNKNOWN,
            "containerd returned a successful response with an empty $field",
        )

    internal companion object {
        private val logger = LoggerFactory.getLogger("mcorch.cri.CriClient")

        private val FORCED_SHUTDOWN_WAIT: Duration = 2.seconds

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

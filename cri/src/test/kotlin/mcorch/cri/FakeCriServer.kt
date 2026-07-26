package mcorch.cri

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CompletableDeferred
import mcorch.cri.internal.GrpcCriClient
import runtime.v1.Api
import runtime.v1.ImageServiceGrpcKt
import runtime.v1.RuntimeServiceGrpcKt
import java.util.concurrent.TimeUnit

/**
 * A fake CRI runtime over grpc's in-process transport.
 *
 * The point is to exercise the *real* wrapper — real stubs, real deadlines, real
 * error translation — with no socket, no netty and no containerd, so the whole
 * suite runs under `./gradlew build` on a machine with no container runtime.
 * Behaviour against a real containerd is `:app:integrationTest`'s job.
 */
internal class FakeCriServer(
    private val runtime: RuntimeBehaviour = RuntimeBehaviour(),
    private val images: ImageBehaviour = ImageBehaviour(),
) : AutoCloseable {
    private val name: String = InProcessServerBuilder.generateName()

    private val server: Server =
        InProcessServerBuilder
            .forName(name)
            .addService(runtime)
            .addService(images)
            .build()
            .start()

    private val channel: ManagedChannel =
        InProcessChannelBuilder
            .forName(name)
            .build()

    /** The wrapper under test, wired to this fake. */
    val client: CriClient = GrpcCriClient.forChannel(channel, CriTimeouts())

    fun clientWith(timeouts: CriTimeouts): CriClient = GrpcCriClient.forChannel(channel, timeouts)

    override fun close() {
        channel.shutdownNow()
        server.shutdownNow()
        server.awaitTermination(5, TimeUnit.SECONDS)
    }

    /**
     * Every RPC the wrapper issues against `RuntimeService`. Unset behaviours
     * inherit the generated base class, which answers `UNIMPLEMENTED`.
     */
    internal class RuntimeBehaviour(
        /** Thrown by every implemented RPC when set. */
        var failWith: Status? = null,
        var version: Api.VersionResponse = Api.VersionResponse.getDefaultInstance(),
        var status: Api.StatusResponse = Api.StatusResponse.getDefaultInstance(),
        var runSandbox: Api.RunPodSandboxResponse = Api.RunPodSandboxResponse.getDefaultInstance(),
        var sandboxStatus: Api.PodSandboxStatusResponse = Api.PodSandboxStatusResponse.getDefaultInstance(),
        var listSandboxes: Api.ListPodSandboxResponse = Api.ListPodSandboxResponse.getDefaultInstance(),
        var createContainer: Api.CreateContainerResponse = Api.CreateContainerResponse.getDefaultInstance(),
        var containerStatus: Api.ContainerStatusResponse = Api.ContainerStatusResponse.getDefaultInstance(),
        var listContainers: Api.ListContainersResponse = Api.ListContainersResponse.getDefaultInstance(),
        var execSync: Api.ExecSyncResponse = Api.ExecSyncResponse.getDefaultInstance(),
        var exec: Api.ExecResponse = Api.ExecResponse.getDefaultInstance(),
        /** When true, implemented RPCs suspend forever so deadlines and cancellation can be observed. */
        var hang: Boolean = false,
    ) : RuntimeServiceGrpcKt.RuntimeServiceCoroutineImplBase() {
        /** Completes once a hanging call has actually reached the server. */
        val serverReached: CompletableDeferred<Unit> = CompletableDeferred()

        /** Completes when a hanging call is cancelled, proving cancellation reached the server. */
        val hangCancelled: CompletableDeferred<Unit> = CompletableDeferred()

        /** The last request of each kind the wrapper actually sent. */
        var lastStopContainer: Api.StopContainerRequest? = null
        var lastExecSync: Api.ExecSyncRequest? = null
        var lastExec: Api.ExecRequest? = null
        var lastCreateContainer: Api.CreateContainerRequest? = null
        var lastRunSandbox: Api.RunPodSandboxRequest? = null
        var lastListContainers: Api.ListContainersRequest? = null
        var lastListSandboxes: Api.ListPodSandboxRequest? = null

        private suspend fun <T> respond(value: T): T {
            failWith?.let { throw StatusException(it) }
            if (hang) {
                serverReached.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    hangCancelled.complete(Unit)
                    throw e
                }
            }
            return value
        }

        override suspend fun version(request: Api.VersionRequest): Api.VersionResponse = respond(version)

        override suspend fun status(request: Api.StatusRequest): Api.StatusResponse = respond(status)

        override suspend fun runPodSandbox(request: Api.RunPodSandboxRequest): Api.RunPodSandboxResponse {
            lastRunSandbox = request
            return respond(runSandbox)
        }

        override suspend fun stopPodSandbox(request: Api.StopPodSandboxRequest): Api.StopPodSandboxResponse =
            respond(Api.StopPodSandboxResponse.getDefaultInstance())

        override suspend fun removePodSandbox(request: Api.RemovePodSandboxRequest): Api.RemovePodSandboxResponse =
            respond(Api.RemovePodSandboxResponse.getDefaultInstance())

        override suspend fun podSandboxStatus(request: Api.PodSandboxStatusRequest): Api.PodSandboxStatusResponse =
            respond(sandboxStatus)

        override suspend fun listPodSandbox(request: Api.ListPodSandboxRequest): Api.ListPodSandboxResponse {
            lastListSandboxes = request
            return respond(listSandboxes)
        }

        override suspend fun createContainer(request: Api.CreateContainerRequest): Api.CreateContainerResponse {
            lastCreateContainer = request
            return respond(createContainer)
        }

        override suspend fun startContainer(request: Api.StartContainerRequest): Api.StartContainerResponse =
            respond(Api.StartContainerResponse.getDefaultInstance())

        override suspend fun stopContainer(request: Api.StopContainerRequest): Api.StopContainerResponse {
            lastStopContainer = request
            return respond(Api.StopContainerResponse.getDefaultInstance())
        }

        override suspend fun removeContainer(request: Api.RemoveContainerRequest): Api.RemoveContainerResponse =
            respond(Api.RemoveContainerResponse.getDefaultInstance())

        override suspend fun containerStatus(request: Api.ContainerStatusRequest): Api.ContainerStatusResponse =
            respond(containerStatus)

        override suspend fun listContainers(request: Api.ListContainersRequest): Api.ListContainersResponse {
            lastListContainers = request
            return respond(listContainers)
        }

        override suspend fun execSync(request: Api.ExecSyncRequest): Api.ExecSyncResponse {
            lastExecSync = request
            return respond(execSync)
        }

        override suspend fun exec(request: Api.ExecRequest): Api.ExecResponse {
            lastExec = request
            return respond(exec)
        }
    }

    /** Every RPC the wrapper issues against `ImageService`. */
    internal class ImageBehaviour(
        var failWith: Status? = null,
        var imageStatus: Api.ImageStatusResponse = Api.ImageStatusResponse.getDefaultInstance(),
        var listImages: Api.ListImagesResponse = Api.ListImagesResponse.getDefaultInstance(),
        var pullImage: Api.PullImageResponse = Api.PullImageResponse.getDefaultInstance(),
    ) : ImageServiceGrpcKt.ImageServiceCoroutineImplBase() {
        var lastPullImage: Api.PullImageRequest? = null
        var lastImageStatus: Api.ImageStatusRequest? = null

        private fun <T> respond(value: T): T {
            failWith?.let { throw StatusException(it) }
            return value
        }

        override suspend fun imageStatus(request: Api.ImageStatusRequest): Api.ImageStatusResponse {
            lastImageStatus = request
            return respond(imageStatus)
        }

        override suspend fun listImages(request: Api.ListImagesRequest): Api.ListImagesResponse = respond(listImages)

        override suspend fun pullImage(request: Api.PullImageRequest): Api.PullImageResponse {
            lastPullImage = request
            return respond(pullImage)
        }

        override suspend fun removeImage(request: Api.RemoveImageRequest): Api.RemoveImageResponse =
            respond(Api.RemoveImageResponse.getDefaultInstance())
    }
}

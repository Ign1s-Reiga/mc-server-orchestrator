package mcorch.cri

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import runtime.v1.Api
import runtime.v1.container
import runtime.v1.containerMetadata
import runtime.v1.containerStatus
import runtime.v1.containerStatusResponse
import runtime.v1.image
import runtime.v1.imageSpec
import runtime.v1.imageStatusResponse
import runtime.v1.listContainersResponse
import runtime.v1.podIP
import runtime.v1.podSandboxMetadata
import runtime.v1.podSandboxNetworkStatus
import runtime.v1.podSandboxStatus
import runtime.v1.podSandboxStatusResponse
import runtime.v1.runtimeCondition
import runtime.v1.runtimeStatus
import runtime.v1.statusResponse
import runtime.v1.versionResponse
import java.time.Instant

/** What containerd says, turned into the wrapper's own types. */
class ResponseMappingTest {
    @Test
    fun `an absent image is reported as null, not as a failure`() =
        runCriTest {
            val images = FakeCriServer.ImageBehaviour(imageStatus = Api.ImageStatusResponse.getDefaultInstance())
            FakeCriServer(images = images).use { fake ->
                // This is what keeps reconcile from re-pulling on every pass.
                fake.client.imageStatus(ImageName("itzg/minecraft-server:latest")).shouldBeNull()
            }
        }

    @Test
    fun `a present image is reported with its resolved id`() =
        runCriTest {
            val images =
                FakeCriServer.ImageBehaviour(
                    imageStatus =
                        imageStatusResponse {
                            image =
                                image {
                                    id = "sha256:feed"
                                    repoTags += "docker.io/itzg/minecraft-server:latest"
                                    repoDigests += "docker.io/itzg/minecraft-server@sha256:feed"
                                    size = 512L
                                    pinned = true
                                }
                        },
                )
            FakeCriServer(images = images).use { fake ->
                val info = fake.client.imageStatus(ImageName("itzg/minecraft-server:latest")).shouldNotBeNull()

                info.id shouldBe ImageId("sha256:feed")
                info.repoTags shouldContainExactly listOf("docker.io/itzg/minecraft-server:latest")
                info.sizeBytes shouldBe 512L
                info.pinned shouldBe true
            }
        }

    @Test
    fun `container status timestamps and exit code follow CRI's not-specified conventions`() =
        runCriTest {
            val running =
                FakeCriServer.RuntimeBehaviour(
                    containerStatus =
                        containerStatusResponse {
                            status =
                                containerStatus {
                                    id = "c1"
                                    metadata = containerMetadata { name = "paper" }
                                    state = Api.ContainerState.CONTAINER_RUNNING
                                    createdAt = 1_700_000_000_000_000_000L
                                    startedAt = 1_700_000_001_000_000_000L
                                    // finishedAt stays 0 - not specified.
                                    exitCode = 0
                                    image = imageSpec { image = "itzg/minecraft-server:latest" }
                                    imageId = "sha256:feed"
                                }
                        },
                )
            FakeCriServer(runtime = running).use { fake ->
                val status = fake.client.containerStatus(ContainerId("c1"))

                status.state shouldBe ContainerState.RUNNING
                status.createdAt shouldBe Instant.ofEpochSecond(1_700_000_000L)
                status.startedAt shouldBe Instant.ofEpochSecond(1_700_000_001L)
                status.finishedAt.shouldBeNull()
                // exit_code is only meaningful once finished_at is set; reporting
                // 0 here would look like a clean exit from a running server.
                status.exitCode.shouldBeNull()
                status.imageId shouldBe ImageId("sha256:feed")
            }
        }

    @Test
    fun `an exited container reports its exit code`() =
        runCriTest {
            val exited =
                FakeCriServer.RuntimeBehaviour(
                    containerStatus =
                        containerStatusResponse {
                            status =
                                containerStatus {
                                    id = "c1"
                                    metadata = containerMetadata { name = "paper" }
                                    state = Api.ContainerState.CONTAINER_EXITED
                                    createdAt = 1_700_000_000_000_000_000L
                                    startedAt = 1_700_000_001_000_000_000L
                                    finishedAt = 1_700_000_099_000_000_000L
                                    exitCode = 137
                                    reason = "OOMKilled"
                                    image = imageSpec { image = "itzg/minecraft-server:latest" }
                                    imageId = "sha256:feed"
                                }
                        },
                )
            FakeCriServer(runtime = exited).use { fake ->
                val status = fake.client.containerStatus(ContainerId("c1"))

                status.state shouldBe ContainerState.EXITED
                status.exitCode shouldBe 137
                status.reason shouldBe "OOMKilled"
                status.finishedAt shouldBe Instant.ofEpochSecond(1_700_000_099L)
            }
        }

    @Test
    fun `sandbox status collects every ip and the statuses of its containers`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    sandboxStatus =
                        podSandboxStatusResponse {
                            status =
                                podSandboxStatus {
                                    id = "s1"
                                    metadata =
                                        podSandboxMetadata {
                                            name = "survival-1"
                                            uid = "u"
                                            namespace = "mcorch"
                                            attempt = 2
                                        }
                                    state = Api.PodSandboxState.SANDBOX_READY
                                    createdAt = 1_700_000_000_000_000_000L
                                    network =
                                        podSandboxNetworkStatus {
                                            ip = "10.87.0.5"
                                            additionalIps += podIP { ip = "fd00::5" }
                                        }
                                }
                            containersStatuses +=
                                containerStatus {
                                    id = "c1"
                                    metadata = containerMetadata { name = "paper" }
                                    state = Api.ContainerState.CONTAINER_RUNNING
                                    createdAt = 1_700_000_000_000_000_000L
                                    image = imageSpec { image = "itzg/minecraft-server:latest" }
                                    imageId = "sha256:feed"
                                }
                        },
                )
            FakeCriServer(runtime = runtime).use { fake ->
                val status = fake.client.sandboxStatus(SandboxId("s1"))

                status.state shouldBe SandboxState.READY
                status.metadata.attempt shouldBe 2u
                status.ips shouldContainExactly listOf("10.87.0.5", "fd00::5")
                status.containerStatuses.single().id shouldBe ContainerId("c1")
                // Addresses must not reach a log line even by accident.
                status.toString().contains("10.87.0.5") shouldBe false
            }
        }

    @Test
    fun `runtime status exposes the readiness conditions a startup check needs`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    status =
                        statusResponse {
                            status =
                                runtimeStatus {
                                    conditions +=
                                        runtimeCondition {
                                            type = "RuntimeReady"
                                            status = true
                                        }
                                    conditions +=
                                        runtimeCondition {
                                            type = "NetworkReady"
                                            status = false
                                            reason = "NetworkPluginNotReady"
                                            message = "cni plugin not initialized"
                                        }
                                }
                        },
                    version =
                        versionResponse {
                            version = "v1"
                            runtimeName = "containerd"
                            runtimeVersion = "2.3.3"
                            runtimeApiVersion = "v1"
                        },
                )
            FakeCriServer(runtime = runtime).use { fake ->
                val status = fake.client.status()
                status.runtimeReady shouldBe true
                // A sandbox will not start without CNI; this is the cheap way to find out.
                status.networkReady shouldBe false

                val version = fake.client.version()
                version.runtimeName shouldBe "containerd"
                version.runtimeVersion shouldBe "2.3.3"
            }
        }

    @Test
    fun `listing returns an empty list rather than failing when nothing matches`() =
        runCriTest {
            FakeCriServer().use { fake ->
                fake.client.listContainers(ContainerFilter.byLabels(mapOf("a" to "b"))) shouldBe emptyList()
                fake.client.listSandboxes(SandboxFilter.byLabels(mapOf("a" to "b"))) shouldBe emptyList()
                fake.client.listImages() shouldBe emptyList()
            }
        }

    @Test
    fun `container summaries carry the sandbox they belong to`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    listContainers =
                        listContainersResponse {
                            containers +=
                                container {
                                    id = "c1"
                                    podSandboxId = "s1"
                                    metadata = containerMetadata { name = "paper" }
                                    state = Api.ContainerState.CONTAINER_RUNNING
                                    createdAt = 1_700_000_000_000_000_000L
                                    image = imageSpec { image = "itzg/minecraft-server:latest" }
                                    imageId = "sha256:feed"
                                    labels.put("mcorch.dev/server", "survival-1")
                                }
                        },
                )
            FakeCriServer(runtime = runtime).use { fake ->
                val summary = fake.client.listContainers().single()

                summary.id shouldBe ContainerId("c1")
                summary.sandboxId shouldBe SandboxId("s1")
                summary.state shouldBe ContainerState.RUNNING
                summary.labels shouldBe mapOf("mcorch.dev/server" to "survival-1")
            }
        }
}

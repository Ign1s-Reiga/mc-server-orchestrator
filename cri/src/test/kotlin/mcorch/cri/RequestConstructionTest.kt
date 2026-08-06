package mcorch.cri

import com.google.protobuf.ByteString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import runtime.v1.Api
import runtime.v1.createContainerResponse
import runtime.v1.execResponse
import runtime.v1.execSyncResponse
import runtime.v1.pullImageResponse
import runtime.v1.runPodSandboxResponse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the wrapper actually puts on the wire. These are the fields that
 * "generate but do not behave" if they are wrong.
 */
class RequestConstructionTest {
    @Test
    fun `stopContainer sends the grace period as whole seconds`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour()
            FakeCriServer(runtime = runtime).use { fake ->
                fake.client.stopContainer(ContainerId("c1"), StopGracePeriod.ofSeconds(120))

                val sent = runtime.lastStopContainer.shouldNotBeNull()
                sent.containerId shouldBe "c1"
                sent.timeout shouldBe 120L
            }
        }

    @Test
    fun `a fractional grace period rounds up, never down`() {
        // Rounding down would shorten the safety net below the configured value.
        StopGracePeriod.of(90.5.seconds).seconds shouldBe 91L
        StopGracePeriod.of(1.milliseconds).seconds shouldBe 1L
        StopGracePeriod.of(90.seconds).seconds shouldBe 90L
    }

    @Test
    fun `a zero or negative grace period cannot be constructed by accident`() {
        shouldThrow<IllegalArgumentException> { StopGracePeriod.of(0.seconds) }
        shouldThrow<IllegalArgumentException> { StopGracePeriod.of((-5).seconds) }
        shouldThrow<IllegalArgumentException> { StopGracePeriod.ofSeconds(0) }

        // The zero case exists, but only under a name that shows up in a drain audit.
        StopGracePeriod.IMMEDIATE_KILL.seconds shouldBe 0L
        StopGracePeriod.IMMEDIATE_KILL.toString() shouldBe "IMMEDIATE_KILL"
    }

    @Test
    fun `createContainer passes back the same sandbox config the sandbox was created with`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    runSandbox = runPodSandboxResponse { podSandboxId = "sandbox-1" },
                    createContainer = createContainerResponse { containerId = "container-1" },
                )
            FakeCriServer(runtime = runtime).use { fake ->
                val spec = sampleSandboxSpec()
                val sandboxId = fake.client.runSandbox(spec)
                fake.client.createContainer(sandboxId, spec, sampleContainerSpec())

                val run = runtime.lastRunSandbox.shouldNotBeNull()
                val create = runtime.lastCreateContainer.shouldNotBeNull()
                create.podSandboxId shouldBe "sandbox-1"
                // CRI requires the identical PodSandboxConfig on CreateContainer.
                create.sandboxConfig shouldBe run.config
            }
        }

    @Test
    fun `container config carries command, env, mounts and labels`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(createContainer = createContainerResponse { containerId = "c" })
            FakeCriServer(runtime = runtime).use { fake ->
                val spec =
                    sampleContainerSpec(env = mapOf("EULA" to "TRUE", "MEMORY" to "4G")).copy(
                        command = listOf("/entrypoint.sh"),
                        args = listOf("--nogui"),
                        linux =
                            LinuxContainerSpec(
                                resources =
                                    LinuxResources(
                                        memoryLimitBytes = 4L * 1024 * 1024 * 1024,
                                        cpuShares = 1024,
                                    ),
                                securityContext = LinuxSecurityContext(runAsUser = 1000, runAsGroup = 1000),
                            ),
                    )
                fake.client.createContainer(SandboxId("s"), sampleSandboxSpec(), spec)

                val config = runtime.lastCreateContainer.shouldNotBeNull().config
                config.metadata.name shouldBe "paper"
                config.image.image shouldBe "docker.io/itzg/minecraft-server:latest"
                config.commandList shouldContainExactly listOf("/entrypoint.sh")
                config.argsList shouldContainExactly listOf("--nogui")
                config.envsList.associate { it.key to it.value } shouldBe
                    mapOf("EULA" to "TRUE", "MEMORY" to "4G")
                config.mountsList.single().containerPath shouldBe "/data"
                config.mountsList.single().hostPath shouldBe "/var/lib/mcorch/worlds/survival-1"
                config.mountsList
                    .single()
                    .readonly
                    .shouldBeFalse()
                config.labelsMap shouldBe mapOf("mcorch.dev/server" to "survival-1")
                config.linux.resources.memoryLimitInBytes shouldBe 4L * 1024 * 1024 * 1024
                config.linux.securityContext.runAsUser.value shouldBe 1000L
            }
        }

    @Test
    fun `execSync sends the command and rounds its timeout up to whole seconds`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    execSync =
                        execSyncResponse {
                            exitCode = 0
                            stdout = ByteString.copyFromUtf8("Saved the game")
                        },
                )
            FakeCriServer(runtime = runtime).use { fake ->
                val result =
                    fake.client.execSync(ContainerId("c1"), listOf("rcon-cli", "save-all", "flush"), 30.5.seconds)

                val sent = runtime.lastExecSync.shouldNotBeNull()
                sent.containerId shouldBe "c1"
                sent.cmdList shouldContainExactly listOf("rcon-cli", "save-all", "flush")
                sent.timeout shouldBe 31L
                result.exitCode shouldBe 0
                result.stdout shouldBe "Saved the game"
                result.succeeded.shouldBeTrue()
            }
        }

    @Test
    fun `execSync surfaces a non-zero exit without throwing, so the caller can judge the save`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    execSync =
                        execSyncResponse {
                            exitCode = 1
                            stderr = ByteString.copyFromUtf8("Connection refused")
                        },
                )
            FakeCriServer(runtime = runtime).use { fake ->
                val result = fake.client.execSync(ContainerId("c1"), listOf("rcon-cli", "save-all"), 5.seconds)

                result.exitCode shouldBe 1
                result.succeeded.shouldBeFalse()
                result.stderr shouldBe "Connection refused"
            }
        }

    @Test
    fun `exec stream request honours the requested streams`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    exec = execResponse { url = "http://127.0.0.1:10010/exec/abc" },
                )
            FakeCriServer(runtime = runtime).use { fake ->
                fake.client.execStreamUrl(ContainerId("c1"), listOf("bash"), ExecStreams.INTERACTIVE_TTY) shouldBe
                    "http://127.0.0.1:10010/exec/abc"

                val sent = runtime.lastExec.shouldNotBeNull()
                sent.tty.shouldBeTrue()
                sent.stdin.shouldBeTrue()
                sent.stdout.shouldBeTrue()
                // CRI forbids stderr with a tty.
                sent.stderr.shouldBeFalse()
            }
        }

    @Test
    fun `list filters are translated, including label selectors`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour()
            FakeCriServer(runtime = runtime).use { fake ->
                fake.client.listContainers(
                    ContainerFilter(
                        sandboxId = SandboxId("s1"),
                        state = ContainerState.RUNNING,
                        labelSelector = mapOf("mcorch.dev/server" to "survival-1"),
                    ),
                )
                fake.client.listSandboxes(SandboxFilter(state = SandboxState.READY))

                val containers = runtime.lastListContainers.shouldNotBeNull().filter
                containers.podSandboxId shouldBe "s1"
                containers.state.state shouldBe Api.ContainerState.CONTAINER_RUNNING
                containers.labelSelectorMap shouldBe mapOf("mcorch.dev/server" to "survival-1")

                val sandboxes = runtime.lastListSandboxes.shouldNotBeNull().filter
                sandboxes.state.state shouldBe Api.PodSandboxState.SANDBOX_READY
            }
        }

    @Test
    fun `an unknown sandbox state cannot be used as a filter`() {
        shouldThrow<IllegalArgumentException> { SandboxFilter(state = SandboxState.UNKNOWN) }
    }

    @Test
    fun `pullImage carries auth and an optional sandbox context`() =
        runCriTest {
            val images =
                FakeCriServer.ImageBehaviour(
                    pullImage = pullImageResponse { imageRef = "sha256:abcd" },
                )
            FakeCriServer(images = images).use { fake ->
                val id =
                    fake.client.pullImage(
                        ImageName("docker.io/itzg/minecraft-server:latest"),
                        auth = RegistryAuth(username = "bot", password = "placeholder-not-a-real-credential"),
                        sandbox = sampleSandboxSpec(),
                    )

                id shouldBe ImageId("sha256:abcd")
                val sent = images.lastPullImage.shouldNotBeNull()
                sent.image.image shouldBe "docker.io/itzg/minecraft-server:latest"
                sent.auth.username shouldBe "bot"
                sent.hasSandboxConfig().shouldBeTrue()
            }
        }

    @Test
    fun `an empty identifier in a successful response is reported rather than propagated`() =
        runCriTest {
            // A blank ID would otherwise blow up later, far from the cause.
            val images = FakeCriServer.ImageBehaviour(pullImage = Api.PullImageResponse.getDefaultInstance())
            FakeCriServer(images = images).use { fake ->
                val thrown = shouldThrow<CriException> { fake.client.pullImage(ImageName("x:1")) }
                thrown.operation shouldBe CriOperation.PULL_IMAGE
                thrown.retryable.shouldBeTrue()
            }
        }
}

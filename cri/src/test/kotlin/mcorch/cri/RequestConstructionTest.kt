package mcorch.cri

import com.google.protobuf.ByteString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import runtime.v1.Api
import runtime.v1.createContainerResponse
import runtime.v1.execResponse
import runtime.v1.execSyncResponse
import runtime.v1.pullImageResponse
import runtime.v1.runPodSandboxResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
                fake.client.stopContainer(ContainerId("c1"), StopGracePeriod.ofSeconds(120).getOrThrow())

                val sent = runtime.lastStopContainer.shouldNotBeNull()
                sent.containerId shouldBe "c1"
                sent.timeout shouldBe 120L
            }
        }

    @Test
    fun `a fractional grace period rounds up, never down`() {
        // Rounding down would shorten the safety net below the configured value.
        StopGracePeriod.of(90.5.seconds).getOrThrow().seconds shouldBe 91L
        StopGracePeriod.of(1.milliseconds).getOrThrow().seconds shouldBe 1L
        StopGracePeriod.of(90.seconds).getOrThrow().seconds shouldBe 90L
    }

    @Test
    fun `a zero or negative grace period cannot be constructed by accident`() {
        StopGracePeriod.of(0.seconds).isFailure.shouldBeTrue()
        StopGracePeriod.of((-5).seconds).isFailure.shouldBeTrue()
        StopGracePeriod.ofSeconds(0).isFailure.shouldBeTrue()

        // The zero case exists, but only under a name that shows up in a drain audit.
        StopGracePeriod.IMMEDIATE_KILL.seconds shouldBe 0L
        StopGracePeriod.IMMEDIATE_KILL.toString() shouldBe "IMMEDIATE_KILL"
    }

    @Test
    fun `a grace period containerd would invert into a kill cannot be constructed`() {
        // The defect this closes is not "the stop fails". It is that containerd
        // multiplies these seconds by a billion into an int64 nanosecond count,
        // and above MAX_SECONDS that product wraps: the runtime then kills the
        // container with little or no grace and answers `{}` — the same empty
        // message a stop that waited the full period returns. Nothing downstream
        // can tell the two apart, so the value has to be refused before it is
        // sent. The numbers are measured; see StopGracePeriod.MAX_SECONDS.
        StopGracePeriod.ofSeconds(StopGracePeriod.MAX_SECONDS).getOrThrow().seconds shouldBe
            StopGracePeriod.MAX_SECONDS

        // One second further is where containerd stopped waiting and started
        // killing on the pinned runtime.
        StopGracePeriod.ofSeconds(StopGracePeriod.MAX_SECONDS + 1).isFailure.shouldBeTrue()
        StopGracePeriod.ofSeconds(Long.MAX_VALUE).isFailure.shouldBeTrue()

        // 18446744083s is the row that matters most: it wraps to a *positive*
        // 9.29s, so containerd signals, waits and kills exactly as a healthy stop
        // looks. 584 years asked for, nine seconds served.
        StopGracePeriod.ofSeconds(18_446_744_083L).isFailure.shouldBeTrue()

        // Reached through a Duration as well, since that is the form the node
        // hands in. Rounding up must not be a way over the line either.
        StopGracePeriod.of(StopGracePeriod.MAX_SECONDS.seconds).getOrThrow().seconds shouldBe
            StopGracePeriod.MAX_SECONDS
        StopGracePeriod.of((StopGracePeriod.MAX_SECONDS.seconds) + 1.milliseconds).isFailure.shouldBeTrue()
        StopGracePeriod.of((400 * 365).days).isFailure.shouldBeTrue()

        // Infinity is refused before the rounding runs, not by the range check:
        // rounding an infinite duration up would add one to Long.MAX_VALUE and
        // hand the range check a negative number, which reads as "not positive".
        val infinite = StopGracePeriod.of(Duration.INFINITE)
        infinite.isFailure.shouldBeTrue()
        infinite.exceptionOrNull()?.message.shouldNotBeNull() shouldContain "finite"
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

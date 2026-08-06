package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.WorkloadHandle
import mcorch.core.coreTest
import mcorch.cri.ContainerFilter
import mcorch.cri.ContainerId
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerStatus
import mcorch.cri.ContainerSummary
import mcorch.cri.CriClient
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
import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.store.SecretStore
import mcorch.store.SecretValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * What [LocalNode.stopWorkload] does with the grace period it is handed, before
 * anything reaches containerd.
 *
 * This guard used to be a local `gracePeriod.isPositive()` check standing in
 * front of a [StopGracePeriod] that enforced strictly more, so the two disagreed
 * about which values were acceptable — and a value in the gap failed deep inside
 * the call instead, as an unclassified `RuntimeException` that the node's
 * catch-all turns into a *non-retryable* rejection. A permanent abort of a
 * drain, produced by an argument, with the container runtime never consulted.
 *
 * The wider gap was on the other side: a grace period large enough to overflow
 * containerd's own seconds-to-nanoseconds conversion passed both checks and was
 * sent, and containerd answers such a request by killing the container almost
 * immediately and reporting success. Bigger was not safer. So these assertions
 * are as much about **what never reaches the client** as about what the caller
 * sees.
 */
class StopGraceGuardTest {
    @Test
    fun `a grace period containerd would invert never leaves the node`(
        @TempDir root: Path,
    ) = coreTest {
        // One second over StopGracePeriod.MAX_SECONDS. Measured against
        // containerd 2.3.3: a request of this size is answered by killing the
        // container in under half a second, with an empty success response that
        // is indistinguishable from a stop which waited the whole period.
        val overflowing = (StopGracePeriod.MAX_SECONDS + 1).seconds
        val client = RefusingCriClient()

        val thrown =
            shouldThrow<NodeException.Rejected> { node(client, root).stopWorkload(handle(), overflowing) }

        thrown.operation shouldBe NodeOperation.STOP
        // Names the constraint and where the value came from, so an operator
        // reading a failed drain is not left with "the node failed in a way it
        // does not classify".
        thrown.message.shouldNotBeNull() shouldContain "${StopGracePeriod.MAX_SECONDS}"
        thrown.message.shouldNotBeNull() shouldContain "spec.lifecycle.stopGracePeriod"
        // The whole point: containerd was never asked.
        client.stops.shouldBeEmpty()
    }

    @Test
    fun `an unbounded grace period is refused by the rule that owns it, not by the catch-all`(
        @TempDir root: Path,
    ) = coreTest {
        // Duration.INFINITE cleared the node's own guard and failed inside the
        // CRI call instead. Same verdict either way, but reached by the rule
        // that owns it, and with a message that says which rule.
        val client = RefusingCriClient()

        val thrown =
            shouldThrow<NodeException.Rejected> { node(client, root).stopWorkload(handle(), Duration.INFINITE) }

        thrown.operation shouldBe NodeOperation.STOP
        thrown.message.shouldNotBeNull() shouldContain "finite"
        thrown.message.shouldNotBeNull() shouldNotContain "does not classify"
        client.stops.shouldBeEmpty()
    }

    @Test
    fun `zero and negative are still refused`(
        @TempDir root: Path,
    ) = coreTest {
        for (bad in listOf(Duration.ZERO, (-1).seconds, (-30).days)) {
            val client = RefusingCriClient()
            shouldThrow<NodeException.Rejected> { node(client, root).stopWorkload(handle(), bad) }
            client.stops.shouldBeEmpty()
        }
    }

    @Test
    fun `a long but legal grace period is passed through whole and unshortened`(
        @TempDir root: Path,
    ) = coreTest {
        // Two hours is the schema's cap for a Paper server, and it is nowhere
        // near the runtime's limit — the guard must not be mistaken for a policy
        // on how long a save may take.
        val client = RefusingCriClient()
        node(client, root).stopWorkload(handle(), 2.hours)

        client.stops shouldBe listOf(ContainerId("c1") to StopGracePeriod.ofSeconds(7200).getOrThrow())
    }

    @Test
    fun `the largest grace period the runtime honours is accepted`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RefusingCriClient()
        node(client, root).stopWorkload(handle(), StopGracePeriod.MAX_SECONDS.seconds)

        client.stops
            .single()
            .second.seconds shouldBe StopGracePeriod.MAX_SECONDS
    }

    @Test
    fun `a workload with no container is a no-op, and is still argument-checked first`(
        @TempDir root: Path,
    ) = coreTest {
        val sandboxOnly = WorkloadHandle(NODE, "s1", containerId = null)

        val quiet = RefusingCriClient()
        node(quiet, root).stopWorkload(sandboxOnly, 30.seconds)
        quiet.stops.shouldBeEmpty()

        // A nonsense grace period is refused whether or not there is anything to
        // stop. The argument is wrong either way, and a caller that only learns
        // so once a container exists learns so during a drain.
        val refused = RefusingCriClient()
        shouldThrow<NodeException.Rejected> { node(refused, root).stopWorkload(sandboxOnly, Duration.INFINITE) }
        refused.stops.shouldBeEmpty()
    }

    private fun node(
        client: CriClient,
        root: Path,
    ): LocalNode =
        LocalNode(
            name = NODE,
            client = client,
            secrets = UnusedSecretStore,
            volumeRoot = root.resolve("volumes").createDirectories(),
            logRoot = root.resolve("logs").createDirectories(),
            assetRoot = root.resolve("assets").createDirectories(),
            sandboxNamespace = "mcorch-test",
            cgroupParent = null,
        )

    private fun handle(): WorkloadHandle = WorkloadHandle(NODE, "s1", "c1")

    private companion object {
        val NODE: NodeName = NodeName.of("test-node").getOrThrow()
    }
}

/**
 * A CRI client that records stops and refuses everything else.
 *
 * Every other member throws rather than returning a benign default: a stop guard
 * test that quietly let another call through would be asserting less than it
 * appears to.
 */
private class RefusingCriClient : CriClient {
    val stops: MutableList<Pair<ContainerId, StopGracePeriod>> = mutableListOf()

    override suspend fun stopContainer(
        id: ContainerId,
        gracePeriod: StopGracePeriod,
    ) {
        stops += id to gracePeriod
    }

    override suspend fun version(): RuntimeVersion = unexpected("version")

    override suspend fun status(): RuntimeStatus = unexpected("status")

    override suspend fun imageStatus(image: ImageName): ImageInfo? = unexpected("imageStatus")

    override suspend fun listImages(image: ImageName?): List<ImageInfo> = unexpected("listImages")

    override suspend fun pullImage(
        image: ImageName,
        auth: RegistryAuth?,
        sandbox: SandboxSpec?,
    ): ImageId = unexpected("pullImage")

    override suspend fun removeImage(image: ImageName): Unit = unexpected("removeImage")

    override suspend fun runSandbox(spec: SandboxSpec): SandboxId = unexpected("runSandbox")

    override suspend fun stopSandbox(id: SandboxId): Unit = unexpected("stopSandbox")

    override suspend fun removeSandbox(id: SandboxId): Unit = unexpected("removeSandbox")

    override suspend fun sandboxStatus(id: SandboxId): SandboxStatus = unexpected("sandboxStatus")

    override suspend fun listSandboxes(filter: SandboxFilter): List<SandboxSummary> = unexpected("listSandboxes")

    override suspend fun createContainer(
        sandboxId: SandboxId,
        sandboxSpec: SandboxSpec,
        spec: ContainerSpec,
    ): ContainerId = unexpected("createContainer")

    override suspend fun startContainer(id: ContainerId): Unit = unexpected("startContainer")

    override suspend fun removeContainer(id: ContainerId): Unit = unexpected("removeContainer")

    override suspend fun containerStatus(id: ContainerId): ContainerStatus = unexpected("containerStatus")

    override suspend fun listContainers(filter: ContainerFilter): List<ContainerSummary> = unexpected("listContainers")

    override suspend fun execSync(
        id: ContainerId,
        command: List<String>,
        timeout: Duration,
    ): ExecResult = unexpected("execSync")

    override suspend fun execStreamUrl(
        id: ContainerId,
        command: List<String>,
        streams: ExecStreams,
    ): String = unexpected("execStreamUrl")

    override suspend fun shutdown(gracePeriod: Duration) = Unit

    override fun close() = Unit

    private fun unexpected(operation: String): Nothing =
        error("the stop guard test reached $operation; nothing but stopContainer should be called")
}

private object UnusedSecretStore : SecretStore {
    override suspend fun put(
        ref: SecretRef,
        value: SecretValue,
    ): Unit = error("the stop path resolves no secrets")

    override suspend fun resolve(ref: SecretRef): SecretValue? = error("the stop path resolves no secrets")

    override suspend fun contains(ref: SecretRef): Boolean = error("the stop path resolves no secrets")

    override suspend fun removeKey(ref: SecretRef): Boolean = error("the stop path resolves no secrets")

    override suspend fun removeSecret(name: ResourceName): Int = error("the stop path resolves no secrets")

    override suspend fun listNames(): List<ResourceName> = error("the stop path resolves no secrets")

    override suspend fun listKeys(name: ResourceName): List<String> = error("the stop path resolves no secrets")

    override fun close() = Unit
}

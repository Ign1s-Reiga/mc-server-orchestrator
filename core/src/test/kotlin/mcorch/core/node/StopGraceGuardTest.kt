package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.StopGraceCeiling
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
    /**
     * **Rewritten by the twenty-ninth audit's third finding, and the claim changed.**
     *
     * It used to assert that a value containerd's arithmetic would invert is
     * *refused*. It is now **capped** at [StopGraceCeiling.MAX] and the stop goes
     * out, so the refusal it asserted no longer happens. The property it existed for
     * is stronger than before rather than weaker: such a value cannot reach
     * containerd through this node at all, and now for a structural reason rather
     * than because one guard says no.
     *
     * Why the verdict changed is in [StopGraceCeiling]: the operation being refused
     * was the **stop**, and a stop nobody can issue is a populated, world-holding
     * server nobody can retire. The cap is safe because nothing reaches
     * `Node.stopWorkload` except through the zero-player gate and `mayStop`, so a
     * completed save is already confirmed and the grace period is the last-resort
     * net.
     */
    @Test
    fun `a grace period containerd would invert is capped, not sent`(
        @TempDir root: Path,
    ) = coreTest {
        // One second over StopGracePeriod.MAX_SECONDS. Measured against
        // containerd 2.3.3: a request of this size is answered by killing the
        // container in under half a second, with an empty success response that
        // is indistinguishable from a stop which waited the whole period.
        val overflowing = (StopGracePeriod.MAX_SECONDS + 1).seconds
        val client = RefusingCriClient()

        node(client, root).stopWorkload(handle(), overflowing)

        // The whole point, unchanged: containerd was never asked to wait that
        // long. It was asked to wait the ceiling.
        client.stops shouldBe listOf(ContainerId("c1") to StopGracePeriod.ofSeconds(7200).getOrThrow())
        StopGraceCeiling.MAX shouldBe 2.hours
    }

    /**
     * The bound itself, called directly with the inputs no scenario can produce.
     *
     * A rule with call sites in one implementation is a rule a unit test has to be
     * able to reach; the node test above can only drive the one path it drives.
     */
    @Test
    fun `the ceiling caps a long finite grace period and leaves everything else alone`() {
        StopGraceCeiling.bound(30.seconds) shouldBe 30.seconds
        StopGraceCeiling.bound(StopGraceCeiling.MAX) shouldBe StopGraceCeiling.MAX
        StopGraceCeiling.bound(StopGraceCeiling.MAX + 1.seconds) shouldBe StopGraceCeiling.MAX
        StopGraceCeiling.bound(StopGracePeriod.MAX_SECONDS.seconds) shouldBe StopGraceCeiling.MAX
        // Not a duration anybody meant. Capping it would turn an argument the code
        // cannot interpret into a plausible-looking stop, so it is handed on
        // untouched to the rule that refuses it and says why.
        StopGraceCeiling.bound(Duration.INFINITE) shouldBe Duration.INFINITE
        StopGraceCeiling.bound(Duration.ZERO) shouldBe Duration.ZERO
        StopGraceCeiling.bound((-30).days) shouldBe (-30).days
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

    /**
     * Also rewritten by the twenty-ninth audit's third finding — see the note on
     * `a grace period containerd would invert is capped, not sent`.
     *
     * The largest value the *runtime* honours is 292 years, and the reason it may
     * not be sent has nothing to do with containerd: `GrpcCriClient.stopContainer`
     * derives its gRPC deadline as `gracePeriod + slack`, so a stop with that grace
     * period is a reconcile worker parked at a container that will not exit, with no
     * effective timeout — the one property CLAUDE.md requires of every call crossing
     * the `:cri` boundary. The bound that bites here is the node's own.
     */
    @Test
    fun `the largest grace period the runtime honours is still capped, because the call is deadlined off it`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RefusingCriClient()
        node(client, root).stopWorkload(handle(), StopGracePeriod.MAX_SECONDS.seconds)

        client.stops
            .single()
            .second.seconds shouldBe StopGraceCeiling.MAX.inWholeSeconds
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

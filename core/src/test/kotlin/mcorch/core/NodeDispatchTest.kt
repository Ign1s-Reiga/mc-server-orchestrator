package mcorch.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import mcorch.core.node.LocalNode
import mcorch.core.paper.PaperWorkloadPlanner
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
import mcorch.cri.SandboxMetadata
import mcorch.cri.SandboxSpec
import mcorch.cri.SandboxState
import mcorch.cri.SandboxStatus
import mcorch.cri.SandboxSummary
import mcorch.cri.StopGracePeriod
import mcorch.schema.FailureClass
import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.store.SecretStore
import mcorch.store.SecretValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [NodeDispatch]: *was anything sent*, asked of the failure rather than
 * reconstructed at the call site.
 *
 * Every audit round that has touched a compensating edge has needed this fact and
 * had to derive it from something that correlates with it — the drain's state, the
 * exception's subclass, a timestamp stamped before the call — and each derivation
 * was correct at the one site it was written for. The tests here hold three
 * things:
 *
 * 1. the default is the cautious answer, for **every** member of the sealed
 *    hierarchy, so a subclass added later cannot claim more than it knows;
 * 2. the answer is independent of `retryable` in both directions, because the
 *    retryable/permanent classification is load-bearing and audited and this
 *    change may not move it;
 * 3. the sites in the single-host node that can prove nothing was sent say so,
 *    and the ones that cannot do not.
 *
 * Nothing in `:core` **branches** on the property yet, by design: the guards that
 * would consult it are drain guards and they go through `drain-auditor` before
 * they move. So these are the tests that make the fact true; the tests that make
 * it load-bearing come with whichever guard adopts it.
 */
internal class NodeDispatchTest {
    /**
     * The safe default, checked against the sealed hierarchy rather than against a
     * list somebody remembered to extend.
     *
     * `sealedSubclasses` is the enforcement point. A sixth `NodeException` written
     * with `NodeDispatch.NOTHING_SENT` as its default — which reads perfectly
     * reasonable for, say, an `Unbuildable` — reddens this without anybody having
     * to think of it, and that is the direction that costs a world: a caller
     * licensed to re-send a `save-all flush` or to re-admit players behind a
     * `SIGTERM` on the strength of a claim nothing established.
     */
    @Test
    fun `every node failure defaults to the answer that assumes the request landed`() {
        val defaults =
            listOf(
                NodeException.Unreachable(NODE, NodeOperation.OBSERVE, "no answer"),
                NodeException.Timeout(NODE, NodeOperation.EXEC, "no answer"),
                NodeException.Busy(NODE, NodeOperation.CREATE, "out of room"),
                NodeException.NotFound(NODE, NodeOperation.STATUS, "gone"),
                NodeException.Rejected(NODE, NodeOperation.STOP, "refused"),
            )

        // The control: every subclass is represented above, so the assertion below
        // is about the hierarchy and not about five instances of it.
        defaults.map { it::class }.shouldContainExactlyInAnyOrder(NodeException::class.sealedSubclasses)

        for (failure in defaults) {
            withClue(failure::class.simpleName.orEmpty()) {
                failure.dispatch shouldBe NodeDispatch.UNKNOWN
            }
        }
    }

    /**
     * The constraint this change was given: it may not move a single
     * retryable/permanent verdict.
     *
     * Asserted as an independence property over the whole 5x2 matrix rather than as
     * five values, because the failure mode is not "somebody edits `retryable`" —
     * it is a future reader folding the two questions into one, which is exactly
     * what the two-derivations findings in this repo have all been. Both mappings
     * that consume a [NodeException] on the status path are included: the class and
     * the reason.
     */
    @Test
    fun `saying nothing was sent changes no classification`() {
        for (dispatch in NodeDispatch.entries) {
            for (operation in NodeOperation.entries) {
                val cases =
                    listOf(
                        NodeException.Unreachable(NODE, operation, "m", null, dispatch) to true,
                        NodeException.Timeout(NODE, operation, "m", null, false, dispatch) to true,
                        NodeException.Busy(NODE, operation, "m", null, dispatch) to true,
                        NodeException.NotFound(NODE, operation, "m", null, dispatch) to false,
                        NodeException.Rejected(NODE, operation, "m", null, dispatch) to false,
                    )
                for ((failure, retryable) in cases) {
                    withClue("$dispatch/$operation/${failure::class.simpleName}") {
                        failure.retryable shouldBe retryable
                        failure.asFailureClass() shouldBe
                            if (retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT
                        // The reason is a function of the operation alone, and
                        // stays one.
                        failure.asFailureReason() shouldBe
                            NodeException.Busy(NODE, operation, "m").asFailureReason()
                    }
                }
            }
        }
    }

    /**
     * The grace period refused above every call the method makes.
     *
     * This is the case the thirty-second and thirty-third audits kept coming back
     * to. `DrainController.stop` stamps `DrainStatus.stopDispatchedAt` *before* it
     * calls, deliberately — losing that record re-admits players to a container in
     * shutdown — so on this path the drain records a `SIGTERM` that was never sent
     * and `restoreRegistration` then refuses to put the backend back. The record is
     * not changed here. What changes is that the fact is now on the failure, so the
     * repair is a guard reading one property rather than a fourth proxy for it.
     *
     * `client.stops` is asserted empty beside it: the property is *nothing was
     * sent*, and reading it off the exception alone would pass against a node that
     * set the flag and called anyway.
     */
    @Test
    fun `a stop refused on its own argument sent nothing, and says so`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RecordingCriClient()

        val refused =
            shouldThrow<NodeException.Rejected> {
                node(client, root).stopWorkload(handle(), StopGrace.of(Duration.INFINITE, NO_WORLD))
            }

        refused.dispatch shouldBe NodeDispatch.NOTHING_SENT
        refused.operation shouldBe NodeOperation.STOP
        // Unchanged, and the point of the previous test: a permanent refusal that
        // dispatched nothing is an ordinary combination, not a contradiction.
        refused.retryable shouldBe false
        client.stops.shouldBeEmpty()
        client.calls.shouldBeEmpty()
    }

    /**
     * The precondition both `startWorkload` and `exec` check above their
     * `translating` block.
     *
     * `EXEC` is the one that matters: it is the operation a world save travels on,
     * and `PaperServerAgent.requestSave` may only leave `saveRequestedAt` unset —
     * which is what lets a later pass send a second `save-all flush` — when nothing
     * reached the server. Today it concludes that from *which subclass* it caught;
     * from here it can be told.
     */
    @Test
    fun `a call against a workload with no container sent nothing, on both operations`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RecordingCriClient()
        val node = node(client, root)
        val sandboxOnly = WorkloadHandle(NODE, "s1", containerId = null)

        val start = shouldThrow<NodeException.Rejected> { node.startWorkload(sandboxOnly) }
        val exec =
            shouldThrow<NodeException.Rejected> {
                node.exec(sandboxOnly, ExecRequest(listOf("true"), ExecTimeout.of(5.seconds)))
            }

        start.dispatch shouldBe NodeDispatch.NOTHING_SENT
        start.operation shouldBe NodeOperation.START
        exec.dispatch shouldBe NodeDispatch.NOTHING_SENT
        exec.operation shouldBe NodeOperation.EXEC
        client.calls.shouldBeEmpty()
    }

    /**
     * Both refusals on the way to the proxy's control endpoint — the channel drain
     * steps 2, 4 and 6 speak on.
     *
     * The address case is a **retryable** failure that sent nothing, which is why
     * the two properties are separate and neither is derivable from the other. The
     * sandbox status read above it costs the far side nothing and does not
     * disqualify the claim; the rule is about requests that could change something.
     */
    @Test
    fun `neither endpoint refusal reaches the workload`(
        @TempDir root: Path,
    ) = coreTest {
        val request =
            EndpointRequest(
                port = 8080,
                verb = HttpVerb.GET,
                path = "/v1/backends",
                bearerToken = TOKEN,
                timeout = EndpointTimeout.of(5.seconds),
            )

        val addressless = RecordingCriClient(ips = emptyList())
        val waiting =
            shouldThrow<NodeException.Busy> {
                node(addressless, root, EmptySecretStore).callEndpoint(handle(), request)
            }
        waiting.dispatch shouldBe NodeDispatch.NOTHING_SENT
        waiting.operation shouldBe NodeOperation.ENDPOINT
        waiting.retryable shouldBe true

        // An address, so the send is reachable — and the missing token is what
        // stops it. Without the case above, "nothing was sent" here would be
        // satisfied by there being nowhere to send to.
        val addressed = RecordingCriClient(ips = listOf("10.0.0.2"))
        val untokened =
            shouldThrow<NodeException.Rejected> {
                node(addressed, root, EmptySecretStore).callEndpoint(handle(), request)
            }
        untokened.dispatch shouldBe NodeDispatch.NOTHING_SENT
        untokened.operation shouldBe NodeOperation.ENDPOINT
        addressed.calls shouldBe listOf("sandboxStatus")
    }

    /**
     * The one refusal in this module that runs before the sandbox exists, and the
     * ones beside it that do not.
     *
     * `HostPaths.prepare` is called from `ensureWorkload` on the line above
     * `runSandbox`, in the branch that found no sandbox — so a create refused there
     * asked the runtime for nothing. `HostPaths.mounts`' refusals reach the same
     * caller through `containerSpecFor`, which runs *after* the sandbox exists, so
     * they keep the default. Asserting both is the point: a per-file rule would be
     * wrong, and the difference is which line of one method throws.
     */
    @Test
    fun `a create refused before the sandbox exists sent nothing, and one refused after it does not claim to`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RecordingCriClient()
        val blocked = root.resolve("volumes")
        Files.writeString(blocked, "not a directory")

        val refused =
            shouldThrow<NodeException> {
                node(client, root, volumes = blocked)
                    .ensureWorkload(PaperWorkloadPlanner.plan(paperDefinition()))
            }

        refused.dispatch shouldBe NodeDispatch.NOTHING_SENT
        refused.operation shouldBe NodeOperation.CREATE
        // The whole claim, measured rather than reasoned about: the runtime was
        // asked to list, and never asked to make anything.
        client.calls shouldBe listOf("listSandboxes")
    }

    private fun node(
        client: CriClient,
        root: Path,
        secrets: SecretStore = UnreachedSecretStore,
        volumes: Path = root.resolve("volumes").createDirectories(),
    ): LocalNode =
        LocalNode(
            name = NODE,
            client = client,
            secrets = secrets,
            volumeRoot = volumes,
            logRoot = root.resolve("logs").createDirectories(),
            assetRoot = root.resolve("assets").createDirectories(),
            sandboxNamespace = "mcorch-test",
            cgroupParent = null,
        )

    private fun handle(): WorkloadHandle = WorkloadHandle(NODE, "s1", "c1")

    private companion object {
        val NODE: NodeName = NodeName.of("test-node").getOrThrow()
        val TOKEN: SecretRef =
            SecretRef(ResourceName.of("proxy-control").getOrThrow(), "token")

        /** No world, so the stop-grace ceiling has no floor under it. */
        val NO_WORLD: Duration = Duration.ZERO
    }
}

/**
 * A CRI client that records the name of every call and refuses to do anything
 * useful.
 *
 * Names rather than arguments: what these tests measure is *whether the runtime
 * was asked*, so the instrument has to record the calls that are allowed through
 * as well as the ones that are not. A fake that only recorded stops would read
 * empty for a create that reached `runSandbox`.
 */
private class RecordingCriClient(
    private val ips: List<String> = emptyList(),
) : CriClient {
    val calls: MutableList<String> = mutableListOf()
    val stops: MutableList<Pair<ContainerId, StopGracePeriod>> = mutableListOf()

    override suspend fun stopContainer(
        id: ContainerId,
        gracePeriod: StopGracePeriod,
    ) {
        calls += "stopContainer"
        stops += id to gracePeriod
    }

    override suspend fun listSandboxes(filter: SandboxFilter): List<SandboxSummary> {
        calls += "listSandboxes"
        return emptyList()
    }

    override suspend fun sandboxStatus(id: SandboxId): SandboxStatus {
        calls += "sandboxStatus"
        return SandboxStatus(
            id = id,
            metadata = SandboxMetadata("s1", "u1", "mcorch-test", 0u),
            state = SandboxState.READY,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            ips = ips,
            labels = emptyMap(),
            annotations = emptyMap(),
            runtimeHandler = "",
            containerStatuses = emptyList(),
        )
    }

    override suspend fun runSandbox(spec: SandboxSpec): SandboxId {
        calls += "runSandbox"
        return SandboxId("s1")
    }

    override suspend fun createContainer(
        sandboxId: SandboxId,
        sandboxSpec: SandboxSpec,
        spec: ContainerSpec,
    ): ContainerId {
        calls += "createContainer"
        return ContainerId("c1")
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

    override suspend fun stopSandbox(id: SandboxId): Unit = unexpected("stopSandbox")

    override suspend fun removeSandbox(id: SandboxId): Unit = unexpected("removeSandbox")

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
        error("the dispatch test reached $operation, which no scenario here should reach")
}

/** Answers "not there" rather than throwing: a missing secret is the case under test. */
private object EmptySecretStore : SecretStore {
    override suspend fun put(
        ref: SecretRef,
        value: SecretValue,
    ): Unit = error("no test here writes a secret")

    override suspend fun resolve(ref: SecretRef): SecretValue? = null

    override suspend fun contains(ref: SecretRef): Boolean = false

    override suspend fun removeKey(ref: SecretRef): Boolean = error("no test here removes a secret")

    override suspend fun removeSecret(name: ResourceName): Int = error("no test here removes a secret")

    override suspend fun listNames(): List<ResourceName> = error("no test here lists secrets")

    override suspend fun listKeys(name: ResourceName): List<String> = error("no test here lists secrets")

    override fun close() = Unit
}

private object UnreachedSecretStore : SecretStore {
    override suspend fun put(
        ref: SecretRef,
        value: SecretValue,
    ): Unit = error("this scenario resolves no secrets")

    override suspend fun resolve(ref: SecretRef): SecretValue? = error("this scenario resolves no secrets")

    override suspend fun contains(ref: SecretRef): Boolean = error("this scenario resolves no secrets")

    override suspend fun removeKey(ref: SecretRef): Boolean = error("this scenario resolves no secrets")

    override suspend fun removeSecret(name: ResourceName): Int = error("this scenario resolves no secrets")

    override suspend fun listNames(): List<ResourceName> = error("this scenario resolves no secrets")

    override suspend fun listKeys(name: ResourceName): List<String> = error("this scenario resolves no secrets")

    override fun close() = Unit
}

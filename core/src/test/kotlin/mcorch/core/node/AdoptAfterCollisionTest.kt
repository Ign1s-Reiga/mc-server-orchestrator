package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.Labels
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.WorkloadSpec
import mcorch.core.WorkloadState
import mcorch.core.coreTest
import mcorch.core.paper.PaperWorkloadPlanner
import mcorch.core.paperDefinition
import mcorch.cri.ContainerFilter
import mcorch.cri.ContainerId
import mcorch.cri.ContainerMetadata
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerState
import mcorch.cri.ContainerStatus
import mcorch.cri.ContainerSummary
import mcorch.cri.CriClient
import mcorch.cri.CriException
import mcorch.cri.CriOperation
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
import mcorch.schema.NodeName
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.store.SecretStore
import mcorch.store.SecretValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.time.Duration

/**
 * What [LocalNode.ensureWorkload] does when a create loses a race, which until now
 * nothing exercised.
 *
 * ## Why this is a fixture and not an integration test
 *
 * The branch under test is `:core`'s: a create that collides is answered by looking
 * again and adopting what is there, rather than by reporting a permanent rejection.
 * Reproducing the race against a real containerd means two concurrent creators of one
 * name, and two concurrent suites against one runtime manufacture phantom containers
 * that read like label defects — which is what happened the last time it was tried.
 * A fake client can produce the collision exactly, on the call that matters, with
 * nothing else moving.
 *
 * ## The status code is the finding this test exists to keep
 *
 * containerd does **not** answer a duplicate create with `ALREADY_EXISTS`. Its CRI
 * plugin rejects the duplicate at *name reservation*, and a reservation conflict is
 * `FAILED_PRECONDITION` — established by probing a real runtime earlier in this
 * project, where both `RunPodSandbox` and `CreateContainer` answer
 * `failed to reserve ... name "..." is reserved for "<id>"`. While
 * `LocalNode.isNameCollision` matched only the obvious code, both adoptions were dead
 * code: a create that lost a race surfaced as a permanent rejection, and the
 * replacement drain that depends on the adoption would have left a server torn down
 * with nothing brought back up. So the fixture answers what containerd answers, and
 * the documented code is covered beside it rather than instead of it.
 *
 * ## What is asserted, and why not the message
 *
 * Every case reads the *side effect* — how many creates were attempted, and which
 * sandbox the container was created into — rather than only the value returned. An
 * adoption that returned the right observation while creating a second sandbox is
 * precisely the defect `ensureWorkload` exists to prevent (CLAUDE.md invariant 5),
 * and a return value cannot see it.
 */
internal class AdoptAfterCollisionTest {
    /**
     * The race, on the sandbox: the list is empty, the create is refused because
     * somebody else got there first, and the second look finds it.
     *
     * `runSandbox` is attempted **once**. A retry loop here is the shape the
     * `unadoptable` refusal exists to refuse: every future pass would repeat the same
     * find-create-collide cycle with nobody asked to look.
     *
     * The second half is the idempotency the adoption exists for: the pass after,
     * against the state this one left, creates nothing at all. A collision resolved
     * by creating a second sandbox next to the first would satisfy every assertion
     * about the returned observation and break CLAUDE.md invariant 5.
     */
    @Test
    fun `a sandbox create refused as a duplicate adopts the sandbox that won the race`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RacingCriClient(publishOnCollision = true)
        val node = node(client, root)
        val spec = spec()

        val observation = node.ensureWorkload(spec)

        client.sandboxCreates shouldBe 1
        observation.handle.sandboxId shouldBe RACER.value
        observation.handle.containerId.shouldNotBeNull()
        observation.state shouldBe WorkloadState.CREATED
        // Created *into* the adopted sandbox, which is the half a returned
        // observation cannot show.
        client.containerCreates shouldBe listOf(RACER)

        val second = node.ensureWorkload(spec)

        second.handle shouldBe observation.handle
        client.sandboxCreates shouldBe 1
        client.containerCreates shouldBe listOf(RACER)
    }

    /**
     * The documented code, covered beside the one containerd actually sends.
     *
     * `ALREADY_EXISTS` is what CRI's own contract describes and what another runtime
     * — or a later containerd — may well send. Keying the adoption on one of the two
     * is what made it dead code before; this is the assertion that says both are a
     * name collision.
     */
    @Test
    fun `the documented already-exists code is adopted too`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RacingCriClient(publishOnCollision = true, code = Collision.ALREADY_EXISTS)

        val observation = node(client, root).ensureWorkload(spec())

        client.sandboxCreates shouldBe 1
        observation.handle.sandboxId shouldBe RACER.value
    }

    /**
     * The collision nobody can find, which is the one case that must not be retried
     * for ever.
     *
     * Something on this node holds the name without carrying this orchestrator's
     * labels, so listing by label will never find it and the next pass would collide
     * identically. Permanent, and the message has to tell an operator what to go and
     * do — the loop cannot resolve it and must stop pretending it will.
     */
    @Test
    fun `a collision that listing by label cannot find is refused permanently, with an instruction`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RacingCriClient(publishOnCollision = false)

        val refusal =
            shouldThrow<NodeException.Rejected> {
                node(client, root).ensureWorkload(spec())
            }

        refusal.retryable.shouldBeFalse()
        refusal.operation shouldBe NodeOperation.CREATE
        refusal.message.shouldNotBeNull() shouldContain "crictl"
        // Refused rather than retried, and nothing was created on the way to
        // finding out.
        client.sandboxCreates shouldBe 1
        client.containerCreates.shouldBeEmpty()
    }

    /**
     * The same race one level down: the sandbox is ours, and the *container* create
     * collides.
     *
     * The re-read is of the sandbox rather than of the container, because a container
     * that exists is one this node can observe through the sandbox it belongs to —
     * and a container is only adoptable if the re-read shows one. That distinction is
     * the next case.
     */
    @Test
    fun `a container create refused as a duplicate adopts the container that is there`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RacingCriClient(publishOnCollision = true, collideOn = Stage.CONTAINER)
        client.publishSandbox()

        val observation = node(client, root).ensureWorkload(spec())

        client.containerCreates shouldHaveSize 1
        observation.handle.containerId shouldBe RACER_CONTAINER.value
        observation.state shouldBe WorkloadState.RUNNING
    }

    /**
     * …and the container collision whose re-read still shows an empty sandbox.
     *
     * Adopting nothing and returning `SANDBOX_ONLY` would tell the reconciler the
     * workload exists with no container in it, which is a state it converges by
     * *creating one* — straight back into the same collision, for ever. It is refused
     * instead, permanently and with the same instruction.
     */
    @Test
    fun `a container collision with nothing to adopt is refused rather than reported as an empty sandbox`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RacingCriClient(publishOnCollision = false, collideOn = Stage.CONTAINER)
        client.publishSandbox()

        val refusal =
            shouldThrow<NodeException.Rejected> {
                node(client, root).ensureWorkload(spec())
            }

        refusal.retryable.shouldBeFalse()
        refusal.message.shouldNotBeNull() shouldContain "container"
        client.containerCreates shouldHaveSize 1
    }

    /**
     * The classification the adoption must not swallow: a create that failed because
     * the runtime is unavailable is a **transient** failure and is requeued.
     *
     * `isNameCollision` is the narrowing that keeps this true. Widening it to "any
     * failed create means somebody else made it" would turn a node blip into a
     * permanent rejection when nothing found it, and into an adoption of whatever a
     * stale list happened to contain when something did.
     */
    @Test
    fun `a create that failed for any other reason is retryable and adopts nothing`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RacingCriClient(publishOnCollision = true, code = Collision.UNAVAILABLE)

        val failure =
            shouldThrow<NodeException> {
                node(client, root).ensureWorkload(spec())
            }

        failure.retryable.shouldBeTrue()
        client.containerCreates.shouldBeEmpty()
    }

    private fun spec(): WorkloadSpec =
        // RCON off, so the create resolves no secrets and [UnusedSecrets] can stay
        // a fixture that refuses everything. What is under test is the collision,
        // not the environment.
        PaperWorkloadPlanner.plan(paperDefinition(rcon = RconSpec.Disabled))

    private fun node(
        client: CriClient,
        root: Path,
    ): LocalNode =
        LocalNode(
            name = NODE,
            client = client,
            secrets = UnusedSecrets,
            volumeRoot = root.resolve("volumes").createDirectories(),
            logRoot = root.resolve("logs").createDirectories(),
            assetRoot = root.resolve("assets").createDirectories(),
            sandboxNamespace = "mcorch-test",
            cgroupParent = null,
        )

    private companion object {
        val NODE: NodeName = NodeName.of("test-node").getOrThrow()
    }
}

/**
 * The server the fixture is about, and what the other creator left behind.
 *
 * At file scope so the test and the fixture read one set of values: two copies of
 * "the sandbox that won the race" is a test that can pass while asserting about a
 * different object from the one the fixture published.
 */
private val SERVER: ResourceName = ResourceName.of("survival-01").getOrThrow()

private val RACER: SandboxId = SandboxId("sandbox-from-the-other-creator")

private val RACER_CONTAINER: ContainerId = ContainerId("container-from-the-other-creator")

private val CREATED: Instant = Instant.parse("2026-01-01T00:00:00Z")

private val IMAGE: ImageName = ImageName("docker.io/itzg/minecraft-server:2026.6.1")

private val IMAGE_ID: ImageId = ImageId("sha256:0000000000000000000000000000000000000000000000000000000000000000")

/** Which create the fixture answers with a collision. */
private enum class Stage {
    SANDBOX,
    CONTAINER,
}

/** Which status code it answers with. */
private enum class Collision {
    FAILED_PRECONDITION,
    ALREADY_EXISTS,
    UNAVAILABLE,
}

/**
 * A CRI client that loses one create to another creator.
 *
 * The publication is the point: a real race publishes the object *between* the list
 * and the create, so the second look succeeds where the first found nothing. With
 * [publishOnCollision] false it never appears, which is the object held under a name
 * this orchestrator cannot see.
 *
 * Everything not needed by a create throws, on the rule `RefusingCriClient` follows:
 * a fixture that quietly answers a call the test did not mean to make is a test
 * asserting less than it appears to.
 */
private class RacingCriClient(
    private val publishOnCollision: Boolean,
    private val collideOn: Stage = Stage.SANDBOX,
    private val code: Collision = Collision.FAILED_PRECONDITION,
) : CriClient {
    var sandboxCreates: Int = 0
        private set

    val containerCreates: MutableList<SandboxId> = mutableListOf()

    private var sandbox: SandboxSummary? = null
    private var container: ContainerSummary? = null

    /** Puts the sandbox in place before the pass, for the container-stage cases. */
    fun publishSandbox() {
        sandbox = summary()
    }

    override suspend fun listSandboxes(filter: SandboxFilter): List<SandboxSummary> = listOfNotNull(sandbox)

    override suspend fun runSandbox(spec: SandboxSpec): SandboxId {
        sandboxCreates += 1
        if (collideOn == Stage.SANDBOX) {
            if (publishOnCollision) sandbox = summary()
            throw refusal(CriOperation.RUN_SANDBOX, "sandbox")
        }
        sandbox = summary()
        return RACER
    }

    override suspend fun createContainer(
        sandboxId: SandboxId,
        sandboxSpec: SandboxSpec,
        spec: ContainerSpec,
    ): ContainerId {
        containerCreates += sandboxId
        if (collideOn == Stage.CONTAINER) {
            if (publishOnCollision) container = containerSummary()
            throw refusal(CriOperation.CREATE_CONTAINER, "container")
        }
        // Published, because a runtime that answered a create and then denied the
        // container exists would make the next pass create a second one — and a
        // fixture that models that is a fixture in which no idempotency assertion
        // means anything.
        container = containerSummary()
        return RACER_CONTAINER
    }

    override suspend fun sandboxStatus(id: SandboxId): SandboxStatus =
        SandboxStatus(
            id = id,
            metadata = SandboxMetadata(name = SERVER.value, uid = "uid", namespace = "mcorch-test", attempt = 0u),
            state = SandboxState.READY,
            createdAt = CREATED,
            ips = listOf(),
            labels = Labels.selectorFor(SERVER),
            annotations = emptyMap(),
            runtimeHandler = "",
            // Deliberately empty: this is CRI's optional overlay, which containerd
            // 2.3.3 never fills, and believing it is how a sandbox holding a live
            // server reads as empty. `listContainers` is what decides who exists.
            containerStatuses = emptyList(),
        )

    override suspend fun listContainers(filter: ContainerFilter): List<ContainerSummary> = listOfNotNull(container)

    /**
     * The per-container detail an enumeration does not carry.
     *
     * Asked for because [sandboxStatus] leaves CRI's optional overlay empty — the way
     * containerd 2.3.3 does — so the node fetches `startedAt` for this server's
     * containers itself. A fixture that refused this call would be modelling a
     * runtime nobody has.
     */
    override suspend fun containerStatus(id: ContainerId): ContainerStatus =
        ContainerStatus(
            id = id,
            metadata = ContainerMetadata(name = SERVER.value, attempt = 0u),
            state = ContainerState.RUNNING,
            createdAt = CREATED,
            startedAt = CREATED,
            finishedAt = null,
            exitCode = null,
            image = IMAGE,
            imageId = IMAGE_ID,
            reason = "",
            message = "",
            labels = Labels.selectorFor(SERVER),
            annotations = emptyMap(),
            mounts = emptyList(),
            logPath = "",
        )

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

    override suspend fun stopContainer(
        id: ContainerId,
        gracePeriod: StopGracePeriod,
    ): Unit = unexpected("stopContainer")

    override suspend fun removeContainer(id: ContainerId): Unit = unexpected("removeContainer")

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

    /**
     * The wording is containerd's own, from the probe that established the code:
     * a duplicate is refused at name reservation and never reaches a create.
     */
    private fun refusal(
        operation: CriOperation,
        what: String,
    ): CriException {
        val description = "failed to reserve $what name \"${SERVER.value}\": name is reserved for another"
        return when (code) {
            Collision.FAILED_PRECONDITION -> CriException.FailedPrecondition(operation, description)
            Collision.ALREADY_EXISTS -> CriException.AlreadyExists(operation, description)
            Collision.UNAVAILABLE -> CriException.Unavailable(operation, "the runtime is restarting")
        }
    }

    private fun summary(): SandboxSummary =
        SandboxSummary(
            id = RACER,
            metadata = SandboxMetadata(name = SERVER.value, uid = "uid", namespace = "mcorch-test", attempt = 0u),
            state = SandboxState.READY,
            createdAt = CREATED,
            labels = Labels.selectorFor(SERVER),
            annotations = emptyMap(),
            runtimeHandler = "",
        )

    private fun containerSummary(): ContainerSummary =
        ContainerSummary(
            id = RACER_CONTAINER,
            sandboxId = RACER,
            metadata = ContainerMetadata(name = SERVER.value, attempt = 0u),
            state = ContainerState.RUNNING,
            createdAt = CREATED,
            image = IMAGE,
            imageId = IMAGE_ID,
            labels = Labels.selectorFor(SERVER),
            annotations = emptyMap(),
        )

    private fun unexpected(operation: String): Nothing =
        error("the collision fixture reached $operation; a create asks for none of it")
}

private object UnusedSecrets : SecretStore {
    override suspend fun put(
        ref: SecretRef,
        value: SecretValue,
    ): Unit = error("this create resolves no secrets")

    override suspend fun resolve(ref: SecretRef): SecretValue? = error("this create resolves no secrets")

    override suspend fun contains(ref: SecretRef): Boolean = error("this create resolves no secrets")

    override suspend fun removeKey(ref: SecretRef): Boolean = error("this create resolves no secrets")

    override suspend fun removeSecret(name: ResourceName): Int = error("this create resolves no secrets")

    override suspend fun listNames(): List<ResourceName> = error("this create resolves no secrets")

    override suspend fun listKeys(name: ResourceName): List<String> = error("this create resolves no secrets")

    override fun close() = Unit
}

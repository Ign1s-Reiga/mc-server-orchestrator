package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.StopGrace
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
import mcorch.schema.PaperServerDefaults
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What [LocalNode.stopWorkload] does with the grace period it is handed, before
 * anything reaches containerd — and what [StopGraceCeiling] does before that.
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
 *
 * ## Two bounds, and only one of them is this node's
 *
 * Since the thirtieth audit the operational ceiling is carried by [StopGrace], the
 * type [LocalNode.stopWorkload] takes, so the node applies nothing of its own to it
 * — the tests below build the value the way the drain controller does and assert on
 * what reaches the client. **There is no mutation for "the node forgets the
 * ceiling"**, and the reason belongs here rather than in the harness: the node
 * cannot express forgetting it. It never sees an unbounded duration. What is left
 * at this end is containerd's own bound (`StopGracePeriod.of`), which is where a
 * second `Node` implementation is entitled to differ.
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
     * server nobody can retire. The cap is safe because of where the stop sits in
     * the protocol — every path to it ends in `mayStop`, so a completed save is
     * already confirmed and the grace period is the last-resort net. There are
     * **two** such paths and `DrainWiringTest` is what holds that count; the
     * sentence this used to carry said "the zero-player gate followed by `mayStop`",
     * which `DrainController`'s class note has contradicted since the re-issue was
     * written.
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

        node(client, root).stopWorkload(handle(), StopGrace.of(overflowing, NO_WORLD))

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
        StopGraceCeiling.bound(30.seconds, NO_WORLD) shouldBe 30.seconds
        StopGraceCeiling.bound(StopGraceCeiling.MAX, NO_WORLD) shouldBe StopGraceCeiling.MAX
        StopGraceCeiling.bound(StopGraceCeiling.MAX + 1.seconds, NO_WORLD) shouldBe StopGraceCeiling.MAX
        StopGraceCeiling.bound(StopGracePeriod.MAX_SECONDS.seconds, NO_WORLD) shouldBe StopGraceCeiling.MAX
        // Not a duration anybody meant. Capping it would turn an argument the code
        // cannot interpret into a plausible-looking stop, so it is handed on
        // untouched to the rule that refuses it and says why.
        StopGraceCeiling.bound(Duration.INFINITE, NO_WORLD) shouldBe Duration.INFINITE
        StopGraceCeiling.bound(Duration.ZERO, NO_WORLD) shouldBe Duration.ZERO
        StopGraceCeiling.bound((-30).days, NO_WORLD) shouldBe (-30).days
    }

    /**
     * **The thirtieth audit's first finding: the ceiling may not invert the pair it
     * clamps half of.**
     *
     * `stopGracePeriod` and `drain.saveTimeout` are validated *together* —
     * `LifecycleSpec.init` refuses a `PaperServer` whose grace period does not exceed
     * its save timeout by [PaperServerDefaults.MIN_STOP_GRACE_MARGIN], because a
     * grace period shorter than the save timeout kills the container part-way
     * through the save. A row carrying `saveTimeout = 3h` and
     * `stopGracePeriod = 3h1m` satisfies that, decodes, and used to be stopped with
     * two hours: SIGKILL into Paper's shutdown save, which is a torn region file.
     *
     * The two conditions are correlated rather than independent, which is what makes
     * this reachable at all: the cap only fires on a definition that bypassed
     * `PaperServerReader`, and that is the same population that can carry a save
     * timeout above `PaperServerDefaults.MAX_TIMEOUT`.
     *
     * The relation is restated here from the public constant rather than called
     * through `SpecInvariants.stopGraceProblem`, which is `internal` to `:schema`. If
     * that rule ever changes shape this assertion has to be re-derived — the
     * constant is shared, the arithmetic around it is not.
     */
    @Test
    fun `a grace period is never capped below the save timeout it was validated against`() {
        val margin = PaperServerDefaults.MIN_STOP_GRACE_MARGIN
        val pairs =
            listOf(
                // The reported case, and the one the old ceiling inverted.
                3.hours to (3.hours + 1.minutes),
                // The smallest margin the schema accepts, well past the ceiling.
                5.hours to (5.hours + margin),
                // A save timeout under the ceiling: the floor is below MAX, so MAX
                // is what bites and nothing about the pair changes.
                3.minutes to 10.hours,
            )
        for ((saveTimeout, declared) in pairs) {
            withClue("saveTimeout=$saveTimeout declared=$declared") {
                // The premise: every pair here is one the schema would accept.
                declared shouldBeGreaterThanOrEqualTo saveTimeout + margin
                val effective = StopGraceCeiling.bound(declared, saveTimeout)
                effective shouldBeLessThanOrEqualTo declared
                effective shouldBeGreaterThanOrEqualTo saveTimeout + margin
            }
        }
        // …and the cap is still a cap. Without this the assertions above are
        // satisfied by a ceiling that does nothing at all.
        StopGraceCeiling.bound(10.hours, 3.minutes) shouldBe StopGraceCeiling.MAX
    }

    /**
     * **The thirty-first audit's first finding: where the floor makes the ceiling
     * inoperative, and it is not at the far end.**
     *
     * [StopGraceCeiling.ceilingFor] is `max(MAX, saveTimeout + margin)`, so the
     * moment the save timeout passes `MAX - margin` the ceiling stops being two hours
     * and becomes the save timeout — and from there it rises with it, all the way to
     * the runtime's own refusal 292 years out. `saveTimeout = 30d` beside
     * `stopGracePeriod = 31d` clears `LifecycleSpec.init`, decodes from a nanosecond
     * column, and is *capped* — to a month. The stop the runtime is given is a month,
     * and `GrpcCriClient.stopContainer` deadlines its call off that number, so the
     * worker is parked for one.
     *
     * The trade is still the right way round and the floor stays: a parked worker
     * loses no world, an inverted pair loses one. What was wrong was the sentence.
     * The residual [StopGraceCeiling] named was the *refusal* at the top of the
     * range, reachable only when both halves are absurd — while this, the reachable
     * one, went unnamed. So it is pinned here, in both halves: the boundary where the
     * floor takes over, and what actually reaches the runtime past it.
     *
     * What bounds the wait, therefore, is whatever bounds `drain.saveTimeout`, and
     * that is the decode's job rather than this ceiling's.
     */
    @Test
    fun `above a two-hour save timeout the ceiling is the save timeout, and a month-long stop goes out`(
        @TempDir root: Path,
    ) = coreTest {
        val margin = PaperServerDefaults.MIN_STOP_GRACE_MARGIN

        // The boundary itself, from both sides. Below it the floor sits under MAX and
        // MAX is what bites; one second above it, the floor is what bites.
        StopGraceCeiling.ceilingFor(StopGraceCeiling.MAX - margin) shouldBe StopGraceCeiling.MAX
        StopGraceCeiling.ceilingFor(StopGraceCeiling.MAX - margin + 1.seconds) shouldBe
            StopGraceCeiling.MAX + 1.seconds

        // Past it there is no bound of this ceiling's own left: the reported case,
        // and one two orders of magnitude larger.
        StopGraceCeiling.bound(3.hours + 1.minutes, 3.hours) shouldBe 3.hours + margin
        StopGraceCeiling.bound(31.days, 30.days) shouldBe 30.days + margin

        // …and it is not arithmetic in a vacuum. This is what containerd is asked to
        // wait, and what the call is deadlined off.
        val client = RefusingCriClient()
        node(client, root).stopWorkload(handle(), StopGrace.of(31.days, 30.days))

        client.stops
            .single()
            .second.seconds shouldBe (30.days + margin).inWholeSeconds
    }

    /**
     * The residual named in [StopGraceCeiling], pinned so it is a decision rather
     * than a discovery.
     *
     * A save timeout large enough that the derived floor passes what *containerd*
     * accepts leaves the stop refused rather than capped — the cap-versus-refuse
     * trade pointing the other way, and deliberately so: a refusal is recorded and
     * loud where a cap that inverts the pair is silent and costs a world. It needs
     * both halves of the pair to be absurd (292 years), not merely unvalidated.
     */
    @Test
    fun `a save timeout past the runtime's own bound leaves the stop refused, not silently inverted`(
        @TempDir root: Path,
    ) = coreTest {
        val absurd = (StopGracePeriod.MAX_SECONDS + 1).seconds
        val client = RefusingCriClient()

        // The floor raises the ceiling above what the runtime will take, so nothing
        // caps it and the runtime's own rule is what answers.
        StopGraceCeiling.bound(absurd, absurd) shouldBe absurd
        shouldThrow<NodeException.Rejected> {
            node(client, root).stopWorkload(handle(), StopGrace.of(absurd, absurd))
        }
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
            shouldThrow<NodeException.Rejected> {
                node(client, root).stopWorkload(handle(), StopGrace.of(Duration.INFINITE, NO_WORLD))
            }

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
            val node = node(client, root)
            shouldThrow<NodeException.Rejected> { node.stopWorkload(handle(), StopGrace.of(bad, NO_WORLD)) }
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
        node(client, root).stopWorkload(handle(), StopGrace.of(2.hours, 3.minutes))

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
        node(client, root).stopWorkload(handle(), StopGrace.of(StopGracePeriod.MAX_SECONDS.seconds, NO_WORLD))

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
        node(quiet, root).stopWorkload(sandboxOnly, StopGrace.of(30.seconds, NO_WORLD))
        quiet.stops.shouldBeEmpty()

        // A nonsense grace period is refused whether or not there is anything to
        // stop. The argument is wrong either way, and a caller that only learns
        // so once a container exists learns so during a drain.
        val refused = RefusingCriClient()
        shouldThrow<NodeException.Rejected> {
            node(refused, root).stopWorkload(sandboxOnly, StopGrace.of(Duration.INFINITE, NO_WORLD))
        }
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

        /**
         * The save timeout of a workload with no world, which is what
         * `DrainSubject.saveTimeout` answers for one. It puts no floor under the
         * ceiling, so every assertion that is about the *cap* uses it rather than
         * quietly relying on a floor it does not name.
         */
        val NO_WORLD: Duration = Duration.ZERO
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

package mcorch.schema

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The two rules that cost world data are enforced by the constructors as well
 * as by the parser, so no module can build a spec that breaks them — not even
 * in a test fixture.
 */
class SpecInvariantsTest {
    private fun memory(text: String): MemoryQuantity = MemoryQuantity.parse(text).getOrThrow()

    @Test
    fun `a heap that reaches the container memory limit cannot be constructed`() {
        val failure =
            shouldThrow<IllegalArgumentException> {
                ResourceSpec(memory = memory("4Gi"), heap = HeapSpec(max = memory("4Gi")))
            }

        failure.message.orEmpty() shouldContain "must leave headroom"
    }

    @Test
    fun `heap headroom scales with the limit and is clamped at both ends`() {
        JvmHeapPolicy.headroom(memory("1Gi")) shouldBe memory("512Mi")
        JvmHeapPolicy.headroom(memory("32Gi")) shouldBe memory("2Gi")
        JvmHeapPolicy.defaultMaxHeap(memory("4Gi")) shouldBe memory("3276Mi")
        (JvmHeapPolicy.defaultMaxHeap(memory("8Gi")) < memory("8Gi")) shouldBe true
    }

    @Test
    fun `a stop grace period that does not outlast the save cannot be constructed`() {
        val failure =
            shouldThrow<IllegalArgumentException> {
                LifecycleSpec(drain = DrainSpec(saveTimeout = 5.minutes), stopGracePeriod = 2.minutes)
            }

        failure.message.orEmpty() shouldContain "must exceed spec.lifecycle.drain.saveTimeout"
    }

    @Test
    fun `the default stop grace period follows the save timeout`() {
        LifecycleSpec(drain = DrainSpec(saveTimeout = 10.minutes)).stopGracePeriod shouldBe 11.minutes
        LifecycleSpec().stopGracePeriod shouldBe 240.seconds
    }

    @Test
    fun `a heap minimum above the maximum cannot be constructed`() {
        shouldThrow<IllegalArgumentException> {
            HeapSpec(max = memory("2Gi"), min = memory("3Gi"))
        }
    }

    @Test
    fun `an unaccepted EULA cannot be constructed`() {
        shouldThrow<IllegalArgumentException> {
            PaperServerSpec(
                image = ImageRef.parse("paper:1.21.8").getOrThrow(),
                paper = PaperVersionSpec(MinecraftVersion.of("1.21.8").getOrThrow()),
                resources = ResourceSpec(memory = memory("4Gi"), heap = HeapSpec(max = memory("2Gi"))),
                storage = StorageSpec.Persistent(VolumeSpec(ResourceName.of("survival-01").getOrThrow())),
                eulaAccepted = false,
            )
        }
    }
}

/** Status is what `:core` writes and `:api` serves. These are its standing guarantees. */
class StatusTest {
    private val now: Instant = Instant.parse("2026-07-26T10:00:00Z")

    @Test
    fun `a freshly accepted definition starts pending and not ready`() {
        val status = PaperServerStatus.pending(ResourceName.of("survival-01").getOrThrow(), 1, now)

        status.phase shouldBe ServerPhase.PENDING
        status.ready shouldBe false
        status.draining shouldBe false
        status.kind shouldBe ServerKind.PAPER_SERVER
    }

    @Test
    fun `two observations of the same state compare equal, so a second pass is a no-op`() {
        val name = ResourceName.of("survival-01").getOrThrow()
        val node = NodeName.of("node-a").getOrThrow()
        val runtime = RuntimeIdentity(node = node, sandboxId = "sandbox-1", containerId = "container-1")
        val first =
            PaperServerStatus.pending(name, 1, now).copy(
                phase = ServerPhase.RUNNING,
                ready = true,
                runtime = runtime,
                players = PlayerOccupancy(online = 3, max = 20, observedAt = now),
            )

        first shouldBe first.copy()
    }

    @Test
    fun `occupancy is counts only, and rejects impossible ones`() {
        val occupancy = PlayerOccupancy(online = 0, max = 20, observedAt = now)

        occupancy.empty shouldBe true
        // Nothing identifying a player exists on the type, so logging it is safe.
        occupancy.toString() shouldContain "online=0"
        shouldThrow<IllegalArgumentException> { PlayerOccupancy(online = -1, max = 20, observedAt = now) }
    }

    /**
     * The pairing that would stop the loop retrying a drain it could finish
     * cannot be built.
     *
     * Narrowed to one reason when the waiting case stopped being a failure at
     * all. What is left is the fleet-capacity case, and the rule stands on that
     * case's own terms rather than on anything the escalation does: capacity is
     * not a property of this server, it comes back when somebody logs off
     * elsewhere or a lobby starts, and `PERMANENT` — which means *stop trying* —
     * would freeze a drain the next pass could finish, in the one state where the
     * container has to keep running. There is no safer version of "stop looking
     * for a destination".
     *
     * That this reason *does* raise `NEEDS_ATTENTION` is a separate question with
     * a separate answer: a fleet that is too small needs a person, it just does
     * not need the loop to give up.
     */
    @Test
    fun `the fleet-capacity reason cannot be constructed permanent`() {
        FailureStatus.ALWAYS_RETRYABLE shouldBe setOf(FailureReason.DRAIN_NO_DESTINATION)

        FailureStatus.ALWAYS_RETRYABLE.forEach { reason ->
            val failure =
                shouldThrow<IllegalArgumentException> {
                    FailureStatus(
                        reason = reason,
                        failureClass = FailureClass.PERMANENT,
                        message = "blocked",
                        occurredAt = now,
                    )
                }
            failure.message.orEmpty() shouldContain "$reason failure is always RETRYABLE"

            FailureStatus(
                reason = reason,
                failureClass = FailureClass.RETRYABLE,
                message = "blocked",
                occurredAt = now,
            ).failureClass shouldBe FailureClass.RETRYABLE
        }

        // The control: every other pairing this type is asked for still builds,
        // so the assertions above are about the one rule and not about the
        // arguments being wrong. A permanent drain failure with a different
        // reason is exactly the case the escalation must still flag.
        FailureStatus(
            reason = FailureReason.DRAIN_STALLED,
            failureClass = FailureClass.PERMANENT,
            message = "no save channel",
            occurredAt = now,
        ).failureClass shouldBe FailureClass.PERMANENT
    }

    /**
     * `drainInitiated`, not `draining`, is what a destination search must ask.
     *
     * The two differ in exactly one state, and it is the dangerous one: a server
     * sitting on a retryable `DRAIN_FAILED` reads as not-draining — deliberately,
     * so the loop can resume it — and would otherwise look like a fine place to
     * send somebody else's players moments before it tries to stop again.
     */
    @Test
    fun `a failed drain is not draining but has still had a drain initiated`() {
        val status =
            PaperServerStatus.pending(ResourceName.of("survival-01").getOrThrow(), 1, now).copy(
                drain =
                    DrainStatus(
                        state = DrainState.DRAIN_FAILED,
                        startedAt = now,
                        enteredStateAt = now,
                        failure =
                            FailureStatus(
                                reason = FailureReason.DRAIN_NO_DESTINATION,
                                failureClass = FailureClass.RETRYABLE,
                                message = "no destination with capacity",
                                occurredAt = now,
                            ),
                    ),
            )

        status.draining shouldBe false
        status.drainInitiated shouldBe true

        val untouched = PaperServerStatus.pending(ResourceName.of("survival-01").getOrThrow(), 1, now)
        untouched.drainInitiated shouldBe false
    }

    /**
     * Progressing, blocked-but-healthy and failed are three states, and the type
     * has to let a consumer tell them apart.
     *
     * This is the shape the whole change turns on. A blocked drain carries
     * [DrainStatus.blocked] and **no** [DrainStatus.failure] — that null is what
     * makes `escalated()` quiet without an exemption list, so a test that only
     * asserted the block were present would pass against a version that recorded
     * both and alarmed on every busy evening.
     *
     * Both are still recorded against a drain that has stopped advancing, which
     * is why the state alone cannot be the discriminator: `DRAIN_FAILED` means
     * *parked*, and the record beside it says whether that is bad news.
     */
    @Test
    fun `a blocked drain records a block and no failure, and is distinguishable from both other states`() {
        val name = ResourceName.of("survival-01").getOrThrow()
        val base = DrainStatus(state = DrainState.SEALED, startedAt = now, enteredStateAt = now)

        val progressing = PaperServerStatus.pending(name, 1, now).copy(drain = base)
        val blocked =
            PaperServerStatus.pending(name, 1, now).copy(
                drain =
                    base.copy(
                        state = DrainState.DRAIN_FAILED,
                        blocked =
                            DrainBlock(
                                reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                                message = "3 of 20 player slots are in use",
                                since = now,
                            ),
                    ),
            )
        val failed =
            PaperServerStatus.pending(name, 1, now).copy(
                drain =
                    base.copy(
                        state = DrainState.DRAIN_FAILED,
                        failure =
                            FailureStatus(
                                reason = FailureReason.DRAIN_STALLED,
                                failureClass = FailureClass.PERMANENT,
                                message = "no save channel",
                                occurredAt = now,
                            ),
                    ),
            )

        progressing.drain?.blocked shouldBe null
        progressing.drain?.failure shouldBe null
        progressing.draining shouldBe true

        blocked.drain?.blocked?.reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
        // The assertion the change exists for.
        blocked.drain?.failure shouldBe null
        blocked.drain?.blocked?.observations shouldBe 1

        failed.drain?.blocked shouldBe null
        failed.drain?.failure?.failureClass shouldBe FailureClass.PERMANENT

        // All three are parked or moving, and the state does not separate the
        // last two — which is exactly why the block is its own field.
        blocked.drain?.state shouldBe failed.drain?.state
    }
}

/** The proxy's own constructor-level rules and status guarantees. */
class VelocityProxyTypesTest {
    private val now: Instant = Instant.parse("2026-07-26T10:00:00Z")

    private fun memory(text: String): MemoryQuantity = MemoryQuantity.parse(text).getOrThrow()

    private fun selector(): BackendSelector = BackendSelector(mapOf("mcorch.dev/fleet" to "main"))

    private fun spec(
        network: ProxyNetworkSpec = ProxyNetworkSpec(),
        control: ControlEndpointSpec = ControlEndpointSpec(),
    ): VelocityProxySpec =
        VelocityProxySpec(
            image = ImageRef.parse("velocity:3.4.0").getOrThrow(),
            resources = ResourceSpec(memory = memory("1Gi"), heap = HeapSpec(max = memory("512Mi"))),
            forwarding =
                ForwardingSpec(
                    secret = SecretRef.of("fleet-forwarding", "modern-forwarding").getOrThrow(),
                ),
            backends = BackendsSpec(selector = selector()),
            network = network,
            control = control,
        )

    @Test
    fun `an empty selector cannot be constructed, not even by a fixture`() {
        val failure = shouldThrow<IllegalArgumentException> { BackendSelector(emptyMap()) }

        failure.message.orEmpty() shouldContain "matches every server in the fleet"
    }

    @Test
    fun `a control endpoint sharing the player port cannot be constructed`() {
        val failure =
            shouldThrow<IllegalArgumentException> {
                spec(network = ProxyNetworkSpec(port = 25577), control = ControlEndpointSpec(port = 25577))
            }

        failure.message.orEmpty() shouldContain "must differ from spec.network.port"
        shouldThrow<IllegalArgumentException> {
            spec(
                network = ProxyNetworkSpec(port = 25577, hostPort = 30000),
                control = ControlEndpointSpec(hostPort = 30000, tokenSecret = null),
            )
        }
    }

    @Test
    fun `a proxy holds no world data, structurally`() {
        spec().holdsWorldData shouldBe false
    }

    @Test
    fun `a paper spec answers the same question from its storage mode`() {
        val paper = { storage: StorageSpec ->
            PaperServerSpec(
                image = ImageRef.parse("paper:1.21.8").getOrThrow(),
                paper = PaperVersionSpec(MinecraftVersion.of("1.21.8").getOrThrow()),
                resources = ResourceSpec(memory = memory("4Gi"), heap = HeapSpec(max = memory("2Gi"))),
                storage = storage,
                eulaAccepted = true,
            )
        }

        paper(StorageSpec.Persistent(VolumeSpec(ResourceName.of("survival-01").getOrThrow())))
            .holdsWorldData shouldBe true
        paper(StorageSpec.Ephemeral()).holdsWorldData shouldBe false
    }

    @Test
    fun `a fresh proxy status starts pending, and carries no storage to be misread`() {
        val status = VelocityProxyStatus.pending(ResourceName.of("proxy-01").getOrThrow(), 1, now)

        status.kind shouldBe ServerKind.VELOCITY_PROXY
        status.phase shouldBe ServerPhase.PENDING
        status.ready shouldBe false
        status.draining shouldBe false
        status.drainInitiated shouldBe false
        status shouldBe status.copy()
    }

    /**
     * The destination rule, made hard to get wrong at the point the proxy
     * observes it rather than at every caller that reads the observation.
     */
    @Test
    fun `only a registered backend with no drain of its own may receive players`() {
        fun backend(
            name: String,
            registration: BackendRegistration,
            drainInitiated: Boolean = false,
        ) = BackendStatus(
            server = ResourceName.of(name).getOrThrow(),
            registration = registration,
            drainInitiated = drainInitiated,
            lastTransitionAt = now,
        )

        val routing =
            BackendRoutingStatus(
                observedAt = now,
                backends =
                    listOf(
                        backend("survival-a", BackendRegistration.REGISTERED),
                        // Reads as "not draining" on a Paper status; still not a destination.
                        backend("survival-b", BackendRegistration.REGISTERED, drainInitiated = true),
                        backend("survival-c", BackendRegistration.SEALED),
                        backend("survival-d", BackendRegistration.PENDING),
                        backend("survival-e", BackendRegistration.UNREACHABLE),
                    ),
            )

        routing.matched shouldBe 5
        routing.registered shouldBe 3
        routing.destinations shouldBe 1
    }

    @Test
    fun `backend observations are counts and server names, never identities`() {
        val backend =
            BackendStatus(
                server = ResourceName.of("survival-a").getOrThrow(),
                registration = BackendRegistration.REGISTERED,
                players = PlayerOccupancy(online = 4, max = 60, observedAt = now),
                lastTransitionAt = now,
            )

        backend.toString() shouldContain "online=4"
        backend.eligibleAsDestination shouldBe true
    }
}

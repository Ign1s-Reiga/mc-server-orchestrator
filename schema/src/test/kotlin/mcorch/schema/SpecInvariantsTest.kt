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
     * The pairings that would stop the loop retrying a drain it could finish
     * cannot be built.
     *
     * Rewritten when `DRAIN_NO_DESTINATION` was split in two. It used to assert
     * one reason and one justification — "players are online, they will log
     * off" — which after the split belongs to
     * [FailureReason.DRAIN_AWAITING_ZERO_PLAYERS] alone. The rule now covers
     * both reasons and stands on the shared argument instead: what each is
     * blocked on is not a property of this server, so `PERMANENT` — which means
     * *stop trying* — buys nothing and costs a drain that the next pass could
     * have completed.
     *
     * The two are not interchangeable elsewhere: only the waiting one is exempt
     * from `NEEDS_ATTENTION`, and a `DRAIN_NO_DESTINATION` that has been true for
     * an hour is exactly what that flag is for.
     */
    @Test
    fun `neither always-retryable reason can be constructed permanent`() {
        FailureStatus.ALWAYS_RETRYABLE shouldBe
            setOf(FailureReason.DRAIN_NO_DESTINATION, FailureReason.DRAIN_AWAITING_ZERO_PLAYERS)

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

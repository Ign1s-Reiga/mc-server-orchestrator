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
     * The pair that would disable the attention alarm cannot be built.
     *
     * `DRAIN_NO_DESTINATION` is the one failure the escalation never raises
     * `NEEDS_ATTENTION` for, and that suppression is only defensible because
     * players logging off resolves it — which is to say, because it is
     * retryable. Classified permanent it would be a wedged drain that is also
     * silently unflagged. It was a convention held up by two call sites
     * happening to agree; this is what makes it true by construction, and it is
     * why `mcorch.core.escalates` no longer depends on the order it checks
     * things in.
     */
    @Test
    fun `a permanent no-destination failure cannot be constructed`() {
        val failure =
            shouldThrow<IllegalArgumentException> {
                FailureStatus(
                    reason = FailureReason.DRAIN_NO_DESTINATION,
                    failureClass = FailureClass.PERMANENT,
                    message = "players are online",
                    occurredAt = now,
                )
            }

        failure.message.orEmpty() shouldContain "resolves itself when they log off"

        // The control: every other pairing this type is asked for still builds,
        // so the assertion above is about the one rule and not about the
        // arguments being wrong. A permanent drain failure with a different
        // reason is exactly the case the escalation must still flag.
        FailureStatus(
            reason = FailureReason.DRAIN_NO_DESTINATION,
            failureClass = FailureClass.RETRYABLE,
            message = "players are online",
            occurredAt = now,
        ).failureClass shouldBe FailureClass.RETRYABLE
        FailureStatus(
            reason = FailureReason.DRAIN_STALLED,
            failureClass = FailureClass.PERMANENT,
            message = "no save channel",
            occurredAt = now,
        ).failureClass shouldBe FailureClass.PERMANENT
    }

    @Test
    fun `a failed drain does not count as draining, because it must not lead to a stop`() {
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
    }
}

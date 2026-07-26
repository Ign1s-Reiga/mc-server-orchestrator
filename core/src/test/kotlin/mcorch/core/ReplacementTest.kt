package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainState
import mcorch.schema.MemoryQuantity
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * A definition change that needs the container recreated.
 *
 * `failure-modes.md` item 5: drain the old container first, then create the
 * replacement. Never both at once.
 */
internal class ReplacementTest {
    @Test
    fun `an image change drains the old container before creating the replacement`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            harness.settle(name, limit = 14)

            // One drain, one stop, one removal, and only then a second create.
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.node.removals shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .image.canonical shouldBe "docker.io/itzg/minecraft-server:2026.7.0"
            harness.node.pulls shouldHaveSize 2
        }

    @Test
    fun `a replacement never happens under live players`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 4

            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            repeat(10) { harness.pass(name) }

            harness.node.creates shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.DRAIN_FAILED
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a change that does not touch the container shape is not a replacement`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // Raising a drain timeout must not restart a server with players
            // on it. It is read fresh from the definition every pass.
            harness.declare(paperDefinition(saveTimeout = 6.minutes))
            val outcome = harness.settle(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Settled>()
            harness.node.creates shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
            harness.status(name).shouldNotBeNull().observedGeneration shouldBe 2L
        }

    @Test
    fun `a world saved during a drain does not keep the WORLD_SAVED condition true afterwards`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            harness.settle(name, limit = 16)

            // A week of play on the replacement container. Nothing has been
            // saved since the drain that replaced the old one.
            harness.clock.advance((7 * 24 * 60).minutes)
            harness.settle(name, limit = 6)

            val status = harness.status(name).shouldNotBeNull()
            // The audit record of the last confirmed save is kept…
            status.storage
                .shouldNotBeNull()
                .lastSaveConfirmedAt
                .shouldNotBeNull()
            // …and it is not evidence that the world *now* is on disk. Reading
            // the condition off that timestamp made a server that has been
            // running untouched for a week report itself saved.
            status.conditions
                .single { it.type == ConditionType.WORLD_SAVED }
                .status shouldBe ConditionStatus.FALSE
        }

    @Test
    fun `a memory change is a replacement`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.declare(
                paperDefinition(memoryBytes = 8L * MemoryQuantity.GIB, heapBytes = 6L * MemoryQuantity.GIB),
            )
            harness.settle(name, limit = 14)

            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .resources.memoryBytes shouldBe 8L * MemoryQuantity.GIB
        }
}

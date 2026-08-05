package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.MemoryQuantity
import mcorch.schema.ServerPhase
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

    /**
     * The sixteenth audit's second critical: the drain advanced exactly one pass
     * after the repair and then froze with the container stopped.
     *
     * `settleRecords`' hysteresis keeps a failure across a resume so the
     * escalation anchor is not restamped. It kept the `PERMANENT` class with it,
     * and `isBlockedByPermanentFailure` reads that class — so the pass that
     * resumed, stopped the container and moved to `STOPPING` wrote the retained
     * failure at the *new* generation, closing the gate behind itself.
     * `awaitStopped` and `teardown` never ran: workload never removed, sandbox
     * never reclaimed, replacement never created, and the status frozen quoting
     * a node fault the operator had already fixed.
     *
     * Asserting on the removal and the replacement rather than on the failure
     * being cleared, because a cleared failure is not what an operator was
     * promised — a running replacement is, and the audit asked for it that way.
     */
    @Test
    fun `an operator edit that repairs a refused stop finishes the replacement`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            // Rejected, not Timeout: a refusal the loop classifies PERMANENT, so
            // the gate arms rather than the drain simply retrying.
            harness.node.failAlways(
                NodeOperation.STOP,
                NodeException.Rejected(harness.node.name, NodeOperation.STOP, "the runtime refused the stop"),
            )
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            harness.settle(name, limit = 14)

            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                ?.state shouldBe DrainState.DRAIN_FAILED
            harness.node.removals shouldHaveSize 0
            harness.node.creates shouldHaveSize 1

            // The operator fixes the node and edits the definition, which is the
            // documented remedy and the only thing that lifts the gate.
            harness.node.clearFailures(NodeOperation.STOP)
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.1"))
            harness.settle(name, limit = 24)

            // The whole point: it got all the way through, not one step in.
            harness.node.removals shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .image.canonical shouldBe "docker.io/itzg/minecraft-server:2026.7.1"
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

    /**
     * The twenty-fifth audit's third warning: the pre-flight existed on the proxy
     * path only, and the kind that holds worlds went without it.
     *
     * `storage.mountPath`, `rcon.secret` and the *proxy's* forwarding secret are all
     * in a `PaperServer`'s spec hash, and every one of them can be edited into a
     * value the create refuses permanently — a relative mount path, a secret
     * reference that is not staged yet. A stored row reaches the same place without
     * an edit at all, because `DefinitionCodec` does not re-run the YAML reader's
     * validation. So: hash mismatch, drain, world saved, container stopped, container
     * removed, and only then a create that refuses for ever. No world is lost, and a
     * running server is taken down and cannot come back.
     *
     * ## Why the scenario has nobody on the server
     *
     * Zero players is the number that makes this test about *this* guard. With
     * players on, the drain's own zero-player gate blocks it at step 2 and nothing is
     * stopped whatever this code does — the assertions below would pass against the
     * build that has the defect. The destructive path needs an empty server, and an
     * empty server is the ordinary state of one being replaced out of hours.
     *
     * `stops` and `removals` are the load-bearing assertions. `drain` being null is
     * the discriminator for the *shape*: the question is asked before the protocol
     * begins, rather than by a drain that starts and then thinks better of it.
     */
    @Test
    fun `a server whose replacement the node cannot build is not drained, and keeps running`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            // The shape round 24 moved out of `StorageRequest.Persistent.init` and
            // into the node: a value only the create refuses, and permanently.
            harness.node.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(
                    harness.node.name,
                    NodeOperation.CREATE,
                    "`storage.mountPath` must be an absolute path inside the container",
                ),
            )
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))

            repeat(8) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            // Nothing was taken away on the strength of being able to build a
            // replacement that cannot be built.
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            harness.node.creates shouldHaveSize 1
            harness.node.saves.shouldBeEmpty()

            val status = harness.status(name).shouldNotBeNull()
            status.phase shouldBe ServerPhase.RUNNING
            status.ready.shouldBeTrue()
            status.drain.shouldBeNull()
            val failure = status.failure.shouldNotBeNull()
            // Retryable, and the class is a mechanism rather than a preference: the
            // remedy is an operator staging something, which bumps no generation, and
            // `isBlockedByPermanentFailure` lifts on nothing else.
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "cannot build the replacement"
            failure.message shouldContain "its world where it is"
        }

    /**
     * The same guard, with players on: they are never sealed, moved or waited for.
     *
     * The test above is the one that reddens against the defect; this is the one
     * that says what an operator sees. A drain that starts and blocks on players is
     * a `DRAIN_INITIATED` condition, a server excluded from being anybody's transfer
     * destination and a proxy-side seal — all of it in aid of a replacement that
     * cannot happen.
     */
    @Test
    fun `players are not drained for a replacement the node cannot build`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 4

            harness.node.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(harness.node.name, NodeOperation.CREATE, "the artefact is not on this node"),
            )
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val status = harness.status(name).shouldNotBeNull()
            status.drain.shouldBeNull()
            status.drainInitiated.shouldBeFalse()
            status.ready.shouldBeTrue()
            status.players.shouldNotBeNull().online shouldBe 4
            harness.node.stops.shouldBeEmpty()
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

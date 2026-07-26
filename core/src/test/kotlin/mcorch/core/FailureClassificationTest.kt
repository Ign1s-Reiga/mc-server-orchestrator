package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.PlacementSpec
import mcorch.schema.ServerPhase
import mcorch.store.StoreException
import org.junit.jupiter.api.Test

/**
 * CLAUDE.md: "Retryable failures requeue; permanent failures surface on the
 * server's observed status." Both paths have to be observable, and a permanent
 * failure has to actually stop the loop from trying again.
 */
internal class FailureClassificationTest {
    @Test
    fun `a transient node failure requeues and is recorded as retryable`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.node.failOnce(NodeOperation.IMAGE, harness.node.unreachable(NodeOperation.IMAGE))

            val outcome = harness.pass(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            val status = harness.status(name).shouldNotBeNull()
            val failure = status.failure.shouldNotBeNull()
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.reason shouldBe FailureReason.IMAGE_PULL_FAILED
            failure.attempts shouldBe 1
            harness.node.creates shouldHaveSize 0

            // The next pass tries again and gets through.
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Progressed>()
            harness.node.creates shouldHaveSize 1
        }

    @Test
    fun `repeated transient failures accumulate an attempt count`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.node.failAlways(NodeOperation.IMAGE, harness.node.unreachable(NodeOperation.IMAGE))

            harness.pass(name)
            harness.pass(name)
            harness.pass(name)

            harness
                .status(name)
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .attempts shouldBe 3
        }

    @Test
    fun `a permanent node failure surfaces and the loop stops re-attempting it`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.node.failAlways(NodeOperation.CREATE, harness.node.rejected(NodeOperation.CREATE))

            val first = harness.pass(name)

            first.shouldBeInstanceOf<ReconcileOutcome.Failed>()
            val status = harness.status(name).shouldNotBeNull()
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            status.failure.shouldNotBeNull().reason shouldBe FailureReason.CONTAINER_CREATE_FAILED

            // "Stops retrying" has to mean the operation is not attempted
            // again, not merely that the outcome says so. The image was pulled
            // once and nothing else was tried.
            val pulls = harness.node.pulls.size
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
            harness.node.pulls shouldHaveSize pulls
            harness
                .status(name)
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .attempts shouldBe 1
        }

    @Test
    fun `changing the definition lifts a permanent failure`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.node.failAlways(NodeOperation.CREATE, harness.node.rejected(NodeOperation.CREATE))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            // The operator fixes whatever it was. The generation moves, so the
            // loop acts again.
            harness.node.stopFailing(NodeOperation.CREATE)
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))

            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Progressed>()
            harness.node.creates shouldHaveSize 1
        }

    @Test
    fun `a pin at an unknown node is permanent, an unavailable node is not`() =
        coreTest {
            val harness = Harness()
            val pinned = paperDefinition(placement = PlacementSpec(node = nodeName("node-z")))
            harness.declare(pinned)

            val outcome = harness.pass(pinned.metadata.name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Failed>()
            val status = harness.status(pinned.metadata.name).shouldNotBeNull()
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            status.failure.shouldNotBeNull().reason shouldBe FailureReason.NODE_UNAVAILABLE
            status.phase shouldBe ServerPhase.PENDING

            // A node that is merely down comes back on its own.
            val other = Harness()
            other.node.ready = false
            val definition = paperDefinition()
            other.declare(definition)
            other.pass(definition.metadata.name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
            other
                .status(definition.metadata.name)
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.RETRYABLE
        }

    @Test
    fun `a retryable store failure requeues and a permanent one surfaces`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)

            harness.store.nextFailure = StoreException.Unavailable("the database is locked")
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()

            harness.store.nextFailure = StoreException.Corrupt("the stored spec cannot be decoded")
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
        }

    @Test
    fun `a container that exits on its own surfaces permanently and is not restarted`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(state = WorkloadState.EXITED, exitCode = 137, reason = "OOMKilled")

            val outcome = harness.pass(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Failed>()
            val status = harness.status(name).shouldNotBeNull()
            status.phase shouldBe ServerPhase.STOPPED
            status.failure.shouldNotBeNull().reason shouldBe FailureReason.CONTAINER_EXITED
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            harness.node.starts shouldHaveSize 1
            harness.node.creates shouldHaveSize 1
        }

    @Test
    fun `a pass whose definition was replaced mid-flight requeues instead of recording`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)

            // The pass reads the definition, acts, and the API server replaces
            // the definition before the observation lands. Recording it would
            // make the server look settled at a generation nobody wants.
            harness.store.beforeStatusWrite = {
                harness.store.putDefinition(paperDefinition(maxPlayers = 40))
            }
            val outcome = harness.pass(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness.status(name) shouldBe null
        }
}

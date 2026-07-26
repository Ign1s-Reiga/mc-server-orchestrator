package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.paper.PaperCommands
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.RconSpec
import mcorch.schema.StorageSpec
import org.junit.jupiter.api.Test

/**
 * The drain protocol.
 *
 * The assertions that matter are the negative ones: **no stop was issued** and
 * **the container is still running**. A drain that reports failure while having
 * stopped the server has lost exactly the data this protocol exists to protect.
 */
internal class DrainTest {
    @Test
    fun `an empty server is drained, saved, stopped, removed and purged in that order`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.node.removals shouldHaveSize 1
            // The grace period comes from the definition, which the schema
            // guarantees exceeds the save timeout.
            harness.node.stops
                .single()
                .second shouldBe definition.spec.lifecycle.stopGracePeriod
            harness.store.getServer(name) shouldBe null
            // The world outlives all of it.
            harness.node.volumes shouldHaveSize 1
        }

    @Test
    fun `a drain with players online aborts and the container survives`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 3
            harness.store.deleteDefinition(name)

            // requested -> sealed -> blocked
            harness.pass(name)
            harness.pass(name)
            val outcome = harness.pass(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_NO_DESTINATION
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
            // A failed drain must not read as progress toward a stop.
            status.draining.shouldBeFalse()

            // Nothing was stopped, nothing was removed, nobody was kicked.
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.saves shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            harness.store.getServer(name).shouldNotBeNull()
        }

    @Test
    fun `a blocked drain keeps the container running however many times it retries`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 1
            harness.store.deleteDefinition(name)

            // Reaching a retry limit is not a reason to force-stop
            // (`failure-modes.md` items 1 and 7).
            repeat(20) { harness.pass(name) }

            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a drain resumes on its own once the last player leaves`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 2
            harness.store.deleteDefinition(name)
            repeat(3) { harness.pass(name) }
            harness.status(name)?.drain?.state shouldBe DrainState.DRAIN_FAILED

            harness.node.online = 0
            harness.settle(name, limit = 12)

            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a save that times out aborts the drain and the container survives`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    throw NodeException.Timeout(harness.node.name, NodeOperation.EXEC, "the save outran its timeout")
                }
                harness.node.defaultExec(command)
            }
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_SAVE_TIMEOUT
            drain.worldSaved.shouldBeFalse()
            // The request went out, so it is recorded — and never repeated.
            drain.saveRequestedAt.shouldNotBeNull()
            harness.node.saves shouldHaveSize 1

            // A timeout tells you the save has not finished. It does not tell
            // you it is now fine to stop.
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            harness.store.getServer(name).shouldNotBeNull()
        }

    @Test
    fun `a save command that exits zero without confirming is a failed save`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // Exit code zero, and an error in the output. Conflating the two is
            // `failure-modes.md` item 2.
            harness.node.savesCleanly = false
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeFalse()
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a probe that cannot run is not a zero-player report`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
            harness.store.deleteDefinition(name)

            repeat(6) { harness.pass(name) }

            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.DRAIN_FAILED
            harness.node.stops shouldHaveSize 0
            harness.node.saves shouldHaveSize 0
        }

    @Test
    fun `a server with world data and no RCON cannot be drained and is not stopped`() =
        coreTest {
            val harness = Harness()
            // Persistent storage, no RCON: there is no channel through which a
            // completed save could be confirmed.
            val definition = paperDefinition(rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `an ephemeral server has no world to save and stops without one`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(storage = StorageSpec.Ephemeral(), rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a container that has already exited is torn down without a save`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(state = WorkloadState.EXITED, exitCode = 1)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            // There is nobody connected to a reaped process and nothing to
            // flush, so no save is attempted and no stop is needed.
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a definition is never purged while its workload is still there`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)
            // The removal keeps failing, so the workload never goes away.
            harness.node.failAlways(NodeOperation.REMOVE, harness.node.unreachable(NodeOperation.REMOVE))

            repeat(12) { harness.pass(name) }

            harness.node.removals shouldHaveSize 0
            // The store would happily purge a tombstoned definition. The guard
            // is here, where the container observation is.
            harness.store.getServer(name).shouldNotBeNull()

            harness.node.stopFailing(NodeOperation.REMOVE)
            harness.settle(name, limit = 6)
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `the drain records each state so a restart can resume it`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            val states = mutableListOf<DrainState>()
            repeat(7) {
                harness.pass(name)
                harness
                    .status(name)
                    ?.drain
                    ?.state
                    ?.let(states::add)
            }

            states shouldBe
                listOf(
                    DrainState.DRAIN_REQUESTED,
                    DrainState.SEALED,
                    DrainState.TARGET_RESOLVED,
                    DrainState.TRANSFERRING,
                    DrainState.SAVING,
                    DrainState.DEREGISTERED,
                    DrainState.STOPPING,
                )
        }

    @Test
    fun `zero players and a confirmed save are both recorded before the stop`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // Up to the pass that issues the stop.
            repeat(7) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            drain.playersEvacuated.shouldBeTrue()
            drain.worldSaved.shouldBeTrue()
            status.storage
                .shouldNotBeNull()
                .lastSaveConfirmedAt
                .shouldNotBeNull()
            harness.node.stops shouldHaveSize 1
        }
}

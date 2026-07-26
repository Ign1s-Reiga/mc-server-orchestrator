package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.paper.PaperCommands
import mcorch.schema.ConditionType
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.RconSpec
import mcorch.schema.StorageSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
    fun `a runtime that stops reporting a container is not a container that has gone`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            val running = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()

            // The node can no longer see the container, while the Paper server
            // inside it carries on serving. CRI's sandbox status carries
            // container statuses in an optional field: an empty one is
            // indistinguishable from an empty sandbox, and reading it that way
            // makes a live server look like one that was never created.
            harness.node.workload =
                running.copy(
                    state = WorkloadState.SANDBOX_ONLY,
                    handle = running.handle.copy(containerId = null),
                )
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            // No probe was possible, no save was taken, and above all the
            // sandbox was not torn down — which would have killed the server
            // with no grace period and no save.
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.saves shouldHaveSize 0
            harness.store.getServer(name).shouldNotBeNull()
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_STALLED

            // And when the runtime starts reporting it again, the drain carries
            // on and finishes properly.
            harness.node.workload = running
            harness.settle(name, limit = 16)
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a sandbox that has never had a container is still torn down`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            // A pass that got as far as the sandbox and then died. Nothing was
            // ever created in it, so there is provably no process inside and
            // the drain may clear it away.
            harness.node.workload =
                WorkloadObservation.Present(
                    handle = WorkloadHandle(harness.node.name, "sandbox-$name"),
                    state = WorkloadState.SANDBOX_ONLY,
                    createdAt = harness.clock.instant(),
                )
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 1
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
    fun `a save confirmed before a player joined does not authorise the stop after they leave`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // The drain gets as far as a confirmed save on an empty server.
            repeat(6) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeTrue()

            // Somebody joins before the stop is issued, plays for an hour, and
            // logs off. Nothing seals joins on a standalone server, so this is
            // an ordinary thing to happen mid-drain.
            harness.node.online = 1
            harness.pass(name)
            harness.node.stops shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeFalse()
            harness.clock.advance(60.minutes)
            repeat(3) { harness.pass(name) }
            harness.node.stops shouldHaveSize 0

            harness.node.online = 0
            harness.settle(name, limit = 14)

            // An hour of play is not covered by a save taken before it. The
            // drain had to ask for a second one, and only then could it stop.
            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    /** Runs [body] when the stop is issued, so a test can assert on the order of side effects. */
    private fun Harness.recordingStops(body: () -> Unit) {
        val runtime = node.onStop
        node.onStop = { present ->
            body()
            runtime(present)
        }
    }

    @Test
    fun `a save does not survive a window in which the loop could not see who was online`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(6) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.DEREGISTERED

            // The exec path goes unhealthy. The loop keeps running and keeps
            // asking — the gap between observations never grows — but every
            // answer is "cannot tell", which is not a zero-player report and
            // must not be treated as one in either direction. Ten minutes of
            // this is ten minutes in which players can arrive, play and log off
            // without a single pass seeing them: nothing seals joins on a
            // standalone server.
            harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
            repeat(30) {
                harness.clock.advance(20.seconds)
                harness.pass(name)
            }
            harness.node.stops shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeFalse()

            // The exec path recovers and the server is empty again — true, and
            // silent about the last ten minutes.
            harness.node.stopFailing(NodeOperation.EXEC)
            var savedBeforeStopping = 0
            harness.recordingStops { savedBeforeStopping = harness.node.saves.size }

            harness.settle(name, limit = 16)

            // The stop was allowed only after a save taken since the blind
            // window, not on the one confirmed before it.
            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
            savedBeforeStopping shouldBe 2
        }

    @Test
    fun `a save does not survive a window in which the loop was not running at all`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(6) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeTrue()

            // The orchestrator stops. The container carries on serving, players
            // play and log off, and half an hour later the loop comes back,
            // reads the drain out of the store, and resumes it at DEREGISTERED
            // with a confirmed save. The container never restarted, so nothing
            // about the workload says anything happened — the only witness that
            // nobody was watching is the gap in the loop's own observations.
            harness.clock.advance(30.minutes)

            var savedBeforeStopping = 0
            harness.recordingStops { savedBeforeStopping = harness.node.saves.size }
            harness.pass(name)

            harness.node.stops shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeFalse()

            harness.settle(name, limit = 16)

            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
            savedBeforeStopping shouldBe 2
        }

    @Test
    fun `a runtime that reports no container start time does not send the drain round in circles`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // No start time to compare a confirmation against. Rejecting every
            // confirmation on that basis is the tempting reading, and it makes
            // the drain save, decline to stop, save again — asking a live
            // server to flush its world for ever.
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(startedAt = null)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 16)

            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a drain resumed from the store does not stop on a save from the previous container`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // Reach DEREGISTERED: zero players, save confirmed, stop not issued.
            repeat(6) { harness.pass(name) }
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DEREGISTERED
            drain.worldSaved.shouldBeTrue()
            harness.node.stops shouldHaveSize 0

            // The loop stops here. A day passes, the container is restarted by
            // hand — a new process, a new world in memory — and the loop comes
            // back to a drain record that still says the world is saved. The
            // one probe it takes reports zero players, which is true and says
            // nothing about the day in between.
            harness.clock.advance(24.hours)
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(startedAt = harness.clock.instant())

            harness.pass(name)

            // No stop on a day-old confirmation from a process that is gone.
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING

            harness.settle(name, limit = 14)

            // It went back and saved again before it stopped anything.
            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
        }

    @Test
    fun `a stop is not re-issued while players are on a container that would not stop`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // The stop is issued and does not take: the container is still
            // running on the next pass.
            harness.node.onStop = { present -> present }
            repeat(7) { harness.pass(name) }
            harness.node.stops shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.STOPPING

            // Somebody is on the server that refused to stop. Re-issuing a stop
            // is normally safe — the save is on disk — but not when the save no
            // longer describes what they are doing.
            harness.node.online = 2
            repeat(4) { harness.pass(name) }

            harness.node.stops shouldHaveSize 1
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.worldSaved.shouldBeFalse()
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
        }

    @Test
    fun `an RCON client that never reached the server may try again`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // `rcon-cli` cannot connect: a non-zero exit with nothing from the
            // server in it. Nothing was delivered, so nothing has to be
            // preserved and a later attempt is safe.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "", "dial tcp 127.0.0.1:25575: connection refused")
                } else {
                    harness.node.defaultExec(command)
                }
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
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
            // The distinction the whole fix turns on: no delivered request, so
            // no record of one, so the server is not wedged.
            drain.saveRequestedAt shouldBe null
            drain.worldSaved.shouldBeFalse()
            harness.node.stops shouldHaveSize 0

            // The hiccup passes and the drain finishes on its own.
            harness.node.onExec = { command -> harness.node.defaultExec(command) }
            harness.settle(name, limit = 14)

            harness.node.saves
                .isNotEmpty()
                .shouldBeTrue()
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a save the server acknowledged and did not finish is still never re-sent`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // The server replied — it started saving — and the client then
            // failed. The request was delivered, so it must not be delivered
            // again on a guess.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "Saving the game (this may take a moment!)", "connection reset")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)

            repeat(10) { harness.pass(name) }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            drain.saveRequestedAt.shouldNotBeNull()
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
        }

    @Test
    fun `enabling RCON on a container that has none does not wedge the server`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The operator follows the advice on the stalled drain and enables
            // RCON. It is in the spec hash, so it asks for a recreate — and the
            // recreate has to drain the container that is running, which was
            // created with RCON disabled and has nothing listening. The loop
            // must not believe the new definition, must not send a save into
            // that socket, and above all must not record a request it never
            // delivered: that record is what used to make the state permanent
            // and unrecoverable.
            harness.store.putDefinition(paperDefinition(rcon = RconSpec.Enabled(passwordSecret = secretRef())))
            repeat(8) { harness.pass(name) }

            val stalled =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stalled.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_STALLED
            stalled.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            stalled.saveRequestedAt shouldBe null
            stalled.worldSaved.shouldBeFalse()
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.creates shouldHaveSize 1
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            // It says which way is out, and the way out is not another edit
            // against this container.
            stalled.failure
                .shouldNotBeNull()
                .message
                .contains("revert spec.network.rcon")
                .shouldBeTrue()

            // Reverting works, which is the whole point: the server goes back to
            // running with nothing left over.
            harness.store.putDefinition(definition)
            harness.settle(name, limit = 8).shouldBeInstanceOf<ReconcileOutcome.Settled>()
            val recovered = harness.status(name).shouldNotBeNull()
            recovered.drain shouldBe null
            recovered.ready.shouldBeTrue()
            harness.node.stops shouldHaveSize 0
        }

    @Test
    fun `a deleted server whose container has no RCON is finished off by hand and then torn down`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)
            repeat(8) { harness.pass(name) }

            val stalled =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stalled.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            stalled.saveRequestedAt shouldBe null
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            // A deleted definition cannot be edited back into shape — the store
            // refuses to write a tombstoned name — so the only way out is a
            // human, and the message has to say so rather than pointing at an
            // edit that cannot be made.
            stalled.failure
                .shouldNotBeNull()
                .message
                .contains("save the world and stop the container yourself")
                .shouldBeTrue()

            // So they do exactly that. The loop has to still be watching, or the
            // advice it gave is a dead end: a permanently failed drain that is
            // never looked at again cannot notice the container is gone.
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(state = WorkloadState.EXITED, exitCode = 0)
            harness.settle(name, limit = 12)

            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
            // And the world it was never able to save is still on disk.
            harness.node.volumes shouldHaveSize 1
        }

    @Test
    fun `switching a running server to ephemeral storage is refused rather than stopped without a save`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // `storage.mode` is in the spec hash, so this asks for a recreate —
            // and the recreate drains the container that is holding the world.
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            repeat(8) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            // Nothing was drained, nothing was saved, and above all nothing was
            // stopped without one.
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.creates shouldHaveSize 1
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING

            // Reverting the edit puts the server back where it was.
            harness.store.putDefinition(definition)
            harness.settle(name).shouldBeInstanceOf<ReconcileOutcome.Settled>()
            harness.node.stops shouldHaveSize 0
        }

    @Test
    fun `a rejected observation still records that a save request went out`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // An image change, so the drain is a replacement and the definition
            // is still writable while it runs.
            harness.store.putDefinition(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            // Up to the pass that requests the save.
            repeat(5) { harness.pass(name) }
            harness.node.saves shouldHaveSize 0

            // The operator edits the definition again while the saving pass
            // runs, so the observation carrying the save record is rejected.
            harness.store.beforeStatusWrite = {
                harness.store.putDefinition(paperDefinition(maxPlayers = 41))
            }
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness.node.saves shouldHaveSize 1

            // The record of the request has to have survived the rejection, or
            // the next pass asks a live server to save all over again.
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .saveRequestedAt
                .shouldNotBeNull()
            repeat(4) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
        }

    @Test
    fun `a drain that is broken and stuck says so, and still does not stop anything`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // The wrong RCON password: permanent in practice, indistinguishable
            // from a hiccup to the loop, so it is retried for ever and nothing
            // ever asks anybody to look at it.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "", "authentication failed")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)

            repeat(6) { harness.pass(name) }
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .message
                .startsWith(ATTENTION)
                .shouldBeFalse()

            harness.clock.advance(11.minutes)
            val outcome = harness.pass(name)

            val status = harness.status(name).shouldNotBeNull()
            val failure =
                status.drain
                    .shouldNotBeNull()
                    .failure
                    .shouldNotBeNull()
            failure.message.startsWith(ATTENTION).shouldBeTrue()
            status.conditions
                .single { it.type == ConditionType.DRAINING }
                .message
                .startsWith(ATTENTION)
                .shouldBeTrue()
            // The count an operator is shown has to be the count. A resume that
            // threw away the failure it was retrying made every pass of a
            // failing drain report its first attempt, occurring now.
            failure.attempts shouldBeGreaterThan 1

            // Everything else is exactly as it was. `failure-modes.md` item 7:
            // at a limit you stop trying, you do not stop the container — and
            // this does not even stop trying.
            failure.failureClass shouldBe FailureClass.RETRYABLE
            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a drain waiting for players to log off is never escalated, however long it waits`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 2
            harness.store.deleteDefinition(name)

            repeat(3) { harness.pass(name) }
            // Four hours of people playing on a server somebody asked to delete.
            // That is the protocol working as designed, and it resolves itself.
            harness.clock.advance(4.hours)
            repeat(3) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            val failure =
                status.drain
                    .shouldNotBeNull()
                    .failure
                    .shouldNotBeNull()
            failure.reason shouldBe FailureReason.DRAIN_NO_DESTINATION
            // Crying wolf on a busy evening every backoff interval is how an
            // operator learns the marker means nothing, and it is the only
            // escalation signal there is.
            failure.message.startsWith(ATTENTION).shouldBeFalse()
            status.conditions
                .single { it.type == ConditionType.DRAINING }
                .message
                .startsWith(ATTENTION)
                .shouldBeFalse()

            harness.node.online = 0
            harness.settle(name, limit = 16)
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a workload that does not say what it holds is saved before it is replaced`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // A container carrying none of this orchestrator's facts about
            // itself: created by an older build, or by hand. Absent is not
            // `false`, and neither the edited definition nor the storage status
            // derived from it is a second opinion worth having.
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(labels = emptyMap())

            var savedBeforeStopping = 0
            harness.recordingStops { savedBeforeStopping = harness.node.saves.size }
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.settle(name, limit = 16)

            // The edit is applied. Refusing it would make every replacement of a
            // genuinely ephemeral pre-label lobby a permanent failure, since
            // nothing here can tell that case from a transition. What must not
            // happen is the container going down without its world on disk.
            savedBeforeStopping shouldBe 1
            harness.node.stops shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .storage
                .shouldBeInstanceOf<StorageRequest.Ephemeral>()
        }

    @Test
    fun `a workload that says it holds a world refuses the same edit, because that one is a transition`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            repeat(8) { harness.pass(name) }

            harness
                .status(name)
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
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

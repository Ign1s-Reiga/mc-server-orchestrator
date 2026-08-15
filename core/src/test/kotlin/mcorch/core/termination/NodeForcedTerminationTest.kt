package mcorch.core.termination

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.ExecOutcome
import mcorch.core.FakeNode
import mcorch.core.SingleNodeScheduler
import mcorch.core.StaticNodeRegistry
import mcorch.core.TestStore
import mcorch.core.coreTest
import mcorch.core.paper.PaperWorkloadPlanner
import mcorch.core.paperDefinition
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.PaperServerDefaults
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.ServerPhase
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The forced stop, and the order it does things in.
 *
 * The ordering is the whole safety content. A stop before the save is a world
 * lost for no reason; a save that is skipped buys an operator the save timeout in
 * exchange for the data this system exists to protect. So the assertions are
 * about **sequence** and about what `saveConfirmed` reports, not about the fact
 * that a stop happened.
 */
internal class NodeForcedTerminationTest {
    private fun running(node: FakeNode) =
        coreTest {
            val definition = paperDefinition()
            node.ensureWorkload(PaperWorkloadPlanner.plan(definition))
            node.startWorkload((node.workload as mcorch.core.WorkloadObservation.Present).handle)
        }

    private val store = TestStore()

    private fun terminationOver(node: FakeNode) =
        NodeForcedTermination(
            StaticNodeRegistry(listOf(node)),
            store,
            SingleNodeScheduler(StaticNodeRegistry(listOf(node))),
        )

    /** A server the loop has observed, which is what the dispatch record hangs off. */
    private suspend fun observed(
        definition: PaperServerDefinition = paperDefinition(),
        drain: DrainStatus? = null,
    ) {
        store.putDefinition(definition)
        store.putStatus(
            PaperServerStatus(
                name = definition.metadata.name,
                observedGeneration = 1,
                phase = ServerPhase.RUNNING,
                observedAt = Instant.EPOCH,
                lastTransitionAt = Instant.EPOCH,
                drain = drain,
            ),
        )
    }

    private suspend fun recordedDrain(definition: PaperServerDefinition = paperDefinition()): DrainStatus? =
        (store.getServer(definition.metadata.name)?.status?.status as? PaperServerStatus)?.drain

    @Test
    fun `the save is requested before the container is stopped`() =
        coreTest {
            val node = FakeNode()
            running(node)
            val order = mutableListOf<String>()
            node.onExec = { command ->
                order += "exec:${command.joinToString(" ")}"
                // Delegate rather than answer everything the same way: overriding
                // the probe's reply too is how the first version of this test made
                // a populated server look unreadable.
                node.defaultExec(command)
            }

            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)

            // Probe, save, probe, then stop — and the sequence is the safety content.
            // The first probe because an unacknowledged population must be refused
            // before anything is sent; the save before the stop because a stop
            // recorded first is a world thrown away that could have been written;
            // and the **second probe** because nothing holds the first one's answer.
            // There is no seal on this path, so between the first reading and the
            // stop lies a whole `saveTimeout` in which anybody may join.
            order shouldHaveSize 3
            order[0] shouldContain "mc-monitor"
            order[1] shouldContain "save-all"
            order[2] shouldContain "mc-monitor"
            node.stops shouldHaveSize 1
            outcome.saveConfirmed shouldBe true
            outcome.detail shouldContain "confirmed"
        }

    @Test
    fun `an unconfirmed save still stops, and says the world may be lost`() =
        coreTest {
            val node = FakeNode()
            running(node)
            // The server answers the *save* without a confirmation — the state this
            // path exists for, and the one an ordinary drain refuses to stop from.
            // The probe is delegated: answering it the same way would make this a
            // test of the occupancy refusal wearing a save test's name.
            node.onExec = { command ->
                if (command
                        .joinToString(
                            " ",
                        ).contains("save-all")
                ) {
                    ExecOutcome(0, "", "")
                } else {
                    node.defaultExec(command)
                }
            }

            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)

            node.stops shouldHaveSize 1
            outcome.saveConfirmed shouldBe false
            // The wording an operator reads. It says what was lost rather than that
            // the request succeeded.
            outcome.detail shouldContain "unsaved play"
        }

    @Test
    fun `the stop uses the declared grace period, not a shortened one`() =
        coreTest {
            val node = FakeNode()
            running(node)
            node.onExec = { command ->
                if (command.joinToString(" ").contains("save-all")) {
                    ExecOutcome(0, "Saved the game", "")
                } else {
                    node.defaultExec(command)
                }
            }

            // Forcing shortens the orchestrator's patience, never the server's. The
            // grace period is the last protection still working when RCON is not,
            // so it is the one thing this path must not trim.
            val definition = paperDefinition(saveTimeout = 2.minutes, stopGracePeriod = 5.minutes)
            terminationOver(node).stop(definition, OccupancyAcknowledgement.None)

            node.stops.single().second shouldBe 5.minutes
        }

    @Test
    fun `a server with no running workload is refused rather than reported stopped`() =
        coreTest {
            val node = FakeNode()
            // Nothing created, so there is no container.
            shouldThrow<ForcedTerminationUnavailable> {
                terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)
            }
            node.stops shouldHaveSize 0
        }

    @Test
    fun `a populated server is refused unless the caller acknowledges it`() =
        coreTest {
            val node = FakeNode()
            node.online = 12
            running(node)

            // Without the acknowledgement nothing is sent and nothing is stopped:
            // the drain would have transferred these players, and this path cannot.
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)
            }.message.toString() shouldContain "12 player"
            node.stops shouldHaveSize 0

            // With it, the count is carried into the outcome rather than lost — the
            // audit record has to be able to say how many sessions were dropped.
            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.Count(12))
            outcome.playersOnline shouldBe 12
            node.stops shouldHaveSize 1
        }

    @Test
    fun `a server that does not answer a player count is unknown, never empty`() =
        coreTest {
            val node = FakeNode()
            // The probe fails. Reading that as "nobody is online" is how this path
            // would stop a populated server while reporting that it did not.
            node.joinable = false
            running(node)

            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)
            }.message.toString() shouldContain "cannot be shown to be empty"
            node.stops shouldHaveSize 0
        }

    @Test
    fun `a container with no save channel is stopped, and says no save was sent`() =
        coreTest {
            val node = FakeNode()
            // The note-1 population: SAVE_CONFIRMABLE false, so `requestSave` returns
            // Unconfirmable without building an exec. The first version of this file
            // reported that identically to a save that timed out.
            node.labelOverrides[mcorch.core.Labels.SAVE_CONFIRMABLE] = "false"
            running(node)
            node.onExec = { command ->
                if (command
                        .joinToString(
                            " ",
                        ).contains("save-all")
                ) {
                    ExecOutcome(0, "", "")
                } else {
                    node.defaultExec(command)
                }
            }

            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)

            // Nothing was sent, and the outcome says so rather than saying
            // "not confirmed", which is what a save that *was* sent reports.
            outcome.saveAttempted shouldBe false
            outcome.saveConfirmed shouldBe false
            outcome.detail shouldContain "no world save could be sent"
            node.stops shouldHaveSize 1
        }

    @Test
    fun `a grace period too short to be the save it would become is raised, not refused`() =
        coreTest {
            val node = FakeNode()
            node.labelOverrides[mcorch.core.Labels.SAVE_CONFIRMABLE] = "false"
            running(node)

            // No save request will be sent, so the grace period is the entire save
            // rather than a backstop behind one. The schema's own minimum is
            // saveTimeout + 30s, so this is close to the smallest pair it permits —
            // and far under what this project says a save takes.
            val definition = paperDefinition(saveTimeout = 5.seconds, stopGracePeriod = 35.seconds)
            terminationOver(node).stop(definition, OccupancyAcknowledgement.None)

            // Raised to the shutdown-save allowance. The previous version *refused*
            // here, and that refusal ran below the tombstone the caller had already
            // written — a definition that cannot be edited, so "raise it and force
            // again" was advice nobody could take and the server was left reachable
            // only by `crictl`. Raising costs nothing: the grace period is a ceiling
            // on containerd's patience, so a server that saves in two seconds exits
            // in two seconds.
            node.stops.single().second shouldBe PaperServerDefaults.SAVE_TIMEOUT
        }

    @Test
    fun `a declared grace period longer than the allowance is left alone`() =
        coreTest {
            val node = FakeNode()
            node.labelOverrides[mcorch.core.Labels.SAVE_CONFIRMABLE] = "false"
            running(node)

            // The floor only ever raises. An operator who declared twenty minutes
            // for a big world must not have it cut to the allowance.
            val definition = paperDefinition(saveTimeout = 5.minutes, stopGracePeriod = 20.minutes)
            terminationOver(node).stop(definition, OccupancyAcknowledgement.None)

            node.stops.single().second shouldBe 20.minutes
        }

    @Test
    fun `an unbuildable save timeout is refused by preflight, where the definition can still be fixed`() =
        coreTest {
            val node = FakeNode()
            running(node)

            // `ExecRequest`'s own `init` refuses this, and the drain turns that into
            // a recorded failure saying "correct that field". Below a tombstone that
            // sentence is uncorrectable, so the check has to run before one is
            // written — which is what `preflight` is for.
            val definition = paperDefinition(saveTimeout = Duration.ZERO, stopGracePeriod = 5.minutes)
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).preflight(definition, OccupancyAcknowledgement.None)
            }.message.toString() shouldContain "saveTimeout"
            node.stops shouldHaveSize 0
        }

    @Test
    fun `preflight refuses a population before anything has been written`() =
        coreTest {
            val node = FakeNode()
            node.online = 7
            running(node)

            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).preflight(paperDefinition(), OccupancyAcknowledgement.None)
            }.message.toString() shouldContain "7 player"
            node.stops shouldHaveSize 0
        }

    @Test
    fun `preflight is silent when there is no workload, because that is an ordinary delete`() =
        coreTest {
            val node = FakeNode()
            // Nothing created. A refusal here would turn "delete a server whose
            // container is already gone" into a 409 the caller cannot clear.
            terminationOver(node).preflight(paperDefinition(), OccupancyAcknowledgement.None)
            node.stops shouldHaveSize 0
        }

    @Test
    fun `an acknowledged count that no longer matches is refused`() =
        coreTest {
            val node = FakeNode()
            node.online = 12
            running(node)

            // The caller was shown 12 and acknowledged 12; by the time the request
            // lands there are 30. A boolean flag cannot tell those apart, which is
            // why the acknowledgement is a count.
            node.online = 30
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.Count(12))
            }.message.toString() shouldContain "30 player"
            node.stops shouldHaveSize 0
        }

    @Test
    fun `acknowledging an unreadable count does not cover a server that answered`() =
        coreTest {
            val node = FakeNode()
            node.online = 4
            running(node)

            // `Unreadable` is a value, not a wildcard. If it matched anything, the
            // acknowledgement every wedged server needs would silently authorise
            // stopping a healthy populated one.
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.Unreadable)
            }.message.toString() shouldContain "4 player"
            node.stops shouldHaveSize 0

            // And the converse: a count does not cover a server that answered nothing.
            node.joinable = false
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.Count(4))
            }.message.toString() shouldContain "cannot be shown to be empty"
            node.stops shouldHaveSize 0
        }

    @Test
    fun `players who join during the save are seen, because the count is read again`() =
        coreTest {
            val node = FakeNode()
            running(node)

            // Empty when the decision is made. There is no seal on this path, so
            // nothing holds that zero: the drain's `requireEmpty` is durable only
            // because `holdSeal` keeps the proxy from routing joins, and the table
            // in this seam's KDoc says that step is not done here.
            //
            // The window is a whole `saveTimeout` wide — up to an hour, per
            // `SpecBounds` — and this is the branch that asks the caller for nothing
            // at all, so a single probe would let it stop a populated server having
            // reported zero and consulted nobody.
            node.onExec = { command ->
                if (command.joinToString(" ").contains("save-all")) node.online = 3
                node.defaultExec(command)
            }

            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)
            }.message.toString() shouldContain "3 player"
            node.stops shouldHaveSize 0
        }

    @Test
    fun `the stop is recorded before it is dispatched, so the loop does not drain over it`() =
        coreTest {
            val node = FakeNode()
            running(node)
            observed()

            terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)

            // `DrainStatus.stopDispatchedAt` is what `stopIsInFlight` answers on.
            // Without it the loop's next pass sees a RUNNING container under a
            // terminating definition, reads it as a drain that has not started, and
            // walks the ladder — seal, destination, transfer, requireEmpty, **save**
            // — into a process already running its shutdown save. The only thing
            // that stood between that and a second `save-all flush` was whether a
            // dying server still answers a ping with zero, which is a coincidence.
            //
            // This went unrecorded through three commits of this feature.
            val drain = recordedDrain()
            drain?.stopDispatchedAt shouldNotBe null
            drain?.state shouldBe DrainState.STOPPING
        }

    @Test
    fun `an existing drain keeps its first dispatch instant rather than being restamped`() =
        coreTest {
            val node = FakeNode()
            running(node)
            // Inside the grace window, so the stop really is still in flight. The
            // refusal is bounded by that window rather than by the stamp existing —
            // see the test below for why.
            val first = Instant.now()
            observed(
                drain =
                    DrainStatus(
                        state = DrainState.SAVING,
                        startedAt = first,
                        enteredStateAt = first,
                        stopDispatchedAt = first,
                    ),
            )

            // Refused, because a dispatched stop is already in flight — and the
            // record is left exactly as it was. "May a SIGTERM already be in that
            // container" is the question readers ask; the most recent one is not.
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)
            }.message.toString() shouldContain "already has a stop in flight"
            node.stops shouldHaveSize 0
            recordedDrain()?.stopDispatchedAt shouldBe first
        }

    @Test
    fun `a stop dispatched longer ago than the grace period no longer bars a force`() =
        coreTest {
            val node = FakeNode()
            running(node)
            // Long past any grace period a definition can carry.
            val ancient = Instant.parse("2020-01-01T00:00:00Z")
            observed(
                drain =
                    DrainStatus(
                        state = DrainState.STOPPING,
                        startedAt = ancient,
                        enteredStateAt = ancient,
                        stopDispatchedAt = ancient,
                    ),
            )

            terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)

            // **The escape hatch had a lock on it.** `stop` records the dispatch
            // *before* calling the node — correct, and the field's KDoc argues that
            // asymmetry — so a `NodeException` from the stop leaves the stamp
            // written and the container still running. Keying the refusal on the
            // stamp's existence then refused every retry, forever: the definition is
            // tombstoned so nothing can be edited, the drain cannot finish for the
            // population this path exists for, and one transient CRI failure was
            // enough to make a server `crictl`-only.
            //
            // Bounding it by the grace period matches `DrainController.awaitStopped`,
            // which already re-issues a stop that did not take. Once the window has
            // passed, the reason to refuse — the container is mid-shutdown-save — has
            // passed with it.
            node.stops shouldHaveSize 1
        }

    @Test
    fun `a force into an outstanding save skips its own rather than refusing`() =
        coreTest {
            val node = FakeNode()
            running(node)
            val requested = Instant.now()
            observed(
                drain =
                    DrainStatus(
                        state = DrainState.DRAIN_FAILED,
                        startedAt = requested,
                        enteredStateAt = requested,
                        saveRequestedAt = requested,
                    ),
            )
            var saves = 0
            node.onExec = { command ->
                if (command.joinToString(" ").contains("save-all")) saves++
                node.defaultExec(command)
            }

            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.None)

            // **This state is `docs/operating.md` note 1, and it used to be
            // refused.** `saveRequestedAt` beside `DRAIN_FAILED` is what the
            // `SaveOutcome.Unconfirmed` arm writes, and only a confirmation or a
            // pass that has *observed a player* clears it — `forgetSaveEvidence` is
            // reachable from nowhere else. A wedged server answers no probe, so no
            // pass observes a player, so it never lifts. The operator was told to
            // "wait for it to confirm or fail" about something that can do neither,
            // below a tombstone, forever.
            //
            // Skipping is what the refusal was actually protecting: the harm is a
            // second `save-all flush` on a main thread already running one, and not
            // sending it prevents that in full. Refusing on top bought nothing and
            // cost the one operation the caller cannot get any other way.
            saves shouldBe 0
            node.stops shouldHaveSize 1

            // And it says so rather than reporting a save that was never sent.
            outcome.saveAttempted shouldBe false
            outcome.saveConfirmed shouldBe false
            outcome.detail shouldContain "had not been confirmed"
        }

    @Test
    fun `an observed player voids the outstanding save, so one is sent after all`() =
        coreTest {
            val node = FakeNode()
            node.online = 6
            running(node)
            val requested = Instant.now()
            observed(
                drain =
                    DrainStatus(
                        state = DrainState.DRAIN_FAILED,
                        startedAt = requested,
                        enteredStateAt = requested,
                        saveRequestedAt = requested,
                    ),
            )
            var saves = 0
            node.onExec = { command ->
                if (command.joinToString(" ").contains("save-all")) saves++
                node.defaultExec(command)
            }

            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.Count(6))

            // **The record stopped describing the world the moment a player logged
            // in.** `DRAIN_FAILED` is deliberately not a sealing state, so a drain
            // parked on an unconfirmed save has its backend admitted again and
            // people play on it. `forgetSaveEvidence` is how the drain handles that:
            // any pass reading a positive count clears `saveRequestedAt`, because
            // the request no longer says what is on disk.
            //
            // Skipping here regardless would have inherited the wedge's protection
            // without its discipline, and stopped the container having sent no save
            // at all — losing every change made since that stale request.
            saves shouldBe 1
            node.stops shouldHaveSize 1
            outcome.saveAttempted shouldBe true
            // Nothing was skipped, so there is nothing to report as outstanding.
            outcome.saveOutstandingSince shouldBe null
        }

    @Test
    fun `players who arrive during the save wait still void a stale outstanding save`() =
        coreTest {
            val node = FakeNode()
            running(node)
            val requested = Instant.now()
            observed(
                drain =
                    DrainStatus(
                        state = DrainState.DRAIN_FAILED,
                        startedAt = requested,
                        enteredStateAt = requested,
                        saveRequestedAt = requested,
                    ),
            )
            var saves = 0
            var probes = 0
            // Empty on the first reading; three players by the second. Standalone, so
            // there is no seal holding the count and both probes really happen.
            node.onExec = { command ->
                val text = command.joinToString(" ")
                if (text.contains("save-all")) saves++
                if (text.contains("mc-monitor")) {
                    probes++
                    if (probes > 1) node.online = 3
                }
                node.defaultExec(command)
            }

            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.Count(3))

            // **A `Count(3)` gets past a first probe of zero**, because
            // `refuseOccupancy` returns early on an observed zero without consulting
            // the acknowledgement at all — there is nobody to acknowledge. So the
            // save decision cannot be taken from the first reading alone: these three
            // joined while the stop was being prepared, changed the world after the
            // outstanding request, and would have been stopped with no save at all.
            saves shouldBe 1
            node.stops shouldHaveSize 1
            outcome.saveAttempted shouldBe true
            // Nothing was skipped in the end, so nothing is reported as outstanding —
            // and the audit log says the same, rather than a non-null instant beside
            // `saveAttempted: true`.
            outcome.saveOutstandingSince shouldBe null
        }

    @Test
    fun `an unreadable count is not an observed player, so the skip stands`() =
        coreTest {
            val node = FakeNode()
            node.joinable = false
            running(node)
            val requested = Instant.now()
            observed(
                drain =
                    DrainStatus(
                        state = DrainState.DRAIN_FAILED,
                        startedAt = requested,
                        enteredStateAt = requested,
                        saveRequestedAt = requested,
                    ),
            )
            var saves = 0
            node.onExec = { command ->
                if (command.joinToString(" ").contains("save-all")) saves++
                node.defaultExec(command)
            }

            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.Unreadable)

            // Unknown is not zero and it is not a player either. Reading an
            // unanswered probe as evidence that somebody is on would send the second
            // flush the wedge exists to prevent, into the wedged server that is this
            // endpoint's whole population.
            saves shouldBe 0
            node.stops shouldHaveSize 1
            outcome.saveAttempted shouldBe false
            outcome.saveOutstandingSince shouldBe requested
        }

    @Test
    fun `the reported count is the one from just before the stop`() =
        coreTest {
            val node = FakeNode()
            node.online = 5
            running(node)
            node.onExec = { command ->
                if (command.joinToString(" ").contains("save-all")) node.online = 5
                node.defaultExec(command)
            }

            val outcome = terminationOver(node).stop(paperDefinition(), OccupancyAcknowledgement.Count(5))

            // An audit record of who was dropped has to be from the instant they
            // were dropped, not from a probe a save-timeout earlier.
            outcome.playersOnline shouldBe 5
            node.stops shouldHaveSize 1
        }
}

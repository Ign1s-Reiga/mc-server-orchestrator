package mcorch.core.termination

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.ExecOutcome
import mcorch.core.FakeNode
import mcorch.core.StaticNodeRegistry
import mcorch.core.coreTest
import mcorch.core.paper.PaperWorkloadPlanner
import mcorch.core.paperDefinition
import org.junit.jupiter.api.Test
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

    private fun terminationOver(node: FakeNode) = NodeForcedTermination(StaticNodeRegistry(listOf(node)))

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

            val outcome = terminationOver(node).stop(paperDefinition(), acknowledgeOccupancy = true)

            // Probe, then save, then stop — and the sequence is the safety content.
            // The probe first because an unacknowledged population must be refused
            // before anything is sent; the save before the stop because a stop
            // recorded first is a world thrown away that could have been written.
            order shouldHaveSize 2
            order[0] shouldContain "mc-monitor"
            order[1] shouldContain "save-all"
            node.stops shouldHaveSize 1
            outcome.saveConfirmed shouldBe true
            outcome.detail shouldContain "confirmed"
        }

    @Test
    fun `an unconfirmed save still stops, and says the world may be lost`() =
        coreTest {
            val node = FakeNode()
            running(node)
            // The server answers, but not with a confirmation — the state this path
            // exists for, and the one an ordinary drain refuses to stop from.
            node.onExec = { ExecOutcome(0, "", "") }

            val outcome = terminationOver(node).stop(paperDefinition(), acknowledgeOccupancy = true)

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
            node.onExec = { ExecOutcome(0, "Saved the game", "") }

            // Forcing shortens the orchestrator's patience, never the server's. The
            // grace period is the last protection still working when RCON is not,
            // so it is the one thing this path must not trim.
            val definition = paperDefinition(saveTimeout = 2.minutes, stopGracePeriod = 5.minutes)
            terminationOver(node).stop(definition, acknowledgeOccupancy = true)

            node.stops.single().second shouldBe 5.minutes
        }

    @Test
    fun `a server with no running workload is refused rather than reported stopped`() =
        coreTest {
            val node = FakeNode()
            // Nothing created, so there is no container.
            shouldThrow<ForcedTerminationUnavailable> {
                terminationOver(node).stop(paperDefinition(), acknowledgeOccupancy = true)
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
                terminationOver(node).stop(paperDefinition(), acknowledgeOccupancy = false)
            }.message.toString() shouldContain "12 player"
            node.stops shouldHaveSize 0

            // With it, the count is carried into the outcome rather than lost — the
            // audit record has to be able to say how many sessions were dropped.
            val outcome = terminationOver(node).stop(paperDefinition(), acknowledgeOccupancy = true)
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
                terminationOver(node).stop(paperDefinition(), acknowledgeOccupancy = false)
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
            var execs = 0
            node.onExec = {
                execs++
                ExecOutcome(0, "", "")
            }

            val outcome = terminationOver(node).stop(paperDefinition(), acknowledgeOccupancy = true)

            // Nothing was sent, and the outcome says so rather than saying
            // "not confirmed", which is what a save that *was* sent reports.
            outcome.saveAttempted shouldBe false
            outcome.saveConfirmed shouldBe false
            outcome.detail shouldContain "no world save could be sent"
            node.stops shouldHaveSize 1
        }

    @Test
    fun `a grace period too short to be the save it would become is refused`() =
        coreTest {
            val node = FakeNode()
            node.labelOverrides[mcorch.core.Labels.SAVE_CONFIRMABLE] = "false"
            running(node)

            // No save request will be sent, so the grace period is the entire save.
            // Ten seconds is not one, and stopping anyway would kill the container
            // mid-shutdown-save.
            // The schema already forces grace > saveTimeout + 30s, so this is the
            // smallest pair it permits — and still under the shutdown-save
            // allowance, which is the window that matters when nothing was sent.
            val definition = paperDefinition(saveTimeout = 5.seconds, stopGracePeriod = 35.seconds)
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(node).stop(definition, acknowledgeOccupancy = true)
            }.message.toString() shouldContain "only chance the world has"
            node.stops shouldHaveSize 0
        }
}

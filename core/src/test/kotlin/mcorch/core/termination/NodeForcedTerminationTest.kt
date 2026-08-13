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
                ExecOutcome(0, "Saved the game", "")
            }

            val outcome = terminationOver(node).stop(paperDefinition())

            // The save reached the server, and it reached it first. A stop recorded
            // before the exec would mean a world thrown away that could have been
            // written.
            order shouldHaveSize 1
            order.single() shouldContain "save-all"
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

            val outcome = terminationOver(node).stop(paperDefinition())

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
            terminationOver(node).stop(definition)

            node.stops.single().second shouldBe 5.minutes
        }

    @Test
    fun `a server with no running workload is refused rather than reported stopped`() =
        coreTest {
            val node = FakeNode()
            // Nothing created, so there is no container.
            shouldThrow<ForcedTerminationUnavailable> {
                terminationOver(node).stop(paperDefinition())
            }
            node.stops shouldHaveSize 0
        }
}

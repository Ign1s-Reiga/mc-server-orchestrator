package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import mcorch.core.paper.PaperCommands
import mcorch.core.paper.diagnose
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * CLAUDE.md: never log player names, UUIDs or addresses — and observed status is
 * served by the API and read into log lines, so the same rule applies to it.
 *
 * The danger is that server output is not ours. A Server List Ping reply carries
 * a `players.sample` block of names and UUIDs, a console reply carries whatever
 * a plugin printed, and both of those used to be truncated into a status field
 * on the way past. Truncating is not redacting: it keeps the first 200
 * characters, which is precisely where the names are.
 *
 * A reply that says nothing recognisable is therefore reported as a size, not as
 * a sample.
 */
internal class RedactionTest {
    private val name = "Notch"
    private val uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
    private val address = "203.0.113.42"

    private fun sampleReply(): ExecOutcome =
        ExecOutcome(
            exitCode = 0,
            stdout = """{"players":{"online":1,"max":20,"sample":[{"name":"$name","id":"$uuid"}]}}""",
            stderr = "peer $address",
        )

    private fun String.carriesPlayerData(): Boolean = contains(name) || contains(uuid) || contains(address)

    @Test
    fun `a diagnosis reports recognised failures by name and everything else by size`() {
        // A phrase from the whitelist is safe to repeat: it came from the
        // client, not from the server, and it is fixed text.
        ExecOutcome(1, "", "dial tcp: connection refused").diagnose().contains("connection refused").shouldBeTrue()

        // Anything else is measured, never quoted.
        val diagnosis = sampleReply().diagnose()
        diagnosis.carriesPlayerData().shouldBeFalse()
        diagnosis.contains("bytes on stdout").shouldBeTrue()

        PaperCommands.diagnostics(sampleReply().output).shouldBe(emptyList())
    }

    @Test
    fun `an unreadable ping reply never reaches observed status`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(startupTimeout = 1.minutes)
            val server = definition.metadata.name
            harness.declare(definition)
            // The server answers, and the answer is a players sample rather
            // than the occupancy line the loop wanted. It is unreadable to the
            // loop and full of exactly what must not be recorded.
            harness.node.onExec = { command ->
                if (command.firstOrNull() == "mc-monitor") sampleReply() else harness.node.defaultExec(command)
            }

            harness.pass(server)
            harness.pass(server)
            harness.clock.advance(5.minutes)
            harness.pass(server)

            val status = harness.status(server).shouldNotBeNull()
            val failure = status.failure.shouldNotBeNull()
            failure.message.carriesPlayerData().shouldBeFalse()
            // The whole status is served by the API, so nothing anywhere in it
            // may carry player data.
            status.toString().carriesPlayerData().shouldBeFalse()
        }

    @Test
    fun `a console reply that blocks a drain never reaches observed status`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val server = definition.metadata.name
            harness.declare(definition)
            harness.settle(server)
            // The save reply carries a plugin's chatter, with a player in it.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(0, "[Essentials] $name ($uuid) from $address is AFK", "")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(server)

            repeat(8) { harness.pass(server) }

            val status = harness.status(server).shouldNotBeNull()
            status.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .message
                .carriesPlayerData()
                .shouldBeFalse()
            status.toString().carriesPlayerData().shouldBeFalse()
            // Still the right verdict: an unrecognised reply is not a completed
            // save, so nothing was stopped.
            harness.node.stops
                .isEmpty()
                .shouldBeTrue()
        }
}

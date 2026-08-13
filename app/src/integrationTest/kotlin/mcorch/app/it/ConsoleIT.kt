package mcorch.app.it

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import mcorch.core.console.ConsoleDecision
import mcorch.core.console.ConsolePolicy
import mcorch.core.console.ConsoleTimedOut
import mcorch.core.console.ConsoleUnavailable
import mcorch.schema.Tier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The console, against a real Minecraft server.
 *
 * **This is the only test of the RCON client that is not a test of itself.**
 * `RconCodecTest` and `RconConnectionTest` drive the protocol against buffers and
 * streams this repository wrote, so they prove the implementation is
 * self-consistent and prove nothing about whether a Paper server agrees with it.
 * Two assumptions have been carried on reasoning alone, and both are flagged in
 * their own KDoc:
 *
 * - **The auth handshake.** Whether a real server answers `SERVERDATA_AUTH` the
 *   way `RconConnection.authenticate` expects, including whether it precedes the
 *   verdict with an empty `RESPONSE_VALUE`.
 * - **Multi-packet reassembly.** `readReply` stops at the first chunk that is not
 *   full, which is what every RCON client does and what the 4 KiB cap *usually*
 *   makes true.
 *
 * The first is now verified: `list`, `seed` and `help` all complete against a real
 * Paper server, so the handshake this client performs is one Paper accepts.
 *
 * ## Reassembly is still unverified, and this is what was tried
 *
 * **No reply this test could provoke exceeds one frame.** Measured, not assumed:
 *
 * | Attempt | Reply |
 * |---|---|
 * | `help` | 430 characters against a 4082-byte payload |
 * | 1200 scoreboard objectives, then `scoreboard objectives list` | 151 characters — Paper answers with a summary, not the set |
 *
 * So `readReply`'s multi-frame path is not reached here, and asserting on it would
 * mean asserting something this test does not exercise. Two earlier versions did
 * exactly that — one guessed `help` was long, one guessed a scoreboard listing
 * would be — and both failed for reasons that said nothing about the client.
 *
 * That the path is hard to reach is itself worth knowing: it is correspondingly
 * rare in production, which lowers the cost of the heuristic being wrong without
 * removing it. If a reply ever does span frames and the heuristic is wrong, the
 * symptom is a **silently truncated** reply — an operator shown partial output
 * with no error. The fix, if it comes to that, is the sentinel approach: send a
 * second harmless command and treat its reply as the terminator, rather than
 * inferring the end from a chunk's size.
 *
 * ## One server, several assertions
 *
 * The first version of this file brought up a separate server per assertion and
 * failed all three. This host has roughly 2 GiB free against a 2 GiB-per-server
 * request, so the first readiness wait timed out, and the sandbox it left behind
 * collided with the creates that followed — three failures from one cause.
 *
 * Every question below can be asked of one running server, so it is. That is not
 * only a capacity fix: a single server means the assertions run against **one**
 * RCON session's lifetime, which is what a real console has. Three servers proved
 * less while costing three times as much.
 */
@Timeout(value = 12, unit = TimeUnit.MINUTES)
internal class ConsoleIT {
    @TempDir
    lateinit var root: Path

    private lateinit var harness: ContainerdHarness

    @BeforeEach
    fun open() {
        harness = ContainerdHarness(root)
    }

    @AfterEach
    fun cleanUp() {
        harness.close()
    }

    @Test
    fun `the console reaches a real server, and refuses what it must`() =
        integrationTest {
            val definition = paperServer(name = "it-console", hostPort = 30417)
            val name = definition.metadata.name
            harness.putSecret(rconSecret("it-console"), "integration-rcon-password")
            harness.declare(definition)
            harness.start(this)
            harness.await("the server to answer a Server List Ping") { harness.status(name)?.ready == true }

            val console = harness.console

            // The smallest round trip that proves the whole path: the sandbox
            // address came from the Node handle, the password was resolved from the
            // secret store, the auth handshake was accepted, and a reply was framed
            // and read back.
            val listed = console.run(definition, "list")
            listed.shouldNotBeEmpty()
            // Paper's wording varies by version, so this asserts the shape rather
            // than the sentence.
            listed shouldContain "player"

            // A second command, because the first proves nothing about whether the
            // connection was left in a state the next one can use.
            console.run(definition, "seed").shouldNotBeEmpty()

            // `help` is measured rather than asserted against a threshold, because
            // the number is the finding: see the note on reassembly in this class's
            // KDoc.
            val help = console.run(definition, "help")
            println("[console-it] help reply: ${help.length} chars, frame payload is $RCON_FRAME_PAYLOAD")
            help.shouldNotBeEmpty()

            // The unit tests prove ConsolePolicy refuses `stop`. This proves the
            // refusal is what stands between a live server and a stop it would
            // otherwise carry out: the server here is real, running, and would obey.
            val decision = ConsolePolicy.screen("stop", held = Tier.SUPERUSER, ceiling = definition.spec.console)
            (decision is ConsoleDecision.RefusedOutright) shouldBe true

            // Still up and still answering, which is what would fail if anything
            // had dispatched it.
            harness.status(name)?.ready shouldBe true
            console.run(definition, "list").shouldNotBeEmpty()
        }

    /**
     * A command against a server with no workload.
     *
     * Needs no container, so it costs nothing. The distinction it pins is the one a
     * client acts on: [ConsoleUnavailable] is retryable and nothing was sent, while
     * [ConsoleTimedOut] means the command may have run.
     */
    @Test
    fun `a server with no workload reports unavailable rather than timing out`() =
        integrationTest {
            val definition = paperServer(name = "it-console-absent", hostPort = 30420)
            // Declared, but the loop is never started, so there is no sandbox.
            harness.putSecret(rconSecret("it-console-absent"), "integration-rcon-password")
            harness.declare(definition)

            val failure = runCatching { harness.console.run(definition, "list") }.exceptionOrNull()
            (failure is ConsoleUnavailable) shouldBe true
            (failure is ConsoleTimedOut) shouldBe false
        }

    private companion object {
        /** The 4 KiB frame cap, less the length prefix and the frame's own fields. */
        const val RCON_FRAME_PAYLOAD = 4082

        /** Objective names are capped at 16 characters, so this stays short. */
        const val OBJECTIVE_PREFIX = "mcorchit"

        /** Checked between batches rather than per objective, to keep the round trips down. */
        const val OBJECTIVE_BATCH = 100

        /**
         * A ceiling, so a server whose listing never grows fails as a bounded test
         * rather than running until the class timeout and reporting nothing.
         */
        const val MAX_OBJECTIVES = 1200
    }
}

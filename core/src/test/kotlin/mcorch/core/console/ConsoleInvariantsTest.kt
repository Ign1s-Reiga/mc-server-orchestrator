package mcorch.core.console

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The gate that no tier can pass.
 *
 * Two kinds of assertion here, and both are needed. The refusals prove the gate
 * closes on every way of writing a lifecycle command — which is the whole job,
 * because an allow-list added to carelessly is exactly the case this exists for.
 * The permissions are the control: without them a gate that refused *everything*
 * would pass every refusal test, and the most important thing this must not
 * refuse is the command the drain itself runs.
 */
internal class ConsoleInvariantsTest {
    private fun refusalOf(line: String): ConsoleScreening.Refused =
        ConsoleInvariants.screen(line).shouldBeInstanceOf<ConsoleScreening.Refused>()

    private fun permits(line: String) = ConsoleInvariants.screen(line) shouldBe ConsoleScreening.Permitted

    @Test
    fun `stop is refused, however it is spelled`() {
        // Each of these is a different way past a set that only knows "stop":
        // a slash prefix, Brigadier's namespace, case, padding, and arguments.
        listOf(
            "stop",
            "/stop",
            "//stop",
            "minecraft:stop",
            "/minecraft:stop",
            "STOP",
            "Stop",
            "  stop  ",
            "stop now",
            "\tstop\t",
        ).forEach { line ->
            refusalOf(line).reason shouldBe RefusalReason.ENDS_THE_SERVER
        }
    }

    @Test
    fun `the rest of the restart family is refused too`() {
        listOf("restart", "shutdown", "halt").forEach { line ->
            refusalOf(line).reason shouldBe RefusalReason.ENDS_THE_SERVER
        }
    }

    @Test
    fun `save-off is refused, and reports the reason that is not about stopping`() {
        val refusal = refusalOf("save-off")
        refusal.reason shouldBe RefusalReason.DISABLES_WORLD_SAVING
        refusal.verb shouldBe "save-off"
    }

    @Test
    fun `the refused verb is reported normalised, so a caller is told what was read`() {
        refusalOf("/minecraft:STOP world").verb shouldBe "stop"
    }

    @Test
    fun `a line that cannot be reduced to one verb is refused rather than waved through`() {
        // A newline may carry a second command. The API rejects those before they
        // reach here; this does not rely on that.
        listOf("stop\nsay hi", "say hi\nstop", "list\r\nstop", "", "   ", "/", "//", "minecraft:")
            .forEach { line ->
                refusalOf(line).reason shouldBe RefusalReason.UNSCREENABLE
            }
    }

    @Test
    fun `save-all is permitted, because it is what the drain runs`() {
        // PaperServerAgent.saveAll() issues `rcon-cli save-all flush`. If this gate
        // ever refused it, the gate would be refusing the drain's own save — the
        // operation the two invariants above exist to protect.
        permits("save-all")
        permits("save-all flush")
        permits("/minecraft:save-all flush")
    }

    @Test
    fun `save-on is permitted, because it is the repair for save-off`() {
        permits("save-on")
    }

    @Test
    fun `ordinary commands are permitted, so the refusals above are not vacuous`() {
        // Control. A gate that refused everything would satisfy every assertion
        // in this file except these.
        listOf("list", "say hello", "tps", "whitelist list", "kick", "seed", "difficulty peaceful")
            .forEach { permits(it) }
    }

    @Test
    fun `permitting here is not permitting overall`() {
        // `kick` passes this gate and is still subject to the tier gate and the
        // allow-list. Nothing in this type decides that a command may run.
        ConsoleInvariants.screen("kick") shouldBe ConsoleScreening.Permitted
    }
}

package mcorch.app

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.testing.KotlinSource
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The operator's lever on the Velocity pin, from the environment to the reconciler.
 *
 * `mcorch.core.ReconcilerConfig.velocityBuild` exists because a spec-hash input that
 * lives in orchestrator source is a fleet-wide replacement with no exit: a proxy's
 * own drain seals its login path and waits for the last player to log off, and on a
 * fleet that does not empty that wait never ends. A knob nobody can reach is not an
 * exit, so the two hops between `MCORCH_VELOCITY_BUILD` and the reconciler are what
 * make the fix real, and both are asserted here.
 */
internal class VelocityPinWiringTest {
    @Test
    fun `the pin is read from the environment, and blank means unset`() {
        val base =
            mapOf(
                OrchestratorConfig.ENDPOINT_VARIABLE to "unix:///run/mcorch-dev/containerd.sock",
                OrchestratorConfig.DATA_VARIABLE to "/var/lib/mcorch",
            )

        OrchestratorConfig.fromEnvironment(base).velocityBuild.shouldBeNull()
        OrchestratorConfig
            .fromEnvironment(base + (OrchestratorConfig.VELOCITY_BUILD_VARIABLE to "4.0.0"))
            .velocityBuild shouldBe "4.0.0"
        // A blank pin would otherwise be a spec-hash input of "": a fleet-wide
        // recreate spelled as a typo, and `ReconcilerConfig` refuses it outright.
        OrchestratorConfig
            .fromEnvironment(base + (OrchestratorConfig.VELOCITY_BUILD_VARIABLE to "  "))
            .velocityBuild
            .shouldBeNull()
    }

    /**
     * The second hop, pinned by shape.
     *
     * Opening an [Orchestrator] needs a containerd socket and a data directory, so no
     * test here can read the reconciler's configuration back. What can be asserted is
     * that the composition root forwards the value rather than dropping it — which is
     * the whole failure mode: a configuration field nothing reads looks exactly like a
     * working lever until an operator has an outage to end.
     */
    @Test
    fun `the composition root forwards the pin to the reconciler`() {
        val source = Path.of(ORCHESTRATOR)
        withClue("expected the module directory as the working directory; no $ORCHESTRATOR") {
            Files.isRegularFile(source).shouldBeTrue()
        }

        source.readText() shouldContain "velocityBuild = config.velocityBuild"
    }

    /**
     * Opening the orchestrator reports a misconfiguration the way the two config
     * reads do, rather than as an uncaught stack trace.
     *
     * `LocalNode.open` carries a pre-flight `require` that refuses to build a node
     * whose CRI stop deadline is shorter than the largest grace period a drain can
     * hand it — the message names which of four constants across three modules to
     * move. `Orchestrator.open` was **outside** the `catch` that turns an
     * `IllegalArgumentException` into `cannot start: …` and [EXIT_MISCONFIGURED], so
     * that message reached an operator as a stack trace on the default exit code:
     * the one presentation guaranteed to be read as a crash rather than as something
     * to go and fix. A carefully written remedy that never reaches the person who
     * needs it is not a remedy.
     *
     * Pinned by shape for the same reason as the test above — `main` calls
     * `exitProcess` and needs a real containerd — and by **counting** the arms rather
     * than matching one, so that a third `fromEnvironment` added outside the channel
     * is caught too. The coupling worth knowing if this ever goes red: the arm
     * catches `IllegalArgumentException` because `require` throws exactly that, so
     * turning that pre-flight into a `NodeException` would slip past this catch
     * silently. Change both together.
     *
     * **It reads code, not text, and the difference is the count.** The first version
     * ran both regexes over the raw file. A KDoc mentioning `Orchestrator.open(`
     * would have reddened it spuriously, which is merely annoying — but a comment
     * containing the literal catch clause inflates the *handled* side, and that hides
     * a startup step genuinely outside the channel. One direction is noise and the
     * other is the defect this test exists for, so the comments have to go first.
     * [KotlinSource] is shared with `:core`'s scan rather than copied; its own KDoc
     * says why.
     *
     * A wart to note rather than fix here: `invalid.message` is nullable, so a
     * library throwing a bare `IllegalArgumentException()` logs `cannot start: null`.
     * That is true of all three arms and predates this branch.
     */
    @Test
    fun `every startup step that can refuse a configuration reports it as one`() {
        val code = KotlinSource.readCodeLines(MAIN).joinToString("\n")
        val refusing = Regex("""(OrchestratorConfig|ApiConfig)\.fromEnvironment\(|Orchestrator\.open\(""")
        val handled = Regex("""catch \(invalid: IllegalArgumentException\)""")

        // Not vacuous: the scan has to have found the steps at all. Without this a
        // stripper that blanked the file would read 0 == 0 and pass.
        withClue("no startup step found in $MAIN — this scan is not reading the file the claim is about") {
            refusing.findAll(code).count() shouldBe 3
        }
        withClue("a startup step that can refuse a configuration is not inside the misconfiguration channel") {
            refusing.findAll(code).count() shouldBe handled.findAll(code).count()
        }
        code shouldContain "exitProcess(EXIT_MISCONFIGURED)"
    }

    private companion object {
        const val ORCHESTRATOR: String = "src/main/kotlin/mcorch/app/Orchestrator.kt"
        const val MAIN: String = "src/main/kotlin/mcorch/app/Main.kt"
    }
}

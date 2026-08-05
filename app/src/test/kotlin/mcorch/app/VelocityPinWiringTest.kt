package mcorch.app

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    private companion object {
        const val ORCHESTRATOR: String = "src/main/kotlin/mcorch/app/Orchestrator.kt"
    }
}

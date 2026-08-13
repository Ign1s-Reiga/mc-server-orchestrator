package mcorch.api

import mcorch.core.termination.ForcedStopOutcome
import mcorch.core.termination.ForcedTermination
import mcorch.core.termination.ForcedTerminationUnavailable
import mcorch.schema.PaperServerDefinition

/**
 * A forced stop with no container behind it.
 *
 * The API tests run against a real store and a real socket but no containerd, so
 * there is nothing to stop. This answers as the real seam does in that case, which
 * is the state those tests are genuinely in.
 *
 * [recorded] lets a test assert the API reached the seam at all — the difference
 * between "refused before dispatch" and "dispatched and failed" is the whole point
 * of several of them.
 */
internal class RefusingForce : ForcedTermination {
    val recorded: MutableList<String> = mutableListOf()

    override suspend fun stop(definition: PaperServerDefinition): ForcedStopOutcome {
        recorded += definition.metadata.name.value
        throw ForcedTerminationUnavailable("`${definition.metadata.name.value}` has no workload in this test")
    }
}

/** A forced stop that succeeds, reporting whether the save was confirmed. */
internal class StoppingForce(
    private val saveConfirmed: Boolean,
) : ForcedTermination {
    val recorded: MutableList<String> = mutableListOf()

    override suspend fun stop(definition: PaperServerDefinition): ForcedStopOutcome {
        recorded += definition.metadata.name.value
        return ForcedStopOutcome(
            saveConfirmed = saveConfirmed,
            detail = if (saveConfirmed) "confirmed" else "not confirmed",
        )
    }
}

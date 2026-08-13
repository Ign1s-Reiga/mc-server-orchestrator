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

    override suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgeOccupancy: Boolean,
    ): ForcedStopOutcome {
        recorded += definition.metadata.name.value
        throw ForcedTerminationUnavailable("`${definition.metadata.name.value}` has no workload in this test")
    }
}

/** A forced stop that succeeds, reporting whether the save was confirmed. */
internal class StoppingForce(
    private val saveConfirmed: Boolean,
    private val saveAttempted: Boolean = true,
    private val playersOnline: Int? = 0,
) : ForcedTermination {
    val recorded: MutableList<String> = mutableListOf()

    /** Whether the caller acknowledged occupancy, so a test can assert it was carried through. */
    val acknowledgements: MutableList<Boolean> = mutableListOf()

    override suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgeOccupancy: Boolean,
    ): ForcedStopOutcome {
        recorded += definition.metadata.name.value
        acknowledgements += acknowledgeOccupancy
        return ForcedStopOutcome(
            saveAttempted = saveAttempted,
            saveConfirmed = saveConfirmed,
            playersOnline = playersOnline,
            detail = if (saveConfirmed) "confirmed" else "not confirmed",
        )
    }
}

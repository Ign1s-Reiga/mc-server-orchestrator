package mcorch.api

import mcorch.core.termination.ForcedStopOutcome
import mcorch.core.termination.ForcedTermination
import mcorch.core.termination.ForcedTerminationRefused
import mcorch.core.termination.ForcedTerminationUnavailable
import mcorch.core.termination.OccupancyAcknowledgement
import mcorch.core.termination.TransferAttempt
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

    override suspend fun preflight(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ) {
        // The real seam's preflight returns quietly when there is no workload
        // rather than refusing, because a delete with nothing to stop is an
        // ordinary delete. Answering any other way here would make these tests
        // pass against a seam that behaves differently from the one shipped.
    }

    override suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
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
    private val transfer: TransferAttempt = TransferAttempt(attempted = false),
) : ForcedTermination {
    val recorded: MutableList<String> = mutableListOf()

    /** What the caller acknowledged, so a test can assert it was carried through rather than assumed. */
    val acknowledgements: MutableList<OccupancyAcknowledgement> = mutableListOf()

    /** The same, as seen by [preflight] — which runs before the tombstone and must see it too. */
    val preflighted: MutableList<OccupancyAcknowledgement> = mutableListOf()

    override suspend fun preflight(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ) {
        preflighted += acknowledgement
    }

    override suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ): ForcedStopOutcome {
        recorded += definition.metadata.name.value
        acknowledgements += acknowledgement
        return ForcedStopOutcome(
            transfer = transfer,
            saveAttempted = saveAttempted,
            saveConfirmed = saveConfirmed,
            playersOnline = playersOnline,
            detail = if (saveConfirmed) "confirmed" else "not confirmed",
        )
    }
}

/**
 * A seam whose [preflight] refuses.
 *
 * The shape that matters most: a refusal decided before anything is written. Any
 * test using this asserts the definition is still there and still editable
 * afterwards, because a refusal that has already tombstoned the server is the
 * `crictl`-only state this whole path exists to remove.
 */
internal class PreflightRefusingForce(
    private val because: String = "the seam refused before anything was written",
) : ForcedTermination {
    val recorded: MutableList<String> = mutableListOf()

    override suspend fun preflight(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ): Unit = throw ForcedTerminationRefused(because)

    override suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ): ForcedStopOutcome {
        recorded += definition.metadata.name.value
        error("stop must not be reached when preflight refused")
    }
}

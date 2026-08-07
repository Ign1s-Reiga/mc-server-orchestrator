package mcorch.schema

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The record a stored observation is not allowed to come back without.
 *
 * [StatusReconstruction] carries the whole argument; these are the properties a
 * change to it would have to keep. The one that is easiest to break by accident is
 * the state test: written as a comparison rather than an equality it would take in
 * [DrainState.DRAIN_FAILED], which is declared after [DrainState.STOPPING] and is
 * the state whose records overwhelmingly never dispatched anything.
 */
class StatusReconstructionTest {
    @Test
    fun `a stopping drain with no dispatch record is given one from its own transition`() {
        val drain = drainIn(DrainState.STOPPING)

        val reconstructed = StatusReconstruction.reconstruct(paper(drain))

        reconstructed.wasReconstructed shouldBe true
        val served =
            reconstructed.status
                .shouldBeInstanceOfPaper()
                .drain
                .shouldNotBeNull()
        // `enteredStateAt`, because a drain reaches STOPPING exactly when a stop
        // request returned cleanly. Not `observedAt`, which belongs to the pass that
        // looked rather than to the stop, and not a clock read here, which would
        // re-date the dispatch on every read.
        served.stopDispatchedAt shouldBe drain.enteredStateAt
        // Nothing else moved. A reconstruction that also re-dated a save request or
        // dropped a block would be repairing what it was not asked to.
        served shouldBe drain.copy(stopDispatchedAt = drain.enteredStateAt)
    }

    @Test
    fun `it reports the field it reconstructed and where the instant came from`() {
        val drain = drainIn(DrainState.STOPPING)

        val records = StatusReconstruction.reconstruct(paper(drain)).reconstructed

        records.size shouldBe 1
        val record = records.first()
        // A reconstruction nothing can report is a silent reinterpretation of stored
        // data, which is what a store is required to refuse. The value and its source
        // are both here so a report says what was inferred and from what.
        record.field shouldBe StatusReconstruction.STOP_DISPATCHED_FIELD
        record.takenFrom shouldBe StatusReconstruction.STOP_DISPATCHED_SOURCE
        record.value shouldBe drain.enteredStateAt
    }

    @Test
    fun `a proxy drain is reconstructed the same way`() {
        val drain = drainIn(DrainState.STOPPING)

        val reconstructed = StatusReconstruction.reconstruct(proxy(drain))

        reconstructed.wasReconstructed shouldBe true
        val served = reconstructed.status
        check(served is VelocityProxyStatus) { "expected a proxy status, found $served" }
        // The drain state machine is one machine and a proxy resumes from the same
        // record; a rule that only covered one kind would leave the other admitting.
        served.drain.shouldNotBeNull().stopDispatchedAt shouldBe drain.enteredStateAt
    }

    /**
     * Every state a stop has not been dispatched from.
     *
     * [DrainState.DEREGISTERED] is where a drain ordinarily waits *before* any stop,
     * so a stamp invented there reports a dispatch for the common case and suppresses
     * the re-registration that puts a parked drain's backend back into routing.
     * [DrainState.DRAIN_FAILED] is excluded on that same ground and not on a
     * structural one — a failed drain *does* have a way to a stop, since the
     * reconciler asks whether the container is already down on every pass whatever
     * the state — but it is where a drain parks after aborting at any step, so its
     * records overwhelmingly never dispatched. It is also declared *after*
     * `STOPPING`, so an ordinal test would sweep it in.
     */
    @Test
    fun `no other drain state is given a dispatch record`() {
        for (state in DrainState.entries - DrainState.STOPPING) {
            val status = paper(drainIn(state))

            val reconstructed = StatusReconstruction.reconstruct(status)

            withClue(state) {
                reconstructed.wasReconstructed shouldBe false
                reconstructed.reconstructed.shouldBeEmpty()
                reconstructed.status shouldBeSameInstanceAs status
                reconstructed.status
                    .shouldBeInstanceOfPaper()
                    .drain
                    .shouldNotBeNull()
                    .stopDispatchedAt
                    .shouldBeNull()
            }
        }
    }

    @Test
    fun `a dispatch record that is already there is never restamped`() {
        val dispatchedAt = T0.minusSeconds(3)
        val status = paper(drainIn(DrainState.STOPPING).copy(stopDispatchedAt = dispatchedAt))

        val reconstructed = StatusReconstruction.reconstruct(status)

        // The first instant stands. There is no un-dispatch, and the question every
        // reader asks is "may a SIGTERM already be in that container", not "when was
        // the most recent one sent".
        reconstructed.wasReconstructed shouldBe false
        reconstructed.status shouldBeSameInstanceAs status
    }

    @Test
    fun `an observation with no drain at all is handed back untouched`() {
        val status = paper(drain = null)

        val reconstructed = StatusReconstruction.reconstruct(status)

        // The ordinary path on a healthy fleet, and it allocates nothing: the whole
        // fleet is read every resync.
        reconstructed.wasReconstructed shouldBe false
        reconstructed.status shouldBeSameInstanceAs status
    }

    private fun drainIn(state: DrainState): DrainStatus =
        DrainStatus(
            state = state,
            startedAt = T0.minusSeconds(120),
            enteredStateAt = T0.minusSeconds(20),
            playersEvacuated = true,
            sealRequestedAt = T0.minusSeconds(115),
            worldSavedAt = T0.minusSeconds(30),
            deregisteredAt = T0.minusSeconds(10),
            transferStartedAt = T0.minusSeconds(100),
            transferAttempts = 4,
            destination = ResourceName.of("lobby-01").getOrThrow(),
        )

    private fun paper(drain: DrainStatus?): PaperServerStatus =
        PaperServerStatus(
            name = ResourceName.of("survival-01").getOrThrow(),
            observedGeneration = 1L,
            phase = ServerPhase.DRAINING,
            observedAt = T0,
            lastTransitionAt = T0.minusSeconds(30),
            drain = drain,
        )

    private fun proxy(drain: DrainStatus?): VelocityProxyStatus =
        VelocityProxyStatus(
            name = ResourceName.of("edge-01").getOrThrow(),
            observedGeneration = 1L,
            phase = ServerPhase.DRAINING,
            observedAt = T0,
            lastTransitionAt = T0.minusSeconds(30),
            drain = drain,
        )

    private fun ServerStatus.shouldBeInstanceOfPaper(): PaperServerStatus {
        check(this is PaperServerStatus) { "expected a Paper status, found $this" }
        return this
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-07-26T12:00:00Z")
    }
}

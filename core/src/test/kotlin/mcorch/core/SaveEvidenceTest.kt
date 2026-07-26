package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What makes a confirmed world save still worth acting on.
 *
 * The rule the whole drain rests on, tested directly rather than only through
 * the state machine, because two of its cases cannot be reached from outside:
 * they are the ones that keep a *later* mistake from being possible.
 *
 * The property is not "no player has been observed since the save". It is that
 * the confirmation is backed by an unbroken chain of positive zero-player
 * observations. A probe answers for the instant it runs; the intervals between
 * probes are the dangerous part, and nothing in a reading of `online=0` says
 * anything about the half hour before it.
 */
internal class SaveEvidenceTest {
    private val start = Instant.parse("2026-07-26T10:00:00Z")
    private val gap = 30.seconds

    private fun drain(
        confirmedAt: Instant?,
        state: DrainState = DrainState.DEREGISTERED,
    ) = DrainStatus(
        state = state,
        startedAt = start,
        enteredStateAt = start,
        playersEvacuated = true,
        worldSaved = confirmedAt != null,
        // Stamped together with the flag, which is what makes it the
        // confirmation instant. See `saveConfirmedAt`.
        saveRequestedAt = confirmedAt,
    )

    @Test
    fun `a confirmation is current while the container it describes is still the one running`() {
        val confirmed = start.plusSeconds(60)
        val containerStarted = start

        drain(confirmed).saveIsCurrent(containerStarted, confirmed, gap).shouldBeTrue()
        // Equality passes: a confirmation cannot describe an earlier process
        // than the one it was taken from, and a frozen clock must not make a
        // correct drain refuse to finish.
        drain(containerStarted).saveIsCurrent(containerStarted, confirmed, gap).shouldBeTrue()
        // A confirmation from before this process started describes a world
        // that is no longer in memory.
        drain(start.minusSeconds(1)).saveIsCurrent(containerStarted, confirmed, gap).shouldBeFalse()
        // No confirmation at all is not a confirmation.
        drain(null).saveIsCurrent(containerStarted, confirmed, gap).shouldBeFalse()
    }

    @Test
    fun `without a container start time only a fresh confirmation counts`() {
        val now = start.plusSeconds(600)

        // Unreachable through the state machine today — every runtime reports a
        // start time for a running container — and deliberately not left to the
        // permissive reading, which would trust a confirmation of any age from
        // a runtime that has told us nothing.
        drain(now.minusSeconds(5)).saveIsCurrent(null, now, gap).shouldBeTrue()
        drain(now.minusSeconds(120)).saveIsCurrent(null, now, gap).shouldBeFalse()

        // And not left to the conservative reading either: rejecting the
        // confirmation this pass just took makes the drain save, decline to
        // stop, and save again for ever.
        drain(now).saveIsCurrent(null, now, gap).shouldBeTrue()
    }

    @Test
    fun `a confirmation does not survive a window in which nothing was observed`() {
        val containerStarted = start
        val confirmed = start.plusSeconds(60)
        val watching = confirmed.plusSeconds(10)

        // The loop has been looking the whole time.
        drain(confirmed)
            .dropUnusableSaveEvidence(containerStarted, watching, watching.plusSeconds(5), gap)
            .worldSaved
            .shouldBeTrue()

        // Half an hour with nobody watching. A player can join, play and log
        // off inside it and leave no trace in anything the next pass can read —
        // the container did not restart, and the probe that follows reports
        // `online=0`, which is true and worth nothing.
        val afterOutage =
            drain(
                confirmed,
            ).dropUnusableSaveEvidence(containerStarted, watching, watching.plus(java.time.Duration.ofMinutes(30)), gap)
        afterOutage.worldSaved.shouldBeFalse()
        // The request stamp goes with the flag, or the re-entered save would
        // read as "requested once, never confirmed" and abort permanently on a
        // save that actually completed.
        afterOutage.saveRequestedAt shouldBe null

        // Nothing was ever observed, so there is no chain to speak of.
        drain(confirmed)
            .dropUnusableSaveEvidence(containerStarted, null, watching, gap)
            .worldSaved
            .shouldBeFalse()
    }

    @Test
    fun `forgetting evidence clears the claim that the server was empty as well`() {
        val confirmed = start.plusSeconds(60)

        val forgotten = drain(confirmed).forgetSaveEvidence()

        forgotten.worldSaved.shouldBeFalse()
        forgotten.saveRequestedAt shouldBe null
        // `playersEvacuated` is the claim that this server was confirmed empty,
        // and it is only ever forgotten because somebody was just seen on it.
        forgotten.playersEvacuated.shouldBeFalse()

        // A drain with nothing to forget is left exactly as it is, so an
        // unchanged status does not become a store write.
        val nothing = drain(null).copy(playersEvacuated = false)
        nothing.forgetSaveEvidence() shouldBe nothing
    }

    /**
     * `saveRequestedAt` means two different things and only `worldSaved` says
     * which, so the pass that cannot vouch for the world any more has to answer
     * differently for each. Both errors are silent:
     *
     * - keeping the timestamp of a *confirmed* save wedges a healthy drain,
     *   because the next `SAVING` reads it as a request that never came back and
     *   aborts permanently on a save that actually completed;
     * - dropping the timestamp of an *unconfirmed* one lifts the wedge that
     *   keeps a second `save-all flush` off a live server.
     */
    @Test
    fun `forgetting a confirmation keeps an unconfirmed request and discards a completed one`() {
        // Delivered, never confirmed: the wedge. It must survive, because this
        // pass observed nobody and so has no grounds to lift it.
        val requested = start.plusSeconds(60)
        val delivered = drain(null).copy(saveRequestedAt = requested)

        val afterFailedProbe = delivered.forgetSaveConfirmation()

        afterFailedProbe.saveRequestedAt shouldBe requested
        afterFailedProbe.worldSaved.shouldBeFalse()
        afterFailedProbe.playersEvacuated.shouldBeFalse()

        // Confirmed, then outlived: the request finished, so there is no
        // outstanding side effect to protect and the drain simply saves again.
        val confirmed = drain(start.plusSeconds(60))

        val afterExpiry = confirmed.forgetSaveConfirmation()

        afterExpiry.worldSaved.shouldBeFalse()
        afterExpiry.saveRequestedAt shouldBe null
        afterExpiry.playersEvacuated.shouldBeFalse()

        // Nothing to forget is left untouched, so an unchanged status does not
        // become a store write.
        val nothing = drain(null).copy(playersEvacuated = false)
        nothing.forgetSaveConfirmation() shouldBe nothing
    }

    @Test
    fun `an unconfirmed request is not evidence and is not cleared by the passage of time`() {
        val requested = start.plusSeconds(60)
        val delivered = drain(null).copy(saveRequestedAt = requested)

        // A save that went out and was never confirmed is a permanent abort,
        // never a re-send. Ageing it must not quietly turn it back into a
        // clean slate.
        val aged =
            delivered.dropUnusableSaveEvidence(
                start,
                requested,
                requested.plus(java.time.Duration.ofHours(1)),
                gap,
            )
        aged.saveRequestedAt shouldBe requested
        aged.worldSaved.shouldBeFalse()

        delivered.saveIsCurrent(start, requested, 5.minutes).shouldBeFalse()
    }
}

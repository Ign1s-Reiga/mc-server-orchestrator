package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import mcorch.core.paper.ProbeOutcome
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.PlayerOccupancy
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

    /**
     * A drain holding a confirmed save, or none.
     *
     * `saveRequestedAt` stays null throughout: a confirmed save has no
     * outstanding request. That used to be the same field as this one, which is
     * what these tests exist to stop happening again.
     */
    private fun drain(
        confirmedAt: Instant?,
        state: DrainState = DrainState.DEREGISTERED,
    ) = DrainStatus(
        state = state,
        startedAt = start,
        enteredStateAt = start,
        playersEvacuated = true,
        worldSavedAt = confirmedAt,
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
        // And no outstanding request is left behind for the re-entered save to
        // trip over. It does not have to be cleared here any more — a confirmed
        // save never had one — but the property is what matters: a drain that
        // has lost its confirmation must be able to save again, and one holding
        // a request stamp cannot.
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
     * The two voiders differ by exactly one field, and by nothing else.
     *
     * This replaces a test that pinned which fact `saveRequestedAt` was carrying
     * and how each voider had to branch on `worldSaved` to find out. There is no
     * branch left to test — that is the point of splitting the fields — so what
     * is worth pinning now is the *difference*, because it is the whole safety
     * argument:
     *
     * - a pass that observed **nobody** may drop the confirmation but must leave
     *   the outstanding request alone, or a delivered `save-all flush` is sent a
     *   second time to a live server;
     * - a pass that observed **a player** drops both, because whatever they did
     *   makes the old request worth nothing and the drain has to save again
     *   before it can reach a stop.
     *
     * Applied to both kinds of drain, so neither voider can quietly acquire a
     * dependency on which field happens to be set.
     */
    @Test
    fun `only a pass that saw a player may clear an outstanding save request`() {
        val requested = start.plusSeconds(60)
        val delivered = drain(null).copy(saveRequestedAt = requested)
        val confirmed = drain(start.plusSeconds(60))

        // Observed nobody: the request survives, whichever kind of drain it is.
        delivered.forgetSaveConfirmation().saveRequestedAt shouldBe requested
        delivered.forgetSaveConfirmation().worldSaved.shouldBeFalse()
        confirmed.forgetSaveConfirmation().saveRequestedAt shouldBe null
        confirmed.forgetSaveConfirmation().worldSaved.shouldBeFalse()

        // Observed a player: everything goes, whichever kind of drain it is.
        delivered.forgetSaveEvidence().saveRequestedAt shouldBe null
        delivered.forgetSaveEvidence().worldSaved.shouldBeFalse()
        confirmed.forgetSaveEvidence().saveRequestedAt shouldBe null
        confirmed.forgetSaveEvidence().worldSaved.shouldBeFalse()

        // The claim that the server was empty goes either way: it was only ever
        // true of the moment it was taken.
        delivered.forgetSaveConfirmation().playersEvacuated.shouldBeFalse()
        confirmed.forgetSaveEvidence().playersEvacuated.shouldBeFalse()

        // The one field they disagree about, stated as such.
        confirmed.forgetSaveConfirmation() shouldBe confirmed.forgetSaveEvidence()
        delivered.forgetSaveConfirmation() shouldNotBe delivered.forgetSaveEvidence()

        // Nothing to forget still compares equal, so an unchanged status does
        // not become a store write.
        val nothing = drain(null).copy(playersEvacuated = false)
        nothing.forgetSaveConfirmation() shouldBe nothing
        nothing.forgetSaveEvidence() shouldBe nothing
    }

    /**
     * The enforcement point for the half of the rule a probe *can* supply.
     *
     * "A positive player count voids the save confirmation" used to be held
     * together by each branch that read `probe.online` calling
     * [forgetSaveEvidence] itself, and by a KDoc sentence carrying a maintained
     * count of those branches. A change added a reader that voided nothing — the
     * re-probe after a confirmed save — and falsified the sentence without
     * reddening a single test, because nothing anywhere asserted the rule as a
     * rule.
     *
     * This is that assertion. It is deliberately about the function rather than
     * about a drain scenario: a scenario tests one caller, and the defect was a
     * caller nobody had thought of.
     */
    @Test
    fun `reading a positive player count hands back evidence that is already voided`() {
        val at = start.plusSeconds(90)
        val confirmed =
            drain(start.plusSeconds(60)).copy(
                saveRequestedAt = null,
                resaveForcedAt = start,
            )

        // Nobody on. The drain comes back untouched — voiding a confirmation
        // that a probe has just corroborated would make the drain save for ever.
        val empty = confirmed.readPlayers(ProbeOutcome.Joinable(online = 0, max = 20), at)
        empty.shouldBeInstanceOf<PlayerReading.Empty>().drain shouldBe confirmed
        empty.occupancy.shouldNotBeNull().observedAt shouldBe at
        empty.occupancy.shouldNotBeNull().online shouldBe 0

        // One player is enough, and the count comes back only alongside the
        // drain that fact implies. There is no route through this function that
        // yields a positive count and unvoided evidence.
        val occupied =
            confirmed
                .readPlayers(ProbeOutcome.Joinable(online = 1, max = 20), at)
                .shouldBeInstanceOf<PlayerReading.Occupied>()
        occupied.online shouldBe 1
        occupied.max shouldBe 20
        occupied.occupancy.observedAt shouldBe at
        occupied.drain shouldBe confirmed.forgetSaveEvidence()
        occupied.drain.worldSaved.shouldBeFalse()
        occupied.drain.saveRequestedAt shouldBe null
        occupied.drain.playersEvacuated.shouldBeFalse()
        // The re-save anchor goes too: a player has been on the server, so the
        // save that follows is this drain doing its job rather than circling.
        occupied.drain.resaveForcedAt shouldBe null

        // A delivered-but-unconfirmed request is dropped as well, because a
        // player having been on since is the one thing that makes it worthless.
        val delivered = drain(null).copy(saveRequestedAt = start.plusSeconds(30))
        delivered
            .readPlayers(ProbeOutcome.Joinable(online = 2, max = 20), at)
            .shouldBeInstanceOf<PlayerReading.Occupied>()
            .drain
            .saveRequestedAt shouldBe null

        // Silence decides nothing here. The three callers disagree about what an
        // unanswered probe means for the evidence, and each applies its own rule
        // at its own call site — so this hands back no drain at all.
        confirmed
            .readPlayers(ProbeOutcome.NotJoinable("no answer"), at)
            .shouldBeInstanceOf<PlayerReading.Unanswered>()
            .occupancy shouldBe null
    }

    /**
     * The clause a pass *entry* adopts from its reading — the primary half of round
     * 18's fix, and the half that had nowhere to be tested until it was a function.
     *
     * `advanceOnce` used to write the predicate inline. That made it untestable in
     * both directions at once: no scenario can distinguish this rule from a narrowed
     * version of it, because the adoption is what makes the confirmation unreachable
     * to every step in the pass and the record-level rule repairs whatever is written
     * down regardless. The twentieth audit proved it by mutating the predicate to
     * `is Occupied && !playersEvacuated` — a plausible misreading of the *Declined*
     * paragraph at the call site, restoring the critical, with the whole suite green.
     *
     * A rule no input can exercise has to be asserted on the rule. Which is this
     * test, and the reason [DrainStatus.adoptSaveClause] exists as a function:
     * `DrainWiringTest` can then pin that the call site applies it unconditionally,
     * which is a shape, and leave what it *does* to be checked here, which is
     * behaviour.
     */
    @Test
    fun `a pass entry adopts the confirmation clause of its reading and no more`() {
        val at = start.plusSeconds(90)
        val confirmed = drain(start.plusSeconds(60)).copy(saveRequestedAt = null, resaveForcedAt = start)

        // Somebody is on: the confirmation cannot describe the world any more, and
        // every state in this pass is run against a drain that does not claim one.
        val occupied = confirmed.readPlayers(ProbeOutcome.Joinable(online = 1, max = 20), at)
        val adopted = confirmed.adoptSaveClause(occupied)
        adopted.worldSaved.shouldBeFalse()

        // No narrowing. The count is the whole of the condition — not the count and
        // some property of the drain — because every field the drain is carrying is
        // one a step in this pass may be about to act on.
        confirmed
            .copy(playersEvacuated = false)
            .adoptSaveClause(occupied)
            .worldSaved
            .shouldBeFalse()
        confirmed
            .copy(saveRequestedAt = at)
            .adoptSaveClause(occupied)
            .worldSaved
            .shouldBeFalse()
        confirmed
            .copy(resaveForcedAt = null)
            .adoptSaveClause(occupied)
            .worldSaved
            .shouldBeFalse()
        confirmed
            .copy(state = DrainState.STOPPING)
            .adoptSaveClause(occupied)
            .worldSaved
            .shouldBeFalse()

        // And no widening: rung 1 of the ladder, never the rung the reading itself
        // carries. `Occupied.drain` has been through `forgetSaveEvidence`, and
        // adopting *that* would drop a parked proxied drain from `saveIsCurrent` past
        // `playersEvacuated` and resume it into a transfer instead of into `SAVING`.
        adopted shouldBe confirmed.unconfirmWorldSave()
        adopted.playersEvacuated.shouldBeTrue()
        adopted.resaveForcedAt shouldBe start

        // Nobody on, and silence. Neither is grounds to take anything away — voiding
        // on a corroborating zero reading would make every healthy drain save for
        // ever, and an unanswered probe establishes nothing at all. Identity, so an
        // unchanged drain does not become a store write.
        val empty = confirmed.readPlayers(ProbeOutcome.Joinable(online = 0, max = 20), at)
        confirmed.adoptSaveClause(empty) shouldBeSameInstanceAs confirmed
        val silent = confirmed.readPlayers(ProbeOutcome.NotJoinable("no answer"), at)
        confirmed.adoptSaveClause(silent) shouldBeSameInstanceAs confirmed
    }

    /**
     * The enforcement point for the same rule at the point a pass is **recorded**,
     * which is where round 18 lost it.
     *
     * [readPlayers] can only bind a step that reads a count. `holdSeal` reads none —
     * it asserts the seal, and at `DEREGISTERED` it runs before the zero-player
     * gate — so when the proxy's control endpoint stopped answering it parked a
     * drain still claiming a save taken before somebody joined on the backend's own
     * port. The rule that actually holds is about the pair of facts a pass writes
     * down together: `DrainController.advance` puts every progress through this on
     * the way out, so a producer that never looks at a player count is bound by it
     * anyway.
     *
     * Tested as a function for the reason the sibling above is: a scenario tests the
     * producer somebody thought of, and both defects were producers nobody had.
     */
    @Test
    fun `a recorded pass cannot carry a confirmed save beside a player count`() {
        val confirmed = drain(start.plusSeconds(60))
        val at = start.plusSeconds(90)
        val outcome = ReconcileOutcome.Retry("parked")

        fun progress(
            record: DrainStatus,
            online: Int?,
        ) = DrainProgress(
            drain = record,
            occupancy = online?.let { PlayerOccupancy(online = it, max = 20, observedAt = at) },
            outcome = outcome,
        )

        // The case the defect produced: an abort that recorded the player it saw
        // and the confirmation it was handed. The confirmation does not survive
        // the record.
        val contradicted = progress(confirmed, online = 1).dropSaveContradictedByPlayers()
        contradicted.drain.worldSaved.shouldBeFalse()
        // And only the confirmation goes. Taking `playersEvacuated` with it would
        // move a parked proxied drain down the resume ladder into a transfer.
        contradicted.drain.playersEvacuated.shouldBeTrue()
        contradicted.occupancy shouldBe progress(confirmed, online = 1).occupancy

        // No narrowing, stated rather than inherited. The assertion above would go
        // red under `|| drain.playersEvacuated` — but only because [drain] happens
        // to hand back a drain that claims it, which is coverage by fixture default
        // and lasts exactly as long as nobody edits the default. The condition is
        // the two facts *this pass* established, a count it observed and a
        // confirmation it is about to write down, and nothing else the drain is
        // carrying: every field below is one a step in the pass may be about to act
        // on, which is why none of them may excuse the record.
        listOf(
            confirmed.copy(playersEvacuated = false),
            confirmed.copy(saveRequestedAt = at),
            confirmed.copy(resaveForcedAt = start),
            confirmed.copy(state = DrainState.STOPPING),
        ).forEach { record ->
            progress(record, online = 1)
                .dropSaveContradictedByPlayers()
                .drain.worldSaved
                .shouldBeFalse()
        }

        // A zero reading corroborates the confirmation rather than contradicting
        // it, and voiding here would make every healthy drain save for ever.
        progress(confirmed, online = 0) shouldBe progress(confirmed, online = 0).dropSaveContradictedByPlayers()

        // Nothing established: a pass that recorded no occupancy has observed
        // nobody and has no grounds to take anything away. That is the same
        // distinction [readPlayers] refuses to collapse.
        progress(confirmed, online = null) shouldBe progress(confirmed, online = null).dropSaveContradictedByPlayers()

        // Nothing to take. Identity matters as well as equality: an unchanged
        // progress is how the caller decides whether to log a defect.
        val nothing = progress(drain(null), online = 3)
        nothing.dropSaveContradictedByPlayers() shouldBeSameInstanceAs nothing
    }

    /**
     * The three voiders are a ladder, and the rungs differ by exactly one field
     * each.
     *
     * Round 18's site needed the narrowest rung — the confirmation alone — and it
     * did not exist, so the site took *none* of them and the drain kept a save a
     * player had outlived. Naming the rung is what stops the next site making the
     * same choice, and `worldSavedAt = null` is now written in one place with the
     * other two composed from it.
     */
    @Test
    fun `voiding the confirmation alone leaves where a parked drain re-enters unchanged`() {
        val confirmed = drain(start.plusSeconds(60)).copy(saveRequestedAt = null, resaveForcedAt = start)

        val unconfirmed = confirmed.unconfirmWorldSave()
        unconfirmed.worldSaved.shouldBeFalse()
        // The two facts the resume ladder reads below `saveIsCurrent`, both intact:
        // a proxied drain parked here re-enters at `SAVING` and blocks on the
        // player, rather than resuming into a transfer.
        unconfirmed.playersEvacuated.shouldBeTrue()
        unconfirmed.resaveForcedAt shouldBe start
        unconfirmed.saveRequestedAt shouldBe null

        // Each rung is the one below it plus one field, which is the property that
        // keeps them from drifting apart.
        confirmed.forgetSaveConfirmation() shouldBe unconfirmed.copy(playersEvacuated = false)
        confirmed.forgetSaveEvidence() shouldBe
            confirmed.forgetSaveConfirmation().copy(saveRequestedAt = null, resaveForcedAt = null)

        // Nothing to void still compares equal, so an unchanged status does not
        // become a store write.
        val nothing = drain(null)
        nothing.unconfirmWorldSave() shouldBe nothing
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

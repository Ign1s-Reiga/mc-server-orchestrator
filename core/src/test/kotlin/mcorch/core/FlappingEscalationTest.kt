package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ConditionStatus
import mcorch.schema.DrainState
import mcorch.schema.ResourceName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The second escalation arm: a drain that fails more often than it recovers is
 * reported however short each individual fault was.
 *
 * ## What was wrong
 *
 * `NEEDS_ATTENTION` was derived from one question — *has a failure stood for
 * `drainAttentionAfter`* — and a flapping control endpoint never presents one.
 * `DrainController.blocked` deletes a retryable failure, so the pass after every
 * fault erases the record and its anchor with it, and the next fault starts over
 * at `attempts = 1`. Measured before this change: four hours of an endpoint
 * failing every other pass, twenty-five observations, `NEEDS_ATTENTION` false at
 * every one of them. Nothing in the fleet asked for a human and the drain never
 * finished.
 *
 * `DrainStatus.faultLedger` is the fact that survives the recovery, and the four
 * tests here are the four things worth pinning about it: that it fires where the
 * age arm cannot, that it stays quiet at the boundary the policy names, that the
 * age arm still fires on its own where it always did, and that a pass which
 * neither records a fault nor establishes health moves it in neither direction.
 *
 * ## Read the first two together
 *
 * They are the same scenario at two fault rates, either side of the crossover,
 * and neither means much alone: the first on its own would be satisfied by a flag
 * that fires on any drain that has ever failed, the second by a flag that never
 * fires at all.
 */
internal class FlappingEscalationTest {
    /**
     * **The reproduction, and the headline.** An endpoint that is broken more
     * often than it works is flagged.
     *
     * Two failing passes to one healthy one, which nets `+1` per cycle. Six cycles
     * reach the threshold.
     *
     * The assertion that makes this a test of the *new* arm rather than of the
     * flag in general is [DrainStatus.failingTooLong] being false at the moment
     * `NEEDS_ATTENTION` is true. There is no failure on this drain old enough to
     * raise anything — the block in each cycle deletes it and the next fault
     * restamps — so the old rule provably could not have produced this alert. A
     * mutation that breaks the age arm cannot redden this test, and that is
     * deliberate: `the age arm still fires on its own` below is where that lives.
     *
     * Two minutes per pass, chosen rather than inherited: a cycle spans six
     * minutes and the two consecutive faults span two, both comfortably inside the
     * fifteen-minute age threshold. Spread them wider and the age arm starts
     * firing, and this test would pass for the wrong reason.
     */
    @Test
    fun `an endpoint that fails more often than it recovers is flagged, where its age never could`() =
        coreTest {
            val harness = Harness()
            val name = drainingServer(harness)

            repeat(6) {
                // Two passes with the probe channel down: the drain cannot confirm
                // who is online, so it aborts retryably. +1 each.
                harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
                repeat(2) { step(harness, name) }
                // …and one with it back. Three players are on, so the drain reaches
                // its gate and blocks: nothing is wrong, and it pays one back.
                harness.node.clearFailures(NodeOperation.EXEC)
                step(harness, name)
            }

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()

            drain.faultLedger shouldBeGreaterThanOrEqualTo LEDGER
            status.attention().status shouldBe ConditionStatus.TRUE

            // The whole point: the old arm is false at this instant. Whatever failure
            // is on the record is younger than the threshold, and on the passes where
            // there is none it is false for want of a subject.
            drain.failingTooLong(harness.clock.instant(), 15.minutes).shouldBeFalse()
            drain.failingTooOften(LEDGER).shouldBeTrue()

            // The operator-facing half. A message that said "failing since" would be
            // quoting an anchor this case does not have.
            val message = status.attention().message
            message shouldContain "keeps failing and recovering"
            message shouldContain "still retrying"
            message shouldNotContain "cannot finish on its own"

            // And nothing was done to the container on the strength of any of it.
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * **The policy, stated as a test.** A fault present on exactly half the passes
     * is not flagged.
     *
     * This is the twelve-cycle reproduction that measured the defect, run against
     * the fix, and it is the assertion the design has to be honest about: with a
     * symmetric ledger the crossover sits *at* one half, so a perfectly
     * alternating fault is driftless and never arrives. That is the number an
     * operator can be told in a sentence — **it is reported when it is failing
     * more often than it is working** — and buying the perfectly-alternating case
     * would mean giving that sentence up.
     *
     * It is not as narrow as it looks. A real intermittent fault is not a metronome:
     * at a genuine one-half rate the ledger is a driftless walk against a floor at
     * zero, which wanders up as readily as down and has no ceiling, so it arrives
     * eventually. What never arrives is this — the exact alternation — and the
     * reason to pin it is that it is the one case somebody will later "fix" by
     * making the decrement smaller than the increment, which is the same as moving
     * the crossover somewhere no sentence describes.
     *
     * Four hours of clock, so the failure is not that the scenario was too short.
     */
    @Test
    fun `a fault present on exactly half the passes stays quiet, which is where the crossover is`() =
        coreTest {
            val harness = Harness()
            val name = drainingServer(harness)

            repeat(12) {
                harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
                step(harness, name, 10.minutes)
                harness.node.clearFailures(NodeOperation.EXEC)
                step(harness, name, 10.minutes)

                // Asserted every cycle rather than at the end: a ledger that climbed
                // to nine and came back to one would satisfy a final read and would
                // be a completely different behaviour.
                val drain =
                    harness
                        .status(name)
                        .shouldNotBeNull()
                        .drain
                        .shouldNotBeNull()
                drain.faultLedger shouldBeLessThanOrEqualTo 1
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .attention()
                    .status shouldBe ConditionStatus.FALSE
            }

            harness.node.stops.shouldBeEmpty()
        }

    /**
     * **The control.** A drain that fails and never recovers is still reported by
     * the age of its failure, on its own.
     *
     * The two arms are a disjunction so that adding the second cannot delay the
     * first, and this is that property under test. It is kept isolated from the
     * ledger arm by the pass count rather than by turning anything off: three
     * passes eight minutes apart put the ledger at three, half the threshold, while
     * the failure's own age passes fifteen minutes. So the flag here is the age arm
     * and nothing else — a mutation that deletes the ledger arm leaves this green,
     * and a mutation that deletes the age arm reddens this and not the first test.
     */
    @Test
    fun `a drain that keeps failing without recovering is still flagged by its age alone`() =
        coreTest {
            val harness = Harness()
            val name = drainingServer(harness)
            harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))

            repeat(3) { step(harness, name, 8.minutes) }

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()

            // The isolation, asserted rather than assumed.
            drain.faultLedger shouldBeLessThanOrEqualTo LEDGER - 1
            drain.failingTooOften(LEDGER).shouldBeFalse()

            drain.failingTooLong(harness.clock.instant(), 15.minutes).shouldBeTrue()
            status.attention().status shouldBe ConditionStatus.TRUE
            // …and the message is the age arm's, not the ledger arm's.
            status.attention().message shouldContain "cannot finish on its own"

            harness.node.stops.shouldBeEmpty()
        }

    /**
     * **"Did not fail" is not "was found healthy", and neither is "a failure is
     * standing".**
     *
     * Both halves of the funnel's predicate, on one scenario, because one scenario
     * reaches both. A stop is refused twice and then repaired; the container is
     * slow to exit, so the drain sits in `STOPPING` inside its grace period with
     * the earlier failure still on the record.
     *
     * Each of those passes:
     *
     * - **records no fault.** `awaitStopped` carries the standing failure forward
     *   untouched — a container that has not finished exiting is not a new fault —
     *   so the increment must ask whether *this pass wrote* a failure, not whether
     *   one is present. Asking the latter would climb the ledger once per poll on a
     *   drain that is behaving, and escalate on a fault that happened once and was
     *   fixed. The assertion that the failure's `attempts` does not move is what
     *   makes this discriminating: it states that a failure is standing *and* that
     *   this pass did not record it.
     * - **establishes nothing.** `workDone` is false — the branch is reached
     *   precisely because the previous stop has not taken — and there is no block.
     *   Were the rule "every non-failing pass pays one back", these passes would
     *   erase the evidence of the refused stop while learning nothing about it.
     *
     * ## Why not an unreported container
     *
     * The first draft used an `UNKNOWN` observation and a mutation proved it
     * worthless: `advance` answers that one *before* any step runs, so those passes
     * never reach the funnel and making its neutral branch decrement reddened
     * nothing. The property was true and the test was measuring a path that
     * bypasses the code implementing it. `STOPPING` inside the grace period is the
     * reachable, repeatable neutral pass — ten of them in twenty seconds against a
     * four-minute grace, so none of this is the overdue branch, which *does* record
     * a fault and should.
     */
    @Test
    fun `a pass that records no fault and establishes nothing moves the ledger in neither direction`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 0
            // The container is sent its stop and never exits, which is what keeps
            // `awaitStopped` returning without having achieved anything.
            harness.node.onStop = { it }
            harness.store.deleteDefinition(name)

            // Down to the stop, on an empty server. Every pass here does real work.
            repeat(6) { step(harness, name, 2.seconds) }
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.DEREGISTERED

            // Two refused stops: two faults, and a failure that will outlive them.
            harness.node.failAlways(NodeOperation.STOP, harness.node.unreachable(NodeOperation.STOP))
            repeat(2) { step(harness, name, 2.seconds) }
            harness.node.clearFailures(NodeOperation.STOP)

            // The stop goes out. That pass is real work and pays one back.
            step(harness, name, 2.seconds)
            val parked =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            parked.state shouldBe DrainState.STOPPING
            val earned = parked.faultLedger
            earned shouldBeGreaterThanOrEqualTo 1
            val standing = parked.failure.shouldNotBeNull()

            // Ten passes that record no fault and establish nothing.
            repeat(10) { step(harness, name, 2.seconds) }

            val after =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            after.state shouldBe DrainState.STOPPING
            after.faultLedger shouldBe earned
            // The premise, asserted rather than assumed: a failure was standing
            // throughout and not one of those passes recorded it.
            val carried = after.failure.shouldNotBeNull()
            carried.attempts shouldBe standing.attempts
            carried.occurredAt shouldBe standing.occurredAt
        }

    /** A deleted, populated server: the drain that cannot finish while people play. */
    private suspend fun drainingServer(harness: Harness): ResourceName {
        val definition = paperDefinition()
        val name = definition.metadata.name
        harness.declare(definition)
        harness.settle(name)
        harness.node.online = 3
        harness.store.deleteDefinition(name)
        // requested -> sealed -> blocked on players. Nothing is wrong yet, and the
        // baseline is asserted so that every `TRUE` below is a transition rather
        // than a flag that was already up before the scenario started.
        repeat(3) { harness.pass(name) }
        harness
            .status(name)
            .shouldNotBeNull()
            .attention()
            .status shouldBe ConditionStatus.FALSE
        return name
    }

    private suspend fun step(
        harness: Harness,
        name: ResourceName,
        advance: kotlin.time.Duration = 2.minutes,
    ) {
        harness.pass(name)
        harness.clock.advance(advance)
    }

    private companion object {
        /** `ReconcilerConfig.drainAttentionLedger`'s shipped value. */
        const val LEDGER: Int = 6
    }
}

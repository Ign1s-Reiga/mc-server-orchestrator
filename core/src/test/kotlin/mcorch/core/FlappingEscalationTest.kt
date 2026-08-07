package mcorch.core

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ConditionStatus
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.ResourceName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

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
            drain.failingTooOften(harness.clock.instant(), 15.minutes, LEDGER).shouldBeTrue()

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
            drain.failingTooOften(harness.clock.instant(), 15.minutes, LEDGER).shouldBeFalse()

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

    /**
     * **The forty-second audit's finding: a count is not a duration.**
     *
     * Six consecutive aborts each return `Retry`, so the real loop requeues them one,
     * two, four, eight and sixteen seconds apart — the sixth lands around half a
     * minute in. With the count as the only test, one containerd blip or one proxy
     * restart raised the operator's single alert flag, and did not clear it for six
     * healthy passes. That is the alarm fatigue this whole arm exists to avoid,
     * produced by the arm.
     *
     * The gate is the same fifteen minutes the age arm uses, measured from
     * `faultLedgerSince`. So the count is reached here and the flag stays down.
     *
     * **The cadence is the scenario, not a detail.** These are the intervals
     * `Backoff` actually schedules for consecutive `Retry` outcomes; the other tests
     * in this file advance minutes per pass and cannot see this at all, which is why
     * the defect survived them. The last two assertions are what stop the test
     * passing for the wrong reason: the count really did reach the threshold, and it
     * is the age half that is holding the flag down.
     */
    @Test
    fun `a burst of faults inside the backoff's first seconds reaches the count and is not flagged`() =
        coreTest {
            val harness = Harness()
            val name = drainingServer(harness)
            harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))

            // Derived from `Backoff` rather than written down. A hard-coded
            // 1s/2s/4s/8s/16s was what round 42's defect was made of — a rule
            // counting passes and a test choosing its own spacing — so the scenario
            // asks the scheduler the loop asks. Jitter off, because jitter only ever
            // shortens a delay and this test wants the *slowest* schedule the real
            // loop can produce: anything faster reaches the count sooner and makes
            // the assertion easier, so the number chosen here is the conservative one
            // in the direction that matters.
            val backoff = Backoff(jitter = 0.0)
            (1..5).forEach { attempt -> step(harness, name, backoff.delayFor(attempt)) }
            step(harness, name, 1.seconds)

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()

            // Half a minute of wall clock, and the whole budget spent.
            drain.faultLedger shouldBeGreaterThanOrEqualTo LEDGER
            status.attention().status shouldBe ConditionStatus.FALSE

            // Neither arm, and for different reasons: the count is there and the age
            // is not; the failure is younger than the age arm's threshold.
            drain.failingTooOften(harness.clock.instant(), 15.minutes, LEDGER).shouldBeFalse()
            drain.failingTooLong(harness.clock.instant(), 15.minutes).shouldBeFalse()
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * **Why the age is the ledger's and not the drain's**, which is the whole
     * justification for `faultLedgerSince` existing rather than reusing
     * `DrainStatus.startedAt`.
     *
     * `startedAt` is set once and never restamped, so it looks like a free anchor —
     * and it answers the wrong question. A drain that has been waiting healthily for
     * four hours is four hours old with nothing wrong with it, so a gate measured
     * from it is already open, and the blip above escalates after all. The gate has
     * to measure how long *this run of faults* has been going, which is a fact
     * nothing on the record answered before.
     *
     * Same burst as the test above, on a drain that has been running for four hours.
     * The two instants are asserted apart so the scenario cannot quietly become the
     * one above it.
     */
    @Test
    fun `a burst late in a long healthy drain is aged from the ledger, not from the drain`() =
        coreTest {
            val harness = Harness()
            val name = drainingServer(harness)

            // Four hours of healthy blocking on a busy evening. Nothing is wrong and
            // the ledger never leaves zero.
            repeat(8) { step(harness, name, 30.minutes) }
            val healthy =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            healthy.faultLedger shouldBe 0
            healthy.faultLedgerSince.shouldBeNull()

            harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
            val backoff = Backoff(jitter = 0.0)
            (1..5).forEach { attempt -> step(harness, name, backoff.delayFor(attempt)) }
            step(harness, name, 1.seconds)

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()

            drain.faultLedger shouldBeGreaterThanOrEqualTo LEDGER
            // The premise, and it is what tells the two anchors apart: the drain is
            // hours old and its ledger is seconds old.
            JavaDuration
                .between(drain.startedAt, harness.clock.instant())
                .toKotlinDuration() shouldBeGreaterThan 15.minutes
            JavaDuration
                .between(drain.faultLedgerSince.shouldNotBeNull(), harness.clock.instant())
                .toKotlinDuration() shouldBeLessThan 15.minutes

            status.attention().status shouldBe ConditionStatus.FALSE
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * A stored row that carries a count with no instant is **not** escalated.
     *
     * Unreachable through the loop — the funnel keeps the pair consistent and
     * re-dates a row that arrives half-written — so it is asserted directly on the
     * rule, which is the only place the case exists. It is the reading
     * `LegacyDrainRowTest` declares for a document written before the instant: the
     * arm stays quiet, because firing would report on evidence whose age nothing
     * established.
     *
     * The second half is the control. Without it a rule that never escalated at all
     * would satisfy the first.
     */
    @Test
    fun `a ledger with no instant is not escalated, and the same ledger with an old one is`() =
        coreTest {
            val clock = MutableClock()
            val at = clock.instant()
            val halfWritten =
                DrainStatus(
                    state = DrainState.DRAIN_FAILED,
                    startedAt = at.minusSeconds(60 * 60),
                    enteredStateAt = at,
                    faultLedger = LEDGER + 3,
                )

            halfWritten.failingTooOften(at, 15.minutes, LEDGER).shouldBeFalse()
            halfWritten.escalated(at, 15.minutes, LEDGER).shouldBeFalse()

            halfWritten
                .copy(faultLedgerSince = at.minusSeconds(16 * 60))
                .failingTooOften(at, 15.minutes, LEDGER)
                .shouldBeTrue()
        }

    /**
     * **A runtime that stops reporting a container is a fault, and it is counted.**
     *
     * `advance` answers a `SANDBOX_ONLY` observation with an abort before any step
     * runs — the one early return that records a fault rather than establishing
     * nothing. Left outside the funnel it scored zero while the healthy pass after it
     * still scored −1, so the single class of fault this arm most needs to see could
     * never reach the threshold, and mixed with a genuine endpoint fault it paid the
     * endpoint's evidence down.
     *
     * The control is the second half: the same drain, the same passes, with the
     * runtime reporting normally. Without it this asserts only that *something*
     * moved the ledger.
     */
    @Test
    fun `a runtime that stops reporting a container is counted like any other fault`() =
        coreTest {
            val harness = Harness()
            val name = drainingServer(harness)

            val reported = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = reported.copy(state = WorkloadState.SANDBOX_ONLY)
            repeat(3) { step(harness, name, 1.minutes) }

            val unreported =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            unreported.faultLedger shouldBe 3
            unreported.faultLedgerSince.shouldNotBeNull()

            // The control: put the container back and the same three passes pay it
            // down again, so the number above is this abort and not the scenario.
            harness.node.workload = reported
            repeat(3) { step(harness, name, 1.minutes) }
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .faultLedger shouldBe 0
        }

    /**
     * The pair's invariant, over a whole scenario rather than at one instant.
     *
     * `faultLedgerSince` is non-null exactly while `faultLedger` is positive. It is
     * maintained in one expression at one funnel precisely so that no writer has to
     * remember it — and this is what says the funnel really is the only writer. A
     * `since` left behind after the count returns to zero would date a later,
     * unrelated run of faults from an instant that has nothing to do with them, and
     * the arm would fire on its first pass.
     */
    @Test
    fun `the ledger and its instant are non-null together at every step of a flapping drain`() =
        coreTest {
            val harness = Harness()
            val name = drainingServer(harness)

            repeat(6) {
                harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
                step(harness, name, 30.seconds)
                assertPaired(harness, name)
                harness.node.clearFailures(NodeOperation.EXEC)
                repeat(2) {
                    step(harness, name, 30.seconds)
                    assertPaired(harness, name)
                }
            }

            // The scenario has to have exercised both sides of the invariant, or it
            // is asserting one branch twelve times.
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .faultLedger shouldBe 0
        }

    private suspend fun assertPaired(
        harness: Harness,
        name: ResourceName,
    ) {
        val drain =
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
        withClue("ledger=${drain.faultLedger} since=${drain.faultLedgerSince}") {
            (drain.faultLedgerSince != null) shouldBe (drain.faultLedger > 0)
        }
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

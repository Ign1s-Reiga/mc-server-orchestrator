package mcorch.core

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.FailureClass
import mcorch.schema.RconSpec
import mcorch.schema.ServerPhase
import mcorch.schema.StorageSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * The escalation, on failures that are not a drain's.
 *
 * `NEEDS_ATTENTION` used to be derived from `DrainStatus.escalated()` alone,
 * which made it a drain flag by accident rather than by decision — the rule
 * underneath it, `escalates()`, has never had anything drain-specific in it. The
 * case that forced the question is `Reconciler.forbiddenTransition`: a
 * **permanent** failure, no drain at all, and a phase of `RUNNING`. The loop
 * stops observing that server entirely until an operator reverts the edit, and
 * every surface showed it as an ordinary running server.
 *
 * The assertions here come in pairs on purpose. Every test that asserts the flag
 * is raised also asserts **no stop was issued**, because the flag is a report and
 * must never become a reason to act on a container: raising it on more servers is
 * only safe while nothing branches on it.
 */
internal class AttentionTest {
    /**
     * The decisive case. `RUNNING`, nothing draining, and the loop has stopped.
     *
     * A fleet table reading the badge alone sees a healthy server. It is not one:
     * `isBlockedByPermanentFailure` returns before the next pass observes
     * anything, so there will be no further probe, no occupancy and no
     * observation until somebody edits the definition back.
     */
    @Test
    fun `a server the loop has stopped acting on is flagged even though its badge says running`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The refused edit: `storage.mode` persistent → ephemeral on a
            // container that was created holding world data.
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val status = harness.status(name).shouldNotBeNull()
            status.phase shouldBe ServerPhase.RUNNING
            status.drain shouldBe null
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            status.attention().status shouldBe ConditionStatus.TRUE

            val message = status.attention().message
            // The two facts a reader gets wrong from the badge, in the order they
            // need them: the loop has stopped, and the container has not been.
            message shouldContain "the loop has stopped acting on this server"
            message shouldContain "The container is not being stopped by the orchestrator"
            // It must not be worded as a drain problem — there is no drain.
            message shouldNotContain "the drain"
            // Nothing here establishes that anybody can still connect, and the
            // status has been frozen by the permanent failure, so an over-claim
            // would stand for as long as the server does.
            message shouldNotContain "joinable"

            // The flag reports. It did not authorise anything.
            harness.node.stops.shouldBeEmpty()
            harness.node.saves.shouldBeEmpty()
        }

    /**
     * The idempotency half, and it is about the *condition* rather than the
     * container.
     *
     * `StatusCondition.lastTransitionAt` is what an alert measures an age from, so
     * a pass that re-derives the same flag with a fresh timestamp is not a no-op
     * even though the status reads the same to a human — an alert on "flagged for
     * more than ten minutes" would never fire against a flag that is reborn every
     * pass. Both paths are checked: the permanent one, where the gate means no
     * status is written at all, and the retryable one, where a status *is* written
     * every pass and the condition has to survive being derived again.
     */
    @Test
    fun `repeated passes over a flagged server accumulate nothing and do not restamp the flag`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.pass(name)

            val first = harness.status(name).shouldNotBeNull()
            val writes = harness.store.statusWrites
            val creates = harness.node.creates.size

            harness.clock.advance(30.seconds)
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val second = harness.status(name).shouldNotBeNull()
            second.attention().status shouldBe ConditionStatus.TRUE
            second.attention().lastTransitionAt shouldBe first.attention().lastTransitionAt
            second.observedAt shouldBe first.observedAt
            harness.store.statusWrites shouldBe writes
            harness.node.creates shouldHaveSize creates
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * A transient failure requeues and stays quiet until the threshold, then
     * escalates without changing what the loop does.
     *
     * This is the whole answer to "widening this floods the dashboard": a
     * retryable failure is never escalated on the pass that records it, however
     * many passes have recorded it, and the requeue is unaffected either way.
     */
    @Test
    fun `a transient failure requeues quietly and is only flagged once it has lasted`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.node.failAlways(NodeOperation.OBSERVE, harness.node.unreachable(NodeOperation.OBSERVE))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()

            val early = harness.status(name).shouldNotBeNull()
            early.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
            early.attention().status shouldBe ConditionStatus.FALSE

            // Still not flagged a minute short of the threshold, and still
            // requeueing rather than failing.
            harness.clock.advance(14.minutes)
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.FALSE

            harness.clock.advance(2.minutes)
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()

            val late = harness.status(name).shouldNotBeNull()
            late.attention().status shouldBe ConditionStatus.TRUE
            // Wording for a loop that has *not* given up. Telling an operator it
            // has is how they go and act on the container by hand.
            late.attention().message shouldContain "still retrying"
            late.attention().message shouldContain "The container is not being stopped by the orchestrator"
            // The class is untouched, so the requeue is untouched. The escalation
            // changes the report and nothing else (`failure-modes.md` item 7).
            late.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
            harness.node.stops.shouldBeEmpty()

            // Derived again, on a pass that does write a status: the attempt count
            // moves, the flag does not.
            harness.clock.advance(1.minutes)
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
            val again = harness.status(name).shouldNotBeNull()
            again.failure.shouldNotBeNull().attempts shouldBe late.failure.shouldNotBeNull().attempts + 1
            again.attention().lastTransitionAt shouldBe late.attention().lastTransitionAt

            // And it clears itself. A pass that gets somewhere records no failure,
            // so the flag goes with it rather than being a second thing to reset.
            harness.node.stopFailing(NodeOperation.OBSERVE)
            harness.settle(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.FALSE
        }

    /**
     * A permanent failure is flagged on the pass that records it, because for a
     * permanent failure a timer cannot work at all: the gate returns before the
     * next pass writes a status, so a threshold not crossed by then is never
     * re-evaluated.
     */
    @Test
    fun `a permanent node failure is flagged at once and the loop stops calling the node`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.node.failAlways(NodeOperation.OBSERVE, harness.node.rejected(NodeOperation.OBSERVE))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val status = harness.status(name).shouldNotBeNull()
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            status.attention().status shouldBe ConditionStatus.TRUE
            status.attention().message shouldContain "Nothing further will be attempted"

            // The gate is what makes the flag load-bearing: after this the loop
            // does not touch the node again, so nothing else will ever notice.
            val calls = harness.node.calls.size
            harness.clock.advance(1.hours)
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
            harness.node.calls shouldHaveSize calls
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * A drain that is *waiting* beside a node the loop cannot reach.
     *
     * Two separate things are true and the report has to keep them apart. The
     * server needs a human — nothing is going to move until the node comes back —
     * and the drain itself has nothing wrong with it. Wording the `DRAINING`
     * condition from the widened flag would have it assert the drain is "failing
     * since … and not recovering on its own" about a drain that is doing exactly
     * what it should.
     *
     * It is also the case that ends the documented "`DRAIN_BLOCKED` and
     * `NEEDS_ATTENTION` are never both true". They answer different questions —
     * *is the drain stuck* and *must somebody act* — and here the honest answers
     * are no and yes.
     */
    @Test
    fun `a blocked drain beside an unreachable node is flagged without being called failing`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 3
            harness.store.deleteDefinition(name)
            // requested -> sealed -> blocked
            repeat(3) { harness.pass(name) }
            harness
                .status(name)
                .shouldNotBeNull()
                .condition(ConditionType.DRAIN_BLOCKED)
                .status shouldBe ConditionStatus.TRUE

            harness.node.failAlways(NodeOperation.OBSERVE, harness.node.rejected(NodeOperation.OBSERVE))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val status = harness.status(name).shouldNotBeNull()
            // The drain record is carried forward untouched, block and all.
            status.drain
                .shouldNotBeNull()
                .blocked
                .shouldNotBeNull()
            status.drain?.failure shouldBe null
            status.condition(ConditionType.DRAIN_BLOCKED).status shouldBe ConditionStatus.TRUE

            status.attention().status shouldBe ConditionStatus.TRUE
            val attention = status.attention().message
            attention shouldContain "the loop cannot complete a pass"
            attention shouldContain "its drain is not advancing"
            // The drain has not failed and must not be described as having failed.
            attention shouldNotContain "the drain cannot finish on its own"
            // Every arm has to say this, and this is the arm where a reader has
            // the strongest positive reason to believe a stop is coming: they have
            // just been told a drain exists and is not advancing.
            attention shouldContain "not being stopped by the orchestrator"

            // The condition about the drain still describes the drain.
            status.condition(ConditionType.DRAINING).message shouldNotContain "not recovering on its own"

            // Three people are playing on a server somebody asked to delete, and
            // the node is unreachable. Nothing about that may produce a stop.
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * The anchor is the failure's own first occurrence.
     *
     * A drain that has been blocked for four hours is the normal state of a
     * popular server somebody asked to delete. Anchoring the pass arm on
     * `drain.startedAt` would make the first *retryable* hiccup on such a server
     * escalate instantly — every node restart, on every busy server, immediately.
     * That is the alarm fatigue the threshold exists to prevent, arriving by the
     * back door.
     */
    @Test
    fun `a fresh transient failure during a long block is not flagged by the drain's age`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 3
            harness.store.deleteDefinition(name)
            repeat(3) { harness.pass(name) }

            harness.clock.advance(4.hours)
            harness.pass(name)
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // The premise, asserted rather than assumed: the drain is far older
            // than any threshold this test could be measuring against.
            JavaDuration
                .between(drain.startedAt, harness.clock.instant())
                .toKotlinDuration()
                .let { it > 1.hours }
                .shouldBeTrue()

            harness.node.failAlways(NodeOperation.OBSERVE, harness.node.unreachable(NodeOperation.OBSERVE))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()

            val status = harness.status(name).shouldNotBeNull()
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
            status.attention().status shouldBe ConditionStatus.FALSE
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * The identity `Reconciler.drain` writes, asserted through what depends on it.
     *
     * `status.failure` is assigned `progress.drain.failure` — the same value, not
     * a derived one — and three consumers discriminate on that equality. Assign a
     * `copy(...)` there and this drain is described by the *pass* arm, whose
     * sentence says the loop cannot complete a pass, about a loop that completed
     * one perfectly well and found the drain could not finish.
     */
    @Test
    fun `an aborted drain is worded as the drain's own failure, not as a failed pass`() =
        coreTest {
            val harness = Harness()
            // No RCON: nothing can ever report that a save completed, so the drain
            // aborts permanently and deliberately leaves the container running.
            val definition = paperDefinition(rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)
            repeat(6) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            val drainFailure =
                status.drain
                    .shouldNotBeNull()
                    .failure
                    .shouldNotBeNull()
            drainFailure.failureClass shouldBe FailureClass.PERMANENT
            // One event, written to two fields. Everything below depends on it.
            status.failure shouldBe drainFailure

            status.attention().status shouldBe ConditionStatus.TRUE
            val attention = status.attention().message
            attention shouldContain "the drain cannot finish on its own"
            attention shouldNotContain "the loop cannot complete a pass"
            attention shouldNotContain "the loop has stopped acting on this server"

            harness.node.stops.shouldBeEmpty()
        }

    /**
     * The one cell of the class matrix where ranking by arm is wrong.
     *
     * A `REPLACEMENT` drain failing retryably past the threshold, then a permanent
     * node failure on the next pass. Both arms escalate. Ranking by arm renders the
     * drain's sentence — *"The loop keeps retrying and the container keeps
     * running"* — and on the very next pass
     * `Reconciler.Pass.isBlockedByPermanentFailure` returns before anything is
     * observed and gates this server off for good.
     *
     * The pager quotes this condition rather than `:api`'s `detail()`, so that
     * sentence is what an operator acts on, and it tells them to wait for a loop
     * that has stopped. The remaining three cells still rank by arm.
     */
    @Test
    fun `a retryable drain failure does not outrank a permanent pass failure`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // A definition edit that needs the container recreated: the drain that
            // applies it is a REPLACEMENT, not a delete.
            harness.store.putDefinition(paperDefinition(maxPlayers = 40))
            // The exec channel is down, so the drain cannot confirm zero players
            // and aborts *retryably* — the container is left running, correctly.
            harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
            repeat(3) { harness.pass(name) }
            harness.clock.advance(20.minutes)
            harness.pass(name)

            val stuck = harness.status(name).shouldNotBeNull()
            stuck.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.RETRYABLE
            // The premise: on its own, the drain arm is escalated and says so.
            stuck.attention().status shouldBe ConditionStatus.TRUE
            stuck.attention().message shouldContain "The loop keeps retrying"

            // Now the node refuses permanently. The observation never happens, so
            // the drain record is carried forward untouched beside a permanent
            // failure recorded on the pass.
            harness.node.failAlways(NodeOperation.OBSERVE, harness.node.rejected(NodeOperation.OBSERVE))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val status = harness.status(name).shouldNotBeNull()
            status.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.RETRYABLE
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            status.attention().status shouldBe ConditionStatus.TRUE

            val attention = status.attention().message
            // The sentence that would send somebody away to wait.
            attention shouldNotContain "The loop keeps retrying"
            attention shouldContain "Nothing further will be attempted"
            attention shouldContain "not being stopped by the orchestrator"

            // The next pass is the one that proves the wording matters: the gate
            // returns before anything is observed, so this status is the last one
            // this server will ever have.
            val calls = harness.node.calls.size
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
            harness.node.calls shouldHaveSize calls
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * The drain arm's own anchor, which is the same defect one level down.
     *
     * `DrainStatus.startedAt` never resets, and a drain sitting blocked for a whole
     * play session is the protocol working. So a drain arm anchored on it reports
     * the first retryable hiccup after four hours of healthy waiting as *"unable to
     * finish for 240 minutes"*, on the pass that records it — the same alarm
     * fatigue the pass arm was already corrected for.
     *
     * The threshold still fires. It fires from when this *failure* started.
     */
    @Test
    fun `a drain failure after a long healthy block is not flagged by the drain's age`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 3
            harness.store.deleteDefinition(name)
            // requested -> sealed -> blocked on players. Nothing is wrong.
            repeat(3) { harness.pass(name) }
            harness.clock.advance(4.hours)
            harness.pass(name)

            val blocked = harness.status(name).shouldNotBeNull()
            blocked.drain
                .shouldNotBeNull()
                .blocked
                .shouldNotBeNull()
            blocked.drain?.failure shouldBe null
            blocked.attention().status shouldBe ConditionStatus.FALSE
            // The premise, asserted rather than assumed.
            JavaDuration
                .between(blocked.drain.shouldNotBeNull().startedAt, harness.clock.instant())
                .toKotlinDuration()
                .let { it > 1.hours }
                .shouldBeTrue()

            // Now something actually goes wrong, retryably: the probe channel dies,
            // so zero players cannot be confirmed and the drain aborts.
            harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()

            val fresh = harness.status(name).shouldNotBeNull()
            fresh.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.RETRYABLE
            fresh.attention().status shouldBe ConditionStatus.FALSE
            fresh.condition(ConditionType.DRAINING).message shouldNotContain "not recovering on its own"

            // And it still escalates, from its own first occurrence.
            harness.clock.advance(16.minutes)
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.TRUE

            harness.node.stops.shouldBeEmpty()
        }
}

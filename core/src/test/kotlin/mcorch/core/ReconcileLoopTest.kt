package mcorch.core

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import mcorch.schema.DrainState
import mcorch.schema.ServerPhase
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The driver: finding work, and coming back to it.
 *
 * Virtual time throughout, and never `advanceUntilIdle` — the loop has a
 * resync ticker that by design never stops, so "idle" is not a state it
 * reaches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReconcileLoopTest {
    private fun loopFor(
        harness: Harness,
        backoff: Backoff = Backoff(base = 10.seconds, factor = 2.0, max = 1.minutes, jitter = 0.0),
    ) = ReconcileLoop(
        store = harness.store,
        reconciler = harness.reconciler,
        config =
            ReconcileLoopConfig(
                resyncPeriod = 5.minutes,
                changePollInterval = 200.milliseconds,
                stepInterval = 100.milliseconds,
                concurrency = 2,
            ),
        backoff = backoff,
    )

    @Test
    fun `the loop finds a declared server and brings it up without being told`() =
        runTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)

            val job = launch { loopFor(harness).run() }
            advanceTimeBy(2.seconds)

            harness.node.creates shouldHaveSize 1
            harness.node.starts shouldHaveSize 1
            harness.status(name).shouldNotBeNull().phase shouldBe ServerPhase.RUNNING
            harness
                .status(name)
                .shouldNotBeNull()
                .ready
                .shouldBeTrue()
            job.cancel()
        }

    @Test
    fun `a definition written after the loop started is picked up from the change feed`() =
        runTest {
            val harness = Harness()
            val job = launch { loopFor(harness).run() }
            advanceTimeBy(1.seconds)
            harness.node.creates shouldHaveSize 0

            val definition = paperDefinition()
            harness.declare(definition)
            // Far sooner than the five-minute resync.
            advanceTimeBy(2.seconds)

            harness.node.creates shouldHaveSize 1
            job.cancel()
        }

    @Test
    fun `a retryable failure is retried on the backoff, not immediately`() =
        runTest {
            val harness = Harness()
            val definition = paperDefinition()
            harness.declare(definition)
            harness.node.failAlways(NodeOperation.IMAGE, harness.node.unreachable(NodeOperation.IMAGE))

            val job = launch { loopFor(harness).run() }
            advanceTimeBy(1.seconds)
            harness.node.calls.count { it == NodeOperation.IMAGE } shouldBe 1

            advanceTimeBy(5.seconds)
            harness.node.calls.count { it == NodeOperation.IMAGE } shouldBe 1

            advanceTimeBy(6.seconds)
            harness.node.calls.count { it == NodeOperation.IMAGE } shouldBe 2

            // ... and the second wait is longer than the first.
            advanceTimeBy(11.seconds)
            harness.node.calls.count { it == NodeOperation.IMAGE } shouldBe 2
            advanceTimeBy(10.seconds)
            harness.node.calls.count { it == NodeOperation.IMAGE } shouldBe 3
            job.cancel()
        }

    @Test
    fun `a permanently failed server is not retried between resyncs`() =
        runTest {
            val harness = Harness()
            val definition = paperDefinition()
            harness.declare(definition)
            harness.node.failAlways(NodeOperation.CREATE, harness.node.rejected(NodeOperation.CREATE))

            val job = launch { loopFor(harness).run() }
            advanceTimeBy(2.seconds)
            val attempts = harness.node.calls.count { it == NodeOperation.CREATE }
            attempts shouldBe 1

            advanceTimeBy(2.minutes)

            harness.node.calls.count { it == NodeOperation.CREATE } shouldBe attempts
            job.cancel()
        }

    @Test
    fun `a drain left in flight by a restart is resumed rather than restarted`() =
        runTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)
            // Get the drain as far as SAVING, then "restart" by handing the
            // stored state to a fresh loop.
            repeat(5) { harness.pass(name) }
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.SAVING
            harness.node.saves shouldHaveSize 0

            val job = launch { loopFor(harness).run() }
            advanceTimeBy(3.seconds)

            // It carried on from SAVING: one save, one stop, and the definition
            // is gone.
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
            job.cancel()
        }
}

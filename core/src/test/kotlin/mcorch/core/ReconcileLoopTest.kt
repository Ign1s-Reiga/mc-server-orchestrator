package mcorch.core

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mcorch.core.paper.PaperCommands
import mcorch.schema.DrainState
import mcorch.schema.PlacementSpec
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
    fun `an exception a node did not translate does not stop the loop serving other servers`() =
        runTest {
            val other = FakeNode(name = nodeName("node-b"))
            val harness = Harness(additionalNodes = listOf(other))
            val broken = paperDefinition(name = "survival-01")
            val healthy =
                paperDefinition(
                    name = "lobby-01",
                    hostPort = 30002,
                    placement = PlacementSpec(node = nodeName("node-b")),
                )
            harness.declare(broken)
            harness.declare(healthy)
            // The node fails the way a node must not: an `IOException` from
            // creating a host directory on a full disk, escaping without being
            // translated. It used to escape `launch` too, cancel the scope, and
            // take the resync ticker, the change feed and every other worker
            // with it.
            harness.node.throwRaw(
                NodeOperation.OBSERVE,
                java.io.IOException("No space left on device"),
            )

            val job = launch { loopFor(harness).run() }
            advanceTimeBy(3.seconds)

            // The other server was brought all the way up while the first one
            // was failing.
            other.creates shouldHaveSize 1
            other.starts shouldHaveSize 1
            harness.status(healthy.metadata.name).shouldNotBeNull().phase shouldBe ServerPhase.RUNNING

            // And the broken one is still being retried on the backoff rather
            // than forgotten.
            advanceTimeBy(20.seconds)
            harness.node.calls.count { it == NodeOperation.OBSERVE } shouldBeGreaterThan 1

            // The loop is still alive all the way through: once the node stops
            // misbehaving the server it could not touch is brought up.
            harness.node.stopFailing(NodeOperation.OBSERVE)
            advanceTimeBy(1.minutes)
            harness.node.creates shouldHaveSize 1
            harness.status(broken.metadata.name).shouldNotBeNull().phase shouldBe ServerPhase.RUNNING
            job.cancel()
        }

    @Test
    fun `a drain whose save keeps failing backs off instead of spinning`() =
        runTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // A permanent misconfiguration that the loop cannot tell from a
            // hiccup: the wrong RCON password. Classified retryable, correctly,
            // so it retries for ever — the question is how fast.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "", "authentication failed")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)

            val job = launch { loopFor(harness).run() }
            advanceTimeBy(5.minutes)

            // With a 10s base doubling to a 1m cap, five minutes is under a
            // dozen attempts. The failure used to alternate with a resume pass
            // that reported progress and reset the counter, so the backoff
            // never applied and this was a save request every couple of seconds
            // against a live server — about 150 of them.
            val attempts = harness.node.saves.size
            attempts shouldBeGreaterThan 1
            attempts shouldBeLessThan 15
            harness.node.stops shouldHaveSize 0
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

    /**
     * Shutting the loop down must not itself fail.
     *
     * `run`'s `finally` closes the queue while cancellation is still
     * propagating to the workers, so a worker parked in `take()` could be
     * resumed by the close rather than by the cancellation and escape with
     * `ClosedReceiveChannelException` — an orderly shutdown reporting a channel
     * error nobody can act on, which masks any genuine error on the way out.
     *
     * `cancelAndJoin` is the assertion: `launch` reports an unhandled child
     * exception to its parent scope, so a loop that throws on the way out fails
     * this test, which is exactly how it surfaced in `:app`.
     *
     * **This test does not reproduce the race, and did not catch it.** Under
     * `runTest` the dispatcher is single-threaded and virtual, so cancellation
     * reaches the workers before the close every time and this passed against
     * the broken code. Measured on a real dispatcher the losing interleaving
     * came up 4 times in 200 shutdowns — real, and far too rare to assert on.
     * The deterministic pin is `WorkQueueTest`'s parked-worker test; this one is
     * a cheap guard on the surrounding behaviour, and it is worth knowing which
     * is which.
     */
    @Test
    fun `a loop shut down with its workers parked exits cleanly`() =
        runTest {
            val harness = Harness()

            val job = launch { loopFor(harness).run() }
            // Long enough to seed, find nothing, and park every worker.
            advanceTimeBy(1.seconds)

            job.cancelAndJoin()

            job.isCancelled shouldBe true
        }

    /**
     * The loop does not finish unwinding until an issued save has been recorded.
     *
     * This is the half of the save-record shield that lives here rather than in
     * `Reconciler`. `NonCancellable` makes the write happen; it is worth nothing
     * unless the *store is still open* when it does, and what keeps the store
     * open is `mcorch.app.Main` closing the `Orchestrator` only after
     * `loop.join()` returns. That join is only meaningful if `run()` waits for a
     * cancelled worker to finish its shielded write — so this asserts the record
     * is already on the store the instant the loop's job completes, with no
     * polling and no second chance.
     *
     * Written against a real dispatcher and a real `Job`, not virtual time: the
     * thing being pinned is an ordering between a cancellation and a write, and
     * `runTest`'s single-threaded scheduler is exactly where such an ordering
     * looks fine whatever the code does. The cancellation is still deterministic
     * — it is issued from inside the save exec — so this is a pin rather than a
     * probe.
     */
    @Test
    fun `the loop does not finish unwinding until an issued save is recorded`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)
            repeat(5) { harness.pass(name) }
            harness.node.saves shouldHaveSize 0

            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val loop =
                    scope.launch(start = CoroutineStart.LAZY) {
                        loopFor(harness).run()
                    }
                harness.node.onExec = { command ->
                    if (command == PaperCommands.saveAll()) {
                        loop.cancel(CancellationException("the orchestrator is shutting down"))
                    }
                    harness.node.defaultExec(command)
                }
                loop.start()
                // A generous ceiling on a fast operation: it exists so a loop that
                // never unwinds fails the test instead of hanging the suite.
                withTimeout(30.seconds) { loop.join() }

                harness.node.saves shouldHaveSize 1
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
                    .worldSavedAt
                    .shouldNotBeNull()
            } finally {
                scope.cancel()
            }
        }

    /**
     * The same shutdown, with a pass actually in flight rather than every worker
     * idle — the other half of the race, and the one that decides whether an
     * abandoned pass can take the loop down with it.
     */
    @Test
    fun `a loop shut down mid-pass exits cleanly`() =
        runTest {
            val harness = Harness()
            harness.declare(paperDefinition())

            val job = launch { loopFor(harness).run() }
            // Mid-bring-up: created and starting, so a worker is inside a pass
            // rather than waiting for one.
            advanceTimeBy(150.milliseconds)

            job.cancelAndJoin()

            job.isCancelled shouldBe true
        }
}

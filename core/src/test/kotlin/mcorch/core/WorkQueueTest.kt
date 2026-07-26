package mcorch.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import mcorch.schema.ResourceName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The queue is how the loop waits without blocking. Its behaviour under virtual
 * time is exactly its behaviour in production because it has no clock of its
 * own — only `delay`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WorkQueueTest {
    private val survival: ResourceName = resourceName("survival-01")
    private val lobby: ResourceName = resourceName("lobby-01")

    @Test
    fun `a server queued twice is handed out once`() =
        runTest {
            val queue = WorkQueue(this)
            queue.add(survival)
            queue.add(survival)
            queue.add(lobby)

            val taken = listOf(queue.take(), queue.take())

            taken shouldContainExactly listOf(survival, lobby)
            queue.close()
        }

    @Test
    fun `a delayed add does not block and arrives when it is due`() =
        runTest {
            val queue = WorkQueue(this)
            queue.addAfter(survival, 5.seconds)

            val early = async { withTimeoutOrNull(1.seconds) { queue.take() } }
            advanceTimeBy(1.seconds + 1.milliseconds)
            early.await() shouldBe null

            val due = async { queue.take() }
            advanceTimeBy(5.seconds)
            due.await() shouldBe survival
            queue.close()
        }

    @Test
    fun `a shorter delay supersedes a longer one`() =
        runTest {
            val queue = WorkQueue(this)
            queue.addAfter(survival, 5.minutesAsSeconds())
            queue.addAfter(survival, 1.seconds)

            val taken = async { queue.take() }
            advanceTimeBy(1.seconds + 1.milliseconds)

            taken.await() shouldBe survival
            queue.close()
        }

    @Test
    fun `a server being reconciled is not handed out again until the pass finishes`() =
        runTest {
            val queue = WorkQueue(this)
            queue.add(survival)
            queue.take() shouldBe survival

            // A change arrives mid-pass. It must not start a second concurrent
            // pass, and it must not be lost either.
            queue.add(survival)
            val concurrent = async { withTimeoutOrNull(1.seconds) { queue.take() } }
            advanceTimeBy(2.seconds)
            concurrent.await() shouldBe null

            queue.done(survival)
            val after = async { queue.take() }
            advanceTimeBy(1.milliseconds)
            after.await() shouldBe survival
            queue.close()
        }

    @Test
    fun `closing cancels the delayed re-adds the queue owns`() =
        runTest {
            val queue = WorkQueue(this)
            queue.addAfter(survival, 5.seconds)

            queue.close()
            // Nothing is left ticking: `runTest` fails a test that ends with a
            // live child coroutine, and the pending re-add is one. The loop
            // never called this, so every shutdown left its timers running
            // until the scope got round to them.
            advanceTimeBy(10.seconds)

            shouldThrow<ClosedReceiveChannelException> { queue.take() }
        }

    @Test
    fun `attempt counts rise on failure and reset on success`() =
        runTest {
            val queue = WorkQueue(this)

            queue.failed(survival) shouldBe 1
            queue.failed(survival) shouldBe 2
            queue.succeeded(survival)
            queue.failed(survival) shouldBe 1
            queue.close()
        }

    private fun Int.minutesAsSeconds() = (this * 60).seconds
}

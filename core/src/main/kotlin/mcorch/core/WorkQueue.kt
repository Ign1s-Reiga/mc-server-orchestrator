package mcorch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mcorch.schema.ResourceName
import kotlin.time.Duration

/**
 * The work the loop has to do, one entry per server.
 *
 * Three properties, all of which the reconcile loop depends on:
 *
 * - **Coalescing.** A server appears at most once. Ten edits in a second
 *   produce one pass, not ten, and a pass always reads the latest state anyway.
 * - **Delayed re-add.** Requeueing after a backoff is how the loop waits.
 *   Nothing sleeps in place: [addAfter] schedules and returns immediately.
 * - **No concurrent passes for one server.** A name handed out by [take] is not
 *   handed out again until [done]. Anything that asks for it in the meantime is
 *   remembered and re-added on completion, so a change that arrives mid-pass is
 *   never lost.
 *
 * Time is expressed only through [delay], with no clock of its own, so a test
 * driving virtual time sees exactly the behaviour production does.
 */
internal class WorkQueue(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val ready = Channel<ResourceName>(Channel.UNLIMITED)

    /** Queued and waiting to be taken. */
    private val queued = mutableSetOf<ResourceName>()

    /** Taken and not finished. */
    private val processing = mutableSetOf<ResourceName>()

    /** Asked for while it was being processed; re-added on [done]. */
    private val dirty = mutableSetOf<ResourceName>()

    /** Pending delayed re-adds, so a shorter delay can supersede a longer one. */
    private val timers = mutableMapOf<ResourceName, Timer>()

    /** Consecutive retryable failures, for the backoff. */
    private val attempts = mutableMapOf<ResourceName, Int>()

    private class Timer(
        val job: Job,
        val delay: Duration,
    )

    /** Queues [name] to be reconciled as soon as a worker is free. */
    suspend fun add(name: ResourceName) {
        mutex.withLock { enqueue(name) }
    }

    /**
     * Queues [name] after [delay]. A pending delayed add for the same name is
     * replaced when [delay] is shorter, so an operator's edit is not made to
     * wait out a five-minute backoff.
     */
    suspend fun addAfter(
        name: ResourceName,
        delay: Duration,
    ) {
        if (!delay.isPositive()) {
            add(name)
            return
        }
        mutex.withLock {
            val existing = timers[name]
            if (existing != null && existing.delay <= delay) return@withLock
            existing?.job?.cancel()
            val job =
                scope.launch {
                    delay(delay)
                    mutex.withLock {
                        timers.remove(name)
                        enqueue(name)
                    }
                }
            timers[name] = Timer(job, delay)
        }
    }

    /** Suspends until a server needs a pass. */
    suspend fun take(): ResourceName {
        val name = ready.receive()
        mutex.withLock {
            queued.remove(name)
            processing.add(name)
        }
        return name
    }

    /**
     * Marks a pass finished. Anything that asked for this server while the pass
     * was running is queued now.
     */
    suspend fun done(name: ResourceName) {
        mutex.withLock {
            processing.remove(name)
            if (dirty.remove(name)) enqueue(name)
        }
    }

    /** Records a retryable failure and returns the number of consecutive ones. */
    suspend fun failed(name: ResourceName): Int =
        mutex.withLock {
            val next = (attempts[name] ?: 0) + 1
            attempts[name] = next
            next
        }

    /** Clears the failure count after a pass that did not fail. */
    suspend fun succeeded(name: ResourceName) {
        mutex.withLock { attempts.remove(name) }
    }

    /** Cancels every pending delayed add. The scope owns the workers. */
    suspend fun close() {
        mutex.withLock {
            timers.values.forEach { it.job.cancel() }
            timers.clear()
        }
        ready.close()
    }

    private fun enqueue(name: ResourceName) {
        if (name in processing) {
            dirty.add(name)
            return
        }
        if (!queued.add(name)) return
        // Unlimited capacity, so this cannot suspend or fail while the queue is
        // open; a closed queue means shutdown, and dropping work then is right.
        ready.trySend(name)
    }
}

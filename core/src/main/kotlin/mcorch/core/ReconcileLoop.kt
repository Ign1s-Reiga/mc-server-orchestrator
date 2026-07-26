package mcorch.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mcorch.schema.DrainState
import mcorch.schema.ResourceName
import mcorch.store.ChangeFeed
import mcorch.store.Store
import mcorch.store.StoreCursor
import mcorch.store.StoreException
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The driver: finds work, runs [Reconciler] passes, and requeues.
 *
 * ## How work is found
 *
 * Three ways, in this order of trust:
 *
 * 1. **In-flight drains, first of all.** On startup, servers whose last
 *    observation recorded a drain part-way through are queued before anything
 *    else. A drain that is never picked up leaves players on a server nobody is
 *    watching, and one that silently restarts from the beginning re-sends a
 *    save request to a live server.
 * 2. **A full resync**, every [ReconcileLoopConfig.resyncPeriod]. Correctness
 *    rests on this alone: it cannot miss anything.
 * 3. **The change feed**, polled every [ReconcileLoopConfig.changePollInterval],
 *    so an operator's edit is acted on in milliseconds rather than at the next
 *    resync. It is an optimisation, and it is allowed to expire and send the
 *    loop back to a full resync.
 *
 * ## How it waits
 *
 * It does not. A pass that needs to wait for a container returns
 * [ReconcileOutcome.Waiting] and the server is re-added to the queue after a
 * delay; a pass that failed retryably comes back on an exponential [Backoff].
 * No pass ever blocks on a container reaching a state, because a blocked pass
 * is a worker that cannot serve the other servers behind it.
 */
public class ReconcileLoop(
    private val store: Store,
    private val reconciler: Reconciler,
    private val config: ReconcileLoopConfig = ReconcileLoopConfig(),
    private val backoff: Backoff = Backoff(),
) {
    /**
     * Runs until the calling coroutine is cancelled.
     *
     * Structured throughout: workers, the resync ticker, the change-feed poller
     * and every pending requeue are children of the caller's scope, so
     * cancelling it stops all of them.
     */
    public suspend fun run(): Unit =
        coroutineScope {
            val queue = WorkQueue(this)
            try {
                resumeDrains(queue)
                val cursor = seed(queue)
                buildList {
                    add(launch { watchChanges(queue, cursor) })
                    add(launch { resyncPeriodically(queue) })
                    repeat(config.concurrency) { worker -> add(launch { work(queue, worker) }) }
                    // Joined explicitly rather than left to `coroutineScope`, so
                    // that the shutdown below happens after the workers stop and
                    // not the moment they are launched.
                }.joinAll()
            } finally {
                // Only reached on the way out, since the children above never
                // return on their own. Pending delayed re-adds are the queue's,
                // not the scope's, so they are cancelled here rather than left
                // to a scope that is already shutting down.
                withContext(NonCancellable) { queue.close() }
            }
        }

    /**
     * Queues drains that were in flight when the loop last stopped, before any
     * other work.
     */
    private suspend fun resumeDrains(queue: WorkQueue) {
        val inFlight =
            try {
                store.listByDrainState(RESUMABLE_DRAIN_STATES)
            } catch (failure: StoreException) {
                LOG.warn("could not read in-flight drains at startup: {}", failure.message)
                return
            }
        if (inFlight.isEmpty()) return
        LOG.info("resuming {} in-flight drain(s) before anything else", inFlight.size)
        inFlight.forEach { queue.add(it.name) }
    }

    /** Reads the cursor *before* the resync, so nothing between the two is missed. */
    private suspend fun seed(queue: WorkQueue): StoreCursor? {
        val cursor =
            try {
                store.currentCursor()
            } catch (failure: StoreException) {
                LOG.warn("could not read the change cursor at startup: {}", failure.message)
                null
            }
        resync(queue)
        return cursor
    }

    private suspend fun resync(queue: WorkQueue) {
        try {
            store.listServers().forEach { queue.add(it.name) }
        } catch (failure: StoreException) {
            LOG.warn("resync failed: {}", failure.message)
        }
    }

    private suspend fun resyncPeriodically(queue: WorkQueue) {
        while (true) {
            delay(config.resyncPeriod)
            resync(queue)
        }
    }

    private suspend fun watchChanges(
        queue: WorkQueue,
        from: StoreCursor?,
    ) {
        var cursor = from
        while (true) {
            delay(config.changePollInterval)
            val feed =
                try {
                    store.changesSince(cursor)
                } catch (failure: StoreException) {
                    LOG.warn("could not read the change feed: {}", failure.message)
                    continue
                }
            when (feed) {
                is ChangeFeed.Changes -> {
                    feed.changes.forEach { queue.add(it.name) }
                    cursor = feed.cursor
                }

                is ChangeFeed.Expired -> {
                    LOG.info("the change feed expired; falling back to a full resync")
                    cursor = feed.cursor
                    resync(queue)
                }
            }
        }
    }

    /**
     * One worker: take a server, reconcile it, decide when to look again.
     *
     * The catch is the last line of defence, and it is deliberately broad. A
     * [Reconciler] pass classifies everything it expects, but an exception it
     * did not expect would escape `launch`, cancel this scope, and take the
     * resync ticker, the change-feed poller and the other workers with it — so
     * one server with a bad definition or a node throwing something untranslated
     * would stop the orchestrator reconciling *every* server. Nothing is
     * swallowed: it is logged at error and the server comes back on a backoff.
     */
    private suspend fun work(
        queue: WorkQueue,
        worker: Int,
    ) {
        while (true) {
            val name = queue.take()
            try {
                val outcome =
                    try {
                        reconciler.reconcile(name)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (unexpected: Throwable) {
                        LOG.error(
                            "server={} failed a pass with an unhandled exception; the loop keeps running and " +
                                "will retry it",
                            name,
                            unexpected,
                        )
                        ReconcileOutcome.Retry("the pass failed unexpectedly: ${unexpected.message}")
                    }
                requeue(queue, name, outcome)
            } finally {
                // Even a cancelled pass releases the name, so a restarted
                // worker can pick the server up again. Under `NonCancellable`
                // because `done` takes a lock, and taking a lock from a
                // cancelled coroutine throws instead of waiting — which would
                // leave the name held for good.
                withContext(NonCancellable) { queue.done(name) }
            }
            LOG.trace("worker {} finished a pass for server={}", worker, name)
        }
    }

    /** Turns an outcome into "when do I look at this again". */
    private suspend fun requeue(
        queue: WorkQueue,
        name: ResourceName,
        outcome: ReconcileOutcome,
    ) {
        when (outcome) {
            is ReconcileOutcome.Settled -> {
                queue.succeeded(name)
                queue.addAfter(name, config.resyncPeriod)
            }

            is ReconcileOutcome.Progressed -> {
                queue.succeeded(name)
                queue.addAfter(name, config.stepInterval)
            }

            is ReconcileOutcome.Waiting -> {
                queue.succeeded(name)
                queue.addAfter(name, outcome.after)
            }

            is ReconcileOutcome.Retry -> {
                val attempt = queue.failed(name)
                val delay = backoff.delayFor(attempt)
                LOG.debug(
                    "server={} will be retried in {}ms (attempt {}): {}",
                    name,
                    delay.inWholeMilliseconds,
                    attempt,
                    outcome.detail,
                )
                queue.addAfter(name, delay)
            }

            // Deliberately not requeued for another attempt. The next pass
            // happens at the resync, or when the definition changes and the
            // change feed wakes it — and a pass that finds the same permanent
            // failure at the same generation does nothing at all.
            is ReconcileOutcome.Failed -> {
                queue.succeeded(name)
                LOG.warn("server={} needs attention: {}", name, outcome.detail)
            }
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(ReconcileLoop::class.java)

        /**
         * Every drain state except the terminal failure. A drain that aborted
         * permanently is not resumed at startup — that is what "wait for a
         * human" means — and the resync picks it up for reporting anyway.
         */
        private val RESUMABLE_DRAIN_STATES: Set<DrainState> =
            DrainState.entries.toSet() - DrainState.DRAIN_FAILED
    }
}

/** Cadences for the driver. */
public data class ReconcileLoopConfig(
    /** The full read that correctness rests on. */
    val resyncPeriod: Duration = 5.minutes,
    /** How often the low-latency change feed is polled. */
    val changePollInterval: Duration = 500.milliseconds,
    /** How soon after a step the next step is attempted. */
    val stepInterval: Duration = 1.seconds,
    /** How many servers may be reconciled at once. One server is never reconciled twice at once. */
    val concurrency: Int = 4,
) {
    init {
        require(resyncPeriod.isPositive()) { "resyncPeriod must be positive" }
        require(changePollInterval.isPositive()) { "changePollInterval must be positive" }
        require(stepInterval.isPositive()) { "stepInterval must be positive" }
        require(concurrency >= 1) { "concurrency must be at least 1, got: $concurrency" }
    }
}

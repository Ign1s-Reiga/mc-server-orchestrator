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
import mcorch.store.ServerListing
import mcorch.store.Store
import mcorch.store.StoreCursor
import mcorch.store.StoreException
import mcorch.store.Unreadable
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
                // Startup is inside the containment too, and it is the half that
                // bites hardest: `seed` resyncs before anything is launched, so a
                // throwable there kills the process on *every* restart rather
                // than once. An orchestrator that cannot start is one nobody can
                // use to repair the state that stopped it starting.
                contained("resuming in-flight drains") { resumeDrains(queue) }
                val cursor = contained("the startup resync", null) { seed(queue) }
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
        val listing =
            try {
                store.listAllByDrainState(RESUMABLE_DRAIN_STATES)
            } catch (failure: StoreException) {
                LOG.warn("could not read in-flight drains at startup: {}", failure.message)
                return
            }
        val resumable = report(listing, "resuming in-flight drains")
        if (resumable.isEmpty()) return
        LOG.info("resuming {} in-flight drain(s) before anything else", resumable.size)
        resumable.forEach { queue.add(it) }
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
        val listing =
            try {
                store.listAll()
            } catch (failure: StoreException) {
                LOG.warn("resync failed: {}", failure.message)
                return
            }
        report(listing, "resync").forEach { queue.add(it) }
    }

    /**
     * The servers this pass may act on, having said out loud what it is skipping.
     *
     * Two kinds of row are held back, for two different reasons, and neither may
     * be allowed to cost anything but itself. **This read used to be the strict
     * one, and a single undecodable row threw out of it** — so a resync queued
     * nothing at all and the loop reconciled *nothing*, on every pass, from one
     * bad row. No world data was at risk directly, because a halted loop cannot
     * stop a container; what was lost was the ability to act at all, including
     * finishing a drain with players waiting on it.
     *
     * - An unreadable **definition** has no desired state to converge on. There
     *   is nothing to reconcile and no [ResourceName] to queue it under — the
     *   name itself can be what will not parse.
     * - An unreadable **observation** is a server the loop must not act on, and
     *   this is the drain-relevant half. The record of a delivered save lives in
     *   the observation, so an unreadable one is exactly the case where the loop
     *   cannot know whether a save request went out.
     *
     * **The protection is [Store.getServer]'s, not this function's.** That read
     * raises for such a row, so a pass could not act on one even if it were
     * queued; skipping here is a *reporting* improvement — one line naming the
     * server and which half is unreadable, once per resync, instead of a generic
     * store failure per pass — and it costs a `getServer` and two warnings less.
     * It is not what makes the drain safe, and it does not stop leaning on
     * another module: it leans on it entirely. Do not let this comment grow into
     * a claim that it does.
     *
     * Latency is identical either way, which is worth stating because it is not
     * obvious: a queued-and-refused pass ends in a non-retryable store failure,
     * which [requeue] answers with `succeeded` and *no* re-add, so that variant
     * also waits for the next resync.
     *
     * Both are reported at error every time rather than once: they do not heal on
     * their own, and a line that appears only at startup is a line nobody sees.
     * Repairing the row is enough to bring the server back — the next resync
     * simply finds it readable.
     *
     * ## Why [Unreadable.retryable] is not consulted
     *
     * Every decode failure is permanent by construction — the same bytes parse
     * the same way next time — so today the flag is always false here and
     * branching on it would be dead. It is deliberately *not* used as the
     * partition key, because the safe answer does not depend on it: a row that
     * cannot be read now must not be acted on now, whether or not a later read
     * might succeed. If `:store` ever reports a genuinely retryable unreadable —
     * a lock timeout surfaced this way rather than raised — the correct change is
     * to keep skipping and stop logging it at error, not to start acting on it.
     */
    private fun report(
        listing: ServerListing,
        what: String,
    ): List<ResourceName> {
        listing.unreadable.forEach { entry ->
            LOG.error(
                "{}: skipping server={} — its desired state cannot be read and nothing can be reconciled for it " +
                    "until the row is repaired: {}",
                what,
                entry.name,
                entry.unreadable.reason,
            )
        }
        val (blocked, actionable) = listing.servers.partition { it.unreadable != null }
        blocked.forEach { server ->
            LOG.error(
                "{}: skipping server={} — its last observation cannot be read, so the loop cannot tell what it " +
                    "has already done to this server and will not act on it. If a drain was in flight it stays " +
                    "where it is and the container keeps running: {}",
                what,
                server.name,
                server.unreadable?.reason,
            )
        }
        return actionable.map { it.name }
    }

    private suspend fun resyncPeriodically(queue: WorkQueue) {
        while (true) {
            delay(config.resyncPeriod)
            contained("the resync") { resync(queue) }
        }
    }

    private suspend fun watchChanges(
        queue: WorkQueue,
        from: StoreCursor?,
    ) {
        var cursor = from
        while (true) {
            delay(config.changePollInterval)
            cursor = contained("the change feed", cursor) { poll(queue, cursor) }
        }
    }

    private suspend fun poll(
        queue: WorkQueue,
        cursor: StoreCursor?,
    ): StoreCursor? {
        val feed =
            try {
                store.changesSince(cursor)
            } catch (failure: StoreException) {
                LOG.warn("could not read the change feed: {}", failure.message)
                return cursor
            }
        return when (feed) {
            is ChangeFeed.Changes -> {
                feed.changes.forEach { queue.add(it.name) }
                feed.cursor
            }

            is ChangeFeed.Expired -> {
                LOG.info("the change feed expired; falling back to a full resync")
                resync(queue)
                feed.cursor
            }
        }
    }

    /**
     * Runs one tick and lets nothing but cancellation out of it.
     *
     * The tickers are `launch`ed children, so anything escaping one cancels the
     * whole scope and takes the workers and the other ticker with it. `run` does
     * not restart them and neither does `Orchestrator`, so the process is simply
     * down — and because `seed` resyncs at startup, down again on every restart.
     * No container is stopped by that, so nothing is lost directly; what it costs
     * is every in-flight drain frozen with players on it and an orchestrator
     * nobody can bring back without repairing state by hand.
     *
     * [work] has had this guard for a while, on exactly this reasoning. The
     * tickers did not, and a store read is not obliged to fail as a
     * [StoreException] — the one that found this raised an NPE from a row whose
     * primary key was NULL, which every `catch (StoreException)` on the path
     * walked straight past. The point is not that particular row: it is that a
     * `catch` naming one type is a bet on what the layer below will throw, and
     * the tickers are where losing that bet costs the whole process rather than
     * one server.
     *
     * A failed tick is skipped, not retried in place — the next tick is the
     * retry, and both cadences run for ever.
     */
    private suspend fun <T> contained(
        what: String,
        fallback: T,
        tick: suspend () -> T,
    ): T =
        try {
            tick()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Throwable) {
            LOG.error(
                "{} failed with an unhandled exception; the loop keeps running and will try again next tick",
                what,
                unexpected,
            )
            fallback
        }

    private suspend fun contained(
        what: String,
        tick: suspend () -> Unit,
    ): Unit = contained(what, Unit, tick)

    /**
     * One worker: take a server, reconcile it, decide when to look again.
     *
     * ## Nothing but cancellation leaves this function
     *
     * An exception escaping `launch` cancels this scope and takes the resync
     * ticker, the change-feed poller and every other worker with it — so one
     * server with a bad definition, or a node throwing something untranslated,
     * would stop the orchestrator reconciling *every* server. There are two
     * guards rather than one because there are two places it can come from, and
     * for a while only the inner one existed:
     *
     * - the **pass**, which is where an untranslated node or store failure
     *   arrives. Logged at error, and the server comes back on a backoff.
     * - the **requeue**, which takes locks and computes a delay. Logged at
     *   error and left to the resync, which cannot miss it.
     *
     * `queue.take()` sits outside both on purpose: it is the one call whose
     * failure is not this server's problem, and it now reports a closed queue by
     * returning null rather than by throwing. That is what an orderly shutdown
     * looks like from in here.
     *
     * Nothing is swallowed and nothing is retried silently.
     */
    private suspend fun work(
        queue: WorkQueue,
        worker: Int,
    ) {
        while (true) {
            // The queue is closed: the loop is shutting down and there is no
            // more work coming. Returning ends this worker cleanly, which is
            // the whole point — see [WorkQueue.take].
            val name = queue.take() ?: return
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Throwable) {
                // Requeueing failed, which is not something a pass can classify
                // — the pass is already over. The server simply does not get its
                // scheduled re-add, and the resync brings it back, so the cost
                // is latency rather than correctness. What must not happen is
                // this worker dying and taking the others with it.
                LOG.error(
                    "server={} finished a pass that could not be requeued; the resync will pick it up",
                    name,
                    unexpected,
                )
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

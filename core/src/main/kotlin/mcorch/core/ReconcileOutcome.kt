package mcorch.core

import kotlin.time.Duration

/**
 * What one reconcile pass concluded, and when the server should be looked at
 * again.
 *
 * Every outcome except [Failed] comes back to the queue. The loop never waits
 * in place for a container to reach a state — there is no wait-for-exit call to
 * wait on, and blocking a pass would stall every other server behind it — so
 * "wait" is always spelled [Waiting] and always means "requeue me".
 */
public sealed interface ReconcileOutcome {
    /** A short, operator-facing explanation. Never player data. */
    public val detail: String

    /**
     * Desired and observed state agree; nothing was done and nothing needs
     * doing. Requeued at the resync period.
     */
    public data class Settled(
        override val detail: String,
    ) : ReconcileOutcome

    /**
     * One convergent step was applied. Requeued promptly to apply the next one.
     */
    public data class Progressed(
        override val detail: String,
    ) : ReconcileOutcome

    /**
     * Waiting on something that changes by itself — a container starting, a
     * server becoming joinable, a stopped container being reaped. Requeued
     * after [after], which is a poll interval rather than a backoff: nothing
     * failed.
     */
    public data class Waiting(
        override val detail: String,
        val after: Duration,
    ) : ReconcileOutcome

    /**
     * A retryable failure. Requeued with exponential backoff, and the failure
     * is on observed status while it lasts.
     */
    public data class Retry(
        override val detail: String,
    ) : ReconcileOutcome

    /**
     * A permanent failure, already recorded on observed status. Not requeued
     * for another attempt: the next pass on this server happens when the
     * definition changes or at the next resync, and a pass that finds the same
     * permanent failure at the same generation does nothing at all.
     */
    public data class Failed(
        override val detail: String,
    ) : ReconcileOutcome
}

package mcorch.cri

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Per-call deadlines. Every outward CRI call gets one — a hung containerd must
 * not hang the reconcile loop.
 *
 * These are transport deadlines. When one elapses the call fails with
 * [CriException.Timeout], which is retryable. They are separate from, and always
 * larger than, the semantic timeouts a caller passes in
 * ([StopGracePeriod], the `ExecSync` command timeout).
 */
public data class CriTimeouts(
    /** Reads that containerd answers from memory: version, status, list, per-object status. */
    val query: Duration = 15.seconds,
    /** Sandbox create/teardown, including CNI setup. */
    val sandboxLifecycle: Duration = 2.minutes,
    /** Container create/start/remove. Not stop — see [stopDeadlineCap]. */
    val containerLifecycle: Duration = 2.minutes,
    /**
     * Image pull. Long by design: a Paper server image over a slow link is
     * minutes, and failing a pull early only makes the loop retry the whole
     * download.
     */
    val imagePull: Duration = 30.minutes,
    /** Image removal. */
    val imageLifecycle: Duration = 2.minutes,
    /**
     * Added on top of the wait a caller asked for to get the transport deadline.
     * A `StopContainer` with a 120s grace period gets a 120s + this deadline, so
     * the kill fires before the transport gives up and the caller learns the
     * container actually stopped rather than getting an ambiguous timeout. Same
     * for the `ExecSync` command timeout.
     *
     * The wait it is added to is the caller's, [stopDeadlineCap]'s, whichever is
     * smaller — this is the margin, not the bound.
     */
    val deadlineSlack: Duration = 30.seconds,
    /**
     * The most of a stop grace period that may become the transport deadline of
     * a single `CriClient.stopContainer`.
     *
     * The deadline is `min(gracePeriod, this) + deadlineSlack`. **The grace
     * period containerd is asked for is never shortened by this** — the whole
     * value still goes on the wire — so nothing here can make a container be
     * killed sooner than the caller asked. What it bounds is the *other* thing a
     * grace period used to decide: how long one RPC may park the caller. Without
     * it the two are the same number, and a definition carrying a 30-day grace
     * period parks a reconcile worker for a month with no effective deadline,
     * which is the property CLAUDE.md requires of everything crossing this
     * boundary. A cap on the grace period cannot own that property — a grace
     * period is half of a validated pair and shortening it inverts the pair —
     * so it is owned here, on the value this module actually owns.
     *
     * ## What it costs when it bites
     *
     * A `DEADLINE_EXCEEDED` on a stop that is still perfectly healthy, reported
     * as a retryable [CriException.Timeout] that says as much. That is the
     * intended trade, and the direction it errs in matters: **a capped deadline
     * can only ever leave a container running longer, never kill it sooner.**
     *
     * The reason it leaves one running longer is containerd's, and it is worth
     * knowing before reading a stuck container as a defect. containerd sends the
     * stop signal, waits out the grace period on a context derived from the
     * request's, and escalates to `SIGKILL` only if that inner wait is the thing
     * that expired: `internal/cri/server/container_stop.go` returns immediately
     * with `if ctx.Err() != nil { return ctx.Err() }` when the request context
     * went first (read against containerd 2.3.3, the release
     * `scripts/dev/containerd-env.sh` pins, and measured in
     * `cri/src/integrationTest`). So when this cap fires, the container has the
     * stop signal and will *not* be killed by that call. A caller that re-issues
     * the stop delivers the signal again; a container that ignores it is
     * reported rather than killed.
     *
     * Measured against that runtime: a 12s grace period on a container that
     * ignores `SIGTERM`, deadlined at 4s by a 2s cap, gave up at 4.04s and left
     * the container `RUNNING` 17s after the stop was issued — five seconds past
     * the grace period containerd had been asked for, with no kill. A re-issued
     * stop whose grace period fitted inside the cap finished it in 1.74s.
     *
     * ## Why two hours
     *
     * It is set to be at least the largest grace period any server definition in
     * this system can legitimately carry, so on every definition an operator
     * could actually write this changes nothing whatever: the deadline is still
     * `gracePeriod + deadlineSlack` and the runtime's own kill still fires. This
     * module cannot see `:schema`'s cap and deliberately does not depend on it;
     * if that cap ever rises above this, the consequence is a retryable timeout
     * on a stop that is still proceeding, not a shortened grace period.
     */
    val stopDeadlineCap: Duration = 2.hours,
) {
    init {
        requireDeadline(query, "query")
        requireDeadline(sandboxLifecycle, "sandboxLifecycle")
        requireDeadline(containerLifecycle, "containerLifecycle")
        requireDeadline(imagePull, "imagePull")
        requireDeadline(imageLifecycle, "imageLifecycle")
        requireDeadline(deadlineSlack, "deadlineSlack")
        requireDeadline(stopDeadlineCap, "stopDeadlineCap")
    }
}

/**
 * Every value in [CriTimeouts] has to be a duration a deadline can be made of.
 *
 * Finiteness is checked as well as sign, and not as a formality:
 * `Duration.INFINITE.inWholeMilliseconds` is `Long.MAX_VALUE`, which grpc
 * saturates into a deadline about 292 million years out. That is not "no
 * timeout configured" — it is the timeout removed while every call still looks
 * like it has one.
 */
private fun requireDeadline(
    value: Duration,
    name: String,
) {
    require(value.isPositive() && value.isFinite()) {
        "$name must be a positive, finite duration, got: $value"
    }
}

/**
 * Everything needed to open a CRI connection.
 *
 * [endpoint] has no default on purpose: defaulting it would let a misconfigured
 * deployment quietly talk to the dev containerd. Resolve it from configuration,
 * or from [CriEndpoint.fromEnvironment], and decide explicitly what "unset"
 * means.
 */
public data class CriClientConfig(
    val endpoint: CriEndpoint,
    val timeouts: CriTimeouts = CriTimeouts(),
    /**
     * gRPC's default 4 MiB inbound limit is too small for CRI: `ExecSync` is
     * specified to return up to 16 MiB of captured output, and a `ListContainers`
     * on a busy node is not small either. 32 MiB leaves headroom for both.
     */
    val maxInboundMessageSizeBytes: Int = DEFAULT_MAX_INBOUND_MESSAGE_SIZE_BYTES,
) {
    init {
        require(maxInboundMessageSizeBytes >= MIN_INBOUND_MESSAGE_SIZE_BYTES) {
            "maxInboundMessageSizeBytes must be at least $MIN_INBOUND_MESSAGE_SIZE_BYTES " +
                "(ExecSync alone may return 16 MiB), got: $maxInboundMessageSizeBytes"
        }
    }

    public companion object {
        /** 32 MiB. */
        public const val DEFAULT_MAX_INBOUND_MESSAGE_SIZE_BYTES: Int = 32 * 1024 * 1024

        /** 16 MiB — the cap CRI puts on a single `ExecSync` response. */
        public const val MIN_INBOUND_MESSAGE_SIZE_BYTES: Int = 16 * 1024 * 1024
    }
}

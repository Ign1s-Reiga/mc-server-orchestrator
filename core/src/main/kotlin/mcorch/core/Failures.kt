package mcorch.core

import mcorch.schema.DrainBlock
import mcorch.schema.DrainBlockReason
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.FailureStatus
import java.time.Instant

/**
 * Builds a failure for observed status, carrying the attempt count forward.
 *
 * [previous] is the failure recorded last pass. When the same [reason] is still
 * the problem, the count goes up and [FailureStatus.occurredAt] keeps pointing
 * at the *first* occurrence — an operator wants to know how long this has been
 * happening, not when the loop last looked. A different reason starts over.
 */
internal fun recordFailure(
    reason: FailureReason,
    failureClass: FailureClass,
    message: String,
    now: Instant,
    previous: FailureStatus?,
): FailureStatus =
    if (previous != null && previous.reason == reason) {
        FailureStatus(
            reason = reason,
            failureClass = failureClass,
            message = message,
            occurredAt = previous.firstOccurrenceOf(reason, now),
            attempts = previous.attempts + 1,
        )
    } else {
        FailureStatus(
            reason = reason,
            failureClass = failureClass,
            message = message,
            occurredAt = now,
            attempts = 1,
        )
    }

/**
 * When the failure a pass is *about to record* first occurred.
 *
 * The same carry-forward rule [recordFailure] applies, pulled out so a caller can
 * ask it **before** the failure exists. [DrainController.abort] needs exactly
 * that: the escalation threshold is measured from this instant, and the escalation
 * decides the wording of the message that `recordFailure` is then given. Deriving
 * it twice is how the anchor and the count come to disagree about the same event.
 *
 * A different [reason] starts over, because it is a different problem — the same
 * discrimination `recordFailure` makes for [FailureStatus.attempts].
 */
internal fun FailureStatus?.firstOccurrenceOf(
    reason: FailureReason,
    now: Instant,
): Instant = if (this != null && this.reason == reason) occurredAt else now

/**
 * The same bookkeeping for a drain that is blocked rather than failing.
 *
 * [DrainBlock.since] keeps pointing at the *first* pass that found this block, so
 * "waiting since 19:40" survives every re-check, and [DrainBlock.observations]
 * counts the passes that have looked. Together they are what tells an operator
 * the loop is still watching rather than wedged — the same job
 * [FailureStatus.attempts] does for a failure, which is why the two are shaped
 * alike. A different reason starts over, because it is a different wait.
 *
 * Kept beside [recordFailure] rather than folded into it. They take different
 * types, they carry different fields, and a single function over both would need
 * the caller to say which one it meant — which is the discrimination the split
 * exists to remove.
 */
internal fun recordBlock(
    reason: DrainBlockReason,
    message: String,
    now: Instant,
    previous: DrainBlock?,
): DrainBlock =
    if (previous != null && previous.reason == reason) {
        DrainBlock(
            reason = reason,
            message = message,
            since = previous.since,
            observations = previous.observations + 1,
        )
    } else {
        DrainBlock(reason = reason, message = message, since = now, observations = 1)
    }

/**
 * How a [NodeException] is classified on observed status.
 *
 * The operation decides the reason, and [NodeException.retryable] — which the
 * node implementation already decided — decides the class. Nothing here
 * re-inspects a transport error, because nothing above the [Node] boundary is
 * allowed to know what transport there is.
 */
internal fun NodeException.asFailureReason(): FailureReason =
    when (operation) {
        NodeOperation.STATUS -> FailureReason.NODE_UNAVAILABLE

        NodeOperation.OBSERVE -> FailureReason.RUNTIME_UNREACHABLE

        NodeOperation.IMAGE -> FailureReason.IMAGE_PULL_FAILED

        NodeOperation.CREATE -> FailureReason.CONTAINER_CREATE_FAILED

        NodeOperation.START -> FailureReason.CONTAINER_START_FAILED

        NodeOperation.EXEC -> FailureReason.DRAIN_STALLED

        // Deliberately *not* `PROXY_CONTROL_UNREACHABLE`. This mapping is about
        // what a generic node call failing means, and the endpoint channel is not
        // reserved for the proxy plugin. Whoever knows which protocol it was
        // speaking classifies it — `ControlChannel` translates the proxy case at
        // its own edge, exactly as the `Node` boundary requires of everything else.
        NodeOperation.ENDPOINT -> FailureReason.RUNTIME_UNREACHABLE

        // Present to keep this total, and **not** expected to be reached. A console
        // command is an operator's ad-hoc action, not a reconcile step: it arrives
        // through the API and its failure goes back to that caller as an HTTP
        // error. Nothing in the loop issues one, so nothing in the loop records
        // one here.
        //
        // The distinction is worth the comment because getting it wrong is
        // expensive in one direction: an operator's typo, or a server that refuses
        // a command while running perfectly, must never land on observed status.
        // That would make a report into a reason to act on a container — the thing
        // `AttentionTest` exists to prevent — and would mark a healthy server
        // failed for the duration of a mistyped word.
        NodeOperation.CONSOLE -> FailureReason.RUNTIME_UNREACHABLE

        NodeOperation.STOP -> FailureReason.DRAIN_STALLED

        NodeOperation.REMOVE -> FailureReason.RUNTIME_UNREACHABLE
    }

internal fun NodeException.asFailureClass(): FailureClass =
    if (retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT

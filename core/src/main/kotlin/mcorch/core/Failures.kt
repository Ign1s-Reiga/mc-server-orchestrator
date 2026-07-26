package mcorch.core

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
            occurredAt = previous.occurredAt,
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
        NodeOperation.STOP -> FailureReason.DRAIN_STALLED
        NodeOperation.REMOVE -> FailureReason.RUNTIME_UNREACHABLE
    }

internal fun NodeException.asFailureClass(): FailureClass =
    if (retryable) FailureClass.RETRYABLE else FailureClass.PERMANENT

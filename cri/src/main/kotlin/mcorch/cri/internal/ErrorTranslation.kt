package mcorch.cri.internal

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mcorch.cri.CriException
import mcorch.cri.CriOperation
import mcorch.cri.CriStatusCode

/**
 * The single place a gRPC failure becomes a typed Kotlin failure.
 *
 * The mapping is by status code, not by message text. containerd's own error
 * vocabulary (`errdefs.ErrNotFound`, `ErrAlreadyExists`, `ErrUnavailable`, …) is
 * defined to correspond to the gRPC codes of the same name, so the code is the
 * stable signal; descriptions are free-form and change between releases.
 */
internal fun translateStatus(
    operation: CriOperation,
    status: Status,
    cause: Throwable,
): CriException {
    val description = status.description ?: cause.message ?: "no description"
    return when (status.code) {
        Status.Code.UNAVAILABLE -> {
            CriException.Unavailable(operation, description, cause)
        }

        Status.Code.DEADLINE_EXCEEDED -> {
            CriException.Timeout(operation, description, cause)
        }

        Status.Code.RESOURCE_EXHAUSTED -> {
            CriException.ResourceExhausted(operation, description, cause)
        }

        Status.Code.ABORTED -> {
            CriException.Aborted(operation, description, cause)
        }

        Status.Code.NOT_FOUND -> {
            CriException.NotFound(operation, description, cause)
        }

        Status.Code.ALREADY_EXISTS -> {
            CriException.AlreadyExists(operation, description, cause)
        }

        Status.Code.FAILED_PRECONDITION -> {
            CriException.FailedPrecondition(operation, description, cause)
        }

        Status.Code.UNIMPLEMENTED -> {
            CriException.Unimplemented(operation, description, cause)
        }

        Status.Code.CANCELLED -> {
            CriException.Cancelled(operation, description, cause)
        }

        Status.Code.INVALID_ARGUMENT -> {
            CriException.InvalidArgument(operation, CriStatusCode.INVALID_ARGUMENT, description, cause)
        }

        Status.Code.OUT_OF_RANGE -> {
            CriException.InvalidArgument(operation, CriStatusCode.OUT_OF_RANGE, description, cause)
        }

        Status.Code.PERMISSION_DENIED -> {
            CriException.PermissionDenied(operation, CriStatusCode.PERMISSION_DENIED, description, cause)
        }

        Status.Code.UNAUTHENTICATED -> {
            CriException.PermissionDenied(operation, CriStatusCode.UNAUTHENTICATED, description, cause)
        }

        // UNKNOWN / INTERNAL / DATA_LOSS: containerd could not classify it, so
        // neither can we. Retryable — see CriException.RuntimeFailure.
        Status.Code.UNKNOWN -> {
            CriException.RuntimeFailure(operation, CriStatusCode.UNKNOWN, description, cause)
        }

        Status.Code.INTERNAL -> {
            CriException.RuntimeFailure(operation, CriStatusCode.INTERNAL, description, cause)
        }

        Status.Code.DATA_LOSS -> {
            CriException.RuntimeFailure(operation, CriStatusCode.DATA_LOSS, description, cause)
        }

        // OK never reaches here, and a future gRPC code is as unclassified as UNKNOWN.
        else -> {
            CriException.RuntimeFailure(operation, CriStatusCode.UNRECOGNISED, description, cause)
        }
    }
}

/**
 * Runs [block] and converts anything gRPC throws into a [CriException].
 *
 * Cancellation is deliberately not translated. If the calling coroutine was
 * cancelled, grpc-kotlin cancels the RPC and the failure surfaces either as a
 * `CancellationException` or as a `CANCELLED` status; [ensureActive] turns the
 * second case back into the first, so a cancelled caller always sees
 * cancellation and structured concurrency is not broken. A `CANCELLED` that
 * arrives while the caller is still active really did come from containerd, and
 * becomes [CriException.Cancelled].
 */
internal suspend inline fun <T> translatingErrors(
    operation: CriOperation,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: StatusException) {
        currentCoroutineContext().ensureActive()
        throw translateStatus(operation, e.status, e)
    } catch (e: StatusRuntimeException) {
        currentCoroutineContext().ensureActive()
        throw translateStatus(operation, e.status, e)
    }

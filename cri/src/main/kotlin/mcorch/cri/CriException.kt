package mcorch.cri

/** The CRI operation a failure came from. Lets a caller tell a pull failure from a create failure. */
public enum class CriOperation {
    VERSION,
    RUNTIME_STATUS,

    PULL_IMAGE,
    IMAGE_STATUS,
    LIST_IMAGES,
    REMOVE_IMAGE,

    RUN_SANDBOX,
    STOP_SANDBOX,
    REMOVE_SANDBOX,
    SANDBOX_STATUS,
    LIST_SANDBOXES,

    CREATE_CONTAINER,
    START_CONTAINER,
    STOP_CONTAINER,
    REMOVE_CONTAINER,
    CONTAINER_STATUS,
    LIST_CONTAINERS,

    EXEC_SYNC,
    EXEC,
}

/**
 * The gRPC status code the runtime replied with, re-declared here so callers can
 * refine a decision without depending on `io.grpc`.
 *
 * [UNRECOGNISED] covers codes a future gRPC adds that this enum does not name.
 */
public enum class CriStatusCode {
    CANCELLED,
    UNKNOWN,
    INVALID_ARGUMENT,
    DEADLINE_EXCEEDED,
    NOT_FOUND,
    ALREADY_EXISTS,
    PERMISSION_DENIED,
    RESOURCE_EXHAUSTED,
    FAILED_PRECONDITION,
    ABORTED,
    OUT_OF_RANGE,
    UNIMPLEMENTED,
    INTERNAL,
    UNAVAILABLE,
    DATA_LOSS,
    UNAUTHENTICATED,
    UNRECOGNISED,
}

/**
 * Every failure crossing the `:cri` boundary. No `io.grpc.StatusException` ever
 * escapes this module.
 *
 * The reconcile loop branches on [retryable]:
 *
 * - `retryable == true`  — requeue with backoff. containerd is not up yet, the
 *   call timed out, the runtime is busy. Nothing about the desired state is
 *   wrong; try again later.
 * - `retryable == false` — surface on the server's observed status. The request
 *   as written cannot succeed: the image does not exist, the config is invalid,
 *   the container that should be there is gone.
 *
 * Use [operation] to report *which* step failed (pull versus create, say) and
 * [code] if a call site needs to be more specific than [retryable]. Prefer
 * matching on the subclass over matching on [code].
 *
 * Cancellation is never reported here. A cancelled coroutine sees
 * `kotlinx.coroutines.CancellationException`, as structured concurrency
 * requires; only a *server-side* cancellation surfaces, as [Cancelled].
 */
public sealed class CriException(
    /** The operation that failed. */
    public val operation: CriOperation,
    /** The status code containerd replied with. */
    public val code: CriStatusCode,
    message: String,
    cause: Throwable?,
) : Exception(message, cause) {
    /** `true` when requeueing with backoff can plausibly succeed; `false` when the request itself is wrong. */
    public abstract val retryable: Boolean

    /** containerd is unreachable — not started, socket missing, connection refused, shutting down. Retryable. */
    public class Unavailable(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.UNAVAILABLE, message, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * The per-call deadline elapsed before containerd answered. Retryable.
     *
     * Note this is the *transport* deadline, not a container stop grace period
     * and not an `ExecSync` command timeout — those are separate parameters and
     * a command that outruns its own timeout also lands here.
     */
    public class Timeout(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.DEADLINE_EXCEEDED, message, cause) {
        override val retryable: Boolean get() = true
    }

    /** The runtime is out of some resource (disk, inodes, memory, gRPC quota). Retryable. */
    public class ResourceExhausted(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.RESOURCE_EXHAUSTED, message, cause) {
        override val retryable: Boolean get() = true
    }

    /** Concurrency conflict inside the runtime — a lock, or a competing operation on the same object. Retryable. */
    public class Aborted(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.ABORTED, message, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * containerd failed in a way it did not classify: `UNKNOWN`, `INTERNAL` or
     * `DATA_LOSS`. Retryable.
     *
     * Treated as retryable on purpose. containerd reports genuinely transient
     * conditions this way — snapshotter contention, a registry hiccup during an
     * image pull — and a bounded backoff recovers from those. It is the one
     * bucket where the classification is a judgement call, so it has its own
     * type: a caller that wants a tighter retry budget for "we do not know what
     * went wrong" can match on it specifically.
     */
    public class RuntimeFailure(
        operation: CriOperation,
        code: CriStatusCode,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, code, message, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * The object does not exist: an unknown container/sandbox ID, or an image
     * that is not present and could not be resolved. Not retryable.
     *
     * On a status or stop call this is the normal, idempotent answer to "is it
     * still there" — treat it as observed absence, not as an error to report.
     */
    public class NotFound(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.NOT_FOUND, message, cause) {
        override val retryable: Boolean get() = false
    }

    /**
     * The object is already there. Not retryable, and usually not a problem:
     * for an idempotent reconcile pass, look it up and adopt it rather than
     * creating a second one.
     */
    public class AlreadyExists(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.ALREADY_EXISTS, message, cause) {
        override val retryable: Boolean get() = false
    }

    /** The request as written is rejected — bad config, bad field. Not retryable; fix the definition. */
    public class InvalidArgument(
        operation: CriOperation,
        code: CriStatusCode,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, code, message, cause) {
        override val retryable: Boolean get() = false
    }

    /**
     * The runtime is in the wrong state for this call — e.g. exec against a
     * container that is not running. Not retryable *by this call*; the caller
     * has to change something first.
     */
    public class FailedPrecondition(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.FAILED_PRECONDITION, message, cause) {
        override val retryable: Boolean get() = false
    }

    /** Rejected by containerd's authz, or by the socket's file permissions. Not retryable. */
    public class PermissionDenied(
        operation: CriOperation,
        code: CriStatusCode,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, code, message, cause) {
        override val retryable: Boolean get() = false
    }

    /** The runtime does not implement this RPC — a CRI/containerd version mismatch. Not retryable. */
    public class Unimplemented(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.UNIMPLEMENTED, message, cause) {
        override val retryable: Boolean get() = false
    }

    /**
     * containerd cancelled the call from its side. Not retryable without
     * understanding why.
     *
     * Cancelling the *caller's* coroutine does not produce this — that raises
     * `CancellationException` unchanged.
     */
    public class Cancelled(
        operation: CriOperation,
        message: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.CANCELLED, message, cause) {
        override val retryable: Boolean get() = false
    }

    override val message: String
        get() {
            val classification = if (retryable) "retryable" else "permanent"
            return "${operation.name} failed (${code.name}, $classification): ${super.message}"
        }
}

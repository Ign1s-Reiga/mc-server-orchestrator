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
    ;

    /**
     * Whether this operation's **request** carries secret material, so that a
     * failure description from the runtime must not be repeated anywhere.
     *
     * This is about the request, not the reply. A runtime's error text is
     * free-form, and Go's `fmt.Errorf("...: %+v", config)` habit means a
     * rejected request can come back with the request in it. That is a promise
     * about a third party's error strings, which is not a promise anyone can
     * make — so the operations whose requests hold a secret are enumerated here
     * and their descriptions are simply never logged.
     *
     * - [PULL_IMAGE] carries `AuthConfig`, whose `password`, `auth`,
     *   `identity_token` and `registry_token` the CRI proto *itself* marks
     *   `debug_redact = true`. Upstream has already said this must not appear in
     *   debug output.
     * - [CREATE_CONTAINER] carries `ContainerConfig.envs`. That is where the
     *   RCON password is put, and it is the only route by which the Velocity
     *   forwarding secret ever reaches a container (CLAUDE.md invariant 4).
     * - [RUN_SANDBOX] carries the `PodSandboxConfig` that `CreateContainer` is
     *   then required to hand back verbatim, so it is on the same footing.
     *
     * **[EXEC_SYNC] and [EXEC] are deliberately absent.** Their requests carry
     * an argv, and no call site puts a credential in one — the RCON password
     * reaches `rcon-cli` through the container's environment, not its arguments.
     * Their descriptions are also the single most useful diagnostic this client
     * produces: `failed to exec in container: timeout 10s exceeded` is what
     * distinguishes a slow command from a sick node. Add them here the day a
     * call site passes a secret as an argument, and not before.
     *
     * Kept as one exhaustive `when` rather than a set so that adding an RPC to
     * this enum will not compile until someone has decided which side of the
     * line it falls on.
     */
    public val requestMayCarrySecrets: Boolean
        get() =
            when (this) {
                PULL_IMAGE, CREATE_CONTAINER, RUN_SANDBOX -> true

                VERSION,
                RUNTIME_STATUS,
                IMAGE_STATUS,
                LIST_IMAGES,
                REMOVE_IMAGE,
                STOP_SANDBOX,
                REMOVE_SANDBOX,
                SANDBOX_STATUS,
                LIST_SANDBOXES,
                START_CONTAINER,
                STOP_CONTAINER,
                REMOVE_CONTAINER,
                CONTAINER_STATUS,
                LIST_CONTAINERS,
                EXEC_SYNC,
                EXEC,
                -> false
            }
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
    /**
     * The runtime's own account of what went wrong, undecorated.
     *
     * [message] is this with the operation and classification prefixed, which is
     * the right thing to log or record. This is here for a caller that wants to
     * quote containerd without also quoting us.
     */
    public val description: String,
    cause: Throwable?,
) : Exception(description, cause) {
    /** `true` when requeueing with backoff can plausibly succeed; `false` when the request itself is wrong. */
    public abstract val retryable: Boolean

    /** containerd is unreachable — not started, socket missing, connection refused, shutting down. Retryable. */
    public class Unavailable(
        operation: CriOperation,
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.UNAVAILABLE, description, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * A deadline elapsed before the call produced a result. Retryable.
     *
     * **Two very different things arrive here, and [commandTimeout] is what tells
     * them apart.** Either this client gave up on a runtime that was not
     * answering, or the runtime answered promptly to say that a timeout the
     * *caller* asked for had run out. The gRPC code is `DEADLINE_EXCEEDED` in
     * both cases, so nothing downstream can tell them apart from the code alone —
     * and the difference is the difference between "the node may be sick" and
     * "the node is fine, the command was slow".
     */
    public class Timeout(
        operation: CriOperation,
        description: String,
        cause: Throwable? = null,
        /**
         * True when the runtime itself stopped the operation at a timeout the
         * caller supplied, and false when this client's transport deadline
         * elapsed.
         *
         * Only [CriOperation.EXEC_SYNC] can set it: that is the one call that
         * carries a caller-supplied timeout the runtime enforces and reports as
         * an error. A true here says nothing whatever about the node's health.
         */
        public val commandTimeout: Boolean = false,
    ) : CriException(operation, CriStatusCode.DEADLINE_EXCEEDED, description, cause) {
        override val retryable: Boolean get() = true
    }

    /** The runtime is out of some resource (disk, inodes, memory, gRPC quota). Retryable. */
    public class ResourceExhausted(
        operation: CriOperation,
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.RESOURCE_EXHAUSTED, description, cause) {
        override val retryable: Boolean get() = true
    }

    /** Concurrency conflict inside the runtime — a lock, or a competing operation on the same object. Retryable. */
    public class Aborted(
        operation: CriOperation,
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.ABORTED, description, cause) {
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
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, code, description, cause) {
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
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.NOT_FOUND, description, cause) {
        override val retryable: Boolean get() = false
    }

    /**
     * The object is already there. Not retryable, and usually not a problem:
     * for an idempotent reconcile pass, look it up and adopt it rather than
     * creating a second one.
     */
    public class AlreadyExists(
        operation: CriOperation,
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.ALREADY_EXISTS, description, cause) {
        override val retryable: Boolean get() = false
    }

    /** The request as written is rejected — bad config, bad field. Not retryable; fix the definition. */
    public class InvalidArgument(
        operation: CriOperation,
        code: CriStatusCode,
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, code, description, cause) {
        override val retryable: Boolean get() = false
    }

    /**
     * The runtime is in the wrong state for this call — e.g. exec against a
     * container that is not running. Not retryable *by this call*; the caller
     * has to change something first.
     */
    public class FailedPrecondition(
        operation: CriOperation,
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.FAILED_PRECONDITION, description, cause) {
        override val retryable: Boolean get() = false
    }

    /** Rejected by containerd's authz, or by the socket's file permissions. Not retryable. */
    public class PermissionDenied(
        operation: CriOperation,
        code: CriStatusCode,
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, code, description, cause) {
        override val retryable: Boolean get() = false
    }

    /** The runtime does not implement this RPC — a CRI/containerd version mismatch. Not retryable. */
    public class Unimplemented(
        operation: CriOperation,
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.UNIMPLEMENTED, description, cause) {
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
        description: String,
        cause: Throwable? = null,
    ) : CriException(operation, CriStatusCode.CANCELLED, description, cause) {
        override val retryable: Boolean get() = false
    }

    override val message: String
        get() {
            val classification = if (retryable) "retryable" else "permanent"
            return "${operation.name} failed (${code.name}, $classification): $description"
        }
}

package mcorch.core

import mcorch.schema.NodeName

/** Which node operation failed. Lets a caller tell an image failure from a create failure. */
public enum class NodeOperation {
    STATUS,
    OBSERVE,
    IMAGE,
    CREATE,
    START,
    EXEC,
    STOP,
    REMOVE,
}

/**
 * Every failure crossing the [Node] boundary.
 *
 * This type exists so the reconcile loop never sees a `mcorch.cri` exception. A
 * loop that pattern-matches on a CRI status code has assumed the node is a
 * local containerd, and a remote node implementation — which fails with
 * connection errors of an entirely different shape — could not satisfy the same
 * call sites. Implementations translate their transport's failures into these
 * once, at their own edge.
 *
 * Branch on [retryable]:
 *
 * - `true` — requeue with backoff. The node is not up yet, the call timed out,
 *   the runtime is busy. Nothing about the desired state is wrong.
 * - `false` — surface on the server's observed status and stop retrying. The
 *   request as written cannot succeed.
 */
public sealed class NodeException(
    public val node: NodeName,
    public val operation: NodeOperation,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    public abstract val retryable: Boolean

    /** The node could not be reached at all. Retryable. */
    public class Unreachable(
        node: NodeName,
        operation: NodeOperation,
        message: String,
        cause: Throwable? = null,
    ) : NodeException(node, operation, message, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * The call did not answer in time.
     *
     * Retryable as a *call*, but note what it does not tell you: a save that
     * timed out has not been confirmed, and a timeout is never a reason to stop
     * a container (`failure-modes.md` item 1).
     */
    public class Timeout(
        node: NodeName,
        operation: NodeOperation,
        message: String,
        cause: Throwable? = null,
    ) : NodeException(node, operation, message, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * The node is out of some resource, lost an internal race, or failed in a
     * way it could not classify. Retryable with backoff.
     */
    public class Busy(
        node: NodeName,
        operation: NodeOperation,
        message: String,
        cause: Throwable? = null,
    ) : NodeException(node, operation, message, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * The object is not there. Not retryable, and often not a problem: on an
     * observation it means the workload is gone, which is an answer rather than
     * an error.
     */
    public class NotFound(
        node: NodeName,
        operation: NodeOperation,
        message: String,
        cause: Throwable? = null,
    ) : NodeException(node, operation, message, cause) {
        override val retryable: Boolean get() = false
    }

    /**
     * The request as written is refused — an image that does not exist, an
     * invalid configuration, a permission denied, or a call made against a
     * workload in the wrong state. Not retryable; something has to change
     * first.
     */
    public class Rejected(
        node: NodeName,
        operation: NodeOperation,
        message: String,
        cause: Throwable? = null,
    ) : NodeException(node, operation, message, cause) {
        override val retryable: Boolean get() = false
    }

    override val message: String
        get() {
            val classification = if (retryable) "retryable" else "permanent"
            return "node `$node`: ${operation.name} failed ($classification): ${super.message}"
        }
}

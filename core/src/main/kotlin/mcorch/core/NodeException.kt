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

    /** An HTTP call to a port inside a workload — the proxy control channel. */
    ENDPOINT,
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
     *
     * **Two different things arrive here and [commandTimeout] separates them.**
     * Either the node did not answer, or it answered promptly to say that a
     * command *the caller asked it to run* outran the timeout the caller gave
     * it. Only the first says anything about the node's health, and conflating
     * them once reported a healthy runtime as unreachable for a Paper server
     * that was merely still generating its world.
     */
    public class Timeout(
        node: NodeName,
        operation: NodeOperation,
        message: String,
        cause: Throwable? = null,
        /**
         * True when the node answered and the *command* ran out of time; false
         * when the node itself did not answer.
         *
         * Only [NodeOperation.EXEC] can set it — that is the one call carrying a
         * caller-supplied timeout the node enforces on the caller's behalf. A
         * true here is a statement about the workload, not about the node, and a
         * caller that reads it as "the runtime is unreachable" is wrong.
         *
         * It stays a boolean rather than a separate subclass on purpose: it does
         * not change [retryable], every existing call site's decision is still
         * correct without consulting it, and a distributed node implementation
         * has the same two cases to report — its agent not answering, and a
         * command its agent ran overrunning.
         */
        public val commandTimeout: Boolean = false,
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

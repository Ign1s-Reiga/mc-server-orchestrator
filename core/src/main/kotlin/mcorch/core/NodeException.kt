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
 * Whether a failed [Node] call put anything on the wire.
 *
 * The second question every audit of this loop has had to ask about a node
 * failure, after "may I retry it". A drain compensates for side effects that
 * outlive the call that made them — a `SIGTERM` already delivered, a
 * `save-all flush` the server may have accepted — so what a caller needs to know
 * is whether the operation *happened at all*. [NodeException.retryable] does not
 * answer that and was never meant to: the two are independent, and a permanent
 * failure can follow a delivered request exactly as a retryable one can precede
 * an undelivered one.
 *
 * Before this existed the answer was re-derived at each call site from whatever
 * happened to correlate there — `DrainStatus.stopDispatchedAt` for the stop path,
 * `failure is NodeException.Timeout` for the save path — and each derivation was
 * right at the site it was written for and wrong at the next one. The taxonomy is
 * the node's to state, because the node is the only thing that knows.
 *
 * There is deliberately no `SENT`. A caller that must not repeat a side effect
 * needs *proof of absence*, and every other case — the request left and the
 * runtime never answered, the runtime answered with an error that says nothing
 * about what it had already done, a failure this node could not classify — is the
 * same case from that caller's point of view. Splitting [UNKNOWN] would invite a
 * branch that treats a probable send differently from a certain one, which is the
 * reasoning this type exists to stop.
 */
public enum class NodeDispatch {
    /**
     * The node refused before issuing anything that could change anything.
     *
     * A caller may act as though the operation had never been attempted: no
     * request left this process for the runtime or the workload, so there is
     * nothing to compensate for and nothing that makes a second attempt a
     * repeat. Only an implementation may claim this, and only where it is
     * provable by construction — an argument refused before the call, a
     * precondition checked at the top of the method. A read that costs the far
     * side nothing does not disqualify it; a write of any kind does.
     */
    NOTHING_SENT,

    /**
     * The node cannot say, so a caller must assume the request landed.
     *
     * The default, and the safe direction on every path that has been examined:
     * re-sending a `save-all flush` costs one idempotent flush, while assuming a
     * dispatched stop was never dispatched re-admits players to a container in
     * shutdown. Anything an implementation has not deliberately classified
     * arrives here.
     */
    UNKNOWN,
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
 *
 * And on [dispatch] for the *other* question — whether anything was sent. The two
 * are orthogonal by design and neither may be derived from the other; see
 * [NodeDispatch].
 */
public sealed class NodeException(
    public val node: NodeName,
    public val operation: NodeOperation,
    message: String,
    cause: Throwable? = null,
    /**
     * Whether this failure left anything on the wire. See [NodeDispatch].
     *
     * A constructor parameter rather than an abstract member, because it is a
     * property of the *call* and not of the kind of failure: the same [Rejected]
     * is undispatched when it comes from an argument check and dispatched when
     * the runtime refuses one, so no subclass can answer it for itself.
     *
     * **No default here.** Each subclass defaults it to [NodeDispatch.UNKNOWN] and
     * this parameter is required, so those five defaults are the whole of the
     * rule and a base-class default cannot sit behind them saying something
     * different. That is not tidiness: a default here would be unreachable — every
     * subclass passes the parameter on — so it would be a line stating the safe
     * direction that could be edited to state the unsafe one with nothing
     * observing the change. The mutation that has to be visible is a *subclass*
     * default flipped to [NodeDispatch.NOTHING_SENT], and `NodeDispatchTest` sees
     * exactly that.
     */
    public val dispatch: NodeDispatch,
) : RuntimeException(message, cause) {
    public abstract val retryable: Boolean

    /** The node could not be reached at all. Retryable. */
    public class Unreachable(
        node: NodeName,
        operation: NodeOperation,
        message: String,
        cause: Throwable? = null,
        dispatch: NodeDispatch = NodeDispatch.UNKNOWN,
    ) : NodeException(node, operation, message, cause, dispatch) {
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
        dispatch: NodeDispatch = NodeDispatch.UNKNOWN,
    ) : NodeException(node, operation, message, cause, dispatch) {
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
        dispatch: NodeDispatch = NodeDispatch.UNKNOWN,
    ) : NodeException(node, operation, message, cause, dispatch) {
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
        dispatch: NodeDispatch = NodeDispatch.UNKNOWN,
    ) : NodeException(node, operation, message, cause, dispatch) {
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
        dispatch: NodeDispatch = NodeDispatch.UNKNOWN,
    ) : NodeException(node, operation, message, cause, dispatch) {
        override val retryable: Boolean get() = false
    }

    override val message: String
        get() {
            val classification = if (retryable) "retryable" else "permanent"
            return "node `$node`: ${operation.name} failed ($classification): ${super.message}"
        }
}

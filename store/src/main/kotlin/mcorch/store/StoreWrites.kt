package mcorch.store

import mcorch.schema.ResourceName

/**
 * What the store must find before a write is allowed to land.
 *
 * This is the whole concurrency-control story, and it is deliberately
 * compare-and-swap rather than an interactive `transaction { }` block. A
 * distributed backend cannot honestly offer a caller-held transaction across a
 * network — but every candidate backend (etcd, Consul, a SQL database, this
 * SQLite file) can compare a version and swap. Anything the reconcile loop needs
 * to do atomically is therefore expressed as a single call carrying the version
 * it read, and the store either applies it or reports a [WriteOutcome.Conflict].
 */
public sealed interface Precondition {
    /** No check. The write lands whatever is there. */
    public data object None : Precondition

    /** The write lands only if nothing is stored under the name. */
    public data object Absent : Precondition

    /** The write lands only if what is stored is still at [resourceVersion]. */
    public data class AtVersion(
        val resourceVersion: ResourceVersion,
    ) : Precondition
}

/** Why a write did not land. */
public enum class ConflictReason {
    /** [Precondition.Absent] was asked for and something is already stored. */
    ALREADY_EXISTS,

    /** [Precondition.AtVersion] was asked for and the stored version has moved on. */
    VERSION_MISMATCH,

    /** Nothing is stored under the name. */
    NOT_FOUND,

    /**
     * A delete has been requested for this name and the loop has not finished with
     * it. The name cannot be written again until [Store.purge] completes: creating
     * a replacement while the old container may still have players on it is the
     * exact mistake the drain protocol exists to prevent.
     */
    TERMINATING,

    /**
     * [Store.purge] was called on a definition that has not been deleted. Purging a
     * live definition would leave a running container with nothing describing it.
     */
    NOT_DELETED,

    /**
     * The name is already held by a different [mcorch.schema.ServerKind]. Changing
     * kind in place is a recreate; delete and purge the old one first.
     */
    KIND_MISMATCH,

    /**
     * A status write named the definition version the pass acted on, and the stored
     * definition has moved on since. The observation describes a spec the operator
     * has already replaced, so recording it would make the server look settled when
     * it is not.
     */
    DEFINITION_CHANGED,
}

/**
 * The result of a write. A conflict is an ordinary value, not an exception: it is
 * an expected outcome the reconcile loop handles by re-reading and requeueing.
 * Storage *failures* are [StoreException]s, and they are a different thing.
 */
public sealed interface WriteOutcome<out T> {
    public data class Applied<out T>(
        val value: T,
    ) : WriteOutcome<T>

    public data class Conflict(
        val name: ResourceName,
        val reason: ConflictReason,
        /**
         * The current version of whatever the conflict was about, so a caller can act
         * without a second read. That is the stored object's version for
         * [ConflictReason.VERSION_MISMATCH] and [ConflictReason.ALREADY_EXISTS], and the
         * *definition's* version for [ConflictReason.DEFINITION_CHANGED] — which is
         * exactly what the next pass has to read against. Null when nothing is stored.
         */
        val currentResourceVersion: ResourceVersion?,
    ) : WriteOutcome<Nothing>
}

/** The written value, or null if the write conflicted. */
public fun <T> WriteOutcome<T>.valueOrNull(): T? =
    when (this) {
        is WriteOutcome.Applied -> value
        is WriteOutcome.Conflict -> null
    }

/**
 * The written value, or [StoreConflictException]. For callers that treat a
 * conflict as exceptional — the API server handling a request, or a test. The
 * reconcile loop should match on [WriteOutcome] instead and requeue.
 */
public fun <T> WriteOutcome<T>.getOrThrow(): T =
    when (this) {
        is WriteOutcome.Applied -> value
        is WriteOutcome.Conflict -> throw StoreConflictException(this)
    }

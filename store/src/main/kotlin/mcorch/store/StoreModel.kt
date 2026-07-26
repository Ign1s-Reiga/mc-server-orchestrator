package mcorch.store

import mcorch.schema.ResourceName
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerStatus
import java.time.Instant

/**
 * An opaque optimistic-concurrency token for one stored object.
 *
 * The only things a caller may do with one are hold it, compare it for equality,
 * and hand it back as a [Precondition]. It is deliberately *not* ordered and not
 * a number: the embedded store derives it from a local sequence, a distributed
 * store would derive it from whatever its backend calls a revision, and neither
 * shape may leak into code that reads it.
 */
@JvmInline
public value class ResourceVersion(
    public val token: String,
) {
    init {
        require(token.isNotEmpty()) { "resource version token must not be empty" }
    }

    override fun toString(): String = token
}

/**
 * A position in the desired-state change feed. Opaque for the same reasons as
 * [ResourceVersion]; obtain one from [Store.currentCursor] or from a
 * [ChangeFeed.Changes], never by constructing a value.
 */
@JvmInline
public value class StoreCursor(
    public val token: String,
) {
    init {
        require(token.isNotEmpty()) { "store cursor token must not be empty" }
    }

    override fun toString(): String = token
}

/**
 * A definition as the store holds it: what the operator declared, plus the
 * bookkeeping the store owns.
 *
 * [generation] changes only when the *spec* changes. [resourceVersion] changes
 * on every write. That split is what lets the reconcile loop tell "the operator
 * changed what they want" (act on it) from "something about this row was
 * rewritten" (do not re-run side effects) — see [Store.putDefinition].
 */
public data class StoredDefinition(
    val definition: ServerDefinition,
    val generation: Long,
    val resourceVersion: ResourceVersion,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * Set once a delete has been requested and not yet completed. The definition
     * stays readable on purpose: the loop needs the spec — the drain timeouts and
     * the stop grace period in particular — to shut the server down safely.
     */
    val deletedAt: Instant? = null,
) {
    val name: ResourceName get() = definition.metadata.name

    /** A delete was requested; the loop still has work to do before the name can be reused. */
    val terminating: Boolean get() = deletedAt != null
}

/**
 * An observation as the store holds it.
 *
 * [recordedAt] is the store's own clock, not the loop's: it answers "when did
 * anything last land in the store for this server", which is how a loop that has
 * stopped advancing is told apart from one that is idle. The loop's own view of
 * time stays in [ServerStatus.observedAt].
 */
public data class StoredStatus(
    val status: ServerStatus,
    val resourceVersion: ResourceVersion,
    val recordedAt: Instant,
) {
    val name: ResourceName get() = status.name
}

/**
 * Desired and observed state for one server, read as a single consistent
 * snapshot. Reading the two separately can tear — a definition written between
 * the two reads produces a pair that never existed — so the store never offers
 * that.
 *
 * A status cannot outlive its definition row: [Store.deleteDefinition] only
 * tombstones the definition, and [Store.purge] removes both together.
 */
public data class StoredServer(
    val definition: StoredDefinition,
    val status: StoredStatus? = null,
) {
    val name: ResourceName get() = definition.name

    /** The last observation reflects the current spec. False also when nothing has been observed yet. */
    val caughtUp: Boolean
        get() = status != null && status.status.observedGeneration == definition.generation
}

/** What happened to a definition. Observed state does not appear in the feed — see [Store.changesSince]. */
public enum class ChangeKind {
    /** Created, or written with a change to its spec or its metadata. */
    WRITTEN,

    /** A delete was requested. The definition is still readable and the server is still running. */
    DELETED,

    /** The definition and its status are gone. */
    PURGED,
}

/** One entry in the desired-state change feed. */
public data class ServerChange(
    val name: ResourceName,
    val kind: ChangeKind,
    val resourceVersion: ResourceVersion,
    val at: Instant,
)

/** The result of reading the change feed. */
public sealed interface ChangeFeed {
    public data class Changes(
        val changes: List<ServerChange>,
        /** Pass this back to continue. Advances even when [changes] is empty. */
        val cursor: StoreCursor,
        /** More changes were available than the requested limit. Read again immediately. */
        val more: Boolean,
    ) : ChangeFeed

    /**
     * The cursor is older than what the store still remembers. There is no way to
     * enumerate what was missed: discard it, call [Store.listServers] for a full
     * resync, and continue from [cursor].
     */
    public data class Expired(
        val cursor: StoreCursor,
    ) : ChangeFeed
}

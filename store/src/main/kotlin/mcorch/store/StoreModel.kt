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
    /**
     * Set when there is an observation stored for this server that the store
     * could not decode. [status] is then null, and the two together mean "an
     * observation exists and it cannot be read", which is emphatically not
     * "nothing has been observed yet" — see [neverObserved].
     *
     * Always [StatePart.OBSERVED]. A *definition* that will not decode cannot
     * produce a [StoredServer] at all — there would be nothing to put in
     * [definition] short of inventing it — so it surfaces as an
     * [UnreadableServer] in [ServerListing.unreadable] instead.
     */
    val unreadable: Unreadable? = null,
) {
    val name: ResourceName get() = definition.name

    /**
     * Nothing has been observed for this server yet.
     *
     * Deliberately not `status == null`. An observation the store cannot decode
     * also leaves [status] null, and a caller that reads the two the same way
     * re-runs a pass that already happened: it would re-issue the side effects of
     * a drain that is halfway through, against a server that still has players on
     * it. Whoever needs "there is no observation" wants this; whoever needs "I
     * cannot see the observation" wants [unreadable].
     */
    val neverObserved: Boolean get() = status == null && unreadable == null

    /**
     * The last observation reflects the current spec. False also when nothing has
     * been observed yet, and when the observation could not be decoded — an
     * unreadable observation is not evidence of anything.
     */
    val caughtUp: Boolean
        get() = status != null && status.status.observedGeneration == definition.generation
}

/** Which half of a server's stored state something is about. */
public enum class StatePart {
    /** What the operator declared. The definition. */
    DESIRED,

    /** What the reconcile loop last recorded. The status. */
    OBSERVED,
}

/**
 * A part of one server's stored state that the store holds but cannot decode.
 *
 * Returned as a value rather than raised, so a row the store cannot read costs
 * the one server it belongs to instead of every server in the same read.
 *
 * Deliberately not the underlying exception. A caller can act on the
 * classification and can show or log the [reason]; a stack trace out of whichever
 * backend produced it is not part of this interface, and holding one would make a
 * value type carry a snapshot of somebody's call stack.
 */
public data class Unreadable(
    /** Which half could not be read. */
    val part: StatePart,
    /**
     * Why, in a form that is safe to log and to show an operator. It names the
     * server and what about the stored form was rejected. Secrets never reach
     * this: they live in [SecretStore] and only their names are ever in state.
     */
    val reason: String,
    /**
     * Whether reading again could plausibly succeed.
     *
     * False for anything that failed to decode: the stored bytes will say the same
     * thing next time, and a caller that retries them forever never surfaces the
     * problem. An implementation must not turn a *retryable* failure into one of
     * these at all — it describes the read, not the record, and a caller handed it
     * as an annotation would write off a passing problem as a corrupt row
     * permanently. The embedded store enforces that by re-raising anything
     * retryable instead of attaching it, so in practice this is always false
     * there.
     *
     * The field exists because a backend that decodes somewhere else can honestly
     * disagree — "the thing that knows how to read this is unreachable, ask again"
     * is a real answer for a networked store, and it is not the same answer as
     * "this record is broken".
     */
    val retryable: Boolean,
)

/**
 * A server the store holds and whose *desired* state it cannot decode.
 *
 * There is no definition to act on, so this is not a [StoredServer] and nothing
 * can reconcile it. What it exists for is to keep the row from reading as
 * *absent*: the server was declared, it may well have a container running with
 * players on it, and anything that treats "not in the list" as "purged" —
 * a garbage collector, a dashboard's removal event — would otherwise report a
 * deletion that never happened.
 */
public data class UnreadableServer(
    /**
     * The stored name, exactly as the store holds it. Raw rather than a
     * [ResourceName] because the name itself can be the reason the row will not
     * read, and a type that cannot hold it would drop the only identifying thing
     * left.
     *
     * Null when the record has no name at all. That is not hypothetical: SQLite
     * permits NULL in a rowid table's primary key, so a row hand-written without
     * one is possible, and it is precisely the record nothing can refer to — it
     * cannot be fetched, reconciled or purged by name. Reporting it as `null` is
     * the honest answer; substituting a placeholder would invent an identity the
     * store does not have and contradict what this field promises.
     */
    val name: String?,
    /** What could not be decoded. Always [StatePart.DESIRED] here. */
    val unreadable: Unreadable,
) {
    /** The stored name as a [ResourceName], or null when it is absent or not a valid one. */
    val resourceName: ResourceName? get() = name?.let { ResourceName.of(it).getOrNull() }
}

/**
 * A listing that admits what it could not read.
 *
 * [servers] is everything a caller can act on, including servers whose
 * observation would not decode — those keep their definition and carry
 * [StoredServer.unreadable].
 *
 * [unreadable] is everything else: rows with no usable desired state. They are
 * reported rather than dropped because absence means something to callers, and
 * "the store could not read it" must never be delivered as "it is not there".
 *
 * The two lists come from one read, so a server is in exactly one of them and no
 * caller has to reconcile two snapshots taken at different moments.
 */
public data class ServerListing(
    val servers: List<StoredServer>,
    val unreadable: List<UnreadableServer> = emptyList(),
)

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

package mcorch.store

import mcorch.schema.DrainState
import mcorch.schema.ResourceName
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerStatus

/**
 * Desired and observed state for every server the orchestrator manages.
 *
 * One of the three distribution seams (see CLAUDE.md). Nothing about a storage
 * engine appears below: no connection, no transaction handle, no query language,
 * no assumption that a write is local or that there is one writer. The embedded
 * SQLite implementation is one implementation of this, and a networked one must
 * be droppable in behind it without a single call site changing.
 *
 * ## Who calls what
 *
 * The API server writes desired state ([putDefinition], [deleteDefinition]) and
 * reads observed state back ([getServer], [listServers]). The reconcile loop
 * reads desired state, reads the observation it recorded last pass, writes a new
 * one ([putStatus]), and completes deletes ([purge]). The loop never writes a
 * definition and the API server never writes a status.
 *
 * ## Finding work
 *
 * Two ways, and the loop is expected to use both.
 *
 * [listServers] is the authoritative one: a full read every resync period. It
 * cannot miss anything, and correctness rests on it alone.
 *
 * [changesSince] is the low-latency one: a cursor-based feed of desired-state
 * changes so an operator's edit is acted on in milliseconds rather than at the
 * next resync. It is an optimisation, and it is allowed to say
 * [ChangeFeed.Expired] and send the caller back to a full resync.
 *
 * The feed is a *pull*, not a `Flow`, on purpose. The reconcile loop owns its own
 * cadence, its own coalescing of repeated changes to one name, and its own
 * backoff; a `Flow` here would move that policy into the store, which is the
 * wrong place for it. Observed state is deliberately not in the feed either —
 * the loop is the only writer of observed state, so feeding it back would be a
 * self-loop.
 *
 * ## Concurrency
 *
 * Every write takes a [Precondition] and returns a [WriteOutcome]. That is the
 * only concurrency control there is, and it is enough: it covers "create if
 * absent", "replace exactly what I read", and (for observations) "only if the
 * definition I acted on is still current". See [Precondition] for why this is
 * compare-and-swap rather than an interactive transaction.
 *
 * Reads that span both halves of a server's state return a
 * consistent snapshot ([StoredServer]); callers never have to stitch two reads
 * together and risk a pair that never existed.
 *
 * ## Failures
 *
 * A conflict is a returned value. A failure is a [StoreException], already
 * classified retryable or permanent. Implementations must not swallow either.
 *
 * One kind of failure is *scoped* rather than raised: a stored record this build
 * cannot decode. That belongs to the one server it was stored for, so it comes
 * back attached to that server ([Unreadable]) and leaves the rest of the read
 * whole. A point read still raises it — see [getServer].
 *
 * ## Deadlines are bounded on the way out
 *
 * Every definition handed back by a read has passed through
 * [mcorch.schema.SpecBounds]: no duration that becomes a transport deadline
 * exceeds the widest value a reader would have accepted for it. **Every**
 * implementation owes this, not just the embedded one, and it is a read-side
 * guarantee on purpose — the stored record keeps whatever it holds, so nothing is
 * lost and a later, wider ceiling gives the declared value back.
 *
 * It is here rather than in the reconcile loop because a store is where a
 * definition that never came through a YAML reader enters the system: a
 * hand-edited row, a restored backup, a migration. Nothing else sees all of them,
 * and nothing else holds both halves of the `stopGracePeriod`/`saveTimeout` pair
 * at the moment either could be bounded. A caller that acts on a definition read
 * from here may assume every deadline on it is one it can afford to wait for; it
 * may not assume the same of a definition it built itself.
 *
 * A write echoes the definition it was given, unbounded — the caller has it
 * already, and pretending to have adjusted an argument is a worse answer than
 * saying what the next read will return. Read it back to see the bounded form.
 */
public interface Store : AutoCloseable {
    // ---------------------------------------------------------------- desired state

    /**
     * Writes a definition and returns it with the store's bookkeeping applied.
     *
     * Generation rules, which the reconcile loop's idempotency depends on:
     *
     * - A name that is not stored yet gets generation 1.
     * - A write whose spec differs from the stored spec increments the generation.
     * - A write whose spec is equal to the stored spec leaves the generation alone,
     *   however many times it is repeated. Re-applying the same file is a no-op, so
     *   `status.observedGeneration == generation` keeps meaning "the loop has caught
     *   up" instead of flickering forever.
     * - A write that changes nothing at all — same spec, same metadata — does not
     *   move the [ResourceVersion] and does not appear in the change feed either.
     * - Metadata that changes while the spec does not (labels, say) moves the
     *   [ResourceVersion] and appears in the feed, but does not touch the
     *   generation. It did not change what the operator asked to be running.
     *
     * Two integrity rules apply regardless of [precondition], because they are not
     * about races: a name held by another [mcorch.schema.ServerKind] conflicts with
     * [ConflictReason.KIND_MISMATCH], and a name awaiting [purge] conflicts with
     * [ConflictReason.TERMINATING].
     */
    public suspend fun putDefinition(
        definition: ServerDefinition,
        precondition: Precondition = Precondition.None,
    ): WriteOutcome<StoredDefinition>

    /**
     * Requests a delete. The definition is *tombstoned*, not removed: it stays
     * readable, [StoredDefinition.deletedAt] is set, and the change feed reports
     * [ChangeKind.DELETED].
     *
     * This is deliberately not "remove the row". The server may have players on it,
     * and the loop needs the spec it is about to drain against — the save timeout,
     * the stop grace period — which a removed row cannot supply. The row goes away
     * at [purge], once the loop has finished.
     *
     * Deleting an already-deleted definition is a no-op that reports success.
     */
    public suspend fun deleteDefinition(
        name: ResourceName,
        precondition: Precondition = Precondition.None,
    ): WriteOutcome<StoredDefinition>

    /**
     * Completes a delete: removes the definition and its status together, and frees
     * the name for reuse. Called by the reconcile loop once the drain finished and
     * the container is gone, never by the API server.
     *
     * Conflicts with [ConflictReason.NOT_DELETED] if [deleteDefinition] has not been
     * called for this name. Purging a live definition would orphan a running
     * container.
     *
     * That is the only guard there is, and the caller has to supply the rest.
     * Purging while a drain is still in flight throws away the record of which side
     * effects have already been issued, and the container carries on running with
     * nothing describing it — but the store cannot tell that a container is gone,
     * because it never sees a container. Whoever does see one owns that check.
     *
     * Purging a name that is not stored is a no-op that reports success, unless a
     * [Precondition.AtVersion] was given.
     */
    public suspend fun purge(
        name: ResourceName,
        precondition: Precondition = Precondition.None,
    ): WriteOutcome<Unit>

    // --------------------------------------------------------------- observed state

    /**
     * Records what the loop observed. Called every pass, so implementations must
     * treat this as the hot write path.
     *
     * Writing a status equal to the stored one is a no-op: the [ResourceVersion]
     * does not move. That keeps a settled server from generating write traffic and
     * keeps compare-and-swap callers from seeing spurious changes.
     *
     * [observedDefinition] is the anti-lost-update guard, and the reconcile loop
     * should always pass it. Give it the [StoredDefinition.resourceVersion] the
     * pass actually read. If the operator replaced the definition while the pass was
     * running, the write conflicts with [ConflictReason.DEFINITION_CHANGED] instead
     * of recording an observation of a spec that is no longer desired. The side
     * effects of that pass have already happened and the store cannot undo them —
     * what it can refuse to do is let the server *look* settled when it is not.
     *
     * A status may only be written for a name that has a definition row, including a
     * tombstoned one: a drain records progress after the delete request, and that
     * has to be durable.
     */
    public suspend fun putStatus(
        status: ServerStatus,
        precondition: Precondition = Precondition.None,
        observedDefinition: ResourceVersion? = null,
    ): WriteOutcome<StoredStatus>

    // ------------------------------------------------------------------------ reads

    /**
     * Desired and observed state for one server as a single snapshot, or null if
     * the name is unknown.
     *
     * Unlike the listings below, this one *fails* when either half of the row will
     * not decode, rather than handing back a snapshot with a hole in it. A caller
     * that named one server wants the answer for that server: null would say "it
     * is not there", and a half-read snapshot would say "there is no observation",
     * and both are answers to a question nobody asked.
     */
    public suspend fun getServer(name: ResourceName): StoredServer?

    /**
     * Every server, tombstoned ones included, as a single snapshot.
     *
     * The reconcile loop's resync read. Filtering is deliberately left to the
     * caller: at the scale this orchestrator targets, deciding what needs work is
     * reconcile policy, and policy in the store is policy in two places.
     *
     * ## One unreadable row costs one server
     *
     * Rows are decoded one at a time. A server whose *observation* will not decode
     * still has a definition the loop can act on, so it is in this list with
     * [StoredServer.unreadable] set and [StoredServer.status] null. The rest of the
     * list is intact. Failing the whole read instead — which this store used to do
     * — left the loop with nothing to reconcile and the dashboard with nothing to
     * show, on every pass, from one hand-edited row.
     *
     * A server whose *definition* will not decode has no [StoredServer] to be
     * marked on, and this read still fails for it. That is deliberate rather than
     * lazy: quietly returning a shorter list is indistinguishable from the server
     * having been purged, and callers do derive removal from absence. [listAll]
     * returns those rows as entries instead of as a failure, and is the read to use
     * anywhere absence has a meaning.
     */
    public suspend fun listServers(): List<StoredServer>

    /**
     * Servers whose last observation recorded a drain in one of [states].
     *
     * This one query is pushed down rather than filtered in the caller because of
     * what it is for: after a restart, the loop has to find drains that were in
     * flight and resume them from where they stopped, before it does anything else.
     * A drain that silently restarts from the beginning re-sends a save request and
     * loads a live server for nothing; one that is never picked up leaves players
     * on a server nobody is watching.
     *
     * Reads one row at a time on the same terms as [listServers]: the drain state a
     * row is selected by is stored beside the observation, so a server whose
     * observation will not decode is still *found* here — with the drain to resume
     * and nothing readable saying how far it got, which is exactly the case a
     * caller must be told about rather than left to infer from an empty list.
     *
     * An empty [states] returns nothing.
     */
    public suspend fun listByDrainState(states: Set<DrainState>): List<StoredServer>

    /**
     * [listServers], plus the rows whose desired state will not decode, as entries
     * rather than as a failure.
     *
     * One consistent read: every server is in exactly one of
     * [ServerListing.servers] and [ServerListing.unreadable].
     *
     * This is the read for any caller that gives absence a meaning — stopping a
     * container it can no longer find a definition for, telling an operator a
     * server was removed. With [listServers] such a caller cannot tell "gone" from
     * "unreadable" without also handling the failure; here it cannot miss it.
     *
     * The default implementation is for stores that have no undecodable row to
     * report — one holding objects in memory, say. It is honest rather than a
     * silent empty: an implementation that *could* hold one would have failed
     * [listServers] instead of hiding it.
     */
    public suspend fun listAll(): ServerListing = ServerListing(listServers())

    /** [listByDrainState] with the tolerance [listAll] adds to [listServers]. */
    public suspend fun listAllByDrainState(states: Set<DrainState>): ServerListing =
        ServerListing(listByDrainState(states))

    // ----------------------------------------------------------------- change feed

    /** The current end of the change feed. Read this before a full resync, then feed from it. */
    public suspend fun currentCursor(): StoreCursor

    /**
     * Desired-state changes after [cursor], oldest first, at most [limit] of them.
     *
     * A null [cursor] means "from the oldest change the store still remembers",
     * which is not the same as "from the beginning of time" — see
     * [ChangeFeed.Expired].
     */
    public suspend fun changesSince(
        cursor: StoreCursor?,
        limit: Int = DEFAULT_CHANGE_LIMIT,
    ): ChangeFeed

    public companion object {
        public const val DEFAULT_CHANGE_LIMIT: Int = 256
    }
}

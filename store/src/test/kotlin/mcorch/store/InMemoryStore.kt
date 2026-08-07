package mcorch.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mcorch.schema.DrainState
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerStatus
import mcorch.schema.SpecBounds
import mcorch.schema.StatusReconstruction
import mcorch.schema.VelocityProxyStatus
import java.time.Clock
import java.time.Instant

/**
 * A second [Store], in test sources only.
 *
 * It exists to keep [StoreConformanceSuite] honest. A suite with one
 * implementation behind it drifts into testing that implementation — the SQLite
 * store could grow a behaviour the interface never promised and the suite would
 * happily lock it in. This one shares no code with it: it holds objects in maps
 * rather than encoded documents, and it decides "did the spec change" by
 * comparing `spec` values rather than by comparing encoded bytes. When both pass
 * the same suite, the suite is describing the interface rather than SQLite.
 *
 * Deliberately in `src/test`: it is not a shortcut anyone can reach for in
 * production, and it makes no durability claim whatsoever.
 */
internal class InMemoryStore(
    private val clock: Clock = Clock.systemUTC(),
) : Store {
    private val mutex = Mutex()
    private val definitions = linkedMapOf<ResourceName, StoredDefinition>()
    private val statuses = linkedMapOf<ResourceName, StoredStatus>()
    private val changes = mutableListOf<ServerChange>()
    private var revision = 0L
    private var closed = false

    override suspend fun putDefinition(
        definition: ServerDefinition,
        precondition: Precondition,
    ): WriteOutcome<StoredDefinition> =
        guarded {
            val name = definition.metadata.name
            val existing = definitions[name]
            val now = clock.instant()

            if (existing == null) {
                checkPrecondition(name, precondition, null)?.let { return@guarded it }
                val stored =
                    StoredDefinition(
                        definition = definition,
                        generation = 1L,
                        resourceVersion = nextVersion(),
                        createdAt = now,
                        updatedAt = now,
                    )
                definitions[name] = stored
                record(name, ChangeKind.WRITTEN, stored.resourceVersion, now)
                return@guarded WriteOutcome.Applied(stored)
            }

            if (existing.deletedAt != null) {
                return@guarded WriteOutcome.Conflict(name, ConflictReason.TERMINATING, existing.resourceVersion)
            }
            if (existing.definition.kind != definition.kind) {
                return@guarded WriteOutcome.Conflict(name, ConflictReason.KIND_MISMATCH, existing.resourceVersion)
            }
            checkPrecondition(name, precondition, existing.resourceVersion)?.let { return@guarded it }

            val specChanged = existing.definition.spec != definition.spec
            val unchanged =
                !specChanged &&
                    existing.definition.metadata == definition.metadata &&
                    existing.definition.apiVersion == definition.apiVersion
            if (unchanged) return@guarded WriteOutcome.Applied(existing)

            val stored =
                existing.copy(
                    definition = definition,
                    generation = if (specChanged) existing.generation + 1 else existing.generation,
                    resourceVersion = nextVersion(),
                    updatedAt = now,
                )
            definitions[name] = stored
            record(name, ChangeKind.WRITTEN, stored.resourceVersion, now)
            WriteOutcome.Applied(stored)
        }

    override suspend fun deleteDefinition(
        name: ResourceName,
        precondition: Precondition,
    ): WriteOutcome<StoredDefinition> =
        guarded {
            val existing =
                definitions[name]
                    ?: return@guarded WriteOutcome.Conflict(name, ConflictReason.NOT_FOUND, null)
            checkPrecondition(name, precondition, existing.resourceVersion)?.let { return@guarded it }
            if (existing.deletedAt != null) return@guarded WriteOutcome.Applied(existing.bounded())

            val now = clock.instant()
            val stored = existing.copy(resourceVersion = nextVersion(), updatedAt = now, deletedAt = now)
            definitions[name] = stored
            record(name, ChangeKind.DELETED, stored.resourceVersion, now)
            WriteOutcome.Applied(stored.bounded())
        }

    override suspend fun purge(
        name: ResourceName,
        precondition: Precondition,
    ): WriteOutcome<Unit> =
        guarded {
            val existing = definitions[name]
            if (existing == null) {
                return@guarded if (precondition is Precondition.AtVersion) {
                    WriteOutcome.Conflict(name, ConflictReason.NOT_FOUND, null)
                } else {
                    WriteOutcome.Applied(Unit)
                }
            }
            checkPrecondition(name, precondition, existing.resourceVersion)?.let { return@guarded it }
            if (existing.deletedAt == null) {
                return@guarded WriteOutcome.Conflict(name, ConflictReason.NOT_DELETED, existing.resourceVersion)
            }
            definitions.remove(name)
            statuses.remove(name)
            record(name, ChangeKind.PURGED, nextVersion(), clock.instant())
            WriteOutcome.Applied(Unit)
        }

    override suspend fun putStatus(
        status: ServerStatus,
        precondition: Precondition,
        observedDefinition: ResourceVersion?,
    ): WriteOutcome<StoredStatus> =
        guarded {
            val name = status.name
            val definition =
                definitions[name]
                    ?: return@guarded WriteOutcome.Conflict(name, ConflictReason.NOT_FOUND, null)
            if (definition.definition.kind != status.kind) {
                return@guarded WriteOutcome.Conflict(name, ConflictReason.KIND_MISMATCH, definition.resourceVersion)
            }
            if (observedDefinition != null && observedDefinition != definition.resourceVersion) {
                return@guarded WriteOutcome.Conflict(
                    name,
                    ConflictReason.DEFINITION_CHANGED,
                    definition.resourceVersion,
                )
            }
            val existing = statuses[name]
            checkPrecondition(name, precondition, existing?.resourceVersion)?.let { return@guarded it }
            if (existing != null && existing.status == status) return@guarded WriteOutcome.Applied(existing)

            val stored = StoredStatus(status, nextVersion(), clock.instant())
            statuses[name] = stored
            WriteOutcome.Applied(stored)
        }

    override suspend fun getServer(name: ResourceName): StoredServer? =
        guarded { definitions[name]?.let { StoredServer(it.bounded(), statuses[name].reconstructed()) } }

    override suspend fun listServers(): List<StoredServer> =
        guarded {
            definitions.values.sortedBy { it.name.value }.map {
                StoredServer(it.bounded(), statuses[it.name].reconstructed())
            }
        }

    override suspend fun listByDrainState(states: Set<DrainState>): List<StoredServer> {
        if (states.isEmpty()) return emptyList()
        return guarded {
            definitions.values
                .sortedBy { it.name.value }
                .mapNotNull { definition ->
                    val status = statuses[definition.name] ?: return@mapNotNull null
                    // Selected on the *stored* state, served reconstructed. The
                    // projection a store filters on is the drain state, which no
                    // reconstruction here touches, so the two cannot disagree.
                    if (drainStateOf(status.status) in states) {
                        StoredServer(definition.bounded(), status.reconstructed())
                    } else {
                        null
                    }
                }
        }
    }

    override suspend fun currentCursor(): StoreCursor = guarded { StoreCursor(revision.toString()) }

    override suspend fun changesSince(
        cursor: StoreCursor?,
        limit: Int,
    ): ChangeFeed {
        require(limit > 0) { "change feed limit must be positive, found $limit" }
        return guarded {
            val from = cursor?.token?.toLongOrNull() ?: 0L
            val matching = changes.filter { it.resourceVersion.token.toLong() > from }
            val page = matching.take(limit)
            val next =
                page
                    .lastOrNull()
                    ?.resourceVersion
                    ?.token
                    ?.toLong() ?: maxOf(from, revision)
            ChangeFeed.Changes(
                changes = page,
                cursor = StoreCursor(next.toString()),
                more = matching.size > limit,
            )
        }
    }

    override fun close() {
        closed = true
    }

    private suspend fun <T> guarded(block: () -> T): T {
        if (closed) throw StoreException.Closed("the store has been closed")
        return mutex.withLock { block() }
    }

    /**
     * The bound [Store] promises on every definition it hands back.
     *
     * This store keeps objects rather than documents, so it has no decode to hang
     * the bound on — which is exactly why it is worth doing here. The guarantee
     * belongs to the interface, not to a codec, and an implementation that reached
     * it by accident of its encoding would be evidence of nothing. Applied where a
     * definition *leaves* the store, matching the embedded one: a write echoes its
     * argument, a read is bounded.
     */
    private fun StoredDefinition.bounded(): StoredDefinition {
        val bounded = SpecBounds.bound(definition)
        return if (bounded.wasClamped) copy(definition = bounded.definition) else this
    }

    /**
     * The side-effect records [Store] promises on every observation it hands back.
     *
     * [bounded]'s argument, for the other read-side guarantee. This store keeps
     * `DrainStatus` objects rather than documents, so "the key is absent" and "the
     * field is null" are the same thing here — which is the point. The rule is
     * stated on the decoded record and not on a document key, so an implementation
     * that never encodes anything owes it just as much as one that does, and a
     * suite that only asked the SQLite store would be testing a codec instead of
     * the interface.
     */
    private fun StoredStatus?.reconstructed(): StoredStatus? {
        val stored = this ?: return null
        val reconstructed = StatusReconstruction.reconstruct(stored.status)
        return if (reconstructed.wasReconstructed) stored.copy(status = reconstructed.status) else stored
    }

    private fun nextVersion(): ResourceVersion {
        revision += 1
        return ResourceVersion(revision.toString())
    }

    private fun record(
        name: ResourceName,
        kind: ChangeKind,
        version: ResourceVersion,
        at: Instant,
    ) {
        changes += ServerChange(name, kind, version, at)
    }

    private fun drainStateOf(status: ServerStatus): DrainState? =
        when (status) {
            is PaperServerStatus -> status.drain?.state
            is VelocityProxyStatus -> status.drain?.state
        }

    private fun checkPrecondition(
        name: ResourceName,
        precondition: Precondition,
        current: ResourceVersion?,
    ): WriteOutcome.Conflict? =
        when (precondition) {
            is Precondition.None -> {
                null
            }

            is Precondition.Absent -> {
                if (current == null) null else WriteOutcome.Conflict(name, ConflictReason.ALREADY_EXISTS, current)
            }

            is Precondition.AtVersion -> {
                when {
                    current == null -> {
                        WriteOutcome.Conflict(name, ConflictReason.NOT_FOUND, null)
                    }

                    current != precondition.resourceVersion -> {
                        WriteOutcome.Conflict(name, ConflictReason.VERSION_MISMATCH, current)
                    }

                    else -> {
                        null
                    }
                }
            }
        }
}

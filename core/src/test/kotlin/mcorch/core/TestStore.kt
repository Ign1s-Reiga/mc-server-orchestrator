package mcorch.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mcorch.schema.DrainState
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerStatus
import mcorch.store.ChangeFeed
import mcorch.store.ChangeKind
import mcorch.store.ConflictReason
import mcorch.store.Precondition
import mcorch.store.ResourceVersion
import mcorch.store.ServerChange
import mcorch.store.Store
import mcorch.store.StoreCursor
import mcorch.store.StoreException
import mcorch.store.StoredDefinition
import mcorch.store.StoredServer
import mcorch.store.StoredStatus
import mcorch.store.WriteOutcome
import java.time.Clock
import java.time.Instant

/**
 * A [Store] for the reconciler's tests.
 *
 * `:store` has its own in-memory implementation, but it lives in that module's
 * test sources and is not visible here — so this is a second one, written
 * against the same documented contract. It implements only what the loop
 * actually depends on, and it implements those parts exactly: generations move
 * on a spec change and not otherwise, an identical status write is a no-op, a
 * stale `observedDefinition` conflicts, and a purge of a live definition is
 * refused.
 *
 * [statusWrites] is what the idempotency test asserts on: a settled server must
 * not produce store traffic.
 */
internal class TestStore(
    private val clock: Clock = Clock.systemUTC(),
) : Store {
    private val mutex = Mutex()
    private val definitions = linkedMapOf<ResourceName, StoredDefinition>()
    private val statuses = linkedMapOf<ResourceName, StoredStatus>()
    private val changes = mutableListOf<ServerChange>()
    private var revision = 0L

    /** Status writes that actually landed. A no-op write does not count. */
    var statusWrites: Int = 0
        private set

    /** Every call to [putStatus], landed or not. */
    var statusPuts: Int = 0
        private set

    /** Injected failure for the next call, if any. */
    var nextFailure: StoreException? = null

    /**
     * Runs once, just before the next status write lands. Lets a test simulate
     * the API server replacing a definition while a pass is in flight.
     */
    var beforeStatusWrite: (suspend () -> Unit)? = null

    override suspend fun putDefinition(
        definition: ServerDefinition,
        precondition: Precondition,
    ): WriteOutcome<StoredDefinition> =
        guarded {
            val name = definition.metadata.name
            val now = clock.instant()
            val existing = definitions[name]
            val stored =
                if (existing == null) {
                    StoredDefinition(definition, 1L, nextVersion(), now, now)
                } else {
                    val specChanged = existing.definition.spec != definition.spec
                    existing.copy(
                        definition = definition,
                        generation = if (specChanged) existing.generation + 1 else existing.generation,
                        resourceVersion = nextVersion(),
                        updatedAt = now,
                    )
                }
            definitions[name] = stored
            changes += ServerChange(name, ChangeKind.WRITTEN, stored.resourceVersion, now)
            WriteOutcome.Applied(stored)
        }

    override suspend fun deleteDefinition(
        name: ResourceName,
        precondition: Precondition,
    ): WriteOutcome<StoredDefinition> =
        guarded {
            val existing =
                definitions[name] ?: return@guarded WriteOutcome.Conflict(name, ConflictReason.NOT_FOUND, null)
            if (existing.deletedAt != null) return@guarded WriteOutcome.Applied(existing)
            val now = clock.instant()
            val stored = existing.copy(resourceVersion = nextVersion(), updatedAt = now, deletedAt = now)
            definitions[name] = stored
            changes += ServerChange(name, ChangeKind.DELETED, stored.resourceVersion, now)
            WriteOutcome.Applied(stored)
        }

    override suspend fun purge(
        name: ResourceName,
        precondition: Precondition,
    ): WriteOutcome<Unit> =
        guarded {
            val existing = definitions[name] ?: return@guarded WriteOutcome.Applied(Unit)
            if (precondition is Precondition.AtVersion && precondition.resourceVersion != existing.resourceVersion) {
                return@guarded WriteOutcome.Conflict(
                    name,
                    ConflictReason.VERSION_MISMATCH,
                    existing.resourceVersion,
                )
            }
            // The only guard the store offers: a live definition must not be
            // purged. Whether the *container* is gone is the reconciler's
            // check, because the store never sees one.
            if (existing.deletedAt == null) {
                return@guarded WriteOutcome.Conflict(name, ConflictReason.NOT_DELETED, existing.resourceVersion)
            }
            definitions.remove(name)
            statuses.remove(name)
            changes += ServerChange(name, ChangeKind.PURGED, nextVersion(), clock.instant())
            WriteOutcome.Applied(Unit)
        }

    override suspend fun putStatus(
        status: ServerStatus,
        precondition: Precondition,
        observedDefinition: ResourceVersion?,
    ): WriteOutcome<StoredStatus> {
        // Outside the lock: the hook writes to this same store.
        beforeStatusWrite?.let { hook ->
            beforeStatusWrite = null
            hook()
        }
        return guarded {
            statusPuts += 1
            val name = status.name
            val definition =
                definitions[name] ?: return@guarded WriteOutcome.Conflict(name, ConflictReason.NOT_FOUND, null)
            if (observedDefinition != null && observedDefinition != definition.resourceVersion) {
                return@guarded WriteOutcome.Conflict(
                    name,
                    ConflictReason.DEFINITION_CHANGED,
                    definition.resourceVersion,
                )
            }
            val existing = statuses[name]
            if (existing != null && existing.status == status) return@guarded WriteOutcome.Applied(existing)
            val stored = StoredStatus(status, nextVersion(), clock.instant())
            statuses[name] = stored
            statusWrites += 1
            WriteOutcome.Applied(stored)
        }
    }

    override suspend fun getServer(name: ResourceName): StoredServer? =
        guarded { definitions[name]?.let { StoredServer(it, statuses[name]) } }

    override suspend fun listServers(): List<StoredServer> =
        guarded { definitions.values.map { StoredServer(it, statuses[it.name]) } }

    override suspend fun listByDrainState(states: Set<DrainState>): List<StoredServer> =
        guarded {
            if (states.isEmpty()) return@guarded emptyList()
            definitions.values.mapNotNull { definition ->
                val status = statuses[definition.name] ?: return@mapNotNull null
                val drain = (status.status as? PaperServerStatus)?.drain?.state
                if (drain in states) StoredServer(definition, status) else null
            }
        }

    override suspend fun currentCursor(): StoreCursor = guarded { StoreCursor(revision.toString()) }

    override suspend fun changesSince(
        cursor: StoreCursor?,
        limit: Int,
    ): ChangeFeed =
        guarded {
            val from = cursor?.token?.toLongOrNull() ?: 0L
            val matching = changes.filter { it.resourceVersion.token.toLong() > from }
            val page = matching.take(limit)
            val next =
                page
                    .lastOrNull()
                    ?.resourceVersion
                    ?.token
                    ?.toLong() ?: maxOf(from, revision)
            ChangeFeed.Changes(page, StoreCursor(next.toString()), matching.size > limit)
        }

    override fun close(): Unit = Unit

    /** The status as stored, for assertions. */
    suspend fun statusOf(name: ResourceName): PaperServerStatus? =
        guarded { statuses[name]?.status as? PaperServerStatus }

    suspend fun recordedAt(name: ResourceName): Instant? = guarded { statuses[name]?.recordedAt }

    private suspend fun <T> guarded(block: () -> T): T {
        nextFailure?.let {
            nextFailure = null
            throw it
        }
        return mutex.withLock { block() }
    }

    private fun nextVersion(): ResourceVersion {
        revision += 1
        return ResourceVersion(revision.toString())
    }
}

package mcorch.store.sqlite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mcorch.schema.DrainState
import mcorch.schema.DurationFormat
import mcorch.schema.ResourceName
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerKind
import mcorch.schema.ServerStatus
import mcorch.store.ChangeFeed
import mcorch.store.ChangeKind
import mcorch.store.ConflictReason
import mcorch.store.Precondition
import mcorch.store.ResourceVersion
import mcorch.store.ServerChange
import mcorch.store.ServerListing
import mcorch.store.StatePart
import mcorch.store.Store
import mcorch.store.StoreCursor
import mcorch.store.StoreException
import mcorch.store.StoredDefinition
import mcorch.store.StoredServer
import mcorch.store.StoredStatus
import mcorch.store.Unreadable
import mcorch.store.UnreadableServer
import mcorch.store.WriteOutcome
import mcorch.store.codec.DefinitionCodec
import mcorch.store.codec.PropertyDocument
import mcorch.store.codec.StatusCodec
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant

/**
 * The single-host [Store]: one SQLite file, one connection, one writer at a time.
 *
 * `internal` on purpose. The only way to get one is [EmbeddedStore.open], which
 * hands back the interface type, so no consumer can name this class or reach a
 * method that is not on [Store]. That closes the gap the module's build file used
 * to describe as "isolation by review".
 *
 * ## Concurrency
 *
 * Every operation is one SQLite transaction, serialised through a [Mutex] and run
 * on an IO dispatcher. Serialising is not the concurrency control — the
 * [Precondition] on each write is. The mutex only keeps a single JDBC connection
 * from being used by two coroutines at once; take it away and replace it with a
 * connection pool, or with a network, and every caller still behaves correctly
 * because it named the version it expected.
 *
 * ## Ordering
 *
 * `store_sequence` is a single counter bumped inside the same transaction as the
 * write it belongs to. A row's `resource_version` is the counter value at its last
 * write, which makes versions globally ordered and comparable — the same shape a
 * revision-based distributed store would have, so the change feed does not need a
 * different design to move.
 */
internal class SqliteStore(
    private val connection: Connection,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    private val changeLogRetention: Int,
) : Store {
    private val mutex = Mutex()

    @Volatile
    private var closed: Boolean = false

    // -------------------------------------------------------------- desired state

    override suspend fun putDefinition(
        definition: ServerDefinition,
        precondition: Precondition,
    ): WriteOutcome<StoredDefinition> =
        write { connection ->
            val name = definition.metadata.name
            val existing = readDefinitionRow(connection, name)
            val metadataDoc = DefinitionCodec.encodeMetadata(definition.metadata)
            val specDoc = DefinitionCodec.encodeSpec(definition.spec)
            val now = clock.instant()

            if (existing == null) {
                checkPrecondition(name, precondition, current = null)?.let { return@write it }
                val revision = nextRevision(connection)
                connection.update(
                    """
                    INSERT INTO server_definition (
                        name, api_version, kind, generation, resource_version,
                        created_at, updated_at, deleted_at, metadata_doc, spec_doc, doc_encoding
                    ) VALUES (?, ?, ?, 1, ?, ?, ?, NULL, ?, ?, ?)
                    """.trimIndent(),
                ) {
                    setString(1, name.value)
                    setString(2, definition.apiVersion.wireValue)
                    setString(3, definition.kind.wireValue)
                    setLong(4, revision)
                    setInstant(5, now)
                    setInstant(6, now)
                    setString(7, metadataDoc)
                    setString(8, specDoc)
                    setInt(9, PropertyDocument.ENCODING_VERSION)
                }
                appendChange(connection, revision, name, ChangeKind.WRITTEN, now)
                return@write WriteOutcome.Applied(
                    StoredDefinition(
                        definition = definition,
                        generation = 1L,
                        resourceVersion = ResourceVersion(revision.toString()),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }

            // Integrity rules first: they describe the name, not a race, so they hold
            // however confident the caller was about the version it read.
            if (existing.deletedAt != null) {
                return@write conflict(name, ConflictReason.TERMINATING, existing.resourceVersion)
            }
            if (existing.kind != definition.kind) {
                return@write conflict(name, ConflictReason.KIND_MISMATCH, existing.resourceVersion)
            }
            checkPrecondition(name, precondition, current = existing.resourceVersion)?.let { return@write it }

            val specChanged = existing.specDoc != specDoc
            val metadataChanged = existing.metadataDoc != metadataDoc
            val envelopeChanged = existing.apiVersion != definition.apiVersion
            if (!specChanged && !metadataChanged && !envelopeChanged) {
                // Re-applying an unchanged file must not move the version and must not
                // show up in the change feed. Otherwise every resync wakes the loop.
                return@write WriteOutcome.Applied(existing.toStored(definition))
            }

            val revision = nextRevision(connection)
            val generation = if (specChanged) existing.generation + 1 else existing.generation
            connection.update(
                """
                UPDATE server_definition
                   SET api_version = ?, generation = ?, resource_version = ?, updated_at = ?,
                       metadata_doc = ?, spec_doc = ?, doc_encoding = ?
                 WHERE name = ?
                """.trimIndent(),
            ) {
                setString(1, definition.apiVersion.wireValue)
                setLong(2, generation)
                setLong(3, revision)
                setInstant(4, now)
                setString(5, metadataDoc)
                setString(6, specDoc)
                setInt(7, PropertyDocument.ENCODING_VERSION)
                setString(8, name.value)
            }
            appendChange(connection, revision, name, ChangeKind.WRITTEN, now)
            WriteOutcome.Applied(
                StoredDefinition(
                    definition = definition,
                    generation = generation,
                    resourceVersion = ResourceVersion(revision.toString()),
                    createdAt = existing.createdAt,
                    updatedAt = now,
                ),
            )
        }

    override suspend fun deleteDefinition(
        name: ResourceName,
        precondition: Precondition,
    ): WriteOutcome<StoredDefinition> =
        write { connection ->
            val existing =
                readDefinitionRow(connection, name)
                    ?: return@write conflict(name, ConflictReason.NOT_FOUND, null)
            checkPrecondition(name, precondition, current = existing.resourceVersion)?.let { return@write it }
            if (existing.deletedAt != null) {
                // Already requested. Repeating a delete is not an error and must not
                // move the version: the loop may still be draining against it.
                return@write WriteOutcome.Applied(existing.toStored(decodeDefinition(existing)))
            }
            val now = clock.instant()
            val revision = nextRevision(connection)
            connection.update(
                "UPDATE server_definition SET deleted_at = ?, resource_version = ?, updated_at = ? WHERE name = ?",
            ) {
                setInstant(1, now)
                setLong(2, revision)
                setInstant(3, now)
                setString(4, name.value)
            }
            appendChange(connection, revision, name, ChangeKind.DELETED, now)
            WriteOutcome.Applied(
                existing.toStored(decodeDefinition(existing)).copy(
                    resourceVersion = ResourceVersion(revision.toString()),
                    updatedAt = now,
                    deletedAt = now,
                ),
            )
        }

    override suspend fun purge(
        name: ResourceName,
        precondition: Precondition,
    ): WriteOutcome<Unit> =
        write { connection ->
            val existing = readDefinitionRow(connection, name)
            if (existing == null) {
                return@write if (precondition is Precondition.AtVersion) {
                    conflict(name, ConflictReason.NOT_FOUND, null)
                } else {
                    WriteOutcome.Applied(Unit)
                }
            }
            checkPrecondition(name, precondition, current = existing.resourceVersion)?.let { return@write it }
            if (existing.deletedAt == null) {
                return@write conflict(name, ConflictReason.NOT_DELETED, existing.resourceVersion)
            }
            val now = clock.instant()
            val revision = nextRevision(connection)
            // The status goes with it: ON DELETE CASCADE, so a status can never be
            // left behind describing a server that no longer has a definition.
            connection.update("DELETE FROM server_definition WHERE name = ?") { setString(1, name.value) }
            appendChange(connection, revision, name, ChangeKind.PURGED, now)
            WriteOutcome.Applied(Unit)
        }

    // ------------------------------------------------------------- observed state

    override suspend fun putStatus(
        status: ServerStatus,
        precondition: Precondition,
        observedDefinition: ResourceVersion?,
    ): WriteOutcome<StoredStatus> =
        write { connection ->
            val name = status.name
            val definition =
                readDefinitionRow(connection, name)
                    ?: return@write conflict(name, ConflictReason.NOT_FOUND, null)
            if (definition.kind != status.kind) {
                return@write conflict(name, ConflictReason.KIND_MISMATCH, definition.resourceVersion)
            }
            if (observedDefinition != null && observedDefinition != definition.resourceVersion) {
                // The pass ran against a spec the operator has since replaced. Recording
                // it would make the server look settled at a generation nobody wants.
                return@write conflict(name, ConflictReason.DEFINITION_CHANGED, definition.resourceVersion)
            }

            val existing = readStatusRow(connection, name)
            checkPrecondition(name, precondition, current = existing?.resourceVersion)?.let { return@write it }

            val statusDoc = StatusCodec.encode(status)
            if (existing != null && existing.statusDoc == statusDoc && existing.apiVersion == status.apiVersion) {
                // Nothing observed changed. Do not move the version: a settled server
                // must not generate write traffic or wake compare-and-swap callers.
                return@write WriteOutcome.Applied(
                    StoredStatus(status, existing.resourceVersion, existing.recordedAt),
                )
            }

            val now = clock.instant()
            val revision = nextRevision(connection)
            connection.update(
                """
                INSERT INTO server_status (
                    name, api_version, kind, resource_version, recorded_at, status_doc, doc_encoding, drain_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET
                    api_version = excluded.api_version,
                    kind = excluded.kind,
                    resource_version = excluded.resource_version,
                    recorded_at = excluded.recorded_at,
                    status_doc = excluded.status_doc,
                    doc_encoding = excluded.doc_encoding,
                    drain_state = excluded.drain_state
                """.trimIndent(),
            ) {
                setString(1, name.value)
                setString(2, status.apiVersion.wireValue)
                setString(3, status.kind.wireValue)
                setLong(4, revision)
                setInstant(5, now)
                setString(6, statusDoc)
                setInt(7, PropertyDocument.ENCODING_VERSION)
                val drainState = StatusCodec.drainStateOf(status)
                if (drainState == null) setNull(8, java.sql.Types.VARCHAR) else setString(8, drainState.name)
            }
            // Status writes deliberately do not append to the change feed: the loop is
            // the only writer of observed state, and feeding it back to itself is a
            // self-loop, not a notification.
            WriteOutcome.Applied(StoredStatus(status, ResourceVersion(revision.toString()), now))
        }

    // ----------------------------------------------------------------------- reads

    override suspend fun getServer(name: ResourceName): StoredServer? =
        read { connection ->
            connection.query(
                "$SERVER_SELECT WHERE d.name = ?",
                bind = { setString(1, name.value) },
            ) { rows -> if (rows.next()) readRow(rows) else null }
        }?.strict()

    override suspend fun listServers(): List<StoredServer> = allRows().servers()

    override suspend fun listByDrainState(states: Set<DrainState>): List<StoredServer> = drainRows(states).servers()

    override suspend fun listAll(): ServerListing = allRows().listing()

    override suspend fun listAllByDrainState(states: Set<DrainState>): ServerListing = drainRows(states).listing()

    private suspend fun allRows(): List<RowRead> = readRows("$SERVER_SELECT ORDER BY d.name")

    private suspend fun drainRows(states: Set<DrainState>): List<RowRead> {
        if (states.isEmpty()) return emptyList()
        val placeholders = states.joinToString(", ") { "?" }
        val ordered = states.toList()
        return readRows("$SERVER_SELECT WHERE s.drain_state IN ($placeholders) ORDER BY d.name") {
            ordered.forEachIndexed { index, state -> setString(index + 1, state.name) }
        }
    }

    private suspend fun readRows(
        sql: String,
        bind: PreparedStatement.() -> Unit = {},
    ): List<RowRead> = read { connection -> connection.query(sql, bind) { rows -> rows.mapAll(::readRow) } }

    // ---------------------------------------------------------------- change feed

    override suspend fun currentCursor(): StoreCursor =
        read { connection ->
            StoreCursor(readSequence(connection).nextRevision.toString())
        }

    override suspend fun changesSince(
        cursor: StoreCursor?,
        limit: Int,
    ): ChangeFeed {
        require(limit > 0) { "change feed limit must be positive, found $limit" }
        return read { connection ->
            val sequence = readSequence(connection)
            val from = cursor?.let { parseCursor(it) } ?: sequence.compactedBelow
            if (from < sequence.compactedBelow) {
                return@read ChangeFeed.Expired(StoreCursor(sequence.nextRevision.toString()))
            }
            val changes =
                connection.query(
                    """
                    SELECT revision, name, change_kind, at
                      FROM definition_change
                     WHERE revision > ?
                     ORDER BY revision
                     LIMIT ?
                    """.trimIndent(),
                    bind = {
                        setLong(1, from)
                        setInt(2, limit + 1)
                    },
                ) { rows -> rows.mapAll(::readChange) }
            val more = changes.size > limit
            val page = if (more) changes.subList(0, limit) else changes
            val next =
                page
                    .lastOrNull()
                    ?.resourceVersion
                    ?.token
                    ?.toLong() ?: maxOf(from, sequence.nextRevision)
            ChangeFeed.Changes(changes = page, cursor = StoreCursor(next.toString()), more = more)
        }
    }

    override fun close() {
        closed = true
        try {
            connection.close()
        } catch (failure: SQLException) {
            throw failure.asStoreException("closing the store")
        }
    }

    // ------------------------------------------------------------------- internals

    private suspend fun <T> write(block: (Connection) -> T): T = guarded { connection -> connection.transaction(block) }

    private suspend fun <T> read(block: (Connection) -> T): T = guarded { connection -> connection.transaction(block) }

    private suspend fun <T> guarded(block: (Connection) -> T): T {
        if (closed) throw StoreException.Closed("the store has been closed")
        return withContext(dispatcher) {
            mutex.withLock {
                if (closed) throw StoreException.Closed("the store has been closed")
                block(connection)
            }
        }
    }

    /**
     * Keeps the change log bounded, inside the transaction that appended to it.
     *
     * Anything dropped raises `compacted_below`, and that is what turns a cursor
     * from before the drop into [ChangeFeed.Expired]. A feed that silently skipped
     * the changes it no longer has would be worse than one that admits it: the loop
     * would carry on believing it had seen everything.
     */
    private fun trimChangeLog(connection: Connection) {
        val count =
            connection.query("SELECT COUNT(*) AS n FROM definition_change") { rows ->
                if (rows.next()) rows.getLong("n") else 0L
            }
        if (count <= changeLogRetention) return
        val keepFrom =
            connection.query(
                "SELECT revision FROM definition_change ORDER BY revision DESC LIMIT 1 OFFSET ?",
                bind = { setInt(1, changeLogRetention - 1) },
            ) { rows -> if (rows.next()) rows.getLong("revision") else null } ?: return
        connection.update("DELETE FROM definition_change WHERE revision < ?") { setLong(1, keepFrom) }
        connection.update("UPDATE store_sequence SET compacted_below = ? WHERE id = 0") { setLong(1, keepFrom - 1) }
    }

    private fun nextRevision(connection: Connection): Long {
        connection.update("UPDATE store_sequence SET next_revision = next_revision + 1 WHERE id = 0")
        return readSequence(connection).nextRevision
    }

    private fun readSequence(connection: Connection): SequenceRow =
        connection.query("SELECT next_revision, compacted_below FROM store_sequence WHERE id = 0") { rows ->
            if (!rows.next()) throw StoreException.Corrupt("store sequence row is missing")
            SequenceRow(rows.getLong("next_revision"), rows.getLong("compacted_below"))
        }

    private fun appendChange(
        connection: Connection,
        revision: Long,
        name: ResourceName,
        kind: ChangeKind,
        at: Instant,
    ) {
        connection.update("INSERT INTO definition_change (revision, name, change_kind, at) VALUES (?, ?, ?, ?)") {
            setLong(1, revision)
            setString(2, name.value)
            setString(3, kind.name)
            setInstant(4, at)
        }
        // Trimming is amortised rather than done on every append: the bound is a
        // bound, not an exact length, and a COUNT on the hot write path is not free.
        if (revision % maxOf(1, changeLogRetention / 4) == 0L) trimChangeLog(connection)
    }

    private fun parseCursor(cursor: StoreCursor): Long =
        cursor.token.toLongOrNull()
            ?: throw StoreException.Unsupported("cursor `$cursor` was not issued by this store")

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
                if (current == null) null else conflict(name, ConflictReason.ALREADY_EXISTS, current)
            }

            is Precondition.AtVersion -> {
                when {
                    current == null -> {
                        conflict(name, ConflictReason.NOT_FOUND, null)
                    }

                    current != precondition.resourceVersion -> {
                        conflict(name, ConflictReason.VERSION_MISMATCH, current)
                    }

                    else -> {
                        null
                    }
                }
            }
        }

    private fun conflict(
        name: ResourceName,
        reason: ConflictReason,
        current: ResourceVersion?,
    ): WriteOutcome.Conflict = WriteOutcome.Conflict(name, reason, current)

    // -------------------------------------------------------------- row decoding

    private fun readDefinitionRow(
        connection: Connection,
        name: ResourceName,
    ): DefinitionRow? =
        connection.query(
            """
            SELECT name, api_version, kind, generation, resource_version, created_at, updated_at,
                   deleted_at, metadata_doc, spec_doc, doc_encoding
              FROM server_definition
             WHERE name = ?
            """.trimIndent(),
            bind = { setString(1, name.value) },
        ) { rows -> if (rows.next()) readDefinitionRow(rows) else null }

    private fun readStatusRow(
        connection: Connection,
        name: ResourceName,
    ): StatusRow? =
        connection.query(
            """
            SELECT name, api_version, kind, resource_version, recorded_at, status_doc, doc_encoding
              FROM server_status
             WHERE name = ?
            """.trimIndent(),
            bind = { setString(1, name.value) },
        ) { rows -> if (rows.next()) readStatusRow(rows) else null }

    /**
     * One joined row, decoded as far as it will go.
     *
     * The two halves are decoded separately so that a failure in either is
     * attributable to this row and this server, and the caller decides whether to
     * raise it ([strict]) or to carry it ([servers], [listing]). Decoding both and
     * throwing on the first problem is what made a single hand-edited row cost
     * every server in the same read.
     *
     * Only a *permanent* [StoreException] is caught, and that is what makes
     * [Unreadable.retryable] structurally false rather than incidentally false: a
     * retryable failure is re-raised, because it describes the read rather than
     * the record and a caller that saw it as an annotation would treat a passing
     * problem as a corrupt row for good.
     *
     * A JDBC failure never reaches either branch. It arrives as an `SQLException`,
     * which is not a [StoreException], so it passes through this function and is
     * classified by [query] on the way out — an unreachable database stays an
     * unreachable database rather than becoming a corrupt server.
     */
    private fun readRow(rows: ResultSet): RowRead {
        // Not `getString`: a NULL here is exactly the case this has to survive.
        val rawName = rows.stringOrNull("name")
        val definition =
            try {
                val row = readDefinitionRow(rows)
                row.toStored(decodeDefinition(row))
            } catch (failure: StoreException) {
                if (failure.retryable) throw failure
                return RowRead.Undecodable(
                    UnreadableServer(rawName, unreadable(StatePart.DESIRED, failure)),
                    failure,
                )
            }

        // getLong then wasNull, in that order and with nothing between them: the
        // flag describes the column that was read last.
        val statusVersion = rows.getLong("status_resource_version")
        if (rows.wasNull()) return RowRead.Readable(StoredServer(definition), failure = null)

        return try {
            RowRead.Readable(StoredServer(definition, readStatus(rows, definition.name, statusVersion)), null)
        } catch (failure: StoreException) {
            if (failure.retryable) throw failure
            RowRead.Readable(
                StoredServer(definition, status = null, unreadable = unreadable(StatePart.OBSERVED, failure)),
                failure,
            )
        }
    }

    private fun readStatus(
        rows: ResultSet,
        name: ResourceName,
        resourceVersion: Long,
    ): StoredStatus {
        val what = "status of `$name`"
        requireEncoding(rows.getInt("status_doc_encoding"), what)
        return StoredStatus(
            status =
                decodeStatus(
                    name = name,
                    apiVersion = schemaVersion(rows.requiredString("status_api_version", what), what),
                    kind = serverKind(rows.requiredString("status_kind", what), what),
                    encoded = rows.requiredString("status_doc", what),
                    what = what,
                ),
            resourceVersion = ResourceVersion(resourceVersion.toString()),
            recordedAt = rows.instant("status_recorded_at", what),
        )
    }

    /**
     * Rebuilds the observation in a row, and says so when a record had to be
     * reconstructed.
     *
     * [StatusCodec.decode] restores a side-effect record that a build predating the
     * field could not have written; see [mcorch.schema.StatusReconstruction] for
     * which record, why it is restored on the read rather than by a migration, and
     * why reading its absence at face value routes players onto a container that has
     * been sent `SIGTERM`. The reporting is here rather than in the codec for
     * [decodeDefinition]'s reason: this is the module's logger, and an inference
     * nothing says out loud is the silent reinterpretation of stored data the codec
     * is written to refuse.
     *
     * Unlike a clamp this is self-clearing — the reconcile loop carries the record
     * it read into the observation it writes, so the next pass persists the
     * reconstruction and the line stops. That makes it a warning about an upgrade
     * rather than a standing condition, and it is deliberately *not* silenced on the
     * strength of that: a line that keeps appearing means the row is not being
     * written back, which is a different fault and one worth seeing.
     */
    private fun decodeStatus(
        name: ResourceName,
        apiVersion: SchemaVersion,
        kind: ServerKind,
        encoded: String,
        what: String,
    ): ServerStatus {
        val decoded =
            StatusCodec.decode(
                name = name,
                apiVersion = apiVersion,
                kind = kind,
                encoded = encoded,
                what = what,
            )
        for (record in decoded.reconstructed) {
            LOG.warn(
                "server={} field={} was not recorded by the build that wrote this observation; " +
                    "reading it as {} taken from {}. The stored document is unchanged",
                name.value,
                record.field,
                record.value,
                record.takenFrom,
            )
        }
        return decoded.status
    }

    private fun unreadable(
        part: StatePart,
        failure: StoreException,
    ): Unreadable =
        Unreadable(
            part = part,
            reason = failure.message ?: failure.toString(),
            retryable = failure.retryable,
        )

    /** The point-read view: whatever would not decode is raised. See [Store.getServer]. */
    private fun RowRead.strict(): StoredServer =
        when (this) {
            is RowRead.Readable -> {
                if (failure != null) throw failure
                server
            }

            is RowRead.Undecodable -> {
                throw failure
            }
        }

    /**
     * The [Store.listServers] view: an unreadable observation is marked on its own
     * server, an unreadable definition still fails the read.
     */
    private fun List<RowRead>.servers(): List<StoredServer> =
        map { row ->
            when (row) {
                is RowRead.Readable -> {
                    row.failure?.let { report(row.server.name.value, StatePart.OBSERVED, it) }
                    row.server
                }

                is RowRead.Undecodable -> {
                    throw row.failure
                }
            }
        }

    /** The [Store.listAll] view: nothing is raised, everything is accounted for. */
    private fun List<RowRead>.listing(): ServerListing {
        val servers = ArrayList<StoredServer>(size)
        val unreadable = mutableListOf<UnreadableServer>()
        for (row in this) {
            when (row) {
                is RowRead.Readable -> {
                    row.failure?.let { report(row.server.name.value, StatePart.OBSERVED, it) }
                    servers += row.server
                }

                is RowRead.Undecodable -> {
                    report(row.entry.name, StatePart.DESIRED, row.failure)
                    unreadable += row.entry
                }
            }
        }
        return ServerListing(servers, unreadable)
    }

    /**
     * Says out loud what a caller is only *offered*.
     *
     * This is the one place a failure stops being raised, and something that is
     * neither raised nor logged is swallowed. Logged on the read rather than on the
     * write because the row was written by a build that could encode it — a
     * downgrade, or a hand edit, is only ever discovered here. Names are resource
     * names; no player identity passes through the state store at all.
     */
    private fun report(
        name: String?,
        part: StatePart,
        failure: StoreException,
    ) {
        LOG.warn(
            "server={} has {} state this build cannot decode: {}",
            name ?: "<unnamed row>",
            part,
            failure.message,
        )
    }

    private fun readDefinitionRow(rows: ResultSet): DefinitionRow {
        val what = describe("definition", rows.stringOrNull("name"))
        return DefinitionRow(
            name = resourceName(rows.requiredString("name", what), what),
            apiVersion = schemaVersion(rows.requiredString("api_version", what), what),
            kind = serverKind(rows.requiredString("kind", what), what),
            generation = rows.getLong("generation"),
            resourceVersion = ResourceVersion(rows.getLong("resource_version").toString()),
            createdAt = rows.instant("created_at", what),
            updatedAt = rows.instant("updated_at", what),
            deletedAt = rows.instantOrNull("deleted_at", what),
            metadataDoc = rows.requiredString("metadata_doc", what),
            specDoc = rows.requiredString("spec_doc", what),
            encoding = requireEncoding(rows.getInt("doc_encoding"), what),
        )
    }

    private fun readStatusRow(rows: ResultSet): StatusRow {
        val what = describe("status", rows.stringOrNull("name"))
        return StatusRow(
            name = resourceName(rows.requiredString("name", what), what),
            apiVersion = schemaVersion(rows.requiredString("api_version", what), what),
            kind = serverKind(rows.requiredString("kind", what), what),
            resourceVersion = ResourceVersion(rows.getLong("resource_version").toString()),
            recordedAt = rows.instant("recorded_at", what),
            statusDoc = rows.requiredString("status_doc", what),
            encoding = requireEncoding(rows.getInt("doc_encoding"), what),
        )
    }

    private fun readChange(rows: ResultSet): ServerChange {
        val revision = rows.getLong("revision")
        val what = "change at revision $revision"
        val kind = rows.requiredString("change_kind", what)
        return ServerChange(
            name = resourceName(rows.requiredString("name", what), what),
            kind =
                ChangeKind.entries.firstOrNull { it.name == kind }
                    ?: throw StoreException.Corrupt("$what: unknown change kind `$kind`"),
            resourceVersion = ResourceVersion(revision.toString()),
            at = rows.instant("at", what),
        )
    }

    /**
     * Names the record a failure is about, without assuming it has a name.
     *
     * A row whose own primary key is NULL has to be describable, or the message
     * saying so cannot be built.
     */
    private fun describe(
        part: String,
        rawName: String?,
    ): String = if (rawName == null) "$part of an unnamed row" else "$part of `$rawName`"

    /**
     * Rebuilds the definition in [row], bounded, and says so when the bound bit.
     *
     * [DefinitionCodec.decode] caps the durations that become transport deadlines;
     * see [mcorch.schema.SpecBounds] for why a stored row can carry one that parks a
     * reconcile worker and why the answer is a cap rather than a refusal. The
     * reporting is here rather than in the codec because this is the module's
     * logger, and because a clamp that nothing says out loud is the silent
     * reinterpretation of stored data the whole codec is written to refuse.
     *
     * Logged on every read, deliberately, on the same reasoning as [report]: the row
     * is unchanged on disk, so the condition persists until an operator edits it and
     * they should keep being told. On a healthy store this costs one comparison of
     * an empty list per row.
     */
    private fun decodeDefinition(row: DefinitionRow): ServerDefinition {
        val bounded =
            DefinitionCodec.decode(
                apiVersion = row.apiVersion,
                kind = row.kind,
                encodedMetadata = row.metadataDoc,
                encodedSpec = row.specDoc,
                what = "definition of `${row.name}`",
            )
        for (clamp in bounded.clamped) {
            LOG.warn(
                "server={} field={} declares {} above the {} this build will act on; " +
                    "using the ceiling. The stored value is unchanged — edit the definition to clear this",
                row.name.value,
                clamp.field,
                DurationFormat.render(clamp.declared),
                DurationFormat.render(clamp.applied),
            )
        }
        return bounded.definition
    }

    private fun requireEncoding(
        encoding: Int,
        what: String,
    ): Int =
        if (encoding == PropertyDocument.ENCODING_VERSION) {
            encoding
        } else {
            throw StoreException.Unsupported(
                "$what is stored with document encoding $encoding; this build reads " +
                    "${PropertyDocument.ENCODING_VERSION}. Refusing to reinterpret it",
            )
        }

    private fun resourceName(
        raw: String,
        what: String,
    ): ResourceName =
        ResourceName.of(raw).getOrElse {
            throw StoreException.Corrupt("$what: stored name `$raw` is not a valid resource name", it)
        }

    private fun schemaVersion(
        raw: String,
        what: String,
    ): SchemaVersion =
        SchemaVersion.fromWire(raw)
            ?: throw StoreException.Unsupported(
                "$what declares apiVersion `$raw`, which this build does not know " +
                    "(${SchemaVersion.supported().joinToString(", ")})",
            )

    private fun serverKind(
        raw: String,
        what: String,
    ): ServerKind =
        ServerKind.fromWire(raw)
            ?: throw StoreException.Unsupported(
                "$what declares kind `$raw`, which this build does not know " +
                    "(${ServerKind.supported().joinToString(", ")})",
            )

    private fun DefinitionRow.toStored(definition: ServerDefinition): StoredDefinition =
        StoredDefinition(
            definition = definition,
            generation = generation,
            resourceVersion = resourceVersion,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
        )

    private fun <T> ResultSet.mapAll(read: (ResultSet) -> T): List<T> {
        val values = mutableListOf<T>()
        while (next()) values += read(this)
        return values
    }

    /**
     * How far one joined row decoded.
     *
     * The failure is kept beside the value instead of being thrown at the point it
     * happened, so one decode can serve a point read that raises it and a listing
     * that carries it, without reading the row twice or deciding the policy here.
     */
    private sealed interface RowRead {
        /** The desired state decoded. [failure] is set when the observation did not. */
        data class Readable(
            val server: StoredServer,
            val failure: StoreException?,
        ) : RowRead

        /** The desired state did not decode, so there is no server to hand back. */
        data class Undecodable(
            val entry: UnreadableServer,
            val failure: StoreException,
        ) : RowRead
    }

    private data class SequenceRow(
        val nextRevision: Long,
        val compactedBelow: Long,
    )

    private data class DefinitionRow(
        val name: ResourceName,
        val apiVersion: SchemaVersion,
        val kind: ServerKind,
        val generation: Long,
        val resourceVersion: ResourceVersion,
        val createdAt: Instant,
        val updatedAt: Instant,
        val deletedAt: Instant?,
        val metadataDoc: String,
        val specDoc: String,
        val encoding: Int,
    )

    private data class StatusRow(
        val name: ResourceName,
        val apiVersion: SchemaVersion,
        val kind: ServerKind,
        val resourceVersion: ResourceVersion,
        val recordedAt: Instant,
        val statusDoc: String,
        val encoding: Int,
    )

    private companion object {
        private val LOG = LoggerFactory.getLogger(SqliteStore::class.java)

        /**
         * The joined read. Definition and status always come out of one statement so a
         * caller can never see a pair that never existed on disk.
         */
        const val SERVER_SELECT: String =
            """
            SELECT d.name, d.api_version, d.kind, d.generation, d.resource_version,
                   d.created_at, d.updated_at, d.deleted_at, d.metadata_doc, d.spec_doc, d.doc_encoding,
                   s.api_version      AS status_api_version,
                   s.kind             AS status_kind,
                   s.resource_version AS status_resource_version,
                   s.recorded_at      AS status_recorded_at,
                   s.status_doc       AS status_doc,
                   s.doc_encoding     AS status_doc_encoding
              FROM server_definition d
              LEFT JOIN server_status s ON s.name = d.name
            """
    }
}

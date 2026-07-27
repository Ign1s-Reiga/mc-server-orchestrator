package mcorch.store.sqlite

import mcorch.store.StoreException
import mcorch.store.codec.DocumentReader
import mcorch.store.codec.DocumentWriter
import mcorch.store.codec.PropertyDocument
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock

/**
 * One forward step in the on-disk schema.
 *
 * ## Rules
 *
 * - **Forward only.** There is no `down`. A rollback is a restore from backup, and
 *   pretending otherwise invites a "reversible" migration that silently drops a
 *   column somebody still needs.
 * - **Never edited once shipped.** A migration that has run on a real disk is
 *   history. Changing it makes two machines that both report the same version
 *   disagree about what is on disk. Fix a mistake with the next migration.
 * - **Data migrations read documents at the key level**, never through the current
 *   `:schema` object model — see [V2StatusDrainProjection]. A migration written
 *   today has to keep working after `:schema` changes, and it cannot do that if it
 *   depends on today's Kotlin types.
 * - **One transaction per migration**, including the row that records it, so a
 *   crash halfway leaves the store at the previous version rather than at a
 *   version that was never fully applied.
 */
internal interface Migration {
    val version: Int
    val description: String

    fun apply(connection: Connection)
}

internal data class MigrationReport(
    val from: Int,
    val to: Int,
    val applied: List<Int>,
)

/**
 * The ordered migration list and the runner that applies it.
 *
 * ## Adding version 4
 *
 * 1. Write a `V4Something : Migration` below with `version = 4`.
 * 2. Append it to [ALL]. Do not renumber, do not reorder, do not touch V1, V2 or V3.
 * 3. Add a case to the migration test: write data through the store at the previous
 *    version, migrate, assert every field is still there and anything new is
 *    correctly derived from the data that was already on disk.
 *
 * A store whose recorded version is *higher* than [latest] is refused outright.
 * An older binary that reinterpreted a newer layout would be the one failure mode
 * that loses data silently instead of loudly.
 */
internal object Migrations {
    private val logger = LoggerFactory.getLogger(Migrations::class.java)

    val ALL: List<Migration> = listOf(V1BaseSchema, V2StatusDrainProjection, V3SplitWorldSavedInstant)

    val latest: Int = ALL.maxOf { it.version }

    init {
        require(ALL.map { it.version } == ALL.indices.map { it + 1 }) {
            "migrations must be numbered 1..n with no gaps and listed in order"
        }
    }

    /**
     * Brings the store up to [upTo], which is [latest] everywhere except one place.
     *
     * That place is the migration test, which needs a database that genuinely
     * stopped at an older version so it can migrate a real one forward rather than
     * assert against a re-creation of what it thinks an old disk looked like.
     */
    fun migrate(
        connection: Connection,
        clock: Clock,
        upTo: Int = latest,
    ): MigrationReport {
        createVersionTable(connection)
        val current = currentVersion(connection)
        if (current > latest) {
            throw StoreException.Unsupported(
                "on-disk store schema is at version $current, but this build only understands up to $latest. " +
                    "Refusing to open it rather than reinterpret it",
            )
        }
        val pending = ALL.filter { it.version in (current + 1)..upTo }
        if (pending.isEmpty()) {
            logger.debug("store schema up to date version={}", current)
            return MigrationReport(from = current, to = current, applied = emptyList())
        }
        logger.info(
            "migrating store schema from={} to={} pending={}",
            current,
            upTo,
            pending.map { it.version },
        )
        for (migration in pending) {
            applyOne(connection, migration, clock)
        }
        return MigrationReport(from = current, to = pending.last().version, applied = pending.map { it.version })
    }

    private fun applyOne(
        connection: Connection,
        migration: Migration,
        clock: Clock,
    ) {
        try {
            connection.transaction {
                migration.apply(connection)
                connection.update(
                    "INSERT INTO schema_migration (version, description, applied_at) VALUES (?, ?, ?)",
                ) {
                    setInt(1, migration.version)
                    setString(2, migration.description)
                    setString(3, clock.instant().toString())
                }
            }
        } catch (failure: StoreException) {
            throw StoreException.MigrationFailed(
                "store schema migration to version ${migration.version} (${migration.description}) failed",
                failure,
            )
        } catch (failure: SQLException) {
            throw StoreException.MigrationFailed(
                "store schema migration to version ${migration.version} (${migration.description}) failed",
                failure,
            )
        }
        logger.info(
            "applied store schema migration version={} description={}",
            migration.version,
            migration.description,
        )
    }

    private fun createVersionTable(connection: Connection) {
        connection.transaction {
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migration (
                    version     INTEGER PRIMARY KEY,
                    description TEXT NOT NULL,
                    applied_at  TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    fun currentVersion(connection: Connection): Int =
        connection.query("SELECT COALESCE(MAX(version), 0) AS version FROM schema_migration") { rows ->
            if (rows.next()) rows.getInt("version") else 0
        }
}

/**
 * The tables the store started life with.
 *
 * Two things are worth reading twice. `server_status.name` references
 * `server_definition` with `ON DELETE CASCADE`, which is what makes "a status can
 * never outlive its definition row" a property of the disk rather than of the
 * code above it. And `store_sequence` is a single row holding one monotonically
 * increasing counter: it is the source of every [mcorch.store.ResourceVersion]
 * and every [mcorch.store.StoreCursor], which is why a compare-and-swap here is a
 * genuine ordering and not a per-row guess.
 */
private object V1BaseSchema : Migration {
    override val version: Int = 1
    override val description: String = "base schema: definitions, statuses, change log, revision sequence"

    override fun apply(connection: Connection) {
        connection.execute(
            """
            CREATE TABLE store_sequence (
                id              INTEGER PRIMARY KEY CHECK (id = 0),
                next_revision   INTEGER NOT NULL,
                compacted_below INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execute("INSERT INTO store_sequence (id, next_revision, compacted_below) VALUES (0, 0, 0)")
        connection.execute(
            """
            CREATE TABLE server_definition (
                name             TEXT PRIMARY KEY,
                api_version      TEXT NOT NULL,
                kind             TEXT NOT NULL,
                generation       INTEGER NOT NULL,
                resource_version INTEGER NOT NULL,
                created_at       TEXT NOT NULL,
                updated_at       TEXT NOT NULL,
                deleted_at       TEXT,
                metadata_doc     TEXT NOT NULL,
                spec_doc         TEXT NOT NULL,
                doc_encoding     INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execute(
            """
            CREATE TABLE server_status (
                name             TEXT PRIMARY KEY
                                 REFERENCES server_definition (name) ON DELETE CASCADE,
                api_version      TEXT NOT NULL,
                kind             TEXT NOT NULL,
                resource_version INTEGER NOT NULL,
                recorded_at      TEXT NOT NULL,
                status_doc       TEXT NOT NULL,
                doc_encoding     INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execute(
            """
            CREATE TABLE definition_change (
                revision    INTEGER PRIMARY KEY,
                name        TEXT NOT NULL,
                change_kind TEXT NOT NULL,
                at          TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
}

/**
 * Adds the drain-state projection and the index behind
 * [mcorch.store.Store.listByDrainState].
 *
 * The projection is derived from `status_doc`, which stays the source of truth —
 * reads decode the document, never the column. The column exists so that "which
 * servers had a drain in flight when the process died" is a query rather than a
 * full scan and a decode of every row, on the one path where being slow or
 * incomplete strands players on a server nobody is watching.
 *
 * The backfill reads the stored document by key. It does not build a
 * `PaperServerStatus`: this migration has to keep producing the same result years
 * from now, and that is only true if it depends on the `drain.state` key rather
 * than on whatever `:schema` looks like at the time it runs.
 */
private object V2StatusDrainProjection : Migration {
    override val version: Int = 2
    override val description: String = "project drain state out of stored status documents and index it"

    override fun apply(connection: Connection) {
        connection.execute("ALTER TABLE server_status ADD COLUMN drain_state TEXT")

        val projections = mutableListOf<Pair<String, String?>>()
        connection.query("SELECT name, status_doc FROM server_status") { rows ->
            while (rows.next()) {
                val name = rows.getString("name")
                val document = PropertyDocument.parse(rows.getString("status_doc"), "status of `$name`")
                projections += name to document.string("drain.state")
            }
        }
        for ((name, drainState) in projections) {
            connection.update("UPDATE server_status SET drain_state = ? WHERE name = ?") {
                if (drainState == null) setNull(1, java.sql.Types.VARCHAR) else setString(1, drainState)
                setString(2, name)
            }
        }
        connection.execute(
            "CREATE INDEX server_status_drain_state ON server_status (drain_state) WHERE drain_state IS NOT NULL",
        )
    }
}

/**
 * Splits `drain.saveRequestedAt` into "a request is outstanding" and "a save
 * completed at", which used to be the same key discriminated by
 * `drain.worldSaved`.
 *
 * **This needs a migration even though no column changes.** The document format
 * treats an absent key as null, so a new key is free on disk — but the *meaning*
 * of an existing key changed, and that is not free. A row written before this
 * with `worldSaved=true, saveRequestedAt=T` says *a save completed at T*. Read
 * by the new code, which no longer looks at the flag, the same bytes say *a
 * request went out at T and was never confirmed* — the drain's permanent-wedge
 * condition. Every in-flight drain holding a confirmed save would come back from
 * an upgrade needing a human, and the operator would be told to go and verify a
 * world that was already on disk.
 *
 * So each status document is rewritten:
 *
 * - `worldSaved=true` → `worldSavedAt` takes the old `saveRequestedAt` value and
 *   `saveRequestedAt` is dropped. The two are disjoint now: a confirmed save has
 *   no outstanding request, and leaving the old key would re-create the same
 *   inversion one field over.
 * - `worldSaved=false` → `saveRequestedAt` is left exactly as it is. That is the
 *   wedge, and it must survive an upgrade or a delivered-but-unconfirmed save
 *   gets re-sent to a live server on the next pass.
 * - `worldSaved=true` with no `saveRequestedAt` — no instant to carry, so the
 *   confirmation is dropped rather than invented. The drain saves again, which
 *   costs one `save-all flush` on a server it has already confirmed empty. No
 *   combination of the code that wrote these rows produces this; it is here so
 *   that a hand-repaired row degrades toward saving again rather than toward
 *   stopping on evidence nobody can date.
 *
 * The key is removed in every case, so nothing downstream can read the flag by
 * accident.
 *
 * Like [V2StatusDrainProjection] this works at the key level and never builds a
 * `:schema` object — it has to keep producing the same result after `DrainStatus`
 * moves again. It does use the document codec to re-render, so it asserts the
 * encoding it was written against rather than silently rewriting a shape it does
 * not understand.
 */
private object V3SplitWorldSavedInstant : Migration {
    override val version: Int = 3
    override val description: String = "split a confirmed world save out of the save-request timestamp"

    private const val FLAG = "drain.worldSaved"
    private const val REQUESTED = "drain.saveRequestedAt"
    private const val CONFIRMED = "drain.worldSavedAt"

    override fun apply(connection: Connection) {
        val rewritten = mutableListOf<Pair<String, String>>()
        connection.query("SELECT name, status_doc, doc_encoding FROM server_status") { rows ->
            while (rows.next()) {
                val name = rows.getString("name")
                val encoding = rows.getInt("doc_encoding")
                val what = "status of `$name`"
                if (encoding != PropertyDocument.ENCODING_VERSION) {
                    // Loudly, and before anything is written: a migration that
                    // skipped rows it did not recognise would leave some drains
                    // reading a confirmed save as an outstanding request, which
                    // is the whole failure this exists to prevent.
                    throw StoreException.Corrupt(
                        "$what is encoded at version $encoding, but this migration only understands " +
                            "${PropertyDocument.ENCODING_VERSION}",
                    )
                }
                val document = PropertyDocument.parse(rows.getString("status_doc"), what)
                if (!document.has(FLAG)) continue
                rewritten += name to rewrite(document)
            }
        }
        for ((name, document) in rewritten) {
            connection.update("UPDATE server_status SET status_doc = ? WHERE name = ?") {
                setString(1, document)
                setString(2, name)
            }
        }
    }

    private fun rewrite(document: DocumentReader): String {
        val confirmed = document.string(FLAG) == true.toString()
        val requestedAt = document.string(REQUESTED)
        val writer = DocumentWriter()
        for (key in document.keys()) {
            if (key == FLAG || key == REQUESTED) continue
            writer.put(key, document.string(key))
        }
        when {
            confirmed && requestedAt != null -> writer.put(CONFIRMED, requestedAt)
            !confirmed && requestedAt != null -> writer.put(REQUESTED, requestedAt)
            else -> Unit
        }
        return writer.render()
    }
}

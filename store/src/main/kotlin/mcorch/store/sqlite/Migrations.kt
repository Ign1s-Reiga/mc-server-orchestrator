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
 * ## Adding version 6
 *
 * 1. Write a `V6Something : Migration` below with `version = 6`.
 * 2. Append it to [ALL]. Do not renumber, do not reorder, do not touch V1 to V5.
 * 3. If it reads a stored document, it must check `doc_encoding` against a frozen
 *    literal and refuse anything else, rather than parse whatever is on disk.
 *    [V3SplitWorldSavedInstant] is the worked example, down to why the literal is
 *    not `PropertyDocument.ENCODING_VERSION`. [V2StatusDrainProjection] has no such
 *    check because it shipped before the rule, which is a grandfathered gap and not
 *    a precedent.
 * 4. Add a case to the migration test: write data through the store at the previous
 *    version, migrate, assert every field is still there and anything new is
 *    correctly derived from the data that was already on disk.
 *
 * A store whose recorded version is *higher* than [latest] is refused outright.
 * An older binary that reinterpreted a newer layout would be the one failure mode
 * that loses data silently instead of loudly.
 */
internal object Migrations {
    private val logger = LoggerFactory.getLogger(Migrations::class.java)

    val ALL: List<Migration> =
        listOf(
            V1BaseSchema,
            V2StatusDrainProjection,
            V3SplitWorldSavedInstant,
            V4RejectUnnamedRows,
            V5BlockedDrainIsNotAFailure,
        )

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
 *
 * **It has no encoding guard, and is deliberately not being given one.** It parses
 * `status_doc` with today's parser whatever `doc_encoding` says, where
 * [V3SplitWorldSavedInstant] refuses a row at an encoding it was not written
 * against. This one predates that guard, and retrofitting it would turn a
 * migration that has already succeeded on real disks into one that refuses —
 * exactly the change "never edited once shipped" exists to prevent. The gap stays
 * theoretical while encoding 1 is the only one ever written, and a store past
 * version 2 never runs this again. So: do not "fix" it. New migrations carry the
 * guard instead; see [Migrations].
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
 * A row carrying the old flag *and* a `worldSavedAt` is refused, because no code
 * that wrote these rows could produce one and this migration cannot tell which of
 * the two confirmations an operator meant.
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

    /**
     * The document encoding this migration was written against, pinned as a
     * literal on purpose rather than read from [PropertyDocument.ENCODING_VERSION].
     *
     * It reads like a magic number, so: a shipped migration is history and the
     * question it asks has to stay the same question for ever. Asked against the
     * live constant it becomes *today's* question instead, and the day the
     * encoding is bumped this migration starts refusing exactly the rows it was
     * written to rewrite — every store still at version 2 stops opening, on an
     * upgrade path nobody edited. Loud and lossless, but stranded. That is the
     * "never edit a shipped migration" rule broken without anyone editing
     * anything. Handling a later encoding is the job of the migration that
     * introduces it.
     */
    private const val ENCODING_WRITTEN_AGAINST = 1

    override fun apply(connection: Connection) {
        val rewritten = mutableListOf<Pair<String, String>>()
        connection.query("SELECT name, status_doc, doc_encoding FROM server_status") { rows ->
            while (rows.next()) {
                val name = rows.getString("name")
                val encoding = rows.getInt("doc_encoding")
                val what = "status of `$name`"
                if (encoding != ENCODING_WRITTEN_AGAINST) {
                    // Loudly, and before anything is written: a migration that
                    // skipped rows it did not recognise would leave some drains
                    // reading a confirmed save as an outstanding request, which
                    // is the whole failure this exists to prevent.
                    throw StoreException.Corrupt(
                        "$what is encoded at version $encoding, but this migration only understands " +
                            "$ENCODING_WRITTEN_AGAINST",
                    )
                }
                val document = PropertyDocument.parse(rows.getString("status_doc"), what)
                if (!document.has(FLAG)) continue
                rewritten += name to rewrite(document, what)
            }
        }
        for ((name, document) in rewritten) {
            connection.update("UPDATE server_status SET status_doc = ? WHERE name = ?") {
                setString(1, document)
                setString(2, name)
            }
        }
    }

    private fun rewrite(
        document: DocumentReader,
        what: String,
    ): String {
        val confirmed = document.string(FLAG) == true.toString()
        val requestedAt = document.string(REQUESTED)
        val writer = DocumentWriter()
        for (key in document.keys()) {
            if (key == FLAG || key == REQUESTED) continue
            writer.put(key, document.string(key))
        }
        // The copy loop above already carried any existing `CONFIRMED` across, so
        // the branch below would write a second one on top of it and trip
        // `DocumentWriter`'s duplicate-key guard — leaving an
        // `IllegalArgumentException` to cross the store boundary, where `:core`
        // has only `StoreException.retryable` to classify a failure by. Refuse in
        // the store's own vocabulary instead, on exactly the condition that would
        // have collided so the decision table below keeps its behaviour, and name
        // the row: a store that will not open is the moment an operator most
        // needs to be told which row to go and look at.
        if (confirmed && requestedAt != null && document.has(CONFIRMED)) {
            throw StoreException.Corrupt(
                "$what carries both `$FLAG` and `$CONFIRMED`. Nothing that wrote these rows could " +
                    "produce that combination, so the row has been edited by hand and this migration " +
                    "cannot tell which of the two confirmations is the real one. Refusing to guess: " +
                    "remove one of the two keys and open the store again",
            )
        }
        when {
            confirmed && requestedAt != null -> writer.put(CONFIRMED, requestedAt)
            !confirmed && requestedAt != null -> writer.put(REQUESTED, requestedAt)
            else -> Unit
        }
        return writer.render()
    }
}

/**
 * Closes the hole that let a row exist with no name.
 *
 * `server_definition.name` and `server_status.name` are `TEXT PRIMARY KEY`
 * without `NOT NULL`, and SQLite permits NULL in a rowid table's primary key —
 * a long-standing documented quirk kept for backwards compatibility. Confirmed
 * on this schema with a raw insert, not assumed. Nothing the store writes can
 * produce one (`setString` is fed a [mcorch.schema.ResourceName]), so the vector
 * is raw SQL against the file; the tenth drain audit found that the read path
 * then raised a `NullPointerException`, which is not a
 * [mcorch.store.StoreException] and so escaped every guard the reconcile loop
 * had, killing the process on every start.
 *
 * ## Why a trigger and not `NOT NULL`
 *
 * SQLite cannot add a constraint to an existing column; the documented route is
 * to rebuild the table and copy. Measured on this schema, that route is worse
 * than the defect:
 *
 * - `DROP TABLE server_definition` with `PRAGMA foreign_keys = ON` — which this
 *   store sets, and depends on for the purge cascade — performs an implicit
 *   `DELETE FROM` first, so `server_status`'s `ON DELETE CASCADE` fires and
 *   **every observation on disk is deleted**. Reproduced: one definition and one
 *   status in, one definition and zero statuses out. The observation is where
 *   "the save request already went out" lives, so that is every in-flight drain
 *   losing the record that stops it re-issuing a save against a live server.
 *   Turning the pragma off is not available either: it is a no-op inside a
 *   transaction, and migrations run in one by design.
 * - A rebuild also cannot copy a row that already has a NULL name, so the
 *   migration written to fix the problem would refuse to open exactly the store
 *   that has it.
 *
 * The triggers reject the same writes the constraint would, from raw SQL
 * included, while touching no data and dropping nothing. A row that is already
 * unnamed is left alone deliberately: it stays readable as an unreadable entry,
 * so an operator keeps being told about it every resync instead of it being
 * quietly deleted or renamed to something invented.
 *
 * No document is read here, so there is no encoding to pin.
 */
private object V4RejectUnnamedRows : Migration {
    override val version: Int = 4
    override val description: String = "reject rows written with no name, which SQLite's primary key allows"

    override fun apply(connection: Connection) {
        for (table in listOf("server_definition", "server_status")) {
            connection.execute(guard(table, "insert", "BEFORE INSERT ON $table"))
            connection.execute(guard(table, "update", "BEFORE UPDATE OF name ON $table"))
        }
    }

    /**
     * `WHEN NEW.name IS NULL` rather than a bare `RAISE`, so the trigger costs a
     * null check on the write path and nothing else. `BEFORE UPDATE OF name`
     * narrows it further: the store never updates a name, so the ordinary write
     * path does not reach this at all.
     */
    private fun guard(
        table: String,
        event: String,
        on: String,
    ): String =
        """
        CREATE TRIGGER ${table}_name_not_null_$event
        $on
        WHEN NEW.name IS NULL
        BEGIN
            SELECT RAISE(ABORT, '$table.name must not be NULL: a row nothing can refer to cannot be read, reconciled or purged');
        END
        """.trimIndent()
}

/**
 * Turns a drain that was blocked on players into a *block* rather than a failure.
 *
 * **No column changes, and it still needs a migration**, for the same reason
 * [V3SplitWorldSavedInstant] did: the meaning of an existing key changed. A row
 * written before this with `drain.failure.reason=DRAIN_NO_DESTINATION` says
 * *there are players on this server and nowhere to send them, which is the
 * protocol working*. That reason now means something else entirely — *a
 * destination was searched for and the fleet had no capacity* — and the two want
 * opposite treatment. Read by the new code the same bytes would put a healthy
 * server's drain into the escalation path, so every stored one raises
 * `NEEDS_ATTENTION` the moment it is older than `drainAttentionAfter`. Which is
 * to say: every drain that was quietly waiting for a busy evening to end comes
 * back from an upgrade calling for a human, and the operator is sent to look at a
 * server where people are simply playing. That is the exact alert-fatigue failure
 * this whole change removes, delivered by the upgrade that removed it.
 *
 * So each status document is rewritten:
 *
 * - `drain.failure.*` with a retired reason becomes `drain.blocked.*`. The
 *   message carries over verbatim — it described what was true and still does —
 *   `occurredAt` becomes `since` and `attempts` becomes `observations`. Those two
 *   are renames, not re-derivations: an operator's "waiting since" must not jump
 *   to the moment of the upgrade.
 * - the *top-level* `failure.*` with a retired reason is dropped. The reconciler
 *   copies a drain's failure onto `status.failure`, so these rows carry the same
 *   fact twice, and a blocked drain records no failure at either level.
 *
 * Two things are deliberately left alone. The `drain_state` projection column
 * still reads `DRAIN_FAILED`, because a blocked drain still parks there — the
 * state means *not advancing*, and only the record beside it says why. And the
 * stored `conditions` array is untouched: the next pass re-derives every
 * condition from the fields, and a migration inventing a `lastTransitionAt` for
 * the new `DRAIN_BLOCKED` entry would be dating something it cannot know. Both
 * self-correct on the first pass after the upgrade; neither is read for a drain
 * decision in the meantime.
 *
 * `DRAIN_AWAITING_ZERO_PLAYERS` is handled beside `DRAIN_NO_DESTINATION` even
 * though no released build ever wrote it. It existed for exactly one commit, as
 * the intended home for the waiting case before it was decided the case is not a
 * failure at all, and a dev store opened against that commit is a real file on
 * somebody's disk. Being total over the retired vocabulary costs one branch;
 * being nearly total costs a store that will not open, because the value is no
 * longer in the enum and the decode would report the row corrupt.
 *
 * Like V2 and V3 this works at the key level and never builds a `:schema` object,
 * so it keeps producing the same result after `DrainStatus` moves again — and it
 * pins the document encoding it was written against for the reason set out on
 * [V3SplitWorldSavedInstant.ENCODING_WRITTEN_AGAINST].
 */
private object V5BlockedDrainIsNotAFailure : Migration {
    override val version: Int = 5
    override val description: String = "record a drain blocked on players as a block rather than as a failure"

    private const val DRAIN_FAILURE = "drain.failure"
    private const val DRAIN_BLOCK = "drain.blocked"
    private const val STATUS_FAILURE = "failure"

    /**
     * The reasons that meant "waiting for players" when these rows were written.
     *
     * String literals rather than `FailureReason` values, and that is the rule
     * rather than an accident: one of the two is not in the enum any more, and the
     * other one's *meaning* is what this migration exists to correct. A shipped
     * migration has to keep asking the question it was written to ask, which it
     * cannot do through a type that has since moved on.
     */
    private val RETIRED = setOf("DRAIN_NO_DESTINATION", "DRAIN_AWAITING_ZERO_PLAYERS")

    /** Frozen too, for the same reason. See [RETIRED]. */
    private const val BLOCK_REASON = "AWAITING_ZERO_PLAYERS"

    /** See [V3SplitWorldSavedInstant.ENCODING_WRITTEN_AGAINST]: pinned, never read from the live constant. */
    private const val ENCODING_WRITTEN_AGAINST = 1

    override fun apply(connection: Connection) {
        val rewritten = mutableListOf<Pair<String, String>>()
        connection.query("SELECT name, status_doc, doc_encoding FROM server_status") { rows ->
            while (rows.next()) {
                val name = rows.getString("name")
                val encoding = rows.getInt("doc_encoding")
                val what = "status of `$name`"
                if (encoding != ENCODING_WRITTEN_AGAINST) {
                    // Loudly, and before anything is written. A migration that
                    // skipped rows it did not recognise would leave some drains
                    // reading a healthy wait as a fleet-capacity failure, which is
                    // the whole thing this exists to prevent.
                    throw StoreException.Corrupt(
                        "$what is encoded at version $encoding, but this migration only understands " +
                            "$ENCODING_WRITTEN_AGAINST",
                    )
                }
                val document = PropertyDocument.parse(rows.getString("status_doc"), what)
                if (!document.retired(DRAIN_FAILURE) && !document.retired(STATUS_FAILURE)) continue
                rewritten += name to rewrite(document, what)
            }
        }
        for ((name, document) in rewritten) {
            connection.update("UPDATE server_status SET status_doc = ? WHERE name = ?") {
                setString(1, document)
                setString(2, name)
            }
        }
    }

    private fun DocumentReader.retired(prefix: String): Boolean = string("$prefix.reason") in RETIRED

    private fun rewrite(
        document: DocumentReader,
        what: String,
    ): String {
        val block = document.retired(DRAIN_FAILURE)
        // The copy loop below carries any pre-existing `drain.blocked.*` across,
        // so writing the derived one on top would trip `DocumentWriter`'s
        // duplicate-key guard and leave an `IllegalArgumentException` crossing the
        // store boundary — where `:core` has only `StoreException.retryable` to
        // classify by. Nothing that wrote these rows could produce the
        // combination, so refuse in the store's own vocabulary and name the row: a
        // store that will not open is when an operator most needs to be told which
        // one to go and look at.
        if (block && document.has("$DRAIN_BLOCK.reason")) {
            throw StoreException.Corrupt(
                "$what records a drain that is both blocked and failed with `${document.string(
                    "$DRAIN_FAILURE.reason",
                )}`. Nothing that wrote these rows could produce that combination, so the row has been edited " +
                    "by hand and this migration cannot tell which of the two the operator meant. Refusing to " +
                    "guess: remove one of `$DRAIN_BLOCK` or `$DRAIN_FAILURE` and open the store again",
            )
        }
        val writer = DocumentWriter()
        for (key in document.keys()) {
            if (block && key.startsWith("$DRAIN_FAILURE.")) continue
            // The reconciler mirrors a drain's failure onto the status, so this is
            // the same fact one level up. A blocked drain has neither.
            if (document.retired(STATUS_FAILURE) && key.startsWith("$STATUS_FAILURE.")) continue
            writer.put(key, document.string(key))
        }
        if (block) {
            writer.put("$DRAIN_BLOCK.reason", BLOCK_REASON)
            // Verbatim. It said "the server keeps running, the drain resumes once
            // it is empty", which is exactly as true after this migration as
            // before it, and rewriting operator prose in a migration is how a
            // stored message stops matching the code that produced it.
            writer.put("$DRAIN_BLOCK.message", document.string("$DRAIN_FAILURE.message"))
            writer.put("$DRAIN_BLOCK.since", document.string("$DRAIN_FAILURE.occurredAt"))
            writer.put("$DRAIN_BLOCK.observations", document.string("$DRAIN_FAILURE.attempts"))
        }
        return writer.render()
    }
}

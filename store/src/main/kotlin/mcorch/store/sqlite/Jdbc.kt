package mcorch.store.sqlite

import mcorch.store.StoreException
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The only place in this module that knows what JDBC is.
 *
 * Everything here is `internal`, and nothing it defines appears in a signature
 * outside `mcorch.store.sqlite`. That is the compiler-enforced half of "the Store
 * interface must not leak SQLite".
 */
internal fun Connection.execute(sql: String) {
    try {
        createStatement().use { it.execute(sql) }
    } catch (failure: SQLException) {
        throw failure.asStoreException("executing statement")
    }
}

internal fun <T> Connection.query(
    sql: String,
    bind: PreparedStatement.() -> Unit = {},
    read: (ResultSet) -> T,
): T =
    try {
        prepareStatement(sql).use { statement ->
            statement.bind()
            statement.executeQuery().use(read)
        }
    } catch (failure: SQLException) {
        throw failure.asStoreException("running query")
    }

internal fun Connection.update(
    sql: String,
    bind: PreparedStatement.() -> Unit = {},
): Int =
    try {
        prepareStatement(sql).use { statement ->
            statement.bind()
            statement.executeUpdate()
        }
    } catch (failure: SQLException) {
        throw failure.asStoreException("running update")
    }

/**
 * Runs [block] in one transaction and commits it, or rolls back and rethrows.
 *
 * Deliberately `internal` and never handed to a caller: an interactive
 * transaction is the thing a distributed store cannot offer, so the [mcorch.store.Store]
 * interface exposes compare-and-swap instead and this stays an implementation
 * detail of how a single call is made atomic.
 */
internal fun <T> Connection.transaction(block: (Connection) -> T): T {
    try {
        val result = block(this)
        // Classified like every other JDBC failure rather than allowed to escape raw:
        // a COMMIT can itself lose a race for the write lock, and that has to reach the
        // loop as a retryable StoreException, not as a bare SQLException nothing above
        // this module knows how to read.
        try {
            commit()
        } catch (failure: SQLException) {
            throw failure.asStoreException("committing transaction")
        }
        return result
    } catch (failure: Throwable) {
        try {
            rollback()
        } catch (rollbackFailure: SQLException) {
            failure.addSuppressed(rollbackFailure)
        }
        throw failure
    }
}

internal fun ResultSet.stringOrNull(column: String): String? {
    val value = getString(column)
    return if (wasNull()) null else value
}

/**
 * A text column that the types above this expect to be present.
 *
 * `getString` returns Java's `null`, and Kotlin types the result as a platform
 * type — so a NULL flowing into a non-null parameter raises a
 * `NullPointerException` rather than anything [StoreException] shaped, and
 * [asStoreException] never sees it because it is not an `SQLException`. That is
 * not a theoretical gap: `server_definition.name` is `TEXT PRIMARY KEY` with no
 * `NOT NULL`, and SQLite permits NULL in a rowid table's primary key.
 *
 * Every read of a column whose Kotlin counterpart is non-null goes through here,
 * including the ones whose `NOT NULL` makes it unreachable today. The cost is a
 * branch; the alternative is that the next column declared without `NOT NULL`
 * reaches the reconcile loop as an exception it does not catch.
 */
internal fun ResultSet.requiredString(
    column: String,
    what: String,
): String =
    stringOrNull(column)
        ?: throw StoreException.Corrupt("$what: column `$column` is unexpectedly null")

internal fun ResultSet.instant(
    column: String,
    what: String,
): Instant = instantOrNull(column, what) ?: throw StoreException.Corrupt("$what: column `$column` is unexpectedly null")

internal fun ResultSet.instantOrNull(
    column: String,
    what: String,
): Instant? {
    val raw = stringOrNull(column) ?: return null
    return try {
        Instant.parse(raw)
    } catch (failure: DateTimeParseException) {
        throw StoreException.Corrupt("$what: column `$column` is not an ISO-8601 instant", failure)
    }
}

internal fun PreparedStatement.setInstant(
    index: Int,
    value: Instant?,
) {
    if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value.toString())
}

/**
 * Classifies a JDBC failure once, so nothing downstream has to guess whether to
 * requeue. Everything that is not clearly transient is treated as permanent —
 * retrying a permanent failure forever hides it.
 *
 * The signal is SQLite's result code, never the message text. `:cri` refuses to
 * classify by message for the same reason (see `mcorch.cri.internal.translateStatus`):
 * descriptions are free-form and change between releases. Here the consequence of
 * getting it wrong is specific — a `SQLITE_BUSY` whose wording drifts in a driver
 * bump would become [StoreException.Failed], the reconcile pass would fail
 * permanently, and the server would sit out of the queue until the next full
 * resync. Silently, and only while the database is under contention.
 *
 * Naming `org.sqlite` types here is deliberate and contained: every declaration in
 * this package is `internal` and sqlite-jdbc is an `implementation` dependency, so
 * none of it can appear in the [mcorch.store.Store] interface.
 */
internal fun SQLException.asStoreException(what: String): StoreException {
    val summary = "$what failed: ${message ?: this::class.simpleName}"
    return if (isTransient()) StoreException.Unavailable(summary, this) else StoreException.Failed(summary, this)
}

/**
 * True when SQLite itself said the failure was contention.
 *
 * A failure with no [SQLiteException] in its cause chain is permanent by
 * definition here, and there is deliberately no message-text fallback: the bare
 * `SQLException`s sqlite-jdbc raises come from its JDBC layer rather than from the
 * engine — a closed connection, a statement that is not executing, an invalid
 * parameter index, an unsupported JDBC feature — and every one of those is a
 * lifecycle or usage bug that retrying cannot fix.
 */
private fun SQLException.isTransient(): Boolean {
    val code = sqliteResultCode() ?: return false
    return (code.code and PRIMARY_CODE_MASK) in TRANSIENT_PRIMARY_CODES
}

/** The first SQLite result code in this failure's cause chain, if any. */
private fun SQLException.sqliteResultCode(): SQLiteErrorCode? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is SQLiteException) return current.resultCode
        current = current.cause
        depth++
    }
    return null
}

/**
 * Extended result codes are `primary or (detail shl 8)` — part of SQLite's C API
 * contract, not a driver detail — so masking the low byte folds every extended
 * code into the family it belongs to.
 */
private const val PRIMARY_CODE_MASK: Int = 0xFF

/** Bounded so a cause chain that refers back to itself cannot spin. */
private const val MAX_CAUSE_DEPTH: Int = 8

/**
 * The result codes worth trying again, as primary codes.
 *
 * - `SQLITE_BUSY`, `SQLITE_LOCKED`: another writer holds the lock. This is the case
 *   the classification exists for, and it covers the whole extended family —
 *   `SQLITE_BUSY_SNAPSHOT`, `SQLITE_BUSY_TIMEOUT`, `SQLITE_BUSY_RECOVERY`,
 *   `SQLITE_LOCKED_SHAREDCACHE` and anything a later SQLite adds to either.
 * - `SQLITE_PROTOCOL`: a locking-protocol problem SQLite gave up retrying for us.
 *   Its documentation says to try again later.
 * - `SQLITE_INTERRUPT`: the statement was cancelled before it committed anything, so
 *   the same call can simply be made again.
 *
 * Everything else is permanent on purpose. `SQLITE_IOERR`, `SQLITE_FULL`,
 * `SQLITE_CORRUPT`, `SQLITE_READONLY`, `SQLITE_NOTADB` and the constraint family
 * all need a human or a code change, and spinning on them would hide them.
 */
private val TRANSIENT_PRIMARY_CODES: Set<Int> =
    setOf(
        SQLiteErrorCode.SQLITE_BUSY,
        SQLiteErrorCode.SQLITE_LOCKED,
        SQLiteErrorCode.SQLITE_PROTOCOL,
        SQLiteErrorCode.SQLITE_INTERRUPT,
    ).mapTo(mutableSetOf()) { it.code and PRIMARY_CODE_MASK }

package mcorch.store.sqlite

import mcorch.store.StoreException
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
        commit()
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
 */
internal fun SQLException.asStoreException(what: String): StoreException {
    val text = (message ?: "").lowercase()
    val transient =
        "database is locked" in text ||
            "database table is locked" in text ||
            "busy" in text ||
            "interrupted" in text
    val summary = "$what failed: ${message ?: this::class.simpleName}"
    return if (transient) StoreException.Unavailable(summary, this) else StoreException.Failed(summary, this)
}

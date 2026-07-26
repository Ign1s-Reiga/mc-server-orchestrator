package mcorch.store.sqlite

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.store.StoreException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Whether a storage failure requeues is decided by SQLite's result code, and by
 * nothing else.
 *
 * This is the classification the reconcile loop acts on: [StoreException.Unavailable]
 * requeues with backoff, everything else fails the pass and the server waits for the
 * five-minute resync. Getting a `SQLITE_BUSY` wrong therefore drops a server out of
 * the queue precisely when the database is contended — silently, because a permanent
 * failure looks exactly like a real one.
 *
 * Every message below is chosen to disagree with the classification the result code
 * asks for, so a return to matching on message substrings fails these tests instead
 * of passing them.
 */
class JdbcErrorClassificationTest {
    private val directories = mutableListOf<Path>()

    @AfterEach
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }

    @Test
    fun `contention is retryable however the driver words it`() {
        // Not one of these messages contains "busy", "locked" or "interrupted".
        val contention =
            listOf(
                SQLiteErrorCode.SQLITE_BUSY to "no such table: server_definition",
                SQLiteErrorCode.SQLITE_LOCKED to "constraint failed",
                SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT to "cannot start a transaction within a transaction",
                SQLiteErrorCode.SQLITE_BUSY_TIMEOUT to "query aborted",
                SQLiteErrorCode.SQLITE_BUSY_RECOVERY to "query aborted",
                SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE to "query aborted",
                SQLiteErrorCode.SQLITE_PROTOCOL to "disk I/O error",
                SQLiteErrorCode.SQLITE_INTERRUPT to "unable to open database file",
            )

        val misclassified =
            contention
                .map { (code, message) -> code to SQLiteException(message, code).asStoreException("running update") }
                .filter { (_, classified) -> !classified.retryable }
                .map { (code, _) -> code.name }

        misclassified shouldContainExactly emptyList()
    }

    @Test
    fun `a failure that needs a human is permanent however the driver words it`() {
        // Every message here reads like contention. None of them is.
        val permanent =
            listOf(
                SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE to "database is locked",
                SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY to "database table is locked",
                SQLiteErrorCode.SQLITE_CORRUPT to "the database is busy",
                SQLiteErrorCode.SQLITE_FULL to "statement was interrupted",
                SQLiteErrorCode.SQLITE_READONLY to "database is locked",
                SQLiteErrorCode.SQLITE_NOTADB to "busy",
                SQLiteErrorCode.SQLITE_IOERR_WRITE to "interrupted",
                SQLiteErrorCode.SQLITE_MISUSE to "database is locked",
            )

        val misclassified =
            permanent
                .map { (code, message) -> code to SQLiteException(message, code).asStoreException("running update") }
                .filter { (_, classified) -> classified.retryable }
                .map { (code, _) -> code.name }

        misclassified shouldContainExactly emptyList()
    }

    @Test
    fun `a busy failure is Unavailable, and a constraint failure is Failed`() {
        val busy = SQLiteException("no such column: doc", SQLiteErrorCode.SQLITE_BUSY)
        val constraint = SQLiteException("database is locked", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)

        busy.asStoreException("running update").shouldBeInstanceOf<StoreException.Unavailable>()
        constraint.asStoreException("running update").shouldBeInstanceOf<StoreException.Failed>()
    }

    @Test
    fun `a result code buried under a wrapper is still found`() {
        val wrapped =
            SQLException("running update failed", SQLiteException("nothing to see here", SQLiteErrorCode.SQLITE_BUSY))

        wrapped.asStoreException("running update").retryable shouldBe true
    }

    @Test
    fun `a plain SQLException is permanent even when it talks about locks`() {
        // Deliberate: the bare SQLExceptions sqlite-jdbc raises are JDBC-layer usage
        // errors, and there is no message-text fallback to rescue this one.
        val failure = SQLException("database is locked")

        failure.asStoreException("running update").shouldBeInstanceOf<StoreException.Failed>()
    }

    @Test
    fun `the message is still carried into the classified failure`() {
        val failure = SQLiteException("no such table: server_definition", SQLiteErrorCode.SQLITE_ERROR)

        val classified = failure.asStoreException("running query")

        classified.message shouldBe "running query failed: no such table: server_definition"
        classified.cause shouldBe failure
    }

    @Test
    fun `a real SQLITE_BUSY from a contended database is retryable`() {
        // The constructed cases above pin the mapping; this one pins that the mapping
        // is against what SQLite actually raises when two writers meet.
        val directory = Files.createTempDirectory("mcorch-busy").also { directories.add(it) }
        val path = directory.resolve("contended.db").toAbsolutePath()

        connect(path, busyTimeoutMillis = 0).use { holder ->
            holder.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            holder.commit()

            connect(path, busyTimeoutMillis = 0).use { contender ->
                // The holder takes the write lock and keeps it: no commit inside this block.
                holder.update("INSERT INTO t (id) VALUES (1)")

                val failure =
                    runCatching { contender.update("INSERT INTO t (id) VALUES (2)") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<StoreException.Unavailable>()

                failure.retryable shouldBe true
                // The cause really is the engine saying BUSY, not a lookalike.
                val cause = failure.cause.shouldBeInstanceOf<SQLiteException>()
                (cause.resultCode.code and 0xFF) shouldBe SQLiteErrorCode.SQLITE_BUSY.code
            }
            holder.rollback()
        }
    }

    @Test
    fun `a commit failure is classified rather than escaping raw`() {
        // The commit is the one JDBC call in `transaction` that is not already behind
        // execute/query/update, and a COMMIT can fail on its own. Whatever it throws has
        // to reach the loop as a StoreException like everything else does.
        val directory = Files.createTempDirectory("mcorch-commit").also { directories.add(it) }
        val connection = connect(directory.resolve("state.db").toAbsolutePath(), busyTimeoutMillis = 0)
        connection.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        connection.commit()
        connection.close()

        val failure =
            runCatching { connection.transaction { } }
                .exceptionOrNull()
                .shouldBeInstanceOf<StoreException>()

        failure.message shouldBe "committing transaction failed: ${(failure.cause as SQLException).message}"
    }

    /** A raw connection, configured like the store's own but refusing to wait for a lock. */
    private fun connect(
        path: Path,
        busyTimeoutMillis: Int,
    ): Connection =
        DriverManager.getConnection("jdbc:sqlite:$path").also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA busy_timeout = $busyTimeoutMillis")
            }
            connection.autoCommit = false
        }
}

package mcorch.store.sqlite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import mcorch.store.SecretStore
import mcorch.store.Store
import mcorch.store.StoreException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock

/**
 * How the single-host store is opened.
 *
 * This is the whole public surface of the SQLite backend. [open] hands back
 * [Store] and [SecretStore] — the interface types — and the classes behind them
 * are `internal`, so nothing outside this module can name an implementation, call
 * a method that is not on the interface, or accidentally grow a dependency on
 * SQLite. Swapping in a distributed backend is then a change to whoever calls
 * [open], and to nothing else.
 */
public class EmbeddedStore private constructor(
    /** Desired and observed state. */
    public val state: Store,
    /** Secret material. A separate interface backed by a separate file — see [SecretStore]. */
    public val secrets: SecretStore,
) : AutoCloseable {
    override fun close() {
        // Both are closed even if the first throws: leaking a file handle because the
        // other end failed would leave a locked database behind.
        try {
            state.close()
        } finally {
            secrets.close()
        }
    }

    public companion object {
        private val logger = LoggerFactory.getLogger(EmbeddedStore::class.java)

        /**
         * Opens (creating if needed) the state and secret databases under
         * [EmbeddedStoreConfig.directory] and migrates both schemas up.
         *
         * Refuses to open a database written by a newer build: see
         * [StoreException.Unsupported].
         */
        public fun open(config: EmbeddedStoreConfig): EmbeddedStore {
            require(config.changeLogRetention > 0) {
                "changeLogRetention must be positive, found ${config.changeLogRetention}"
            }
            createDirectory(config.directory)
            val statePath = config.directory.resolve(config.stateFileName)
            val secretsPath = config.directory.resolve(config.secretsFileName)

            val stateConnection = connect("jdbc:sqlite:${statePath.toAbsolutePath()}")
            val secretsConnection =
                try {
                    connect("jdbc:sqlite:${secretsPath.toAbsolutePath()}")
                } catch (failure: Throwable) {
                    closeQuietly(stateConnection)
                    throw failure
                }
            return try {
                restrictPermissions(statePath)
                restrictPermissions(secretsPath)
                val report = Migrations.migrate(stateConnection, config.clock)
                SecretSchema.migrate(secretsConnection, config.clock)
                logger.info(
                    "opened embedded store directory={} schemaVersion={} migrationsApplied={}",
                    config.directory,
                    report.to,
                    report.applied.size,
                )
                EmbeddedStore(
                    state =
                        SqliteStore(
                            connection = stateConnection,
                            clock = config.clock,
                            dispatcher = config.dispatcher,
                            changeLogRetention = config.changeLogRetention,
                        ),
                    secrets =
                        SqliteSecretStore(
                            connection = secretsConnection,
                            clock = config.clock,
                            dispatcher = config.dispatcher,
                        ),
                )
            } catch (failure: Throwable) {
                closeQuietly(stateConnection)
                closeQuietly(secretsConnection)
                throw failure
            }
        }

        private fun connect(url: String): Connection =
            try {
                DriverManager.getConnection(url).also { connection ->
                    // Pragmas run before the connection stops auto-committing: SQLite will
                    // not change the journal mode inside a transaction.
                    connection.createStatement().use { statement ->
                        // A status can never be left behind pointing at a definition that is
                        // gone, and that is enforced by the file rather than by the code above
                        // it — but only if foreign keys are actually switched on, which SQLite
                        // does not do by default.
                        statement.execute("PRAGMA foreign_keys = ON")
                        statement.execute("PRAGMA journal_mode = WAL")
                        // FULL, not NORMAL. The expensive thing here is an fsync per commit;
                        // the cheap thing is losing the last few status writes to a power cut,
                        // and one of those writes is "the save request already went out". A
                        // drain that forgets that re-sends it against a live server.
                        statement.execute("PRAGMA synchronous = FULL")
                        statement.execute("PRAGMA busy_timeout = 5000")
                    }
                    connection.autoCommit = false
                }
            } catch (failure: SQLException) {
                throw failure.asStoreException("opening `$url`")
            }

        private fun createDirectory(directory: Path) {
            try {
                Files.createDirectories(directory)
            } catch (failure: IOException) {
                throw StoreException.Unavailable("could not create the store directory `$directory`", failure)
            }
        }

        /**
         * Owner-only, on the state database as well as the secret one. The state
         * database holds no secret material, but it does hold the full topology of
         * every server this orchestrator runs.
         *
         * Best effort: a filesystem that does not do POSIX permissions is not a reason
         * to refuse to start, but it is a reason to say so out loud.
         */
        private fun restrictPermissions(path: Path) {
            if (!Files.exists(path)) return
            try {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            } catch (failure: UnsupportedOperationException) {
                logger.warn("filesystem does not support POSIX permissions path={}", path, failure)
            } catch (failure: IOException) {
                logger.warn("could not restrict permissions path={}", path, failure)
            }
        }

        private fun closeQuietly(connection: Connection) {
            try {
                connection.close()
            } catch (failure: SQLException) {
                logger.warn("could not close a store connection while unwinding an open failure", failure)
            }
        }
    }
}

/**
 * Where the embedded store lives and how it behaves.
 *
 * @property directory the directory holding both database files. Created if absent.
 * @property stateFileName the state database. Holds no secret material.
 * @property secretsFileName the secret database. Separate file, never joined against the state one.
 * @property clock source of the store's own timestamps — `createdAt`, `deletedAt`, `recordedAt`.
 *   Injected so tests can make time deterministic rather than sleeping.
 * @property dispatcher where blocking JDBC work runs.
 * @property changeLogRetention how many desired-state changes to keep for [Store.changesSince].
 *   Beyond this the oldest are dropped and a cursor pointing into the dropped range gets
 *   [mcorch.store.ChangeFeed.Expired] rather than a quietly incomplete feed. It is a bound, not an
 *   exact length: trimming is amortised.
 */
public data class EmbeddedStoreConfig(
    val directory: Path,
    val stateFileName: String = "state.db",
    val secretsFileName: String = "secrets.db",
    val clock: Clock = Clock.systemUTC(),
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val changeLogRetention: Int = 10_000,
)

package mcorch.store.sqlite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.store.SecretStore
import mcorch.store.SecretValue
import mcorch.store.StoreException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock

/**
 * Secret material in its own SQLite file.
 *
 * A separate file, not a separate table, so that the property "an ordinary state
 * read cannot return secret material" survives a careless join, a `SELECT *`, and
 * somebody copying the state database somewhere to debug it.
 *
 * ## What this implementation does not do
 *
 * It does not encrypt at rest. Encrypting with a key that lives next to the
 * database is theatre — it changes nothing about who can read the file — and a
 * real answer needs a key-encryption key held somewhere else, which is a
 * deployment decision nobody has made yet. What it does instead is what actually
 * helps on a single host: a file only the owner can read, separate from
 * everything else, behind an interface that hands out [SecretValue] and never a
 * [String]. When there is a KEK story, it goes behind this same interface.
 *
 * Nothing here logs. Not the value, not the name, not a miss — a log line saying
 * which secret was looked up and failed is a map of the system for anyone reading
 * the log.
 */
internal class SqliteSecretStore(
    private val connection: Connection,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
) : SecretStore {
    private val mutex = Mutex()

    @Volatile
    private var closed: Boolean = false

    override suspend fun put(
        ref: SecretRef,
        value: SecretValue,
    ) {
        val material = value.use { chars -> encodeUtf8(chars) }
        try {
            guarded { connection ->
                connection.transaction {
                    connection.update(
                        """
                        INSERT INTO secret (name, key, material, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (name, key) DO UPDATE SET
                            material = excluded.material,
                            updated_at = excluded.updated_at
                        """.trimIndent(),
                    ) {
                        val now = clock.instant().toString()
                        setString(1, ref.name.value)
                        setString(2, ref.key)
                        setBytes(3, material)
                        setString(4, now)
                        setString(5, now)
                    }
                }
            }
        } finally {
            material.fill(0)
        }
    }

    override suspend fun resolve(ref: SecretRef): SecretValue? =
        guarded { connection ->
            connection.transaction {
                connection.query(
                    "SELECT material FROM secret WHERE name = ? AND key = ?",
                    bind = {
                        setString(1, ref.name.value)
                        setString(2, ref.key)
                    },
                ) { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        val material = rows.getBytes("material")
                        try {
                            SecretValue.of(decodeUtf8(material))
                        } finally {
                            material.fill(0)
                        }
                    }
                }
            }
        }

    override suspend fun contains(ref: SecretRef): Boolean =
        guarded { connection ->
            connection.transaction {
                connection.query(
                    "SELECT 1 FROM secret WHERE name = ? AND key = ?",
                    bind = {
                        setString(1, ref.name.value)
                        setString(2, ref.key)
                    },
                ) { rows -> rows.next() }
            }
        }

    override suspend fun removeKey(ref: SecretRef): Boolean =
        guarded { connection ->
            connection.transaction {
                connection.update("DELETE FROM secret WHERE name = ? AND key = ?") {
                    setString(1, ref.name.value)
                    setString(2, ref.key)
                } > 0
            }
        }

    override suspend fun removeSecret(name: ResourceName): Int =
        guarded { connection ->
            connection.transaction {
                connection.update("DELETE FROM secret WHERE name = ?") { setString(1, name.value) }
            }
        }

    override suspend fun listNames(): List<ResourceName> =
        guarded { connection ->
            connection.transaction {
                connection.query("SELECT DISTINCT name FROM secret ORDER BY name") { rows ->
                    val names = mutableListOf<ResourceName>()
                    while (rows.next()) {
                        val raw = rows.getString("name")
                        names +=
                            ResourceName.of(raw).getOrElse {
                                throw StoreException.Corrupt("stored secret name `$raw` is not a valid resource name")
                            }
                    }
                    names
                }
            }
        }

    override suspend fun listKeys(name: ResourceName): List<String> =
        guarded { connection ->
            connection.transaction {
                connection.query(
                    "SELECT key FROM secret WHERE name = ? ORDER BY key",
                    bind = { setString(1, name.value) },
                ) { rows ->
                    val keys = mutableListOf<String>()
                    while (rows.next()) keys += rows.getString("key")
                    keys
                }
            }
        }

    override fun close() {
        closed = true
        try {
            connection.close()
        } catch (failure: SQLException) {
            throw failure.asStoreException("closing the secret store")
        }
    }

    private suspend fun <T> guarded(block: (Connection) -> T): T {
        if (closed) throw StoreException.Closed("the secret store has been closed")
        return withContext(dispatcher) {
            mutex.withLock {
                if (closed) throw StoreException.Closed("the secret store has been closed")
                block(connection)
            }
        }
    }

    /** Encodes without ever building a [String]: a String cannot be wiped. */
    private fun encodeUtf8(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    }

    private fun decodeUtf8(bytes: ByteArray): CharArray {
        val decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(decoded.remaining())
        decoded.get(chars)
        if (decoded.hasArray()) decoded.array().fill('\u0000')
        return chars
    }
}

/**
 * The secret database's own schema and its own version line.
 *
 * Separate from the state store's migrations on purpose: the two files have
 * independent lifetimes, and a secret backend that is not SQLite at all should be
 * able to arrive without dragging a state migration with it.
 */
internal object SecretSchema {
    fun migrate(
        connection: Connection,
        clock: Clock,
    ) {
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
        val current = Migrations.currentVersion(connection)
        if (current > VERSION) {
            throw StoreException.Unsupported(
                "on-disk secret schema is at version $current, but this build only understands up to $VERSION. " +
                    "Refusing to open it rather than reinterpret it",
            )
        }
        if (current == VERSION) return
        connection.transaction {
            connection.execute(
                """
                CREATE TABLE secret (
                    name       TEXT NOT NULL,
                    key        TEXT NOT NULL,
                    material   BLOB NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (name, key)
                )
                """.trimIndent(),
            )
            connection.update("INSERT INTO schema_migration (version, description, applied_at) VALUES (?, ?, ?)") {
                setInt(1, VERSION)
                setString(2, DESCRIPTION)
                setString(3, clock.instant().toString())
            }
        }
    }

    private const val VERSION: Int = 1
    private const val DESCRIPTION: String = "secret material keyed by (name, key)"
}

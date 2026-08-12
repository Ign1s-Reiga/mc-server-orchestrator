package mcorch.store.sqlite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mcorch.schema.ResourceName
import mcorch.schema.Tier
import mcorch.store.Identity
import mcorch.store.IdentityStore
import mcorch.store.StoreException
import java.sql.Connection
import java.sql.SQLException

/**
 * [IdentityStore] over the state database.
 *
 * In the state file rather than the secrets one because it holds no material —
 * a digest is not a credential — and because the state file is where the
 * versioned migration chain lives, which is what a table added later needs.
 */
internal class SqliteIdentityStore(
    private val connection: Connection,
    private val dispatcher: CoroutineDispatcher,
) : IdentityStore {
    private val mutex = Mutex()

    @Volatile
    private var closed: Boolean = false

    override suspend fun put(identity: Identity) {
        guarded {
            connection.transaction {
                connection.update(
                    """
                    INSERT INTO identity (name, credential_digest, tier, enabled, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (name) DO UPDATE SET
                        credential_digest = excluded.credential_digest,
                        tier              = excluded.tier,
                        enabled           = excluded.enabled
                    """.trimIndent(),
                ) {
                    setString(1, identity.name.value)
                    setString(2, identity.credentialDigest)
                    setString(3, identity.tier.wireValue)
                    setInt(4, if (identity.enabled) 1 else 0)
                    setString(5, identity.createdAt.toString())
                }
            }
        }
    }

    override suspend fun get(name: ResourceName): Identity? =
        guarded {
            connection.query(
                "SELECT name, credential_digest, tier, enabled, created_at FROM identity WHERE name = ?",
                { setString(1, name.value) },
            ) { rows -> if (rows.next()) read(rows) else null }
        }

    override suspend fun list(): List<Identity> =
        guarded {
            connection.query(
                "SELECT name, credential_digest, tier, enabled, created_at FROM identity",
            ) { rows ->
                buildList {
                    while (rows.next()) add(read(rows))
                }
            }
        }

    override suspend fun remove(name: ResourceName): Boolean =
        guarded {
            connection.transaction {
                connection.update("DELETE FROM identity WHERE name = ?") { setString(1, name.value) } > 0
            }
        }

    /**
     * Rebuilds one row, refusing anything this build cannot read.
     *
     * A tier this build does not recognise is [StoreException.Corrupt] rather
     * than a default. Guessing would mean choosing a privilege level for a row
     * somebody else wrote, and the safe-looking guess — the lowest tier — is
     * still a guess about authority.
     */
    private fun read(rows: java.sql.ResultSet): Identity {
        val name = rows.requiredString("name", "identity")
        val rawTier = rows.requiredString("tier", "identity `$name`")
        return Identity(
            name = ResourceName.of(name).getOrElse { throw StoreException.Corrupt("identity name `$name` is invalid") },
            credentialDigest = rows.requiredString("credential_digest", "identity `$name`"),
            tier =
                Tier.parse(rawTier)
                    ?: throw StoreException.Corrupt(
                        "identity `$name` carries tier `$rawTier`, which this build does not recognise",
                    ),
            enabled = rows.getInt("enabled") != 0,
            createdAt = rows.instant("created_at", "identity `$name`"),
        )
    }

    private suspend fun <T> guarded(block: (Connection) -> T): T =
        withContext(dispatcher) {
            mutex.withLock {
                if (closed) throw StoreException.Unavailable("the identity store is closed")
                try {
                    block(connection)
                } catch (failure: SQLException) {
                    throw failure.asStoreException("identity store")
                }
            }
        }

    override fun close() {
        closed = true
    }
}

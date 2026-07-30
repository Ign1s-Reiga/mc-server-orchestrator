package mcorch.store.sqlite

import mcorch.schema.ResourceName
import mcorch.store.Store
import mcorch.store.StoreConformanceSuite
import org.junit.jupiter.api.AfterEach
import java.nio.file.Path
import java.sql.DriverManager

/** The embedded store, run against the interface contract. */
class SqliteStoreConformanceTest : StoreConformanceSuite() {
    private val stores = TempStores()
    private var directory: Path? = null

    override fun createStore(): Store {
        val fresh = stores.directory()
        directory = fresh
        return stores.open(fresh).state
    }

    /**
     * Rewrites the stored document to something the codec will reject, through a
     * second connection to the same file while the store under test is open.
     *
     * A separate connection on purpose: the corruption has to arrive the way a
     * real one would — from outside the store, without its cooperation — and the
     * store must then meet it on an ordinary read rather than on a reopen.
     */
    override suspend fun corruptObservation(name: ResourceName) {
        val path = requireNotNull(directory) { "no store has been created yet" }
        DriverManager.getConnection("jdbc:sqlite:${path.resolve("state.db").toAbsolutePath()}").use { connection ->
            connection.autoCommit = false
            connection.update("UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE' WHERE name = ?") {
                setString(1, name.value)
            }
            connection.commit()
        }
    }

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }
}

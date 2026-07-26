package mcorch.store.sqlite

import mcorch.store.Store
import mcorch.store.StoreConformanceSuite
import org.junit.jupiter.api.AfterEach

/** The embedded store, run against the interface contract. */
class SqliteStoreConformanceTest : StoreConformanceSuite() {
    private val stores = TempStores()

    override fun createStore(): Store = stores.open(stores.directory()).state

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }
}

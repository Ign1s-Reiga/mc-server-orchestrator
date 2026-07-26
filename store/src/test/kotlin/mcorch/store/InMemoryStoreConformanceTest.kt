package mcorch.store

/** The reference implementation of the contract. See [InMemoryStore] for why it exists. */
class InMemoryStoreConformanceTest : StoreConformanceSuite() {
    override fun createStore(): Store = InMemoryStore()
}

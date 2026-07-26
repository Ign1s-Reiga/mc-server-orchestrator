package mcorch.store.sqlite

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.store.ChangeFeed
import mcorch.store.Fixtures
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * What happens when the change feed outruns what the store still remembers.
 *
 * Not in the conformance suite: retention is this implementation's answer to an
 * unbounded log, and another backend will have its own (etcd compacts on a
 * revision, a queue expires on time). What the *interface* promises is that a
 * cursor which has fallen behind is told so, rather than handed a feed that
 * silently skips what it missed — and that promise is what is tested here.
 */
class ChangeLogRetentionTest {
    private val stores = TempStores()

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }

    @Test
    fun `a cursor older than what the store kept is told to resync rather than handed a gap`() =
        runTest {
            val store = stores.open(stores.directory(), changeLogRetention = 2).state
            val stale = store.currentCursor()

            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-c")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-d")).getOrThrow()

            val expired = store.changesSince(stale).shouldBeInstanceOf<ChangeFeed.Expired>()

            // The cursor it hands back is usable straight after a full resync.
            val resumed = store.changesSince(expired.cursor).shouldBeInstanceOf<ChangeFeed.Changes>()
            resumed.changes shouldBe emptyList()
        }

    @Test
    fun `a cursor still inside the retained window keeps working`() =
        runTest {
            val store = stores.open(stores.directory(), changeLogRetention = 4).state
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            val recent = store.currentCursor()

            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()

            val feed = store.changesSince(recent).shouldBeInstanceOf<ChangeFeed.Changes>()
            feed.changes.map { it.name.value } shouldBe listOf("survival-b")
        }

    @Test
    fun `a null cursor reads everything the store still has`() =
        runTest {
            val store = stores.open(stores.directory(), changeLogRetention = 2).state
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-c")).getOrThrow()

            val feed = store.changesSince(null).shouldBeInstanceOf<ChangeFeed.Changes>()

            feed.changes.map { it.name.value } shouldBe listOf("survival-b", "survival-c")
        }
}

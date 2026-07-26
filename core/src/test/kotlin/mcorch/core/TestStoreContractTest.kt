package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.store.ChangeFeed
import mcorch.store.ConflictReason
import mcorch.store.Precondition
import mcorch.store.WriteOutcome
import mcorch.store.getOrThrow
import org.junit.jupiter.api.Test

/**
 * [TestStore] against the [mcorch.store.Store] contract it claims to implement.
 *
 * Every assertion in this file is about the fake rather than about the loop, and
 * that is the point: the loop's idempotency is argued from what the store
 * promises — generations that hold still, writes that do not land twice,
 * preconditions that are honoured — and all of it is checked here against a fake
 * written by the same person who wrote the loop. A fake that is quietly more
 * permissive than the real store turns the whole suite into a tautology.
 *
 * `:store` has a conformance suite of its own for the real implementation. When
 * it is published as a test fixture this file should be deleted and replaced by
 * running that suite against [TestStore]; until then, these are the clauses the
 * reconciler leans on.
 */
internal class TestStoreContractTest {
    @Test
    fun `re-applying a byte-identical definition moves nothing and is not a change`() =
        coreTest {
            val store = TestStore(MutableClock())
            val definition = paperDefinition()
            val first = store.putDefinition(definition).getOrThrow()
            val cursor = store.currentCursor()

            val again = store.putDefinition(definition).getOrThrow()

            again.resourceVersion shouldBe first.resourceVersion
            again.generation shouldBe first.generation
            // No version move means no change-feed entry, or the loop would
            // wake for a write that changed nothing.
            store
                .changesSince(cursor)
                .shouldBeInstanceOf<ChangeFeed.Changes>()
                .changes shouldHaveSize 0
        }

    @Test
    fun `a spec change moves the generation and a metadata-only change does not`() =
        coreTest {
            val store = TestStore(MutableClock())
            val definition = paperDefinition()
            val first = store.putDefinition(definition).getOrThrow()

            val respecced = store.putDefinition(paperDefinition(maxPlayers = 40)).getOrThrow()
            respecced.generation shouldBe first.generation + 1

            val relabelled =
                store
                    .putDefinition(
                        paperDefinition(maxPlayers = 40).let { current ->
                            current.copy(metadata = current.metadata.copy(labels = mapOf("tier" to "a")))
                        },
                    ).getOrThrow()
            // The operator did not change what they asked to be running, so the
            // loop must not see the server as needing anything.
            relabelled.generation shouldBe respecced.generation
            relabelled.resourceVersion shouldNotBeSame respecced.resourceVersion
        }

    @Test
    fun `a tombstoned name refuses further definitions until it is purged`() =
        coreTest {
            val store = TestStore(MutableClock())
            val definition = paperDefinition()
            val name = definition.metadata.name
            store.putDefinition(definition).getOrThrow()
            store.deleteDefinition(name).getOrThrow()

            // Creating a replacement while the old container may still have
            // players on it is the mistake the drain protocol exists to
            // prevent, so the name is held until the loop is finished with it.
            store
                .putDefinition(paperDefinition(maxPlayers = 40))
                .shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.TERMINATING
        }

    @Test
    fun `preconditions are honoured on every write`() =
        coreTest {
            val store = TestStore(MutableClock())
            val definition = paperDefinition()
            val name = definition.metadata.name

            store
                .putDefinition(definition, Precondition.AtVersion(mcorch.store.ResourceVersion("nope")))
                .shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.NOT_FOUND

            val stored = store.putDefinition(definition, Precondition.Absent).getOrThrow()

            store
                .putDefinition(definition, Precondition.Absent)
                .shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.ALREADY_EXISTS

            store
                .putDefinition(
                    paperDefinition(maxPlayers = 40),
                    Precondition.AtVersion(mcorch.store.ResourceVersion("stale")),
                ).shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.VERSION_MISMATCH

            store
                .putDefinition(paperDefinition(maxPlayers = 40), Precondition.AtVersion(stored.resourceVersion))
                .shouldBeInstanceOf<WriteOutcome.Applied<*>>()

            val status = mcorch.schema.PaperServerStatus.pending(name, 2L, MutableClock().instant())
            store
                .putStatus(status, Precondition.AtVersion(mcorch.store.ResourceVersion("stale")))
                .shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.NOT_FOUND
            store.putStatus(status, Precondition.Absent).shouldBeInstanceOf<WriteOutcome.Applied<*>>()
            store
                .putStatus(status, Precondition.Absent)
                .shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.ALREADY_EXISTS
        }

    @Test
    fun `an identical status write lands nothing and a purge needs a delete first`() =
        coreTest {
            val clock = MutableClock()
            val store = TestStore(clock)
            val definition = paperDefinition()
            val name = definition.metadata.name
            store.putDefinition(definition).getOrThrow()
            val status = mcorch.schema.PaperServerStatus.pending(name, 1L, clock.instant())
            store.putStatus(status).getOrThrow()
            val writes = store.statusWrites

            store.putStatus(status).getOrThrow()
            store.statusWrites shouldBe writes

            // Purging a live definition would leave a running container with
            // nothing describing it.
            store
                .purge(name)
                .shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.NOT_DELETED
            store.deleteDefinition(name).getOrThrow()
            store.purge(name).shouldBeInstanceOf<WriteOutcome.Applied<*>>()
            store.getServer(name) shouldBe null
        }

    @Test
    fun `a stale observedDefinition conflicts and a status for an unknown name is not found`() =
        coreTest {
            val clock = MutableClock()
            val store = TestStore(clock)
            val definition = paperDefinition()
            val name = definition.metadata.name
            val stored = store.putDefinition(definition).getOrThrow()
            store.putDefinition(paperDefinition(maxPlayers = 40)).getOrThrow()

            store
                .putStatus(
                    mcorch.schema.PaperServerStatus.pending(name, 1L, clock.instant()),
                    observedDefinition = stored.resourceVersion,
                ).shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.DEFINITION_CHANGED

            store
                .putStatus(mcorch.schema.PaperServerStatus.pending(resourceName("nobody"), 1L, clock.instant()))
                .shouldBeInstanceOf<WriteOutcome.Conflict>()
                .reason shouldBe ConflictReason.NOT_FOUND
        }
}

private infix fun Any?.shouldNotBeSame(other: Any?) {
    if (this == other) error("expected a different value, but both were $this")
}

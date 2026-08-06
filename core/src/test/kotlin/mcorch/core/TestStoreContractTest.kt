package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import mcorch.store.ChangeFeed
import mcorch.store.ConflictReason
import mcorch.store.Precondition
import mcorch.store.WriteOutcome
import mcorch.store.getOrThrow
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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

    /**
     * A write from a cancelled coroutine does not land.
     *
     * The real store runs every call as `withContext(dispatcher) { … }`, and a
     * dispatch from a cancelled coroutine never runs the block — so a pass
     * cancelled before its write reaches the store loses it. That is the fact the
     * whole save-record durability argument rests on, and a fake that quietly
     * wrote anyway would make `SaveRecordDurabilityTest` pass against code with
     * no shield in it at all: an *uncontended* [kotlinx.coroutines.sync.Mutex] is
     * taken on a fast path that never suspends and never notices cancellation.
     */
    @Test
    fun `a call from a cancelled coroutine does not reach the store`() =
        coreTest {
            val clock = MutableClock()
            val store = TestStore(clock)
            val definition = paperDefinition()
            val name = definition.metadata.name
            store.putDefinition(definition).getOrThrow()
            val writes = store.statusWrites

            val job =
                launch {
                    coroutineContext.job.cancel()
                    runCatching {
                        store.putStatus(mcorch.schema.PaperServerStatus.pending(name, 1L, clock.instant()))
                    }
                }
            job.join()

            store.statusWrites shouldBe writes
            store.getServer(name).shouldNotBeNull().status shouldBe null
        }

    /**
     * The one clause of the contract this fake **deliberately does not deliver**,
     * pinned so that closing it is a decision rather than a tidy-up.
     *
     * `Store`'s KDoc says every definition handed back by a read has been through
     * `SpecBounds`, and `:store`'s conformance suite holds both of its
     * implementations to it. [TestStore] does not, and the direction matters: it is
     * *more permissive than the real store*, which for a read-side guarantee means
     * `:core` is exercised against a **wider** input set than a real store can
     * produce, not a narrower one. The fake that got this project into trouble was
     * permissive about *writes* — accepting what the real store refuses — and that
     * is the opposite direction.
     *
     * Delivering it here would be actively harmful, and this is the argument to read
     * before "fixing" it. `SpecBounds` is explicit that `:core`'s own ceilings stay
     * load-bearing for anything that never went through a store, and the tests that
     * hold them — `DrainTest`'s `a store row past both ceilings …`, and
     * `UnbuildableRequestTest`'s two proxy cases — all reach `:core` through this
     * fake. Clamping here would make every one of them assert a value the fake
     * produced, and a later change that deleted `StopGraceCeiling`,
     * `ExecTimeoutCeiling` or `EndpointTimeout` outright would leave the suite green.
     *
     * So the divergence is a ruling. What would change it is `:store` publishing its
     * conformance suite as a test fixture — at which point this whole class goes, and
     * the right answer is a *second* fake for the bound reads rather than one that
     * has to be both.
     */
    @Test
    fun `a definition with a deadline past its ceiling comes back exactly as it was stored`() =
        coreTest {
            val store = TestStore(MutableClock())
            // Past `SpecBounds.MAX_SAVE_TIMEOUT` and `MAX_STOP_GRACE_PERIOD` alike,
            // and a pair `LifecycleSpec.init` accepts — which is what makes it a row
            // a real store would have clamped on the way out.
            val definition = paperDefinition(saveTimeout = 3.hours, stopGracePeriod = 3.hours + 1.minutes)
            val name = definition.metadata.name
            store.putDefinition(definition).getOrThrow()

            val read =
                store
                    .getServer(name)
                    .shouldNotBeNull()
                    .definition.definition
            read shouldBe definition
            store
                .listServers()
                .single()
                .definition.definition shouldBe definition
        }
}

private infix fun Any?.shouldNotBeSame(other: Any?) {
    if (this == other) error("expected a different value, but both were $this")
}

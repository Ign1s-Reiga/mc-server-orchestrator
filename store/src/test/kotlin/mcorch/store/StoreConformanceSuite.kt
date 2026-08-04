package mcorch.store

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.schema.ServerPhase
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * The contract every [Store] has to satisfy, written against the interface and
 * nothing else.
 *
 * This suite is the thing that makes the distribution seam real. It names no
 * implementation, opens no file, and knows nothing about SQL; a networked store
 * arriving later gets a subclass that supplies [createStore] and either passes or
 * is not finished. If a test here needs to know how the store is built, the test
 * is wrong, or the interface has leaked.
 *
 * Implementation-specific behaviour — migrations, on-disk layout, change-log
 * retention — belongs in that implementation's own tests, not here.
 */
abstract class StoreConformanceSuite {
    /** A fresh, empty store. Closed by the suite after each test. */
    protected abstract fun createStore(): Store

    /**
     * Makes the observation already stored for [name] undecodable, by whatever
     * means this implementation has — reaching past the interface is the point.
     *
     * Every implementation must answer this, because the answer is load-bearing.
     * The interface's defence against a half-finished drain being restarted is
     * that a *point* read refuses a record it cannot decode: the loop asks about
     * one server, gets a failure, and does not act. An implementation that
     * quietly returned "no observation" instead would let the loop re-issue a
     * save request against a server that already has one in flight. Nothing about
     * the type signatures stops it, so the suite has to.
     *
     * The default aborts rather than passes. A store that cannot hold an
     * undecodable record at all — one keeping objects in memory — is exempt in
     * fact, and says so out loud; a store that *can* and has not implemented this
     * is announcing untested behaviour rather than banking a green tick.
     */
    protected open suspend fun corruptObservation(name: ResourceName) {
        Assumptions.abort<Unit>(
            "this store cannot hold an undecodable observation, so it has nothing to refuse. " +
                "An implementation that can hold one must override corruptObservation",
        )
    }

    private fun withStore(block: suspend (Store) -> Unit) =
        runTest {
            createStore().use { store -> block(store) }
        }

    // ------------------------------------------------------------- round-tripping

    @Test
    fun `a stored definition comes back exactly as it went in`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")

            store.putDefinition(definition).getOrThrow()

            val stored = store.getServer(definition.metadata.name).shouldNotBeNull()
            stored.definition.definition shouldBe definition
            stored.definition.generation shouldBe 1L
            stored.status.shouldBeNull()
        }

    @Test
    fun `every example definition round-trips`() =
        withStore { store ->
            val examples = listOf("minimal.yaml", "full.yaml", "ephemeral-lobby.yaml")

            for (example in examples) {
                val definition = Fixtures.definition(example)
                store.putDefinition(definition).getOrThrow()
                store
                    .getServer(definition.metadata.name)
                    .shouldNotBeNull()
                    .definition.definition shouldBe definition
            }
        }

    @Test
    fun `a fully populated status comes back exactly as it went in`() =
        withStore { store ->
            val definition = Fixtures.definitionNamed("survival-02")
            store.putDefinition(definition).getOrThrow()
            val status = Fixtures.fullStatus("survival-02")

            store.putStatus(status).getOrThrow()

            val stored = store.getServer(definition.metadata.name).shouldNotBeNull()
            stored.status.shouldNotBeNull().status shouldBe status
        }

    @Test
    fun `a drain in flight survives being written and read back, field for field`() =
        withStore { store ->
            // The drain protocol re-reads this to decide what it has already done. A
            // dropped `saveRequestedAt` re-sends a save request to a live server; a
            // dropped `state` restarts a drain that was nearly finished.
            store.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            val status = Fixtures.fullStatus("survival-02", drainState = DrainState.SAVING)

            store.putStatus(status).getOrThrow()

            val drain =
                store
                    .getServer(Fixtures.resourceName("survival-02"))
                    .shouldNotBeNull()
                    .status
                    .shouldNotBeNull()
                    .status
                    .shouldBeInstanceOf<PaperServerStatus>()
                    .drain
                    .shouldNotBeNull()
            val expected = status.drain.shouldNotBeNull()
            drain shouldBe expected
            drain.state shouldBe expected.state
            drain.startedAt shouldBe expected.startedAt
            drain.enteredStateAt shouldBe expected.enteredStateAt
            drain.playersEvacuated shouldBe expected.playersEvacuated
            drain.sealRequestedAt shouldBe expected.sealRequestedAt
            drain.saveRequestedAt shouldBe expected.saveRequestedAt
            drain.worldSavedAt shouldBe expected.worldSavedAt
            drain.worldSaved shouldBe expected.worldSaved
            drain.deregisteredAt.shouldBeNull()
            drain.transferAttempts shouldBe expected.transferAttempts
            drain.destination shouldBe expected.destination
            drain.failure shouldBe expected.failure

            // The wedge, spelled out. This record says a request went out and
            // never came back; reading it as a confirmation would authorise a
            // stop on a save nobody has seen finish.
            drain.saveRequestedAt.shouldNotBeNull()
            drain.worldSavedAt.shouldBeNull()
            drain.worldSaved shouldBe false
        }

    /**
     * The other half of the same round trip, and the one an upgrade can invert.
     *
     * A confirmed save and an unconfirmed request differ by which key carries
     * the timestamp. If a store loses `worldSavedAt`, or writes it back into
     * `saveRequestedAt`, a drain that had finished its save comes back believing
     * a request went out and never returned — it wedges permanently and asks a
     * human to verify a world that is already on disk.
     */
    @Test
    fun `a confirmed world save comes back as a confirmation, not as an outstanding request`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-03")).getOrThrow()
            val expected = Fixtures.confirmedDrain()
            val status = Fixtures.fullStatus("survival-03").copy(drain = expected)

            store.putStatus(status).getOrThrow()

            val drain =
                store
                    .getServer(Fixtures.resourceName("survival-03"))
                    .shouldNotBeNull()
                    .status
                    .shouldNotBeNull()
                    .status
                    .shouldBeInstanceOf<PaperServerStatus>()
                    .drain
                    .shouldNotBeNull()
            drain shouldBe expected
            drain.worldSavedAt shouldBe expected.worldSavedAt
            drain.worldSaved shouldBe true
            // Disjoint: a confirmed save has no request outstanding, and a store
            // that resurrected one would wedge the drain on the next pass.
            drain.saveRequestedAt.shouldBeNull()
        }

    /**
     * The third record, and the one whose meaning is carried by a null.
     *
     * A blocked drain is waiting for players to log off. It records a
     * `DrainBlock` and **no** `FailureStatus`, and that absence is what keeps the
     * escalation quiet — so a store that resurrected a failure here, or dropped
     * the block and left the drain looking like an ordinary abort, would put a
     * server with people happily playing on it back into the "a human must act"
     * path on the first read after a restart.
     *
     * Field for field rather than by equality alone, because the fields differ in
     * how they fail. A dropped `since` re-dates the wait to the restart; a dropped
     * `observations` says the loop has looked once when it has looked forty times.
     */
    @Test
    fun `a drain blocked on players comes back blocked, and comes back with no failure`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-04")).getOrThrow()
            val expected = Fixtures.blockedDrain()
            val status = Fixtures.fullStatus("survival-04").copy(drain = expected, failure = null)

            store.putStatus(status).getOrThrow()

            val drain =
                store
                    .getServer(Fixtures.resourceName("survival-04"))
                    .shouldNotBeNull()
                    .status
                    .shouldNotBeNull()
                    .status
                    .shouldBeInstanceOf<PaperServerStatus>()
                    .drain
                    .shouldNotBeNull()
            drain shouldBe expected
            val blocked = drain.blocked.shouldNotBeNull()
            blocked.reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            blocked.message shouldBe expected.blocked?.message
            blocked.since shouldBe expected.blocked?.since
            blocked.observations shouldBe expected.blocked?.observations
            // The assertion the record exists for.
            drain.failure.shouldBeNull()
        }

    @Test
    fun `every drain state round-trips`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()

            for (state in DrainState.entries) {
                val status = Fixtures.fullStatus("survival-02", drainState = state)
                store.putStatus(status).getOrThrow()
                store
                    .getServer(Fixtures.resourceName("survival-02"))
                    .shouldNotBeNull()
                    .status
                    .shouldNotBeNull()
                    .status shouldBe status
            }
        }

    @Test
    fun `every phase round-trips`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()

            for (phase in ServerPhase.entries) {
                val status = Fixtures.fullStatus("survival-02", phase = phase)
                store.putStatus(status).getOrThrow()
                store
                    .getServer(Fixtures.resourceName("survival-02"))
                    .shouldNotBeNull()
                    .status
                    .shouldNotBeNull()
                    .status shouldBe status
            }
        }

    @Test
    fun `a status with nothing but the required fields round-trips`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            val status = Fixtures.pendingStatus("survival-02", generation = 1L)

            store.putStatus(status).getOrThrow()

            store
                .getServer(Fixtures.resourceName("survival-02"))
                .shouldNotBeNull()
                .status
                .shouldNotBeNull()
                .status shouldBe status
        }

    // ----------------------------------------------------------------- generation

    @Test
    fun `re-storing an identical spec does not move the generation`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")
            val first = store.putDefinition(definition).getOrThrow()

            val second = store.putDefinition(definition).getOrThrow()
            val third = store.putDefinition(definition).getOrThrow()

            first.generation shouldBe 1L
            second.generation shouldBe 1L
            third.generation shouldBe 1L
        }

    @Test
    fun `re-storing an identical definition does not move the resource version either`() =
        withStore { store ->
            // A resync that re-applies every file must not look like a change to
            // anything watching, or the loop never settles.
            val definition = Fixtures.definition("full.yaml")
            val first = store.putDefinition(definition).getOrThrow()

            val second = store.putDefinition(definition).getOrThrow()

            second.resourceVersion shouldBe first.resourceVersion
            second.updatedAt shouldBe first.updatedAt
        }

    @Test
    fun `changing the spec bumps the generation`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")
            store.putDefinition(definition).getOrThrow()
            val changed = definition.copy(spec = definition.spec.copy(maxPlayers = definition.spec.maxPlayers + 1))

            val stored = store.putDefinition(changed).getOrThrow()

            stored.generation shouldBe 2L
        }

    @Test
    fun `changing only metadata moves the version but not the generation`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")
            val first = store.putDefinition(definition).getOrThrow()
            val relabelled = definition.copy(metadata = definition.metadata.copy(labels = mapOf("tier" to "creative")))

            val second = store.putDefinition(relabelled).getOrThrow()

            second.generation shouldBe first.generation
            second.resourceVersion shouldNotBe first.resourceVersion
            store
                .getServer(definition.metadata.name)
                .shouldNotBeNull()
                .definition.definition.metadata.labels shouldBe mapOf("tier" to "creative")
        }

    @Test
    fun `generation restarts after a purge, because that is a different server`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")
            store.putDefinition(definition).getOrThrow()
            store.putDefinition(definition.copy(spec = definition.spec.copy(maxPlayers = 61))).getOrThrow()
            store.deleteDefinition(definition.metadata.name).getOrThrow()
            store.purge(definition.metadata.name).getOrThrow()

            val recreated = store.putDefinition(definition).getOrThrow()

            recreated.generation shouldBe 1L
        }

    @Test
    fun `caughtUp reflects whether the last observation matches the current spec`() =
        withStore { store ->
            val definition = Fixtures.definitionNamed("survival-02")
            val first = store.putDefinition(definition).getOrThrow()
            store.putStatus(Fixtures.pendingStatus("survival-02", first.generation)).getOrThrow()
            store.getServer(definition.metadata.name).shouldNotBeNull().caughtUp shouldBe true

            store.putDefinition(definition.copy(spec = definition.spec.copy(maxPlayers = 99))).getOrThrow()

            store.getServer(definition.metadata.name).shouldNotBeNull().caughtUp shouldBe false
        }

    // -------------------------------------------------------- concurrency control

    @Test
    fun `creating with Absent conflicts when the name is taken`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")
            val first = store.putDefinition(definition, Precondition.Absent).getOrThrow()

            val outcome = store.putDefinition(definition, Precondition.Absent)

            val conflict = outcome.shouldBeInstanceOf<WriteOutcome.Conflict>()
            conflict.reason shouldBe ConflictReason.ALREADY_EXISTS
            conflict.currentResourceVersion shouldBe first.resourceVersion
        }

    @Test
    fun `writing at a version somebody else has moved on from conflicts instead of overwriting`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")
            val first = store.putDefinition(definition).getOrThrow()
            // Somebody else got there in between.
            val second =
                store.putDefinition(definition.copy(spec = definition.spec.copy(maxPlayers = 61))).getOrThrow()

            val outcome =
                store.putDefinition(
                    definition.copy(spec = definition.spec.copy(maxPlayers = 62)),
                    Precondition.AtVersion(first.resourceVersion),
                )

            val conflict = outcome.shouldBeInstanceOf<WriteOutcome.Conflict>()
            conflict.reason shouldBe ConflictReason.VERSION_MISMATCH
            conflict.currentResourceVersion shouldBe second.resourceVersion
            // The lost update did not happen: what is stored is still the second write.
            store
                .getServer(definition.metadata.name)
                .shouldNotBeNull()
                .definition.definition shouldBe definition.copy(spec = definition.spec.copy(maxPlayers = 61))
        }

    @Test
    fun `writing at the current version applies`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")
            val first = store.putDefinition(definition).getOrThrow()

            val second =
                store
                    .putDefinition(
                        definition.copy(spec = definition.spec.copy(maxPlayers = 61)),
                        Precondition.AtVersion(first.resourceVersion),
                    ).getOrThrow()

            second.generation shouldBe 2L
        }

    @Test
    fun `writing at a version when nothing is stored conflicts as NOT_FOUND`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")

            val outcome =
                store.putDefinition(definition, Precondition.AtVersion(ResourceVersion("whatever")))

            val conflict = outcome.shouldBeInstanceOf<WriteOutcome.Conflict>()
            conflict.reason shouldBe ConflictReason.NOT_FOUND
            conflict.currentResourceVersion.shouldBeNull()
        }

    @Test
    fun `an observation of a definition the operator has already replaced is refused`() =
        withStore { store ->
            // The single most important thing this interface does. The pass read
            // generation 1, did its work, and while it was working the operator applied
            // a new spec. Recording the observation now would leave the server looking
            // settled at a generation nobody wants any more.
            val definition = Fixtures.definitionNamed("survival-02")
            val readByThePass = store.putDefinition(definition).getOrThrow()
            store.putDefinition(definition.copy(spec = definition.spec.copy(maxPlayers = 61))).getOrThrow()

            val outcome =
                store.putStatus(
                    Fixtures.fullStatus("survival-02", generation = readByThePass.generation),
                    observedDefinition = readByThePass.resourceVersion,
                )

            val conflict = outcome.shouldBeInstanceOf<WriteOutcome.Conflict>()
            conflict.reason shouldBe ConflictReason.DEFINITION_CHANGED
            store
                .getServer(definition.metadata.name)
                .shouldNotBeNull()
                .status
                .shouldBeNull()
        }

    @Test
    fun `an observation of the current definition is recorded`() =
        withStore { store ->
            val definition = Fixtures.definitionNamed("survival-02")
            val stored = store.putDefinition(definition).getOrThrow()

            val outcome =
                store.putStatus(
                    Fixtures.fullStatus("survival-02", generation = stored.generation),
                    observedDefinition = stored.resourceVersion,
                )

            outcome.shouldBeInstanceOf<WriteOutcome.Applied<*>>()
        }

    @Test
    fun `a status write at a stale status version conflicts`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            val first = store.putStatus(Fixtures.pendingStatus("survival-02", 1L)).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-02", phase = ServerPhase.RUNNING)).getOrThrow()

            val outcome =
                store.putStatus(
                    Fixtures.fullStatus("survival-02", phase = ServerPhase.STOPPED),
                    precondition = Precondition.AtVersion(first.resourceVersion),
                )

            outcome.shouldBeInstanceOf<WriteOutcome.Conflict>().reason shouldBe ConflictReason.VERSION_MISMATCH
        }

    @Test
    fun `writing an unchanged status does not move the version`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            val status = Fixtures.fullStatus("survival-02")
            val first = store.putStatus(status).getOrThrow()

            val second = store.putStatus(status).getOrThrow()

            second.resourceVersion shouldBe first.resourceVersion
        }

    @Test
    fun `a status for a name with no definition is refused`() =
        withStore { store ->
            val outcome = store.putStatus(Fixtures.pendingStatus("survival-02", 1L))

            outcome.shouldBeInstanceOf<WriteOutcome.Conflict>().reason shouldBe ConflictReason.NOT_FOUND
        }

    // ------------------------------------------------------------------- deletion

    @Test
    fun `a deleted definition stays readable, spec intact, so the drain has something to work from`() =
        withStore { store ->
            val definition = Fixtures.definitionNamed("survival-02")
            store.putDefinition(definition).getOrThrow()

            val deleted = store.deleteDefinition(definition.metadata.name).getOrThrow()

            deleted.terminating shouldBe true
            deleted.deletedAt.shouldNotBeNull()
            val stored = store.getServer(definition.metadata.name).shouldNotBeNull()
            stored.definition.terminating shouldBe true
            // The whole point: the loop still knows how long it may take to save.
            stored.definition.definition shouldBe definition
        }

    @Test
    fun `deleting twice is not an error and does not move the version`() =
        withStore { store ->
            val definition = Fixtures.definitionNamed("survival-02")
            store.putDefinition(definition).getOrThrow()
            val first = store.deleteDefinition(definition.metadata.name).getOrThrow()

            val second = store.deleteDefinition(definition.metadata.name).getOrThrow()

            second.resourceVersion shouldBe first.resourceVersion
            second.deletedAt shouldBe first.deletedAt
        }

    @Test
    fun `deleting a name that is not stored conflicts`() =
        withStore { store ->
            val outcome = store.deleteDefinition(Fixtures.resourceName("nothing-here"))

            outcome.shouldBeInstanceOf<WriteOutcome.Conflict>().reason shouldBe ConflictReason.NOT_FOUND
        }

    @Test
    fun `a drain still records progress after the delete request`() =
        withStore { store ->
            val definition = Fixtures.definitionNamed("survival-02")
            store.putDefinition(definition).getOrThrow()
            store.deleteDefinition(definition.metadata.name).getOrThrow()

            val outcome =
                store.putStatus(Fixtures.fullStatus("survival-02", drainState = DrainState.TRANSFERRING))

            outcome.shouldBeInstanceOf<WriteOutcome.Applied<*>>()
        }

    @Test
    fun `a name awaiting cleanup cannot be written again`() =
        withStore { store ->
            // Otherwise a re-apply creates the replacement while the old container may
            // still have players on it.
            val definition = Fixtures.definitionNamed("survival-02")
            store.putDefinition(definition).getOrThrow()
            store.deleteDefinition(definition.metadata.name).getOrThrow()

            val outcome = store.putDefinition(definition)

            outcome.shouldBeInstanceOf<WriteOutcome.Conflict>().reason shouldBe ConflictReason.TERMINATING
        }

    @Test
    fun `purging a definition that was never deleted is refused`() =
        withStore { store ->
            val definition = Fixtures.definitionNamed("survival-02")
            store.putDefinition(definition).getOrThrow()

            val outcome = store.purge(definition.metadata.name)

            outcome.shouldBeInstanceOf<WriteOutcome.Conflict>().reason shouldBe ConflictReason.NOT_DELETED
            store.getServer(definition.metadata.name).shouldNotBeNull()
        }

    @Test
    fun `purging removes the definition and its status together`() =
        withStore { store ->
            val definition = Fixtures.definitionNamed("survival-02")
            store.putDefinition(definition).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-02")).getOrThrow()
            store.deleteDefinition(definition.metadata.name).getOrThrow()

            store.purge(definition.metadata.name).getOrThrow()

            store.getServer(definition.metadata.name).shouldBeNull()
            store.listServers() shouldBe emptyList()
        }

    @Test
    fun `purging a name that is not stored is a no-op`() =
        withStore { store ->
            store.purge(Fixtures.resourceName("nothing-here")).getOrThrow()
        }

    // ---------------------------------------------------------------------- reads

    @Test
    fun `listServers returns every server including the ones awaiting cleanup`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-c")).getOrThrow()
            store.deleteDefinition(Fixtures.resourceName("survival-c")).getOrThrow()

            val servers = store.listServers()

            servers.map { it.name.value }.shouldContainExactlyInAnyOrder("survival-a", "survival-b", "survival-c")
            servers.single { it.name.value == "survival-c" }.definition.terminating shouldBe true
        }

    @Test
    fun `listByDrainState finds the servers with a drain recorded in those states`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-c")).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-a", drainState = DrainState.SAVING)).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-b", drainState = DrainState.DRAIN_FAILED)).getOrThrow()
            store.putStatus(Fixtures.pendingStatus("survival-c", 1L)).getOrThrow()

            val saving = store.listByDrainState(setOf(DrainState.SAVING))
            val either = store.listByDrainState(setOf(DrainState.SAVING, DrainState.DRAIN_FAILED))

            saving.map { it.name.value } shouldBe listOf("survival-a")
            either.map { it.name.value }.shouldContainExactlyInAnyOrder("survival-a", "survival-b")
            store.listByDrainState(emptySet()) shouldBe emptyList()
        }

    @Test
    fun `listByDrainState follows the drain as it advances`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-a", drainState = DrainState.SEALED)).getOrThrow()

            store.putStatus(Fixtures.fullStatus("survival-a", drainState = DrainState.SAVING)).getOrThrow()

            store.listByDrainState(setOf(DrainState.SEALED)) shouldBe emptyList()
            store.listByDrainState(setOf(DrainState.SAVING)).map { it.name.value } shouldBe listOf("survival-a")
        }

    @Test
    fun `listAll returns the same servers as listServers when every row reads`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-a")).getOrThrow()

            val listing = store.listAll()

            listing.servers shouldBe store.listServers()
            listing.unreadable.shouldBeEmpty()
        }

    @Test
    fun `listAllByDrainState returns the same servers as listByDrainState when every row reads`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-a", drainState = DrainState.SAVING)).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-b", drainState = DrainState.SEALED)).getOrThrow()

            val listing = store.listAllByDrainState(setOf(DrainState.SAVING))

            listing.servers shouldBe store.listByDrainState(setOf(DrainState.SAVING))
            listing.servers.map { it.name.value } shouldBe listOf("survival-a")
            listing.unreadable.shouldBeEmpty()
        }

    /**
     * The distinction the loop's idempotency rests on.
     *
     * "Nothing has been observed" means reconcile from the beginning. "There is an
     * observation and the store cannot read it" must not, or a drain that already
     * issued its save request issues it again against a live server. Both leave
     * [StoredServer.status] null, so the interface has to say which is which.
     */
    @Test
    fun `a server with no observation reports that nothing was observed, not that it is unreadable`() =
        withStore { store ->
            val name = Fixtures.resourceName("survival-a")
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()

            val fresh = store.getServer(name).shouldNotBeNull()
            fresh.neverObserved shouldBe true
            fresh.unreadable.shouldBeNull()
            fresh.caughtUp shouldBe false

            store.putStatus(Fixtures.fullStatus("survival-a")).getOrThrow()

            val observed = store.getServer(name).shouldNotBeNull()
            observed.neverObserved shouldBe false
            observed.unreadable.shouldBeNull()
        }

    // ------------------------------------------------ state that will not decode

    /**
     * The refusal the drain protocol leans on.
     *
     * A pass that cannot read the last observation cannot know whether the save
     * request already went out, and re-sending one loads a live server and can
     * restart a drain that was nearly done. The store's answer is to refuse the
     * point read, so the pass fails instead of proceeding on an assumption. It is
     * the single most load-bearing behaviour in the unreadable-state design and
     * it is the one a new backend is most likely to get wrong, because being
     * *lenient* here looks like robustness.
     */
    @Test
    fun `a point read refuses a server whose observation cannot be decoded`() =
        withStore { store ->
            val name = Fixtures.resourceName("survival-a")
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-a")).getOrThrow()

            corruptObservation(name)

            val failure =
                runCatching { store.getServer(name) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<StoreException>()
            failure.retryable shouldBe false
        }

    /**
     * The other half of the same rule: a *listing* must not refuse, and must not
     * pretend either. The row stays, marked, so a caller sees every other server
     * and is told about this one — see [Store.listServers].
     */
    @Test
    fun `a listing marks the server whose observation cannot be decoded and keeps the rest`() =
        withStore { store ->
            val name = Fixtures.resourceName("survival-a")
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-a")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-b")).getOrThrow()

            corruptObservation(name)

            val servers = store.listServers()
            servers.map { it.name.value }.shouldContainExactlyInAnyOrder("survival-a", "survival-b")

            val marked = servers.single { it.name == name }
            marked.status.shouldBeNull()
            marked.unreadable.shouldNotBeNull().part shouldBe StatePart.OBSERVED
            marked.neverObserved shouldBe false

            // The untouched server is untouched: one bad record costs one server.
            servers.single { it.name.value == "survival-b" }.status.shouldNotBeNull()
        }

    // --------------------------------------------------------------- change feed

    @Test
    fun `the feed reports creates, updates, deletes and purges in order`() =
        withStore { store ->
            val start = store.currentCursor()
            val definition = Fixtures.definitionNamed("survival-02")
            store.putDefinition(definition).getOrThrow()
            store.putDefinition(definition.copy(spec = definition.spec.copy(maxPlayers = 61))).getOrThrow()
            store.deleteDefinition(definition.metadata.name).getOrThrow()
            store.purge(definition.metadata.name).getOrThrow()

            val feed = store.changesSince(start).shouldBeInstanceOf<ChangeFeed.Changes>()

            feed.changes.map { it.kind } shouldBe
                listOf(ChangeKind.WRITTEN, ChangeKind.WRITTEN, ChangeKind.DELETED, ChangeKind.PURGED)
            feed.changes.map { it.name.value }.toSet() shouldBe setOf("survival-02")
            feed.more shouldBe false
        }

    @Test
    fun `re-applying an unchanged definition produces no change`() =
        withStore { store ->
            val definition = Fixtures.definition("full.yaml")
            store.putDefinition(definition).getOrThrow()
            val afterCreate = store.currentCursor()

            store.putDefinition(definition).getOrThrow()
            store.putDefinition(definition).getOrThrow()

            store.changesSince(afterCreate).shouldBeInstanceOf<ChangeFeed.Changes>().changes shouldBe emptyList()
        }

    @Test
    fun `observations are not in the feed`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            val afterCreate = store.currentCursor()

            store.putStatus(Fixtures.fullStatus("survival-02")).getOrThrow()
            store.putStatus(Fixtures.fullStatus("survival-02", phase = ServerPhase.STOPPED)).getOrThrow()

            store.changesSince(afterCreate).shouldBeInstanceOf<ChangeFeed.Changes>().changes shouldBe emptyList()
        }

    @Test
    fun `a cursor from the feed resumes where it left off`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            val first = store.changesSince(null).shouldBeInstanceOf<ChangeFeed.Changes>()

            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            val second = store.changesSince(first.cursor).shouldBeInstanceOf<ChangeFeed.Changes>()

            first.changes.map { it.name.value } shouldBe listOf("survival-a")
            second.changes.map { it.name.value } shouldBe listOf("survival-b")
        }

    @Test
    fun `the limit is honoured and more says whether to read again`() =
        withStore { store ->
            val start = store.currentCursor()
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-b")).getOrThrow()
            store.putDefinition(Fixtures.definitionNamed("survival-c")).getOrThrow()

            val first = store.changesSince(start, limit = 2).shouldBeInstanceOf<ChangeFeed.Changes>()
            val second = store.changesSince(first.cursor, limit = 2).shouldBeInstanceOf<ChangeFeed.Changes>()

            first.changes.map { it.name.value } shouldBe listOf("survival-a", "survival-b")
            first.more shouldBe true
            second.changes.map { it.name.value } shouldBe listOf("survival-c")
            second.more shouldBe false
        }

    @Test
    fun `nothing has happened since the current cursor`() =
        withStore { store ->
            store.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()

            val feed = store.changesSince(store.currentCursor()).shouldBeInstanceOf<ChangeFeed.Changes>()

            feed.changes shouldBe emptyList()
            feed.more shouldBe false
        }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun `a closed store refuses to be used`() =
        runTest {
            val store = createStore()
            store.close()

            val failure =
                runCatching { store.listServers() }.exceptionOrNull().shouldBeInstanceOf<StoreException.Closed>()

            failure.retryable shouldBe false
        }
}

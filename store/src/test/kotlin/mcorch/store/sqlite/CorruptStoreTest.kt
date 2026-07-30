package mcorch.store.sqlite

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.schema.DrainState
import mcorch.store.Fixtures
import mcorch.store.StatePart
import mcorch.store.Store
import mcorch.store.StoreException
import mcorch.store.getOrThrow
import mcorch.store.logging.CapturedLogs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * What happens when the disk says something this build cannot make sense of.
 *
 * Two rules, and they are not the same rule.
 *
 * *Refuse it.* Nothing here is ever coerced into something loadable. A record the
 * store cannot decode stays undecoded, and the failure is permanent so the loop
 * surfaces it rather than spinning on it. Every case here would otherwise be a
 * silent reinterpretation of stored data.
 *
 * *Charge it to one server.* Refusing a record is not a reason to refuse every
 * other server in the same read. The ninth drain audit found that it was: one row
 * that would not decode failed `listServers` outright, so the reconcile loop's
 * resync queued nothing, no in-flight drain was resumed at startup, and the
 * dashboard's fleet table and event stream went dark at the same instant — every
 * five minutes, indefinitely, from one hand-edited row.
 */
class CorruptStoreTest {
    private val stores = TempStores()

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }

    // ------------------------------------------------- desired state that will not read

    /**
     * A definition this build cannot decode has no server to be attached to, so
     * [Store.listServers] still fails for it — quietly returning a shorter list is
     * indistinguishable from the server having been purged. [Store.listAll] is
     * where it becomes one entry beside the servers that did read.
     */
    @Test
    fun `a document written with a newer encoding is refused`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-01")).getOrThrow()
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
                store.state.putDefinition(Fixtures.definitionNamed("survival-03")).getOrThrow()
            }
            mutate(directory, "UPDATE server_definition SET doc_encoding = 99 WHERE name = 'survival-02'")

            stores.open(directory).use { store ->
                val failure =
                    runCatching { store.state.listServers() }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<StoreException.Unsupported>()

                failure.retryable shouldBe false
                failure.message.shouldNotBeNull() shouldContain "encoding 99"

                val listing = store.state.listAll()
                listing.servers.map { it.name.value } shouldBe listOf("survival-01", "survival-03")
                val entry = listing.unreadable.single()
                entry.name shouldBe "survival-02"
                entry.unreadable.part shouldBe StatePart.DESIRED
                entry.unreadable.retryable shouldBe false
                entry.unreadable.reason shouldContain "encoding 99"
            }
        }

    @Test
    fun `a kind this build does not know is refused`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-01")).getOrThrow()
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            }
            mutate(directory, "UPDATE server_definition SET kind = 'VelocityProxy' WHERE name = 'survival-02'")

            stores.open(directory).use { store ->
                val failure =
                    runCatching { store.state.listServers() }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<StoreException.Unsupported>()

                failure.message.shouldNotBeNull() shouldContain "VelocityProxy"

                val listing = store.state.listAll()
                listing.servers.map { it.name.value } shouldBe listOf("survival-01")
                listing.unreadable
                    .single()
                    .unreadable.reason shouldContain "VelocityProxy"
            }
        }

    // ----------------------------------------------- observed state that will not read

    /**
     * A row whose observation will not decode still has a perfectly good
     * definition, so the loop can go on reconciling it. The observation is refused
     * and *marked*: [mcorch.store.StoredServer.status] stays null, and
     * `unreadable` says why, so nobody reads it as "nothing has been observed
     * yet" and starts a drain over from the beginning.
     */
    @Test
    fun `an unreadable status document costs that server its observation, not the list`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                for (name in listOf("survival-01", "survival-02", "survival-03")) {
                    store.state.putDefinition(Fixtures.definitionNamed(name)).getOrThrow()
                    store.state.putStatus(Fixtures.fullStatus(name)).getOrThrow()
                }
            }
            mutate(directory, "UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE' WHERE name = 'survival-02'")

            stores.open(directory).use { store ->
                val servers = store.state.listServers()

                servers.map { it.name.value } shouldBe listOf("survival-01", "survival-02", "survival-03")
                servers.single { it.name.value == "survival-01" }.status.shouldNotBeNull()
                servers.single { it.name.value == "survival-03" }.status.shouldNotBeNull()

                val bad = servers.single { it.name.value == "survival-02" }
                bad.status.shouldBeNull()
                bad.neverObserved shouldBe false
                bad.caughtUp shouldBe false
                val unreadable = bad.unreadable.shouldNotBeNull()
                unreadable.part shouldBe StatePart.OBSERVED
                unreadable.retryable shouldBe false
                unreadable.reason shouldContain "survival-02"

                // Nothing about it is a *desired* state failure, so listAll reports
                // it in the same place, as a server rather than as a lost row.
                store.state
                    .listAll()
                    .unreadable
                    .shouldBeEmpty()
                store.state
                    .listAll()
                    .servers
                    .single { it.name.value == "survival-02" }
                    .unreadable
                    .shouldNotBeNull()
            }
        }

    /** A point read is asked about one server, so it gets the failure rather than a snapshot with a hole in it. */
    @Test
    fun `getServer still refuses an unreadable status for the name it was asked about`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus("survival-02")).getOrThrow()
            }
            mutate(directory, "UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE'")

            stores.open(directory).use { store ->
                val failure =
                    runCatching { store.state.getServer(Fixtures.resourceName("survival-02")) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<StoreException.Corrupt>()

                failure.retryable shouldBe false
                failure.message.shouldNotBeNull() shouldContain "survival-02"
            }
        }

    /**
     * A stored failure whose reason and class contradict each other is refused
     * rather than loaded.
     *
     * `FailureStatus` refuses a permanent `DRAIN_NO_DESTINATION`: it means
     * players are online, which resolves itself when they log off, and
     * classifying it permanent would be a wedged drain that the attention
     * condition also deliberately never flags. No code path writes that pair, so
     * a row holding it was edited by hand — and this pins that the constructor
     * invariant really does run on the way *in* from disk, not only in memory.
     * The escalation's correctness now rests on that.
     *
     * Refused, and never coerced: the contradiction is not repaired into a
     * `RETRYABLE` on the way through, which would be the first place in this
     * codebase where a stop-adjacent record is silently rewritten. Nothing is
     * loaded at all — the server keeps its definition and loses its observation.
     */
    @Test
    fun `a stored failure that contradicts itself is refused rather than loaded`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-01")).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus("survival-01")).getOrThrow()
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus("survival-02")).getOrThrow()
            }
            // Exactly the two keys, so the status-level failure beside it is left
            // valid and the refusal below is about this pair and nothing else.
            mutate(
                directory,
                """
                UPDATE server_status SET status_doc = replace(
                    replace(status_doc,
                        'drain.failure.reason=DRAIN_TRANSFER_FAILED',
                        'drain.failure.reason=DRAIN_NO_DESTINATION'),
                    'drain.failure.failureClass=RETRYABLE',
                    'drain.failure.failureClass=PERMANENT')
                WHERE name = 'survival-02'
                """.trimIndent(),
            )

            stores.open(directory).use { store ->
                val bad = store.state.listServers().single { it.name.value == "survival-02" }

                bad.status.shouldBeNull()
                val unreadable = bad.unreadable.shouldNotBeNull()
                unreadable.retryable shouldBe false
                unreadable.reason shouldContain "resolves itself when they log off"

                // The point read says the same thing, as a permanent failure.
                val failure =
                    runCatching { store.state.getServer(Fixtures.resourceName("survival-02")) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<StoreException.Corrupt>()
                failure.retryable shouldBe false
                failure.message.shouldNotBeNull() shouldContain "resolves itself when they log off"

                // And it cost exactly the one server: the other still loads.
                store.state
                    .listServers()
                    .single { it.name.value == "survival-01" }
                    .status
                    .shouldNotBeNull()
            }
        }

    /**
     * A failure that stops being raised has to start being logged.
     *
     * The list reads turn a decode failure into a value, which is the one place in
     * this module where an exception does not travel on. "Do not swallow
     * exceptions" is satisfied by the caller being *offered* it only if the store
     * also says so out loud — a consumer that ignores the annotation would
     * otherwise leave the row unreported by anything, anywhere.
     */
    @Test
    fun `an unreadable row is logged, not merely offered to the caller`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus("survival-02")).getOrThrow()
            }
            mutate(directory, "UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE'")

            stores.open(directory).use { store ->
                CapturedLogs.clear()

                store.state.listServers()

                val logs = CapturedLogs.text()
                logs shouldContain "server=survival-02"
                logs shouldContain StatePart.OBSERVED.name
            }
        }

    // ------------------------------------------------------------- what the loop sees

    /**
     * The audit's sequence, at the seam it was found on: `ReconcileLoop.resync`
     * catches [StoreException], logs `resync failed` and queues *nothing*, so a
     * single undecodable row stopped the loop from being given any work at all —
     * every resync period, forever.
     */
    @Test
    fun `a loop-shaped resync still queues every server when one observation will not read`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                for (name in listOf("survival-01", "survival-02", "survival-03")) {
                    store.state.putDefinition(Fixtures.definitionNamed(name)).getOrThrow()
                    store.state.putStatus(Fixtures.fullStatus(name)).getOrThrow()
                }
            }
            mutate(directory, "UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE' WHERE name = 'survival-02'")

            stores.open(directory).use { store ->
                val loop = resyncLike(store.state)

                loop.failure.shouldBeNull()
                loop.queued shouldBe listOf("survival-01", "survival-02", "survival-03")
            }
        }

    /**
     * The other half of it. `ReconcileLoop.resumeDrains` runs before anything else
     * at startup and swallows the same failure, so no in-flight drain on *any*
     * server was picked up — against its own documented reason for existing, that
     * "a drain that is never picked up leaves players on a server nobody is
     * watching".
     *
     * The drain state a row is selected by lives beside the observation rather
     * than inside it, so the server whose observation will not decode is still
     * found here. That is the point: it is the one the loop most needs to be told
     * about.
     */
    @Test
    fun `a loop-shaped drain resumption still queues every in-flight drain when one will not read`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                for (name in listOf("survival-01", "survival-02", "survival-03")) {
                    store.state.putDefinition(Fixtures.definitionNamed(name)).getOrThrow()
                    store.state.putStatus(Fixtures.fullStatus(name, drainState = DrainState.SAVING)).getOrThrow()
                }
            }
            mutate(directory, "UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE' WHERE name = 'survival-02'")

            stores.open(directory).use { store ->
                val loop = resumeDrainsLike(store.state)

                loop.failure.shouldBeNull()
                loop.queued shouldBe listOf("survival-01", "survival-02", "survival-03")
                store.state
                    .listByDrainState(RESUMABLE_DRAIN_STATES)
                    .single { it.name.value == "survival-02" }
                    .neverObserved shouldBe false
            }
        }

    /**
     * `ReconcileLoop.resync`'s shape, reproduced rather than imported: `:store`
     * does not depend on `:core`, and a test that called the real loop would be
     * testing the loop. What is being pinned is that a consumer written this way —
     * read the list, queue every name, treat a [StoreException] as "no work this
     * pass" — comes away with work.
     */
    private suspend fun resyncLike(store: Store): LoopShapedConsumer =
        LoopShapedConsumer().apply {
            try {
                store.listServers().forEach { queued += it.name.value }
            } catch (thrown: StoreException) {
                failure = thrown
            }
        }

    /** `ReconcileLoop.resumeDrains`'s shape, for the same reason. */
    private suspend fun resumeDrainsLike(store: Store): LoopShapedConsumer =
        LoopShapedConsumer().apply {
            try {
                store.listByDrainState(RESUMABLE_DRAIN_STATES).forEach { queued += it.name.value }
            } catch (thrown: StoreException) {
                failure = thrown
            }
        }

    private class LoopShapedConsumer {
        val queued: MutableList<String> = mutableListOf()
        var failure: StoreException? = null
    }

    private fun mutate(
        directory: Path,
        sql: String,
    ) {
        connection(directory).use { connection ->
            connection.update(sql)
            connection.commit()
        }
    }

    private fun connection(directory: Path): Connection =
        DriverManager
            .getConnection("jdbc:sqlite:${directory.resolve("state.db").toAbsolutePath()}")
            .also { it.autoCommit = false }

    private companion object {
        /**
         * `ReconcileLoop.RESUMABLE_DRAIN_STATES`, spelled out here because it is
         * private to `:core`. Every state but the terminal failure: a drain in any
         * of them has side effects already issued that must not be re-issued.
         */
        val RESUMABLE_DRAIN_STATES: Set<DrainState> = DrainState.entries.toSet() - DrainState.DRAIN_FAILED
    }
}

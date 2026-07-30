package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mcorch.schema.DrainState
import mcorch.schema.PaperServerStatus
import mcorch.schema.PlacementSpec
import mcorch.store.getOrThrow
import mcorch.store.sqlite.EmbeddedStore
import mcorch.store.sqlite.EmbeddedStoreConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

/**
 * What the loop does with state the store holds and cannot read.
 *
 * Two halves of a row and two different answers, and the difference is the whole
 * point:
 *
 * - An unreadable **observation** belongs to one server, and acting on it would
 *   mean acting on a drain whose progress cannot be read. The pass refuses. That
 *   costs that server until a human repairs the row, and it is the safe side:
 *   the alternative is a mid-flight drain restarting from the beginning and
 *   re-issuing a save request that was already delivered.
 * - An unreadable **definition** belongs to one server too, and until `:store`
 *   grew a tolerant read it cost the *fleet*: one such row made `listServers`
 *   throw, so a resync queued nothing at all and the loop reconciled nothing,
 *   for ever, from a single bad row.
 */
internal class UnreadableStateTest {
    private val directories = mutableListOf<Path>()

    @AfterEach
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }

    private fun directory(): Path = createTempDirectory("mcorch-unreadable").also { directories.add(it) }

    /** Raw SQL, because the point is a row no code path in this build would write. */
    private fun mutate(
        directory: Path,
        sql: String,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("state.db").toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }

    /**
     * A drain that has already sent its save must not be restarted by a status
     * the loop can no longer read.
     *
     * The record of a delivered save lives *in* the observation, so an
     * unreadable observation is exactly the case where the loop cannot know
     * whether the request went out. Reading "no status" as "nothing has happened
     * yet" would start the drain again from `DRAIN_REQUESTED` and walk it back
     * into `SAVING` — a second `save-all flush` against a live server, which is
     * CLAUDE.md invariant 5 and the wedge `saveRequestedAt` exists to hold.
     *
     * **This already held before `:store` grew its tolerant reads, and this test
     * was green the first time it ran.** `getServer` refuses the row rather than
     * returning a [mcorch.store.StoredServer] with a null status, so the pass
     * never gets far enough to mistake "cannot read" for "nothing observed".
     * That is worth pinning precisely because it is not obvious from `:core`:
     * the protection lives in another module's point-read contract, and a
     * tolerant point read introduced later would silently remove it.
     */
    @Test
    fun `a drain whose observation cannot be read is not restarted and does not re-save`() =
        coreTest {
            val directory = directory()
            val clock = MutableClock()
            val node = FakeNode(clock = clock)
            val registry = StaticNodeRegistry(listOf(node))
            val definition = paperDefinition()
            val name = definition.metadata.name

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { embedded ->
                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)
                embedded.state.putDefinition(definition).getOrThrow()
                repeat(4) { reconciler.reconcile(name) }
                embedded.state.deleteDefinition(name).getOrThrow()
                // Exit zero with no completion reported: the request reached the
                // server and nothing confirmed it. Never re-sent, by design.
                node.savesCleanly = false
                repeat(6) { reconciler.reconcile(name) }

                val drain =
                    (
                        embedded.state
                            .getServer(name)
                            .shouldNotBeNull()
                            .status
                            ?.status as? PaperServerStatus
                    )?.drain.shouldNotBeNull()
                drain.saveRequestedAt.shouldNotBeNull()
                node.saves shouldHaveSize 1
            }

            // The row rots — a truncated write, a hand edit, an older binary.
            mutate(directory, "UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE'")

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { embedded ->
                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)
                node.savesCleanly = true

                repeat(6) { reconciler.reconcile(name) }

                // The assertion that matters: no second save, ever. A restarted
                // drain would have walked back to SAVING and sent one.
                node.saves shouldHaveSize 1
                // And nothing was stopped on state nobody could read.
                node.stops shouldHaveSize 0
                node.removals shouldHaveSize 0
                node.workload
                    .shouldBeInstanceOf<WorkloadObservation.Present>()
                    .state shouldBe WorkloadState.RUNNING
                // The definition is still there: a delete is not completed on the
                // strength of an observation the store cannot read. Read through
                // the tolerant list, because the point read is entitled to refuse
                // this row and does.
                embedded.state
                    .listAll()
                    .servers
                    .map { it.name } shouldBe listOf(name)
            }
        }

    /**
     * The loop does not touch a server whose observation it cannot read, and
     * does not let that stop it serving the rest.
     *
     * **This is a guard on the property, not a regression test for the loop's
     * skip.** Measured: it passes with the skip removed, and it passed before
     * the skip existed. The safety comes from `Reconciler` — the pass reads
     * through [mcorch.store.Store.getServer], which raises for that row, so the
     * server is never touched whether the loop queues it or not. What the skip
     * buys is a clear report once per resync instead of a generic store failure
     * per pass, and not leaning on a refusal that happens in another module.
     *
     * It is worth keeping as the end-to-end statement of the property that
     * matters: a server whose recorded state cannot be read receives **no node
     * call at all** — not an observe, and above all not a stop — while the rest
     * of the fleet keeps moving.
     */
    @Test
    fun `a server whose observation cannot be read is left alone and does not block the fleet`() =
        coreTest {
            val directory = directory()
            val clock = MutableClock()
            // One simulator per server: `FakeNode` holds a single workload, and
            // the placement pins each definition to its own node.
            val stuckNode = FakeNode(name = nodeName("node-a"), clock = clock)
            val healthyNode = FakeNode(name = nodeName("node-b"), clock = clock)
            val registry = StaticNodeRegistry(listOf(stuckNode, healthyNode))
            val stuck = paperDefinition(name = "survival-01", placement = PlacementSpec(node = nodeName("node-a")))
            val healthy =
                paperDefinition(
                    name = "lobby-01",
                    hostPort = 30002,
                    placement = PlacementSpec(node = nodeName("node-b")),
                )

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { embedded ->
                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)
                embedded.state.putDefinition(stuck).getOrThrow()
                repeat(4) { reconciler.reconcile(stuck.metadata.name) }
                embedded.state.deleteDefinition(stuck.metadata.name).getOrThrow()
                stuckNode.savesCleanly = false
                repeat(6) { reconciler.reconcile(stuck.metadata.name) }
                stuckNode.saves shouldHaveSize 1
            }

            mutate(directory, "UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE' WHERE name = 'survival-01'")
            val untouched = stuckNode.calls.size

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { embedded ->
                embedded.state.putDefinition(healthy).getOrThrow()
                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)
                val loop = ReconcileLoop(embedded.state, reconciler)
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                try {
                    val running = scope.launch { loop.run() }
                    withTimeout(30.seconds) {
                        while (healthyNode.creates.isEmpty()) delay(50)
                    }
                    // Long enough that a loop which *was* going to touch the
                    // stuck server would have: it is queued first of all if at
                    // all, since resuming drains runs before anything else.
                    delay(500)
                    running.cancelAndJoin()
                } finally {
                    scope.cancel()
                }

                // The fleet kept moving.
                healthyNode.creates shouldHaveSize 1
                // And the server nobody could read was not touched: no second
                // save, no stop, no observe, nothing.
                stuckNode.calls.size shouldBe untouched
                stuckNode.saves shouldHaveSize 1
                stuckNode.stops shouldHaveSize 0
            }
        }

    /**
     * One server's unreadable *definition* must not stop the loop reconciling
     * every other server.
     *
     * This is the reachable outage. `resync` and `resumeDrains` read the whole
     * fleet, and the strict reads throw for a row whose desired state will not
     * decode — so a single bad row meant nothing was queued, on every resync, for
     * as long as it stayed there. Nothing is lost by a halted loop directly; what
     * is lost is the ability to act at all, including finishing a drain that has
     * players waiting on it.
     */
    @Test
    fun `a definition the store cannot read costs its own server and no other`() =
        coreTest {
            val directory = directory()
            val clock = MutableClock()
            val node = FakeNode(clock = clock)
            val registry = StaticNodeRegistry(listOf(node))
            val healthy = paperDefinition(name = "survival-01")
            val rotten = paperDefinition(name = "lobby-01", hostPort = 30002)

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { embedded ->
                embedded.state.putDefinition(healthy).getOrThrow()
                embedded.state.putDefinition(rotten).getOrThrow()
            }
            mutate(directory, "UPDATE server_definition SET spec_doc = 'nonsense=1' WHERE name = 'lobby-01'")

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { embedded ->
                // The store's own view first, so a failure below can be told
                // apart from the fleet read being wrong.
                val listing = embedded.state.listAll()
                listing.servers.map { it.name.value } shouldBe listOf("survival-01")
                listing.unreadable shouldHaveSize 1

                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)
                val loop = ReconcileLoop(embedded.state, reconciler)
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                try {
                    val running = scope.launch { loop.run() }
                    // Only the resync can queue anything here: the definitions
                    // were written in an earlier store session, and `seed` reads
                    // the cursor *before* resyncing, so the change feed has
                    // nothing to replay. A resync that threw would leave this
                    // waiting for ever, which is precisely the outage.
                    withTimeout(30.seconds) {
                        while (node.creates.isEmpty()) delay(50)
                    }
                    running.cancelAndJoin()
                } finally {
                    scope.cancel()
                }

                node.creates.map { it.server.value } shouldBe listOf("survival-01")
            }
        }
}

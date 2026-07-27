package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mcorch.core.paper.PaperCommands
import mcorch.schema.DrainState
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.store.getOrThrow
import mcorch.store.sqlite.EmbeddedStore
import mcorch.store.sqlite.EmbeddedStoreConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * A save request that has gone out survives the pass being cancelled.
 *
 * ## The window
 *
 * Shutting the orchestrator down cancels the passes in flight, and a pass
 * unwinds from its next suspension point. For a drain in [DrainState.SAVING]
 * that point is the store write that records the save — so a cancellation
 * landing between the exec coming back and the write completing loses the record
 * of a side effect that has definitely happened, and the next process sends a
 * second `save-all flush`. That is CLAUDE.md invariant 5.
 *
 * `mustRecord`/`forceRecord` did not cover it: they protect a record whose write
 * is **rejected**, and a cancelled coroutine never gets as far as being rejected.
 * Every store call dispatches, and a dispatch from a cancelled coroutine does not
 * run the block at all.
 *
 * ## Both halves, because the record moved
 *
 * Which field holds the record depends on how the save ended, and they are
 * disjoint by construction:
 *
 * - confirmed → `worldSavedAt` set, `saveRequestedAt` cleared;
 * - delivered but unconfirmed → `saveRequestedAt` set, `worldSavedAt` untouched.
 *
 * Losing the confirmed one is the milder failure — the next pass re-saves a world
 * that is already on disk — but it is still a repeated side effect, and it also
 * throws away a confirmation the evidence chain would have accepted. So both are
 * tested, and each asserts the *other* field is still empty.
 *
 * ## These are deterministic, not probes
 *
 * The window is microseconds wide in production. Nothing here races it: the
 * cancellation is injected from inside [FakeNode.onExec], which runs after the
 * save command has been recorded and before the exec returns, so the losing
 * interleaving happens on every run rather than four times in two hundred.
 * `runBlocking` dispatches `launch` rather than running it inline, so the job
 * handle is assigned before the pass starts and there is no ordering to get
 * wrong.
 */
internal class SaveRecordDurabilityTest {
    private val directories = mutableListOf<Path>()

    @AfterEach
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }

    private fun directory(): Path = Files.createTempDirectory("mcorch-save-record").also { directories.add(it) }

    /**
     * Cancels [job] the instant the save command is executed, leaving every other
     * command answered normally.
     *
     * This is the shutdown signal arriving at the worst possible moment: the
     * request has reached the server and the pass has not yet written down that
     * it did.
     */
    private fun cancelOnSave(
        node: FakeNode,
        job: () -> Job?,
    ) {
        node.onExec = { command ->
            if (command == PaperCommands.saveAll()) {
                job()?.cancel(CancellationException("the orchestrator is shutting down"))
            }
            node.defaultExec(command)
        }
    }

    /** Runs one pass in a child job, so cancelling it does not take the test with it. */
    private suspend fun cancellablePass(
        scope: CoroutineScope,
        harness: Harness,
        name: ResourceName,
        arm: (() -> Job?) -> Unit,
    ) {
        var pass: Job? = null
        arm { pass }
        pass = scope.launch { harness.pass(name) }
        pass.join()
    }

    /** A drain driven up to — but not into — [DrainState.SAVING]. */
    private suspend fun drainAtSaving(harness: Harness): ResourceName {
        val definition = paperDefinition()
        val name = definition.metadata.name
        harness.declare(definition)
        harness.settle(name)
        harness.store.deleteDefinition(name).getOrThrow()
        repeat(5) { harness.pass(name) }
        harness
            .status(name)
            .shouldNotBeNull()
            .drain
            .shouldNotBeNull()
            .state shouldBe DrainState.SAVING
        harness.node.saves shouldHaveSize 0
        return name
    }

    @Test
    fun `a confirmed save is recorded even though the pass was cancelled the moment it returned`() =
        coreTest {
            val harness = Harness()
            val name = drainAtSaving(harness)

            cancellablePass(this, harness, name) { job -> cancelOnSave(harness.node, job) }

            // The side effect happened, so the record of it has to exist.
            harness.node.saves shouldHaveSize 1
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.worldSavedAt.shouldNotBeNull()
            // Disjointness: a confirmation never sits beside its own outstanding
            // request, which is what used to make the next `SAVING` read a
            // completed save as one that never came back.
            drain.saveRequestedAt shouldBe null

            // And the next process does not re-send it. The container stops on
            // the confirmation this pass paid for, rather than saving again
            // first.
            harness.node.onExec = { command -> harness.node.defaultExec(command) }
            repeat(4) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
        }

    @Test
    fun `a delivered but unconfirmed save is recorded even though the pass was cancelled`() =
        coreTest {
            val harness = Harness()
            // Exit zero, no completion reported: the request reached the server
            // and nothing confirmed the write, which is the bucket that is never
            // re-sent.
            harness.node.savesCleanly = false
            val name = drainAtSaving(harness)

            cancellablePass(this, harness, name) { job -> cancelOnSave(harness.node, job) }

            harness.node.saves shouldHaveSize 1
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.saveRequestedAt.shouldNotBeNull()
            drain.worldSavedAt shouldBe null

            // The wedge holds: a healthy exec channel afterwards does not license
            // a second request, and nothing is stopped on evidence nobody gave.
            harness.node.savesCleanly = true
            harness.node.onExec = { command -> harness.node.defaultExec(command) }
            repeat(4) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
        }

    /**
     * The property that actually matters, end to end and across a store that has
     * been closed and reopened.
     *
     * The in-memory assertions above prove the write happened; this proves it is
     * *on disk*, through the real embedded store, and that a genuinely new
     * process — new store handle, new reconciler, the same runtime still holding
     * the same container — does not send a second save.
     */
    @Test
    fun `a save issued by a cancelled pass is not re-sent by the next process`() =
        coreTest {
            val directory = directory()
            val clock = MutableClock()
            // The runtime outlives the orchestrator: same node, same container,
            // across the restart. That is the whole point of the scenario.
            val node = FakeNode(clock = clock)
            val registry = StaticNodeRegistry(listOf(node))
            val definition = paperDefinition()
            val name = definition.metadata.name

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { embedded ->
                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)
                embedded.state.putDefinition(definition).getOrThrow()
                repeat(4) { reconciler.reconcile(name) }
                embedded.state.deleteDefinition(name).getOrThrow()
                repeat(5) { reconciler.reconcile(name) }
                node.saves shouldHaveSize 0

                var pass: Job? = null
                cancelOnSave(node) { pass }
                pass = launch { reconciler.reconcile(name) }
                pass.join()
                node.saves shouldHaveSize 1
            }

            // A new process: nothing carried over but the database file and the
            // containers that were already running.
            node.onExec = { command -> node.defaultExec(command) }
            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { embedded ->
                // Read before the drain is allowed to finish, because finishing
                // it purges the row this record lives on.
                val recovered =
                    (
                        embedded.state
                            .getServer(name)
                            .shouldNotBeNull()
                            .status
                            ?.status as? PaperServerStatus
                    )?.drain
                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)
                repeat(6) { reconciler.reconcile(name) }

                // The assertion that is the whole point, and it is asserted
                // first: one save for this drain, ever. Without the record on
                // disk the next process finds a drain in SAVING with nothing
                // saved and sends a second `save-all flush` — CLAUDE.md
                // invariant 5, against a container it is about to stop.
                node.saves shouldHaveSize 1
                // And the drain still finished, so the recovered record was good
                // enough to authorise the stop rather than merely blocking it.
                node.stops shouldHaveSize 1
                embedded.state.getServer(name) shouldBe null

                // And the record the new process read off disk says what it has
                // to say: the save was confirmed, and the request was never left
                // outstanding beside it.
                recovered.shouldNotBeNull().worldSavedAt.shouldNotBeNull()
                recovered.saveRequestedAt shouldBe null
            }
        }

    /**
     * The shield covers the record, not the pass.
     *
     * A pass cancelled *before* it has done anything irreversible must stop where
     * it is, because the alternative — a region wide enough to hold a container
     * operation — is a shutdown that waits out a save timeout. Cancelling at the
     * readiness probe, one step earlier than the tests above, must therefore
     * leave the save unsent and nothing recorded.
     */
    @Test
    fun `a pass cancelled before the side effect issues nothing and records nothing`() =
        coreTest {
            val harness = Harness()
            val name = drainAtSaving(harness)
            val before = harness.store.statusPuts
            val recorded = harness.status(name).shouldNotBeNull()

            var pass: Job? = null
            harness.node.onExec = { command ->
                if (command.firstOrNull() == "mc-monitor") {
                    pass?.cancel(CancellationException("the orchestrator is shutting down"))
                }
                harness.node.defaultExec(command)
            }
            pass = launch { harness.pass(name) }
            pass.join()

            // The probe is a read and it answered; the save behind it never went
            // out, because the node refuses a cancelled caller the way a real one
            // does.
            harness.node.probes.size shouldBeGreaterThan 0
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            // Nothing was written either: there is no side effect to make
            // durable, so the pass simply unwinds.
            harness.store.statusPuts shouldBe before
            harness.status(name) shouldBe recorded
        }
}

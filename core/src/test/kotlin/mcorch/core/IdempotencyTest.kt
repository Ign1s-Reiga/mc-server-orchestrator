package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.DrainState
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * CLAUDE.md invariant 5: running reconcile repeatedly against the same desired
 * and observed state must not accumulate side effects.
 *
 * Every assertion here is about *what was done* — creates, pulls, save
 * requests, store writes — rather than about what a pass returned. A pass can
 * report "settled" while having created a second container.
 */
internal class IdempotencyTest {
    @Test
    fun `a second pass against unchanged state does nothing at all`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)

            harness.settle(name).shouldBeInstanceOf<ReconcileOutcome.Settled>()
            val pulls = harness.node.pulls.size
            val creates = harness.node.creates.size
            val starts = harness.node.starts.size
            val saves = harness.node.saves.size
            val writes = harness.store.statusWrites

            val second = harness.pass(name)

            second.shouldBeInstanceOf<ReconcileOutcome.Settled>()
            harness.node.pulls shouldHaveSize pulls
            harness.node.creates shouldHaveSize creates
            harness.node.starts shouldHaveSize starts
            harness.node.saves shouldHaveSize saves
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            // A settled server generates no store traffic either: the status it
            // would write says exactly what the stored one says.
            harness.store.statusWrites shouldBe writes
        }

    @Test
    fun `ten further passes still create one container and pull one image`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            repeat(10) { harness.pass(name) }

            harness.node.creates shouldHaveSize 1
            harness.node.pulls shouldHaveSize 1
            harness.node.starts shouldHaveSize 1
        }

    @Test
    fun `an unchanged observation is refreshed once the heartbeat has elapsed`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(statusHeartbeat = 30.seconds))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            val writes = harness.store.statusWrites

            harness.pass(name)
            harness.store.statusWrites shouldBe writes

            // A status that never advances must mean the loop has died, not
            // that nothing is happening — so it is refreshed on a heartbeat.
            harness.clock.advance(1.minutes)
            harness.pass(name)
            harness.store.statusWrites shouldBe writes + 1
        }

    @Test
    fun `re-applying an identical definition does not restart anything`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The store keeps the generation still for an identical spec, so
            // the loop must see no diff at all.
            harness.declare(definition)
            val outcome = harness.pass(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Settled>()
            harness.node.creates shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
            harness.status(name).shouldNotBeNull().drain shouldBe null
        }

    /**
     * A probe the node keeps cutting short now reads as "not joinable yet"
     * rather than as an unreachable runtime, which means a starting server sits
     * in this state for as long as world generation takes — thirty passes, in
     * the run that found the bug. Every one of them must do exactly what the
     * last one did.
     */
    @Test
    fun `a second pass against a server whose probe keeps timing out changes nothing`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.pass(name)
            harness.pass(name)
            harness.node.failAlways(NodeOperation.EXEC, harness.node.commandTimedOut(NodeOperation.EXEC))

            val first = harness.pass(name)
            val pulls = harness.node.pulls.size
            val creates = harness.node.creates.size
            val starts = harness.node.starts.size
            val writes = harness.store.statusWrites
            val status = harness.status(name)

            val second = harness.pass(name)

            second shouldBe first
            harness.node.pulls shouldHaveSize pulls
            harness.node.creates shouldHaveSize creates
            harness.node.starts shouldHaveSize starts
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.saves shouldHaveSize 0
            // The observation did not change, so it was not rewritten — a
            // starting server must not generate a store write per pass for the
            // whole of world generation.
            harness.store.statusWrites shouldBe writes
            harness.status(name) shouldBe status
        }

    @Test
    fun `a resumed drain does not re-send a save request that already went out`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The save goes out and its completion is never confirmed — the
            // loop died between the request and the reply.
            harness.node.savesCleanly = false
            harness.store.deleteDefinition(name)
            repeat(6) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.saveRequestedAt.shouldNotBeNull()

            // A restart re-reads the drain from the store. It must not ask the
            // server to save again, and it must not stop the container on the
            // strength of a save nobody confirmed.
            harness.node.savesCleanly = true
            repeat(4) { harness.pass(name) }

            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }
}

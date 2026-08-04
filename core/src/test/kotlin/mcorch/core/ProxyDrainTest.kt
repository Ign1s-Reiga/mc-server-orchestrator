package mcorch.core

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import org.junit.jupiter.api.Test

/**
 * The drain, with a proxy on the other end of it.
 *
 * Every test here asserts on **side effects at the proxy and at the runtime** —
 * what was sealed, what was transferred, what was deregistered, what was stopped —
 * because that is what the invariants are about. The most important assertions are
 * the negative ones: *no stop was issued*, *nobody was disconnected*.
 */
internal class ProxyDrainTest {
    /**
     * A proxied drain with players **transfers them**.
     *
     * This is the state that used to be unreachable. `requireEmpty` wrapped
     * `SEALED`, `TARGET_RESOLVED` and `TRANSFERRING`, so a drain of a server with
     * anybody on it aborted at step 3 and never transferred anyone — the guard
     * aborted precisely on the precondition that is supposed to *trigger* the
     * destination search.
     *
     * The order is a wire property and is asserted as one: the seal is asserted
     * before the transfer, because the plugin refuses `POST /transfer` with
     * `SOURCE_NOT_SEALED` otherwise.
     */
    @Test
    fun `a proxied drain with players online transfers them instead of aborting`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(leaving, destination))
            harness.bringUp()

            // Three people are playing on the server somebody just asked to delete.
            harness.nodeOf(leaving).online = 3
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .players = 3
            harness.store.deleteDefinition(leaving.metadata.name)

            // requested -> sealed -> destination -> transfer
            repeat(4) { harness.pass(leaving.metadata.name) }

            harness.plugin.asserts shouldContain ("survival-01" to false)
            harness.plugin.sweepsStarted shouldContain "survival-01"
            harness.plugin.transfers
                .single()
                .second shouldBe "survival-02"
            // Step 2 precedes step 4, which the plugin enforces: a sweep on an
            // unsealed backend is `SOURCE_NOT_SEALED`, so reaching one at all
            // proves the ordering held.
            harness.scheduler.destinationRequests.shouldHaveSize(1)
            harness.scheduler.destinationRequests
                .single()
                .candidates
                .map { it.server.value } shouldBe listOf("survival-02")

            // Nothing was stopped and nobody was disconnected.
            harness.nodeOf(leaving).stops.shouldBeEmpty()
            harness.nodeOf(leaving).saves.shouldBeEmpty()

            // The players arrive, the ping goes to zero, and the drain finishes.
            harness.plugin.completeSweep("survival-01")
            harness.nodeOf(leaving).online = 0
            repeat(8) { harness.pass(leaving.metadata.name) }

            harness.plugin.deregistrations shouldContain "survival-01"
            harness.nodeOf(leaving).saves shouldHaveSize 1
            harness.nodeOf(leaving).stops shouldHaveSize 1
        }

    /**
     * A failing transfer must back off, not spin.
     *
     * Round 4 closed the hot loop because a resume ran the resumed state in the
     * same pass. Bodies reopen it: `DRAIN_FAILED` resumes to `SEALED`, the
     * destination search succeeds, that reports `Progressed`, `ReconcileLoop` calls
     * `queue.succeeded` and clears the attempt counter — then the next pass
     * transfers, fails, and parks again. A two-second loop, for ever, issuing
     * destination lookups and **transfer requests at live players**, with `attempts`
     * pinned at 1 and the backoff never growing.
     *
     * It is measured rather than reasoned about: the number of sweeps the plugin
     * was asked to start, and the failure's attempt count, over twenty passes.
     */
    @Test
    fun `a drain whose transfer keeps failing backs off instead of spinning`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(leaving, destination))
            harness.bringUp()

            harness.nodeOf(leaving).online = 4
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .players = 4
            // The destination goes away between step 3 and step 4 and stays away,
            // so every sweep request is refused.
            harness.store.deleteDefinition(leaving.metadata.name)
            repeat(2) { harness.pass(leaving.metadata.name) }
            harness.plugin.backends.remove("survival-02")

            repeat(20) { harness.pass(leaving.metadata.name) }

            val status = harness.status(leaving.metadata.name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED

            // The measurement. Twenty passes against a destination that is never
            // coming back must not produce twenty sweeps: the drain stops asking at
            // the limit, and every pass after that is one abort rather than a
            // resolve/transfer cycle.
            harness.plugin.sweepsStarted.size shouldBeLessThanOrEqual 6
            // And the counter the backoff is built on actually moves, which is the
            // half that the `Progressed` reset used to destroy.
            drain.failure.shouldNotBeNull().attempts shouldBeGreaterThan 1

            // At the limit the loop stops trying. It does not kick and it does not
            // stop (`failure-modes.md` item 7).
            harness.nodeOf(leaving).stops.shouldBeEmpty()
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
        }

    /**
     * An aborted drain restores joins, and a proxy restart re-asserts the seal.
     *
     * Both fall out of the seal being level-triggered rather than issued. The first
     * matters because a retryable abort leaves the server running: an event-shaped
     * seal would leave it sealed off from new joins indefinitely while the dashboard
     * showed a healthy running server no player could reach. The second matters
     * because seal state is soft and never persisted, so a restart lifts every seal
     * — and `:core` asserts *then* reads, so the read alone cannot tell a seal that
     * held from one lifted moments earlier.
     */
    @Test
    fun `an aborted drain restores joins and a proxy restart re-asserts the seal`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            harness.bringUp()

            harness.nodeOf(leaving).online = 2
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .players = 2
            harness.store.deleteDefinition(leaving.metadata.name)
            // requested -> sealed. No second backend, so step 3 finds no capacity
            // and the drain parks.
            repeat(4) { harness.pass(leaving.metadata.name) }

            harness
                .status(leaving.metadata.name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .reason shouldBe FailureReason.DRAIN_NO_DESTINATION
            harness.nodeOf(leaving).stops.shouldBeEmpty()

            // The proxy's own pass is what restores joins, and it has to be: a
            // permanently aborted drain stops the *backend* being reconciled at all,
            // so nothing else could.
            harness.pass(harness.proxyDefinition.metadata.name)
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .admits
                .shouldBeTrue()

            // Now the proxy restarts. Every seal and every registration is gone,
            // because none of it is persisted.
            harness.plugin.restart()
            harness.plugin.backend("survival-01") shouldBe null

            harness.pass(harness.proxyDefinition.metadata.name)
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .name shouldBe "survival-01"
            // Re-registered by the sweep, with no edge anybody had to remember to
            // write — which is the whole argument for asserting rather than issuing.
            harness.plugin.registrations.count { it == "survival-01" } shouldBe 2
        }

    /**
     * The gate stays on the workload's own Server List Ping.
     *
     * A proxy count is cheaper — one RPC where SLP is an `ExecSync` — and it is
     * strictly wrong: a client connected straight to the backend's port is
     * invisible to the proxy and visible to SLP, and whether backends are firewalled
     * is a deployment property this code cannot assert.
     *
     * So here the proxy says zero and the ping says two. The drain must **not**
     * stop, must not save, and must record the ping's number.
     */
    @Test
    fun `a proxy reporting zero does not move a gate the ping says is occupied`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(leaving, destination))
            harness.bringUp()

            // The disagreement, in the direction that matters: two players are
            // connected straight to the backend's port, so the proxy cannot see
            // them.
            harness.nodeOf(leaving).online = 2
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .players = 0

            harness.store.deleteDefinition(leaving.metadata.name)
            repeat(10) { harness.pass(leaving.metadata.name) }

            harness.nodeOf(leaving).stops.shouldBeEmpty()
            harness.nodeOf(leaving).saves.shouldBeEmpty()
            harness.plugin.deregistrations.shouldBeEmpty()
            // Occupancy on the status is the ping's, from its single construction
            // site — never a number the proxy supplied.
            harness
                .status(leaving.metadata.name)
                .shouldNotBeNull()
                .players
                .shouldNotBeNull()
                .online shouldBe 2

            // The discriminating assertions, and they are the ones that can
            // actually go red.
            //
            // The three negatives above are satisfied by the `SAVING` gate whatever
            // step 4 concluded — a drain that *had* believed the proxy would still
            // not stop, save or deregister, and this test would pass having proved
            // nothing. What differs is where the drain ends up. Believing the proxy
            // makes step 4 conclude the server is empty, walk to `SAVING`, and be
            // stopped there by the ping — which records a **block** and no failure.
            // Not believing it keeps step 4 asking until the limit, which records
            // `DRAIN_TRANSFER_FAILED` and no block.
            val drain =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_TRANSFER_FAILED
            drain.blocked shouldBe null
            drain.transferAttempts shouldBeGreaterThan 1
            harness.plugin.transfers.size shouldBeGreaterThan 1
        }

    /**
     * A container the runtime says is gone must not leave a live registration.
     *
     * `advance` jumps straight to `STOPPING` with `playersEvacuated = true` on that
     * observation, skipping all seven steps. Right for the container — there is
     * provably nobody connected — and it used to leave the proxy holding a
     * registration for a backend about to be removed, after which the proxy routes
     * new players to a dead address.
     *
     * Safe on this path and no other: the runtime has said there is no container,
     * and the plugin refuses `DELETE` outright while anybody is connected anyway.
     */
    @Test
    fun `a container the runtime reports gone does not leave a live registration`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            harness.bringUp()
            harness.plugin.backend("survival-01").shouldNotBeNull()

            // The container exits underneath the loop — an operator stopped it, or
            // it crashed — and only then is the definition deleted.
            val present =
                harness.nodeOf(leaving).workload as WorkloadObservation.Present
            harness.nodeOf(leaving).workload =
                present.copy(state = WorkloadState.EXITED, exitCode = 0)
            harness.store.deleteDefinition(leaving.metadata.name)

            harness.pass(leaving.metadata.name)
            harness.pass(leaving.metadata.name)

            harness.plugin.deregistrations shouldContain "survival-01"
            harness.plugin.backend("survival-01") shouldBe null
            // Nothing was stopped: it was already down.
            harness.nodeOf(leaving).stops.shouldBeEmpty()
        }

    /**
     * A drain that gets as far as deregistering and then fails puts the
     * registration back.
     *
     * Deregistration is the one proxy step that cannot be level-triggered — it is
     * the last thing before the stop, so re-asserting it every pass would mean
     * asserting it from states that must not reach it. It therefore needs an
     * explicit edge on the abort path, and without one a drain that deregistered and
     * then could not stop leaves a running server unreachable through the proxy with
     * nothing left that would re-add it.
     */
    @Test
    fun `a drain that aborts after deregistering re-registers the backend`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            harness.bringUp()
            harness.store.deleteDefinition(leaving.metadata.name)

            // Empty server: start, seal, no destination needed, transfer (nobody),
            // zero confirmed, save, deregister. Seven passes, one step each.
            repeat(7) { harness.pass(leaving.metadata.name) }
            harness.plugin.deregistrations shouldContain "survival-01"
            harness.plugin.backend("survival-01") shouldBe null

            // The stop then fails permanently. The drain parks with the container
            // still running and the backend out of the routing table.
            harness
                .nodeOf(leaving)
                .failAlways(NodeOperation.STOP, harness.nodeOf(leaving).rejected(NodeOperation.STOP))
            harness.pass(leaving.metadata.name)

            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .name shouldBe "survival-01"
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .admits
                .shouldBeTrue()
            harness
                .status(leaving.metadata.name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .deregisteredAt shouldBe null
        }

    /**
     * A control endpoint that stops answering aborts the drain rather than
     * carrying on unsealed.
     *
     * Retryable, and the container keeps running. Carrying on would mean
     * transferring into a backend that is still admitting, so the sweep refills
     * behind itself and `remaining` never reaches zero — which is exactly what the
     * protocol's `SOURCE_NOT_SEALED` exists to make unreachable.
     */
    @Test
    fun `a drain whose proxy stops answering aborts retryably and stops nothing`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            harness.bringUp()

            harness.plugin.unreachable = true
            harness.store.deleteDefinition(leaving.metadata.name)
            repeat(4) { harness.pass(leaving.metadata.name) }

            val drain =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            val failure = drain.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "new joins could not be stopped at the proxy"

            harness.nodeOf(leaving).stops.shouldBeEmpty()
            harness.nodeOf(leaving).saves.shouldBeEmpty()
        }

    /**
     * Two servers draining at once must not select each other.
     *
     * `PaperServerStatus.draining` is deliberately **false** in `DRAIN_FAILED`, so
     * a server parked on a retryable abort reads as not-draining and looks like a
     * perfectly good destination — moments before it tries to stop again. Each
     * drain would then move its players onto the other, the other would move them
     * back, and neither would ever reach a stop.
     *
     * The exclusion is therefore on *any drain record at all*, which is what
     * `drainInitiated` means, and it is asserted on the candidate list the
     * scheduler was actually handed.
     */
    @Test
    fun `two servers draining at once do not select each other as destinations`() =
        coreTest {
            val first = backendDefinition("survival-01")
            val second = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(first, second))
            harness.bringUp()

            harness.nodeOf(first).online = 2
            harness.nodeOf(second).online = 1
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .players = 2
            harness.plugin
                .backend("survival-02")
                .shouldNotBeNull()
                .players = 1

            // Both are asked to go away, and both are reconciled interleaved the way
            // a resync would.
            harness.store.deleteDefinition(first.metadata.name)
            harness.store.deleteDefinition(second.metadata.name)
            repeat(6) {
                harness.pass(first.metadata.name)
                harness.pass(second.metadata.name)
            }

            // Every candidate either side was offered carries a drain record, so
            // neither is eligible and both record the fleet-capacity failure.
            harness.scheduler.destinationRequests
                .takeLast(2)
                .flatMap { it.candidates }
                .all { it.drainInitiated }
                .shouldBeTrue()
            listOf(first, second).forEach { server ->
                harness
                    .status(server.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
                    .failure
                    .shouldNotBeNull()
                    .reason shouldBe FailureReason.DRAIN_NO_DESTINATION
            }

            // Nobody was moved and nothing was stopped. Five people are still
            // playing, on two servers somebody asked to delete.
            harness.plugin.sweepsStarted.shouldBeEmpty()
            harness.nodeOf(first).stops.shouldBeEmpty()
            harness.nodeOf(second).stops.shouldBeEmpty()
        }

    /**
     * Two passes over a drain that is waiting change nothing at the proxy.
     *
     * The idempotency assertion for the drain path, and it is on side effects: no
     * second sweep started, no second deregistration, no stop. A repeat request
     * naming the same destination joins the running sweep and asks nobody to move
     * again, which is the protocol's job — this pins that `:core` relies on it
     * rather than gating on a timestamp of its own.
     */
    @Test
    fun `re-entering a transfer joins the sweep instead of starting a second one`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(leaving, destination))
            harness.bringUp()

            harness.nodeOf(leaving).online = 3
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .players = 3
            harness.store.deleteDefinition(leaving.metadata.name)
            repeat(4) { harness.pass(leaving.metadata.name) }
            harness.plugin.sweepsStarted shouldHaveSize 1

            // Two more passes while the sweep is still in flight.
            harness.pass(leaving.metadata.name)
            harness.pass(leaving.metadata.name)

            harness.plugin.sweepsStarted shouldHaveSize 1
            harness.plugin.transfers.size shouldBeGreaterThan 1
            harness.nodeOf(leaving).stops.shouldBeEmpty()
            harness.plugin.deregistrations.shouldBeEmpty()
        }
}

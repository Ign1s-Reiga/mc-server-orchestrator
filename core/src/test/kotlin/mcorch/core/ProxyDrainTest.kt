package mcorch.core

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.schema.ConditionStatus
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.StorageSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

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
     * A transfer that never converges must back off, and must not spin.
     *
     * Round 4 closed the hot loop because a resume ran the resumed state in the
     * same pass. Bodies reopen it: `DRAIN_FAILED` resumes, the destination search
     * succeeds, that reports `Progressed`, `ReconcileLoop` calls `queue.succeeded`
     * and clears the attempt counter — then the next pass transfers, fails, and
     * parks again. A two-second loop, for ever, issuing destination lookups and
     * **transfer requests at live players**, with the backoff never growing.
     *
     * ## The instrument
     *
     * The property that actually matters is that **no pass reports `Progressed`
     * while the transfer is unfinished**, because `Progressed` is the only thing
     * that resets the backoff. That is measured directly here rather than through a
     * proxy-side counter: an earlier version of this test asserted
     * `sweepsStarted <= 6` in a scenario where the destination had been removed from
     * the plugin, so `transfer` refused before recording anything and the counter
     * was structurally zero. It could not have failed.
     *
     * The scenario here keeps a real, eligible destination and a sweep that never
     * settles — the ordinary "players are slow to move" case — so every request is
     * recorded and the counter can move.
     */
    @Test
    fun `a transfer that never converges backs off instead of spinning`() =
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
            harness.store.deleteDefinition(leaving.metadata.name)

            val outcomes = mutableListOf<ReconcileOutcome>()

            // First: the bound is the clock, not a count of passes. Ten passes at
            // the poll interval is a couple of seconds of wall time and well inside
            // the 120s allowance, so a drain that is trying must still be trying. A
            // pass-counting bound spends a six-sweep budget here in twelve seconds
            // and parks — which is what made the documented allowance unreachable
            // and, with the ordering bug above it, made the drain unfinishable.
            repeat(10) {
                outcomes += harness.pass(leaving.metadata.name)
                harness.clock.advance(2.seconds)
            }
            harness
                .status(leaving.metadata.name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.TRANSFERRING

            // Then let the allowance actually elapse.
            repeat(20) {
                outcomes += harness.pass(leaving.metadata.name)
                harness.clock.advance(10.seconds)
            }

            val drain =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()

            // The instrument is not vacuous: requests were recorded, so a counter
            // that failed to move would be a real observation rather than an absence.
            harness.plugin.transfers.size shouldBeGreaterThan 1

            // The measurement. Once the drain is in step 4 nothing reports progress,
            // so `ReconcileLoop` never resets the backoff.
            val afterTransfer = outcomes.dropWhile { it !is ReconcileOutcome.Waiting }
            afterTransfer.none { it is ReconcileOutcome.Progressed }.shouldBeTrue()

            // And it stops asking rather than asking for ever.
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_TRANSFER_FAILED
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
            drain.failure.shouldNotBeNull().attempts shouldBeGreaterThan 1

            // At the limit the loop stops trying. It does not kick and it does not
            // stop (`failure-modes.md` item 7).
            harness.nodeOf(leaving).stops.shouldBeEmpty()
        }

    /**
     * A drain that spent its transfer budget still finishes once the server empties.
     *
     * The wedge this pins is the worst state the proxy work can produce, because its
     * exit is a manual `crictl stop` — a container stopped with no save.
     * `startTransfer` used to go straight to the limit check, which reads no player
     * count, so once `transferAttempts` was spent the resume ladder re-entered the
     * limit on every pass **and never looked at whether anybody was still there**.
     * The delete could not be completed by waiting for the last player to log off,
     * by editing the definition (the drain record survives a generation bump), or by
     * restarting the loop. Meanwhile `DRAIN_FAILED` does not seal, so the proxy
     * sweep kept re-admitting players to it.
     *
     * The fix is an ordering: the zero-player exit is taken *before* the limit is
     * consulted. A limit bounds how long the loop keeps asking, and an empty server
     * has nothing left to ask for.
     */
    @Test
    fun `a drain that has spent its transfer budget still finishes when the server empties`() =
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
            harness.store.deleteDefinition(leaving.metadata.name)

            // Spend the budget: the sweep never settles and the allowance elapses.
            repeat(20) {
                harness.pass(leaving.metadata.name)
                harness.clock.advance(10.seconds)
            }
            harness
                .status(leaving.metadata.name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .reason shouldBe FailureReason.DRAIN_TRANSFER_FAILED

            // Now everybody logs off by themselves, which is what the protocol has
            // been waiting for all along.
            harness.plugin.completeSweep("survival-01")
            harness.nodeOf(leaving).online = 0

            repeat(10) { harness.pass(leaving.metadata.name) }

            // The drain completes: saved, deregistered, stopped, in that order.
            harness.nodeOf(leaving).saves shouldHaveSize 1
            harness.plugin.deregistrations shouldContain "survival-01"
            harness.nodeOf(leaving).stops shouldHaveSize 1
            harness.store.getServer(leaving.metadata.name) shouldBe null
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
            // nothing. What differs is where the drain ends up: believing the proxy
            // makes step 4 conclude the server is empty and walk on to `SAVING`.
            //
            // Keyed on the state and on `playersEvacuated`, deliberately, and not on
            // which failure gets recorded — that would couple this test to the
            // transfer retry limit, so a change to the limit would turn it red for a
            // reason that has nothing to do with which count decides.
            val drain =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.TRANSFERRING
            drain.playersEvacuated shouldBe false
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
     * The proxy's sweep must not undo drain step 6.
     *
     * `holdSeal` stops asserting once the backend has been deregistered, with a
     * comment explaining that putting it back between steps 6 and 7 would re-add an
     * entry moments before the container stops. The proxy's own level trigger then
     * did exactly that: `DEREGISTERED.sealsBackend()` is true, so the backend stayed
     * in the matched set and received a `PUT` on any proxy pass landing in that
     * window. Sealed, so nothing is routed there deliberately — but Velocity's own
     * fallback reconnect can land a player on any *registered* backend, and this one
     * is about to go away.
     *
     * One pass wide, and the two halves of the same rule have to agree about it.
     */
    @Test
    fun `a proxy pass between steps 6 and 7 does not re-register the backend`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            harness.bringUp()
            harness.store.deleteDefinition(leaving.metadata.name)

            // Seven passes lands exactly in the window: deregistered, stop next.
            repeat(7) { harness.pass(leaving.metadata.name) }
            val drain =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DEREGISTERED
            drain.deregisteredAt.shouldNotBeNull()
            harness.plugin.backend("survival-01") shouldBe null
            harness.nodeOf(leaving).stops.shouldBeEmpty()

            // The proxy sweeps while the drain sits between step 6 and step 7.
            harness.pass(harness.proxyDefinition.metadata.name)

            harness.plugin.backend("survival-01") shouldBe null

            // And the drain still completes from there.
            repeat(4) { harness.pass(leaving.metadata.name) }
            harness.nodeOf(leaving).stops shouldHaveSize 1
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
     * A backend registered before it was joinable must still be drainable.
     *
     * `:core` derived a backend's address in two places, and they disagreed for
     * exactly the window that matters. The proxy sweep read
     * `status.endpoint.address` — written only by `awaitJoinable` and cleared by
     * `teardown` — and fell back to the *server name*; the drain derived the node.
     * So a proxy pass landing while a backend was `Absent`, `CREATING` or `STARTING`
     * registered it under a hostname that does not resolve.
     *
     * Two things followed, and the second is the wedge. Players routed to that entry
     * got a connection failure while the fleet reported healthy — and every later
     * assertion sent the node instead, which the plugin answers with
     * `ADDRESS_CONFLICT` rather than upserting, so **drain step 2 aborted on every
     * pass, for ever**. The only thing that clears a wrong registration is `DELETE`,
     * which is step 6, which step 2 never reaches.
     *
     * This is the window `ProxyHarness.sweep()` cannot produce, because it runs
     * backends before the proxy: the proxy is brought up first here, and the backend
     * is declared afterwards.
     */
    @Test
    fun `a backend registered before it was joinable can still be sealed and drained`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))

            // The proxy comes up on its own, with nothing behind it yet.
            harness.declare(harness.proxyDefinition)
            repeat(4) { harness.pass(harness.proxyDefinition.metadata.name) }

            // The backend is declared and the proxy sweeps *before* it is joinable —
            // the recreate window of every REPLACEMENT and RELOCATION, and the
            // ordinary case for a server added to a running fleet.
            harness.declare(leaving)
            harness.pass(harness.proxyDefinition.metadata.name)
            harness
                .status(leaving.metadata.name)
                ?.endpoint shouldBe null

            // Now it comes up, and the proxy sweeps again.
            repeat(4) { harness.pass(leaving.metadata.name) }
            harness.pass(harness.proxyDefinition.metadata.name)

            // Whatever it was registered as, it is registered as one thing: a second
            // assertion never contradicts the first, so the plugin never has to
            // refuse one.
            val registered = harness.plugin.backend("survival-01").shouldNotBeNull()
            registered.address shouldBe "node-survival-01:25565"

            // And the drain gets past step 2, which is the property the conflict
            // destroyed. It reaches the stop.
            harness.store.deleteDefinition(leaving.metadata.name)
            repeat(10) { harness.pass(leaving.metadata.name) }

            harness
                .status(leaving.metadata.name)
                ?.drain
                ?.failure shouldBe null
            harness.plugin.deregistrations shouldContain "survival-01"
            harness.nodeOf(leaving).stops shouldHaveSize 1
        }

    /**
     * A drain that could not seal on its one step-2 pass still stops asking.
     *
     * `sealRequestedAt` is written at exactly one place and `holdSeal` runs above
     * it, so a single `Unavailable` on the drain's one bodied `DRAIN_REQUESTED`
     * pass — the control endpoint blinking, which this design treats as expected —
     * meant the stamp never happened. Nothing re-enters `DRAIN_REQUESTED`: the
     * resume ladder tops out at `SEALED`, and `started()` needs no drain record at
     * all. So the anchor was absent for the life of that drain, `exhausted` fell
     * back to `enteredStateAt`, and the bound could never trip: ~2 minutes of
     * asking, one pass parked, the allowance handed back in full, for ever, with
     * `failure` cleared each cycle so nothing escalated. Sealed, unjoinable,
     * transfer requests firing at live players, and a delete that never completes.
     *
     * The anchor is stamped on entry to step 4 instead, which every path takes.
     */
    @Test
    fun `a drain whose first seal was refused still reaches the transfer bound`() =
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

            // Pass one records the drain and returns. Pass two is the only bodied
            // `DRAIN_REQUESTED` pass this drain will ever have — and the endpoint is
            // down for exactly that one.
            harness.pass(leaving.metadata.name)
            harness.plugin.unreachable = true
            harness.pass(leaving.metadata.name)
            harness.plugin.unreachable = false

            // The premise, asserted rather than assumed: step 2 never stamped, and
            // the drain will never revisit the state that would.
            harness
                .status(leaving.metadata.name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .sealRequestedAt shouldBe null

            repeat(30) {
                harness.pass(leaving.metadata.name)
                harness.clock.advance(10.seconds)
            }

            val drain =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // Step 4 stamped its own anchor, and it is still null at step 2.
            drain.sealRequestedAt shouldBe null
            drain.transferStartedAt.shouldNotBeNull()
            // And the bound trips, which is the whole point.
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_TRANSFER_FAILED
            harness.nodeOf(leaving).stops.shouldBeEmpty()
        }

    /**
     * Everything before step 4 must not spend step 4's allowance.
     *
     * The knob is `spec.lifecycle.drain.playerTransferTimeout` and it is documented
     * as how long step 4 gets. Anchored at step *2* it was how long everything after
     * step 2 gets — a destination search parked on a full fleet, a flapping control
     * endpoint, or simply an orchestrator restart, since the anchor is persisted. A
     * drain that waited out its allowance before a destination existed then aborted
     * on its first `TARGET_RESOLVED` pass **having asked nobody to move**, and the
     * fleet silently degraded to the standalone wait-for-zero posture while the
     * proxy sweep kept re-admitting joiners.
     */
    @Test
    fun `a long wait for capacity does not spend the transfer allowance`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val destination = backendDefinition("survival-02", hostPort = 30002)
            // Both are known to the harness so both have a node, but only the first
            // is declared: the second arrives later, which is the scenario.
            val harness = ProxyHarness(backends = listOf(leaving, destination))
            harness.declare(harness.proxyDefinition)
            harness.declare(leaving)
            repeat(6) {
                harness.pass(leaving.metadata.name)
                harness.pass(harness.proxyDefinition.metadata.name)
            }

            harness.nodeOf(leaving).online = 3
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .players = 3
            harness.store.deleteDefinition(leaving.metadata.name)

            // No sibling exists, so step 3 finds no capacity and the drain parks —
            // for far longer than the whole transfer allowance.
            repeat(6) {
                harness.pass(leaving.metadata.name)
                harness.clock.advance(60.seconds)
            }
            harness
                .status(leaving.metadata.name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .reason shouldBe FailureReason.DRAIN_NO_DESTINATION
            harness.plugin.sweepsStarted.shouldBeEmpty()

            // An operator adds capacity. The drain must now actually try.
            harness.declare(destination)
            repeat(6) { harness.pass(destination.metadata.name) }
            harness.pass(harness.proxyDefinition.metadata.name)
            harness.plugin.backend("survival-02").shouldNotBeNull()

            repeat(4) { harness.pass(leaving.metadata.name) }

            // It asked. Anchored at step 2 it would have aborted here without ever
            // calling the proxy.
            harness.plugin.sweepsStarted shouldContain "survival-01"
            harness.nodeOf(leaving).stops.shouldBeEmpty()
        }

    /**
     * A destination the fleet offers and the proxy refuses must escalate.
     *
     * Danger pattern 34, reopened by step 3 gaining a body. The resume ladder picks
     * `SEALED` when `destination` is null, `secureDestination` asks the scheduler,
     * gets an answer and reports `Progressed` — so the resume cleared the recorded
     * failure on every other pass. `recordFailure` then saw no previous one each
     * time: `attempts` pinned at 1, `occurredAt` restamped, `escalates()` never
     * true, and `queue.succeeded` on the `Progressed` held the backoff at the poll
     * interval. A permanently stuck drain that never asks for help, at two seconds
     * a cycle, with admission flapping as `sealsBackend` alternated.
     *
     * The stable trigger is a sibling the fleet reports `ready` while the proxy will
     * not accept it — here, one the proxy has no registration for.
     */
    @Test
    fun `a destination the proxy keeps refusing accumulates and escalates`() =
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
            // The fleet still says `survival-02` is a healthy, joinable backend; the
            // proxy has no registration for it. Nothing here re-registers it, because
            // only a proxy pass would and this test runs none.
            harness.plugin.backends.remove("survival-02")
            harness.store.deleteDefinition(leaving.metadata.name)

            val outcomes = mutableListOf<ReconcileOutcome>()
            repeat(14) {
                outcomes += harness.pass(leaving.metadata.name)
                harness.clock.advance(2.minutes)
            }

            val drain =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            val failure = drain.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.DRAIN_TRANSFER_FAILED

            // The three things the cycle destroyed, in the order they matter.
            failure.attempts shouldBeGreaterThan 2
            harness
                .status(leaving.metadata.name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.TRUE
            // Once the cycle starts, no pass reports progress — so `ReconcileLoop`
            // never calls `queue.succeeded` and the backoff keeps growing. The
            // drain's opening passes are genuine progress and are dropped.
            outcomes
                .dropWhile { it is ReconcileOutcome.Progressed }
                .none { it is ReconcileOutcome.Progressed }
                .shouldBeTrue()

            harness.nodeOf(leaving).stops.shouldBeEmpty()
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

    /**
     * A block does not survive the pass that resumes past it.
     *
     * A block is a live claim that somebody is connected, and it is written by one
     * function that always parks the drain in `DRAIN_FAILED` — so a block riding on
     * any other state is stale by construction. It used to ride. Nothing but an
     * abort, a fresh block, the container-is-down branch and a resume that was not
     * merely re-deriving cleared it, and a *re-deriving* resume returned before the
     * clearing line. The result is a drain that walks the rest of the protocol and
     * arrives at `stopWorkload` still recorded as waiting for players — which `:api`
     * renders as "waiting, not stuck" seconds before the container stops — and a
     * `since` and `observations` that `recordBlock` then carries into the next
     * genuine block, so "waiting since" points at a wait that had already ended.
     *
     * ## The scenario is the re-deriving resume, because that is the one that leaks
     *
     * The player is still online on the pass that resumes, so the ladder lands on
     * `SEALED` and step 3 asks the scheduler for a destination — the one step that
     * does no external work, and the one whose early return skipped the clearing. A
     * resume that found the server already empty took a different path and cleared
     * it, which is why a test that lets the player log off first proves nothing.
     *
     * It also pins the block's **wording**, which was wrong for every proxied backend
     * that reached it: "there is no proxy to transfer them through" is a sentence
     * about the standalone shape, and this is precisely the case where a proxy exists
     * and cannot see the player.
     */
    @Test
    fun `a block does not survive the resume that re-derives past it`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(leaving, destination))
            val name = leaving.metadata.name
            harness.bringUp()
            harness.store.deleteDefinition(name)

            // Empty at the proxy, so steps 3 and 4 pass straight through and the
            // drain reaches `SAVING` without ever choosing a destination.
            repeat(5) { harness.pass(name) }
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.SAVING

            // Somebody connects straight to the backend's own port. The proxy cannot
            // see them and the ping can, which is the whole reason the ping is the
            // gate — and the reason the message must not blame the absence of a proxy.
            harness.nodeOf(leaving).online = 1
            harness.pass(name)
            val blocked =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
                    .blocked
                    .shouldNotBeNull()
            blocked.message shouldContain "connected straight to this server's own port"
            blocked.message shouldNotContain "there is no proxy"

            // The pass that resumes while they are *still* online: it re-derives a
            // destination and does nothing else.
            harness.pass(name)
            val resumed =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // The instrument is not vacuous: this really was the re-deriving resume.
            resumed.state shouldBe DrainState.TARGET_RESOLVED
            resumed.destination.shouldNotBeNull()
            resumed.blocked shouldBe null

            // And it stays gone for the rest of the drain, which now finishes.
            harness.nodeOf(leaving).online = 0
            val seen = mutableListOf<DrainStatus>()
            repeat(8) {
                harness.pass(name)
                harness.status(name)?.drain?.let { drain -> seen += drain }
            }
            harness.nodeOf(leaving).stops shouldHaveSize 1
            seen.filter { it.state == DrainState.STOPPING }.shouldNotBeEmpty()
            seen.none { it.blocked != null }.shouldBeTrue()
        }

    /**
     * A drain that recovers from a fault does not carry the failure to the stop.
     *
     * The failure now survives the resume — one good pass is not proof that a drain
     * has recovered, which is what critical 2 of the fifteenth audit turned on — so
     * the thing to pin is the other end: it is still gone by the time the drain does
     * anything an operator would read a failure against. A status saying "the drain
     * aborted; the server is still running" about a drain seconds from stopping the
     * container is the same class of wrong answer as a stale block, one step louder.
     */
    @Test
    fun `a recovered drain carries no stale failure into the stop`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            val name = leaving.metadata.name
            harness.bringUp()
            harness.store.deleteDefinition(name)
            repeat(4) { harness.pass(name) }

            // One ping that does not answer: a retryable abort and nothing more.
            harness.nodeOf(leaving).failOnce(
                NodeOperation.EXEC,
                harness.nodeOf(leaving).unreachable(NodeOperation.EXEC),
            )
            harness.pass(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.RETRYABLE

            val seen = mutableListOf<DrainStatus>()
            repeat(8) {
                harness.pass(name)
                harness.status(name)?.drain?.let { drain -> seen += drain }
            }

            harness.nodeOf(leaving).stops shouldHaveSize 1
            val stopping = seen.filter { it.state == DrainState.STOPPING }
            stopping.shouldNotBeEmpty()
            stopping.forEach { it.failure shouldBe null }
        }

    /**
     * A backend the proxy will not let go of asks for a human, and keeps asking.
     *
     * Critical 1 of the fifteenth audit, and the harm is entirely in the *report*:
     * nothing is stopped, nobody is kicked, and the drain is correct to keep trying.
     * What was lost is the escalation, which is the only mechanism the system has
     * for saying "this has been stuck for a quarter of an hour and will not fix
     * itself".
     *
     * The shape is an **ephemeral** backend behind a proxy that refuses `DELETE`.
     * `save` returns early for a container with no world — it reads a label and
     * issues nothing — and that early return reported `Progressed`, so the resume
     * out of `DRAIN_FAILED` treated it as work and deleted the recorded failure. The
     * next pass tried the deregistration, was refused, and recorded a *fresh*
     * failure: `attempts` pinned at 1, `occurredAt` restamped every other pass, the
     * fifteen-minute threshold unreachable for ever, and a `PUT`, a `DELETE` and two
     * pings on the wire every second while it lasted.
     *
     * `BACKEND_OCCUPIED` is the refusal because it is the one the plugin actually
     * has for a `DELETE`, and it is stable here in the way a real deployment
     * produces: the proxy still counts somebody on this backend while its own Server
     * List Ping reports empty, which is what a client connected straight to the
     * backend port looks like. Every refusal code maps to `retryable = true` on
     * purpose, so any of them does this.
     *
     * ## What is asserted, and why the anchor rather than the flag alone
     *
     * `occurredAt` is the value the escalation is measured from, so it is asserted
     * directly. A test that only watched the flag would pass against a build that
     * restamped the anchor but happened to cross the threshold anyway.
     */
    @Test
    fun `a backend whose deregistration keeps being refused escalates instead of restamping`() =
        coreTest {
            val leaving = backendDefinition("survival-01", storage = StorageSpec.Ephemeral())
            val harness =
                ProxyHarness(
                    backends = listOf(leaving),
                    config = ReconcilerConfig(drainAttentionAfter = 10.minutes),
                )
            val name = leaving.metadata.name
            harness.bringUp()

            // The ping says empty and the proxy says occupied. Both are true of a
            // player who connected to the backend's own port, and the `DELETE` the
            // drain is about to issue is refused for as long as it lasts.
            harness.nodeOf(leaving).online = 0
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .players = 2
            harness.store.deleteDefinition(name)

            // Twenty passes over twenty minutes. Only the backend is passed over:
            // the proxy's own sweep would deregister it by a different route, and
            // this is about the backend's own drain loop.
            val attempts = mutableListOf<Int>()
            repeat(20) {
                harness.pass(name)
                harness.clock.advance(1.minutes)
                harness
                    .status(name)
                    ?.drain
                    ?.failure
                    ?.let { attempts += it.attempts }
            }

            // The instrument is not vacuous: the drain really did reach the
            // deregistration and really was refused, every cycle.
            harness.plugin.deregistrations.shouldBeEmpty()
            attempts.size shouldBeGreaterThan 4

            // The count rises rather than alternating 1, 1, 1. Asserted first and on
            // the whole series, because the defect produced a *stable* 1 whatever the
            // run length — and on some parities it produced no recorded failure at
            // all on the pass a test happens to look at, which a single end-state
            // assertion would report as an unrelated null.
            attempts.distinct().size shouldBeGreaterThan 3

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            val failure = drain.failure.shouldNotBeNull()
            failure.attempts shouldBeGreaterThan 3
            failure.reason shouldBe FailureReason.DRAIN_TRANSFER_FAILED
            failure.failureClass shouldBe FailureClass.RETRYABLE

            // The anchor is the first occurrence and it has not moved since.
            JavaDuration
                .between(failure.occurredAt, harness.clock.instant())
                .toKotlinDuration() shouldBeGreaterThan 10.minutes

            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.TRUE

            // The flag is a report and nothing branches on it. The container was
            // never stopped, the backend never left the routing table, and the
            // delete is still outstanding — which is the correct outcome, and the
            // reason the escalation is the only thing that can move this.
            harness.nodeOf(leaving).stops.shouldBeEmpty()
            drain.deregisteredAt shouldBe null
            harness.plugin.backend("survival-01").shouldNotBeNull()
            harness.store.getServer(name).shouldNotBeNull()
        }
}

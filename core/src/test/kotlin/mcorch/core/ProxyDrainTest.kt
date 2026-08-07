package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.proxy.VelocityWorkloadPlanner
import mcorch.schema.ConditionStatus
import mcorch.schema.DrainBlockReason
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
     * A drain that gets as far as deregistering and then fails **before dispatching
     * a stop** puts the registration back.
     *
     * Deregistration is the one proxy step that cannot be level-triggered — it is
     * the last thing before the stop, so re-asserting it every pass would mean
     * asserting it from states that must not reach it. It therefore needs an
     * explicit edge on the abort path, and without one a drain that deregistered and
     * then failed leaves a running server unreachable through the proxy with nothing
     * left that would re-add it.
     *
     * ## Why the failure is a probe and no longer a refused stop
     *
     * This scenario used to arm `NodeException.Rejected` on the stop, and the
     * thirty-second audit is why it cannot any more: `DrainController` records
     * `stopDispatchedAt` **before** the request is issued, and from there on the
     * registration is deliberately not handed back. A `Rejected` from a `Node` is not
     * evidence that nothing was signalled — `LocalNode` uses it both for a grace
     * period it refused before calling anything *and* as the bucket for a failure its
     * translator did not recognise — so the record cannot be rolled back on it
     * without guessing.
     *
     * A ping that cannot be answered is the honest "before any stop" failure and it
     * is the more common one: `requireEmpty` guards `DEREGISTERED`, so an unanswered
     * probe aborts with the runtime never asked for anything. `stops` and
     * `stopDispatchedAt` are asserted together, because "no stop was dispatched" is
     * the premise the restore now rests on and an assertion about the registration
     * alone would not notice it moving.
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

            // The server stops answering its ping, so the pass that would have
            // stopped it aborts at the zero-player gate instead.
            harness.nodeOf(leaving).joinable = false
            harness.pass(leaving.metadata.name)

            harness.nodeOf(leaving).stops.shouldBeEmpty()
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .name shouldBe "survival-01"
            harness.plugin
                .backend("survival-01")
                .shouldNotBeNull()
                .admits
                .shouldBeTrue()
            val drain =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.deregisteredAt shouldBe null
            drain.stopDispatchedAt shouldBe null
        }

    /**
     * A stop whose deadline elapsed is a stop that may have landed, and the backend
     * is **not** handed back to the proxy while it may be shutting down.
     *
     * The thirty-second audit's must-fix. containerd delivers the `SIGTERM` and does
     * not escalate once the request context has expired, so what the client's timeout
     * leaves behind is a container inside Paper's shutdown hook. Re-registering it
     * *admitting* — which is what `PUT /v1/backends/{name}` does, there being no
     * register-without-admitting call — routes a player onto a process whose
     * `savePlayers` step has already run against the set it had then. They get in,
     * they play, the shutdown disconnects them, and **that session is not saved**.
     *
     * ## What the assertion is on, and why it is not the routing table alone
     *
     * The wire. `plugin.asserts` is every `PUT /v1/backends/{name}` that landed, and
     * the baseline is taken at the moment the property starts holding rather than at
     * the end — the proxy's own pass re-asserts a backend's admission every time it
     * runs, so a level read afterwards can be satisfied by something other than the
     * edge under test. The routing-table read is kept beside it as the coarser
     * control.
     *
     * ## What is deliberately *not* claimed
     *
     * That the drain is now safe from refilling in general: the stop was already
     * gated at both ends and still is (`requireEmpty` → `mayStop` on the way in, an
     * `Occupied` reading blocking and voiding the evidence on the way out). The
     * defect this pins is narrower and is a defect all the same — the drain actively
     * refilling a container it is part-way through stopping.
     *
     * The clock does not move, on purpose: with the save confirmation still current
     * the resume after the park goes back to the stop rather than round to another
     * save, which is exactly the window in which an admitting registration is
     * harmful.
     */
    @Test
    fun `a drain whose stop timed out does not hand the backend back to the proxy`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            val node = harness.nodeOf(leaving)
            harness.bringUp()
            harness.store.deleteDefinition(leaving.metadata.name)

            repeat(7) { harness.pass(leaving.metadata.name) }
            harness.plugin.backend("survival-01") shouldBe null
            node.stops.shouldBeEmpty()

            // From here on nothing may re-admit this backend.
            val baseline = harness.plugin.asserts.size

            // The stop is issued and this client stops waiting for it. The container
            // is still there — containerd does not escalate after the deadline — and
            // whether the signal landed is exactly what nobody can know.
            node.failOnce(NodeOperation.STOP, node.unanswered(NodeOperation.STOP))
            harness.pass(leaving.metadata.name)

            harness.plugin.asserts.drop(baseline) shouldNotContain ("survival-01" to true)
            harness.plugin.backend("survival-01") shouldBe null

            val parked =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            parked.state shouldBe DrainState.DRAIN_FAILED
            // The record the decision is made on, asserted beside the decision: a
            // build that skipped the restore for some other reason would satisfy
            // everything above.
            parked.stopDispatchedAt.shouldNotBeNull()
            parked.deregisteredAt.shouldNotBeNull()
            val failure = parked.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.DRAIN_STALLED
            // Retryable: a node that did not answer is a node to ask again, and the
            // strand below lasts only as long as the drain does.
            failure.failureClass shouldBe FailureClass.RETRYABLE

            // …and it is not a wedge. With the node answering again the drain finishes
            // from where it parked, which is what makes the cost of not restoring
            // availability rather than a server nobody can retire.
            repeat(4) { harness.pass(leaving.metadata.name) }
            node.stops.shouldNotBeEmpty()
            harness.plugin.asserts.drop(baseline) shouldNotContain ("survival-01" to true)
        }

    /**
     * The mirror, and the half an exception-class discriminator gets wrong: a stop
     * that could not be **re-issued** still follows one that was dispatched.
     *
     * `awaitStopped` only runs because a *first* stop returned successfully — that is
     * the only thing that puts a drain in `STOPPING` — so a plain `Rejected` on the
     * re-issue is a permanent park at a container that has had its `SIGTERM`. Keying
     * the compensation on `failure is NodeException.Timeout` would restore the
     * registration here; keying it on `state == STOPPING` would restore it in the
     * scenario above. The record is neither, which is why it is a record.
     *
     * The container is made not to exit (`onStop` returns the workload unchanged),
     * which is the state `awaitStopped` exists for and the only way to reach the
     * re-issue at all.
     */
    @Test
    fun `a stop that could not be re-issued does not hand the backend back either`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            val node = harness.nodeOf(leaving)
            harness.bringUp()
            harness.store.deleteDefinition(leaving.metadata.name)

            // A container that takes the stop and does not go away.
            node.onStop = { present -> present }

            repeat(8) { harness.pass(leaving.metadata.name) }
            val stopping =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stopping.state shouldBe DrainState.STOPPING
            node.stops shouldHaveSize 1

            val baseline = harness.plugin.asserts.size

            // The re-issue is refused outright. Nothing about *this* call says a
            // signal was delivered; the one before it does.
            node.failAlways(NodeOperation.STOP, node.rejected(NodeOperation.STOP))
            harness.pass(leaving.metadata.name)

            harness.plugin.asserts.drop(baseline) shouldNotContain ("survival-01" to true)
            harness.plugin.backend("survival-01") shouldBe null

            val parked =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            parked.state shouldBe DrainState.DRAIN_FAILED
            parked.deregisteredAt.shouldNotBeNull()
            // Stamped by the *first* stop and not restamped by the refused re-issue:
            // the question is whether a signal may be in that container, not when the
            // most recent attempt was made.
            parked.stopDispatchedAt shouldBe stopping.stopDispatchedAt
            val failure = parked.failure.shouldNotBeNull()
            failure.failureClass shouldBe FailureClass.PERMANENT
            failure.message shouldContain "could not be re-issued"
        }

    /**
     * Withdrawing the *cause* does not withdraw the stop, and the record of one
     * survives the withdrawal.
     *
     * The thirty-third audit's critical, and it arrives through the record added for
     * the thirty-second. Reverting the edit is the documented way to call off a
     * `REPLACEMENT`, and it is the only lever an operator has. Taken while the drain
     * is between step 7 and the container's exit, it used to:
     *
     * 1. make `drainCause` answer null — the hashes match again — so the pass
     *    converged instead of draining;
     * 2. reach `awaitJoinable`, which writes `drain = null` on **every** branch,
     *    because a joinable server means a drain is over — and a container inside its
     *    grace period answers a ping right up to the moment it stops;
     * 3. leave the proxy's sweep reading a backend with no drain record at all:
     *    `letGo` false, `sealed` false, so `PUT /v1/backends/{name}` puts it back
     *    **admitting**.
     *
     * Players then land on a process whose `savePlayers` has already run against the
     * set it held at the stop, and containerd `SIGKILL`s it at the end of the grace
     * period. Everything played in between is gone — up to `stopGracePeriod`, four
     * minutes by default.
     *
     * ## What is asserted, and why the record is asserted beside the wire
     *
     * The wire, for the reason the two tests above give: the proxy re-asserts every
     * backend's admission on every pass, so a level read afterwards can be satisfied
     * by something other than the edge under test. The baseline is taken at the
     * moment the property starts holding.
     *
     * The drain record is asserted too, and not as a proxy for the wire: it is the
     * fact the whole compensation family is decided on, and a build that kept the
     * backend out of routing for some other reason — a sweep that never ran, a
     * proxy that stopped answering — would satisfy the wire assertion while having
     * lost the thing that makes the next pass safe.
     *
     * ## …and the revert is not swallowed
     *
     * The second half matters as much as the first: the withdrawal is honoured one
     * step later rather than ignored. The container that was already signalled goes,
     * the workload is removed, and the definition that stands *now* — the one the
     * operator reverted to — is what comes back. Asserting on the create's image is
     * what tells a stalled drain apart from a completed withdrawal, and only the
     * second is a fix.
     *
     * The clock does not move: with the save confirmation still current, the pass
     * after the revert re-issues the stop rather than going round for another save,
     * which is the window an admitting registration is harmful in.
     */
    @Test
    fun `reverting the edit does not undo a stop that has already been dispatched`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            val node = harness.nodeOf(leaving)
            harness.bringUp()

            // A container that takes the stop and does not exit — which is what every
            // container looks like for the length of its grace period.
            node.onStop = { present -> present }
            harness.declare(backendDefinition("survival-01", image = REPLACEMENT_SERVER_IMAGE))

            // Start, seal, no destination needed, transfer (nobody), zero confirmed,
            // save, deregister, stop. One step each.
            repeat(8) { harness.pass(leaving.metadata.name) }
            val stopping =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stopping.state shouldBe DrainState.STOPPING
            stopping.stopDispatchedAt.shouldNotBeNull()
            stopping.deregisteredAt.shouldNotBeNull()
            node.stops shouldHaveSize 1
            harness.plugin.backend("survival-01") shouldBe null

            // From here on nothing may re-admit this backend.
            val baseline = harness.plugin.asserts.size

            // The operator reverts the image. The definition now matches the running
            // container's spec hash again, so nothing wants a drain any more.
            harness.declare(leaving)
            repeat(3) { harness.sweep() }

            harness.plugin.asserts.drop(baseline) shouldNotContain ("survival-01" to true)
            harness.plugin.backend("survival-01") shouldBe null
            val carried =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            carried.stopDispatchedAt shouldBe stopping.stopDispatchedAt
            carried.deregisteredAt shouldBe stopping.deregisteredAt
            // The drain is still the thing in charge of this container, which is what
            // the surviving record buys: the stop is re-issued rather than the loop
            // converging over the top of it.
            node.stops.size shouldBeGreaterThan 1

            // …and the withdrawal is honoured, one step later than it was asked for:
            // the signalled container goes, and what comes back is the *reverted*
            // definition rather than the edit that started the drain.
            node.onStop = { present ->
                present.copy(state = WorkloadState.EXITED, finishedAt = harness.clock.instant(), exitCode = 0)
            }
            repeat(5) { harness.pass(leaving.metadata.name) }
            node.removals shouldHaveSize 1
            node.creates shouldHaveSize 2
            node.creates[1]
                .image.canonical shouldBe DEFAULT_SERVER_IMAGE
        }

    /**
     * The same revert, against a runtime that has stopped reporting the container.
     *
     * The thirty-fourth audit's critical, and it is the thirty-third's exactly one
     * argument short. A sandbox the runtime reports no container in is two different
     * worlds — a workload whose container was never created, and a live container
     * `ListContainers` has stopped returning — and the observation cannot tell them
     * apart. The drain's own `containerIsDown` refuses to call the second a dead
     * container and says why: *getting this wrong tears down a sandbox with a live
     * server inside it*. `stopIsInFlight` classified it without that fact, so:
     *
     * 1. `drainCause` answers null after the revert, as it is meant to;
     * 2. `outstandingStopCause` answered null too — `SANDBOX_ONLY` was "not the
     *    signalled container", unconditionally — so the pass converged;
     * 3. `converge`'s `SANDBOX_ONLY` branch wrote `drain = null` and called
     *    `ensureWorkload`, which is a **second** Paper container against the same
     *    persistent host path if the runtime was merely under-reporting;
     * 4. and the proxy's sweep read a backend with no record: `sealed` false, `letGo`
     *    false, `assertBackend(admits = true)`.
     *
     * Players then land on a process whose shutdown save has already run and which
     * has a `SIGTERM` in it.
     *
     * ## The scenario, and why the fake had to change first
     *
     * `FakeNode` used to name every container it built after the server alone, so
     * every container it ever created had the same id and "is this the container the
     * drain signalled" was true by construction. Ids are per-create now, which is
     * what makes the assertion below about the record rather than about the fixture.
     *
     * The wire is asserted before the record, for the reason the two tests above
     * give, and the create count is asserted beside them: re-admission and a
     * duplicate container are two different harms from the one missing argument, and
     * a build could close either without the other.
     */
    @Test
    fun `a runtime that stops reporting a container mid-stop does not re-admit the backend`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            val node = harness.nodeOf(leaving)
            harness.bringUp()

            node.onStop = { present -> present }
            harness.declare(backendDefinition("survival-01", image = REPLACEMENT_SERVER_IMAGE))
            repeat(8) { harness.pass(leaving.metadata.name) }
            val stopping =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stopping.state shouldBe DrainState.STOPPING
            stopping.stopDispatchedAt.shouldNotBeNull()
            stopping.deregisteredAt.shouldNotBeNull()
            node.stops shouldHaveSize 1
            node.creates shouldHaveSize 1

            // From here on nothing may re-admit this backend, and nothing may build a
            // second container against its world.
            val baseline = harness.plugin.asserts.size

            // The runtime stops enumerating the container. The process is still in
            // there — it is inside its stop grace period — and the sandbox still
            // carries the spec hash the container was created with.
            val running = node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            node.workload =
                running.copy(
                    state = WorkloadState.SANDBOX_ONLY,
                    handle = running.handle.copy(containerId = null),
                )

            // …and the operator reverts the edit in the same window, which is the
            // documented lever and the exact sequence the record exists for. The
            // sandbox's hash now equals the desired one, so nothing wants a drain.
            harness.declare(leaving)
            repeat(3) { harness.sweep() }

            harness.plugin.asserts.drop(baseline) shouldNotContain ("survival-01" to true)
            harness.plugin.backend("survival-01") shouldBe null
            node.creates shouldHaveSize 1

            val carried =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            carried.stopDispatchedAt shouldBe stopping.stopDispatchedAt
            carried.deregisteredAt shouldBe stopping.deregisteredAt
        }

    /**
     * The proxy mirror, and the same missing guard.
     *
     * `proxyDrainCause` is withdrawable exactly as `drainCause` is, and
     * `awaitProxyReady` cleared the record on the same unconditional line. What
     * follows differs only in what it costs: `assertProxyAdmission(admits = true)`
     * reopens the *fleet's* login path onto a front door inside its own stop grace
     * period, so it is a mass disconnect and an availability report nobody can act
     * on rather than a lost world — a proxy holds none, and its backends save on
     * disconnect.
     *
     * The assertion is `proxyAsserts` rather than `proxyAdmits` for the reason round
     * 27 established: the door is a level that the next pass re-asserts, so a build
     * that opens it and shuts it again reads `false` at every point a test could
     * look. One backend, so the door is genuinely open at bring-up rather than shut
     * by `assertBackends` finding nothing admitting.
     */
    @Test
    fun `reverting a proxy's edit does not undo a stop that has already been dispatched`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.proxyAdmits.shouldBeTrue()

            harness.proxyNode.onStop = { present -> present }
            // A hash-bearing edit. `maxPlayers` is in the proxy's spec hash.
            harness.declare(proxyDefinition(maxPlayers = 300))
            // One step each, to the stop: start, seal, nothing to transfer, zero
            // confirmed, no world to save, nothing to deregister, stop.
            repeat(7) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            val stopping =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stopping.state shouldBe DrainState.STOPPING
            stopping.stopDispatchedAt.shouldNotBeNull()
            harness.proxyNode.stops shouldHaveSize 1
            harness.plugin.proxyAdmits.shouldBeFalse()

            // From here on no `PUT /v1/proxy` may assert `true`.
            val baseline = harness.plugin.proxyAsserts.size

            harness.declare(harness.proxyDefinition)
            repeat(4) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            harness.plugin.proxyAsserts.drop(baseline) shouldNotContain true
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .stopDispatchedAt shouldBe stopping.stopDispatchedAt
            harness.proxyNode.stops.size shouldBeGreaterThan 1
        }

    /**
     * …and the third door into the same defect: an edit the loop **refuses** also
     * used to take the record with it.
     *
     * `forbiddenTransition` turns `storage.mode: persistent → ephemeral` on a
     * running, world-holding container into a permanent refusal, and drafted its
     * status with `drain = null` — the refusal being, in every case anybody had
     * considered, about a drain that had not started. It can land while one is
     * inside its stop grace period, and then the refusal deleted the evidence that a
     * `SIGTERM` was already out and the proxy re-admitted players to it. The audit
     * named `converge`, `awaitJoinable` and `awaitProxyReady`; this site is on none
     * of those paths, and no scenario would have reached it from the fix as
     * prescribed.
     *
     * **The refusal itself is untouched, and that is deliberate.** What the guard is
     * about — memory that would be discarded rather than flushed — is a question
     * about a container that is still serving, and the edit is still refused with the
     * same wording and the same class. All that changes is that refusing an edit is
     * no longer a way of forgetting a stop.
     *
     * The residual, named rather than fixed: the container this drain signalled will
     * exit, the drain will finish, and the create after it applies the ephemeral
     * definition — the world files stay on the volume, untouched and unmounted, and
     * nothing is discarded that the confirmed save did not already flush. Whether
     * that is the right end state for an edit the loop spent several passes refusing
     * is a ruling for the drain audit, not for this test.
     */
    @Test
    fun `an edit refused mid-stop does not delete the record of the stop`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            val node = harness.nodeOf(leaving)
            harness.bringUp()

            node.onStop = { present -> present }
            harness.declare(backendDefinition("survival-01", image = REPLACEMENT_SERVER_IMAGE))
            repeat(8) { harness.pass(leaving.metadata.name) }
            val stopping =
                harness
                    .status(leaving.metadata.name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stopping.state shouldBe DrainState.STOPPING
            stopping.stopDispatchedAt.shouldNotBeNull()
            node.stops shouldHaveSize 1

            val baseline = harness.plugin.asserts.size

            // A second edit, landing inside the grace period of the stop the first
            // one asked for. The container is still `RUNNING` — that is what a
            // container inside its grace period is — and its label still says it
            // holds a world, so the guard fires.
            harness.declare(
                backendDefinition(
                    "survival-01",
                    image = REPLACEMENT_SERVER_IMAGE,
                    storage = StorageSpec.Ephemeral(),
                ),
            )
            repeat(2) { harness.sweep() }

            // The wire first, and the record after it. Deleting the record is what
            // lets the sweep re-admit, so an assertion about the record placed first
            // fails first and leaves the harm itself unasserted — which is how a test
            // comes to pin the mechanism rather than the consequence.
            harness.plugin.asserts.drop(baseline) shouldNotContain ("survival-01" to true)
            harness.plugin.backend("survival-01") shouldBe null

            val status = harness.status(leaving.metadata.name).shouldNotBeNull()
            // The refusal still happens, in the same words.
            val refusal = status.failure.shouldNotBeNull()
            refusal.failureClass shouldBe FailureClass.PERMANENT
            refusal.message shouldContain "storage.mode"
            // …and it is no longer a way of forgetting a dispatched stop.
            val carried = status.drain.shouldNotBeNull()
            carried.stopDispatchedAt shouldBe stopping.stopDispatchedAt
            carried.deregisteredAt shouldBe stopping.deregisteredAt
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
     * The twenty-fourth audit's critical: **a proxy whose control endpoint is dead
     * could never be drained, replaced or deleted — at zero players, for ever.**
     *
     * The test above is its mirror and both are correct. A *backend* that cannot be
     * sealed parks, because carrying on would transfer players into a queue
     * refilling behind them. A *proxy* has no transfer: `ProxyDrainSubject.router`
     * is null and its drain is seal-then-wait-for-zero, exactly the standalone
     * `PaperServer` shape. A standalone survives a dead proxy because it has no seal
     * at all and short-circuits; a proxy always has a seal object, so it aborted at
     * step 2 on every pass of every state — `DRAIN_REQUESTED` → abort → resume →
     * zero players → `SEALED` → abort — and there was no exit. Recreating it is a
     * `REPLACEMENT` drain through the same endpoint, and deleting it takes the same
     * path and never purges. What was left was a running, joinable, permanently
     * undeletable front door and an operator reaching for `crictl stop`.
     *
     * ## The shape is ordinary, which is why it was critical rather than historical
     *
     * The proxy image's Velocity version was unpinned, so a breaking upstream
     * release would have produced exactly this on the next restart: `RUNNING`,
     * `ready = true`, serving players, plugin failed to load, no spec-hash input
     * moved. (`VELOCITY_VERSION` is pinned and hash-bearing in the same change, so
     * that state is now one the loop can drift *out of*.) The asset going missing
     * between two creates, and a control-token rotation, reach it the same way.
     *
     * ## What the assertions separate
     *
     * `stops` is the load-bearing one: a drain that merely reported a nicer failure
     * would satisfy everything else. The plugin's counters are the discriminator for
     * the *shape* — nothing was sealed, transferred or deregistered, because nothing
     * could be, and the drain did not pretend otherwise. `sealRequestedAt` is the
     * one an over-eager fix fails: waiving the seal must not stamp the instant a
     * dashboard reads as "new joins are stopped".
     */
    @Test
    fun `a proxy at zero players whose control endpoint is dead can still be stopped`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .ready
                .shouldBeTrue()

            // The plugin stops answering — it failed to load, or the JAR is gone —
            // and the operator asks for the proxy to go away. Nobody is connected:
            // `FakeNode.online` is 0, so the Server List Ping the gate reads answers
            // zero on every pass. The proxy itself is perfectly healthy otherwise.
            harness.plugin.unreachable = true
            // Taken after the bring-up, because a proxy asserts its own login
            // admission on every converge pass and this fleet has no backends.
            // What the drain must not do is add to it.
            val assertsBefore = harness.plugin.proxyAsserts.size
            harness.store.deleteDefinition(name)

            repeat(10) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            // The exit exists. Both halves: stopped, and then taken away.
            harness.proxyNode.stops shouldHaveSize 1
            harness.proxyNode.removals.shouldNotBeEmpty()
            harness.store.getServer(name) shouldBe null

            // Nothing was claimed that did not happen: no seal landed at the wire,
            // and no transfer or deregistration was invented to get past the step.
            harness.plugin.proxyAsserts shouldHaveSize assertsBefore
            harness.plugin.transfers.shouldBeEmpty()
            harness.plugin.deregistrations.shouldBeEmpty()
        }

    /**
     * The waiver is for the subject with nowhere to send anybody, **and only while
     * it is empty**.
     *
     * With players on, the seal is doing real work: it is what lets the wait for
     * zero end rather than run against a population that keeps climbing. So a proxy
     * that cannot seal *and* has somebody connected still parks, with
     * `PROXY_CONTROL_UNREACHABLE` on its status telling an operator to go and look —
     * and nothing is stopped.
     *
     * The number is chosen: one player, and they never leave. A scenario that let
     * them log off would end in the test above and prove nothing about this branch.
     *
     * ## The assertion that is about *this* branch, and the one that is not
     *
     * "Nothing was stopped" is delivered by `requireEmpty` whatever step 2
     * concluded, so on its own it would pass against a build that waived the seal
     * unconditionally. The discriminator is the **recorded failure on the pass that
     * aborts**: a waived seal reports `Progressed` and records nothing.
     *
     * ## What the passes after the park now record, and why it changed
     *
     * They used to settle into a healthy block, because the gated resume ran
     * `requireEmpty` and stopped there. That was the twenty-seventh audit's second
     * finding seen from the reporting side: with step 2 unreachable for ever, the
     * seal could never land, so the population could never fall and the wait could
     * never end — and the record said *waiting, needs nobody* about it. Step 2 now
     * runs on the resume, ahead of the gate, so each pass tries the endpoint again
     * and records what it got. A drain whose seal cannot be maintained is not a
     * healthy wait, and the failure is what tells an operator to go and look at the
     * endpoint.
     */
    @Test
    fun `a proxy with players online still parks when its control endpoint is dead`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()

            harness.proxyNode.online = 1
            harness.plugin.unreachable = true
            harness.store.deleteDefinition(name)

            // Pass one starts the drain and performs no step; pass two is the one
            // that runs step 2 and cannot.
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.pass(name)

            val aborted =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            aborted.state shouldBe DrainState.DRAIN_FAILED
            // Not stamped: the record must not claim a seal that is not in place.
            aborted.sealRequestedAt shouldBe null
            val failure = aborted.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            failure.failureClass shouldBe FailureClass.RETRYABLE
            // No seal was ever asserted here, so the message is the one about a
            // front door that is still taking players.
            failure.message shouldContain "keeps taking players"

            // It keeps retrying rather than escalating into anything destructive,
            // and it keeps saying what is wrong: the endpoint is still down, so
            // every pass fails step 2 again and the attempt count rises.
            repeat(6) {
                harness.clock.advance(2.seconds)
                harness.pass(name)
            }
            val waiting =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            waiting.state shouldBe DrainState.DRAIN_FAILED
            waiting.blocked shouldBe null
            val standing = waiting.failure.shouldNotBeNull()
            standing.reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            standing.failureClass shouldBe FailureClass.RETRYABLE
            (standing.attempts > failure.attempts) shouldBe true

            harness.proxyNode.stops.shouldBeEmpty()
            harness.proxyNode.removals.shouldBeEmpty()
            harness.store.getServer(name).shouldNotBeNull()
        }

    /**
     * …and when the endpoint comes back, that drain finishes — which is the half
     * that could not happen before.
     *
     * The mirror of the twenty-seventh audit's critical, and the reason step 2 moved
     * ahead of the gate on the resume. A proxy whose **first** `holdSeal` fails with
     * players on parks with the login path never sealed. Every later pass used to
     * stop at `requireEmpty`, which is behind the six states that seal — so
     * `holdSeal` was unreachable for ever, the door stayed open, the population it
     * was waiting on refilled rather than fell, and the drain did not converge *even
     * after the endpoint recovered*. Nothing about that state lifts on its own; a
     * delete parked in it is another manual `crictl stop`.
     *
     * ## The two halves, and which build passes which
     *
     * The old build passes the first half — parked, nothing stopped — and hangs for
     * ever on the second. The seal landing at the wire after the recovery is the
     * assertion that separates them: it can only happen on a pass that reached step
     * 2, and on the old build no pass after the park ever does.
     *
     * One backend, so that `assertBackends` finds something admitting at bring-up
     * and leaves the proxy's own door open. On an empty fleet it seals the proxy for
     * an unrelated, ruled-on reason and every seal assertion here would read the
     * same whatever the drain did.
     */
    @Test
    fun `a proxy whose first seal failed still converges once the endpoint comes back`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.proxyAdmits.shouldBeTrue()

            // Players on, endpoint down, and a delete asked for. Step 2 never lands.
            harness.proxyNode.online = 2
            harness.plugin.unreachable = true
            harness.store.deleteDefinition(name)

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .sealRequestedAt shouldBe null
            harness.plugin.proxyAdmits.shouldBeTrue()

            // The plugin comes back — it finished loading, or the operator restarted
            // it. The next pass has to be able to reach step 2, and on a build where
            // the resume stops at the zero-player gate it never can.
            harness.plugin.unreachable = false
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.plugin.proxyAdmits.shouldBeFalse()

            // With the door shut the population can fall, and then the delete runs
            // to completion on its own.
            harness.proxyNode.online = 0
            repeat(8) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness.proxyNode.stops shouldHaveSize 1
            harness.proxyNode.removals.shouldNotBeEmpty()
            harness.store.getServer(name) shouldBe null
        }

    /**
     * …and once its resume has shut the door, the park says so instead of saying the
     * opposite.
     *
     * The twenty-eighth audit's first critical, and it is the discriminator the two
     * tests above cannot draw between them. Both of them end with `sealRequestedAt`
     * null — one because the seal never landed, the other because the *record* of it
     * landing was written only by the `DRAIN_REQUESTED` arm, which this drain never
     * runs again. The state in between is the one this drives: seal refused with
     * players on, park, endpoint returns, the **resume** shuts the door, players stay
     * on, endpoint drops again. The old build then told an operator *"The server
     * keeps running and keeps taking players"* about a fleet whose login path this
     * controller had shut one pass earlier — the same sentence the twenty-seventh
     * round removed from the KDoc, reintroduced through a call site.
     *
     * ## What each assertion is for
     *
     * The wire flag is read *with* the record, deliberately. `sealRequestedAt` alone
     * would pass against a build that stamped it without sealing anything, and the
     * flag alone says nothing about what the operator is told. The message
     * assertions are then a claim about the pair: the door is shut at the simulator,
     * the record says since when, and the sentence describes that state rather than
     * its opposite.
     *
     * The last one is the fourth finding of the same round: a `DELETION` cannot be
     * withdrawn — `deletedAt` is one-way and there is no un-delete — so the remedy
     * *"until whatever asked for this drain is withdrawn"* was an impossible action
     * offered in the case where the blackout lasts longest.
     */
    @Test
    fun `a proxy sealed by its resume reports the blackout when its endpoint drops again`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.proxyAdmits.shouldBeTrue()

            // Players on, endpoint down, delete asked for: step 2 never lands, so the
            // drain parks with the front door open and says so.
            harness.proxyNode.online = 2
            harness.plugin.unreachable = true
            harness.store.deleteDefinition(name)
            repeat(4) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            val open =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            open.sealRequestedAt shouldBe null
            open.failure.shouldNotBeNull().message shouldContain "keeps taking players"

            // The endpoint comes back. The resume asserts step 2 ahead of the
            // zero-player gate, so *this* pass is what shuts the fleet's front door —
            // and it is the only pass that ever will, because nothing re-enters
            // `DRAIN_REQUESTED`.
            harness.plugin.unreachable = false
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.plugin.proxyAdmits.shouldBeFalse()
            val shut =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            val sealedAt = shut.sealRequestedAt.shouldNotBeNull()

            // …and now it drops again, with the same players still connected.
            harness.plugin.unreachable = true
            harness.pass(name)
            harness.clock.advance(2.seconds)

            val parked =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // The record survives the park: it is what the sentence below is read
            // from, and a seal nobody wrote down is a blackout nobody can report.
            parked.sealRequestedAt shouldBe sealedAt
            val failure = parked.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            // The door really is shut at the proxy…
            harness.plugin.proxyAdmits.shouldBeFalse()
            // …so the sentence about it must be the blackout one, not its opposite.
            failure.message shouldNotContain "keeps taking players"
            failure.message shouldContain "login seal this drain put on is still in place"
            failure.message shouldContain "Nobody can log in"
            // The remedy offered is one this cause has. A delete has no un-delete.
            failure.message shouldContain "a delete cannot be withdrawn"

            harness.proxyNode.stops.shouldBeEmpty()
            harness.store.getServer(name).shouldNotBeNull()
        }

    /**
     * A permanent park whose seal release does not land is recorded **retryable**, so
     * the loop comes back and tries the release again.
     *
     * The twenty-eighth audit's second finding. The release was best-effort inside
     * the one gate that guarantees nobody retries it: a single refused control call
     * left the fleet's front door shut, the permanent class then froze
     * `reconcileProxy`, and no pass ever tried again. A definition edit does not
     * repair it either — the generation bump resumes the passes straight into
     * `holdSeal`, which shuts the door again — and a frozen proxy stops running
     * `assertBackends`, so a backend whose own drain has parked stays out of routing
     * with nothing left to re-register it.
     *
     * `restoreRegistration` is best-effort too and is safe for a reason this edge
     * does not have: `assertBackends` re-registers a parked backend on every proxy
     * pass. The seal has no such third party, which is the whole argument for the
     * edge existing.
     *
     * ## The two halves, and which build passes which
     *
     * The old build records `PERMANENT` and freezes with the door shut, so it fails
     * the class assertion and the one that follows it — a pass that still reaches the
     * runtime. The new build keeps coming back until the release lands, and then
     * settles exactly where the compensation was always meant to leave it: door open,
     * failure permanent, loop no longer looking. Both ends are asserted, because a
     * build that only ever retried would be a permanent failure nobody can ever act
     * on, which is the other way to get this wrong.
     *
     * The route to a permanent abort is the twenty-sixth audit's: a container whose
     * `WORLD_DATA` label is missing reads as *holding* world data, and no Velocity
     * proxy can confirm a world save. One backend, so `assertBackends` leaves the
     * proxy's own door open at bring-up rather than sealing it for an unrelated
     * reason and making every reading below vacuous.
     */
    @Test
    fun `a permanent park whose seal release fails keeps being retried until it lands`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.proxyAdmits.shouldBeTrue()

            val observed = harness.proxyNode.workload as WorkloadObservation.Present
            harness.proxyNode.workload = observed.copy(labels = observed.labels - Labels.WORLD_DATA)
            // The endpoint answers and the proxy stays shut whatever it is asked:
            // the seal lands, the release does not. An edit rather than a delete, so
            // a permanent record really would stop the passes.
            harness.plugin.stuckSealed = true
            harness.declare(proxyDefinition(maxPlayers = 300))

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val stuck =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stuck.state shouldBe DrainState.DRAIN_FAILED
            val held = stuck.failure.shouldNotBeNull()
            // The step's own verdict was permanent. What is recorded is what the
            // compensation achieved, and it achieved nothing.
            held.failureClass shouldBe FailureClass.RETRYABLE
            held.message shouldContain "could not be released either"
            harness.plugin.proxyAdmits.shouldBeFalse()

            // The consequence that matters: the loop is still looking at this proxy.
            val calls = harness.proxyNode.calls.size
            harness.pass(name)
            harness.clock.advance(2.seconds)
            (harness.proxyNode.calls.size > calls) shouldBe true

            // The proxy's login handler comes back. The next release lands, and the
            // abort settles where it was always meant to: door open, nothing else to
            // be done, and no more passes.
            harness.plugin.stuckSealed = false
            repeat(4) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            val settled =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            settled.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            harness.plugin.proxyAdmits.shouldBeTrue()

            val frozen = harness.proxyNode.calls.size
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
            harness.proxyNode.calls shouldHaveSize frozen
            // Nothing was stopped on the way through any of that.
            harness.proxyNode.stops.shouldBeEmpty()
            harness.proxyNode.removals.shouldBeEmpty()
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
     * `sealRequestedAt` used to be written at exactly one place, with `holdSeal`
     * above it, so a single `Unavailable` on the drain's one bodied
     * `DRAIN_REQUESTED` pass — the control endpoint blinking, which this design
     * treats as expected — meant the stamp never happened. Nothing re-enters
     * `DRAIN_REQUESTED`: the resume ladder tops out at `SEALED`, and `started()`
     * needs no drain record at all. So the anchor was absent for the life of that
     * drain, `exhausted` fell back to `enteredStateAt`, and the bound could never
     * trip: ~2 minutes of asking, one pass parked, the allowance handed back in
     * full, for ever, with `failure` cleared each cycle so nothing escalated.
     * Sealed, unjoinable, transfer requests firing at live players, and a delete
     * that never completes.
     *
     * The anchor is stamped on entry to step 4 instead, which every path takes.
     *
     * ## What changed under it, and why this still tests the same thing
     *
     * Since the twenty-eighth audit the seal record is maintained by *every* state
     * that asserts step 2 (`SealHold.recordedOn`), so this drain does get one — from
     * the pass that re-seals after the endpoint comes back, long after the
     * `DRAIN_REQUESTED` pass that missed it. That is a strictly better field and it
     * is still the wrong anchor: it moves with the seal, not with step 4, so a bound
     * measured from it would restart the allowance at every re-seal. The assertion is
     * therefore the *ordering* of the two records rather than the absence of one.
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
            // Both records exist, and on this path one pass wrote both: the drain
            // resumed into step 3, `holdSeal` re-asserted the seal the endpoint had
            // refused, and the destination search below it stamped step 4's anchor.
            // Equal, and not interchangeable — the seal's record is *since when the
            // door has been shut* and is maintained by every state that asserts one,
            // while the anchor is *when step 4 began* and nothing may move it. The
            // bound that trips below is measured from the second.
            val sealedAt = drain.sealRequestedAt.shouldNotBeNull()
            val anchor = drain.transferStartedAt.shouldNotBeNull()
            sealedAt shouldBe anchor
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

    /**
     * The eighteenth audit's critical: a step that reads **no** player count parks
     * a drain still holding a confirmation the same pass has just contradicted.
     *
     * Round 17 made a positive count void the confirmation *where the count is
     * read*. `holdSeal` reads none — it asserts the seal and nothing else — and at
     * `DEREGISTERED` it runs **before** the zero-player gate. So:
     *
     * 1. the save confirms on an empty server and the drain moves to
     *    `DEREGISTERED`, still registered;
     * 2. somebody connects straight to the backend's own port, which the proxy
     *    cannot see and a Server List Ping can — the exact case the gate at
     *    `DEREGISTERED` exists for;
     * 3. the proxy's control endpoint stops answering (a restart, a plugin reload,
     *    a node blip), so `holdSeal` aborts *first*, with the drain it was handed;
     * 4. the abort touches neither the confirmation nor `playersEvacuated`, and the
     *    pass records the player it saw — which advances `lastProbedAt` and so
     *    **refreshes** the window keeping the confirmation alive rather than
     *    breaking it;
     * 5. they log off, the proxy comes back, and the resume ladder jumps straight
     *    to the stop on a confirmation taken before they arrived.
     *
     * ## The numbers are chosen
     *
     * The outage runs for forty seconds of two-second passes, which is deliberately
     * **longer** than `saveEvidenceMaxGap`. That is the point: the loop keeps
     * probing throughout, so the observation-gap rule never fires and cannot be
     * what protects the world here. Unlike round 17's window this one has no
     * upper bound of its own — it lasts as long as the control channel is down.
     *
     * ## What each assertion separates
     *
     * `savedWhenStopped` is the load-bearing one, as in `DrainTest`: a drain that
     * stopped on the stale confirmation and saved afterwards would satisfy a total
     * of two. `playersEvacuated` staying true is the discriminator for the *shape*
     * of the fix — the pass adopts the confirmation clause alone, not
     * `forgetSaveEvidence`, so a parked proxied drain still re-enters at `SAVING`
     * and blocks there instead of resuming into a destination search that this
     * one-backend fleet could never satisfy.
     *
     * ## Which fix this reddens against, which is not what it looks like
     *
     * The change has two halves and **either one alone keeps this test green**.
     * Sabotaging the pass-entry adoption in `advance` leaves it passing, because
     * `dropSaveContradictedByPlayers` repairs the same record on the way out; both
     * had to be disabled for it to fail, and then it fails twice — on
     * `worldSaved` first and, with that assertion relaxed, on `savedWhenStopped`
     * being 1.
     *
     * They are not redundant, and the difference is what a net cannot do. The net
     * repairs what is *written*; the pass-entry adoption is what makes every
     * *decision* in the pass see a drain that does not claim a save. Nothing today
     * decides to stop before the zero-player gate, so today the two produce the same
     * record — but a step that acted on `saveIsCurrent` before that gate would have
     * stopped the container already, and no repair of the record afterwards can
     * un-stop it. The net's own value is as a defect signal (it logs at error and
     * says so), and that value depends on the adoption keeping it unreachable.
     */
    @Test
    fun `a seal that cannot be asserted does not park a drain on a save a player has outlived`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            val name = leaving.metadata.name
            val node = harness.nodeOf(leaving)
            harness.bringUp()
            harness.store.deleteDefinition(name)

            // requested, sealed, destination, transfer, save, deregistered.
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            val confirmed =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // The pass under test is the one *before* the deregistration, which is
            // the only state where `holdSeal` still runs and a stop is one gate
            // away.
            confirmed.state shouldBe DrainState.DEREGISTERED
            confirmed.worldSaved.shouldBeTrue()
            confirmed.deregisteredAt shouldBe null
            node.saves shouldHaveSize 1
            node.stops.shouldBeEmpty()

            // They connect to the backend's own port, and the proxy's control
            // endpoint goes away in the same moment.
            node.online = 1
            harness.plugin.unreachable = true

            repeat(20) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val parked =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            parked.state shouldBe DrainState.DRAIN_FAILED
            parked.failure.shouldNotBeNull().reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            // The confirmation is gone, though nothing on this path ever read a
            // player count.
            parked.worldSaved.shouldBeFalse()
            // And only the confirmation is gone.
            parked.playersEvacuated.shouldBeTrue()
            // The pass recorded the player it saw, which is what used to keep the
            // evidence window fresh.
            harness
                .status(name)
                .shouldNotBeNull()
                .players
                .shouldNotBeNull()
                .online shouldBe 1
            node.stops.shouldBeEmpty()
            node.saves shouldHaveSize 1
            harness.plugin.deregistrations.shouldBeEmpty()

            // They log off and the proxy comes back.
            node.online = 0
            harness.plugin.unreachable = false

            var savedWhenStopped = 0
            node.recordingStops { savedWhenStopped = node.saves.size }
            repeat(16) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            // The world on disk includes whatever they built: the stop waited for a
            // second flush taken after they had gone.
            savedWhenStopped shouldBe 2
            node.saves shouldHaveSize 2
            node.stops shouldHaveSize 1
            harness.plugin.deregistrations shouldContain "survival-01"
            harness.store.getServer(name) shouldBe null
        }

    /**
     * The twenty-fifth audit's first warning: an orchestrator upgrade must not
     * close the fleet's login path with no way back.
     *
     * `velocity.build` is a spec-hash input, so bumping it is a replacement — and a
     * proxy's replacement drain seals its own login path and then waits for the last
     * player to log off, because a fleet has one front door and there is nowhere to
     * send anybody. On a fleet that does not empty, that wait never ends. Every
     * other hash input is a field an operator can put back; this one lived in
     * orchestrator source, so the seal had **no exit** short of editing that source
     * or `crictl stop`-ing a running, joinable front door.
     *
     * ## What the scenario has to contain to mean anything
     *
     * **Players, and they never leave.** At zero the drain simply completes, the
     * proxy is recreated on the new build and there is no outage to see; the whole
     * defect is about the fleet that does not empty. One backend, so the fleet's own
     * `assertBackends` has something to admit — with nothing registered the proxy
     * seals itself for an unrelated, ruled-on reason and `proxyAdmits` would read
     * false whatever this test did.
     *
     * ## The assertions
     *
     * The seal at the wire is the load-bearing one, in both directions: false while
     * the pinned build disagrees with the running container, and true again once the
     * operator pins the build their containers were created with. A status field
     * would not do — the harm is that *players cannot log in*, and that is a fact
     * about the plugin, not about a record.
     *
     * ## What holds the first half up, re-examined
     *
     * The twenty-sixth audit asked the question and the answer is worth writing down:
     * the blackout this asserts is reached through [DrainController.blocked], because
     * the proxy's control endpoint is healthy here and nothing aborts. So this test
     * says nothing about the *abort* path, and for a while that path handed the
     * login seal back on any retryable failure and could never take it again — a
     * defect with the same shape as the one this test exists for, one door along. It
     * is pinned by `a proxy drain that parks on a retryable abort keeps its login
     * seal on`, and the two are kept separate on purpose: this one demonstrates the
     * lever, that one the rule.
     */
    @Test
    fun `an operator can pin a proxy fleet back onto the build its containers were created with`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(backend))
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.proxyAdmits.shouldBeTrue()
            val created = harness.proxyNode.creates.size

            // Sixty players, and none of them log off for the rest of this test.
            harness.proxyNode.online = 60

            // The orchestrator is upgraded, and its bundled plugin now targets a
            // newer Velocity. Same store, same node, same containers.
            val upgraded =
                Reconciler(
                    harness.store,
                    harness.registry,
                    harness.scheduler,
                    ReconcilerConfig(velocityBuild = "4.1.0"),
                    harness.clock,
                )
            repeat(6) {
                upgraded.reconcile(name)
                harness.clock.advance(2.seconds)
            }

            // The replacement is wanted, correctly, and it cannot proceed: the front
            // door is sealed and the players it is waiting for are still playing.
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.DRAIN_FAILED
            harness.plugin.proxyAdmits.shouldBeFalse()
            harness.proxyNode.stops.shouldBeEmpty()

            // The exit: pin the build the running containers were created with. No
            // definition edit, no restart of anybody's server.
            val pinned =
                Reconciler(
                    harness.store,
                    harness.registry,
                    harness.scheduler,
                    ReconcilerConfig(velocityBuild = VelocityWorkloadPlanner.VELOCITY_BUILD),
                    harness.clock,
                )
            repeat(4) {
                pinned.reconcile(name)
                harness.clock.advance(2.seconds)
            }

            // Logins are open again, at the wire, with the sixty players still on.
            harness.plugin.proxyAdmits.shouldBeTrue()
            val settled = harness.proxyStatus().shouldNotBeNull()
            settled.drain shouldBe null
            settled.ready.shouldBeTrue()
            // And nothing was taken away to get here.
            harness.proxyNode.stops.shouldBeEmpty()
            harness.proxyNode.removals.shouldBeEmpty()
            harness.proxyNode.creates shouldHaveSize created
        }

    /**
     * The waiver, under the cause that actually fires on every proxy in existence.
     *
     * Both of the cases this waiver was written for drive `DELETION`, and the
     * twenty-fifth audit pointed out that `REPLACEMENT` is the cause a proxy meets
     * without anybody asking for anything. The two take different routes into the
     * same drain — a tombstoned definition against a hash that no longer matches —
     * and only one of them was exercised.
     *
     * Zero players, so the seal is not a precondition and the drain carries on
     * without it, exactly as for a delete: the replacement completes, on a proxy
     * whose control endpoint never answered once.
     */
    @Test
    fun `a proxy at zero players whose endpoint is dead can still be replaced`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            val created = harness.proxyNode.creates.size

            harness.plugin.unreachable = true
            val assertsBefore = harness.plugin.proxyAsserts.size
            // A hash-bearing edit. `maxPlayers` is in the proxy's spec hash.
            harness.declare(proxyDefinition(maxPlayers = 300))

            repeat(12) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            // Stopped, taken away and rebuilt — the exit exists for a replacement,
            // not only for a delete.
            harness.proxyNode.stops shouldHaveSize 1
            harness.proxyNode.removals.shouldNotBeEmpty()
            harness.proxyNode.creates shouldHaveSize created + 1
            harness.proxyNode.creates
                .last()
                .specHash shouldBe VelocityWorkloadPlanner.plan(proxyDefinition(maxPlayers = 300)).specHash
            // Nothing was claimed that did not happen: no seal landed at the wire.
            harness.plugin.proxyAsserts shouldHaveSize assertsBefore
        }

    /**
     * …and the park, under the same cause.
     *
     * With players on, the seal is doing real work — it is what lets the wait for
     * zero end — so a proxy that cannot seal still parks, and the container it was
     * asked to replace keeps running. The discriminator against a build that waived
     * the seal unconditionally is the **recorded failure**: a waiver reports
     * `Progressed` and records nothing, and both builds otherwise end parked.
     */
    @Test
    fun `a proxy with players online still parks when a replacement finds its endpoint dead`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()

            harness.proxyNode.online = 1
            harness.plugin.unreachable = true
            harness.declare(proxyDefinition(maxPlayers = 300))

            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.pass(name)

            val aborted =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            aborted.state shouldBe DrainState.DRAIN_FAILED
            aborted.sealRequestedAt shouldBe null
            val failure = aborted.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            failure.failureClass shouldBe FailureClass.RETRYABLE

            harness.proxyNode.stops.shouldBeEmpty()
            harness.proxyNode.removals.shouldBeEmpty()
        }

    /**
     * A drain that parks where **no pass will look again** gives the login path
     * back.
     *
     * The seal is asserted rather than issued so that an abort needs no compensating
     * edge — but that argument names a *third party*: a backend is un-sealed by the
     * proxy's own pass re-asserting its admission every pass. A proxy sealing
     * **itself** has nobody to do that for it. Its own re-assertion lives in
     * `assertBackends`, which only a non-draining pass reaches, and a frozen pass
     * gate stops its passes altogether — so without this compensation the proxy is
     * left running, ready, and joinable to nobody, with the loop no longer looking
     * at it.
     *
     * ## The cause is a replacement, and that is the point
     *
     * It was written as a delete, and a delete is the one cause for which the
     * premise is false: `Reconciler.isBlockedByPermanentFailure` exempts a
     * terminating definition, so the passes carry on and the release reopens a door
     * nothing can shut again. That is the twenty-seventh audit's critical, and the
     * test for it is the one below. This scenario keeps the compensation honest by
     * driving the case the argument is actually about — a `REPLACEMENT`, where the
     * permanent failure really does freeze the server.
     *
     * The last assertion is the premise itself: the pass after the park does nothing
     * at all. Without it this test would still pass on a build where something *did*
     * look again, and then the release would be the defect rather than the fix.
     *
     * ## Reaching a permanent abort without inventing a failure
     *
     * The route is the one the twenty-sixth audit traced: a container whose
     * `WORLD_DATA` label is missing — an image or a build older than the label —
     * reads as *holding* world data, because that is the safe default. The drain
     * then asks a Velocity proxy to confirm a world save, which it can never do, and
     * that is `PERMANENT` at `SAVING`. The label is stripped from the observation
     * rather than mocked at the planner, because what the drain reads is the
     * container.
     */
    @Test
    fun `a permanent abort that stops the passes releases the proxy login seal`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()

            // The container predates the label. A drain has to assume it holds a
            // world, and no proxy can confirm a save.
            val observed = harness.proxyNode.workload as WorkloadObservation.Present
            harness.proxyNode.workload = observed.copy(labels = observed.labels - Labels.WORLD_DATA)
            // An edit rather than a delete: the definition stays, so the permanent
            // failure this drain records is one the loop will not pass through.
            harness.declare(proxyDefinition(maxPlayers = 300))

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val parked =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            parked.state shouldBe DrainState.DRAIN_FAILED
            val failure = parked.failure.shouldNotBeNull()
            failure.failureClass shouldBe FailureClass.PERMANENT
            // Nothing is going to move those players, and nothing is going to run
            // another pass over this proxy either…
            harness.proxyNode.stops.shouldBeEmpty()
            // …so the seal it put on had to come off on the way past. This is the
            // assertion: at the wire, the front door admits players.
            harness.plugin.proxyAdmits.shouldBeTrue()
            harness.plugin.proxyAsserts
                .last()
                .shouldBeTrue()

            // The premise, pinned rather than assumed: the gate is shut, so the pass
            // after the park observes nothing and asserts nothing.
            val calls = harness.proxyNode.calls.size
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
            harness.proxyNode.calls shouldHaveSize calls
        }

    /**
     * …and a definition edit made while people are playing must not be spent on the
     * block, because for this kind the frozen state is a fleet-wide blackout.
     *
     * The twenty-ninth audit's first finding, on the workload it costs the most. A
     * generation bump is the only thing that lifts `isBlockedByPermanentFailure` on a
     * proxy nobody has deleted, and it lifts it for one pass. Since the
     * twenty-seventh audit the gated resume asserts [DrainController.holdSeal]
     * *before* the zero-player gate, so that one pass shuts the front door and then
     * lands in `blocked` — and since the twenty-eighth a block keeps a standing
     * permanent failure, so the pass wrote it back at the new generation and closed
     * the gate behind itself. End state: every login into the fleet refused, the loop
     * no longer looking, `releaseSeal` reachable only from an abort no pass will
     * reach, and each further edit re-shutting the door and re-freezing.
     *
     * The assertion is the door coming back **without a further edit** once the last
     * player logs off — the drain resumes, aborts permanently again, and that abort
     * is what releases the seal. Reading the flag mid-wait would say nothing: the
     * resume re-asserts the seal every pass by design, so `false` there is the
     * correct answer and the defect and the fix agree about it.
     */
    @Test
    fun `an edit made while a proxy is populated does not leave the fleet locked out`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.proxyAdmits.shouldBeTrue()

            // The container predates the `WORLD_DATA` label, so the drain must assume
            // it holds a world and no proxy can ever confirm a save: `PERMANENT` at
            // `SAVING`. An edit rather than a delete, so the passes really do stop.
            val observed = harness.proxyNode.workload as WorkloadObservation.Present
            harness.proxyNode.workload = observed.copy(labels = observed.labels - Labels.WORLD_DATA)
            harness.declare(proxyDefinition(maxPlayers = 300))
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT
            // The park gave the door back, which is the twenty-sixth audit's edge
            // working. This is the state an operator then tries to repair.
            harness.plugin.proxyAdmits.shouldBeTrue()

            // People come back to the running proxy, and only then does the operator
            // edit the definition again — the documented way to lift the gate.
            harness.proxyNode.online = 4
            harness.declare(proxyDefinition(maxPlayers = 400))

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            // The resume shut the door before the gate, and the drain is waiting.
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .blocked
                .shouldNotBeNull()
                .reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            harness.plugin.proxyAdmits.shouldBeFalse()
            harness.proxyNode.stops.shouldBeEmpty()

            // The last player logs off. No further edit, no operator action: the loop
            // has to still be looking, or the fleet's front door never opens again.
            harness.proxyNode.online = 0
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness.plugin.proxyAdmits.shouldBeTrue()
            harness.proxyNode.stops.shouldBeEmpty()
            harness.proxyNode.removals.shouldBeEmpty()
        }

    /**
     * …and a permanent abort under a **delete** keeps the login path shut, because
     * those passes do not stop.
     *
     * The twenty-seventh audit's critical. The release above was keyed on the
     * failure *class*, which is one input to `isBlockedByPermanentFailure`; its
     * other input is the terminating flag, and a delete is exempt from the gate so
     * that a failure can never make a workload undeletable. So a permanent abort
     * during a delete reopened the front door of a fleet the loop was still
     * reconciling — and nothing could ever shut it again, because the gated resume
     * only reaches the states that seal once the server is empty, and the population
     * refills behind an open door. The delete never completes. The failure does not
     * even survive to say why: the first block that follows clears it, and a block
     * does not escalate, so the status settles on *"waiting for the server to
     * empty… the drain resumes on its own once it is empty"* about a fleet whose
     * door the orchestrator itself reopened.
     *
     * ## The trigger is the audit's own: a stop the node refuses permanently
     *
     * `NodeException.Rejected` is not retryable, and `DrainController.stop` turns it
     * into a permanent abort rather than letting it escape — the drain has already
     * deregistered by then, and an exception out of the controller would skip the
     * compensating edges. Nothing about that is exotic: a runtime that refuses a
     * call against a container in the wrong state produces it.
     *
     * ## Why the player count is set by hand
     *
     * `FakeNode.online` is the Server List Ping, and the seal the plugin holds does
     * not feed back into it — no fake can model "this player never got in". So the
     * count is raised *after* the abort, which is what the old build let happen for
     * real and what the new one is claiming to prevent. The test then asserts the
     * two things that are still in `:core`'s gift: the door stays shut across every
     * pass of the wait, and the delete finishes once the count falls.
     *
     * ## The assertion is at the wire, and reading the flag would not do
     *
     * `proxyAdmits` is a level, and the resume now asserts step 2 on every pass —
     * so a build that releases the door and re-seals it a backoff later reads
     * `false` at every point a test could look. Only the *record of the calls*
     * shows it: from the pass that seals onwards, no `PUT /v1/proxy` may assert
     * `true`. The first draft of this test read the flag, and the mutation that
     * restores the defect passed it.
     *
     * One backend, so the proxy's own door is open at bring-up rather than sealed by
     * `assertBackends` finding nothing admitting — on an empty fleet every assertion
     * below would be satisfied before the drain ran.
     */
    @Test
    fun `a permanent abort under a delete keeps the proxy login seal on`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.proxyAdmits.shouldBeTrue()

            // The runtime will not stop this container, and says so in a way that
            // asking again cannot change.
            harness.proxyNode.failAlways(NodeOperation.STOP, harness.proxyNode.rejected(NodeOperation.STOP))
            harness.store.deleteDefinition(name)

            // Step 1 records the drain; step 2 shuts the door. Taken here rather
            // than after the abort, because from this point on **no `PUT /v1/proxy`
            // may ever assert `true`** — and that, not the flag's value at the end,
            // is the assertion. A release followed by the next pass's re-seal leaves
            // the flag reading `false` again, so a build that hands the door back
            // for a whole backoff passes every reading and fails this.
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.plugin.proxyAdmits.shouldBeFalse()
            val sealed = harness.plugin.proxyAsserts.size

            // Empty, so the drain gets as far as the stop: save, deregister, stop —
            // and the stop is refused, permanently.
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            val parked =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // The recorded failure rather than the recorded state: with the passes
            // still running, this drain alternates between the refused stop and the
            // re-derivation above it, so which state a given pass ends in is a
            // parity of the loop and not the property under test.
            parked.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            // The stop reached the node and was refused there: `calls` records the
            // attempt, `stops` only the ones that took.
            harness.proxyNode.calls shouldContain NodeOperation.STOP
            harness.proxyNode.stops.shouldBeEmpty()
            harness.proxyNode.removals.shouldBeEmpty()

            // The assertion. The old build released here, on the class alone.
            harness.plugin.proxyAdmits.shouldBeFalse()
            harness.plugin.proxyAsserts.drop(sealed) shouldNotContain true

            // Somebody is connected — through the port, or from before the seal —
            // and the drain waits for them rather than disconnecting anybody. The
            // door stays shut throughout, which is what makes the wait end.
            harness.proxyNode.online = 4
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.DRAIN_FAILED
            harness.plugin.proxyAdmits.shouldBeFalse()
            harness.plugin.proxyAsserts.drop(sealed) shouldNotContain true
            harness.store.getServer(name).shouldNotBeNull()

            // The operator restarts the wedged runtime and the last player logs off.
            // The delete completes on its own, which is the whole point of exempting
            // a terminating definition from the permanent gate.
            harness.proxyNode.clearFailures(NodeOperation.STOP)
            harness.proxyNode.online = 0
            repeat(8) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness.proxyNode.stops shouldHaveSize 1
            harness.proxyNode.removals.shouldNotBeEmpty()
            harness.store.getServer(name) shouldBe null
        }

    /**
     * …and a drain that is merely *waiting* keeps the seal on.
     *
     * The counterpart to the test above, and the reason the compensation is on
     * `DrainController.abort` alone. A block is the protocol working: the proxy has
     * sealed its login path and is waiting for the last player to log off, and the
     * seal is the mechanism of that wait. Releasing it there would refill the
     * population the drain is waiting to drain, and a delete on a busy fleet could
     * never complete — which is the state that ends in a manual `crictl stop`.
     *
     * ## …and what the operator is told about it
     *
     * The twenty-ninth audit's second finding, asserted here beside the wire flag
     * because the pair is the assertion: the record alone passes against a build
     * that says the right thing without shutting anything, and the flag alone says
     * nothing about what a person reads. A block renders as `DRAIN_BLOCKED` —
     * *waiting, needs nobody* — so its message is the only sentence there is about a
     * fleet nobody can log in to, and it used to say *"the server keeps running and
     * stays joinable"*.
     */
    @Test
    fun `a proxy drain waiting for players to leave keeps its login seal on`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()

            harness.proxyNode.online = 3
            harness.store.deleteDefinition(name)

            repeat(8) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val waiting =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            waiting.blocked.shouldNotBeNull().reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            // No failure: waiting for players is not a fault…
            waiting.failure shouldBe null
            // …and the login path stays shut, which is what lets the wait end.
            harness.plugin.proxyAdmits.shouldBeFalse()
            harness.proxyNode.stops.shouldBeEmpty()

            // The message an operator reads about that shut door, asserted with the
            // door itself. The premise is that this drain really did shut it — the
            // stamp is what `loginPathAfterAPark` branches on, and without it the
            // sentence would be right by accident.
            waiting.sealRequestedAt.shouldNotBeNull()
            val message = waiting.blocked.shouldNotBeNull().message
            message shouldContain "Nobody can log in"
            message shouldNotContain "stays joinable"

            // **The blackout leads, and that is the thirtieth audit's fourth
            // finding.** `:api` renders a blocked drain as "waiting, not stuck — "
            // plus this string; the wait sentence used to come first and the
            // blackout arrived around 250 characters in, so a truncated fleet table
            // showed only the half that agrees with `DRAIN_BLOCKED`'s *needs
            // nobody*. An evening of refused logins read as a healthy wait.
            //
            // Asserted as a position rather than as presence: the old message
            // contained the same sentence and was the defect.
            message shouldStartWith "Nobody can log in"
            // …and nothing was dropped to get it there. The wait is still stated,
            // which is the half that is also true — sentence-cased, because it was
            // written to follow `:api`'s "waiting, not stuck — " and now follows a
            // full stop instead.
            message shouldContain "Waiting for the server to empty"
        }

    /**
     * …and a drain that parks *retryably* keeps it on too, which is the case
     * between the two above and the one nothing asserted.
     *
     * The twenty-sixth audit's critical. The compensation was written for the state
     * where nothing will ever re-assert the seal — a permanent abort stops the
     * server's passes altogether — and it was applied to every abort. A retryable
     * one is the opposite: the loop keeps coming back, so the drain is still trying
     * to reach zero, and the seal is what lets it get there.
     *
     * ## Why releasing it there is a door handed back, whatever the next pass does
     *
     * When the audit found it, nothing could take the door back at all: the gated
     * resume ran `requireEmpty` first, so with anybody online it landed in `blocked`
     * — which does not seal — and the six forward states that do were unreachable.
     * The population refilled behind the open door and the wait was for a zero that
     * could no longer arrive.
     *
     * Since the twenty-seventh audit the resume asserts step 2 ahead of that gate,
     * so a release would be re-sealed on the next pass rather than never. That makes
     * it a **flap**, not a repair: the door is open for a whole backoff — five
     * minutes on a grown one — once per cycle, which on a busy fleet is enough to
     * refill it, and each refill lengthens the wait it is supposed to be ending. The
     * two changes are complements and this test is what keeps them apart: releasing
     * on a retryable park is still wrong, and the resume asserting a seal is not a
     * licence for it.
     *
     * ## The scenario needs no exotic fault
     *
     * A busy proxy that misses one Server List Ping inside its 10s timeout is a
     * `PlayerReading.Unanswered` at `SEALED`, and the routerless branch of
     * [DrainController.secureDestination] runs that through `requireEmpty` →
     * `unansweredProbe` → a `RETRYABLE` abort. The control endpoint is healthy
     * throughout, which is exactly what makes the release land at the wire.
     *
     * ## The assertions
     *
     * At the wire, in both halves: the front door stays shut across the park and
     * every pass after it, and no `PUT /v1/proxy` in that window ever asserted
     * `true` — a build that released and re-sealed once a backoff would pass the
     * first assertion and fail the second. Then the drain converges once the last
     * player logs off, because a seal that could never be released would be a
     * different defect with the same first half.
     *
     * One backend, for the reason the pin-exit test needs one: with nothing
     * registered, `assertBackends` finds nothing admitting and seals the proxy on
     * bring-up for an unrelated, ruled-on reason, and every assertion here would
     * read the same whatever the drain did.
     */
    @Test
    fun `a proxy drain that parks on a retryable abort keeps its login seal on`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.proxyAdmits.shouldBeTrue()

            // Four players on the front door, and a delete asked for.
            harness.proxyNode.online = 4
            harness.store.deleteDefinition(name)

            // Step 1 and step 2: the drain is recorded, then the seal lands.
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.plugin.proxyAdmits.shouldBeFalse()
            val sealed = harness.plugin.proxyAsserts.size

            // One missed ping. Nothing else is wrong with the proxy.
            harness.proxyNode.joinable = false
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.proxyNode.joinable = true

            val parked =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            parked.state shouldBe DrainState.DRAIN_FAILED
            // The discriminator against the permanent case, which *does* release:
            // this drain is still being attempted.
            parked.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE

            // The park itself, and then the passes after it — where the resume is
            // gated on zero players and lands in `blocked`, which cannot re-seal.
            harness.plugin.proxyAdmits.shouldBeFalse()
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .blocked
                .shouldNotBeNull()
                .reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            harness.plugin.proxyAdmits.shouldBeFalse()
            // …and it was never handed back in between, not even for one backoff.
            harness.plugin.proxyAsserts.drop(sealed) shouldNotContain true
            harness.proxyNode.stops.shouldBeEmpty()

            // The last player logs off and the drain finishes on its own.
            harness.proxyNode.online = 0
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness.proxyNode.stops shouldHaveSize 1
            harness.proxyNode.removals.shouldNotBeEmpty()
        }

    /** Runs [body] when the stop is issued, so a test can assert on the order of side effects. */
    private fun FakeNode.recordingStops(body: () -> Unit) {
        val runtime = onStop
        onStop = { present ->
            body()
            runtime(present)
        }
    }
}

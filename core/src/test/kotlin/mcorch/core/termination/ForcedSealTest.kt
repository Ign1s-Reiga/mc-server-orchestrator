package mcorch.core.termination

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.ProxyHarness
import mcorch.core.backendDefinition
import mcorch.core.coreTest
import mcorch.core.proxyDefinition
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Drain step 2 on the forced path, against a real proxy.
 *
 * **Separate from `NodeForcedTerminationTest` because every fixture there is
 * standalone**, and a standalone server has no proxy to seal. Those tests all take
 * `sealOff`'s `NOTHING_TO_SEAL` branch, so the seal could have been deleted
 * outright and the whole suite would have stayed green — a drain step that exists
 * only in a KDoc table. This file is the one that would go red.
 *
 * The assertions are about **a side effect on a third party**: the proxy's own
 * record of what it was told, read back from the plugin rather than from anything
 * `:core` returns. A seal that `:core` believes it asserted and the proxy never
 * received is the failure mode worth catching, and only the counterparty can
 * report it.
 */
internal class ForcedSealTest {
    // `backendDefinition`, not `paperDefinition`: the proxy's selector matches a
    // label, and a backend without it resolves Standalone — which is how the first
    // version of this file managed to test the seal against a server that had no
    // proxy in front of it.
    private val backend = backendDefinition("survival-01")

    private fun harness() = ProxyHarness(backends = listOf(backend))

    private fun terminationOver(harness: ProxyHarness) =
        NodeForcedTermination(harness.registry, harness.store, harness.scheduler, clock = harness.clock)

    @Test
    fun `the login path is shut at the proxy before the container is stopped`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val node = harness.nodeOf(backend)

            terminationOver(harness).stop(backend, OccupancyAcknowledgement.None)

            // The proxy's own record, not this module's belief about it. Without
            // this the backend keeps taking logins right up to the `SIGTERM` and
            // for the whole grace period after it — which for the raised grace on
            // the no-save branch is three minutes of players joining a process
            // running its shutdown save.
            harness.plugin.backend(backend.metadata.name.value)?.admits shouldBe false
            node.stops shouldHaveSize 1
        }

    @Test
    fun `a sealed server is not probed a second time, because the count is held`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val node = harness.nodeOf(backend)
            val probes = mutableListOf<String>()
            node.onExec = { command ->
                if (command.joinToString(" ").contains("mc-monitor")) probes += "probe"
                node.defaultExec(command)
            }

            terminationOver(harness).stop(backend, OccupancyAcknowledgement.None)

            // One reading. The second probe exists only where nothing owns the
            // count; with the door shut it can buy nothing, and against the wedged
            // server this endpoint is for it costs another full probe timeout to
            // learn what the seal already guarantees.
            probes shouldHaveSize 1
        }

    @Test
    fun `a populated server whose proxy will not answer is refused rather than stopped`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val node = harness.nodeOf(backend)
            node.online = 4
            // The control channel goes away after the fleet is up, so there is a
            // door and it cannot be shut.
            harness.plugin.ready = false

            shouldThrow<ForcedTerminationRefused> {
                terminationOver(harness).stop(backend, OccupancyAcknowledgement.Count(4))
            }.message.toString() shouldContain "could not have its login path shut"

            // Refused even though the caller acknowledged the exact count, because
            // the acknowledgement is a compare-and-swap and nothing is holding the
            // value it swapped against. Recoverable: fix the proxy, or wait for the
            // server to empty, and force again.
            node.stops shouldHaveSize 0
        }

    @Test
    fun `a wedged server behind a dead proxy can still be forced, once the operator says so`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val node = harness.nodeOf(backend)
            // Both halves of one bad minute, which is how they usually arrive: the
            // backend has stopped answering a Server List Ping — the note-1
            // population, and the only reason this endpoint exists — and the proxy's
            // control channel is down too.
            node.joinable = false
            harness.plugin.ready = false

            terminationOver(harness).stop(backend, OccupancyAcknowledgement.Unreadable)

            // **The branch that had no way out.** The unsealed-population refusal
            // reads `players == 0` to mean "nothing to protect", and an unanswered
            // probe is null, not zero — so "wait for the server to empty" could
            // never be taken by a server that never answers a count. The only other
            // remedy was another system's health. Tombstoned, frozen, `crictl` only:
            // the exact state this path exists to remove, reached by the exact
            // population it was built for.
            //
            // Letting an *acknowledged* unreadable count through is not a hole. The
            // refusal protects a count from decaying between the reading and the
            // `SIGTERM`, and here there is no count to protect — the operator has
            // said in the request that they know it cannot be read.
            node.stops shouldHaveSize 1
        }

    @Test
    fun `an unacknowledged wedged server behind a dead proxy is still refused`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val node = harness.nodeOf(backend)
            node.joinable = false
            harness.plugin.ready = false

            // The escape above is opened by the acknowledgement and by nothing else.
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(harness).stop(backend, OccupancyAcknowledgement.None)
            }
            node.stops shouldHaveSize 0
        }

    @Test
    fun `a backend two proxies both claim is refused, not treated as having no proxy`() =
        coreTest {
            // Two proxies whose selectors both match, so `ProxyFleet.resolve` answers
            // `Conflicted` and neither door can be shut.
            val harness = ProxyHarness(backends = listOf(backend))
            harness.bringUp()
            harness.declare(proxyDefinition(name = "front-02", node = "proxy-node"))
            val node = harness.nodeOf(backend)
            node.online = 4

            shouldThrow<ForcedTerminationRefused> {
                terminationOver(harness).stop(backend, OccupancyAcknowledgement.Count(4))
            }.message.toString() shouldContain "could not have its login path shut"

            // The first version filed `Conflicted` under `NOTHING_TO_SEAL`, whose own
            // KDoc says "nothing routes to this server, so there is no door to shut"
            // — the opposite of what is true here, where two proxies are both
            // admitting. That inverted the ladder: a populated backend behind ONE
            // unreachable proxy was refused, and one behind TWO wide-open proxies was
            // stopped. The worse case treated more permissively than the milder one.
            node.stops shouldHaveSize 0
        }

    @Test
    fun `the proxy sweep does not hand the door back after a forced stop`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val name = backend.metadata.name
            // The note-1 population: a drain that aborted permanently on an
            // unconfirmed save. `DRAIN_FAILED` is where the force finds it.
            val failed = harness.clock.instant()
            val current = harness.status(name)
            if (current != null) {
                harness.store.putStatus(
                    current.copy(
                        drain =
                            DrainStatus(
                                state = DrainState.DRAIN_FAILED,
                                startedAt = failed,
                                enteredStateAt = failed,
                                saveRequestedAt = failed,
                            ),
                    ),
                )
            }

            terminationOver(harness).stop(backend, OccupancyAcknowledgement.Unreadable)
            harness.plugin.backend(name.value)?.admits shouldBe false

            // **One proxy pass used to undo the whole thing.** `assertBackends` is a
            // level trigger: it re-states admission from `sealsBackend()`, and
            // `DRAIN_FAILED` answers false on purpose, because a parked drain should
            // not hold a running server out of routing.
            //
            // That reasoning stops applying the instant a `SIGTERM` has been sent,
            // and the record saying so is `stopDispatchedAt` — which this derivation
            // never consulted. The drain's own aborts escaped by accident, since it
            // deregisters before it stops; the forced path does neither, so it had
            // no guard at all and the door reopened for the rest of a 180s grace
            // period, onto a container running its shutdown save.
            harness.pass(harness.proxyDefinition.metadata.name)

            harness.plugin.backend(name.value)?.admits shouldBe false
        }

    @Test
    fun `a stop that never landed stops sealing the backend once its window passes`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val name = backend.metadata.name
            val dispatched = harness.clock.instant()
            val current = harness.status(name)
            if (current != null) {
                harness.store.putStatus(
                    current.copy(
                        drain =
                            DrainStatus(
                                state = DrainState.DRAIN_FAILED,
                                startedAt = dispatched,
                                enteredStateAt = dispatched,
                                stopDispatchedAt = dispatched,
                            ),
                    ),
                )
            }

            // Inside the window, the door stays shut.
            harness.pass(harness.proxyDefinition.metadata.name)
            harness.plugin.backend(name.value)?.admits shouldBe false

            // **Set-once is the argument for the clause and the danger of it.** A
            // level trigger re-states its fact every pass, so an input that never
            // clears makes the fact permanent — and `stopDispatchedAt` reads true
            // for a stop that never reached the runtime, which is exactly what a
            // `NodeException` out of `stopWorkload` leaves: stamp written, container
            // running, caller told nothing was stopped. Unbounded, that server was
            // sealed out of routing for ever, and `DRAIN_FAILED` is deliberately not
            // a sealing state precisely because holding a parked backend out of
            // routing costs a running server no player can reach.
            harness.clock.advance(forcedStopWindow(backend) + 1.minutes)
            harness.pass(harness.proxyDefinition.metadata.name)

            harness.plugin.backend(name.value)?.admits shouldBe true
        }

    @Test
    fun `the fleet's sibling view agrees with the sweep about a forced backend`() =
        coreTest {
            val other = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(backend, other))
            harness.bringUp()
            val forced = backend.metadata.name
            val dispatched = harness.clock.instant()
            val current = harness.status(forced)
            if (current != null) {
                harness.store.putStatus(
                    current.copy(
                        drain =
                            DrainStatus(
                                state = DrainState.DRAIN_FAILED,
                                startedAt = dispatched,
                                enteredStateAt = dispatched,
                                stopDispatchedAt = dispatched,
                            ),
                    ),
                )
            }

            // **Two derivations of one question, and for a round they disagreed.**
            // The proxy sweep was taught to treat a dispatched stop as sealing;
            // `ProxyFleet.resolve`'s sibling view was not. `BackendLink.lastAdmitting`
            // is computed from these siblings, so it would have believed the forced
            // backend still admitted, skipped sealing the proxy before sealing
            // another backend, and left the plugin's admit-anyway path free to route
            // a login onto an all-sealed fleet — including the server already
            // shutting down.
            val stored = harness.store.getServer(other.metadata.name)
            val fleet = mcorch.core.ProxyFleet.resolve(harness.store, stored!!, harness.clock.instant())
            val siblings = (fleet as mcorch.core.ProxyFleet.Resolution.Behind).binding.siblings

            siblings.single { it.server == forced }.sealed shouldBe true

            // …and it lapses on the same window as the sweep, rather than pinning the
            // fleet's view open for ever.
            harness.clock.advance(forcedStopWindow(backend) + 1.minutes)
            val later = mcorch.core.ProxyFleet.resolve(harness.store, stored, harness.clock.instant())
            val laterSiblings = (later as mcorch.core.ProxyFleet.Resolution.Behind).binding.siblings
            laterSiblings.single { it.server == forced }.sealed shouldBe false
        }

    @Test
    fun `a re-issued forced stop gets a fresh seal window, not the first one`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val name = backend.metadata.name
            val node = harness.nodeOf(backend)
            // A drain that already aborted permanently — the note-1 population, and
            // the only case where this matters: with no prior drain the force writes
            // a `STOPPING` record, and `STOPPING.sealsBackend()` is true regardless
            // of any window. `DRAIN_FAILED` is where the window is load-bearing.
            val failed = harness.clock.instant()
            harness.status(name)?.let {
                harness.store.putStatus(
                    it.copy(
                        drain =
                            DrainStatus(
                                state = DrainState.DRAIN_FAILED,
                                startedAt = failed,
                                enteredStateAt = failed,
                            ),
                    ),
                )
            }

            // First attempt: the node refuses the stop, so the stamp is written and
            // the container keeps running. That is the state the bounded refusal was
            // added for in round 52.
            node.failOnce(mcorch.core.NodeOperation.STOP, node.rejected(mcorch.core.NodeOperation.STOP))
            shouldThrow<ForcedTerminationRefused> {
                terminationOver(harness).stop(backend, OccupancyAcknowledgement.None)
            }

            // Wait the window out. The seal lapses, correctly — nothing is shutting
            // down, so the backend should take players again.
            harness.clock.advance(forcedStopWindow(backend) + 1.minutes)
            harness.pass(harness.proxyDefinition.metadata.name)
            harness.plugin.backend(name.value)?.admits shouldBe true

            // Now force again, and this time it lands.
            terminationOver(harness).stop(backend, OccupancyAcknowledgement.None)
            node.stops shouldHaveSize 1

            // **The retry must not be born already expired.** `dispatchingStop` keeps
            // the *first* instant on purpose — "may a SIGTERM already be in that
            // container" only ever becomes true — but a bounded reader needs the
            // latest. With one set-once field carrying both questions, this second
            // `SIGTERM` would have been outside its own window the moment it was
            // sent: the next proxy sweep reopens the backend while that shutdown
            // runs, and a third force would not see this one as overlapping either.
            harness.pass(harness.proxyDefinition.metadata.name)
            harness.plugin.backend(name.value)?.admits shouldBe false
        }

    @Test
    fun `players are transferred before the container is stopped`() =
        coreTest {
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(backend, destination))
            harness.bringUp()
            val name = backend.metadata.name
            val node = harness.nodeOf(backend)
            node.online = 4
            harness.plugin.backend(name.value)?.players = 4
            // The sweep lands as soon as it is asked, which is what a proxy with a
            // healthy destination does.
            harness.plugin.onTransfer = { harness.plugin.completeSweep(name.value) }
            node.onExec = { command ->
                if (command.joinToString(" ").contains("mc-monitor")) node.online = 0
                node.defaultExec(command)
            }

            val outcome = terminationOver(harness).stop(backend, OccupancyAcknowledgement.Count(4))

            // **Drain step 4, which this path used to skip outright.** Forcing a
            // populated server disconnected every session on it; the ones the proxy
            // can move are now moved first, and the stop happens after.
            //
            // Asserted from the proxy's own record rather than from the outcome:
            // `:core` believing it asked is not the same as the sweep having run.
            // Only this backend was swept, and its players are on the destination.
            //
            // Not a count of sweeps: polling re-asks a start-or-join call, and once
            // a sweep has finished the proxy answers a fresh one — against a server
            // that is now empty, so it moves nobody. That is the plugin's own
            // behaviour and the drain re-asks the same way once per pass; counting
            // it here would assert the double's bookkeeping rather than anything
            // about players.
            harness.plugin.transfers
                .map { it.first }
                .distinct() shouldBe listOf(name.value)
            harness.plugin.backend(name.value)?.players shouldBe 0
            harness.plugin.backend(destination.metadata.name.value)?.players shouldBe 4
            outcome.transfer.attempted shouldBe true
            node.stops shouldHaveSize 1
        }

    @Test
    fun `a transfer that empties the server is not then refused over the count it changed`() =
        coreTest {
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(backend, destination))
            harness.bringUp()
            val name = backend.metadata.name
            val node = harness.nodeOf(backend)
            node.online = 5
            harness.plugin.backend(name.value)?.players = 5
            harness.plugin.onTransfer = { harness.plugin.completeSweep(name.value) }
            // The server empties as the sweep lands, which the pre-stop probe sees.
            node.onExec = { command ->
                if (command.joinToString(" ").contains("mc-monitor")) {
                    node.online = if (harness.plugin.backend(name.value)?.players == 0) 0 else 5
                }
                node.defaultExec(command)
            }

            val outcome = terminationOver(harness).stop(backend, OccupancyAcknowledgement.Count(5))

            // **This is where a decrease is forgiven, and the only place it is.**
            // The acknowledgement was settled against five before the sweep; the
            // sweep then moved them, so refusing over the zero it produced would
            // make every successful transfer a 409.
            //
            // `refuseArrivals` is reached only because a transfer was attempted. On
            // a path with no sweep the exact rule stands, so the permission cannot
            // drift onto a reading nothing reduced.
            node.stops shouldHaveSize 1
            outcome.transfer.attempted shouldBe true
            outcome.playersOnline shouldBe 0
        }

    @Test
    fun `a decrease bigger than the sweep moved is not forgiven`() =
        coreTest {
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(backend, destination))
            harness.bringUp()
            val name = backend.metadata.name
            val node = harness.nodeOf(backend)
            node.online = 12
            // The proxy knows about nine of them; three are connected straight to the
            // backend's port, which the seal does not close and the proxy cannot see.
            harness.plugin.backend(name.value)?.players = 9
            harness.plugin.onTransfer = { harness.plugin.completeSweep(name.value) }
            node.onExec = { command ->
                // Nine move out, five join directly while the sweep runs: 12 -> 8.
                if (command.joinToString(" ").contains("mc-monitor")) {
                    if (harness.plugin.backend(name.value)?.players == 0) node.online = 8
                }
                node.defaultExec(command)
            }

            shouldThrow<ForcedTerminationRefused> {
                terminationOver(harness).stop(backend, OccupancyAcknowledgement.Count(12))
            }

            // **A sweep having run is not a licence for any fall.** Nine moved, so a
            // fall of nine is accounted for; the count fell by four, which means five
            // arrived on the port behind them. `attempted` alone would have passed
            // this — eight is less than twelve — and dropped five sessions nobody
            // signed off, which is the masking case in a third disguise.
            node.stops shouldHaveSize 0
        }

    @Test
    fun `a fleet with nowhere to put them still stops the server`() =
        coreTest {
            // One backend, so the only candidate is the server being drained.
            val harness = harness()
            harness.bringUp()
            val node = harness.nodeOf(backend)
            node.online = 2

            val outcome = terminationOver(harness).stop(backend, OccupancyAcknowledgement.Count(2))

            // No destination is not a refusal. A transfer that cannot be attempted
            // leaves the endpoint doing exactly what it did before step 4 existed —
            // which is the whole reason it is "attempt, then proceed" rather than a
            // precondition.
            harness.plugin.transfers.shouldBeEmpty()
            outcome.transfer.attempted shouldBe false
            node.stops shouldHaveSize 1
        }

    @Test
    fun `an unsealed proxy is not swept, because the sweep would race logins`() =
        coreTest {
            val destination = backendDefinition("survival-02", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(backend, destination))
            harness.bringUp()
            val node = harness.nodeOf(backend)
            harness.plugin.ready = false

            terminationOver(harness).stop(backend, OccupancyAcknowledgement.Unreadable)

            // The door could not be shut, so the server is still admitting. Sweeping
            // then races logins the sweep cannot see the end of, which is why the
            // drain never reaches step 4 from a state that has not held the seal.
            harness.plugin.transfers.shouldBeEmpty()
            node.stops shouldHaveSize 1
        }

    @Test
    fun `an empty server whose proxy will not answer is still stopped`() =
        coreTest {
            val harness = harness()
            harness.bringUp()
            val node = harness.nodeOf(backend)
            harness.plugin.ready = false

            terminationOver(harness).stop(backend, OccupancyAcknowledgement.None)

            // The trade `DrainController.abortSeal` already makes through
            // `sealIsPrecondition`: nobody is on it, so there is no door worth
            // holding, and refusing here would make a down proxy enough to render a
            // server unretirable — which is the state this whole path removes.
            node.stops shouldHaveSize 1
        }
}

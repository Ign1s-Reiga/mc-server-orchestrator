package mcorch.core.termination

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.ProxyHarness
import mcorch.core.backendDefinition
import mcorch.core.coreTest
import mcorch.core.proxyDefinition
import org.junit.jupiter.api.Test

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

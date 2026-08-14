package mcorch.core.termination

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.ProxyHarness
import mcorch.core.backendDefinition
import mcorch.core.coreTest
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

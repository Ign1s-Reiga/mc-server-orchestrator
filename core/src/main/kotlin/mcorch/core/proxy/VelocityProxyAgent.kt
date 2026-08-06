package mcorch.core.proxy

import mcorch.core.ExecRequest
import mcorch.core.ExecTimeout
import mcorch.core.Labels
import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.WorkloadHandle
import mcorch.core.WorkloadObservation
import mcorch.core.paper.PaperCommands
import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.WorkloadContract
import mcorch.core.paper.diagnose
import mcorch.core.paper.unbuildableProbe
import mcorch.schema.VelocityProxyDefinition
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the loop talks to a running Velocity proxy.
 *
 * Two channels, and they answer different questions on purpose:
 *
 * 1. **Is it joinable?** A Server List Ping against the proxy's own player port,
 *    the same handshake a client makes. Velocity answers SLP before it has
 *    finished registering backends, so this is readiness in the sense a player
 *    cares about and nothing more.
 * 2. **Can the drain protocol reach it?** The plugin's control endpoint. A proxy
 *    that is joinable but whose plugin does not answer is a proxy behind which
 *    **no backend can complete a drain**, so the two are reported separately and
 *    neither implies the other.
 *
 * The aggregate player count for the proxy's own drain comes from the ping rather
 * than from the plugin, and that is not an oversight: the gate on a stop is the
 * workload's own SLP everywhere in this codebase, and a proxy is not an exception
 * to a rule that exists because a count from a *different* process is a count from
 * a different process.
 */
internal class VelocityProxyAgent(
    private val definition: VelocityProxyDefinition,
) {
    private val spec get() = definition.spec

    /**
     * Asks the proxy whether it is accepting connections, and how many it has.
     *
     * `max` is `spec.maxPlayers` when the reply carries no denominator — the
     * control protocol reports occupancy as a bare count, and `PlayerOccupancy`
     * needs a limit. Taking it from the declaration is what the plugin author
     * asked for rather than having the proxy guess at somebody else's
     * configuration.
     *
     * The `try` is the same guard `PaperServerAgent.probe` carries, unreachable for
     * the same reason and kept for the same one: [Node] states that a request built
     * from a definition is classified rather than thrown, and a rule with exceptions
     * is not a rule.
     */
    suspend fun probe(
        node: Node,
        handle: WorkloadHandle,
    ): ProbeOutcome {
        val request =
            try {
                ExecRequest(
                    command = PaperCommands.serverListPing(spec.network.port),
                    timeout = ExecTimeout.of(PROBE_TIMEOUT),
                )
            } catch (rejected: IllegalArgumentException) {
                return ProbeOutcome.Unavailable(detail = unbuildableProbe(rejected), retryable = false)
            }
        val result =
            try {
                node.exec(handle, request)
            } catch (failure: NodeException) {
                if (failure is NodeException.Timeout && failure.commandTimeout) {
                    return ProbeOutcome.NotJoinable(
                        "the proxy did not answer a Server List Ping within ${PROBE_TIMEOUT.inWholeSeconds}s, " +
                            "and the node stopped the probe at that timeout. The node answered promptly, so it " +
                            "is reachable: this is the proxy not replying — still starting, or wedged",
                    )
                }
                return ProbeOutcome.Unavailable(detail = failure.message, retryable = failure.retryable)
            }
        if (!result.exitedCleanly) {
            return ProbeOutcome.NotJoinable("the proxy did not answer a Server List Ping (${result.diagnose()})")
        }
        val occupancy =
            PaperCommands.parseOccupancy(result.output)
                ?: return ProbeOutcome.NotJoinable(
                    "the Server List Ping reply carried no occupancy report (${result.diagnose()})",
                )
        return ProbeOutcome.Joinable(
            online = occupancy.online,
            max = if (occupancy.max > 0) occupancy.max else spec.maxPlayers,
        )
    }

    /**
     * What the running container was built with.
     *
     * The same rule as a Paper server's, and the same reason: a drain is conducted
     * against the container. The default when the label is absent is deliberately
     * **not** softened for a proxy — `holdsWorldData` still defaults to `true`,
     * because "this workload does not say" must never be read as "no world" for
     * anything. What stops that being a problem is that
     * `VelocityWorkloadPlanner` always writes the label, and the test that would
     * notice it stopped doing so exists precisely because nothing else would.
     */
    fun contractOf(observation: WorkloadObservation.Present): WorkloadContract {
        val worldData = Labels.booleanValue(observation.labels, Labels.WORLD_DATA)
        val saveChannel = Labels.booleanValue(observation.labels, Labels.SAVE_CONFIRMABLE)
        return WorkloadContract(
            holdsWorldData = worldData ?: true,
            // Velocity has no RCON and nothing else that replies, so there is no
            // guess to make: the declared answer is false whatever the label says.
            saveConfirmable = saveChannel ?: false,
            observed = worldData != null && saveChannel != null,
        )
    }

    private companion object {
        private val PROBE_TIMEOUT: Duration = 10.seconds
    }
}

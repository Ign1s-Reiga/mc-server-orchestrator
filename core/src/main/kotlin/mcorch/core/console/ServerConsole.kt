package mcorch.core.console

import mcorch.core.ConsoleRequest
import mcorch.core.ExecTimeout
import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.NodeRegistry
import mcorch.core.WorkloadObservation
import mcorch.schema.PaperServerDefinition
import mcorch.schema.ResourceName
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Running one console command against a declared server.
 *
 * The seam `:api` depends on, and **deliberately the whole of it**. Reaching a
 * running container from the API needs `Node`, which lives here — `api/API.md`
 * §11 anticipated that pressure and ruled that adding a `:core` edge is *"a real
 * decision with a real justification, not something to slip in"*. This interface
 * is how that edge stays a doorway rather than an opening: `:api` gets one method
 * that runs one screened command, not the node registry, not the reconciler, and
 * no way to stop anything.
 *
 * ## What it does not do
 *
 * **Decide whether the command should run.** [ConsoleInvariants] and
 * [ConsolePolicy] do that, above this, and a caller that skips them gets a
 * faithfully executed `stop`. The split is deliberate: policy needs the caller's
 * tier and the server's ceiling, which are the API's to know, while this needs a
 * node handle, which is not.
 */
public interface ServerConsole {
    /**
     * Runs [command] and returns the server's reply verbatim.
     *
     * @throws ConsoleUnavailable when the server is not in a state that can answer
     *   — no workload, or one that is not running. Distinct from a node failure
     *   because the remedy differs: this one waits.
     */
    public suspend fun run(
        definition: PaperServerDefinition,
        command: String,
    ): String
}

/**
 * The server is not in a state that can answer, or could not be reached.
 *
 * Retryable, and **nothing was dispatched** — which is what separates it from
 * [ConsoleTimedOut].
 */
public class ConsoleUnavailable(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The command **ran or may have run**, and no reply arrived in time.
 *
 * A separate type rather than a flag on [ConsoleUnavailable], because the two
 * call for opposite things from a caller: one may be retried, and retrying this
 * one may run a side-effecting command twice. RCON offers no way to tell "never
 * ran" from "still queued on the main thread" from "ran after the deadline", and
 * there is no request id to reconcile against afterwards.
 */
public class ConsoleTimedOut(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * [ServerConsole] over the nodes this orchestrator knows.
 *
 * Finds the workload by asking every node, the same way nothing else here assumes
 * where a server runs. On one node that is one call; on several it is the price
 * of not writing the single-host shortcut the seventh invariant exists to keep
 * out.
 */
public class NodeServerConsole(
    private val nodes: NodeRegistry,
    private val timeout: Duration,
) : ServerConsole {
    override suspend fun run(
        definition: PaperServerDefinition,
        command: String,
    ): String {
        val name = definition.metadata.name
        val (node, handle) = locate(name)
        val request =
            ConsoleRequest(
                port = definition.spec.network.rcon.port,
                passwordSecret = definition.spec.network.rcon.passwordSecret,
                command = command,
                timeout = ExecTimeout.of(timeout),
            )
        // Never the command, never the reply. The audit record is where a console
        // command is written down, and it is redacted there — see
        // `spec/04-output.md` §3.
        LOG.debug("console command dispatched server={} port={}", name.value, request.port)
        // Translated here, so `:api` never pattern-matches on a NodeException —
        // the same rule LocalNode applies to CriException, applied one layer up.
        // A caller that had to read node failures would be a caller coupled to how
        // this orchestrator reaches containers.
        return try {
            node.console(handle, request)
        } catch (timeout: NodeException.Timeout) {
            throw ConsoleTimedOut(
                "`${name.value}` did not answer the console command in time",
                timeout,
            )
        } catch (failure: NodeException) {
            throw ConsoleUnavailable(
                "the console could not reach `${name.value}`",
                failure,
            )
        }
    }

    private suspend fun locate(name: ResourceName): Pair<Node, mcorch.core.WorkloadHandle> {
        for (node in nodes.nodes()) {
            val observation =
                try {
                    node.observe(name)
                } catch (unreachable: NodeException) {
                    // One node being unreachable is not the same as the server not
                    // existing, so this keeps looking rather than concluding.
                    LOG.debug("node {} could not be asked about {}", node.name, name.value, unreachable)
                    continue
                }
            val present = observation as? WorkloadObservation.Present ?: continue
            if (present.state != mcorch.core.WorkloadState.RUNNING) {
                throw ConsoleUnavailable(
                    "`${name.value}` is not running, so there is nothing listening for a console command",
                )
            }
            return node to present.handle
        }
        throw ConsoleUnavailable("`${name.value}` has no workload on any node yet")
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(NodeServerConsole::class.java)
    }
}

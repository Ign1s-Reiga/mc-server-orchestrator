package mcorch.core

import mcorch.schema.NodeName
import mcorch.schema.ResourceName

/**
 * Decides which node a server lands on.
 *
 * One of the three distribution seams (see CLAUDE.md). Today the only
 * implementation is [SingleNodeScheduler] and it is trivial — but it is a real
 * interface with a real call site: the reconcile loop asks it *every pass*,
 * before it touches a node, and uses the answer. Nothing in the loop names a
 * node directly, so the day there are three of them the loop does not change.
 */
public interface Scheduler {
    /** Where [request] should run, or why it cannot run anywhere. */
    public suspend fun schedule(request: PlacementRequest): PlacementDecision

    /**
     * Where a draining server's players should go, or why nowhere will do.
     *
     * Here rather than inside `DrainController` on purpose. Choosing which server
     * receives another server's players is a fleet decision made against fleet
     * capacity — the same kind of decision [schedule] makes, with the same reasons
     * to be replaceable — and a fleet decision written inside the drain controller
     * is a single-host assumption waiting to happen. The controller asks; it does
     * not choose.
     *
     * Implementations must honour [DestinationRequest.candidates] as the complete
     * set of options. The caller has already excluded what must not be chosen, and
     * the reason for each exclusion is on the candidate.
     */
    public suspend fun selectDestination(request: DestinationRequest): DestinationDecision
}

/**
 * Which server's players are moving, and what they could move to.
 *
 * [preference] is the proxy's declared `backends.fallback`, most-preferred first.
 * Naming a server there does not enrol it — it still has to be a candidate.
 */
public data class DestinationRequest(
    val server: ResourceName,
    val proxy: ResourceName,
    val candidates: List<DestinationCandidate>,
    val preference: List<ResourceName> = emptyList(),
)

/**
 * One server that could receive players, and everything that decides whether it
 * may.
 *
 * ## [drainInitiated], never `draining`
 *
 * `PaperServerStatus.draining` is deliberately **false** in
 * `DrainState.DRAIN_FAILED`, because the loop has to be free to resume a drain
 * from there. So a server parked on a retryable abort reads as not-draining and
 * looks like a perfectly good destination — moments before it tries to stop again.
 * Two servers draining at once can then each select the other, players are moved
 * back and forth, and neither ever reaches a stop.
 *
 * The rule is therefore *any drain record at all*, which is exactly what
 * `drainInitiated` means. It is carried as its own field rather than derived here
 * so the mistake cannot be made a second time by a caller that only had a status
 * in hand.
 */
public data class DestinationCandidate(
    val server: ResourceName,
    val maxPlayers: Int,
    /** Players on it right now, or null when nothing has observed it. */
    val online: Int? = null,
    /** True when the proxy is routing new players to it. A sealed backend is not a destination. */
    val admitsNewPlayers: Boolean = true,
    /** Any drain record at all, in flight *or* aborted. See the class note. */
    val drainInitiated: Boolean = false,
    /** Whether the loop has observed it joinable. A server that is still starting is not a destination. */
    val ready: Boolean = true,
)

/** Where a draining server's players go, or why nowhere will do. */
public sealed interface DestinationDecision {
    public data class Selected(
        val destination: ResourceName,
        val reason: String = "",
    ) : DestinationDecision

    /**
     * The search ran and nothing in the fleet could take them.
     *
     * Not the same as "there is no proxy": this is the fleet being too small, so
     * the drain records `DRAIN_NO_DESTINATION` and an operator has to add capacity.
     */
    public data class NoCapacity(
        val message: String,
    ) : DestinationDecision
}

/**
 * What the scheduler is being asked to place.
 *
 * [currentNode] matters as much as [demand]: a server that is already running
 * somewhere is not free to move. Moving it means draining it on the source
 * first, so a scheduler that wants to relocate has to say so explicitly rather
 * than by quietly returning a different node.
 */
public data class PlacementRequest(
    val server: ResourceName,
    /** `spec.placement.node`, when the operator pinned one. Null means "you choose". */
    val pin: NodeName? = null,
    /** Where this server's workload was last observed, if anywhere. */
    val currentNode: NodeName? = null,
    val demand: PlacementDemand,
)

/**
 * What the server needs from a node.
 *
 * `maxPlayers` is here rather than derived because it is the capacity unit this
 * orchestrator actually schedules on — a Minecraft host runs out of player
 * slots long before it runs out of anything a generic scheduler would measure.
 */
public data class PlacementDemand(
    val maxPlayers: Int,
    val memoryBytes: Long,
    val cpuMillicores: Int? = null,
    /** Set when the server has a persistent volume that has to be reachable from the chosen node. */
    val persistentVolume: ResourceName? = null,
)

/** Where a server goes, or why it goes nowhere. */
public sealed interface PlacementDecision {
    public data class Scheduled(
        val node: NodeName,
        /** Why this node. Recorded on observed status so a placement can be explained. */
        val reason: String = "",
    ) : PlacementDecision

    public data class Unschedulable(
        val problem: PlacementProblem,
        val message: String,
    ) : PlacementDecision
}

/**
 * Why nothing could be placed. The distinction drives failure classification:
 * a pin at a node that does not exist is a definition an operator has to fix,
 * whereas a node that is merely down comes back on its own.
 */
public enum class PlacementProblem {
    /** The registry is empty. */
    NO_NODES,

    /** `spec.placement.node` names a node this orchestrator has never heard of. Permanent. */
    PINNED_NODE_UNKNOWN,

    /** The pinned node exists but is not accepting work right now. Retryable. */
    PINNED_NODE_UNAVAILABLE,

    /** Nodes exist, none of them can take this server. Retryable. */
    INSUFFICIENT_CAPACITY,
}

/**
 * The scheduler for a single-host deployment.
 *
 * It is trivial and it stays honest about *why* it is trivial: it asks the
 * registry for the nodes rather than being handed "the" node, it honours a pin,
 * it keeps a running server where it already is, and it refuses to place
 * anything when the node is not ready. Swap in a registry with three nodes and
 * this class still answers correctly — it just always answers with the first
 * ready one, which is where a real scoring implementation would go.
 *
 * It deliberately does *not* check [PlacementDemand] against
 * [NodeCapacity]: on a single host nothing reports allocatable capacity, and a
 * scheduler that treated "not reported" as "zero" would refuse to place
 * anything. Capacity is carried in the request so a real implementation has it
 * without a signature change.
 */
public class SingleNodeScheduler(
    private val registry: NodeRegistry,
) : Scheduler {
    override suspend fun schedule(request: PlacementRequest): PlacementDecision {
        // One readiness answer per node per decision. Without this a node is
        // asked twice on the ordinary path — once as the server's current node,
        // once as a candidate — and a real registry would multiply that by the
        // number of nodes on every pass of every server. The cache is scoped to
        // this call on purpose: readiness is exactly the thing that must not be
        // remembered between passes.
        val readiness = HashMap<NodeName, Boolean>()
        val nodes = registry.nodes()
        if (nodes.isEmpty()) {
            return PlacementDecision.Unschedulable(
                PlacementProblem.NO_NODES,
                "no nodes are registered",
            )
        }

        val pin = request.pin
        if (pin != null) {
            val pinned =
                registry.node(pin)
                    ?: return PlacementDecision.Unschedulable(
                        PlacementProblem.PINNED_NODE_UNKNOWN,
                        "spec.placement.node pins this server to `$pin`, which is not a known node",
                    )
            return if (ready(pinned, readiness)) {
                PlacementDecision.Scheduled(pinned.name, "pinned by spec.placement.node")
            } else {
                PlacementDecision.Unschedulable(
                    PlacementProblem.PINNED_NODE_UNAVAILABLE,
                    "the pinned node `$pin` is not accepting work",
                )
            }
        }

        // A running server stays put. Relocating means draining it on the
        // source node first, which is a decision with player-facing
        // consequences and does not belong in a placement heuristic.
        val current = request.currentNode
        if (current != null) {
            val existing = registry.node(current)
            if (existing != null && ready(existing, readiness)) {
                return PlacementDecision.Scheduled(existing.name, "already running here")
            }
        }

        val candidate =
            nodes.firstOrNull { ready(it, readiness) }
                ?: return PlacementDecision.Unschedulable(
                    PlacementProblem.INSUFFICIENT_CAPACITY,
                    "no registered node is accepting work",
                )
        return PlacementDecision.Scheduled(candidate.name, "the only node accepting work")
    }

    /**
     * The declared preference order first, then whatever is left, by name.
     *
     * Trivial in the same way [schedule] is, and honest about it. Every exclusion
     * that matters — a sealed backend, one with any drain record at all, one that
     * is not joinable yet — is already expressed on the candidate, so this is a
     * *choice among the eligible* rather than a filter. A real implementation would
     * score by free slots and latency and would still be answering the same
     * question against the same list.
     *
     * Ordering by name after the preference list is deliberate rather than
     * arbitrary: a stable choice means a drain that is retried picks the same
     * destination, which is what makes the plugin's start-or-join rule join the
     * sweep it already has instead of superseding it every pass.
     */
    override suspend fun selectDestination(request: DestinationRequest): DestinationDecision {
        val eligible =
            request.candidates.filter { candidate ->
                candidate.server != request.server &&
                    candidate.ready &&
                    candidate.admitsNewPlayers &&
                    !candidate.drainInitiated &&
                    (candidate.online ?: 0) < candidate.maxPlayers
            }
        if (eligible.isEmpty()) {
            val excluded = request.candidates.count { it.server != request.server }
            return DestinationDecision.NoCapacity(
                "none of the $excluded other backend(s) behind `${request.proxy}` can take these players: a " +
                    "destination must be joinable, still admitting, free of any drain record and below its " +
                    "player limit",
            )
        }
        val preferred =
            request.preference.firstNotNullOfOrNull { name -> eligible.firstOrNull { it.server == name } }
        val chosen = preferred ?: eligible.minByOrNull { it.server.value }
        // Not reachable: `eligible` is non-empty, so `minByOrNull` cannot be null.
        // Spelled as a refusal rather than `!!`, which this codebase bans.
        chosen ?: return DestinationDecision.NoCapacity("no destination could be chosen from a non-empty set")
        return DestinationDecision.Selected(
            destination = chosen.server,
            reason = if (preferred != null) "named in backends.fallback" else "the first eligible backend by name",
        )
    }

    /**
     * A node that cannot be reached is not ready. The exception is swallowed
     * here on purpose and only here: "is this node usable" is exactly the
     * question being asked, and the loop reports the answer as an
     * unschedulable placement rather than as a node error.
     */
    private suspend fun ready(
        node: Node,
        cache: MutableMap<NodeName, Boolean>,
    ): Boolean =
        cache.getOrPut(node.name) {
            try {
                node.status().ready
            } catch (failure: NodeException) {
                LOG.debug("node {} is not accepting work: {}", node.name, failure.message)
                false
            }
        }

    private companion object {
        private val LOG = org.slf4j.LoggerFactory.getLogger(SingleNodeScheduler::class.java)
    }
}

package mcorch.core

import mcorch.schema.NodeName

/**
 * The nodes this orchestrator knows about.
 *
 * This is the one place that knows how many nodes there are. Today a
 * [StaticNodeRegistry] holding a single localhost node is constructed at
 * wiring time; a distributed build would put a membership protocol here and
 * nothing above would notice. The reconcile loop asks the [Scheduler] where a
 * server goes and then asks this for the handle — it never enumerates nodes to
 * "find the one".
 */
public interface NodeRegistry {
    /** Every node currently known, in a stable order. */
    public suspend fun nodes(): List<Node>

    /** The node with this name, or null if it is not a member. */
    public suspend fun node(name: NodeName): Node?
}

/**
 * A registry over a fixed set of nodes, supplied at construction.
 *
 * Single host is `StaticNodeRegistry(listOf(localNode))`. That is the *only*
 * expression of "there is one node" in this codebase, and it is a wiring
 * decision rather than an assumption baked into the loop.
 */
public class StaticNodeRegistry(
    nodes: List<Node>,
) : NodeRegistry {
    private val byName: Map<NodeName, Node> = nodes.associateBy { it.name }
    private val ordered: List<Node> = nodes.toList()

    init {
        require(byName.size == ordered.size) { "node names must be unique, got: ${ordered.map { it.name }}" }
    }

    override suspend fun nodes(): List<Node> = ordered

    override suspend fun node(name: NodeName): Node? = byName[name]
}

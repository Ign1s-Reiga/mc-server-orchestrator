package mcorch.app

import mcorch.core.NodeRegistry
import mcorch.core.ReconcileLoop
import mcorch.core.ReconcileLoopConfig
import mcorch.core.Reconciler
import mcorch.core.ReconcilerConfig
import mcorch.core.SingleNodeScheduler
import mcorch.core.StaticNodeRegistry
import mcorch.core.node.LocalNode
import mcorch.core.node.LocalNodeConfig
import mcorch.store.SecretStore
import mcorch.store.Store
import mcorch.store.sqlite.EmbeddedStore
import mcorch.store.sqlite.EmbeddedStoreConfig
import org.slf4j.LoggerFactory

/**
 * Everything wired together, and the only place that decides what "everything"
 * is.
 *
 * The composition root is deliberately thin and deliberately ignorant. It names
 * a [Store], a [NodeRegistry], a scheduler and a loop — no SQLite type, no CRI
 * type, no socket. `:app` does not depend on `:cri` at all
 * (`app/build.gradle.kts` says why), and [LocalNode.open] takes an endpoint
 * *string* precisely so that stays true: if wiring ever cannot construct a node
 * without naming a CRI type, the seam is wrong and the seam is what should
 * change.
 *
 * Held open as an [AutoCloseable] rather than run and forgotten, because the
 * integration suite drives exactly this object. A composition root that only
 * `main` can build is a composition root nothing tests.
 */
public class Orchestrator private constructor(
    private val embedded: EmbeddedStore,
    private val node: LocalNode,
    /** Desired and observed state, for anything that wants to read or declare. */
    public val store: Store,
    /** Secret material. The RCON password lives here, never in a definition. */
    public val secrets: SecretStore,
    public val nodes: NodeRegistry,
    public val reconciler: Reconciler,
    private val loop: ReconcileLoop,
) : AutoCloseable {
    /**
     * Runs the reconcile loop until the calling coroutine is cancelled.
     *
     * Everything the loop starts is a child of the caller's scope, so cancelling
     * it stops the workers, the resync ticker and the change-feed poller
     * together — and the loop's own shutdown cancels its pending requeues on the
     * way out.
     */
    public suspend fun run(): Unit = loop.run()

    /**
     * Closes the node and then the store.
     *
     * In that order on purpose: the node holds a gRPC channel that a pass may
     * still be using, and the store holds the record of what those passes did.
     * Both are closed even if the first throws — a leaked SQLite handle leaves a
     * locked database behind, which is a worse morning than a leaked socket.
     *
     * **Nothing here stops a container.** Shutting the orchestrator down is not
     * a request to stop the servers it manages: they keep running, and the next
     * process to start reconciles them back into its view of the world. A stop
     * only ever comes from the drain protocol.
     */
    override fun close() {
        try {
            node.close()
        } finally {
            embedded.close()
        }
    }

    public companion object {
        private val LOG = LoggerFactory.getLogger(Orchestrator::class.java)

        /** Opens the store and the node described by [config]. Does not connect eagerly. */
        public fun open(
            config: OrchestratorConfig,
            reconcilerConfig: ReconcilerConfig = ReconcilerConfig(),
            loopConfig: ReconcileLoopConfig = ReconcileLoopConfig(),
        ): Orchestrator {
            val embedded = EmbeddedStore.open(EmbeddedStoreConfig(directory = config.dataDirectory))
            val node =
                try {
                    LocalNode.open(
                        config =
                            LocalNodeConfig(
                                name = config.nodeName,
                                runtimeEndpoint = config.runtimeEndpoint,
                                volumeRoot = config.volumeRoot,
                                logRoot = config.logRoot,
                                sandboxNamespace = config.sandboxNamespace,
                                cgroupParent = config.cgroupParent,
                            ),
                        secrets = embedded.secrets,
                    )
                } catch (failure: Throwable) {
                    embedded.close()
                    throw failure
                }
            return try {
                // One node today. This is the *only* expression of that fact in
                // the whole program: the scheduler asks the registry, the loop
                // asks the scheduler, and neither of them counts.
                val registry = StaticNodeRegistry(listOf(node))
                val scheduler = SingleNodeScheduler(registry)
                val reconciler = Reconciler(embedded.state, registry, scheduler, reconcilerConfig)
                LOG.info(
                    "orchestrator wired: node={} data={} volumes={}",
                    config.nodeName,
                    config.dataDirectory,
                    config.volumeRoot,
                )
                Orchestrator(
                    embedded = embedded,
                    node = node,
                    store = embedded.state,
                    secrets = embedded.secrets,
                    nodes = registry,
                    reconciler = reconciler,
                    loop = ReconcileLoop(embedded.state, reconciler, loopConfig),
                )
            } catch (failure: Throwable) {
                node.close()
                embedded.close()
                throw failure
            }
        }
    }
}

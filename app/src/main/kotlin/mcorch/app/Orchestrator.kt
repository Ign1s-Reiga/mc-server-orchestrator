package mcorch.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mcorch.core.NodeRegistry
import mcorch.core.ReconcileLoop
import mcorch.core.ReconcileLoopConfig
import mcorch.core.Reconciler
import mcorch.core.ReconcilerConfig
import mcorch.core.SingleNodeScheduler
import mcorch.core.StaticNodeRegistry
import mcorch.core.console.NodeServerConsole
import mcorch.core.console.ServerConsole
import mcorch.core.node.LocalNode
import mcorch.core.node.LocalNodeConfig
import mcorch.core.termination.ForcedTermination
import mcorch.core.termination.NodeForcedTermination
import mcorch.store.IdentityStore
import mcorch.store.SecretStore
import mcorch.store.Store
import mcorch.store.sqlite.EmbeddedStore
import mcorch.store.sqlite.EmbeddedStoreConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

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
    /** Operators and their tiers. Empty until one is created. */
    public val identities: IdentityStore,
    public val nodes: NodeRegistry,
    /** The one method `:api` reaches `:core` through, for the remote console. */
    public val console: ServerConsole,
    /** The forced-stop path. Superuser-only at the API, and it can lose unsaved play. */
    public val forcedTermination: ForcedTermination,
    public val reconciler: Reconciler,
    private val loop: ReconcileLoop,
) : AutoCloseable {
    /** Whether [run] is between its first and last instruction. Read by [close]. */
    private val running = AtomicBoolean(false)

    /**
     * Runs the reconcile loop until the calling coroutine is cancelled.
     *
     * Everything the loop starts is a child of the caller's scope, so cancelling
     * it stops the workers, the resync ticker and the change-feed poller
     * together — and the loop's own shutdown cancels its pending requeues on the
     * way out.
     *
     * **On a multi-threaded dispatcher, and not the caller's.** The loop runs
     * four workers, a resync ticker and a change-feed poller, and every one of
     * them crosses a gRPC boundary into containerd. `runBlocking` — which is
     * what `main` and any test naturally provide — is a *single-threaded* event
     * loop: on it the workers cannot run concurrently at all, and a call that
     * blocks its thread rather than suspending freezes the ticker, the poller,
     * every other server's pass, and every `delay` and timeout scheduled on that
     * loop. This was not theoretical: it stalled the whole process about a
     * minute into an integration run and made a five-minute timeout in the test
     * never fire, because the timer had no thread to fire on.
     */
    public suspend fun run(): Unit =
        withContext(Dispatchers.Default) {
            running.set(true)
            try {
                loop.run()
            } finally {
                running.set(false)
            }
        }

    /**
     * Closes the node and then the store.
     *
     * In that order on purpose: the node holds a gRPC channel that a pass may
     * still be using, and the store holds the record of what those passes did.
     * Both are closed even if the first throws — a leaked SQLite handle leaves a
     * locked database behind, which is a worse morning than a leaked socket.
     *
     * **It must not be called while [run] is still running**, and that is a
     * correctness requirement rather than tidiness. A pass that has issued a save
     * request records it under `NonCancellable` precisely so a shutdown cannot
     * lose it; a store closed underneath that write loses it anyway, and the next
     * process sends a second `save-all flush` to a running server. `main` gets
     * this right by joining the loop first.
     *
     * The violation is *reported* rather than refused. Refusing would mean
     * declining to close a SQLite handle, and a locked database left behind is a
     * worse outcome than the one being guarded against — so this closes anyway
     * and says loudly that a record may have been lost.
     *
     * **Nothing here stops a container.** Shutting the orchestrator down is not
     * a request to stop the servers it manages: they keep running, and the next
     * process to start reconciles them back into its view of the world. A stop
     * only ever comes from the drain protocol.
     */
    override fun close() {
        if (running.get()) {
            LOG.error(
                "the orchestrator is being closed while its reconcile loop is still running. Cancel the loop and " +
                    "join it first: a pass that has just issued a world save records that fact through the " +
                    "store, and closing it underneath that write means the next process saves the same world " +
                    "again",
            )
        }
        try {
            node.close()
        } finally {
            embedded.close()
        }
    }

    public companion object {
        private val LOG = LoggerFactory.getLogger(Orchestrator::class.java)

        /**
         * How long a console command has to answer.
         *
         * RCON dispatches onto the game's main thread, so this is really "how long
         * a busy server is given". Long enough that an ordinary command on a
         * loaded server answers; short enough that an operator learns the server
         * is wedged rather than watching a spinner.
         */
        private val CONSOLE_TIMEOUT: kotlin.time.Duration = kotlin.time.Duration.parse("30s")

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
                                assetRoot = config.assetRoot,
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
                // The environment's Velocity pin overrides whatever a caller passed,
                // and only when it is set. `MCORCH_VELOCITY_BUILD` is the operator's
                // lever on a spec-hash input that would otherwise live in
                // orchestrator source, and a lever the composition root drops is not
                // one — see [ReconcilerConfig.velocityBuild].
                val reconciler =
                    Reconciler(
                        embedded.state,
                        registry,
                        scheduler,
                        reconcilerConfig.copy(
                            velocityBuild = config.velocityBuild ?: reconcilerConfig.velocityBuild,
                        ),
                    )
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
                    identities = embedded.identities,
                    console = NodeServerConsole(registry, CONSOLE_TIMEOUT),
                    forcedTermination = NodeForcedTermination(registry, embedded.state),
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

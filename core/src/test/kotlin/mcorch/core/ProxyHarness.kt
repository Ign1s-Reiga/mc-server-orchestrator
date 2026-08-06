package mcorch.core

import mcorch.schema.BackendDrainSpec
import mcorch.schema.BackendSelector
import mcorch.schema.BackendsSpec
import mcorch.schema.ControlEndpointSpec
import mcorch.schema.ForwardingSpec
import mcorch.schema.HeapSpec
import mcorch.schema.ImageRef
import mcorch.schema.MemoryQuantity
import mcorch.schema.ObjectMetadata
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.PlacementSpec
import mcorch.schema.ProxyNetworkSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SecretRef
import mcorch.schema.ServerDefinition
import mcorch.schema.StorageSpec
import mcorch.schema.VelocityProxyDefaults
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxySpec
import mcorch.schema.VelocityProxyStatus
import mcorch.schema.VolumeSpec
import mcorch.store.StoredDefinition
import mcorch.store.getOrThrow
import kotlin.time.Duration

/** The label a fixture proxy's selector matches. Nothing else in the fleet carries it. */
internal const val BACKEND_LABEL: String = "mcorch.example/pool"
internal const val BACKEND_POOL: String = "survival"

/**
 * A proxy definition that is boring on purpose.
 *
 * The forwarding secret is a [SecretRef] and there is no material anywhere in this
 * file — a fixture is exactly the kind of file where such a value gets committed
 * by accident and lives for ever (CLAUDE.md invariant 4).
 */
internal fun proxyDefinition(
    name: String = "front-01",
    node: String = "proxy-node",
    selector: Map<String, String> = mapOf(BACKEND_LABEL to BACKEND_POOL),
    fallback: List<ResourceName> = emptyList(),
    controlPort: Int = VelocityProxyDefaults.CONTROL_PORT,
    /**
     * Coordinates of the control endpoint's bearer token, or null for an
     * endpoint that is not published and needs none.
     *
     * Null by default because that is the schema's default, and because the
     * interesting assertion is about the *pair*: a token declared here has to
     * reach both `:core`'s outbound calls and the plugin's own environment, and
     * for a while it reached only the first.
     */
    tokenSecret: SecretRef? = null,
    maxPlayers: Int = 200,
    /**
     * How long the proxy gets to answer each control call, and the one field a test
     * can put a value in that no reader would have produced.
     *
     * `VelocityProxyReader` accepts `1s..1h`; `BackendDrainSpec` has no `init`, so a
     * store row, a migration or a fixture carries anything. Both ends of that gap are
     * exercised — `EndpointTimeoutCeiling` above, and `ControlChannel.unbuildable`
     * below, where zero is a request no call can be made from.
     */
    sealTimeout: Duration = VelocityProxyDefaults.SEAL_TIMEOUT,
): VelocityProxyDefinition =
    VelocityProxyDefinition(
        apiVersion = SchemaVersion.CURRENT,
        metadata = ObjectMetadata(name = resourceName(name)),
        spec =
            VelocityProxySpec(
                image = ImageRef.parse("docker.io/itzg/bungeecord:2026.6.1").getOrThrow(),
                resources =
                    ResourceSpec(
                        memory = memory(2L * MemoryQuantity.GIB),
                        heap = HeapSpec(max = memory(1L * MemoryQuantity.GIB)),
                    ),
                forwarding = ForwardingSpec(secret = SecretRef.of("$name-forwarding", "secret").getOrThrow()),
                backends =
                    BackendsSpec(
                        selector = BackendSelector(selector),
                        fallback = fallback,
                        drain = BackendDrainSpec(sealTimeout = sealTimeout),
                    ),
                control = ControlEndpointSpec(port = controlPort, tokenSecret = tokenSecret),
                maxPlayers = maxPlayers,
                network = ProxyNetworkSpec(hostPort = 25577),
                placement = PlacementSpec(node = nodeName(node)),
            ),
    )

/**
 * A fleet with one proxy and any number of backends, each pinned to its own node.
 *
 * Pinned rather than sharing one node because [FakeNode] holds a single workload:
 * that is a faithful simplification for a one-server test and an unfaithful one
 * for a fleet, and the honest fix at this scale is one node per workload rather
 * than a fake that pretends. It also exercises the seam harder — every control
 * call has to find the *proxy's* node through the registry rather than "the" node.
 */
internal class ProxyHarness(
    val clock: MutableClock = MutableClock(),
    proxy: VelocityProxyDefinition = proxyDefinition(),
    backends: List<PaperServerDefinition> = emptyList(),
    config: ReconcilerConfig = ReconcilerConfig(),
) {
    val proxyDefinition: VelocityProxyDefinition = proxy
    val backendDefinitions: List<PaperServerDefinition> = backends

    val proxyNode: FakeNode = FakeNode(nodeName("proxy-node"), clock)

    /** The plugin listening inside the proxy's sandbox. Every control assertion lands here. */
    val plugin: FakeProxyPlugin = FakeProxyPlugin()

    val backendNodes: Map<ResourceName, FakeNode> =
        backends.associate { it.metadata.name to FakeNode(nodeName("node-${it.metadata.name}"), clock) }

    val store: TestStore = TestStore(clock)
    val registry: NodeRegistry = StaticNodeRegistry(listOf(proxyNode) + backendNodes.values)
    val scheduler: RecordingScheduler = RecordingScheduler(SingleNodeScheduler(registry))
    val reconciler: Reconciler = Reconciler(store, registry, scheduler, config, clock)

    init {
        proxyNode.endpoints[proxy.spec.control.port] = plugin
    }

    fun nodeOf(backend: PaperServerDefinition): FakeNode =
        backendNodes[backend.metadata.name] ?: error("no node for ${backend.metadata.name}")

    suspend fun declare(definition: ServerDefinition): StoredDefinition = store.putDefinition(definition).getOrThrow()

    suspend fun declareAll() {
        declare(proxyDefinition)
        backendDefinitions.forEach { declare(it) }
    }

    suspend fun pass(name: ResourceName): ReconcileOutcome = reconciler.reconcile(name)

    /** Runs passes over one server until it settles or fails, or [limit] is reached. */
    suspend fun settle(
        name: ResourceName,
        limit: Int = 12,
    ): ReconcileOutcome {
        var last: ReconcileOutcome = ReconcileOutcome.Settled("no pass ran")
        repeat(limit) {
            last = pass(name)
            if (last is ReconcileOutcome.Settled || last is ReconcileOutcome.Failed) return last
        }
        return last
    }

    /** One round over the whole fleet, proxy last, the way a resync would. */
    suspend fun sweep() {
        backendDefinitions.forEach { pass(it.metadata.name) }
        pass(proxyDefinition.metadata.name)
    }

    /** Brings the proxy and every backend up to running-and-routing. */
    suspend fun bringUp() {
        declareAll()
        repeat(SETTLE_ROUNDS) { sweep() }
    }

    suspend fun proxyStatus(): VelocityProxyStatus? = store.proxyStatusOf(proxyDefinition.metadata.name)

    suspend fun status(name: ResourceName): PaperServerStatus? = store.statusOf(name)

    private companion object {
        /** Enough rounds for image, create, start and readiness on every workload. */
        private const val SETTLE_ROUNDS = 6
    }
}

/**
 * A server the fixture proxy's selector does **not** match, pinned to its own node.
 *
 * Pinned like every other workload here. An unpinned definition is placed on the
 * first ready node, which is the proxy's — and [FakeNode] holds one workload, so
 * an unpinned fixture silently evicts the proxy and every assertion about routing
 * then fails for a reason that has nothing to do with routing.
 */
internal fun unmatchedDefinition(
    name: String,
    hostPort: Int? = null,
): PaperServerDefinition =
    paperDefinition(
        name = name,
        hostPort = hostPort,
        placement = PlacementSpec(node = nodeName("node-$name")),
    )

/** A backend of the fixture proxy: carries the label its selector matches. */
internal fun backendDefinition(
    name: String,
    maxPlayers: Int = 20,
    hostPort: Int? = null,
    storage: StorageSpec = StorageSpec.Persistent(VolumeSpec(resourceName("$name-world"))),
    /**
     * The one field a test edits to ask for a replacement of a *backend*.
     *
     * `maxPlayers` is the lever the proxy tests use on the proxy, and it is not one
     * here: a backend's max is in the registration the proxy holds rather than in
     * its container's spec hash. An image is in the hash on both kinds.
     */
    image: String = DEFAULT_SERVER_IMAGE,
): PaperServerDefinition =
    paperDefinition(
        name = name,
        labels = mapOf(BACKEND_LABEL to BACKEND_POOL),
        image = image,
        maxPlayers = maxPlayers,
        hostPort = hostPort,
        storage = storage,
        placement = PlacementSpec(node = nodeName("node-$name")),
    )

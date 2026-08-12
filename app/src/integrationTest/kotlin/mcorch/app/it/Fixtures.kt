package mcorch.app.it

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import mcorch.schema.BackendSelector
import mcorch.schema.BackendsSpec
import mcorch.schema.ControlEndpointSpec
import mcorch.schema.DrainSpec
import mcorch.schema.ForwardingSpec
import mcorch.schema.HeapSpec
import mcorch.schema.ImageRef
import mcorch.schema.LifecycleSpec
import mcorch.schema.MemoryQuantity
import mcorch.schema.MinecraftVersion
import mcorch.schema.NetworkSpec
import mcorch.schema.ObjectMetadata
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerSpec
import mcorch.schema.PaperVersionSpec
import mcorch.schema.ProxyLifecycleSpec
import mcorch.schema.ProxyNetworkSpec
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SecretRef
import mcorch.schema.StorageSpec
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxySpec
import mcorch.schema.VolumeSpec
import mcorch.store.getOrThrow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the enclosing test method's return type to `Unit`, and stops the loop the
 * body started.
 *
 * JUnit Jupiter silently drops a `@Test` method whose return type is not void —
 * it disappears at discovery with a warning nobody reads, and the suite goes
 * green having run nothing. This has already cost this repo 54 tests once.
 *
 * **The `cancelChildren` is why this suite terminates.** `runBlocking` returns
 * when its body has finished *and every coroutine launched in its scope has
 * finished too*, and [ContainerdHarness.start] launches the reconcile loop in
 * exactly that scope — a loop whose whole job is never to return. So a body that
 * passed all its assertions and fell off the end left `runBlocking` parked for
 * ever on a coroutine that was working perfectly, and the only thing that ever
 * ended the test was the eight-minute `@Timeout` watchdog. It looked from the
 * outside exactly like a hang in the code under test: output stopped mid-run,
 * `@AfterEach` never ran because the test method could not return, and the loop
 * went on reconciling into a store nobody was reading. It reported as
 * `TimeoutException` on a test whose every assertion had already passed.
 *
 * A failing body needs no help — the failure cancels the scope on its way out.
 * It is the *passing* one that has to say when it is done.
 */
internal fun integrationTest(body: suspend CoroutineScope.() -> Unit): Unit =
    runBlocking {
        try {
            body()
        } finally {
            coroutineContext.cancelChildren()
        }
    }

internal fun resourceName(raw: String): ResourceName = ResourceName.of(raw).getOrThrow()

/**
 * The image the tests run.
 *
 * Pinned to a real tag: `:schema` rejects `latest`, because a moving tag makes
 * an image change invisible to reconcile. A test host that has not pulled this
 * will pull it, which is why the readiness budget is what it is.
 */
internal const val PAPER_IMAGE: String = "docker.io/itzg/minecraft-server:2026.6.1"

internal fun rconSecret(server: String): SecretRef = SecretRef.of("$server-rcon", "password").getOrThrow()

/**
 * The proxy image, pinned to a real tag on the JVM the plugin is built for.
 *
 * `-java25` is not cosmetic: `velocity-api` 4.0.0 declares `jvm.version = 25`, so
 * the plugin JAR this repo builds targets 25 and will not link on the image's
 * older-JVM variants. A run against `:2026.7.1` alone is a run against whatever
 * default that tag carries.
 */
internal const val PROXY_IMAGE: String = "docker.io/itzg/mc-proxy:2026.7.1-java25"

internal fun forwardingSecret(proxy: String): SecretRef = SecretRef.of("$proxy-forwarding", "modern").getOrThrow()

internal fun controlToken(proxy: String): SecretRef = SecretRef.of("$proxy-control", "token").getOrThrow()

/**
 * Secret material for one run, generated rather than written.
 *
 * Never a literal, and not only because CLAUDE.md invariant 4 says the forwarding
 * secret may not appear in a fixture: a committed control token is a committed
 * credential for an endpoint that can move every player in a fleet. Generated per
 * run, it exists in the secret store and in this process and nowhere else.
 */
internal fun generatedSecret(): String =
    java.util.UUID
        .randomUUID()
        .toString()
        .replace("-", "")

/**
 * A proxy whose control endpoint is inside the sandbox and needs a token to talk
 * to.
 *
 * Unpublished on purpose — that is the schema's default and the safe one — so
 * `:core` reaches the endpoint through the [mcorch.core.Node] abstraction, which
 * is the path a drain uses. The token is declared anyway: an endpoint reachable
 * from anything on this host is one worth authenticating, and it is the half of
 * the control channel that was missing entirely.
 */
internal fun velocityProxy(
    name: String,
    image: String = PROXY_IMAGE,
    hostPort: Int? = null,
    selector: Map<String, String> = mapOf(PROXY_POOL_LABEL to name),
    startupTimeout: Duration = 5.minutes,
): VelocityProxyDefinition =
    VelocityProxyDefinition(
        apiVersion = SchemaVersion.CURRENT,
        metadata = ObjectMetadata(name = resourceName(name)),
        spec =
            VelocityProxySpec(
                image = ImageRef.parse(image).getOrThrow(),
                resources =
                    ResourceSpec(
                        memory = MemoryQuantity.ofBytes(2L * MemoryQuantity.GIB).getOrThrow(),
                        heap = HeapSpec(max = MemoryQuantity.ofBytes(1L * MemoryQuantity.GIB).getOrThrow()),
                    ),
                forwarding = ForwardingSpec(secret = forwardingSecret(name)),
                backends = BackendsSpec(selector = BackendSelector(selector)),
                control = ControlEndpointSpec(tokenSecret = controlToken(name)),
                network = ProxyNetworkSpec(hostPort = hostPort),
                lifecycle = ProxyLifecycleSpec(startupTimeout = startupTimeout),
            ),
    )

/** A label no other fixture carries, so a proxy's selector matches only what a test gave it. */
internal const val PROXY_POOL_LABEL: String = "mcorch.example/proxy-pool"

/**
 * A deliberately small Paper server.
 *
 * Nothing here carries a Velocity forwarding secret, a player name or a UUID —
 * a fixture is exactly the kind of file where such a value gets committed by
 * accident and lives for ever.
 */
@Suppress("LongParameterList")
internal fun paperServer(
    name: String,
    image: String = PAPER_IMAGE,
    hostPort: Int,
    storage: StorageSpec = StorageSpec.Persistent(VolumeSpec(resourceName("$name-world"))),
    rcon: RconSpec = RconSpec(passwordSecret = rconSecret(name)),
    maxPlayers: Int = 20,
    saveTimeout: Duration = 60.seconds,
    startupTimeout: Duration = 5.minutes,
): PaperServerDefinition =
    PaperServerDefinition(
        apiVersion = SchemaVersion.CURRENT,
        metadata = ObjectMetadata(name = resourceName(name)),
        spec =
            PaperServerSpec(
                image = ImageRef.parse(image).getOrThrow(),
                paper = PaperVersionSpec(minecraftVersion = MinecraftVersion.of("1.21.4").getOrThrow()),
                resources =
                    ResourceSpec(
                        memory = MemoryQuantity.ofBytes(2L * MemoryQuantity.GIB).getOrThrow(),
                        heap = HeapSpec(max = MemoryQuantity.ofBytes(1L * MemoryQuantity.GIB).getOrThrow()),
                    ),
                storage = storage,
                eulaAccepted = true,
                maxPlayers = maxPlayers,
                network = NetworkSpec(hostPort = hostPort, rcon = rcon),
                lifecycle =
                    LifecycleSpec(
                        drain = DrainSpec(saveTimeout = saveTimeout),
                        stopGracePeriod = saveTimeout + 30.seconds,
                        startupTimeout = startupTimeout,
                    ),
            ),
    )

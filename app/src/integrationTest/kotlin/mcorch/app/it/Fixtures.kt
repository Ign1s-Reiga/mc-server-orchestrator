package mcorch.app.it

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import mcorch.schema.DrainSpec
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
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SecretRef
import mcorch.schema.StorageSpec
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
    rcon: RconSpec = RconSpec.Enabled(passwordSecret = rconSecret(name)),
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

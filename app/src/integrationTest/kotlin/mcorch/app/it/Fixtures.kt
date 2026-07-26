package mcorch.app.it

import kotlinx.coroutines.CoroutineScope
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
 * Pins the enclosing test method's return type to `Unit`.
 *
 * JUnit Jupiter silently drops a `@Test` method whose return type is not void —
 * it disappears at discovery with a warning nobody reads, and the suite goes
 * green having run nothing. This has already cost this repo 54 tests once.
 */
internal fun integrationTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = body)

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

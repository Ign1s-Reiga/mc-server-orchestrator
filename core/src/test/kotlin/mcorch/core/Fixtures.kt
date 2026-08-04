package mcorch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import mcorch.schema.ConditionType
import mcorch.schema.DrainSpec
import mcorch.schema.HeapSpec
import mcorch.schema.ImageRef
import mcorch.schema.LifecycleSpec
import mcorch.schema.MemoryQuantity
import mcorch.schema.MinecraftVersion
import mcorch.schema.NetworkSpec
import mcorch.schema.NodeName
import mcorch.schema.ObjectMetadata
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerSpec
import mcorch.schema.PaperServerStatus
import mcorch.schema.PaperVersionSpec
import mcorch.schema.PlacementSpec
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SecretRef
import mcorch.schema.StatusCondition
import mcorch.schema.StorageSpec
import mcorch.schema.VolumeSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Runs a suspending test body and pins the enclosing test method's return type
 * to `Unit`.
 *
 * `@Test fun t() = runBlocking { ... }` infers whatever the block's last
 * expression returns, and JUnit Jupiter does not treat a method with a non-void
 * return type as a test at all — it is dropped at discovery with only a
 * warning, so the suite goes green having run nothing. Every suspending test
 * here goes through this, which returns `Unit` explicitly, so that cannot
 * happen.
 */
internal fun coreTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = body)

/**
 * A clock the test moves by hand.
 *
 * The loop decides several things from elapsed time — the startup timeout, the
 * status heartbeat — and a real clock would make those tests either slow or
 * flaky.
 */
internal class MutableClock(
    private var current: Instant = Instant.parse("2026-07-26T10:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    fun advance(by: Duration) {
        current = current.plus(by.toJavaDuration())
    }
}

/**
 * The escalation condition, which must always be present.
 *
 * `single` rather than `firstOrNull`: a condition that is simply absent from a
 * status is indistinguishable, to anything asserting on it, from one that is
 * present and false — and "no alert fired" passing because the condition was
 * never derived at all is exactly the green-for-the-wrong-reason this suite has
 * been bitten by. Absence fails here.
 */
internal fun PaperServerStatus.attention(): StatusCondition = condition(ConditionType.NEEDS_ATTENTION)

/** Any condition, with the same insistence that it be present. */
internal fun PaperServerStatus.condition(type: ConditionType): StatusCondition = conditions.single { it.type == type }

internal fun resourceName(raw: String): ResourceName = ResourceName.of(raw).getOrThrow()

internal fun nodeName(raw: String): NodeName = NodeName.of(raw).getOrThrow()

internal fun memory(bytes: Long): MemoryQuantity = MemoryQuantity.ofBytes(bytes).getOrThrow()

internal fun secretRef(
    name: String = "survival-01-rcon",
    key: String = "password",
): SecretRef = SecretRef.of(name, key).getOrThrow()

/**
 * A deliberately boring definition, close to `schema/src/testFixtures/resources/examples`.
 *
 * Built here rather than parsed from those files on purpose: the reconcile tests
 * vary one field at a time, which a YAML fixture per variation cannot do
 * readably. :core therefore reads no example file and takes no dependency on
 * `:schema`'s test fixtures — only this sentence points at them.
 *
 * Nothing here carries a Velocity forwarding secret, a player name or a UUID; a
 * fixture is exactly the kind of file where such a value gets committed by
 * accident and lives forever.
 */
internal fun paperDefinition(
    name: String = "survival-01",
    image: String = "docker.io/itzg/minecraft-server:2026.6.1",
    storage: StorageSpec = StorageSpec.Persistent(VolumeSpec(resourceName("$name-world"))),
    rcon: RconSpec = RconSpec.Enabled(passwordSecret = secretRef()),
    maxPlayers: Int = 20,
    hostPort: Int? = 30001,
    placement: PlacementSpec = PlacementSpec(),
    saveTimeout: Duration = 3.minutes,
    startupTimeout: Duration = 5.minutes,
    memoryBytes: Long = 4L * MemoryQuantity.GIB,
    heapBytes: Long = 3L * MemoryQuantity.GIB,
): PaperServerDefinition =
    PaperServerDefinition(
        apiVersion = SchemaVersion.CURRENT,
        metadata = ObjectMetadata(name = resourceName(name)),
        spec =
            PaperServerSpec(
                image = ImageRef.parse(image).getOrThrow(),
                paper = PaperVersionSpec(minecraftVersion = MinecraftVersion.of("1.21.8").getOrThrow()),
                resources =
                    ResourceSpec(
                        memory = memory(memoryBytes),
                        heap = HeapSpec(max = memory(heapBytes)),
                    ),
                storage = storage,
                eulaAccepted = true,
                maxPlayers = maxPlayers,
                network = NetworkSpec(hostPort = hostPort, rcon = rcon),
                lifecycle =
                    LifecycleSpec(
                        drain = DrainSpec(saveTimeout = saveTimeout),
                        stopGracePeriod = saveTimeout + 60.seconds,
                        startupTimeout = startupTimeout,
                    ),
                placement = placement,
            ),
    )

package mcorch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import mcorch.schema.VelocityProxyStatus
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
 *
 * The body is also bounded. A suspending test that awaits something the code
 * under test never delivers does not fail: `runBlocking` parks, and the run
 * hangs for as long as anything is willing to wait — on CI until the job
 * timeout, locally until somebody notices. One parked here for two hours
 * having spent ten seconds of CPU. [DEFAULT_TEST_TIMEOUT] makes that a
 * failure that names the test instead, which is the whole difference between
 * a diagnosis and a killed run.
 *
 * Nothing correct in this module needs the time. Every test drives a
 * [MutableClock] it moves by hand precisely so elapsed time is not something
 * the suite waits out. Pass [timeout] to widen it for one test that genuinely
 * does, rather than raising the default for all of them.
 */
internal fun coreTest(
    timeout: Duration = DEFAULT_TEST_TIMEOUT,
    body: suspend CoroutineScope.() -> Unit,
): Unit = runBlocking { withTimeout(timeout) { body() } }

/**
 * Long enough that a loaded machine cannot fail a correct test — this module's
 * entire suite runs in well under a minute — and short enough that a hang
 * surfaces as something read rather than something killed.
 */
internal val DEFAULT_TEST_TIMEOUT: Duration = 60.seconds

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

/** The same two, for a proxy. Absence fails here for the same reason. */
internal fun VelocityProxyStatus.attention(): StatusCondition = condition(ConditionType.NEEDS_ATTENTION)

internal fun VelocityProxyStatus.condition(type: ConditionType): StatusCondition = conditions.single { it.type == type }

internal fun resourceName(raw: String): ResourceName = ResourceName.of(raw).getOrThrow()

internal fun nodeName(raw: String): NodeName = NodeName.of(raw).getOrThrow()

internal fun memory(bytes: Long): MemoryQuantity = MemoryQuantity.ofBytes(bytes).getOrThrow()

internal fun secretRef(
    name: String = "survival-01-rcon",
    key: String = "password",
): SecretRef = SecretRef.of(name, key).getOrThrow()

/** What a fixture server runs unless a test is asking for a replacement. */
internal const val DEFAULT_SERVER_IMAGE: String = "docker.io/itzg/minecraft-server:2026.6.1"

/**
 * A second image, so that declaring it is a container replacement.
 *
 * Named because a test that withdraws a replacement has to declare the *first*
 * image again, and two spellings of "the one it was created with" is how such a
 * test comes to assert against an edit it never reverted.
 */
internal const val REPLACEMENT_SERVER_IMAGE: String = "docker.io/itzg/minecraft-server:2026.7.0"

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
    labels: Map<String, String> = emptyMap(),
    image: String = DEFAULT_SERVER_IMAGE,
    storage: StorageSpec = StorageSpec.Persistent(VolumeSpec(resourceName("$name-world"))),
    rcon: RconSpec = RconSpec(passwordSecret = secretRef()),
    maxPlayers: Int = 20,
    hostPort: Int? = 30001,
    placement: PlacementSpec = PlacementSpec(),
    saveTimeout: Duration = 3.minutes,
    /**
     * Independent of [saveTimeout] on purpose, though the default is the schema's
     * own derivation of it.
     *
     * They are two quantities and the schema only relates them one way — a
     * `PaperServer`'s grace period must *exceed* the save timeout, not track it —
     * so an operator may set a long one for a reason of their own. A fixture that
     * could not express that made every `:core` test one where the two moved
     * together, which is how a bound on a save came to be written in terms of the
     * grace period.
     */
    stopGracePeriod: Duration = saveTimeout + 60.seconds,
    startupTimeout: Duration = 5.minutes,
    memoryBytes: Long = 4L * MemoryQuantity.GIB,
    heapBytes: Long = 3L * MemoryQuantity.GIB,
): PaperServerDefinition =
    PaperServerDefinition(
        apiVersion = SchemaVersion.CURRENT,
        metadata = ObjectMetadata(name = resourceName(name), labels = labels),
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
                        stopGracePeriod = stopGracePeriod,
                        startupTimeout = startupTimeout,
                    ),
                placement = placement,
            ),
    )

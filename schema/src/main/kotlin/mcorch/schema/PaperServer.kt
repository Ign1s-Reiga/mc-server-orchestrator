package mcorch.schema

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A single Paper (PaperMC) Minecraft server.
 *
 * This is the template every later kind copies: an envelope
 * ([apiVersion]/[kind]/[metadata]) plus a [spec] of nested, already-defaulted
 * value objects. Observed state never appears here — see [PaperServerStatus].
 */
public data class PaperServerDefinition(
    override val apiVersion: SchemaVersion,
    override val metadata: ObjectMetadata,
    override val spec: PaperServerSpec,
) : ServerDefinition {
    override val kind: ServerKind get() = ServerKind.PAPER_SERVER
}

/** Defaults live in one place so the parser, the tests and the reconciler cannot drift apart. */
public object PaperServerDefaults {
    public const val GAME_PORT: Int = 25565
    public const val RCON_PORT: Int = 25575
    public const val MAX_PLAYERS: Int = 20
    public const val MOUNT_PATH: String = "/data"

    public val PLAYER_TRANSFER_TIMEOUT: Duration = 120.seconds
    public val SAVE_TIMEOUT: Duration = 180.seconds
    public val STARTUP_TIMEOUT: Duration = 5.minutes

    /** How far the default stop grace period is placed above the save timeout. */
    public val STOP_GRACE_MARGIN: Duration = 60.seconds

    /** The smallest margin an operator-supplied stop grace period may keep above the save timeout. */
    public val MIN_STOP_GRACE_MARGIN: Duration = 30.seconds

    /** Upper bound for any lifecycle timeout. Longer than this is a stuck loop, not a slow save. */
    public val MAX_TIMEOUT: Duration = 1.hours

    /** The grace period has to be able to sit above the largest allowed save timeout. */
    public val MAX_STOP_GRACE_PERIOD: Duration = 2.hours

    public val MIN_CONTAINER_MEMORY: MemoryQuantity = memory(1L * MemoryQuantity.GIB)
    public val MAX_CONTAINER_MEMORY: MemoryQuantity = memory(1L * MemoryQuantity.TIB)
    public val MIN_HEAP: MemoryQuantity = memory(256L * MemoryQuantity.MIB)
    public val MIN_VOLUME_SIZE: MemoryQuantity = memory(1L * MemoryQuantity.GIB)

    public const val MAX_PLAYERS_LIMIT: Int = 10_000
    public const val MAX_CPU_MILLICORES: Int = 256_000
}

private fun memory(bytes: Long): MemoryQuantity =
    MemoryQuantity.ofBytes(bytes).getOrElse { error("built-in memory constant is invalid: ${it.message}") }

/**
 * How much of the container's memory limit the JVM heap may claim.
 *
 * A JVM needs more than its heap: metaspace, code cache, thread stacks, GC
 * structures and — for a Minecraft server — a lot of direct byte buffers for
 * network and chunk IO. Sizing `-Xmx` at the container limit does not produce
 * an `OutOfMemoryError`, it produces a kill by the runtime, mid-tick, with the
 * world unsaved. So the schema refuses to express it.
 */
public object JvmHeapPolicy {
    public const val HEADROOM_PERCENT: Int = 20
    public val MIN_HEADROOM: MemoryQuantity = memory(512L * MemoryQuantity.MIB)
    public val MAX_HEADROOM: MemoryQuantity = memory(2L * MemoryQuantity.GIB)

    /** Reserved for everything in the container that is not heap. */
    public fun headroom(memory: MemoryQuantity): MemoryQuantity {
        val proportional = memory.bytes / 100L * HEADROOM_PERCENT
        val clamped = proportional.coerceIn(MIN_HEADROOM.bytes, MAX_HEADROOM.bytes)
        return MemoryQuantity.ofBytes(minOf(clamped, memory.bytes)).getOrElse { MIN_HEADROOM }
    }

    /** The largest `-Xmx` allowed under a given container memory limit. */
    public fun maxAllowedHeap(memory: MemoryQuantity): MemoryQuantity = memory - headroom(memory)

    /** What `-Xmx` becomes when the operator does not say. Rounded down to a whole MiB. */
    public fun defaultMaxHeap(memory: MemoryQuantity): MemoryQuantity {
        val allowed = maxAllowedHeap(memory).bytes
        val wholeMib = allowed / MemoryQuantity.MIB * MemoryQuantity.MIB
        return MemoryQuantity.ofBytes(wholeMib).getOrElse { PaperServerDefaults.MIN_HEAP }
    }
}

/**
 * Cross-field rules that would lose data if they were only checked at parse
 * time. They are stated once and enforced twice: the parser reports them as
 * violations (so they aggregate with everything else), and the constructors
 * refuse to build an object that breaks them (so no other module can invent
 * one).
 */
internal object SpecInvariants {
    fun heapProblem(
        heapMax: MemoryQuantity,
        containerMemory: MemoryQuantity,
    ): String? {
        val allowed = JvmHeapPolicy.maxAllowedHeap(containerMemory)
        return if (heapMax <= allowed) {
            null
        } else {
            "must leave headroom below the container memory limit: with " +
                "spec.resources.memory=${containerMemory.render()} the largest heap is " +
                "${allowed.render()}, found ${heapMax.render()}. A heap sized at the container limit is " +
                "OOM-killed mid-tick with the world unsaved"
        }
    }

    fun stopGraceProblem(
        stopGracePeriod: Duration,
        saveTimeout: Duration,
    ): String? {
        val minimum = saveTimeout + PaperServerDefaults.MIN_STOP_GRACE_MARGIN
        return if (stopGracePeriod >= minimum) {
            null
        } else {
            "must exceed spec.lifecycle.drain.saveTimeout (${DurationFormat.render(saveTimeout)}) by at least " +
                "${DurationFormat.render(PaperServerDefaults.MIN_STOP_GRACE_MARGIN)}, so at least " +
                "${DurationFormat.render(minimum)}, found ${DurationFormat.render(stopGracePeriod)}. " +
                "A grace period shorter than the save timeout kills the container part-way through the save"
        }
    }
}

/** Which Paper build to run. The image supplies the launcher; this pins what it launches. */
public data class PaperVersionSpec(
    val minecraftVersion: MinecraftVersion,
    val build: Int? = null,
)

/** A Minecraft release such as `1.21.8`. Snapshots are not expressible on purpose. */
@JvmInline
public value class MinecraftVersion private constructor(
    public val value: String,
) : Comparable<MinecraftVersion> {
    override fun compareTo(other: MinecraftVersion): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        private val PATTERN = Regex("^[0-9]+\\.[0-9]+(?:\\.[0-9]+)?$")

        public fun of(raw: String): Result<MinecraftVersion> =
            if (PATTERN.matches(raw)) {
                Result.success(MinecraftVersion(raw))
            } else {
                invalidValue("expected a Minecraft release such as `1.21.8`, found `$raw`")
            }
    }
}

/**
 * Ports the server listens on inside its sandbox, plus how it is published.
 *
 * [hostPort] is optional and is the only place a definition may talk about the
 * outside of the sandbox. Leaving it unset means "the orchestrator publishes it
 * however the node does"; it does not name a node either way.
 */
public data class NetworkSpec(
    val port: Int = PaperServerDefaults.GAME_PORT,
    val hostPort: Int? = null,
    val rcon: RconSpec = RconSpec.Disabled,
)

/**
 * RCON, off unless asked for.
 *
 * Sealed rather than a flag plus nullable fields: "enabled but no password
 * reference" is not a state anything in this system can hold. There is no
 * inline password field and there will not be one.
 */
public sealed interface RconSpec {
    public data object Disabled : RconSpec

    public data class Enabled(
        val port: Int = PaperServerDefaults.RCON_PORT,
        val passwordSecret: SecretRef,
    ) : RconSpec
}

/** The container's memory/CPU limits and the JVM heap that has to fit inside them. */
public data class ResourceSpec(
    val memory: MemoryQuantity,
    val heap: HeapSpec,
    val cpu: CpuQuantity? = null,
) {
    init {
        val problem = SpecInvariants.heapProblem(heap.max, memory)
        require(problem == null) { "heap.max $problem" }
    }
}

/** `-Xmx` / `-Xms`. They default to the same value: a resizing heap causes avoidable GC pauses. */
public data class HeapSpec(
    val max: MemoryQuantity,
    val min: MemoryQuantity = max,
) {
    init {
        require(min <= max) {
            "heap.min (${min.render()}) must not exceed heap.max (${max.render()})"
        }
    }
}

/**
 * Where the world lives.
 *
 * [Persistent] is the default and the safe side: the volume outlives the
 * container, so a restart, an image change or a reschedule does not take the
 * world with it. [Ephemeral] exists for genuinely disposable instances —
 * lobbies, minigame rounds — and has to be asked for by name.
 */
public sealed interface StorageSpec {
    public val mountPath: String

    /** The wire discriminator, for APIs and for the store's schema. */
    public val mode: StorageMode

    public data class Persistent(
        val volume: VolumeSpec,
        override val mountPath: String = PaperServerDefaults.MOUNT_PATH,
    ) : StorageSpec {
        override val mode: StorageMode get() = StorageMode.PERSISTENT
    }

    public data class Ephemeral(
        override val mountPath: String = PaperServerDefaults.MOUNT_PATH,
    ) : StorageSpec {
        override val mode: StorageMode get() = StorageMode.EPHEMERAL
    }
}

/** How storage is declared in YAML. `persistent` is the default; `ephemeral` must be asked for. */
public enum class StorageMode(
    public val wireValue: String,
) {
    PERSISTENT("persistent"),
    EPHEMERAL("ephemeral"),
    ;

    override fun toString(): String = wireValue

    public companion object {
        public fun fromWire(raw: String): StorageMode? = entries.firstOrNull { it.wireValue == raw }

        public fun supported(): List<String> = entries.map { it.wireValue }
    }
}

/**
 * The claim on persistent storage. Named, not located: a host path would pin
 * the server to a node.
 */
public data class VolumeSpec(
    val name: ResourceName,
    val size: MemoryQuantity? = null,
)

/**
 * What has to happen before this server may be stopped, and how long each part
 * of it is allowed to take.
 *
 * Only the two timings that scale with the world and the player count are
 * exposed. The proxy-side handshakes (seal, destination lookup, deregister) are
 * fixed in the reconciler; they do not vary per server and are not worth a knob
 * an operator can set wrong.
 */
public data class LifecycleSpec(
    val drain: DrainSpec = DrainSpec(),
    val stopGracePeriod: Duration = drain.saveTimeout + PaperServerDefaults.STOP_GRACE_MARGIN,
    val startupTimeout: Duration = PaperServerDefaults.STARTUP_TIMEOUT,
) {
    init {
        val problem = SpecInvariants.stopGraceProblem(stopGracePeriod, drain.saveTimeout)
        require(problem == null) { "stopGracePeriod $problem" }
    }
}

/** Drain settings. Draining is not optional; only its timings are. */
public data class DrainSpec(
    val policy: DrainPolicy = DrainPolicy.WAIT_FOR_ZERO_PLAYERS,
    val playerTransferTimeout: Duration = PaperServerDefaults.PLAYER_TRANSFER_TIMEOUT,
    val saveTimeout: Duration = PaperServerDefaults.SAVE_TIMEOUT,
)

/**
 * How players leave before a stop.
 *
 * One value today, and it is the default: transfer players away and wait until
 * the server reports zero. There is deliberately no "kick after a timeout"
 * policy — a drain that cannot finish aborts and leaves the server running.
 * Later policies (a named fallback pool, for instance) are added here, which is
 * an additive change.
 */
public enum class DrainPolicy(
    public val wireValue: String,
) {
    WAIT_FOR_ZERO_PLAYERS("waitForZeroPlayers"),
    ;

    override fun toString(): String = wireValue

    public companion object {
        public fun fromWire(raw: String): DrainPolicy? = entries.firstOrNull { it.wireValue == raw }

        public fun supported(): List<String> = entries.map { it.wireValue }
    }
}

/**
 * Everything an operator declares about a Paper server. Fully defaulted by the
 * parser: every value here is the one the reconciler must act on.
 */
public data class PaperServerSpec(
    val image: ImageRef,
    val paper: PaperVersionSpec,
    val resources: ResourceSpec,
    val storage: StorageSpec,
    val eulaAccepted: Boolean,
    val maxPlayers: Int = PaperServerDefaults.MAX_PLAYERS,
    val network: NetworkSpec = NetworkSpec(),
    val lifecycle: LifecycleSpec = LifecycleSpec(),
    val placement: PlacementSpec = PlacementSpec(),
) : ServerSpec {
    init {
        require(eulaAccepted) {
            "eulaAccepted must be true: a Paper server refuses to start until the Minecraft EULA is accepted"
        }
    }
}

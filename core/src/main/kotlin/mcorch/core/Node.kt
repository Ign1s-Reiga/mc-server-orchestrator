package mcorch.core

import mcorch.schema.ImageRef
import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.ServerKind
import java.time.Instant
import kotlin.time.Duration

/**
 * Where a container runs.
 *
 * One of the three distribution seams (see CLAUDE.md). Today there is exactly
 * one implementation and it talks to a containerd on localhost — but nothing
 * about that appears below. There is no CRI type in this file, no socket, no
 * "the local one": a [Node] is addressed by [name] and every container
 * operation is a suspending call that may fail because a *remote* machine is
 * unreachable. A distributed implementation is an agent client behind this same
 * interface, and no call site changes.
 *
 * ## The unit is a workload, not a container
 *
 * CRI splits a running server into a sandbox and a container inside it, with an
 * ordering that is easy to get wrong and a teardown that can forcibly kill a
 * server mid-save. That split is a runtime detail, so it lives behind this
 * interface: [ensureWorkload] brings the whole thing into existence and
 * [removeWorkload] takes the whole thing away, in the right order.
 *
 * ## Idempotency
 *
 * [ensureImage] and [ensureWorkload] are idempotent by contract: called twice
 * with the same argument they perform the side effect at most once and adopt
 * what is already there. That is what lets the reconcile loop run a pass
 * repeatedly without accumulating containers or re-downloading images.
 * Implementations discover what they created last time by *listing by label*,
 * never by trusting an ID a caller remembered.
 *
 * ## Failure
 *
 * Every function here throws [NodeException] and nothing else from this module.
 * Branch on [NodeException.retryable]. No `mcorch.cri` type — exception or
 * otherwise — is allowed to escape an implementation, because a caller that
 * pattern-matches on one has quietly assumed the node is a local containerd.
 *
 * ## Stopping
 *
 * [stopWorkload] takes a strictly positive grace period, so a zero-grace kill
 * is not expressible through this interface at all. Stopping is never
 * unconditional either: the caller has confirmed zero players and a completed
 * save first — see `.claude/skills/drain-protocol/`.
 */
public interface Node {
    /** How this node is addressed. Stable for the node's lifetime. */
    public val name: NodeName

    /**
     * Whether the node can accept work, and what it has to offer.
     *
     * The cheapest call here. A node that answers this is reachable; one that
     * throws [NodeException.Unreachable] is not, which is a requeue rather than
     * a failure of any particular server.
     */
    public suspend fun status(): NodeStatus

    /**
     * What is running for [server] on this node, discovered by label.
     *
     * The reconcile loop's "observe" step. Absence is [WorkloadObservation.Absent],
     * not an exception: a server that has never been created is a normal state.
     */
    public suspend fun observe(server: ResourceName): WorkloadObservation

    /**
     * Makes [image] available on the node, pulling only if it is not already
     * there.
     *
     * [ImageAvailability.pulled] reports whether this call actually pulled, so a
     * caller can tell "already present" from "downloaded just now" — and so a
     * test can assert that a second reconcile pass did not re-pull.
     */
    public suspend fun ensureImage(image: ImageRef): ImageAvailability

    /**
     * Brings the workload described by [spec] into existence, or adopts the one
     * already there. Does **not** start it.
     *
     * Adoption is by label, and it is what makes a repeated pass safe. A
     * workload that exists but was created from a different
     * [WorkloadSpec.specHash] is adopted and reported with its old hash: it is
     * the caller's decision — never this interface's — whether that difference
     * warrants draining and replacing it.
     */
    public suspend fun ensureWorkload(spec: WorkloadSpec): WorkloadObservation.Present

    /**
     * Starts a created workload. Idempotent: starting a running workload is a
     * no-op, not a failure.
     *
     * Returning means the process was started. It does **not** mean the server
     * is joinable — that is what [exec] and a readiness probe are for.
     */
    public suspend fun startWorkload(handle: WorkloadHandle)

    /**
     * Runs a command inside the workload and waits for it to exit.
     *
     * The mechanism behind "save the world and confirm it completed", and
     * behind reading a player count. This interface deliberately does not
     * interpret the result: what counts as a completed save is a property of
     * the server kind, and a command that exits zero having printed an error is
     * a failed save. [ExecOutcome] is the raw answer; the caller judges it.
     */
    public suspend fun exec(
        handle: WorkloadHandle,
        request: ExecRequest,
    ): ExecOutcome

    /**
     * Stops the workload's container, killing it after [gracePeriod].
     *
     * **Never call this on a server with players online, and never before a
     * completed world save has been confirmed.** The grace period is the
     * last-resort safety net for a container that reaches here anyway, not the
     * save path.
     *
     * [gracePeriod] must be strictly positive — a zero-grace kill is not
     * expressible through this interface, on purpose. Implementations derive it
     * from `spec.lifecycle.stopGracePeriod`, which the schema already
     * guarantees exceeds the save timeout.
     *
     * Idempotent: stopping an already-stopped workload succeeds.
     */
    public suspend fun stopWorkload(
        handle: WorkloadHandle,
        gracePeriod: Duration,
    )

    /**
     * Removes the workload's container and then its sandbox.
     *
     * **Persistent storage survives this**, which is the entire point of
     * [StorageRequest.Persistent]: the volume outlives the container, so a
     * removal here does not take the world with it.
     *
     * The caller must have stopped the container first. An implementation that
     * finds a *running* container refuses with [NodeException.Rejected] rather
     * than forcing it down, because the runtime's own teardown kills whatever
     * is still inside with no grace and no save.
     *
     * Idempotent: removing what is already gone succeeds.
     */
    public suspend fun removeWorkload(handle: WorkloadHandle)
}

/** What a node reports about itself. */
public data class NodeStatus(
    /** True when the node can accept containers right now. */
    val ready: Boolean,
    /** Operator-facing detail. Never an address. */
    val detail: String = "",
    val capacity: NodeCapacity = NodeCapacity(),
)

/**
 * What a node has to offer a scheduler. Every field is nullable and null means
 * "not reported", never "zero" — a single-host deployment has no reason to
 * account for any of it, and a scheduler that cannot tell the difference would
 * refuse to place anything.
 */
public data class NodeCapacity(
    val allocatablePlayers: Int? = null,
    val allocatableMemoryBytes: Long? = null,
    val allocatableCpuMillicores: Int? = null,
)

/**
 * A handle on one server's runtime objects on one node.
 *
 * Carries [node] so it cannot be used against the wrong one: a handle is not a
 * bare ID and there is no "current node" it could be resolved against.
 * [containerId] is null between sandbox creation and container creation — an
 * honest gap rather than a placeholder.
 */
public data class WorkloadHandle(
    val node: NodeName,
    val sandboxId: String,
    val containerId: String? = null,
) {
    init {
        require(sandboxId.isNotBlank()) { "sandboxId must not be blank" }
        require(containerId == null || containerId.isNotBlank()) { "containerId must not be blank when set" }
    }
}

/** What the runtime is doing with a workload. Not a readiness verdict. */
public enum class WorkloadState {
    /** The sandbox exists; no container has been created in it yet. */
    SANDBOX_ONLY,

    /** The container exists and has not been started. */
    CREATED,

    /** The process is running. A Paper server is `RUNNING` throughout world generation and while deadlocked. */
    RUNNING,

    /** The process has exited and been reaped. There is provably nobody connected. */
    EXITED,

    /** The runtime reported something this build does not recognise. Not a reason to act. */
    UNKNOWN,
}

/** What a node found when it looked for a server's workload. */
public sealed interface WorkloadObservation {
    /** Nothing on this node belongs to that server. */
    public data object Absent : WorkloadObservation

    public data class Present(
        val handle: WorkloadHandle,
        val state: WorkloadState,
        /**
         * The [WorkloadSpec.specHash] the workload was created from, or null if
         * it carries none. A value different from the desired one is how the
         * loop sees "the definition changed under a running server".
         */
        val specHash: String? = null,
        /** What the runtime resolved the image to, for drift detection. */
        val imageId: String? = null,
        val createdAt: Instant? = null,
        val startedAt: Instant? = null,
        val finishedAt: Instant? = null,
        val exitCode: Int? = null,
        /** Short runtime explanation such as `OOMKilled`. Empty when none was given. */
        val reason: String = "",
        /** Runtime detail. Never player data. */
        val message: String = "",
    ) : WorkloadObservation
}

/** The result of making an image available. */
public data class ImageAvailability(
    val image: ImageRef,
    /** The node-unique ID the runtime resolved it to. */
    val id: String,
    /** True when *this call* pulled. False when the image was already there. */
    val pulled: Boolean,
)

/**
 * Everything a node needs to bring one server's workload into existence.
 *
 * Deliberately not a CRI type: it names no sandbox, no namespace and no host
 * path. How this becomes a sandbox plus a container is the implementation's
 * business, and a remote node would answer that question differently.
 *
 * Secret material never appears here. [secretEnv] holds *coordinates* into the
 * secret store, and the implementation resolves them at the moment it hands
 * them to the runtime — see CLAUDE.md invariant 4.
 */
public data class WorkloadSpec(
    val server: ResourceName,
    val kind: ServerKind,
    val image: ImageRef,
    /**
     * A fingerprint of the parts of the definition that require the workload to
     * be recreated rather than updated in place. Recorded on the workload so a
     * later pass can compare without re-deriving anything.
     */
    val specHash: String,
    val storage: StorageRequest,
    val resources: ResourceRequest,
    val hostname: String,
    val env: Map<String, String> = emptyMap(),
    /** Environment variables whose values live in the secret store. Coordinates only. */
    val secretEnv: Map<String, SecretRef> = emptyMap(),
    val command: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val ports: List<PortRequest> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
) {
    init {
        require(specHash.isNotBlank()) { "specHash must not be blank" }
        require(hostname.isNotBlank()) { "hostname must not be blank" }
        require(env.keys.none { it.isBlank() }) { "environment variable names must not be blank" }
        require(secretEnv.keys.none { it.isBlank() }) { "environment variable names must not be blank" }
        require(env.keys.none { it in secretEnv.keys }) {
            "an environment variable must not be declared both plainly and as a secret"
        }
    }

    /** Environment *values* are redacted, the same way the runtime's own spec redacts them. */
    override fun toString(): String =
        "WorkloadSpec(server=$server, kind=$kind, image=${image.canonical}, specHash=$specHash, " +
            "storage=$storage, resources=$resources, hostname=$hostname, env=${env.keys.sorted()}=<redacted>, " +
            "secretEnv=${secretEnv.keys.sorted()}=<from secret store>, command=$command, args=$args, " +
            "ports=$ports, labels=$labels)"
}

/**
 * Where the server's data lives.
 *
 * [Persistent] outlives the container: removing and recreating the workload
 * keeps the world. [Ephemeral] is the only case that skips it, and only
 * explicitly-disposable kinds may ask for it (CLAUDE.md invariant 2).
 */
public sealed interface StorageRequest {
    public val mountPath: String

    public data class Persistent(
        /** Names the claim. Deliberately not a host path — that would pin the server to a node. */
        val volume: ResourceName,
        override val mountPath: String,
    ) : StorageRequest

    public data class Ephemeral(
        override val mountPath: String,
    ) : StorageRequest
}

/** Container limits. The JVM heap that has to fit inside them is already in the environment. */
public data class ResourceRequest(
    val memoryBytes: Long,
    val cpuMillicores: Int? = null,
) {
    init {
        require(memoryBytes > 0) { "memoryBytes must be positive, got: $memoryBytes" }
        require(cpuMillicores == null || cpuMillicores > 0) { "cpuMillicores must be positive when set" }
    }
}

/** A port the workload listens on, and optionally publishes. */
public data class PortRequest(
    /** Operator-facing label such as `game` or `rcon`. */
    val name: String,
    val containerPort: Int,
    /** Null means "do not publish on the host". */
    val hostPort: Int? = null,
) {
    init {
        require(name.isNotBlank()) { "port name must not be blank" }
        require(containerPort in 1..65535) { "containerPort must be in 1..65535, got: $containerPort" }
        require(hostPort == null || hostPort in 1..65535) { "hostPort must be in 1..65535 when set" }
    }
}

/** A command to run inside a workload, with the time it is allowed to take. */
public data class ExecRequest(
    val command: List<String>,
    /**
     * How long the command may run. Required and strictly positive: an
     * unbounded exec would pin the reconcile loop on a call that never returns.
     */
    val timeout: Duration,
) {
    init {
        require(command.isNotEmpty()) { "exec command must not be empty" }
        require(command.none { it.isBlank() }) { "exec command arguments must not be blank" }
        require(timeout.isPositive() && timeout.isFinite()) { "exec timeout must be positive and finite" }
    }
}

/**
 * What a command did. Uninterpreted on purpose.
 *
 * [exitCode] `0` means the command *exited cleanly*, not that it achieved
 * anything. Judging a save from this is the caller's job, and it has to read
 * the output too.
 */
public data class ExecOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    public val exitedCleanly: Boolean get() = exitCode == 0

    /** Both streams, for pattern matching against a server's reply. */
    public val output: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr"
}

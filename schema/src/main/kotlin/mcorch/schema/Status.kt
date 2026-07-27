package mcorch.schema

import java.time.Instant

/**
 * Observed state. Written by the reconcile loop, served by the API, never
 * parsed from an operator's YAML — there is no reader for these types on
 * purpose.
 *
 * Two rules shape everything below.
 *
 * 1. It has to be *comparable*. Every type here is a data class of value types,
 *    so a pass can decide "nothing changed, do nothing" by comparing the status
 *    it would write against the one already stored, instead of re-issuing side
 *    effects. That is how the loop stays idempotent.
 * 2. It must never carry player identity. Occupancy is a count. Endpoints are
 *    the server's own address, never a client's. Nothing here has a name, a
 *    UUID or an IP of a player in it, so the default `toString` of any of these
 *    types is safe to log.
 */
public sealed interface ServerStatus {
    public val name: ResourceName
    public val apiVersion: SchemaVersion
    public val kind: ServerKind

    /** The definition generation this observation reflects. The store assigns generations. */
    public val observedGeneration: Long
    public val phase: ServerPhase

    /** When the loop last looked. A status that stops advancing is an alert, not a steady state. */
    public val observedAt: Instant
}

/** Coarse lifecycle position, for the dashboard and for deciding what to do next. */
public enum class ServerPhase {
    /** Accepted, not acted on yet. */
    PENDING,

    IMAGE_PULLING,
    CREATING,

    /** Container started, not joinable yet. */
    STARTING,

    /** Running; see `ready` for whether players can actually join. */
    RUNNING,

    /** A drain is in progress; see `drain` for where it is. */
    DRAINING,

    STOPPING,
    STOPPED,

    /** Permanently failed; see `failure`. Requires human attention. */
    FAILED,

    /** The node or runtime could not be reached this pass. Not a reason to act. */
    UNKNOWN,
}

/** The drain state machine, mirrored onto observed state so a restart of the loop can resume. */
public enum class DrainState {
    DRAIN_REQUESTED,
    SEALED,
    TARGET_RESOLVED,
    TRANSFERRING,
    SAVING,
    DEREGISTERED,
    STOPPING,

    /** Aborted. There is no edge from here to a stop: a failed drain leaves the server running. */
    DRAIN_FAILED,
}

/** Whether the loop should try again or stop and surface the problem. */
public enum class FailureClass {
    RETRYABLE,
    PERMANENT,
}

/** Closed set of failure causes, so the API and the dashboard can key off them. */
public enum class FailureReason {
    IMAGE_PULL_FAILED,
    IMAGE_REFERENCE_REJECTED,
    SANDBOX_CREATE_FAILED,
    CONTAINER_CREATE_FAILED,
    CONTAINER_START_FAILED,
    CONTAINER_EXITED,
    READINESS_TIMEOUT,
    VOLUME_UNAVAILABLE,
    NODE_UNAVAILABLE,
    RUNTIME_UNREACHABLE,
    DRAIN_NO_DESTINATION,
    DRAIN_TRANSFER_FAILED,
    DRAIN_SAVE_TIMEOUT,
    DRAIN_STALLED,
    UNKNOWN,
}

/**
 * A classified failure. [message] is operator-facing detail — it must not
 * contain player names, UUIDs or addresses.
 */
public data class FailureStatus(
    val reason: FailureReason,
    val failureClass: FailureClass,
    val message: String,
    val occurredAt: Instant,
    val attempts: Int = 1,
)

/**
 * What the loop knows about the image. [resolvedDigest] is what makes a repeat
 * pass skip the pull instead of re-pulling.
 */
public data class ImageStatus(
    val requested: ImageRef,
    val resolvedDigest: String? = null,
    val pulledAt: Instant? = null,
) {
    public val available: Boolean get() = resolvedDigest != null
}

/**
 * The runtime objects backing this server, and the node they are on.
 *
 * Recorded so that a pass can find what it already created instead of creating
 * it again. [containerId] is null between sandbox creation and container
 * creation — an honest gap, not a placeholder.
 */
public data class RuntimeIdentity(
    val node: NodeName,
    val sandboxId: String,
    val containerId: String? = null,
    val createdAt: Instant? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val exitCode: Int? = null,
    val restartCount: Int = 0,
)

/** Where the proxy (or an operator) reaches this server. Never a client address. */
public data class ServerEndpoint(
    val node: NodeName,
    val address: String,
    val port: Int,
)

/** Occupancy, as counts. Identities are not observed and must not be added here. */
public data class PlayerOccupancy(
    val online: Int,
    val max: Int,
    val observedAt: Instant,
) {
    init {
        require(online >= 0) { "online must not be negative" }
        require(max >= 0) { "max must not be negative" }
    }

    public val empty: Boolean get() = online == 0
}

/**
 * Observed storage. [lastSaveConfirmedAt] is the evidence for "the world save
 * completed" — it is set when a save *completion* was confirmed, never when a
 * save was merely requested.
 */
public data class StorageStatus(
    val persistent: Boolean,
    val volumeName: ResourceName? = null,
    val bound: Boolean = false,
    val lastSaveConfirmedAt: Instant? = null,
)

/**
 * A drain in flight, or the one that failed.
 *
 * The `*RequestedAt` fields exist for idempotency: the loop may re-enter a
 * state any number of times, and re-sending a save request costs the server
 * real work. If the timestamp is set, the request went out; wait for the
 * confirmation instead of asking again.
 *
 * ## [saveRequestedAt] and [worldSavedAt] are disjoint, and that is the point
 *
 * A world save has exactly two states worth recording and they mean opposite
 * things to a drain, so they are two fields:
 *
 * - [saveRequestedAt] — a request went out and **has not been confirmed**. It is
 *   the wedge that stops a second `save-all flush` reaching a live server, and
 *   only a human, or a pass that has *observed a player*, may clear it.
 * - [worldSavedAt] — the server itself reported a **completed** save, at that
 *   instant. This is the only thing that may authorise a container stop.
 *
 * Confirming a save clears [saveRequestedAt] and sets [worldSavedAt]; at most
 * one of them is ever set. They used to be one field discriminated by a
 * `worldSaved` flag, and two separate bugs came from a site clearing it without
 * consulting the flag — in one direction a healthy drain wedged for ever, in the
 * other a delivered save was silently re-sent to a live server. Splitting them
 * is what makes both voiders unconditional.
 *
 * No code path in this repository can produce a record with both set: the only
 * writer of [worldSavedAt] is the branch that confirms a save, and it is only
 * reachable when [saveRequestedAt] is already null. Disjointness is enforced by
 * construction rather than by convention, which is the point of the split.
 *
 * Should a stored record carry both anyway — a hand-repaired row, a document
 * from a build that did not know the rule — the reader is deliberately not made
 * to fail. Refusing to decode would make the row unreconcilable, which is worse
 * than reading it. What the drain then does depends on the state, and is worth
 * knowing exactly rather than approximately:
 *
 * - In `SAVING` the unconfirmed request wins. The save is not re-sent, and the
 *   drain aborts permanently for a human to resolve.
 * - In `DEREGISTERED` and `STOPPING` the confirmation wins, because those states
 *   consult [worldSavedAt] alone. A stop may follow — but only behind a current
 *   confirmation, an unbroken chain of zero-player observations, and a fresh
 *   zero-player probe taken on that same pass, so it is not a stop taken on the
 *   strength of the contradictory row by itself.
 */
public data class DrainStatus(
    val state: DrainState,
    val startedAt: Instant,
    val enteredStateAt: Instant,
    val playersEvacuated: Boolean = false,
    val sealRequestedAt: Instant? = null,
    /** A save request that went out and was never confirmed. See the note above. */
    val saveRequestedAt: Instant? = null,
    /** When the server confirmed a *completed* save. Never when one was merely asked for. */
    val worldSavedAt: Instant? = null,
    val deregisteredAt: Instant? = null,
    val transferAttempts: Int = 0,
    /** Where players were sent. A server name, never a player. */
    val destination: ResourceName? = null,
    val failure: FailureStatus? = null,
) {
    /**
     * Whether a completed save has been confirmed for this drain.
     *
     * Derived, never stored: a flag beside its own timestamp is two fields for
     * one fact, and keeping them in step by hand is exactly the mistake this
     * type used to make. Nothing can set this to something [worldSavedAt] does
     * not say, including `copy`.
     */
    public val worldSaved: Boolean get() = worldSavedAt != null
}

public enum class ConditionType {
    IMAGE_AVAILABLE,
    VOLUME_BOUND,
    CONTAINER_RUNNING,

    /** Actually joinable, not merely running. */
    READY,
    DRAINING,
    PLAYERS_EVACUATED,
    WORLD_SAVED,

    /**
     * This server is not going to fix itself and a human has to look at it.
     *
     * A condition rather than a [FailureReason] on purpose. A reason answers
     * *why*, and the why is unchanged by the passage of time: a drain blocked on
     * an unreachable RCON listener is `DRAIN_STALLED` at minute one and at
     * minute twenty, so spending the reason on the escalation would throw away
     * the only field that says what to actually fix. What changes is how long it
     * has been true, and [StatusCondition.lastTransitionAt] records exactly that
     * — which is what an alert wants to fire on.
     *
     * **It reports; it never authorises anything.** Nothing branches on it. A
     * drain that needs attention keeps its failure class, keeps being retried,
     * and keeps its container running: at a limit you stop trying, you do not
     * stop a Minecraft server (`failure-modes.md` item 7).
     *
     * Set today only by a drain that has been failing retryably for longer than
     * `ReconcilerConfig.drainAttentionAfter`, and never by one blocked on
     * players being online — that is the protocol working, and an escalation
     * that fires on a busy evening teaches operators to ignore the signal. The
     * name is deliberately general: a permanently failed bring-up is the obvious
     * next thing to raise it, and doing so needs no schema change.
     */
    NEEDS_ATTENTION,
}

public enum class ConditionStatus {
    TRUE,
    FALSE,
    UNKNOWN,
}

/**
 * An extensible observation with its own transition time, so timeouts can be
 * measured from when a thing became true rather than from when the pass ran.
 */
public data class StatusCondition(
    val type: ConditionType,
    val status: ConditionStatus,
    val message: String = "",
    val lastTransitionAt: Instant,
)

/** Observed state of a [PaperServerDefinition]. */
public data class PaperServerStatus(
    override val name: ResourceName,
    override val observedGeneration: Long,
    override val phase: ServerPhase,
    override val observedAt: Instant,
    val lastTransitionAt: Instant,
    /** Joinable. `phase == RUNNING` is necessary but not sufficient. */
    val ready: Boolean = false,
    val image: ImageStatus? = null,
    val runtime: RuntimeIdentity? = null,
    val endpoint: ServerEndpoint? = null,
    val players: PlayerOccupancy? = null,
    val storage: StorageStatus? = null,
    val drain: DrainStatus? = null,
    val failure: FailureStatus? = null,
    val conditions: List<StatusCondition> = emptyList(),
    override val apiVersion: SchemaVersion = SchemaVersion.CURRENT,
) : ServerStatus {
    override val kind: ServerKind get() = ServerKind.PAPER_SERVER

    /** True while a drain is in flight or has failed — the loop must not "heal" the server back to running. */
    public val draining: Boolean get() = drain != null && drain.state != DrainState.DRAIN_FAILED

    public companion object {
        /** The status to record the moment a definition is accepted and nothing has been observed yet. */
        public fun pending(
            name: ResourceName,
            observedGeneration: Long,
            at: Instant,
        ): PaperServerStatus =
            PaperServerStatus(
                name = name,
                observedGeneration = observedGeneration,
                phase = ServerPhase.PENDING,
                observedAt = at,
                lastTransitionAt = at,
            )
    }
}

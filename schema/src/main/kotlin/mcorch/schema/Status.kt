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

/**
 * Closed set of failure causes, so the API and the dashboard can key off them.
 *
 * Closed, but not fixed: values are appended as kinds arrive, and `:api` serves
 * the current set through `/meta` so a dashboard can render one it has not been
 * taught. Removing or renaming a value is the breaking direction and needs a
 * migration for stored rows.
 */
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

    /**
     * A destination was sought for this server's players and no server in the
     * fleet had capacity for them.
     *
     * This is step 3 of the drain protocol failing on its own terms: the search
     * ran and came back empty. It does **not** resolve itself — it needs an
     * operator to add capacity or start a server — so it escalates like any other
     * retryable drain failure once it has been true for long enough. It used to
     * cover [DRAIN_AWAITING_ZERO_PLAYERS] as well, and inherited that reason's
     * exemption from escalation; the pair are opposite news and are now opposite
     * values.
     */
    DRAIN_NO_DESTINATION,

    /**
     * The drain has nowhere to send anybody and is waiting for the players on
     * this server to log off by themselves.
     *
     * There is no counterparty to transfer to: a standalone Paper server with no
     * proxy in front of it, or the proxy's own drain, since a fleet has one front
     * door. The protocol does not kick players to make progress, so the only
     * correct behaviour is to keep the container running and keep looking.
     *
     * **This is the one reason that is never escalated.** It resolves itself
     * when the last player logs off, and an attention flag that fires every
     * backoff interval on a busy evening is one operators learn to ignore —
     * which costs them the flag for the failures that do need a person. That
     * exemption is only defensible because the state is transient, which is why
     * it is also one of the reasons that may not be classified
     * [FailureClass.PERMANENT]; see [FailureStatus].
     */
    DRAIN_AWAITING_ZERO_PLAYERS,

    DRAIN_TRANSFER_FAILED,
    DRAIN_SAVE_TIMEOUT,
    DRAIN_STALLED,

    /** The Velocity plugin's control endpoint did not answer, so no backend can be sealed or deregistered. */
    PROXY_CONTROL_UNREACHABLE,

    /** The plugin speaks a control protocol this build of `:core` does not. See [ControlEndpointSpec]. */
    PROXY_PLUGIN_INCOMPATIBLE,

    /** The secret store could not supply [ForwardingSpec.secret], so no backend can be brought up. */
    FORWARDING_SECRET_UNAVAILABLE,

    UNKNOWN,
}

/**
 * A classified failure. [message] is operator-facing detail — it must not
 * contain player names, UUIDs or addresses.
 *
 * ## Why two reasons may not be permanent
 *
 * [ALWAYS_RETRYABLE] holds the reasons a call site may not classify
 * [FailureClass.PERMANENT]. `PERMANENT` is not a severity, it is an instruction:
 * *stop trying*. For these two there is no version of stopping that is safer
 * than continuing, because continuing costs a backoff interval and the container
 * stays up either way.
 *
 * - [FailureReason.DRAIN_NO_DESTINATION] — no server in the fleet had capacity.
 *   Fleet capacity is not a property of this server and not a fixed one: it
 *   returns when a player logs off somewhere else, when a scale-up lands, when an
 *   operator starts a lobby. A permanent classification freezes a drain that the
 *   next pass could have finished, and freezes it in the one state where the
 *   container must keep running — so the cost of being wrong is unbounded and the
 *   saving is one search per interval. (This reason *does* escalate: it needs a
 *   person, it just does not need the loop to give up. The two are different
 *   questions and are answered in different places.)
 * - [FailureReason.DRAIN_AWAITING_ZERO_PLAYERS] — waiting for the last player to
 *   leave. This one is additionally the reason the escalation never fires on, and
 *   that exemption is only defensible while the state is transient. Classified
 *   `PERMANENT` it would be a wedged, unretried drain that is also never
 *   flagged: silently the worst of both, and precisely the state the escalation
 *   exists to surface.
 *
 * The pair is refused here, where the two fields meet, rather than guarded at
 * each site that builds one — it was previously a convention held up by two call
 * sites happening to agree, and a third would have broken it without failing a
 * test. Call sites should still pass [FailureClass.RETRYABLE] as a literal
 * rather than a computed value, so the check stays a backstop rather than the
 * only thing deciding.
 *
 * It is one `require` over a set rather than one per reason on purpose. This
 * runs on decode as well as construction — `mcorch.store` rebuilds statuses
 * through their constructors — so every check here is paid by the widest read in
 * the system, and a second one is a second way for a fleet read to abort.
 *
 * A stored row carrying a refused pair is reported as a corrupt row rather than
 * loaded. That is the intended answer: no code path writes one, so a row that
 * has it was edited by hand, and a drain decision taken on hand-edited state is
 * worse than a loud refusal.
 */
public data class FailureStatus(
    val reason: FailureReason,
    val failureClass: FailureClass,
    val message: String,
    val occurredAt: Instant,
    val attempts: Int = 1,
) {
    init {
        require(failureClass == FailureClass.RETRYABLE || reason !in ALWAYS_RETRYABLE) {
            "a $reason failure is always ${FailureClass.RETRYABLE}: what it is blocked on is not a property of " +
                "this server and resolves without anything about this server changing. Classifying it " +
                "${FailureClass.PERMANENT} would stop the loop retrying a drain the next pass could finish"
        }
    }

    public companion object {
        /** Reasons that may only ever be [FailureClass.RETRYABLE]. See the note above. */
        public val ALWAYS_RETRYABLE: Set<FailureReason> =
            setOf(
                FailureReason.DRAIN_NO_DESTINATION,
                FailureReason.DRAIN_AWAITING_ZERO_PLAYERS,
            )
    }
}

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
     * The proxy's backend selector resolved to at least one registered backend.
     *
     * `False` is not a failure — the proxy is running and an operator may simply
     * not have labelled a server yet — but it is the answer to "why can nobody
     * join". A selector that matches nothing cannot be caught at parse time: it
     * is checked against definitions the parse never sees. This is where it
     * surfaces instead. See [BackendsSpec].
     */
    BACKENDS_RESOLVED,

    /**
     * The shipped Velocity plugin answered, and speaks a control protocol this
     * build understands.
     *
     * `False` means seal, transfer and deregister are unavailable, which means
     * no backend behind this proxy can complete a drain. See
     * [ControlEndpointSpec] for why the protocol version is observed here rather
     * than pinned in the spec.
     */
    CONTROL_ENDPOINT_READY,

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
     * `ReconcilerConfig.drainAttentionAfter`, and never by one whose reason is
     * [FailureReason.DRAIN_AWAITING_ZERO_PLAYERS] — that is the protocol working,
     * and an escalation that fires on a busy evening teaches operators to ignore
     * the signal. That exemption belongs to that reason alone: a
     * [FailureReason.DRAIN_NO_DESTINATION] is a drain sitting blocked until
     * somebody adds capacity, which is exactly what this flag is for. The
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

    /**
     * Any drain has been started against this server, including one that
     * aborted.
     *
     * **This, not [draining], is what decides whether a server may receive
     * another server's players.** [draining] is deliberately false in
     * [DrainState.DRAIN_FAILED], because the loop must be free to resume a drain
     * from there — but a server sitting on a retryable abort is a server that
     * will try to stop again on the next pass. Choosing it as a transfer
     * destination moves players in and straight back out, and two servers
     * draining at once can each select the other and neither ever reaches a stop.
     *
     * The two properties exist side by side because they answer different
     * questions and the wrong one is the plausible-looking one.
     */
    public val drainInitiated: Boolean get() = drain != null

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

/**
 * How the proxy currently routes to one backend.
 *
 * The four registered values are the drain protocol's own vocabulary, so a
 * dashboard can show where a backend is without knowing the protocol: [SEALED]
 * is step 2 and [DEREGISTERED] is step 6, and the gap between them is where the
 * players are being moved.
 */
public enum class BackendRegistration {
    /** Matches the selector; the proxy has not been told about it yet. */
    PENDING,

    /** In the routing table and taking new players. */
    REGISTERED,

    /** In the routing table, taking no new players. Existing players are still connected. Drain step 2. */
    SEALED,

    /** Removed from the routing table. Drain step 6. */
    DEREGISTERED,

    /** Matches the selector, but the proxy cannot open a connection to it. */
    UNREACHABLE,
}

/**
 * One backend, as this proxy sees it.
 *
 * [server] is a declared object's name. There is no field here for who is
 * connected to it — [players] is a pair of counts, like everywhere else.
 */
public data class BackendStatus(
    val server: ResourceName,
    val registration: BackendRegistration,
    val players: PlayerOccupancy? = null,
    /**
     * The backend carries a drain record of its own, aborted or in flight.
     *
     * Mirrored from [PaperServerStatus.drainInitiated] rather than from
     * `draining`, and for the reason set out there: a backend on a retryable
     * drain abort reads as not-draining and would otherwise look like a perfectly
     * good destination for somebody else's players, moments before it tries to
     * stop again.
     */
    val drainInitiated: Boolean = false,
    val lastTransitionAt: Instant,
) {
    /**
     * Whether this backend may be handed another server's players.
     *
     * The whole eligibility rule, in one place, so no caller re-derives it from
     * [registration] alone and forgets [drainInitiated].
     */
    public val eligibleAsDestination: Boolean
        get() = registration == BackendRegistration.REGISTERED && !drainInitiated
}

/** What the proxy's selector currently resolves to. Counts and server names only. */
public data class BackendRoutingStatus(
    val observedAt: Instant,
    val backends: List<BackendStatus> = emptyList(),
) {
    /** Backends the selector matched, whatever state they are in. */
    public val matched: Int get() = backends.size

    /** Backends in the routing table, sealed or not. */
    public val registered: Int
        get() =
            backends.count {
                it.registration == BackendRegistration.REGISTERED || it.registration == BackendRegistration.SEALED
            }

    /** Backends that may receive a transfer right now. */
    public val destinations: Int get() = backends.count { it.eligibleAsDestination }
}

/**
 * Whether `:core` can reach the shipped Velocity plugin, and whether they speak
 * the same protocol.
 *
 * [pluginApiVersion] is what the endpoint reported, not what anybody declared —
 * [ControlEndpointSpec] explains why the spec does not pin it. [compatible] is
 * this build's verdict on that report, kept as its own field so a dashboard can
 * tell "did not answer" from "answered, wrong version": the remedies are
 * different and only one of them is "upgrade the proxy image".
 */
public data class ControlEndpointStatus(
    val reachable: Boolean,
    val pluginApiVersion: String? = null,
    val compatible: Boolean = false,
    val lastContactAt: Instant? = null,
)

/**
 * Observed state of a [VelocityProxyDefinition].
 *
 * It is not a [PaperServerStatus] with fields removed. There is no `storage` —
 * a proxy holds no world, and a nullable storage block would invite a reader to
 * conclude "not persistent yet" from an absence. What it has instead is the two
 * observations only a proxy can make: what it is routing to
 * ([backends]) and whether the control endpoint the drain protocol depends on is
 * answering ([control]).
 *
 * [drain] is the same [DrainStatus] a server uses, deliberately. The drain state
 * machine is one machine and a restarted loop has to resume either kind from the
 * same record. A proxy drain simply never visits [DrainState.SAVING], so
 * `saveRequestedAt` and `worldSavedAt` stay null on one — an honest gap, not a
 * placeholder.
 */
public data class VelocityProxyStatus(
    override val name: ResourceName,
    override val observedGeneration: Long,
    override val phase: ServerPhase,
    override val observedAt: Instant,
    val lastTransitionAt: Instant,
    /** Accepting player connections. `phase == RUNNING` is necessary but not sufficient. */
    val ready: Boolean = false,
    val image: ImageStatus? = null,
    val runtime: RuntimeIdentity? = null,
    val endpoint: ServerEndpoint? = null,
    val players: PlayerOccupancy? = null,
    val backends: BackendRoutingStatus? = null,
    val control: ControlEndpointStatus? = null,
    val drain: DrainStatus? = null,
    val failure: FailureStatus? = null,
    val conditions: List<StatusCondition> = emptyList(),
    override val apiVersion: SchemaVersion = SchemaVersion.CURRENT,
) : ServerStatus {
    override val kind: ServerKind get() = ServerKind.VELOCITY_PROXY

    /** True while a drain is in flight or has failed — the loop must not "heal" the proxy back to running. */
    public val draining: Boolean get() = drain != null && drain.state != DrainState.DRAIN_FAILED

    /** Any drain has been started against this proxy, including one that aborted. See [PaperServerStatus.drainInitiated]. */
    public val drainInitiated: Boolean get() = drain != null

    public companion object {
        /** The status to record the moment a definition is accepted and nothing has been observed yet. */
        public fun pending(
            name: ResourceName,
            observedGeneration: Long,
            at: Instant,
        ): VelocityProxyStatus =
            VelocityProxyStatus(
                name = name,
                observedGeneration = observedGeneration,
                phase = ServerPhase.PENDING,
                observedAt = at,
                lastTransitionAt = at,
            )
    }
}

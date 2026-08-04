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
     * retryable drain failure once it has been true for long enough.
     *
     * It is a [FailureReason] and not a [DrainBlockReason] for exactly that
     * reason. "No transfer counterparty exists, so wait for people to log off" is
     * the protocol working and is recorded as [DrainBlock]; "the search ran and
     * the fleet is full" is the fleet being too small, and a human has to fix it.
     * The two used to be this one value, which is why the escalation needed an
     * exemption to avoid alarming on the first — see [DrainStatus.blocked] for
     * what replaced that.
     */
    DRAIN_NO_DESTINATION,

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
 * ## Why one reason may not be permanent
 *
 * [ALWAYS_RETRYABLE] holds the reasons a call site may not classify
 * [FailureClass.PERMANENT]. `PERMANENT` is not a severity, it is an instruction:
 * *stop trying*.
 *
 * There is one, [FailureReason.DRAIN_NO_DESTINATION]: the search for somewhere
 * to put this server's players ran and the fleet had no capacity. Fleet capacity
 * is not a property of this server and not a fixed one — it comes back when a
 * player logs off somewhere else, when a scale-up lands, when an operator starts
 * a lobby — so there is no version of *stop looking for a destination* that is
 * safer than *keep looking, container running*. Giving up freezes a drain the
 * next pass could have finished, and freezes it in the one state where the
 * container must keep running; continuing costs one search per backoff interval.
 * The cost of being wrong is unbounded and the saving is a search.
 *
 * That argument stands on the capacity case alone and does not borrow anything
 * from the escalation. This reason *does* raise [ConditionType.NEEDS_ATTENTION]
 * once it has been true for long enough — a fleet that is too small needs a
 * person — and the rule here would be identical if it did not. *Stop trying* and
 * *call a human* are different questions and are answered in different places.
 *
 * It is refused here, where the two fields meet, rather than guarded at each site
 * that builds one — it was previously a convention held up by two call sites
 * happening to agree, and a third would have broken it without failing a test.
 * Call sites should still pass [FailureClass.RETRYABLE] as a literal rather than
 * a computed value, so the check stays a backstop rather than the only thing
 * deciding.
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
        public val ALWAYS_RETRYABLE: Set<FailureReason> = setOf(FailureReason.DRAIN_NO_DESTINATION)
    }
}

/**
 * Why a drain has stopped advancing when nothing has gone wrong.
 *
 * A closed set, like [FailureReason], and served through `/meta` for the same
 * reason: a dashboard has to be able to render a value it was not taught.
 */
public enum class DrainBlockReason {
    /**
     * There is no counterparty to transfer players through, so the drain is
     * waiting for the last of them to log off by themselves.
     *
     * A standalone Paper server with no proxy in front of it, or the proxy's own
     * drain, since a fleet has one front door. The protocol does not kick players
     * to make progress (`failure-modes.md` item 4), so the only correct behaviour
     * is to keep the container running and keep looking.
     */
    AWAITING_ZERO_PLAYERS,
}

/**
 * A drain that is not advancing, and is not failing either.
 *
 * The same shape as [FailureStatus] with one field missing, and the missing one
 * is the point: there is no [FailureClass] because there is nothing to classify.
 * A block is always retried — that is what it means for it to resolve on its own
 * — so a field saying whether to keep trying would only ever hold one value, and
 * a field that can only hold one value is an invitation to set the other.
 *
 * [message] is operator-facing and must not contain player names, UUIDs or
 * addresses; counts are fine and are the whole content of the useful ones.
 * [since] is when this block was *first* recorded rather than when the loop last
 * looked, and [observations] is how many passes have found it still true — the
 * pair is what tells an operator the loop is still watching rather than wedged.
 */
public data class DrainBlock(
    val reason: DrainBlockReason,
    val message: String,
    val since: Instant,
    val observations: Int = 1,
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
 *
 * ## [blocked] is not [failure], and a consumer has to tell three states apart
 *
 * - **Progressing** — [state] is not [DrainState.DRAIN_FAILED], and neither
 *   [blocked] nor [failure] is set.
 * - **Blocked, and healthy** — [blocked] is set and [failure] is null. Nothing is
 *   wrong; there is simply nothing this drain is allowed to do yet. It parks in
 *   [DrainState.DRAIN_FAILED], keeps being retried, and resolves without anybody
 *   doing anything.
 * - **Failed** — [failure] is set. Something went wrong, and
 *   [FailureStatus.failureClass] says whether the loop is still trying.
 *
 * A blocked drain used to record a [FailureStatus] with a dedicated
 * [FailureReason]. Every consumer that asks "is anything wrong here" then said
 * yes about a server with people happily playing on it, and the escalation
 * carried an explicit exemption for that one reason so it would not alarm on a
 * busy evening. Recording no failure retires the exemption rather than moving it:
 * the escalation is already false whenever [failure] is null, so the correct
 * behaviour falls out of the rule that was always there.
 *
 * The two are disjoint by construction — every site that records one clears the
 * other — and that is deliberately **not** enforced by a `require` here. This
 * type is rebuilt on every status decode, so a check costs the widest read in the
 * system and is one more way for a single hand-edited row to abort a fleet read.
 * A document carrying both is read, and wherever the two are consulted together
 * the failure wins: reporting the louder of the two is the safe direction.
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
    /**
     * When this drain first entered drain step 4, and the anchor its allowance is
     * measured from.
     *
     * **Set once and never cleared.** It is the single load-bearing field of step
     * 4, in the way [saveRequestedAt] is of step 5, and it earns that description
     * the same way: the bound on how long the loop keeps asking a proxy to move
     * players is a *duration*, and a duration needs an anchor that no later pass can
     * move.
     *
     * Two anchors were tried before this field existed and both produced a drain
     * that could never finish. [enteredStateAt] restamps whenever the drain parks
     * and resumes, so the allowance was handed back in full on every cycle — the
     * loop asked for two minutes, parked for one pass, asked for two minutes again,
     * for ever, clearing its own failure each time so nothing ever escalated.
     * [sealRequestedAt] does not restamp but is stamped at step *2*, so it was
     * absent on every path that reached step 4 without a bodied `DRAIN_REQUESTED`
     * pass, and — when present — spent step 4's budget on everything between: a
     * destination search parked on a full fleet, a flapping control endpoint, or
     * simply an orchestrator restart, after which a drain could return with an
     * anchor hours old and abort having asked nobody to move.
     *
     * Null means step 4 has not been reached. A reader must not substitute another
     * instant for it: the correct response to a missing anchor is to stamp one.
     */
    val transferStartedAt: Instant? = null,
    val transferAttempts: Int = 0,
    /** Where players were sent. A server name, never a player. */
    val destination: ResourceName? = null,
    /** Parked, and nothing is wrong. Null unless the drain is waiting on something. See the note above. */
    val blocked: DrainBlock? = null,
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

    /**
     * The drain has stopped advancing and **nothing is wrong**.
     *
     * This is the answer to the only question an operator asks about a drain that
     * is not moving: *is it stuck, or is it just waiting for people to log off?*
     * Today it means the latter — see [DrainBlockReason.AWAITING_ZERO_PLAYERS] —
     * and the honest report is that the container keeps running, the server stays
     * joinable, and the drain resumes on its own.
     *
     * Deliberately a separate signal from [NEEDS_ATTENTION] rather than a shade
     * of it: this one says **do not act** *about the drain*, that one says
     * **act**. Folding the two together is how the attention flag comes to fire on
     * a busy evening every backoff interval, which is how an operator learns it
     * means nothing.
     *
     * **They used to be documented as never both true, and that is no longer
     * so.** [NEEDS_ATTENTION] is derived from the failure recorded on the *pass*
     * as well as from the drain's own, and the two answer different questions: a
     * drain can be quietly waiting for players while the node it is on has become
     * unreachable, and both facts are then true and worth reporting. The
     * disjointness that does still hold is the narrow one this condition is
     * defined by — a drain is never simultaneously *blocked* and *failed* — and a
     * dashboard that rendered the pair as one tri-state must show the attention
     * flag separately.
     *
     * `False` rather than `Unknown` on a server with no drain at all, for the same
     * reason [NEEDS_ATTENTION] is: "nothing is blocked" is something the loop
     * positively knows, and an alert that has to treat `Unknown` as quiet treats a
     * genuinely unreadable status as quiet too. [StatusCondition.lastTransitionAt]
     * is *blocked since when*, which is the number a dashboard puts beside it.
     *
     * True only while [DrainStatus.failure] is null. The two are disjoint by
     * construction, and a stored document that says otherwise is reported as
     * failed — the louder of the two.
     */
    DRAIN_BLOCKED,

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
     * ## What raises it
     *
     * One rule, asked of two failures: the drain's own ([DrainStatus.failure])
     * and the one recorded on the pass ([PaperServerStatus.failure]) when that is
     * a different event from the drain's. Either escalates when it is
     * **permanent** — the loop has stopped, which is the definition of this flag —
     * or when it has been recurring for longer than
     * `ReconcilerConfig.drainAttentionAfter`, measured from its own first
     * occurrence.
     *
     * The second arm is what stops it being a drain flag. A refused definition
     * edit, a pinned node that does not exist and a container that exited all
     * record a permanent failure with **no drain at all**, and until this widened
     * they showed an ordinary badge — `RUNNING`, even — with nothing beside it,
     * on a server the loop had stopped observing. That is the same defect as the
     * terminating-badge one below, with the lie pointing the quieter way.
     *
     * **No [FailureReason] is exempt.** There used to be one, for the drain
     * waiting on players to log off, and it is gone because that drain now records
     * no failure at all ([DrainStatus.blocked]) — so this flag, which is false
     * whenever there is no failure to escalate, stays quiet by the ordinary rule
     * instead of by an exception to it. The exemption was worth deleting on its
     * own: it was checked before the failure class to stop a future permanent
     * classification routing a healthy drain back in, and that ordering was a
     * second thing to get right in a rule that should not have had a first.
     *
     * It is *more* self-clearing than a drain-only flag would be, which is the
     * answer to the alarm-fatigue objection: a pass that gets anywhere records no
     * failure, so the flag goes with it, and a retryable failure is never
     * escalated before the threshold however often it repeats.
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

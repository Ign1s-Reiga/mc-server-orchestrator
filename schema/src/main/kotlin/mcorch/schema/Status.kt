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

    /**
     * The same failure with its permanence dropped and its anchor kept.
     *
     * For the one caller that has evidence against the class it is carrying: a
     * drain that resumed and completed a step has *disproved* the permanence of
     * whatever refused that step. [attempts] and [occurredAt] survive, because
     * how long this trouble has been true is not what the resume disproved, and
     * they are what the escalation threshold measures.
     *
     * Only ever widens what the loop is willing to retry, so it cannot make a
     * `PERMANENT` failure out of a `RETRYABLE` one and cannot violate the
     * [ALWAYS_RETRYABLE] rule above.
     */
    public fun recoverable(): FailureStatus =
        if (failureClass == FailureClass.RETRYABLE) this else copy(failureClass = FailureClass.RETRYABLE)

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
 * Observed storage, and **nothing here is re-read from the definition**.
 *
 * The type-level rule at the top of this file says a status is what the loop
 * observed. This block did not honour it: every field was drafted from
 * `spec.storage` on every pass, so a server whose definition had just been
 * edited reported the edit rather than the workload — a volume that does not
 * exist reported exactly what was asked for. An operator diagnosing a
 * half-applied storage change read the change back at them.
 *
 * The absence of the whole block is the only way this type says *"nothing has
 * been observed"*. There is no per-field unknown: [persistent] is a `Boolean`
 * because a tri-state would have to be decoded, migrated and re-answered by
 * every consumer, and a null block already carries the meaning. So a null
 * [ServerStatus] storage block claims nothing and gates nothing, and a
 * consumer must not read it as `persistent = false` — those are the two
 * sentences *"this row has never said"* and *"there is no world here"*, and
 * only the second tells somebody to stop looking for a world.
 *
 * ## Where each field comes from
 *
 * - [persistent] — **observed.** Read back off the workload's own
 *   `mcorch.dev/world-data` label, which `:core` writes at create time
 *   precisely so that a later pass can ask what the container *was built with*
 *   rather than what the definition says today (see [ServerSpec.holdsWorldData],
 *   which states the same rule for the drain). A workload carrying no such
 *   label says nothing, and the previous record stands.
 * - [volumeName] — **carried forward, not observed yet.** It is the last value
 *   this loop recorded, kept across passes and never rewritten from the
 *   definition. Reading the volume a container actually has mounted needs the
 *   runtime's mount list plumbed out through the node abstraction, which is a
 *   separate change; until then a name here is *the last one recorded for this
 *   server* and may be older than the workload beside it. It is deliberately
 *   kept even when [persistent] goes false, because it is the only record of
 *   which volume holds a world that a replacement has stopped mounting, and
 *   that name is what a recovery starts from.
 * - [bound] — **observed.** The node reported a workload for this server on
 *   the pass that wrote this record.
 * - [lastSaveConfirmedAt] — **observed.** Set when a save *completion* was
 *   confirmed, never when a save was merely requested. It is an audit record
 *   that outlives the drain which earned it; nothing gates on it, and
 *   [DrainStatus.worldSavedAt] is the evidence a stop is authorised by.
 *
 * `:core` also synthesises one of these for a kind that has no storage block at
 * all — [VelocityProxyStatus] — so that the shared conditions have an answer.
 * `persistent = false` is sound there because a proxy's
 * [ServerSpec.holdsWorldData] is `false` by construction rather than by
 * configuration: it is a property of the kind, not a reading of a definition.
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
 * The two were disjoint by construction and are not any more. A park clears any
 * block, and a block clears a **retryable** failure — the pass that reached it
 * re-established whatever that fault was about — but a *permanent* one stands: a
 * world save that was never confirmed is not resolved by somebody logging back in,
 * and the drain hits it again the moment the server empties. So *blocked, and
 * healthy* above means [blocked] set and [failure] null, which is what
 * [ConditionType.DRAIN_BLOCKED] is derived from.
 *
 * Neither the clearing nor the pairing is enforced by a `require` here. This type
 * is rebuilt on every status decode, so a check costs the widest read in the
 * system and is one more way for a single hand-edited row to abort a fleet read.
 * Wherever the two are consulted together the failure wins: reporting the louder
 * of the two is the safe direction.
 */
public data class DrainStatus(
    val state: DrainState,
    val startedAt: Instant,
    val enteredStateAt: Instant,
    val playersEvacuated: Boolean = false,
    /**
     * Since when this drain has had the workload's login path shut at the proxy.
     *
     * Stamped by whichever state got a `PUT` the proxy confirmed — drain step 2 runs
     * on every pass of every state that depends on it, and the *first* of them to
     * land writes this. Never restamped, so it stays "since when" rather than "most
     * recently"; never cleared, because there is no unseal operation for a drain to
     * record.
     *
     * **Not a live level.** A drain that has parked hands a backend's admission back
     * without touching this field, since it is the *proxy's* pass that states a
     * backend's admission. Whether players can join right now is what the proxy
     * answers, and for a backend `PaperServerStatus.draining` is the closer question.
     * Nothing in the loop gates on this — a bound that did is the event-shaped seal
     * wearing a timestamp, and [transferStartedAt] is the anchor that replaced it.
     */
    val sealRequestedAt: Instant? = null,
    /** A save request that went out and was never confirmed. See the note above. */
    val saveRequestedAt: Instant? = null,
    /** When the server confirmed a *completed* save. Never when one was merely asked for. */
    val worldSavedAt: Instant? = null,
    /**
     * When this drain first had to go back and save the world **again** because
     * the confirmation it was holding had stopped describing the container in
     * front of it.
     *
     * One forced re-save is ordinary: the container restarted, or the loop was
     * not watching for long enough, and the drain saves again rather than
     * stopping on evidence it cannot vouch for. A drain that keeps doing it is
     * not: it flushes a live server's world on every other pass, reports progress
     * on every one of them, and never reaches a stop. That state used to be
     * invisible — nothing failed, so nothing was recorded and nothing escalated —
     * and this is the anchor that measures it.
     *
     * **Stamped on the edge back to `SAVING`** — the drain going back for another
     * confirmation, which is the thing being counted — rather than where a
     * confirmation is voided. Losing one while parked is not this defect and has a
     * failure recorded already.
     *
     * **Set once, and cleared by exactly one event: a probe that saw somebody on
     * the server.** A player makes the next save legitimate rather than
     * suspicious, so the count starts again from there. It is deliberately *not*
     * cleared by a save, by a stop attempt or by a park: each of those happens
     * once per cycle of the very loop this measures, and clearing on any of them
     * hands the allowance back for ever — the mistake `enteredStateAt` made for
     * drain step 4 ([transferStartedAt]).
     *
     * A row written before this field existed reads null and the next voided
     * confirmation stamps it, which costs one extra cycle before a stuck drain is
     * reported and is the safe direction.
     */
    val resaveForcedAt: Instant? = null,
    val deregisteredAt: Instant? = null,
    /**
     * When a container stop request for this drain **left this process**.
     *
     * The one fact a compensating edge needs and nothing else records: not whether
     * the stop succeeded, not whether the container is going away, but whether a
     * `SIGTERM` may already have been delivered to the process a compensation is
     * about to send players back to. [state] `== STOPPING` is not that fact — a stop
     * whose deadline elapsed is caught with the drain still `DEREGISTERED` — and
     * neither is the exception class, because a refusal of a *re-issue* usually
     * follows a first stop that returned cleanly and therefore a dispatch. Both were
     * proposed as discriminators and each is wrong at a different call site; this is
     * the fact they were proxies for.
     *
     * **`STOPPING` has two producers and only one of them dispatches anything.** This
     * KDoc used to say a first stop returning cleanly was "the only thing that puts a
     * drain in `STOPPING`". It is not: a drain whose container the runtime already
     * reports as gone is moved to `STOPPING` from the *observation*, with no request
     * issued and this field left as it was. That is the strongest reason to keep the
     * discriminator on this field rather than on [state] — the state is reachable
     * without a dispatch, and a stamp is not — so the correction strengthens the
     * design it was written to justify rather than qualifying it.
     *
     * **Stamped before the request is issued, which is deliberately the opposite of
     * [saveRequestedAt].** The two records have opposite purposes. A save record
     * exists to stop a *second* send, so stamping one for a request that never left
     * would wedge a drain on a save the server never got: it is written after the
     * call, from what the call reported. This record exists to tell a later pass there
     * is something **not** to reverse, so losing it errs towards re-admitting players
     * to a container that is shutting down — the direction to design against. A stamp
     * for a request that then failed to leave costs availability; a dispatch with no
     * stamp costs a player's session.
     *
     * **So what it records is that a call was made, not that a request reached a
     * runtime**, and the ordering above is the whole content of the field, so the one
     * path that breaks the equivalence is named here rather than left to be
     * rediscovered: a `Node` is free to return without issuing anything, and the
     * single-host one does exactly that for a handle carrying no container id. It errs
     * the safe way — a stamp with nothing behind it withholds a re-admission rather
     * than granting one — and nothing routes into a stop with such a handle today. The
     * field is named for the boundary it can actually observe, which is this process.
     *
     * **Set once, and retired by the workload rather than by the drain.** There is no
     * un-dispatch, so a later pass can only learn that another stop is *also*
     * outstanding, never that the first one is not. Every other field here is cleared
     * by the whole record going when the drain is no longer wanted — and that rule is
     * wrong for this one, because **"no longer wanted" and "the `SIGTERM` is already
     * out" are different questions, and only the first is the operator's to answer.**
     * Reverting the edit that asked for a `REPLACEMENT` withdraws the cause while the
     * container is still inside its stop grace period, reporting `RUNNING` throughout;
     * a pass that took this field with the cause would hand the workload straight back
     * to a proxy's routing sweep, which admits players to a process whose shutdown
     * save has already run. It is therefore retired where the container is *observed
     * gone* — `Reconciler.teardown`, through `clearedDrainRecord`, which is what every
     * site in the loop that would clear a drain record asks first.
     *
     * **A row written before this field existed is reconstructed on the read, and is
     * not left to cost a cycle like the other anchors here.** This paragraph used to
     * say the opposite — that a drain caught mid-stop by an upgrade "can re-admit
     * once", at "the same one-cycle cost every anchor here pays". Every clause of
     * that was wrong. [resaveForcedAt] and [transferStartedAt] cost a cycle when they
     * are null; paragraph four above says losing *this* one costs a player's session
     * and is the direction to design against, so the residual was priced in a
     * sibling's currency. It is not once, either: the pass that finds no dispatch
     * deletes the whole drain record, and nothing restores it, so the backend keeps
     * admitting for the rest of the grace period. And inferring a dispatch from the
     * state is not the proxy this field replaces — `state == STOPPING` was refused as
     * the *call-site* discriminator because it **under**-reports, and on a document
     * that carries no stamp at all the same test **over**-reports, which is the safe
     * direction. [StatusReconstruction] owns that argument, states why the threshold
     * is `STOPPING` and not one state earlier or later, and every [ServerStatus] a
     * store hands back has been through it.
     */
    val stopDispatchedAt: Instant? = null,
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
    /**
     * How far this drain's faults exceed its recoveries — a **ledger**, not a
     * count.
     *
     * [failure] answers *"is something wrong right now"* and its `occurredAt`
     * answers *"since when"*. Neither can answer *"has this been going wrong and
     * right, over and over"*, because a recovery deletes the record: a control
     * endpoint that fails on one pass and behaves on the next has no standing
     * failure at any instant a reader might look, and every anchor it might be
     * measured from is reset by the pass in between. Four hours of that used to
     * raise nothing at all.
     *
     * So this one is not reset by a recovery, it is **paid down** by one. A pass
     * that records a fault adds 1; a pass that positively establishes health —
     * work that came back with what it asked for, or a drain that reached its
     * gate and found only players in the way — subtracts 1, with a floor at zero.
     * A pass that establishes *neither*, such as one waiting on a container the
     * runtime will not describe, leaves it alone: "did not fail" and "was found
     * healthy" are different facts, and spending the second on the first turns a
     * quiet loop into a slow eraser of real faults.
     *
     * The arithmetic is the specification and it is meant to be checkable in a
     * sentence: **the ledger grows only while a drain is failing more often than
     * it recovers.**
     *
     * That is a statement about the *drift*, not about what is reachable, and the
     * difference is worth being exact about because the loose version has been
     * written here before. Above half the drift is upward and the threshold is
     * reached quickly. At or below half there is no upward drift — but the walk is
     * not absorbed at zero, it is reflected there, so the count alone still reaches
     * any threshold almost surely: on the order of `(q/p)^N` passes below half and
     * `N(N+1)` at exactly half. **Nothing here is a guarantee of silence.**
     *
     * Those two figures are about **this counter and not about the flag**, and the
     * distinction is the whole of why no wall-clock number is quoted for the flag
     * anywhere. The escalation is a conjunction: the count has to reach the
     * threshold *and* stay above zero for `drainAttentionAfter`. A recovering pass
     * reports progress, `WorkQueue` clears the attempt count, and the retry delay
     * drops back to its base — so that duration is a long unbroken run of passes,
     * and below half the probability of an excursion surviving one decays
     * exponentially in its length. The count's hitting time is therefore a floor
     * under the flag's, and a loose one. `docs/operating.md` states the consequence
     * qualitatively and says why it offers no figure; do not put one back.
     *
     * ## One scalar, and the question it has nowhere to put
     *
     * A recovery pays down whatever fault came before it, whether or not the two
     * are about the same thing. A drain waiting in `STOPPING` scores a recovery
     * without exercising the control endpoint or the save path at all, so it can
     * pay down a control-endpoint fault it says nothing about. Harmless while the
     * arm is a single fleet-wide *"this drain is not converging"* signal, which is
     * all it claims to be.
     *
     * It stops being harmless the moment somebody narrows the arm — per subsystem,
     * per failure reason, per step — because the first question that change has to
     * answer is **which fault a recovery is a recovery of**, and one integer offers
     * nowhere to write the answer. That is a re-shaping of this field and its
     * companion, not an extra branch at the escalation, and it is worth knowing
     * before rather than after.
     *
     * ## Why an `Int` here rather than a derived thing
     *
     * It has to survive a restart. Every alternative is a function of history the
     * status does not keep: `FailureStatus.attempts` is reset by the recovery, and
     * a list of past faults would be a log, on a type every consumer reads every
     * pass. One integer, monotone in the fact it reports, comparable by `equals`
     * so the reconciler's write-skip still works, and carrying nothing about who
     * was connected.
     *
     * **Not `require`d non-negative, deliberately.** A decode-time refusal on this
     * type is charged to the whole fleet read, and a hand-edited negative is worth
     * far less than a store that opens: the decode clamps to zero instead, which
     * is the value that cannot escalate. The floor that matters is enforced where
     * the subtraction happens.
     *
     * A row written before this field existed reads zero — the safe side, since a
     * drain cannot escalate on an arm it has no history for, and it starts
     * accumulating on its next fault.
     */
    val faultLedger: Int = 0,
    /**
     * When [faultLedger] last left zero, and null whenever it is zero.
     *
     * The count alone says *how much* has gone wrong and nothing about *over how
     * long*. Six consecutive aborts requeue on a backoff of one, two, four, eight
     * and sixteen seconds, so a single containerd blip or a proxy restart reaches
     * six inside half a minute — and raising the operator's one alert flag on a
     * thirty-second hiccup is the alarm fatigue this whole arm was built to avoid.
     * This is what lets the escalation ask for a count **and** a duration, so the
     * arm can only ever report something the age arm has already had its chance at.
     *
     * ## Not the set-once anchor this codebase has withdrawn twice
     *
     * `troubleSince` was declined and `enteredStateAt`-as-a-step-anchor was
     * removed, both for lifetime reasons: one was never cleared by anything on the
     * healthy path, the other was restamped by every park. The difference here is
     * that this field has **no lifetime of its own**. It is stamped and cleared by
     * the same arithmetic, in the same expression, at the same single funnel that
     * moves [faultLedger] — non-null exactly while the count is positive:
     *
     * ```
     * since = if (ledger == 0) null else (previous.faultLedgerSince ?: now)
     * ```
     *
     * So it cannot go stale while the thing it dates has gone, and it cannot be
     * restamped while that thing persists. The invariant
     * `(faultLedger > 0) == (faultLedgerSince != null)` is therefore maintained by
     * construction rather than by every writer remembering it, and there is exactly
     * one writer.
     *
     * The `?: now` is a self-repair rather than a fallback of the kind
     * [transferStartedAt] warns about, and the distinction is which way it errs. A
     * row carrying a positive count and no instant — an intermediate build, a hand
     * edit — is dated from the pass that noticed, which delays the report by one
     * threshold and never advances it. The alternative, treating the pair as
     * escalated, would report on evidence whose age nothing established.
     *
     * Needs no migration: absent is a legitimate value, it means null, and every
     * row V6 wrote carries a zero count beside it.
     */
    val faultLedgerSince: Instant? = null,
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
     * and the honest report is that the container keeps running and the drain
     * resumes on its own.
     *
     * **It does not mean the workload is joinable**, and this sentence used to say
     * it was. A workload that seals its own login path shuts that path *before* the
     * gate it is blocked on, so a blocked drain is often a blackout that resolves
     * precisely because nobody new can get in. Whether players can join is a
     * separate fact, and [DrainStatus.blocked]'s own message is where the
     * reconciler states it.
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
     * unreachable, and both facts are then true and worth reporting. A dashboard
     * that rendered the pair as one tri-state must show the attention flag
     * separately.
     *
     * `False` rather than `Unknown` on a server with no drain at all, for the same
     * reason [NEEDS_ATTENTION] is: "nothing is blocked" is something the loop
     * positively knows, and an alert that has to treat `Unknown` as quiet treats a
     * genuinely unreadable status as quiet too. [StatusCondition.lastTransitionAt]
     * is *blocked since when*, which is the number a dashboard puts beside it.
     *
     * True only while [DrainStatus.failure] is null, and a record carrying both is
     * reported as failed — the louder of the two. That pair is **produced on
     * purpose** rather than being a repaired document: a drain whose *permanent*
     * diagnosis is outstanding keeps it while it waits for players, because
     * somebody logging in does not resolve a world save that was never confirmed.
     * Waiting is then true and so is the fault, and the sentence this condition
     * carries — *waiting, not stuck, needs nobody* — is the one that must not be
     * shown.
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
 * What an authenticated control call to the proxy plugin did, on the pass that
 * wrote this status.
 *
 * Three values and not a `Boolean`, because "we did not ask" is a real state and
 * neither boolean answer describes it. A `Boolean` defaulting to `true` would
 * claim a credential was accepted on every status written before the field
 * existed, and on every pass that never made an authenticated call — a green
 * light nobody lit. Defaulting to `false` would report a credential problem on
 * the same rows, which is the "answering but not to us" alarm firing where
 * nothing was observed at all. [UNTESTED] is the honest default and it is what a
 * consumer must treat as *no evidence*, never as either verdict.
 */
public enum class ControlCredential {
    /**
     * No authenticated call was made, or none came back with a verdict.
     *
     * The ordinary reasons are: the handshake did not answer or reported an
     * incompatible protocol, so the pass stopped before authenticating; the
     * proxy is still starting; the row was written by a build that had no such
     * field. Claims nothing either way.
     */
    UNTESTED,

    /** An authenticated call was accepted. Establishes only that the credential is the right one. */
    ACCEPTED,

    /**
     * The endpoint answered and refused this orchestrator's credential.
     *
     * The state that made this field necessary: the control token's *coordinates*
     * are in the proxy's spec hash and its value is not, deliberately — so
     * rotating the secret behind the reference does not recreate the container.
     * The container keeps the token it was created with, `:core` starts sending
     * the new one, and the unauthenticated handshake keeps reporting
     * [ControlEndpointStatus.reachable] and [ControlEndpointStatus.compatible]
     * while every seal, transfer and deregistration in the fleet is refused. The
     * remedy is re-aligning the token, and it needs no definition edit.
     */
    REJECTED,
    ;

    /**
     * This verdict updated by a later one taken in the same pass.
     *
     * The whole rule is that [UNTESTED] is *no evidence* and therefore never
     * overwrites evidence: a call that established nothing — an endpoint that
     * stopped answering, a refusal carrying some other code — leaves what the
     * pass already learned alone, and anything else replaces it. Last verdict
     * wins among the calls that had one.
     *
     * It lives here rather than at either caller because a pass refines this in
     * two modules' worth of places — the routing sweep's authenticated calls and
     * the proxy drain's own admission assertions — and two copies of a merge rule
     * drift at whichever branch is added last. One rule, one home, one test.
     */
    public fun refinedBy(later: ControlCredential): ControlCredential = if (later == UNTESTED) this else later
}

/**
 * Whether `:core` can reach the shipped Velocity plugin, whether they speak the
 * same protocol, and whether the plugin accepts this orchestrator's credential.
 *
 * [pluginApiVersion] is what the endpoint reported, not what anybody declared —
 * [ControlEndpointSpec] explains why the spec does not pin it. [compatible] is
 * this build's verdict on that report, kept as its own field so a dashboard can
 * tell "did not answer" from "answered, wrong version": the remedies are
 * different and only one of them is "upgrade the proxy image".
 *
 * [credential] is the third such split, for the same reason. Answering is not
 * answering *to us*: the handshake route needs no token by design — that is what
 * lets a wrong credential be told from a wrong port — so [reachable] and
 * [compatible] can both be true on a proxy that refuses every call the drain
 * protocol is made of. Its remedy is neither "the proxy is down" nor "upgrade
 * the image", so it is neither of those two fields.
 *
 * All three are observations. Nothing here is copied from a definition.
 */
public data class ControlEndpointStatus(
    val reachable: Boolean,
    val pluginApiVersion: String? = null,
    val compatible: Boolean = false,
    val lastContactAt: Instant? = null,
    val credential: ControlCredential = ControlCredential.UNTESTED,
) {
    /**
     * **A presentation predicate. Never a gate.**
     *
     * What it answers is "should a reader be told something is wrong with this
     * endpoint", and its only permitted consumers are the
     * `CONTROL_ENDPOINT_READY` condition and `:api`'s renderers. A structural
     * test pins that list, because the sentence this KDoc used to lead with —
     * *"whether the drain protocol can be conducted through this endpoint"* — is
     * a standing invitation to write `if (control.usable)` before starting a
     * drain or choosing a destination, and under **that** caller the rule below
     * becomes "proceed on a fact nobody established".
     *
     * [ControlCredential.UNTESTED] counts as *not refused* rather than as *not
     * accepted*. That asymmetry is right for a badge — requiring
     * [ControlCredential.ACCEPTED] would light a red lamp on every starting proxy
     * and every pass that had no reason to authenticate, and a flag that is false
     * while nothing is wrong is the one that stops being read — and it is wrong
     * for a gate, which must require [ControlCredential.ACCEPTED] and read the
     * enum itself. The alarm-fatigue argument does not license the leniency; the
     * *narrowness of the consumer list* does, which is why the list is enforced
     * rather than described.
     *
     * The one derivation of the three fields, so the condition and the rendering
     * cannot drift apart by asking slightly different questions. Derived rather
     * than stored, so it cannot disagree with the fields it is computed from, and
     * so it stays out of `equals` — the loop's write-skip compares statuses
     * structurally and a derived property must not be able to make two equal
     * records unequal.
     */
    public val usable: Boolean
        get() = reachable && compatible && credential != ControlCredential.REJECTED
}

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

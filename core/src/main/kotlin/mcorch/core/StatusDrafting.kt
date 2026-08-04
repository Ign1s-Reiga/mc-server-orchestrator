package mcorch.core

import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainBlock
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureStatus
import mcorch.schema.ImageStatus
import mcorch.schema.PaperServerStatus
import mcorch.schema.PlayerOccupancy
import mcorch.schema.ResourceName
import mcorch.schema.RuntimeIdentity
import mcorch.schema.ServerEndpoint
import mcorch.schema.ServerPhase
import mcorch.schema.StatusCondition
import mcorch.schema.StorageStatus
import java.time.Instant
import kotlin.time.Duration

/**
 * Builds the observation a pass will record, carrying forward everything it did
 * not look at.
 *
 * Every field defaults to what was observed last pass, so a pass that only
 * learned one thing does not silently erase the rest — a status that loses its
 * [RuntimeIdentity] because the node was briefly unreachable is a status that
 * makes the next pass create a second container.
 *
 * [conditions] are *derived* rather than passed in. They are a second
 * expression of the same facts, and hand-writing them at each call site is how
 * they end up contradicting the fields they describe.
 */
@Suppress("LongParameterList")
internal fun draftStatus(
    previous: PaperServerStatus?,
    name: ResourceName,
    generation: Long,
    now: Instant,
    phase: ServerPhase,
    /**
     * How long a *retryable* failure may keep recurring before the escalation
     * says a human is needed — whether it is the drain's or the pass's. Passed in
     * rather than defaulted, because a default here would be a second copy of a
     * threshold the operator configures once.
     *
     * Still spelled `drainAttentionAfter` on [ReconcilerConfig]. Renaming a
     * configuration key to widen a derivation would break every operator's
     * config file for a doc comment, so the name stays and this says what it
     * means.
     */
    attentionAfter: Duration,
    ready: Boolean = false,
    image: ImageStatus? = previous?.image,
    runtime: RuntimeIdentity? = previous?.runtime,
    endpoint: ServerEndpoint? = previous?.endpoint,
    players: PlayerOccupancy? = previous?.players,
    storage: StorageStatus? = previous?.storage,
    drain: DrainStatus? = previous?.drain,
    failure: FailureStatus? = null,
): PaperServerStatus {
    val transitioned = previous == null || previous.phase != phase
    return PaperServerStatus(
        name = name,
        observedGeneration = generation,
        phase = phase,
        observedAt = now,
        lastTransitionAt = if (transitioned) now else previous.lastTransitionAt,
        ready = ready,
        image = image,
        runtime = runtime,
        endpoint = endpoint,
        players = players,
        storage = storage,
        drain = drain,
        failure = failure,
        conditions =
            deriveConditions(
                previous = previous?.conditions.orEmpty(),
                now = now,
                phase = phase,
                ready = ready,
                image = image,
                storage = storage,
                drain = drain,
                // The failure this pass is recording, so the escalation can see a
                // server the loop has stopped acting on that has no drain at all.
                // It is the same value being written to [PaperServerStatus.failure]
                // above rather than a second one derived here.
                failure = failure,
                attentionAfter = attentionAfter,
            ),
    )
}

@Suppress("LongParameterList")
private fun deriveConditions(
    previous: List<StatusCondition>,
    now: Instant,
    phase: ServerPhase,
    ready: Boolean,
    image: ImageStatus?,
    storage: StorageStatus?,
    drain: DrainStatus?,
    failure: FailureStatus?,
    attentionAfter: Duration,
): List<StatusCondition> {
    val draining = drain != null && drain.state != DrainState.DRAIN_FAILED
    // Asked of the drain rather than passed in from the pass that aborted it,
    // and the difference matters: a status is also drafted by passes that never
    // reached the drain at all — a node failure carries the drain forward
    // untouched — and a fact supplied by the caller would be absent on those,
    // flapping the condition off and on again between two passes of the same
    // stuck drain.
    val drainAttention = drain?.escalated(now, attentionAfter) == true
    // The second arm, and the reason this flag is no longer a drain flag.
    //
    // A drain is not the only way the loop stops being able to move a server.
    // `Reconciler.forbiddenTransition` records a **permanent** failure with no
    // drain at all and a phase of `RUNNING`: the loop will not probe it, observe
    // it or touch it again until an operator reverts the edit, and with only the
    // drain arm it sat in a fleet table looking like an ordinary running server.
    // That is the same defect as the terminating-badge one this flag was created
    // for, with the badge lying in the quieter direction — nobody looks.
    //
    // Discriminated against the drain's own failure because an aborted drain
    // records one event in both fields (`Reconciler.drain` copies it), and
    // folding that in would mean the *pass* arm, whose prose says the loop cannot
    // complete a pass, claimed a drain that had merely failed. `:api`'s
    // `ServerJson.passFailure` asks the identical question for the sentence it
    // renders; `DisplayConformanceTest` is what keeps the two answers the same.
    val passFailure = failure?.takeIf { it != drain?.failure }
    // Anchored on the failure's own first occurrence and never on
    // `drain.startedAt`: a node that goes unreachable during a long block would
    // already be past the threshold and fire on the first pass that saw it, which
    // is the alarm-fatigue outcome this threshold exists to prevent. Never
    // `lastTransitionAt` either — that is restamped by every phase change — and
    // never `attempts`, which counts passes rather than time.
    val passAttention =
        passFailure?.let { escalates(it.occurredAt, it.failureClass, now, attentionAfter) } == true
    val needsAttention = drainAttention || passAttention
    // Blocked *and* not failed. The two are disjoint by construction, so the
    // second half only decides for a stored document that has been repaired by
    // hand — and there the failure wins, because reporting the louder of the two
    // is the direction that does not leave somebody uncalled. It also makes the
    // three states a consumer has to tell apart mutually exclusive at the
    // condition level, which is what the dashboard reads.
    val drainBlocked = drain?.blocked != null && drain.failure == null
    val entries =
        listOf(
            condition(
                ConditionType.IMAGE_AVAILABLE,
                image?.available.toConditionStatus(),
                if (image?.available == true) "" else "the image has not been resolved on a node yet",
            ),
            condition(
                ConditionType.VOLUME_BOUND,
                storage?.bound.toConditionStatus(),
                if (storage?.persistent == false) "ephemeral storage: no volume is bound" else "",
            ),
            condition(
                ConditionType.CONTAINER_RUNNING,
                (phase == ServerPhase.RUNNING || phase == ServerPhase.DRAINING).toConditionStatus(),
                "",
            ),
            condition(ConditionType.READY, ready.toConditionStatus(), if (ready) "" else "not joinable"),
            // [drainAttention], deliberately not [needsAttention]. The second arm
            // is a fact about the *pass*, and a blocked drain beside an
            // unreachable node would otherwise be described here as "failing since
            // … and not recovering on its own". It is not failing; it is waiting,
            // and the loop not getting to it is a different sentence in a
            // different condition.
            condition(
                ConditionType.DRAINING,
                draining.toConditionStatus(),
                drain?.let { drainMessage(it, drainAttention) }.orEmpty(),
            ),
            // False rather than unknown for the same reason NEEDS_ATTENTION is
            // below: "nothing is blocked" is something the loop positively knows,
            // and a dashboard that has to read UNKNOWN as quiet reads an
            // unreadable status as quiet too. The message is the block's own,
            // which carries the counts an operator needs to see that people are
            // simply playing.
            condition(
                ConditionType.DRAIN_BLOCKED,
                drainBlocked.toConditionStatus(),
                if (drainBlocked) blockedMessage(drain?.blocked) else "",
            ),
            // False rather than unknown on a server with nothing wrong with it:
            // "no escalation is outstanding" is something the loop positively
            // knows, and an alert that has to treat UNKNOWN as quiet is an alert
            // that treats a genuinely unreadable status as quiet too.
            condition(
                ConditionType.NEEDS_ATTENTION,
                needsAttention.toConditionStatus(),
                if (needsAttention) attentionMessage(drain, drainAttention, passFailure) else "",
            ),
            condition(
                ConditionType.PLAYERS_EVACUATED,
                drain?.playersEvacuated.toConditionStatus(),
                "",
            ),
            // Deliberately *not* derived from `storage.lastSaveConfirmedAt`.
            // That field is a historical fact — the last time a save was ever
            // confirmed — and it is carried forward, so reading the condition
            // off it makes a server that has been running untouched for a week
            // report that its world is saved. The condition means "the world as
            // it is now is on disk", and the only thing that can say so is a
            // drain holding a confirmation it has not since voided.
            condition(
                ConditionType.WORLD_SAVED,
                (drain?.worldSaved == true).toConditionStatus(),
                worldSavedMessage(storage, drain),
            ),
        )
    return entries.map { entry ->
        val before = previous.firstOrNull { it.type == entry.first }
        // The transition time is when the condition *became* what it is, not
        // when the loop last ran — timeouts are measured from it.
        val transitionedAt = if (before != null && before.status == entry.second) before.lastTransitionAt else now
        StatusCondition(
            type = entry.first,
            status = entry.second,
            message = entry.third,
            lastTransitionAt = transitionedAt,
        )
    }
}

/**
 * The drain's state, and whether it has stopped being able to reach the end on
 * its own.
 *
 * This used to decide the second half by looking for a marker string at the
 * front of the failure message, because [DrainStatus] had nowhere to carry the
 * fact and a `NEEDS_ATTENTION` [ConditionType] did not exist. It does now, so
 * the escalation is a condition with its own transition time and this is only a
 * message. It remains a report either way: nothing branches on it, and a drain
 * that needs attention is still retried and still leaves its container running.
 */
private fun drainMessage(
    drain: DrainStatus,
    drainAttention: Boolean,
): String {
    val state = "drain state ${drain.state}"
    return if (drainAttention) {
        "$state, failing since ${drain.startedAt} and not recovering on its own"
    } else {
        state
    }
}

/**
 * What the drain is waiting for, and — as loudly — that waiting is the correct
 * behaviour.
 *
 * The sentence an operator reads first has to say *do not act*. This condition
 * appears on a server whose badge is `TERMINATING` or `DRAINING`, next to a
 * `drainState` of `DRAIN_FAILED`, which without prose reads as a server that has
 * given up. The remedy for that reading is not a quieter badge; it is telling
 * them what will happen next, which is nothing, by design, until people log off.
 *
 * `since` rather than a duration: the loop drafts a status on its own schedule, so
 * a rendered "waiting for 4 minutes" would be as stale as the last pass, whereas
 * an instant stays correct and the dashboard can count from it.
 */
private fun blockedMessage(block: DrainBlock?): String {
    if (block == null) return ""
    return "this drain is waiting, not stuck, and needs nobody: ${block.message}. Blocked since " +
        "${block.since}, re-checked ${block.observations} time(s); the container keeps running until it can " +
        "finish on its own."
}

/**
 * What a human is being called about.
 *
 * Says what will and will not happen next, because the honest answer is
 * unintuitive in both directions and they are opposite answers. Where the loop is
 * still retrying, the surprise is that it has *not* given up and is not going to
 * stop the server to unblock itself — an operator who reads "needs attention" as
 * "the orchestrator has stopped trying" may go and stop the container by hand.
 * Where the failure is permanent the surprise is the reverse: nothing further
 * will be attempted, so waiting achieves nothing.
 *
 * Every branch says the container is **not being stopped**, because that is the
 * fact a fleet view gets wrong — and it gets it wrong in *both* directions. A
 * failed drain ranks as `TERMINATING` in the dashboard's badge, which reads as on
 * its way out; a refused definition edit leaves the phase at `RUNNING`, which
 * reads as a perfectly ordinary server. Neither reader would guess that the loop
 * has stopped.
 *
 * Whether the server is also *joinable* is deliberately never claimed. Only a
 * probe that answered establishes that, the drain's own failure message says so
 * when it did, and one of the aborts covered here is reached precisely because
 * nothing could be confirmed about who is on the server.
 *
 * ## The three arms
 *
 * The drain arm is checked first and its sentence is unchanged, so a stuck drain
 * reads today exactly as it did before the flag widened. When a pass failure
 * escalates *as well*, `:api`'s `detail()` renders that one separately — its
 * precedence is the opposite, and deliberately, because there the question is
 * "what is true now" rather than "what is the worst thing outstanding".
 *
 * The two pass arms differ on whether there is a drain to talk about, and neither
 * says the drain failed: a drain that is merely blocked, on a server whose node
 * has gone away, has nothing wrong with it and the sentence must not imply
 * otherwise.
 */
private fun attentionMessage(
    drain: DrainStatus?,
    drainAttention: Boolean,
    passFailure: FailureStatus?,
): String =
    when {
        drainAttention -> drainAttentionMessage(drain)

        passFailure != null -> passAttentionMessage(drain, passFailure)

        // Unreachable: the condition is only true when one of the two arms is,
        // and both are covered above. "" rather than a claim, because a sentence
        // invented here would be the one thing on the status nothing derived.
        else -> ""
    }

/** Today's sentence, for a drain that cannot finish on its own. */
private fun drainAttentionMessage(drain: DrainStatus?): String {
    val since = drain?.startedAt?.let { " This drain has been running since $it." }.orEmpty()
    val next =
        if (drain?.failure?.failureClass == FailureClass.PERMANENT) {
            "Nothing further will be attempted until somebody resolves it; the container keeps running and " +
                "will not be stopped by the orchestrator."
        } else {
            "The loop keeps retrying and the container keeps running — it will not be stopped until a save " +
                "is confirmed."
        }
    return "this server needs a human: the drain cannot finish on its own.$since $next " +
        (drain?.failure?.message ?: "")
}

/**
 * The pass could not be completed, which is a different thing from the drain
 * having failed.
 *
 * Branched on the class as well as on whether a drain exists, for the reason the
 * drain arm is: "the loop has stopped acting on this server" is the honest
 * summary of a permanent failure and a false one for a retryable failure that has
 * simply been outstanding a long time. The loop *is* still retrying that one, and
 * telling an operator otherwise invites them to go and act on the container by
 * hand — which is the pressure this whole posture exists to remove.
 */
private fun passAttentionMessage(
    drain: DrainStatus?,
    failure: FailureStatus,
): String {
    val permanent = failure.failureClass == FailureClass.PERMANENT
    val lead =
        if (drain != null) {
            // Not "the drain failed". It may be perfectly healthy and simply
            // waiting; what has gone wrong is upstream of it.
            "this server needs a human: the loop cannot complete a pass for this server, so its drain is " +
                "not advancing; the container keeps running."
        } else if (permanent) {
            "this server needs a human: the loop has stopped acting on this server. The container is not " +
                "being stopped by the orchestrator."
        } else {
            "this server needs a human: the loop is not getting through to this server and is not recovering " +
                "on its own. The container is not being stopped by the orchestrator."
        }
    val next =
        if (permanent) {
            "Nothing further will be attempted until somebody resolves it."
        } else {
            "The loop is still retrying, and has been failing since ${failure.occurredAt}."
        }
    return "$lead $next ${failure.message}"
}

private fun worldSavedMessage(
    storage: StorageStatus?,
    drain: DrainStatus?,
): String =
    when {
        drain?.worldSaved == true -> {
            ""
        }

        storage?.persistent == false -> {
            "ephemeral storage: there is no world to save"
        }

        storage?.lastSaveConfirmedAt != null -> {
            "no save is confirmed for the world as it is now; the last confirmed save was at " +
                "${storage.lastSaveConfirmedAt}"
        }

        else -> {
            "no save has ever been confirmed for this server"
        }
    }

private fun condition(
    type: ConditionType,
    status: ConditionStatus,
    message: String,
): Triple<ConditionType, ConditionStatus, String> = Triple(type, status, message)

private fun Boolean?.toConditionStatus(): ConditionStatus =
    when (this) {
        true -> ConditionStatus.TRUE
        false -> ConditionStatus.FALSE
        null -> ConditionStatus.UNKNOWN
    }

package mcorch.core

import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
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
     * How long a drain may keep failing before its condition says a human is
     * needed. Passed in rather than defaulted, because a default here would be a
     * second copy of a threshold the operator configures once.
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
    attentionAfter: Duration,
): List<StatusCondition> {
    val draining = drain != null && drain.state != DrainState.DRAIN_FAILED
    // Asked of the drain rather than passed in from the pass that aborted it,
    // and the difference matters: a status is also drafted by passes that never
    // reached the drain at all — a node failure carries the drain forward
    // untouched — and a fact supplied by the caller would be absent on those,
    // flapping the condition off and on again between two passes of the same
    // stuck drain.
    val needsAttention = drain?.escalated(now, attentionAfter) == true
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
            condition(
                ConditionType.DRAINING,
                draining.toConditionStatus(),
                drain?.let { drainMessage(it, needsAttention) }.orEmpty(),
            ),
            // False rather than unknown on a server with nothing wrong with it:
            // "no escalation is outstanding" is something the loop positively
            // knows, and an alert that has to treat UNKNOWN as quiet is an alert
            // that treats a genuinely unreadable status as quiet too.
            condition(
                ConditionType.NEEDS_ATTENTION,
                needsAttention.toConditionStatus(),
                if (needsAttention) attentionMessage(drain) else "",
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
    needsAttention: Boolean,
): String {
    val state = "drain state ${drain.state}"
    return if (needsAttention) {
        "$state, failing since ${drain.startedAt} and not recovering on its own"
    } else {
        state
    }
}

/**
 * What a human is being called about.
 *
 * Says what will and will not happen next, because the honest answer is
 * unintuitive in both directions and they are opposite answers. For a drain the
 * loop is still retrying, the surprise is that it has *not* given up and is not
 * going to stop the server to unblock itself — an operator who reads "needs
 * attention" as "the orchestrator has stopped trying" may go and stop the
 * container by hand. For a drain that failed permanently the surprise is the
 * reverse: nothing further will be attempted, so waiting achieves nothing.
 *
 * The one thing both say is that the container is **still running and still
 * joinable**, because that is the fact a fleet view gets wrong — a failed drain
 * ranks as `TERMINATING` in the dashboard's badge, which reads as on its way out.
 */
private fun attentionMessage(drain: DrainStatus?): String {
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

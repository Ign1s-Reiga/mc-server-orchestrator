package mcorch.core

import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
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
            ),
    )
}

private fun deriveConditions(
    previous: List<StatusCondition>,
    now: Instant,
    phase: ServerPhase,
    ready: Boolean,
    image: ImageStatus?,
    storage: StorageStatus?,
    drain: DrainStatus?,
): List<StatusCondition> {
    val draining = drain != null && drain.state != DrainState.DRAIN_FAILED
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
                drain?.let { drainMessage(it) }.orEmpty(),
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
 * The drain's state, plus the escalation marker once it has been failing long
 * enough to need a human.
 *
 * The marker is read back off the failure message because [DrainStatus] has
 * nowhere else to carry it — a `NEEDS_ATTENTION` [ConditionType] is what this
 * wants and that lives in `:schema`. It is a report only: nothing branches on
 * it, and a drain that needs attention is still retried and still leaves its
 * container running.
 */
private fun drainMessage(drain: DrainStatus): String {
    val state = "drain state ${drain.state}"
    return if (drain.failure?.message?.startsWith(ATTENTION) == true) {
        "$ATTENTION $state, failing since ${drain.startedAt} and not recovering on its own"
    } else {
        state
    }
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

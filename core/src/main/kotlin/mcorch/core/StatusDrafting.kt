package mcorch.core

import mcorch.schema.BackendRoutingStatus
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.ControlEndpointStatus
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
import mcorch.schema.VelocityProxyStatus
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

/**
 * The same drafting for a [VelocityProxyStatus].
 *
 * A separate function rather than a generic one over [ServerStatus]: the two
 * statuses differ in their *fields*, not in how the shared ones are derived, and a
 * generic builder over a sealed hierarchy would need a branch per kind at every
 * assignment anyway. What is genuinely shared — the condition derivation, which is
 * where the escalation rule lives and where two copies would be a real hazard — is
 * shared, and [deriveConditions] is the one implementation.
 *
 * `storage` is synthesised rather than stored. [VelocityProxyStatus] has no
 * storage block on purpose — a nullable one would invite a reader to conclude "not
 * persistent yet" from an absence — but `VOLUME_BOUND` and `WORLD_SAVED` are in
 * the shared condition set and both have a true answer for a proxy: there is no
 * volume, and there is no world to save.
 */
@Suppress("LongParameterList")
internal fun draftProxyStatus(
    previous: VelocityProxyStatus?,
    name: ResourceName,
    generation: Long,
    now: Instant,
    phase: ServerPhase,
    attentionAfter: Duration,
    ready: Boolean = false,
    image: ImageStatus? = previous?.image,
    runtime: RuntimeIdentity? = previous?.runtime,
    endpoint: ServerEndpoint? = previous?.endpoint,
    players: PlayerOccupancy? = previous?.players,
    backends: BackendRoutingStatus? = previous?.backends,
    control: ControlEndpointStatus? = previous?.control,
    drain: DrainStatus? = previous?.drain,
    failure: FailureStatus? = null,
): VelocityProxyStatus {
    val transitioned = previous == null || previous.phase != phase
    val storage = StorageStatus(persistent = false, bound = runtime != null)
    return VelocityProxyStatus(
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
        backends = backends,
        control = control,
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
                failure = failure,
                attentionAfter = attentionAfter,
                proxy =
                    ProxyConditions(
                        backends = backends,
                        control = control,
                    ),
            ),
    )
}

/**
 * The two observations only a proxy can make, so the shared derivation can emit
 * their conditions without a `when` on the status type.
 *
 * Null for a `PaperServer`, and then neither condition is emitted at all. That is
 * the honest answer: a Paper server has no backend selector and no control
 * endpoint, so `BACKENDS_RESOLVED = Unknown` on one would be a condition about a
 * thing that does not exist.
 */
internal class ProxyConditions(
    val backends: BackendRoutingStatus?,
    val control: ControlEndpointStatus?,
)

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
    proxy: ProxyConditions? = null,
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
        ) + proxyEntries(proxy)
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
    // The failure's own first occurrence, not `drain.startedAt`. A drain that
    // waited four hours for players to log off and then hit a hiccup has not been
    // "failing since" it started; see `escalates` for the same correction on the
    // threshold this sentence accompanies.
    return if (drainAttention) {
        "$state, failing since ${drain.failure?.occurredAt} and not recovering on its own"
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
 * The drain arm is checked first, so a stuck drain reads as it always did. When a
 * pass failure escalates *as well*, `:api`'s `detail()` renders that one
 * separately — its precedence is the opposite, and deliberately, because there the
 * question is "what is true now" rather than "what is the worst thing
 * outstanding".
 *
 * The two pass arms differ on whether there is a drain to talk about, and neither
 * says the drain failed: a drain that is merely blocked, on a server whose node
 * has gone away, has nothing wrong with it and the sentence must not imply
 * otherwise.
 *
 * ## The one cell where ranking by arm is wrong
 *
 * Rank by arm everywhere **except** a retryable drain failure sitting beside a
 * permanent pass failure. That combination is reachable and it is the worst one
 * to get wrong: a `REPLACEMENT` drain failing retryably past the threshold, then a
 * permanent `NodeException` on the same pass. Ranking by arm renders *"The loop
 * keeps retrying…"* while `Reconciler.Pass.isBlockedByPermanentFailure` gates the
 * server off on the very next pass and nothing is ever attempted again. The pager
 * quotes this condition rather than `:api`'s `detail()`, so the sentence here is
 * the only thing an operator sees, and it tells them to wait.
 *
 * The rule is one-directional: a *permanent* drain failure still outranks a
 * permanent pass failure, because both then say "nothing further will be
 * attempted" and the drain's sentence carries the more specific remedy. Only the
 * retryable-drain-versus-permanent-pass cell moves.
 */
private fun attentionMessage(
    drain: DrainStatus?,
    drainAttention: Boolean,
    passFailure: FailureStatus?,
): String =
    when {
        drainAttention && !outrankedByPass(drain, passFailure) -> drainAttentionMessage(drain)

        passFailure != null -> passAttentionMessage(drain, passFailure)

        // A drain arm that lost the cell above with no pass failure to render is
        // not reachable — `outrankedByPass` is false whenever `passFailure` is
        // null — but the drain sentence is the honest fallback rather than "".
        drainAttention -> drainAttentionMessage(drain)

        // Unreachable: the condition is only true when one of the two arms is,
        // and both are covered above. "" rather than a claim, because a sentence
        // invented here would be the one thing on the status nothing derived.
        else -> ""
    }

/**
 * Whether the failure recorded on the *pass* has to be the one reported, even
 * though the drain arm is also raised.
 *
 * True for exactly one cell of the class matrix: the drain's failure is
 * [FailureClass.RETRYABLE] and the pass's is [FailureClass.PERMANENT]. See
 * [attentionMessage] for why that cell alone moves.
 */
private fun outrankedByPass(
    drain: DrainStatus?,
    passFailure: FailureStatus?,
): Boolean =
    passFailure?.failureClass == FailureClass.PERMANENT &&
        drain?.failure?.failureClass == FailureClass.RETRYABLE

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
            //
            // The closing clause is mandatory here as it is in every other arm,
            // and this is the arm where it is least optional: a reader who has
            // just been told a drain exists and is not advancing has positive
            // reason to believe a stop is imminent. It is the one arm the clause
            // was missing from.
            "this server needs a human: the loop cannot complete a pass for this server, so its drain is " +
                "not advancing; the container keeps running and is not being stopped by the orchestrator."
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

/**
 * The proxy-only conditions, and nothing at all for anything else.
 *
 * `BACKENDS_RESOLVED` false is **not** a failure — the proxy is running and an
 * operator may simply not have labelled a server yet — but it is the answer to
 * "why can nobody join". A selector that matches nothing cannot be caught at parse
 * time: it is checked against definitions the parse never sees.
 *
 * `CONTROL_ENDPOINT_READY` false means seal, transfer and deregister are
 * unavailable, which means **no backend behind this proxy can complete a drain**.
 * It keeps "did not answer" apart from "answered, wrong version" in its message,
 * because only one of those two has "upgrade the proxy image" as its remedy.
 */
private fun proxyEntries(proxy: ProxyConditions?): List<Triple<ConditionType, ConditionStatus, String>> {
    if (proxy == null) return emptyList()
    val backends = proxy.backends
    val control = proxy.control
    val resolved = backends != null && backends.matched > 0
    val endpointReady = control != null && control.reachable && control.compatible
    return listOf(
        condition(
            ConditionType.BACKENDS_RESOLVED,
            if (backends == null) ConditionStatus.UNKNOWN else resolved.toConditionStatus(),
            when {
                backends == null -> {
                    "the selector has not been resolved yet"
                }

                !resolved -> {
                    "spec.backends.selector matches no server. Nobody can join through this proxy until a " +
                        "server carries the labels it names"
                }

                else -> {
                    "${backends.registered} of ${backends.matched} matched server(s) are in the routing " +
                        "table; ${backends.destinations} can receive a transfer"
                }
            },
        ),
        condition(
            ConditionType.CONTROL_ENDPOINT_READY,
            if (control == null) ConditionStatus.UNKNOWN else endpointReady.toConditionStatus(),
            when {
                control == null -> {
                    "the control endpoint has not been contacted yet"
                }

                !control.reachable -> {
                    "the control endpoint did not answer, so no backend behind this proxy can be sealed, " +
                        "transferred or deregistered — which means none of them can complete a drain"
                }

                !control.compatible -> {
                    "the plugin answered and speaks control protocol " +
                        "`${control.pluginApiVersion}`, which this build does not. Upgrade the proxy image to " +
                        "one built from this revision; until then no backend behind this proxy can complete a " +
                        "drain"
                }

                else -> {
                    ""
                }
            },
        ),
    )
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

package mcorch.core.termination

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.NodeRegistry
import mcorch.core.StopGrace
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadState
import mcorch.core.dispatchingStop
import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.SaveOutcome
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.PaperServerDefaults
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.store.Store
import mcorch.store.StoreException
import mcorch.store.WriteOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration

/**
 * Stopping a server whose drain cannot finish.
 *
 * `spec/termination/02-force-stop.md`. The escape hatch for `docs/operating.md`
 * note 1: a persistent server whose world save cannot be confirmed, whose drain
 * therefore aborts, and which otherwise can only be retired by hand.
 *
 * ## Why this is not a change to the drain
 *
 * `DrainPass.cause` carries a rule — *"what a drain does is the same whatever
 * asked for it, and a cause consulted at a gate is how a delete comes to take a
 * path a replacement was written for"* — and `DrainStatus.mayStop` is guarded by
 * a comment about *"a future edit that routes into the stop without a current
 * save"*. Force-as-exemptions inside the controller is what both refuse.
 *
 * ## What that isolation costs
 *
 * `mayStop` is not the drain's protection. It is the **last link** of one: the
 * proxy seal, a secured destination, the transfer, `requireEmpty`'s freshly
 * observed zero, the save, and the deregistration all come first. Weakening
 * `mayStop` under a flag would have dropped one of those. Writing a separate path
 * drops all of them *by construction*, and silently, because there is no shared
 * code for a test to diff against.
 *
 * So the isolation is only defensible if this path re-states what it is dropping.
 * It does so as follows, and the list is the design rather than a caveat:
 *
 * | Drain step | Here |
 * |---|---|
 * | Seal the proxy (2) | **Not done.** The loop's next pass deregisters; until then the proxy may route joins at a dead address |
 * | Transfer players (4) | **Not done.** Players are disconnected |
 * | `requireEmpty` — zero players (5) | Replaced by two probes and a counted acknowledgement. See below |
 * | Save (5) | Requested, and **[ForcedStopOutcome.saveAttempted] says whether it really was** |
 * | Deregister (6) | **Not done.** As the seal |
 * | `mayStop` (7) | Deliberately bypassed. That is the feature |
 *
 * ## Why the occupancy is read twice
 *
 * `requireEmpty`'s zero is durable because the seal holds it: every drain state
 * from `SEALED` onward re-asserts `holdSeal`, so nobody can join between the
 * observation and the stop. There is no seal here — the table above says so — and
 * a single probe therefore decays the instant it is taken. The gap between the
 * first probe and the stop is a whole `saveTimeout` wide, which `SpecBounds` caps
 * at an hour.
 *
 * The branch that made this urgent is the one that looks safest: a server probing
 * zero needs **no acknowledgement at all**, so an hour-long window on that branch
 * is a stop that drops live sessions while reporting `playersOnline: 0` and having
 * asked nobody anything. So the count is read again immediately before the stop,
 * and it is the second reading that decides — and that the outcome reports.
 *
 * ## The acknowledgement is a count, not a flag
 *
 * A boolean says *"proceed regardless"*, which is not an acknowledgement of
 * anything: it cannot notice that the population changed between the operator
 * deciding and the request arriving, and it does not require them to have seen a
 * number at all. Worse, this endpoint's own target population — a wedged server —
 * does not answer a Server List Ping, so a boolean would be mandatory on
 * essentially every legitimate use and would become a fixed string in every
 * runbook within a week. A confirmation that fires on every correct invocation has
 * been designed into noise, and the one case where it carries information is then
 * indistinguishable from the routine one.
 *
 * So [OccupancyAcknowledgement] is a compare-and-swap: the caller states the
 * number they were shown, and a different number refuses. `Unreadable` is a
 * distinct value rather than a wildcard, so acknowledging a wedged server cannot
 * silently cover a server that answered with players on it.
 *
 * ## The save is *requested*, and three branches never send one
 *
 * An earlier version of this file claimed the save is "always requested and always
 * waited out". That was false, and the branches it was false on are the ones this
 * feature exists for: [SaveOutcome.Unconfirmable] returns before an exec is even
 * built when the container has no save channel — verbatim the note-1 population —
 * and an unbuildable `saveTimeout` returns just as fast.
 *
 * So the outcome carries [ForcedStopOutcome.saveAttempted] beside `saveConfirmed`,
 * and the branches are decided one at a time rather than collapsed into "not
 * confirmed".
 *
 * ## Nothing here refuses for a reason a tombstone would freeze
 *
 * [stop] runs after the caller has written the tombstone, and a tombstoned
 * definition cannot be edited — `SqliteStore.putDefinition` answers
 * `ConflictReason.TERMINATING` for any write to a row with `deleted_at` set, and
 * nothing un-tombstones it. So a refusal from [stop] whose remedy is *"change the
 * definition and force again"* strands the server permanently: undrainable,
 * unforceable, and reachable only with `crictl` — which is the state this feature
 * exists to eliminate.
 *
 * An earlier version had two of those. They are gone. Everything decidable from
 * the definition is now refused by [preflight], which runs **before** the
 * tombstone; [stop] refuses only for reasons the caller can answer by re-sending
 * the same request — a changed player count, or a node that would not take the
 * stop. In particular a grace period below [SHUTDOWN_SAVE_ALLOWANCE] no longer
 * refuses at all, it is **raised**: see [SHUTDOWN_SAVE_ALLOWANCE].
 */
public interface ForcedTermination {
    /**
     * Every refusal that is decidable before anything has been written.
     *
     * Called with the definition still intact and still editable, so a refusal
     * here costs the caller nothing and can honestly say *"correct that and force
     * again"*. Returns normally when there is no workload at all: that is not a
     * refusal, it is an ordinary delete.
     *
     * @throws ForcedTerminationRefused when stopping would be indefensible.
     */
    public suspend fun preflight(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    )

    /**
     * Saves what can be saved, then stops the container.
     *
     * The definition must already be tombstoned. This does not delete it: the
     * teardown that frees the name belongs to the reconcile loop.
     *
     * Re-runs the occupancy check itself rather than trusting [preflight]'s, both
     * because the population moves and because a seam whose safety depends on
     * having been called in the right order has none.
     *
     * @throws ForcedTerminationUnavailable when there is no running workload.
     * @throws ForcedTerminationRefused only for reasons re-sending can answer.
     */
    public suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ): ForcedStopOutcome
}

/**
 * What the caller says they were shown about who is online.
 *
 * A compare-and-swap rather than a flag — see the class KDoc on
 * [ForcedTermination]. [Unreadable] is a value of its own and not a wildcard: it
 * matches a probe that did not answer and nothing else.
 */
public sealed interface OccupancyAcknowledgement {
    /** Nothing was acknowledged. Only a freshly observed zero proceeds under this. */
    public data object None : OccupancyAcknowledgement

    /** The caller was shown this many players online and still wants the stop. */
    public data class Count(
        val players: Int,
    ) : OccupancyAcknowledgement

    /** The caller was shown that the count could not be read at all, and still wants the stop. */
    public data object Unreadable : OccupancyAcknowledgement
}

/**
 * What a forced stop did.
 *
 * [saveAttempted] and [saveConfirmed] are separate because three `requestSave`
 * branches return without sending anything, and "not confirmed" would report them
 * identically to a save that was issued and timed out. They are the two halves an
 * audit record needs to tell "retired a stuck server" from "lost a world".
 *
 * [playersOnline] is the count read **immediately before the stop**, not the one
 * the caller acknowledged: an audit record of who was dropped has to be from the
 * instant they were dropped. It is null when that probe did not answer. **Null is
 * not zero** — reading it as zero is how this path would come to stop a populated
 * server while reporting that it did not.
 */
public data class ForcedStopOutcome(
    val saveAttempted: Boolean,
    val saveConfirmed: Boolean,
    val playersOnline: Int?,
    /** Operator-facing, and never a player name or an address. */
    val detail: String,
)

/** There is no running workload to stop. */
public class ForcedTerminationUnavailable(
    message: String,
) : Exception(message)

/** Stopping would be indefensible, and a human can fix the reason. */
public class ForcedTerminationRefused(
    message: String,
) : Exception(message)

/** [ForcedTermination] over the nodes this orchestrator knows. */
public class NodeForcedTermination(
    private val nodes: NodeRegistry,
    private val store: Store,
    private val clock: Clock = Clock.systemUTC(),
) : ForcedTermination {
    override suspend fun preflight(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ) {
        val name = definition.metadata.name
        refuseSecondSideEffect(name)

        // Decidable from the definition alone, and refused here rather than from
        // `requestSave`'s `unbuildableSave` — whose own wording is "correct that
        // field and the drain carries on from here", advice that is dead the
        // moment it is quoted from below a tombstone.
        val saveTimeout = definition.spec.lifecycle.drain.saveTimeout
        if (!saveTimeout.isPositive() || saveTimeout.isInfinite()) {
            throw ForcedTerminationRefused(
                "`${name.value}` has a spec.lifecycle.drain.saveTimeout of $saveTimeout, which is not a " +
                    "duration a save command can be run with — so no world save could be requested before " +
                    "the stop. Correct that field and force again",
            )
        }

        // No workload is not a refusal: the caller's delete stands on its own and
        // the loop tears down a stopped container without any of this.
        val (node, observation) = locateOrNull(name) ?: return
        refuseOccupancy(name, occupancy(PaperServerAgent(definition), node, observation), acknowledgement)
    }

    override suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ): ForcedStopOutcome {
        val name = definition.metadata.name
        val (node, observation) = locate(name)
        val agent = PaperServerAgent(definition)

        // Re-asserted here and not left to `preflight`. It lived in `:api` until
        // round 51, where it protected this route and nothing else: a second caller
        // of this seam got none of it, and the KDoc excusing that claimed a
        // "binding protection … which observes the container rather than a status
        // row". There was no such protection. `WorkloadState` reports `RUNNING` and
        // says nothing about whether a save is outstanding.
        refuseSecondSideEffect(name)
        refuseOccupancy(name, occupancy(agent, node, observation), acknowledgement)

        val save = requestSave(agent, node, observation)
        val attempted = save !is SaveOutcome.Unconfirmable && save !is SaveOutcome.NotDelivered
        val confirmed = save is SaveOutcome.Confirmed

        // The reading that decides. The one above is a whole `saveTimeout` old by
        // now, and without a seal nothing held it — see "Why the occupancy is read
        // twice". Refusing here is recoverable: the caller re-sends with the number
        // this refusal names.
        val players = occupancy(agent, node, observation)
        refuseOccupancy(name, players, acknowledgement)

        val declared = definition.spec.lifecycle.stopGracePeriod
        // Raised, never lowered, and never a refusal. When no save request was
        // sent the grace period stops being a last-resort net and becomes the
        // *entire* save, so it gets at least what this project's own model says a
        // save takes. A server that finishes early exits early — the grace period
        // is a ceiling on containerd's patience, not a wait.
        val requested = if (attempted) declared else maxOf(declared, SHUTDOWN_SAVE_ALLOWANCE)
        val grace = StopGrace.of(requested, definition.spec.lifecycle.drain.saveTimeout)

        LOG.warn(
            "forced stop server={} saveAttempted={} saveConfirmed={} playersOnline={} gracePeriodSeconds={} " +
                "— this bypasses the drain's evidence rule",
            name.value,
            attempted,
            confirmed,
            players ?: "unknown",
            grace.period.inWholeSeconds,
        )
        // **Stamped before the call, and this is the record the first three
        // versions of this file did not keep.** `DrainStatus.stopDispatchedAt` is
        // what `stopIsInFlight` answers on, and a stop nobody recorded leaves that
        // predicate false for the whole grace period: the loop's next pass sees a
        // `RUNNING` container under a terminating definition, takes it for a drain
        // that has not started, and walks the ladder — seal, destination, transfer,
        // `requireEmpty`, **save** — into a process already running its shutdown
        // save. The only thing that had been standing between that and a second
        // `save-all flush` was whether a dying server still answers a ping with
        // zero, which is a coincidence and not a guard.
        //
        // Before rather than after, for the reason the field's own KDoc gives:
        // over-reporting costs availability that recovers on its own, losing the
        // record costs a player's session and no later pass repairs it. Under
        // `NonCancellable` for the same asymmetry — a shutdown landing between the
        // write and the stop must not be able to drop it.
        recordStopDispatched(name)
        try {
            node.stopWorkload(observation.handle, grace)
        } catch (failure: NodeException) {
            // Caught so the save result is not lost with it. A caller told only
            // "500" re-fires, which would send a second `save-all flush` into a
            // server that already took one.
            throw ForcedTerminationRefused(
                "the save reported ${describe(attempted, confirmed)}, and the stop was then refused by the " +
                    "node: ${failure.message}. Nothing was stopped",
            )
        }

        return ForcedStopOutcome(
            saveAttempted = attempted,
            saveConfirmed = confirmed,
            playersOnline = players,
            detail = describe(attempted, confirmed),
        )
    }

    /**
     * Refuses a force into a drain that already has a save or a stop outstanding.
     *
     * A dispatched stop means the container is inside its grace period running its
     * shutdown save; forcing finds it `RUNNING`, sends another `save-all flush`
     * into that, and stops it again. `saveRequestedAt` is the same hole one step
     * earlier — it is non-null exactly while a request has gone out and has not
     * been confirmed, which is the drain's never-re-send wedge armed.
     *
     * **Advisory, and the limits are stated rather than argued away.** The read is
     * a moment before the seam acts, so the loop may dispatch in between; and a
     * status that will not decode is indistinguishable here from one not yet
     * written, so both fall to the permissive side. Refusing on the unreadable case
     * would block every force against a server the loop has not reached, which
     * trades a narrow hole for a wide one. What makes a *repeat* of this path safe
     * is [recordStopDispatched], not this.
     */
    private suspend fun refuseSecondSideEffect(name: ResourceName) {
        val drain = (store.getServer(name)?.status?.status as? PaperServerStatus)?.drain ?: return
        if (drain.stopDispatchedAt != null) {
            throw ForcedTerminationRefused(
                "`${name.value}` already has a stop in flight, dispatched at ${drain.stopDispatchedAt}. It is " +
                    "inside its grace period; forcing again would send a second save into a server already " +
                    "shutting down",
            )
        }
        if (drain.saveRequestedAt != null) {
            throw ForcedTerminationRefused(
                "`${name.value}` has an unconfirmed world save outstanding, requested at " +
                    "${drain.saveRequestedAt}. Forcing now would send a second save into a server already " +
                    "running one; wait for it to confirm or fail",
            )
        }
    }

    /**
     * Records that a `SIGTERM` is about to leave this process.
     *
     * The same record `DrainController.stop` keeps, written the same way round, so
     * that `stopIsInFlight` answers the same for a forced stop as for a drained
     * one. This is **observed** state and not desired: it says what happened, and
     * the definition it hangs off is already tombstoned by the caller.
     *
     * A server with no drain status yet gets one in `STOPPING`, because that is
     * what is true — a stop has been dispatched and nothing else about a drain has.
     * The state is not a claim that the ladder above it ran; the stamp beside it is
     * what readers gate on, and `DrainStatus.stopDispatchedAt` says so: *"a producer
     * of this state that dispatched nothing writes no stamp, so `STOPPING` is not
     * evidence of a request having gone out and the stamp is."*
     *
     * A failed write is logged and not raised. The stop still has to happen — the
     * caller has tombstoned the definition and this path is the last resort — and
     * an exception here would leave a server that could only be retired by hand,
     * which is the state this file exists to remove. What it costs is the record,
     * and the log line is the compensation available.
     */
    private suspend fun recordStopDispatched(name: ResourceName) {
        withContext(NonCancellable) {
            try {
                val stored = store.getServer(name) ?: return@withContext
                val now = clock.instant()
                // Only ever an *edit* of an observation the loop already made.
                // Drafting one from nothing would mean this path inventing a
                // `phase`, an `observedGeneration` and an `observedAt` for a
                // container it looked at once — an observation the loop did not
                // make, written into the field the loop reads to decide what to do
                // next. `draftStatus` is the one thing allowed to author those, and
                // it belongs to a reconcile pass.
                //
                // The gap this leaves is a server the loop has never observed. It is
                // narrow — a force needs a `RUNNING` container, which the loop
                // created — and the log line below is what covers it.
                val status =
                    stored.status?.status as? PaperServerStatus
                        ?: run {
                            LOG.error(
                                "no observation to record a forced stop dispatch against server={} — the loop " +
                                    "may start a drain over a container already shutting down",
                                name.value,
                            )
                            return@withContext
                        }
                val next = status.copy(drain = status.drain?.dispatchingStop(now) ?: forcedStopDrain(now))
                when (val outcome = store.putStatus(next)) {
                    is WriteOutcome.Applied -> {
                        Unit
                    }

                    is WriteOutcome.Conflict -> {
                        LOG.error(
                            "could not record a forced stop dispatch server={} reason={} — the loop may start a " +
                                "drain over a container already shutting down",
                            name.value,
                            outcome,
                        )
                    }
                }
            } catch (failure: StoreException) {
                LOG.error(
                    "could not record a forced stop dispatch server={} — the loop may start a drain over a " +
                        "container already shutting down",
                    name.value,
                    failure,
                )
            }
        }
    }

    private fun forcedStopDrain(now: Instant): DrainStatus =
        DrainStatus(
            // The state and the record are written in one expression, which is what
            // `StatusReconstruction`'s decode rule needs of every producer of this
            // state: it reconstructs a missing `stopDispatchedAt` from
            // `enteredStateAt` for anything sitting in `STOPPING` without one, and a
            // producer that leaves the record off makes that reconstruction a guess.
            // Here there is nothing to reconstruct — the stamp is set on the same
            // line as everything else, from the same instant.
            state = DrainState.STOPPING,
            startedAt = now,
            enteredStateAt = now,
            stopDispatchedAt = now,
        )

    /**
     * Refuses unless the acknowledgement matches what was just observed.
     *
     * A freshly observed zero needs no acknowledgement — there is nobody to
     * acknowledge. Everything else has to match exactly, including the difference
     * between "twelve players" and "could not tell".
     */
    private fun refuseOccupancy(
        name: ResourceName,
        observed: Int?,
        acknowledgement: OccupancyAcknowledgement,
    ) {
        if (observed == 0) return
        val matches =
            when (acknowledgement) {
                is OccupancyAcknowledgement.None -> false
                is OccupancyAcknowledgement.Unreadable -> observed == null
                is OccupancyAcknowledgement.Count -> observed == acknowledgement.players
            }
        if (matches) return
        throw ForcedTerminationRefused(
            when (observed) {
                null -> {
                    "`${name.value}` did not answer a player count, so it cannot be shown to be empty. " +
                        "Forcing would disconnect anybody on it without transferring them; re-send " +
                        "acknowledging the occupancy as unreadable if that is intended"
                }

                else -> {
                    "`${name.value}` has $observed player(s) online. Forcing disconnects them without a " +
                        "transfer; re-send acknowledging exactly $observed if that is intended"
                }
            },
        )
    }

    /**
     * One save request, retried once when the first provably never went out.
     *
     * Narrower than [SaveOutcome.NotDelivered]'s own contract, deliberately. That
     * type says *"the request never went out, safe to try again later"*, and the
     * agent's `else ->` arm carries the same KDoc's admission that for a
     * `NodeException` subclass this is an **assumption**: a `RuntimeFailure` on an
     * `ExecSync` arrives as `Busy` and says nothing about whether the command ran.
     * On the drain that assumption costs one extra flush a pass apart. Here it
     * would cost two flushes back to back into a main thread that may be running
     * the first, followed by a `SIGTERM` — so only `neverDispatched` is retried.
     *
     * [SaveOutcome.Unconfirmed] is never retried: its own contract is *"the request
     * counts as issued: do not send it again"*.
     */
    private suspend fun requestSave(
        agent: PaperServerAgent,
        node: Node,
        observation: WorkloadObservation.Present,
    ): SaveOutcome {
        val first = attemptSave(agent, node, observation)
        if (first is SaveOutcome.NotDelivered && first.retryable && first.neverDispatched) {
            return attemptSave(agent, node, observation)
        }
        return first
    }

    private suspend fun attemptSave(
        agent: PaperServerAgent,
        node: Node,
        observation: WorkloadObservation.Present,
    ): SaveOutcome =
        try {
            agent.requestSave(node, observation)
        } catch (failure: NodeException) {
            SaveOutcome.NotDelivered("the save could not be attempted: ${failure.message}", retryable = false)
        }

    /** Players online, or null when nothing answered. Null is never read as zero. */
    private suspend fun occupancy(
        agent: PaperServerAgent,
        node: Node,
        observation: WorkloadObservation.Present,
    ): Int? =
        try {
            when (val probe = agent.probe(node, observation.handle)) {
                is ProbeOutcome.Joinable -> probe.online
                is ProbeOutcome.Unanswered -> null
            }
        } catch (failure: NodeException) {
            LOG.debug("occupancy probe failed for a forced stop", failure)
            null
        }

    private fun describe(
        attempted: Boolean,
        confirmed: Boolean,
    ): String =
        when {
            confirmed -> {
                "the world save was confirmed before the container was stopped"
            }

            attempted -> {
                "a world save was requested and never confirmed; unsaved play since the last successful " +
                    "save may be lost"
            }

            else -> {
                "no world save could be sent — the container has no channel that could confirm one — so the " +
                    "stop grace period was the only chance the world had to reach disk"
            }
        }

    private suspend fun locate(name: ResourceName): Pair<Node, WorkloadObservation.Present> =
        locateOrNull(name) ?: throw ForcedTerminationUnavailable("`${name.value}` has no workload on any node")

    private suspend fun locateOrNull(name: ResourceName): Pair<Node, WorkloadObservation.Present>? {
        for (node in nodes.nodes()) {
            val observation =
                try {
                    node.observe(name)
                } catch (unreachable: NodeException) {
                    LOG.debug("node {} could not be asked about {}", node.name, name.value, unreachable)
                    continue
                }
            val present = observation as? WorkloadObservation.Present ?: continue
            if (present.state != WorkloadState.RUNNING) {
                throw ForcedTerminationUnavailable(
                    "`${name.value}` is not running, so there is no container to stop",
                )
            }
            return node to present
        }
        return null
    }

    private companion object {
        /**
         * How long a Paper server is given to save on `SIGTERM` when no save
         * request reached it. A **floor on the grace period**, applied by raising
         * it — never a reason to refuse.
         *
         * `PaperServerDefaults.SAVE_TIMEOUT` and not a number of this file's own,
         * because that is already this project's model of how long a world save
         * takes: it is what the drain waits for a save it can watch. The branch
         * this applies to is the one where the grace period does *all* the work
         * with nothing watching, so accepting less here than the drain accepts
         * there would put the lower bar on the more dangerous path. The schema's
         * own minimum is `saveTimeout + 30s`, which permits as little as 31s.
         *
         * An earlier version refused instead of raising, and paired that with a
         * second argument to `StopGrace.of` that does nothing:
         * `StopGraceCeiling.ceilingFor` cannot return below `MAX` (two hours) for
         * any argument, and `SpecBounds` already caps `stopGracePeriod` there, so
         * `StopGrace.of(declared, x)` returns `declared` for every `x` a definition
         * can carry. The floor argument is passed as `saveTimeout` here for the
         * same reason the reconcile path passes it — both halves off one
         * definition, which is the property `StopGrace.of`'s KDoc asks of its
         * callers — and the raising is done where it can be read, above.
         */
        private val SHUTDOWN_SAVE_ALLOWANCE: Duration = PaperServerDefaults.SAVE_TIMEOUT

        private val LOG = LoggerFactory.getLogger(NodeForcedTermination::class.java)
    }
}

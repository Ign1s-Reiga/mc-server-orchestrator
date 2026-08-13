package mcorch.core.termination

import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.NodeRegistry
import mcorch.core.StopGrace
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadState
import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.SaveOutcome
import mcorch.schema.PaperServerDefinition
import mcorch.schema.ResourceName
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * ## What that isolation costs, which the first version of this file got wrong
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
 * | `requireEmpty` — zero players (5) | Replaced by [probe] plus an explicit acknowledgement from the caller |
 * | Save (5) | Requested, and **[ForcedStopOutcome.saveAttempted] says whether it really was** |
 * | Deregister (6) | **Not done.** As the seal |
 * | `mayStop` (7) | Deliberately bypassed. That is the feature |
 *
 * ## The save is *requested*, and three branches never send one
 *
 * An earlier version of this file claimed the save is "always requested and always
 * waited out". That was false, and the branches it was false on are the ones this
 * feature exists for: [SaveOutcome.Unconfirmable] returns before an exec is even
 * built when the container has no save channel — verbatim the note-1 population —
 * and an unbuildable `saveTimeout` returns just as fast.
 *
 * So the outcome now carries [ForcedStopOutcome.saveAttempted] beside
 * `saveConfirmed`, and the branches are decided one at a time rather than
 * collapsed into "not confirmed".
 */
public interface ForcedTermination {
    /**
     * Saves what can be saved, then stops the container.
     *
     * The definition must already be tombstoned. This does not delete it: the
     * teardown that frees the name belongs to the reconcile loop.
     *
     * @param acknowledgeOccupancy the caller has been told the occupancy and still
     *   wants the stop. Required whenever the server is populated **or its
     *   occupancy could not be read** — an unanswered probe is unknown, never
     *   zero, which is the rule `ProbeOutcome.Unanswered` states for the drain and
     *   which applies here for the same reason.
     * @throws ForcedTerminationUnavailable when there is no running workload.
     * @throws ForcedTerminationRefused when stopping would be indefensible: an
     *   unacknowledged population, a `saveTimeout` no command can run with, or a
     *   grace period too short to be the save it would become.
     */
    public suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgeOccupancy: Boolean,
    ): ForcedStopOutcome
}

/**
 * What a forced stop did.
 *
 * [saveAttempted] and [saveConfirmed] are separate because three `requestSave`
 * branches return without sending anything, and "not confirmed" would report them
 * identically to a save that was issued and timed out. They are the two halves an
 * audit record needs to tell "retired a stuck server" from "lost a world".
 *
 * [playersOnline] is null when the probe did not answer. **Null is not zero** —
 * reading it as zero is how this path would come to stop a populated server while
 * reporting that it did not.
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
) : ForcedTermination {
    override suspend fun stop(
        definition: PaperServerDefinition,
        acknowledgeOccupancy: Boolean,
    ): ForcedStopOutcome {
        val name = definition.metadata.name
        val (node, observation) = locate(name)
        val agent = PaperServerAgent(definition)

        val players = occupancy(agent, node, observation)
        if (players != 0 && !acknowledgeOccupancy) {
            throw ForcedTerminationRefused(
                when (players) {
                    null -> {
                        "`${name.value}` did not answer a player count, so it cannot be shown to be empty. " +
                            "Forcing would disconnect anybody on it without transferring them; " +
                            "re-send with the occupancy acknowledged if that is intended"
                    }

                    else -> {
                        "`${name.value}` has $players player(s) online. Forcing disconnects them without a " +
                            "transfer; re-send with the occupancy acknowledged if that is intended"
                    }
                },
            )
        }

        val save = requestSave(agent, node, observation)
        // `NotDelivered` that is *not* retryable is `unbuildableSave`: the field is
        // wrong and nothing was sent. Refusing costs the operator one edit and is
        // the difference between a stop with no save attempt and a stop that could
        // have had one.
        if (save is SaveOutcome.NotDelivered && !save.retryable) {
            throw ForcedTerminationRefused(
                "no world save could be requested for `${name.value}`: ${save.detail}",
            )
        }
        val attempted = save !is SaveOutcome.Unconfirmable && save !is SaveOutcome.NotDelivered
        val confirmed = save is SaveOutcome.Confirmed

        // The floor the grace period is bounded against. When a save really was
        // issued, `saveTimeout` describes it and the reconcile path's pairing is
        // right. When nothing was sent, that number describes an RCON exec that
        // never ran — and the grace period is no longer the last-resort net, it is
        // the *entire* save. It gets a floor that says so.
        val floor = if (attempted) definition.spec.lifecycle.drain.saveTimeout else SHUTDOWN_SAVE_ALLOWANCE
        val declared = definition.spec.lifecycle.stopGracePeriod
        if (!attempted && declared < SHUTDOWN_SAVE_ALLOWANCE) {
            throw ForcedTerminationRefused(
                "`${name.value}` would be stopped without a save request having been sent, so its " +
                    "spec.lifecycle.stopGracePeriod of $declared is the only chance the world has to reach " +
                    "disk — and it is below the $SHUTDOWN_SAVE_ALLOWANCE a shutdown save needs. Raise it and " +
                    "force again",
            )
        }
        val grace = StopGrace.of(declared, floor)

        LOG.warn(
            "forced stop server={} saveAttempted={} saveConfirmed={} playersOnline={} gracePeriodSeconds={} " +
                "— this bypasses the drain's evidence rule",
            name.value,
            attempted,
            confirmed,
            players ?: "unknown",
            grace.period.inWholeSeconds,
        )
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
     * One save request, retried once when the first never went out.
     *
     * Only [SaveOutcome.NotDelivered] with `retryable` is retried, and only once:
     * that outcome states the request never left, so a second is not a repeat.
     * [SaveOutcome.Unconfirmed] is never retried — its own contract is *"the
     * request counts as issued: do not send it again"*, and re-sending would put a
     * second flush on a main thread already running one.
     */
    private suspend fun requestSave(
        agent: PaperServerAgent,
        node: Node,
        observation: WorkloadObservation.Present,
    ): SaveOutcome {
        val first = attemptSave(agent, node, observation)
        if (first is SaveOutcome.NotDelivered && first.retryable) {
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

    private suspend fun locate(name: ResourceName): Pair<Node, WorkloadObservation.Present> {
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
        throw ForcedTerminationUnavailable("`${name.value}` has no workload on any node")
    }

    private companion object {
        /**
         * How long a Paper server is given to save on `SIGTERM` when no save
         * request reached it.
         *
         * Not `spec.lifecycle.drain.saveTimeout`: that number describes an RCON
         * exec, and on this branch no exec ran. It is the floor the grace period is
         * bounded against so that `StopGraceCeiling.ceilingFor` cannot cap the one
         * window the world has left.
         */
        private val SHUTDOWN_SAVE_ALLOWANCE: Duration = 60.seconds

        private val LOG = LoggerFactory.getLogger(NodeForcedTermination::class.java)
    }
}

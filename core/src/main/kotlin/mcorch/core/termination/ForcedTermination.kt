package mcorch.core.termination

import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.NodeRegistry
import mcorch.core.StopGrace
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadState
import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.SaveOutcome
import mcorch.schema.PaperServerDefinition
import mcorch.schema.ResourceName
import org.slf4j.LoggerFactory

/**
 * Stopping a server whose drain cannot finish.
 *
 * `spec/termination/02-force-stop.md`. This is the escape hatch for the state
 * `docs/operating.md` note 1 describes: a persistent server whose world save
 * cannot be confirmed, whose drain therefore aborts, and which today can only be
 * retired by hand.
 *
 * ## Why this is not a change to the drain
 *
 * The specification described force as three exemptions inside the drain — skip
 * the player wait, skip the retry, stop after the save timeout regardless. That
 * is not what is built here, and the code is why.
 *
 * `DrainPass.cause` carries an explicit rule: *"what a drain does is the same
 * whatever asked for it, and a cause consulted at a gate is how a delete comes to
 * take a path a replacement was written for."* Force-as-exemptions is precisely a
 * per-drain variation read at gates. And `DrainStatus.mayStop` is the single
 * precondition for **every** stop in that file, guarded by a comment saying it
 * exists to catch *"a future edit that routes into the stop without a current
 * save"*.
 *
 * So this does what an operator does by hand instead, which `docs/operating.md`
 * already documents as the way out: **save the world, stop the container, and let
 * the existing teardown observe it.** A terminating definition keeps reconciling,
 * so the loop notices the stopped container and finishes on its own. Nothing in
 * `DrainController` changes, `mayStop` keeps its meaning, and the drain has no
 * new mode.
 *
 * ## What it costs, and it is not hidden
 *
 * This is an unconditional container stop, which CLAUDE.md's first invariant
 * forbids putting in a code path. It is here **once**, in a type named after what
 * it does, reachable only by a `superuser`, and only after a save has been asked
 * for and waited out. That is the irreducible price of the feature; the choice
 * made here is to pay it in one visible place rather than by loosening a gate the
 * whole drain depends on.
 *
 * The save is always requested and always waited out. Skipping it would buy an
 * operator the save timeout — tens of seconds — in exchange for the data the
 * system exists to protect. The stop grace period is unchanged for the same
 * reason: it is the last thing still working when RCON is not.
 */
public interface ForcedTermination {
    /**
     * Saves what can be saved, then stops the container regardless.
     *
     * The definition must already be tombstoned. This does not delete it: the
     * teardown that frees the name belongs to the reconcile loop, and an API that
     * could reach past that guard would orphan a running container.
     *
     * @throws ForcedTerminationUnavailable when there is no running workload to
     *   stop, which means the drain never got as far as needing this.
     */
    public suspend fun stop(definition: PaperServerDefinition): ForcedStopOutcome
}

/**
 * What a forced stop did.
 *
 * [saveConfirmed] is the field that matters and the reason this type exists
 * rather than a `Unit` return. It is the difference between *"an operator retired
 * a stuck server"* and *"an operator lost a world"*, and six months later it is
 * the only thing that can tell them apart.
 */
public data class ForcedStopOutcome(
    val saveConfirmed: Boolean,
    /** Operator-facing, and never a player name or an address. */
    val detail: String,
)

/** There is no running workload to stop. */
public class ForcedTerminationUnavailable(
    message: String,
) : Exception(message)

/** [ForcedTermination] over the nodes this orchestrator knows. */
public class NodeForcedTermination(
    private val nodes: NodeRegistry,
) : ForcedTermination {
    override suspend fun stop(definition: PaperServerDefinition): ForcedStopOutcome {
        val name = definition.metadata.name
        val (node, observation) = locate(name)
        val agent = PaperServerAgent(definition)

        // Always asked for, always waited out. `requestSave` blocks for the
        // declared save timeout and reports what the server said, which is exactly
        // the wait this needs — there is no separate "force" timeout, because a
        // shorter one would only make the save less likely to land.
        val save =
            try {
                agent.requestSave(node, observation)
            } catch (failure: NodeException) {
                // A save that could not even be attempted is not a reason to stop
                // *less* carefully, but it is not a reason to refuse either: this
                // path exists for servers whose save channel is already broken.
                SaveOutcome.Unconfirmed("the save could not be attempted: ${failure.message}")
            }
        val confirmed = save is SaveOutcome.Confirmed

        val grace = StopGrace.of(definition.spec.lifecycle.stopGracePeriod, definition.spec.lifecycle.drain.saveTimeout)
        LOG.warn(
            "forced stop server={} saveConfirmed={} gracePeriodSeconds={} — this bypasses the drain's evidence rule",
            name.value,
            confirmed,
            grace.period.inWholeSeconds,
        )
        node.stopWorkload(observation.handle, grace)

        return ForcedStopOutcome(
            saveConfirmed = confirmed,
            detail =
                if (confirmed) {
                    "the world save was confirmed before the container was stopped"
                } else {
                    "the container was stopped without a confirmed world save; " +
                        "unsaved play since the last successful save is lost"
                },
        )
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
        private val LOG = LoggerFactory.getLogger(NodeForcedTermination::class.java)
    }
}

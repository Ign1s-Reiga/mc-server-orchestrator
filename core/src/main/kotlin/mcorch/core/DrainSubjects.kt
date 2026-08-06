package mcorch.core

import mcorch.core.paper.PaperServerAgent
import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.SaveOutcome
import mcorch.core.paper.WorkloadContract
import mcorch.core.proxy.VelocityProxyAgent
import mcorch.schema.PaperServerDefinition
import mcorch.schema.ResourceName
import mcorch.schema.VelocityProxyDefinition
import kotlin.time.Duration

/**
 * A `PaperServer` being drained, with or without a proxy in front of it.
 *
 * [seal] and [router] are supplied by the reconciler, which is the only thing that
 * can resolve them: whether this server is somebody's backend is decided by a
 * selector on a *different* definition, so it is a fleet-level read and not a
 * property of this one.
 */
internal class PaperDrainSubject(
    private val definition: PaperServerDefinition,
    private val agent: PaperServerAgent,
    override val replacementSpec: WorkloadSpec,
    override val seal: DrainSeal? = null,
    override val router: DrainRouter? = null,
) : DrainSubject {
    override val server: ResourceName get() = definition.metadata.name

    override val stopGracePeriod: Duration get() = definition.spec.lifecycle.stopGracePeriod

    override val saveTimeout: Duration get() = definition.spec.lifecycle.drain.saveTimeout

    override val playerTransferTimeout: Duration get() = definition.spec.lifecycle.drain.playerTransferTimeout

    override val maxPlayers: Int get() = definition.spec.maxPlayers

    override suspend fun probe(
        node: Node,
        handle: WorkloadHandle,
    ): ProbeOutcome = agent.probe(node, handle)

    override fun contractOf(observation: WorkloadObservation.Present): WorkloadContract = agent.contractOf(observation)

    override suspend fun requestSave(
        node: Node,
        observation: WorkloadObservation.Present,
        contract: WorkloadContract,
    ): SaveOutcome = agent.requestSave(node, observation, contract)
}

/**
 * The `VelocityProxy` itself being drained.
 *
 * [router] is null and stays null. A proxy has nowhere to send its own players by
 * construction — a fleet has one front door — so steps 3 and 4 have no
 * counterparty and the drain blocks on aggregate zero players exactly the way a
 * standalone Paper server does. That is not a gap waiting to be filled: filling it
 * would mean a second proxy, and moving players between proxies is a different
 * feature with a different failure mode.
 *
 * [requestSave] can never be reached. The workload carries
 * `Labels.WORLD_DATA = false`, so `save` returns before asking; this reports
 * honestly rather than throwing, because a `contractOf` that had somehow read
 * `true` should produce an abort an operator can read and not a crash inside the
 * loop.
 */
internal class ProxyDrainSubject(
    private val definition: VelocityProxyDefinition,
    private val agent: VelocityProxyAgent,
    override val replacementSpec: WorkloadSpec,
    override val seal: DrainSeal? = null,
) : DrainSubject {
    override val server: ResourceName get() = definition.metadata.name

    override val stopGracePeriod: Duration get() = definition.spec.lifecycle.stopGracePeriod

    /**
     * A proxy holds no world, so a lap of its drain contains no save and nothing
     * measuring one should be given an allowance for it.
     *
     * `ProxyLifecycleSpec` has no save timeout to read even if this wanted one —
     * deliberately, and its KDoc says why. Zero is the honest answer rather than a
     * stand-in.
     *
     * ## Why zero is inert, written down rather than left to reachability
     *
     * The sentence above is about a proxy whose container carries its labels, and
     * `VelocityProxyAgent.contractOf` reads `holdsWorldData = worldData ?: true` —
     * the safe default, kept deliberately unsoftened for a proxy. So an *unlabelled*
     * proxy container is drained as though it held a world: if the label goes
     * missing after the drain has passed `SAVING`, `mayStop` is false at
     * `DEREGISTERED` and the drain lands in `goingRoundInCircles` with an allowance
     * of `saveEvidenceMaxGap + 0` — thirty seconds. The value is consulted; it is not
     * unreachable, and the earlier version of this note claimed it did not need the
     * argument that follows.
     *
     * It is still the right allowance, for a reason that does not depend on that
     * branch being unreachable: [requestSave] answers `Unconfirmable` with no round
     * trip, which aborts the drain `PERMANENT` at `SAVING`. **No lap of a proxy's
     * drain can spend time inside a save**, whatever the label says — the lap is two
     * passes and one immediate refusal — so thirty seconds is the honest length of
     * one rather than a number that happens not to be reached. The direction the
     * borrowed `stopGracePeriod` got wrong was the opposite one: it handed a lap
     * *hours*.
     *
     * What it costs, stated plainly: a proxy whose labels stop being readable
     * mid-drain could be told it is circling after thirty seconds. That abort is
     * `RETRYABLE` and reports a container whose labels cannot be read, and the
     * permanent abort from `SAVING` — the accurate diagnosis — is one pass behind it.
     *
     * ## The second consumer, which is not about laps at all
     *
     * `StopGrace.of` takes this as the **floor** under the stop's operational
     * ceiling, so zero here means a proxy's grace period is capped at
     * `StopGraceCeiling.MAX` flat, with nothing underneath it. That is right for the
     * same reason as everything above — there is no save for a shortened grace period
     * to interrupt — and it is the one place the floor is disarmed rather than
     * derived. `PaperDrainSubject` reads both halves off one `LifecycleSpec`; this
     * one supplies a constant. **A change that gives a proxy something to flush has
     * to change this line**, or the ceiling silently starts cutting a grace period
     * below a save again, which is the thirtieth audit's finding restored on the kind
     * nobody was watching.
     */
    override val saveTimeout: Duration get() = Duration.ZERO

    /**
     * There is no transfer, so nothing measures against this. It is the proxy's
     * own seal timeout so that a value read off a status is at least the operator's
     * own number rather than an invented one.
     */
    override val playerTransferTimeout: Duration get() = definition.spec.lifecycle.drain.sealTimeout

    override val maxPlayers: Int get() = definition.spec.maxPlayers

    override val router: DrainRouter? get() = null

    override suspend fun probe(
        node: Node,
        handle: WorkloadHandle,
    ): ProbeOutcome = agent.probe(node, handle)

    override fun contractOf(observation: WorkloadObservation.Present): WorkloadContract = agent.contractOf(observation)

    override suspend fun requestSave(
        node: Node,
        observation: WorkloadObservation.Present,
        contract: WorkloadContract,
    ): SaveOutcome =
        SaveOutcome.Unconfirmable(
            "this is a Velocity proxy: it holds no world and has no channel that could report a completed " +
                "save. Reaching here means the workload is missing its `${Labels.WORLD_DATA}` label, which " +
                "makes a drain demand a save nothing can confirm and leaves the container unstoppable. Recreate " +
                "the proxy so its container carries the label",
        )
}

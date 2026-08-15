package mcorch.core

import mcorch.core.paper.ProbeOutcome
import mcorch.core.paper.SaveOutcome
import mcorch.core.paper.WorkloadContract
import mcorch.core.termination.forcedStopWindow
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.PaperServerDefinition
import mcorch.schema.ResourceName
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The workload a drain is being conducted against, and the two counterparties it
 * may or may not have.
 *
 * `DrainController` used to take a `PaperServerDefinition` and a
 * `PaperServerAgent` directly, which was fine while there was one kind. There are
 * two now, and they differ in exactly the places the protocol has choices:
 *
 * | | [seal] | [router] |
 * |---|---|---|
 * | a `PaperServer` behind a proxy | the backend's routing seal | that proxy |
 * | a standalone `PaperServer` | none | none |
 * | the `VelocityProxy` itself | its own login seal | **none** |
 *
 * The proxy's own row is the one worth reading twice. A proxy can stop admitting
 * logins, so it has a [seal]; it has nowhere to send the players already connected
 * to it, because a fleet has one front door, so it has no [router]. Its drain
 * therefore keeps the standalone shape — seal, then wait for the last player to
 * log off — and that is a property of this table rather than a branch inside the
 * controller.
 *
 * Nothing here is nullable-because-unimplemented. A null [seal] means *there is
 * nothing that could stop new joins*, and the drain says so on observed status
 * instead of pretending.
 */
internal interface DrainSubject {
    val server: ResourceName

    /**
     * From `spec.lifecycle.stopGracePeriod`: what the container stop is given, and
     * nothing else.
     *
     * **It is not a bound on a save, and nothing here may read it as one.** The
     * guarantee that it exceeds the save timeout belongs to `PaperServer` alone
     * (`SpecInvariants.stopGraceProblem`); `ProxyLifecycleSpec` deliberately has
     * no such rule, and says so in its own KDoc, because a proxy holds no world.
     * A reader that wants "how long a save may take" wants [saveTimeout], which
     * every subject answers for itself — the substitution was made once, in
     * `goingRoundInCircles`, and it was correct for a `PaperServer`, silently
     * unfounded for a proxy, and capped at `MAX_STOP_GRACE_PERIOD` (two hours) for
     * an operator who had set a long grace period for an unrelated reason.
     */
    val stopGracePeriod: Duration

    /**
     * The longest a world flush may take on this workload, from
     * `spec.lifecycle.drain.saveTimeout`.
     *
     * `Duration.ZERO` for a workload that holds no world: there is no save in its
     * drain, so nothing that measures a lap of the protocol should be given an
     * allowance for one. That is an answer about the subject rather than a
     * placeholder — but it is not self-evidently inert, and the argument that it is
     * belongs to the implementation that answers zero.
     *
     * The premise is about a *labelled* container. `holdsWorldData` defaults to
     * `true` for every kind when the label cannot be read, deliberately, so a
     * workload that claims no world here can still be drained as though it held one
     * and can reach the arithmetic with a zero allowance. See
     * [ProxyDrainSubject.saveTimeout], which carries the reason that is still safe.
     */
    val saveTimeout: Duration

    /**
     * How long step 4 gets before the loop stops issuing transfers, before the
     * per-player extension.
     */
    val playerTransferTimeout: Duration

    /**
     * `spec.maxPlayers`.
     *
     * The proxy's control protocol reports occupancy as a count with no
     * denominator — `PlayerOccupancy.max` has to come from the declaration, and
     * the plugin author said so explicitly rather than adding a field that would
     * have been the proxy's guess at somebody else's configuration.
     */
    val maxPlayers: Int

    /**
     * What a `REPLACEMENT` of this workload would create, so the drain can ask the
     * node whether it can build it *before* taking the running one away.
     *
     * `Reconciler` asks the same question once per pass, before it decides to drain
     * at all — and exempts a drain that is already in flight, on the grounds that
     * the container it would have saved is gone or going. That is true from the stop
     * onwards and false for every pass before it, which on a populated server is
     * hours: an orchestrator upgrade that replaces the asset directory, or a secret
     * rotated out from under a reference, lands inside that window and the teardown
     * still commits. So the question is asked again at the entry to steps 6 and 7 —
     * see `DrainController.letGoAndStop` — where a refusal still costs nothing.
     *
     * It is a spec rather than a `checkWorkload()` closure because the subject is
     * where facts about the workload live, and because a closure would let the two
     * askers disagree about what is being asked. `DELETION` never reads it: a delete
     * needs no create and must never be blocked by one.
     */
    val replacementSpec: WorkloadSpec

    /** Step 2's counterparty, or null when nothing can stop new joins. */
    val seal: DrainSeal?

    /** Steps 3, 4 and 6's counterparty, or null when there is nowhere to send players. */
    val router: DrainRouter?

    suspend fun probe(
        node: Node,
        handle: WorkloadHandle,
    ): ProbeOutcome

    fun contractOf(observation: WorkloadObservation.Present): WorkloadContract

    suspend fun requestSave(
        node: Node,
        observation: WorkloadObservation.Present,
        contract: WorkloadContract,
    ): SaveOutcome
}

/**
 * Whether a drain in this state is holding the workload out of the proxy's
 * routing.
 *
 * **The one definition of the seal rule**, asked from two places that must not
 * disagree: `DrainController`, which asserts it for the workload it is draining,
 * and the proxy's own reconcile pass, which asserts it for *every* backend from
 * stored state. The second is what makes the seal level-triggered in the sense
 * that matters — it repairs a proxy restart, an orchestrator that died mid-drain,
 * and a backend whose drain aborted **permanently**, where the backend's own
 * passes have stopped and nothing else could.
 *
 * `DRAIN_FAILED` is deliberately not a sealing state. A drain that has stopped
 * advancing is a drain that is not going to move those players, so holding the
 * backend out of routing buys nothing and costs a running server no player can
 * reach — permanently, if the abort was permanent.
 *
 * It is **not** `PaperServerStatus.drainInitiated`, which is the *destination
 * eligibility* rule and answers a different question: a parked server takes
 * players again but must never be handed somebody else's.
 */
internal fun DrainState.sealsBackend(): Boolean =
    when (this) {
        // Step 2 has not happened yet.
        DrainState.DRAIN_REQUESTED -> false

        DrainState.SEALED,
        DrainState.TARGET_RESOLVED,
        DrainState.TRANSFERRING,
        DrainState.SAVING,
        DrainState.DEREGISTERED,
        DrainState.STOPPING,
        -> true

        DrainState.DRAIN_FAILED -> false
    }

/**
 * Whether a backend's login path should be held shut **right now**.
 *
 * [sealsBackend] answers from the drain's state alone, and `DRAIN_FAILED` answers
 * false there on purpose. That is right for a server that is still running and
 * wrong the moment a `SIGTERM` has left this process for it — so this adds the
 * record that says so, bounded by the window that stop is still inside.
 *
 * **One predicate, every consumer**, and that is the point rather than tidiness.
 * `Reconciler.ProxyPass.backends` and `ProxyFleet.resolve`'s sibling derivation
 * both answer this question, and for one round they disagreed: the sweep sealed a
 * forced backend while `Sibling.sealed` still called it admitting, so
 * `BackendLink.lastAdmitting` skipped the proxy-wide seal and the plugin's
 * admit-anyway path could route a login onto an all-sealed fleet — including the
 * server already shutting down.
 */
internal fun DrainStatus?.sealsBackendAt(
    definition: PaperServerDefinition,
    now: Instant,
): Boolean {
    if (this?.state?.sealsBackend() == true) return true
    // The **latest** dispatch, not the first: a retried stop restamps only that
    // half, and bounding on the first would expire a signal that has just been
    // sent. Falling back for rows written before the field existed.
    val dispatched = this?.stopLastDispatchedAt ?: this?.stopDispatchedAt ?: return false
    return now < dispatched.plus(forcedStopWindow(definition).toJavaDuration())
}

/**
 * Drain step 2, and the reason it is spelled as an assertion rather than an
 * action.
 *
 * A seal is an effect on a **third party that outlives an abort**. Every retryable
 * abort leaves the drain parked with the server running, and an event-shaped seal
 * would leave it sealed off from new joins indefinitely while the dashboard shows
 * a healthy running server no player can reach. A permanent abort freezes the
 * status, so it would be permanently invisible, unreachable and running.
 *
 * So there is no "unseal" operation, here or on the wire. [assert] states what
 * should be true and the proxy makes it true, and it is called on **every pass**
 * of every state that depends on it — the way `ensureImage` is called on every
 * pass of a bring-up. An abort restores joins because the level-trigger stops
 * asserting a seal; a proxy restart is repaired because the next assertion puts it
 * back. Neither needs an edge somebody has to remember to write.
 */
internal interface DrainSeal {
    /**
     * Makes the workload's admission match [admits], and reports what the proxy
     * says it is now.
     */
    suspend fun assertAdmission(admits: Boolean): SealOutcome
}

/** What an admission assertion achieved. */
internal sealed interface SealOutcome {
    /** The proxy says the workload's admission is now [admits]. */
    data class Asserted(
        val admits: Boolean,
    ) : SealOutcome

    /**
     * The proxy answered and declined.
     *
     * The only refusal step 2 can meet is an address conflict — the backend is
     * registered at a different address — and it is deliberately not an upsert:
     * the only way Velocity can move a registration is unregister-then-register,
     * and an unregister here would be step 6 performed at step 2.
     */
    data class Refused(
        val detail: String,
        val retryable: Boolean,
    ) : SealOutcome

    /** The control endpoint could not be reached, or spoke a protocol this build cannot read. */
    data class Unavailable(
        val detail: String,
        val retryable: Boolean,
    ) : SealOutcome
}

/** Steps 3, 4 and 6: where players go, moving them, and letting go of the backend. */
internal interface DrainRouter {
    /** Which proxy this is, for operator-facing messages. Never an address. */
    val proxy: ResourceName

    /**
     * Step 3.
     *
     * Goes through the [Scheduler] seam rather than being decided here, for the
     * same reason placement does: choosing where a workload's players end up is a
     * fleet decision, and a fleet decision inside the drain controller is a
     * single-host assumption waiting to be written.
     */
    suspend fun resolveDestination(): DestinationChoice

    /** Step 4. Start-or-join; never a second sweep against the same destination. */
    suspend fun transfer(destination: ResourceName): TransferReport

    /** Step 6. Refused outright while anybody is still connected — there is no force flag. */
    suspend fun deregister(): SealOutcome

    /**
     * The compensating edge for step 6, taken when a drain aborts after
     * deregistering.
     *
     * Deregistration is the one step that cannot be level-triggered: it is the
     * last thing before the stop, so "assert it every pass" would mean asserting
     * it from states that must not reach it. An explicit edge is therefore
     * required, and this is it.
     */
    suspend fun reregister(): SealOutcome

    /**
     * How many players the *proxy* has on this workload, or null if it could not
     * say.
     *
     * **Corroboration only, and never a gate.** It is one RPC where a Server List
     * Ping is an `ExecSync`, which makes it tempting — and it is strictly wrong: a
     * client connected straight to the backend's own port is invisible to the
     * proxy and visible to SLP, and whether backends are firewalled is a
     * deployment property this code cannot assert. A disagreement between the two
     * is a log line. It is never a decision.
     */
    suspend fun observedPlayers(): Int?
}

/** Where this workload's players are going. */
internal sealed interface DestinationChoice {
    data class Chosen(
        val destination: ResourceName,
    ) : DestinationChoice

    /**
     * The search ran and the fleet had nothing with capacity.
     *
     * Distinct from having no router at all: this one means an operator has to add
     * capacity, so it is a retryable failure that escalates, rather than a block
     * that resolves when people log off.
     */
    data class NoCapacity(
        val detail: String,
    ) : DestinationChoice

    data class Unavailable(
        val detail: String,
        val retryable: Boolean,
    ) : DestinationChoice
}

/** How step 4 is going. */
internal sealed interface TransferReport {
    /**
     * The sweep exists and this is where it is.
     *
     * [remaining] is read live off the backend, so somebody who joined mid-sweep
     * counts against it even though nothing asked them to move.
     */
    data class Sweeping(
        val remaining: Int,
        val unmoved: Int,
        val finished: Boolean,
    ) : TransferReport

    /**
     * The destination stopped being a destination — it is gone, or it is sealed
     * itself.
     *
     * Not a failure: the drain goes back to step 3 and picks another. Moving a
     * draining server's players onto another draining server is a destination
     * without capacity in the only sense that matters.
     */
    data class DestinationLost(
        val detail: String,
    ) : TransferReport

    data class Refused(
        val detail: String,
        val retryable: Boolean,
    ) : TransferReport

    data class Unavailable(
        val detail: String,
        val retryable: Boolean,
    ) : TransferReport
}

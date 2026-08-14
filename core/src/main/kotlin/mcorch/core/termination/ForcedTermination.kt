package mcorch.core.termination

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.NodeRegistry
import mcorch.core.ProxyFleet
import mcorch.core.ReconcilerConfig
import mcorch.core.Scheduler
import mcorch.core.SealOutcome
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
import mcorch.store.Precondition
import mcorch.store.Store
import mcorch.store.StoreException
import mcorch.store.WriteOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

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
 * | Seal the proxy (2) | **Done.** Asserted through the same `ProxyFleet.linkFor` channel the drain uses. See below |
 * | Transfer players (4) | **Not done.** Players are disconnected |
 * | `requireEmpty` — zero players (5) | Replaced by a counted acknowledgement, read under the seal. See below |
 * | Save (5) | Requested, and **[ForcedStopOutcome.saveAttempted] says whether it really was** |
 * | Deregister (6) | **Not done.** The loop's next pass does it; until then the proxy holds a registration at an address that is going away |
 * | `mayStop` (7) | Deliberately bypassed. That is the feature |
 *
 * ## The seal is what makes the count mean something
 *
 * `requireEmpty`'s zero is durable in the drain because the seal holds it: from
 * `SEALED` onward every state re-asserts `holdSeal`, so nobody can join between
 * the observation and the stop. Two earlier versions of this file dropped step 2
 * and tried to make a bare probe carry the same weight. Neither could:
 *
 * - One probe, and the reading decayed across the save wait — a whole
 *   `saveTimeout`, which `SpecBounds` caps at an hour. Worst on the branch that
 *   looks safest, since a server probing zero is asked for no acknowledgement at
 *   all and would have been stopped with players who arrived during the wait.
 * - Two probes, and the second only narrowed the window. It also made the counted
 *   acknowledgement livelock: with nothing owning the number between the 409 that
 *   names it and the re-send that quotes it, a busy server could refuse forever,
 *   each turn spending a save timeout and a `save-all flush` on a definition
 *   already tombstoned.
 *
 * **A compare-and-swap is only a compare-and-swap if something owns the value
 * between the read and the write.** So step 2 is done here, first, and the reading
 * is taken under it. The count can then only fall — players leave, none arrive —
 * which is the direction that is safe to be wrong about, and which makes a retry
 * terminate instead of oscillating.
 *
 * **The second probe survives where the seal does not reach.** A standalone server
 * has no proxy to seal and players connect to it directly, so there is no door and
 * nothing owns the count; the reading is taken again before the stop there, and
 * the race is honestly still a race. That is not a defect this path can fix — the
 * drain does not fix it either, it declines to stop instead — and an operator
 * forcing an unproxied server with a live population is being told to let it empty
 * rather than being handed a guarantee that does not exist.
 *
 * When there *is* a door and it cannot be shut, [refuseUnsealedPopulation] decides
 * that against the count rather than on its own, which is the trade
 * `DrainController.abortSeal` already makes: an empty server needs no door held.
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
     * Seals the login path first, then reads the count under it, then saves, then
     * stops. Re-runs every check [preflight] made rather than trusting it: a seam
     * whose safety depends on having been called in the right order has none.
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
 * [playersOnline] is the count this stop was decided on, never the one the caller
 * acknowledged. It is null when the probe did not answer. **Null is not zero** —
 * reading it as zero is how this path would come to stop a populated server while
 * reporting that it did not.
 *
 * On a **sealed** server it is the reading taken before the save, which may have
 * run for a save timeout since. That is deliberate and is not worth another probe:
 * under the seal the number can only fall, so the record over-states who was
 * dropped, and over-stating is the safe direction for a field an investigator
 * reads after a world is lost. On an **unsealed** one it is re-read immediately
 * before the stop, because there the number can rise.
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
    private val scheduler: Scheduler,
    private val config: ReconcilerConfig = ReconcilerConfig(),
    private val clock: Clock = Clock.systemUTC(),
) : ForcedTermination {
    /** What drain step 2 achieved here. Three answers, as the drain's own [SealHold] has. */
    private enum class SealResult {
        /** The proxy confirmed the workload no longer admits new players. */
        ASSERTED,

        /** Nothing routes to this server, so there is no door to shut. */
        NOTHING_TO_SEAL,

        /** There is a door and it could not be shut. Decided against the count, not on its own. */
        FAILED,
    }

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

        // Drain step 2, and it comes **first**. Everything below reads a player
        // count and acts on it, and a count nobody is holding still is not a
        // reading — see "The seal is what makes the count mean something".
        val seal = sealOff(name)

        // Read under the seal, and refused before the save rather than after it, so
        // a mismatch costs the caller neither a flush nor a save timeout.
        val players = occupancy(agent, node, observation)
        refuseOccupancy(name, players, acknowledgement)
        refuseUnsealedPopulation(name, seal, players, acknowledgement)

        val save = requestSave(agent, node, observation)
        val attempted = save !is SaveOutcome.Unconfirmable && save !is SaveOutcome.NotDelivered
        val confirmed = save is SaveOutcome.Confirmed

        // **One more reading, and only when nothing is holding the last one.**
        //
        // With the door shut the count can only fall, so the reading above stays
        // good enough: a second probe would cost a wedged server another 10s to
        // learn something the seal already guarantees.
        //
        // Without one it does not, and `NOTHING_TO_SEAL` is not a rare case — a
        // standalone server has no proxy to seal and players reach it directly.
        // Dropping this probe outright, as the seal's first draft did, would have
        // widened that window from milliseconds to a whole save timeout for exactly
        // the servers with no door. So the probe follows the guarantee rather than
        // the other way round.
        val atStop = if (seal == SealResult.ASSERTED) players else occupancy(agent, node, observation)
        refuseOccupancy(name, atStop, acknowledgement)

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
            atStop ?: "unknown",
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
            playersOnline = atStop,
            detail = describe(attempted, confirmed),
        )
    }

    /**
     * Drain step 2: stop new joins at the proxy.
     *
     * Asserted rather than assumed, exactly as `DrainController.holdSeal` does, and
     * through the same `ProxyFleet.linkFor` channel so there is one derivation of a
     * backend's address rather than two.
     *
     * Unlike the drain this does not park on a failure, because there is no next
     * pass to park for — the caller is holding an HTTP request open and the
     * definition is already tombstoned. A failure is carried to
     * [refuseUnsealedPopulation], which decides it against the count, the same
     * trade `DrainController.abortSeal` makes through `sealIsPrecondition`: an
     * empty server needs no door held, and a populated one does.
     *
     * A proxy that cannot be reached is **not** a failure of the backend. That is
     * `linkFor`'s own rule — *"the proxy being down is the proxy's problem, and a
     * backend that refused to drain because of it would be undeletable for as long
     * as the proxy was"* — and it applies with more force here, on the path whose
     * whole purpose is that a server can always be retired.
     */
    private suspend fun sealOff(name: ResourceName): SealResult {
        val stored = store.getServer(name) ?: return SealResult.NOTHING_TO_SEAL
        val binding =
            when (val fleet = ProxyFleet.resolve(store, stored)) {
                is ProxyFleet.Resolution.Behind -> fleet.binding

                // Two proxies claim it, and sealing one leaves the other admitting —
                // the same degradation the reconcile path takes rather than picking
                // one. **[SealResult.FAILED], not [SealResult.NOTHING_TO_SEAL]:**
                // there are two doors and neither can be shut, which is strictly
                // worse than having none.
                //
                // Filing it under "no door" inverted the ladder, because
                // [refuseUnsealedPopulation] only bites on `FAILED` — a populated
                // backend behind *one* unreachable proxy was refused, and one behind
                // *two* wide-open proxies was stopped. The worse case treated more
                // permissively than the milder one, off a comment that reasoned
                // about it correctly and then routed it to the value whose
                // documented meaning is the opposite.
                is ProxyFleet.Resolution.Conflicted -> return SealResult.FAILED

                // Standalone: no proxy routes to it at all, so players reach it
                // directly and there is no door. The pre-stop re-probe is what
                // stands in for one.
                else -> return SealResult.NOTHING_TO_SEAL
            }
        val runtime = (stored.status?.status as? PaperServerStatus)?.runtime
        val link =
            ProxyFleet.linkFor(binding, name, nodes, scheduler, runtime?.node ?: return SealResult.FAILED, config)
                ?: return SealResult.FAILED
        return try {
            when (val outcome = link.assertAdmission(admits = false)) {
                is SealOutcome.Asserted -> if (outcome.admits) SealResult.FAILED else SealResult.ASSERTED
                is SealOutcome.Refused -> SealResult.FAILED
                is SealOutcome.Unavailable -> SealResult.FAILED
            }
        } catch (failure: NodeException) {
            LOG.debug("the login seal could not be asserted for a forced stop", failure)
            SealResult.FAILED
        }
    }

    /**
     * Refuses a populated server whose login path could not be shut.
     *
     * `DrainController.abortSeal` makes the same call through `sealIsPrecondition`:
     * a server with nobody on it needs no door held, and the zero-player gate is
     * what decides. A populated one is different — the count below is the whole
     * basis for stopping, and an unsealed proxy can put a player behind it between
     * the reading and the `SIGTERM`.
     *
     * Recoverable, so it may sit below the tombstone: the operator fixes the proxy,
     * or waits for the server to empty, and forces again.
     */
    private fun refuseUnsealedPopulation(
        name: ResourceName,
        seal: SealResult,
        players: Int?,
        acknowledgement: OccupancyAcknowledgement,
    ) {
        if (seal != SealResult.FAILED || players == 0) return
        // **An acknowledged unreadable count is let through, and this is the branch
        // that had no way out.**
        //
        // The refusal's whole basis is protecting a *count* from decaying between
        // the reading and the `SIGTERM`. When there is no count there is nothing to
        // protect, and the operator has already said in the request that they know
        // it cannot be read.
        //
        // Without this the endpoint refused exactly the population it exists for.
        // A wedged server does not answer a Server List Ping, so `players` is null,
        // and `null == 0` is false — so "wait for the server to empty" could never
        // be taken: the count never reads zero, it reads unknown, forever. Pair
        // that with a proxy that will not answer, which is not an exotic pairing
        // when a node is having a bad minute, and the definition was tombstoned,
        // frozen, undrainable and `crictl`-only.
        if (players == null && acknowledgement == OccupancyAcknowledgement.Unreadable) return
        throw ForcedTerminationRefused(
            "`${name.value}` could not have its login path shut at the proxy, and it is not empty, so nothing " +
                "stops a player joining between the count this stop is based on and the stop itself. Restore " +
                "the proxy's control channel, or wait for the server to empty, and force again",
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
        val dispatched = drain.stopDispatchedAt
        // **Bounded by the grace period, not by the stamp's existence.**
        //
        // The reason to refuse is that the container is *inside* its grace period
        // running a shutdown save. Once that window has passed the reason is gone,
        // and `DrainController.awaitStopped` already holds the licence this borrows:
        // it re-issues a stop that did not take, with the same grace period.
        //
        // Keying on the stamp alone locked the hatch permanently. `stop` records the
        // dispatch *before* `stopWorkload` — correct, and the field's KDoc argues
        // that asymmetry — so a `NodeException` from the stop leaves the stamp
        // written and the container running. The caller is told "nothing was
        // stopped", every retry then meets this refusal, the definition is
        // tombstoned so nothing can be edited, and the drain cannot finish for the
        // population this path exists for. One transient CRI failure was enough.
        if (dispatched != null && clock.instant() < dispatched.plus(graceWindow(name))) {
            throw ForcedTerminationRefused(
                "`${name.value}` already has a stop in flight, dispatched at $dispatched. It is inside its " +
                    "grace period; forcing again would send a second save into a server already shutting down",
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
     * How long a dispatched stop is still "in flight" for.
     *
     * The declared grace period, raised by the shutdown-save allowance so it can
     * never be shorter than the window [stop] itself might have given the
     * container. A definition that will not read falls back to the allowance
     * rather than to zero: a window of zero would make the refusal above vanish
     * entirely, which is the failure this bound was added to avoid in reverse.
     */
    private suspend fun graceWindow(name: ResourceName): java.time.Duration {
        val definition = (store.getServer(name)?.definition?.definition as? PaperServerDefinition)
        val declared = definition?.spec?.lifecycle?.stopGracePeriod ?: SHUTDOWN_SAVE_ALLOWANCE
        return maxOf(declared, SHUTDOWN_SAVE_ALLOWANCE).toJavaDuration()
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
                val held = stored.status ?: return@withContext
                val next = status.copy(drain = status.drain?.dispatchingStop(now) ?: forcedStopDrain(now))
                // **Conditional, because this is no longer the only writer of
                // observed state.**
                //
                // `SqliteStore.putStatus` is an unconditional upsert without a
                // precondition, and `next` is built from a snapshot two store calls
                // old. A reconcile pass overlapping this request — the ordinary
                // case, since the tombstone hits the change feed and queues the
                // server — would have had its own fields overwritten away by this
                // write. Losing `saveRequestedAt` disarms the never-re-send wedge;
                // losing `sealRequestedAt` leaves a backend sealed with nothing
                // knowing to restore it.
                //
                // Retried once on a conflict rather than logged: a conflict means a
                // pass wrote in between, and a re-read resolves it. The
                // log-and-carry posture below is for a store that is *down*, which
                // a second read would not fix either.
                when (store.putStatus(next, Precondition.AtVersion(held.resourceVersion))) {
                    is WriteOutcome.Applied -> Unit
                    is WriteOutcome.Conflict -> retryStopDispatched(name, now)
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

    /**
     * One re-read and one more attempt, then the log line.
     *
     * Not a loop. A second conflict means passes are landing faster than this can
     * read, and spinning under [NonCancellable] would hold a shutdown open; the
     * container still has to be stopped either way, and the stop is what the
     * caller is waiting on.
     */
    private suspend fun retryStopDispatched(
        name: ResourceName,
        now: Instant,
    ) {
        val stored = store.getServer(name)
        val status = stored?.status?.status as? PaperServerStatus
        val held = stored?.status
        if (status == null || held == null) return
        val next = status.copy(drain = status.drain?.dispatchingStop(now) ?: forcedStopDrain(now))
        if (store.putStatus(next, Precondition.AtVersion(held.resourceVersion)) is WriteOutcome.Applied) return
        LOG.error(
            "could not record a forced stop dispatch server={} — a reconcile pass wrote twice underneath it. " +
                "The loop may start a drain over a container already shutting down",
            name.value,
        )
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

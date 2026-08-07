package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * How long the record of a dispatched container stop lives.
 *
 * The thirty-third audit's critical is a **lifetime** rather than a value:
 * `DrainStatus.stopDispatchedAt` was right, and every site that concluded no drain
 * was wanted deleted the whole record it lives in — including while the `SIGTERM`
 * was still inside the container. The scenarios in `ProxyDrainTest` drive the two
 * paths that produced a lost session and a fleet blackout; this is the rule they
 * both rest on, asked directly, because two of its cases exist to stop a *later*
 * mistake and neither is reachable from a scenario:
 *
 * - **`CREATED` is never the signalled container.** Without that clause the record
 *   survived the container it named, and the pass after the replacement was built
 *   drained the replacement — for ever. One existing test caught it (`a proxy at
 *   zero players whose endpoint is dead can still be replaced`, by counting
 *   creates), which is luck rather than coverage.
 * - **`SANDBOX_ONLY` is decided by `hadContainer`, and by nothing else.** That is
 *   the thirty-fourth audit's critical: the rule used to answer it `false`
 *   unconditionally, so a runtime that stopped enumerating a container inside its
 *   stop grace period made the loop converge over the top of its own stop.
 * - **The state set is exhaustive.** A sixth `WorkloadState` has to be classified
 *   before this compiles, and the control below says which side each of the five is
 *   on, so a re-classification is a visible edit rather than a silent one.
 *
 * ## The fixture is shaped so that the discriminator can be missing
 *
 * Every observation here used to carry a container id, `SANDBOX_ONLY` included —
 * and a real one never can: `WorkloadView.observe` sets `handle.containerId =
 * mine?.id` and takes the `SANDBOX_ONLY` branch exactly when `mine == null`. A
 * fixture that hands the rule an id it could not have in the field cannot express
 * the case the rule gets wrong, and the exhaustiveness control below reads as
 * complete while one of the five states is being classified from data that does not
 * occur. [observed] now builds `SANDBOX_ONLY` the way the node does, and the
 * `hadContainer` axis is asked of every state rather than of the one it changes.
 */
internal class DrainRecordLifetimeTest {
    private val now: Instant = Instant.parse("2026-07-26T10:00:00Z")

    private fun drain(dispatchedAt: Instant?) =
        DrainStatus(
            state = DrainState.STOPPING,
            startedAt = now,
            enteredStateAt = now,
            playersEvacuated = true,
            deregisteredAt = now,
            worldSavedAt = now,
            stopDispatchedAt = dispatchedAt,
        )

    /**
     * An observation as a node really produces it.
     *
     * `SANDBOX_ONLY` carries no container id — that is what the state *means* — so
     * the handle is built the way `WorkloadView.observe` builds it rather than the
     * way every other state's is.
     */
    private fun observed(state: WorkloadState) =
        WorkloadObservation.Present(
            handle =
                WorkloadHandle(
                    nodeName("node-a"),
                    "sandbox-survival-01",
                    if (state == WorkloadState.SANDBOX_ONLY) null else "container-survival-01",
                ),
            state = state,
            startedAt = now,
        )

    /**
     * The states in which the runtime may still be reporting the container that was
     * signalled, whatever this loop has recorded about it.
     */
    private val signalled =
        setOf(
            WorkloadState.RUNNING,
            WorkloadState.EXITED,
            WorkloadState.UNKNOWN,
        )

    /**
     * The state whose answer is [stopIsInFlight]'s third argument and nothing else.
     *
     * A sandbox the runtime reports no container in is either a workload whose
     * container was never created or a live container it has stopped enumerating,
     * and the observation cannot tell them apart. `containerIsDown` splits them on
     * this fact; so does this.
     */
    private val decidedByHistory = setOf(WorkloadState.SANDBOX_ONLY)

    @Test
    fun `a record with no dispatched stop is cleared by any observation`() {
        val record = drain(dispatchedAt = null)
        val observations = WorkloadState.entries.map(::observed) + WorkloadObservation.Absent
        observations.forEach { observation ->
            // Including the history: with nothing dispatched there is nothing to
            // keep, however much of a container this loop remembers.
            listOf(false, true).forEach { hadContainer ->
                stopIsInFlight(record, observation, hadContainer).shouldBeFalse()
                clearedDrainRecord(record, observation, hadContainer) shouldBe null
                outstandingStopCause(record, observation, hadContainer) shouldBe null
            }
        }
        // …and neither is no record at all, which is what most passes carry.
        stopIsInFlight(null, observed(WorkloadState.RUNNING), hadContainer = true).shouldBeFalse()
        clearedDrainRecord(null, observed(WorkloadState.RUNNING), hadContainer = true) shouldBe null
    }

    @Test
    fun `a dispatched stop survives while the runtime still reports the container`() {
        val record = drain(dispatchedAt = now)
        signalled.forEach { state ->
            val observation = observed(state)
            listOf(false, true).forEach { hadContainer ->
                stopIsInFlight(record, observation, hadContainer).shouldBeTrue()
                // The same record, not a redraft: every field of it is evidence, and
                // `deregisteredAt` is what keeps the proxy's sweep off the backend.
                clearedDrainRecord(record, observation, hadContainer) shouldBeSameInstanceAs record
                outstandingStopCause(record, observation, hadContainer) shouldBe DrainCause.REPLACEMENT
            }
        }
    }

    /**
     * The thirty-fourth audit's critical, at the rule.
     *
     * The observation is identical in both halves — a sandbox, no container in it —
     * and only what this loop wrote down differs. Answering it `false` for both is
     * the defect: the drain had dispatched a stop into a container that is still
     * inside its grace period, the loop concluded nothing was in flight, converged,
     * created a **second** container against the same persistent host path, and the
     * proxy's sweep put the backend back into routing.
     */
    @Test
    fun `a sandbox the runtime reports no container in is decided by what this loop recorded`() {
        val record = drain(dispatchedAt = now)
        val observation = observed(WorkloadState.SANDBOX_ONLY)
        // The handle a node really hands over. If this ever carries an id, the two
        // halves below stop being the same observation and the test says nothing.
        observation.handle.containerId shouldBe null

        // A container this loop created and recorded: the runtime has stopped
        // enumerating it, which is not evidence that the stop finished.
        stopIsInFlight(record, observation, hadContainer = true).shouldBeTrue()
        clearedDrainRecord(record, observation, hadContainer = true) shouldBeSameInstanceAs record
        outstandingStopCause(record, observation, hadContainer = true) shouldBe DrainCause.REPLACEMENT

        // No container was ever recorded — the pass after a create, or after the
        // teardown's partial removal nulled the id on purpose. Nothing can have been
        // signalled into it, and a record that survived here made the next pass
        // drain the container that had just been built.
        stopIsInFlight(record, observation, hadContainer = false).shouldBeFalse()
        clearedDrainRecord(record, observation, hadContainer = false) shouldBe null
        outstandingStopCause(record, observation, hadContainer = false) shouldBe null
    }

    @Test
    fun `a dispatched stop does not outlive the container it was aimed at`() {
        val record = drain(dispatchedAt = now)

        listOf(false, true).forEach { hadContainer ->
            // Absent is the evidence the stop finished, and the only thing that
            // retires the record — `Reconciler.teardown` writes the clear on exactly
            // this, and a remembered container id does not resurrect it.
            stopIsInFlight(record, WorkloadObservation.Absent, hadContainer).shouldBeFalse()
            clearedDrainRecord(record, WorkloadObservation.Absent, hadContainer) shouldBe null
            outstandingStopCause(record, WorkloadObservation.Absent, hadContainer) shouldBe null

            // A container that has never been started is not one anything was
            // signalled into, and this is a property of CRI rather than a
            // convention: `CONTAINER_CREATED` → `CONTAINER_RUNNING` →
            // `CONTAINER_EXITED` never runs backwards and ids are not reused, so a
            // container this loop started can never be reported `CREATED` again.
            // Whatever is in `CREATED` was built after the signalled one.
            val created = observed(WorkloadState.CREATED)
            stopIsInFlight(record, created, hadContainer).shouldBeFalse()
            clearedDrainRecord(record, created, hadContainer) shouldBe null
            outstandingStopCause(record, created, hadContainer) shouldBe null
        }
    }

    /**
     * The control: the three sets are the whole enumeration and are disjoint.
     *
     * Every assertion above iterates a set derived from [signalled] and
     * [decidedByHistory], so a state missing from the enumeration would be tested by
     * none of them and the suite would still pass. This is what makes the tuple a
     * classification rather than three lists.
     */
    @Test
    fun `every workload state is classified`() {
        val never = WorkloadState.entries.toSet() - signalled - decidedByHistory
        (signalled + decidedByHistory + never) shouldBe WorkloadState.entries.toSet()
        (signalled intersect decidedByHistory) shouldBe emptySet()
        signalled.size shouldBe 3
        decidedByHistory.size shouldBe 1
        never shouldBe setOf(WorkloadState.CREATED)
        WorkloadState.entries.size shouldBe 5
    }
}

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
 * - **`CREATED` and `SANDBOX_ONLY` are not the signalled container.** Without that
 *   clause the record survived the container it named, and the pass after the
 *   replacement was built drained the replacement — for ever. One existing test
 *   caught it (`a proxy at zero players whose endpoint is dead can still be
 *   replaced`, by counting creates), which is luck rather than coverage.
 * - **The state set is exhaustive.** A sixth `WorkloadState` has to be classified
 *   before this compiles, and the control below says which side each of the five is
 *   on, so a re-classification is a visible edit rather than a silent one.
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

    private fun observed(state: WorkloadState) =
        WorkloadObservation.Present(
            handle = WorkloadHandle(nodeName("node-a"), "sandbox-survival-01", "container-survival-01"),
            state = state,
            startedAt = now,
        )

    /** The states in which the runtime may still be reporting the container that was signalled. */
    private val signalled =
        setOf(
            WorkloadState.RUNNING,
            WorkloadState.EXITED,
            WorkloadState.UNKNOWN,
        )

    @Test
    fun `a record with no dispatched stop is cleared by any observation`() {
        val record = drain(dispatchedAt = null)
        (WorkloadState.entries.map(::observed) + WorkloadObservation.Absent).forEach { observation ->
            stopIsInFlight(record, observation).shouldBeFalse()
            clearedDrainRecord(record, observation) shouldBe null
            outstandingStopCause(record, observation) shouldBe null
        }
        // …and neither is no record at all, which is what most passes carry.
        stopIsInFlight(null, observed(WorkloadState.RUNNING)).shouldBeFalse()
        clearedDrainRecord(null, observed(WorkloadState.RUNNING)) shouldBe null
    }

    @Test
    fun `a dispatched stop survives while the runtime still reports the container`() {
        val record = drain(dispatchedAt = now)
        signalled.forEach { state ->
            val observation = observed(state)
            stopIsInFlight(record, observation).shouldBeTrue()
            // The same record, not a redraft: every field of it is evidence, and
            // `deregisteredAt` is what keeps the proxy's sweep off the backend.
            clearedDrainRecord(record, observation) shouldBeSameInstanceAs record
            outstandingStopCause(record, observation) shouldBe DrainCause.REPLACEMENT
        }
    }

    @Test
    fun `a dispatched stop does not outlive the container it was aimed at`() {
        val record = drain(dispatchedAt = now)

        // Absent is the evidence the stop finished, and the only thing that retires
        // the record — `Reconciler.teardown` writes the clear on exactly this.
        stopIsInFlight(record, WorkloadObservation.Absent).shouldBeFalse()
        clearedDrainRecord(record, WorkloadObservation.Absent) shouldBe null
        outstandingStopCause(record, WorkloadObservation.Absent) shouldBe null

        // A container that has never been started, and a sandbox with no container
        // in it, are what the pass *after* a create sees. A stop is only ever
        // dispatched against a container this loop started and probed, so neither
        // can be the one that was signalled — and a record that survived into one
        // of them made the next pass drain the container that had just been built.
        (WorkloadState.entries.toSet() - signalled).forEach { state ->
            val observation = observed(state)
            stopIsInFlight(record, observation).shouldBeFalse()
            clearedDrainRecord(record, observation) shouldBe null
            outstandingStopCause(record, observation) shouldBe null
        }
    }

    /**
     * The control: the two sets are the whole enumeration and are disjoint.
     *
     * Both assertions above iterate a set derived from [signalled], so a state
     * missing from the enumeration would be tested by neither and the pair would
     * still pass. This is what makes the tuple above a classification rather than
     * two lists.
     */
    @Test
    fun `every workload state is classified`() {
        (signalled + (WorkloadState.entries.toSet() - signalled)) shouldBe WorkloadState.entries.toSet()
        signalled.size shouldBe 3
        WorkloadState.entries.size shouldBe 5
    }
}

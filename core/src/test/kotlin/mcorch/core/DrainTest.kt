package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.paper.PaperCommands
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainBlock
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.FailureStatus
import mcorch.schema.PaperServerDefaults
import mcorch.schema.RconSpec
import mcorch.schema.ServerPhase
import mcorch.schema.StorageSpec
import mcorch.store.getOrThrow
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * The drain protocol.
 *
 * The assertions that matter are the negative ones: **no stop was issued** and
 * **the container is still running**. A drain that reports failure while having
 * stopped the server has lost exactly the data this protocol exists to protect.
 */
internal class DrainTest {
    @Test
    fun `an empty server is drained, saved, stopped, removed and purged in that order`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.node.removals shouldHaveSize 1
            // The grace period comes from the definition. This is a `PaperServer`,
            // so the schema guarantees it exceeds *that server's* save timeout —
            // a relation `ProxyLifecycleSpec` deliberately does not have.
            harness.node.stops
                .single()
                .second shouldBe definition.spec.lifecycle.stopGracePeriod
            harness.store.getServer(name) shouldBe null
            // The world outlives all of it.
            harness.node.volumes shouldHaveSize 1
        }

    /**
     * **The thirtieth audit's first and second findings, in the one scenario that
     * contains both.**
     *
     * A definition that never came through `PaperServerReader` — a hand-repaired
     * store row, a migration — can carry lifecycle durations no YAML could. This one
     * carries `saveTimeout = 3h` and `stopGracePeriod = 3h1m`: a pair
     * `LifecycleSpec.init` accepts (the margin is 30s), that `DefinitionCodec`
     * decodes, and that both operational ceilings used to get wrong in opposite
     * directions.
     *
     * - **The stop grace period must not be capped below the save timeout it was
     *   validated against.** `StopGraceCeiling` clamped it to two hours, which is
     *   the schema's own words for what that state does: *"a grace period shorter
     *   than the save timeout kills the container part-way through the save"*. The
     *   drain's own flush is confirmed by then, so what SIGKILL lands in is Paper's
     *   **shutdown** save — a torn region file. The floor closes it, and note what
     *   the floor is not: the ceiling still shortens this row, down to the smallest
     *   value the schema would have accepted for the pair rather than down to `MAX`.
     * - **The save exec must be bounded.** `spec.lifecycle.drain.saveTimeout`
     *   becomes `execSync`'s gRPC deadline directly, so the same row parked a
     *   reconcile worker in `save-all flush` for three hours with no effective
     *   timeout. `ExecTimeoutCeiling` closes it, and the direction is safe: a cap
     *   can only make a save go unconfirmed sooner, and an unconfirmed save is a
     *   container this orchestrator does not stop.
     *
     * The two are asserted together because they read the **same field** and bound
     * it differently on purpose — one is how long this process waits for its own
     * flush, the other how long the container is given to finish its shutdown save
     * — and a future "make these consistent" edit is exactly what would break one of
     * them.
     */
    @Test
    fun `a store row past both ceilings keeps its grace above its save timeout, and its save exec bounded`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(saveTimeout = 3.hours, stopGracePeriod = 3.hours + 1.minutes)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            harness.node.stops shouldHaveSize 1
            val (_, grace) = harness.node.stops.single()
            // The ceiling still bites — this is not "give up on an unvalidated row",
            // and the worker is held no longer than it has to be — but it stops at
            // the floor instead of at `MAX`.
            grace shouldBe 3.hours + PaperServerDefaults.MIN_STOP_GRACE_MARGIN
            grace shouldBeLessThan definition.spec.lifecycle.stopGracePeriod
            // The property the floor exists for: the relation the schema validated
            // the pair against survives whatever the ceiling does to one half of it.
            grace shouldBeGreaterThan definition.spec.lifecycle.drain.saveTimeout
            // …and the reason that is not vacuous: this is what it used to be, which
            // is two hours *below* the save timeout it was validated against.
            grace shouldNotBe StopGraceCeiling.MAX

            // The other consumer of the same field, bounded the other way.
            val save =
                harness.node.execRequests
                    .single { it.command == PaperCommands.saveAll() }
            save.timeout.period shouldBe ExecTimeoutCeiling.MAX
            save.timeout.period shouldBeLessThan definition.spec.lifecycle.drain.saveTimeout
        }

    /**
     * The drain blocks, the container survives, and **no failure is recorded**.
     *
     * The last clause is the one that used to be false. A drain waiting for
     * people to log off is the protocol working, and recording it as a
     * `FailureStatus` made every consumer that asks "is anything wrong here" say
     * yes about a server with three people on it — which is why the escalation
     * needed a named exemption to stay quiet. Asserting the *absence* of the
     * failure is therefore not tidiness: it is the property the rest of the
     * behaviour now rests on.
     */
    @Test
    fun `a drain with players online blocks without recording a failure, and the container survives`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 3
            harness.store.deleteDefinition(name)

            // requested -> sealed -> blocked
            harness.pass(name)
            harness.pass(name)
            val outcome = harness.pass(name)

            // Unchanged: a block is requeued with backoff exactly as an abort was,
            // which is what lets it resolve when the last player leaves.
            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure shouldBe null
            // And not one level up either: the reconciler mirrors a drain's
            // failure onto observed status, so a leak there would light up the
            // dashboard's failure panel for a server nobody needs to look at.
            status.failure shouldBe null

            val blocked = drain.blocked.shouldNotBeNull()
            blocked.reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            blocked.since shouldBe harness.clock.instant()
            blocked.observations shouldBe 1
            blocked.message shouldContain "3 of 20 player slots"
            blocked.message shouldContain "keeps running"
            // What the login path is left in, from the one function that knows —
            // and this is its *third* branch, the standalone server with nothing
            // that could stop a join. The proxy branch is asserted in
            // `a proxy drain waiting for players to leave keeps its login seal on`,
            // and the pair is what makes the sentence a derivation rather than a
            // constant that happens to be right here.
            blocked.message shouldContain "keeps taking players"

            // The condition a dashboard reads, and its opposite number.
            status.conditions.single { it.type == ConditionType.DRAIN_BLOCKED }.status shouldBe ConditionStatus.TRUE
            status.attention().status shouldBe ConditionStatus.FALSE

            // A parked drain must not read as progress toward a stop.
            status.draining.shouldBeFalse()

            // Nothing was stopped, nothing was removed, nobody was kicked.
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.saves shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            harness.store.getServer(name).shouldNotBeNull()
        }

    /**
     * Re-checking a block accumulates nothing but the count of re-checks.
     *
     * CLAUDE.md invariant 5 applied to the state a drain spends the longest in.
     * `since` must not creep forward — an operator reads "waiting since" off it,
     * and a value that reset every pass would say the block is always brand new —
     * and no side effect may be issued for looking again.
     */
    @Test
    fun `a second pass against a blocked drain issues nothing and does not restart the clock`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 1
            harness.store.deleteDefinition(name)
            repeat(3) { harness.pass(name) }

            val first =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
                    .blocked
                    .shouldNotBeNull()
            val saves = harness.node.saves.size
            val probes = harness.node.probes.size

            harness.clock.advance(5.minutes)
            harness.pass(name)

            val second =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
                    .blocked
                    .shouldNotBeNull()
            second.since shouldBe first.since
            second.reason shouldBe first.reason
            // The one thing that moves, and it moves because the loop looked.
            second.observations shouldBe first.observations + 1

            // A probe is the pass looking; everything that changes the server is
            // still at zero.
            harness.node.probes.size shouldBeGreaterThan probes
            harness.node.saves shouldHaveSize saves
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
        }

    @Test
    fun `a blocked drain keeps the container running however many times it retries`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 1
            harness.store.deleteDefinition(name)

            // Reaching a retry limit is not a reason to force-stop
            // (`failure-modes.md` items 1 and 7).
            repeat(20) { harness.pass(name) }

            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a drain resumes on its own once the last player leaves`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 2
            harness.store.deleteDefinition(name)
            repeat(3) { harness.pass(name) }
            harness.status(name)?.drain?.state shouldBe DrainState.DRAIN_FAILED

            harness.node.online = 0
            harness.settle(name, limit = 12)

            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    /**
     * The sixteenth audit's first critical: a save that outruns
     * `saveEvidenceMaxGap` used to expire its own evidence.
     *
     * `pass.now` is read once at the top of `advance`. Stamping `worldSavedAt`
     * with it dates a confirmation to before the flush that earned it, so the
     * next pass measured a gap of `save + poll`, voided evidence seconds old and
     * went back to `SAVING`. Nothing failed, so nothing escalated — `Progressed`
     * every pass, for ever, re-flushing a live server.
     *
     * Forty-five seconds against a thirty-second gap. The real threshold was
     * about twenty-eight, and the schema's own default `saveTimeout` is 180: this
     * is a mature survival world being deleted, not a pathological one.
     *
     * No test could express this before, because the harness clock only ever
     * moved *between* passes — which models the backoff, and the backoff was
     * never the problem. Advancing it inside the exec is the whole point.
     */
    @Test
    fun `a save that outruns the evidence gap does not expire its own evidence`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 0
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    harness.clock.advance(45.seconds)
                }
                harness.node.defaultExec(command)
            }
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 20)

            // The drain finishes: stopped once, removed, gone from the store.
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
            // And invariant 5 held on the way — one flush, not one per pass.
            // Asserted separately from the stop because a drain that stopped
            // correctly after nine redundant saves would satisfy the lines above
            // while still being the defect.
            harness.node.execs.count { it == PaperCommands.saveAll() } shouldBe 1
        }

    @Test
    fun `a save that times out aborts the drain and the container survives`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    throw NodeException.Timeout(harness.node.name, NodeOperation.EXEC, "the save outran its timeout")
                }
                harness.node.defaultExec(command)
            }
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_SAVE_TIMEOUT
            drain.worldSaved.shouldBeFalse()
            // The request went out, so it is recorded — and never repeated.
            drain.saveRequestedAt.shouldNotBeNull()
            harness.node.saves shouldHaveSize 1

            // A timeout tells you the save has not finished. It does not tell
            // you it is now fine to stop.
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            harness.store.getServer(name).shouldNotBeNull()
        }

    @Test
    fun `a save command that exits zero without confirming is a failed save`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // Exit code zero, and an error in the output. Conflating the two is
            // `failure-modes.md` item 2.
            harness.node.savesCleanly = false
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeFalse()
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a probe that cannot run is not a zero-player report`() =
        coreTest {
            unanswerableProbeNeverReadsAsEmpty { node -> node.unreachable(NodeOperation.EXEC) }
        }

    /**
     * The same rule for the probe failure that now reads as "not joinable"
     * rather than "the runtime is unreachable" — see
     * [mcorch.core.paper.PaperServerAgent]. The reclassification must not have
     * bought a nicer bring-up message at the cost of a drain that mistakes
     * silence for an empty server: a probe stopped at its own timeout answers
     * nothing about who is online, whoever's clock ran out.
     */
    @Test
    fun `a probe the node cut short is not a zero-player report either`() =
        coreTest {
            unanswerableProbeNeverReadsAsEmpty { node -> node.commandTimedOut(NodeOperation.EXEC) }
        }

    /**
     * Drives a whole drain against a probe that never answers, and pins every
     * part of the verdict rather than just the state.
     *
     * The two callers pass the two failures that used to take *different*
     * branches of `requireEmpty` and now take one. Asserting the reason and the
     * class here, not only `DRAIN_FAILED`, is what makes this a test of the
     * merge: two branches could both reach `DRAIN_FAILED` while disagreeing
     * about whether a human needs to look.
     */
    private suspend fun unanswerableProbeNeverReadsAsEmpty(failure: (FakeNode) -> NodeException) {
        val harness = Harness()
        val definition = paperDefinition()
        val name = definition.metadata.name
        harness.declare(definition)
        harness.settle(name)
        harness.node.failAlways(NodeOperation.EXEC, failure(harness.node))
        harness.store.deleteDefinition(name)

        repeat(6) { harness.pass(name) }

        val drain =
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
        drain.state shouldBe DrainState.DRAIN_FAILED
        val drainFailure = drain.failure.shouldNotBeNull()
        drainFailure.reason shouldBe FailureReason.DRAIN_STALLED
        drainFailure.failureClass shouldBe FailureClass.RETRYABLE
        harness.node.stops shouldHaveSize 0
        harness.node.saves shouldHaveSize 0
        harness.node.removals shouldHaveSize 0
        harness.node.workload
            .shouldBeInstanceOf<WorkloadObservation.Present>()
            .state shouldBe WorkloadState.RUNNING
    }

    /**
     * The wedge that keeps a second `save-all flush` off a live server must
     * survive a probe that fails, because a pass which observed nothing has no
     * grounds to lift it.
     *
     * Fails against the old code: `forgetSaveEvidence()` cleared
     * `saveRequestedAt` along with the confirmation, which demoted the permanent
     * abort to retryable and let the next healthy pass re-send the save. Only
     * seeing a *player* may lift it — that is what makes the earlier request
     * worthless — and a flickering exec channel is not that.
     */
    @Test
    fun `a delivered save request survives a failed probe and is still never re-sent`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The save goes out and never reports completion, so the request is
            // recorded as delivered and the drain wedges permanently.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    throw harness.node.unanswered(NodeOperation.EXEC)
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)
            repeat(6) { harness.pass(name) }

            harness.node.saves shouldHaveSize 1
            val wedged =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            wedged.saveRequestedAt.shouldNotBeNull()
            wedged.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT

            // Now the probe fails — the exec channel flickers. This observes
            // nobody, so it must change nothing about what was already sent.
            harness.node.failAlways(NodeOperation.EXEC, harness.node.commandTimedOut(NodeOperation.EXEC))
            repeat(4) { harness.pass(name) }

            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .saveRequestedAt
                .shouldNotBeNull()

            // The channel recovers and the server would now save cleanly. The
            // drain must still refuse to send a second request.
            harness.node.stopFailing(NodeOperation.EXEC)
            harness.node.onExec = { command -> harness.node.defaultExec(command) }
            repeat(6) { harness.pass(name) }

            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT
        }

    /**
     * A permanent diagnosis is not resolved by somebody logging back on.
     *
     * The twenty-eighth audit's third finding, and the state it names is the most
     * ordinary one there is: a standalone server under a **delete**, a save request
     * delivered and never confirmed, and then a player. `permanentFailureStopsPasses`
     * is false under a delete — a failure must never make a workload undeletable — so
     * the passes carry on, the resume finds the server occupied, and the block used
     * to write `failure = null`.
     *
     * What a dashboard then said was *"waiting, not stuck … the drain resumes on its
     * own once it is empty"* about a server whose world may not be on disk and whose
     * delete cannot complete: when it empties, `save` takes the `saveRequestedAt`
     * branch and aborts permanently again. Nothing is stopped either way — the wedge
     * survives, which is why this is a reporting defect — but it is the report that
     * decides whether somebody reaches for `crictl stop`.
     *
     * ## Three assertions, three different consumers
     *
     * The record is the first: a permanent failure survives the block. The
     * **escalation anchor** is the second, and it is the half that made a
     * come-and-go population outlast any threshold — every clear restamped
     * `occurredAt`, so a fault present for hours reported as first seen a moment ago.
     * The third is the condition a dashboard reads: `DRAIN_BLOCKED` is derived from
     * `blocked != null && failure == null`, so the *needs nobody* sentence disappears
     * exactly here, and `NEEDS_ATTENTION` is free to fire on the anchor that survived.
     *
     * A retryable failure is still cleared by a block, and that is deliberate — a
     * healthy wait must not carry a fault the pass in front of it has already got
     * past. `a block does not survive the resume that re-derives past it` is the
     * other side of it.
     */
    @Test
    fun `a permanent save wedge survives a player logging back on`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The save goes out and never reports completion: the request is
            // recorded as delivered and only a human can say what is on disk.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    throw harness.node.unanswered(NodeOperation.EXEC)
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val wedged =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            wedged.saveRequestedAt.shouldNotBeNull()
            val diagnosis = wedged.failure.shouldNotBeNull()
            diagnosis.reason shouldBe FailureReason.DRAIN_SAVE_TIMEOUT
            diagnosis.failureClass shouldBe FailureClass.PERMANENT

            // Somebody logs back on, and keeps playing. The drain waits for them,
            // which is correct — and says nothing about the wedge that is still
            // there, which was not.
            harness.node.online = 2
            harness.clock.advance(20.minutes)
            repeat(4) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val waiting =
                harness
                    .status(name)
                    .shouldNotBeNull()
            val drain = waiting.drain.shouldNotBeNull()
            drain.blocked.shouldNotBeNull().reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            val standing = drain.failure.shouldNotBeNull()
            standing.reason shouldBe FailureReason.DRAIN_SAVE_TIMEOUT
            standing.failureClass shouldBe FailureClass.PERMANENT
            // The anchor, unmoved by the block. A population that comes and goes
            // must not reset how long this has been outstanding.
            standing.occurredAt shouldBe diagnosis.occurredAt
            // The block says so too: it is the drain's own message, and "resumes on
            // its own once it is empty" is false while this stands.
            drain.blocked.shouldNotBeNull().message shouldContain "DRAIN_SAVE_TIMEOUT"
            drain.blocked.shouldNotBeNull().message shouldContain "waiting alone does not finish this"

            // What the dashboard reads. Not *waiting, needs nobody* — somebody has
            // to confirm the world state before this delete can complete.
            waiting.condition(ConditionType.DRAIN_BLOCKED).status shouldBe ConditionStatus.FALSE
            waiting.attention().status shouldBe ConditionStatus.TRUE

            // And nothing was stopped, saved again, or removed while all that was
            // being reported wrongly.
            harness.node.saves shouldHaveSize 1
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            harness.store.getServer(name).shouldNotBeNull()
        }

    @Test
    fun `a server with world data and no RCON cannot be drained and is not stopped`() =
        coreTest {
            val harness = Harness()
            // Persistent storage, no RCON: there is no channel through which a
            // completed save could be confirmed.
            val definition = paperDefinition(rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `an ephemeral server has no world to save and stops without one`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(storage = StorageSpec.Ephemeral(), rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a runtime that stops reporting a container is not a container that has gone`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            val running = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()

            // The node can no longer see the container, while the Paper server
            // inside it carries on serving. CRI's sandbox status carries
            // container statuses in an optional field: an empty one is
            // indistinguishable from an empty sandbox, and reading it that way
            // makes a live server look like one that was never created.
            harness.node.workload =
                running.copy(
                    state = WorkloadState.SANDBOX_ONLY,
                    handle = running.handle.copy(containerId = null),
                )
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            // No probe was possible, no save was taken, and above all the
            // sandbox was not torn down — which would have killed the server
            // with no grace period and no save.
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.saves shouldHaveSize 0
            harness.store.getServer(name).shouldNotBeNull()
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_STALLED

            // And when the runtime starts reporting it again, the drain carries
            // on and finishes properly.
            harness.node.workload = running
            harness.settle(name, limit = 16)
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a teardown that removed the container and not the sandbox finishes on a later pass`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)
            // The container goes; tearing the sandbox down flakes. What the node
            // reports next is a sandbox with nothing in it — which is exactly
            // what a runtime hiding a live container looks like, and the drain
            // refuses to act on that. It has to be told the difference, or the
            // delete never completes and nobody can remove this server without
            // `crictl`.
            harness.node.sandboxRemovalFails = true

            harness.settle(name, limit = 16)

            harness.node.containerRemovals shouldHaveSize 1
            harness.node.removals shouldHaveSize 0
            harness.store.getServer(name).shouldNotBeNull()

            // The flake passes.
            harness.node.sandboxRemovalFails = false
            harness.settle(name, limit = 12)

            harness.node.removals shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
            harness.node.volumes shouldHaveSize 1
        }

    @Test
    fun `a runtime that hides a container does not lift the wedge on a delivered save`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // A save that reached the server and never confirmed. It is never
            // re-sent: only a human can say what is on disk.
            harness.node.savesCleanly = false
            harness.store.deleteDefinition(name)
            repeat(6) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .saveRequestedAt
                .shouldNotBeNull()

            // Now the runtime stops reporting the container. That pass observes
            // nothing at all — no probe is even possible — so it has no grounds
            // to decide the outstanding request no longer matters.
            val running = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload =
                running.copy(
                    state = WorkloadState.SANDBOX_ONLY,
                    handle = running.handle.copy(containerId = null),
                )
            repeat(4) { harness.pass(name) }

            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .saveRequestedAt
                .shouldNotBeNull()

            // And when the container comes back into view, the drain is still
            // wedged where a human left it: no second save on a live server.
            harness.node.savesCleanly = true
            harness.node.workload = running
            repeat(6) { harness.pass(name) }

            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
        }

    @Test
    fun `a sandbox that has never had a container is still torn down`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            // A pass that got as far as the sandbox and then died. Nothing was
            // ever created in it, so there is provably no process inside and
            // the drain may clear it away.
            harness.node.workload =
                WorkloadObservation.Present(
                    handle = WorkloadHandle(harness.node.name, "sandbox-$name"),
                    state = WorkloadState.SANDBOX_ONLY,
                    createdAt = harness.clock.instant(),
                )
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a container that has already exited is torn down without a save`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(state = WorkloadState.EXITED, exitCode = 1)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 12)

            // There is nobody connected to a reaped process and nothing to
            // flush, so no save is attempted and no stop is needed.
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a definition is never purged while its workload is still there`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)
            // The removal keeps failing, so the workload never goes away.
            harness.node.failAlways(NodeOperation.REMOVE, harness.node.unreachable(NodeOperation.REMOVE))

            repeat(12) { harness.pass(name) }

            harness.node.removals shouldHaveSize 0
            // The store would happily purge a tombstoned definition. The guard
            // is here, where the container observation is.
            harness.store.getServer(name).shouldNotBeNull()

            harness.node.stopFailing(NodeOperation.REMOVE)
            harness.settle(name, limit = 6)
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `the drain records each state so a restart can resume it`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            val states = mutableListOf<DrainState>()
            repeat(7) {
                harness.pass(name)
                harness
                    .status(name)
                    ?.drain
                    ?.state
                    ?.let(states::add)
            }

            states shouldBe
                listOf(
                    DrainState.DRAIN_REQUESTED,
                    DrainState.SEALED,
                    DrainState.TARGET_RESOLVED,
                    DrainState.TRANSFERRING,
                    DrainState.SAVING,
                    DrainState.DEREGISTERED,
                    DrainState.STOPPING,
                )
        }

    @Test
    fun `a save confirmed before a player joined does not authorise the stop after they leave`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // The drain gets as far as a confirmed save on an empty server.
            repeat(6) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeTrue()

            // Somebody joins before the stop is issued, plays for an hour, and
            // logs off. Nothing seals joins on a standalone server, so this is
            // an ordinary thing to happen mid-drain.
            harness.node.online = 1
            harness.pass(name)
            harness.node.stops shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeFalse()
            harness.clock.advance(60.minutes)
            repeat(3) { harness.pass(name) }
            harness.node.stops shouldHaveSize 0

            harness.node.online = 0
            harness.settle(name, limit = 14)

            // An hour of play is not covered by a save taken before it. The
            // drain had to ask for a second one, and only then could it stop.
            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    /**
     * Somebody logs in *while the world is being flushed*, plays, and logs off
     * before the next pass. The save that finished after they arrived must not
     * authorise the stop.
     *
     * The seventeenth audit's critical, and the window is narrow enough to be
     * worth spelling out: a standalone Paper server, a three-gigabyte world whose
     * flush takes a minute, and nothing that seals joins — there is no proxy, so
     * `SAVING` is a fully joinable state. A player connects fifteen seconds in,
     * the save confirms at t+60, and the re-probe taken immediately after it sees
     * them. Recording that reading and keeping the confirmation was worse than
     * not probing at all: `lastProbedAt` advances on any probe that *answered*,
     * whatever it counted, so the reading refreshed the evidence window instead of
     * breaking it. They place blocks for a minute and disconnect; the next pass
     * probes zero, `mayStop` passes on the pre-arrival confirmation, and the
     * container stops. Paper writes player data on quit, so the inventory comes
     * back and the blocks and entities do not.
     *
     * The load-bearing assertion is **how many saves had been sent when the stop
     * was issued**, not the final count. A drain that stopped on the stale
     * confirmation and saved again afterwards would satisfy a total of two.
     */
    @Test
    fun `a player who joins during the save is not stopped out from under once they leave`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // The first flush takes a minute, and somebody joins while it runs.
            // Only the first: the drain's second save is on an empty server, and
            // a fixture that let them back in every time would test a different
            // scenario — one nobody could ever drain.
            var joinedDuringSave = false
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll() && !joinedDuringSave) {
                    joinedDuringSave = true
                    harness.clock.advance(60.seconds)
                    harness.node.online = 1
                }
                harness.node.defaultExec(command)
            }

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            // The flush went out and the server confirmed it — and the drain is
            // waiting rather than deregistered, because the probe that followed
            // the flush found somebody on.
            harness.node.saves shouldHaveSize 1
            joinedDuringSave.shouldBeTrue()
            val waiting =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            waiting.state shouldBe DrainState.DRAIN_FAILED
            waiting.blocked.shouldNotBeNull().reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            // Waiting, not broken: a player being on the server is the protocol
            // working.
            waiting.failure shouldBe null
            // The confirmation is gone. It is the one assertion that separates
            // this from a drain that merely happened to be slow.
            waiting.worldSaved.shouldBeFalse()
            // The save did complete, and observed status still says so — from a
            // field nothing gates on, next to a drain that does not claim it.
            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .lastSaveConfirmedAt
                .shouldNotBeNull()

            // They build for ten seconds and disconnect. The number is chosen,
            // not arbitrary: it is inside `saveEvidenceMaxGap`, so the
            // observation-gap rule — which voids a confirmation the loop stopped
            // watching — cannot be what protects the world here. A minute of
            // building is protected by that rule whatever this branch does, and a
            // test written that way asserts against the guard downstream of the
            // one it is about. The exposed window is exactly this one: shorter
            // than the gap, longer than nothing.
            harness.clock.advance(10.seconds)
            harness.node.online = 0

            var savedWhenStopped = 0
            harness.recordingStops { savedWhenStopped = harness.node.saves.size }
            repeat(14) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            // The drain finished, and the world on disk includes what they built:
            // the stop waited for a second flush taken after they had gone.
            savedWhenStopped shouldBe 2
            harness.node.stops shouldHaveSize 1
            harness.node.saves shouldHaveSize 2
            harness.store.getServer(name) shouldBe null
        }

    /**
     * The same pass run twice against the same world: one flush, one block, and a
     * `blocked since` that does not restart.
     *
     * The branch above is reached by a step that has *already issued a side
     * effect* when it decides to park, which is the shape idempotency is easiest
     * to lose in — the obvious repair for "the confirmation is worthless" is to
     * ask for another flush, and asking on every pass would put a `save-all flush`
     * into a live server twice a minute for as long as somebody was playing.
     */
    @Test
    fun `a drain parked by the probe after its own save does not flush again while they play`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            var joinedDuringSave = false
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll() && !joinedDuringSave) {
                    joinedDuringSave = true
                    harness.clock.advance(60.seconds)
                    harness.node.online = 1
                }
                harness.node.defaultExec(command)
            }

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            val first =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
                    .blocked
                    .shouldNotBeNull()

            // Six more passes with the same player still connected.
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(45.seconds)
            }

            val later =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // No second flush, no stop, no removal, and the container is exactly
            // where it was.
            harness.node.saves shouldHaveSize 1
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            // The wait is one wait, counted up rather than restarted, and it is
            // still not a failure however many times it repeats.
            later.blocked.shouldNotBeNull().since shouldBe first.since
            later.blocked.shouldNotBeNull().observations shouldBeGreaterThan first.observations
            later.failure shouldBe null
        }

    /**
     * The save-confirming pass writes down **two** instants, and they are not the
     * same instant.
     *
     * `worldSavedAt` is when `save-all flush` came back; the occupancy's
     * `observedAt` is when the probe *after* it answered. They were one value read
     * once before the probe, which is the shape of the sixteenth audit's first
     * critical — an instant that dates a reading to before the work that produced
     * it — and the seventeenth round accepted that this fixture could not tell the
     * two apart. It can: [FakeNode.exec] routes every command through `onExec`,
     * `mc-monitor` included, so a ping that costs five seconds makes the gap
     * something a test can read off the recorded status.
     *
     * Fused back into one instant read before the probe, the two are equal and this
     * fails. That is the whole assertion; the drain reaching `DEREGISTERED` on one
     * flush is here so it cannot pass by never getting to the branch.
     */
    @Test
    fun `the occupancy recorded with a confirmed save is read after the probe that took it`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            harness.node.onExec = { command ->
                if (command.firstOrNull() == "mc-monitor") harness.clock.advance(5.seconds)
                harness.node.defaultExec(command)
            }

            repeat(6) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            status.drain.shouldNotBeNull().state shouldBe DrainState.DEREGISTERED
            harness.node.saves shouldHaveSize 1

            val confirmedAt =
                status.storage
                    .shouldNotBeNull()
                    .lastSaveConfirmedAt
                    .shouldNotBeNull()
            status.players
                .shouldNotBeNull()
                .observedAt shouldBe confirmedAt.plusSeconds(5)
        }

    /** Runs [body] when the stop is issued, so a test can assert on the order of side effects. */
    private fun Harness.recordingStops(body: () -> Unit) {
        val runtime = node.onStop
        node.onStop = { present ->
            body()
            runtime(present)
        }
    }

    @Test
    fun `a save does not survive a window in which the loop could not see who was online`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(6) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.DEREGISTERED

            // The exec path goes unhealthy. The loop keeps running and keeps
            // asking — the gap between observations never grows — but every
            // answer is "cannot tell", which is not a zero-player report and
            // must not be treated as one in either direction. Ten minutes of
            // this is ten minutes in which players can arrive, play and log off
            // without a single pass seeing them: nothing seals joins on a
            // standalone server.
            harness.node.failAlways(NodeOperation.EXEC, harness.node.unreachable(NodeOperation.EXEC))
            repeat(30) {
                harness.clock.advance(20.seconds)
                harness.pass(name)
            }
            harness.node.stops shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeFalse()

            // The exec path recovers and the server is empty again — true, and
            // silent about the last ten minutes.
            harness.node.stopFailing(NodeOperation.EXEC)
            var savedBeforeStopping = 0
            harness.recordingStops { savedBeforeStopping = harness.node.saves.size }

            harness.settle(name, limit = 16)

            // The stop was allowed only after a save taken since the blind
            // window, not on the one confirmed before it.
            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
            savedBeforeStopping shouldBe 2
        }

    @Test
    fun `a save does not survive a window in which the loop was not running at all`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(6) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeTrue()

            // The orchestrator stops. The container carries on serving, players
            // play and log off, and half an hour later the loop comes back,
            // reads the drain out of the store, and resumes it at DEREGISTERED
            // with a confirmed save. The container never restarted, so nothing
            // about the workload says anything happened — the only witness that
            // nobody was watching is the gap in the loop's own observations.
            harness.clock.advance(30.minutes)

            var savedBeforeStopping = 0
            harness.recordingStops { savedBeforeStopping = harness.node.saves.size }
            harness.pass(name)

            harness.node.stops shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeFalse()

            harness.settle(name, limit = 16)

            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
            savedBeforeStopping shouldBe 2
        }

    @Test
    fun `a runtime that reports no container start time does not send the drain round in circles`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // No start time to compare a confirmation against. Rejecting every
            // confirmation on that basis is the tempting reading, and it makes
            // the drain save, decline to stop, save again — asking a live
            // server to flush its world for ever.
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(startedAt = null)
            harness.store.deleteDefinition(name)

            harness.settle(name, limit = 16)

            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a drain resumed from the store does not stop on a save from the previous container`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // Reach DEREGISTERED: zero players, save confirmed, stop not issued.
            repeat(6) { harness.pass(name) }
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DEREGISTERED
            drain.worldSaved.shouldBeTrue()
            harness.node.stops shouldHaveSize 0

            // The loop stops here. A day passes, the container is restarted by
            // hand — a new process, a new world in memory — and the loop comes
            // back to a drain record that still says the world is saved. The
            // one probe it takes reports zero players, which is true and says
            // nothing about the day in between.
            harness.clock.advance(24.hours)
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(startedAt = harness.clock.instant())

            harness.pass(name)

            // No stop on a day-old confirmation from a process that is gone.
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING

            harness.settle(name, limit = 14)

            // It went back and saved again before it stopped anything.
            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
        }

    @Test
    fun `a stop is not re-issued while players are on a container that would not stop`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // The stop is issued and does not take: the container is still
            // running on the next pass.
            harness.node.onStop = { present -> present }
            repeat(7) { harness.pass(name) }
            harness.node.stops shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.STOPPING

            // Somebody is on the server that refused to stop. Re-issuing a stop
            // is normally safe — the save is on disk — but not when the save no
            // longer describes what they are doing.
            harness.node.online = 2
            repeat(4) { harness.pass(name) }

            harness.node.stops shouldHaveSize 1
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.worldSaved.shouldBeFalse()
            // The other blocked call site, and the same answer: somebody is on
            // the server, so this is a wait rather than a fault. Nothing here
            // went wrong — the stop simply may not be re-issued while they are
            // connected and the save no longer describes what they are doing.
            drain.failure shouldBe null
            drain.blocked.shouldNotBeNull().reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
        }

    /**
     * The other arm of `STOPPING`'s asymmetry: a probe that stops *answering* does
     * not block the re-issue.
     *
     * `STOPPING` is the one stop-bearing state the zero-player gate does not wrap,
     * and this is the reason. A container inside its stop grace period is expected
     * to go quiet — that is what a server shutting down looks like — so routing this
     * state through `requireEmpty`, which aborts on a probe that could not answer at
     * all, would park the drain precisely when it is working correctly: backend
     * already deregistered, kill already counting down, and nothing left that would
     * re-register it.
     *
     * The sibling above pins the arm that *does* block, a positive count. Both are
     * asserted now because the class KDoc states these two gates as the whole of the
     * stop safety argument, and only one arm of the second one was covered.
     *
     * Nine seconds of silence, and the number is a choice rather than a round
     * figure. Past `saveEvidenceMaxGap` (30s) the confirmation is voided for want of
     * a witness and the drain goes back to `SAVING` — correct, and a different rule
     * from the one under test. Inside it, the only thing that could stop the
     * re-issue is the gate.
     */
    @Test
    fun `a container that has gone quiet inside its grace period is still stopped`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // The stop is issued and does not take: the container is still running
            // on the next pass, which is what brings the re-issue into play.
            harness.node.onStop = { present -> present }
            repeat(7) { harness.pass(name) }
            harness.node.stops shouldHaveSize 1
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.STOPPING

            // And now it stops answering its Server List Ping.
            harness.node.joinable = false
            repeat(3) {
                harness.pass(name)
                harness.clock.advance(3.seconds)
            }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.STOPPING
            // Neither an abort nor a block. Nothing was observed that says anything
            // is wrong, and the drain is not waiting on anybody.
            drain.failure shouldBe null
            drain.blocked shouldBe null
            // The instrument is not vacuous: the silence reached the re-issue, and
            // the re-issue happened anyway.
            harness.node.stops.size shouldBeGreaterThan 1
            // The other half of the gate is untouched by the silence: the stop is
            // re-issued *because* the confirmation is still current, and no second
            // flush is sent at a container that is already going away.
            drain.worldSaved.shouldBeTrue()
            harness.node.saves shouldHaveSize 1
        }

    /**
     * The third arm of `STOPPING`'s gate, and the one with a world in it: a stop is
     * not re-issued at a container that restarted underneath the drain.
     *
     * The two tests above are about the player count. This one is about the other
     * half of the same `if`, `mayStop`, which had **no behavioural coverage in
     * `STOPPING` at all** until the twenty-first audit went looking: narrowing the
     * gate to `!mayStop(…) && !drain.playersEvacuated` kept the token, the call
     * count and the enclosing function, so `DrainWiringTest` stayed green — and
     * `playersEvacuated` is true of every drain that has reached this state, so the
     * narrowed gate is an unconditional re-issue.
     *
     * What that costs is a world. The stop does not take; an operator restarts the
     * container by hand; players join the new process and build. The confirmation
     * this drain is holding is about the process that is gone, which is exactly what
     * [DrainStatus.saveIsCurrent] compares against the container's start time, and
     * the re-issued stop would take the new session's world with it — the grace
     * period is a safety net, not a save.
     *
     * The probe is **silent** here on purpose, and that is the whole difficulty: a
     * container that has just been restarted has not finished booting, so the
     * occupied arm cannot fire and `requireEmpty` is deliberately not in the way
     * (`STOPPING` lets silence through, which the test above pins). The gate is the
     * only thing left standing.
     *
     * The correct answer is not to give up either: the drain goes back for another
     * save and re-issues the stop once it has one, so the last assertion is about
     * *ordering* rather than about a refusal.
     */
    @Test
    fun `a stop is not re-issued at a container that restarted underneath the drain`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // The stop is issued and does not take: the container is still running
            // on the next pass, which is what brings the re-issue into play.
            harness.node.onStop = { present -> present }
            repeat(7) { harness.pass(name) }
            harness.node.stops shouldHaveSize 1
            harness.node.saves shouldHaveSize 1
            val stopping =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stopping.state shouldBe DrainState.STOPPING
            // The confirmation this drain reached the stop on. It is about to stop
            // describing the container in front of it.
            stopping.worldSaved.shouldBeTrue()
            stopping.playersEvacuated.shouldBeTrue()

            // An operator restarts it by hand. Same workload, new process: whatever
            // was in memory is gone, and anything somebody does from here is not on
            // disk. Five seconds, so nothing here turns on the evidence ageing out —
            // the container's start time is what makes the confirmation worthless.
            harness.clock.advance(5.seconds)
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(startedAt = harness.clock.instant())
            // …and it is still booting, so its Server List Ping does not answer.
            harness.node.joinable = false

            val outcome = harness.pass(name)

            // No second stop at a container this drain has never saved.
            harness.node.stops shouldHaveSize 1
            val resaving =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            resaving.state shouldBe DrainState.SAVING
            resaving.worldSaved.shouldBeFalse()
            // Nothing is wrong and nobody is being waited on: one forced re-save is
            // the protocol working, and it must not read as either.
            resaving.failure shouldBe null
            resaving.blocked shouldBe null
            // The pass says which gate refused, because "Progressed" on its own is
            // what a re-issued stop reports too.
            outcome.detail shouldContain "the stop is not re-issued until the world is saved again"

            // It finishes booting. The drain saves again and only *then* re-issues
            // the stop, which is the ordering the gate exists for — a refusal that
            // never lifted would be a server nobody can retire.
            harness.node.joinable = true
            var savesWhenReStopped: Int? = null
            repeat(8) {
                harness.pass(name)
                if (savesWhenReStopped == null && harness.node.stops.size > 1) {
                    savesWhenReStopped = harness.node.saves.size
                }
                harness.clock.advance(2.seconds)
            }
            // The instrument is not vacuous: a second stop really was reached, and
            // the second save came first.
            savesWhenReStopped.shouldNotBeNull() shouldBe 2
        }

    @Test
    fun `an RCON client that never reached the server may try again`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // `rcon-cli` cannot connect: a non-zero exit with nothing from the
            // server in it. Nothing was delivered, so nothing has to be
            // preserved and a later attempt is safe.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "", "dial tcp 127.0.0.1:25575: connection refused")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
            // The distinction the whole fix turns on: no delivered request, so
            // no record of one, so the server is not wedged.
            drain.saveRequestedAt shouldBe null
            drain.worldSaved.shouldBeFalse()
            harness.node.stops shouldHaveSize 0

            // The hiccup passes and the drain finishes on its own.
            harness.node.onExec = { command -> harness.node.defaultExec(command) }
            harness.settle(name, limit = 14)

            harness.node.saves
                .isNotEmpty()
                .shouldBeTrue()
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    @Test
    fun `a save the server acknowledged and did not finish is still never re-sent`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // The server replied — it started saving — and the client then
            // failed. The request was delivered, so it must not be delivered
            // again on a guess.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "Saving the game (this may take a moment!)", "connection reset")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)

            repeat(10) { harness.pass(name) }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            drain.saveRequestedAt.shouldNotBeNull()
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
        }

    @Test
    fun `enabling RCON on a container that has none does not wedge the server`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The operator follows the advice on the stalled drain and enables
            // RCON. It is in the spec hash, so it asks for a recreate — and the
            // recreate has to drain the container that is running, which was
            // created with RCON disabled and has nothing listening. The loop
            // must not believe the new definition, must not send a save into
            // that socket, and above all must not record a request it never
            // delivered: that record is what used to make the state permanent
            // and unrecoverable.
            harness.store.putDefinition(paperDefinition(rcon = RconSpec.Enabled(passwordSecret = secretRef())))
            repeat(8) { harness.pass(name) }

            val stalled =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stalled.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_STALLED
            stalled.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            stalled.saveRequestedAt shouldBe null
            stalled.worldSaved.shouldBeFalse()
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.creates shouldHaveSize 1
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            // It says which way is out, and the way out is not another edit
            // against this container.
            stalled.failure
                .shouldNotBeNull()
                .message
                .contains("revert spec.network.rcon")
                .shouldBeTrue()

            // Reverting works, which is the whole point: the server goes back to
            // running with nothing left over.
            harness.store.putDefinition(definition)
            harness.settle(name, limit = 8).shouldBeInstanceOf<ReconcileOutcome.Settled>()
            val recovered = harness.status(name).shouldNotBeNull()
            recovered.drain shouldBe null
            recovered.ready.shouldBeTrue()
            harness.node.stops shouldHaveSize 0
        }

    @Test
    fun `a deleted server whose container has no RCON is finished off by hand and then torn down`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)
            repeat(8) { harness.pass(name) }

            val stalled =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stalled.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            stalled.saveRequestedAt shouldBe null
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            // A deleted definition cannot be edited back into shape — the store
            // refuses to write a tombstoned name — so the only way out is a
            // human, and the message has to say so rather than pointing at an
            // edit that cannot be made.
            stalled.failure
                .shouldNotBeNull()
                .message
                .contains("save the world and stop the container yourself")
                .shouldBeTrue()

            // So they do exactly that. The loop has to still be watching, or the
            // advice it gave is a dead end: a permanently failed drain that is
            // never looked at again cannot notice the container is gone.
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(state = WorkloadState.EXITED, exitCode = 0)
            harness.settle(name, limit = 12)

            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
            // And the world it was never able to save is still on disk.
            harness.node.volumes shouldHaveSize 1
        }

    @Test
    fun `switching a running server to ephemeral storage is refused rather than stopped without a save`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // `storage.mode` is in the spec hash, so this asks for a recreate —
            // and the recreate drains the container that is holding the world.
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            repeat(8) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            // Nothing was drained, nothing was saved, and above all nothing was
            // stopped without one.
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness.node.creates shouldHaveSize 1
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING

            // Reverting the edit puts the server back where it was.
            harness.store.putDefinition(definition)
            harness.settle(name).shouldBeInstanceOf<ReconcileOutcome.Settled>()
            harness.node.stops shouldHaveSize 0
        }

    /**
     * The save in this one *completes*, so what has to survive a rejected write
     * is `worldSavedAt`. The assertion used to name `saveRequestedAt`, because
     * both facts lived in that one field; splitting them moved this case to the
     * other one. The behaviour is unchanged and the closing assertion — one save
     * after four more passes — is the same one it always was.
     *
     * `a delivered save request survives a rejected observation too` is the
     * other half, where the save is *not* confirmed and `saveRequestedAt` is
     * what must survive.
     */
    @Test
    fun `a rejected observation still records that a save completed`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // An image change, so the drain is a replacement and the definition
            // is still writable while it runs.
            harness.store.putDefinition(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            // Up to the pass that requests the save.
            repeat(5) { harness.pass(name) }
            harness.node.saves shouldHaveSize 0

            // The operator edits the definition again while the saving pass
            // runs, so the observation carrying the save record is rejected.
            harness.store.beforeStatusWrite = {
                harness.store.putDefinition(paperDefinition(maxPlayers = 41))
            }
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness.node.saves shouldHaveSize 1

            // The record has to have survived the rejection, or the next pass
            // asks a live server to save all over again.
            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.worldSavedAt.shouldNotBeNull()
            // And the request is not *also* outstanding: a confirmation beside a
            // live request is what used to wedge the next `SAVING`.
            drain.saveRequestedAt shouldBe null
            repeat(4) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
        }

    /**
     * The same rejected write, with a save the server never confirmed. Here
     * `saveRequestedAt` is the durable record — the wedge — and losing it sends
     * a second `save-all flush` to a live server.
     */
    @Test
    fun `a delivered save request survives a rejected observation too`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // Exit zero, no completion reported: delivered, never confirmed.
            harness.node.savesCleanly = false
            harness.store.putDefinition(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            repeat(5) { harness.pass(name) }
            harness.node.saves shouldHaveSize 0

            harness.store.beforeStatusWrite = {
                harness.store.putDefinition(paperDefinition(maxPlayers = 41))
            }
            harness.pass(name)
            harness.node.saves shouldHaveSize 1

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.saveRequestedAt.shouldNotBeNull()
            drain.worldSavedAt shouldBe null

            // Never a second one, and the container keeps running.
            harness.node.savesCleanly = true
            repeat(4) { harness.pass(name) }
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
        }

    @Test
    fun `a drain that is broken and stuck says so, and still does not stop anything`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // The wrong RCON password: permanent in practice, indistinguishable
            // from a hiccup to the loop, so it is retried for ever and nothing
            // ever asks anybody to look at it.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "", "authentication failed")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)

            repeat(6) { harness.pass(name) }
            val early = harness.status(name).shouldNotBeNull()
            early.attention().status shouldBe ConditionStatus.FALSE
            val quietSince = early.attention().lastTransitionAt

            harness.clock.advance(11.minutes)
            val outcome = harness.pass(name)

            val status = harness.status(name).shouldNotBeNull()
            val failure =
                status.drain
                    .shouldNotBeNull()
                    .failure
                    .shouldNotBeNull()
            // The escalation is a condition now, not a marker at the front of a
            // message. `lastTransitionAt` moving is the half a string could never
            // carry: it says *when* this started needing a human, which is what an
            // alert fires on.
            val attention = status.attention()
            attention.status shouldBe ConditionStatus.TRUE
            attention.lastTransitionAt shouldNotBe quietSince
            attention.lastTransitionAt shouldBe harness.clock.instant()
            // The message tells an operator the two things they would otherwise
            // get wrong: the loop has not given up, and it is not going to stop
            // the server to unblock itself.
            attention.message shouldContain "keeps retrying"
            attention.message shouldContain "will not be stopped"
            status.conditions
                .single { it.type == ConditionType.DRAINING }
                .message shouldContain "not recovering on its own"
            // The count an operator is shown has to be the count. A resume that
            // threw away the failure it was retrying made every pass of a
            // failing drain report its first attempt, occurring now.
            failure.attempts shouldBeGreaterThan 1

            // Everything else is exactly as it was. `failure-modes.md` item 7:
            // at a limit you stop trying, you do not stop the container — and
            // this does not even stop trying.
            failure.failureClass shouldBe FailureClass.RETRYABLE
            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a drain waiting for players to log off is never escalated, however long it waits`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 2
            harness.store.deleteDefinition(name)

            repeat(3) { harness.pass(name) }
            // Four hours of people playing on a server somebody asked to delete.
            // That is the protocol working as designed, and it resolves itself.
            harness.clock.advance(4.hours)
            repeat(3) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            // Through the new mechanism, not the deleted exemption. Four hours is
            // twenty-four times `drainAttentionAfter`, so if this drain recorded a
            // retryable failure of any reason at all the flag would be up: it is
            // quiet because there is no failure to escalate from.
            drain.failure shouldBe null
            drain.blocked.shouldNotBeNull().reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            // Crying wolf on a busy evening every backoff interval is how an
            // operator learns the signal means nothing, and it is the only
            // escalation signal there is.
            status.attention().status shouldBe ConditionStatus.FALSE
            status.conditions
                .single { it.type == ConditionType.DRAINING }
                .message shouldNotContain "not recovering on its own"
            // Quiet is not the same as silent. The operator is told this is a
            // wait rather than being told nothing, which is the difference
            // between reading the fleet table and going to look at the host.
            val blockedCondition = status.conditions.single { it.type == ConditionType.DRAIN_BLOCKED }
            blockedCondition.status shouldBe ConditionStatus.TRUE
            blockedCondition.message shouldContain "waiting, not stuck"

            harness.node.online = 0
            harness.settle(name, limit = 16)
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    /**
     * A drain that has stopped for good raises the flag, and raises it at once.
     *
     * This is the case the escalation was originally *inverted* on: it required
     * `RETRYABLE`, so the states whose documented remedy is "a human resolves
     * this" were the only ones never flagged. A server with world data and no
     * RCON cannot be drained at all — no edit reaches the running container —
     * and it sits `DRAIN_FAILED`, running and joinable, for ever.
     *
     * Immediately, with no clock advanced, and that is asserted rather than
     * incidental. `isBlockedByPermanentFailure` returns before a non-terminating
     * server is observed at all, so a permanent abort can be the *last* status
     * ever written for it — a threshold that had not been crossed by then would
     * never be re-evaluated, and the flag would never appear.
     */
    @Test
    fun `a drain that failed permanently needs a human straight away`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition(rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT

            val attention = status.attention()
            attention.status shouldBe ConditionStatus.TRUE
            // No time has been advanced past `drainAttentionAfter`, so this
            // fired on the pass that recorded the failure rather than on a
            // timer that a permanent failure would never live to see.
            attention.lastTransitionAt shouldBe harness.clock.instant()
            // And it tells the truth about what happens next. Saying "the loop
            // keeps retrying" here would be the reason an operator waits.
            attention.message shouldContain "Nothing further will be attempted"
            attention.message shouldNotContain "keeps retrying"

            // The drain-safety assertions that always matter: nothing was
            // stopped, and the server an operator is being called about is
            // still the running, joinable one.
            harness.node.stops shouldHaveSize 0
            harness.node.saves shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    /**
     * The other permanent case, and the one the flag is most needed for: a save
     * that was delivered and never confirmed.
     *
     * By design the request is never re-sent — only a human can say what is on
     * disk — so this drain will sit here for ever. It is also the state where
     * the dashboard reads worst: `:api` ranks `TERMINATING` above everything for
     * its badge, so without the flag a fleet table shows a server that is still
     * up and still joinable as though it were on its way out.
     */
    @Test
    fun `a save that was delivered and never confirmed raises the flag too`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // Exit zero, no completion reported: delivered, unconfirmed, never
            // re-sent.
            harness.node.savesCleanly = false
            harness.store.deleteDefinition(name)

            repeat(8) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            drain.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_SAVE_TIMEOUT
            drain.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            status.attention().status shouldBe ConditionStatus.TRUE

            // Exactly one save, and the container is still up: the flag changes
            // what is reported, never what is done.
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    /**
     * A drain blocked on **fleet capacity** does need a human, once it has been
     * blocked for long enough.
     *
     * This is the behaviour change, and it is the inverse of the test above.
     * `DRAIN_NO_DESTINATION` used to mean both "people are playing" and "the fleet
     * is full", so it had to be exempted from the escalation to keep the first
     * one quiet — which silenced the second one too. The second one is exactly
     * what the flag is for: the search ran, every server was full, and the drain
     * will sit there until an operator adds capacity. Nothing about waiting fixes
     * it.
     *
     * Driven through the drain record rather than through the loop on purpose.
     * `:core` cannot yet *produce* this failure — there is no proxy and so no
     * destination search — so the honest level to pin it at is the rule and the
     * condition derived from it, which is what a dashboard reads either way.
     */
    @Test
    fun `a drain blocked on fleet capacity is escalated once it has been blocked for long enough`() =
        coreTest {
            val startedAt = MutableClock().instant()
            val noCapacity =
                DrainStatus(
                    state = DrainState.DRAIN_FAILED,
                    startedAt = startedAt,
                    enteredStateAt = startedAt,
                    failure =
                        FailureStatus(
                            reason = FailureReason.DRAIN_NO_DESTINATION,
                            // A literal at every call site, as the invariant in
                            // `:schema` asks. Never computed from the reason.
                            failureClass = FailureClass.RETRYABLE,
                            message = "no server in the fleet had capacity for these players",
                            occurredAt = startedAt,
                        ),
                )

            // The control, and it is what makes this a test of the timer rather
            // than of a hard-wired true: nine minutes in, the drain still has time
            // for the fleet to change under it.
            // The real threshold rather than one turned off, so these assertions
            // say the *time* arm decides here — the fixture's ledger is zero, and a
            // ledger arm that had crept into deciding this would make the first line
            // true.
            noCapacity.escalated(startedAt.plusSeconds(9 * 60), 10.minutes, LEDGER).shouldBeFalse()
            noCapacity.escalated(startedAt.plusSeconds(11 * 60), 10.minutes, LEDGER).shouldBeTrue()

            // And it reaches the condition, which is the artefact an alert fires
            // on. `DRAIN_BLOCKED` stays false: this one is not a healthy wait.
            val status =
                draftStatus(
                    previous = null,
                    name = resourceName("survival-01"),
                    generation = 1,
                    now = startedAt.plusSeconds(11 * 60),
                    phase = ServerPhase.RUNNING,
                    attentionAfter = 10.minutes,
                    attentionLedger = LEDGER,
                    drain = noCapacity,
                )
            status.attention().status shouldBe ConditionStatus.TRUE
            status.conditions.single { it.type == ConditionType.DRAIN_BLOCKED }.status shouldBe ConditionStatus.FALSE
        }

    /**
     * The escalation rule has no exempt reason left, and cannot grow one back by
     * accident.
     *
     * `escalates` no longer takes a [FailureReason] at all — the parameter went
     * with the exemption — so the only inputs are the class and the elapsed time.
     * A permanent failure fires at once; a retryable one fires on the threshold.
     */
    @Test
    fun `the escalation rule is the class and the clock, and nothing else`() =
        coreTest {
            val startedAt = MutableClock().instant()

            escalates(
                failingSince = startedAt,
                failureClass = FailureClass.PERMANENT,
                now = startedAt,
                after = 10.minutes,
            ).shouldBeTrue()
            escalates(
                failingSince = startedAt,
                failureClass = FailureClass.RETRYABLE,
                now = startedAt.plusSeconds(9 * 60),
                after = 10.minutes,
            ).shouldBeFalse()
            escalates(
                failingSince = startedAt,
                failureClass = FailureClass.RETRYABLE,
                now = startedAt.plusSeconds(10 * 60),
                after = 10.minutes,
            ).shouldBeTrue()

            // A drain with nothing recorded against it is never escalated, whatever
            // its age — and that one line is what keeps a blocked drain quiet now
            // that there is no list of reasons to consult.
            DrainStatus(
                state = DrainState.DRAIN_FAILED,
                startedAt = startedAt,
                enteredStateAt = startedAt,
                blocked =
                    DrainBlock(
                        reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                        message = "2 of 20 player slots are in use",
                        since = startedAt,
                    ),
            ).escalated(startedAt.plusSeconds(60 * 60 * 4), 10.minutes, LEDGER).shouldBeFalse()
        }

    /**
     * The escalation withdraws itself when the drain gets somewhere — one
     * *ordinary* pass after it does.
     *
     * It is derived from the drain's recorded failure rather than latched, and a
     * pass that makes progress clears that failure — so there is no second thing
     * to remember to reset. A latched escalation would leave a finished drain
     * telling an operator to go and look at a server that no longer exists.
     *
     * ## Rewritten for the fifteenth audit: the resume itself no longer withdraws it
     *
     * This asserted that the *first* pass after the fix cleared the flag, and that
     * pass is the resume out of `DRAIN_FAILED`. Clearing there is what critical 2
     * of that audit turned on: a drain parked on a refused container stop resumes
     * into `SAVING` every time the save evidence ages out, saves for real, and the
     * save is genuine work — so the failure carrying the escalation anchor was
     * deleted every cycle and the fifteen-minute threshold could not be reached by a
     * stop that had been refused for six hours.
     *
     * The drain now proves it has recovered by completing one ordinary step *after*
     * the resume, which is what this asserts in two halves. The behaviour that
     * changed is a delay of one pass in the withdrawal, in the safe direction; what
     * did not change is that it withdraws itself rather than needing a reset.
     */
    @Test
    fun `an escalated drain stops asking for a human once it can finish`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "", "authentication failed")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)
            repeat(6) { harness.pass(name) }
            harness.clock.advance(11.minutes)
            harness.pass(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.TRUE

            // Somebody fixes the RCON password. The next pass is the resume: it
            // saves the world for real, and the flag stays up, because a resume that
            // did work is exactly what a drain looping between a good save and a
            // refused stop looks like.
            harness.node.onExec = { command -> harness.node.defaultExec(command) }
            val savesBefore = harness.node.saves.size
            harness.pass(name)
            // The premise, asserted rather than assumed: that pass really did send a
            // save. Without this the assertion below would also pass against a build
            // where the resume did nothing at all.
            harness.node.saves shouldHaveSize savesBefore + 1
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.TRUE

            // The ordinary pass that follows is the proof, and it withdraws it.
            harness.pass(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.FALSE
            // And the drain goes on to finish normally, having saved exactly once
            // after the fix.
            harness.settle(name, limit = 12)
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    /**
     * A pass that never reaches the drain does not withdraw the escalation.
     *
     * This is the reason the condition is derived from the drain record rather
     * than handed to the status draft by the pass that decided it. A node failure
     * drafts a status carrying the drain forward untouched and knows nothing
     * about escalation, so a caller-supplied fact would be absent there and the
     * condition would flap off and on again between two passes of one stuck
     * drain — every backoff interval, for as long as the node was sick.
     */
    @Test
    fun `an escalation survives a pass that could not reach the node at all`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    ExecOutcome(1, "", "authentication failed")
                } else {
                    harness.node.defaultExec(command)
                }
            }
            harness.store.deleteDefinition(name)
            repeat(6) { harness.pass(name) }
            harness.clock.advance(11.minutes)
            harness.pass(name)
            val escalatedAt = harness.status(name).shouldNotBeNull().attention()
            escalatedAt.status shouldBe ConditionStatus.TRUE

            // The node goes away entirely: this pass observes nothing and writes
            // a status from the previous one.
            harness.node.failAlways(NodeOperation.OBSERVE, harness.node.unreachable(NodeOperation.OBSERVE))
            harness.clock.advance(1.minutes)
            harness.pass(name)

            val during = harness.status(name).shouldNotBeNull().attention()
            during.status shouldBe ConditionStatus.TRUE
            // Still the same escalation, not a new one: an alert keyed on the
            // transition time must not re-fire because the node hiccupped.
            during.lastTransitionAt shouldBe escalatedAt.lastTransitionAt
            harness.node.stops shouldHaveSize 0
        }

    @Test
    fun `a workload that does not say what it holds is saved before it is replaced`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // A container carrying none of this orchestrator's facts about
            // itself: created by an older build, or by hand. Absent is not
            // `false`, and neither the edited definition nor the storage status
            // derived from it is a second opinion worth having.
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(labels = emptyMap())

            var savedBeforeStopping = 0
            harness.recordingStops { savedBeforeStopping = harness.node.saves.size }
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.settle(name, limit = 16)

            // The edit is applied. Refusing it would make every replacement of a
            // genuinely ephemeral pre-label lobby a permanent failure, since
            // nothing here can tell that case from a transition. What must not
            // happen is the container going down without its world on disk.
            savedBeforeStopping shouldBe 1
            harness.node.stops shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .storage
                .shouldBeInstanceOf<StorageRequest.Ephemeral>()
        }

    @Test
    fun `a workload that says it holds a world refuses the same edit, because that one is a transition`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            repeat(8) { harness.pass(name) }

            harness
                .status(name)
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT
            harness.node.saves shouldHaveSize 0
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
        }

    /**
     * The refusal outlives the container, because the drain is what ends it.
     *
     * The thirty-fourth audit's second critical, and it is a consequence of the
     * thirty-third's fix rather than of the guard. The refusal used to be
     * conditioned on `RUNNING`, which is a state the *drain itself* takes away:
     *
     * 1. an image edit starts a `REPLACEMENT` and the stop is dispatched;
     * 2. a second edit lands inside the grace period — `storage.mode` persistent →
     *    ephemeral — and is refused, permanently;
     * 3. the refusal keeps the dispatch record now, so the drain is in `STOPPING`,
     *    so `parkedOnTheFailure()` is false, so the permanent-failure gate does not
     *    arm and passes keep coming. Before that fix the record was deleted here,
     *    the gate armed, and the server froze with the edit unapplied;
     * 4. the signalled container exits on its own;
     * 5. `RUNNING` stops being true, the refusal stops firing, the drain resumes and
     *    the teardown runs;
     * 6. and the create applies the ephemeral definition.
     *
     * No world is discarded — the volume's files are untouched and the drain flushed
     * the container before it stopped — which is why invariant 2 holds literally and
     * this is still a critical. The server comes back on a **freshly generated empty
     * world**, everything built from then on lives in a writable layer that dies with
     * the next replacement, and the observed status the operator would recover from
     * has stopped naming the volume that holds the real world.
     *
     * `Labels.WORLD_DATA` is read off the container and is still there when it has
     * `EXITED`, so the discriminator outlives the process and the refusal now does
     * too. The close of the test is the other half: a refusal that cannot be lifted
     * is a wedge, so reverting `spec.storage.mode` has to let the drain finish the
     * container it signalled and apply the edit that was never in question.
     */
    @Test
    fun `a refused storage transition is not applied by the container exiting underneath it`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            val replacement = "docker.io/itzg/minecraft-server:2026.7.0"
            harness.declare(definition)
            harness.settle(name)
            harness.node.volumes shouldHaveSize 1

            // A container that takes the stop and does not exit, which is what every
            // container looks like for the length of its grace period.
            harness.node.onStop = { present -> present }
            harness.store.putDefinition(paperDefinition(image = replacement))
            repeat(8) { harness.pass(name) }
            val stopping =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stopping.state shouldBe DrainState.STOPPING
            stopping.stopDispatchedAt.shouldNotBeNull()
            // Not a count: a standalone drain whose container does not exit re-issues
            // the stop, which is `awaitStopped` doing its job and has nothing to do
            // with what this test is about. What matters is that one went out.
            harness.node.stops.shouldNotBeEmpty()

            // The second edit, landing inside that grace period.
            harness.store.putDefinition(paperDefinition(image = replacement, storage = StorageSpec.Ephemeral()))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            // The signalled container exits on its own. Nothing about the edit has
            // changed; only the state the refusal used to read.
            val signalled = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload =
                signalled.copy(
                    state = WorkloadState.EXITED,
                    finishedAt = harness.clock.instant(),
                    exitCode = 0,
                )
            repeat(6) { harness.pass(name) }

            // The edit is still refused, and — the assertion this test exists for —
            // it is **not applied**: no teardown, and no container built from a
            // definition that mounts nothing.
            harness.node.removals shouldHaveSize 0
            harness.node.creates shouldHaveSize 1
            val refused = harness.status(name).shouldNotBeNull()
            val failure = refused.failure.shouldNotBeNull()
            failure.failureClass shouldBe FailureClass.PERMANENT
            failure.message shouldContain "storage.mode"
            // …and the loop has not erased its own record of which volume holds the
            // world. `storageStatus` used to derive from the *definition*, so a
            // refusal that drafted it reported `persistent = false, volumeName = null`
            // for a server it is refusing to make ephemeral — leaving recovery to
            // depend on the operator remembering the name. Both halves are read off
            // the container's own labels now, and both discriminate here precisely
            // because the definition disagrees with them: it says `ephemeral`, so a
            // record derived from it is `persistent = false, volumeName = null`.
            val storage = refused.storage.shouldNotBeNull()
            storage.persistent.shouldBeTrue()
            storage.volumeName shouldBe resourceName("survival-01-world")

            // The lever: reverting the transition is what lifts the refusal, and the
            // drain then finishes the container it had already signalled and applies
            // the edit that was never in question.
            harness.store.putDefinition(paperDefinition(image = replacement))
            harness.settle(name, limit = 16)
            harness.node.removals shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .storage
                .shouldBeInstanceOf<StorageRequest.Persistent>()
            harness.node.creates[1]
                .image.canonical shouldBe replacement
            // The world was on the volume throughout, and nothing removed it.
            harness.node.volumes shouldHaveSize 1
        }

    /**
     * A row that records no storage at all is never handed the edit's answer — and
     * now gets the container's own.
     *
     * The round-34 fix kept the *container's* storage record instead of drafting one
     * from the edited definition, and it kept a fallback for the case where there is
     * no previous record: `pass.previous?.storage?.copy(bound = true) ?:
     * pass.storageStatus(observation)`. The fallback was the erasure again, on the one
     * population least able to afford it. `StatusCodec.readStorage` answers **null**
     * whenever `storage.persistent` is absent — every status row written before the
     * field existed — so those rows reached the refusal with nothing to carry forward,
     * took the fallback, and had `persistent = false, volumeName = null` derived from
     * the very definition the pass was refusing to apply.
     *
     * No world was lost by it. What was produced is a **false sentence at the surface
     * an operator diagnoses from**: `StatusDrafting.worldSavedMessage` renders
     * `persistent == false` as "ephemeral storage: there is no world to save", for a
     * server the loop is at that moment refusing to make ephemeral, and whose volume
     * name is recorded nowhere else in the system.
     *
     * ## What changed, and why the claim is stronger rather than retired
     *
     * `Pass.storageStatus` reads the workload's own labels now instead of
     * `spec.storage`, so this row is no longer left with nothing: the container the
     * refusal is *about* says `world-data=true` and names the volume it mounts, and
     * both are observations, so the row gets them. Absence still stays absence where
     * there is nothing to observe — [StorageObservationTest] holds that half — and
     * what must never appear here is the edit's answer, which for this row would be
     * `persistent = false, volumeName = null`.
     *
     * The failure assertion is the positive control: it is what proves the refusal
     * fired in this scenario at all, without which the assertions below are satisfied
     * by a pass that never reached the guard.
     */
    @Test
    fun `a status row that predates the storage field is not given a false one by the refusal`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The row as an older build left it: everything else observed, and no
            // storage block, which is exactly what the decoder hands back for one.
            val settled = harness.status(name).shouldNotBeNull()
            settled.storage.shouldNotBeNull()
            harness.store.putStatus(settled.copy(storage = null)).getOrThrow()

            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val refused = harness.status(name).shouldNotBeNull()
            refused.failure.shouldNotBeNull().message shouldContain "storage.mode"
            // The container's answers, both of which are the opposite of the edit's:
            // a record derived from the definition here reads `persistent = false,
            // volumeName = null`. The row started with no storage block at all, so
            // neither value can have been carried — they came from the container or
            // from nowhere.
            val storage = refused.storage.shouldNotBeNull()
            storage.persistent.shouldBeTrue()
            storage.volumeName shouldBe resourceName("survival-01-world")
            // …and the sentences an operator reads say nothing about a world that is
            // not there. Both are derived from `storage.persistent == false`, which is
            // what the fallback used to write.
            refused.condition(ConditionType.WORLD_SAVED).message shouldNotContain "ephemeral storage"
            refused.condition(ConditionType.VOLUME_BOUND).message shouldNotContain "ephemeral storage"
        }

    @Test
    fun `zero players and a confirmed save are both recorded before the stop`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // Up to the pass that issues the stop.
            repeat(7) { harness.pass(name) }

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            drain.playersEvacuated.shouldBeTrue()
            drain.worldSaved.shouldBeTrue()
            status.storage
                .shouldNotBeNull()
                .lastSaveConfirmedAt
                .shouldNotBeNull()
            harness.node.stops shouldHaveSize 1
        }

    /**
     * The sixteenth audit's first critical, seen from the other end: a drain that
     * saves, loses the confirmation, saves again and never reaches the stop.
     *
     * The primary cause is fixed — evidence used to be stamped from `pass.now`,
     * before the flush that earned it — and this is about every *other* cause,
     * because the loop's report was identical for all of them: `Progressed` on
     * every pass, `DRAINING` on the badge, no failure, no attempt count, no
     * escalation, and a full `save-all flush` at a live server twice a minute for
     * ever.
     *
     * The scenario is a loop that is behind rather than a container that is
     * broken, which is why it is silent: forty seconds between two passes of the
     * *same* server is a busy orchestrator, not a fault, and it is longer than the
     * thirty a save confirmation survives. Every pass then voids a confirmation
     * that was fine when it was taken. Nothing in the drain, the container or the
     * probe is wrong, and nothing was ever recorded.
     *
     * The fixed spacing is the mechanism here rather than a shortcut. A requeue
     * delay is a *floor* — `ReconcileLoop` promises not to come back sooner — so a
     * saturated loop arrives late whatever the last outcome was, and modelling the
     * poll/backoff alternation would be modelling the case that is not the
     * problem.
     */
    @Test
    fun `a drain that keeps re-saving and never reaches the stop asks for a human`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            var firstAnchor: java.time.Instant? = null
            val attempts = mutableListOf<Int>()
            repeat(40) {
                harness.pass(name)
                harness.clock.advance(40.seconds)
                val drain = harness.status(name)?.drain
                if (firstAnchor == null) firstAnchor = drain?.resaveForcedAt
                drain?.failure?.let { attempts += it.attempts }
            }

            // The instrument is not vacuous: the drain really did keep flushing a
            // live server's world, which is the cost this makes visible.
            harness.node.saves.size shouldBeGreaterThan 3

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // Set once. A restamped anchor is an allowance handed back on every
            // lap, and the escalation could then never be reached however long the
            // cycle ran — the mistake `enteredStateAt` made for drain step 4.
            drain.resaveForcedAt.shouldNotBeNull() shouldBe firstAnchor.shouldNotBeNull()

            val failure = drain.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.DRAIN_STALLED
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "never reaches the stop"
            // The count rises and the anchor does not move, which is what makes
            // the threshold reachable at all.
            attempts.distinct().size shouldBeGreaterThan 3
            failure.attempts shouldBeGreaterThan 3
            JavaDuration
                .between(failure.occurredAt, harness.clock.instant())
                .toKotlinDuration() shouldBeGreaterThan 10.minutes
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.TRUE

            // Nothing was done to the container, which is the half of
            // `failure-modes.md` item 7 that never moves: the report changed and
            // that is all.
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            harness.store.getServer(name).shouldNotBeNull()
        }

    /**
     * The discriminator for the test above: **one** forced re-save is the protocol
     * working, and must record nothing.
     *
     * An orchestrator restart mid-drain leaves a confirmation nobody was watching
     * behind, the drain saves again, and it finishes. If that raised a failure the
     * mechanism would be an alarm on every deploy, and the fifteen-minute
     * threshold would be the only thing between it and alarm fatigue — which is
     * not a design, it is a delay.
     */
    @Test
    fun `a single forced re-save is not a failure and does not ask for a human`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(6) { harness.pass(name) }
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeTrue()

            // The loop is down for half an hour. The container never restarted, so
            // the only witness that nobody was watching is the gap in the loop's
            // own observations — and it costs exactly one more flush.
            harness.clock.advance(30.minutes)
            harness.pass(name)

            val resaving =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            resaving.resaveForcedAt.shouldNotBeNull()
            resaving.failure shouldBe null
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.FALSE

            harness.settle(name, limit = 16)

            harness.node.saves shouldHaveSize 2
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    /**
     * The second discriminator: the bound on a re-saving drain is **one lap**, and
     * a lap contains a save.
     *
     * It was one `saveEvidenceMaxGap` — thirty seconds, from a different module —
     * on the argument that a confirmation older than one is worthless. True of the
     * confirmation, false of the cycle: the schema sizes a save at `saveTimeout`,
     * three minutes by default, so a world that takes longer than half a minute to
     * flush could not complete a single forced re-save inside the allowance it was
     * being judged against. Two ordinary loop stalls then produced a failure
     * telling an operator it "does not clear on its own" about a drain that
     * cleared on its own two passes later — round sixteen's first critical
     * relocated.
     *
     * The scenario is deliberately the innocent one. Nothing here is broken: a
     * three-gigabyte world, an orchestrator that fell behind twice, and a drain
     * that saves again each time and finishes. The detection it must not weaken is
     * the test above, which keeps stalling for ever.
     */
    @Test
    fun `a forced re-save is given time for the save itself before the drain is called stalled`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // Every flush takes a minute, which is twice the evidence gap on its
            // own and is the whole difficulty.
            harness.node.onExec = { command ->
                if (command == PaperCommands.saveAll()) {
                    harness.clock.advance(60.seconds)
                }
                harness.node.defaultExec(command)
            }

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness.node.saves shouldHaveSize 1

            // Stall one: the loop is away for half an hour. Nobody was watching,
            // so the confirmation goes and the drain saves again.
            harness.clock.advance(30.minutes)
            harness.pass(name)
            val anchor =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
                    .resaveForcedAt
                    .shouldNotBeNull()
            harness.clock.advance(2.seconds)
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.node.saves shouldHaveSize 2

            // Stall two, and this one only has to be wider than the evidence gap
            // to void the confirmation again — thirty-five seconds between two
            // passes of the same server is a busy orchestrator, not a fault.
            harness.clock.advance(35.seconds)
            harness.pass(name)

            val circling =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            // The instrument is not vacuous: this really is a *second* forced
            // re-save measured against the first one's anchor, which is the only
            // situation the bound governs.
            circling.resaveForcedAt shouldBe anchor
            circling.state shouldBe DrainState.SAVING
            // And it is not a failure. Nothing is wrong with this server.
            circling.failure shouldBe null

            harness.clock.advance(2.seconds)
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            harness.node.saves shouldHaveSize 3
            harness.node.stops shouldHaveSize 1
            harness.store.getServer(name) shouldBe null
        }

    /**
     * The allowance for a lap is the **save timeout**, and an unrelated grace
     * period does not buy the defect more time to run.
     *
     * The bound was `saveEvidenceMaxGap + stopGracePeriod`, using the grace period
     * as a stand-in for the save timeout on the strength of a schema guarantee that
     * one exceeds the other. Sound in direction, and it hands an operator a lever
     * they did not know they were pulling: `stopGracePeriod` is their number, capped
     * at two hours, and setting a long one for a reason of their own — a world that
     * takes for ever to unload, a runtime that is slow to reap — bought a two-hour
     * escalation latency on a defect whose honest lap is about a minute. Meanwhile
     * the drain flushes a multi-gigabyte world roughly once a minute, records no
     * failure, and reads `NEEDS_ATTENTION = false` throughout.
     *
     * The scenario is the same silent livelock as the test above — a loop arriving
     * every forty seconds, which is longer than a confirmation survives — with the
     * two durations pulled apart. Thirteen minutes is far past
     * `30s + saveTimeout` and nowhere near `30s + 2h`.
     */
    @Test
    fun `a long stop grace period does not delay the report of a drain that keeps re-saving`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition(saveTimeout = 3.minutes, stopGracePeriod = 2.hours)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            repeat(20) {
                harness.pass(name)
                harness.clock.advance(40.seconds)
            }

            // The instrument is not vacuous: this really is a drain flushing a live
            // server's world over and over.
            harness.node.saves.size shouldBeGreaterThan 3

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            val failure = drain.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.DRAIN_STALLED
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "never reaches the stop"

            // The prognosis is gone from the wording, and this is the assertion for
            // it. "It does not clear on its own" is the opposite of what a retryable
            // abort means — `resumeInto` re-enters on the next pass — and an
            // operator told a drain will not clear intervenes on the container,
            // where the only intervention is a stop with no save. What replaces it
            // is the measured fact and the two checks that would explain it.
            failure.message shouldNotContain "It does not clear on its own"
            failure.message shouldContain "was first voided"
            failure.message shouldContain "it has happened again since"
            // And the fact is stated once. `resaveForcedAt` is never cleared by the
            // success path, so it spans laps that each ended with a real save and a
            // re-issued stop — deliberately, or the detector would hand its own
            // allowance back once per lap. The cost of that span is that the number
            // may include time the drain spent making progress, so it is reported as
            // what it measures and not restated as "nothing has worked for Ns".
            failure.message shouldNotContain "has not cleared on its own in"
            // The span is not only left unclaimed, it is disclaimed. An anchor hours
            // old and a cause a minute old produce the same number — the first pass
            // of an unrelated later defect aborts on an interval that was mostly a
            // working drain — and the operator this message is for cannot tell those
            // apart from the number.
            failure.message shouldContain "not a duration of downtime"

            // Nothing was done to the container. `failure-modes.md` item 7 does not
            // move because the bound moved.
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            harness.store.getServer(name).shouldNotBeNull()
        }

    /**
     * A container that will not exit, reported without anything being escalated at
     * it.
     *
     * Two separate defects live in this branch. The re-issue used to claim
     * [DrainProgress.workDone] — reached *because* the container is still running,
     * so the previous stop did not take — and that claim deleted whatever failure
     * the drain was carrying, in the one state where the container is meant to be
     * going away. And a container that simply never dies looped here for ever with
     * `Retry` and no `FailureStatus` at all: the loop was trying, nobody was told,
     * and `crictl` was the only thing that would ever end it.
     *
     * What does **not** change is what happens to the container: the same stop,
     * the same grace period, no kill, no removal. `failure-modes.md` item 7.
     */
    @Test
    fun `a container that never exits is reported and is never killed harder`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 5.minutes))
            val definition = paperDefinition(saveTimeout = 1.minutes)
            val name = definition.metadata.name
            val grace = definition.spec.lifecycle.stopGracePeriod
            harness.declare(definition)
            harness.settle(name)
            // The stop is accepted and the container carries on running. The probe
            // keeps answering, so the save confirmation stays current and this is
            // the elapsed-time rule rather than the re-save one.
            harness.node.onStop = { present -> present }
            harness.store.deleteDefinition(name)

            repeat(7) { harness.pass(name) }
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.STOPPING
            harness.node.stops shouldHaveSize 1

            // Inside the grace period a container that is still running is what a
            // stop in progress looks like, and there is nothing to report.
            harness.clock.advance(25.seconds)
            harness.pass(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .failure shouldBe null

            repeat(40) {
                harness.pass(name)
                harness.clock.advance(25.seconds)
            }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.STOPPING
            val failure = drain.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.DRAIN_STALLED
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "grace period"
            failure.attempts shouldBeGreaterThan 3
            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.TRUE

            // The stop is re-issued and nothing else is done: the same grace
            // period every time, no zero-grace kill, no removal — and no second
            // world save, because the confirmation that authorised the stop is
            // still current. That last one is the idempotency assertion: forty
            // passes over an unchanged state added no side effect the first one
            // had not already made.
            harness.node.stops.size shouldBeGreaterThan 3
            harness.node.stops
                .map { it.second }
                .distinct() shouldBe listOf(grace)
            harness.node.saves shouldHaveSize 1
            harness.node.removals.shouldBeEmpty()
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    /**
     * The re-issue does not delete the failure the drain came into `STOPPING`
     * carrying.
     *
     * Item 11 of the failure modes, reopened by one flag. The drain here aborts on
     * a refused stop, resumes, gets the stop accepted — and the container does not
     * go away, so every later pass re-issues it. While that pass claimed
     * `workDone`, the pass after the resume cleared the record: `attempts` back to
     * one, `occurredAt` restamped, and the fifteen-minute threshold unreachable
     * while the loop hammered a container that was never going to exit.
     *
     * The assertion is on `occurredAt` rather than on mere non-nullness, because a
     * *new* failure recorded by the grace-period rule would satisfy the weaker
     * one. Nothing advances the clock here, so that rule cannot fire and the only
     * failure that can be present is the one carried in.
     */
    @Test
    fun `a re-issued stop does not delete the failure the drain was carrying`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.onStop = { present -> present }
            // One refused stop, then the runtime takes it — and the container
            // keeps running anyway.
            harness.node.failOnce(NodeOperation.STOP, harness.node.unreachable(NodeOperation.STOP))
            harness.store.deleteDefinition(name)

            repeat(7) { harness.pass(name) }
            val aborted =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            aborted.state shouldBe DrainState.DRAIN_FAILED
            val recorded = aborted.failure.shouldNotBeNull()

            // The resume issues the stop for real, which is work; the pass after
            // it only re-issues one the container ignored, which is not.
            repeat(3) { harness.pass(name) }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.STOPPING
            val surviving = drain.failure.shouldNotBeNull()
            surviving.occurredAt shouldBe recorded.occurredAt
            surviving.attempts shouldBeGreaterThan 0
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    /**
     * A container stop the runtime keeps refusing asks for a human, however many
     * times the drain has to save again while it waits.
     *
     * Critical 2 of the fifteenth audit, and the same root as the ephemeral case in
     * `ProxyDrainTest` with a slower clock and no proxy in sight. A drain parked on
     * a refused stop re-enters through the ladder, and once the save evidence has
     * aged past `saveEvidenceMaxGap` the ladder lands on `SAVING` rather than
     * `DEREGISTERED`. The save that follows is **real** — a `save-all flush` goes
     * out and the server confirms it — so a rule that cleared the recorded failure
     * whenever the resumed step did work cleared it here, every cycle. `attempts`
     * went back to 1, `occurredAt` was restamped with it, and a stop refused for six
     * hours reported three attempts and never reached the fifteen-minute threshold:
     * the anchor destroyed nine times before it could fire.
     *
     * This is why [DrainController]'s rule is not "did the step do work" alone. The
     * drain proves it has recovered by completing one ordinary step *after* the
     * resume; the save is work, and it is not the step that is failing.
     *
     * Nothing here is a data-loss risk — no container is stopped and nobody is
     * kicked — and the assertions say so. What is lost is the escalation.
     */
    @Test
    fun `a stop the runtime keeps refusing escalates even though each resume saves for real`() =
        coreTest {
            val harness = Harness(config = ReconcilerConfig(drainAttentionAfter = 10.minutes))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The runtime refuses every stop, retryably: a containerd that is up and
            // will not take the request.
            harness.node.failAlways(NodeOperation.STOP, harness.node.unreachable(NodeOperation.STOP))
            harness.store.deleteDefinition(name)

            // The clock is moved the way `ReconcileLoop` would move it, because the
            // spacing of the passes is the mechanism and not a detail: a pass that
            // reports `Progressed` is requeued at the poll interval, and one that
            // retries waits out a backoff. Forty-five seconds is that backoff once it
            // has grown, and it is longer than the thirty the save evidence survives —
            // so the resume after every refused stop finds the evidence gone and
            // saves again for real.
            //
            // A fixed spacing measures the wrong thing in both directions. Two seconds
            // throughout never expires the evidence and the drain never re-saves;
            // forty-five throughout expires it *before the stop is ever attempted*, so
            // the drain shuttles `DEREGISTERED` -> `SAVING` without ever calling the
            // runtime, and a test written that way asserts against a scenario the
            // reconcile loop cannot produce.
            val attempts = mutableListOf<Int>()
            repeat(40) {
                val outcome = harness.pass(name)
                harness.clock.advance(if (outcome is ReconcileOutcome.Progressed) 2.seconds else 45.seconds)
                harness
                    .status(name)
                    ?.drain
                    ?.failure
                    ?.let { attempts += it.attempts }
            }

            // The instruments are not vacuous. The drain really did re-save on the
            // way round — which is the whole difficulty, since that is genuine work —
            // and it really did keep asking the runtime to stop the container.
            harness.node.saves.size shouldBeGreaterThan 3
            harness.node.calls.count { it == NodeOperation.STOP } shouldBeGreaterThan 3

            attempts.size shouldBeGreaterThan 4
            attempts.distinct().size shouldBeGreaterThan 3

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            val failure = drain.failure.shouldNotBeNull()
            failure.attempts shouldBeGreaterThan 3
            failure.reason shouldBe FailureReason.DRAIN_STALLED
            failure.failureClass shouldBe FailureClass.RETRYABLE
            JavaDuration
                .between(failure.occurredAt, harness.clock.instant())
                .toKotlinDuration() shouldBeGreaterThan 10.minutes

            harness
                .status(name)
                .shouldNotBeNull()
                .attention()
                .status shouldBe ConditionStatus.TRUE

            // No stop ever took, the container is still running, and the definition
            // is still there. The escalation is the only thing that moved.
            harness.node.stops.shouldBeEmpty()
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
            harness.store.getServer(name).shouldNotBeNull()
        }

    /**
     * The exemption that lets an operator finish a drain the loop cannot.
     *
     * A drain parked in `DRAIN_FAILED` carries a message telling the operator to
     * save and stop the container themselves, and promising the teardown finishes
     * once a stopped container is observed. On a **terminating** definition that
     * was true, because a delete keeps the passes running. On this one — an
     * ordinary edit — the gate returned before the pass observed anything, so the
     * stop the message asked for was one nothing was left to notice, and the
     * server sat over a container that had already exited. Issue #1.
     *
     * The setup is the one that produces it in practice: a persistent server
     * created with RCON disabled, then edited to enable it. The edit reshapes the
     * container, the replacement drain needs a confirmed save, and the container
     * that is *running* has no channel to confirm one — so the drain aborts with
     * the server still up and still joinable.
     */
    @Test
    fun `a stalled drain finishes its teardown once the operator stops the container`() =
        coreTest {
            val harness = Harness()
            val name = paperDefinition().metadata.name
            harness.declare(paperDefinition(rcon = RconSpec.Disabled))
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            // The edit that cannot reach the running container.
            harness.declare(paperDefinition(rcon = RconSpec.Enabled(passwordSecret = secretRef())))
            repeat(6) { harness.pass(name) }

            val stalled =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            stalled.state shouldBe DrainState.DRAIN_FAILED
            // The whole point of the state: nothing was stopped, and the loop said so.
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()

            // The operator does what the message asks and stops it by hand.
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(state = WorkloadState.EXITED, exitCode = 0)

            repeat(4) { harness.pass(name) }

            // ...and the loop notices, which is the sentence the status had been
            // making all along.
            harness.node.removals.shouldNotBeEmpty()
        }

    /**
     * The gate expires on an operator action, never on a containerd restart.
     *
     * The exemption above is the first thing that makes a gated pass touch the
     * node, and a node that cannot answer has said nothing about whether the
     * operator stopped anything. Recording that failure would *replace* the
     * permanent one the gate reads, so a transient `RUNTIME_UNREACHABLE` would
     * open the gate on the next pass and resume a drain against a server that is
     * still running and may still have players on it. The drain-auditor's finding
     * on the first draft of this change.
     */
    @Test
    fun `a node that cannot answer a gated pass does not reopen the gate`() =
        coreTest {
            val harness = Harness()
            val name = paperDefinition().metadata.name
            harness.declare(paperDefinition(rcon = RconSpec.Disabled))
            harness.settle(name)
            harness.declare(paperDefinition(rcon = RconSpec.Enabled(passwordSecret = secretRef())))
            repeat(6) { harness.pass(name) }

            val stalled = harness.status(name).shouldNotBeNull()
            stalled.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT

            // The node cannot answer the one question a gated pass asks.
            harness.node.failAlways(NodeOperation.OBSERVE, harness.node.unreachable(NodeOperation.OBSERVE))
            repeat(3) { harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>() }

            // Still shut: the permanent record stands rather than being overwritten
            // by a retryable one, and nothing was re-issued at a running server.
            val after = harness.status(name).shouldNotBeNull()
            after.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT
            after.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * The neighbouring case, which must **not** move: a permanent failure with no
     * drain at all.
     *
     * `parkedOnTheFailure` answers true for a null drain, so an exemption keyed on
     * it alone would lift here too — and lifting here recreates a container that
     * exited on its own, which the loop deliberately does not do. The exemption
     * asks for a drain record and a state precisely so that this stays shut.
     */
    @Test
    fun `a container that exited under a permanent failure with no drain is still not recreated`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(state = WorkloadState.EXITED, exitCode = 137, reason = "OOMKilled")

            harness.pass(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldBeNull()
            repeat(4) { harness.pass(name) }

            harness.node.creates shouldHaveSize 1
            harness.node.starts shouldHaveSize 1
            harness.node.removals.shouldBeEmpty()
        }

    private companion object {
        /**
         * `ReconcilerConfig.drainAttentionLedger`'s shipped value, restated rather
         * than turned off.
         *
         * Every fixture in this file carries a zero ledger, so passing the real
         * threshold keeps the second escalation arm switched on and quiet — which is
         * a stronger statement than passing something unreachable. An assertion here
         * that started depending on the ledger arm would show up as a value this
         * constant cannot produce.
         */
        const val LEDGER: Int = 6
    }
}

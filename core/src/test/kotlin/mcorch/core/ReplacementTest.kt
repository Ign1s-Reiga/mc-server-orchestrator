package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.MemoryQuantity
import mcorch.schema.ServerPhase
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A definition change that needs the container recreated.
 *
 * `failure-modes.md` item 5: drain the old container first, then create the
 * replacement. Never both at once.
 */
internal class ReplacementTest {
    @Test
    fun `an image change drains the old container before creating the replacement`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            harness.settle(name, limit = 14)

            // One drain, one stop, one removal, and only then a second create.
            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.node.removals shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .image.canonical shouldBe "docker.io/itzg/minecraft-server:2026.7.0"
            harness.node.pulls shouldHaveSize 2
        }

    /**
     * The sixteenth audit's second critical: the drain advanced exactly one pass
     * after the repair and then froze with the container stopped.
     *
     * `settleRecords`' hysteresis keeps a failure across a resume so the
     * escalation anchor is not restamped. It kept the `PERMANENT` class with it,
     * and `isBlockedByPermanentFailure` reads that class — so the pass that
     * resumed, stopped the container and moved to `STOPPING` wrote the retained
     * failure at the *new* generation, closing the gate behind itself.
     * `awaitStopped` and `teardown` never ran: workload never removed, sandbox
     * never reclaimed, replacement never created, and the status frozen quoting
     * a node fault the operator had already fixed.
     *
     * Asserting on the removal and the replacement rather than on the failure
     * being cleared, because a cleared failure is not what an operator was
     * promised — a running replacement is, and the audit asked for it that way.
     */
    @Test
    fun `an operator edit that repairs a refused stop finishes the replacement`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            // Rejected, not Timeout: a refusal the loop classifies PERMANENT, so
            // the gate arms rather than the drain simply retrying.
            harness.node.failAlways(
                NodeOperation.STOP,
                NodeException.Rejected(harness.node.name, NodeOperation.STOP, "the runtime refused the stop"),
            )
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            harness.settle(name, limit = 14)

            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                ?.state shouldBe DrainState.DRAIN_FAILED
            harness.node.removals shouldHaveSize 0
            harness.node.creates shouldHaveSize 1

            // The operator fixes the node and edits the definition, which is the
            // documented remedy and the only thing that lifts the gate.
            harness.node.clearFailures(NodeOperation.STOP)
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.1"))
            harness.settle(name, limit = 24)

            // The whole point: it got all the way through, not one step in.
            harness.node.removals shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .image.canonical shouldBe "docker.io/itzg/minecraft-server:2026.7.1"
        }

    /**
     * The twenty-ninth audit's first finding: the same repair, with somebody
     * playing at the moment the operator makes it.
     *
     * A generation bump is the only thing that lifts
     * `isBlockedByPermanentFailure` on a server nobody has deleted, and it lifts it
     * for exactly one pass. Since the twenty-eighth audit a block *keeps* a standing
     * permanent failure and parks in `DRAIN_FAILED` like an abort — so that one pass
     * used to write the retained failure back at the new generation and close the
     * gate behind itself, and the drain never got to the repaired step. Every
     * further edit went the same way for as long as anybody was connected, and no
     * status was written meanwhile, so the operator could not even see the server
     * empty.
     *
     * Asserted on the **replacement running**, not on the failure being cleared: a
     * cleared record is not what the operator was promised, and it is the assertion
     * the sibling test above already chose for the same reason.
     *
     * The player logs on *before* the edit, so the very first pass at the new
     * generation is the blocked one. That is the ordering the defect needs, and
     * writing it the other way round — edit, then player — would let a converging
     * pass slip through and pass against the defect.
     */
    @Test
    fun `an operator edit is not spent on a player who logged on while the drain was frozen`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            harness.node.failAlways(
                NodeOperation.STOP,
                NodeException.Rejected(harness.node.name, NodeOperation.STOP, "the runtime refused the stop"),
            )
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            harness.settle(name, limit = 14)

            val frozen = harness.status(name).shouldNotBeNull()
            frozen.drain.shouldNotBeNull().state shouldBe DrainState.DRAIN_FAILED
            frozen.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT
            harness.node.removals.shouldBeEmpty()

            // Somebody logs back on to the server that is still running, and only
            // then does the operator fix the node and edit the definition.
            harness.node.online = 3
            harness.node.clearFailures(NodeOperation.STOP)
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.1"))

            // The loop keeps looking while they play. It has to: nobody is going to
            // bump the generation again to tell it the server emptied.
            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            val waiting = harness.status(name).shouldNotBeNull()
            waiting.drain
                .shouldNotBeNull()
                .blocked
                .shouldNotBeNull()
                .reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
            // Waiting is all it did: no stop was issued at a populated container and
            // the container is still there.
            harness.node.removals.shouldBeEmpty()
            harness.node.creates shouldHaveSize 1
            // The permanent diagnosis is still standing beside the block — the block
            // is what stops the gate arming, not a clear of the record.
            waiting.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT

            // They log off. Nothing else changes, and no further edit is made.
            harness.node.online = 0
            harness.settle(name, limit = 24)

            harness.node.removals shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .image.canonical shouldBe "docker.io/itzg/minecraft-server:2026.7.1"
        }

    @Test
    fun `a replacement never happens under live players`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 4

            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            repeat(10) { harness.pass(name) }

            harness.node.creates shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
            harness.node.removals shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .state shouldBe DrainState.DRAIN_FAILED
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a change that does not touch the container shape is not a replacement`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // Raising a drain timeout must not restart a server with players
            // on it. It is read fresh from the definition every pass.
            harness.declare(paperDefinition(saveTimeout = 6.minutes))
            val outcome = harness.settle(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Settled>()
            harness.node.creates shouldHaveSize 1
            harness.node.stops shouldHaveSize 0
            harness.status(name).shouldNotBeNull().observedGeneration shouldBe 2L
        }

    @Test
    fun `a world saved during a drain does not keep the WORLD_SAVED condition true afterwards`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            harness.settle(name, limit = 16)

            // A week of play on the replacement container. Nothing has been
            // saved since the drain that replaced the old one.
            harness.clock.advance((7 * 24 * 60).minutes)
            harness.settle(name, limit = 6)

            val status = harness.status(name).shouldNotBeNull()
            // The audit record of the last confirmed save is kept…
            status.storage
                .shouldNotBeNull()
                .lastSaveConfirmedAt
                .shouldNotBeNull()
            // …and it is not evidence that the world *now* is on disk. Reading
            // the condition off that timestamp made a server that has been
            // running untouched for a week report itself saved.
            status.conditions
                .single { it.type == ConditionType.WORLD_SAVED }
                .status shouldBe ConditionStatus.FALSE
        }

    /**
     * The twenty-fifth audit's third warning: the pre-flight existed on the proxy
     * path only, and the kind that holds worlds went without it.
     *
     * `storage.mountPath`, `rcon.secret` and the *proxy's* forwarding secret are all
     * in a `PaperServer`'s spec hash, and every one of them can be edited into a
     * value the create refuses permanently — a relative mount path, a secret
     * reference that is not staged yet. A stored row reaches the same place without
     * an edit at all, because `DefinitionCodec` does not re-run the YAML reader's
     * validation. So: hash mismatch, drain, world saved, container stopped, container
     * removed, and only then a create that refuses for ever. No world is lost, and a
     * running server is taken down and cannot come back.
     *
     * ## Why the scenario has nobody on the server
     *
     * Zero players is the number that makes this test about *this* guard. With
     * players on, the drain's own zero-player gate blocks it at step 2 and nothing is
     * stopped whatever this code does — the assertions below would pass against the
     * build that has the defect. The destructive path needs an empty server, and an
     * empty server is the ordinary state of one being replaced out of hours.
     *
     * `stops` and `removals` are the load-bearing assertions. `drain` being null is
     * the discriminator for the *shape*: the question is asked before the protocol
     * begins, rather than by a drain that starts and then thinks better of it.
     */
    @Test
    fun `a server whose replacement the node cannot build is not drained, and keeps running`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            // The shape round 24 moved out of `StorageRequest.Persistent.init` and
            // into the node: a value only the create refuses, and permanently.
            harness.node.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(
                    harness.node.name,
                    NodeOperation.CREATE,
                    "`storage.mountPath` must be an absolute path inside the container",
                ),
            )
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))

            repeat(8) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            // Nothing was taken away on the strength of being able to build a
            // replacement that cannot be built.
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            harness.node.creates shouldHaveSize 1
            harness.node.saves.shouldBeEmpty()

            val status = harness.status(name).shouldNotBeNull()
            status.phase shouldBe ServerPhase.RUNNING
            status.ready.shouldBeTrue()
            status.drain.shouldBeNull()
            val failure = status.failure.shouldNotBeNull()
            // Retryable, and the class is a mechanism rather than a preference: the
            // remedy is an operator staging something, which bumps no generation, and
            // `isBlockedByPermanentFailure` lifts on nothing else.
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "cannot build the replacement"
            failure.message shouldContain "its world where it is"
        }

    /**
     * The same guard, with players on: they are never sealed, moved or waited for.
     *
     * The test above is the one that reddens against the defect; this is the one
     * that says what an operator sees. A drain that starts and blocks on players is
     * a `DRAIN_INITIATED` condition, a server excluded from being anybody's transfer
     * destination and a proxy-side seal — all of it in aid of a replacement that
     * cannot happen.
     */
    @Test
    fun `players are not drained for a replacement the node cannot build`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.online = 4

            harness.node.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(harness.node.name, NodeOperation.CREATE, "the artefact is not on this node"),
            )
            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val status = harness.status(name).shouldNotBeNull()
            status.drain.shouldBeNull()
            status.drainInitiated.shouldBeFalse()
            status.ready.shouldBeTrue()
            status.players.shouldNotBeNull().online shouldBe 4
            harness.node.stops.shouldBeEmpty()
        }

    /**
     * The twenty-sixth audit's fourth warning: the pre-flight's exemption for a
     * drain already in flight is justified by the *end* of the drain and applied
     * from its beginning.
     *
     * `replacementBlocker` returns null as soon as a drain record exists, because
     * "the container it would have saved is gone or going". True from the stop
     * onwards; false for every pass before it — sealing, waiting, transferring,
     * saving — which on a populated server is hours. An orchestrator upgrade that
     * replaces the asset directory, or a secret rotated out from under a reference,
     * lands inside that window, and the teardown then commits into a create that
     * refuses for ever.
     *
     * ## What the scenario has to contain
     *
     * The fault is armed **after the world is saved**, which is the last pass before
     * the deregistration and the stop. Arming it earlier would be the case the
     * pass-level pre-flight already refuses, and this test would pass against the
     * build that has the defect. The world save is also what makes the checkpoint
     * unambiguous: the drain is one gate from `stopWorkload`.
     *
     * ## The assertions
     *
     * `stops` and `removals`, in that order of importance: the running container is
     * still there, so the replacement can happen the moment the node can build it.
     * The second half is the repair, because a guard that parks for ever is the same
     * outage with a different message.
     */
    @Test
    fun `a replacement that becomes unbuildable mid-drain parks before the stop`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.creates shouldHaveSize 1

            harness.declare(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0"))
            // Up to the point where the world is on disk and the next gate is the
            // stop. Driven by the drain's own record rather than by a pass count, so
            // a change to the ladder cannot quietly move the checkpoint.
            var passes = 0
            while (harness.status(name)?.drain?.worldSaved != true && passes++ < 8) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness
                .status(name)
                .shouldNotBeNull()
                .drain
                .shouldNotBeNull()
                .worldSaved
                .shouldBeTrue()
            harness.node.saves shouldHaveSize 1
            harness.node.stops.shouldBeEmpty()

            // The artefact goes away while the drain is mid-protocol: an upgrade
            // restaging the asset directory, a secret rotated behind its reference.
            harness.node.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(harness.node.name, NodeOperation.CREATE, "the artefact is not on this node"),
            )

            repeat(4) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            harness.node.creates shouldHaveSize 1
            val parked =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            parked.state shouldBe DrainState.DRAIN_FAILED
            val failure = parked.failure.shouldNotBeNull()
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "cannot build the replacement"
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING

            // Idempotent while it is parked: more passes over the same state issue
            // no stop, no removal and no create. The save count is in there too, and
            // the pass spacing is what lets it be — eight passes at two seconds is
            // inside `saveEvidenceMaxGap` (30s), so the confirmation this drain is
            // holding has not aged out and the ladder does not go back to `SAVING`.
            // Past that gap a second flush at an empty server is the designed
            // behaviour and not a repeated side effect, which is why the number of
            // passes here is a choice rather than a round figure.
            repeat(4) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }
            harness.node.saves shouldHaveSize 1
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            harness.node.creates shouldHaveSize 1

            // The operator stages the artefact again. No definition edit: the class
            // is retryable precisely so that this is enough.
            harness.node.clearFailures(NodeOperation.CREATE)
            harness.settle(name, limit = 16)

            harness.node.stops shouldHaveSize 1
            harness.node.removals shouldHaveSize 1
            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .image.canonical shouldBe "docker.io/itzg/minecraft-server:2026.7.0"
        }

    /**
     * …and the same fault never blocks a **delete**.
     *
     * The scope half of the guard above, and the one that has to be pinned by a
     * scenario rather than by reading the condition: a delete needs no create, and a
     * delete a create can block is how a workload becomes undeletable — the failure
     * mode the pre-flight exists to avoid, arriving from the other direction.
     */
    @Test
    fun `a delete still completes while the node refuses every create`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.node.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(harness.node.name, NodeOperation.CREATE, "the artefact is not on this node"),
            )
            harness.store.deleteDefinition(name)

            repeat(10) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            harness.node.saves shouldHaveSize 1
            harness.node.stops shouldHaveSize 1
            harness.node.removals shouldHaveSize 1
            harness.store.getServer(name).shouldBeNull()
        }

    @Test
    fun `a memory change is a replacement`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.declare(
                paperDefinition(memoryBytes = 8L * MemoryQuantity.GIB, heapBytes = 6L * MemoryQuantity.GIB),
            )
            harness.settle(name, limit = 14)

            harness.node.creates shouldHaveSize 2
            harness.node.creates[1]
                .resources.memoryBytes shouldBe 8L * MemoryQuantity.GIB
        }
}

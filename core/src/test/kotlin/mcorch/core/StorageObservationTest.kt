package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.FailureClass
import mcorch.schema.StorageSpec
import mcorch.schema.VolumeSpec
import mcorch.store.getOrThrow
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `StorageStatus` is what the loop **observed**, not what the definition asked
 * for.
 *
 * `Status.kt` has always said so, and `Reconciler.Pass.storageStatus` drafted the
 * whole block from `definition.spec.storage` on every pass — so a volume that does
 * not exist reported exactly what was asked for, and an operator diagnosing a
 * half-applied `storage.mode` edit read their own edit back at them. `persistent`
 * comes off the workload's `Labels.WORLD_DATA` now: the same fact, recorded at
 * create time on the object a later pass can actually read, and already trusted by
 * the drain for the decision that costs a world.
 *
 * ## What each test here is for
 *
 * - The producer, in both directions, and that an *edit* does not move it.
 * - The window the whole argument rests on. Drain-audit item 149 wants a
 *   create-side guard against a `persistent → ephemeral` edit landing where there
 *   is no container to read a label from, and in that window the status record is
 *   the only memory there is. It is worth having only if it is a memory of an
 *   observation rather than a rewrite of the definition, which is what these two
 *   cases assert — one per state the window passes through.
 * - Absence, in its three shapes: a workload carrying no label, a pass that could
 *   not reach the node at all, and a row that has never recorded a storage block.
 *   None of them may be answered by reading the definition, and none of them may
 *   erase what was last observed.
 * - Idempotency, because a status that is re-derived from a *different* source is
 *   exactly the kind of field that starts flapping between two answers.
 *
 * ## `volumeName` is observed the same way, and its absence is not symmetrical
 *
 * It is read off `Labels.VOLUME`, written by the planner only when there *is* a
 * volume. That asymmetry with `WORLD_DATA` — which writes `false` — is the point:
 * a workload that mounts nothing must not clear the record of which volume still
 * holds the world it stopped mounting, so absence means "the previous record
 * stands" rather than "there is no volume". A container created before the label
 * carries none and is not recreated to gain one (labels are outside the spec
 * hash), so the field converges as the fleet turns over.
 */
internal class StorageObservationTest {
    /**
     * The producer, in both directions.
     *
     * A `persistent` server's container carries `world-data=true` and an explicitly
     * ephemeral one carries `false`, so the two answers here are the two labels and
     * not the two definitions. The pair is the vacuity guard for everything below:
     * a scan that always answered `true` would satisfy half these tests.
     */
    @Test
    fun `a settled server records what its container was built with`() =
        coreTest {
            val harness = Harness()
            val persistent = paperDefinition()
            harness.declare(persistent)
            harness.settle(persistent.metadata.name)
            harness
                .status(persistent.metadata.name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .persistent
                .shouldBeTrue()

            val lobby = Harness()
            val ephemeral = paperDefinition(name = "lobby-01", storage = StorageSpec.Ephemeral())
            lobby.declare(ephemeral)
            lobby.settle(ephemeral.metadata.name)
            lobby
                .status(ephemeral.metadata.name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .persistent
                .shouldBeFalse()
        }

    /**
     * The volume its container was created to mount, read back off the container.
     *
     * Before `Labels.VOLUME` existed this was unassertable: the field was drafted
     * from `spec.storage` every pass, so it reported the definition, and removing
     * that left it with no producer at all.
     */
    @Test
    fun `a settled server records the volume its container mounts`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            harness.declare(definition)
            harness.settle(definition.metadata.name)

            harness
                .status(definition.metadata.name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .volumeName shouldBe resourceName("survival-01-world")
        }

    /**
     * Renaming a volume does not move the record until the container it names has
     * been replaced.
     *
     * `spec.storage.volume` is in the spec hash, so the edit asks for a recreate
     * and the loop drains and rebuilds. Between the edit and that rebuild the
     * container on the node is still mounting the **old** volume, and the record
     * has to say so — which is the entire difference between an observation and a
     * copy of the definition. The second half is what proves it is not merely
     * stuck.
     */
    @Test
    fun `a volume rename does not move the record until the container it names is replaced`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.store.putDefinition(
                paperDefinition(storage = StorageSpec.Persistent(VolumeSpec(resourceName("survival-01-world-b")))),
            )
            harness.pass(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .volumeName shouldBe resourceName("survival-01-world")

            harness.settle(name, limit = 20)
            harness.node.creates shouldHaveSize 2
            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .volumeName shouldBe resourceName("survival-01-world-b")
        }

    /**
     * A workload that stops mounting a volume keeps the record of which volume
     * held the world.
     *
     * The asymmetry the label's absence buys, driven end to end: an ephemeral edit
     * lands in the window with no container, nothing refuses it, and the created
     * container reports `persistent = false` with no `Labels.VOLUME`. `persistent`
     * follows the new container; the name does **not**, because it is the only
     * record of where the world that container is no longer mounting still lives,
     * and `docs/operating.md` tells an operator to start a recovery from it.
     *
     * A label written as an empty value instead of omitted would clear it here.
     */
    @Test
    fun `a workload that stops mounting a volume keeps the name of the one that holds the world`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            val volume =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .storage
                    .shouldNotBeNull()
                    .volumeName
                    .shouldNotBeNull()

            harness.node.workload = WorkloadObservation.Absent
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.settle(name, limit = 20)

            val observed =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .storage
                    .shouldNotBeNull()
            // The new container's own answer, so the positive half moved…
            observed.persistent.shouldBeFalse()
            // …and the name it says nothing about did not.
            observed.volumeName shouldBe volume
        }

    /**
     * A container created before the label leaves the carried name standing.
     *
     * The population that exists on every upgrade: labels are outside the spec
     * hash, so nothing is recreated to gain one, and a running container simply
     * carries none until it is replaced for a reason of its own. Absent must mean
     * "the previous record stands" — reading it as "no volume" would empty the
     * field for the whole fleet at the moment the label was introduced.
     */
    @Test
    fun `a container that predates the volume label does not clear the recorded name`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(labels = present.labels - Labels.VOLUME)
            harness.clock.advance(ReconcilerConfig().statusHeartbeat + 1.seconds)
            harness.pass(name)

            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .volumeName shouldBe resourceName("survival-01-world")
        }

    /**
     * An edit moves the definition and not the record, and the direction chosen is
     * the one nothing else already guards.
     *
     * `persistent → ephemeral` is refused outright by `forbiddenTransition` while a
     * container says it holds a world, so a test in that direction cannot tell "the
     * record follows the container" from "the refusal fired first". `ephemeral →
     * persistent` is a perfectly ordinary replacement: nothing refuses it, the loop
     * drains and recreates, and the record must read `false` for as long as the
     * container that is actually there was built ephemeral — then `true` on the one
     * that replaces it, which is the half that proves the record is not simply
     * stuck.
     */
    @Test
    fun `an edit does not move the record until the container it describes is replaced`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(name = "lobby-01", storage = StorageSpec.Ephemeral())
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .persistent
                .shouldBeFalse()

            // The edit lands. The container is untouched by it, and so is the record:
            // a status drafted from `spec.storage` reads `true` from here on, for a
            // workload that mounts nothing.
            harness.store.putDefinition(
                paperDefinition(
                    name = "lobby-01",
                    storage = StorageSpec.Persistent(mcorch.schema.VolumeSpec(resourceName("lobby-01-world"))),
                ),
            )
            harness.pass(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .persistent
                .shouldBeFalse()

            // …and the replacement is what changes it, because the replacement is
            // what changes the container.
            harness.settle(name, limit = 20)
            harness.node.creates shouldHaveSize 2
            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .persistent
                .shouldBeTrue()
        }

    /**
     * **The scenario the whole argument rests on**, in the first of the two states
     * the window passes through.
     *
     * Drain-audit item 149: an `ephemeral` edit landing between a replacement
     * drain's teardown and the next create is refused by nothing — `forbiddenTransition`
     * needs a `Present` observation and there is none — so the loop converges onto a
     * freshly generated empty world beside an orphaned volume. What would close it is
     * a guard in front of the create, and in that window there is no container whose
     * label it could ask. The status record is the only memory available, and it is
     * worth having only if it remembers an observation.
     *
     * So: tear the workload down, land the edit while the node reports nothing, and
     * assert the record still says the world was persistent. A record drafted from
     * `spec.storage` says `false` here — the guard would consult it, find the edit's
     * own answer, and wave the edit through.
     */
    @Test
    fun `an ephemeral edit landing with no workload at all does not rewrite what was observed`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .persistent
                .shouldBeTrue()

            // The gap between a teardown and a create: the sandbox and the container
            // are both gone, and the loop's memory of them is the status row.
            harness.node.workload = WorkloadObservation.Absent
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.pass(name)

            val observed =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .storage
                    .shouldNotBeNull()
            observed.persistent.shouldBeTrue()
            // Nothing on the node, so nothing bound. `bound` is the one part of the
            // record this pass can still speak to, and it is still an observation.
            observed.bound.shouldBeFalse()
        }

    /**
     * The same window, in the state it passes through next.
     *
     * `SANDBOX_ONLY` is the half of the window that has *something* to read, and
     * reading it is the trap: `WorkloadView.observe` reports the **sandbox's** labels
     * when no container exists, and a sandbox built moments ago from the edited
     * definition carries `world-data=false`. That is the edit laundered through the
     * runtime, and it would erase the memory just as surely as reading `spec.storage`
     * did. `hadContainer` cannot repair it either — knowing a container once existed
     * says nothing about what it was built with — which is why the arm carries that
     * argument rather than the fact.
     */
    @Test
    fun `a sandbox labelled by the edit does not overwrite the container's own answer`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The container is gone and the sandbox that outlived it carries the
            // edited definition's answer, which is what a sandbox created after the
            // edit would carry.
            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload =
                present.copy(
                    handle = present.handle.copy(containerId = null),
                    state = WorkloadState.SANDBOX_ONLY,
                    labels = present.labels + (Labels.WORLD_DATA to Labels.booleanLabel(false)),
                )
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.pass(name)

            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .persistent
                .shouldBeTrue()
        }

    /**
     * A workload that says nothing about itself leaves the record alone.
     *
     * An absent `WORLD_DATA` label means "created before this label existed", which
     * is not `false` — the label's own KDoc says so, and the drain answers the same
     * question the same way. The failure mode being pinned is the elvis: a fix that
     * stops deriving a field from the wrong source, and then adds a fallback for
     * "there is nothing to carry forward" which derives it **from the wrong source**,
     * firing on exactly the rows with the least other evidence. Drain-audit item 148.
     */
    @Test
    fun `an unlabelled workload leaves the last observation standing`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            val present = harness.node.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.node.workload = present.copy(labels = present.labels - Labels.WORLD_DATA)
            // An ephemeral definition beside it, so that a pass reaching for the
            // definition has something wrong to reach for. The edit is not refused:
            // an unlabelled container cannot be told from a lobby that has always
            // been ephemeral, so `forbiddenTransition` lets it past on purpose.
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.pass(name)

            harness
                .status(name)
                .shouldNotBeNull()
                .storage
                .shouldNotBeNull()
                .persistent
                .shouldBeTrue()
        }

    /**
     * A transient failure requeues and rewrites nothing.
     *
     * The node being unreachable is the retryable class: the pass records what it
     * last observed, asks to be tried again, and issues nothing. What it must not do
     * is treat "I could not look" as "there is no world here" — an unobservable pass
     * has strictly less information than the row it is about to write, and the only
     * source it could fill the gap from is the definition.
     */
    @Test
    fun `a pass that cannot reach the node requeues and keeps what it last observed`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.node.failAlways(NodeOperation.OBSERVE, harness.node.unreachable(NodeOperation.OBSERVE))
            // The edited definition is the wrong answer sitting within reach.
            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            val outcome = harness.pass(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            val status = harness.status(name).shouldNotBeNull()
            // Retryable, so it is not parked: the failure is recorded and the loop
            // comes back.
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
            status.storage
                .shouldNotBeNull()
                .persistent
                .shouldBeTrue()
            harness.node.stops shouldHaveSize 0
        }

    /**
     * A permanent failure surfaces on observed status, and the record it surfaces
     * beside is the container's.
     *
     * The refusal is the permanent class in this area: `storage.mode` persistent →
     * ephemeral on a workload that says it holds a world is not retried, it is
     * reported for a human. Both halves are asserted because a status carrying the
     * *edit's* storage record beside a failure that refuses the edit is a
     * self-contradicting row, and that is what the loop used to write.
     */
    @Test
    fun `a refused edit surfaces permanently and records the container's storage`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            harness.store.putDefinition(paperDefinition(storage = StorageSpec.Ephemeral()))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val status = harness.status(name).shouldNotBeNull()
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            status.storage
                .shouldNotBeNull()
                .persistent
                .shouldBeTrue()
            // Nothing was stopped to refuse it, which is what makes this a refusal
            // rather than a drain.
            harness.node.stops shouldHaveSize 0
        }

    /**
     * Two passes over the same desired and observed state write the same record and
     * issue nothing (CLAUDE.md invariant 5).
     *
     * A field that is re-derived on every pass from a source *other* than the one it
     * was written from is the shape that flaps, and comparability is what lets the
     * loop decide "nothing changed, do nothing" — so the record has to be equal, not
     * merely equivalent.
     *
     * **The clock moves past `statusHeartbeat` between the two passes, and that is
     * load-bearing rather than tidy.** `Harness` runs on a clock that only advances
     * when a test advances it, so a record stamped `now` on every pass is stamped the
     * *same* `now` twice and compares equal: sabotaging the producer to re-stamp the
     * record left this green until the advance was added. The advance also guarantees
     * a store write happens at all — an equal status inside the heartbeat is not
     * written, and a comparison against a row nothing rewrote is a value compared
     * with itself.
     */
    @Test
    fun `a second pass over the same state records the same storage and issues nothing`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            val settled = harness.status(name).shouldNotBeNull()
            val before = settled.observedAt
            val first = settled.storage.shouldNotBeNull()
            val creates = harness.node.creates.size
            val stops = harness.node.stops.size
            val saves = harness.node.saves.size
            val pulls = harness.node.pulls.size

            harness.clock.advance(ReconcilerConfig().statusHeartbeat + 1.seconds)
            harness.pass(name)

            val second = harness.status(name).shouldNotBeNull()
            // The pass really did re-record — `observedAt` has moved — so the
            // comparison below is between two written rows rather than one row read
            // twice.
            second.observedAt shouldNotBe before
            second.storage shouldBe first
            harness.node.creates shouldHaveSize creates
            harness.node.stops shouldHaveSize stops
            harness.node.saves shouldHaveSize saves
            harness.node.pulls shouldHaveSize pulls
        }

    /**
     * A row that has never recorded storage, on a workload that says nothing, stays
     * silent — and the condition derived from it says `Unknown` rather than `False`.
     *
     * This is the visible behaviour change and it is the honest one. `VOLUME_BOUND`
     * used to read `False` for a server the loop had not looked at yet, which is the
     * loop asserting something it does not know; `storage == null` makes it
     * `Unknown`. The two sentences are *"this row has never said"* and *"there is no
     * volume"*, and only the second one tells somebody to stop looking.
     */
    @Test
    fun `a row with nothing observed and nothing to carry says nothing`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            // One pass: the workload is created from the `Absent` observation this
            // pass entered with, so nothing has been observed about it yet.
            harness.pass(name)

            val status = harness.status(name).shouldNotBeNull()
            status.storage.shouldBeNull()
            status.condition(ConditionType.VOLUME_BOUND).status shouldBe ConditionStatus.UNKNOWN
            // The create still happened. Absence of a record is not absence of a
            // workload, and asserting the first without the second would pass on a
            // pass that did nothing at all.
            harness.node.creates shouldHaveSize 1
        }
}

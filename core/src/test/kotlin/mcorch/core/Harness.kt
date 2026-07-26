package mcorch.core

import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.store.StoredDefinition
import mcorch.store.getOrThrow

/**
 * Store, registry, scheduler and reconciler wired the way `:app` would wire
 * them, with a simulated node underneath.
 *
 * Deliberately assembled through the same public constructors production uses:
 * a harness that reached past [Scheduler] or [NodeRegistry] would be testing a
 * shape the real wiring does not have.
 */
internal class Harness(
    val clock: MutableClock = MutableClock(),
    val node: FakeNode = FakeNode(clock = clock),
    additionalNodes: List<Node> = emptyList(),
    config: ReconcilerConfig = ReconcilerConfig(),
) {
    val store: TestStore = TestStore(clock)
    val registry: NodeRegistry = StaticNodeRegistry(listOf(node) + additionalNodes)
    val scheduler: RecordingScheduler = RecordingScheduler(SingleNodeScheduler(registry))
    val reconciler: Reconciler = Reconciler(store, registry, scheduler, config, clock)

    suspend fun declare(definition: PaperServerDefinition): StoredDefinition =
        store.putDefinition(definition).getOrThrow()

    suspend fun pass(name: ResourceName): ReconcileOutcome = reconciler.reconcile(name)

    /** Runs passes until nothing more happens, or [limit] is reached. */
    suspend fun settle(
        name: ResourceName,
        limit: Int = 12,
    ): ReconcileOutcome {
        var last: ReconcileOutcome = ReconcileOutcome.Settled("no pass ran")
        repeat(limit) {
            last = pass(name)
            if (last is ReconcileOutcome.Settled || last is ReconcileOutcome.Failed) return last
        }
        return last
    }

    suspend fun status(name: ResourceName): PaperServerStatus? = store.statusOf(name)
}

/** A [Scheduler] that records what it was asked, so a test can prove it was asked at all. */
internal class RecordingScheduler(
    private val delegate: Scheduler,
) : Scheduler {
    val requests: MutableList<PlacementRequest> = mutableListOf()
    val decisions: MutableList<PlacementDecision> = mutableListOf()

    override suspend fun schedule(request: PlacementRequest): PlacementDecision {
        requests += request
        val decision = delegate.schedule(request)
        decisions += decision
        return decision
    }
}

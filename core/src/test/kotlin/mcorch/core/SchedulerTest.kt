package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.PlacementSpec
import org.junit.jupiter.api.Test

/**
 * The scheduler is trivial today and it is still a real interface with a real
 * call site. These tests are what stops "there is only one node" from being
 * inlined into the loop.
 */
internal class SchedulerTest {
    @Test
    fun `the loop asks the scheduler on every pass, not just at creation`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)

            harness.pass(name)
            harness.pass(name)
            harness.pass(name)

            harness.scheduler.requests shouldHaveSize 3
            harness.scheduler.requests
                .first()
                .server shouldBe name
        }

    @Test
    fun `the request carries the capacity a real scheduler would need`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(maxPlayers = 60)
            harness.declare(definition)
            harness.pass(definition.metadata.name)

            val request = harness.scheduler.requests.single()
            request.demand.maxPlayers shouldBe 60
            request.demand.memoryBytes shouldBe definition.spec.resources.memory.bytes
            request.demand.persistentVolume shouldBe resourceName("survival-01-world")
            request.pin shouldBe null
        }

    @Test
    fun `a placement pin is honoured`() =
        coreTest {
            val second = FakeNode(name = nodeName("node-b"))
            val harness = Harness(additionalNodes = listOf(second))
            val definition = paperDefinition(placement = PlacementSpec(node = nodeName("node-b")))
            val name = definition.metadata.name
            harness.declare(definition)

            harness.pass(name)

            harness.scheduler.requests
                .single()
                .pin shouldBe nodeName("node-b")
            harness.scheduler.decisions
                .single()
                .shouldBeInstanceOf<PlacementDecision.Scheduled>()
                .node shouldBe nodeName("node-b")
            // The container went to the pinned node, and nowhere near the
            // first one.
            second.creates shouldHaveSize 1
            harness.node.creates shouldHaveSize 0
            harness
                .status(name)
                .shouldNotBeNull()
                .runtime
                ?.node shouldBe nodeName("node-b")
        }

    @Test
    fun `a running server is told to stay where it is`() =
        coreTest {
            val second = FakeNode(name = nodeName("node-b"))
            val harness = Harness(additionalNodes = listOf(second))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            val request = harness.scheduler.requests.last()
            request.currentNode shouldBe harness.node.name
            harness.scheduler.decisions
                .last()
                .shouldBeInstanceOf<PlacementDecision.Scheduled>()
                .node shouldBe harness.node.name
        }

    @Test
    fun `an unknown pin is unschedulable and a known one on a down node is not fatal`() =
        coreTest {
            val registry = StaticNodeRegistry(listOf(FakeNode()))
            val scheduler = SingleNodeScheduler(registry)

            val unknown =
                scheduler.schedule(
                    PlacementRequest(
                        server = resourceName("survival-01"),
                        pin = nodeName("node-z"),
                        demand = PlacementDemand(maxPlayers = 20, memoryBytes = 1),
                    ),
                )
            unknown.shouldBeInstanceOf<PlacementDecision.Unschedulable>().problem shouldBe
                PlacementProblem.PINNED_NODE_UNKNOWN

            val down = FakeNode(name = nodeName("node-b")).apply { ready = false }
            val downScheduler = SingleNodeScheduler(StaticNodeRegistry(listOf(down)))
            downScheduler
                .schedule(
                    PlacementRequest(
                        server = resourceName("survival-01"),
                        pin = nodeName("node-b"),
                        demand = PlacementDemand(maxPlayers = 20, memoryBytes = 1),
                    ),
                ).shouldBeInstanceOf<PlacementDecision.Unschedulable>()
                .problem shouldBe PlacementProblem.PINNED_NODE_UNAVAILABLE
        }

    @Test
    fun `an empty registry places nothing`() =
        coreTest {
            val scheduler = SingleNodeScheduler(StaticNodeRegistry(emptyList()))

            scheduler
                .schedule(
                    PlacementRequest(
                        server = resourceName("survival-01"),
                        demand = PlacementDemand(maxPlayers = 20, memoryBytes = 1),
                    ),
                ).shouldBeInstanceOf<PlacementDecision.Unschedulable>()
                .problem shouldBe PlacementProblem.NO_NODES
        }

    @Test
    fun `a node is asked whether it is ready at most once per decision`() =
        coreTest {
            val node = FakeNode()
            val scheduler = SingleNodeScheduler(StaticNodeRegistry(listOf(node)))

            scheduler.schedule(
                PlacementRequest(
                    server = resourceName("survival-01"),
                    // Both the "keep it where it is" check and the candidate
                    // scan look at this node.
                    currentNode = node.name,
                    demand = PlacementDemand(maxPlayers = 20, memoryBytes = 1),
                ),
            )

            node.calls.count { it == NodeOperation.STATUS } shouldBe 1

            // And the answer is not kept between decisions: a node that went
            // down since the last pass must not be scheduled onto.
            node.ready = false
            scheduler
                .schedule(
                    PlacementRequest(
                        server = resourceName("survival-01"),
                        currentNode = node.name,
                        demand = PlacementDemand(maxPlayers = 20, memoryBytes = 1),
                    ),
                ).shouldBeInstanceOf<PlacementDecision.Unschedulable>()
        }

    @Test
    fun `a server whose node has vanished is refused rather than started somewhere else`() =
        coreTest {
            val original = FakeNode(name = nodeName("node-a"))
            val replacement = FakeNode(name = nodeName("node-b"))
            val harness = Harness(node = original, additionalNodes = listOf(replacement))
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            original.creates shouldHaveSize 1

            // The node holding the workload leaves the registry. It is not
            // stopped — an unreachable node is not a stopped container — and
            // there are players on it as far as anybody here knows.
            val survivors = StaticNodeRegistry(listOf(replacement))
            val orphaned =
                Reconciler(
                    store = harness.store,
                    registry = survivors,
                    scheduler = SingleNodeScheduler(survivors),
                    clock = harness.clock,
                )

            val outcome = orphaned.reconcile(name)

            // Scheduling it onto the surviving node would run a second copy of
            // a server that is still up somewhere else.
            replacement.creates shouldHaveSize 0
            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            val failure =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .failure
                    .shouldNotBeNull()
            failure.reason shouldBe mcorch.schema.FailureReason.NODE_UNAVAILABLE
            failure.message.contains("node-a").shouldBe(true)
        }

    @Test
    fun `a node whose status call fails is not chosen`() =
        coreTest {
            val broken = FakeNode()
            broken.failAlways(NodeOperation.STATUS, broken.unreachable(NodeOperation.STATUS))
            val scheduler = SingleNodeScheduler(StaticNodeRegistry(listOf(broken)))

            scheduler
                .schedule(
                    PlacementRequest(
                        server = resourceName("survival-01"),
                        demand = PlacementDemand(maxPlayers = 20, memoryBytes = 1),
                    ),
                ).shouldBeInstanceOf<PlacementDecision.Unschedulable>()
                .problem shouldBe PlacementProblem.INSUFFICIENT_CAPACITY
        }
}

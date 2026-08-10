package mcorch.core.node

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.Labels
import mcorch.core.WorkloadState
import mcorch.core.nodeName
import mcorch.core.resourceName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What a node concludes from a sandbox and the containers in it.
 *
 * These are the judgements that decide whether a drain runs at all and whether a
 * sandbox may be torn down — and getting either wrong kills a live server with
 * no grace period and no save. They are tested here, in this module's own types,
 * because the alternative is testing them through a CRI client that no unit test
 * can stand up. What is left untested in [LocalNode] is the field copy that
 * feeds this; the judgements are all here.
 */
internal class WorkloadViewTest {
    private val node = nodeName("node-a")
    private val server = resourceName("survival-01")
    private val at = Instant.parse("2026-07-26T10:00:00Z")

    private fun container(
        id: String,
        state: WorkloadState = WorkloadState.RUNNING,
        mine: Boolean = true,
        createdAt: Instant = at,
    ) = ContainerView(
        id = id,
        labels = if (mine) mapOf(Labels.SERVER to server.value, Labels.WORLD_DATA to "true") else emptyMap(),
        state = state,
        createdAt = createdAt,
        startedAt = createdAt,
    )

    private fun observe(containers: List<ContainerView>) =
        WorkloadView.observe(
            node = node,
            server = server,
            sandboxId = "sandbox-1",
            sandboxLabels = mapOf(Labels.SERVER to server.value, Labels.SPEC_HASH to "abc"),
            sandboxCreatedAt = at,
            containers = containers,
        )

    @Test
    fun `the newest container carrying the server's label is the one adopted`() {
        val observation =
            observe(
                listOf(
                    container("old", createdAt = at.minusSeconds(600)),
                    container("new", createdAt = at),
                    container("someone-elses", mine = false),
                ),
            )

        observation.handle.containerId shouldBe "new"
        observation.state shouldBe WorkloadState.RUNNING
        observation.startedAt shouldBe at
        // The container's own labels are what a drain reads to find out what it
        // was built with.
        observation.labels[Labels.WORLD_DATA] shouldBe "true"
    }

    /**
     * A key the container does not carry is **not** answered by the sandbox.
     *
     * `Present.labels` used to be `sandboxLabels + mine.labels`, which reads like
     * "the container wins" and is only half that: the container wins for keys it
     * *has*, and for keys it lacks the sandbox's value survives — inside the state
     * every consumer treats as the container's own word.
     * `Reconciler.labelsDescribeItsContainer` gates on the workload state and
     * cannot see which map a key came from, so a container carrying no
     * `WORLD_DATA` would have been answered by the sandbox beside it, and the rule
     * that decides whether a world needs flushing would have read a fact about the
     * wrong object.
     *
     * ## Which assertion is the pin, because it is not the obvious one
     *
     * **The `SAVE_CONFIRMABLE` and `volumeValue` nulls are load-bearing. Do not
     * trim them.** The discriminating property is *a key present on the sandbox
     * and absent on the container*, and only those two lines have it — the fixture
     * carries two so that either alone would redden, rather than because the claim
     * needs both.
     *
     * The `WORLD_DATA` assertion **cannot** detect the merge and is not the pin:
     * the container carries `false` there, and `sandboxLabels + mine.labels` yields
     * `false` too, so it is green either way. It earns its place as the other half
     * of the claim — *the container wins where it has an opinion* — which a test
     * built only from absent keys would leave unasserted.
     *
     * The `specHash` line guards against over-reading all of this as "sandbox
     * labels are never useful". [Labels.SPEC_HASH] is the one key that *does* fall
     * through, through its own expression rather than through a map merge — the
     * shape a per-key decision has to have, because it is the only one somebody can
     * disagree with in review.
     */
    @Test
    fun `a key the container does not carry is not answered by the sandbox`() {
        val bare =
            container("mine").copy(
                labels = mapOf(Labels.SERVER to server.value, Labels.WORLD_DATA to "false"),
            )

        val observation =
            WorkloadView.observe(
                node = node,
                server = server,
                sandboxId = "sandbox-1",
                sandboxLabels =
                    mapOf(
                        Labels.SERVER to server.value,
                        Labels.SPEC_HASH to "abc",
                        Labels.WORLD_DATA to "true",
                        Labels.SAVE_CONFIRMABLE to "true",
                        Labels.VOLUME to "sandbox-world",
                    ),
                sandboxCreatedAt = at,
                containers = listOf(bare),
            )

        // The container's own answer, not the sandbox's louder one.
        // The container's own answer where it has one. Not the pin: under a merge
        // this is `false` too, because the container carries the key.
        observation.labels[Labels.WORLD_DATA] shouldBe "false"
        // **The pin, both lines.** Present on the sandbox, absent on the container,
        // so a merge answers them from the sandbox and only these can see it.
        // Absent here means "this workload does not say", which every consumer
        // answers on its own safe side.
        observation.labels[Labels.SAVE_CONFIRMABLE] shouldBe null
        Labels.volumeValue(observation.labels) shouldBe null
        // …and the deliberate per-key fallback still works, which is what stops
        // this being read as "sandbox labels are never useful".
        observation.specHash shouldBe "abc"
    }

    @Test
    fun `an empty container list is SANDBOX_ONLY, which is why the caller must ask an authoritative source`() {
        val observation = observe(emptyList())

        observation.state shouldBe WorkloadState.SANDBOX_ONLY
        observation.handle.containerId shouldBe null
        // This is the reading that has to be right. CRI's
        // `containers_statuses` is optional and an empty one is
        // indistinguishable from an empty sandbox, so a sandbox holding a
        // running Paper server reads exactly like this — and the drain treats
        // it as "nothing is running, nothing to save". `ListContainers` is
        // mandatory; the reconcile loop refuses to act on this state for a
        // server it has seen a container for.
        observation.specHash shouldBe "abc"
    }

    @Test
    fun `a container nobody labelled still counts as an occupant`() {
        val foreign = container("stranger", mine = false)

        // Not this server's workload, so the observation ignores it...
        observe(listOf(foreign)).state shouldBe WorkloadState.SANDBOX_ONLY
        // ...and it is emphatically not ignorable when the sandbox is about to
        // be removed, which kills whatever is inside with no grace and no save.
        WorkloadView.occupants(listOf(foreign), own = null) shouldHaveSize 1
    }

    @Test
    fun `an occupant is anything not provably exited`() {
        val containers =
            listOf(
                container("running", state = WorkloadState.RUNNING),
                container("unknown", state = WorkloadState.UNKNOWN),
                container("created", state = WorkloadState.CREATED),
                container("exited", state = WorkloadState.EXITED),
            )

        val occupants = WorkloadView.occupants(containers, own = null).map { it.id }

        // `UNKNOWN` in particular: a state the runtime cannot classify is not
        // evidence that nothing is inside. The reconcile loop refuses to act on
        // that same signal, and a last line of defence must not be more
        // credulous than the thing it is defending.
        occupants shouldBe listOf("running", "unknown", "created")
    }

    @Test
    fun `the workload's own container is not an occupant of its own sandbox`() {
        val containers = listOf(container("mine"), container("exited-stranger", state = WorkloadState.EXITED))

        WorkloadView.occupants(containers, own = "mine") shouldHaveSize 0
    }

    @Test
    fun `detail decorates the enumeration and cannot contradict it`() {
        val listed = listOf(container("a"), container("b"))
        val detail =
            listOf(
                ContainerDetail(container("a").copy(exitCode = 137, reason = "OOMKilled")),
                // Describes something the enumeration says is not there. A
                // runtime that reports containers piecemeal must not be able to
                // *add* one, and — the direction that matters — the enumeration
                // is what decides membership, so a partial detail list cannot
                // suppress a container either.
                ContainerDetail(container("ghost")),
            )

        val merged = WorkloadView.merge(listed, detail)

        merged.map { it.id } shouldBe listOf("a", "b")
        merged.single { it.id == "a" }.exitCode shouldBe 137
        // Untouched by an overlay that says nothing about it.
        merged.single { it.id == "b" }.exitCode shouldBe null
        // The empty overlay containerd 2.3.3 always sends changes nothing.
        WorkloadView.merge(listed, emptyList()) shouldBe listed
    }

    @Test
    fun `teardown removes the container before it touches the sandbox`() {
        val mine = container("mine", state = WorkloadState.EXITED)

        val plan = WorkloadView.teardown(own = mine, containers = listOf(mine), ownId = "mine")

        // The order is the whole point: `StopPodSandbox` kills anything still
        // inside with no grace period and no save.
        plan shouldBe listOf(TeardownStep.RemoveContainer("mine"), TeardownStep.RemoveSandbox)
    }

    @Test
    fun `teardown refuses while this workload's own container might still be alive`() {
        for (state in listOf(WorkloadState.RUNNING, WorkloadState.UNKNOWN)) {
            val mine = container("mine", state = state)

            WorkloadView
                .teardown(own = mine, containers = listOf(mine), ownId = "mine")
                .single()
                .shouldBeInstanceOf<TeardownStep.Refuse>()
        }

        // A `CREATED` container has never been started, so removing it cannot
        // kill a serving process.
        val created = container("mine", state = WorkloadState.CREATED)
        WorkloadView.teardown(own = created, containers = listOf(created), ownId = "mine") shouldBe
            listOf(TeardownStep.RemoveContainer("mine"), TeardownStep.RemoveSandbox)
    }

    @Test
    fun `teardown refuses while anything else is still in the sandbox`() {
        val mine = container("mine", state = WorkloadState.EXITED)
        val stranger = container("stranger", mine = false, state = WorkloadState.CREATED)

        val plan = WorkloadView.teardown(own = mine, containers = listOf(mine, stranger), ownId = "mine")

        // A foreign `CREATED` container does block it: this orchestrator did not
        // start it and cannot know it is not about to be started.
        plan
            .single()
            .shouldBeInstanceOf<TeardownStep.Refuse>()
            .reason
            .contains("CREATED") shouldBe true
    }

    @Test
    fun `a sandbox with no container of ours is torn down on its own`() {
        val plan = WorkloadView.teardown(own = null, containers = emptyList(), ownId = null)

        plan shouldBe listOf(TeardownStep.RemoveSandbox)
    }
}

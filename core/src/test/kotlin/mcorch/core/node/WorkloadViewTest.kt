package mcorch.core.node

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
}

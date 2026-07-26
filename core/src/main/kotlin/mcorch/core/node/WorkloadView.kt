package mcorch.core.node

import mcorch.core.Labels
import mcorch.core.WorkloadHandle
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadState
import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import java.time.Instant

/**
 * One container as the runtime describes it, in this module's own vocabulary.
 *
 * A deliberate stopping point for CRI types. [LocalNode] maps a runtime's
 * answers into these and everything that *decides* something works on them
 * instead — which is what makes those decisions testable without a containerd.
 * The mapping that remains untested is a field copy; the judgements below are
 * where the damage lives.
 */
internal data class ContainerView(
    val id: String,
    val labels: Map<String, String>,
    val state: WorkloadState,
    val createdAt: Instant?,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val exitCode: Int? = null,
    val reason: String = "",
    val message: String = "",
    val imageId: String? = null,
)

/**
 * What a node concludes from a sandbox and the containers inside it.
 *
 * Two questions, both of which have killed a live server when answered from
 * data that merely *looked* authoritative:
 *
 * - **What is this server's workload doing?** ([observe])
 * - **Is it safe to tear this sandbox down?** ([occupants])
 *
 * The caller is responsible for the list being an authoritative enumeration.
 * CRI's `PodSandboxStatusResponse.containers_statuses` is not: the field is
 * optional, runtime-version-dependent, and an empty one is indistinguishable
 * from "no containers". Reading a sandbox holding a running Paper server as
 * empty makes [observe] report [WorkloadState.SANDBOX_ONLY] — which the drain
 * treats as "nothing is running" — and makes [occupants] report nobody home, so
 * the guard that should have caught it is defeated by the same data that caused
 * it. `ListContainers` is mandatory; ask that.
 */
internal object WorkloadView {
    /**
     * The workload [server] has in this sandbox.
     *
     * The newest container carrying the server's label is the one adopted; any
     * others are left alone, because "remove the container I do not recognise"
     * is not a decision to make automatically.
     */
    @Suppress("LongParameterList")
    fun observe(
        node: NodeName,
        server: ResourceName,
        sandboxId: String,
        sandboxLabels: Map<String, String>,
        sandboxCreatedAt: Instant?,
        containers: List<ContainerView>,
    ): WorkloadObservation.Present {
        val mine =
            containers
                .filter { it.labels[Labels.SERVER] == server.value }
                .maxByOrNull { it.createdAt ?: Instant.EPOCH }
        val handle = WorkloadHandle(node = node, sandboxId = sandboxId, containerId = mine?.id)
        val specHash = mine?.labels?.get(Labels.SPEC_HASH) ?: sandboxLabels[Labels.SPEC_HASH]
        // The container's labels over the sandbox's. They are what a drain reads
        // to find out what this workload was built with, so they are reported as
        // the runtime holds them and nothing here interprets them.
        val labels = if (mine != null) sandboxLabels + mine.labels else sandboxLabels
        if (mine == null) {
            return WorkloadObservation.Present(
                handle = handle,
                state = WorkloadState.SANDBOX_ONLY,
                specHash = specHash,
                labels = labels,
                createdAt = sandboxCreatedAt,
            )
        }
        return WorkloadObservation.Present(
            handle = handle,
            state = mine.state,
            specHash = specHash,
            labels = labels,
            imageId = mine.imageId,
            createdAt = mine.createdAt,
            startedAt = mine.startedAt,
            finishedAt = mine.finishedAt,
            exitCode = mine.exitCode,
            reason = mine.reason,
            message = mine.message,
        )
    }

    /**
     * Containers in the sandbox that may still have a process in them, [own]
     * excepted.
     *
     * The test is "not provably exited", not "running". `StopPodSandbox` kills
     * everything inside with no grace period and no save, so the question is
     * whether anything *might* be alive — and a container the runtime cannot
     * classify might be. Treating [WorkloadState.UNKNOWN] as empty here would
     * contradict the posture the reconcile loop takes with the identical
     * signal, which is to requeue and act on nothing.
     *
     * Labels are deliberately not consulted. A container this orchestrator did
     * not create is somebody's running process too, and it is the one the
     * label-filtered view of the world cannot see.
     */
    fun occupants(
        containers: List<ContainerView>,
        own: String?,
    ): List<ContainerView> = containers.filter { it.id != own && it.state != WorkloadState.EXITED }
}

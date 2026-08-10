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
 * Extra detail about a container that an enumeration does not carry — when it
 * started, when it finished, how it exited.
 *
 * A separate type from [ContainerView] on purpose, and the reason is safety
 * rather than tidiness. It comes from CRI's optional
 * `PodSandboxStatusResponse.containers_statuses`, which on containerd 2.3.3 is
 * simply always empty and on other versions may be partial. It is therefore an
 * *overlay* and never a source of truth about which containers exist: a
 * container missing from it is not a container that is gone. Making the two
 * roles different types means [WorkloadView.merge] cannot be called with them
 * the wrong way round, which is the mistake that let a sandbox holding a live
 * Paper server read as empty.
 */
internal data class ContainerDetail(
    val container: ContainerView,
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
        // The one key that falls through from the container to the sandbox, and it
        // does so *explicitly*, one key at a time. That is the shape a per-key
        // decision has to have — see below for what happens when it is a map merge
        // instead.
        val specHash = mine?.labels?.get(Labels.SPEC_HASH) ?: sandboxLabels[Labels.SPEC_HASH]
        // The container's labels when there is a container, the sandbox's only when
        // there is not, and **never the two merged**. They are what a drain reads to
        // find out what this workload was built with, so they are reported as the
        // runtime holds them and nothing here interprets them.
        //
        // This was `sandboxLabels + mine.labels`, which looks like "the container's
        // labels win" and is not: a key the *container* lacks falls through to the
        // *sandbox's* value, inside the branch every consumer treats as the
        // container's own word. `Reconciler.labelsDescribeItsContainer` gates on the
        // workload state, which cannot see which map a key came from, so a container
        // that carries no `WORLD_DATA` would be answered by the sandbox standing
        // beside it — a fact about the wrong object, handed to the rule that decides
        // whether a world needs flushing.
        //
        // Nothing was broken by it, but only through a conjunction that nothing
        // asserted: both maps are built from one `WorkloadSpec` inside one
        // `ensureWorkload`, adoption is gated on the spec hash so a persistent
        // sandbox never receives an ephemeral container, and every build so far has
        // written every label on the container as well as the sandbox. Break any one
        // of the three and the sandbox starts answering for the container. A safety
        // property that holds by coincidence of three unrelated facts is worth
        // replacing with one that holds by construction.
        val labels = mine?.labels ?: sandboxLabels
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

    /**
     * The enumerated containers, enriched with whatever detail was reported for
     * them.
     *
     * [listed] decides membership; [detail] only decorates. An entry in [detail]
     * for a container the enumeration does not contain is discarded rather than
     * added — it describes something the authoritative source says is not there.
     */
    fun merge(
        listed: List<ContainerView>,
        detail: List<ContainerDetail>,
    ): List<ContainerView> {
        if (detail.isEmpty()) return listed
        val byId = detail.associateBy { it.container.id }
        return listed.map { byId[it.id]?.container ?: it }
    }

    /**
     * The order a workload comes apart in, and the reasons not to start.
     *
     * A plan rather than something done inline, so that the ordering and the
     * refusals can be tested without a runtime. Both matter: `StopPodSandbox`
     * and `RemovePodSandbox` kill every container still inside with no grace
     * period and no save, so the container is removed first, always, and the
     * sandbox is not touched while anything in it might still have a process.
     */
    fun teardown(
        own: ContainerView?,
        containers: List<ContainerView>,
        ownId: String?,
    ): List<TeardownStep> {
        if (own != null && own.state != WorkloadState.EXITED && own.state != WorkloadState.CREATED) {
            // A `CREATED` container has never had `StartContainer` called on it,
            // so removing it cannot kill a serving process. Anything else —
            // running, or a state the runtime will not name — has to be drained
            // and stopped first.
            return listOf(
                TeardownStep.Refuse(
                    "refusing to remove container ${own.id}: the runtime reports it as ${own.state}, not " +
                        "stopped. It must be drained and stopped first",
                ),
            )
        }
        val occupants = occupants(containers, own = ownId)
        if (occupants.isNotEmpty()) {
            return listOf(
                TeardownStep.Refuse(
                    "refusing to tear down the sandbox: ${occupants.size} container(s) inside it are not " +
                        "stopped (${occupants.joinToString { it.state.name }}), and removing the sandbox would " +
                        "kill them with no grace period and no save. Drain and stop them first",
                ),
            )
        }
        return buildList {
            if (ownId != null) add(TeardownStep.RemoveContainer(ownId))
            add(TeardownStep.RemoveSandbox)
        }
    }
}

/** One step of taking a workload apart, in the order it has to happen. */
internal sealed interface TeardownStep {
    data class RemoveContainer(
        val id: String,
    ) : TeardownStep

    /** Stops and removes the sandbox. Only ever after every container in it is gone. */
    data object RemoveSandbox : TeardownStep

    data class Refuse(
        val reason: String,
    ) : TeardownStep
}

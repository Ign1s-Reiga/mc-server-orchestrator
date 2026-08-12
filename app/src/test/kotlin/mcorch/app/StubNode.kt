package mcorch.app

import mcorch.core.ConsoleRequest
import mcorch.core.EndpointRequest
import mcorch.core.EndpointResponse
import mcorch.core.ExecOutcome
import mcorch.core.ExecRequest
import mcorch.core.ImageAvailability
import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.NodeStatus
import mcorch.core.StopGrace
import mcorch.core.StorageRequest
import mcorch.core.WorkloadHandle
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadRemoval
import mcorch.core.WorkloadSpec
import mcorch.core.WorkloadState
import mcorch.schema.DrainSpec
import mcorch.schema.HeapSpec
import mcorch.schema.ImageRef
import mcorch.schema.LifecycleSpec
import mcorch.schema.MemoryQuantity
import mcorch.schema.MinecraftVersion
import mcorch.schema.NetworkSpec
import mcorch.schema.NodeName
import mcorch.schema.ObjectMetadata
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerSpec
import mcorch.schema.PaperVersionSpec
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SecretRef
import mcorch.schema.StorageSpec
import mcorch.schema.VolumeSpec
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A [Node] that pretends to be a healthy runtime with a joinable server on it.
 *
 * `:core` has a richer simulator, but it lives in that module's test sources and
 * is not published, and exporting it would mean making test-only types part of
 * `:core`'s public API under explicit-API mode. This is the small subset
 * [DisplayConformanceTest] needs: enough for a real [mcorch.core.Reconciler] to
 * bring a server up and run a drain against it.
 *
 * Writing a [Node] here does not breach CLAUDE.md invariant 7 — the point of
 * that rule is that no module outside the single-host implementation may name a
 * `mcorch.cri` type, and nothing in this file does. A fake node is an
 * implementation of the seam, which is exactly what the seam is for.
 */
internal class StubNode(
    override val name: NodeName = NodeName.of("node-a").getOrThrow(),
    /** Reported by the Server List Ping. Non-zero blocks a drain, which is the point. */
    private val online: Int = 0,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Whether `save-all flush` reports a *completed* save.
     *
     * Off by default, and the default is the load-bearing one: every other server
     * in these tests must never get past `SAVING`, and a fake that silently
     * confirmed a save would walk a drain straight through the state being
     * asserted on. Turned on only for the drain that has to reach the stop.
     */
    private val savesCleanly: Boolean = false,
    /**
     * Refuses the first stop, retryably, and takes every one after it.
     *
     * The only way to reach a drain that has *aborted and then recovered its
     * step*, which is the state
     * [DisplayConformanceTest.a drain that is progressing again still reads as broken, and that is the safe direction]
     * pins.
     */
    private val refuseFirstStop: Boolean = false,
) : Node {
    private var stopsRefused = 0

    private var workload: WorkloadObservation = WorkloadObservation.Absent
    private val images = mutableSetOf<String>()

    /**
     * Every stop this node was asked for.
     *
     * Both drains in [DisplayConformanceTest] assert this stays empty, and that
     * is the load-bearing assertion in the file rather than a diagnostic aid: one
     * server holds a world it can never confirm a save for, the other has players
     * on it, and neither may be stopped for any reason. A rendering assertion
     * would still pass on a loop that had stopped them both.
     */
    val stops: MutableList<WorkloadHandle> = mutableListOf()

    /**
     * Makes every later observation fail the way a node that has gone does.
     *
     * The one behaviour a conformance test cannot reach any other way: a
     * `NodeException` out of the pass, which `Reconciler.nodeFailure` records on
     * the *status* while carrying the drain record forward untouched. That is the
     * only route to a status where the two failures are different events, which is
     * the case the `:core` and `:api` discriminators must agree about.
     */
    fun stopAnswering() {
        answering = false
    }

    private var answering = true

    override suspend fun status(): NodeStatus = NodeStatus(ready = true, detail = "ready")

    override suspend fun observe(server: ResourceName): WorkloadObservation {
        if (!answering) {
            throw NodeException.Rejected(name, NodeOperation.OBSERVE, "this node no longer hosts this workload")
        }
        return workload
    }

    override suspend fun ensureImage(image: ImageRef): ImageAvailability {
        val reference = image.canonical
        val pulled = images.add(reference)
        return ImageAvailability(image, id = "sha256:${reference.hashCode().toUInt()}", pulled = pulled)
    }

    /** This node has everything. The pre-flight is a question, and here the answer is yes. */
    override suspend fun checkWorkload(spec: WorkloadSpec) = Unit

    /**
     * How many containers this node has created, ever.
     *
     * It is in the container id, and that is not decoration: a real runtime never
     * reuses one, so a stub that hands the *same* id to a replacement makes "is this
     * the container the drain signalled" true by construction. No test here turns on
     * container identity today, which is precisely why it is free to fix — the cost
     * of leaving it named after the server is that the first `:app` assertion keyed
     * on identity would be true whatever the code did, and nobody would know. The
     * sandbox id is not counted, because a replacement really can land in the sandbox
     * that is already there. `:core`'s `FakeNode` carries the same counter for the
     * same reason.
     */
    private var containersCreated: Int = 0

    override suspend fun ensureWorkload(spec: WorkloadSpec): WorkloadObservation.Present {
        val existing = workload
        if (existing is WorkloadObservation.Present && existing.state != WorkloadState.SANDBOX_ONLY) return existing
        containersCreated++
        val created =
            WorkloadObservation.Present(
                handle = WorkloadHandle(name, "sandbox-${spec.server}", "container-${spec.server}-$containersCreated"),
                state = WorkloadState.CREATED,
                specHash = spec.specHash,
                // Carried the way a real runtime carries them, because a drain
                // reads what the container was built with rather than what the
                // definition says now.
                labels = applyLabelOverrides(spec.labels + ("mcorch.dev/spec-hash" to spec.specHash)),
                createdAt = clock.instant(),
            )
        workload = created
        return created
    }

    override suspend fun startWorkload(handle: WorkloadHandle) {
        val present = workload as? WorkloadObservation.Present ?: error("nothing to start")
        workload = present.copy(state = WorkloadState.RUNNING, startedAt = clock.instant())
    }

    override suspend fun exec(
        handle: WorkloadHandle,
        request: ExecRequest,
    ): ExecOutcome =
        when {
            request.command.firstOrNull() == "mc-monitor" -> {
                ExecOutcome(0, "version=1.21.8 online=$online max=20 motd=a server", "")
            }

            // The completion message, not the acknowledgement. Paper says
            // "Saving the game" when it starts and this when the write is done,
            // and only the second one authorises a stop.
            savesCleanly -> {
                ExecOutcome(0, "Saved the game", "")
            }

            // Otherwise never reached: those servers either have no RCON at all or
            // still have players on them, so no drain gets as far as a save.
            // Answering honestly rather than cleanly keeps it that way — a fake
            // that silently confirmed a save would let a drain walk past the state
            // being asserted on.
            else -> {
                ExecOutcome(1, "", "no rcon listener")
            }
        }

    /**
     * No control endpoint. Every server in these tests is standalone, so nothing
     * should reach for one — and a stub that answered would let a drain take the
     * proxied path in a file whose assertions are all about the standalone one.
     */
    override suspend fun console(
        handle: WorkloadHandle,
        request: ConsoleRequest,
    ): String =
        throw NodeException.Unreachable(
            name,
            NodeOperation.CONSOLE,
            "nothing is listening on port ${request.port}",
        )

    override suspend fun callEndpoint(
        handle: WorkloadHandle,
        request: EndpointRequest,
    ): EndpointResponse =
        throw NodeException.Unreachable(
            name,
            NodeOperation.ENDPOINT,
            "nothing is listening on port ${request.port}",
        )

    override suspend fun stopWorkload(
        handle: WorkloadHandle,
        gracePeriod: StopGrace,
    ) {
        // The operational ceiling is not re-checked: [StopGrace] is the only thing
        // this signature accepts and its factory has applied it. What is left is
        // the half every implementation owns for itself.
        require(gracePeriod.period.isPositive()) { "the stub holds the real node to its contract" }
        if (refuseFirstStop && stopsRefused == 0) {
            stopsRefused += 1
            throw NodeException.Unreachable(name, NodeOperation.STOP, "the runtime did not take the stop")
        }
        stops += handle
        val present = workload as? WorkloadObservation.Present ?: return
        workload = present.copy(state = WorkloadState.EXITED, finishedAt = clock.instant(), exitCode = 0)
    }

    override suspend fun removeWorkload(handle: WorkloadHandle): WorkloadRemoval {
        workload = WorkloadObservation.Absent
        return WorkloadRemoval.COMPLETE
    }
}

internal fun resourceName(raw: String): ResourceName = ResourceName.of(raw).getOrThrow()

/**
 * Labels forced onto a created workload; a `null` value removes one.
 *
 * `Labels.SAVE_CONFIRMABLE` is why this exists. RCON is standard, so a
 * `PaperServer` without a save channel can no longer be *declared* — but the
 * drain reads that label off the running container, where absence means "this
 * workload does not say". Constructing that container is the only way to reach
 * the permanently-failed drain this suite asserts a display for.
 */
internal val stubLabelOverrides: MutableMap<String, String?> = mutableMapOf()

private fun applyLabelOverrides(labels: Map<String, String>): Map<String, String> {
    if (stubLabelOverrides.isEmpty()) return labels
    val result = labels.toMutableMap()
    stubLabelOverrides.forEach { (key, value) -> if (value == null) result.remove(key) else result[key] = value }
    return result
}

/**
 * A definition that is boring on purpose. No forwarding secret, no player name,
 * no address — a fixture is where such a value gets committed by accident.
 */
internal fun paperServer(
    name: String,
    rcon: RconSpec = RconSpec(passwordSecret = SecretRef.of("$name-rcon", "password").getOrThrow()),
    storage: StorageSpec = StorageSpec.Persistent(VolumeSpec(resourceName("$name-world"))),
    saveTimeout: Duration = 60.seconds,
): PaperServerDefinition =
    PaperServerDefinition(
        apiVersion = SchemaVersion.CURRENT,
        metadata = ObjectMetadata(name = resourceName(name)),
        spec =
            PaperServerSpec(
                image = ImageRef.parse("docker.io/itzg/minecraft-server:2026.6.1").getOrThrow(),
                paper = PaperVersionSpec(minecraftVersion = MinecraftVersion.of("1.21.8").getOrThrow()),
                resources =
                    ResourceSpec(
                        memory = MemoryQuantity.ofBytes(2L * MemoryQuantity.GIB).getOrThrow(),
                        heap = HeapSpec(max = MemoryQuantity.ofBytes(1L * MemoryQuantity.GIB).getOrThrow()),
                    ),
                storage = storage,
                eulaAccepted = true,
                maxPlayers = 20,
                network = NetworkSpec(hostPort = null, rcon = rcon),
                lifecycle =
                    LifecycleSpec(
                        drain = DrainSpec(saveTimeout = saveTimeout),
                        stopGracePeriod = saveTimeout + 30.seconds,
                        startupTimeout = 5.minutes,
                    ),
            ),
    )

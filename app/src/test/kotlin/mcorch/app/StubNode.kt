package mcorch.app

import mcorch.core.ExecOutcome
import mcorch.core.ExecRequest
import mcorch.core.ImageAvailability
import mcorch.core.Node
import mcorch.core.NodeStatus
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
) : Node {
    private var workload: WorkloadObservation = WorkloadObservation.Absent
    private val images = mutableSetOf<String>()

    /** Asserted on by nothing here, kept so a stop that should not happen is visible in a failure. */
    val stops: MutableList<WorkloadHandle> = mutableListOf()

    override suspend fun status(): NodeStatus = NodeStatus(ready = true, detail = "ready")

    override suspend fun observe(server: ResourceName): WorkloadObservation = workload

    override suspend fun ensureImage(image: ImageRef): ImageAvailability {
        val reference = image.canonical
        val pulled = images.add(reference)
        return ImageAvailability(image, id = "sha256:${reference.hashCode().toUInt()}", pulled = pulled)
    }

    override suspend fun ensureWorkload(spec: WorkloadSpec): WorkloadObservation.Present {
        val existing = workload
        if (existing is WorkloadObservation.Present && existing.state != WorkloadState.SANDBOX_ONLY) return existing
        val created =
            WorkloadObservation.Present(
                handle = WorkloadHandle(name, "sandbox-${spec.server}", "container-${spec.server}"),
                state = WorkloadState.CREATED,
                specHash = spec.specHash,
                // Carried the way a real runtime carries them, because a drain
                // reads what the container was built with rather than what the
                // definition says now.
                labels = spec.labels + ("mcorch.dev/spec-hash" to spec.specHash),
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

            // Never reached in these tests: every server here either has no RCON
            // at all or still has players on it, so no drain gets as far as a
            // save. Answering honestly rather than cleanly keeps it that way — a
            // fake that silently confirmed a save would let a drain walk past the
            // state being asserted on.
            else -> {
                ExecOutcome(1, "", "no rcon listener")
            }
        }

    override suspend fun stopWorkload(
        handle: WorkloadHandle,
        gracePeriod: Duration,
    ) {
        require(gracePeriod.isPositive()) { "the stub holds the real node to its contract" }
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
 * A definition that is boring on purpose. No forwarding secret, no player name,
 * no address — a fixture is where such a value gets committed by accident.
 */
internal fun paperServer(
    name: String,
    rcon: RconSpec = RconSpec.Enabled(passwordSecret = SecretRef.of("$name-rcon", "password").getOrThrow()),
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

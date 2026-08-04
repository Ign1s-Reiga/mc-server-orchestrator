package mcorch.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mcorch.core.paper.PaperCommands
import mcorch.schema.ImageRef
import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import java.time.Clock
import kotlin.time.Duration

/**
 * A [Node] that simulates a runtime in memory and records every side effect.
 *
 * It is a simulator rather than a script: it holds a workload that actually
 * moves between states, so a test drives the loop the way the loop drives
 * containerd, and an idempotency assertion is about *what was done* rather than
 * about what a stub was asked.
 *
 * The counters exist because the invariants are about side effects. "The second
 * pass changed nothing" is not provable from a status: it is provable from
 * [creates], [pulls], [saves] and [stops] not having moved.
 */
internal class FakeNode(
    override val name: NodeName = nodeName("node-a"),
    private val clock: Clock = Clock.systemUTC(),
) : Node {
    // ── what the runtime currently holds ─────────────────────────────────────

    var workload: WorkloadObservation = WorkloadObservation.Absent
    val images: MutableSet<String> = mutableSetOf()

    /**
     * Persistent volumes that exist. Nothing in [removeWorkload] touches this —
     * that is the property CLAUDE.md invariant 2 is about, and a test asserts on
     * it after a teardown.
     */
    val volumes: MutableSet<ResourceName> = mutableSetOf()

    var ready: Boolean = true

    // ── recorded side effects ────────────────────────────────────────────────

    val pulls: MutableList<String> = mutableListOf()
    val creates: MutableList<WorkloadSpec> = mutableListOf()
    val starts: MutableList<WorkloadHandle> = mutableListOf()
    val stops: MutableList<Pair<WorkloadHandle, Duration>> = mutableListOf()

    /** Workloads taken away in full: container *and* sandbox. */
    val removals: MutableList<WorkloadHandle> = mutableListOf()

    /** Containers removed, whether or not the sandbox went with them. */
    val containerRemovals: MutableList<WorkloadHandle> = mutableListOf()
    val execs: MutableList<List<String>> = mutableListOf()

    /** Every control-channel request, so a test can count seals and transfers at the wire. */
    val endpointCalls: MutableList<EndpointRequest> = mutableListOf()

    /** What is listening inside the sandbox, by port. A proxy's plugin goes here. */
    val endpoints: MutableMap<Int, FakeProxyPlugin> = mutableMapOf()

    /** Every call that reached the node, failed ones included. Counts attempts. */
    val calls: MutableList<NodeOperation> = mutableListOf()

    /** Save requests only — the side effect that must never be sent twice. */
    val saves: List<List<String>> get() = execs.filter { it == PaperCommands.saveAll() }

    /** Readiness probes. Reads, not side effects: these are expected to repeat. */
    val probes: List<List<String>> get() = execs.filter { it.firstOrNull() == "mc-monitor" }

    // ── programmable behaviour ───────────────────────────────────────────────

    /** Answers an exec. Defaults to an empty, joinable server that saves cleanly. */
    var onExec: (List<String>) -> ExecOutcome = { command -> defaultExec(command) }

    var online: Int = 0
    var joinable: Boolean = true
    var savesCleanly: Boolean = true

    /**
     * Makes the sandbox half of a removal fail after the container half
     * succeeded — a CNI teardown flake, in practice. The interesting part is
     * what the node reports afterwards, since the caller has to record progress
     * it can no longer see.
     */
    var sandboxRemovalFails: Boolean = false

    /**
     * What a stop does to the workload. The default is what a healthy runtime
     * does; returning the workload unchanged simulates a stop that does not
     * take, which is the state `awaitStopped` exists for.
     */
    var onStop: (WorkloadObservation.Present) -> WorkloadObservation.Present = { present ->
        present.copy(state = WorkloadState.EXITED, finishedAt = clock.instant(), exitCode = 0)
    }

    private val onceFailures = mutableMapOf<NodeOperation, ArrayDeque<NodeException>>()
    private val alwaysFailures = mutableMapOf<NodeOperation, NodeException>()
    private val rawFailures = mutableMapOf<NodeOperation, Throwable>()

    fun failOnce(
        operation: NodeOperation,
        failure: NodeException,
    ) {
        onceFailures.getOrPut(operation) { ArrayDeque() }.addLast(failure)
    }

    fun failAlways(
        operation: NodeOperation,
        failure: NodeException,
    ) {
        alwaysFailures[operation] = failure
    }

    /**
     * Throws something that is *not* a [NodeException], the way a node
     * implementation does when a failure escapes its own translation — an
     * `IOException` from creating a host directory, say.
     *
     * A real [Node] must never do this, which is exactly why the loop is tested
     * against one that does: the promise is enforced in one implementation and
     * relied on by every worker.
     */
    fun throwRaw(
        operation: NodeOperation,
        failure: Throwable,
    ) {
        rawFailures[operation] = failure
    }

    fun stopFailing(operation: NodeOperation) {
        alwaysFailures.remove(operation)
        onceFailures.remove(operation)
        rawFailures.remove(operation)
    }

    fun unreachable(operation: NodeOperation): NodeException =
        NodeException.Unreachable(name, operation, "containerd is not up")

    fun rejected(operation: NodeOperation): NodeException =
        NodeException.Rejected(name, operation, "the runtime refused the request")

    /**
     * The node did not answer at all: this loop's own deadline elapsed waiting
     * on it. The node may be sick.
     */
    fun unanswered(operation: NodeOperation): NodeException =
        NodeException.Timeout(name, operation, "the node did not answer within the deadline")

    /**
     * The node answered promptly to say the *command* outran the timeout it was
     * given. Says nothing about the node's health — which is the entire point of
     * having it distinct from [unanswered], since both arrive as the same
     * `DEADLINE_EXCEEDED` from a real runtime.
     */
    fun commandTimedOut(operation: NodeOperation): NodeException =
        NodeException.Timeout(
            name,
            operation,
            "the command did not finish within the timeout it was given, and the runtime stopped it",
            commandTimeout = true,
        )

    // ── Node ─────────────────────────────────────────────────────────────────

    override suspend fun status(): NodeStatus {
        check(NodeOperation.STATUS)
        return NodeStatus(ready = ready, detail = if (ready) "ready" else "not ready")
    }

    override suspend fun observe(server: ResourceName): WorkloadObservation {
        check(NodeOperation.OBSERVE)
        return workload
    }

    override suspend fun ensureImage(image: ImageRef): ImageAvailability {
        check(NodeOperation.IMAGE)
        val reference = image.canonical
        if (images.add(reference)) {
            pulls += reference
            return ImageAvailability(image, id = "sha256:${reference.hashCode().toUInt()}", pulled = true)
        }
        return ImageAvailability(image, id = "sha256:${reference.hashCode().toUInt()}", pulled = false)
    }

    override suspend fun ensureWorkload(spec: WorkloadSpec): WorkloadObservation.Present {
        check(NodeOperation.CREATE)
        val existing = workload
        if (existing is WorkloadObservation.Present && existing.state != WorkloadState.SANDBOX_ONLY) {
            // Adoption. A second pass finds what the first one built.
            return existing
        }
        creates += spec
        (spec.storage as? StorageRequest.Persistent)?.let { volumes += it.volume }
        val created =
            WorkloadObservation.Present(
                handle = WorkloadHandle(name, "sandbox-${spec.server}", "container-${spec.server}"),
                state = WorkloadState.CREATED,
                specHash = spec.specHash,
                // The workload keeps the labels it was created with, the way a
                // real runtime does. This is what makes a later drain able to
                // read what the *container* was built with rather than what the
                // definition says after an edit.
                labels = spec.labels + (Labels.SPEC_HASH to spec.specHash),
                createdAt = clock.instant(),
            )
        workload = created
        return created
    }

    override suspend fun startWorkload(handle: WorkloadHandle) {
        check(NodeOperation.START)
        starts += handle
        val present = workload as? WorkloadObservation.Present ?: error("nothing to start")
        workload = present.copy(state = WorkloadState.RUNNING, startedAt = clock.instant())
    }

    override suspend fun exec(
        handle: WorkloadHandle,
        request: ExecRequest,
    ): ExecOutcome {
        check(NodeOperation.EXEC)
        val present = workload as? WorkloadObservation.Present
        if (present?.state != WorkloadState.RUNNING) {
            throw NodeException.Rejected(name, NodeOperation.EXEC, "the container is not running")
        }
        execs += request.command
        return onExec(request.command)
    }

    /**
     * The in-sandbox HTTP channel, answered by whatever is listening on that port.
     *
     * A port with nothing on it is [NodeException.Unreachable], which is what a
     * real node reports for a refused connection — and it is the state a proxy
     * whose plugin failed to load is in, so it has to be reachable from a test.
     */
    override suspend fun callEndpoint(
        handle: WorkloadHandle,
        request: EndpointRequest,
    ): EndpointResponse {
        check(NodeOperation.ENDPOINT)
        endpointCalls += request
        val listener =
            endpoints[request.port]
                ?: throw NodeException.Unreachable(
                    name,
                    NodeOperation.ENDPOINT,
                    "nothing is listening on port ${request.port}",
                )
        return try {
            listener.handle(request)
        } catch (refused: java.io.IOException) {
            throw NodeException.Unreachable(name, NodeOperation.ENDPOINT, refused.message.orEmpty(), refused)
        }
    }

    override suspend fun stopWorkload(
        handle: WorkloadHandle,
        gracePeriod: Duration,
    ) {
        require(gracePeriod.isPositive()) { "the fake node holds the real node to its contract" }
        check(NodeOperation.STOP)
        stops += handle to gracePeriod
        val present = workload as? WorkloadObservation.Present ?: return
        workload = onStop(present)
    }

    override suspend fun removeWorkload(handle: WorkloadHandle): WorkloadRemoval {
        check(NodeOperation.REMOVE)
        val present = workload as? WorkloadObservation.Present
        if (present?.state == WorkloadState.RUNNING) {
            throw NodeException.Rejected(name, NodeOperation.REMOVE, "the container is still running")
        }
        if (present?.handle?.containerId != null) containerRemovals += handle
        if (sandboxRemovalFails) {
            // The realistic partial failure: the container went, and tearing the
            // sandbox down did not. What the runtime reports afterwards is a
            // sandbox with nothing in it — indistinguishable, from the outside,
            // from one whose container it has simply stopped mentioning.
            present?.let {
                workload =
                    it.copy(
                        state = WorkloadState.SANDBOX_ONLY,
                        handle = it.handle.copy(containerId = null),
                    )
            }
            return WorkloadRemoval(
                containerRemoved = true,
                sandboxRemoved = false,
                detail = "the sandbox teardown failed",
            )
        }
        removals += handle
        workload = WorkloadObservation.Absent
        // `volumes` is deliberately not touched: the world outlives the
        // container.
        return WorkloadRemoval.COMPLETE
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The gate every operation goes through, and the one place cancellation is
     * honoured.
     *
     * A real [Node] crosses a gRPC boundary on every call, so a cancelled
     * coroutine never reaches the runtime — CLAUDE.md requires exactly that of
     * anything crossing the `:cri` boundary. Nothing in this fake suspends, so
     * without this check a cancelled pass would keep driving the "runtime",
     * issuing execs and stops that a real node would have refused. That is the
     * difference between a test that pins where a shield ends and one that
     * cannot see cancellation at all.
     */
    private suspend fun check(operation: NodeOperation) {
        currentCoroutineContext().ensureActive()
        calls += operation
        rawFailures[operation]?.let { throw it }
        alwaysFailures[operation]?.let { throw it }
        onceFailures[operation]?.removeFirstOrNull()?.let { throw it }
    }

    /** The stock answers, so a test can override one command and keep the rest. */
    fun defaultExec(command: List<String>): ExecOutcome =
        when {
            command.firstOrNull() == "mc-monitor" -> {
                if (joinable) {
                    ExecOutcome(0, "version=1.21.8 online=$online max=20 motd=a server", "")
                } else {
                    ExecOutcome(1, "", "connection refused")
                }
            }

            command == PaperCommands.saveAll() -> {
                if (savesCleanly) {
                    ExecOutcome(0, "Saved the game", "")
                } else {
                    // The trap from `failure-modes.md` item 2: exit zero, no
                    // completion. It is a failed save.
                    ExecOutcome(0, "Unknown command. Try /help", "")
                }
            }

            else -> {
                ExecOutcome(0, "", "")
            }
        }
}

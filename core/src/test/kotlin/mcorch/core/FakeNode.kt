package mcorch.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mcorch.core.paper.PaperCommands
import mcorch.cri.StopGracePeriod
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

    /**
     * The whole request, so a test can assert on the **deadline** an exec was given
     * and not only on what it ran.
     *
     * [execs] is the list nearly every assertion wants and it stays the primary one.
     * This exists because `ExecRequest.timeout` is a duration a definition supplies
     * and a `Node` turns into a transport deadline — see `ExecTimeoutCeiling` — so
     * "what was this call allowed to take" is a side effect like any other.
     */
    val execRequests: MutableList<ExecRequest> = mutableListOf()

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
     * Lifts every fault armed against [operation].
     *
     * The operator half of a repair. A node fault that a human fixes — a
     * permission corrected, a wedged runtime restarted — is not expressible with
     * [failOnce] alone, because that decides in advance how many passes the fault
     * lasts; a gate that only lifts on a definition change needs the fault to
     * outlive an arbitrary number of passes and then stop.
     */
    fun clearFailures(operation: NodeOperation) {
        alwaysFailures.remove(operation)
        onceFailures.remove(operation)
        rawFailures.remove(operation)
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

    /**
     * The pre-flight, and deliberately **not** `check(NodeOperation.CREATE)`.
     *
     * `LocalNode` answers this out of its own mount derivation and never touches
     * the runtime, so a one-shot CRI flake armed against the create is not
     * something this call could see — and consuming one here would silently spend a
     * budget a test set for the create itself, which is the fake being *more*
     * permissive than the thing it stands in for in one direction and less in the
     * other.
     *
     * A standing [NodeException.Rejected] is exactly the shape it does see: an
     * artefact that is not on this host is not there for the pre-flight either.
     */
    override suspend fun checkWorkload(spec: WorkloadSpec) {
        currentCoroutineContext().ensureActive()
        (alwaysFailures[NodeOperation.CREATE] as? NodeException.Rejected)?.let { throw it }
    }

    /**
     * How many containers this node has created, ever.
     *
     * It is in the container id, and that is not decoration: a real runtime never
     * reuses one, so a fake that hands the *same* id to a replacement makes
     * "is this the container the drain signalled" true by construction — and an
     * identity test written against it reports a record correctly retired in
     * exactly the scenario where it outlived its container. The sandbox id is not
     * counted, because a replacement really can land in the sandbox that is
     * already there.
     */
    private var containersCreated: Int = 0

    override suspend fun ensureWorkload(spec: WorkloadSpec): WorkloadObservation.Present {
        check(NodeOperation.CREATE)
        val existing = workload
        if (existing is WorkloadObservation.Present && existing.state != WorkloadState.SANDBOX_ONLY) {
            // Adoption. A second pass finds what the first one built.
            return existing
        }
        creates += spec
        (spec.storage as? StorageRequest.Persistent)?.let { volumes += it.volume }
        containersCreated++
        val created =
            WorkloadObservation.Present(
                handle =
                    WorkloadHandle(
                        name,
                        // Adopted when it is there, the way `ensureWorkload`
                        // adopts a sandbox rather than building a second one.
                        (existing as? WorkloadObservation.Present)?.handle?.sandboxId ?: "sandbox-${spec.server}",
                        "container-${spec.server}-$containersCreated",
                    ),
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
        execRequests += request
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
        gracePeriod: StopGrace,
    ) {
        // The same rule the real node applies, asked of the same object, so this
        // fake cannot accept a grace period `LocalNode` would refuse. It used to
        // be a local `isPositive()`, which is the *weaker* half — a caller that
        // reached this fake with an unbounded or overflowing value would have
        // been told it was fine.
        //
        // The interface's *own* ceiling is not re-asked here, and could not be:
        // since the thirtieth audit it is carried by [StopGrace], so a caller
        // cannot have skipped it. What is left is this node's runtime bound, which
        // a second implementation is free to differ on — and holding the fake to
        // the real one's is the point.
        StopGracePeriod.of(gracePeriod.period).getOrElse {
            throw IllegalArgumentException("the fake node holds the real node to its contract: ${it.message}", it)
        }
        check(NodeOperation.STOP)
        // Recorded as the duration that reached the runtime, which is what every
        // assertion about a stop is about. [StopGrace] carries nothing else.
        stops += handle to gracePeriod.period
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

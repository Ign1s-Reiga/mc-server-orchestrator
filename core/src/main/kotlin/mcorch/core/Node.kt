package mcorch.core

import mcorch.schema.ImageRef
import mcorch.schema.NodeName
import mcorch.schema.PaperServerDefaults
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.ServerKind
import java.time.Instant
import kotlin.time.Duration

/**
 * Where a container runs.
 *
 * One of the three distribution seams (see CLAUDE.md). Today there is exactly
 * one implementation and it talks to a containerd on localhost — but nothing
 * about that appears below. There is no CRI type in this file, no socket, no
 * "the local one": a [Node] is addressed by [name] and every container
 * operation is a suspending call that may fail because a *remote* machine is
 * unreachable. A distributed implementation is an agent client behind this same
 * interface, and no call site changes.
 *
 * ## The unit is a workload, not a container
 *
 * CRI splits a running server into a sandbox and a container inside it, with an
 * ordering that is easy to get wrong and a teardown that can forcibly kill a
 * server mid-save. That split is a runtime detail, so it lives behind this
 * interface: [ensureWorkload] brings the whole thing into existence and
 * [removeWorkload] takes the whole thing away, in the right order.
 *
 * ## Idempotency
 *
 * [ensureImage] and [ensureWorkload] are idempotent by contract: called twice
 * with the same argument they perform the side effect at most once and adopt
 * what is already there. That is what lets the reconcile loop run a pass
 * repeatedly without accumulating containers or re-downloading images.
 * Implementations discover what they created last time by *listing by label*,
 * never by trusting an ID a caller remembered.
 *
 * ## Failure
 *
 * Every function here throws [NodeException] and nothing else from this module.
 * Branch on [NodeException.retryable]. No `mcorch.cri` type — exception or
 * otherwise — is allowed to escape an implementation, because a caller that
 * pattern-matches on one has quietly assumed the node is a local containerd.
 *
 * ## Stopping
 *
 * [stopWorkload] takes a [StopGrace] rather than a bare duration, so this
 * interface's own operational ceiling is applied by construction and no
 * implementation can fail to apply it. Stopping is never unconditional either:
 * the caller has confirmed zero players and a completed save first — see
 * `.claude/skills/drain-protocol/`.
 *
 * ## Every duration crossing this interface is a transport deadline
 *
 * An implementation of a `Node` is a client of something, and the durations a
 * caller hands it become that transport's deadlines: the containerd
 * implementation derives `stopContainer`'s from the grace period and `execSync`'s
 * from [ExecRequest.timeout] directly. CLAUDE.md requires every call crossing the
 * `:cri` boundary to have a timeout, and a deadline derived from an unbounded
 * argument is not one. So both of those arguments are **bounded types** —
 * [StopGrace] and [ExecTimeout] — whose factories are the only way to obtain one.
 * That is deliberately not a rule each implementation applies for itself: a rule
 * every implementation has to remember is a rule the second implementation
 * breaks, and this interface is the distribution seam.
 */
public interface Node {
    /** How this node is addressed. Stable for the node's lifetime. */
    public val name: NodeName

    /**
     * Whether the node can accept work, and what it has to offer.
     *
     * The cheapest call here. A node that answers this is reachable; one that
     * throws [NodeException.Unreachable] is not, which is a requeue rather than
     * a failure of any particular server.
     */
    public suspend fun status(): NodeStatus

    /**
     * What is running for [server] on this node, discovered by label.
     *
     * The reconcile loop's "observe" step. Absence is [WorkloadObservation.Absent],
     * not an exception: a server that has never been created is a normal state.
     */
    public suspend fun observe(server: ResourceName): WorkloadObservation

    /**
     * Makes [image] available on the node, pulling only if it is not already
     * there.
     *
     * [ImageAvailability.pulled] reports whether this call actually pulled, so a
     * caller can tell "already present" from "downloaded just now" — and so a
     * test can assert that a second reconcile pass did not re-pull.
     */
    public suspend fun ensureImage(image: ImageRef): ImageAvailability

    /**
     * Whether this node could build [spec], asked *before* something irreversible
     * is done on the assumption that it can.
     *
     * ## The question exists because the answer arrived too late once
     *
     * A hash-bearing edit to a proxy is a replacement: drain to zero, stop, remove,
     * then create. Every one of those steps is correct and the last one is where
     * the node discovers it has no copy of the control plugin to mount — so the
     * front door is already gone and the loop has just learned, permanently, that
     * it cannot build another. Nothing stages that artefact for an ordinary
     * install, so it is the default state rather than an unlucky one.
     *
     * Returns normally when the workload could be built, and throws exactly what
     * [ensureWorkload] would have thrown otherwise. It is deliberately **not** a
     * second copy of the checks: an implementation answers this by running the same
     * derivation the create runs and discarding the result, so a rule that gains a
     * new case gains it in both places at once or in neither.
     *
     * Cheap and side-effect free: it creates nothing, and it must not — the caller
     * is a pass that has decided to do nothing yet.
     *
     * Those two paragraphs pull against each other, and the seam between them is
     * where an implementation has to be explicit. A create that *prepares* something
     * before it builds — host directories, in `LocalNode` — cannot have that half
     * checked without doing it, so the half is outside the promise and the
     * implementation says which half. A caller therefore gets "the derivation says
     * yes", never "the create will succeed": a create that refuses after a
     * successful pre-flight is narrower than it was, not impossible.
     *
     * A node that cannot answer without contacting a runtime may throw
     * [NodeException.Unreachable], which the caller requeues; "I could not check"
     * is never "yes".
     */
    public suspend fun checkWorkload(spec: WorkloadSpec)

    /**
     * Brings the workload described by [spec] into existence, or adopts the one
     * already there. Does **not** start it.
     *
     * Adoption is by label, and it is what makes a repeated pass safe. A
     * workload that exists but was created from a different
     * [WorkloadSpec.specHash] is adopted and reported with its old hash: it is
     * the caller's decision — never this interface's — whether that difference
     * warrants draining and replacing it.
     */
    public suspend fun ensureWorkload(spec: WorkloadSpec): WorkloadObservation.Present

    /**
     * Starts a created workload. Idempotent: starting a running workload is a
     * no-op, not a failure.
     *
     * Returning means the process was started. It does **not** mean the server
     * is joinable — that is what [exec] and a readiness probe are for.
     */
    public suspend fun startWorkload(handle: WorkloadHandle)

    /**
     * Runs a command inside the workload and waits for it to exit.
     *
     * The mechanism behind "save the world and confirm it completed", and
     * behind reading a player count. This interface deliberately does not
     * interpret the result: what counts as a completed save is a property of
     * the server kind, and a command that exits zero having printed an error is
     * a failed save. [ExecOutcome] is the raw answer; the caller judges it.
     */
    public suspend fun exec(
        handle: WorkloadHandle,
        request: ExecRequest,
    ): ExecOutcome

    /**
     * Sends an HTTP request to a port the workload listens on, and returns what
     * came back.
     *
     * The channel to a control plane running *inside* a container — today the
     * shipped Velocity plugin, which is how the drain protocol's steps 2, 4 and 6
     * happen at all. It is on [Node] rather than in the caller for the same
     * reason [exec] is: a caller that opened its own socket would have decided
     * the container is on this machine, and CLAUDE.md invariant 7 says nothing in
     * the loop may assume that. A distributed node forwards this to its agent and
     * no call site changes.
     *
     * Deliberately uninterpreted, exactly like [exec]. This interface knows
     * nothing about the protocol being spoken: a non-2xx status is a
     * [EndpointResponse], not an exception, because what a 409 means is a
     * property of the protocol and the caller is the only thing that knows it.
     * Only a failure to *reach* the port at all is a [NodeException].
     *
     * ## The token is a coordinate
     *
     * [EndpointRequest.bearerToken] is a [SecretRef], never material, and the
     * implementation resolves it at the moment it builds the request — the same
     * rule [WorkloadSpec.secretEnv] follows. `:core` therefore has no way to hold
     * the token, so it has no way to log it (CLAUDE.md invariant 4 generalised).
     */
    public suspend fun callEndpoint(
        handle: WorkloadHandle,
        request: EndpointRequest,
    ): EndpointResponse

    /**
     * Stops the workload's container, killing it after [gracePeriod].
     *
     * **Never call this on a server with players online, and never before a
     * completed world save has been confirmed.** The grace period is the
     * last-resort safety net for a container that reaches here anyway, not the
     * save path.
     *
     * [gracePeriod] comes from `spec.lifecycle.stopGracePeriod` and is used as
     * nothing else. For a `PaperServer` the schema guarantees it exceeds the save
     * timeout (`SpecInvariants.stopGraceProblem`); a `VelocityProxy` has no such
     * rule and needs none, because it holds no world. Nothing may read this value
     * *as* a save timeout on the strength of the first half —
     * `DrainSubject.saveTimeout` is the quantity for that.
     *
     * **It is bounded above twice, and neither bound is for tidiness.**
     *
     * The first is this interface's own operational ceiling, and it is carried by
     * the argument's *type*: a [StopGrace] can only be obtained from
     * [StopGrace.of], which applies [StopGraceCeiling]. A `Node` call is a call
     * over a transport with a deadline, and an implementation that derives that
     * deadline from the grace period — which the containerd one does — has no
     * effective timeout at all once the grace period is large enough. See
     * [StopGraceCeiling] for why it caps rather than refuses, and why the cap has a
     * floor.
     *
     * The second belongs to the runtime and stays with the implementation. A
     * container runtime carries the grace period as a fixed-width count, and past
     * some magnitude its own arithmetic wraps: the value stops meaning "wait
     * longer" and starts meaning "kill now", while the call still reports success.
     * So an implementation may refuse a grace period for being *too large*, and a
     * caller must not read a bigger number as a safer one. Where that bound
     * actually is belongs to the runtime — `mcorch.cri.StopGracePeriod` for the
     * containerd implementation, which carries the measurements it was derived
     * from. Zero, negative and `Duration.INFINITE` reach here intact and are
     * refused there for the same reason: they are not durations anybody meant.
     *
     * Idempotent: stopping an already-stopped workload succeeds.
     */
    public suspend fun stopWorkload(
        handle: WorkloadHandle,
        gracePeriod: StopGrace,
    )

    /**
     * Removes the workload's container and then its sandbox.
     *
     * **Persistent storage survives this**, which is the entire point of
     * [StorageRequest.Persistent]: the volume outlives the container, so a
     * removal here does not take the world with it.
     *
     * The caller must have stopped the container first. An implementation that
     * finds a *running* container refuses with [NodeException.Rejected] rather
     * than forcing it down, because the runtime's own teardown kills whatever
     * is still inside with no grace and no save.
     *
     * Idempotent: removing what is already gone succeeds.
     *
     * ## Partial progress is reported, not thrown
     *
     * Removing a workload is two operations with a required order, and the
     * second can fail after the first succeeded. That is reported as a
     * [WorkloadRemoval] rather than as an exception, because the caller has to
     * record it: a container this node has removed must stop counting as one
     * that exists, or the next pass sees a sandbox with no containers in it,
     * cannot tell that from a runtime hiding a live container, and refuses to
     * retry the half that failed — for ever. A failure with *nothing* removed
     * still throws; there is nothing to record.
     */
    public suspend fun removeWorkload(handle: WorkloadHandle): WorkloadRemoval
}

/**
 * The longest a [Node] will hold a stop open for, whatever it is handed.
 *
 * ## What it is defending against
 *
 * A stop is a call over a transport, and an implementation derives that
 * transport's deadline from the grace period — `GrpcCriClient.stopContainer` does
 * exactly that, `gracePeriod + slack`. So the grace period is not only what the
 * runtime waits, it is how long a reconcile worker is parked at a container that
 * does not exit, and CLAUDE.md requires every call crossing the `:cri` boundary to
 * have a timeout. `StopGracePeriod` bounds it only where containerd's own
 * arithmetic would wrap, which is 292 years away: a value anywhere below that
 * clears every check in the system and parks a worker with no effective timeout.
 *
 * ## Where such a value comes from, since no reader will produce one
 *
 * `PaperServerReader` caps `spec.lifecycle.stopGracePeriod` at
 * [PaperServerDefaults.MAX_STOP_GRACE_PERIOD] and `VelocityProxyReader` caps its
 * own lower still, but neither type enforces it: `LifecycleSpec.init` checks only
 * the save-timeout relation and `ProxyLifecycleSpec` has no `init` at all. A
 * definition that did not come through a reader — a hand-edited store row, a
 * migration, a fixture — therefore carries anything, and `DefinitionCodec` does not
 * re-run the reader's validation. That is the same second arrival route
 * `WorkloadSpec`'s `init` is written around.
 *
 * ## Why it caps rather than refuses, which is the interesting half
 *
 * A `require` in `LifecycleSpec` would make the whole definition undecodable, and a
 * row the store cannot decode costs the fleet rather than the server. A refusal
 * *here* is charged to one server and looks tidier — but the operation it refuses
 * is the **stop**, and a stop nobody can issue is a populated, world-holding server
 * nobody can retire, which is the state that ends in a manual `crictl stop`. That is
 * a certain harm traded for a conditional one.
 *
 * Capping is safe because of where in the protocol the stop sits: every path to
 * [Node.stopWorkload] ends in `mayStop` — there are **two** of them and
 * `DrainWiringTest` holds the count, not this sentence; see `DrainController`'s
 * class note, which is where the difference between them is written — so a
 * completed world save has already been confirmed (CLAUDE.md invariant 3) and the
 * grace period is the last-resort net rather than the save path. And [MAX] is the
 * largest value any reader in this system accepts, so no definition an operator
 * could legitimately write is shortened by a single second.
 *
 * The constant is *borrowed* rather than restated: the property being relied on is
 * "no reader accepts more than this", which is exactly what
 * [PaperServerDefaults.MAX_STOP_GRACE_PERIOD] means, so raising the reader's cap
 * moves this with it instead of silently making the cap bite.
 *
 * ## Why the ceiling has a floor, which is the thirtieth audit's finding
 *
 * `stopGracePeriod` and `drain.saveTimeout` are a **validated pair**:
 * `LifecycleSpec.init` refuses a `PaperServer` whose grace period does not exceed
 * its save timeout by [PaperServerDefaults.MIN_STOP_GRACE_MARGIN], because — in the
 * schema's own words — *"a grace period shorter than the save timeout kills the
 * container part-way through the save"*. A ceiling applied to one half of that pair
 * by a consumer that cannot see the other half can **invert it**: a row carrying
 * `saveTimeout = 3h` and `stopGracePeriod = 3h1m` satisfies the schema, decodes,
 * confirms its save, and used to be stopped with two hours — SIGKILL part-way
 * through Paper's own shutdown save, which is a torn region file.
 *
 * That population is not independent of this one. The cap only ever fires on a
 * definition that bypassed `PaperServerReader` (nothing else produces a grace
 * period above two hours), and that is exactly the population that can also carry a
 * save timeout above `PaperServerDefaults.MAX_TIMEOUT`. So [bound] takes the save
 * timeout and caps to `max(MAX, saveTimeout + MIN_STOP_GRACE_MARGIN)`: the schema's
 * relation survives whatever this does, and for every pair a reader would accept
 * the floor is below [MAX] and changes nothing.
 *
 * **The residual, stated rather than discovered.** A save timeout large enough that
 * the derived floor passes what the *runtime* accepts leaves the stop refused by
 * `StopGracePeriod` — which for containerd is 292 years away, so it needs both
 * halves of the pair to be absurd rather than merely unvalidated. That refusal is
 * the cap-versus-refuse trade pointing the other way, and it is the right way round
 * here: a refusal is recorded and loud, where a cap that inverts the pair is silent
 * and costs a world. It is also the same answer `Duration.INFINITE` already gets.
 */
public object StopGraceCeiling {
    /** See the note above. Two hours, borrowed from the widest cap any reader applies. */
    public val MAX: Duration = PaperServerDefaults.MAX_STOP_GRACE_PERIOD

    /**
     * The ceiling that applies to a workload whose world save may take
     * [saveTimeout]: [MAX], or high enough to keep the schema's margin above the
     * save timeout when that is higher.
     *
     * A save timeout that is not a finite positive duration gets [MAX] and no
     * floor. `Duration.ZERO` is the honest answer from a workload that holds no
     * world (`DrainSubject.saveTimeout`), and there is nothing to protect there;
     * anything else in that bucket is not a duration anybody meant, and reading one
     * as a licence to raise this ceiling is how an uninterpretable field becomes a
     * plausible-looking stop.
     */
    public fun ceilingFor(saveTimeout: Duration): Duration =
        if (saveTimeout.isFinite() && saveTimeout.isPositive()) {
            maxOf(MAX, saveTimeout + PaperServerDefaults.MIN_STOP_GRACE_MARGIN)
        } else {
            MAX
        }

    /**
     * [requested], or [ceilingFor] [saveTimeout] if that is lower and [requested] is
     * **finite**.
     *
     * Deliberately returns the duration alone and no flag about it: a caller that
     * wants to log the difference has both values in hand, and a value beside a
     * boolean about itself is the shape this codebase keeps having to unpick.
     *
     * Non-positive and non-finite values pass through untouched, and that is the
     * whole of the split: a grace period that is too long is a number somebody
     * meant, and capping it costs nothing because a confirmed save is already
     * behind it. `Duration.INFINITE`, zero and a negative are not durations anybody
     * meant — capping `INFINITE` to two hours would turn an argument the code cannot
     * interpret into a plausible-looking stop — so they stay refusals, and they
     * belong to the rule that owns them at the runtime edge (`StopGracePeriod.of`),
     * which is also the rule whose message an operator reads.
     */
    public fun bound(
        requested: Duration,
        saveTimeout: Duration,
    ): Duration {
        val ceiling = ceilingFor(saveTimeout)
        return if (requested.isFinite() && requested > ceiling) ceiling else requested
    }
}

/**
 * A stop grace period that has been through [StopGraceCeiling].
 *
 * The type exists because of what the thirtieth audit said about the shape of the
 * old fix: the ceiling was applied inside `LocalNode.stopWorkload`, so the property
 * "a `Node` never holds a stop open past the operational ceiling" was held by one
 * implementation doing the right thing, and pinned by a test *a second
 * implementation is not required to pass*. [Node] is the distribution seam; an
 * invariant of the seam that each implementation has to remember is an invariant
 * the second implementation breaks. A parameter type whose only factory applies the
 * bound turns "every implementation must remember" into "no implementation can
 * fail to".
 *
 * It carries the **policy** ceiling only. Each implementation keeps its own runtime
 * bound — `StopGracePeriod.of` for the containerd one — because where a runtime's
 * arithmetic wraps is a fact about that runtime and not about this interface. See
 * [Node.stopWorkload] for the two-bound structure.
 */
@JvmInline
public value class StopGrace private constructor(
    /** What the stop is actually given. Never above [StopGraceCeiling.ceilingFor]. */
    public val period: Duration,
) {
    override fun toString(): String = period.toString()

    public companion object {
        /**
         * The only way to obtain a [StopGrace].
         *
         * Total, and that is the point: it caps rather than refusing, so no caller
         * has a failure to handle and none is tempted to build one another way.
         * [saveTimeout] is the workload's own — `DrainSubject.saveTimeout`, which
         * every subject answers for itself — and it is the *floor* the ceiling may
         * not cut below. Both quantities are read from one definition at the call
         * site, which is what makes clamping one of a validated pair impossible
         * here.
         */
        public fun of(
            requested: Duration,
            saveTimeout: Duration,
        ): StopGrace = StopGrace(StopGraceCeiling.bound(requested, saveTimeout))
    }
}

/** What a node reports about itself. */
public data class NodeStatus(
    /** True when the node can accept containers right now. */
    val ready: Boolean,
    /** Operator-facing detail. Never an address. */
    val detail: String = "",
    val capacity: NodeCapacity = NodeCapacity(),
)

/**
 * What a node has to offer a scheduler. Every field is nullable and null means
 * "not reported", never "zero" — a single-host deployment has no reason to
 * account for any of it, and a scheduler that cannot tell the difference would
 * refuse to place anything.
 */
public data class NodeCapacity(
    val allocatablePlayers: Int? = null,
    val allocatableMemoryBytes: Long? = null,
    val allocatableCpuMillicores: Int? = null,
)

/**
 * A handle on one server's runtime objects on one node.
 *
 * Carries [node] so it cannot be used against the wrong one: a handle is not a
 * bare ID and there is no "current node" it could be resolved against.
 * [containerId] is null between sandbox creation and container creation — an
 * honest gap rather than a placeholder.
 */
public data class WorkloadHandle(
    val node: NodeName,
    val sandboxId: String,
    val containerId: String? = null,
) {
    init {
        require(sandboxId.isNotBlank()) { "sandboxId must not be blank" }
        require(containerId == null || containerId.isNotBlank()) { "containerId must not be blank when set" }
    }
}

/** What the runtime is doing with a workload. Not a readiness verdict. */
public enum class WorkloadState {
    /** The sandbox exists; no container has been created in it yet. */
    SANDBOX_ONLY,

    /** The container exists and has not been started. */
    CREATED,

    /** The process is running. A Paper server is `RUNNING` throughout world generation and while deadlocked. */
    RUNNING,

    /** The process has exited and been reaped. There is provably nobody connected. */
    EXITED,

    /** The runtime reported something this build does not recognise. Not a reason to act. */
    UNKNOWN,
}

/** What a node found when it looked for a server's workload. */
public sealed interface WorkloadObservation {
    /** Nothing on this node belongs to that server. */
    public data object Absent : WorkloadObservation

    public data class Present(
        val handle: WorkloadHandle,
        val state: WorkloadState,
        /**
         * The [WorkloadSpec.specHash] the workload was created from, or null if
         * it carries none. A value different from the desired one is how the
         * loop sees "the definition changed under a running server".
         */
        val specHash: String? = null,
        /**
         * The labels the workload actually carries, as the runtime reports
         * them.
         *
         * This is how a caller reads *what the container was built with* rather
         * than what the definition says today. A drain has to be conducted
         * against the running container — whether it holds world data, whether
         * it has a channel that can confirm a save — and after an edit the
         * definition no longer describes it. Empty when the runtime reports no
         * labels, which means "unknown", never "false".
         */
        val labels: Map<String, String> = emptyMap(),
        /** What the runtime resolved the image to, for drift detection. */
        val imageId: String? = null,
        val createdAt: Instant? = null,
        val startedAt: Instant? = null,
        val finishedAt: Instant? = null,
        val exitCode: Int? = null,
        /** Short runtime explanation such as `OOMKilled`. Empty when none was given. */
        val reason: String = "",
        /** Runtime detail. Never player data. */
        val message: String = "",
    ) : WorkloadObservation
}

/**
 * How far a [Node.removeWorkload] got.
 *
 * [complete] is the only thing a caller may treat as "this workload is gone".
 * Anything else means the node made progress it cannot undo and the call has to
 * come back — with the *progress recorded first*, which is the whole reason this
 * is a value and not an exception.
 */
public data class WorkloadRemoval(
    /** True once no container remains for this workload, including "there never was one". */
    val containerRemoved: Boolean,
    /** True once the sandbox is gone too. */
    val sandboxRemoved: Boolean,
    /** Why it did not finish, for an operator. Empty when it did. */
    val detail: String = "",
) {
    public val complete: Boolean get() = containerRemoved && sandboxRemoved

    public companion object {
        public val COMPLETE: WorkloadRemoval = WorkloadRemoval(containerRemoved = true, sandboxRemoved = true)
    }
}

/** The result of making an image available. */
public data class ImageAvailability(
    val image: ImageRef,
    /** The node-unique ID the runtime resolved it to. */
    val id: String,
    /** True when *this call* pulled. False when the image was already there. */
    val pulled: Boolean,
)

/**
 * Everything a node needs to bring one server's workload into existence.
 *
 * Deliberately not a CRI type: it names no sandbox, no namespace and no host
 * path. How this becomes a sandbox plus a container is the implementation's
 * business, and a remote node would answer that question differently.
 *
 * Secret material never appears here. [secretEnv] holds *coordinates* into the
 * secret store, and the implementation resolves them at the moment it hands
 * them to the runtime — see CLAUDE.md invariant 4.
 */
public data class WorkloadSpec(
    val server: ResourceName,
    val kind: ServerKind,
    val image: ImageRef,
    /**
     * A fingerprint of the parts of the definition that require the workload to
     * be recreated rather than updated in place. Recorded on the workload so a
     * later pass can compare without re-deriving anything.
     */
    val specHash: String,
    val storage: StorageRequest,
    /**
     * Artefacts this orchestrator ships that have to be inside the container.
     *
     * Coordinates, exactly like [secretEnv]: an [AssetMount] names *what* is
     * needed and *where in the container* it goes, and the node resolves it to
     * whatever it has locally at the moment it builds the container. No path on
     * any host appears here, so a workload spec stays something a remote node
     * could satisfy from its own copy.
     */
    val assets: List<AssetMount> = emptyList(),
    val resources: ResourceRequest,
    val hostname: String,
    val env: Map<String, String> = emptyMap(),
    /** Environment variables whose values live in the secret store. Coordinates only. */
    val secretEnv: Map<String, SecretRef> = emptyMap(),
    val command: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val ports: List<PortRequest> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
) {
    // What a **planner** can get wrong, and deliberately not what an *operator* or
    // a *stored row* can.
    //
    // The distinction is not stylistic. A `require` here throws out of
    // `Reconciler`'s `Pass` construction, which happens before the delete exemption
    // and lands in `rejectDefinition` — a `PERMANENT` failure recorded with no
    // exemption for a terminating definition. So a rule enforced here about a value
    // an operator supplies makes the server **permanently unreconcilable, drain and
    // delete included**: exactly the undeletable-populated-server state that ends
    // in a manual `crictl stop`. And a `require` cannot be made to know whether the
    // definition it came from is on its way out.
    //
    // Every check below is therefore about a value the planners derive from *code*
    // — a blank hash, a blank hostname, two assets at one path, all of which are
    // closed sets in this repository and none of which any YAML or store row can
    // reach. Freezing on one of those is correct: the repair is a code change.
    //
    // Rules about operator-supplied paths — that a persistent mount is absolute,
    // that an asset does not shadow it — live in `HostPaths`, which refuses the
    // *create* through `NodeException.Rejected` and leaves the drain able to run.
    // See `StorageRequest.Persistent`.
    init {
        require(specHash.isNotBlank()) { "specHash must not be blank" }
        require(hostname.isNotBlank()) { "hostname must not be blank" }
        require(assets.distinctBy { it.destination }.size == assets.size) {
            "two assets must not be mounted at the same path: ${assets.map { it.destination }}"
        }
        require(env.keys.none { it.isBlank() }) { "environment variable names must not be blank" }
        require(secretEnv.keys.none { it.isBlank() }) { "environment variable names must not be blank" }
        require(env.keys.none { it in secretEnv.keys }) {
            "an environment variable must not be declared both plainly and as a secret"
        }
    }

    /** Environment *values* are redacted, the same way the runtime's own spec redacts them. */
    override fun toString(): String =
        "WorkloadSpec(server=$server, kind=$kind, image=${image.canonical}, specHash=$specHash, " +
            "storage=$storage, assets=$assets, resources=$resources, hostname=$hostname, " +
            "env=${env.keys.sorted()}=<redacted>, " +
            "secretEnv=${secretEnv.keys.sorted()}=<from secret store>, command=$command, args=$args, " +
            "ports=$ports, labels=$labels)"
}

/**
 * Where the server's data lives.
 *
 * [Persistent] outlives the container: removing and recreating the workload
 * keeps the world. [Ephemeral] is the only case that skips it, and only
 * explicitly-disposable kinds may ask for it (CLAUDE.md invariant 2).
 *
 * ## Only [Persistent] carries a path, and that is the point
 *
 * [Ephemeral] used to carry a `mountPath` too, and the single-host node
 * discarded it — a field that every planner filled in, that no implementation
 * read, and that read like configuration in every test that asserted on it.
 * That is how the proxy's control plugin came to be "mounted" at a path nothing
 * ever mounted anything at. A path in this type is now a path that is honoured;
 * ephemeral means the container's own writable layer and nothing else. Anything
 * that has to be *put* somewhere inside the container is an [AssetMount].
 */
public sealed interface StorageRequest {
    public data class Persistent(
        /** Names the claim. Deliberately not a host path — that would pin the server to a node. */
        val volume: ResourceName,
        /**
         * Where the world appears inside the container. Absolute — but **not
         * checked here**, and the absence is deliberate.
         *
         * This value comes from `spec.storage.mountPath`, so it is operator data
         * that also arrives by a second route: a stored row read back through
         * `DefinitionCodec`, which does not re-run the reader's validation. An
         * `init` check would therefore throw out of `Reconciler`'s `Pass`
         * construction — before the delete exemption — and record a `PERMANENT`
         * failure that no drain and no delete can get past. A row that bypassed the
         * reader would freeze a *world-holding* server rather than merely failing
         * its create, and an undeletable populated server is what produces a manual
         * `crictl stop`.
         *
         * `HostPaths` refuses it instead, as a `NodeException.Rejected` on the
         * create. Same permanence, same message, and the drain still runs — a
         * container that already exists is drained against its own labels and never
         * asks this type anything. See `WorkloadSpec`'s `init` for the split.
         *
         * `AssetMount.directory` keeps its `init` checks for the opposite reason:
         * it is derived from a planner constant and a closed enum, so no definition
         * and no store row can reach it.
         */
        val mountPath: String,
    ) : StorageRequest

    public data object Ephemeral : StorageRequest
}

/**
 * An artefact this orchestrator ships, and where it belongs inside a container.
 *
 * ## Why this is not a host path
 *
 * The control plugin has to be inside the proxy container or every backend
 * behind that proxy is undrainable — so *something* has to carry a JAR from
 * where the build put it to where Velocity reads plugins from. Naming that file
 * in a [WorkloadSpec] would put a build-output path into the reconcile loop's
 * vocabulary and make the spec unsatisfiable by any node that does not share
 * this filesystem. So the loop names the [asset] and the node answers with its
 * own copy: the single-host node bind-mounts a file it was configured with, and
 * a distributed node would ship the bytes to its host first. Neither is visible
 * here, which is the test of whether this seam is honest (CLAUDE.md invariant
 * 7).
 *
 * ## Read-only, always
 *
 * There is no writable variant and there is no reason to add one. These are
 * artefacts the orchestrator ships and the container consumes; a container that
 * could rewrite one would be a container that can change what the next one
 * loads.
 */
public data class AssetMount(
    val asset: WorkloadAsset,
    /**
     * The **directory** inside the container the asset is placed in. The file
     * name is the asset's own ([WorkloadAsset.fileName]) — a caller does not get
     * to rename an artefact it does not own.
     */
    val directory: String,
) {
    init {
        require(directory.startsWith("/")) { "directory must be absolute, got: $directory" }
        require(!directory.endsWith("/")) { "directory must not have a trailing slash, got: $directory" }
    }

    /** Where the file lands inside the container. */
    public val destination: String get() = "$directory/${asset.fileName}"
}

/**
 * The artefacts this orchestrator can put inside a container.
 *
 * A closed set, so "which JAR" is a compile-time question. [fileName] is part of
 * the identity rather than a caller's choice: Velocity discovers plugins by
 * scanning for `*.jar`, so an artefact that arrived under the wrong name is an
 * artefact that silently does not load — which is the failure this whole type
 * exists to end.
 */
public enum class WorkloadAsset(
    public val fileName: String,
) {
    /**
     * The Velocity plugin `:velocity-plugin` builds — the control channel drain
     * steps 2, 4 and 6 run through.
     *
     * A proxy without it comes up perfectly well, serves players, and has no
     * control endpoint; every backend behind it is then undrainable, which is a
     * fleet-wide property no single server's status would report.
     */
    VELOCITY_CONTROL_PLUGIN("mcorch-velocity-control.jar"),
}

/** Container limits. The JVM heap that has to fit inside them is already in the environment. */
public data class ResourceRequest(
    val memoryBytes: Long,
    val cpuMillicores: Int? = null,
) {
    init {
        require(memoryBytes > 0) { "memoryBytes must be positive, got: $memoryBytes" }
        require(cpuMillicores == null || cpuMillicores > 0) { "cpuMillicores must be positive when set" }
    }
}

/** A port the workload listens on, and optionally publishes. */
public data class PortRequest(
    /** Operator-facing label such as `game` or `rcon`. */
    val name: String,
    val containerPort: Int,
    /** Null means "do not publish on the host". */
    val hostPort: Int? = null,
) {
    init {
        require(name.isNotBlank()) { "port name must not be blank" }
        require(containerPort in 1..65535) { "containerPort must be in 1..65535, got: $containerPort" }
        require(hostPort == null || hostPort in 1..65535) { "hostPort must be in 1..65535 when set" }
    }
}

/** A command to run inside a workload, with the time it is allowed to take. */
public data class ExecRequest(
    val command: List<String>,
    /**
     * How long the command may run.
     *
     * An [ExecTimeout] rather than a bare duration, for the reason
     * [StopGrace] is one: an implementation turns this straight into a transport
     * deadline (`GrpcCriClient.execSync` does exactly that), so an unbounded value
     * here is a reconcile worker with no effective timeout — the same defect the
     * stop path was fixed for, on the *longer* of the two calls.
     */
    val timeout: ExecTimeout,
) {
    init {
        require(command.isNotEmpty()) { "exec command must not be empty" }
        require(command.none { it.isBlank() }) { "exec command arguments must not be blank" }
        // Finiteness is structural now — [ExecTimeout.of] caps it — so what is left
        // to check is the half a ceiling cannot fix. Zero and negative are refused
        // rather than raised: an exec that may run for no time is not a call
        // anybody meant, and quietly turning it into a real wait would hide the
        // row that produced it.
        require(timeout.period.isPositive()) { "exec timeout must be positive, found ${timeout.period}" }
    }
}

/**
 * How long a command inside a workload may run, bounded by [ExecTimeoutCeiling].
 *
 * ## The number that becomes a gRPC deadline
 *
 * The thirtieth audit's second finding. [StopGraceCeiling]'s whole argument is
 * *"this becomes a transport deadline, so an absurd value parks a worker with no
 * effective timeout"*, and `GrpcCriClient.execSync` does the identical thing with
 * `spec.lifecycle.drain.saveTimeout` — from the same store row, with the same
 * absent type-level bound, on the **longer** of the two calls. A row carrying a
 * 292-year save timeout parked a reconcile worker in `save-all flush`, which is the
 * CLAUDE.md "every `:cri` call has a timeout" violation the stop's ceiling was
 * written to close, left open on the sibling path. A fix derived from *"this number
 * becomes a deadline"* belongs at every number that does.
 *
 * ## Why this one caps `INFINITE` where the stop's ceiling refuses it
 *
 * The two are not inconsistent; what the number authorises differs. A stop's grace
 * period authorises a **kill**, and a shorter one is the more dangerous direction,
 * so an uninterpretable value must not be silently made plausible. An exec timeout
 * authorises only **waiting**: cutting it short can never do more than withhold a
 * confirmation, and a save that is not confirmed is a container this orchestrator
 * will not stop. So capping costs nothing here, and it removes the one route by
 * which an `IllegalArgumentException` from [ExecRequest]'s own `init` could leave an
 * agent as an unclassified failure.
 */
@JvmInline
public value class ExecTimeout private constructor(
    /** What the exec is actually given. Never above [ExecTimeoutCeiling.MAX]. */
    public val period: Duration,
) {
    override fun toString(): String = period.toString()

    public companion object {
        /** The only way to obtain an [ExecTimeout]. Total: it caps rather than refusing. */
        public fun of(requested: Duration): ExecTimeout = ExecTimeout(ExecTimeoutCeiling.bound(requested))
    }
}

/**
 * The longest a [Node] will wait for a command inside a workload.
 *
 * Borrowed rather than restated, for the reason [StopGraceCeiling.MAX] is:
 * [PaperServerDefaults.MAX_TIMEOUT] is what every reader caps a lifecycle timeout
 * at — *"longer than this is a stuck loop, not a slow save"* — so no definition an
 * operator could legitimately write is shortened by a single second, and raising
 * the reader's cap moves this with it.
 *
 * This is **not** a claim about how long a world save takes. It bounds how long
 * *this orchestrator* waits for its own flush to be acknowledged; how long the
 * container is given to finish Paper's shutdown save is the stop grace period, and
 * [StopGraceCeiling] deliberately keeps that above the declared save timeout rather
 * than above this. Two consumers of one field, two bounds, and the difference is
 * which side of the call is doing the waiting.
 */
public object ExecTimeoutCeiling {
    /** One hour, borrowed from the widest cap any reader applies to a lifecycle timeout. */
    public val MAX: Duration = PaperServerDefaults.MAX_TIMEOUT

    /** [requested], or [MAX] if that is lower. Non-finite is capped; see [ExecTimeout]. */
    public fun bound(requested: Duration): Duration = if (requested > MAX) MAX else requested
}

/** The verbs the control protocol uses. A closed set, so a typo is a compile error. */
public enum class HttpVerb {
    GET,
    PUT,
    POST,
    DELETE,
}

/**
 * One request to a port inside a workload.
 *
 * [timeout] is required and strictly positive for the reason [ExecRequest.timeout]
 * is: a control plane that stops answering must not pin a reconcile pass. A drain
 * waits on this one, so an unbounded wait here is a container the orchestrator can
 * never stop.
 */
public data class EndpointRequest(
    val port: Int,
    val verb: HttpVerb,
    val path: String,
    val body: String? = null,
    val contentType: String? = null,
    /**
     * Coordinates of the bearer token, or null when the endpoint is unpublished
     * and needs none. **Never material** — the node resolves it.
     */
    val bearerToken: SecretRef? = null,
    val timeout: Duration,
) {
    init {
        require(port in 1..65535) { "port must be in 1..65535, got: $port" }
        require(path.startsWith("/")) { "path must be absolute, got: $path" }
        require(timeout.isPositive() && timeout.isFinite()) { "endpoint timeout must be positive and finite" }
    }
}

/**
 * What the endpoint answered. Uninterpreted: [status] may be any HTTP status, and
 * deciding what a 409 means belongs to whoever knows the protocol.
 */
public data class EndpointResponse(
    val status: Int,
    val body: String,
) {
    public val successful: Boolean get() = status in 200..299
}

/**
 * What a command did. Uninterpreted on purpose.
 *
 * [exitCode] `0` means the command *exited cleanly*, not that it achieved
 * anything. Judging a save from this is the caller's job, and it has to read
 * the output too.
 */
public data class ExecOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    public val exitedCleanly: Boolean get() = exitCode == 0

    /** Both streams, for pattern matching against a server's reply. */
    public val output: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr"
}

package mcorch.core

import mcorch.schema.ImageRef
import mcorch.schema.NodeName
import mcorch.schema.PaperServerDefaults
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.ServerKind
import mcorch.schema.VelocityProxyDefaults
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
 * caller hands it become that transport's deadlines: the containerd implementation
 * derives `execSync`'s from [ExecRequest.timeout] and an HTTP client's from
 * [EndpointRequest.timeout], each directly, and `stopContainer`'s from the grace
 * period up to a cap of its own. CLAUDE.md requires every call crossing the `:cri`
 * boundary to have a timeout, and a deadline derived from an unbounded argument is
 * not one. So all three of those arguments are **bounded types** — [StopGrace],
 * [ExecTimeout] and [EndpointTimeout] — whose factories are the only way to obtain
 * one. That is deliberately not a rule each implementation applies for itself: a
 * rule every implementation has to remember is a rule the second implementation
 * breaks, and this interface is the distribution seam.
 *
 * The stop is the one where the two numbers have come apart, and a reader has to
 * keep them apart: [StopGrace] bounds what the *container* is given, `:cri` bounds
 * how long this process *waits* for it, and neither substitutes for the other.
 *
 * **A ceiling is not a floor, and the difference is where the two halves live.**
 * Each factory caps; none of them raises a zero or a negative into a plausible
 * wait, because a value the code cannot interpret must not be made to look like one
 * somebody meant. What is left is refused by the request type's own `init`, as an
 * `IllegalArgumentException` — so **a caller that builds a request from a
 * definition field classifies that refusal** rather than letting it escape as an
 * unclassified throwable. `UnbuildableRequestTest` holds the rule
 * against this module's sources; see [EndpointRequest] for what it costs when one
 * site forgets.
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
     * [StopGrace.of], which applies [StopGraceCeiling]. It bounds the **relation**
     * between this value and the save timeout it was validated against, and — the
     * part not to skip — above a save timeout of two hours it stops bounding the
     * magnitude of either: the floor raises the ceiling to the save timeout and it
     * rises with it. See [StopGraceCeiling].
     *
     * So it is not what stops a long grace period parking a reconcile worker. That
     * belongs to the layer that issues the call and it is a separate number:
     * `GrpcCriClient` deadlines a stop at
     * `min(gracePeriod, CriTimeouts.stopDeadlineCap) + deadlineSlack`, two hours by
     * default, while sending the whole grace period on the wire. An implementation
     * that let its deadline *be* the grace period would have no effective timeout at
     * all once the grace period was large enough, and a caller that read the cap as
     * a bound on the container's grace would be wrong about the value the runtime
     * was given — the overdue accounting in `DrainController.awaitStopped` measures
     * against what was sent.
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
 * The bound a [Node] puts on a stop grace period before any runtime is asked to
 * honour it.
 *
 * Not "two hours, whatever it is handed" — [ceilingFor] is the ceiling and it has a
 * floor under it, so above a certain save timeout the effective ceiling is the save
 * timeout rather than [MAX]. What that leaves uncapped is written out below, under
 * *What the floor costs*, and it is the part a reader looking for a deadline needs.
 *
 * ## What it is defending against
 *
 * A stop is a call over a transport, and the grace period is what the runtime is
 * asked to wait. `GrpcCriClient.stopContainer` used to make it the *deadline* as
 * well — `gracePeriod + slack` — so a definition carrying a very long grace period
 * parked a reconcile worker for exactly as long as it asked for, with no effective
 * timeout, which is the CLAUDE.md rule that every call crossing the `:cri` boundary
 * is deadlined. `StopGracePeriod` bounds the value only where containerd's own
 * arithmetic would wrap, 292 years away, so nothing below that was refused either.
 *
 * **That half now belongs to `:cri` and not to this constant**, and the split is
 * the thing to carry: `GrpcCriClient` deadlines the call at
 * `min(gracePeriod, CriTimeouts.stopDeadlineCap) + deadlineSlack` while sending the
 * *whole* grace period on the wire. The cap shortens what this process waits and
 * never what the container is given — so it can only leave a container running
 * longer, never kill it sooner, and the overdue accounting in
 * `DrainController.awaitStopped` still measures against the value the runtime was
 * given.
 *
 * What is left here is a bound on the **pair**, and that is what the sections below
 * are about. Being wrong about which layer owns which is how a reader concludes that
 * capping the grace period would be safe because "the call is deadlined anyway", or
 * that a capped deadline kills a container early. Neither is true.
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
 * ## What the floor costs, over the range where it actually applies
 *
 * The floor is not free, and its price is not at the far end. [ceilingFor] returns
 * `max(MAX, saveTimeout + MIN_STOP_GRACE_MARGIN)`, so **for every save timeout above
 * `MAX - MIN_STOP_GRACE_MARGIN` — one hour fifty-nine and a half — the effective
 * ceiling is the save timeout, not two hours**, and it rises with it without limit
 * until the *runtime's* own bound refuses the stop outright. A row carrying
 * `saveTimeout = 30d` beside `stopGracePeriod = 31d` satisfies `LifecycleSpec.init`,
 * decodes from a nanosecond column, and is capped to `30d30s`: shortened, landed
 * exactly on the smallest grace the schema would have accepted for that pair. Over
 * that whole range this bounds **the relation** and does not bound **the wait**.
 *
 * That is the trade taken deliberately and it is the right way round — a parked
 * worker loses no world, an inverted pair loses one. **The wait it does not bound
 * now has an owner**, and naming it is what keeps this section from reading as an
 * open hole: `GrpcCriClient` deadlines the call at
 * `min(gracePeriod, CriTimeouts.stopDeadlineCap) + deadlineSlack`, so the month-long
 * grace above parks a worker for two hours and not for a month. Two consequences
 * worth stating rather than implying:
 *
 * - **The container still gets the whole month.** The cap is on this process's
 *   deadline, never on what is sent, so a capped stop can only leave a container
 *   running *longer* than an uncapped one — containerd does not escalate to
 *   `SIGKILL` once the request context has expired. It is finished by the drain
 *   re-issuing the stop, which is why that timeout is retryable.
 * - **Nothing here should be re-tightened on the strength of it.** "The call is
 *   deadlined anyway" is not an argument for capping the grace period below the save
 *   timeout: the deadline bounds the wait, and the grace period is the last-resort
 *   net a save depends on.
 *
 * So read this as *"a grace period may not exceed the save it is protecting by more
 * than a reader would allow"*, never as *"a stop is deadlined at two hours"* — that
 * second sentence is true, and it is `:cri`'s to make.
 *
 * **At the top of the range the trade inverts, on purpose.** A save timeout large
 * enough that the derived floor passes what the runtime accepts leaves the stop
 * refused by `StopGracePeriod` rather than capped — 292 years for containerd, so it
 * needs both halves of the pair absurd rather than merely unvalidated. A refusal
 * there is recorded and loud where a cap that inverts the pair is silent and costs a
 * world, and it is the same answer `Duration.INFINITE` already gets.
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
         *
         * **The floor is only a floor while both halves come off one definition**,
         * and that is a property of the *subjects*, not of this signature.
         * `PaperDrainSubject` reads `stopGracePeriod` and `saveTimeout` off the same
         * `LifecycleSpec`, so the pair handed here is the pair
         * `SpecInvariants.stopGraceProblem` validated together and the floor lands on
         * the schema's own boundary. `ProxyDrainSubject` pairs its grace period with
         * a hard-coded `Duration.ZERO`, which is **no floor at all** — correct today
         * because a proxy holds no world to be killed part-way through saving, and
         * the single place a change that gives proxies something to save would remove
         * the floor without touching this file or any test of it. A third subject
         * inherits whichever of the two it was copied from, silently.
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
 * so an uninterpretable value must not be silently made plausible.
 *
 * **The licence to cap is derived for the save exec, and it does not carry to the
 * others.** Cutting `save-all flush` short can do no more than withhold a
 * confirmation, and a save that is not confirmed is a container this orchestrator
 * will not stop — the safe direction, and `PaperServerAgent.requestSave` is the one
 * construction site whose timeout comes off a definition at all. A **probe** cut
 * short is not read as silence, it is read as consent: `DrainController.save`
 * re-probes after the flush and stamps `worldSavedAt` on an `Unanswered` reading
 * exactly as on an `Empty` one, so a probe that merely ran out of time mints the
 * confirmation the stop is gated on, and `awaitStopped` states outright that an
 * unanswered probe falls through with the drain untouched, so it does not hold the
 * re-issue back either. Both probe timeouts are private ten-second constants against
 * a one-hour ceiling, so nothing there is capped and this is not a live defect —
 * but the day a probe timeout comes off a definition field the way `saveTimeout`
 * does, this cap becomes a shortener of the one call whose silence is taken for a
 * zero-player report. **Re-derive the licence at that construction site rather than
 * reading it off this sentence.**
 *
 * ## What the cap does not close, and where the other half went
 *
 * It removes `Duration.INFINITE` as a route into [ExecRequest]'s own `init`, and
 * that is all it removes. **Zero and negative are still refused there**, as an
 * `IllegalArgumentException` — and raising a zero into a real wait here would be the
 * "plausible-looking" move the paragraph above refuses, so this type is the wrong
 * place to close it. It is closed in two others instead:
 *
 * - **At the decode.** `mcorch.schema.SpecBounds` caps a stored row's deadlines, so
 *   no definition that came through a store carries an absurd one. It deliberately
 *   applies no *floor*: flooring `saveTimeout` up to one second raises the minimum
 *   `SpecInvariants.stopGraceProblem` demands of the grace period beside it, which
 *   inverts a pair that satisfied the schema on disk.
 * - **At the construction site.** `PaperServerAgent.requestSave` builds this request
 *   inside a `try` and turns the refusal into `SaveOutcome.NotDelivered`, so a zero
 *   in the row is recorded as a drain failure an operator can see and repair.
 *   Uncaught it was a bare requeue with **no status write** — no failure recorded,
 *   nothing raising `NEEDS_ATTENTION`, the dashboard keeping whatever it had.
 *
 * [EndpointRequest] is the same shape and now carries the same pair; see
 * [EndpointTimeout] for the ceiling and for why its licence to cap is derived at its
 * own call rather than read off this one.
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
 *
 * ## Every field here can arrive from a definition, which is why the whole
 * construction is classified
 *
 * [timeout] is `backends.drain.sealTimeout` off a `VelocityProxy`, and [port] is
 * `spec.control.port` off the same one. Both reach `:core` from a store row as well
 * as from a reader, and `DefinitionCodec` does not re-run the reader's validation —
 * so the checks below are about **operator data**, not about what a planner can get
 * wrong, and they can fail on a definition an operator can also repair.
 *
 * That is the whole argument for the shape at the call sites. The ceiling above is
 * carried by the argument's *type* ([EndpointTimeout]), because a bound every
 * construction site has to remember is a bound the next one forgets. The floor
 * cannot be: raising a zero into a real wait is the "make an uninterpretable value
 * plausible" move a ceiling exists to refuse, so it stays an
 * `IllegalArgumentException` here — and a caller building this from a definition
 * catches it and says what it means. Left uncaught it is thrown inside a drain,
 * outside every typed catch on the way up, and lands in `ReconcileLoop.work`'s
 * `catch (Throwable)` as a bare requeue **with no status write**: no failure
 * recorded, nothing raising `NEEDS_ATTENTION`, the dashboard keeping whatever it
 * had, and the server undeletable for as long as the row says zero.
 *
 * A `require` in the *spec* type would be worse still, for the reason
 * `mcorch.schema.SpecBounds` gives: it makes the row undecodable, which costs the
 * fleet rather than the server.
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
    /**
     * How long the endpoint has to answer.
     *
     * An [EndpointTimeout] rather than a bare duration, for the reason
     * [ExecRequest.timeout] is an [ExecTimeout]: an implementation turns it straight
     * into the transport's deadline, so an unbounded value here is a reconcile
     * worker with no effective timeout — on the *drain's* control path, where a
     * parked worker is a backend that cannot be sealed.
     */
    val timeout: EndpointTimeout,
) {
    init {
        require(port in 1..65535) { "port must be in 1..65535, got: $port" }
        require(path.startsWith("/")) { "path must be absolute, got: $path" }
        // Finiteness is structural — [EndpointTimeout.of] caps it — so what is left
        // is the half a ceiling must not fix. See the note above for why a zero is
        // refused rather than raised, and where the refusal is turned into something
        // an operator can read.
        require(timeout.period.isPositive()) { "endpoint timeout must be positive, found ${timeout.period}" }
    }
}

/**
 * How long a request to a port inside a workload may take, bounded by
 * [EndpointTimeoutCeiling].
 *
 * The third of the three durations that become transport deadlines, and the last to
 * get its type. The note left on it in round 30 — *"every construction site is a
 * compile-time constant"* — was a survey of call sites, and it was false:
 * `ControlChannel`'s one site is handed `spec.backends.drain.sealTimeout` off a
 * `VelocityProxy` at both of `ControlChannel`'s own construction sites. `SpecBounds`
 * now caps that field where a stored row is decoded, which closes the row-level half
 * for every definition that came through a store; this closes the **type** half, for
 * a fixture, a hand-built spec, or a caller a store never saw. Two bounds, and the
 * one here is the one no future caller can route around.
 *
 * ## The licence to cap is derived for *this* call, not read off [ExecTimeout]
 *
 * [ExecTimeout]'s KDoc caps on the argument that cutting a wait short can do no more
 * than withhold a confirmation — and that sentence is **false of a probe**, whose
 * silence `DrainController.save` reads as consent. So it is re-derived here rather
 * than inherited: what does this orchestrator conclude from a control call that ran
 * out of time?
 *
 * Nothing that lets a container go. Every unanswered control call becomes
 * `ControlOutcome.Unavailable`, and every consumer of one either parks the drain
 * (`holdSeal`, `transfer`, `deregister`, `reregister`) or discards the answer
 * (`BackendLink.observedPlayers`, which is *"corroboration only, and never a gate"*
 * and returns null). No branch reads an unanswered endpoint as *nobody is
 * connected*, as *the backend is sealed* or as *the registration is gone* — which is
 * what makes shortening this wait safe in the direction that matters: it can only
 * park a drain, never advance one.
 *
 * **The day a branch reads the absence of an answer as an answer, this argument
 * expires** and the cap has to be re-derived — the same way the probe expired
 * [ExecTimeout]'s.
 */
@JvmInline
public value class EndpointTimeout private constructor(
    /** What the call is actually given. Never above [EndpointTimeoutCeiling.MAX]. */
    public val period: Duration,
) {
    override fun toString(): String = period.toString()

    public companion object {
        /** The only way to obtain an [EndpointTimeout]. Total: it caps rather than refusing. */
        public fun of(requested: Duration): EndpointTimeout = EndpointTimeout(EndpointTimeoutCeiling.bound(requested))
    }
}

/**
 * The longest a [Node] will wait for a port inside a workload to answer.
 *
 * Borrowed rather than restated, for the reason [StopGraceCeiling.MAX] and
 * [ExecTimeoutCeiling.MAX] are: [VelocityProxyDefaults.MAX_TIMEOUT] is what
 * `VelocityProxyReader.handshakeTimeout` caps all three of the proxy's drain
 * timeouts at — and what `mcorch.schema.SpecBounds.MAX_HANDSHAKE_TIMEOUT` borrows
 * for the same field at the decode — so no definition an operator could legitimately
 * write is shortened by a single second, and raising the reader's cap moves this
 * with it.
 *
 * It is the *proxy's* constant rather than `PaperServerDefaults`', although the two
 * are the same hour today, because the only definition-fed value that reaches an
 * endpoint call is a proxy's. If a second protocol ever speaks through
 * [Node.callEndpoint] with a timeout off some other kind's spec, the honest change
 * is to say which reader bounds it — not to keep quoting this one.
 */
public object EndpointTimeoutCeiling {
    /** One hour, borrowed from the widest cap `VelocityProxyReader` applies to a handshake timeout. */
    public val MAX: Duration = VelocityProxyDefaults.MAX_TIMEOUT

    /** [requested], or [MAX] if that is lower. Non-finite is capped; see [EndpointTimeout]. */
    public fun bound(requested: Duration): Duration = if (requested > MAX) MAX else requested
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

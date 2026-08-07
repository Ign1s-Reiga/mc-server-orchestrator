package mcorch.cri

import mcorch.cri.internal.GrpcCriClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A thin, stateless wrapper over containerd's CRI gRPC API.
 *
 * ## What this is and is not
 *
 * It executes CRI operations and reports what containerd says. It holds no
 * desired state, makes no policy decisions, retries nothing, and never judges
 * readiness. Reconcile decisions live in `:core`; only the single-host `Node`
 * implementation is allowed to hold one of these.
 *
 * ## Failure
 *
 * Every function here throws [CriException] and nothing else from this module.
 * Branch on [CriException.retryable]: true means requeue with backoff, false
 * means surface on the server's observed status. No `io.grpc` type escapes.
 *
 * ## Timeouts and cancellation
 *
 * Every call carries a gRPC deadline from [CriTimeouts]; there is no way to make
 * an unbounded call. A deadline that elapses raises [CriException.Timeout],
 * which is retryable. Cancelling the calling coroutine cancels the RPC and
 * raises `CancellationException` unchanged — it is never converted into a
 * [CriException].
 *
 * ## Lifecycle ordering
 *
 * Bring up: pull the image, [runSandbox], [createContainer], [startContainer].
 * Tear down in reverse: [stopContainer] (after a completed drain),
 * [removeContainer], [stopSandbox], [removeSandbox]. A sandbox must exist before
 * a container can be created in it, and every container must be gone before the
 * sandbox is removed — [stopSandbox] and [removeSandbox] will forcibly kill any
 * container still inside, with no grace period and no save.
 *
 * ## Idempotency
 *
 * `stopSandbox`, `removeSandbox`, `stopContainer` and `removeContainer` are
 * specified idempotent by CRI and do not fail when the work is already done.
 * `runSandbox` and `createContainer` are not: a second create with the same
 * identity raises [CriException.AlreadyExists]. An idempotent reconcile pass
 * lists by label first and adopts what it finds.
 *
 * Implementations are safe for concurrent use from multiple coroutines.
 */
public interface CriClient : AutoCloseable {
    // ── runtime health ───────────────────────────────────────────────────────

    /**
     * Which containerd is on the other end.
     *
     * Cheapest proof that the socket is there and the CRI plugin is serving.
     *
     * @throws CriException on any failure; [CriException.Unavailable] when
     *   containerd is not up yet.
     */
    public suspend fun version(): RuntimeVersion

    /**
     * containerd's self-report. Use it as the startup health check — see
     * [RuntimeStatus.runtimeReady] and [RuntimeStatus.networkReady].
     *
     * @throws CriException on any failure.
     */
    public suspend fun status(): RuntimeStatus

    // ── images ───────────────────────────────────────────────────────────────

    /**
     * Whether [image] is already on the node, and what containerd resolved it
     * to. Returns `null` when it is absent — CRI reports absence as an empty
     * response, not as an error, so absence is not a failure here either.
     *
     * This is what keeps reconcile from re-pulling on every pass: check first,
     * pull only on `null`.
     *
     * @throws CriException on any failure.
     */
    public suspend fun imageStatus(image: ImageName): ImageInfo?

    /**
     * Images on the node, optionally narrowed to those matching [image].
     *
     * @throws CriException on any failure.
     */
    public suspend fun listImages(image: ImageName? = null): List<ImageInfo>

    /**
     * Pulls [image] and returns what containerd resolved it to.
     *
     * Separate from [createContainer] on purpose: a pull failure and a create
     * failure are different things to report. Failures from here carry
     * [CriOperation.PULL_IMAGE].
     *
     * Long-running. It carries [CriTimeouts.imagePull] as its deadline and is
     * cancellable throughout. There is no progress callback — CRI's `PullImage`
     * is a unary RPC that reports nothing until it finishes.
     *
     * @param sandbox when set, the pull happens in that sandbox's context, which
     *   is how per-sandbox registry configuration applies.
     * @throws CriException.NotFound when the image does not exist in the
     *   registry (permanent — a bad tag is not fixed by retrying).
     * @throws CriException.RuntimeFailure when containerd could not classify the
     *   failure; retryable, and where a registry outage usually lands.
     * @throws CriException on any other failure.
     */
    public suspend fun pullImage(
        image: ImageName,
        auth: RegistryAuth? = null,
        sandbox: SandboxSpec? = null,
    ): ImageId

    /**
     * Removes [image]. Idempotent per CRI.
     *
     * Removes every tag that resolves to the same digest, not just the one
     * named.
     *
     * @throws CriException on any failure.
     */
    public suspend fun removeImage(image: ImageName)

    // ── sandboxes ────────────────────────────────────────────────────────────

    /**
     * Creates and starts a pod sandbox. On success the sandbox is ready.
     *
     * Keep [spec] — CRI requires the identical value again in
     * [createContainer].
     *
     * @throws CriException.AlreadyExists if a sandbox with this identity exists.
     * @throws CriException on any other failure. A CNI that is not configured
     *   usually surfaces here rather than at connect time.
     */
    public suspend fun runSandbox(spec: SandboxSpec): SandboxId

    /**
     * Stops the sandbox and reclaims its network resources. Idempotent.
     *
     * **Forcibly terminates every container still inside, with no grace period
     * and no save.** Containers with world data must have been drained and
     * stopped before this is called.
     *
     * @throws CriException on any failure.
     */
    public suspend fun stopSandbox(id: SandboxId)

    /**
     * Removes the sandbox. Idempotent; does not fail when it is already gone.
     *
     * **Forcibly terminates and removes any container still inside.** Same
     * warning as [stopSandbox].
     *
     * @throws CriException on any failure.
     */
    public suspend fun removeSandbox(id: SandboxId)

    /**
     * Status of one sandbox, including the statuses of its containers.
     *
     * @throws CriException.NotFound when the sandbox does not exist. Callers
     *   that treat absence as normal should prefer [listSandboxes].
     * @throws CriException on any other failure.
     */
    public suspend fun sandboxStatus(id: SandboxId): SandboxStatus

    /**
     * Sandboxes matching [filter]. Returns an empty list rather than failing
     * when nothing matches, which makes it the safe discovery call for a
     * reconcile pass.
     *
     * @throws CriException on any failure.
     */
    public suspend fun listSandboxes(filter: SandboxFilter = SandboxFilter.ALL): List<SandboxSummary>

    // ── containers ───────────────────────────────────────────────────────────

    /**
     * Creates a container inside an existing sandbox. Does not start it, and
     * does not pull the image.
     *
     * @param sandboxId from [runSandbox].
     * @param sandboxSpec **the same value passed to [runSandbox]**. CRI requires
     *   the sandbox config to be handed back here; it is immutable for the
     *   sandbox's lifetime, and a reconstructed near-copy is a source of subtle
     *   misbehaviour.
     * @throws CriException.AlreadyExists when a container with this name and
     *   attempt already exists in the sandbox.
     * @throws CriException.NotFound when the sandbox or the image is missing —
     *   note this is a create failure ([CriOperation.CREATE_CONTAINER]), not a
     *   pull failure.
     * @throws CriException on any other failure.
     */
    public suspend fun createContainer(
        sandboxId: SandboxId,
        sandboxSpec: SandboxSpec,
        spec: ContainerSpec,
    ): ContainerId

    /**
     * Starts a created container. Returning means containerd started the
     * process, not that the server is up.
     *
     * @throws CriException on any failure.
     */
    public suspend fun startContainer(id: ContainerId)

    /**
     * Stops a running container, killing it after [gracePeriod]. Idempotent —
     * does not fail if it has already stopped.
     *
     * **Never call this on a server with players online.** The drain protocol
     * (`.claude/skills/drain-protocol/`) transfers players, saves the world and
     * confirms the save *before* this call. [gracePeriod] is the safety net for
     * a container that reaches here anyway; it must exceed the maximum expected
     * save duration.
     *
     * There is no default: see [StopGracePeriod].
     *
     * ## The deadline is not the grace period
     *
     * The transport deadline is `min([gracePeriod], `[CriTimeouts.stopDeadlineCap]`)`
     * plus [CriTimeouts.deadlineSlack]. Below the cap — where every grace period
     * a server definition can legitimately carry sits — that is
     * `gracePeriod + slack` and the kill fires before the RPC gives up, as
     * before. Above it the call gives up first, with a retryable
     * [CriException.Timeout] that says so, so that a very long grace period
     * cannot park a caller indefinitely on a call with no effective deadline.
     *
     * **What containerd is asked to wait is always the whole [gracePeriod].**
     * The cap shortens the deadline and never the grace period, so this call can
     * only ever leave a container running longer, never kill it sooner.
     *
     * A timeout therefore does not mean the stop failed: the container has the
     * stop signal, and containerd will not escalate to a kill for a call that has
     * already given up. It does mean the kill at the end of the grace period will
     * not happen for *this* call — re-issue the stop, which is idempotent, or read
     * the container's state.
     *
     * **A re-issue does not deliver the stop signal a second time**, so one
     * carrying the same grace period ends exactly as this one did, however many
     * times it is made. What a re-issue supplies is a fresh grace period on a
     * fresh context, and the kill is reached only when that grace period is what
     * expires first — which is why a re-issue inside the cap does finish the
     * container: its own deadline then outlasts it. The runtime behaviour this
     * rests on, and the measurements behind it, are on
     * [CriTimeouts.stopDeadlineCap].
     *
     * @throws CriException on any failure.
     */
    public suspend fun stopContainer(
        id: ContainerId,
        gracePeriod: StopGracePeriod,
    )

    /**
     * Removes a container. Idempotent.
     *
     * **Forcibly removes it if it is still running**, with no grace period and
     * no save. Stop it properly first. Persistent mounts survive this.
     *
     * @throws CriException on any failure.
     */
    public suspend fun removeContainer(id: ContainerId)

    /**
     * Status of one container, as containerd sees it. Not a readiness verdict.
     *
     * @throws CriException.NotFound when the container does not exist. Callers
     *   that treat absence as normal should prefer [listContainers].
     * @throws CriException on any other failure.
     */
    public suspend fun containerStatus(id: ContainerId): ContainerStatus

    /**
     * Containers matching [filter]. Empty list rather than a failure when
     * nothing matches.
     *
     * @throws CriException on any failure.
     */
    public suspend fun listContainers(filter: ContainerFilter = ContainerFilter.ALL): List<ContainerSummary>

    // ── exec ─────────────────────────────────────────────────────────────────

    /**
     * Runs [command] inside a running container and waits for it to finish.
     *
     * This is the mechanism behind the drain protocol's "save the world and wait
     * for the completion notification": issue the save command, and treat the
     * returned [ExecResult] as the confirmation. Returning means the command
     * *exited* — check [ExecResult.exitCode] and the captured output before
     * concluding the save actually completed. A command that exits zero having
     * printed an error is still a failed save.
     *
     * @param timeout how long containerd lets the command run before stopping
     *   it. Required, and must be positive and finite: CRI treats `0` as "run
     *   forever", which would let a stuck save pin the reconcile loop's requeue
     *   on a call that never returns, and an infinite one takes this call's own
     *   deadline away while leaving it looking deadlined. Size it above the
     *   maximum expected save duration and below the stop grace period. On
     *   timeout the drain aborts and the container stays running.
     * @throws CriException.Timeout when the command outran [timeout], or the
     *   transport deadline elapsed.
     * @throws CriException.NotFound when the container does not exist.
     * @throws CriException on any other failure.
     */
    public suspend fun execSync(
        id: ContainerId,
        command: List<String>,
        timeout: Duration,
    ): ExecResult

    /**
     * Prepares a streaming exec session and returns the URL of containerd's
     * streaming server.
     *
     * The URL is a SPDY/websocket endpoint that this module does not consume;
     * it exists for an interactive console feature that speaks it directly. For
     * anything the reconcile loop or the drain protocol needs — including the
     * world save and reading a player count — use [execSync].
     *
     * @throws CriException on any failure.
     */
    public suspend fun execStreamUrl(
        id: ContainerId,
        command: List<String>,
        streams: ExecStreams = ExecStreams.OUTPUT_ONLY,
    ): String

    // ── lifecycle ────────────────────────────────────────────────────────────

    /**
     * Closes the connection, letting in-flight calls finish within
     * [gracePeriod]. Calls started after this fail with
     * [CriException.Unavailable]. Safe to call more than once.
     */
    public suspend fun shutdown(gracePeriod: Duration = DEFAULT_SHUTDOWN_GRACE)

    /**
     * Closes the connection immediately, cancelling anything in flight. Blocks
     * briefly. For `use { }` blocks and JVM shutdown hooks; prefer [shutdown]
     * from a coroutine.
     */
    override fun close()

    public companion object {
        /** Grace given to in-flight calls by [shutdown]. */
        public val DEFAULT_SHUTDOWN_GRACE: Duration = 5.seconds

        /**
         * Opens a connection. Does not connect eagerly — the first call is what
         * discovers containerd is down, and it fails with
         * [CriException.Unavailable].
         *
         * @throws IllegalStateException if the endpoint is a Unix socket and the
         *   native epoll transport is unavailable, which no retry will fix.
         */
        public fun connect(config: CriClientConfig): CriClient = GrpcCriClient.connect(config)
    }
}

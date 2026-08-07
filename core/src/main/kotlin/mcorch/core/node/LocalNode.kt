package mcorch.core.node

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mcorch.core.EndpointRequest
import mcorch.core.EndpointResponse
import mcorch.core.ExecOutcome
import mcorch.core.ExecRequest
import mcorch.core.ImageAvailability
import mcorch.core.Labels
import mcorch.core.Node
import mcorch.core.NodeCapacity
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.NodeStatus
import mcorch.core.StopGrace
import mcorch.core.StopGraceCeiling
import mcorch.core.StorageRequest
import mcorch.core.WorkloadHandle
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadRemoval
import mcorch.core.WorkloadSpec
import mcorch.core.WorkloadState
import mcorch.cri.ContainerFilter
import mcorch.cri.ContainerId
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerState
import mcorch.cri.ContainerStatus
import mcorch.cri.ContainerSummary
import mcorch.cri.CriClient
import mcorch.cri.CriClientConfig
import mcorch.cri.CriEndpoint
import mcorch.cri.CriException
import mcorch.cri.ImageName
import mcorch.cri.LinuxContainerSpec
import mcorch.cri.LinuxResources
import mcorch.cri.LinuxSandboxSpec
import mcorch.cri.PortMapping
import mcorch.cri.SandboxFilter
import mcorch.cri.SandboxId
import mcorch.cri.SandboxSpec
import mcorch.cri.SandboxStatus
import mcorch.cri.StopGracePeriod
import mcorch.cri.VolumeMount
import mcorch.schema.ImageRef
import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.SpecBounds
import mcorch.store.SecretStore
import mcorch.store.StoreException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The [Node] that runs containers through a containerd on one host.
 *
 * **This is the only place in the orchestrator that may name a `mcorch.cri`
 * type** (CLAUDE.md invariant 7). `:core` depends on `:cri` as an
 * `implementation` dependency precisely so that nothing downstream can reach
 * around this class, and every CRI failure is translated into a
 * [NodeException] here so that no caller can pattern-match on a transport
 * detail either.
 *
 * ## What it holds together
 *
 * CRI's model is a sandbox with containers inside it, with two sharp edges:
 *
 * - `RunPodSandbox` and `CreateContainer` are **not** idempotent — a repeat
 *   raises `AlreadyExists` — so every create here lists by label and adopts
 *   first.
 * - `StopPodSandbox` and `RemovePodSandbox` **forcibly kill every container
 *   still inside, with no grace period and no save**. So the container is
 *   stopped and removed before the sandbox is touched, always, and
 *   [removeWorkload] refuses outright if it finds one still running.
 *
 * ## Persistent storage
 *
 * A [StorageRequest.Persistent] volume becomes a host directory under
 * [volumeRoot], created if it is not there and **never removed by this class**.
 * That is what makes the world survive the container (CLAUDE.md invariant 2).
 *
 * ## Assets
 *
 * [mcorch.core.AssetMount] is where an artefact this orchestrator ships becomes
 * a path: **this class is the only thing that knows one**, and it knows it
 * because [LocalNodeConfig.assetRoot] told it. A caller asks for
 * [mcorch.core.WorkloadAsset.VELOCITY_CONTROL_PLUGIN] and never learns where the
 * file is; a distributed node would answer the same request out of its own
 * store.
 */
public class LocalNode internal constructor(
    override val name: NodeName,
    private val client: CriClient,
    private val secrets: SecretStore,
    private val volumeRoot: Path,
    private val logRoot: Path,
    private val assetRoot: Path,
    private val sandboxNamespace: String,
    private val cgroupParent: String?,
) : Node,
    AutoCloseable {
    /**
     * One client for this node's lifetime, for [callEndpoint].
     *
     * Redirects are **never** followed: a control plane that can seal every
     * backend in a fleet is not something to chase a `Location` header for, and
     * the plugin never sends one. HTTP/1.1 rather than the default negotiation,
     * because the plugin serves a `com.sun.net.httpserver` socket and an upgrade
     * attempt against it is a round trip that can only fail.
     */
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build()

    // ── health ───────────────────────────────────────────────────────────────

    override suspend fun status(): NodeStatus =
        translating(NodeOperation.STATUS) {
            val status = client.status()
            NodeStatus(
                ready = status.runtimeReady && status.networkReady,
                detail =
                    if (status.runtimeReady && status.networkReady) {
                        "containerd is ready"
                    } else {
                        status.conditions
                            .filterNot { it.status }
                            .joinToString("; ") { "${it.type}: ${it.reason}" }
                            .ifEmpty { "the runtime did not report itself ready" }
                    },
                // Nothing on a single host accounts for allocatable capacity,
                // and reporting a guess as a fact would make a real scheduler
                // place on fiction.
                capacity = NodeCapacity(),
            )
        }

    // ── observation ──────────────────────────────────────────────────────────

    override suspend fun observe(server: ResourceName): WorkloadObservation =
        translating(NodeOperation.OBSERVE) {
            val sandbox = findSandbox(server) ?: return@translating WorkloadObservation.Absent
            val status =
                try {
                    client.sandboxStatus(sandbox)
                } catch (gone: CriException.NotFound) {
                    LOG.debug("sandbox {} disappeared between the list and the status read", sandbox, gone)
                    return@translating WorkloadObservation.Absent
                }
            observationOf(server, status)
        }

    private suspend fun observationOf(
        server: ResourceName,
        status: SandboxStatus,
    ): WorkloadObservation.Present =
        WorkloadView.observe(
            node = name,
            server = server,
            sandboxId = status.id.value,
            sandboxLabels = status.labels,
            sandboxCreatedAt = status.createdAt,
            containers = containersIn(status.id, server, status.containerStatuses.map { it.toDetail() }),
        )

    /**
     * Every container in the sandbox, enumerated by `ListContainers`.
     *
     * **Not** from `PodSandboxStatusResponse.containers_statuses.** That field
     * is optional and runtime-version-dependent — it was added for evented PLEG
     * and no runtime is obliged to fill it — so an empty one cannot be told
     * apart from an empty sandbox. Believing it costs a running Paper server:
     * the workload reads as [WorkloadState.SANDBOX_ONLY], which a drain treats
     * as "nothing is running, nothing to save", and the teardown guard reads
     * the same empty list and sees nobody to protect. `ListContainers` is a
     * mandatory CRI call, so it is the one asked.
     *
     * The optional field is still used, for what it is good for: the per-status
     * detail a summary does not carry — start and finish times, exit code — for
     * the containers belonging to this server. When it is absent, that detail
     * is fetched for those containers only, which is at most one round trip and
     * only on a runtime that does not populate it. `startedAt` in particular is
     * load-bearing: the drain's save evidence is measured against it.
     */
    private suspend fun containersIn(
        sandbox: SandboxId,
        server: ResourceName?,
        reported: List<ContainerDetail>,
    ): List<ContainerView> {
        // The enumeration decides who exists. The overlay only decorates, and
        // the two are different types so they cannot be passed the wrong way
        // round.
        val listed = client.listContainers(ContainerFilter.inSandbox(sandbox)).map { it.toView() }
        val merged = WorkloadView.merge(listed, reported)
        if (server == null) return merged
        // Anything of this server's that the overlay did not describe: fetch the
        // detail the enumeration cannot carry. At most one round trip, and only
        // on a runtime that leaves the optional field empty.
        return merged.map { view ->
            if (view.labels[Labels.SERVER] == server.value && view.startedAt == null) {
                statusOrNull(ContainerId(view.id))?.toView() ?: view
            } else {
                view
            }
        }
    }

    private suspend fun statusOrNull(id: ContainerId): ContainerStatus? =
        try {
            client.containerStatus(id)
        } catch (gone: CriException.NotFound) {
            LOG.debug("container {} disappeared between the list and the status read", id, gone)
            null
        }

    /**
     * Finds this server's sandbox by label.
     *
     * By label rather than by a remembered ID: the loop's memory does not
     * survive a restart and the label does. More than one is a situation that
     * should not arise; the newest is adopted and the rest are reported loudly
     * rather than silently cleaned up, because "clean up the container I do not
     * recognise" is not a decision to make automatically.
     */
    private suspend fun findSandbox(server: ResourceName): SandboxId? {
        val found = client.listSandboxes(SandboxFilter.byLabels(Labels.selectorFor(server)))
        if (found.size > 1) {
            LOG.warn(
                "server={} has {} sandboxes on node={}; adopting the newest and leaving the rest alone",
                server,
                found.size,
                name,
            )
        }
        return found.maxByOrNull { it.createdAt }?.id
    }

    // ── images ───────────────────────────────────────────────────────────────

    override suspend fun ensureImage(image: ImageRef): ImageAvailability =
        translating(NodeOperation.IMAGE) {
            val reference = ImageName(image.canonical)
            // The anti-re-pull check. CRI reports an absent image as an empty
            // response rather than an error, so this is a question, not a
            // gamble.
            val present = client.imageStatus(reference)
            if (present != null) {
                return@translating ImageAvailability(image = image, id = present.id.value, pulled = false)
            }
            val pulled = client.pullImage(reference)
            ImageAvailability(image = image, id = pulled.value, pulled = true)
        }

    // ── workload lifecycle ───────────────────────────────────────────────────

    /**
     * The create's own container derivation, run and thrown away.
     *
     * Deliberately [containerSpecFor] rather than a list of the things it checks:
     * a re-implementation here would be a second enforcement point, which is the
     * thing the twenty-fourth audit asked not to add while asking for the question
     * to be asked earlier. So the promise is exact and it is also **bounded**, and
     * the bound is written here rather than left to be discovered:
     *
     * > Every refusal [containerSpecFor] can produce, this call produces too. The
     * > steps of [ensureWorkload] that are *not* that derivation are outside it.
     *
     * ## What is outside, named
     *
     * [prepareHostPaths] — `HostPaths.prepare`, which creates the log directory and,
     * for persistent storage, the volume directory. It throws
     * [NodeException.Rejected] (permanent) on an `IOException` or a
     * `SecurityException`: a volume or log root that has been unmounted, remounted
     * read-only, or had its ownership changed. A replacement can therefore still
     * pass this call, drain, save, stop, remove, and meet a create that refuses.
     *
     * The twenty-sixth audit's third warning, and the reason it is a narrowed
     * sentence rather than a new check: whether `createDirectories` will succeed
     * cannot be established without attempting it, and an approximation — "are the
     * two roots writable" — is a *different* derivation that can refuse a create the
     * real one would accept, which would freeze every replacement on a host whose
     * roots are read-only but whose per-workload directories already exist. That is
     * the second enforcement point this call exists not to be. Closing it properly
     * means letting a pre-flight create the directories it is checking, which
     * [Node.checkWorkload] forbids on purpose; the day that trade looks right, it is
     * an interface change and not a quiet one here.
     *
     * `sandboxSpecFor` is also outside and needs no argument: it is field copying
     * and cannot refuse anything.
     *
     * ## It used to be [mountsFor], and that was one of two
     *
     * The twenty-fifth audit's second warning. `containerSpecFor` can refuse a
     * workload in two ways — a mount it cannot build, and a secret reference that
     * resolves to nothing — and this ran only the first. So an operator who
     * repointed `spec.control.tokenSecret` (or `forwarding.secret`) at a secret not
     * yet staged moved the spec hash, passed the pre-flight, and had the running
     * proxy sealed, drained to zero, stopped and removed before the create asked
     * the question that refuses permanently. Calling the *whole* derivation is what
     * makes the sentence above true, and it is what makes a third refusal added to
     * `containerSpecFor` tomorrow pre-flighted without anybody remembering to come
     * back here.
     *
     * ## Presence, not material
     *
     * [SecretAccess.PRESENCE_ONLY] asks the secret store whether each reference
     * resolves and never materialises a value. `SecretStore.resolve` is documented
     * as a use-time call — "do not resolve early and carry the result around" — and
     * a pre-flight has nothing to hand the material to, so materialising here would
     * create copies of forwarding secrets for no purpose (CLAUDE.md invariant 4).
     * The two arms refuse the same condition with the same message; the difference
     * between them is a value nobody reads, and a reference that stops resolving
     * between this call and the create is refused by the create exactly as before.
     *
     * One consequence, since "the same derivation" is doing work above: the arms
     * differ in the *shape* of what they build, not only in the value. `PRESENCE_ONLY`
     * contributes no entries, so the [ContainerSpec] built here has no secret
     * environment variables in it and `ContainerSpec`'s own `require` on blank
     * environment keys is blind to a blank `secretEnv` key. Unreachable today — every
     * such key is a compile-time constant in a planner — and if it ever were not, the
     * `IllegalArgumentException` would reach the create as a permanent `Rejected`
     * *after* a teardown. It is a second entry on the bounded list above rather than
     * a defect to fix now.
     *
     * `translating` for the same reason every other entry point uses it: a caller
     * of [Node] sees nothing but a [NodeException].
     */
    override suspend fun checkWorkload(spec: WorkloadSpec) {
        translating(NodeOperation.CREATE) {
            containerSpecFor(spec, SecretAccess.PRESENCE_ONLY)
        }
    }

    override suspend fun ensureWorkload(spec: WorkloadSpec): WorkloadObservation.Present =
        translating(NodeOperation.CREATE) {
            val sandboxSpec = sandboxSpecFor(spec)
            val existing = findSandbox(spec.server)

            val sandboxId =
                if (existing != null) {
                    val status = client.sandboxStatus(existing)
                    val adopted = observationOf(spec.server, status)
                    // `hadContainer` is the reconcile loop's fact and there is
                    // none of it at this layer: it is a container id *the loop*
                    // recorded, and a node is the thing being asked rather than
                    // the thing that remembers. What this reads is the sandbox
                    // status fetched on the line above, in this same call, so
                    // "no container in it" is this call's own observation rather
                    // than a claim about history — and both answers build the
                    // container the caller asked for. Nothing here ends one.
                    if (adopted.state != WorkloadState.SANDBOX_ONLY) {
                        // Already built. Adopting it is the whole reason a
                        // second pass is not a second container.
                        return@translating adopted
                    }
                    if (adopted.specHash != null && adopted.specHash != spec.specHash) {
                        // The sandbox was created from a different definition.
                        // CRI requires the *same* sandbox config to create a
                        // container into it, and the caller has to drain and
                        // replace rather than graft a new container onto an old
                        // sandbox.
                        return@translating adopted
                    }
                    existing
                } else {
                    prepareHostPaths(spec)
                    try {
                        client.runSandbox(sandboxSpec)
                    } catch (collision: CriException) {
                        // Something created it between the list and the create.
                        // Adopt it here rather than reporting "busy, try again":
                        // if the second look cannot find it either, the object
                        // exists under a name this build cannot see, and every
                        // future pass would repeat the same find-create-collide
                        // cycle for ever without anyone being asked to look.
                        if (!collision.isNameCollision()) throw collision
                        adoptAfterCollision(spec.server, collision)
                    }
                }

            val containerSpec = containerSpecFor(spec)
            // The same `sandboxSpec` value that created the sandbox — CRI
            // requires it back here, and a reconstructed near-copy misbehaves.
            // It is derived from the workload spec alone, so an adopted sandbox
            // rebuilds an identical one.
            val containerId =
                try {
                    client.createContainer(sandboxId, sandboxSpec, containerSpec)
                } catch (collision: CriException) {
                    if (!collision.isNameCollision()) throw collision
                    val adopted = observationOf(spec.server, client.sandboxStatus(sandboxId))
                    // The same reading as the adoption above, and `hadContainer`
                    // is as absent here for the same reason. This asks only
                    // whether the collision left something to adopt: the sandbox
                    // was just re-read, a container that is there is the one the
                    // create raced with, and one that is not means the object
                    // exists under a name this build cannot see. Refusing is the
                    // whole of the answer — nothing is stopped or removed on it.
                    if (adopted.state == WorkloadState.SANDBOX_ONLY) {
                        throw unadoptable(NodeOperation.CREATE, "container for `${spec.server}`", collision)
                    }
                    return@translating adopted
                }
            WorkloadObservation.Present(
                handle = WorkloadHandle(name, sandboxId.value, containerId.value),
                state = WorkloadState.CREATED,
                specHash = spec.specHash,
                labels = spec.labels + (Labels.SPEC_HASH to spec.specHash),
                createdAt = null,
            )
        }

    /**
     * Re-reads after a create lost a race, and refuses rather than looping when
     * the object still cannot be found.
     */
    private suspend fun adoptAfterCollision(
        server: ResourceName,
        cause: CriException,
    ): SandboxId = findSandbox(server) ?: throw unadoptable(NodeOperation.CREATE, "sandbox for `$server`", cause)

    /**
     * Whether a failed create means "that name is already taken".
     *
     * Two status codes, because containerd does not use the obvious one.
     * containerd 2.3.3's CRI plugin rejects a duplicate at *name reservation*,
     * and a reservation conflict is `FAILED_PRECONDITION` — verified against a
     * real runtime, where `RunPodSandbox` and `CreateContainer` both answer
     * `failed to reserve ... name "..." is reserved for "<id>"`. Matching only
     * `ALREADY_EXISTS` made the adoption below dead code, so a create that lost
     * a race surfaced as a permanent rejection instead of adopting what is
     * already there.
     *
     * The narrowing is done here rather than in `:cri`'s code mapping, which is
     * right as it stands: `FAILED_PRECONDITION` legitimately covers other
     * things, and only a caller that has just attempted a create knows a
     * precondition failure is a name collision. The *message* carries the
     * colliding ID, and this deliberately does not parse it out — listing by
     * label finds the object whether or not containerd words it that way
     * tomorrow.
     */
    private fun CriException.isNameCollision(): Boolean =
        this is CriException.AlreadyExists || this is CriException.FailedPrecondition

    private fun unadoptable(
        operation: NodeOperation,
        what: String,
        cause: CriException,
    ): NodeException =
        NodeException.Rejected(
            name,
            operation,
            "the runtime says the $what already exists, and listing by label does not find it. Something on " +
                "this node holds the name without carrying this orchestrator's labels; retrying cannot resolve " +
                "that. Find it with `ctr`/`crictl` and either label it or remove it: ${cause.message}",
            cause,
        )

    override suspend fun startWorkload(handle: WorkloadHandle) {
        val containerId = handle.requireContainer(NodeOperation.START)
        translating(NodeOperation.START) {
            val status = client.containerStatus(containerId)
            if (status.state == ContainerState.RUNNING) {
                LOG.debug("container {} is already running", containerId)
                return@translating
            }
            client.startContainer(containerId)
        }
    }

    override suspend fun exec(
        handle: WorkloadHandle,
        request: ExecRequest,
    ): ExecOutcome {
        val containerId = handle.requireContainer(NodeOperation.EXEC)
        return translating(NodeOperation.EXEC) {
            // Already bounded: [ExecTimeout] is the only thing [ExecRequest] accepts
            // and its factory applies [ExecTimeoutCeiling]. This is the value
            // `GrpcCriClient.execSync` turns into the call's gRPC deadline, which is
            // why the bound is on the type rather than asked for here.
            val result = client.execSync(containerId, request.command, request.timeout.period)
            ExecOutcome(exitCode = result.exitCode, stdout = result.stdout, stderr = result.stderr)
        }
    }

    /**
     * Reaches a port inside the sandbox over HTTP.
     *
     * ## The one place "inside the sandbox" is a routable address
     *
     * A remote node would forward this to its own agent and never expose an
     * address at all; on this host the sandbox has a CNI address and the
     * orchestrator process can open a socket to it directly. That difference is
     * the whole reason [Node.callEndpoint] exists as an interface method — a
     * caller that resolved an address itself would have hard-coded the single-host
     * deployment (CLAUDE.md invariant 7).
     *
     * The address is read fresh on every call rather than cached on the handle. A
     * sandbox that is recreated gets a new one, and a control request aimed at the
     * previous occupant of an address is a request that seals somebody else's
     * backend.
     *
     * ## What is not logged
     *
     * The address, ever. `SandboxStatus` redacts `ips` from its own `toString`
     * for the same reason, and CLAUDE.md forbids addresses in log lines. Failures
     * name the port and the path, which are declared configuration.
     */
    override suspend fun callEndpoint(
        handle: WorkloadHandle,
        request: EndpointRequest,
    ): EndpointResponse {
        val sandboxId = SandboxId(handle.sandboxId)
        return translating(NodeOperation.ENDPOINT) {
            val status = client.sandboxStatus(sandboxId)
            val address =
                status.ips.firstOrNull()
                    ?: throw NodeException.Busy(
                        name,
                        NodeOperation.ENDPOINT,
                        "the runtime reports no address for sandbox ${handle.sandboxId} yet, so port " +
                            "${request.port} cannot be reached. A sandbox gets one when its network is " +
                            "attached, so this is a wait rather than a misconfiguration",
                    )
            // Coordinates in, material out, and the material never leaves this
            // function: `Authorization` is built here and the header map is
            // discarded with the request.
            val token = request.bearerToken?.let { resolveToken(it) }
            send(address, request, token)
        }
    }

    private suspend fun resolveToken(ref: SecretRef): String {
        val secret =
            secrets.resolve(ref) ?: throw NodeException.Rejected(
                name,
                NodeOperation.ENDPOINT,
                "the control-endpoint token `${ref.name}/${ref.key}` is not in the secret store",
            )
        return try {
            secret.use { material -> String(material) }
        } finally {
            secret.destroy()
        }
    }

    /**
     * The blocking half, on the IO dispatcher.
     *
     * `HttpClient.send` blocks a thread, so it cannot run on the loop's
     * dispatcher — and the timeout is set on the request rather than relied on
     * from cancellation, because a blocking call is not interruptible by a
     * cancelled coroutine. Both halves are needed: the request timeout bounds the
     * wait, and [translating] turns whatever comes out of it into a
     * [NodeException].
     */
    private suspend fun send(
        address: String,
        request: EndpointRequest,
        token: String?,
    ): EndpointResponse =
        withContext(Dispatchers.IO) {
            val host = if (address.contains(':')) "[$address]" else address
            val builder =
                HttpRequest
                    .newBuilder(URI.create("http://$host:${request.port}${request.path}"))
                    .timeout(request.timeout.period.toJavaDuration())
            val publisher =
                request.body?.let { HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8) }
                    ?: HttpRequest.BodyPublishers.noBody()
            builder.method(request.verb.name, publisher)
            request.contentType?.let { builder.header("Content-Type", it) }
            token?.let { builder.header("Authorization", "Bearer $it") }
            val response =
                try {
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                } catch (timeout: HttpTimeoutException) {
                    throw NodeException.Timeout(
                        name,
                        NodeOperation.ENDPOINT,
                        "port ${request.port} did not answer ${request.verb} ${request.path} within " +
                            "${request.timeout.period.inWholeSeconds}s",
                        timeout,
                        // The *call* ran out of time, not a command the caller
                        // asked the node to run. `commandTimeout` is reserved for
                        // the latter; claiming it here would tell a caller the
                        // node is healthy when it may not be.
                        commandTimeout = false,
                    )
                } catch (failure: IOException) {
                    throw NodeException.Unreachable(
                        name,
                        NodeOperation.ENDPOINT,
                        "port ${request.port} refused or dropped ${request.verb} ${request.path}: " +
                            "${failure::class.simpleName}",
                        failure,
                    )
                }
            EndpointResponse(status = response.statusCode(), body = response.body().orEmpty())
        }

    /**
     * Stops the container, and nothing else.
     *
     * The sandbox is deliberately left alone: `StopPodSandbox` kills whatever
     * is inside with no grace and no save, which is the opposite of what a stop
     * with a grace period is for.
     */
    override suspend fun stopWorkload(
        handle: WorkloadHandle,
        gracePeriod: StopGrace,
    ) {
        // One rule, asked once, of the type that owns it. This used to be a
        // local `isPositive()` check standing in front of a `StopGracePeriod.of`
        // that enforced strictly more — so `Duration.INFINITE` cleared the guard
        // here and failed inside [translating] instead, where the catch-all for
        // unclassified `RuntimeException`s turns it into a *non-retryable*
        // rejection: a permanent drain abort produced by an argument, with no
        // container runtime involved. Two guards that disagree about the same
        // value is the defect; a second, better-informed local guard would be
        // the same defect again.
        //
        // `of` rounds up to whole seconds, so a grace period is never silently
        // shortened, and it refuses anything containerd's own arithmetic would
        // wrap — see [StopGracePeriod.MAX_SECONDS], where a very long grace
        // period becomes a very short one. That is **this runtime's** bound and it
        // stays here for that reason.
        //
        // The interface's own bound is not applied here at all any more, and that
        // is the thirtieth audit's ruling on the seam: it is carried by the
        // argument's type. [StopGrace] can only be built by [StopGrace.of], which
        // applies [StopGraceCeiling] — so the property "no `Node` holds a stop open
        // past the operational ceiling" belongs to every implementation of the
        // interface rather than to this one remembering. It also cannot be applied
        // here correctly: the ceiling has a floor derived from the workload's save
        // timeout, and a node cannot see the other half of that pair.
        val grace =
            StopGracePeriod.of(gracePeriod.period).getOrElse { rejection ->
                // Refused rather than thrown on: [Node] promises callers see
                // nothing but a [NodeException], and an
                // `IllegalArgumentException` from here would be the one thing a
                // caller cannot classify. The refusal is just as absolute — no
                // stop is issued either way.
                throw NodeException.Rejected(
                    name,
                    NodeOperation.STOP,
                    "${rejection.message}. It comes from spec.lifecycle.stopGracePeriod, which the schema " +
                        "validates at parse time — for a Paper server, to exceed that server's save timeout; " +
                        "a Velocity proxy holds no world and has no such rule",
                )
            }
        val containerId =
            handle.containerId ?: run {
                LOG.debug("nothing to stop for sandbox {}: no container was ever created", handle.sandboxId)
                return
            }
        translating(NodeOperation.STOP) {
            // `ContainerId` is constructed *inside* [translating] because it can
            // reject. A blank id built outside would leave here as a raw
            // `IllegalArgumentException` — not a [NodeException], so it would
            // slip past the `catch (failure: NodeException)` in
            // `DrainController.stop` that exists to compensate the routing table.
            //
            // Nothing can currently reach that: [WorkloadHandle] already requires
            // a set `containerId` to be non-blank, so the value here is either
            // null (handled above) or valid. This is not a fix for an observed
            // failure — it is so that the argument for safety is "the failure is
            // classified" rather than "the invariant one type away still holds".
            client.stopContainer(ContainerId(containerId), grace)
        }
    }

    override suspend fun removeWorkload(handle: WorkloadHandle): WorkloadRemoval {
        val sandboxId = SandboxId(handle.sandboxId)
        val plan =
            translating(NodeOperation.REMOVE) {
                // Everything still inside, not just the container this workload
                // knows about. `StopPodSandbox` kills whatever is in there with
                // no grace and no save, so "my container is gone" is not the
                // question — "is the sandbox empty" is. A container this
                // orchestrator did not create, or one left by a create that
                // raced, is somebody's running process either way.
                //
                // No server and no overlay: the guard needs an id and a state,
                // and both come from the enumeration. Handing it the optional
                // status field would be handing it the empty list that made this
                // guard necessary.
                val containers = containersIn(sandboxId, server = null, reported = emptyList())
                WorkloadView.teardown(
                    own = containers.firstOrNull { it.id == handle.containerId },
                    containers = containers,
                    ownId = handle.containerId,
                )
            }

        var containerRemoved = handle.containerId == null
        for (step in plan) {
            when (step) {
                is TeardownStep.Refuse -> {
                    throw NodeException.Rejected(name, NodeOperation.REMOVE, step.reason)
                }

                is TeardownStep.RemoveContainer -> {
                    translating(NodeOperation.REMOVE) { client.removeContainer(ContainerId(step.id)) }
                    containerRemoved = true
                }

                TeardownStep.RemoveSandbox -> {
                    // Safe now, and only now: there is nothing left inside to
                    // kill. A failure here is reported rather than thrown, so
                    // the caller can record that the container is gone — if it
                    // does not, the next pass sees a sandbox with no containers,
                    // cannot tell that from a runtime hiding a live one, and
                    // never retries this.
                    val failure =
                        try {
                            client.stopSandbox(sandboxId)
                            client.removeSandbox(sandboxId)
                            null
                        } catch (failure: CriException) {
                            failure.asNodeException(NodeOperation.REMOVE)
                        }
                    if (failure != null) {
                        if (!containerRemoved) throw failure
                        LOG.warn(
                            "server workload on node={} had its container removed but its sandbox {} did not go " +
                                "away: {}",
                            name,
                            sandboxId,
                            failure.message,
                        )
                        return WorkloadRemoval(
                            containerRemoved = true,
                            sandboxRemoved = false,
                            detail = failure.message,
                        )
                    }
                    // The persistent volume directory is deliberately untouched.
                }
            }
        }
        return WorkloadRemoval.COMPLETE
    }

    private suspend fun containerStateOf(id: ContainerId): ContainerState? =
        try {
            client.containerStatus(id).state
        } catch (gone: CriException.NotFound) {
            LOG.debug("container {} is already gone", id, gone)
            null
        }

    // ── spec derivation ──────────────────────────────────────────────────────

    /**
     * Derives the sandbox configuration from the workload alone.
     *
     * Deterministic on purpose: CRI wants the identical sandbox config handed
     * back at `CreateContainer`, and the loop does not keep one between passes.
     * Same [WorkloadSpec] in, same value out — and a workload whose spec has
     * changed is drained and recreated rather than grafted onto the old
     * sandbox.
     */
    private fun sandboxSpecFor(spec: WorkloadSpec): SandboxSpec =
        SandboxSpec(
            name = "${SANDBOX_PREFIX}${spec.server.value}",
            uid = "$sandboxNamespace/${spec.server.value}",
            namespace = sandboxNamespace,
            hostname = spec.hostname,
            logDirectory = logDirectoryFor(spec.server).toString(),
            portMappings =
                spec.ports.mapNotNull { port ->
                    port.hostPort?.let { PortMapping(containerPort = port.containerPort, hostPort = it) }
                },
            labels = spec.labels + (Labels.SPEC_HASH to spec.specHash),
            // Without this, a host running the systemd cgroup driver rejects
            // every sandbox: containerd falls back to a cgroupfs-shaped path and
            // runc refuses it, wanting `slice:prefix:name`. Nothing this
            // orchestrator creates can start. See [LocalNodeConfig.cgroupParent]
            // for why it is not a constant.
            linux = LinuxSandboxSpec(cgroupParent = cgroupParent),
        )

    /**
     * Whether a derivation needs the secret **values** or only the answer to
     * whether they are there.
     *
     * An enum rather than a boolean because the two arms differ in what they do
     * with material, and a `true` at a call site would say nothing about which is
     * which. The create needs [MATERIALISE]; a pre-flight that discards the result
     * would be making copies of a forwarding secret for nothing.
     */
    private enum class SecretAccess {
        MATERIALISE,
        PRESENCE_ONLY,
    }

    private suspend fun containerSpecFor(
        spec: WorkloadSpec,
        secretAccess: SecretAccess = SecretAccess.MATERIALISE,
    ): ContainerSpec =
        ContainerSpec(
            name = spec.server.value,
            image = ImageName(spec.image.canonical),
            env = spec.env + secretsFor(spec.secretEnv, secretAccess),
            command = spec.command,
            args = spec.args,
            mounts = mountsFor(spec),
            labels = spec.labels + (Labels.SPEC_HASH to spec.specHash),
            logPath = "${spec.server.value}.log",
            linux =
                LinuxContainerSpec(
                    resources =
                        LinuxResources(
                            memoryLimitBytes = spec.resources.memoryBytes,
                            cpuQuotaMicros =
                                spec.resources.cpuMillicores?.let {
                                    it.toLong() * CPU_PERIOD_MICROS / MILLICORES_PER_CORE
                                },
                            cpuPeriodMicros = spec.resources.cpuMillicores?.let { CPU_PERIOD_MICROS },
                        ),
                ),
        )

    /**
     * The workload's mounts, as CRI wants them.
     *
     * A field copy and nothing else. Every decision — which storage gets a
     * directory, where an asset comes from, whether a missing one is a failure —
     * belongs to [HostPaths.mounts], which is in this module's own types and can
     * therefore be tested. This function is where those decisions used to live,
     * and where one of them was silently dropped.
     */
    private fun mountsFor(spec: WorkloadSpec): List<VolumeMount> =
        HostPaths.mounts(name, volumeRoot, assetRoot, spec).map { mount ->
            VolumeMount(
                containerPath = mount.containerPath,
                hostPath = mount.hostPath,
                readOnly = mount.readOnly,
            )
        }

    /**
     * Resolves secret references at the moment they are handed to the runtime.
     *
     * The material exists as a `String` for exactly as long as it takes to put
     * it in a [ContainerSpec], whose `toString` redacts environment values.
     * Nothing here logs, wraps or returns it, and the reference — not the value
     * — is what travels through the rest of the system.
     *
     * The `String` is unavoidable: the CRI environment is a proto field of
     * strings, so somewhere between the store and the wire the material has to
     * become one. What is avoidable is the [SecretValue] outliving the call that
     * needed it — `use` hands out a copy and wipes the copy, but the original
     * lives on in whatever the store handed back — so it is destroyed here,
     * whatever happens next.
     */
    private suspend fun resolveSecrets(refs: Map<String, SecretRef>): Map<String, String> {
        if (refs.isEmpty()) return emptyMap()
        return refs.mapValues { (variable, ref) ->
            val secret = secrets.resolve(ref) ?: throw missingSecret(variable, ref)
            try {
                secret.use { material -> String(material) }
            } finally {
                secret.destroy()
            }
        }
    }

    /**
     * The environment contribution of [refs], with or without their values.
     *
     * Both arms refuse the same condition through [missingSecret], so the
     * pre-flight and the create cannot come to different conclusions about a
     * reference or word the refusal differently. What they differ in is whether
     * material exists in this process at all.
     */
    private suspend fun secretsFor(
        refs: Map<String, SecretRef>,
        access: SecretAccess,
    ): Map<String, String> =
        when (access) {
            SecretAccess.MATERIALISE -> {
                resolveSecrets(refs)
            }

            SecretAccess.PRESENCE_ONLY -> {
                for ((variable, ref) in refs) {
                    if (!secrets.contains(ref)) throw missingSecret(variable, ref)
                }
                emptyMap()
            }
        }

    /**
     * One definition of "this workload names a secret that is not there", so the
     * create's refusal and the pre-flight's are the same refusal.
     *
     * [NodeOperation.CREATE] in both cases, because that is the operation being
     * refused: the pre-flight is the create's question asked earlier and reports
     * nothing else. Names the coordinates and the variable, never the material.
     */
    private fun missingSecret(
        variable: String,
        ref: SecretRef,
    ): NodeException.Rejected =
        NodeException.Rejected(
            name,
            NodeOperation.CREATE,
            "the secret `${ref.name}/${ref.key}` needed for `$variable` is not in the secret store",
        )

    /**
     * Creates the host directories the runtime needs.
     *
     * The volume directory is created if it is absent and is otherwise left
     * exactly as it is — an existing world is the point of a persistent volume,
     * and this is the code path a restart goes through.
     */
    private fun prepareHostPaths(spec: WorkloadSpec) {
        // A full disk, a read-only mount, the wrong owner. None of it is a CRI
        // failure, so nothing above would translate it — see [HostPaths], which
        // owns that translation and is testable without a containerd.
        HostPaths.prepare(name, volumeRoot, logRoot, spec)
    }

    // There is deliberately no `volumePathFor` here any more. It existed for the
    // mount derivation that used to live in this file, and leaving a
    // volume-root-to-path helper lying about is an invitation to rebuild that
    // derivation beside the one in `HostPaths` — which is exactly the shape the
    // dropped plugin mount was written in.

    private fun logDirectoryFor(server: ResourceName): Path = HostPaths.logDirectory(logRoot, server)

    // ── failure translation ──────────────────────────────────────────────────

    /**
     * Runs a call and converts anything it throws into a [NodeException].
     *
     * The classification of a CRI failure is not re-derived:
     * [CriException.retryable] has already decided, and this maps the subclass
     * onto the node's own vocabulary. `CancellationException` is rethrown
     * untouched, as structured concurrency requires.
     *
     * The last two clauses are what make this a boundary rather than a mapper.
     * [Node] promises that nothing but a [NodeException] comes out, and callers
     * rely on it hard: the reconcile loop's worker catches [NodeException] and
     * [mcorch.store.StoreException], and anything else escaping cancels the
     * scope that owns *every* worker. A node that reaches a full disk, a secret
     * store that is down, or a bug in this class must therefore surface as a
     * failure of one server rather than as the end of the loop. Nothing is
     * swallowed — the original is the cause of what is thrown.
     */
    private suspend fun <T> translating(
        operation: NodeOperation,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CriException) {
            throw failure.asNodeException(operation)
        } catch (failure: NodeException) {
            throw failure
        } catch (failure: StoreException) {
            // Secret resolution is the only store call a node makes. A remote
            // node would resolve it on the far side, so its failure is a node
            // failure from here.
            throw if (failure.retryable) {
                NodeException.Busy(name, operation, "the secret store is unavailable: ${failure.message}", failure)
            } else {
                NodeException.Rejected(name, operation, "the secret store rejected a read: ${failure.message}", failure)
            }
        } catch (failure: RuntimeException) {
            throw NodeException.Rejected(
                name,
                operation,
                "the node failed in a way it does not classify: ${failure::class.simpleName}: ${failure.message}",
                failure,
            )
        }

    /**
     * Translates a CRI failure into this module's vocabulary.
     *
     * Every branch takes [CriException.safeMessage], never `message`. That is
     * the form with the runtime's own words withheld where they might quote a
     * request holding secret material, and it is the only form allowed out of
     * here: this string becomes `FailureStatus.message`, which is written to
     * SQLite and served through the API.
     *
     * **The decision is not repeated here, and must not be.** `:cri` makes it
     * once, in `safeDescription`, against a list it keeps as an exhaustive
     * `when`. This module briefly consulted that list itself and combined the
     * two halves — which put a one-token security decision in a module whose
     * tests cannot name a `mcorch.cri` type, so nothing could cover it.
     * Reaching for `description` or `message` here, or re-testing the operation,
     * rebuilds exactly that gap.
     */
    private fun CriException.asNodeException(operation: NodeOperation): NodeException =
        when (this) {
            is CriException.Unavailable -> NodeException.Unreachable(name, operation, safeMessage, this)

            // The trailing argument is `commandTimeout`, carried across rather
            // than re-derived: :cri decides it by elapsed time against its own
            // deadline, and that deadline is not visible from anywhere above
            // this line. Nothing above may look at a gRPC code either, so this
            // is the only place the two kinds of timeout can be told apart.
            is CriException.Timeout -> NodeException.Timeout(name, operation, safeMessage, this, commandTimeout)

            is CriException.ResourceExhausted -> NodeException.Busy(name, operation, safeMessage, this)

            is CriException.Aborted -> NodeException.Busy(name, operation, safeMessage, this)

            is CriException.RuntimeFailure -> NodeException.Busy(name, operation, safeMessage, this)

            // A name collision from either create path has already been through
            // `adoptAfterCollision` and survived a second lookup that found
            // nothing — see [isNameCollision], and note that containerd reports
            // one as `FAILED_PRECONDITION` rather than this. Whichever code it
            // arrives as, retrying repeats the same find-create-collide cycle
            // for ever and shows `RETRYABLE` while never asking anyone to look,
            // so it is reported instead.
            is CriException.AlreadyExists -> NodeException.Rejected(name, operation, safeMessage, this)

            is CriException.NotFound -> NodeException.NotFound(name, operation, safeMessage, this)

            is CriException.InvalidArgument -> NodeException.Rejected(name, operation, safeMessage, this)

            is CriException.FailedPrecondition -> NodeException.Rejected(name, operation, safeMessage, this)

            is CriException.PermissionDenied -> NodeException.Rejected(name, operation, safeMessage, this)

            is CriException.Unimplemented -> NodeException.Rejected(name, operation, safeMessage, this)

            is CriException.Cancelled -> NodeException.Rejected(name, operation, safeMessage, this)
        }

    private fun WorkloadHandle.requireContainer(operation: NodeOperation): ContainerId =
        containerId?.let(::ContainerId)
            ?: throw NodeException.Rejected(
                name,
                operation,
                "sandbox $sandboxId has no container yet",
            )

    override fun close() {
        client.close()
    }

    public companion object {
        private val LOG = LoggerFactory.getLogger(LocalNode::class.java)
        private const val SANDBOX_PREFIX = "mcorch-"
        private const val CPU_PERIOD_MICROS = 100_000L
        private const val MILLICORES_PER_CORE = 1000L

        /**
         * How long a TCP connect to a sandbox address may take.
         *
         * Short and separate from the caller's per-request timeout, which bounds
         * the *answer*. Connecting to a port on the same host either succeeds
         * immediately or the listener is not there.
         */
        private const val CONNECT_TIMEOUT_SECONDS = 5L

        /**
         * Opens a node against a containerd on this host.
         *
         * Takes a [LocalNodeConfig] rather than a CRI configuration so the
         * composition root can build one without depending on `:cri` at all —
         * if wiring ever cannot construct a [Node] without reaching for a CRI
         * type, the seam is wrong.
         *
         * Does not connect eagerly: the first call is what discovers containerd
         * is down, and it fails with a retryable
         * [NodeException.Unreachable].
         *
         * ## The one pre-flight, and why it is here rather than at the ceiling
         *
         * A stop this node issues is deadlined by `:cri` at
         * `min(gracePeriod, stopDeadlineCap) + deadlineSlack`, and a grace period
         * past that deadline can never reach the runtime's kill however many times
         * the drain re-issues it — [StopGraceCeiling], *The relation a re-issued stop
         * terminates on*, has the mechanism and the measurement. So the largest grace
         * period [StopGrace] will hand this node has to stay inside the cap the
         * client is actually built with.
         *
         * `StopGraceCeiling`'s own `init` was the obvious place and it cannot be:
         * `Node.kt` is the distribution seam, and a `:cri` type named there would
         * make the interface's policy ceiling a statement about one runtime's
         * transport configuration. **This** class is the one `:core` already permits
         * to name CRI types, and it is the one holding the [CriClientConfig]. The
         * arithmetic on the constants is pinned separately, in `StopGraceGuardTest`;
         * this binds the config, and neither subsumes the other.
         *
         * **What makes it bind the config is an identity, not the check's presence.**
         * The `require` reads `criConfig.timeouts.stopDeadlineCap` and
         * `CriClient.connect` is handed **the same `criConfig` value** — that is the
         * whole of the guarantee. `connect(criConfig.copy(timeouts = …))` would
         * satisfy the check and then run on a different cap, and no scan can see that,
         * because this file is entitled to name these types. Keep the two naming one
         * value; if a future edit has to transform the config, do it *above* the
         * `require` so the check reads what is connected.
         *
         * Today the config is built two lines up with default timeouts, so this is
         * asking about the same numbers `StopGraceGuardTest` asks about. The
         * difference becomes real the moment [LocalNodeConfig] gains a timeouts input
         * — which is precisely the change that would silently invalidate the test —
         * and it is written this way now so that change needs no new thinking.
         *
         * It throws at wiring time, which is the right blast radius: nothing has been
         * reconciled yet, no container exists to be stranded, and the repair is a
         * code or configuration change. That is the split round 24 drew — a `require`
         * may enforce what a *planner* gets wrong, never what an operator supplies on
         * a definition, because the latter freezes a server nobody can then retire.
         * Both operands here are compile-time constants: nothing an operator writes,
         * in YAML or the environment, reaches this predicate.
         */
        public fun open(
            config: LocalNodeConfig,
            secrets: SecretStore,
        ): LocalNode {
            val criConfig = CriClientConfig(endpoint = CriEndpoint.parse(config.runtimeEndpoint))
            val ceiling = StopGraceCeiling.ceilingFor(SpecBounds.MAX_SAVE_TIMEOUT)
            require(ceiling <= criConfig.timeouts.stopDeadlineCap) {
                "a stop this node issues may carry a grace period of up to $ceiling, but its CRI client stops " +
                    "waiting for a stop after ${criConfig.timeouts.stopDeadlineCap} plus " +
                    "${criConfig.timeouts.deadlineSlack} of slack. A grace period past that total never reaches " +
                    "the runtime's kill, and the drain re-issues it on every pass for ever — a container that can " +
                    "only be retired with crictl. This refuses at the cap alone, one slack short of where the " +
                    "behaviour changes, so that it does not depend on a margin :cri may retune. The fix is to " +
                    "raise CriTimeouts.stopDeadlineCap: it bounds only how long one call waits, never the grace " +
                    "period the container is given. Lowering the ceiling instead is possible but narrower than " +
                    "it looks: PaperServerDefaults.MAX_STOP_GRACE_PERIOD can only fall as far as " +
                    "MAX_TIMEOUT + MIN_STOP_GRACE_MARGIN before SpecBounds.init refuses it, so on the shipped " +
                    "constants that is 1h30s and it helps only for a cap somewhere in (1h30s, 2h). Going below " +
                    "that means lowering MAX_TIMEOUT too, which is the ceiling PaperServerReader applies to " +
                    "several spec timeouts and not only the save timeout"
            }
            return LocalNode(
                name = config.name,
                client = CriClient.connect(criConfig),
                secrets = secrets,
                volumeRoot = config.volumeRoot,
                logRoot = config.logRoot,
                assetRoot = config.assetRoot,
                sandboxNamespace = config.sandboxNamespace,
                cgroupParent = config.cgroupParent,
            )
        }
    }
}

/**
 * Everything the single-host node needs, in types the composition root can
 * name.
 *
 * [runtimeEndpoint] is a string in the form containerd tooling uses
 * (`unix:///run/containerd/containerd.sock`). It has no default: defaulting it
 * would let a misconfigured deployment quietly talk to the wrong containerd.
 */
public data class LocalNodeConfig(
    val name: NodeName,
    val runtimeEndpoint: String,
    /** Root of the persistent volume directories. Worlds live under here and outlive containers. */
    val volumeRoot: Path,
    /** Root of the container log directories. */
    val logRoot: Path,
    /**
     * Where this host keeps the artefacts the orchestrator ships into
     * containers, by [mcorch.core.WorkloadAsset.fileName].
     *
     * Deliberately has no default, and deliberately is not derived from
     * anything. It is a deployment fact — where the install put the plugin JAR —
     * and a default would be a guess that reads as working right up until a
     * proxy comes up without its control plugin. A workload that asks for an
     * asset which is not here is refused at create time rather than started
     * without it.
     */
    val assetRoot: Path,
    /** Groups this orchestrator's sandboxes. Not a Kubernetes namespace; nothing resolves it anywhere. */
    val sandboxNamespace: String = "mcorch",
    /**
     * The cgroup every sandbox this node creates is placed under.
     *
     * **Its shape depends on the runtime's cgroup driver, so it cannot be a
     * constant.** With the systemd driver — what containerd selects on a systemd
     * host with cgroup v2, and what `scripts/dev/containerd-up.sh` configures —
     * this is a slice name, which containerd expands into the
     * `slice:prefix:name` path runc demands. With the cgroupfs driver it is a
     * path fragment instead, so an operator on such a host sets `/mcorch`; a
     * slice name there would create a directory literally called
     * `mcorch.slice`.
     *
     * Null leaves the field unset, which is containerd's own default — and on a
     * systemd host that default is exactly what makes every `RunPodSandbox`
     * fail, so it is a deliberate opt-out rather than a sensible blank.
     *
     * The slice need not exist as a unit file: systemd creates a transient
     * slice on demand.
     */
    val cgroupParent: String? = DEFAULT_CGROUP_PARENT,
) {
    init {
        require(runtimeEndpoint.isNotBlank()) { "runtimeEndpoint must not be blank" }
        require(sandboxNamespace.isNotBlank()) { "sandboxNamespace must not be blank" }
        require(cgroupParent == null || cgroupParent.isNotBlank()) {
            "cgroupParent must not be blank; use null to leave the choice to the runtime"
        }
    }

    public companion object {
        /**
         * A systemd slice, because that is the driver containerd selects on the
         * hosts this targets. Everything the orchestrator runs lands under it,
         * so a host's Minecraft servers can be accounted for in one place.
         */
        public const val DEFAULT_CGROUP_PARENT: String = "mcorch.slice"
    }
}

/**
 * The detail view. `ListContainers` does not carry timings or exit information,
 * so a summary-derived view leaves them null rather than guessing.
 */
private fun ContainerStatus.toDetail(): ContainerDetail = ContainerDetail(toView())

private fun ContainerStatus.toView(): ContainerView =
    ContainerView(
        id = id.value,
        labels = labels,
        state = state.toWorkloadState(),
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
        exitCode = exitCode,
        reason = reason,
        message = message,
        imageId = imageId.value,
    )

private fun ContainerSummary.toView(): ContainerView =
    ContainerView(
        id = id.value,
        labels = labels,
        state = state.toWorkloadState(),
        createdAt = createdAt,
        imageId = imageId.value,
    )

private fun ContainerState.toWorkloadState(): WorkloadState =
    when (this) {
        ContainerState.CREATED -> WorkloadState.CREATED
        ContainerState.RUNNING -> WorkloadState.RUNNING
        ContainerState.EXITED -> WorkloadState.EXITED
        ContainerState.UNKNOWN -> WorkloadState.UNKNOWN
    }

package mcorch.core.proxy

import mcorch.core.EndpointRequest
import mcorch.core.EndpointResponse
import mcorch.core.HttpVerb
import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.WorkloadHandle
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.velocity.control.ControlErrorCode
import mcorch.velocity.control.ControlProtocol
import kotlin.time.Duration

/**
 * The orchestrator's half of the control protocol, over the [Node] abstraction.
 *
 * Every request goes through [Node.callEndpoint], so nothing here knows whether
 * the proxy is on this host or another one — the channel is a workload handle, a
 * port and a token coordinate, and a distributed node satisfies it unchanged
 * (CLAUDE.md invariant 7). There is no socket, no address and no `localhost` in
 * this file.
 *
 * ## What this type is responsible for
 *
 * Turning three kinds of answer into three different things, because the drain
 * treats them differently and collapsing any two of them loses a decision:
 *
 * - **The proxy answered and did it** — [ControlOutcome.Answered].
 * - **The proxy answered and refused** — [ControlOutcome.Refused], carrying the
 *   protocol's own [ControlErrorCode]. These are not failures of the channel:
 *   `BACKEND_OCCUPIED` means *this* caller ran step 6 before step 4 finished, and
 *   `SOURCE_NOT_SEALED` means it ran step 4 before step 2. The remedy is in the
 *   caller, so the code has to survive the trip.
 * - **The proxy could not be reached, or said something unreadable** —
 *   [ControlOutcome.Unavailable]. This is where `PROXY_CONTROL_UNREACHABLE` and
 *   `PROXY_PLUGIN_INCOMPATIBLE` come from, and it is where the [NodeException]
 *   translation happens. Nothing above this line sees a node failure from the
 *   control channel, for the same reason nothing above [Node] sees a CRI one.
 *
 * ## Compatibility is set membership
 *
 * [version] reports [ProxyVersion.compatible] as `ControlProtocol.VERSION in
 * supported`, read from [ControlProtocol] rather than from a string in this file.
 * Not `>=`: the version's only meaning is "the wire changed", so there is no
 * ordering over it to compare, and a set is the only rule under which a newer
 * plugin can keep serving an older `:core` through a rolling upgrade.
 */
internal class ControlChannel(
    private val node: Node,
    private val handle: WorkloadHandle,
    private val port: Int,
    private val token: SecretRef?,
    private val timeout: Duration,
) {
    /**
     * The handshake. The one route the plugin serves before it is ready and
     * without a token, which is what lets a caller tell three states apart that
     * would otherwise all read as silence: a wrong port, a wrong token, and a
     * proxy that is still starting.
     */
    suspend fun version(): ControlOutcome<ProxyVersion> =
        call(HttpVerb.GET, ControlProtocol.PATH_VERSION) { body ->
            val supported = body.strings("supported")
            ProxyVersion(
                plugin = body.string("plugin"),
                pluginVersion = body.string("pluginVersion"),
                pluginApiVersion = body.string("pluginApiVersion"),
                supported = supported,
                ready = body.boolean("ready"),
                compatible = ControlProtocol.isCompatibleWith(supported),
            )
        }

    /** Everything the plugin observes, in one read. Counts only — no identities exist in this shape. */
    suspend fun state(): ControlOutcome<ProxyState> =
        call(HttpVerb.GET, ControlProtocol.PATH_STATE) { body -> body.toProxyState() }

    /**
     * Level-triggered assertion of whether the *proxy itself* accepts new logins.
     *
     * Asserted before the last admitting backend is sealed, which is the one
     * ordering constraint the protocol cannot enforce for `:core`: with every
     * backend sealed the login-path seal has nowhere to deflect a joining player
     * to and admits them anyway, so a fleet-wide drain could never reach zero.
     * Refusing a login is not disconnecting anybody, so this is always safe.
     */
    suspend fun assertProxyAdmission(admits: Boolean): ControlOutcome<ProxySelf> =
        call(
            verb = HttpVerb.PUT,
            path = ControlProtocol.PATH_PROXY,
            body = ControlJson.body("admitsNewPlayers" to admits),
        ) { it.toProxySelf() }

    /**
     * Drain step 2, and the steady-state assertion, in one idempotent call.
     *
     * The body states what should be true and the plugin makes it true: this
     * registers the backend if it is absent and leaves the seal where the caller
     * says it should be. Calling it a thousand times with the same body registers
     * at most once, which is why the reconcile loop can assert it every pass
     * without accumulating anything.
     *
     * It never deregisters. A changed address comes back as
     * [ControlErrorCode.ADDRESS_CONFLICT] rather than as an upsert, because the
     * only way Velocity can move a registration is unregister-then-register, and
     * an unregister hidden inside this call would be step 6 executed at step 2.
     */
    suspend fun assertBackend(
        backend: ResourceName,
        address: String,
        admits: Boolean,
    ): ControlOutcome<BackendView> =
        call(
            verb = HttpVerb.PUT,
            path = ControlProtocol.PATH_BACKEND + backend.value,
            body = ControlJson.body("address" to address, "admitsNewPlayers" to admits),
        ) { it.toBackendView() }

    /**
     * Drain step 4. Start-or-join: a repeat naming the same destination while a
     * sweep is running returns that sweep and asks nobody to move again.
     *
     * [message] is operator-facing and reaches players. It must never contain a
     * player's name — nothing here has one to put in it.
     */
    suspend fun transfer(
        backend: ResourceName,
        destination: ResourceName,
        message: String?,
    ): ControlOutcome<TransferView> =
        call(
            verb = HttpVerb.POST,
            path = ControlProtocol.PATH_BACKEND + backend.value + ControlProtocol.TRANSFER_SUFFIX,
            body = ControlJson.body("destination" to destination.value, "message" to message),
        ) { it.toTransferView() }

    /**
     * Drain step 6, and the only thing that removes a registration.
     *
     * Refused with [ControlErrorCode.BACKEND_OCCUPIED] while anybody is still
     * connected, and there is no force flag. Reaching that refusal means the
     * caller's own ordering is wrong.
     */
    suspend fun deregister(backend: ResourceName): ControlOutcome<DeregisterView> =
        call(HttpVerb.DELETE, ControlProtocol.PATH_BACKEND + backend.value) { body ->
            DeregisterView(
                name = body.string("name"),
                registered = body.boolean("registered"),
                deregistered = body.boolean("deregistered"),
            )
        }

    private suspend fun <T> call(
        verb: HttpVerb,
        path: String,
        body: String? = null,
        read: (ControlObject) -> T,
    ): ControlOutcome<T> {
        val response =
            try {
                node.callEndpoint(
                    handle,
                    EndpointRequest(
                        port = port,
                        verb = verb,
                        path = path,
                        body = body,
                        contentType = if (body == null) null else ControlProtocol.CONTENT_TYPE,
                        bearerToken = token,
                        timeout = timeout,
                    ),
                )
            } catch (failure: NodeException) {
                // Translated at this edge, exactly as `LocalNode` translates a CRI
                // failure at its own. Above here a control failure is a control
                // failure, never a node one — a caller that pattern-matched on
                // `NodeException` from a proxy call would be treating the proxy's
                // health as the node's.
                return ControlOutcome.Unavailable(
                    detail =
                        "the proxy control endpoint on port $port did not answer $verb $path: " +
                            failure.message,
                    retryable = failure.retryable,
                )
            }
        return interpret(verb, path, response, read)
    }

    private fun <T> interpret(
        verb: HttpVerb,
        path: String,
        response: EndpointResponse,
        read: (ControlObject) -> T,
    ): ControlOutcome<T> =
        try {
            val parsed = ControlJson.parse(response.body)
            if (response.successful) {
                ControlOutcome.Answered(read(parsed))
            } else {
                val error = parsed.objectAt(ControlProtocol.FIELD_ERROR)
                val code =
                    ControlErrorCode.entries.firstOrNull { it.name == error.string("code") }
                        ?: return ControlOutcome.Unavailable(
                            detail =
                                "the proxy refused $verb $path with a code this build does not know " +
                                    "(`${error.string("code")}`), which means it is speaking a protocol this " +
                                    "build does not",
                            retryable = false,
                        )
                ControlOutcome.Refused(code, error.string("message"))
            }
        } catch (malformed: ControlMalformed) {
            // A body this build cannot read is a *compatibility* problem, not a
            // reachability one, and it is deliberately not retryable: asking the
            // same plugin again gets the same body. The remedy is an image
            // upgrade, which is what `PROXY_PLUGIN_INCOMPATIBLE` tells an operator.
            ControlOutcome.Unavailable(
                detail =
                    "the proxy answered $verb $path with a body this build cannot read " +
                        "(${malformed.message}); the plugin is speaking a protocol this build does not",
                retryable = false,
            )
        }
}

/**
 * What a control request produced.
 *
 * Three cases and not two: see [ControlChannel] for why a refusal must not be
 * collapsed into a failure.
 */
internal sealed interface ControlOutcome<out T> {
    data class Answered<T>(
        val value: T,
    ) : ControlOutcome<T>

    /** The proxy answered and declined. [code] is the protocol's own, and the caller branches on it. */
    data class Refused(
        val code: ControlErrorCode,
        val problem: String,
    ) : ControlOutcome<Nothing>

    /** The endpoint could not be reached, or answered something unreadable. */
    data class Unavailable(
        val detail: String,
        val retryable: Boolean,
    ) : ControlOutcome<Nothing>
}

internal data class ProxyVersion(
    val plugin: String,
    val pluginVersion: String,
    val pluginApiVersion: String,
    val supported: List<String>,
    val ready: Boolean,
    /** `ControlProtocol.VERSION in supported`. Set membership, never an ordering. */
    val compatible: Boolean,
)

internal data class ProxySelf(
    val admitsNewPlayers: Boolean,
    val players: Int,
    val refusedLogins: Int,
    /**
     * When this plugin instance came up.
     *
     * The **only** way a proxy restart is detectable. Seal state is soft and never
     * persisted, so a restart lifts every seal — and because `:core` asserts and
     * then reads, the read alone cannot tell a seal that held continuously from
     * one lifted moments earlier. Both answer `admitsNewPlayers: false`.
     */
    val startedAtEpochMs: Long,
)

internal data class ProxyState(
    val pluginApiVersion: String,
    val observedAtEpochMs: Long,
    val proxy: ProxySelf,
    val backends: List<BackendView>,
) {
    fun backend(name: ResourceName): BackendView? = backends.firstOrNull { it.name.equals(name.value, true) }

    /** Backends still taking new players. The set the proxy's own seal has to be asserted against. */
    val admitting: List<BackendView> get() = backends.filter { it.admitsNewPlayers }
}

internal data class BackendView(
    val name: String,
    val address: String,
    val registered: Boolean,
    val admitsNewPlayers: Boolean,
    /**
     * Players the *proxy* has on this backend.
     *
     * **Never the stop gate.** It cannot see a client connected straight to the
     * backend's own port, and whether backends are firewalled is a deployment
     * property this code cannot assert. It is the right number for the
     * deregistration guard — a directly-connected player is unaffected by a
     * routing-table removal — and it is corroboration for a Server List Ping,
     * which is what actually decides.
     */
    val players: Int,
    val transfer: TransferView?,
)

internal data class TransferView(
    val destination: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val requested: Int,
    val moved: Int,
    val alreadyThere: Int,
    val refused: Int,
    val failed: Int,
    val inFlight: Int,
    /** Read live off the backend, so a player who joined mid-sweep counts against it. */
    val remaining: Int,
) {
    val finished: Boolean get() = finishedAtEpochMs != null

    /** How many of the requested moves did not land. Retryable ones and failed ones alike. */
    val unmoved: Int get() = refused + failed
}

internal data class DeregisterView(
    val name: String,
    val registered: Boolean,
    val deregistered: Boolean,
)

private fun ControlObject.toProxySelf(): ProxySelf =
    ProxySelf(
        admitsNewPlayers = boolean("admitsNewPlayers"),
        players = int("players"),
        refusedLogins = int("refusedLogins"),
        startedAtEpochMs = long("startedAtEpochMs"),
    )

private fun ControlObject.toProxyState(): ProxyState =
    ProxyState(
        pluginApiVersion = string("pluginApiVersion"),
        observedAtEpochMs = long("observedAtEpochMs"),
        proxy = objectAt("proxy").toProxySelf(),
        backends = objects("backends").map { it.toBackendView() },
    )

private fun ControlObject.toBackendView(): BackendView =
    BackendView(
        name = string("name"),
        address = string("address"),
        registered = boolean("registered"),
        admitsNewPlayers = boolean("admitsNewPlayers"),
        players = int("players"),
        transfer = optionalObject("transfer")?.toTransferView(),
    )

private fun ControlObject.toTransferView(): TransferView =
    TransferView(
        destination = string("destination"),
        startedAtEpochMs = long("startedAtEpochMs"),
        finishedAtEpochMs = optionalLong("finishedAtEpochMs"),
        requested = int("requested"),
        moved = int("moved"),
        alreadyThere = int("alreadyThere"),
        refused = int("refused"),
        failed = int("failed"),
        inFlight = int("inFlight"),
        remaining = int("remaining"),
    )

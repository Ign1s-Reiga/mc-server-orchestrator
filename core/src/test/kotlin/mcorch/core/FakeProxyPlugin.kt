package mcorch.core

import mcorch.velocity.control.ControlErrorCode
import mcorch.velocity.control.ControlProtocol

/**
 * The Velocity plugin's control endpoint, simulated in memory.
 *
 * A simulator rather than a stub, for the same reason [FakeNode] is one: the
 * assertions that matter are about **side effects on a third party** — no second
 * seal, no second transfer, no second registration, joins restored after an
 * abort — and those are only provable from counters on something that actually
 * holds state.
 *
 * It enforces the ordering constraints the real protocol enforces, because those
 * are the ones `:core` is being tested against. In particular:
 *
 * - `DELETE` refuses with `BACKEND_OCCUPIED` while anybody is connected, and there
 *   is no force flag.
 * - `POST /transfer` refuses with `SOURCE_NOT_SEALED` when the source still
 *   admits, which is what makes "step 2 precedes step 4" a wire property.
 * - `PUT` on a registered backend at a different address is `ADDRESS_CONFLICT`
 *   rather than an upsert.
 *
 * A fake that were more permissive than the real plugin would validate `:core`'s
 * ordering against something nobody checked — the exact failure `TestStore` was
 * tightened to avoid.
 */
internal class FakeProxyPlugin(
    var ready: Boolean = true,
    /** What the handshake advertises. A build that speaks something else is a compatibility test. */
    var supported: List<String> = ControlProtocol.SUPPORTED,
) {
    /** Backends in the routing table, by lowercased name. */
    val backends: MutableMap<String, Backend> = linkedMapOf()

    var proxyAdmits: Boolean = true
        private set

    var refusedLogins: Int = 0

    /** Changes on every [restart]. The only way a proxy restart is detectable. */
    var startedAtEpochMs: Long = 1_000L
        private set

    // ── recorded side effects ────────────────────────────────────────────────

    /** Every `PUT /v1/backends/{name}` that landed, with what it asserted. */
    val asserts: MutableList<Pair<String, Boolean>> = mutableListOf()

    /** Registrations that actually created a routing-table entry. A repeat does not count. */
    val registrations: MutableList<String> = mutableListOf()

    /** Every `POST .../transfer` that started or joined a sweep. */
    val transfers: MutableList<Pair<String, String>> = mutableListOf()

    /** Sweeps that actually asked somebody to move. A join does not count. */
    val sweepsStarted: MutableList<String> = mutableListOf()

    val deregistrations: MutableList<String> = mutableListOf()

    /** Every `PUT /v1/proxy` that landed. */
    val proxyAsserts: MutableList<Boolean> = mutableListOf()

    var versionCalls: Int = 0
        private set

    /** Requests that never got a reply, simulating an endpoint that is not listening. */
    var unreachable: Boolean = false

    class Backend(
        val name: String,
        var address: String,
        var admits: Boolean = true,
        var players: Int = 0,
        var sweep: Sweep? = null,
    )

    class Sweep(
        val destination: String,
        val startedAtEpochMs: Long,
        val requested: Int,
        var finished: Boolean,
        var refused: Int = 0,
    )

    /**
     * A proxy that came back up.
     *
     * Seal state is soft and never persisted, so every seal lifts and the
     * registrations go with it — which is exactly the state `:core` has to repair
     * by re-asserting rather than by remembering.
     */
    fun restart() {
        startedAtEpochMs += 1_000L
        backends.clear()
        proxyAdmits = true
        refusedLogins = 0
    }

    fun register(
        name: String,
        address: String,
        players: Int = 0,
        admits: Boolean = true,
    ) {
        backends[name.lowercase()] = Backend(name, address, admits, players)
    }

    fun backend(name: String): Backend? = backends[name.lowercase()]

    /** Every player on [name] moves to their sweep's destination. */
    fun completeSweep(name: String) {
        val backend = backend(name) ?: return
        val sweep = backend.sweep ?: return
        backend(sweep.destination)?.let { it.players += backend.players }
        backend.players = 0
        sweep.finished = true
    }

    /** Answers one request, the way the plugin's `ControlService` would. */
    @Suppress("ReturnCount")
    fun handle(request: EndpointRequest): EndpointResponse {
        if (unreachable) throw java.io.IOException("nothing is listening on port ${request.port}")
        if (request.path == ControlProtocol.PATH_VERSION) {
            versionCalls += 1
            return ok(
                """{"plugin":"${ControlProtocol.PLUGIN_ID}","pluginVersion":"1.0.0",""" +
                    """"pluginApiVersion":"${ControlProtocol.VERSION}",""" +
                    """"supported":[${supported.joinToString(",") { "\"$it\"" }}],"ready":$ready}""",
            )
        }
        if (!ready) return error(ControlErrorCode.NOT_READY, "the proxy has not finished starting")
        return when {
            request.path == ControlProtocol.PATH_STATE -> state()
            request.path == ControlProtocol.PATH_PROXY -> assertProxy(request)
            request.path.startsWith(ControlProtocol.PATH_BACKEND) -> routeBackend(request)
            else -> error(ControlErrorCode.NO_SUCH_ROUTE, "no route for ${request.path}")
        }
    }

    private fun routeBackend(request: EndpointRequest): EndpointResponse {
        val tail = request.path.removePrefix(ControlProtocol.PATH_BACKEND)
        if (tail.endsWith(ControlProtocol.TRANSFER_SUFFIX)) {
            return transfer(tail.removeSuffix(ControlProtocol.TRANSFER_SUFFIX), request)
        }
        return when (request.verb) {
            HttpVerb.PUT -> assertBackend(tail, request)
            HttpVerb.DELETE -> deregister(tail)
            else -> error(ControlErrorCode.METHOD_NOT_ALLOWED, "this path accepts PUT, DELETE")
        }
    }

    private fun assertProxy(request: EndpointRequest): EndpointResponse {
        val admits = field(request.body, "admitsNewPlayers").toBoolean()
        proxyAdmits = admits
        proxyAsserts += admits
        return ok(proxyJson())
    }

    private fun assertBackend(
        name: String,
        request: EndpointRequest,
    ): EndpointResponse {
        val address = field(request.body, "address")
        val admits = field(request.body, "admitsNewPlayers").toBoolean()
        val existing = backend(name)
        // The seal goes on first and unconditionally, before the address check
        // throws: refusing the address half must not silently refuse the seal half.
        asserts += name to admits
        existing?.admits = admits
        if (existing == null) {
            backends[name.lowercase()] = Backend(name, address, admits)
            registrations += name
        } else if (!existing.address.equals(address, ignoreCase = true)) {
            return error(
                ControlErrorCode.ADDRESS_CONFLICT,
                "`$name` is registered at ${existing.address}, not at $address",
            )
        }
        return ok(backendJson(backend(name) ?: Backend(name, address, admits)))
    }

    private fun deregister(name: String): EndpointResponse {
        val existing = backend(name) ?: return ok("""{"name":"$name","registered":false,"deregistered":false}""")
        if (existing.players > 0) {
            return error(
                ControlErrorCode.BACKEND_OCCUPIED,
                "`$name` still has ${existing.players} player(s) connected",
            )
        }
        backends.remove(name.lowercase())
        deregistrations += name
        return ok("""{"name":"$name","registered":false,"deregistered":true}""")
    }

    private fun transfer(
        name: String,
        request: EndpointRequest,
    ): EndpointResponse {
        val destinationName = field(request.body, "destination")
        val source = backend(name) ?: return error(ControlErrorCode.BACKEND_UNKNOWN, "`$name` is not registered")
        if (source.admits) {
            return error(ControlErrorCode.SOURCE_NOT_SEALED, "`$name` still admits new players")
        }
        if (destinationName.equals(name, ignoreCase = true)) {
            return error(ControlErrorCode.TRANSFER_TO_SELF, "`$name` cannot be its own destination")
        }
        val destination =
            backend(destinationName)
                ?: return error(ControlErrorCode.DESTINATION_UNKNOWN, "`$destinationName` is not registered")
        if (!destination.admits) {
            return error(ControlErrorCode.DESTINATION_SEALED, "`$destinationName` is sealed")
        }
        transfers += name to destinationName
        val running = source.sweep
        if (running != null && running.destination.equals(destinationName, true) && !running.finished) {
            // Start-or-join: a repeat while a sweep is running asks nobody to move
            // again. Not counted in `sweepsStarted`, which is what an idempotency
            // assertion reads.
            return ok(sweepJson(running, source.players))
        }
        val sweep = Sweep(destinationName, startedAtEpochMs, source.players, finished = source.players == 0)
        source.sweep = sweep
        sweepsStarted += name
        return ok(sweepJson(sweep, source.players))
    }

    private fun state(): EndpointResponse =
        ok(
            """{"pluginApiVersion":"${ControlProtocol.VERSION}","observedAtEpochMs":$startedAtEpochMs,""" +
                """"proxy":${proxyJson()},"backends":[""" +
                backends.values.joinToString(",") { backendJson(it) } + "]}",
        )

    private fun proxyJson(): String =
        """{"admitsNewPlayers":$proxyAdmits,"players":${backends.values.sumOf { it.players }},""" +
            """"refusedLogins":$refusedLogins,"startedAtEpochMs":$startedAtEpochMs}"""

    private fun backendJson(backend: Backend): String {
        val sweep = backend.sweep
        val transfer = if (sweep == null) "null" else sweepJsonBody(sweep, backend.players)
        return """{"name":"${backend.name}","address":"${backend.address}","registered":true,""" +
            """"admitsNewPlayers":${backend.admits},"players":${backend.players},"transfer":$transfer}"""
    }

    private fun sweepJson(
        sweep: Sweep,
        remaining: Int,
    ): String = sweepJsonBody(sweep, remaining)

    private fun sweepJsonBody(
        sweep: Sweep,
        remaining: Int,
    ): String =
        """{"destination":"${sweep.destination}","startedAtEpochMs":${sweep.startedAtEpochMs},""" +
            """"finishedAtEpochMs":${if (sweep.finished) sweep.startedAtEpochMs else null},""" +
            """"requested":${sweep.requested},"moved":${sweep.requested - sweep.refused - remaining},""" +
            """"alreadyThere":0,"refused":${sweep.refused},"failed":0,"inFlight":0,"remaining":$remaining}"""

    private fun ok(body: String) = EndpointResponse(200, body)

    private fun error(
        code: ControlErrorCode,
        message: String,
    ) = EndpointResponse(
        code.httpStatus,
        """{"error":{"code":"${code.name}","message":"$message"}}""",
    )

    /** A field out of a request body. The bodies here are flat, so this need not be a parser. */
    private fun field(
        body: String?,
        name: String,
    ): String {
        val raw = body.orEmpty()
        val quoted = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
        if (quoted != null) return quoted
        return Regex("\"$name\"\\s*:\\s*(true|false|null)").find(raw)?.groupValues?.get(1) ?: ""
    }
}

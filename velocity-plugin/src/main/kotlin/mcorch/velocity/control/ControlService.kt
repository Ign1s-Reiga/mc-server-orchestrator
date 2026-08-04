package mcorch.velocity.control

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Every operation the control protocol offers, over [ProxyControl].
 *
 * This class holds all of the semantics and none of the transport: it takes a
 * method, a path and a body, and returns a status and a body. Authentication is
 * deliberately *not* here — that is a property of the socket the request arrived
 * on, and keeping it out means routing, idempotency and the drain-critical
 * refusals are all reachable from a unit test with no server and no proxy.
 *
 * ## The six operations
 *
 * | Verb and path | Drain step | Notes |
 * |---|---|---|
 * | `GET /v1/version` | — | The handshake. The one route the transport does not authenticate |
 * | `GET /v1/state` | read-back | Everything observed, as counts |
 * | `PUT /v1/proxy` | proxy drain | Level-triggered: does the proxy accept logins |
 * | `PUT /v1/backends/{name}` | 2 | Level-triggered: registered, and does it admit new players |
 * | `POST /v1/backends/{name}/transfer` | 4 | Start-or-join a sweep. Never a second sweep |
 * | `DELETE /v1/backends/{name}` | 6 | The only thing that removes a registration |
 *
 * ## What is not here
 *
 * There is no endpoint that reads or writes the forwarding secret, and there
 * will not be one. It reaches the proxy at container-create time and this
 * channel never carries it.
 *
 * There is also no "unseal" and no "list players". The first because the seal is
 * asserted rather than toggled; the second because the answer would be a list of
 * identities and this plugin does not have a shape to put one in.
 */
public class ControlService(
    private val proxy: ProxyControl,
    public val admission: AdmissionRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Held across "is it registered?" then "register it".
     *
     * Velocity does not document what a second `registerServer` for a name it
     * already has does, so this plugin must never issue one. Two concurrent
     * asserts for the same new backend would otherwise both see it absent.
     */
    private val registryLock = ReentrantLock()

    private val transfers = ConcurrentHashMap<String, TransferOperation>()

    /**
     * When this plugin instance came up, reported as `proxy.startedAtEpochMs`.
     *
     * Read at construction rather than at [markReady] so it is the incarnation's
     * identity and not a readiness timestamp: what `:core` needs from it is only
     * that it differs after a restart.
     */
    private val startedAtEpochMs: Long = clock()

    /** True once the proxy has finished initialising. Until then every route but the handshake is 503. */
    @Volatile
    private var ready: Boolean = false

    public fun markReady() {
        ready = true
    }

    public fun handle(
        method: String,
        path: String,
        body: String,
    ): ControlResponse =
        try {
            route(method, path, body)
        } catch (failure: ControlFailure) {
            error(failure.code, failure.problem)
        }

    private fun route(
        method: String,
        path: String,
        body: String,
    ): ControlResponse {
        // The handshake answers before readiness, so that a `:core` talking to a
        // still-starting proxy sees "reachable, compatible, not ready" rather
        // than the same silence a wrong port produces.
        if (path == ControlProtocol.PATH_VERSION) {
            return if (method == "GET") version() else refuseMethod("GET")
        }
        if (!ready) {
            throw ControlFailure(ControlErrorCode.NOT_READY, "the proxy has not finished starting")
        }
        return when {
            path == ControlProtocol.PATH_STATE -> {
                if (method == "GET") state() else refuseMethod("GET")
            }

            path == ControlProtocol.PATH_PROXY -> {
                if (method == "PUT") assertProxyAdmission(body) else refuseMethod("PUT")
            }

            path.startsWith(ControlProtocol.PATH_BACKEND) -> {
                routeBackend(method, path, body)
            }

            else -> {
                throw ControlFailure(ControlErrorCode.NO_SUCH_ROUTE, "no route for $path")
            }
        }
    }

    private fun routeBackend(
        method: String,
        path: String,
        body: String,
    ): ControlResponse {
        val tail = path.removePrefix(ControlProtocol.PATH_BACKEND)
        if (tail.endsWith(ControlProtocol.TRANSFER_SUFFIX)) {
            val name = tail.removeSuffix(ControlProtocol.TRANSFER_SUFFIX).requireBackendName()
            return if (method == "POST") transfer(name, body) else refuseMethod("POST")
        }
        val name = tail.requireBackendName()
        return when (method) {
            "PUT" -> assertBackend(name, body)
            "DELETE" -> deregister(name)
            else -> refuseMethod("PUT, DELETE")
        }
    }

    private fun String.requireBackendName(): String {
        if (isEmpty() || contains('/')) {
            throw ControlFailure(ControlErrorCode.NO_SUCH_ROUTE, "the path does not name a single backend")
        }
        return this
    }

    // --- GET /v1/version ---

    /**
     * The handshake, and the whole of the compatibility contract on the wire.
     *
     * `:core` fills `ControlEndpointStatus.pluginApiVersion` from
     * `pluginApiVersion` and `compatible` from whether its own
     * [ControlProtocol.VERSION] appears in `supported`. It must not re-derive
     * either from a hardcoded string.
     */
    private fun version(): ControlResponse =
        ok(
            JsonWriter()
                .obj {
                    field("plugin", ControlProtocol.PLUGIN_ID)
                    field("pluginVersion", ControlProtocol.PLUGIN_VERSION)
                    field("pluginApiVersion", ControlProtocol.VERSION)
                    stringArray("supported", ControlProtocol.SUPPORTED)
                    field("ready", ready)
                }.toString(),
        )

    // --- GET /v1/state ---

    /**
     * Everything the plugin observes, in one read.
     *
     * The read-back is the reason this is a plugin rather than a config rewrite:
     * `:core` asserts a seal and then *confirms* it, instead of recording that it
     * sent a request. Every record-versus-side-effect hazard in this codebase
     * dissolves at the point the effect can be observed afterwards.
     *
     * Counts only. There is no field here, at any depth, that a player's name,
     * UUID or address could be written into — see `PlayerIdentityLeakageTest`.
     */
    private fun state(): ControlResponse {
        val observedAt = clock()
        val backends = proxy.backends().sortedBy { it.name.lowercase(Locale.ROOT) }
        val body =
            JsonWriter()
                .obj {
                    field("pluginApiVersion", ControlProtocol.VERSION)
                    field("observedAtEpochMs", observedAt)
                    objectField("proxy") {
                        field("admitsNewPlayers", admission.proxyAdmits())
                        field("players", proxy.playerCount())
                        field("refusedLogins", admission.refusedLogins())
                        // The incarnation marker. Seal state is soft, so a restart lifts
                        // every seal until the next pass re-asserts it — and because
                        // `:core` asserts then reads, the read alone cannot tell a seal
                        // that held from one lifted moments ago. This changes on every
                        // restart, which is the signal.
                        field("startedAtEpochMs", startedAtEpochMs)
                    }
                    objectArray("backends", backends) { backend -> writeBackend(backend) }
                }.toString()
        return ok(body)
    }

    private fun JsonWriter.ObjectScope.writeBackend(backend: BackendHandle) {
        val counters = admission.counters(backend.name)
        field("name", backend.name)
        field("address", backend.address)
        field("registered", true)
        field("admitsNewPlayers", admission.admits(backend.name))
        field("players", backend.players().size)
        objectField("seal") {
            field("refusedSwitches", counters.refusedSwitches)
            field("deflectedJoins", counters.deflectedJoins)
            field("admittedWithoutAlternative", counters.admittedWithoutAlternative)
        }
        val operation = transfers[key(backend.name)]
        if (operation == null) {
            nullField("transfer")
        } else {
            objectField("transfer") { writeTransfer(operation, backend.players().size) }
        }
    }

    private fun JsonWriter.ObjectScope.writeTransfer(
        operation: TransferOperation,
        remaining: Int,
    ) {
        val tally = operation.tally()
        field("destination", operation.destination)
        field("startedAtEpochMs", operation.startedAtEpochMs)
        field("finishedAtEpochMs", operation.finishedAtEpochMs)
        field("requested", operation.requested)
        field("moved", tally.moved)
        field("alreadyThere", tally.alreadyThere)
        field("refused", tally.refused)
        field("failed", tally.failed)
        field("inFlight", operation.requested - tally.settled)
        // Read live from the backend rather than derived from the tally: it is the
        // number drain step 4 actually waits on, and a player who joined mid-sweep
        // counts against it even though nothing asked to move them.
        field("remaining", remaining)
    }

    // --- PUT /v1/proxy ---

    private fun assertProxyAdmission(body: String): ControlResponse {
        val admits = Json.parseObject(body).boolean("admitsNewPlayers")
        admission.assertProxyAdmission(admits)
        return ok(
            JsonWriter()
                .obj {
                    field("admitsNewPlayers", admission.proxyAdmits())
                    field("players", proxy.playerCount())
                    field("refusedLogins", admission.refusedLogins())
                    field("startedAtEpochMs", startedAtEpochMs)
                }.toString(),
        )
    }

    // --- PUT /v1/backends/{name} ---

    /**
     * Drain step 2, and the ordinary steady-state assertion, in one call.
     *
     * Level-triggered: the body states what should be true, and this makes it
     * true. Calling it with the same body a thousand times registers the backend
     * at most once and leaves the seal where it already was.
     *
     * It never deregisters anything. Not on a conflict, not on a re-address, not
     * on a seal. The only path that removes a registration is `DELETE`.
     */
    private fun assertBackend(
        name: String,
        body: String,
    ): ControlResponse {
        val request = Json.parseObject(body)
        val address = BackendAddress.parse(request.string("address"))
        val admits = request.boolean("admitsNewPlayers")

        val backend =
            registryLock.withLock {
                // The seal goes on first, and unconditionally. Two reasons, both of
                // which are the same reason:
                //
                //  * A backend asserted as sealed must never exist in the routing
                //    table in an admitting state, not even for the microseconds
                //    between `register` and a later `assertAdmission`. Velocity will
                //    route a joining player in that window.
                //  * The address conflict below throws. The seal has nothing to do
                //    with the address, so refusing the address half must not silently
                //    refuse the seal half — that is the one way this file could
                //    contradict its own thesis that the seal is not the registration.
                //    A caller whose address is wrong still gets the backend sealed,
                //    and sealing a backend you disagree about the address of is
                //    always the safe direction.
                admission.assertAdmission(name, admits)
                val existing = proxy.backend(name)
                if (existing == null) {
                    proxy.register(name, address.host, address.port)
                    proxy.backend(name)
                        ?: throw ControlFailure(
                            ControlErrorCode.INTERNAL,
                            "the proxy did not expose `$name` after registering it",
                        )
                } else {
                    if (!existing.address.equals(address.toString(), ignoreCase = true)) {
                        // Changing a Velocity registration's address means unregister
                        // then register, and an unregister here would be drain step 6
                        // performed at step 2. Refuse and let :core drain it properly.
                        throw ControlFailure(
                            ControlErrorCode.ADDRESS_CONFLICT,
                            "`$name` is registered at ${existing.address}, not at $address. " +
                                "Drain and deregister it before moving it",
                        )
                    }
                    existing
                }
            }
        return ok(JsonWriter().obj { writeBackend(backend) }.toString())
    }

    // --- DELETE /v1/backends/{name} ---

    /**
     * Drain step 6.
     *
     * Refuses while anybody is still connected. Velocity does not document what
     * unregistering does to those connections, and `failure-modes.md` item 3 says
     * what this project assumes: they are lost. There is no force flag — reaching
     * this refusal means `:core` ran step 6 before step 4 finished, and the remedy
     * is to fix that ordering, not to overrule it from the far side.
     *
     * Deregistering something already absent is a success, because the loop may
     * re-enter any state any number of times.
     */
    private fun deregister(name: String): ControlResponse {
        val removed =
            registryLock.withLock {
                val existing = proxy.backend(name)
                val occupants = existing?.players()?.size ?: 0
                if (occupants > 0) {
                    throw ControlFailure(
                        ControlErrorCode.BACKEND_OCCUPIED,
                        "`$name` still has $occupants player(s) connected. " +
                            "Transfer them before deregistering it (drain step 4 precedes step 6)",
                    )
                }
                val gone = existing != null && proxy.deregister(name)
                // Inside the lock, not after it. A seal dropped while the backend is
                // still registered is a backend that silently starts admitting again
                // — and released early, a concurrent PUT could register and seal in
                // the gap, only for this `forget` to erase that seal and leave
                // `:core` holding a 200 that says sealed.
                admission.forget(name)
                synchronized(transfers) { transfers.remove(key(name)) }
                gone
            }
        return ok(
            JsonWriter()
                .obj {
                    field("name", name)
                    field("registered", false)
                    field("deregistered", removed)
                }.toString(),
        )
    }

    // --- POST /v1/backends/{name}/transfer ---

    /**
     * Drain step 4: move this backend's players to [destination], and never do
     * anything else to them.
     *
     * Start-or-join. A repeat call naming the same destination while a sweep is
     * still running returns that sweep's state and issues no second request to
     * anybody — the loop re-enters this state on every pass and a duplicated
     * connection request per player is a real side effect. Once a sweep has
     * finished, a repeat call starts a fresh one, because that is the retry the
     * drain protocol asks for and whoever already moved is no longer on this
     * backend to be asked again.
     *
     * Every failure mode ends with the player still connected to something. There
     * is no branch here that disconnects anyone, and there is nothing on
     * [PlayerHandle] with which to write one.
     *
     * ## Naming a different destination mid-sweep supersedes rather than refuses
     *
     * The running sweep's record is replaced and the new one starts. Players whose
     * move to the old destination is already in flight arrive there — Velocity
     * answers a second request for a connection already in progress with
     * `CONNECTION_IN_PROGRESS`, which lands here as [TransferResult.REFUSED] and is
     * retried on the next pass. Nobody is disconnected on either path, so this is
     * left as a supersede rather than a refusal: `:core` changing its mind is
     * usually a destination that stopped being eligible, and refusing would leave
     * it with a sweep it could not redirect.
     */
    private fun transfer(
        name: String,
        body: String,
    ): ControlResponse {
        val request = Json.parseObject(body)
        val destinationName = request.string("destination")
        val message = request.optionalString("message")
        if (message != null && message.length > ControlProtocol.MAX_MESSAGE_LENGTH) {
            throw ControlFailure(
                ControlErrorCode.MALFORMED_REQUEST,
                "`message` must be at most ${ControlProtocol.MAX_MESSAGE_LENGTH} characters",
            )
        }

        val source =
            proxy.backend(name)
                ?: throw ControlFailure(ControlErrorCode.BACKEND_UNKNOWN, "`$name` is not registered with this proxy")
        if (admission.admits(source.name)) {
            // Step 2 precedes step 4, made a property of the protocol rather than of
            // the caller's discipline. A sweep on a backend that still admits refills
            // behind itself and can never converge.
            throw ControlFailure(
                ControlErrorCode.SOURCE_NOT_SEALED,
                "`$name` still admits new players. Seal it first, or the sweep refills behind itself",
            )
        }
        if (destinationName.equals(name, ignoreCase = true)) {
            throw ControlFailure(ControlErrorCode.TRANSFER_TO_SELF, "`$name` cannot be its own transfer destination")
        }
        val destination =
            proxy.backend(destinationName)
                ?: throw ControlFailure(
                    ControlErrorCode.DESTINATION_UNKNOWN,
                    "`$destinationName` is not registered with this proxy",
                )
        if (!admission.admits(destination.name)) {
            throw ControlFailure(
                ControlErrorCode.DESTINATION_SEALED,
                "`$destinationName` is sealed and is not a destination with capacity",
            )
        }

        val operation = startOrJoin(source, destination, message)
        return ok(JsonWriter().obj { writeTransfer(operation, source.players().size) }.toString())
    }

    /**
     * Starts a sweep, or hands back the one already running.
     *
     * ## Why every path here has to reach a settled tally
     *
     * The record is published with its denominator (`requested`) already set, and
     * the join rule below returns a running record without asking anybody to move.
     * So a record that can never settle is a record that absorbs every retry step
     * 4 asks for, for ever: `remaining` never falls, `DELETE` keeps refusing
     * because players are still there, and the backend becomes permanently
     * undrainable and therefore permanently undeletable — with players on it. No
     * player is disconnected by that and no world is lost, but it is the state
     * that generates pressure toward stopping the container by hand, which is the
     * actual data-loss event.
     *
     * Three guards, because there are three ways to not settle:
     *
     * 1. **A throw while issuing.** `notify` or `requestTransfer` can throw
     *    synchronously — `sendMessage` on a component that will not encode for a
     *    player's protocol version, for one, and the notice text is operator
     *    supplied. Caught per player and counted as a failure, so the loop always
     *    registers an outcome for every one of `requested`.
     * 2. **A future that never completes.** Bounded by
     *    [ControlProtocol.TRANSFER_SETTLE_TIMEOUT_MS]. Everything crossing a
     *    boundary this slow gets a timeout; a drain waits on this one.
     * 3. **A record stuck anyway.** The join rule only joins a sweep that is both
     *    unfinished *and* younger than [ControlProtocol.SWEEP_MAX_AGE_MS], so a
     *    stuck record is superseded by the next request rather than inherited by
     *    it.
     *
     * The issuing loop runs outside the monitor. Holding it across N proxy calls
     * would serialise unrelated backends' sweeps on a small thread pool inside a
     * live proxy; the monitor is only needed to make "decide, then publish"
     * atomic.
     */
    private fun startOrJoin(
        source: BackendHandle,
        destination: BackendHandle,
        message: String?,
    ): TransferOperation {
        val sourceKey = key(source.name)
        val players: List<PlayerHandle>
        val operation: TransferOperation
        synchronized(transfers) {
            val existing = transfers[sourceKey]
            if (existing != null &&
                existing.destination.equals(destination.name, ignoreCase = true) &&
                existing.joinable(clock())
            ) {
                return existing
            }
            players = source.players()
            operation = TransferOperation(destination.name, clock(), players.size)
            transfers[sourceKey] = operation
        }
        if (players.isEmpty()) {
            operation.finish(clock())
            return operation
        }
        val notice = message ?: defaultNotice(destination.name)
        for (player in players) {
            try {
                // Told before they are moved — SKILL.md step 4. The notice goes out
                // even if the move then fails, which is the honest ordering: they
                // were about to be moved.
                player.notify(notice)
                player
                    .requestTransfer(destination)
                    .orTimeout(ControlProtocol.TRANSFER_SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .whenComplete { result, thrown -> settle(operation, result, thrown) }
            } catch (thrown: RuntimeException) {
                // Not swallowed: it is counted as this player's failure, which is
                // what it is. They are still connected to the source, and the tally
                // still reaches `requested` so the sweep can finish.
                settle(operation, null, thrown)
            }
        }
        return operation
    }

    private fun settle(
        operation: TransferOperation,
        result: TransferResult?,
        thrown: Throwable?,
    ) {
        operation.record(result, thrown)
        if (operation.settledAll()) operation.finish(clock())
    }

    private fun defaultNotice(destination: String): String =
        "<yellow>This server is being drained. Moving you to <white>$destination</white>.</yellow>"

    // --- plumbing ---

    private fun refuseMethod(allowed: String): ControlResponse =
        error(ControlErrorCode.METHOD_NOT_ALLOWED, "this path accepts $allowed")

    private fun ok(body: String): ControlResponse = ControlResponse(200, body)

    private fun error(
        code: ControlErrorCode,
        problem: String,
    ): ControlResponse =
        ControlResponse(
            code.httpStatus,
            JsonWriter()
                .obj {
                    objectField(ControlProtocol.FIELD_ERROR) {
                        field("code", code.name)
                        field("message", problem)
                    }
                }.toString(),
        )

    private fun key(name: String): String = name.lowercase(Locale.ROOT)
}

/** A status and a JSON body. The transport adds headers and nothing else. */
public data class ControlResponse(
    val status: Int,
    val body: String,
)

/**
 * One transfer sweep in flight, as tallies.
 *
 * The counters are the only record kept of a sweep. Which players moved is not
 * stored, because storing it would mean holding identities for the length of a
 * drain in something a read endpoint can reach.
 */
public class TransferOperation(
    public val destination: String,
    public val startedAtEpochMs: Long,
    /** How many players were on the backend when the sweep started. */
    public val requested: Int,
) {
    private val moved = AtomicInteger()
    private val alreadyThere = AtomicInteger()
    private val refused = AtomicInteger()
    private val failed = AtomicInteger()

    @Volatile
    public var finishedAtEpochMs: Long? = null
        private set

    internal fun record(
        result: TransferResult?,
        thrown: Throwable?,
    ) {
        when {
            // A request that blew up is a request that did not move anybody. It is
            // counted as a failure and the player stays where they are; nothing
            // here reacts to an exception by disconnecting them.
            thrown != null || result == null -> failed

            result == TransferResult.MOVED -> moved

            result == TransferResult.ALREADY_THERE -> alreadyThere

            result == TransferResult.REFUSED -> refused

            else -> failed
        }.incrementAndGet()
    }

    internal fun settledAll(): Boolean = tally().settled >= requested

    internal fun finish(at: Long) {
        if (finishedAtEpochMs == null) finishedAtEpochMs = at
    }

    /**
     * Whether a repeat request should join this sweep rather than start a new one.
     *
     * Unfinished *and* young. The age bound is what stops a record that can never
     * settle from absorbing every retry the drain protocol asks for — see
     * [ControlProtocol.SWEEP_MAX_AGE_MS].
     */
    internal fun joinable(now: Long): Boolean =
        finishedAtEpochMs == null && now - startedAtEpochMs < ControlProtocol.SWEEP_MAX_AGE_MS

    public fun tally(): TransferTally =
        TransferTally(
            moved = moved.get(),
            alreadyThere = alreadyThere.get(),
            refused = refused.get(),
            failed = failed.get(),
        )
}

/** Outcome counts for a sweep. [settled] is how many of the requested moves have an answer. */
public data class TransferTally(
    val moved: Int,
    val alreadyThere: Int,
    val refused: Int,
    val failed: Int,
) {
    public val settled: Int get() = moved + alreadyThere + refused + failed
}

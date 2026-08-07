package mcorch.velocity.control

/**
 * The wire contract between `:core` and the shipped Velocity plugin.
 *
 * This file names no Velocity type, on purpose. `:core` may depend on
 * `:velocity-plugin` for exactly this object, so that the protocol version and
 * the paths have one definition in the repository rather than one here and a
 * copy in the reconciler that goes stale silently.
 *
 * ## What the endpoint is for
 *
 * Three steps of the drain protocol are things only a proxy can do: stop new
 * joins (step 2), transfer players (step 4), deregister the backend (step 6).
 * Velocity has no RCON and no admin socket, so the orchestrator talks to a
 * plugin. Everything below exists to serve those three steps and to let `:core`
 * *read back* what it asserted, because a drain that cannot observe its own
 * effects is a drain that guesses.
 *
 * ## Two things the shape of this protocol is defending
 *
 * **The seal is not deregistration.** Velocity's obvious "stop sending players
 * here" is removing the backend from the registered-server map — which is also
 * step 6. Collapsing them means deregistering before transferring, which
 * disconnects everyone still connected. So the seal is a *routing-level refusal*
 * asserted through [PATH_BACKEND] with the registration left intact, and
 * deregistration is its own verb ([the DELETE][PATH_BACKEND]) that is never
 * implied by anything: not by omission from a list, not by a seal, not by a
 * transfer finishing.
 *
 * **The seal is level-triggered.** `:core` asserts "this backend admits new
 * players / does not" on every reconcile pass and the plugin makes reality
 * match. It is not an event and there is no "unseal" operation, because an
 * operation to undo a thing is an operation somebody can forget to call. An
 * aborted drain restores joins on the next pass for free; a proxy restart is
 * re-asserted on the next pass for free. The corollary is deliberate: seal state
 * is **soft state, held in memory and never persisted**. A plugin that persisted
 * it would survive an orchestrator that died mid-drain, and the backend would
 * stay sealed forever with nothing left that knew to un-seal it.
 *
 * ## One ordering constraint this surface cannot enforce for `:core`
 *
 * With every backend sealed, the login-path seal has nowhere to deflect a joining
 * player to and admits them ([InitialChoice.AdmitAnyway]) — so a fleet-wide drain
 * can never reach zero players on any backend. The proxy's own seal
 * ([PATH_PROXY]) is what stops that, and it is safe because refusing a login is
 * not disconnecting anybody. **`:core` must assert the proxy seal before it seals
 * the last admitting backend.** Nothing here can check that, because no single
 * request knows it is the last one.
 *
 * ## Versioning
 *
 * [VERSION] changes only when the wire changes in a way an older `:core` cannot
 * read. [SUPPORTED] is the set of versions *this build* can serve, and
 * compatibility is [membership][isCompatibleWith] in that set — not `>=`, not a
 * semver range. A set is the only rule under which a newer plugin can keep
 * serving an older `:core` through a rolling upgrade, and the only rule that
 * does not invent an ordering over a number whose sole meaning is "the wire
 * changed".
 */
public object ControlProtocol {
    /**
     * The protocol this build speaks. Reported as `pluginApiVersion` and compared
     * by `:core`, which must read it from here rather than hardcode it.
     */
    public const val VERSION: String = "1"

    /**
     * Every protocol version this build can serve. One entry today; a transition
     * release would carry two, and a version leaves this list only when support
     * for it is deliberately removed.
     */
    public val SUPPORTED: List<String> = listOf(VERSION)

    /** Whether a peer advertising [supported] can talk to this build. See the class note. */
    public fun isCompatibleWith(supported: List<String>): Boolean = VERSION in supported

    /** Velocity requires `[a-z][a-z0-9-_]{0,63}`. Also the JAR's `velocity-plugin.json` id. */
    public const val PLUGIN_ID: String = "mcorch-control"

    public const val PLUGIN_NAME: String = "mcorch control"

    /**
     * The plugin's own release version, distinct from [VERSION]: this moves on
     * every change to the plugin, the protocol version only on a wire break.
     */
    public const val PLUGIN_VERSION: String = "1.0.0"

    public const val PLUGIN_MAIN_CLASS: String = "mcorch.velocity.plugin.VelocityControlPlugin"

    /** Read by `PluginDescriptorTest`, which is what stops the checked-in JSON drifting from the above. */
    public const val DESCRIPTOR_RESOURCE: String = "velocity-plugin.json"

    // --- transport ---

    public const val CONTENT_TYPE: String = "application/json; charset=utf-8"
    public const val HEADER_AUTHORIZATION: String = "Authorization"
    public const val BEARER_PREFIX: String = "Bearer "

    /**
     * Request bodies here are four fields at most. The cap exists so an unbounded
     * read cannot be aimed at a proxy's heap by anything that reaches the port.
     */
    public const val MAX_BODY_BYTES: Int = 16 * 1024

    /** A player-facing transfer notice. Long enough for a sentence, short enough not to be a payload. */
    public const val MAX_MESSAGE_LENGTH: Int = 512

    /**
     * How long one player's move may stay unsettled before it is counted failed.
     *
     * **Not the drain's transfer timeout.** That one lives on the backend's
     * `spec.lifecycle.drain.playerTransferTimeout`, scales with player count, and
     * belongs to `:core`. This is the far cruder guarantee that a sweep always
     * *reaches* a settled state: a Velocity future that never completes would
     * otherwise leave the record permanently in flight, and the join rule would
     * then hand that record to every retry without asking anybody to move again.
     * A drain that waits forever on a proxy is a container the orchestrator can
     * never stop.
     */
    public const val TRANSFER_SETTLE_TIMEOUT_MS: Long = 60_000

    /**
     * When a running sweep stops being joinable and a fresh request supersedes it.
     *
     * Comfortably above [TRANSFER_SETTLE_TIMEOUT_MS] so a slow-but-working sweep is
     * never superseded mid-flight. It exists because the join rule is the one place
     * a stuck record could absorb every retry `SKILL.md` step 4 asks for.
     */
    public const val SWEEP_MAX_AGE_MS: Long = 180_000

    // --- paths ---

    /**
     * Version handshake. **The one route that does not require the token.**
     *
     * `ControlEndpointStatus` splits "did not answer" from "answered, wrong
     * version" because the remedies differ, and only an unauthenticated handshake
     * lets `:core` tell those apart from a *third* case — a misconfigured token —
     * instead of collapsing all three into `reachable = false`. What it discloses
     * is the string "1" and the plugin's name, to a caller that already reached
     * the port.
     */
    public const val PATH_VERSION: String = "/v1/version"

    /**
     * Everything the plugin observes, in one read. Counts only, never identities.
     *
     * ## `backends[].players` is not the stop gate
     *
     * It counts players the *proxy* has on a backend. It cannot see a client
     * connected straight to the backend's own port, so it is the wrong number to
     * conclude "this container is empty and may be stopped" from — that stays with
     * `:core`'s Server List Ping against the backend itself.
     *
     * It *is* the right number for the deregistration guard, and the asymmetry is
     * deliberate rather than an oversight: a directly-connected player is
     * unaffected by removing the backend from the proxy's routing table, so
     * `DELETE` refusing on this count is refusing on exactly the population it
     * would harm.
     *
     * ## `proxy.startedAtEpochMs` is how a restart is detected
     *
     * Seal state is deliberately not persisted, so a proxy restart lifts every
     * seal until `:core`'s next pass re-asserts it. Because `:core` asserts and
     * *then* reads, the read alone cannot tell a seal that held continuously from
     * one lifted moments earlier — both answer `admitsNewPlayers: false`. This
     * field changes on every restart, which is the signal, and the seal counters
     * resetting to zero is the corroboration.
     */
    public const val PATH_STATE: String = "/v1/state"

    /** `PUT` — level-triggered assertion of whether the proxy itself admits new logins. */
    public const val PATH_PROXY: String = "/v1/proxy"

    /**
     * `PUT /v1/backends/{name}` asserts registration *and* seal in one idempotent
     * call; `DELETE /v1/backends/{name}` is drain step 6 and is the only thing
     * that ever removes a registration.
     */
    public const val PATH_BACKEND: String = "/v1/backends/"

    /** `POST /v1/backends/{name}/transfer` — drain step 4. Start-or-join, never a second sweep. */
    public const val TRANSFER_SUFFIX: String = "/transfer"

    /** The JSON field carrying a failure code, so `:core` branches on a symbol rather than on prose. */
    public const val FIELD_ERROR: String = "error"
}

/**
 * Why a control request did not do what it asked.
 *
 * Each is a distinct remedy on the `:core` side, which is the test for whether a
 * code deserves to exist. [BACKEND_OCCUPIED] and [ADDRESS_CONFLICT] in
 * particular are refusals to perform something destructive, not internal
 * failures — see their notes.
 */
public enum class ControlErrorCode(
    public val httpStatus: Int,
) {
    /** The body was not JSON, a field was the wrong type, or a required field was absent. */
    MALFORMED_REQUEST(400),

    /** No `Authorization: Bearer` header, or the wrong token. */
    UNAUTHENTICATED(401),

    /** No route. Distinct from [BACKEND_UNKNOWN]: this one means the path itself is not part of the protocol. */
    NO_SUCH_ROUTE(404),

    METHOD_NOT_ALLOWED(405),

    /** The named backend is not registered with this proxy. */
    BACKEND_UNKNOWN(404),

    /**
     * A `PUT` named an already-registered backend at a *different* address.
     *
     * Refused rather than re-registered, and this is the load-bearing part: the
     * only way Velocity can change a registration's address is unregister then
     * register, and an unregister hidden inside an "upsert" is drain step 6
     * executed without steps 2 to 5. `:core` must drain and deregister the
     * backend before moving it.
     */
    ADDRESS_CONFLICT(409),

    /**
     * A `DELETE` named a backend with players still connected.
     *
     * Deregistering a populated backend is `failure-modes.md` item 3 — it
     * disconnects everyone still on it — so the plugin refuses it outright. There
     * is deliberately no force flag: an escape hatch here is a way to spell the
     * one thing this endpoint exists to make unspellable, and `:core` reaching
     * this code means its own ordering is wrong, not that it needs a stronger
     * verb.
     */
    BACKEND_OCCUPIED(409),

    /** The transfer destination is not registered with this proxy. */
    DESTINATION_UNKNOWN(409),

    /**
     * The transfer destination is itself sealed.
     *
     * Moving a draining server's players onto another draining server is a
     * destination without capacity in the only sense that matters, and the drain
     * protocol says to abort rather than to move players somewhere they will have
     * to be moved from again.
     */
    DESTINATION_SEALED(409),

    /** Source and destination are the same backend. */
    TRANSFER_TO_SELF(400),

    /**
     * A transfer was requested for a backend that still admits new players.
     *
     * Drain step 2 precedes step 4 and this makes the ordering a property of the
     * protocol rather than of `:core`'s discipline. A sweep on an unsealed backend
     * refills behind itself: players arrive as fast as they are moved, `remaining`
     * never reaches zero, and the drain sits in `Transferring` until it times out
     * and aborts — repeatedly, because nothing about the situation changes.
     */
    SOURCE_NOT_SEALED(409),

    /** The proxy has not finished starting, so there is nothing truthful to report yet. */
    NOT_READY(503),

    /** Anything unhandled. The message never carries a stack trace or a player identity. */
    INTERNAL(500),
}

/** A refusal, in the form every non-2xx response takes. */
public class ControlFailure(
    public val code: ControlErrorCode,
    public val problem: String,
) : RuntimeException("$code: $problem")

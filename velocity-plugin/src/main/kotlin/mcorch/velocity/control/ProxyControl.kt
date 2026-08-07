package mcorch.velocity.control

import java.util.concurrent.CompletableFuture

/**
 * Everything this plugin does to a proxy, behind an interface that names no
 * Velocity type.
 *
 * There are two reasons for the seam, and only one of them is testability.
 *
 * **The operations that matter can be driven without a proxy.** The seal, the
 * transfer sweep, idempotency and the whole of the read-back path live in
 * [ControlService] against this interface, so they are covered by ordinary unit
 * tests. What is left on the Velocity side is a translation layer thin enough to
 * read in one sitting — which is the part that cannot be tested without a live
 * proxy, so it is the part worth making small.
 *
 * **A transfer cannot become a kick.** [PlayerHandle] has no disconnect, kick or
 * close operation, and it never will. The transfer sweep is written against this
 * interface and therefore has nothing to call: `failure-modes.md` item 4 is
 * refused by the type system rather than by a reviewer noticing. The Velocity
 * adapter wraps a `Player`, which *does* have `disconnect`, so
 * `TransferNeverKicksTest` also scans this module's sources for it.
 *
 * ## Identity is present here on purpose
 *
 * [PlayerHandle] exposes [PlayerHandle.username], [PlayerHandle.uniqueId] and
 * [PlayerHandle.remoteAddress] even though nothing above it is allowed to emit
 * them. A port that only handed out counts would make `PlayerIdentityLeakageTest`
 * prove nothing — there would be no identity in the system to leak. The rule is
 * that identity stops at this boundary, and the test is what enforces it.
 */
public interface ProxyControl {
    /** Every backend the proxy currently has in its routing table. */
    public fun backends(): List<BackendHandle>

    /** One backend by name, or null if it is not registered. Velocity matches names case-insensitively. */
    public fun backend(name: String): BackendHandle?

    /**
     * Adds a backend to the routing table.
     *
     * Only ever called after [backend] returned null for this name, because
     * Velocity does not document what a second `registerServer` with the same
     * name does. [ControlService] holds a lock across the check and this call so
     * two concurrent asserts cannot both find it absent.
     */
    public fun register(
        name: String,
        host: String,
        port: Int,
    )

    /**
     * Removes a backend from the routing table. Drain step 6.
     *
     * Returns false if it was not registered, which is a success: the loop may
     * re-enter any state any number of times and a deregistration that already
     * happened is the state that was wanted.
     *
     * Velocity does not document what happens to players still connected to a
     * server it unregisters, which is exactly why [ControlService] refuses to
     * call this while any are.
     */
    public fun deregister(name: String): Boolean

    /** Players connected to the proxy across all backends. A count, and only a count. */
    public fun playerCount(): Int
}

/** One backend in the proxy's routing table. */
public interface BackendHandle {
    public val name: String

    /** `host:port`, from the registration. A backend address — never a player's. */
    public val address: String

    /**
     * The players on this backend right now.
     *
     * A copy, not a view: the caller iterates it while asking each player to move
     * somewhere else, and Velocity does not document whether its own collection
     * is live.
     */
    public fun players(): List<PlayerHandle>
}

/**
 * One connected player.
 *
 * Note what is absent: there is no `disconnect`, no `kick`, no `close`. See the
 * note on [ProxyControl].
 */
public interface PlayerHandle {
    public val username: String

    public val uniqueId: String

    public val remoteAddress: String

    /** Tells the player something before they are moved. `SKILL.md` step 4. */
    public fun notify(message: String)

    /**
     * Asks the proxy to move this player to [destination].
     *
     * Asynchronous because Velocity's is: `Player.createConnectionRequest(server)
     * .connect()` hands back a `CompletableFuture`. Modelling it synchronously
     * here would mean the adapter blocking an HTTP thread on a network handshake
     * and would hide the in-flight state the drain has to be able to observe.
     *
     * A failed request leaves the player exactly where they are. Nothing in this
     * plugin reacts to a failure by disconnecting anybody.
     */
    public fun requestTransfer(destination: BackendHandle): CompletableFuture<TransferResult>
}

/**
 * A backend's `host:port`, parsed once on the way in.
 *
 * Parsed in [ControlService] rather than in the Velocity adapter so that a
 * malformed address is a 400 before anything touches the routing table. The
 * alternative — validating inside `register` — means the check runs after the
 * decision to register has already been made, which is the shape of bug that
 * leaves a registry half-updated.
 *
 * Never resolved here. Velocity resolves a backend address lazily at connect
 * time, and a DNS lookup on the request thread would make registering a backend
 * whose container is still starting fail for a reason that has nothing to do
 * with the registration.
 */
public data class BackendAddress(
    val host: String,
    val port: Int,
) {
    /** The canonical form, and what the read-back reports. IPv6 hosts keep their brackets. */
    override fun toString(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    public companion object {
        public fun parse(raw: String): BackendAddress {
            val trimmed = raw.trim()
            val (host, portText) =
                if (trimmed.startsWith("[")) {
                    // IPv6 literal: [::1]:25565
                    val close = trimmed.indexOf(']')
                    if (close < 0 || trimmed.getOrNull(close + 1) != ':') refuse(raw)
                    trimmed.substring(1, close) to trimmed.substring(close + 2)
                } else {
                    val separator = trimmed.lastIndexOf(':')
                    if (separator <= 0) refuse(raw)
                    trimmed.substring(0, separator) to trimmed.substring(separator + 1)
                }
            if (host.isEmpty()) refuse(raw)
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: refuse(raw)
            return BackendAddress(host, port)
        }

        private fun refuse(raw: String): Nothing =
            throw ControlFailure(
                ControlErrorCode.MALFORMED_REQUEST,
                "`address` must be host:port with a port in 1..65535, found `$raw`",
            )
    }
}

/**
 * How one player's move turned out.
 *
 * These mirror Velocity's `ConnectionRequestBuilder.Status`, collapsed to the
 * four outcomes the drain protocol treats differently. All of them leave the
 * player connected to something.
 */
public enum class TransferResult {
    /** The player is now on the destination. */
    MOVED,

    /** The player was already on the destination. Counts as evacuated. */
    ALREADY_THERE,

    /**
     * The proxy declined: another connection was already in flight, or a plugin
     * denied the destination. Retryable — `:core` re-issues on the next pass.
     */
    REFUSED,

    /**
     * The destination did not accept the connection.
     *
     * The drain protocol's answer is to abort and leave everyone where they are,
     * never to disconnect them to make the number go down.
     */
    FAILED,
}

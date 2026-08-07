package mcorch.velocity.control

import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * A proxy, simulated well enough to catch the things a real one would.
 *
 * Held to the *contract* rather than to what [ControlService] happens to do
 * today, because a fake that is more permissive than the real thing validates
 * the caller against something that does not exist. Three clauses in particular
 * are enforced here rather than assumed:
 *
 * - [register] throws if it is called for a name already registered. Velocity
 *   does not document what a second `registerServer` does, so this plugin must
 *   never issue one; a fake that quietly overwrote would let that bug through.
 * - [backend] matches names case-insensitively, as Velocity's `getServer` does.
 *   A seal keyed on the wrong case is a seal that does nothing.
 * - A [FakePlayer] moves between backends only on [TransferResult.MOVED]. Every
 *   other outcome leaves them where they were, so `remaining` and "nobody was
 *   disconnected" are read off real state instead of off a returned status.
 *
 * There is no way to disconnect a player here, because [PlayerHandle] offers
 * none. That is the point — see `TransferNeverKicksTest`.
 */
class FakeProxy : ProxyControl {
    private val registered = LinkedHashMap<String, FakeBackend>()

    /** Side-effect counters. Idempotency is asserted on these, not on what a call returned. */
    var registerCalls: Int = 0
        private set

    var deregisterCalls: Int = 0
        private set

    override fun backends(): List<BackendHandle> = registered.values.toList()

    override fun backend(name: String): BackendHandle? = registered[key(name)]

    override fun register(
        name: String,
        host: String,
        port: Int,
    ) {
        registerCalls++
        check(key(name) !in registered) {
            "registerServer was called twice for `$name`; Velocity does not document what that does"
        }
        registered[key(name)] = FakeBackend(name, if (host.contains(':')) "[$host]:$port" else "$host:$port")
    }

    override fun deregister(name: String): Boolean {
        deregisterCalls++
        return registered.remove(key(name)) != null
    }

    override fun playerCount(): Int = registered.values.sumOf { it.connected.size }

    // --- test helpers ---

    fun add(
        name: String,
        address: String = "10.0.0.1:25565",
    ): FakeBackend = FakeBackend(name, address).also { registered[key(name)] = it }

    fun isRegistered(name: String): Boolean = key(name) in registered

    fun named(name: String): FakeBackend = requireNotNull(registered[key(name)]) { "no backend `$name`" }

    /** Every player the fake knows about, wherever they are. */
    fun everyone(): List<FakePlayer> = registered.values.flatMap { it.connected }

    /**
     * Everything the fake holds, identities included.
     *
     * Only `PlayerIdentityLeakageTest` uses it, as its control assertion: it
     * proves the needles it then searches for are findable at all, so a passing
     * leak assertion cannot be one that was searching for something absent.
     */
    fun revealEverything(): String =
        registered.values.joinToString("\n") { backend ->
            "${backend.name} ${backend.address} " +
                backend.connected.joinToString(",") { "${it.username}/${it.uniqueId}/${it.remoteAddress}" }
        }

    private fun key(name: String): String = name.lowercase(Locale.ROOT)
}

class FakeBackend(
    override val name: String,
    override val address: String,
) : BackendHandle {
    val connected: MutableList<FakePlayer> = mutableListOf()

    override fun players(): List<PlayerHandle> = connected.toList()

    fun join(player: FakePlayer): FakePlayer {
        connected += player
        player.connectedTo = this
        return player
    }
}

/**
 * A connected player, carrying the three things that must never leave the plugin.
 *
 * They are here on purpose. A fake that only held counts would make the leakage
 * test vacuous — there would be nothing in the system for it to find.
 */
class FakePlayer(
    override val username: String,
    override val uniqueId: String,
    override val remoteAddress: String,
) : PlayerHandle {
    /**
     * Where this player is. Never null after they join, and nothing in the plugin
     * can make it null: that is the assertion "a transfer never becomes a kick"
     * is read from.
     */
    var connectedTo: FakeBackend? = null

    val notices: MutableList<String> = mutableListOf()

    var transferRequests: Int = 0
        private set

    /** What the next request answers with. Set per player to script a partial failure. */
    var outcome: TransferResult = TransferResult.MOVED

    /**
     * When set, [requestTransfer] returns this instead of a completed future, so a
     * test can observe the in-flight state before deciding the answer.
     */
    var pending: CompletableFuture<TransferResult>? = null

    /**
     * Makes [notify] or [requestTransfer] throw.
     *
     * Velocity can: `sendMessage` on a component that will not encode for a
     * player's protocol version throws, and the notice is operator-supplied text.
     * A fake that could not throw is a fake that validates the sweep against
     * something more forgiving than a real proxy — and the sweep's whole safety
     * argument is that its tally always reaches `requested`.
     */
    var throwsOnNotify: Boolean = false

    var throwsOnTransfer: Boolean = false

    override fun notify(message: String) {
        if (throwsOnNotify) throw IllegalStateException("this player cannot be sent a message")
        notices += message
    }

    override fun requestTransfer(destination: BackendHandle): CompletableFuture<TransferResult> {
        if (throwsOnTransfer) throw IllegalStateException("this player cannot be asked to move")
        transferRequests++
        val held = pending
        if (held != null) {
            return held.thenApply { result ->
                settle(destination, result)
                result
            }
        }
        settle(destination, outcome)
        return CompletableFuture.completedFuture(outcome)
    }

    private fun settle(
        destination: BackendHandle,
        result: TransferResult,
    ) {
        if (result != TransferResult.MOVED) return
        val target = destination as? FakeBackend ?: return
        connectedTo?.connected?.remove(this)
        target.connected += this
        connectedTo = target
    }
}

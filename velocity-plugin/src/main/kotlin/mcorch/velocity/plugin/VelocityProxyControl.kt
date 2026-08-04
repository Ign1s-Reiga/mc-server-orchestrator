package mcorch.velocity.plugin

import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import mcorch.velocity.control.BackendHandle
import mcorch.velocity.control.PlayerHandle
import mcorch.velocity.control.ProxyControl
import mcorch.velocity.control.TransferResult
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture

/**
 * The translation layer between [ProxyControl] and Velocity, and the only code
 * in this module that names a Velocity type outside the listeners.
 *
 * It is deliberately the thinnest thing that can work, because it is the only
 * part of the plugin that cannot be covered without a live proxy. Every decision
 * — what to seal, whom to move, what to refuse — is made above this file against
 * an interface with tests. What is here is field access, one enum mapping, and
 * two calls.
 *
 * ## The three Velocity behaviours this is written around
 *
 * 1. Velocity does not document what a second `registerServer` for an existing
 *    name does, so [register] is only ever reached after `ControlService` has
 *    checked, under a lock, that the name is absent.
 * 2. Velocity does not document what `unregisterServer` does to players still
 *    connected. `ControlService` refuses to call [deregister] while there are
 *    any, so it does not matter which answer is right.
 * 3. `ConnectionRequestBuilder.connectWithIndication()` applies Velocity's own
 *    error handling to a failed connection, and `fireAndForget()` discards the
 *    result. Only `connect()` is used: it reports what happened and does nothing
 *    to the player on failure. `TransferNeverKicksTest` asserts the other two
 *    never appear in this module.
 */
internal class VelocityProxyControl(
    private val proxy: ProxyServer,
    private val log: Logger,
) : ProxyControl {
    override fun backends(): List<BackendHandle> = proxy.allServers.map { VelocityBackend(proxy, it, log) }

    override fun backend(name: String): BackendHandle? =
        proxy.getServer(name).map { VelocityBackend(proxy, it, log) as BackendHandle }.orElse(null)

    override fun register(
        name: String,
        host: String,
        port: Int,
    ) {
        // Unresolved on purpose. Velocity resolves a backend address when it first
        // connects, so registering a container that has not finished starting — or
        // whose DNS name is not answering yet — must not fail here.
        proxy.registerServer(ServerInfo(name, InetSocketAddress.createUnresolved(host, port)))
    }

    override fun deregister(name: String): Boolean {
        val existing = proxy.getServer(name).orElse(null) ?: return false
        proxy.unregisterServer(existing.serverInfo)
        return true
    }

    override fun playerCount(): Int = proxy.playerCount
}

private class VelocityBackend(
    private val proxy: ProxyServer,
    private val server: RegisteredServer,
    private val log: Logger,
) : BackendHandle {
    override val name: String get() = server.serverInfo.name

    override val address: String
        get() =
            server.serverInfo.address.let { socket ->
                val host = socket.hostString
                if (host.contains(':')) "[$host]:${socket.port}" else "$host:${socket.port}"
            }

    // Copied out immediately: the caller iterates this while asking each player to
    // move off it, and Velocity does not document whether its own collection is a
    // live view of the connections being changed.
    override fun players(): List<PlayerHandle> = server.playersConnected.map { VelocityPlayerHandle(proxy, it, log) }
}

/**
 * One connected player.
 *
 * This class can see a player's name, UUID and address, and it is the last place
 * in the plugin that can. Nothing it exposes is ever written to a response or a
 * log line — see `PlayerIdentityLeakageTest`, which populates a fake with real
 * identities precisely so that the assertion has something to find.
 */
private class VelocityPlayerHandle(
    private val proxy: ProxyServer,
    private val player: Player,
    private val log: Logger,
) : PlayerHandle {
    override val username: String get() = player.username

    override val uniqueId: String get() = player.uniqueId.toString()

    override val remoteAddress: String get() = player.remoteAddress.toString()

    override fun notify(message: String) {
        // Told before they are moved: SKILL.md step 4. A notice whose MiniMessage
        // will not parse must not abort a drain, so it degrades to plain text
        // rather than throwing out of the sweep.
        val component =
            try {
                MiniMessage.miniMessage().deserialize(message)
            } catch (invalid: RuntimeException) {
                // Degraded, not swallowed. A notice that never parses is a `:core`
                // bug, and one nobody would otherwise ever see. The type only: the
                // exception's message quotes the notice, which is operator text this
                // module has not checked for anything.
                log.warn("mcorch transfer notice is not valid MiniMessage type={}", invalid.javaClass.name)
                Component.text(message)
            }
        player.sendMessage(component)
    }

    override fun requestTransfer(destination: BackendHandle): CompletableFuture<TransferResult> {
        val target =
            proxy.getServer(destination.name).orElse(null)
                ?: return CompletableFuture.completedFuture(TransferResult.FAILED)
        // connect(), never connectWithIndication() or fireAndForget(). See the note
        // on VelocityProxyControl. A failed request leaves the player exactly where
        // they are and this method has nothing else to do about it.
        return player
            .createConnectionRequest(target)
            .connect()
            .thenApply { result -> classify(result.status) }
    }

    private fun classify(status: ConnectionRequestBuilder.Status): TransferResult =
        when (status) {
            ConnectionRequestBuilder.Status.SUCCESS -> TransferResult.MOVED

            ConnectionRequestBuilder.Status.ALREADY_CONNECTED -> TransferResult.ALREADY_THERE

            // Both are "not now": another connection is in flight, or a plugin —
            // possibly this one's own seal — denied the destination. Retryable, and
            // the player is still connected to the source.
            ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS -> TransferResult.REFUSED

            ConnectionRequestBuilder.Status.CONNECTION_CANCELLED -> TransferResult.REFUSED

            ConnectionRequestBuilder.Status.SERVER_DISCONNECTED -> TransferResult.FAILED
            // Deliberately exhaustive, with no `else`. A Velocity bump that adds a
            // status breaks this compile, which is the point: a new outcome has to be
            // classified by somebody who has read what it means. Defaulting it would
            // let an unknown status be counted as a settled transfer, and the number
            // a drain waits on is the one that must not be guessed.
        }
}

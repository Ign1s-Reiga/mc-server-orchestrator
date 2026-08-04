package mcorch.velocity.plugin

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import mcorch.velocity.control.AdmissionRegistry
import mcorch.velocity.control.InitialChoice
import mcorch.velocity.control.SealPolicy
import mcorch.velocity.control.SwitchDecision
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.util.Locale

/**
 * Where the seal actually takes effect.
 *
 * Three listeners, each of which reads an event, asks [SealPolicy] one question,
 * and applies the answer. The decisions are in `SealPolicy` because that is
 * testable and this is not.
 *
 * ## None of these disconnects anybody who is already playing
 *
 * That is the property the whole design turns on, and it is worth being explicit
 * about which event does what:
 *
 * - [onChooseInitialServer] only ever *redirects*. It never denies, because a
 *   denial on the login path strands the client on "Connecting to server" until
 *   it times out (PaperMC/Velocity issue 689). When there is nowhere else to
 *   send a joining player, the seal lets them through and counts the fact.
 * - [onServerPreConnect] denies only a switch made by a player who already has a
 *   server to stay on. A denied switch leaves them on it.
 * - [onPreLogin] refuses a *new* connection to the proxy while the proxy itself
 *   is sealed. Refusing to admit somebody is not disconnecting anybody, and it
 *   is the only thing `ProxyDrainSpec`'s seal can mean: a fleet has one front
 *   door, so there is nowhere to transfer the proxy's own players to, and the
 *   drain waits for them to log off rather than pushing them off.
 *
 * Nothing here removes a registration and nothing here closes a connection.
 */
internal class RoutingListener(
    private val proxy: ProxyServer,
    private val admission: AdmissionRegistry,
) {
    /**
     * A joining player's first server, when their first choice is sealed.
     *
     * Fires before [onServerPreConnect] on the login path, which is why that one
     * can afford to allow a sealed target for a player with no current server:
     * the deflection has already had its chance here.
     */
    @Subscribe
    fun onChooseInitialServer(event: PlayerChooseInitialServerEvent) {
        val chosen =
            event.initialServer
                .orElse(null)
                ?.serverInfo
                ?.name
        val candidates = proxy.allServers.map { it.serverInfo.name }.sortedBy { it.lowercase(Locale.ROOT) }
        when (val decision = SealPolicy.onInitialChoice(chosen, candidates, admission)) {
            is InitialChoice.Keep -> {
                // The proxy's own choice admits, or there was no choice to change.
            }

            is InitialChoice.Redirect -> {
                val alternative = proxy.getServer(decision.backend).orElse(null)
                when {
                    // The alternative was deregistered between listing the candidates
                    // and now. The player proceeds to their sealed first choice, so
                    // this is the seal leaking and is counted as one rather than
                    // disappearing into an untaken `ifPresent`.
                    alternative == null -> {
                        if (chosen != null) admission.recordAdmittedWithoutAlternative(chosen)
                    }

                    else -> {
                        event.setInitialServer(alternative)
                        if (chosen != null) admission.recordDeflectedJoin(chosen)
                    }
                }
            }

            is InitialChoice.AdmitAnyway -> {
                if (chosen != null) admission.recordAdmittedWithoutAlternative(chosen)
            }
        }
    }

    /** An in-game switch onto a sealed backend. Denied, which leaves the player where they are. */
    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val result = event.result
        // Another plugin already refused it; there is nothing for the seal to add,
        // and overwriting somebody else's decision is not this plugin's business.
        if (!result.isAllowed) return
        val target = result.server.orElse(event.originalServer) ?: return
        val name = target.serverInfo.name
        when (SealPolicy.onServerSwitch(name, playerIsConnected = event.player.currentServer.isPresent, admission)) {
            SwitchDecision.Allow -> {
                // The target admits. Nothing to do and nothing to report.
            }

            SwitchDecision.AllowSealed -> {
                // Sealed, but this player has no server to be left on, so refusing is
                // the issue-689 strand rather than a safe no-op. Let them through and
                // say so.
                admission.recordAdmittedWithoutAlternative(name)
            }

            SwitchDecision.Refuse -> {
                event.result = ServerPreConnectEvent.ServerResult.denied()
                admission.recordRefusedSwitch(name)
            }
        }
    }

    /** The proxy's own seal: refuse new logins while it is draining. Nobody connected is touched. */
    @Subscribe
    fun onPreLogin(event: PreLoginEvent) {
        if (admission.proxyAdmits()) return
        event.result = PreLoginEvent.PreLoginComponentResult.denied(SEALED_MESSAGE)
        admission.recordRefusedLogin()
    }

    private companion object {
        val SEALED_MESSAGE: Component =
            Component.text("This proxy is shutting down and is not accepting new connections.", NamedTextColor.YELLOW)
    }
}

package mcorch.velocity.plugin

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import mcorch.velocity.control.AdmissionRegistry
import mcorch.velocity.control.ControlAuth
import mcorch.velocity.control.ControlConfig
import mcorch.velocity.control.ControlEndpoint
import mcorch.velocity.control.ControlProtocol
import mcorch.velocity.control.ControlService
import org.slf4j.Logger

/**
 * The plugin the orchestrator mounts into a Velocity container.
 *
 * Velocity has no RCON and no admin socket: everything the drain protocol needs
 * from a proxy exists only in the plugin API, in-process. So the orchestrator
 * ships a process to run in there and talks to it over a small HTTP control
 * channel. What that channel offers, and why it is shaped as it is, is on
 * [ControlProtocol].
 *
 * ## Lifecycle
 *
 * The endpoint binds on [ProxyInitializeEvent] and stops on
 * [ProxyShutdownEvent]. It answers the version handshake from the moment it
 * binds and refuses everything else with `NOT_READY` until initialisation
 * finishes, so `:core` can tell a proxy that is still starting from one that is
 * not there — the two need different responses from the reconcile loop and only
 * one of them is a failure.
 *
 * A failure to bind is fatal to the plugin and is logged as such. It is
 * deliberately *not* fatal to the proxy: taking down a running proxy full of
 * players because its control channel could not start would trade every
 * connected player for a management feature.
 *
 * ## The descriptor
 *
 * Velocity finds this class through `velocity-plugin.json` in the JAR root,
 * which is checked in rather than generated — the upstream generator is a Java
 * annotation processor, and running one over a Kotlin module means adding kapt
 * to produce eleven lines of JSON. [Plugin] is still declared here, from the same
 * constants the JSON is asserted against, so the two cannot say different things.
 */
@Plugin(
    id = ControlProtocol.PLUGIN_ID,
    name = ControlProtocol.PLUGIN_NAME,
    version = ControlProtocol.PLUGIN_VERSION,
    description = "Orchestrator control channel: seal, transfer and deregister backends.",
)
public class VelocityControlPlugin
    @Inject
    constructor(
        private val proxy: ProxyServer,
        private val log: Logger,
    ) {
        private val admission = AdmissionRegistry()
        private val service = ControlService(VelocityProxyControl(proxy, log), admission)
        private var endpoint: ControlEndpoint? = null

        @Subscribe
        public fun onProxyInitialize(event: ProxyInitializeEvent) {
            proxy.eventManager.register(this, RoutingListener(proxy, admission))

            val config =
                try {
                    ControlConfig.fromEnvironment(System::getenv)
                } catch (invalid: IllegalArgumentException) {
                    // Do not swallow, and do not fall back to a default: a proxy
                    // listening somewhere other than where :core looks is a control
                    // endpoint that reads as permanently unreachable.
                    log.error("mcorch control endpoint not started: {}", invalid.message)
                    return
                }
            val auth =
                try {
                    ControlAuth(config.token)
                } catch (invalid: IllegalArgumentException) {
                    log.error("mcorch control endpoint not started: {}", invalid.message)
                    return
                }
            if (!auth.required) {
                // Not a warning. The schema makes `hostPort` require `tokenSecret`, so
                // an endpoint with no token is one that exists only inside the sandbox.
                log.info("mcorch control endpoint has no token: it is reachable only inside this container's network")
            }

            val started = ControlEndpoint(service, auth, config) { message -> log.info(message) }
            try {
                started.start()
                endpoint = started
            } catch (failure: Exception) {
                // The proxy keeps running with players on it. :core observes
                // ControlEndpointStatus.reachable = false and reports it rather than
                // this becoming a restart loop under live traffic.
                log.error(
                    "mcorch control endpoint failed to bind port={} type={}",
                    config.port,
                    failure.javaClass.name,
                )
                return
            }
            service.markReady()
        }

        @Subscribe
        public fun onProxyShutdown(event: ProxyShutdownEvent) {
            endpoint?.stop()
            endpoint = null
        }
    }

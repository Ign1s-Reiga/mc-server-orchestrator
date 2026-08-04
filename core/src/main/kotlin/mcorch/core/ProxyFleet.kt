package mcorch.core

import mcorch.core.proxy.BackendLink
import mcorch.core.proxy.ControlChannel
import mcorch.schema.NodeName
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxyStatus
import mcorch.store.Store
import mcorch.store.StoredServer

/**
 * Which proxy fronts which server, worked out from the fleet.
 *
 * The reference points **proxy → backend**: a `PaperServer` says nothing about a
 * proxy, and a `VelocityProxy` names its backends with a label selector. That is
 * what keeps a backend definition portable between fleets and what keeps the
 * forwarding secret out of it — but it also means the answer cannot be read off
 * either document alone. It is a fleet-level read, and this is the only place that
 * does it.
 */
internal object ProxyFleet {
    /** What the fleet says about the proxies claiming one server. */
    sealed interface Resolution {
        /** Nothing claims it. A standalone server, with no seal and no router. */
        data object Standalone : Resolution

        data class Behind(
            val binding: Binding,
        ) : Resolution

        /**
         * More than one proxy claims it.
         *
         * Surfaced on the *backend's* status rather than refused at parse time,
         * because neither document is wrong on its own and neither parse can see
         * the other.
         */
        data class Conflicted(
            val message: String,
            /** True when they also disagree about the forwarding secret. */
            val secretsDiffer: Boolean,
        ) : Resolution
    }

    /** One proxy, as much of it as the fleet read could see. */
    data class Binding(
        val definition: VelocityProxyDefinition,
        val status: VelocityProxyStatus?,
        /** Every other server the same selector matched, with what is known about each. */
        val siblings: List<Sibling>,
    ) {
        val proxy: ResourceName get() = definition.metadata.name

        /** Coordinates only. See `Reconciler.Pass.forwardingSecret`. */
        val forwardingSecret: SecretRef get() = definition.spec.forwarding.secret
    }

    /** A server behind the same proxy, and everything the destination rule needs. */
    data class Sibling(
        val server: ResourceName,
        /**
         * The port the proxy dials this backend on: the published host port when
         * there is one, and the container port otherwise. The same rule the
         * readiness endpoint follows, so the two cannot disagree about where a
         * server is.
         */
        val port: Int,
        val maxPlayers: Int,
        val online: Int?,
        val ready: Boolean,
        /**
         * Any drain record at all, in flight *or* aborted.
         *
         * `PaperServerStatus.drainInitiated`, never `draining`: the latter is
         * deliberately false in `DRAIN_FAILED` so a drain can be resumed from
         * there, which makes a server parked on a retryable abort read as a
         * perfectly good destination moments before it tries to stop again.
         */
        val drainInitiated: Boolean,
    )

    /**
     * Reads the fleet and decides which proxy, if any, claims [stored].
     *
     * One `listServers` per pass of a backend. That is the same read the resync
     * already does, and it is the cheapest correct answer: caching it would mean a
     * backend enrolled a moment ago is reconciled against a fleet that predates it,
     * and the *first* thing that goes wrong then is the forwarding secret.
     */
    suspend fun resolve(
        store: Store,
        stored: StoredServer,
    ): Resolution {
        val definition = stored.definition.definition as? PaperServerDefinition ?: return Resolution.Standalone
        val labels = definition.metadata.labels
        val fleet = store.listServers()
        val claiming =
            fleet
                .mapNotNull { it.definition.definition as? VelocityProxyDefinition to it }
                .mapNotNull { (proxy, row) -> proxy?.let { it to row } }
                .filter { (proxy, _) -> proxy.spec.backends.selector.matches(labels) }
                .sortedBy { (proxy, _) -> proxy.metadata.name.value }
        if (claiming.isEmpty()) return Resolution.Standalone
        if (claiming.size > 1) {
            val names = claiming.joinToString(", ") { (proxy, _) -> "`${proxy.metadata.name}`" }
            val secrets = claiming.map { (proxy, _) -> proxy.spec.forwarding.secret }.distinct()
            val differ = secrets.size > 1
            return Resolution.Conflicted(
                message =
                    "this server matches the backend selector of $names. A backend belongs to one proxy: " +
                        if (differ) {
                            "they name different forwarding secrets, so there is no value this container " +
                                "could be created with that both proxies would accept. It is not being " +
                                "created or recreated until one of the selectors stops matching it"
                        } else {
                            "both would route players to it and only one of them would be told to stop " +
                                "during a drain. Narrow one of the selectors"
                        },
                secretsDiffer = differ,
            )
        }
        val (proxy, row) = claiming.first()
        val selector = proxy.spec.backends.selector
        val siblings =
            fleet.mapNotNull { candidate ->
                val backend = candidate.definition.definition as? PaperServerDefinition ?: return@mapNotNull null
                if (!selector.matches(backend.metadata.labels)) return@mapNotNull null
                val status = candidate.status?.status as? PaperServerStatus
                Sibling(
                    server = backend.metadata.name,
                    port = backend.spec.network.hostPort ?: backend.spec.network.port,
                    maxPlayers = backend.spec.maxPlayers,
                    online = status?.players?.online,
                    ready = status?.ready == true,
                    // A tombstoned definition is a server on its way out even
                    // before its drain record exists, and handing players to one is
                    // handing them to a container about to be removed.
                    drainInitiated = status?.drainInitiated == true || candidate.definition.terminating,
                )
            }
        return Resolution.Behind(
            Binding(
                definition = proxy,
                status = row.status?.status as? VelocityProxyStatus,
                siblings = siblings,
            ),
        )
    }

    /**
     * Builds the control-channel link a backend's drain talks to its proxy
     * through, or null when the proxy is not somewhere it can be reached.
     *
     * Null is the honest answer for a proxy that has never been observed, or whose
     * node is no longer registered: the drain then behaves exactly as a standalone
     * one — it blocks on players rather than transferring them — which is the
     * correct degradation. It must **not** be a failure of the backend: the proxy
     * being down is the proxy's problem, and a backend that refused to drain
     * because of it would be undeletable for as long as the proxy was.
     */
    suspend fun linkFor(
        binding: Binding,
        backend: ResourceName,
        registry: NodeRegistry,
        scheduler: Scheduler,
        backendNode: NodeName,
        config: ReconcilerConfig,
    ): BackendLink? {
        val runtime = binding.status?.runtime ?: return null
        val proxyNode = registry.node(runtime.node) ?: return null
        val handle =
            WorkloadHandle(
                node = runtime.node,
                sandboxId = runtime.sandboxId,
                containerId = runtime.containerId,
            )
        val spec = binding.definition.spec
        val siblings = binding.siblings
        val candidates =
            siblings
                .filter { it.server != backend }
                .map {
                    DestinationCandidate(
                        server = it.server,
                        maxPlayers = it.maxPlayers,
                        online = it.online,
                        // Whether the proxy is still routing to it is a fact about
                        // this proxy, and the only thing that stops routing is a
                        // drain — which `drainInitiated` already carries. Reading
                        // it back over the wire would be a second RPC per pass for
                        // an answer the fleet already has.
                        admitsNewPlayers = !it.drainInitiated,
                        drainInitiated = it.drainInitiated,
                        ready = it.ready,
                    )
                }
        return BackendLink(
            backend = backend,
            proxy = binding.proxy,
            // The address the *proxy* dials, which is the backend's node and the
            // port it publishes. Never a player's address, and never logged.
            address = "${backendNode.value}:${backendPort(binding, backend)}",
            channel =
                ControlChannel(
                    node = proxyNode,
                    handle = handle,
                    port = spec.control.port,
                    token = spec.control.tokenSecret,
                    timeout = spec.backends.drain.sealTimeout,
                ),
            scheduler = scheduler,
            candidates = candidates,
            preference = spec.backends.fallback,
            // Sealing the last admitting backend leaves the login-path seal with
            // nowhere to deflect a joining player to, so it admits them anyway and
            // a fleet-wide drain can never reach zero. Derived from stored state
            // rather than read back, so it costs nothing and errs safe: in doubt it
            // seals the proxy, which only refuses logins and disconnects nobody.
            lastAdmitting = siblings.none { it.server != backend && !it.drainInitiated },
        )
    }

    /**
     * The port the proxy dials a backend on, from the fleet read.
     *
     * A backend that is not in its own proxy's sibling list cannot happen — the
     * selector that produced the list is the one that matched it — so this falls
     * back to Velocity's default rather than throwing, because a drain must not
     * die on an impossible lookup.
     */
    private fun backendPort(
        binding: Binding,
        backend: ResourceName,
    ): Int = binding.siblings.firstOrNull { it.server == backend }?.port ?: DEFAULT_BACKEND_PORT

    /** Velocity's default backend port, for a server the fleet read could not describe. */
    private const val DEFAULT_BACKEND_PORT = 25565
}

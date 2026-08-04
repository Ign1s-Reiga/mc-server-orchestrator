package mcorch.schema.yaml

import mcorch.schema.BackendDrainSpec
import mcorch.schema.BackendSelector
import mcorch.schema.BackendsSpec
import mcorch.schema.ControlEndpointSpec
import mcorch.schema.CpuQuantity
import mcorch.schema.DrainPolicy
import mcorch.schema.ForwardingMode
import mcorch.schema.ForwardingSpec
import mcorch.schema.HeapSpec
import mcorch.schema.ImageRef
import mcorch.schema.JvmHeapPolicy
import mcorch.schema.LabelSyntax
import mcorch.schema.NodeName
import mcorch.schema.ObjectMetadata
import mcorch.schema.PlacementSpec
import mcorch.schema.ProxyDrainSpec
import mcorch.schema.ProxyLifecycleSpec
import mcorch.schema.ProxyNetworkSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SpecInvariants
import mcorch.schema.VelocityProxyDefaults
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxySpec
import org.snakeyaml.engine.v2.nodes.Node
import kotlin.time.Duration.Companion.seconds

/**
 * Reads `mcorch.dev/v1alpha1` `VelocityProxy` documents.
 *
 * Same contract as [PaperServerReader]: every optional field is resolved to its
 * default here, nothing is constructed while violations are outstanding, and the
 * reconciler receives a spec with nothing left to infer.
 *
 * Two rules are worth knowing before reading the code.
 *
 * - **No field here can hold secret material.** The forwarding secret and the
 *   optional control token are read through [MappingReader.secretRef], which
 *   refuses a scalar with a message naming the secret store.
 * - **The selector cannot be fully validated.** It matches against definitions
 *   this parse never sees, so "matches nothing" is an observation, not a
 *   violation. "Matches everything" — an empty `matchLabels` — is a violation,
 *   because that one is visible from here.
 */
internal class VelocityProxyReader(
    private val sink: ViolationSink,
) {
    fun read(
        apiVersion: SchemaVersion,
        metadata: ObjectMetadata,
        specNode: Node,
    ): VelocityProxyDefinition? {
        val before = sink.size
        val spec = MappingReader.of("spec", specNode, sink) ?: return null

        val image = spec.value("image", required = true, parse = ImageRef::parse)
        val resources = spec.mapping("resources", required = true)?.let(::readResources)
        val forwarding = readForwarding(spec.mapping("forwarding", required = true))
        val backends = readBackends(spec.mapping("backends", required = true))
        val network = spec.mapping("network")?.let(::readNetwork) ?: ProxyNetworkSpec()
        val control = readControl(spec.mapping("control"), network)
        val maxPlayers =
            spec.int(
                "maxPlayers",
                default = VelocityProxyDefaults.MAX_PLAYERS,
                min = 1,
                max = VelocityProxyDefaults.MAX_PLAYERS_LIMIT,
            )
        val lifecycle = spec.mapping("lifecycle")?.let(::readLifecycle) ?: ProxyLifecycleSpec()
        val placement = spec.mapping("placement")?.let(::readPlacement) ?: PlacementSpec()
        spec.done()

        if (sink.size > before) return null
        return VelocityProxyDefinition(
            apiVersion = apiVersion,
            metadata = metadata,
            spec =
                VelocityProxySpec(
                    image = image ?: return null,
                    resources = resources ?: return null,
                    forwarding = forwarding ?: return null,
                    backends = backends ?: return null,
                    control = control,
                    maxPlayers = maxPlayers ?: VelocityProxyDefaults.MAX_PLAYERS,
                    network = network,
                    lifecycle = lifecycle,
                    placement = placement,
                ),
        )
    }

    /**
     * The proxy's JVM lives under the same headroom rule as a server's.
     *
     * Velocity is a Netty application with a large direct-buffer appetite, and a
     * proxy killed by the runtime for exceeding its memory limit disconnects
     * every player in the fleet at once — so this is if anything the less
     * forgiving of the two cases, not a lighter one.
     */
    private fun readResources(reader: MappingReader): ResourceSpec? {
        val memory =
            reader.memory(
                "memory",
                required = true,
                min = VelocityProxyDefaults.MIN_CONTAINER_MEMORY,
                max = VelocityProxyDefaults.MAX_CONTAINER_MEMORY,
            )
        val cpu = reader.value("cpu", parse = CpuQuantity::parse)
        if (cpu != null && cpu.millicores > VelocityProxyDefaults.MAX_CPU_MILLICORES) {
            reader.violation(
                "cpu",
                "must be at most ${VelocityProxyDefaults.MAX_CPU_MILLICORES / 1000} cores, found ${cpu.render()}",
            )
        }
        val heap = reader.mapping("heap")
        val declaredMax = heap?.memory("max", min = VelocityProxyDefaults.MIN_HEAP)
        val declaredMin = heap?.memory("min", min = VelocityProxyDefaults.MIN_HEAP)
        heap?.done()
        reader.done()

        if (memory == null) return null
        val max = declaredMax ?: JvmHeapPolicy.defaultMaxHeap(memory)
        val min = declaredMin ?: max
        if (min > max) {
            heap?.violation("min", "must not exceed heap.max (${max.render()}), found ${min.render()}")
            return null
        }
        val problem = SpecInvariants.heapProblem(max, memory)
        if (problem != null) {
            if (heap != null) {
                heap.violation("max", problem)
            } else {
                reader.violation("heap", problem)
            }
            return null
        }
        return ResourceSpec(memory = memory, heap = HeapSpec(max = max, min = min), cpu = cpu)
    }

    private fun readForwarding(reader: MappingReader?): ForwardingSpec? {
        if (reader == null) return null
        val mode =
            reader.enum(
                "mode",
                default = ForwardingMode.MODERN,
                supported = ForwardingMode.supported(),
                lookup = ForwardingMode::fromWire,
            )
        val secret = reader.secretRef("secret", required = true)
        reader.done()
        return ForwardingSpec(secret = secret ?: return null, mode = mode ?: ForwardingMode.MODERN)
    }

    private fun readBackends(reader: MappingReader?): BackendsSpec? {
        if (reader == null) return null
        val selector = readSelector(reader.mapping("selector", required = true))
        val fallback =
            reader.valueList(
                "fallback",
                max = VelocityProxyDefaults.MAX_FALLBACKS,
                parse = ResourceName::of,
            )
        val drain = reader.mapping("drain")?.let(::readBackendDrain) ?: BackendDrainSpec()
        reader.done()
        return BackendsSpec(selector = selector ?: return null, fallback = fallback, drain = drain)
    }

    private fun readSelector(reader: MappingReader?): BackendSelector? {
        if (reader == null) return null
        val labels =
            reader.stringMap(
                "matchLabels",
                keyProblem = LabelSyntax::keyProblem,
                valueProblem = LabelSyntax::valueProblem,
            )
        val declared = reader.isPresent("matchLabels")
        reader.done()

        if (labels.isEmpty()) {
            reader.violation(
                "matchLabels",
                if (declared) {
                    "must not be empty: an empty selector matches every server in the fleet, which would enrol " +
                        "servers this proxy was never meant to front and would hand them its forwarding secret. " +
                        "Name at least one label a backend has to carry"
                } else {
                    "is required: a proxy declares its backends by label, and there is no default set"
                },
            )
            return null
        }
        if (labels.size > VelocityProxyDefaults.MAX_MATCH_LABELS) {
            reader.violation(
                "matchLabels",
                "must have at most ${VelocityProxyDefaults.MAX_MATCH_LABELS} entries, found ${labels.size}",
            )
            return null
        }
        return BackendSelector(labels)
    }

    private fun readBackendDrain(reader: MappingReader): BackendDrainSpec {
        val seal = reader.handshakeTimeout("sealTimeout", VelocityProxyDefaults.SEAL_TIMEOUT)
        val destination = reader.handshakeTimeout("destinationTimeout", VelocityProxyDefaults.DESTINATION_TIMEOUT)
        val deregister = reader.handshakeTimeout("deregisterTimeout", VelocityProxyDefaults.DEREGISTER_TIMEOUT)
        reader.done()
        return BackendDrainSpec(
            sealTimeout = seal,
            destinationTimeout = destination,
            deregisterTimeout = deregister,
        )
    }

    private fun readNetwork(reader: MappingReader): ProxyNetworkSpec {
        val port = reader.port("port", default = VelocityProxyDefaults.PLAYER_PORT) ?: VelocityProxyDefaults.PLAYER_PORT
        val hostPort = reader.port("hostPort")
        reader.done()
        return ProxyNetworkSpec(port = port, hostPort = hostPort)
    }

    /**
     * The control endpoint, and the one pairing rule on it.
     *
     * Unpublished it is reachable only from inside the sandbox, through the node,
     * and needs no credential. Published on a host port it is a control plane
     * that can seal every backend and move every player in the fleet, so a token
     * stops being optional. Omission therefore stays the safe answer either way,
     * and the unsafe combination is the one that cannot be spelled.
     */
    private fun readControl(
        reader: MappingReader?,
        network: ProxyNetworkSpec,
    ): ControlEndpointSpec {
        if (reader == null) return ControlEndpointSpec()

        val port =
            reader.port("port", default = VelocityProxyDefaults.CONTROL_PORT) ?: VelocityProxyDefaults.CONTROL_PORT
        val hostPort = reader.port("hostPort")
        val tokenSecret = reader.secretRef("tokenSecret")
        reader.done()

        val candidate = ControlEndpointSpec(port = port, hostPort = hostPort, tokenSecret = tokenSecret)
        if (port == network.port) {
            reader.violation(
                "port",
                "must differ from spec.network.port, both are $port: the control endpoint would otherwise share " +
                    "a listener with the port players connect to",
            )
            return ControlEndpointSpec()
        }
        if (hostPort != null && hostPort == network.hostPort) {
            reader.violation("hostPort", "must differ from spec.network.hostPort, both are $hostPort")
            return ControlEndpointSpec()
        }
        if (hostPort != null && tokenSecret == null) {
            reader.violation(
                "tokenSecret",
                "is required when spec.control.hostPort is set: a control endpoint published off the sandbox can " +
                    "seal backends and move every player in the fleet, so it is authenticated. The token is " +
                    "named in the secret store, it is never written in a definition",
            )
            return ControlEndpointSpec()
        }
        return candidate
    }

    private fun readLifecycle(reader: MappingReader): ProxyLifecycleSpec {
        val drain = reader.mapping("drain")?.let(::readProxyDrain) ?: ProxyDrainSpec()
        val startupTimeout =
            reader.duration(
                "startupTimeout",
                default = VelocityProxyDefaults.STARTUP_TIMEOUT,
                min = 1.seconds,
                max = VelocityProxyDefaults.MAX_TIMEOUT,
            ) ?: VelocityProxyDefaults.STARTUP_TIMEOUT
        val stopGracePeriod =
            reader.duration(
                "stopGracePeriod",
                default = VelocityProxyDefaults.STOP_GRACE_PERIOD,
                min = 1.seconds,
                max = VelocityProxyDefaults.MAX_TIMEOUT,
            ) ?: VelocityProxyDefaults.STOP_GRACE_PERIOD
        reader.done()
        return ProxyLifecycleSpec(
            drain = drain,
            stopGracePeriod = stopGracePeriod,
            startupTimeout = startupTimeout,
        )
    }

    private fun readProxyDrain(reader: MappingReader): ProxyDrainSpec {
        val policy =
            reader.enum(
                "policy",
                default = DrainPolicy.WAIT_FOR_ZERO_PLAYERS,
                supported = DrainPolicy.supported(),
                lookup = DrainPolicy::fromWire,
            ) ?: DrainPolicy.WAIT_FOR_ZERO_PLAYERS
        val seal = reader.handshakeTimeout("sealTimeout", VelocityProxyDefaults.SEAL_TIMEOUT)
        reader.done()
        return ProxyDrainSpec(policy = policy, sealTimeout = seal)
    }

    private fun readPlacement(reader: MappingReader): PlacementSpec {
        val node = reader.value("node", parse = NodeName::of)
        reader.done()
        return PlacementSpec(node = node)
    }
}

/** Every proxy-side handshake timeout is bounded the same way. Stated once so they cannot drift. */
private fun MappingReader.handshakeTimeout(
    name: String,
    default: kotlin.time.Duration,
): kotlin.time.Duration =
    duration(
        name,
        default = default,
        min = 1.seconds,
        max = VelocityProxyDefaults.MAX_TIMEOUT,
    ) ?: default

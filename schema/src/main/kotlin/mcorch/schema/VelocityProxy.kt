package mcorch.schema

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A Velocity proxy: the front door players connect to, and the counterparty the
 * drain protocol has never had.
 *
 * Three steps of `drain-protocol` — stop new joins, transfer players, deregister
 * the backend — are things only a proxy can do. Until this kind existed they
 * were recorded-but-empty states. What an operator declares here is therefore
 * not just "run a proxy": it is the configuration those three steps run against.
 *
 * ## Backends are declared here, not there
 *
 * A [PaperServer][PaperServerDefinition] does not know it is behind a proxy and
 * [PaperServerSpec] says nothing about one. This spec names its backends with a
 * label selector ([BackendsSpec.selector]), so the reference points proxy →
 * backend and never the other way. That is what keeps a backend definition
 * portable between fleets and what keeps the forwarding secret out of it — see
 * [ForwardingSpec].
 *
 * ## It holds no world
 *
 * There is no `storage` block, on purpose, and [VelocityProxySpec.holdsWorldData]
 * is a constant `false`. A proxy that could be declared persistent would be a
 * proxy the drain protocol has to flush before stopping, and a proxy has no
 * `save-all` to confirm — so it would become a container the orchestrator could
 * never stop. See [VelocityProxySpec.holdsWorldData].
 */
public data class VelocityProxyDefinition(
    override val apiVersion: SchemaVersion,
    override val metadata: ObjectMetadata,
    override val spec: VelocityProxySpec,
) : ServerDefinition {
    override val kind: ServerKind get() = ServerKind.VELOCITY_PROXY
}

/** Defaults in one place, so the parser, the tests and the reconciler cannot drift apart. */
public object VelocityProxyDefaults {
    /** Velocity's own default listener. This is the port players type into their client. */
    public const val PLAYER_PORT: Int = 25577

    /**
     * The plugin's control endpoint inside the sandbox. Deliberately nowhere near
     * a Minecraft port: nothing a player speaks reaches it.
     */
    public const val CONTROL_PORT: Int = 8375

    public const val MAX_PLAYERS: Int = 500

    /** Proxy-side drain handshakes, from `drain-protocol/references/state-machine.md`. */
    public val SEAL_TIMEOUT: Duration = 10.seconds
    public val DESTINATION_TIMEOUT: Duration = 30.seconds
    public val DEREGISTER_TIMEOUT: Duration = 10.seconds

    public val STARTUP_TIMEOUT: Duration = 2.minutes

    /**
     * A proxy holds nothing that has to be flushed, so this is not a save window —
     * it is how long Velocity gets to close its listener and its backend
     * connections cleanly after a drain has already emptied it.
     */
    public val STOP_GRACE_PERIOD: Duration = 60.seconds

    /** Upper bound for any timeout here. Longer than this is a stuck loop, not a slow proxy. */
    public val MAX_TIMEOUT: Duration = 1.hours

    /**
     * Below this the JVM heap left after [JvmHeapPolicy] headroom is not a heap
     * Velocity can run in. It is the same floor as a Paper server for the same
     * reason: the headroom policy has a 512Mi minimum.
     */
    public val MIN_CONTAINER_MEMORY: MemoryQuantity = memory(1L * MemoryQuantity.GIB)
    public val MAX_CONTAINER_MEMORY: MemoryQuantity = memory(64L * MemoryQuantity.GIB)
    public val MIN_HEAP: MemoryQuantity = memory(256L * MemoryQuantity.MIB)

    public const val MAX_PLAYERS_LIMIT: Int = 100_000
    public const val MAX_CPU_MILLICORES: Int = 64_000

    /** Bounds on the list shapes, so a pathological document is rejected rather than reconciled. */
    public const val MAX_MATCH_LABELS: Int = 16
    public const val MAX_FALLBACKS: Int = 16
}

private fun memory(bytes: Long): MemoryQuantity =
    MemoryQuantity.ofBytes(bytes).getOrElse { error("built-in memory constant is invalid: ${it.message}") }

/**
 * How the proxy tells a backend who a connecting player really is.
 *
 * ## There is one mode and there will be one mode
 *
 * `modern` is the only value and it is the default. Legacy BungeeCord forwarding
 * sends the player's identity as an unauthenticated header, so any process that
 * can open a socket to a backend can claim to be anybody; `none` gives every
 * player the proxy's own address. Both are expressible in Velocity and neither
 * is expressible here. The enum exists rather than the field being dropped
 * because it is the honest place to reject the other two by name — an operator
 * who writes `mode: bungeecord` gets told why, instead of getting an
 * "unknown field".
 *
 * ## The secret is a reference, and it is the only copy of the coordinate
 *
 * [secret] names an entry in the secret store. There is no field in this module
 * that can hold the material, and `forwardingSecret:` written as a literal
 * anywhere in a document is rejected with a message pointing at the secret store
 * (see the inline-secret rule in the parser).
 *
 * Modern forwarding needs the *same* value on every backend. That does not mean
 * the backend declares it: a [PaperServer][PaperServerDefinition] matched by
 * [BackendsSpec.selector] is handed this same [SecretRef] by the reconciler,
 * resolved from the store at container-create time and written into the
 * container, never into a spec, a stored row, an API response or a log line. So
 * the coordinate exists exactly once — here — the material exists exactly once —
 * in the secret store — and the backend spec still says nothing about a proxy.
 */
public data class ForwardingSpec(
    val secret: SecretRef,
    val mode: ForwardingMode = ForwardingMode.MODERN,
)

/** The forwarding modes this orchestrator will run. There is one. See [ForwardingSpec]. */
public enum class ForwardingMode(
    public val wireValue: String,
) {
    MODERN("modern"),
    ;

    override fun toString(): String = wireValue

    public companion object {
        public fun fromWire(raw: String): ForwardingMode? = entries.firstOrNull { it.wireValue == raw }

        public fun supported(): List<String> = entries.map { it.wireValue }
    }
}

/**
 * Which servers this proxy fronts, and how it lets go of one.
 *
 * The set is derived from labels rather than listed by name so that a backend
 * added to the fleet joins the proxy by carrying the right label, with no edit
 * to this document. That is also why the selector can be wrong in a way the
 * parser cannot see: it matches against definitions this parse never looks at.
 * A selector that matches *nothing* is reported on observed status
 * ([BackendRoutingStatus], [ConditionType.BACKENDS_RESOLVED]), not at parse
 * time. A selector that matches *everything* is a parse error — see
 * [BackendSelector].
 */
public data class BackendsSpec(
    val selector: BackendSelector,
    /**
     * Where a player with no server goes, most-preferred first, and the
     * preference order a draining backend's players are offered.
     *
     * Empty is allowed and means "any registered backend"; the reconciler orders
     * by name so the choice is at least deterministic. Naming a server here does
     * not enrol it — it still has to match [selector].
     */
    val fallback: List<ResourceName> = emptyList(),
    val drain: BackendDrainSpec = BackendDrainSpec(),
)

/**
 * The label match that decides what is a backend of this proxy.
 *
 * ## Empty is refused, here and in the parser
 *
 * An empty `matchLabels` matches every object, which would silently enrol the
 * whole fleet — including servers an operator deliberately kept off this proxy,
 * and including a second proxy's backends, which is how two proxies end up
 * fighting over one server's forwarding secret. Kubernetes spells that as
 * "select everything"; here it is a violation, and it is also a constructor
 * `require` so that no other module — not a test fixture, not a decoded row —
 * can build one.
 */
public data class BackendSelector(
    val matchLabels: Map<String, String>,
) {
    init {
        require(matchLabels.isNotEmpty()) {
            "matchLabels must not be empty: an empty selector matches every server in the fleet"
        }
    }

    /** Whether an object's labels satisfy this selector. Every entry must be present and equal. */
    public fun matches(labels: Map<String, String>): Boolean = matchLabels.all { (key, value) -> labels[key] == value }
}

/**
 * How long the proxy gets for its part of a *backend's* drain.
 *
 * These three used to be constants in the reconciler, because nothing could
 * configure them: a standalone Paper server has no proxy, so seal, destination
 * lookup and deregistration had no counterparty to be slow. They live on the
 * proxy rather than on the backend because they measure how long *this proxy*
 * takes to answer, which does not vary per backend — and because
 * [PaperServerSpec] deliberately does not change.
 *
 * The transfer timeout is not here. It scales with the number of players being
 * moved off one server, so it stays where it already is, on that server's
 * `spec.lifecycle.drain.playerTransferTimeout`.
 */
public data class BackendDrainSpec(
    /** Drain step 2: acknowledge that no new player will be routed to this backend. */
    val sealTimeout: Duration = VelocityProxyDefaults.SEAL_TIMEOUT,
    /** Drain step 3: answer with a destination that has capacity, or with nothing. */
    val destinationTimeout: Duration = VelocityProxyDefaults.DESTINATION_TIMEOUT,
    /** Drain step 6: acknowledge the backend has left the routing table. */
    val deregisterTimeout: Duration = VelocityProxyDefaults.DEREGISTER_TIMEOUT,
)

/**
 * Where `:core` reaches the shipped Velocity plugin to seal, transfer and
 * deregister.
 *
 * ## Safe by omission: not published, so no token needed
 *
 * With only [port] set, the endpoint exists inside the sandbox and `:core`
 * reaches it through the [Node][NodeName] abstraction. Setting [hostPort]
 * publishes a control plane that can move every player in the fleet, so
 * [tokenSecret] becomes required — that pairing is checked at parse time, not
 * left to a reconciler and not left to an operator to remember.
 *
 * ## The plugin's protocol version is not pinned here
 *
 * There is a version contract between the plugin and `:core`, and the spec
 * deliberately does not express it. The plugin is built from this repository at
 * the same version as `:core`, so which protocol they speak is a property of the
 * binary pair, not of an operator's declaration. A field here could only ever
 * be set to something `:core` cannot speak — there is no useful value an
 * operator could choose — and it would go stale in every definition on disk on
 * every release, which is the opposite of what a versioned schema is for.
 *
 * It is enforced where it can actually be checked: the plugin reports what it
 * speaks, `:core` compares, and a mismatch surfaces as
 * [ControlEndpointStatus.compatible] `= false` with
 * [FailureReason.PROXY_PLUGIN_INCOMPATIBLE] and
 * [ConditionType.CONTROL_ENDPOINT_READY] `= False`. Observed, not declared.
 */
public data class ControlEndpointSpec(
    val port: Int = VelocityProxyDefaults.CONTROL_PORT,
    val hostPort: Int? = null,
    val tokenSecret: SecretRef? = null,
)

/**
 * The port players connect to, and how it is published.
 *
 * Separate from [NetworkSpec] rather than shared: that type carries [RconSpec],
 * and Velocity has no RCON. Modelling a proxy through a type with an RCON field
 * would leave "enabled" reachable for something that cannot answer it — and
 * `saveConfirmable` is derived from exactly that.
 */
public data class ProxyNetworkSpec(
    val port: Int = VelocityProxyDefaults.PLAYER_PORT,
    val hostPort: Int? = null,
)

/**
 * What has to happen before *this proxy* may be stopped.
 *
 * Shorter than a server's because there is no world: no save timeout, and no
 * rule tying the grace period to one. The grace period is not load-bearing for
 * data here, and pretending otherwise by inventing an invariant would teach the
 * wrong lesson about which grace periods matter.
 */
public data class ProxyLifecycleSpec(
    val drain: ProxyDrainSpec = ProxyDrainSpec(),
    val stopGracePeriod: Duration = VelocityProxyDefaults.STOP_GRACE_PERIOD,
    val startupTimeout: Duration = VelocityProxyDefaults.STARTUP_TIMEOUT,
)

/**
 * Draining the proxy itself.
 *
 * There is nowhere to transfer to — a fleet has one front door — so the drain
 * seals the listener and then waits for the last player to log off. That is why
 * there is no wait timeout here and there will not be one: a timeout on that
 * wait can only be spelled "disconnect them", and the protocol does not kick
 * players to make progress. A proxy drain that is still waiting records no
 * failure at all — [DrainStatus.blocked] with
 * [DrainBlockReason.AWAITING_ZERO_PLAYERS] — so it is retried and never
 * escalated, both by the ordinary rules.
 */
public data class ProxyDrainSpec(
    val policy: DrainPolicy = DrainPolicy.WAIT_FOR_ZERO_PLAYERS,
    /** How long the proxy has to confirm it has stopped accepting new connections. */
    val sealTimeout: Duration = VelocityProxyDefaults.SEAL_TIMEOUT,
)

/**
 * Everything an operator declares about a Velocity proxy. Fully defaulted by the
 * parser: every value here is the one the reconciler must act on.
 */
public data class VelocityProxySpec(
    val image: ImageRef,
    val resources: ResourceSpec,
    val forwarding: ForwardingSpec,
    val backends: BackendsSpec,
    val control: ControlEndpointSpec = ControlEndpointSpec(),
    val maxPlayers: Int = VelocityProxyDefaults.MAX_PLAYERS,
    val network: ProxyNetworkSpec = ProxyNetworkSpec(),
    val lifecycle: ProxyLifecycleSpec = ProxyLifecycleSpec(),
    val placement: PlacementSpec = PlacementSpec(),
) : ServerSpec {
    /**
     * Always false, and a constant rather than a field.
     *
     * `:core` labels a workload with this and a drain reads the label back to
     * decide whether it has to confirm a world save before stopping. A proxy has
     * no world and no `save-all` to confirm, so a proxy that answered `true` here
     * would ask for a save nothing can confirm, get an unconfirmable result,
     * classify it permanent, and become a container the orchestrator can never
     * stop. The safe default for that question is `true`, which is exactly why
     * this kind has to answer it explicitly rather than leave it unset.
     *
     * It is a constant and not a field because there is no `storage` block to
     * make it vary. Adding optional plugin-data persistence later would make it
     * vary, and that is the reason not to add it lightly.
     */
    override val holdsWorldData: Boolean get() = false

    init {
        val problem = SpecInvariants.proxyPortProblem(network, control)
        require(problem == null) { "control.port $problem" }
    }
}

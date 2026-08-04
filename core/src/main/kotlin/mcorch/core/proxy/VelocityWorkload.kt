package mcorch.core.proxy

import mcorch.core.Labels
import mcorch.core.PortRequest
import mcorch.core.ResourceRequest
import mcorch.core.StorageRequest
import mcorch.core.WorkloadSpec
import mcorch.schema.VelocityProxyDefinition
import mcorch.velocity.control.ControlProtocol
import java.security.MessageDigest

/**
 * Turns a validated [VelocityProxyDefinition] into the workload a node runs.
 *
 * Nothing here re-validates: the schema has already defaulted every field and
 * already refused a control port that collides with the player port.
 *
 * ## Three things this has to get right
 *
 * **`Labels.WORLD_DATA` is `false`, and it comes from the spec.** A drain reads
 * that label off the running container to decide whether it has to confirm a world
 * save before stopping, and an *absent* label means "this workload does not say",
 * which `WorkloadContract` reads as `true` on the safe side. A proxy has no world
 * and no `save-all` to confirm, so a proxy whose workload carried no label would
 * ask for a save nothing can answer, get `SaveOutcome.Unconfirmable`, classify it
 * permanent — and become a container **the orchestrator could never stop, ever**.
 *
 * The value is [mcorch.schema.ServerSpec.holdsWorldData] rather than a literal
 * `false` written here. That property is abstract on `ServerSpec` precisely so the
 * compiler asks every kind the question, and re-deriving the answer in the planner
 * would put it back to being a convention.
 *
 * **The plugin JAR is mounted.** Velocity loads plugins from a directory, so the
 * JAR the orchestrator built has to be visible inside the container. Without it
 * the proxy comes up perfectly well and has no control endpoint, which is the
 * failure mode where every backend behind it becomes undrainable.
 *
 * **The forwarding secret is a coordinate.** [WorkloadSpec.secretEnv] carries the
 * reference; the node resolves it at the moment it hands it to the runtime. There
 * is no field in this file that could hold the material and no log line that could
 * print it (CLAUDE.md invariant 4).
 */
internal object VelocityWorkloadPlanner {
    const val PLAYER_PORT_NAME: String = "player"
    const val CONTROL_PORT_NAME: String = "control"

    /**
     * Where the proxy image expects plugins. `itzg/mc-proxy` and the stock
     * Velocity layout both read `/server/plugins`.
     *
     * **Unverified against a real image**, like the Paper image contract beside
     * it. An integration test against real containerd is what turns it from
     * plausible into true, and it is one constant so it is one place to correct.
     */
    const val PLUGIN_DIRECTORY: String = "/server/plugins"

    /** The environment contract of the proxy image. */
    const val FORWARDING_SECRET: String = "VELOCITY_FORWARDING_SECRET"
    const val FORWARDING_MODE: String = "VELOCITY_FORWARDING_MODE"
    const val PLAYER_PORT: String = "VELOCITY_PORT"
    const val MAX_PLAYERS: String = "VELOCITY_MAX_PLAYERS"
    const val CONTROL_PORT: String = "MCORCH_CONTROL_PORT"
    const val INIT_MEMORY: String = "INIT_MEMORY"
    const val MAX_MEMORY: String = "MAX_MEMORY"

    fun plan(definition: VelocityProxyDefinition): WorkloadSpec {
        val spec = definition.spec
        val name = definition.metadata.name
        return WorkloadSpec(
            server = name,
            kind = definition.kind,
            image = spec.image,
            specHash = specHash(definition),
            // A proxy holds no world, so the mount is the plugin directory and
            // nothing else. This is the branch CLAUDE.md invariant 2 exempts by
            // name — "only disposable lobbies and minigame instances may be
            // treated as ephemeral" — and a proxy qualifies for the stronger
            // reason that there is no `storage` block in its schema to ask.
            storage = StorageRequest.Ephemeral(mountPath = PLUGIN_DIRECTORY),
            resources =
                ResourceRequest(
                    memoryBytes = spec.resources.memory.bytes,
                    cpuMillicores = spec.resources.cpu?.millicores,
                ),
            hostname = name.value,
            env =
                buildMap {
                    put(FORWARDING_MODE, spec.forwarding.mode.wireValue)
                    put(PLAYER_PORT, spec.network.port.toString())
                    put(MAX_PLAYERS, spec.maxPlayers.toString())
                    put(CONTROL_PORT, spec.control.port.toString())
                    put(INIT_MEMORY, jvmMemory(spec.resources.heap.min.bytes))
                    put(MAX_MEMORY, jvmMemory(spec.resources.heap.max.bytes))
                },
            // Coordinates, never material.
            secretEnv = mapOf(FORWARDING_SECRET to spec.forwarding.secret),
            ports =
                buildList {
                    add(
                        PortRequest(
                            name = PLAYER_PORT_NAME,
                            containerPort = spec.network.port,
                            hostPort = spec.network.hostPort,
                        ),
                    )
                    add(
                        PortRequest(
                            name = CONTROL_PORT_NAME,
                            containerPort = spec.control.port,
                            // Published only when the operator asked for it, and
                            // the schema has already made `tokenSecret` required
                            // in that case. Unpublished is the default because
                            // this port can move every player in the fleet.
                            hostPort = spec.control.hostPort,
                        ),
                    )
                },
            labels =
                Labels.forServer(name, definition.kind) +
                    mapOf(
                        // The load-bearing one. See the class note.
                        Labels.WORLD_DATA to Labels.booleanLabel(spec.holdsWorldData),
                        // Velocity has no RCON and nothing else that replies, so
                        // there is no channel through which a save could ever be
                        // confirmed. Recorded honestly: with `WORLD_DATA` false
                        // the drain never asks, and if it ever did it would get
                        // the truth rather than a guess.
                        Labels.SAVE_CONFIRMABLE to Labels.booleanLabel(false),
                        // What protocol the container was built to speak, on the
                        // container, for the same reason the two above are. A
                        // proxy running an older image is running an older plugin,
                        // whatever the definition says today.
                        CONTROL_PROTOCOL to ControlProtocol.VERSION,
                    ),
        )
    }

    /** The control protocol the mounted plugin JAR speaks, recorded on the workload. */
    const val CONTROL_PROTOCOL: String = "mcorch.dev/control-protocol"

    /**
     * A fingerprint of everything that cannot change without recreating the
     * container.
     *
     * What is absent matters as much as what is present. The proxy-side drain
     * timeouts are not here — raising one must not restart a proxy with players on
     * it — and neither is `backends.fallback`, which is read fresh every pass. The
     * *selector* is not here either: enrolling a new backend must not recreate the
     * proxy, and it does not have to, because registration is a level-triggered
     * call rather than a startup argument.
     *
     * The forwarding secret contributes its **coordinates**, never its material,
     * so rotating the value behind the reference does not appear here and the
     * running proxy keeps the secret it was created with until something else
     * recreates it. That is the same rule the Paper planner follows for RCON, and
     * it is what stops a rotation restarting the whole fleet at once.
     */
    fun specHash(definition: VelocityProxyDefinition): String {
        val spec = definition.spec
        val canonical =
            buildList {
                add("kind=${definition.kind.wireValue}")
                add("image=${spec.image.canonical}")
                add("memory=${spec.resources.memory.bytes}")
                add("cpu=${spec.resources.cpu?.millicores ?: "unset"}")
                add("heap.max=${spec.resources.heap.max.bytes}")
                add("heap.min=${spec.resources.heap.min.bytes}")
                add("network.port=${spec.network.port}")
                add("network.hostPort=${spec.network.hostPort ?: "none"}")
                add("control.port=${spec.control.port}")
                add("control.hostPort=${spec.control.hostPort ?: "none"}")
                add("control.token=${spec.control.tokenSecret?.let { "${it.name}/${it.key}" } ?: "none"}")
                add("forwarding.mode=${spec.forwarding.mode.wireValue}")
                add("forwarding.secret=${spec.forwarding.secret.name}/${spec.forwarding.secret.key}")
                add("maxPlayers=${spec.maxPlayers}")
                add("plugin.protocol=${ControlProtocol.VERSION}")
            }.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    private const val HASH_BYTES = 16

    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L
    private const val KIB = 1024L

    /** `6G`, not `6Gi`. The numbers are identical — the JVM's suffixes are already binary — but `-Xmx6Gi` does not parse. */
    fun jvmMemory(bytes: Long): String =
        when {
            bytes % GIB == 0L -> "${bytes / GIB}G"
            bytes % MIB == 0L -> "${bytes / MIB}M"
            bytes % KIB == 0L -> "${bytes / KIB}K"
            else -> bytes.toString()
        }
}

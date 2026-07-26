package mcorch.core.paper

import mcorch.core.Labels
import mcorch.core.PortRequest
import mcorch.core.ResourceRequest
import mcorch.core.StorageRequest
import mcorch.core.WorkloadSpec
import mcorch.schema.MemoryQuantity
import mcorch.schema.PaperServerDefinition
import mcorch.schema.RconSpec
import mcorch.schema.StorageSpec
import java.security.MessageDigest

/**
 * Turns a validated [PaperServerDefinition] into the workload a node runs.
 *
 * Nothing here re-validates or re-derives: the schema has already defaulted
 * every field, already proved the JVM heap leaves headroom under the container
 * memory limit, and already proved the stop grace period exceeds the save
 * timeout. Repeating those checks would mean two places to get them wrong.
 */
internal object PaperWorkloadPlanner {
    /** The port a definition's game traffic uses, and the name it is known by. */
    const val GAME_PORT_NAME: String = "game"
    const val RCON_PORT_NAME: String = "rcon"

    fun plan(definition: PaperServerDefinition): WorkloadSpec {
        val spec = definition.spec
        val name = definition.metadata.name
        val ports =
            buildList {
                add(
                    PortRequest(
                        name = GAME_PORT_NAME,
                        containerPort = spec.network.port,
                        hostPort = spec.network.hostPort,
                    ),
                )
                val rcon = spec.network.rcon
                if (rcon is RconSpec.Enabled) {
                    // Deliberately not published on the host: RCON is a remote
                    // console with full server authority, and the only thing
                    // that needs it is an exec from inside the sandbox.
                    add(PortRequest(name = RCON_PORT_NAME, containerPort = rcon.port))
                }
            }

        val storage =
            when (val declared = spec.storage) {
                is StorageSpec.Persistent -> {
                    StorageRequest.Persistent(
                        volume = declared.volume.name,
                        mountPath = declared.mountPath,
                    )
                }

                // The only branch allowed to skip a volume, and only because
                // the operator asked for `ephemeral` by name.
                is StorageSpec.Ephemeral -> {
                    StorageRequest.Ephemeral(mountPath = declared.mountPath)
                }
            }

        val environment = PaperImageContract.environment(definition)
        val secretEnvironment =
            when (val rcon = spec.network.rcon) {
                is RconSpec.Enabled -> mapOf(PaperImageContract.RCON_PASSWORD to rcon.passwordSecret)
                RconSpec.Disabled -> emptyMap()
            }

        return WorkloadSpec(
            server = name,
            kind = definition.kind,
            image = spec.image,
            specHash = specHash(definition),
            storage = storage,
            resources =
                ResourceRequest(
                    memoryBytes = spec.resources.memory.bytes,
                    cpuMillicores = spec.resources.cpu?.millicores,
                ),
            hostname = name.value,
            env = environment,
            secretEnv = secretEnvironment,
            ports = ports,
            labels = Labels.forServer(name, definition.kind),
        )
    }

    /**
     * A fingerprint of everything that cannot be changed without recreating the
     * container.
     *
     * What is deliberately *absent* matters more than what is present. The
     * lifecycle timeouts are not in here: raising a save timeout must not
     * restart a server with players on it, and the loop reads those fresh from
     * the definition every pass anyway. Neither is `placement`, which is the
     * scheduler's input rather than the container's shape.
     *
     * The RCON secret contributes its *coordinates*, never its material — so
     * rotating the value behind the reference does not show up here, and the
     * running container keeps the password it was created with until something
     * else recreates it.
     */
    fun specHash(definition: PaperServerDefinition): String {
        val spec = definition.spec
        val rcon = spec.network.rcon
        val canonical =
            buildList {
                add("kind=${definition.kind.wireValue}")
                add("image=${spec.image.canonical}")
                add("paper.version=${spec.paper.minecraftVersion}")
                add("paper.build=${spec.paper.build ?: "latest"}")
                add("memory=${spec.resources.memory.bytes}")
                add("cpu=${spec.resources.cpu?.millicores ?: "unset"}")
                add("heap.max=${spec.resources.heap.max.bytes}")
                add("heap.min=${spec.resources.heap.min.bytes}")
                add("storage.mode=${spec.storage.mode.wireValue}")
                add("storage.mountPath=${spec.storage.mountPath}")
                add(
                    "storage.volume=" +
                        when (val storage = spec.storage) {
                            is StorageSpec.Persistent -> storage.volume.name.value
                            is StorageSpec.Ephemeral -> "none"
                        },
                )
                add("network.port=${spec.network.port}")
                add("network.hostPort=${spec.network.hostPort ?: "none"}")
                when (rcon) {
                    is RconSpec.Enabled -> {
                        add("rcon.port=${rcon.port}")
                        add("rcon.secret=${rcon.passwordSecret.name}/${rcon.passwordSecret.key}")
                    }

                    RconSpec.Disabled -> {
                        add("rcon=disabled")
                    }
                }
                add("maxPlayers=${spec.maxPlayers}")
            }.joinToString("\n")

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        // Half a SHA-256 is 128 bits, which is plenty to tell two definitions
        // apart, and it fits inside a runtime label value with room to spare.
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    private const val HASH_BYTES = 16
}

/**
 * The environment contract of the server image.
 *
 * This is the one place that knows how a Paper server is configured through its
 * image, and it is written against `itzg/minecraft-server` — the image the
 * schema's own examples use. A different image means a different object here,
 * not a change scattered through the reconciler.
 *
 * **Unverified against a real image.** The variable names below come from that
 * image's documented contract but nothing in this module has run one. An
 * integration test against real containerd is what turns them from plausible
 * into true.
 */
internal object PaperImageContract {
    const val EULA: String = "EULA"
    const val TYPE: String = "TYPE"
    const val VERSION: String = "VERSION"
    const val PAPER_BUILD: String = "PAPER_BUILD"
    const val INIT_MEMORY: String = "INIT_MEMORY"
    const val MAX_MEMORY: String = "MAX_MEMORY"
    const val MAX_PLAYERS: String = "MAX_PLAYERS"
    const val SERVER_PORT: String = "SERVER_PORT"
    const val ENABLE_RCON: String = "ENABLE_RCON"
    const val RCON_PORT: String = "RCON_PORT"
    const val RCON_PASSWORD: String = "RCON_PASSWORD"

    fun environment(definition: PaperServerDefinition): Map<String, String> {
        val spec = definition.spec
        return buildMap {
            // The schema refuses to build a spec with `eulaAccepted = false`,
            // so this is a restatement rather than a decision.
            put(EULA, if (spec.eulaAccepted) "TRUE" else "FALSE")
            put(TYPE, "PAPER")
            put(VERSION, spec.paper.minecraftVersion.value)
            spec.paper.build?.let { put(PAPER_BUILD, it.toString()) }

            // -Xms / -Xmx. The schema has already proved these leave headroom
            // under the container memory limit; re-deriving them here is how
            // the two drift apart.
            put(INIT_MEMORY, jvmMemory(spec.resources.heap.min))
            put(MAX_MEMORY, jvmMemory(spec.resources.heap.max))

            put(MAX_PLAYERS, spec.maxPlayers.toString())
            put(SERVER_PORT, spec.network.port.toString())

            when (val rcon = spec.network.rcon) {
                is RconSpec.Enabled -> {
                    put(ENABLE_RCON, "true")
                    put(RCON_PORT, rcon.port.toString())
                    // RCON_PASSWORD is deliberately absent: it is carried as a
                    // secret reference and resolved by the node at the moment
                    // it is handed to the runtime.
                }

                RconSpec.Disabled -> {
                    put(ENABLE_RCON, "false")
                }
            }
        }
    }

    /**
     * Renders memory the way a JVM reads it (`6G`, `512M`), not the way the
     * schema renders it (`6Gi`). The JVM's suffixes are already binary, so the
     * numbers are identical — but `-Xmx6Gi` does not parse.
     */
    fun jvmMemory(quantity: MemoryQuantity): String {
        val bytes = quantity.bytes
        return when {
            bytes % MemoryQuantity.GIB == 0L -> "${bytes / MemoryQuantity.GIB}G"
            bytes % MemoryQuantity.MIB == 0L -> "${bytes / MemoryQuantity.MIB}M"
            bytes % MemoryQuantity.KIB == 0L -> "${bytes / MemoryQuantity.KIB}K"
            else -> bytes.toString()
        }
    }
}

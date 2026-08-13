package mcorch.schema.yaml

import mcorch.schema.ConsoleSpec
import mcorch.schema.CpuQuantity
import mcorch.schema.DrainPolicy
import mcorch.schema.DrainSpec
import mcorch.schema.HeapSpec
import mcorch.schema.ImageRef
import mcorch.schema.JvmHeapPolicy
import mcorch.schema.LifecycleSpec
import mcorch.schema.MinecraftVersion
import mcorch.schema.NetworkSpec
import mcorch.schema.NodeName
import mcorch.schema.ObjectMetadata
import mcorch.schema.PaperServerDefaults
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerSpec
import mcorch.schema.PaperVersionSpec
import mcorch.schema.PlacementSpec
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SecretRef
import mcorch.schema.SpecInvariants
import mcorch.schema.StorageMode
import mcorch.schema.StorageSpec
import mcorch.schema.Tier
import mcorch.schema.VolumeSpec
import org.snakeyaml.engine.v2.nodes.Node
import kotlin.time.Duration.Companion.seconds

/**
 * Reads `mcorch.dev/v1alpha1` `PaperServer` documents.
 *
 * Every field is resolved to a final value here: omitted optional fields get
 * their default, and the defaults are the safe ones — persistent storage,
 * draining on, a stop grace period that outlasts the save. The reconciler
 * receives a spec with nothing left to infer.
 *
 * Nothing is constructed while violations are outstanding, so a definition
 * object never exists in a state that breaks an invariant.
 */
internal class PaperServerReader(
    private val sink: ViolationSink,
) {
    fun read(
        apiVersion: SchemaVersion,
        metadata: ObjectMetadata,
        specNode: Node,
    ): PaperServerDefinition? {
        val before = sink.size
        val spec = MappingReader.of("spec", specNode, sink) ?: return null

        val image = spec.value("image", required = true, parse = ImageRef::parse)
        val paper = spec.mapping("paper", required = true)?.let(::readPaper)
        val eulaAccepted = spec.boolean("eulaAccepted", required = true)
        if (eulaAccepted == false) {
            spec.violation(
                "eulaAccepted",
                "must be true: a Paper server refuses to start until the Minecraft EULA is accepted, " +
                    "so a definition that has not accepted it can never become joinable",
            )
        }
        val maxPlayers =
            spec.int(
                "maxPlayers",
                default = PaperServerDefaults.MAX_PLAYERS,
                min = 1,
                max = PaperServerDefaults.MAX_PLAYERS_LIMIT,
            )
        // Required, because `network.rcon.passwordSecret` is: RCON is standard and
        // its secret cannot be defaulted.
        val network = spec.mapping("network", required = true)?.let(::readNetwork)
        val resources = spec.mapping("resources", required = true)?.let(::readResources)
        val storage = readStorage(spec.mapping("storage"), metadata.name)
        val lifecycle = spec.mapping("lifecycle")?.let(::readLifecycle) ?: LifecycleSpec()
        val placement = spec.mapping("placement")?.let(::readPlacement) ?: PlacementSpec()
        val console = spec.mapping("console")?.let(::readConsole) ?: ConsoleSpec()
        spec.done()

        if (sink.size > before) return null
        return PaperServerDefinition(
            apiVersion = apiVersion,
            metadata = metadata,
            spec =
                PaperServerSpec(
                    image = image ?: return null,
                    paper = paper ?: return null,
                    resources = resources ?: return null,
                    storage = storage,
                    eulaAccepted = eulaAccepted ?: return null,
                    maxPlayers = maxPlayers ?: PaperServerDefaults.MAX_PLAYERS,
                    network = network ?: return null,
                    lifecycle = lifecycle,
                    placement = placement,
                    console = console,
                ),
        )
    }

    private fun readPaper(reader: MappingReader): PaperVersionSpec? {
        val version = reader.value("minecraftVersion", required = true, parse = MinecraftVersion::of)
        val build = reader.int("build", min = 1)
        reader.done()
        return PaperVersionSpec(version ?: return null, build)
    }

    private fun readNetwork(reader: MappingReader): NetworkSpec? {
        val port = reader.port("port", default = PaperServerDefaults.GAME_PORT) ?: PaperServerDefaults.GAME_PORT
        val hostPort = reader.port("hostPort")
        val rcon = reader.mapping("rcon", required = true)?.let { readRcon(it, port) }
        reader.done()
        return NetworkSpec(port = port, hostPort = hostPort, rcon = rcon ?: return null)
    }

    /**
     * `enabled` is deliberately not read.
     *
     * It leaves the key unconsumed, so [MappingReader.done] reports it as an
     * unknown field with a suggestion — which is the right outcome. A definition
     * still carrying `enabled: false` was written by somebody who believed RCON
     * was off; accepting it and turning RCON on would be the orchestrator doing
     * the opposite of what the document says.
     */
    private fun readRcon(
        reader: MappingReader,
        gamePort: Int,
    ): RconSpec? {
        val port = reader.port("port", default = PaperServerDefaults.RCON_PORT) ?: PaperServerDefaults.RCON_PORT
        val secretDeclared = reader.isPresent("passwordSecret")
        val secret = reader.secretRef("passwordSecret")
        reader.done()

        if (port == gamePort) {
            reader.violation("port", "must differ from spec.network.port, both are $gamePort")
            return null
        }
        if (secret == null) {
            if (!secretDeclared) {
                reader.violation(
                    "passwordSecret",
                    "is required: RCON is how a world save is confirmed, so every server has it. " +
                        "The password is named in the secret store, never written in a definition",
                )
            }
            return null
        }
        return RconSpec(port = port, passwordSecret = secret)
    }

    private fun readResources(reader: MappingReader): ResourceSpec? {
        val memory =
            reader.memory(
                "memory",
                required = true,
                min = PaperServerDefaults.MIN_CONTAINER_MEMORY,
                max = PaperServerDefaults.MAX_CONTAINER_MEMORY,
            )
        val cpu = reader.value("cpu", parse = CpuQuantity::parse)
        if (cpu != null && cpu.millicores > PaperServerDefaults.MAX_CPU_MILLICORES) {
            reader.violation(
                "cpu",
                "must be at most ${PaperServerDefaults.MAX_CPU_MILLICORES / 1000} cores, found ${cpu.render()}",
            )
        }
        val heap = reader.mapping("heap")
        val declaredMax = heap?.memory("max", min = PaperServerDefaults.MIN_HEAP)
        val declaredMin = heap?.memory("min", min = PaperServerDefaults.MIN_HEAP)
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

    private fun readStorage(
        reader: MappingReader?,
        definitionName: ResourceName,
    ): StorageSpec {
        if (reader == null) return StorageSpec.Persistent(volume = VolumeSpec(name = definitionName))

        val mode =
            reader.enum(
                "mode",
                default = StorageMode.PERSISTENT,
                supported = StorageMode.supported(),
                lookup = StorageMode::fromWire,
            ) ?: StorageMode.PERSISTENT
        val mountPath = reader.string("mountPath", default = PaperServerDefaults.MOUNT_PATH)
        if (mountPath != null) {
            MountPaths.problem(mountPath)?.let { reader.violation("mountPath", it) }
        }
        val volumeDeclared = reader.isPresent("volume")
        val volume = reader.mapping("volume")?.let { readVolume(it, definitionName) }
        reader.done()

        val resolvedPath = mountPath ?: PaperServerDefaults.MOUNT_PATH
        return when (mode) {
            StorageMode.PERSISTENT -> {
                StorageSpec.Persistent(
                    volume = volume ?: VolumeSpec(name = definitionName),
                    mountPath = resolvedPath,
                )
            }

            StorageMode.EPHEMERAL -> {
                if (volumeDeclared) {
                    reader.violation(
                        "volume",
                        "must not be set when spec.storage.mode is `ephemeral`: an ephemeral server has no " +
                            "volume that outlives its container. Remove one of the two",
                    )
                }
                StorageSpec.Ephemeral(mountPath = resolvedPath)
            }
        }
    }

    private fun readVolume(
        reader: MappingReader,
        definitionName: ResourceName,
    ): VolumeSpec {
        val name = reader.value("name", default = definitionName, parse = ResourceName::of)
        val size = reader.memory("size", min = PaperServerDefaults.MIN_VOLUME_SIZE)
        reader.done()
        return VolumeSpec(name = name ?: definitionName, size = size)
    }

    private fun readLifecycle(reader: MappingReader): LifecycleSpec? {
        val drain = reader.mapping("drain")?.let(::readDrain) ?: DrainSpec()
        val startupTimeout =
            reader.duration(
                "startupTimeout",
                default = PaperServerDefaults.STARTUP_TIMEOUT,
                min = 1.seconds,
                max = PaperServerDefaults.MAX_TIMEOUT,
            ) ?: PaperServerDefaults.STARTUP_TIMEOUT
        val stopGracePeriod =
            reader.duration(
                "stopGracePeriod",
                default = drain.saveTimeout + PaperServerDefaults.STOP_GRACE_MARGIN,
                min = 1.seconds,
                max = PaperServerDefaults.MAX_STOP_GRACE_PERIOD,
            ) ?: (drain.saveTimeout + PaperServerDefaults.STOP_GRACE_MARGIN)
        reader.done()

        val problem = SpecInvariants.stopGraceProblem(stopGracePeriod, drain.saveTimeout)
        if (problem != null) {
            reader.violation("stopGracePeriod", problem)
            return null
        }
        return LifecycleSpec(drain = drain, stopGracePeriod = stopGracePeriod, startupTimeout = startupTimeout)
    }

    private fun readDrain(reader: MappingReader): DrainSpec {
        val policy =
            reader.enum(
                "policy",
                default = DrainPolicy.WAIT_FOR_ZERO_PLAYERS,
                supported = DrainPolicy.supported(),
                lookup = DrainPolicy::fromWire,
            ) ?: DrainPolicy.WAIT_FOR_ZERO_PLAYERS
        val transfer =
            reader.duration(
                "playerTransferTimeout",
                default = PaperServerDefaults.PLAYER_TRANSFER_TIMEOUT,
                min = 1.seconds,
                max = PaperServerDefaults.MAX_TIMEOUT,
            ) ?: PaperServerDefaults.PLAYER_TRANSFER_TIMEOUT
        val save =
            reader.duration(
                "saveTimeout",
                default = PaperServerDefaults.SAVE_TIMEOUT,
                min = 1.seconds,
                max = PaperServerDefaults.MAX_TIMEOUT,
            ) ?: PaperServerDefaults.SAVE_TIMEOUT
        reader.done()
        return DrainSpec(policy = policy, playerTransferTimeout = transfer, saveTimeout = save)
    }

    /**
     * `console.maxTier`, defaulting to the most restrictive tier.
     *
     * An unrecognised tier is a violation rather than a fallback. Falling back
     * would mean choosing a privilege level for a document whose author asked for
     * a different one, and the safe-looking fallback is still a choice made on
     * their behalf.
     */
    private fun readConsole(reader: MappingReader): ConsoleSpec {
        val declared = reader.string("maxTier")
        val auditCommandText = reader.boolean("auditCommandText", default = false) ?: false
        reader.done()
        if (declared == null) return ConsoleSpec(auditCommandText = auditCommandText)
        val tier =
            Tier.parse(declared) ?: run {
                reader.violation(
                    "maxTier",
                    "`$declared` is not a tier. Expected one of ${Tier.entries.joinToString(", ") { it.wireValue }}",
                )
                return ConsoleSpec(auditCommandText = auditCommandText)
            }
        return ConsoleSpec(maxTier = tier, auditCommandText = auditCommandText)
    }

    private fun readPlacement(reader: MappingReader): PlacementSpec {
        val node = reader.value("node", parse = NodeName::of)
        reader.done()
        return PlacementSpec(node = node)
    }
}

/**
 * Where the world is mounted inside the container. Container-internal, so it
 * says nothing about a host and nothing about a node.
 */
internal object MountPaths {
    private val RESERVED =
        setOf(
            "/",
            "/bin",
            "/boot",
            "/dev",
            "/etc",
            "/lib",
            "/lib64",
            "/proc",
            "/root",
            "/run",
            "/sbin",
            "/sys",
            "/tmp",
            "/usr",
            "/var",
        )

    fun problem(raw: String): String? =
        when {
            raw.isEmpty() -> {
                "must not be empty"
            }

            !raw.startsWith("/") -> {
                "must be an absolute path, found `$raw`"
            }

            raw.length > 1 && raw.endsWith("/") -> {
                "must not end with `/`, found `$raw`"
            }

            raw.contains("//") -> {
                "must not contain empty path segments, found `$raw`"
            }

            raw.split("/").any { it == "." || it == ".." } -> {
                "must not contain `.` or `..` segments, found `$raw`"
            }

            raw in RESERVED -> {
                "must not mount over the system path `$raw`; use a dedicated path such as " +
                    "`${PaperServerDefaults.MOUNT_PATH}`"
            }

            else -> {
                null
            }
        }
}

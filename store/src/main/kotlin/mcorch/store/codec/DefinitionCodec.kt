package mcorch.store.codec

import mcorch.schema.CpuQuantity
import mcorch.schema.DrainPolicy
import mcorch.schema.DrainSpec
import mcorch.schema.HeapSpec
import mcorch.schema.ImageRef
import mcorch.schema.LifecycleSpec
import mcorch.schema.MemoryQuantity
import mcorch.schema.MinecraftVersion
import mcorch.schema.NetworkSpec
import mcorch.schema.NodeName
import mcorch.schema.ObjectMetadata
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerSpec
import mcorch.schema.PaperVersionSpec
import mcorch.schema.PlacementSpec
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SecretRef
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerKind
import mcorch.schema.ServerSpec
import mcorch.schema.StorageMode
import mcorch.schema.StorageSpec
import mcorch.schema.VolumeSpec
import mcorch.store.StoreException

/**
 * Encodes and decodes definitions.
 *
 * Metadata and spec are encoded *separately* and stored in separate columns. That
 * is not tidiness: `putDefinition` decides whether the generation moves by
 * comparing the encoded spec against the stored one, and a label edit must not be
 * able to look like a spec change.
 *
 * Adding a kind adds a branch to each `when` here, and the compiler will point at
 * both — the sealed hierarchies in `:schema` are what keep the store honest about
 * a kind it has never been taught.
 */
internal object DefinitionCodec {
    fun encodeMetadata(metadata: ObjectMetadata): String {
        val writer = DocumentWriter()
        writer.put("name", metadata.name.value)
        for ((key, value) in metadata.labels) {
            writer.put("labels.$key", value)
        }
        return writer.render()
    }

    fun decodeMetadata(
        encoded: String,
        what: String,
    ): ObjectMetadata {
        val reader = PropertyDocument.parse(encoded, what)
        val name = reader.requireValue("name", ResourceName::of)
        val labels =
            reader
                .keysUnder("labels.")
                .associate { it.removePrefix("labels.") to reader.requireString(it) }
        return ObjectMetadata(name = name, labels = labels)
    }

    fun encodeSpec(spec: ServerSpec): String {
        val writer = DocumentWriter()
        when (spec) {
            is PaperServerSpec -> writePaperSpec(writer, spec)
        }
        return writer.render()
    }

    fun decode(
        apiVersion: SchemaVersion,
        kind: ServerKind,
        encodedMetadata: String,
        encodedSpec: String,
        what: String,
    ): ServerDefinition {
        val metadata = decodeMetadata(encodedMetadata, what)
        return when (kind) {
            ServerKind.PAPER_SERVER -> {
                PaperServerDefinition(
                    apiVersion = apiVersion,
                    metadata = metadata,
                    spec = readPaperSpec(PropertyDocument.parse(encodedSpec, what), what),
                )
            }
        }
    }

    // ------------------------------------------------------------------ PaperServer

    private fun writePaperSpec(
        writer: DocumentWriter,
        spec: PaperServerSpec,
    ) {
        writer.put("eulaAccepted", spec.eulaAccepted)
        writer.put("maxPlayers", spec.maxPlayers)
        writer.scope("image") { writeImage(this, spec.image) }
        writer.scope("paper") {
            put("minecraftVersion", spec.paper.minecraftVersion.value)
            put("build", spec.paper.build)
        }
        writer.scope("resources") {
            put("memory", spec.resources.memory.bytes)
            put("cpu", spec.resources.cpu?.millicores)
            scope("heap") {
                put("max", spec.resources.heap.max.bytes)
                put("min", spec.resources.heap.min.bytes)
            }
        }
        writer.scope("network") {
            put("port", spec.network.port)
            put("hostPort", spec.network.hostPort)
            when (val rcon = spec.network.rcon) {
                is RconSpec.Disabled -> {
                    put("rcon.enabled", false)
                }

                is RconSpec.Enabled -> {
                    scope("rcon") {
                        put("enabled", true)
                        put("port", rcon.port)
                        // Coordinates only. There is no field here that could hold the password.
                        put("passwordSecret.name", rcon.passwordSecret.name.value)
                        put("passwordSecret.key", rcon.passwordSecret.key)
                    }
                }
            }
        }
        writer.scope("storage") {
            put("mode", spec.storage.mode.wireValue)
            put("mountPath", spec.storage.mountPath)
            when (val storage = spec.storage) {
                is StorageSpec.Persistent -> {
                    scope("volume") {
                        put("name", storage.volume.name.value)
                        put("size", storage.volume.size?.bytes)
                    }
                }

                is StorageSpec.Ephemeral -> {
                    Unit
                }
            }
        }
        writer.scope("lifecycle") {
            putDuration("stopGracePeriod", spec.lifecycle.stopGracePeriod)
            putDuration("startupTimeout", spec.lifecycle.startupTimeout)
            scope("drain") {
                put("policy", spec.lifecycle.drain.policy.wireValue)
                putDuration("playerTransferTimeout", spec.lifecycle.drain.playerTransferTimeout)
                putDuration("saveTimeout", spec.lifecycle.drain.saveTimeout)
            }
        }
        writer.put("placement.node", spec.placement.node?.value)
    }

    private fun readPaperSpec(
        reader: DocumentReader,
        what: String,
    ): PaperServerSpec =
        rebuilding(what) {
            PaperServerSpec(
                image = readImage(reader, "image", what),
                paper =
                    PaperVersionSpec(
                        minecraftVersion = reader.requireValue("paper.minecraftVersion", MinecraftVersion::of),
                        build = reader.int("paper.build"),
                    ),
                resources =
                    ResourceSpec(
                        memory = reader.requireValue("resources.memory") { memoryOf(it) },
                        heap =
                            HeapSpec(
                                max = reader.requireValue("resources.heap.max") { memoryOf(it) },
                                min = reader.requireValue("resources.heap.min") { memoryOf(it) },
                            ),
                        cpu = reader.value("resources.cpu") { cpuOf(it) },
                    ),
                storage = readStorage(reader),
                eulaAccepted = reader.requireBoolean("eulaAccepted"),
                maxPlayers = reader.requireInt("maxPlayers"),
                network =
                    NetworkSpec(
                        port = reader.requireInt("network.port"),
                        hostPort = reader.int("network.hostPort"),
                        rcon = readRcon(reader),
                    ),
                lifecycle =
                    LifecycleSpec(
                        drain =
                            DrainSpec(
                                policy =
                                    readWire("lifecycle.drain.policy", reader, DrainPolicy::fromWire) {
                                        DrainPolicy.supported()
                                    },
                                playerTransferTimeout =
                                    reader.requireDuration(
                                        "lifecycle.drain.playerTransferTimeout",
                                    ),
                                saveTimeout = reader.requireDuration("lifecycle.drain.saveTimeout"),
                            ),
                        stopGracePeriod = reader.requireDuration("lifecycle.stopGracePeriod"),
                        startupTimeout = reader.requireDuration("lifecycle.startupTimeout"),
                    ),
                placement = PlacementSpec(node = reader.value("placement.node", NodeName::of)),
            )
        }

    private fun readStorage(reader: DocumentReader): StorageSpec {
        val mode = readWire("storage.mode", reader, StorageMode::fromWire) { StorageMode.supported() }
        val mountPath = reader.requireString("storage.mountPath")
        return when (mode) {
            StorageMode.PERSISTENT -> {
                StorageSpec.Persistent(
                    volume =
                        VolumeSpec(
                            name = reader.requireValue("storage.volume.name", ResourceName::of),
                            size = reader.value("storage.volume.size") { memoryOf(it) },
                        ),
                    mountPath = mountPath,
                )
            }

            StorageMode.EPHEMERAL -> {
                StorageSpec.Ephemeral(mountPath = mountPath)
            }
        }
    }

    private fun readRcon(reader: DocumentReader): RconSpec {
        if (!reader.requireBoolean("network.rcon.enabled")) return RconSpec.Disabled
        return RconSpec.Enabled(
            port = reader.requireInt("network.rcon.port"),
            passwordSecret =
                SecretRef(
                    name = reader.requireValue("network.rcon.passwordSecret.name", ResourceName::of),
                    key = reader.requireString("network.rcon.passwordSecret.key"),
                ),
        )
    }

    // ------------------------------------------------------------------- shared bits

    fun writeImage(
        scope: DocumentScope,
        image: ImageRef,
    ) {
        // Stored in parts rather than as the canonical string: re-parsing a canonical
        // reference has to guess which leading segment is a registry, and a guess is
        // not a round trip.
        scope.put("registry", image.registry)
        scope.put("repository", image.repository)
        when (image) {
            is ImageRef.Tagged -> scope.put("tag", image.tag)
            is ImageRef.Digested -> scope.put("digest", image.digest)
        }
    }

    fun readImage(
        reader: DocumentReader,
        prefix: String,
        what: String,
    ): ImageRef {
        val registry = reader.string("$prefix.registry")
        val repository = reader.requireString("$prefix.repository")
        val tag = reader.string("$prefix.tag")
        val digest = reader.string("$prefix.digest")
        return when {
            tag != null && digest == null -> ImageRef.Tagged(registry, repository, tag)
            digest != null && tag == null -> ImageRef.Digested(registry, repository, digest)
            else -> throw StoreException.Corrupt("$what: `$prefix` is pinned by neither exactly one tag nor one digest")
        }
    }

    private fun memoryOf(raw: String): Result<MemoryQuantity> =
        raw.toLongOrNull()?.let(MemoryQuantity::ofBytes)
            ?: Result.failure(IllegalArgumentException("expected a byte count, found `$raw`"))

    private fun cpuOf(raw: String): Result<CpuQuantity> =
        raw.toIntOrNull()?.let(CpuQuantity::ofMillicores)
            ?: Result.failure(IllegalArgumentException("expected a millicore count, found `$raw`"))

    private inline fun <reified E : Enum<E>> readWire(
        key: String,
        reader: DocumentReader,
        lookup: (String) -> E?,
        supported: () -> List<String>,
    ): E {
        val raw = reader.requireString(key)
        return lookup(raw) ?: throw reader.unknownEnum(key, raw, E::class.simpleName.orEmpty(), supported())
    }
}

/**
 * Runs a rebuild and turns a rejected invariant into [StoreException.Corrupt].
 *
 * The spec types enforce their own cross-field rules in `init` — heap headroom,
 * stop grace above the save timeout. A stored row that no longer satisfies them
 * is a row this build cannot honour, and it has to say so rather than throw an
 * `IllegalArgumentException` from somewhere in the reconcile loop.
 */
internal inline fun <T> rebuilding(
    what: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: IllegalArgumentException) {
        throw StoreException.Corrupt("$what: stored value no longer satisfies the schema: ${failure.message}", failure)
    }

/** Keys beginning with [prefix]. Used for the open-ended maps (labels). */
internal fun DocumentReader.keysUnder(prefix: String): List<String> = keys().filter { it.startsWith(prefix) }.sorted()

package mcorch.store.codec

import mcorch.schema.BackendDrainSpec
import mcorch.schema.BackendSelector
import mcorch.schema.BackendsSpec
import mcorch.schema.BoundedDefinition
import mcorch.schema.ControlEndpointSpec
import mcorch.schema.CpuQuantity
import mcorch.schema.DrainPolicy
import mcorch.schema.DrainSpec
import mcorch.schema.ForwardingMode
import mcorch.schema.ForwardingSpec
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
import mcorch.schema.ProxyDrainSpec
import mcorch.schema.ProxyLifecycleSpec
import mcorch.schema.ProxyNetworkSpec
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.ResourceSpec
import mcorch.schema.SchemaVersion
import mcorch.schema.SecretRef
import mcorch.schema.ServerKind
import mcorch.schema.ServerSpec
import mcorch.schema.SpecBounds
import mcorch.schema.StorageMode
import mcorch.schema.StorageSpec
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxySpec
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
            is VelocityProxySpec -> writeProxySpec(writer, spec)
        }
        return writer.render()
    }

    /**
     * Rebuilds a definition from its two stored documents, with every
     * deadline-bearing duration inside the ceiling [SpecBounds] states.
     *
     * ## Why the bound is here and not at the consumers
     *
     * `stopGracePeriod`, `drain.saveTimeout` and `backends.drain.sealTimeout` each
     * become a transport deadline, and each is bounded by its YAML reader and by
     * nothing else — no spec type enforces it. A row that did not come through a
     * reader therefore carries whatever the column can express, and thirty hours in
     * a column is a reconcile worker parked with no effective timeout. This is the
     * point where a row stops being bytes and starts being a spec somebody will act
     * on, and it is the only such point for this store, so one bound here replaces a
     * ceiling at every consumer that will ever read one of those fields.
     *
     * It is also the only place holding **both** halves of the `stopGracePeriod` /
     * `saveTimeout` pair, which is what stops the bound inverting the invariant that
     * keeps a container alive until its save finishes. [SpecBounds] owns that
     * argument.
     *
     * ## What it returns, and why it is not just the definition
     *
     * The clamped values come back beside it. A clamp nobody reports is a silent
     * reinterpretation of stored data, which is the one thing this codec exists to
     * refuse; the caller logs them ([mcorch.store.sqlite.SqliteStore]). The stored
     * document is **not** rewritten — the operator's declared number stays on disk,
     * so the row loses nothing and a fixed ceiling later restores it.
     */
    fun decode(
        apiVersion: SchemaVersion,
        kind: ServerKind,
        encodedMetadata: String,
        encodedSpec: String,
        what: String,
    ): BoundedDefinition {
        val metadata = decodeMetadata(encodedMetadata, what)
        val definition =
            when (kind) {
                ServerKind.PAPER_SERVER -> {
                    PaperServerDefinition(
                        apiVersion = apiVersion,
                        metadata = metadata,
                        spec = readPaperSpec(PropertyDocument.parse(encodedSpec, what), what),
                    )
                }

                ServerKind.VELOCITY_PROXY -> {
                    VelocityProxyDefinition(
                        apiVersion = apiVersion,
                        metadata = metadata,
                        spec = readProxySpec(PropertyDocument.parse(encodedSpec, what), what),
                    )
                }
            }
        // Inside `rebuilding` for the reason every other constructor call here is:
        // the clamped spec is rebuilt through `LifecycleSpec.init`, and a rejection
        // there has to arrive as `StoreException.Corrupt` rather than as an
        // `IllegalArgumentException` that escapes the per-row isolation.
        return rebuilding(what) { SpecBounds.bound(definition) }
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
            scope("rcon") {
                val rcon = spec.network.rcon
                put("port", rcon.port)
                // Coordinates only. There is no field here that could hold the password.
                put("passwordSecret.name", rcon.passwordSecret.name.value)
                put("passwordSecret.key", rcon.passwordSecret.key)
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

    /**
     * No `enabled` key, in either direction.
     *
     * A row written by a build that had one will not decode, which surfaces as
     * `SERVER_UNREADABLE` — the path that already exists for a stored form this
     * build cannot read, and the right outcome rather than a silent default.
     */
    private fun readRcon(reader: DocumentReader): RconSpec =
        RconSpec(
            port = reader.requireInt("network.rcon.port"),
            passwordSecret =
                SecretRef(
                    name = reader.requireValue("network.rcon.passwordSecret.name", ResourceName::of),
                    key = reader.requireString("network.rcon.passwordSecret.key"),
                ),
        )

    // --------------------------------------------------------------- VelocityProxy

    /**
     * No `storage` block and no `eulaAccepted`, because the type has neither.
     *
     * The two secrets are written the way every secret in this format is written —
     * name and key, the coordinates of an entry in the [mcorch.store.SecretStore].
     * There is no field on this document that could hold forwarding material, and
     * `SecretLeakageTest` covers this kind so that stays true.
     */
    private fun writeProxySpec(
        writer: DocumentWriter,
        spec: VelocityProxySpec,
    ) {
        writer.put("maxPlayers", spec.maxPlayers)
        writer.scope("image") { writeImage(this, spec.image) }
        writer.scope("resources") {
            put("memory", spec.resources.memory.bytes)
            put("cpu", spec.resources.cpu?.millicores)
            scope("heap") {
                put("max", spec.resources.heap.max.bytes)
                put("min", spec.resources.heap.min.bytes)
            }
        }
        writer.scope("forwarding") {
            put("mode", spec.forwarding.mode.wireValue)
            // Coordinates only. Invariant 4: the material travels through the
            // secret store and reaches nothing else, this row included.
            put("secret.name", spec.forwarding.secret.name.value)
            put("secret.key", spec.forwarding.secret.key)
        }
        writer.scope("backends") {
            // Open-ended keys, so written the way metadata labels are: one entry per
            // key under a prefix, read back with `keysUnder`. A label key may itself
            // contain dots, which is exactly why the prefix is matched rather than
            // the depth counted.
            for ((key, value) in spec.backends.selector.matchLabels) {
                put("selector.matchLabels.$key", value)
            }
            putList("fallback", spec.backends.fallback.map { it.value })
            scope("drain") {
                putDuration("sealTimeout", spec.backends.drain.sealTimeout)
                putDuration("destinationTimeout", spec.backends.drain.destinationTimeout)
                putDuration("deregisterTimeout", spec.backends.drain.deregisterTimeout)
            }
        }
        writer.scope("control") {
            put("port", spec.control.port)
            put("hostPort", spec.control.hostPort)
            put(
                "tokenSecret.name",
                spec.control.tokenSecret
                    ?.name
                    ?.value,
            )
            put("tokenSecret.key", spec.control.tokenSecret?.key)
        }
        writer.scope("network") {
            put("port", spec.network.port)
            put("hostPort", spec.network.hostPort)
        }
        writer.scope("lifecycle") {
            putDuration("stopGracePeriod", spec.lifecycle.stopGracePeriod)
            putDuration("startupTimeout", spec.lifecycle.startupTimeout)
            scope("drain") {
                put("policy", spec.lifecycle.drain.policy.wireValue)
                putDuration("sealTimeout", spec.lifecycle.drain.sealTimeout)
            }
        }
        writer.put("placement.node", spec.placement.node?.value)
    }

    /**
     * Wrapped in [rebuilding] for the same reason [readPaperSpec] is, and with more
     * riding on it: `BackendSelector` refuses an empty `matchLabels` and
     * `VelocityProxySpec` refuses a control port that collides with the player
     * port. Both run here, on the way in from disk, which is intended — a row that
     * no longer satisfies them is a row this build must not reconcile.
     *
     * What [rebuilding] buys is the *type* of the refusal. An
     * `IllegalArgumentException` out of a constructor would cross the store
     * boundary as something `:core` cannot classify and, worse, would escape the
     * per-row isolation in `SqliteStore.readRow`, which catches [StoreException]
     * only — one hand-edited proxy row would take the whole fleet read down with
     * it. As [StoreException.Corrupt] it costs exactly its own server.
     */
    private fun readProxySpec(
        reader: DocumentReader,
        what: String,
    ): VelocityProxySpec =
        rebuilding(what) {
            VelocityProxySpec(
                image = readImage(reader, "image", what),
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
                forwarding =
                    ForwardingSpec(
                        secret =
                            SecretRef(
                                name = reader.requireValue("forwarding.secret.name", ResourceName::of),
                                key = reader.requireString("forwarding.secret.key"),
                            ),
                        mode =
                            readWire("forwarding.mode", reader, ForwardingMode::fromWire) {
                                ForwardingMode.supported()
                            },
                    ),
                backends =
                    BackendsSpec(
                        selector = readSelector(reader),
                        fallback = reader.list("backends.fallback").map { readName(reader, it) },
                        drain =
                            BackendDrainSpec(
                                sealTimeout = reader.requireDuration("backends.drain.sealTimeout"),
                                destinationTimeout = reader.requireDuration("backends.drain.destinationTimeout"),
                                deregisterTimeout = reader.requireDuration("backends.drain.deregisterTimeout"),
                            ),
                    ),
                control =
                    ControlEndpointSpec(
                        port = reader.requireInt("control.port"),
                        hostPort = reader.int("control.hostPort"),
                        tokenSecret = readSecretRef(reader, "control.tokenSecret"),
                    ),
                maxPlayers = reader.requireInt("maxPlayers"),
                network =
                    ProxyNetworkSpec(
                        port = reader.requireInt("network.port"),
                        hostPort = reader.int("network.hostPort"),
                    ),
                lifecycle =
                    ProxyLifecycleSpec(
                        drain =
                            ProxyDrainSpec(
                                policy =
                                    readWire("lifecycle.drain.policy", reader, DrainPolicy::fromWire) {
                                        DrainPolicy.supported()
                                    },
                                sealTimeout = reader.requireDuration("lifecycle.drain.sealTimeout"),
                            ),
                        stopGracePeriod = reader.requireDuration("lifecycle.stopGracePeriod"),
                        startupTimeout = reader.requireDuration("lifecycle.startupTimeout"),
                    ),
                placement = PlacementSpec(node = reader.value("placement.node", NodeName::of)),
            )
        }

    private fun readSelector(reader: DocumentReader): BackendSelector {
        val prefix = "backends.selector.matchLabels."
        return BackendSelector(
            matchLabels = reader.keysUnder(prefix).associate { it.removePrefix(prefix) to reader.requireString(it) },
        )
    }

    private fun readSecretRef(
        reader: DocumentReader,
        prefix: String,
    ): SecretRef? {
        if (!reader.has("$prefix.name")) return null
        return SecretRef(
            name = reader.requireValue("$prefix.name", ResourceName::of),
            key = reader.requireString("$prefix.key"),
        )
    }

    private fun readName(
        reader: DocumentReader,
        key: String,
    ): ResourceName = reader.requireValue(key, ResourceName::of)

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

/** Keys beginning with [prefix]. Used for the open-ended maps (labels, selector match labels). */
internal fun DocumentReader.keysUnder(prefix: String): List<String> = keys().filter { it.startsWith(prefix) }.sorted()

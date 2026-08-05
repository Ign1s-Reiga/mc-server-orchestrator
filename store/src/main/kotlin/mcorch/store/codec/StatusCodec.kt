package mcorch.store.codec

import mcorch.schema.BackendRegistration
import mcorch.schema.BackendRoutingStatus
import mcorch.schema.BackendStatus
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.ControlEndpointStatus
import mcorch.schema.DrainBlock
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.FailureStatus
import mcorch.schema.ImageStatus
import mcorch.schema.NodeName
import mcorch.schema.PaperServerStatus
import mcorch.schema.PlayerOccupancy
import mcorch.schema.ResourceName
import mcorch.schema.RuntimeIdentity
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerEndpoint
import mcorch.schema.ServerKind
import mcorch.schema.ServerPhase
import mcorch.schema.ServerStatus
import mcorch.schema.StatusCondition
import mcorch.schema.StorageStatus
import mcorch.schema.VelocityProxyStatus

/**
 * Encodes and decodes observed state.
 *
 * The part that matters most here is [DrainStatus]. The drain protocol's
 * idempotency rule is that the loop re-reads drain state from stored status and
 * does not re-send a side effect it has already issued: if `saveRequestedAt` is
 * set, the save request went out, and asking again puts real work on a live
 * server for nothing. Every one of those fields therefore has to survive a
 * process restart *exactly* as written — a `null` that comes back as a `false`,
 * or a state that comes back one step earlier, restarts a drain that was already
 * half done.
 *
 * So nothing here is derived, defaulted or normalised on the way back in. Every
 * field is written and read individually, and the round trip is tested field by
 * field over every [DrainState] and every [ServerPhase].
 */
internal object StatusCodec {
    fun encode(status: ServerStatus): String {
        val writer = DocumentWriter()
        when (status) {
            is PaperServerStatus -> writePaperStatus(writer, status)
            is VelocityProxyStatus -> writeProxyStatus(writer, status)
        }
        return writer.render()
    }

    fun decode(
        name: ResourceName,
        apiVersion: SchemaVersion,
        kind: ServerKind,
        encoded: String,
        what: String,
    ): ServerStatus {
        val reader = PropertyDocument.parse(encoded, what)
        return when (kind) {
            ServerKind.PAPER_SERVER -> readPaperStatus(name, apiVersion, reader, what)
            ServerKind.VELOCITY_PROXY -> readProxyStatus(name, apiVersion, reader, what)
        }
    }

    /** The drain state a status records, for the store's projection column. Null when no drain is recorded. */
    fun drainStateOf(status: ServerStatus): DrainState? =
        when (status) {
            is PaperServerStatus -> status.drain?.state
            is VelocityProxyStatus -> status.drain?.state
        }

    // ------------------------------------------------------------------ PaperServer

    private fun writePaperStatus(
        writer: DocumentWriter,
        status: PaperServerStatus,
    ) {
        writer.put("observedGeneration", status.observedGeneration)
        writer.put("phase", status.phase)
        writer.put("observedAt", status.observedAt)
        writer.put("lastTransitionAt", status.lastTransitionAt)
        writer.put("ready", status.ready)

        status.image?.let { image ->
            writer.scope("image") {
                scope("requested") { DefinitionCodec.writeImage(this, image.requested) }
                put("resolvedDigest", image.resolvedDigest)
                put("pulledAt", image.pulledAt)
            }
        }
        status.runtime?.let { runtime -> writer.scope("runtime") { writeRuntime(this, runtime) } }
        status.endpoint?.let { endpoint -> writer.scope("endpoint") { writeEndpoint(this, endpoint) } }
        status.players?.let { players -> writer.scope("players") { writePlayers(this, players) } }
        status.storage?.let { storage ->
            writer.scope("storage") {
                put("persistent", storage.persistent)
                put("volumeName", storage.volumeName?.value)
                put("bound", storage.bound)
                put("lastSaveConfirmedAt", storage.lastSaveConfirmedAt)
            }
        }
        status.drain?.let { drain -> writer.scope("drain") { writeDrain(this, drain) } }
        status.failure?.let { failure -> writer.scope("failure") { writeFailure(this, failure) } }
        writeConditions(writer, status.conditions)
    }

    // ---------------------------------------------------------------- shared blocks

    private fun writeRuntime(
        scope: DocumentScope,
        runtime: RuntimeIdentity,
    ) {
        scope.put("node", runtime.node.value)
        scope.put("sandboxId", runtime.sandboxId)
        scope.put("containerId", runtime.containerId)
        scope.put("createdAt", runtime.createdAt)
        scope.put("startedAt", runtime.startedAt)
        scope.put("finishedAt", runtime.finishedAt)
        scope.put("exitCode", runtime.exitCode)
        scope.put("restartCount", runtime.restartCount)
    }

    private fun writeEndpoint(
        scope: DocumentScope,
        endpoint: ServerEndpoint,
    ) {
        scope.put("node", endpoint.node.value)
        scope.put("address", endpoint.address)
        scope.put("port", endpoint.port)
    }

    /** Counts only. There is no key here that could hold an identity. */
    private fun writePlayers(
        scope: DocumentScope,
        players: PlayerOccupancy,
    ) {
        scope.put("online", players.online)
        scope.put("max", players.max)
        scope.put("observedAt", players.observedAt)
    }

    /**
     * Deliberately writes nothing for an empty list rather than a zero count.
     *
     * Unlike the routing table, `conditions` is not nullable, so "absent" and
     * "empty" are the same value and there is nothing to tell apart. Emitting a
     * count key here would change what every stored Paper row renders to, for no
     * gain — and that is a comparison `putDefinition` makes on specs and a
     * migration makes on documents.
     */
    private fun writeConditions(
        writer: DocumentWriter,
        conditions: List<StatusCondition>,
    ) {
        if (conditions.isEmpty()) return
        writer.putListOf("conditions", conditions.size) { index ->
            val condition = conditions[index]
            writer.scope("conditions.$index") {
                put("type", condition.type)
                put("status", condition.status)
                put("message", condition.message)
                put("lastTransitionAt", condition.lastTransitionAt)
            }
        }
    }

    private fun readPaperStatus(
        name: ResourceName,
        apiVersion: SchemaVersion,
        reader: DocumentReader,
        what: String,
    ): PaperServerStatus =
        rebuilding(what) {
            PaperServerStatus(
                name = name,
                observedGeneration = reader.requireLong("observedGeneration"),
                phase = reader.requireEnum<ServerPhase>("phase"),
                observedAt = reader.requireInstant("observedAt"),
                lastTransitionAt = reader.requireInstant("lastTransitionAt"),
                ready = reader.requireBoolean("ready"),
                image = readImageStatus(reader, what),
                runtime = readRuntime(reader),
                endpoint = readEndpoint(reader),
                players = readPlayers(reader, "players"),
                storage = readStorage(reader),
                drain = readDrain(reader, "drain"),
                failure = readFailure(reader, "failure"),
                conditions = readConditions(reader),
                apiVersion = apiVersion,
            )
        }

    private fun readImageStatus(
        reader: DocumentReader,
        what: String,
    ): ImageStatus? {
        if (!reader.has("image.requested.repository")) return null
        return ImageStatus(
            requested = DefinitionCodec.readImage(reader, "image.requested", what),
            resolvedDigest = reader.string("image.resolvedDigest"),
            pulledAt = reader.instant("image.pulledAt"),
        )
    }

    private fun readRuntime(reader: DocumentReader): RuntimeIdentity? {
        if (!reader.has("runtime.sandboxId")) return null
        return RuntimeIdentity(
            node = reader.requireValue("runtime.node", NodeName::of),
            sandboxId = reader.requireString("runtime.sandboxId"),
            containerId = reader.string("runtime.containerId"),
            createdAt = reader.instant("runtime.createdAt"),
            startedAt = reader.instant("runtime.startedAt"),
            finishedAt = reader.instant("runtime.finishedAt"),
            exitCode = reader.int("runtime.exitCode"),
            restartCount = reader.requireInt("runtime.restartCount"),
        )
    }

    private fun readEndpoint(reader: DocumentReader): ServerEndpoint? {
        if (!reader.has("endpoint.address")) return null
        return ServerEndpoint(
            node = reader.requireValue("endpoint.node", NodeName::of),
            address = reader.requireString("endpoint.address"),
            port = reader.requireInt("endpoint.port"),
        )
    }

    /** Prefixed, because a proxy carries one of these per backend as well as one of its own. */
    private fun readPlayers(
        reader: DocumentReader,
        prefix: String,
    ): PlayerOccupancy? {
        if (!reader.has("$prefix.online")) return null
        return PlayerOccupancy(
            online = reader.requireInt("$prefix.online"),
            max = reader.requireInt("$prefix.max"),
            observedAt = reader.requireInstant("$prefix.observedAt"),
        )
    }

    private fun readStorage(reader: DocumentReader): StorageStatus? {
        if (!reader.has("storage.persistent")) return null
        return StorageStatus(
            persistent = reader.requireBoolean("storage.persistent"),
            volumeName = reader.value("storage.volumeName", ResourceName::of),
            bound = reader.requireBoolean("storage.bound"),
            lastSaveConfirmedAt = reader.instant("storage.lastSaveConfirmedAt"),
        )
    }

    // --------------------------------------------------------------- VelocityProxy

    /**
     * No `storage`, because the type has none: a proxy holds no world, and writing
     * an absent block would invite a later reader to conclude "not persistent yet"
     * from the gap rather than "there is no such thing here".
     *
     * The two proxy-only observations are [BackendRoutingStatus] and
     * [ControlEndpointStatus]. Everything else — image, runtime, endpoint, players,
     * drain, failure, conditions — is written by the same helpers a Paper status
     * uses, so a drain record reads identically whichever kind wrote it. That is
     * deliberate rather than convenient: the drain state machine is one machine,
     * and a restarted loop resumes both kinds from the same keys.
     */
    private fun writeProxyStatus(
        writer: DocumentWriter,
        status: VelocityProxyStatus,
    ) {
        writer.put("observedGeneration", status.observedGeneration)
        writer.put("phase", status.phase)
        writer.put("observedAt", status.observedAt)
        writer.put("lastTransitionAt", status.lastTransitionAt)
        writer.put("ready", status.ready)

        status.image?.let { image ->
            writer.scope("image") {
                scope("requested") { DefinitionCodec.writeImage(this, image.requested) }
                put("resolvedDigest", image.resolvedDigest)
                put("pulledAt", image.pulledAt)
            }
        }
        status.runtime?.let { runtime -> writer.scope("runtime") { writeRuntime(this, runtime) } }
        status.endpoint?.let { endpoint -> writer.scope("endpoint") { writeEndpoint(this, endpoint) } }
        status.players?.let { players -> writer.scope("players") { writePlayers(this, players) } }
        status.backends?.let { backends -> writer.scope("backends") { writeBackends(this, backends) } }
        status.control?.let { control ->
            writer.scope("control") {
                // `reachable` is the presence marker on the way back in: it is the one
                // field of this object that is never null, so a control block that was
                // observed can always be told from one that was not.
                put("reachable", control.reachable)
                put("pluginApiVersion", control.pluginApiVersion)
                put("compatible", control.compatible)
                put("lastContactAt", control.lastContactAt)
            }
        }
        status.drain?.let { drain -> writer.scope("drain") { writeDrain(this, drain) } }
        status.failure?.let { failure -> writer.scope("failure") { writeFailure(this, failure) } }
        writeConditions(writer, status.conditions)
    }

    /**
     * The routing table, as a count plus one indexed record each.
     *
     * The first list of *records* in this format; `conditions` is the precedent and
     * this is the same shape, promoted to [DocumentWriter.putList]. The
     * alternative — packing a backend into one delimited value — was rejected
     * because it needs a second escaping scheme inside a format that already has
     * one, and because every field would stop being individually readable by a
     * migration, which is the level migrations here are required to work at.
     *
     * `observedAt` is written first and unconditionally: it is what tells a
     * *present but empty* routing table from one that was never observed. That
     * distinction is not cosmetic — an empty table means the selector matched
     * nothing, which is a real condition an operator has to see, and reading it
     * back as "no observation" would hide it.
     */
    private fun writeBackends(
        scope: DocumentScope,
        backends: BackendRoutingStatus,
    ) {
        scope.put("observedAt", backends.observedAt)
        scope.putListOf("list", backends.backends.size) { index ->
            val backend = backends.backends[index]
            scope.scope("list.$index") {
                put("server", backend.server.value)
                put("registration", backend.registration)
                // The drain reads this to exclude a destination that is itself
                // draining. Losing it silently widens eligibility to servers on
                // their way down, which is a transfer cycle two servers can enter
                // and never leave.
                put("drainInitiated", backend.drainInitiated)
                put("lastTransitionAt", backend.lastTransitionAt)
                backend.players?.let { players -> scope("players") { writePlayers(this, players) } }
            }
        }
    }

    private fun readProxyStatus(
        name: ResourceName,
        apiVersion: SchemaVersion,
        reader: DocumentReader,
        what: String,
    ): VelocityProxyStatus =
        rebuilding(what) {
            VelocityProxyStatus(
                name = name,
                observedGeneration = reader.requireLong("observedGeneration"),
                phase = reader.requireEnum<ServerPhase>("phase"),
                observedAt = reader.requireInstant("observedAt"),
                lastTransitionAt = reader.requireInstant("lastTransitionAt"),
                ready = reader.requireBoolean("ready"),
                image = readImageStatus(reader, what),
                runtime = readRuntime(reader),
                endpoint = readEndpoint(reader),
                players = readPlayers(reader, "players"),
                backends = readBackends(reader),
                control = readControl(reader),
                drain = readDrain(reader, "drain"),
                failure = readFailure(reader, "failure"),
                conditions = readConditions(reader),
                apiVersion = apiVersion,
            )
        }

    private fun readBackends(reader: DocumentReader): BackendRoutingStatus? {
        if (!reader.has("backends.observedAt")) return null
        return BackendRoutingStatus(
            observedAt = reader.requireInstant("backends.observedAt"),
            backends =
                reader.list("backends.list").map { prefix ->
                    BackendStatus(
                        server = reader.requireValue("$prefix.server", ResourceName::of),
                        registration = reader.requireEnum<BackendRegistration>("$prefix.registration"),
                        players = readPlayers(reader, "$prefix.players"),
                        drainInitiated = reader.requireBoolean("$prefix.drainInitiated"),
                        lastTransitionAt = reader.requireInstant("$prefix.lastTransitionAt"),
                    )
                },
        )
    }

    private fun readControl(reader: DocumentReader): ControlEndpointStatus? {
        if (!reader.has("control.reachable")) return null
        return ControlEndpointStatus(
            reachable = reader.requireBoolean("control.reachable"),
            pluginApiVersion = reader.string("control.pluginApiVersion"),
            compatible = reader.requireBoolean("control.compatible"),
            lastContactAt = reader.instant("control.lastContactAt"),
        )
    }

    // ------------------------------------------------------------------------ drain

    private fun writeDrain(
        scope: DocumentScope,
        drain: DrainStatus,
    ) {
        scope.put("state", drain.state)
        scope.put("startedAt", drain.startedAt)
        scope.put("enteredStateAt", drain.enteredStateAt)
        scope.put("playersEvacuated", drain.playersEvacuated)
        // Each of these is "the side effect went out". Losing one re-issues it.
        scope.put("sealRequestedAt", drain.sealRequestedAt)
        scope.put("saveRequestedAt", drain.saveRequestedAt)
        // Not `worldSaved`: the flag is derived from this instant and storing
        // both would let a document say two things. V3 rewrote the rows that
        // carried the flag — see `V3SplitWorldSavedInstant`.
        scope.put("worldSavedAt", drain.worldSavedAt)
        // The anchor for "this drain keeps having to save again and never gets to
        // the stop". Losing it across a restart is not cosmetic for the same
        // reason `transferStartedAt` is not: the field is stamped once and a drain
        // that came back without it would start its count over on every restart,
        // which is the cycle it exists to make visible.
        scope.put("resaveForcedAt", drain.resaveForcedAt)
        scope.put("deregisteredAt", drain.deregisteredAt)
        // The anchor drain step 4's allowance is measured from. Set once, never
        // cleared, and losing it is not cosmetic: a drain that came back without it
        // would re-stamp on the next pass and be handed its full allowance again,
        // which is the loop the field exists to close. A row written before this
        // field existed reads null, and the next pass through step 4 stamps it —
        // one extra allowance for a drain that was mid-transfer across an upgrade,
        // which is the safe direction.
        scope.put("transferStartedAt", drain.transferStartedAt)
        scope.put("transferAttempts", drain.transferAttempts)
        scope.put("destination", drain.destination?.value)
        // Written as its own object beside `failure` rather than as a variant of
        // it. The two mean opposite things to an operator — waiting versus
        // broken — and a single object discriminated by a key would put the whole
        // difference on whichever code path remembered to look at the
        // discriminator. V5 rewrote the rows that carried the old shape; see
        // `V5BlockedDrainIsNotAFailure`.
        drain.blocked?.let { block -> scope.scope("blocked") { writeBlock(this, block) } }
        drain.failure?.let { failure -> scope.scope("failure") { writeFailure(this, failure) } }
    }

    private fun readDrain(
        reader: DocumentReader,
        prefix: String,
    ): DrainStatus? {
        if (!reader.has("$prefix.state")) return null
        return DrainStatus(
            state = reader.requireEnum<DrainState>("$prefix.state"),
            startedAt = reader.requireInstant("$prefix.startedAt"),
            enteredStateAt = reader.requireInstant("$prefix.enteredStateAt"),
            playersEvacuated = reader.requireBoolean("$prefix.playersEvacuated"),
            sealRequestedAt = reader.instant("$prefix.sealRequestedAt"),
            saveRequestedAt = reader.instant("$prefix.saveRequestedAt"),
            worldSavedAt = reader.instant("$prefix.worldSavedAt"),
            resaveForcedAt = reader.instant("$prefix.resaveForcedAt"),
            deregisteredAt = reader.instant("$prefix.deregisteredAt"),
            transferStartedAt = reader.instant("$prefix.transferStartedAt"),
            transferAttempts = reader.requireInt("$prefix.transferAttempts"),
            destination = reader.value("$prefix.destination", ResourceName::of),
            blocked = readBlock(reader, "$prefix.blocked"),
            failure = readFailure(reader, "$prefix.failure"),
        )
    }

    // ------------------------------------------------------------------------ block

    private fun writeBlock(
        scope: DocumentScope,
        block: DrainBlock,
    ) {
        scope.put("reason", block.reason)
        scope.put("message", block.message)
        scope.put("since", block.since)
        scope.put("observations", block.observations)
    }

    /**
     * A block, or null when there is none.
     *
     * No `require` beyond the ones the readers already impose, deliberately.
     * `DrainBlock` has no forbidden field combination to enforce and gains no
     * constructor check, because everything decoded here is paid for by
     * `listServers` — a check added on this path is another way for one
     * hand-edited row to fail a fleet read. What can still fail is an
     * unrecognised `reason`, which `requireEnum` reports as a corrupt row and the
     * list read isolates to the row it came from.
     */
    private fun readBlock(
        reader: DocumentReader,
        prefix: String,
    ): DrainBlock? {
        if (!reader.has("$prefix.reason")) return null
        return DrainBlock(
            reason = reader.requireEnum<DrainBlockReason>("$prefix.reason"),
            message = reader.requireString("$prefix.message"),
            since = reader.requireInstant("$prefix.since"),
            observations = reader.requireInt("$prefix.observations"),
        )
    }

    // ---------------------------------------------------------------------- failure

    private fun writeFailure(
        scope: DocumentScope,
        failure: FailureStatus,
    ) {
        scope.put("reason", failure.reason)
        scope.put("failureClass", failure.failureClass)
        scope.put("message", failure.message)
        scope.put("occurredAt", failure.occurredAt)
        scope.put("attempts", failure.attempts)
    }

    private fun readFailure(
        reader: DocumentReader,
        prefix: String,
    ): FailureStatus? {
        if (!reader.has("$prefix.reason")) return null
        return FailureStatus(
            reason = reader.requireEnum<FailureReason>("$prefix.reason"),
            failureClass = reader.requireEnum<FailureClass>("$prefix.failureClass"),
            message = reader.requireString("$prefix.message"),
            occurredAt = reader.requireInstant("$prefix.occurredAt"),
            attempts = reader.requireInt("$prefix.attempts"),
        )
    }

    private fun readConditions(reader: DocumentReader): List<StatusCondition> {
        val count = reader.int("conditions.count") ?: return emptyList()
        return (0 until count).map { index ->
            StatusCondition(
                type = reader.requireEnum<ConditionType>("conditions.$index.type"),
                status = reader.requireEnum<ConditionStatus>("conditions.$index.status"),
                message = reader.requireString("conditions.$index.message"),
                lastTransitionAt = reader.requireInstant("conditions.$index.lastTransitionAt"),
            )
        }
    }
}

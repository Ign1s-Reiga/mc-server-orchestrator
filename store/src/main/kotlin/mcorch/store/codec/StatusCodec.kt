package mcorch.store.codec

import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
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
            is VelocityProxyStatus -> throw notYetPersisted(ServerKind.VELOCITY_PROXY)
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
            ServerKind.VELOCITY_PROXY -> throw notYetPersisted(ServerKind.VELOCITY_PROXY)
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
        status.runtime?.let { runtime ->
            writer.scope("runtime") {
                put("node", runtime.node.value)
                put("sandboxId", runtime.sandboxId)
                put("containerId", runtime.containerId)
                put("createdAt", runtime.createdAt)
                put("startedAt", runtime.startedAt)
                put("finishedAt", runtime.finishedAt)
                put("exitCode", runtime.exitCode)
                put("restartCount", runtime.restartCount)
            }
        }
        status.endpoint?.let { endpoint ->
            writer.scope("endpoint") {
                put("node", endpoint.node.value)
                put("address", endpoint.address)
                put("port", endpoint.port)
            }
        }
        status.players?.let { players ->
            // Counts only. There is no key here that could hold an identity.
            writer.scope("players") {
                put("online", players.online)
                put("max", players.max)
                put("observedAt", players.observedAt)
            }
        }
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

        if (status.conditions.isNotEmpty()) {
            writer.put("conditions.count", status.conditions.size)
            status.conditions.forEachIndexed { index, condition ->
                writer.scope("conditions.$index") {
                    put("type", condition.type)
                    put("status", condition.status)
                    put("message", condition.message)
                    put("lastTransitionAt", condition.lastTransitionAt)
                }
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
                players = readPlayers(reader),
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

    private fun readPlayers(reader: DocumentReader): PlayerOccupancy? {
        if (!reader.has("players.online")) return null
        return PlayerOccupancy(
            online = reader.requireInt("players.online"),
            max = reader.requireInt("players.max"),
            observedAt = reader.requireInstant("players.observedAt"),
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
        scope.put("deregisteredAt", drain.deregisteredAt)
        scope.put("transferAttempts", drain.transferAttempts)
        scope.put("destination", drain.destination?.value)
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
            deregisteredAt = reader.instant("$prefix.deregisteredAt"),
            transferAttempts = reader.requireInt("$prefix.transferAttempts"),
            destination = reader.value("$prefix.destination", ResourceName::of),
            failure = readFailure(reader, "$prefix.failure"),
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

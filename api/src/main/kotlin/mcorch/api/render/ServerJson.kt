package mcorch.api.render

import mcorch.api.json.Json
import mcorch.api.json.JsonObjectBuilder
import mcorch.api.json.jsonObject
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.DurationFormat
import mcorch.schema.FailureStatus
import mcorch.schema.ImageStatus
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerSpec
import mcorch.schema.PaperServerStatus
import mcorch.schema.PlayerOccupancy
import mcorch.schema.RconSpec
import mcorch.schema.RuntimeIdentity
import mcorch.schema.SecretRef
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerEndpoint
import mcorch.schema.ServerPhase
import mcorch.schema.ServerStatus
import mcorch.schema.StatusCondition
import mcorch.schema.StorageSpec
import mcorch.schema.StorageStatus
import mcorch.store.StoredServer
import mcorch.store.Unreadable
import mcorch.store.UnreadableServer

/**
 * How a server looks on the wire.
 *
 * ## Two null policies, on purpose
 *
 * `definition` — the `apiVersion`/`kind`/`metadata`/`spec` document — **omits**
 * absent optional fields, and every other object in a response renders them as
 * an explicit `null`.
 *
 * That asymmetry buys the single most useful property this API has: *what `GET`
 * returns under `definition` is valid input to `POST` and `PUT`, unchanged*. The
 * schema treats an explicit `null` as a violation rather than as "unset"
 * (`spec.storage:` with nothing under it is a mistake worth reporting, not a
 * request for the default), so a definition rendered with explicit nulls would
 * come straight back as a 422 the first time a dashboard round-tripped it.
 * Nothing else in a response is ever sent back, so nothing else pays that price,
 * and a fixed key set is worth more to a TypeScript client than symmetry with
 * the definition is.
 *
 * The fields this affects are exactly the optional ones: `paper.build`,
 * `network.hostPort`, `network.rcon`, `resources.cpu`, `storage.volume.size`,
 * `placement.node`, and `metadata.labels` when empty. Read them as
 * `spec.network.rcon ?? { enabled: false }`.
 *
 * ## What is not here
 *
 * No player name, no UUID, no client address, and no secret material. That is
 * not achieved by filtering: `mcorch.schema.PlayerOccupancy` is a pair of counts
 * and `mcorch.schema.SecretRef` is a pair of coordinates, so there is nothing in
 * the objects below to leave out. The rendering is total — every field of every
 * type is emitted — which is what makes that checkable rather than a promise.
 */
internal object ServerJson {
    /** The round-trippable document. Post this back and it is the same definition. */
    fun definition(definition: ServerDefinition): Json.Obj =
        when (definition) {
            is PaperServerDefinition -> {
                jsonObject {
                    put("apiVersion", definition.apiVersion.wireValue)
                    put("kind", definition.kind.wireValue)
                    put(
                        "metadata",
                        jsonObject {
                            put("name", definition.metadata.name.value)
                            if (definition.metadata.labels.isNotEmpty()) {
                                put("labels", Json.map(definition.metadata.labels))
                            }
                        },
                    )
                    put("spec", spec(definition.spec))
                }
            }
        }

    private fun spec(spec: PaperServerSpec): Json.Obj =
        jsonObject {
            put("image", spec.image.canonical)
            put(
                "paper",
                jsonObject {
                    put("minecraftVersion", spec.paper.minecraftVersion.value)
                    spec.paper.build?.let { put("build", it) }
                },
            )
            put("eulaAccepted", spec.eulaAccepted)
            put("maxPlayers", spec.maxPlayers)
            put(
                "network",
                jsonObject {
                    put("port", spec.network.port)
                    spec.network.hostPort?.let { put("hostPort", it) }
                    when (val rcon = spec.network.rcon) {
                        // Omitted rather than rendered as `enabled: false`: absent is
                        // how the schema spells "off", and this document has to parse.
                        RconSpec.Disabled -> {
                        }

                        is RconSpec.Enabled -> {
                            put(
                                "rcon",
                                jsonObject {
                                    put("enabled", true)
                                    put("port", rcon.port)
                                    put("passwordSecret", secretRef(rcon.passwordSecret))
                                },
                            )
                        }
                    }
                },
            )
            put(
                "resources",
                jsonObject {
                    put("memory", spec.resources.memory.render())
                    spec.resources.cpu?.let { put("cpu", it.render()) }
                    put(
                        "heap",
                        jsonObject {
                            put(
                                "max",
                                spec.resources.heap.max
                                    .render(),
                            )
                            put(
                                "min",
                                spec.resources.heap.min
                                    .render(),
                            )
                        },
                    )
                },
            )
            put(
                "storage",
                jsonObject {
                    put("mode", spec.storage.mode.wireValue)
                    put("mountPath", spec.storage.mountPath)
                    when (val storage = spec.storage) {
                        // An ephemeral server has no volume, and declaring one is a
                        // violation rather than a redundancy — so nothing is written.
                        is StorageSpec.Ephemeral -> {
                        }

                        is StorageSpec.Persistent -> {
                            put(
                                "volume",
                                jsonObject {
                                    put("name", storage.volume.name.value)
                                    storage.volume.size?.let { put("size", it.render()) }
                                },
                            )
                        }
                    }
                },
            )
            put(
                "lifecycle",
                jsonObject {
                    put(
                        "drain",
                        jsonObject {
                            put("policy", spec.lifecycle.drain.policy.wireValue)
                            put(
                                "playerTransferTimeout",
                                DurationFormat.render(spec.lifecycle.drain.playerTransferTimeout),
                            )
                            put("saveTimeout", DurationFormat.render(spec.lifecycle.drain.saveTimeout))
                        },
                    )
                    put("stopGracePeriod", DurationFormat.render(spec.lifecycle.stopGracePeriod))
                    put("startupTimeout", DurationFormat.render(spec.lifecycle.startupTimeout))
                },
            )
            spec.placement.node?.let { node ->
                put("placement", jsonObject { put("node", node.value) })
            }
        }

    /** Coordinates. There is no rendering of a secret *value* anywhere in this module. */
    private fun secretRef(ref: SecretRef): Json.Obj =
        jsonObject {
            put("name", ref.name.value)
            put("key", ref.key)
        }

    fun status(status: ServerStatus): Json.Obj =
        when (status) {
            is PaperServerStatus -> {
                jsonObject {
                    put("apiVersion", status.apiVersion.wireValue)
                    put("kind", status.kind.wireValue)
                    put("name", status.name.value)
                    put("observedGeneration", status.observedGeneration)
                    put("phase", status.phase)
                    put("observedAt", status.observedAt)
                    put("lastTransitionAt", status.lastTransitionAt)
                    put("ready", status.ready)
                    put("draining", status.draining)
                    putOrNull("image", status.image, ::image)
                    putOrNull("runtime", status.runtime, ::runtime)
                    putOrNull("endpoint", status.endpoint, ::endpoint)
                    putOrNull("players", status.players, ::players)
                    putOrNull("storage", status.storage, ::storage)
                    putOrNull("drain", status.drain, ::drain)
                    putOrNull("failure", status.failure, ::failure)
                    putArray("conditions", status.conditions, ::condition)
                }
            }
        }

    private fun image(image: ImageStatus): Json.Obj =
        jsonObject {
            put("requested", image.requested.canonical)
            put("resolvedDigest", image.resolvedDigest)
            put("pulledAt", image.pulledAt)
            put("available", image.available)
        }

    private fun runtime(runtime: RuntimeIdentity): Json.Obj =
        jsonObject {
            put("node", runtime.node.value)
            put("sandboxId", runtime.sandboxId)
            put("containerId", runtime.containerId)
            put("createdAt", runtime.createdAt)
            put("startedAt", runtime.startedAt)
            put("finishedAt", runtime.finishedAt)
            put("exitCode", runtime.exitCode)
            put("restartCount", runtime.restartCount)
        }

    /** The *server's* address, never a client's. See [ServerEndpoint]. */
    private fun endpoint(endpoint: ServerEndpoint): Json.Obj =
        jsonObject {
            put("node", endpoint.node.value)
            put("address", endpoint.address)
            put("port", endpoint.port)
        }

    /**
     * Counts and a timestamp. There is no identity to render and no field here to
     * add one to; `PlayerOccupancyLeakageTest` pins that.
     */
    private fun players(players: PlayerOccupancy): Json.Obj =
        jsonObject {
            put("online", players.online)
            put("max", players.max)
            put("observedAt", players.observedAt)
        }

    private fun storage(storage: StorageStatus): Json.Obj =
        jsonObject {
            put("persistent", storage.persistent)
            put("volumeName", storage.volumeName?.value)
            put("bound", storage.bound)
            put("lastSaveConfirmedAt", storage.lastSaveConfirmedAt)
        }

    private fun drain(drain: DrainStatus): Json.Obj =
        jsonObject {
            put("state", drain.state)
            put("startedAt", drain.startedAt)
            put("enteredStateAt", drain.enteredStateAt)
            put("playersEvacuated", drain.playersEvacuated)
            put("sealRequestedAt", drain.sealRequestedAt)
            put("saveRequestedAt", drain.saveRequestedAt)
            put("worldSavedAt", drain.worldSavedAt)
            put("worldSaved", drain.worldSaved)
            put("deregisteredAt", drain.deregisteredAt)
            put("transferAttempts", drain.transferAttempts)
            // A server name. Never a player.
            put("destination", drain.destination?.value)
            putOrNull("failure", drain.failure, ::failure)
        }

    private fun failure(failure: FailureStatus): Json.Obj =
        jsonObject {
            put("reason", failure.reason)
            put("failureClass", failure.failureClass)
            // Already redacted upstream for the CRI operations whose request carries a
            // secret. Nothing here un-redacts it, and nothing here adds a raw-state view.
            put("message", failure.message)
            put("occurredAt", failure.occurredAt)
            put("attempts", failure.attempts)
        }

    private fun condition(condition: StatusCondition): Json =
        jsonObject {
            put("type", condition.type)
            put("status", condition.status)
            put("message", condition.message)
            put("lastTransitionAt", condition.lastTransitionAt)
        }

    /**
     * A part of a server's stored state the store holds and cannot decode.
     *
     * `reason` is the store's own operator-facing text and is passed through
     * verbatim, on the same terms as `FailureStatus.message`: it names the server
     * and what about the stored form was rejected, and it carries no secret
     * material because secrets are not in state at all — only their coordinates
     * are. What it deliberately does not carry is the underlying exception: a
     * stack trace out of whichever backend produced it is an internal detail, and
     * `:store` does not put one in this value in the first place.
     */
    private fun unreadable(unreadable: Unreadable): Json.Obj =
        jsonObject {
            put("part", unreadable.part)
            put("reason", unreadable.reason)
            put("retryable", unreadable.retryable)
        }

    /**
     * A row the store has a name for and nothing else.
     *
     * There is no definition, so there is no resource: this is not a
     * [ServerResource][server] with fields missing, and rendering it as one would
     * mean inventing a spec. It is reported at all because absence means
     * something — see the listing endpoints.
     */
    fun unreadableServer(row: UnreadableServer): Json.Obj =
        jsonObject {
            // Raw, exactly as stored. The name can itself be why the row will not
            // read, and a rendering that dropped an invalid one would throw away
            // the only identifying thing left.
            put("name", row.name)
            put("part", row.unreadable.part)
            put("reason", row.unreadable.reason)
            put("retryable", row.unreadable.retryable)
        }

    /** Desired state, observed state and the store's bookkeeping, as one object. */
    fun server(stored: StoredServer): Json.Obj =
        jsonObject {
            put("name", stored.name.value)
            put("kind", stored.definition.definition.kind.wireValue)
            put("apiVersion", stored.definition.definition.apiVersion.wireValue)
            put("definition", definition(stored.definition.definition))
            put(
                "metadata",
                jsonObject {
                    put("generation", stored.definition.generation)
                    put("resourceVersion", stored.definition.resourceVersion.token)
                    put("createdAt", stored.definition.createdAt)
                    put("updatedAt", stored.definition.updatedAt)
                    put("deletedAt", stored.definition.deletedAt)
                    put("terminating", stored.definition.terminating)
                },
            )
            put("status", stored.status?.let { status(it.status) } ?: Json.Null)
            putObject("statusMeta", stored.status) { held ->
                put("resourceVersion", held.resourceVersion.token)
                put("recordedAt", held.recordedAt)
            }
            // Why `status` is null, when the answer is not "nothing has been
            // observed". Null in the ordinary case, so a client that reads
            // `status === null && unreadable === null` still means "not yet looked
            // at" and needs no change to keep working.
            putOrNull("unreadable", stored.unreadable, ::unreadable)
            put("caughtUp", stored.caughtUp)
            put("neverObserved", stored.neverObserved)
            put("display", display(stored))
        }

    /**
     * The one derived view, so that every dashboard does not invent its own.
     *
     * `state` fuses the phase, the drain and the tombstone into a single badge.
     * The order below is the whole definition of it and is deliberately
     * top-down: a delete that has been requested outranks everything, because a
     * server showing `READY` while its name is being reclaimed is the one wrong
     * answer that matters.
     *
     * `needsAttention` stays a flag rather than a state, matching what
     * [ConditionType.NEEDS_ATTENTION] says about itself: it reports, it never
     * authorises. A drain that has been failing for an hour is still `DRAINING`,
     * with the flag raised beside it.
     */
    fun displayState(stored: StoredServer): DisplayState {
        val status = stored.status?.status as? PaperServerStatus
        return when {
            stored.definition.terminating -> {
                DisplayState.TERMINATING
            }

            // Above every phase-derived value, below TERMINATING. Below, because a
            // delete that has been requested is a readable fact about desired
            // state and the more actionable one; the flag beside the badge carries
            // the rest. Above everything else, because there is no phase to derive
            // from — the observation exists and cannot be read.
            stored.unreadable != null -> {
                DisplayState.UNREADABLE
            }

            // `neverObserved`, not `status == null`. An observation that will not
            // decode also leaves `status` null, and rendering that as PENDING tells
            // an operator their server has not been looked at yet when what was
            // recorded about it is in fact corrupt. Wrong in the direction that
            // matters: PENDING is a state you wait out.
            stored.neverObserved -> {
                DisplayState.PENDING
            }

            status == null -> {
                // Unreachable today — `status` is null only when nothing was
                // observed or the observation would not decode, both handled above.
                // Kept because the compiler cannot see that, and UNKNOWN is the
                // honest answer to "there is no status and no reason for it".
                DisplayState.UNKNOWN
            }

            status.draining -> {
                DisplayState.DRAINING
            }

            else -> {
                when (status.phase) {
                    ServerPhase.FAILED -> DisplayState.FAILED
                    ServerPhase.UNKNOWN -> DisplayState.UNKNOWN
                    ServerPhase.PENDING -> DisplayState.PENDING
                    ServerPhase.IMAGE_PULLING, ServerPhase.CREATING, ServerPhase.STARTING -> DisplayState.STARTING
                    ServerPhase.RUNNING -> if (status.ready) DisplayState.READY else DisplayState.RUNNING
                    ServerPhase.DRAINING -> DisplayState.DRAINING
                    ServerPhase.STOPPING -> DisplayState.STOPPING
                    ServerPhase.STOPPED -> DisplayState.STOPPED
                }
            }
        }
    }

    private fun display(stored: StoredServer): Json.Obj {
        val status = stored.status?.status as? PaperServerStatus
        val state = displayState(stored)
        return jsonObject {
            put("state", state)
            put("ready", status?.ready ?: false)
            // An unreadable row raises this as well as the flag below, and the two
            // are not redundant: `unreadable` says *what* is wrong and is what a
            // dashboard filters on, `needsAttention` says *somebody must act* and
            // is what an alert fires on.
            //
            // It qualifies on the charter rather than by analogy. NEEDS_ATTENTION
            // is "this is not going to fix itself and a human has to look at it",
            // and a row the store cannot decode is exactly that: the bytes say the
            // same thing on every pass, so the loop cannot make progress and only
            // a person repairing the row can. Leaving it off would mean an
            // operator alerting on `needsAttention` never sees these servers —
            // which is the one audience that has to.
            put(
                "needsAttention",
                stored.unreadable != null ||
                    status?.conditions?.any {
                        it.type == ConditionType.NEEDS_ATTENTION && it.status == ConditionStatus.TRUE
                    } ?: false,
            )
            // A flag as well as a state, and for the same reason `needsAttention`
            // is one: TERMINATING outranks UNREADABLE, so a terminating server
            // with a corrupt observation carries the fact here rather than losing
            // it. Filter on this, not on `state == 'UNREADABLE'`.
            put("unreadable", stored.unreadable != null)
            put("drainState", status?.drain?.state)
            put("playersOnline", status?.players?.online)
            put(
                "playersMax",
                status?.players?.max ?: (stored.definition.definition as? PaperServerDefinition)?.spec?.maxPlayers,
            )
            put("detail", detail(stored, status, state))
        }
    }

    private fun detail(
        stored: StoredServer,
        status: PaperServerStatus?,
        state: DisplayState,
    ): String =
        when {
            state == DisplayState.TERMINATING && status?.drain != null -> {
                "delete requested; draining (${status.drain?.state?.name?.lowercase()?.replace('_', ' ')})"
            }

            state == DisplayState.TERMINATING && stored.unreadable != null -> {
                "delete requested; the stored observation could not be read, so how far the drain has got " +
                    "is not known — ${stored.unreadable?.reason}"
            }

            state == DisplayState.TERMINATING -> {
                "delete requested; waiting for the reconcile loop to start the drain"
            }

            stored.unreadable != null -> {
                // Says what is unknown rather than what is wrong. The container is
                // very probably still running exactly as it was; what is broken is
                // the record of it, and an operator reading this needs to know the
                // difference before they reach for a restart.
                "the stored observation could not be read, so nothing here reflects what the server is " +
                    "actually doing — ${stored.unreadable?.reason}"
            }

            stored.neverObserved -> {
                "accepted; nothing observed yet"
            }

            status == null -> {
                "there is no observation and no reason recorded for its absence"
            }

            status.failure != null -> {
                status.failure?.message.orEmpty()
            }

            status.drain?.state == DrainState.DRAIN_FAILED -> {
                "the drain aborted; the server is still running"
            }

            status.drain != null -> {
                "draining (${status.drain?.state?.name?.lowercase()?.replace('_', ' ')})"
            }

            !stored.caughtUp -> {
                "the reconcile loop has not caught up with generation ${stored.definition.generation}"
            }

            else -> {
                ""
            }
        }

    /**
     * The badge a dashboard renders. Derived; never stored, never authoritative.
     *
     * ## Why this is `:api`'s and not `:schema`'s
     *
     * It looks like a schema enum and it is not one. `TERMINATING` — the value
     * that outranks every other — is derived from `StoredDefinition.deletedAt`,
     * which is `:store` bookkeeping: a tombstone is not a concept `:schema` has,
     * deliberately, in the same way `ObjectMetadata` deliberately has no
     * `generation`. `PENDING` for an unobserved server is likewise derived from
     * the absence of a `StoredStatus`, not from anything a definition or a status
     * says. Moving this into `:schema` would mean teaching `:schema` about the
     * store, which is a worse trade than owning a presentation enum here.
     *
     * It is still served through `/api/v1/meta` like every other closed set, so a
     * value added here reaches a dashboard's filters with no frontend release —
     * the guarantee holds, it is just not `:schema` that provides it.
     *
     * The `when` over [ServerPhase] in [displayState] is exhaustive with no
     * `else`, so a phase added in `:schema` breaks *this* module's compile until
     * somebody decides which badge it maps to. That is the intended behaviour
     * rather than a maintenance cost: the alternative is a new phase silently
     * rendering as `UNKNOWN` on every dashboard.
     */
    internal enum class DisplayState {
        PENDING,
        STARTING,
        RUNNING,
        READY,
        DRAINING,
        TERMINATING,
        STOPPING,
        STOPPED,
        FAILED,

        /**
         * An observation is stored for this server and cannot be decoded.
         *
         * Not [UNKNOWN], which means the *node or runtime* could not be reached —
         * a fact about the world. This one is a fact about our own record of it:
         * the container is very probably running exactly as it was, and what is
         * broken is what we wrote down. Conflating the two would send an operator
         * to look at the wrong thing.
         */
        UNREADABLE,

        UNKNOWN,
    }
}

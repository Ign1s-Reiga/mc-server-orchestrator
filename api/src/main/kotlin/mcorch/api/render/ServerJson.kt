package mcorch.api.render

import mcorch.api.json.Json
import mcorch.api.json.JsonObjectBuilder
import mcorch.api.json.jsonObject
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainBlock
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
import mcorch.schema.ServerKind
import mcorch.schema.ServerPhase
import mcorch.schema.ServerStatus
import mcorch.schema.StatusCondition
import mcorch.schema.StorageSpec
import mcorch.schema.StorageStatus
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxyStatus
import mcorch.store.StoreException
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

            is VelocityProxyDefinition -> {
                throw notYetRendered(definition.kind)
            }
        }

    /**
     * A kind this module has not been taught to render.
     *
     * `VelocityProxy` is declarable and fully validated in `:schema` and is
     * neither reconciled nor persisted yet — `:store` refuses to hold one — so
     * nothing this module reads from the store can currently be one, and these
     * branches are unreachable rather than merely unimplemented. They exist
     * because the sealed hierarchies made the compiler ask, and a partial
     * rendering would be worse than a refusal: this object's contract is that it
     * emits *every* field of every type, which is what makes "no player identity
     * and no secret material" checkable instead of promised. A half-rendered
     * proxy would quietly break that guarantee.
     *
     * Raised as a [StoreException.Unsupported] so it travels the path this module
     * already has for "the store held something this build cannot work with",
     * rather than as an unhandled 500.
     */
    private fun notYetRendered(kind: ServerKind): StoreException =
        StoreException.Unsupported(
            "this build cannot render a `${kind.wireValue}`: the kind is declarable and validated, but its " +
                "API representation has not been implemented yet",
        )

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

            is VelocityProxyStatus -> {
                throw notYetRendered(status.kind)
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
            // Rendered beside the attempt count because the two answer the
            // dashboard's question together: how many times the loop has asked, and
            // how long it has been asking. The *duration* is the bound step 4
            // actually stops on; the count is a report.
            put("transferStartedAt", drain.transferStartedAt)
            put("transferAttempts", drain.transferAttempts)
            // A server name. Never a player.
            put("destination", drain.destination?.value)
            putOrNull("blocked", drain.blocked, ::blocked)
            putOrNull("failure", drain.failure, ::failure)
        }

    /**
     * A drain that is waiting and not broken.
     *
     * Rendered beside `failure` and never instead of it: a client reads
     * `blocked !== null && failure === null` as *waiting*, and the two are
     * disjoint in everything this API serves. `observations` is deliberately a
     * count of passes rather than a duration — `since` is the instant, and the
     * client does the arithmetic against its own clock rather than against a
     * number that was stale the moment it was written.
     */
    private fun blocked(block: DrainBlock): Json.Obj =
        jsonObject {
            put("reason", block.reason)
            // Counts and prose. There is no player identity in a block message
            // for the same reason there is none in a failure message.
            put("message", block.message)
            put("since", block.since)
            put("observations", block.observations)
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
     * with the flag raised beside it. `drainBlocked` is the same shape of fact
     * pointing the other way — *do not act* — and is a flag for the same reason:
     * a drain waiting on players is still `TERMINATING` or `DRAINING`, and the
     * badge must not be softened to say otherwise while a delete is outstanding.
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
            // The third flag, and the one that answers the question an operator
            // actually asks about a drain that is not moving: *is this stuck, or
            // is it just waiting for people to log off?* Without it the two are
            // indistinguishable from a fleet table — both show `TERMINATING` with
            // a `drainState` of `DRAIN_FAILED` — and the only discriminator a
            // dashboard had was guessing from `playersOnline`, which is a
            // coincidence of today's one block reason rather than the fact itself.
            //
            // It is the inverse of `needsAttention` in what it tells somebody to
            // do — this one says *do not act*.
            //
            // They are **not** mutually exclusive, and a dashboard must not show
            // them as one tri-state. `needsAttention` was widened beyond drains,
            // so a drain can be correctly waiting on players while the loop has
            // separately given up reaching the node: the block is true, the
            // escalation is true, and suppressing either would be the lie. The
            // overlap comes from the pass arm — a failure recorded on the status
            // rather than on the drain — and is reachable with no hand-edited
            // data, because a node failure carries a blocked drain forward
            // untouched. Order them and let the first win; `API.md` §7 has the
            // priority chain.
            put("drainBlocked", drainBlocked(status))
            put("drainState", status?.drain?.state)
            put("playersOnline", status?.players?.online)
            put(
                "playersMax",
                status?.players?.max ?: (stored.definition.definition as? PaperServerDefinition)?.spec?.maxPlayers,
            )
            put("detail", detail(stored, status, state))
        }
    }

    /**
     * The failure recorded on the pass, when it is a different event from the
     * drain's own failure.
     *
     * Null when the two are equal, because that is one event written to two
     * fields — an aborted drain records in both — and the drain branch words it
     * better. See the precedence note on [detail].
     */
    private fun passFailure(status: PaperServerStatus?): FailureStatus? =
        status?.failure?.takeIf { it != status.drain?.failure }

    /** The lead-in a terminating server's sentence carries, so every branch reads the same. */
    private fun terminating(state: DisplayState): String =
        if (state == DisplayState.TERMINATING) "delete requested; " else ""

    /**
     * Whether the drain is waiting on players rather than broken.
     *
     * Read from the `DRAIN_BLOCKED` **condition**, never from `drain.blocked`,
     * and this is the only place that decides it — [display] renders it and
     * [detail] words the sentence from it.
     *
     * The two used to be derived separately, and they disagreed. The condition
     * asks `blocked != null && failure == null`; this module asked
     * `blocked != null`. So a document carrying both — which the design
     * deliberately permits, because the alternative was a decode-time `require`
     * paid by the widest fleet read, where one bad row aborts `listServers` and
     * halts every in-flight drain — rendered `drainBlocked: false` beside
     * `detail: "waiting, not stuck"`. Declining the `require` makes this
     * precedence the entire specification, so it has to hold at every site that
     * reads it, and one function is how that is guaranteed rather than reviewed.
     *
     * It also closes a window nobody had to hand-edit anything to reach: a
     * migrated blocked drain carries `drain.blocked` before the first pass has
     * derived the condition, so for one pass the field is set and the condition
     * is not. Reading the condition renders that pass as *not* blocked, which
     * over-states brokenness — the safe way round for a sentence whose job is to
     * stop somebody being called.
     */
    private fun drainBlocked(status: PaperServerStatus?): Boolean =
        status?.conditions?.any {
            it.type == ConditionType.DRAIN_BLOCKED && it.status == ConditionStatus.TRUE
        } ?: false

    /**
     * The operator-facing sentence.
     *
     * ## A failure outranks a block, and which failure wins
     *
     * "Waiting, not stuck" tells somebody *not* to act, and it is only true while
     * the loop is running. Any failure means it is not, so the precedence is:
     *
     * 1. `status.failure`, **when it is not the drain's own failure**. A
     *    `NodeException` leaves the drain untouched and records here, so this is a
     *    verdict on *now* while every other field is a snapshot from whenever the
     *    loop last got through. If the node cannot be reached, nothing else is
     *    being updated, and that fact explains all the others.
     * 2. `drain.failure` — the drain itself aborted.
     * 3. the block — waiting on players, and genuinely nothing to do.
     *
     * The qualifier on (1) is load-bearing rather than pedantic. A drain that
     * aborts is recorded in *both* fields as the same value, and ranking
     * `status.failure` first unconditionally would drop the "the drain aborted"
     * framing for every genuinely failed drain — the more informative sentence,
     * lost to a rule meant to catch a different case. Comparing the two values
     * separates "one event, described twice" from "a second, newer thing has gone
     * wrong", and only the latter outranks. It also gets the compound case right:
     * a node failure *after* a drain aborted reports the node, because the two
     * failures then differ.
     *
     * The reachable sequence this ordering exists for needs no hand-edited data:
     * a drain blocks on players online, the next pass throws a `NodeException`,
     * and `Reconciler.nodeFailure` carries the block forward while recording the
     * node failure. Without the ordering an operator is told "waiting, not stuck
     * — the drain resumes on its own once it is empty" about a server whose node
     * the loop cannot reach, and the node failure's message is not rendered
     * anywhere at all.
     *
     * Note that `drainBlocked` stays true in that case, because the condition it
     * comes from is `:core`'s and is still accurate — the drain *is* blocked. The
     * flag says what the drain is doing; this sentence says whether anybody
     * should act. A client must read `needsAttention` and `status.failure` too:
     * `drainBlocked` alone is not permission to ignore a server.
     */
    private fun detail(
        stored: StoredServer,
        status: PaperServerStatus?,
        state: DisplayState,
    ): String =
        when {
            state == DisplayState.TERMINATING && stored.unreadable != null -> {
                "delete requested; the stored observation could not be read, so how far the drain has got " +
                    "is not known — ${stored.unreadable?.reason}"
            }

            // A failure recorded on the pass that is *not* the drain's own, ahead
            // of every drain branch below. See the note above: this is the one that
            // stops a server whose node is unreachable being described as quietly
            // waiting.
            passFailure(status) != null -> {
                terminating(state) +
                    if (drainBlocked(status)) {
                        "the drain is waiting for players to leave, but the last pass did not complete, so " +
                            "it is not resuming on its own — ${passFailure(status)?.message.orEmpty()}"
                    } else {
                        passFailure(status)?.message.orEmpty()
                    }
            }

            // The drain aborted. Above the block because it is a failure and a
            // failure outranks a reassurance; below the branch above because when
            // the two failures are the same event this framing is the better one.
            status?.drain?.failure != null -> {
                terminating(state) + "the drain aborted; the server is still running — " +
                    status.drain
                        ?.failure
                        ?.message
                        .orEmpty()
            }

            // Ahead of the general drain branches below, and this ordering is the
            // whole operator-facing point of it. A delete requested on a server
            // people are playing on used to render as "delete requested; draining
            // (drain failed)" — the exact question this is meant to answer,
            // answered wrongly: the drain has not failed, and there is nothing to
            // do but wait.
            drainBlocked(status) -> {
                terminating(state) + "waiting, not stuck — ${status?.drain?.blocked?.message.orEmpty()}"
            }

            state == DisplayState.TERMINATING && status?.drain != null -> {
                "delete requested; draining (${status.drain?.state?.name?.lowercase()?.replace('_', ' ')})"
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

            // Reached only when the drain is parked in DRAIN_FAILED with neither a
            // recorded failure nor a block — nothing says why, so this says only
            // what is certain.
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

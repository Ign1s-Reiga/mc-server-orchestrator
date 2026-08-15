package mcorch.api.routes

import mcorch.api.http.Access
import mcorch.api.http.ApiException
import mcorch.api.http.ErrorCode
import mcorch.api.http.HandlerResult
import mcorch.api.http.HeaderNames
import mcorch.api.http.Request
import mcorch.api.http.Requests
import mcorch.api.http.Response
import mcorch.api.http.Route
import mcorch.api.json.Json
import mcorch.api.json.jsonObject
import mcorch.api.render.ServerJson
import mcorch.core.termination.ForcedTermination
import mcorch.core.termination.ForcedTerminationRefused
import mcorch.core.termination.ForcedTerminationUnavailable
import mcorch.core.termination.OccupancyAcknowledgement
import mcorch.schema.DrainState
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.schema.SchemaViolation
import mcorch.schema.ServerDefinition
import mcorch.schema.Tier
import mcorch.store.Precondition
import mcorch.store.Store
import mcorch.store.StoreException
import mcorch.store.StoredDefinition
import mcorch.store.StoredServer
import mcorch.store.UnreadableServer
import mcorch.store.WriteOutcome
import org.slf4j.LoggerFactory

/**
 * CRUD over server definitions, and reads of what the loop observed.
 *
 * ## Every mutation here is a write to desired state
 *
 * There is no imperative operation in this file. Creating a server writes a
 * definition and returns; the reconcile loop pulls the image, creates the
 * sandbox and starts the container. Deleting one tombstones the definition and
 * returns `202 Accepted`; the loop sees a terminating definition
 * (`Reconciler.drainCause` → `DELETION`), runs the drain protocol, and only then
 * does `:core` call `purge` to free the name. Changing a spec is the same story
 * with `REPLACEMENT` as the cause.
 *
 * That is why this module has no `:core` edge and no `:cri` edge, and why there
 * is no "stop", "kill" or "force" anywhere: an endpoint that could stop a
 * container directly would be an endpoint that could stop one with players on
 * it. `purge` is deliberately not exposed either — the guard that it only
 * happens once the containers are gone lives in `:core`, and an API that could
 * reach past that guard would orphan a running container.
 */
internal class ServerRoutes(
    private val store: Store,
    private val forced: ForcedTermination,
) {
    fun routes(): List<Route> =
        listOf(
            Route("GET", SERVERS, Access.AtLeast(Tier.MEMBER)) { request, _ -> HandlerResult.Send(list(request)) },
            Route("POST", SERVERS, Access.AtLeast(Tier.OPERATOR)) { request, _ -> HandlerResult.Send(create(request)) },
            Route("GET", SERVER, Access.AtLeast(Tier.MEMBER)) { request, _ -> HandlerResult.Send(get(request)) },
            Route("PUT", SERVER, Access.AtLeast(Tier.OPERATOR)) { request, _ -> HandlerResult.Send(replace(request)) },
            Route(
                "DELETE",
                SERVER,
                Access.AtLeast(Tier.SUPERUSER),
            ) { request, _ -> HandlerResult.Send(delete(request)) },
            Route(
                "GET",
                "$SERVER/status",
                Access.AtLeast(Tier.MEMBER),
            ) { request, _ -> HandlerResult.Send(status(request)) },
            Route(
                "POST",
                "/api/v1/validate",
                Access.AtLeast(Tier.MEMBER),
            ) { request, _ -> HandlerResult.Send(validate(request)) },
        )

    /**
     * The list, plus the change-feed cursor to stream from.
     *
     * The cursor is read **before** the list, and the order is load-bearing. Read
     * the other way round, a definition written between the two reads is in
     * neither, and a dashboard that lists then streams would never learn about
     * it. This way it is in the stream instead — at-least-once, which a client
     * that keys by name absorbs without noticing.
     */
    private suspend fun list(request: Request): Response {
        val cursor = store.currentCursor()
        val filter = ListFilter.of(request)
        val listing = store.listAll()
        val items = listing.servers.filter(filter::matches).sortedBy { it.name.value }
        val unreadable = listing.unreadable.sortedBy { it.name }
        return Response.json(
            200,
            jsonObject {
                put("cursor", cursor.token)
                put("count", items.size)
                putArray("items", items, ServerJson::server)
                // Never filtered, and that is deliberate. A row with no readable
                // definition cannot answer "is it READY", "does it carry this
                // label" or "is it terminating", so any filter would drop it — and
                // dropping it is indistinguishable from the server having been
                // purged. Its own array keeps it out of `items` without hiding it.
                put("unreadableCount", unreadable.size)
                putArray("unreadable", unreadable, ServerJson::unreadableServer)
            },
        )
    }

    private suspend fun get(request: Request): Response {
        val stored = mustFind(Requests.name(request))
        return Response.json(200, ServerJson.server(stored), etag(stored))
    }

    private suspend fun status(request: Request): Response {
        val stored = mustFind(Requests.name(request))
        stored.unreadable?.let { throw ApiException.unreadable(stored.name.value, it) }
        val held =
            stored.status
                ?: throw ApiException(
                    ErrorCode.NOT_FOUND,
                    "`${stored.name}` has no observation yet; the reconcile loop has not looked at it",
                )
        return Response.json(
            200,
            jsonObject {
                put("name", stored.name.value)
                put("observedGeneration", held.status.observedGeneration)
                put("generation", stored.definition.generation)
                put("caughtUp", stored.caughtUp)
                put("recordedAt", held.recordedAt)
                put("resourceVersion", held.resourceVersion.token)
                put("status", ServerJson.status(held.status))
            },
        )
    }

    /**
     * Creates. Always `Precondition.Absent`, so a `POST` can never overwrite.
     *
     * Two operators pasting the same YAML at the same time is the case this
     * covers: exactly one gets a 201 and the other a 409 naming
     * `ALREADY_EXISTS`, rather than one of them silently winning.
     */
    private suspend fun create(request: Request): Response {
        val definition = Requests.definition(request)
        val stored =
            when (val outcome = store.putDefinition(definition, Precondition.Absent)) {
                is WriteOutcome.Applied -> outcome.value
                is WriteOutcome.Conflict -> throw ApiException.conflict(outcome)
            }
        LOG.info(
            "definition created name={} kind={} generation={}",
            stored.name,
            definition.kind,
            stored.generation,
        )
        val server = store.getServer(stored.name) ?: StoredServer(stored)
        return Response.json(
            201,
            ServerJson.server(server),
            etag(server) + ("Location" to "$SERVERS/${stored.name.value}"),
        )
    }

    /**
     * Replaces, and requires `If-Match`.
     *
     * A `PUT` with no `If-Match` is refused with `428 Precondition Required`
     * rather than accepted as last-write-wins. Two operators editing the same
     * server from two dashboard tabs is the ordinary case, not the exotic one,
     * and a spec change here is not a database row: it makes the loop drain the
     * running server and replace it. Silently discarding somebody's edit is bad;
     * silently discarding it *and* restarting a server full of players to do so
     * is worse.
     *
     * `If-Match: *` is the deliberate override — "overwrite whatever is there,
     * but it must exist".
     */
    private suspend fun replace(request: Request): Response {
        val name = Requests.name(request)
        val definition = Requests.definition(request)
        rejectNameMismatch(name, definition)

        val ifMatch = Requests.precondition(request)
        if (ifMatch == Requests.IfMatch.Absent) {
            throw ApiException(
                ErrorCode.PRECONDITION_REQUIRED,
                "PUT requires an ${HeaderNames.IF_MATCH} header carrying the resourceVersion you read, so a " +
                    "concurrent edit cannot be overwritten unnoticed. Send ${HeaderNames.IF_MATCH}: * to " +
                    "overwrite deliberately",
            )
        }
        if (ifMatch == Requests.IfMatch.Any) {
            // `*` means "it must exist". The store has no precondition for that, so
            // it is a read — racy by nature, and honestly so: the write that follows
            // is unconditional because that is what the caller asked for.
            //
            // A row whose definition will not decode counts as existing, and this
            // is the recovery path for one: `PUT` with `If-Match: *` writes a
            // definition the store can read again, which is the only way an
            // operator repairs such a row through this API. Requiring the old one
            // to be readable first would make the broken case the unfixable one.
            if (resolve(name) == Resolution.Absent) throw ApiException.notFound("no server named `$name`")
        }

        val stored =
            when (val outcome = store.putDefinition(definition, ifMatch.toPrecondition())) {
                is WriteOutcome.Applied -> outcome.value
                is WriteOutcome.Conflict -> throw ApiException.conflict(outcome)
            }
        LOG.info("definition replaced name={} generation={}", stored.name, stored.generation)
        val server = store.getServer(stored.name) ?: StoredServer(stored)
        return Response.json(200, ServerJson.server(server), etag(server))
    }

    /**
     * Requests a delete, and returns immediately with `202 Accepted`.
     *
     * **This is a drain request, not a stop.** The definition is tombstoned and
     * stays readable; the loop reads the spec it is about to drain against — the
     * save timeout, the stop grace period — evacuates players, waits for a
     * confirmed world save, and only then stops the container. The name is freed
     * by `:core` afterwards.
     *
     * So the row does not disappear on the next `GET`. It comes back with
     * `metadata.terminating: true` and `display.state: TERMINATING`, and it keeps
     * coming back — with the drain's progress under `status.drain` — until the
     * drain finishes. A dashboard showing a delete as an instant disappearance is
     * showing something that did not happen; showing it as a state is the point.
     *
     * There is no force flag and there will not be one.
     */
    private suspend fun delete(request: Request): Response {
        val name = Requests.name(request)
        val ifMatch = Requests.precondition(request)
        val forced = request.queryValue("force") == "true"
        val existing = mustFind(name)

        // Every refusal is decided **before** the tombstone is written, and that is
        // load-bearing twice over. The first version wrote it first and then
        // answered 409 for a proxy — which for a proxy is a fleet-wide deletion
        // delivered under a status that says nothing happened. The second kept the
        // seam's own refusals below it, and a tombstoned definition cannot be
        // edited (`ConflictReason.TERMINATING`), so a refusal saying "correct that
        // field and force again" left the server permanently `crictl`-only.
        val forcible = if (forced) forcible(name, existing) else null
        val acknowledgement = if (forced) occupancyAcknowledgement(request) else OccupancyAcknowledgement.None
        // The dispatched-stop and outstanding-save guards used to live here. They
        // moved into the seam in round 51: here they protected this route and
        // nothing else, and a second caller of `ForcedTermination` got none of
        // them. `preflight` runs both, still above the tombstone.
        if (forcible != null) preflight(forcible, acknowledgement)

        if (!forced && ifMatch == Requests.IfMatch.Any && existing.definition.terminating) {
            // Already tombstoned: nothing to do, and reporting it as a conflict
            // would make a retried delete look like a failure.
            return accepted(existing)
        }
        val stored =
            if (existing.definition.terminating) {
                // Already terminating, so there is no delete to write — but the
                // precondition still has to be honoured, or a force with a stale
                // `If-Match` would proceed where an ordinary delete would not.
                requireVersionMatches(ifMatch, existing)
                existing.definition
            } else {
                when (val outcome = store.deleteDefinition(name, ifMatch.toPrecondition())) {
                    is WriteOutcome.Applied -> outcome.value
                    is WriteOutcome.Conflict -> throw ApiException.conflict(outcome)
                }
            }
        if (forcible != null) {
            return force(request, forcible, name, acknowledgement, drainStateOf(existing))
        }
        LOG.info("delete requested name={} generation={}", stored.name, stored.generation)
        return accepted(store.getServer(name) ?: StoredServer(stored))
    }

    /**
     * Honours `If-Match` on a path that writes nothing.
     *
     * The already-terminating branch skips `deleteDefinition`, and with it the
     * precondition that call would have checked — so a force carrying a stale
     * version would proceed where an ordinary delete refuses.
     */
    private fun requireVersionMatches(
        ifMatch: Requests.IfMatch,
        existing: StoredServer,
    ) {
        val required = (ifMatch as? Requests.IfMatch.Version)?.resourceVersion ?: return
        if (existing.definition.resourceVersion != required) {
            throw ApiException(
                ErrorCode.CONFLICT,
                "the definition has changed since ${required.token}; re-read it before forcing",
            )
        }
    }

    /** The definition a force would act on, or a refusal. Decided before anything is written. */
    private fun forcible(
        name: ResourceName,
        existing: StoredServer,
    ): PaperServerDefinition =
        existing.definition.definition as? PaperServerDefinition
            ?: throw ApiException(
                ErrorCode.FORCE_NOT_APPLICABLE,
                "`${name.value}` is not a PaperServer. A VelocityProxy holds no world, so its drain cannot " +
                    "stall on a save and there is nothing here to force",
            )

    /** What the drain had reached when force was applied, for the audit record. */
    private fun drainStateOf(existing: StoredServer): DrainState? =
        (existing.status?.status as? PaperServerStatus)?.drain?.state

    /**
     * The occupancy the caller says they were shown.
     *
     * A count and not a flag — `ForcedTermination`'s KDoc has the reasoning. The
     * literal `unreadable` is spelt out rather than allowing a wildcard, so
     * acknowledging a wedged server cannot cover a server that answered with
     * players on it. A boolean `true` is refused rather than quietly read as
     * either, because it is exactly the "proceed regardless" this replaced.
     */
    private fun occupancyAcknowledgement(request: Request): OccupancyAcknowledgement {
        val raw = request.queryValue("acknowledgeOccupancy") ?: return OccupancyAcknowledgement.None
        if (raw == "unreadable") return OccupancyAcknowledgement.Unreadable
        val count = raw.toIntOrNull()
        if (count == null || count < 0) {
            throw ApiException(
                ErrorCode.BAD_REQUEST,
                "acknowledgeOccupancy takes the player count you were shown, or `unreadable` when the " +
                    "server did not answer one — not `$raw`. Read the server first and acknowledge what it " +
                    "reported",
            )
        }
        return OccupancyAcknowledgement.Count(count)
    }

    /**
     * The seam's own refusals, asked while the definition can still be edited.
     *
     * `ForcedTerminationUnavailable` is not a refusal: there is no container, so
     * the delete below stands on its own and the loop tears down what is left.
     */
    private suspend fun preflight(
        definition: PaperServerDefinition,
        acknowledgement: OccupancyAcknowledgement,
    ) {
        try {
            forced.preflight(definition, acknowledgement)
        } catch (unavailable: ForcedTerminationUnavailable) {
            LOG.debug("force preflight found nothing to stop: {}", unavailable.message)
        } catch (refused: ForcedTerminationRefused) {
            throw ApiException(ErrorCode.FORCE_REFUSED, refused.message ?: "the forced stop was refused")
        }
    }

    /** Operator-facing, and never a player name: a count, or the fact there was none. */
    private fun describeAcknowledgement(acknowledgement: OccupancyAcknowledgement): String =
        when (acknowledgement) {
            is OccupancyAcknowledgement.None -> "none"
            is OccupancyAcknowledgement.Unreadable -> "unreadable"
            is OccupancyAcknowledgement.Count -> acknowledgement.players.toString()
        }

    /**
     * Stops the container without waiting for the drain to be satisfied.
     *
     * Tombstoned first, above, and deliberately in that order: a terminating
     * definition keeps reconciling, so the loop is already watching and finishes
     * the teardown the moment it sees the container stopped.
     *
     * The response leads with what was lost rather than with success, because
     * `saveAttempted`, `saveConfirmed` and `playersOnline` are the parts of it an
     * operator has to read.
     */
    private suspend fun force(
        request: Request,
        definition: PaperServerDefinition,
        name: ResourceName,
        acknowledgement: OccupancyAcknowledgement,
        drainState: DrainState?,
    ): Response {
        val principal = request.principal()
        val outcome =
            try {
                forced.stop(definition, acknowledgement)
            } catch (unavailable: ForcedTerminationUnavailable) {
                // Nothing to force, and the tombstone above is already written — so
                // this degenerates into the ordinary delete, which is exactly right:
                // the loop tears down a stopped container without any of this.
                //
                // Answering 409 here would be a refusal that had already deleted the
                // thing it declined to touch, and would make a retried force fail
                // where a retried DELETE answers 202.
                LOG.info("force had nothing to stop name={}; the ordinary teardown carries it", name.value)
                return accepted(
                    store.getServer(name) ?: throw ApiException.notFound("no server named `${name.value}`"),
                    forced = false,
                )
            } catch (refused: ForcedTerminationRefused) {
                throw ApiException(
                    ErrorCode.FORCE_REFUSED,
                    refused.message ?: "the forced stop was refused",
                )
            }
        // Warn, not info, and every field an investigator reads first is on it.
        LOG.warn(
            // Every field `spec/termination/02-force-stop.md` §6 lists, because
            // until that sink exists this line *is* the audit record — and a claim
            // that it carries them all is only worth making if it does. `acknowledged`
            // and `drainState` were the two it did not: without the first, nothing
            // says what population the operator signed off; without the second,
            // nothing says how far the drain had got when they gave up on it. The
            // status row that would answer either is purged by teardown.
            "forced stop identity={} server={} acknowledged={} drainState={} saveAttempted={} " +
                "saveConfirmed={} playersOnline={}",
            principal.name,
            name.value,
            describeAcknowledgement(acknowledgement),
            drainState ?: "none",
            outcome.saveAttempted,
            outcome.saveConfirmed,
            outcome.playersOnline ?: "unknown",
        )
        return Response.json(
            202,
            jsonObject {
                put("accepted", true)
                put("forced", true)
                put("saveAttempted", outcome.saveAttempted)
                put("saveConfirmed", outcome.saveConfirmed)
                // Null means the server did not answer a count. It is not zero, and
                // a client must not render it as one.
                put("playersOnline", Json.of(outcome.playersOnline))
                put("detail", outcome.detail)
                put(
                    "message",
                    "the container was stopped. The reconcile loop completes the teardown and frees the name " +
                        "once it observes the stopped container; poll this server until it reports 404",
                )
            },
        )
    }

    private fun accepted(
        server: StoredServer,
        forced: Boolean? = null,
    ): Response =
        Response.json(
            202,
            jsonObject {
                put("accepted", true)
                // Only present on a forced request, so an ordinary delete's body is
                // unchanged. `false` means the force found nothing to stop and the
                // delete stands on its own.
                forced?.let { put("forced", it) }
                put(
                    "message",
                    "the delete was recorded. The reconcile loop drains the server — evacuating players and " +
                        "confirming a world save — before anything is stopped, and frees the name only when " +
                        "that has finished. Poll this server, or watch the event stream, until it reports " +
                        "404 NOT_FOUND",
                )
                put("server", ServerJson.server(server))
            },
            etag(server),
        )

    /**
     * Validates a document without writing anything.
     *
     * For the editor in the dashboard: it lets a form show every field error as
     * the operator types, against the same parser that would reject the document
     * on submit, so the two can never disagree. It writes nothing and reads
     * nothing, but it is not public — the violation text describes this
     * deployment's rules.
     */
    private fun validate(request: Request): Response {
        val definition = Requests.definition(request)
        return Response.json(
            200,
            jsonObject {
                put("valid", true)
                // The *effective* definition: every default resolved. Showing an
                // operator what their omissions became is most of the value here.
                put("definition", ServerJson.definition(definition))
            },
        )
    }

    private suspend fun mustFind(name: ResourceName): StoredServer =
        when (val resolution = resolve(name)) {
            is Resolution.Found -> resolution.server
            is Resolution.Unreadable -> throw ApiException.unreadable(resolution.row)
            Resolution.Absent -> throw ApiException.notFound("no server named `$name`")
        }

    /**
     * One server, tolerantly.
     *
     * [Store.getServer] is strict by design: it fails when either half of the row
     * will not decode, because a caller that named one server wants the answer for
     * that server and a half-read snapshot would silently say "there is no
     * observation". That is the right default and it is the wrong answer *here* —
     * this module has somewhere honest to put the fact, so it can render the row
     * with `unreadable` set instead of a 500.
     *
     * So the strict read is tried first and the tolerant listing is consulted only
     * when it fails. The extra read costs nothing in the ordinary case, which is
     * the one that happens.
     *
     * A retryable failure is *not* caught: that is the store being unreachable
     * rather than a row being corrupt, the listing would fail the same way, and
     * the caller wants the 503.
     */
    private suspend fun resolve(name: ResourceName): Resolution {
        val direct =
            try {
                return Resolution.Found(store.getServer(name) ?: return Resolution.Absent)
            } catch (failure: StoreException) {
                if (failure.retryable) throw failure
                failure
            }
        LOG.warn("`{}` did not decode; falling back to the tolerant listing", name, direct)
        val listing = store.listAll()
        listing.servers.firstOrNull { it.name == name }?.let { return Resolution.Found(it) }
        listing.unreadable.firstOrNull { it.name == name.value }?.let { return Resolution.Unreadable(it) }
        // The strict read failed and the tolerant one does not have the row at
        // all. Reporting 404 would be a guess; the original failure is the truth.
        throw direct
    }

    private sealed interface Resolution {
        data class Found(
            val server: StoredServer,
        ) : Resolution

        /** The name is stored and its desired state will not decode. There is no resource. */
        data class Unreadable(
            val row: UnreadableServer,
        ) : Resolution

        data object Absent : Resolution
    }

    private fun rejectNameMismatch(
        name: ResourceName,
        definition: ServerDefinition,
    ) {
        if (definition.metadata.name == name) return
        throw Requests.validationFailed(
            listOf(
                SchemaViolation(
                    field = "metadata.name",
                    problem =
                        "must match the name in the request path `${name.value}`, found " +
                            "`${definition.metadata.name.value}`. Renaming a server is a create and a delete, " +
                            "not an edit: the old one has to be drained before its name is released",
                ),
            ),
        )
    }

    private fun etag(server: StoredServer): List<Pair<String, String>> =
        listOf(HeaderNames.ETAG to "\"${server.definition.resourceVersion.token}\"")

    /** Query filters. Equality only, applied in this module — the store keeps no policy. */
    private class ListFilter(
        private val labels: Map<String, String>,
        private val states: Set<String>,
        private val terminating: Boolean?,
    ) {
        fun matches(server: StoredServer): Boolean {
            val declared = server.definition.definition.metadata.labels
            if (labels.any { (key, value) -> declared[key] != value }) return false
            if (terminating != null && server.definition.terminating != terminating) return false
            if (states.isNotEmpty() && ServerJson.displayState(server).name !in states) return false
            return true
        }

        companion object {
            fun of(request: Request): ListFilter =
                ListFilter(
                    labels = parseSelector(request.queryValue("labelSelector")),
                    states = request.queryValues("state").map { it.uppercase() }.toSet(),
                    terminating =
                        when (val raw = request.queryValue("terminating")?.lowercase()) {
                            null, "any" -> null

                            "true" -> true

                            "false" -> false

                            else -> throw ApiException.badRequest(
                                "`terminating` must be true, false or any, found `$raw`",
                            )
                        },
                )

            private fun parseSelector(raw: String?): Map<String, String> {
                if (raw.isNullOrBlank()) return emptyMap()
                return raw
                    .split(',')
                    .filter { it.isNotBlank() }
                    .associate { term ->
                        val separator = term.indexOf('=')
                        if (separator <= 0) {
                            throw ApiException.badRequest(
                                "`labelSelector` terms must be `key=value`, found `${term.trim()}`",
                            )
                        }
                        term.substring(0, separator).trim() to term.substring(separator + 1).trim()
                    }
            }
        }
    }

    companion object {
        const val SERVERS: String = "/api/v1/servers"
        const val SERVER: String = "/api/v1/servers/{name}"

        private val LOG = LoggerFactory.getLogger(ServerRoutes::class.java)
    }
}

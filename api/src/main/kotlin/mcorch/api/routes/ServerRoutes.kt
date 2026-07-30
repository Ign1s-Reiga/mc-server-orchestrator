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
import mcorch.api.json.jsonObject
import mcorch.api.render.ServerJson
import mcorch.schema.ResourceName
import mcorch.schema.SchemaViolation
import mcorch.schema.ServerDefinition
import mcorch.store.Precondition
import mcorch.store.Store
import mcorch.store.StoredServer
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
) {
    fun routes(): List<Route> =
        listOf(
            Route("GET", SERVERS, Access.OPERATOR) { request, _ -> HandlerResult.Send(list(request)) },
            Route("POST", SERVERS, Access.OPERATOR) { request, _ -> HandlerResult.Send(create(request)) },
            Route("GET", SERVER, Access.OPERATOR) { request, _ -> HandlerResult.Send(get(request)) },
            Route("PUT", SERVER, Access.OPERATOR) { request, _ -> HandlerResult.Send(replace(request)) },
            Route("DELETE", SERVER, Access.OPERATOR) { request, _ -> HandlerResult.Send(delete(request)) },
            Route("GET", "$SERVER/status", Access.OPERATOR) { request, _ -> HandlerResult.Send(status(request)) },
            Route("POST", "/api/v1/validate", Access.OPERATOR) { request, _ -> HandlerResult.Send(validate(request)) },
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
        val items = store.listServers().filter(filter::matches).sortedBy { it.name.value }
        return Response.json(
            200,
            jsonObject {
                put("cursor", cursor.token)
                put("count", items.size)
                putArray("items", items, ServerJson::server)
            },
        )
    }

    private suspend fun get(request: Request): Response {
        val stored = mustFind(Requests.name(request))
        return Response.json(200, ServerJson.server(stored), etag(stored))
    }

    private suspend fun status(request: Request): Response {
        val stored = mustFind(Requests.name(request))
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
            mustFind(name)
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
        val existing = mustFind(name)
        if (ifMatch == Requests.IfMatch.Any && existing.definition.terminating) {
            // Already tombstoned: nothing to do, and reporting it as a conflict
            // would make a retried delete look like a failure.
            return accepted(existing)
        }
        val stored =
            when (val outcome = store.deleteDefinition(name, ifMatch.toPrecondition())) {
                is WriteOutcome.Applied -> outcome.value
                is WriteOutcome.Conflict -> throw ApiException.conflict(outcome)
            }
        LOG.info("delete requested name={} generation={}", stored.name, stored.generation)
        return accepted(store.getServer(name) ?: StoredServer(stored))
    }

    private fun accepted(server: StoredServer): Response =
        Response.json(
            202,
            jsonObject {
                put("accepted", true)
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
        store.getServer(name)
            ?: throw ApiException.notFound("no server named `$name`")

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

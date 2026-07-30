package mcorch.api.routes

import mcorch.api.ApiConfig
import mcorch.api.http.Access
import mcorch.api.http.HandlerResult
import mcorch.api.http.Response
import mcorch.api.http.Route
import mcorch.api.json.Json
import mcorch.api.json.jsonObject
import mcorch.api.render.ServerJson
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainPolicy
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerKind
import mcorch.schema.ServerPhase
import mcorch.schema.StorageMode
import mcorch.store.StatePart

/**
 * The two endpoints that describe the server rather than what it manages.
 *
 * `/healthz` is the only unauthenticated route that returns a body, and it says
 * one word. It deliberately does not touch the store: a liveness probe that
 * fails when the database is slow turns a degraded API into a restarted one, and
 * restarting the process does not repair a database.
 *
 * `/api/v1/meta` exists so the dashboard does not hard-code the enumerations it
 * renders. Every closed set the API can return — phases, drain states, condition
 * types, failure reasons, display states — is listed there, so a new value added
 * in `:schema` appears in the dashboard's filters without a frontend release.
 */
internal class MetaRoutes(
    private val config: ApiConfig,
) {
    fun routes(): List<Route> =
        listOf(
            Route("GET", "/healthz", Access.PUBLIC) { _, _ ->
                HandlerResult.Send(Response.json(200, jsonObject { put("status", "ok") }))
            },
            Route("GET", "/api/v1/meta", Access.OPERATOR) { _, _ -> HandlerResult.Send(meta()) },
        )

    private fun meta(): Response =
        Response.json(
            200,
            jsonObject {
                put("apiVersions", Json.strings(SchemaVersion.supported()))
                put("currentApiVersion", SchemaVersion.CURRENT.wireValue)
                put("kinds", Json.strings(ServerKind.supported()))
                // Every closed set that can appear in a response or is needed to
                // build a create form. Two spellings, and the split is not
                // cosmetic: a set that appears in *observed state* is serialised
                // by its Kotlin name (`RUNNING`), and one that appears in a
                // *definition* is serialised by its YAML wire value
                // (`persistent`), because a definition document has to parse. The
                // key names say which — `…State`/`…Type`/`…Reason` are the former,
                // `storageMode`/`drainPolicy` the latter.
                put(
                    "enums",
                    jsonObject {
                        put("phase", Json.strings(ServerPhase.entries.map { it.name }))
                        put("drainState", Json.strings(DrainState.entries.map { it.name }))
                        put("conditionType", Json.strings(ConditionType.entries.map { it.name }))
                        put("conditionStatus", Json.strings(ConditionStatus.entries.map { it.name }))
                        put("failureReason", Json.strings(FailureReason.entries.map { it.name }))
                        put("failureClass", Json.strings(FailureClass.entries.map { it.name }))
                        put("displayState", Json.strings(ServerJson.DisplayState.entries.map { it.name }))
                        put("statePart", Json.strings(StatePart.entries.map { it.name }))
                        // Wire values: these go back into a definition document.
                        put("storageMode", Json.strings(StorageMode.supported()))
                        put("drainPolicy", Json.strings(DrainPolicy.supported()))
                    },
                )
                put(
                    "limits",
                    jsonObject {
                        put("maxBodyBytes", config.maxBodyBytes)
                        put("maxStreams", config.maxStreams)
                    },
                )
                put(
                    "stream",
                    jsonObject {
                        put("path", StreamRoutes.STREAM)
                        put("changePollMillis", config.changePollInterval.inWholeMilliseconds)
                        put("statusPollMillis", config.statusPollInterval.inWholeMilliseconds)
                        put("keepAliveMillis", config.streamKeepAlive.inWholeMilliseconds)
                        put("maxLifetimeMillis", config.maxStreamLifetime.inWholeMilliseconds)
                        put("reconnectMillis", config.streamReconnectDelay.inWholeMilliseconds)
                    },
                )
            },
        )
}

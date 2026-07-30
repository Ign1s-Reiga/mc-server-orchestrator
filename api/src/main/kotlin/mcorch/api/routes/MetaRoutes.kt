package mcorch.api.routes

import mcorch.api.ApiConfig
import mcorch.api.http.Access
import mcorch.api.http.HandlerResult
import mcorch.api.http.Response
import mcorch.api.http.Route
import mcorch.api.json.Json
import mcorch.api.json.jsonObject
import mcorch.api.render.ServerJson
import mcorch.schema.ConditionType
import mcorch.schema.DrainState
import mcorch.schema.FailureReason
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerKind
import mcorch.schema.ServerPhase

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
                put(
                    "enums",
                    jsonObject {
                        put("phase", Json.strings(ServerPhase.entries.map { it.name }))
                        put("drainState", Json.strings(DrainState.entries.map { it.name }))
                        put("conditionType", Json.strings(ConditionType.entries.map { it.name }))
                        put("failureReason", Json.strings(FailureReason.entries.map { it.name }))
                        put("displayState", Json.strings(ServerJson.DisplayState.entries.map { it.name }))
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
                    },
                )
            },
        )
}

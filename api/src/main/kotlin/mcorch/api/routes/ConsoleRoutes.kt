package mcorch.api.routes

import mcorch.api.auth.Principal
import mcorch.api.http.Access
import mcorch.api.http.ApiException
import mcorch.api.http.ErrorCode
import mcorch.api.http.HandlerResult
import mcorch.api.http.Request
import mcorch.api.http.Requests
import mcorch.api.http.Response
import mcorch.api.http.Route
import mcorch.api.json.Json
import mcorch.api.json.jsonObject
import mcorch.core.console.ConsoleDecision
import mcorch.core.console.ConsolePolicy
import mcorch.core.console.ConsoleTimedOut
import mcorch.core.console.ConsoleUnavailable
import mcorch.core.console.ServerConsole
import mcorch.schema.PaperServerDefinition
import mcorch.schema.Tier
import mcorch.store.Store
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * The remote console.
 *
 * **This is the one endpoint in this API that is not a write to desired state.**
 * `api/API.md` §1 tells every client that a 2xx means the request was recorded,
 * not that the world has changed; here a `200` means the command ran on the
 * server. There is no reconcile loop behind it and no status to watch afterwards.
 *
 * ## Two gates, and this route is not one of them
 *
 * `Access.AtLeast(MEMBER)` only gets a caller to the handler. What they may
 * actually run is decided by `ConsolePolicy`: Gate 1 refuses `stop` and
 * `save-off` to everyone, then the tier gate applies the caller's tier clamped by
 * the server's own `spec.console.maxTier`.
 *
 * The route tier deliberately does **not** encode the command policy. A route
 * knows nothing about which command is in the body, and a route tier of
 * `superuser` would have made the console unusable for the tiers it exists to
 * serve.
 *
 * ## The body, and why it is not JSON
 *
 * `text/plain`, one command, no leading slash. Nothing in `:api` parses JSON, and
 * more importantly a command belongs in a body rather than a path or query: it
 * routinely carries a player name, and a query string is logged by every proxy in
 * the world. See `spec/08-origin-and-client.md` §2.
 */
internal class ConsoleRoutes(
    private val store: Store,
    private val console: ServerConsole,
    private val audit: ConsoleAudit,
    private val clock: Clock,
) {
    fun routes(): List<Route> =
        listOf(
            // MEMBER gets to the handler; ConsolePolicy decides the rest.
            Route("POST", CONSOLE, Access.AtLeast(Tier.MEMBER)) { request, _ ->
                HandlerResult.Send(run(request))
            },
            Route("GET", CONSOLE, Access.AtLeast(Tier.MEMBER)) { request, _ ->
                HandlerResult.Send(capability(request))
            },
        )

    private suspend fun run(request: Request): Response {
        val definition = paperServer(request)
        val principal = request.principal()
        val command = commandFrom(request)

        when (val decision = ConsolePolicy.screen(command, principal.tier, definition.spec.console)) {
            is ConsoleDecision.RefusedOutright -> {
                audit.record(principal, definition, command, Outcome.REFUSED_BY_INVARIANT, clock)
                throw ApiException(
                    ErrorCode.CONSOLE_COMMAND_REFUSED,
                    "`${decision.screening.verb}` is refused on every console, at every tier. " +
                        "Stopping a server goes through the drain: " +
                        "DELETE /api/v1/servers/${definition.metadata.name.value}",
                )
            }

            is ConsoleDecision.RefusedByTier -> {
                audit.record(principal, definition, command, Outcome.REFUSED_BY_TIER, clock)
                throw ApiException(
                    ErrorCode.FORBIDDEN,
                    "`${decision.verb}` needs the ${decision.required.wireValue} tier here; this request has " +
                        "${decision.effective.wireValue}",
                    requiredTier = decision.required.wireValue,
                )
            }

            is ConsoleDecision.Permitted -> {
                val output = dispatch(principal, definition, command)
                return Response.json(
                    200,
                    jsonObject {
                        put("server", definition.metadata.name.value)
                        put("command", command)
                        put("tier", decision.effective.wireValue)
                        put("executedAt", clock.instant())
                        // Verbatim, and it routinely carries player names. A client
                        // renders this escaped — it is untrusted text from whatever
                        // a player typed.
                        put("output", output)
                    },
                )
            }
        }
    }

    private suspend fun dispatch(
        principal: Principal,
        definition: PaperServerDefinition,
        command: String,
    ): String =
        try {
            console.run(definition, command).also {
                audit.record(principal, definition, command, Outcome.EXECUTED, clock)
            }
        } catch (unavailable: ConsoleUnavailable) {
            audit.record(principal, definition, command, Outcome.UNAVAILABLE, clock)
            throw ApiException(
                ErrorCode.CONSOLE_UNAVAILABLE,
                unavailable.message ?: "the server cannot answer a console command yet",
                headers = listOf("Retry-After" to "2"),
                cause = unavailable,
            )
        } catch (timedOut: ConsoleTimedOut) {
            audit.record(principal, definition, command, Outcome.TIMED_OUT, clock)
            // Deliberately not retryable, and the message says so: the command may
            // still be running, and there is nothing to reconcile a repeat against.
            throw ApiException(
                ErrorCode.CONSOLE_TIMEOUT,
                "the server did not answer in time. It may still be running the command — do not retry " +
                    "automatically",
                cause = timedOut,
            )
        }

    /** What this caller may run here, so a dashboard renders a console it can use. */
    private suspend fun capability(request: Request): Response {
        val definition = paperServer(request)
        val principal = request.principal()
        val available = ConsolePolicy.available(principal.tier, definition.spec.console)
        val effective = principal.tier.clampedTo(definition.spec.console.maxTier)
        return Response.json(
            200,
            jsonObject {
                put("server", definition.metadata.name.value)
                put("available", true)
                put("tier", effective.wireValue)
                // Null means bounded only by the invariant refusals, so there is no
                // finite list: a dashboard offers a free-text prompt rather than a
                // picker.
                put("unrestricted", available == null)
                put("commands", Json.strings(available.orEmpty().sorted()))
            },
        )
    }

    private suspend fun paperServer(request: Request): PaperServerDefinition {
        val name = Requests.name(request)
        val stored = store.getServer(name) ?: throw ApiException.notFound("no server named `${name.value}`")
        return stored.definition.definition as? PaperServerDefinition
            ?: throw ApiException(
                ErrorCode.CONSOLE_NOT_APPLICABLE,
                "`${name.value}` is not a PaperServer. A VelocityProxy has no RCON and never will; " +
                    "it is driven through its control plugin instead",
            )
    }

    private fun commandFrom(request: Request): String {
        val raw = request.bodyText().trim()
        if (raw.isEmpty()) throw ApiException.badRequest("send the command as the request body")
        // One request, one command, so a refusal and an audit record always refer
        // to exactly one thing.
        if (raw.any { it == '\n' || it == '\r' }) {
            throw ApiException.badRequest("send one command per request; this body has more than one line")
        }
        return raw
    }

    /** Outcomes an audit record can carry. Named here because the sink is not the only writer. */
    internal enum class Outcome {
        EXECUTED,
        REFUSED_BY_INVARIANT,
        REFUSED_BY_TIER,
        UNAVAILABLE,
        TIMED_OUT,
    }

    companion object {
        const val CONSOLE: String = "/api/v1/servers/{name}/console"

        private val LOG = LoggerFactory.getLogger(ConsoleRoutes::class.java)
    }
}

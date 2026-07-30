package mcorch.api.routes

import mcorch.api.ApiConfig
import mcorch.api.auth.OperatorAuth
import mcorch.api.auth.SessionRegistry
import mcorch.api.http.Access
import mcorch.api.http.ApiException
import mcorch.api.http.ErrorCode
import mcorch.api.http.HandlerResult
import mcorch.api.http.Request
import mcorch.api.http.Response
import mcorch.api.http.Route
import mcorch.api.json.Json
import mcorch.api.json.jsonObject
import org.slf4j.LoggerFactory

/**
 * Logging in, checking, and logging out.
 *
 * The session exchange exists because of one browser constraint: `EventSource`
 * cannot set headers, so the live stream can only authenticate with a cookie.
 * Once a cookie is in play the SPA may as well use it everywhere, which is also
 * the safer arrangement — an `HttpOnly` cookie is a credential that injected
 * script cannot read, whereas an operator token held in `localStorage` is one it
 * can post to anywhere.
 */
internal class AuthRoutes(
    private val auth: OperatorAuth,
    private val sessions: SessionRegistry,
    private val config: ApiConfig,
) {
    fun routes(): List<Route> =
        listOf(
            // PUBLIC in the router's sense only: this is where a credential is
            // *established*, so the generic authenticator cannot run first. The
            // handler does the check itself, and does nothing else until it passes.
            Route("POST", SESSION, Access.PUBLIC) { request, _ -> HandlerResult.Send(open(request)) },
            Route("GET", SESSION, Access.OPERATOR) { request, _ -> HandlerResult.Send(describe(request)) },
            Route("DELETE", SESSION, Access.OPERATOR) { request, _ -> HandlerResult.Send(close(request)) },
        )

    /**
     * Exchanges the operator token for a session.
     *
     * The token arrives in `Authorization: Bearer`, never in the body and never
     * in the query string: a query string is logged by every proxy in the world,
     * and a body is the sort of thing that ends up in a har file attached to a
     * bug report.
     */
    private fun open(request: Request): Response {
        val supplied = auth.bearerToken(request) ?: auth.reject("send the operator token in `Authorization: Bearer`")
        if (!auth.matchesOperatorToken(supplied)) auth.reject("the operator token is not correct")

        val issued = sessions.create()
        LOG.info("operator session opened expiresAt={} openSessions={}", issued.session.expiresAt, sessions.size())
        return Response.json(
            200,
            jsonObject {
                put("authenticated", true)
                put("method", "session")
                // Readable by script on purpose: it is not a credential on its own,
                // and the SPA has to echo it in X-CSRF-Token on every mutation.
                put("csrfToken", issued.session.csrfToken)
                put("expiresAt", issued.session.expiresAt)
            },
            listOf("Set-Cookie" to cookie(issued.id, config.sessionTtl.inWholeSeconds)),
        )
    }

    /** Who am I, and what CSRF token should I be sending. The SPA calls this on load. */
    private fun describe(request: Request): Response {
        val credential = auth.authenticate(request)
        return Response.json(
            200,
            jsonObject {
                put("authenticated", true)
                when (credential) {
                    OperatorAuth.Credential.OperatorToken -> {
                        put("method", "bearer")
                        put("csrfToken", Json.Null)
                        put("expiresAt", Json.Null)
                    }

                    is OperatorAuth.Credential.Session -> {
                        put("method", "session")
                        put("csrfToken", credential.session.csrfToken)
                        put("expiresAt", credential.session.expiresAt)
                    }
                }
            },
        )
    }

    /**
     * Ends the session.
     *
     * Mutating, so a cookie-authenticated logout needs the CSRF token like
     * everything else. That is not pedantry: a cross-site page that could log the
     * operator out at will is a nuisance attack, and the exemption would be one
     * more special case in the one place where special cases are expensive.
     */
    private fun close(request: Request): Response {
        val credential = auth.authenticate(request)
        if (credential is OperatorAuth.Credential.Session) {
            request.cookie(OperatorAuth.SESSION_COOKIE)?.let(sessions::revoke)
            LOG.info("operator session closed openSessions={}", sessions.size())
        } else {
            throw ApiException(
                ErrorCode.BAD_REQUEST,
                "there is no session to close: this request authenticated with the operator token",
            )
        }
        return Response.empty(204, listOf("Set-Cookie" to cookie("", 0)))
    }

    private fun cookie(
        value: String,
        maxAgeSeconds: Long,
    ): String =
        buildString {
            append(OperatorAuth.SESSION_COOKIE).append('=').append(value)
            append("; Path=/")
            append("; Max-Age=").append(maxAgeSeconds)
            // Not readable by script: an XSS in the dashboard cannot lift the
            // session out and replay it from somewhere else.
            append("; HttpOnly")
            append("; SameSite=").append(config.cookieSameSite.wireValue)
            if (config.cookieSecure) append("; Secure")
        }

    companion object {
        const val SESSION: String = "/api/v1/auth/session"

        private val LOG = LoggerFactory.getLogger(AuthRoutes::class.java)
    }
}

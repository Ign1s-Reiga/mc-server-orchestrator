package mcorch.api.http

/**
 * Cross-origin policy.
 *
 * The dashboard is a browser SPA, so this is part of the authentication story
 * rather than a convenience: the session lives in a cookie, and a cookie is
 * attached by the browser to any request the browser is willing to make. What
 * decides whether a *hostile* page can make that request is this class plus
 * `SameSite`, in that order.
 *
 * Three cases, and the middle one is the one people get wrong:
 *
 * - No `Origin` header. A non-browser client — curl, a script, the integration
 *   suite. Allowed, and no CORS headers are emitted, because there is nothing to
 *   tell.
 * - An `Origin` that matches the request's own `Host`. Same-origin: the normal
 *   deployment, where the SPA is served from the same place as the API or
 *   proxied onto it. Allowed with no CORS headers.
 * - Any other `Origin`. Allowed only if it was configured, and the response then
 *   names *that* origin — never `*`, which a browser refuses to combine with
 *   credentials anyway. Everything else is refused before the handler runs, so a
 *   cross-site page cannot reach a mutating endpoint even with a valid cookie.
 */
internal class Cors(
    private val allowedOrigins: Set<String>,
) {
    fun decide(
        origin: String?,
        host: String?,
    ): Decision =
        when {
            origin.isNullOrBlank() -> Decision.NoOrigin
            origin in allowedOrigins -> Decision.Allowed(origin)
            host != null && sameOrigin(origin, host) -> Decision.SameOrigin
            else -> Decision.Refused
        }

    /** Headers to add to a real (non-preflight) response. */
    fun headersFor(decision: Decision): List<Pair<String, String>> =
        when (decision) {
            is Decision.Allowed -> {
                listOf(
                    "Access-Control-Allow-Origin" to decision.origin,
                    "Access-Control-Allow-Credentials" to "true",
                    "Vary" to "Origin",
                )
            }

            Decision.NoOrigin, Decision.SameOrigin, Decision.Refused -> {
                emptyList()
            }
        }

    /** The preflight answer for an allowed cross-origin request. */
    fun preflightHeaders(decision: Decision): List<Pair<String, String>> =
        headersFor(decision) +
            listOf(
                "Access-Control-Allow-Methods" to "GET, POST, PUT, DELETE, OPTIONS",
                "Access-Control-Allow-Headers" to
                    listOf(
                        HeaderNames.AUTHORIZATION,
                        HeaderNames.CONTENT_TYPE,
                        HeaderNames.IF_MATCH,
                        HeaderNames.CSRF,
                        HeaderNames.LAST_EVENT_ID,
                    ).joinToString(", "),
                "Access-Control-Expose-Headers" to "${HeaderNames.ETAG}, Location, Retry-After",
                "Access-Control-Max-Age" to "600",
            )

    private fun sameOrigin(
        origin: String,
        host: String,
    ): Boolean {
        val authority = origin.substringAfter("://", missingDelimiterValue = "")
        return authority.isNotEmpty() && authority.equals(host, ignoreCase = true)
    }

    internal sealed interface Decision {
        /** Not a browser request, or one the browser did not label. */
        data object NoOrigin : Decision

        data object SameOrigin : Decision

        data class Allowed(
            val origin: String,
        ) : Decision

        data object Refused : Decision
    }
}
